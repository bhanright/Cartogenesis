package com.cartogenesis.cartography

/**
 * A PNG encoder for the two pictures no image toolkit on either front end will write.
 *
 * The application already has two perfectly good encoders — Skia on both hosts, `ImageIO` on the
 * desktop — and neither is any use for the data exports. Skia has one pixel format, eight bits a
 * channel, so a sixteen-bit heightmap through it is a heightmap thrown away; a browser canvas is
 * eight-bit RGBA by construction and cannot be asked for anything else. Neither will write an
 * indexed image at all, and an indexed image is the whole point of a layer export: one byte per
 * cell that *is* the biome, rather than a colour somebody has to match back to a legend.
 *
 * So the two formats that carry data rather than a picture are written here, in common code, and
 * both front ends get the same bytes. It is a small encoder because PNG is a small format when the
 * only two shapes wanted are greyscale and palette, and because the compression is not written
 * here at all — see [ZlibDeflater].
 */
internal object PngWriter {

    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    /** PNG colour types. Only the two that carry data are here. */
    private const val GREYSCALE = 0
    private const val INDEXED = 3

    /**
     * A sixteen-bit greyscale image, [samples] one entry per pixel in row-major order, each read
     * as an unsigned value in 0..65535.
     *
     * `IntArray` rather than `ShortArray` because every caller computes these as ints and a
     * `ShortArray` would only mean a cast at both ends of the boundary and a sign to remember.
     */
    suspend fun greyscale16(
        widthPixels: Int,
        heightPixels: Int,
        samples: IntArray,
        deflater: ZlibDeflater
    ): ByteArray {
        require(samples.size == widthPixels * heightPixels) {
            "${samples.size} samples for a ${widthPixels}x$heightPixels image"
        }

        // Two bytes a pixel, big-endian, and one filter byte at the head of every row.
        val stride = widthPixels * 2
        val raw = ByteArray((stride + 1) * heightPixels)
        val row = ByteArray(stride)
        val previous = ByteArray(stride)
        // The five candidates, allocated once for the whole image rather than once a row: at 4096
        // that is five allocations instead of twenty thousand.
        val candidates = Array(FILTERS) { ByteArray(stride) }
        var written = 0
        for (y in 0 until heightPixels) {
            val base = y * widthPixels
            for (x in 0 until widthPixels) {
                val value = samples[base + x]
                row[x * 2] = ((value shr 8) and 0xFF).toByte()
                row[x * 2 + 1] = (value and 0xFF).toByte()
            }
            written = filterRow(row, previous, bytesPerPixel = 2, candidates, raw, written)
            row.copyInto(previous)
        }

        return build(
            widthPixels, heightPixels,
            bitDepth = 16, colourType = GREYSCALE, palette = null,
            imageData = raw, deflater = deflater
        )
    }

    /**
     * An eight-bit indexed image: [indices] one palette entry per pixel, [palette] as packed
     * 0xAARRGGBB, of which only the colour is written — a legend has no use for transparency.
     */
    suspend fun indexed8(
        widthPixels: Int,
        heightPixels: Int,
        indices: ByteArray,
        palette: IntArray,
        deflater: ZlibDeflater
    ): ByteArray {
        require(indices.size == widthPixels * heightPixels) {
            "${indices.size} indices for a ${widthPixels}x$heightPixels image"
        }
        require(palette.isNotEmpty() && palette.size <= 256) {
            "a PNG palette holds 1 to 256 colours, not ${palette.size}"
        }

        // Unfiltered, which the PNG specification recommends for palette images: a filter subtracts
        // neighbouring *indices*, and the difference between two indices means nothing.
        val raw = ByteArray((widthPixels + 1) * heightPixels)
        for (y in 0 until heightPixels) {
            val from = y * widthPixels
            val to = y * (widthPixels + 1) + 1
            indices.copyInto(raw, to, from, from + widthPixels)
        }

        return build(
            widthPixels, heightPixels,
            bitDepth = 8, colourType = INDEXED, palette = palette,
            imageData = raw, deflater = deflater
        )
    }

