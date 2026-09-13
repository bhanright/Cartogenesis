package com.cartogenesis.worldgen.concurrent

/**
 * Hands the host back its thread for a moment, in the middle of a long stage.
 *
 * A browser has one thread: the page, the generator and every repaint share it, so while a stage is
 * computing, nothing the reader does reaches the application — a press of Stop is a click event
 * sitting in a queue nobody is reading. The engine already stands aside at each stage boundary so
 * the progress banner can paint (see [com.cartogenesis.worldgen.GenerationProgress]), but erosion
 * is a single stage and over half the work, so between two of those boundaries there can be twenty
 * seconds in which the page is deaf. This is the same courtesy from inside erosion, once per
 * hydraulic round.
 *
 * It has to be a *timer* rather than a bare `yield`. A browser runs its microtasks — which is what
 * a yield schedules — without returning to the event loop, so a yield hands the thread back and the
 * click is still not delivered; a timer is a macrotask, and the queue is drained before it fires.
 *
 * On a host with threads this does nothing at all, and deliberately: the interface was never
 * blocked there, so a wait per round would be time bought for nothing. Noticing that the generation
 * has been cancelled is a separate question and is asked on both platforms — see the `ensureActive`
 * beside every call to this.
 */
expect suspend fun standAside()
