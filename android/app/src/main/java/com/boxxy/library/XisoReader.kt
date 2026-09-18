package com.boxxy.library

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Reads game metadata directly out of an Xbox disc image.
 *
 * This exists because the library previously identified games by filename, which
 * is both unreliable for lookups and unable to produce any art on its own. The
 * disc already carries an authoritative title, a stable title ID, and a title
 * image, so nothing external is needed.
 *
 * Layout, confirmed against four retail discs (see OPEN-WORK.md §7):
 *
 *   XISO      2048-byte sectors. A volume descriptor sits 32 sectors into the
 *             game partition, marked by [MEDIA_MAGIC] at both ends. Retail rips
 *             may place that partition at one of several [BASE_OFFSETS], so the
 *             base is probed rather than assumed.
 *   Directory A binary tree of variable-length entries; child links are 16-bit
 *             offsets in 4-byte units, relative to the start of the table.
 *   XBE       `m_certificate_addr` locates the certificate, which holds
 *             `m_titleid` and `m_title_name[40]` (UTF-16LE). Addresses in the
 *             XBE are virtual, so `m_base` is subtracted to get a file offset.
 *   $$XTIMAGE An XPR0-wrapped texture, in practice always 128x128 DXT1.
 *
 * All reads are small and seek-driven — a scan touches a few KB per disc, not
 * the multi-GB image.
 */
object XisoReader {

    private const val TAG = "xemu-xiso"

    private const val SECTOR = 2048L
    private val MEDIA_MAGIC = "MICROSOFT*XBOX*MEDIA".toByteArray(Charsets.US_ASCII)

    /**
     * Candidate offsets of the game partition: a plain XISO starts at zero,
     * while XGD rips keep the video partition ahead of it.
     */
    private val BASE_OFFSETS = longArrayOf(0L, 0x2080000L, 0x4100000L, 0xFD90000L, 0x18300000L)

    /** Xbox D3DFMT_DXT1. The only compressed format seen on retail title images. */
    private const val D3DFMT_DXT1 = 0x0C

    /** Guards against a corrupt directory table walking forever. */
    private const val MAX_DIR_ENTRIES = 4096

    data class DiscInfo(
        /** Title from the XBE certificate, e.g. "Halo" — not the filename. */
        val title: String,
        /** Stable per-title identifier; the right cache key for artwork. */
        val titleId: Int,
        /** Decoded `$$XTIMAGE`, or null if absent or in an unhandled format. */
        val titleImage: Bitmap?,
    )