    private suspend fun build(
        widthPixels: Int,
        heightPixels: Int,
        bitDepth: Int,
        colourType: Int,
        palette: IntArray?,
        imageData: ByteArray,
        deflater: ZlibDeflater
    ): ByteArray {
        val out = ByteSink(imageData.size / 2 + 1024)
        out.bytes(SIGNATURE)

        val header = ByteSink(13)
        header.int(widthPixels)
        header.int(heightPixels)
        header.byte(bitDepth)
        header.byte(colourType)
        header.byte(0) // compression: deflate, the only one PNG has
        header.byte(0) // filtering: the adaptive five, the only set PNG has
        header.byte(0) // interlace: none
        out.chunk("IHDR", header.toByteArray())

        if (palette != null) {
            val table = ByteSink(palette.size * 3)
            palette.forEach { colour ->
                table.byte((colour shr 16) and 0xFF)
                table.byte((colour shr 8) and 0xFF)
                table.byte(colour and 0xFF)
            }
            out.chunk("PLTE", table.toByteArray())
        }

        out.chunk("IDAT", deflater.toZlibStream(imageData))
        out.chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    /**
     * Writes the best of the five PNG filters for one row into [into] at [at], and returns where
     * the next row goes.
     *
     * "Best" by the minimum-sum-of-absolute-differences heuristic the PNG specification itself
     * recommends: whichever filter leaves the smallest residuals is the one deflate will do most
     * with. It matters here rather than being a nicety — a sixteen-bit heightmap is 32 MB of raw
     * samples at 4096, and terrain is smooth, so the up-and-Paeth predictors are the difference
     * between a file that compresses and one that does not.
     */
    private fun filterRow(
        row: ByteArray,
        previous: ByteArray,
        bytesPerPixel: Int,
        candidates: Array<ByteArray>,
        into: ByteArray,
        at: Int
    ): Int {
        var bestType = 0
        var bestScore = Long.MAX_VALUE

        for (type in 0 until FILTERS) {
            val filtered = applyFilter(type, row, previous, bytesPerPixel, candidates[type])
            var score = 0L
            // Residuals are read as signed bytes here, because a filter's output is a difference
            // and the heuristic is about how far from zero it is.
            for (b in filtered) score += (if (b < 0) -b.toInt() else b.toInt()).toLong()
            if (score < bestScore) {
                bestScore = score
                bestType = type
            }
        }

        into[at] = bestType.toByte()
        candidates[bestType].copyInto(into, at + 1)
        return at + 1 + row.size
    }

    /** The five PNG filters: none, sub, up, average and Paeth, in the order the format numbers them. */
    private const val FILTERS = 5

    private fun applyFilter(
        type: Int,
        row: ByteArray,
        previous: ByteArray,
        bytesPerPixel: Int,
        out: ByteArray
    ): ByteArray {
        if (type == 0) {
            row.copyInto(out)
            return out
        }
        for (i in row.indices) {
            val here = row[i].toInt() and 0xFF
            val left = if (i >= bytesPerPixel) row[i - bytesPerPixel].toInt() and 0xFF else 0
            val above = previous[i].toInt() and 0xFF
            val aboveLeft =
                if (i >= bytesPerPixel) previous[i - bytesPerPixel].toInt() and 0xFF else 0
            val predicted = when (type) {
                1 -> left
                2 -> above
                3 -> (left + above) / 2
                else -> paeth(left, above, aboveLeft)
            }
            out[i] = ((here - predicted) and 0xFF).toByte()
        }
        return out
    }

    /** The PNG specification's own predictor: whichever neighbour the gradient points at. */
    private fun paeth(left: Int, above: Int, aboveLeft: Int): Int {
        val estimate = left + above - aboveLeft
        val toLeft = if (estimate >= left) estimate - left else left - estimate
        val toAbove = if (estimate >= above) estimate - above else above - estimate
        val toCorner = if (estimate >= aboveLeft) estimate - aboveLeft else aboveLeft - estimate
        return when {
            toLeft <= toAbove && toLeft <= toCorner -> left
            toAbove <= toCorner -> above
            else -> aboveLeft
        }
    }
}

/**
 * Turns a run of bytes into a zlib stream, which is the one thing a PNG needs that neither this
 * module nor common Kotlin can do for itself.
 *
 * A seam rather than an implementation because deflate lives in the host: `java.util.zip` on the
 * desktop, `CompressionStream` in a browser, and neither exists in common code. Suspend for the
 * browser's sake, exactly as [Compressor] is.
 */
internal fun interface ZlibDeflater {
    suspend fun toZlibStream(raw: ByteArray): ByteArray
}

/**
 * The zlib stream a PNG wants, built from the gzip the host already knows how to make.
 *
 * gzip and zlib are the same deflate stream in two different envelopes — RFC 1952 wraps it in an
 * eleven-or-more-byte header and a CRC-32, RFC 1950 in two bytes and an Adler-32 — so a host with a
 * gzip encoder has a zlib encoder, and the difference is the envelope. Every save already goes
 * through [Compressor] on both front ends, so taking the deflate payload out of one envelope and
 * putting it in the other is a dozen lines against a second compression seam, two more `expect`
 * declarations and two more platform files for two bytes of difference.
 *
 * The header is checked rather than assumed. Both hosts write the minimal ten-byte form today
 * (`GZIPOutputStream` and `CompressionStream('gzip')` both set no optional fields), but a host that
 * wrote a file name or an extra field into its header would otherwise produce a PNG that no reader
 * could open, which is a silent and very confusing failure; anything unexpected falls back to
 * [storedDeflate] instead, which is larger and always correct.
 */
internal class GzipRewrappingDeflater(private val compressor: Compressor) : ZlibDeflater {

