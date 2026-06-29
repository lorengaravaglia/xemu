package com.xemu.library

import android.content.Context
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
