package com.worldforge.worldgen.math

/**
 * Primitive binary min-heap. Avoids the boxing cost of PriorityQueue<Long>, which matters because
 * the priority-flood pass pushes every land cell — millions of them at export resolutions.
 */
class LongMinHeap(initialCapacity: Int = 1024) {

    private var data = LongArray(initialCapacity.coerceAtLeast(1))
    var size: Int = 0
        private set

    fun isEmpty(): Boolean = size == 0

    fun push(value: Long) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        var i = size++
        data[i] = value
        while (i > 0) {
            val parent = (i - 1) / 2
            if (data[parent] <= data[i]) break
            val t = data[parent]; data[parent] = data[i]; data[i] = t
            i = parent
        }
    }

    fun pop(): Long {
        val top = data[0]
        data[0] = data[--size]
        var i = 0
        while (true) {
            val left = 2 * i + 1
            if (left >= size) break
            val right = left + 1
            val child = if (right < size && data[right] < data[left]) right else left
            if (data[i] <= data[child]) break
            val t = data[child]; data[child] = data[i]; data[i] = t
            i = child
        }
        return top
    }
}