    override suspend fun toZlibStream(raw: ByteArray): ByteArray {
        val gzip = runCatching { compressor.compress(raw) }.getOrNull()
        val payload = gzip?.let { deflatePayloadOf(it) } ?: return storedDeflate(raw)
        val out = ByteSink(payload.size + 6)
        // 0x78 0x9C: deflate, a 32 KB window, the default compression level. (0x789C) % 31 == 0,
        // which is the check byte zlib's two-byte header is defined by.
        out.byte(0x78)
        out.byte(0x9C)
        out.bytes(payload)
        out.int(adler32(raw))
        return out.toByteArray()
    }

    /** The deflate stream inside a minimal gzip envelope, or null if this is not one. */
    private fun deflatePayloadOf(gzip: ByteArray): ByteArray? {
        val headerSize = 10
        val trailerSize = 8
        if (gzip.size <= headerSize + trailerSize) return null
        if (gzip[0] != 0x1F.toByte() || gzip[1] != 0x8B.toByte()) return null
        if (gzip[2].toInt() != 8) return null
        // Any flag set means an optional field follows the fixed header, and the payload does not
        // start where this expects it to.
        if (gzip[3].toInt() != 0) return null
        return gzip.copyOfRange(headerSize, gzip.size - trailerSize)
    }
}

/**
 * A valid zlib stream that compresses nothing, for a host with no deflate at all.
 *
 * Deflate's stored block type exists for data that would grow if it were compressed, and it is
 * equally a way of writing a conforming stream without an encoder. The file is a third larger than
 * the raw samples rather than a third of them, which is a poor export and a great deal better than
 * no export: every reader opens it.
 */
internal fun storedDeflate(raw: ByteArray): ByteArray {
    val blockLimit = 65535
    val out = ByteSink(raw.size + raw.size / blockLimit * 5 + 16)
    out.byte(0x78)
    out.byte(0x01) // (0x7801) % 31 == 0, and the level bits say "fastest", which stored is.

    var at = 0
    do {
        val length = minOf(blockLimit, raw.size - at)
        val last = at + length >= raw.size
        out.byte(if (last) 1 else 0)
        // A stored block's length and its ones-complement, both little-endian, as RFC 1951 has it.
        out.byte(length and 0xFF)
        out.byte((length shr 8) and 0xFF)
        out.byte(length.inv() and 0xFF)
        out.byte((length.inv() shr 8) and 0xFF)
        out.bytes(raw, at, length)
        at += length
    } while (at < raw.size)

    out.int(adler32(raw))
    return out.toByteArray()
}

/** zlib's checksum over the uncompressed data. */
internal fun adler32(data: ByteArray): Int {
    val modulus = 65521
    var low = 1
    var high = 0
    for (b in data) {
        low = (low + (b.toInt() and 0xFF)) % modulus
        high = (high + low) % modulus
    }
    return (high shl 16) or low
}

/** PNG's and zip's checksum, the ordinary reflected CRC-32. */
internal object Crc32 {

