package com.cartogenesis.web

/** A WGSL module this build hands the browser to compile, named as a failing test reports it. */
internal class WgslModule(val name: String, val source: String)

/**
 * Every WGSL module whose source is held as a Kotlin value, for the tests that check them without
 * a device (`WgslReservedWordsTest`) and with one (`WgslCompilesTest`).
 *
 * A module that fails to compile does not throw: the kernel that asked for it declines, and the
 * stage answers on the processor with nothing on screen to say so, which is how the ice's kernel
 * went uncompiled from the day it was written. So the sources are values a test can read, rather
 * than text inside the JavaScript that runs them. The ocean's solve is not in this list: its source
 * is still written inside its JavaScript function in `WebGpuOcean`.
 */
internal val WGSL_MODULES: List<WgslModule> = listOf(
    WgslModule("erosion's rates pass", EROSION_RATES_WGSL),
    WgslModule("erosion's transfer pass", EROSION_TRANSFER_WGSL),
    WgslModule("ice sheet", ICE_SHEET_WGSL)
)
