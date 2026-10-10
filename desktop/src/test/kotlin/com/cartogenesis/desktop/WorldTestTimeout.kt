package com.cartogenesis.desktop

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * How long a test that generates worlds through the interface may run: fifteen minutes. Since the
 * atmosphere is coupled to the rain, a world carries the loop's laps on the atmosphere's own grid,
 * which is the planet's and not the map's, so even the test platforms' 512-row worlds take tens of
 * seconds each, and more where the test worker's heap keeps few of the waves' factors
 * (docs/DESIGN_LEDGER.md, A1-5); the framework's default minute was cutting the arithmetic off,
 * not catching a hang.
 */
internal val WORLD_TEST_TIMEOUT: Duration = 15.minutes
