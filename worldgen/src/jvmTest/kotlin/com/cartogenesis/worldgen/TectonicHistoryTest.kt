package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.BoundaryClass
import com.cartogenesis.worldgen.pipeline.PlateResult
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Whether the world carries the scars of boundaries that are gone.
 *
 * A drift vector that only classifies today's boundaries builds a world where nothing has ever
 * moved: every range is young, every range is high, and every range sits exactly on a plate edge.
 * Earth's continents do not look like that. The Appalachians and the Urals are collisions whose
 * boundary closed hundreds of millions of years ago — a thousand kilometres from any plate edge,
 * worn to a third of Alpine height and spread over a wider province than the Alps occupy — and the
 * Benue trough and the North Sea graben are rifts that opened, failed and filled with sediment.
 *
 * So this measures the three things that distinguish an old belt from a young one, on the same
 * world, by the same method:
 *
 * - **far** — the tallest thing the history built stands well beyond every present boundary;
 * - **lower** — an old belt's peak relief is a stated factor below a present belt's;
 * - **broader** — its full width at half that peak is a stated factor above a present belt's.
 *
 * The old belts are isolated by differencing: a world with a past epoch against the same world
 * with `historyEpochs = 1`, minus the mean of the difference, which is exactly how
 * [BoundaryPairTest] isolates the hotspot chains. What is left is that epoch's own uplift, with a
 * baseline of zero. The present belts are measured on the one-epoch world against the plate
 * interior, the reference level [BoundaryPairTest] reads. Both are then reduced to a peak and a
 * full width at half height by a mean radial profile away from *their own* epoch's boundaries —
 * an old belt cannot be measured against a boundary that has moved — which is why
 * [PlateStage.epochBoundaries] exists. Both are measured with
 * [com.cartogenesis.worldgen.model.TectonicsConfig.plateElevationBias] at zero, for the reason
 * that test gives: the blurred step between plate interiors is the same size as the belts and
 * would be measured as one of them.
 *
 * With `historyEpochs = 1` the difference field is identically zero, so the history built nothing
 * anywhere and the "far" guard has nothing to find — see
 * [`the guard finds nothing at all with a single epoch`].
 */
class TectonicHistoryTest {

    private val seeds = listOf(7L, 42L, 1234L)

    /**
     * Checksums of `PlateResult.height` taken from the build immediately before H1, on the
     * default config at 512.
     *
     * The chunk's contract is that a one-epoch history is not "close to" the old generator but
     * *is* it: every ageing factor is exactly 1 on the present epoch, a multiply by 1f is the
     * identity in IEEE-754, and the widened config copy is never taken. These numbers are how that
     * is proved rather than asserted — they were printed by the pre-change code and pasted here.
     */
    private val presentOnlyChecksums = mapOf(
        7L to 2044751848601263324L,
        42L to 7853987138546465474L,
        1234L to -3577903678715640491L
    )

    private fun platesOf(seed: Long, epochs: Int, flatten: Boolean = true): PlateResult {
        val base = WorldGenConfig(seed = seed, width = 512, height = 512)
        val config = base.copy(
            tectonics = base.tectonics.copy(historyEpochs = epochs),
            // Flattening the plate interiors is switching isostasy off since S2, where before it
            // was setting the step between them to zero: either way what is left is one level for
            // every crust, so a belt's radial profile is the belt and not the crust under it.
            isostasy = base.isostasy.copy(enabled = !flatten)
        )
        return PlateStage.generate(config, TerrainStage.generate(config))
    }

    @Test
    fun `a single epoch reproduces the generator this chunk replaced, bit for bit`() {
        seeds.forEach { seed ->
            listOf(0, 1).forEach { epochs ->
                val base = WorldGenConfig(seed = seed, width = 512, height = 512)
                val config = base.copy(tectonics = base.tectonics.copy(historyEpochs = epochs))
                val plates = PlateStage.generate(config, TerrainStage.generate(config))
                var checksum = 0L
                plates.height.data.forEach { checksum = checksum * 31 + it.toRawBits() }
                println("HISTORY seed $seed epochs=$epochs height checksum $checksum")
                assertEquals(
                    presentOnlyChecksums.getValue(seed),
                    checksum,
                    "seed $seed with historyEpochs=$epochs is no longer the pre-H1 world"
                )
                // With nothing but the present epoch there is no old crust: every cell a belt
                // touched is in the youngest band and everything else is untouched.
                assertTrue(
                    plates.crustAge.data.all { it in 0f..1f },
                    "seed $seed: crust age left its range"
                )
            }
        }
    }

