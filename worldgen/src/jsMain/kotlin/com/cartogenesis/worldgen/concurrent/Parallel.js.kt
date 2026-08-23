package com.cartogenesis.worldgen.concurrent

// js is single-threaded, so these run the loop in order. Identical output, no concurrency.

actual fun parallelism(): Int = 1

actual fun parallelFor(fromInclusive: Int, toExclusive: Int, body: (Int) -> Unit) {
    for (i in fromInclusive until toExclusive) body(i)
}

actual fun parallelChunks(fromInclusive: Int, toExclusive: Int, body: (Int, Int) -> Unit) {
    body(fromInclusive, toExclusive)
}