    /**
     * Parses [uri] as an Xbox disc image. Returns null if it is not one, or if
     * the structures needed are missing or malformed — callers fall back to
     * filename-derived information.
     */
    fun read(context: Context, uri: Uri): DiscInfo? {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { stream ->
                    parse(stream.channel)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "read failed for $uri: $e")
            null
        }
    }

    private fun parse(ch: FileChannel): DiscInfo? {
        val size = ch.size()
        val base = BASE_OFFSETS.firstOrNull { b ->
            b + 33 * SECTOR <= size && readAt(ch, b + 32 * SECTOR, MEDIA_MAGIC.size)
                ?.array()?.contentEquals(MEDIA_MAGIC) == true
        } ?: return null

        // Volume descriptor: magic[20], then rootDirSector u32, rootDirSize u32.
        val vd = readAt(ch, base + 32 * SECTOR + MEDIA_MAGIC.size, 8) ?: return null
        val dirSector = vd.getInt(0).toUInt().toLong()
        val dirSize = vd.getInt(4)
        if (dirSize <= 0 || dirSize > 1 shl 20) return null

        val xbe = findEntry(ch, base, dirSector, dirSize, "default.xbe") ?: return null
        return readXbe(ch, base + xbe.first * SECTOR, xbe.second)
    }

    /** Walks the directory tree, returning (startSector, size) for [want]. */
    private fun findEntry(
        ch: FileChannel,
        base: Long,
        dirSector: Long,
        dirSize: Int,
        want: String,
    ): Pair<Long, Int>? {
        val table = readAt(ch, base + dirSector * SECTOR, dirSize) ?: return null
        val stack = ArrayDeque<Int>()
        stack.addLast(0)
        var visited = 0

        while (stack.isNotEmpty() && visited++ < MAX_DIR_ENTRIES) {
            val off = stack.removeLast()
            if (off < 0 || off + 14 > dirSize) continue

            val left = table.getShort(off).toInt() and 0xFFFF
            val right = table.getShort(off + 2).toInt() and 0xFFFF
            // 0xFFFF in both links marks unused padding rather than a node.
            if (left == 0xFFFF && right == 0xFFFF) continue

            val startSector = table.getInt(off + 4).toUInt().toLong()
            val fileSize = table.getInt(off + 8)
            val nameLen = table.get(off + 13).toInt() and 0xFF

            if (off + 14 + nameLen <= dirSize && nameLen > 0) {
                val name = ByteArray(nameLen).also { b ->
                    for (i in 0 until nameLen) b[i] = table.get(off + 14 + i)
                }.toString(Charsets.ISO_8859_1)
                if (name.equals(want, ignoreCase = true)) return startSector to fileSize
            }
            if (left != 0) stack.addLast(left * 4)
            if (right != 0) stack.addLast(right * 4)
        }
        return null
    }

    private fun readXbe(ch: FileChannel, xbeOffset: Long, xbeSize: Int): DiscInfo? {
        val hdr = readAt(ch, xbeOffset, 0x184) ?: return null
        if (hdr.getInt(0) != 0x48454258) return null  // 'XBEH' little-endian

        val imageBase = hdr.getInt(0x104).toUInt().toLong()
        val certAddr = hdr.getInt(0x118).toUInt().toLong()
        val numSections = hdr.getInt(0x11C)
        val sectionHdrs = hdr.getInt(0x120).toUInt().toLong()

        val certOff = certAddr - imageBase
        if (certOff < 0 || certOff + 0x5C > xbeSize) return null
        val cert = readAt(ch, xbeOffset + certOff, 0x5C) ?: return null

        val titleId = cert.getInt(0x08)
        val nameBytes = ByteArray(80).also { b ->
            for (i in 0 until 80) b[i] = cert.get(0x0C + i)
        }
        val title = nameBytes.toString(Charsets.UTF_16LE).substringBefore('\u0000').trim()

        val image = readTitleImage(ch, xbeOffset, xbeSize, imageBase, numSections, sectionHdrs)
        if (title.isEmpty() && titleId == 0) return null
        return DiscInfo(title = title, titleId = titleId, titleImage = image)
    }

    /** Locates `$$XTIMAGE` among the section headers and decodes it. */
    private fun readTitleImage(
        ch: FileChannel,
        xbeOffset: Long,
        xbeSize: Int,
        imageBase: Long,
        numSections: Int,
        sectionHdrs: Long,
    ): Bitmap? {
        if (numSections <= 0 || numSections > 256) return null
        val hdrOff = sectionHdrs - imageBase
        if (hdrOff < 0 || hdrOff + numSections * 56L > xbeSize) return null
        val hdrs = readAt(ch, xbeOffset + hdrOff, numSections * 56) ?: return null

        for (i in 0 until numSections) {
            val b = i * 56
            val rawAddr = hdrs.getInt(b + 0x0C).toUInt().toLong()
            val rawSize = hdrs.getInt(b + 0x10)
            val nameAddr = hdrs.getInt(b + 0x14).toUInt().toLong()
            if (rawSize <= 0 || rawSize > 1 shl 22) continue

            val nameOff = nameAddr - imageBase
            if (nameOff < 0 || nameOff + 16 > xbeSize) continue
            val nb = readAt(ch, xbeOffset + nameOff, 16) ?: continue
            val name = ByteArray(16).also { a ->
                for (j in 0 until 16) a[j] = nb.get(j)
            }.toString(Charsets.ISO_8859_1).substringBefore('\u0000')

            if (name == "\$\$XTIMAGE") {
                val blob = readAt(ch, xbeOffset + rawAddr, rawSize) ?: return null
                return decodeXpr(blob, rawSize)
            }
        }
        return null
    }

    /**
     * Decodes an XPR0 blob. Header is magic, total size, header size, then a
     * D3D descriptor whose format dword at +0x18 packs the pixel format in bits
     * 8-15 and log2 dimensions in bits 20-23 and 24-27.
     */
    private fun decodeXpr(buf: ByteBuffer, size: Int): Bitmap? {
        if (size < 0x20 || buf.getInt(0) != 0x30525058) return null  // 'XPR0'
        val headerSize = buf.getInt(8)
        if (headerSize <= 0 || headerSize >= size) return null

        val fmtWord = buf.getInt(0x18)
        val format = (fmtWord ushr 8) and 0xFF
        val width = 1 shl ((fmtWord ushr 20) and 0xF)
        val height = 1 shl ((fmtWord ushr 24) and 0xF)
        if (width !in 1..1024 || height !in 1..1024) return null

        if (format != D3DFMT_DXT1) {
            // Worth knowing about: every disc checked so far has been DXT1.
            Log.i(TAG, "unhandled \$\$XTIMAGE format 0x%02X (%dx%d)".format(format, width, height))
            return null
        }
        val needed = width * height / 2
        if (headerSize + needed > size) return null

        val data = ByteArray(needed).also { a ->
            for (i in 0 until needed) a[i] = buf.get(headerSize + i)
        }
        return decodeDxt1(data, width, height)
    }

    /**
     * DXT1 -> ARGB_8888. Compressed textures are stored in linear block order on
     * Xbox (unlike uncompressed surfaces, which are swizzled), so blocks map
     * straight to 4x4 tiles with no address twiddling.
     */
    private fun decodeDxt1(data: ByteArray, width: Int, height: Int): Bitmap {
        val px = IntArray(width * height)
        val palette = IntArray(4)
        var i = 0

        var by = 0
        while (by < height) {
            var bx = 0
            while (bx < width) {
                val c0 = (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8)
                val c1 = (data[i + 2].toInt() and 0xFF) or ((data[i + 3].toInt() and 0xFF) shl 8)
                var bits = 0
                for (k in 0 until 4) bits = bits or ((data[i + 4 + k].toInt() and 0xFF) shl (8 * k))
                i += 8

                palette[0] = rgb565(c0)
                palette[1] = rgb565(c1)
                if (c0 > c1) {
                    palette[2] = lerp(palette[0], palette[1], 2, 1)
                    palette[3] = lerp(palette[0], palette[1], 1, 2)
                } else {
                    palette[2] = lerp(palette[0], palette[1], 1, 1)
                    palette[3] = 0xFF000000.toInt()
                }

                for (y in 0 until 4) {
                    val py = by + y
                    if (py >= height) break
                    for (x in 0 until 4) {
                        val pxx = bx + x
                        if (pxx >= width) continue
                        px[py * width + pxx] = palette[(bits ushr (2 * (4 * y + x))) and 3]
                    }
                }
                bx += 4
            }
            by += 4
        }
        return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun rgb565(c: Int): Int {
        val r = ((c ushr 11) and 0x1F) * 255 / 31
        val g = ((c ushr 5) and 0x3F) * 255 / 63
        val b = (c and 0x1F) * 255 / 31
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun lerp(a: Int, b: Int, wa: Int, wb: Int): Int {
        val t = wa + wb
        fun mix(shift: Int): Int =
            ((((a ushr shift) and 0xFF) * wa + ((b ushr shift) and 0xFF) * wb) / t) and 0xFF
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private fun readAt(ch: FileChannel, pos: Long, len: Int): ByteBuffer? {
        if (pos < 0 || len <= 0) return null
        val buf = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
        var read = 0
        while (read < len) {
            val n = ch.read(buf, pos + read)
            if (n <= 0) return null
            read += n
        }
        buf.rewind()
        return buf
    }
}
