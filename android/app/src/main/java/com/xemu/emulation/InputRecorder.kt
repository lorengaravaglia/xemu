package com.xemu.emulation

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.xemu.NativeInterface
import java.io.File

/**
 * Records and plays back the Xbox controller input stream (button/axis events),
 * regardless of source (physical gamepad or on-screen touch overlay). Both call
 * sites route through here instead of calling NativeInterface directly.
 *
 * Not bit-exact deterministic (MTTCG scheduling isn't) — eliminates human
 * play-pattern variance across repeated profiling runs while staying
 * representative of real (MTTCG) performance, unlike QEMU-level -icount replay.
 */
object InputRecorder {
    private const val TAG = "xemu-inputrec"
    private const val FORMAT_VERSION = 1

    const val ACTION_PLAY          = "com.xemu.action.PLAY_RECORDING"
    const val ACTION_STOP_PLAYBACK = "com.xemu.action.STOP_PLAYBACK"
    const val ACTION_BENCHMARK     = "com.xemu.action.BENCHMARK"
    const val ACTION_LOAD_STATE    = "com.xemu.action.LOAD_STATE"
    const val EXTRA_SLOT           = "slot"
    const val EXTRA_FRAMES         = "frames"
    const val EXTRA_NAME           = "recording_name"

    private sealed class Event(val tMs: Long) {
        class Down(tMs: Long, val mask: Int) : Event(tMs)
        class Up(tMs: Long, val mask: Int) : Event(tMs)
        class Axis(tMs: Long, val axis: Int, val value: Int) : Event(tMs)
    }

    // Recording state — touched only from the main thread (all call sites are main-thread).
    private var recording = false
    private var recordStartUptime = 0L
    private var recordGameId = ""
    private val buffer = mutableListOf<Event>()

    // Playback state.
    @Volatile private var playing = false
    private var playbackThread: HandlerThread? = null
    private var playbackHandler: Handler? = null

    val isRecording: Boolean get() = recording
    val isPlaying: Boolean get() = playing

    // ── Pass-through call sites — replace all direct NativeInterface.send* calls ──

    fun sendButtonDown(mask: Int) {
        if (recording) buffer.add(Event.Down(elapsed(), mask))
        NativeInterface.sendButtonDown(mask)
    }

    fun sendButtonUp(mask: Int) {
        if (recording) buffer.add(Event.Up(elapsed(), mask))
        NativeInterface.sendButtonUp(mask)
    }

    fun sendAxis(axis: Int, value: Int) {
        if (recording) buffer.add(Event.Axis(elapsed(), axis, value))
        NativeInterface.sendAxis(axis, value)
    }

    private fun elapsed() = SystemClock.uptimeMillis() - recordStartUptime

    // ── Recording control (menu-driven) ─────────────────────────────────────

    fun startRecording(gameId: String): Boolean {
        if (recording) { Log.w(TAG, "RECORD_REJECTED reason=already_recording"); return false }
        if (playing)   { Log.w(TAG, "RECORD_REJECTED reason=playback_in_progress"); return false }
        buffer.clear()
        recordGameId = gameId
        recordStartUptime = SystemClock.uptimeMillis()
        recording = true
        Log.i(TAG, "RECORD_START game=$gameId")
        return true
    }

    /** Stops recording and writes the file. Returns the saved File, or null if not recording. */
    fun stopRecording(filesDir: File, rawName: String): File? {
        if (!recording) return null
        recording = false
        val name = sanitizeName(rawName)
        val file = File(recordingsDir(filesDir, recordGameId), "$name.rec")
        file.bufferedWriter().use { w ->
            w.write("# xemu-input-recording v$FORMAT_VERSION\n")
            w.write("# game=$recordGameId\n")
            for (e in buffer) {
                when (e) {
                    is Event.Down -> w.write("${e.tMs},DOWN,${e.mask}\n")
                    is Event.Up   -> w.write("${e.tMs},UP,${e.mask}\n")
                    is Event.Axis -> w.write("${e.tMs},AXIS,${e.axis},${e.value}\n")
                }
            }
        }
        val duration = buffer.maxOfOrNull { it.tMs } ?: 0
        Log.i(TAG, "RECORD_STOP file=${file.absolutePath} events=${buffer.size} durationMs=$duration")
        buffer.clear()
        return file
    }