    private val table = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) 0xEDB88320.toInt() xor (c ushr 1) else c ushr 1 }
        c
    }

    fun of(data: ByteArray, from: Int = 0, length: Int = data.size - from): Int {
        var c = -1
        for (i in from until from + length) {
            c = table[(c xor data[i].toInt()) and 0xFF] xor (c ushr 8)
        }
        return c.inv()
    }
}

/**
 * A growable byte buffer with the handful of writes these two formats need.
 *
 * `ByteArrayOutputStream` is a JVM class and there is no common equivalent; a `MutableList<Byte>`
 * would box thirty million boxes for one heightmap.
 */
internal class ByteSink(capacity: Int = 32) {

    private var buffer = ByteArray(maxOf(capacity, 16))
    private var size = 0

    val length: Int get() = size

    fun byte(value: Int) {
        room(1)
        buffer[size++] = value.toByte()
    }

    /** Big-endian, which is the byte order both PNG chunks and zlib checksums are written in. */
    fun int(value: Int) {
        room(4)
        buffer[size++] = ((value shr 24) and 0xFF).toByte()
        buffer[size++] = ((value shr 16) and 0xFF).toByte()
        buffer[size++] = ((value shr 8) and 0xFF).toByte()
        buffer[size++] = (value and 0xFF).toByte()
    }

    /** Little-endian, which is the byte order zip writes every one of its fields in. */
    fun littleInt(value: Int) {
        room(4)
        buffer[size++] = (value and 0xFF).toByte()
        buffer[size++] = ((value shr 8) and 0xFF).toByte()
        buffer[size++] = ((value shr 16) and 0xFF).toByte()
        buffer[size++] = ((value shr 24) and 0xFF).toByte()
    }

    fun littleShort(value: Int) {
        room(2)
        buffer[size++] = (value and 0xFF).toByte()
        buffer[size++] = ((value shr 8) and 0xFF).toByte()
    }

    fun bytes(data: ByteArray, from: Int = 0, length: Int = data.size - from) {
        room(length)
        data.copyInto(buffer, size, from, from + length)
        size += length
    }

    fun ascii(text: String) {
        room(text.length)
        for (c in text) buffer[size++] = c.code.toByte()
    }

    /** One PNG chunk: its length, its four-letter type, its payload and the CRC of the last two. */
    fun chunk(type: String, payload: ByteArray) {
        int(payload.size)
        val start = size
        ascii(type)
        bytes(payload)
        int(Crc32.of(buffer, start, size - start))
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    private fun room(more: Int) {
        if (size + more <= buffer.size) return
        var grown = buffer.size * 2
        while (grown < size + more) grown *= 2
        buffer = buffer.copyOf(grown)
    }
}
