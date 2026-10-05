package com.boxxy.emulation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.SurfaceView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Save-slot thumbnails and timestamps (OPEN-WORK A5).
 *
 * A slot's picture is captured from the game's SurfaceView just before the
 * state is saved and written only if the save succeeds, as
 * files/slot_thumbs/<snapshot name>.jpg.  The file's modification time is the
 * slot's save time, so no other metadata is kept.  States saved before this
 * existed have no thumbnail and show as plain "Saved" until next overwritten.
 */
object SlotThumbs {
    private const val WIDTH = 320
    private const val HEIGHT = 180

    private fun dir(context: Context) = File(context.filesDir, "slot_thumbs")

    fun file(context: Context, snapshotName: String) =
        File(dir(context), "$snapshotName.jpg")

    /** When [snapshotName] was saved, if a thumbnail records it. */
    fun savedAt(context: Context, snapshotName: String): Long? =
        file(context, snapshotName).takeIf { it.exists() }?.lastModified()

    /**
     * The game image as a thumbnail, or null.  Blocks for at most 500 ms; call
     * it from a worker thread (the save paths all run on one).  PixelCopy
     * needs a Looper for its callback, hence the short-lived HandlerThread.
     */
    fun grab(surfaceView: SurfaceView): Bitmap? {
        val w = surfaceView.width
        val h = surfaceView.height
        if (w == 0 || h == 0 || !surfaceView.holder.surface.isValid) return null
        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val thread = HandlerThread("slot-thumb").apply { start() }
        val done = CountDownLatch(1)
        var ok = false
        try {
            PixelCopy.request(surfaceView, full, { result ->
                ok = result == PixelCopy.SUCCESS
                done.countDown()
            }, Handler(thread.looper))
            done.await(500, TimeUnit.MILLISECONDS)
        } catch (e: IllegalArgumentException) {
            ok = false
        } finally {
            thread.quitSafely()
        }
        if (!ok) {
            full.recycle()
            return null
        }
        return Bitmap.createScaledBitmap(full, WIDTH, HEIGHT, true).also { full.recycle() }
    }

    /** Record [thumb] for [snapshotName]; call only after the save succeeded. */
    fun write(context: Context, snapshotName: String, thumb: Bitmap?) {
        val f = file(context, snapshotName)
        dir(context).mkdirs()
        if (thumb == null) {
            /* Keep the timestamp right even without a picture: an old
             * thumbnail must not keep showing a scene this slot no longer
             * holds. */
            f.delete()
            return
        }
        try {
            f.outputStream().use { thumb.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        } catch (_: Exception) {
            f.delete()
        }
    }

    /** Decoded thumbnail for display, or null. */
    fun load(file: File?): Bitmap? =
        file?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
}
