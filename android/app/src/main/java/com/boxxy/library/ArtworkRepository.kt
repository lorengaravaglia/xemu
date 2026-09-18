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
     * Why a lookup produced no art. Previously every one of these returned a
     * bare null, so a missing key, a rejected key, an unmatched title and a
     * dropped connection were indistinguishable — and all four looked like
     * the feature simply not working.
     */
    sealed interface ArtOutcome {
        data class Found(val file: File) : ArtOutcome
        /** No key configured; the only source we have needs one. */
        data object NoKey : ArtOutcome
        /** The service rejected the key (401/403). */
        data object KeyRejected : ArtOutcome
        /** Key accepted, but the title matched nothing. */
        data object NoMatch : ArtOutcome
        /** Could not reach the service at all. */
        data object Unreachable : ArtOutcome
    }

    /**
     * Returns box art for [displayName], fetching it if it is not already
     * cached, and saying why when it cannot.
     */
    suspend fun fetchArt(
        context: Context,
        displayName: String,
        steamGridDbKey: String,
    ): ArtOutcome = withContext(Dispatchers.IO) {
        val dest = cachedFile(context, displayName)
        if (dest.exists()) return@withContext ArtOutcome.Found(dest)
        if (steamGridDbKey.isEmpty()) return@withContext ArtOutcome.NoKey

        when (val found = fetchFromSteamGridDB(displayName, steamGridDbKey)) {
            is Lookup.Url ->
                downloadTo(found.url, dest)
                    ?.let { ArtOutcome.Found(it) }
                    ?: ArtOutcome.Unreachable
            Lookup.Rejected -> ArtOutcome.KeyRejected
            Lookup.NoMatch -> ArtOutcome.NoMatch
            Lookup.Unreachable -> ArtOutcome.Unreachable
        }

        // TODO: ScreenScraper fallback (requires devid registration + user credentials)
    }

    /** Checks a key without changing any stored art. */
    suspend fun validateKey(key: String): ArtOutcome = withContext(Dispatchers.IO) {
        if (key.isEmpty()) return@withContext ArtOutcome.NoKey
        when (val r = httpGet("$STEAMGRIDDB_BASE/search/autocomplete/halo", key)) {
            is Response.Ok -> ArtOutcome.NoMatch          // reachable and accepted
            Response.Rejected -> ArtOutcome.KeyRejected
            Response.Failed -> ArtOutcome.Unreachable
        }
    }

    /**
     * Clears only art downloaded from the network. User-chosen images and art
     * extracted from discs live in the same directory and are deliberately
     * preserved — wiping the directory wholesale would silently destroy a
     * choice the user made by hand.
     */
    fun clearNetworkArt(context: Context) {
        artworkDir(context).listFiles()?.forEach { f ->
            if (!f.name.startsWith("custom_") && !f.name.startsWith("disc_")) f.delete()
        }
    }

    // ── SteamGridDB ───────────────────────────────────────────────────────────

    private sealed interface Lookup {
        data class Url(val url: String) : Lookup
        data object Rejected : Lookup
        data object NoMatch : Lookup
        data object Unreachable : Lookup
    }

    private fun fetchFromSteamGridDB(gameName: String, apiKey: String): Lookup {
        // Step 1: search for the game ID
        val encoded = Uri.encode(gameName)
        val searchJson = when (val r = httpGet("$STEAMGRIDDB_BASE/search/autocomplete/$encoded", apiKey)) {
            is Response.Ok -> r.body
            Response.Rejected -> return Lookup.Rejected
            Response.Failed -> return Lookup.Unreachable
        }
        val gameId = JSONObject(searchJson)
            .optJSONArray("data")
            ?.optJSONObject(0)
            ?.optInt("id", -1)
            ?.takeIf { it > 0 }
            ?: return Lookup.NoMatch

        // Step 2: fetch portrait grid art (600×900 preferred)
        val gridsJson = when (val r = httpGet(
            "$STEAMGRIDDB_BASE/grids/game/$gameId?dimensions=600x900&mime_types=jpeg,png",
            apiKey,
        )) {
            is Response.Ok -> r.body
            Response.Rejected -> return Lookup.Rejected
            Response.Failed -> return Lookup.Unreachable
        }

        val url = JSONObject(gridsJson)
            .optJSONArray("data")
            ?.optJSONObject(0)
            ?.optString("url")
            ?.takeIf { it.isNotEmpty() }
        return if (url != null) Lookup.Url(url) else Lookup.NoMatch
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private sealed interface Response {
        data class Ok(val body: String) : Response
        /** Authentication failed — the key is wrong, not the query. */
        data object Rejected : Response
        data object Failed : Response
    }

    private fun httpGet(url: String, bearerToken: String?): Response {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "boxxy-android/1.0")
            bearerToken?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            when (conn.responseCode) {
                200 -> Response.Ok(conn.inputStream.bufferedReader().readText())
                401, 403 -> Response.Rejected
                else -> Response.Failed
            }
        } catch (_: Exception) { Response.Failed }
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
