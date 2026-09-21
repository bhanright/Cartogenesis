package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
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
