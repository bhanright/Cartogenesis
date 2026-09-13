package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
import com.cartogenesis.worldgen.pipeline.erodeBlockingReportingRounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * That a generation under way can be stopped, and that stopping one costs nothing afterwards.
 *
 * The pipeline is ordinary blocking arithmetic from end to end — the one `suspend` in it exists so
 * a WebGPU accelerator can await its device — so before F11 a cancelled coroutine went on computing
 * a world nobody wanted until the last landmark was placed. That is the whole of William's
 * complaint: "sometimes I notice I wanted to change a setting and I don't want to wait for it to
 * finish". So each long loop now asks, between one round and the next, whether the generation is
 * still wanted.
 *
 * Three claims, and the first is the one that fails without the fix:
 *
 *  - a stop during erosion is answered within one hydraulic round, which this measures rather than
 *    assumes: it times the rounds of an ordinary erosion first and holds the stop to the slowest of
 *    them;
 *  - the world the engine was handed to reuse comes back untouched, so the map on screen survives
 *    the stop it was interrupted by;
 *  - and the next generation produces the same world as one that was never interrupted, cell for
 *    cell, so a stop leaves nothing behind in the engine.
 */
class GenerationStopTest {

    /**
     * 512, deliberately.
     *
     * The bound is one hydraulic round, and a round at 256 is a few tens of milliseconds — short
     * enough that the measurement is mostly thread scheduling and the guard would be flaky rather
     * than strict. At 512 a round is long enough to be a real bound and short enough that the test
     * runs in a minute.
     */
    private val base = WorldGenConfig(seed = 99L, width = 512, height = 512)

    @Test
    fun `a stop during erosion lands within one hydraulic round and costs the next world nothing`() {
        // What one round costs on this machine, measured on the same terrain the stop will
        // interrupt. Timed round by round rather than as a mean of the stage, because the closing
        // round does more than the others and the bound has to cover the slowest.
        val plates = PlateStage.generate(base, TerrainStage.generate(base))
        val roundMillis = mutableListOf<Long>()
        var lastRoundEnded = System.currentTimeMillis()
        erodeBlockingReportingRounds(base, plates.height) { _ ->
            val now = System.currentTimeMillis()
            roundMillis += now - lastRoundEnded
            lastRoundEnded = now
        }
        val slowestRound = roundMillis.max()
        println(
            "STOP a hydraulic round at ${base.width} costs ${roundMillis.min()}-${slowestRound} ms " +
                "over ${roundMillis.size} rounds (mean ${roundMillis.average().toInt()} ms)"
        )

        // The world already on screen when the reader presses Generate again, and the settings
        // change that makes them press it: a coarser critical slope, which re-runs erosion and
        // everything downstream of it while terrain and plates stand.
        val onScreen = WorldGenerationEngine.generateBlocking(base)
        val onScreenBefore = fingerprint(onScreen)
        val changed = base.copy(
            erosion = base.erosion.copy(
                criticalFallMetresPerKm = base.erosion.criticalFallMetresPerKm + 3f
            )
        )

        // What that change produces when nobody interrupts it, which is the answer the interrupted
        // engine has to give as well.
        val uninterrupted = WorldGenerationEngine.generateBlocking(changed, previous = onScreen)

        val stopLatency = runBlocking {
            val reachedErosion = CompletableDeferred<Unit>()
            val generation = async(Dispatchers.Default) {
                WorldGenerationEngine.generate(changed, previous = onScreen) { stage, _, _ ->
                    if (stage == GenerationStage.EROSION) reachedErosion.complete(Unit)
                }
            }
            reachedErosion.await()
            // Far enough in to be inside the rounds rather than in the thermal sweeps before them,
            // so what the bound measures is the round boundary and not a lucky early exit.
            delay(slowestRound * 2)
            val latency = measureTimeMillis {
                generation.cancel()
                // Generous: what is being measured is how long the join takes, and a timeout that
                // is itself the bound would turn a failure into a hang.
                withTimeout(GIVE_UP_MILLIS) { runCatching { generation.await() } }
            }
            latency
        }

        println("STOP the generation stopped $stopLatency ms after the ask")
        assertTrue(
            stopLatency <= slowestRound,
            "a stop took $stopLatency ms to land, longer than the slowest hydraulic round " +
                "($slowestRound ms), so the pipeline is not checking between rounds"
        )

        // The world on screen was handed to the engine as the one to reuse. A cancelled generation
        // must not have written into it, or the map the reader kept would be half of two worlds.
        assertEquals(
            onScreenBefore,
            fingerprint(onScreen),
            "the world the engine was told to reuse came back changed by a generation that was " +
                "stopped, so a stop leaves a half-built world on screen"
        )

        // And the engine holds nothing over from the run that was abandoned.
        val afterStop = WorldGenerationEngine.generateBlocking(changed, previous = onScreen)
        assertEquals(
            fingerprint(uninterrupted),
            fingerprint(afterStop),
            "the world generated after a stop differs from one generated without an interruption"
        )
    }

