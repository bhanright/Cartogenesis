package com.cartogenesis.worldgen.concurrent

/**
 * Splits an independent loop across cores where the platform has them.
 *
 * Only ever used for work where each iteration writes to indices no other iteration touches, so
 * the result is bit-for-bit identical whether it runs on one thread or twelve. That property is
 * not incidental — a save stores a seed rather than a world, and `WorldFingerprintTest` compares
 * platforms against each other, so a parallel pass that changed its output would break saves and
 * fail CI.
 *
 * Wasm and JS are single-threaded, and their implementations simply run the loop in order.
 */
expect fun parallelFor(fromInclusive: Int, toExclusive: Int, body: (Int) -> Unit)

/**
 * Splits a range into contiguous chunks, one per worker.
 *
 * Preferred over [parallelFor] when each iteration is small, since handing out a whole band of
 * rows at once avoids paying scheduling overhead per row.
 */
expect fun parallelChunks(fromInclusive: Int, toExclusive: Int, body: (Int, Int) -> Unit)

/** How many workers the platform will actually use. 1 means everything runs inline. */
expect fun parallelism(): Int
