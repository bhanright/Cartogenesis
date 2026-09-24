package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig

/**
 * How long a whole generation takes on this machine, measured once per test JVM and shared by the
 * cost guards that state their bar as a share of it.
 *
 * The rule-8 cost guards ask whether a per-cell pass is under a hundredth of a 2048 world. They used
 * to divide by a 2048 world of 180 seconds "as `GenerationSpeedTest` last reported it", which no
 * code measures — that test times 256 and 512 — and which was three times anything recorded. The
 * denominator is now generated here, fresh, on the same machine and in the same run as the pass it
 * is set against, so the share is two timings taken side by side. Seed 7, the default settings,
 * on the processor, as the guards' own arithmetic is timed.
 *
 * A 2048 world is a minute or two of generation, which is why every guard that reads this is in the
 * audit tier; the first to ask pays for it and the rest reuse the figure.
 */
internal object GenerationTime {

    private val measured = HashMap<Int, Double>()

    /** Seconds to generate the default world at [side] cells square, measured the first time. */
    @Synchronized
    fun secondsAt(side: Int): Double = measured.getOrPut(side) {
        val config = WorldGenConfig(seed = 7L, width = 512, height = 512).atResolution(side, side)
        val started = System.nanoTime()
        WorldGenerationEngine.generateBlocking(config)
        val seconds = (System.nanoTime() - started) / 1e9
        println("GENERATION TIME seed 7 at $side: %.1f s".format(seconds))
        seconds
    }
}