    /**
     * Which stages a stop leaves behind: all of the last completed world's that still apply.
     *
     * Cancellation and reuse never meet, and that is the design rather than a happy accident — a
     * cancelled generation returns no `WorldMap` at all, so there is nothing for it to put in the
     * cache and the last completed world stays the one the next generation is handed. This asserts
     * the consequence: after a stop, a restart at a changed erosion setting reuses terrain and
     * plates from the world that finished, and recomputes erosion and everything after it.
     */
    @Test
    fun `a stopped generation leaves the last completed world as the one to reuse`() {
        val small = WorldGenConfig(seed = 99L, width = 256, height = 256)
        val onScreen = WorldGenerationEngine.generateBlocking(small)
        val changed = small.copy(
            erosion = small.erosion.copy(
                criticalFallMetresPerKm = small.erosion.criticalFallMetresPerKm + 3f
            )
        )

        runBlocking {
            val reachedErosion = CompletableDeferred<Unit>()
            val generation = async(Dispatchers.Default) {
                WorldGenerationEngine.generate(changed, previous = onScreen) { stage, _, _ ->
                    if (stage == GenerationStage.EROSION) reachedErosion.complete(Unit)
                }
            }
            reachedErosion.await()
            generation.cancel()
            withTimeout(GIVE_UP_MILLIS) { runCatching { generation.await() } }
        }

        val restarted = WorldGenerationEngine.generateBlocking(changed, previous = onScreen)
        assertSame(onScreen.terrain, restarted.terrain, "terrain did not survive the stop")
        assertSame(onScreen.plates, restarted.plates, "plates did not survive the stop")
        // The erosion setting moved, so from erosion down everything is new. Named one by one
        // rather than as a count, so a stage quietly reused from a half-built world would show.
        assertTrue(onScreen.erosion !== restarted.erosion, "erosion was reused across a change")
        assertTrue(onScreen.sea !== restarted.sea, "the sea stage was reused across a change")
        assertTrue(onScreen.rivers !== restarted.rivers, "rivers were reused across a change")
        println("STOP terrain and plates survived; erosion and everything after it re-ran")
    }

    private fun fingerprint(world: WorldMap): String {
        fun sum(values: FloatArray): Long {
            var checksum = 0L
            values.forEach { checksum = checksum * 31 + it.toRawBits() }
            return checksum
        }
        return listOf(
            "terrain=${sum(world.terrain.height.data)}",
            "plates=${sum(world.plates.height.data)}",
            "erosion=${sum(world.erosion.height.data)}",
            "sea=${sum(world.sea.relativeElevation.data)}",
            "climate=${sum(world.climate.temperature.data)}",
            "rivers=${world.rivers.rivers.size},${sum(world.rivers.flowAccumulation.data)}",
            "nations=${world.nations.nations.size},${world.nations.nationId.sum()}",
            "landmarks=${world.landmarks.landmarks.size}"
        ).joinToString("\n         ")
    }

    private companion object {
        /** A stop that has not landed in this long is a hang, not a slow answer. */
        const val GIVE_UP_MILLIS = 120_000L
    }
}
