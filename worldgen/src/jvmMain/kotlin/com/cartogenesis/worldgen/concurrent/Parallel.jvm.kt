package com.cartogenesis.worldgen.concurrent

import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit

/**
 * Uses the common ForkJoinPool, which is already sized to the machine and is shared with anything
 * else using parallel streams — so generation does not spawn threads of its own.
 */
private val pool: ForkJoinPool = ForkJoinPool.commonPool()

actual fun parallelism(): Int = ForkJoinPool.getCommonPoolParallelism().coerceAtLeast(1)

actual fun parallelFor(fromInclusive: Int, toExclusive: Int, body: (Int) -> Unit) {
    val count = toExclusive - fromInclusive
    val workers = parallelism()
    // Below a few hundred items the coordination costs more than the work saved.
    if (workers <= 1 || count < 256) {
        for (i in fromInclusive until toExclusive) body(i)
        return
    }
    parallelChunks(fromInclusive, toExclusive) { start, end ->
        for (i in start until end) body(i)
    }
}

actual fun parallelChunks(fromInclusive: Int, toExclusive: Int, body: (Int, Int) -> Unit) {
    val count = toExclusive - fromInclusive
    val workers = parallelism()
    if (workers <= 1 || count < 2) {
        body(fromInclusive, toExclusive)
        return
    }

    val chunks = minOf(workers, count)
    val size = (count + chunks - 1) / chunks
    val tasks = (0 until chunks).mapNotNull { index ->
        val start = fromInclusive + index * size
        val end = minOf(start + size, toExclusive)
        if (start >= end) null else pool.submit { body(start, end) }
    }
    // Waiting on every task before returning is what keeps this a drop-in replacement for a
    // sequential loop: callers can rely on all writes being visible once it returns.
    tasks.forEach { it.get(1, TimeUnit.HOURS) }
}
