package com.cartogenesis.worldgen.concurrent

import kotlinx.coroutines.delay

/**
 * The shortest timer there is, which is all this needs.
 *
 * The stage-boundary wait is a whole frame because what it buys is a repaint. This buys only a
 * return to the event loop, so that a click already waiting there is delivered, and one millisecond
 * is enough for that — the browser drains its queue before the timer fires whatever the timer says.
 */
private const val ONE_TASK_MILLIS: Long = 1L

actual suspend fun standAside() = delay(ONE_TASK_MILLIS)
