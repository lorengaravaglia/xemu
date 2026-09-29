package com.boxxy.emulation

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Quick resume: an automatic, hidden save state per game, so a game can be
 * picked up where it was left instead of booting through the kernel, the
 * game's own startup and its intros (~21 s for Halo).
 *
 * The state lives in the HDD image like the eight slots, named
 * "<gameId>_resume" -- the slot picker only lists "<gameId>_slot_N", so it
 * stays out of sight.  Whether one exists is also recorded as a marker file,
 * because the library runs in a different process from the emulator and has
 * no emulator to ask; a file is visible to both, where SharedPreferences are
 * cached per process.
 *
 * The marker can outlive its state (the HDD replaced, say).  Resuming then
 * fails, which the emulator reports and handles by clearing the marker and
 * carrying on with the normal boot it has already started.
 */
object QuickResume {
    /** Stable per-game key, shared by the library and the emulator. */
    fun gameIdFor(uriStr: String): String {
        if (uriStr.isEmpty()) return "dashboard"
        val basename = Uri.parse(uriStr).lastPathSegment
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?: return "game"
        return basename
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(24)
            .ifEmpty { "game" }
    }

    fun snapshotName(gameId: String) = "${gameId}_resume"

    private fun marker(context: Context, gameId: String) =
        File(File(context.filesDir, "resume"), gameId)

    /** When the resume point was saved, or null if there is none. */
    fun savedAt(context: Context, gameId: String): Long? =
        marker(context, gameId).takeIf { it.exists() }?.lastModified()

    fun markSaved(context: Context, gameId: String) {
        val f = marker(context, gameId)
        f.parentFile?.mkdirs()
        f.writeText(System.currentTimeMillis().toString())
    }

    fun clear(context: Context, gameId: String) {
        marker(context, gameId).delete()
    }
}
