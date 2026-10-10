package com.cartogenesis.worldgen.concurrent

actual fun maximumHeapBytes(): Long = Runtime.getRuntime().maxMemory()