    @Test
    fun `an old belt stands far from any present boundary`() {
        seeds.forEach { seed ->
            val inland = inlandRelief(seed, DEFAULT_EPOCHS)
            println(
                ("HISTORY seed %d: the tallest ground the history built beyond %.0f cells of " +
                    "every present boundary stands %+.4f, at %.0f cells out")
                    .format(seed, MIN_INLAND_CELLS, inland.relief, inland.distance)
            )
            assertTrue(
                inland.relief >= MIN_BELT_PEAK,
                "seed $seed: the history's tallest ground more than $MIN_INLAND_CELLS cells from " +
                    "any present boundary is only ${inland.relief}, wanted at least " +
                    "$MIN_BELT_PEAK — an old belt that never leaves a modern plate edge is not a " +
                    "scar, it is the same range twice"
            )
        }
    }

    /**
     * The same measurement with the history switched off, which is the "shown failing" half of
     * rule 2: with one epoch the difference field is identically zero, so there is no ground the
     * history built anywhere, inland or not.
     */
    @Test
    fun `the guard finds nothing at all with a single epoch`() {
        seeds.forEach { seed ->
            val inland = inlandRelief(seed, 1)
            println(
                "HISTORY control seed %d with one epoch: tallest inland relief %+.6f (bar %.2f)"
                    .format(seed, inland.relief, MIN_BELT_PEAK)
            )
            assertTrue(
                inland.relief < MIN_BELT_PEAK,
                "seed $seed built inland ground with a single epoch, which cannot happen"
            )
        }
    }

    @Test
    fun `an old belt is lower and broader than a present one`() {
        val old = seeds.map { seed -> beltProfile(oldRelief(seed, 2), epochBoundaries(seed, 1)) }
        val present = seeds.map { seed ->
            beltProfile(presentRelief(seed), epochBoundaries(seed, 0))
        }

        seeds.forEachIndexed { index, seed ->
            println(
                "HISTORY seed %d old belt peak %+.4f FWHM %.0f cells, present peak %+.4f FWHM %.0f cells"
                    .format(
                        seed, old[index].peak, old[index].fullWidth,
                        present[index].peak, present[index].fullWidth
                    )
            )
        }

        // Pooled, because a belt's height is proportional to how hard its pair happens to be
        // converging while its width is not - the same reason BoundaryPairTest pools six worlds.
        val oldPeak = old.map { it.peak.toDouble() }.average()
        val presentPeak = present.map { it.peak.toDouble() }.average()
        val oldWidth = old.map { it.fullWidth.toDouble() }.average()
        val presentWidth = present.map { it.fullWidth.toDouble() }.average()
        println(
            ("HISTORY pooled: old peak %.4f against present %.4f (%.2fx lower), " +
                "old FWHM %.0f against present %.0f cells (%.2fx broader)")
                .format(
                    oldPeak, presentPeak, presentPeak / oldPeak,
                    oldWidth, presentWidth, oldWidth / presentWidth
                )
        )

        assertTrue(
            presentPeak / oldPeak >= MIN_LOWER,
            "the old belts stand only ${presentPeak / oldPeak}x below the present ones, wanted " +
                "at least ${MIN_LOWER}x — the Appalachians are about a third of Alpine height"
        )
        assertTrue(
            oldWidth / presentWidth >= MIN_BROADER,
            "the old belts are only ${oldWidth / presentWidth}x broader than the present ones, " +
                "wanted at least ${MIN_BROADER}x"
        )
    }