    fun cancelRecording() { recording = false; buffer.clear() }

    fun listRecordings(filesDir: File, gameId: String): List<String> =
        recordingsDir(filesDir, gameId)
            .listFiles { f -> f.isFile && f.name.endsWith(".rec") }
            ?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()

    // ── Playback control (broadcast- or menu-driven) ────────────────────────

    /**
     * Plays back on a dedicated HandlerThread using absolute-time-scheduled
     * callbacks (Handler.postAtTime against one startUptime), so per-event
     * scheduling error never accumulates across a long recording.
     */
    fun startPlayback(filesDir: File, gameId: String, rawName: String): Boolean {
        if (playing)   { Log.w(TAG, "PLAYBACK_REJECTED reason=already_playing"); return false }
        if (recording) { Log.w(TAG, "PLAYBACK_REJECTED reason=recording_in_progress"); return false }
        val name = sanitizeName(rawName)
        val file = File(recordingsDir(filesDir, gameId), "$name.rec")
        if (!file.exists()) { Log.w(TAG, "PLAYBACK_REJECTED reason=not_found name=$name"); return false }

        val events = parseRecording(file)
        if (events.isEmpty()) { Log.w(TAG, "PLAYBACK_REJECTED reason=empty name=$name"); return false }

        val ht = HandlerThread("InputPlayback").apply { start() }
        val handler = Handler(ht.looper)
        playbackThread = ht
        playbackHandler = handler
        playing = true

        val durationMs = events.maxOf { it.tMs }
        Log.i(TAG, "PLAYBACK_START name=$name events=${events.size} durationMs=$durationMs")
        NativeInterface.frameprofMark(true)

        val startUptime = SystemClock.uptimeMillis()
        var idx = 0
        fun scheduleNext() {
            if (!playing || idx >= events.size) {
                if (playing) {
                    playing = false
                    NativeInterface.frameprofMark(false)
                    Log.i(TAG, "PLAYBACK_COMPLETE name=$name")
                }
                ht.quitSafely()
                return
            }
            val e = events[idx++]
            handler.postAtTime({
                when (e) {
                    is Event.Down -> NativeInterface.sendButtonDown(e.mask)
                    is Event.Up   -> NativeInterface.sendButtonUp(e.mask)
                    is Event.Axis -> NativeInterface.sendAxis(e.axis, e.value)
                }
                scheduleNext()
            }, startUptime + e.tMs)
        }
        scheduleNext()
        return true
    }

    fun cancelPlayback() {
        if (!playing) return
        playing = false
        NativeInterface.frameprofMark(false)
        playbackHandler?.removeCallbacksAndMessages(null)
        playbackThread?.quitSafely()
        Log.i(TAG, "PLAYBACK_CANCELLED")
    }

    // ── File format I/O ───────────────────────────────────────────────────

    private fun recordingsDir(filesDir: File, gameId: String): File =
        File(File(filesDir, "recordings"), gameId).also { it.mkdirs() }

    private fun sanitizeName(raw: String): String =
        raw.lowercase().replace(Regex("[^a-z0-9_-]+"), "_").trim('_').take(40).ifEmpty { "recording" }

    private fun parseRecording(file: File): List<Event> {
        val list = mutableListOf<Event>()
        file.forEachLine { line ->
            if (line.isBlank() || line.startsWith("#")) return@forEachLine
            val p = line.trim().split(",")
            try {
                val t = p[0].toLong()
                when (p[1]) {
                    "DOWN" -> list.add(Event.Down(t, p[2].toInt()))
                    "UP"   -> list.add(Event.Up(t, p[2].toInt()))
                    "AXIS" -> list.add(Event.Axis(t, p[2].toInt(), p[3].toInt()))
                }
            } catch (e: Exception) {
                Log.w(TAG, "parseRecording: skipping malformed line: $line")
            }
        }
        return list.sortedBy { it.tMs }
    }
}
