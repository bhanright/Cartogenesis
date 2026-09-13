package com.cartogenesis.worldgen.concurrent

// The generation runs on a pool of real threads here, so the interface was never waiting on it and
// there is nothing to stand aside for.
actual suspend fun standAside() = Unit
