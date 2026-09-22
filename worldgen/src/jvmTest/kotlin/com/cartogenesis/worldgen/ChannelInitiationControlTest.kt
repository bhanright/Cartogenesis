package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ChannelInitiation
import com.cartogenesis.worldgen.pipeline.LakeWaterBalance
import kotlin.test.Test
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * R1's discriminating control: the channel-head threshold with the vegetation term off, shown
 * failing the drainage-density clauses the term exists to meet.
 *
 * A guard nothing can fail is not a guard, and the two clauses R1 earns back — the peak in a
 * dryland and the fall-off on the wet side — are both statements about a *curve*, which a suite can
 * meet by accident if the land happens to be distributed the right way. W2's ledger row records
 * exactly that accident being spent. So the chunk owes a control that says which half of the
 * criterion does the work.
 *
 * **The vegetation term is that half, and the argument is one line.** A runoff weight on the area
 * can only push cells *over* a fixed bar: more rain, more effective catchment, more channel, and
 * nothing in it can ever bring a wet country's density back down. Moglen, Eltahir and Bras's
 * fall-off above semi-arid country is a rise in what the ground can resist, not a fall in what the
 * water brings, and in this criterion that is `ChannelInitiation.coverFactor`. Turned off, every
 * cell is held to the bare-ground threshold and the runoff weight is the only climate term left.
 *
 * The control world is the same world: the finished one is handed back to the engine with the flag
 * off, so every stage above the rivers is reused by identity and the terrain, the erosion, the sea
 * and the climate are literally the same objects. The only difference between the two networks is
 * the term under test.
 */
class ChannelInitiationControlTest {

    private companion object {
        /** `EarthLikenessTest`'s seeds, at the size a preview is drawn at. */
        val SEEDS = listOf(7L, 42L, 1234L, 99L)
        const val SIDE = 512
    }

    @Test
    fun `without the cover term the drainage-density clauses fail`() {
        val pool = EarthLikeness.Pool()
        val bare = SEEDS.map { seed ->
            val config = WorldGenConfig(seed = seed, width = SIDE, height = SIDE)
            val withCover: WorldMap = WorldGenerationEngine.generateBlocking(config)
            val withoutCover = runBlocking {
                WorldGenerationEngine.generate(
                    config.copy(rivers = config.rivers.copy(coverRaisesChannelHead = false)),
                    previous = withCover
                )
            }
            EarthLikeness.measure(withoutCover, "$seed bare", pool)
        }
        val pooled = pool.pooled("pooled bare")
        bare.forEach { report(it) }
        report(pooled)

        val complaints = listOfNotNull(
            EarthLikeness.drainagePeakComplaint(pooled.label, pooled.drainageInitiatedNetwork),
            EarthLikeness.drainageWetSideComplaint(pooled.label, pooled.drainageInitiatedNetwork)
        )
        assertTrue(
            "with the cover term off the drainage density still peaks in a dryland and still" +
                " falls away on the wet side, so the term is not what earns those clauses and the" +
                " control is measuring nothing",
            complaints.isNotEmpty()
        )
        println("R1 CONTROL the bare-ground threshold fails: ${complaints.joinToString("; ")}")
    }

    /**
     * The ground that never thaws is decided by the thermometer, and by nothing a lake owns.
     *
     * `ChannelInitiation.neverThaws` keeps a channel head off ground where water never runs, and it
     * used to ask for that ground by running Thornthwaite's demand and testing it for zero. The
     * demand is zero exactly where neither season rises above freezing, so the answer was right —
     * except that the demand it ran is `LakeWaterBalance.potentialEvaporationMm`, whose last act is
     * to multiply by `LakesConfig.evaporationScale`. That is a knob on how hard a lake's surface
     * evaporates, and at zero it made the whole world read as permanently frozen: every land cell
     * failed the head test and the map carried no channel at all.
     *
     * A cell's warmest month is the quantity the rule is about, so the rule now reads it. This
     * turns the lake knob off and asserts that the network is still there; the control is the
     * superseded test, run over the same cells and counted, which calls every one of them frozen.
     */
    @Test
    fun `the frozen-ground rule does not follow the lake's evaporation scale`() {
        val config = WorldGenConfig(seed = 42L, width = SIDE, height = SIDE)
        val world: WorldMap = WorldGenerationEngine.generateBlocking(config)
        val noLakeEvaporation = runBlocking {
            WorldGenerationEngine.generate(
                config.copy(lakes = config.lakes.copy(evaporationScale = 0f)),
                previous = world
            )
        }

        val channelsWith = ChannelInitiation.channelMaskOf(world).count { it }
        val channelsWithout = ChannelInitiation.channelMaskOf(noLakeEvaporation).count { it }

        // The two rules, counted over the same land: the thermometer's, and the demand's with the
        // lake's scale at zero.
        val climate = noLakeEvaporation.climate
        val scale = noLakeEvaporation.config.lakes.evaporationScale
        var land = 0
        var frozenNow = 0
        var frozenBefore = 0
        for (cell in noLakeEvaporation.sea.isLand.indices) {
            if (!noLakeEvaporation.sea.isLand[cell]) continue
            land++
            if (climate.summerTemperature.data[cell] <= 0f) frozenNow++
            val demandMm = LakeWaterBalance.potentialEvaporationMm(
                climate.summerTemperature.data[cell], climate.winterTemperature.data[cell], scale
            )
            if (demandMm <= 0f) frozenBefore++
        }

        println(
            "R1 CONTROL seed 42 with the lake evaporation off: $channelsWithout channel cells" +
                " against $channelsWith with it on; $frozenNow of $land land cells never thaw by" +
                " the thermometer, $frozenBefore by the demand the rule used to read"
        )
        assertTrue(
            "with the lake's evaporation scale at zero the superseded rule called $frozenBefore of" +
                " $land land cells permanently frozen, so this control is not measuring the fault",
            frozenBefore == land
        )
        assertTrue(
            "the thermometer called $frozenNow of $land land cells permanently frozen, which is" +
                " every one of them, so a lake setting is still deciding the answer",
            frozenNow < land
        )
        assertTrue(
            "a world with the lake's evaporation scale at zero initiated $channelsWithout channel" +
                " cells against $channelsWith with it on, so the lake setting is still deciding" +
                " where the ground carries a channel",
            channelsWithout >= channelsWith / 2
        )
    }

    private fun report(metrics: EarthLikeness.Metrics) {
        val densities = EarthLikeness.Aridity.entries.joinToString(" ") {
            "${it.name}:${"%.4f".format(metrics.drainageInitiatedNetwork.densityIn(it))}"
        }
        println(
            "R1 CONTROL ${metrics.label}: peak ${metrics.drainageInitiatedNetwork.peak()}," +
                " humid over semi-arid" +
                " ${"%.2f".format(EarthLikeness.drainageWetSideRatio(metrics.drainageInitiatedNetwork))}" +
                " — $densities"
        )
    }
}
