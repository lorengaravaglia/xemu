package com.boxxy.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches and caches game box art from online sources.
 *
 * Sources (in priority order):
 *   1. SteamGridDB — requires user API key from https://www.steamgriddb.com/profile/preferences
 *   2. ScreenScraper (TODO) — requires user account at https://www.screenscraper.fr
 *
 * Art is cached locally in filesDir/artwork/<key>.jpg. Once cached, no network
 * request is made on subsequent launches.
 */
object ArtworkRepository {

    private const val STEAMGRIDDB_BASE = "https://www.steamgriddb.com/api/v2"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000

    /** Longest edge kept for user-chosen art; covers never need more. */
    private const val MAX_ART_EDGE = 1024

    fun artworkDir(context: Context): File =
        File(context.filesDir, "artwork").also { it.mkdirs() }

    /** Filesystem-safe key derived from the game's display name. */
    fun gameKey(displayName: String): String =
        displayName.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(48)
            .ifEmpty { "unknown" }

    fun cachedFile(context: Context, displayName: String): File =
        File(artworkDir(context), "${gameKey(displayName)}.jpg")

    /**
     * Cache location for art extracted from the disc itself, keyed by title ID
     * rather than name — the ID is stable across renames and regional variants.
     */
    fun discArtFile(context: Context, titleId: Int): File =
        File(artworkDir(context), "disc_%08x.png".format(titleId))

    // ── User-chosen artwork ───────────────────────────────────────────────────
    //
    // Stored as a copy rather than a reference. A picked image arrives as a
    // content:// URI whose permission grant is not ours to keep, and the file
    // may sit on removable storage — copying makes the choice durable.
    //
    // The filename carries a timestamp because Coil caches by URI: overwriting
    // a fixed name would keep showing the previous image.

    private fun customPrefix(titleId: Int) = "custom_%08x_".format(titleId)

    /** Most recent user-chosen art for [titleId], or null if none. */
    fun customArtFor(context: Context, titleId: Int): File? =
        artworkDir(context)
            .listFiles { f -> f.name.startsWith(customPrefix(titleId)) }
            ?.maxByOrNull { it.lastModified() }

    fun clearCustomArt(context: Context, titleId: Int) {
        artworkDir(context)
            .listFiles { f -> f.name.startsWith(customPrefix(titleId)) }
            ?.forEach { it.delete() }
    }

    /**
     * Copies the image at [src] into the artwork cache for [titleId], scaling
     * it down so a large photo does not sit in app storage at full size.
     * Returns the stored file, or null if the image could not be read.
     */
    fun importCustomArt(context: Context, src: Uri, titleId: Int): File? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(src)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_ART_EDGE) sample *= 2

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = context.contentResolver.openInputStream(src)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            clearCustomArt(context, titleId)
            saveBitmap(bmp, File(artworkDir(context),
                customPrefix(titleId) + System.currentTimeMillis() + ".png"))
        } catch (_: Exception) {
            null
        }
    }

    /** Writes [bmp] to [dest] as PNG. Returns null and cleans up on failure. */
    fun saveBitmap(bmp: Bitmap, dest: File): File? = try {
        dest.parentFile?.mkdirs()
        dest.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        dest
    } catch (_: Exception) {
        dest.delete()
        null
    }

    /**
     * Returns a local cached [File] containing box art for [displayName], fetching
     * from the network if necessary. Returns null if no art could be found or all
     * configured sources are unavailable.
     */
    suspend fun fetchArt(
        context: Context,
        displayName: String,
        steamGridDbKey: String,
    ): File? = withContext(Dispatchers.IO) {
        val dest = cachedFile(context, displayName)
        if (dest.exists()) return@withContext dest

        if (steamGridDbKey.isNotEmpty()) {
            fetchFromSteamGridDB(displayName, steamGridDbKey)?.let { url ->
                downloadTo(url, dest)?.let { return@withContext it }
            }
        }

        // TODO: ScreenScraper fallback (requires devid registration + user credentials)

        null
    }

    // ── SteamGridDB ───────────────────────────────────────────────────────────

    private fun fetchFromSteamGridDB(gameName: String, apiKey: String): String? {
        // Step 1: search for the game ID
        val encoded = Uri.encode(gameName)
        val searchJson = httpGet("$STEAMGRIDDB_BASE/search/autocomplete/$encoded", apiKey)
            ?: return null
        val gameId = JSONObject(searchJson)
            .optJSONArray("data")
            ?.optJSONObject(0)
            ?.optInt("id", -1)
            ?.takeIf { it > 0 }
            ?: return null

        // Step 2: fetch portrait grid art (600×900 preferred)
        val gridsJson = httpGet(
            "$STEAMGRIDDB_BASE/grids/game/$gameId?dimensions=600x900&mime_types=jpeg,png",
            apiKey,
        ) ?: return null

        return JSONObject(gridsJson)
            .optJSONArray("data")
            ?.optJSONObject(0)
            ?.optString("url")
            ?.takeIf { it.isNotEmpty() }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private fun httpGet(url: String, bearerToken: String?): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "xemu-android/1.0")
            bearerToken?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            if (conn.responseCode != 200) return null
            conn.inputStream.bufferedReader().readText()
        } catch (_: Exception) { null }
    }

    private fun downloadTo(url: String, dest: File): File? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "xemu-android/1.0")
            if (conn.responseCode != 200) return null
            dest.parentFile?.mkdirs()
            conn.inputStream.use { it.copyTo(dest.outputStream()) }
            dest
        } catch (_: Exception) {
            dest.delete()
            null
        }
    }
}
