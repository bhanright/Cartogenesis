package com.cartogenesis.ui

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * How long a test that generates a world may run: fifteen minutes. Since the atmosphere is coupled
 * to the rain, every world carries the loop's laps on the atmosphere's own grid, which is the
 * planet's and not the map's, so a 32-cell world costs nearly what a 512-row one does, and this
 * module's half a gigabyte of heap keeps few of the waves' factors between laps
 * (docs/DESIGN_LEDGER.md, A1-5). A 128-cell generation outgrew three and a half minutes; the
 * framework's default minute was cutting the arithmetic off, not catching a hang.
 */
internal val WORLD_TEST_TIMEOUT: Duration = 15.minutes
