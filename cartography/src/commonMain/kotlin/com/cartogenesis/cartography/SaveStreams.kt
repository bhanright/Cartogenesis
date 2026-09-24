package com.cartogenesis.cartography

/**
 * Where a save's bytes go, in order, a piece at a time.
 *
 * A save is written through one of these rather than built as one array because a world's arrays
 * are 146 bytes a cell: 612 MB at 2048 and 2.45 GB at 4096, which no array on either platform can
 * hold in one piece. The codec hands a sink its header and then one compressed chunk at a time, so
 * what a save costs beyond the world itself is a chunk or two, whatever the grid.
 */
interface SaveSink {
    /** Appends [length] bytes of [bytes], starting at [offset]. */
    suspend fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size)
}

/**
 * Where a save's bytes come back from, in order, a piece at a time: a file, a stored blob, or an
 * array already in memory.
 */
interface SaveSource {
    /**
     * Reads up to [length] bytes into [into] at [offset] and returns how many it read, or -1 when
     * nothing is left. May read fewer than asked for without being at the end.
     */
    suspend fun read(into: ByteArray, offset: Int, length: Int): Int
}

/**
 * Reads until [length] bytes have arrived or the source has ended, and returns how many arrived:
 * [length], or fewer only at the end of the source.
 */
suspend fun SaveSource.readFully(into: ByteArray, offset: Int, length: Int): Int {
    var arrived = 0
    while (arrived < length) {
        val count = read(into, offset + arrived, length - arrived)
        if (count < 0) break
        arrived += count
    }
    return arrived
}

/**
 * A sink that keeps everything it is given, for a save small enough to hold as one array: a test's
 * world, or a fixture. Nothing in the application writes a library save through this.
 */
class ByteArraySink(initialCapacity: Int = 1 shl 16) : SaveSink {
    private var buffer = ByteArray(initialCapacity.coerceAtLeast(1))
    private var size = 0

    override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (size + length > buffer.size) {
            var capacity = buffer.size
            while (capacity < size + length) capacity *= 2
            buffer = buffer.copyOf(capacity)
        }
        bytes.copyInto(buffer, size, offset, offset + length)
        size += length
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}

/** A source over an array already in memory. */
class ByteArraySource(private val bytes: ByteArray) : SaveSource {
    private var position = 0

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (position >= bytes.size) return -1
        val count = minOf(length, bytes.size - position)
        bytes.copyInto(into, offset, position, position + count)
        position += count
        return count
    }
}