    /**
     * The bands of [PlateResult.crustAge], reported per seed: how much of the world each epoch
     * claims, and how much is cratonic ground no epoch ever deformed. H3 reads this field.
     */
    @Test
    fun `report the crust-age bands`() {
        seeds.forEach { seed ->
            val plates = platesOf(seed, DEFAULT_EPOCHS, flatten = false)
            val bands = IntArray(DEFAULT_EPOCHS + 1)
            plates.crustAge.data.forEach { age ->
                val band =
                    if (age >= 1f) DEFAULT_EPOCHS
                    else (age * DEFAULT_EPOCHS).toInt().coerceIn(0, DEFAULT_EPOCHS - 1)
                bands[band]++
            }
            val cells = plates.crustAge.data.size
            val text = (0 until DEFAULT_EPOCHS).joinToString(" ") {
                "%d-epochs-ago %.1f%%".format(it, bands[it] * 100.0 / cells)
            }
            println(
                "HISTORY seed %d crust age: %s cratonic %.1f%%"
                    .format(seed, text, bands[DEFAULT_EPOCHS] * 100.0 / cells)
            )
            assertTrue(bands[0] > 0, "seed $seed has no present-epoch crust")
        }
    }

    private fun configOf(seed: Long, epochs: Int): WorldGenConfig {
        val base = WorldGenConfig(seed = seed, width = 512, height = 512)
        return base.copy(
            tectonics = base.tectonics.copy(historyEpochs = epochs),
            isostasy = base.isostasy.copy(enabled = false)
        )
    }

    private fun epochBoundaries(seed: Long, epochsAgo: Int) =
        PlateStage.epochBoundaries(configOf(seed, DEFAULT_EPOCHS), epochsAgo)

    /**
     * The uplift the past epochs added, isolated by differencing, in the one-epoch world's own
     * units.
     *
     * [PlateStage] normalizes its height field over its own range before returning it, so a world
     * with more relief in it is not merely offset from a world with less — it is offset *and*
     * rescaled, and a plain difference would carry a slice of the present belts and of the terrain
     * noise along with the old ones. So the two fields are brought into one frame first, by a
     * least-squares affine fit taken over the cells [PlateResult.crustAge] says no epoch ever
     * deformed: there the two worlds are the same ground, so whatever maps one onto the other there
     * is the normalization and nothing else. What is left after the difference is the past epochs'
     * own uplift, with a baseline of zero.
     *
     * [epochs] two against one, for the profile comparison, so what is left is a single past
     * epoch's belts at a single age rather than two ages averaged; three against one, for the
     * inland guard, so it measures the world the app ships.
     */
    private fun oldRelief(seed: Long, epochs: Int): FloatArray {
        val history = platesOf(seed, epochs)
        val one = platesOf(seed, 1).height.data
        val many = history.height.data

        // Cratonic ground: cells no epoch in the history touched at all. They still carry the
        // terrain noise, so they have the spread a two-parameter fit needs.
        var n = 0.0
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        for (i in many.indices) {
            if (history.crustAge.data[i] < 1f) continue
            val x = many[i].toDouble()
            val y = one[i].toDouble()
            n++; sx += x; sy += y; sxx += x * x; sxy += x * y
        }
        assertTrue(n > 1000, "seed $seed has only $n cratonic cells to fit the two frames on")
        val denominator = n * sxx - sx * sx
        val a = if (denominator == 0.0) 1.0 else (n * sxy - sx * sy) / denominator
        val b = (sy - a * sx) / n
        return FloatArray(many.size) { (a * many[it] + b - one[it]).toFloat() }
    }

    /**
     * The present belts' relief above the plate interior, taken over every cell far from any
     * boundary — the reference level [BoundaryPairTest] reads, on the same flattened world.
     */
    private fun presentRelief(seed: Long): FloatArray {
        val plates = platesOf(seed, 1)
        val far = (plates.height.width * 0.08f).toInt().coerceAtLeast(1)
        var total = 0.0
        var count = 0L
        for (i in plates.height.data.indices) {
            if (plates.boundaryDistance.data[i] < far) continue
            total += plates.height.data[i].toDouble()
            count++
        }
        val baseline = if (count == 0L) 0f else (total / count).toFloat()
        return FloatArray(plates.height.data.size) { plates.height.data[it] - baseline }
    }

