package com.cartogenesis.worldgen.concurrent

/**
 * Splits an independent loop across cores where the platform has them.
 *
 * Only ever used for work where each iteration writes to indices no other iteration touches, so
 * the result is bit-for-bit identical whether it runs on one thread or twelve. That property is
 * not incidental — `WorldFingerprintTest` holds two generations of one seed to the same world, so
 * a parallel pass that changed its output with the thread count would fail it.
 *
 * Declared in common code with the JVM's implementation the only one: the browser targets, which
 * ran the loop in order on their one thread, were removed with the browser build (G1).
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
