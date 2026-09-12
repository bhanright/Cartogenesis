package com.cartogenesis.worldgen.math

/**
 * Primitive binary min-heap. Avoids the boxing cost of PriorityQueue<Long>, which matters because
 * the priority-flood pass pushes every land cell — millions of them at export resolutions.
 *
 * Callers pack a priority into the high bits of the Long and a payload into the low bits, so the
 * plain numeric order this keeps is the order they want.
 */
class LongMinHeap(initialCapacity: Int = DEFAULT_CAPACITY) {

    private var entries = LongArray(initialCapacity.coerceAtLeast(1))

    /** How many entries the heap holds; [pop] is only defined while this is above zero. */
    var size: Int = 0
        private set

    fun isEmpty(): Boolean = size == 0

    /** Adds [value], keeping the smallest entry at the root. */
    fun push(value: Long) {
        if (size == entries.size) entries = entries.copyOf(entries.size * 2)
        var slot = size++
        entries[slot] = value
        // Sift up: swap with the parent while the new entry is the smaller of the two.
        while (slot > 0) {
            val parent = (slot - 1) / 2
            if (entries[parent] <= entries[slot]) break
            val swapped = entries[parent]
            entries[parent] = entries[slot]
            entries[slot] = swapped
            slot = parent
        }
    }

    /** Removes and returns the smallest entry. */
    fun pop(): Long {
        val smallest = entries[0]
        entries[0] = entries[--size]
        // Sift down: swap with the smaller child while that child is smaller than the entry.
        var slot = 0
        while (true) {
            val leftChild = 2 * slot + 1
            if (leftChild >= size) break
            val rightChild = leftChild + 1
            val smallerChild =
                if (rightChild < size && entries[rightChild] < entries[leftChild]) rightChild
                else leftChild
            if (entries[slot] <= entries[smallerChild]) break
            val swapped = entries[smallerChild]
            entries[smallerChild] = entries[slot]
            entries[slot] = swapped
            slot = smallerChild
        }
        return smallest
    }

    private companion object {
        /**
         * Entries a heap starts with when the caller does not say.
         *
         * The array doubles when it fills, so this only decides how many doublings a large flood
         * pays on its way up; every caller inside the pipeline sizes its own heap from the grid.
         */
        const val DEFAULT_CAPACITY = 1024
    }
}