    private class Inland(val relief: Float, val distance: Float)

    /**
     * The tallest thing the history built anywhere further than [MIN_INLAND_CELLS] from a present
     * boundary — a belt whose plate edge is gone, which is the whole claim of this chunk.
     */
    private fun inlandRelief(seed: Long, epochs: Int): Inland {
        val relief = oldRelief(seed, epochs)
        val present = platesOf(seed, 1)
        var best = 0f
        var at = 0f
        for (i in relief.indices) {
            val d = present.boundaryDistance.data[i]
            if (d <= MIN_INLAND_CELLS) continue
            if (relief[i] > best) { best = relief[i]; at = d }
        }
        return Inland(best, at)
    }

    private class BeltShape(val peak: Float, val fullWidth: Float, val cells: Int)

    /**
     * A mean radial profile of [relief] away from the boundaries of [boundaries], reduced to the
     * peak and the full width at half that peak.
     *
     * This is [BoundaryPairTest]'s measurement, applied to whichever epoch's boundaries are handed
     * in: an old belt against the boundary that built it, a present belt against the boundary that
     * is still there. A bin with too few cells in it is discarded for the same reason that test
     * discards one.
     *
     * Restricted to [BoundaryClass.COLLISION_PLATEAU], which is the Appalachians-against-the-Alps
     * comparison the spec asks for — both are continent-against-continent orogens. It is also the
     * only class whose profile is symmetric: an Andean margin carries a trench on its oceanic side
     * and a range on its continental one, so pooling both sides of one averages a mountain with a
     * deep, and [BoundaryPairTest] avoids that by filtering on crust type, which a vanished
     * boundary's plate assignment cannot be asked for here.
     */
    private fun beltProfile(
        relief: FloatArray,
        boundaries: PlateStage.EpochBoundaries
    ): BeltShape {
        val wanted = setOf(BoundaryClass.COLLISION_PLATEAU.ordinal)
        val bins = 160
        val total = DoubleArray(bins)
        val count = IntArray(bins)
        var cells = 0
        for (i in relief.indices) {
            if (boundaries.nearestBoundaryClass[i] !in wanted) continue
            cells++
            val bin = boundaries.distanceCells[i].toInt()
            if (bin !in 0 until bins) continue
            total[bin] += relief[i].toDouble()
            count[bin]++
        }

        fun at(d: Int): Float? = if (count[d] < 24) null else (total[d] / count[d]).toFloat()

        var peak = 0f
        for (d in 0 until bins) {
            val v = at(d) ?: continue
            if (v > peak) peak = v
        }
        var half = 0
        if (peak > 0f) {
            while (half < bins) {
                val v = at(half)
                if (v != null && v < peak / 2f) break
                half++
            }
        }
        return BeltShape(peak, half * 2f, cells)
    }

    private companion object {
        /** The shipped setting, so the guard measures the world the app builds. */
        const val DEFAULT_EPOCHS = 3

        /**
         * How far from a present boundary an old belt's crest has to stand to count as a scar
         * rather than as the modern belt beside it.
         *
         * Stated as twice `boundaryFalloffCells` (26 cells at 512), which is where the present epoch's
         * own uplift has fallen to nothing on every profile the stage builds — so a crest beyond
         * it cannot be a present belt under another name. On a 12,000 km world at 512 that is
         * about 1,200 km; the Appalachian front stands some 2,000 km from the Mid-Atlantic ridge.
         */
        const val MIN_INLAND_CELLS = 52f

        /** The Appalachians against the Alps: roughly 2,000 m against 4,500. */
        const val MIN_LOWER = 1.8f

        /** An old orogen spreads as it falls; a modest bar, since the blur is the mechanism. */
        const val MIN_BROADER = 1.3f

        /** Relief, in normalized elevation, at which ground counts as belonging to a belt. */
        const val RELIEF_FLOOR = 0.02f

        /** Smaller than this and it is a speck of noise, not a range. */
        const val MIN_BELT_CELLS = 200

        /** Lower than this and it is a swell, not a range. */
        const val MIN_BELT_PEAK = 0.04f
    }
}
