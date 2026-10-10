package com.cartogenesis.worldgen.concurrent

/**
 * The most heap the platform will give this process, in bytes: the ceiling it was started with,
 * not what is free at the moment, so a choice made on it is the same on every run of one
 * configuration. Read where a stage may keep a large working set to save time, never where it
 * would change what is computed.
 */
expect fun maximumHeapBytes(): Long
