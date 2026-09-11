package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.BoundaryClass
import com.cartogenesis.worldgen.pipeline.PlateResult
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.PlateType
import com.cartogenesis.worldgen.pipeline.TerrainStage
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Whether a convergent boundary's shape depends on which crusts are colliding.
 *
 * The Andes and the Tibetan plateau are both convergent mountain belts and they are not remotely
 * the same shape: the Andes are a few hundred kilometres wide and seven high, Tibet is well over a
 * thousand wide and five high, flat across the top. That ratio — width against height — is the
 * cheapest thing that separates them, and it is exactly what a generator using one profile for
 * every convergent boundary cannot produce.
 *
 * So this measures each class's mean radial profile away from its boundary, takes the height as
 * the peak above the plate interior and the width as the full width at half that height, and
 * asserts the two ratios differ by at least 2x. With
 * [com.cartogenesis.worldgen.model.TectonicsConfig.crustPairProfiles] off, both classes are built
 * by the same belt profile at the same width, the two ratios coincide, and the assertion fails —
 * which is what gives it meaning.
 *
 * Measured on [PlateResult.height], the belt as tectonics built it, before erosion wears it and
 * before the sea-level percentile turns it into a coastline. Only terrain and plates are run, so
 * the whole file is a couple of seconds.
 */
class BoundaryPairTest {

    /**
     * Seeds recorded by `report which crust pairs each seed produces` below.
     *
     * Every one of them carries all three convergent pairs at once, which is what the guard needs:
     * the comparison is only honest if both belts come out of the same world, since two worlds
     * could differ in belt shape for any number of reasons that have nothing to do with crust
     * pairs. All six are used together — see the pooling note in the guard.
     */
    private val pairSeeds = listOf(1L, 3L, 11L, 17L, 22L, 23L)

    /** A seed carrying island arcs and continental rifts, for the reported profiles. */
    private val arcSeed = 3L

    /**
     * The world with the plate-base step flattened, which is what makes the belts measurable.
     *
     * `PlateStage` builds elevation out of two quite separate things: a blurred step between plate
     * interiors, set by [com.cartogenesis.worldgen.model.TectonicsConfig.plateElevationBias], and
     * the uplift along the boundaries. The step is blurred over `boundaryFalloff / 3` cells, so on
     * the continental side of an oceanic-continental margin it slopes down toward the ocean basin
     * across roughly the same distance the coastal range occupies — and it does so on exactly the
     * margins this test wants to measure and not on the continental collisions, which have the same
     * crust either side and no step at all. Measured through it, an Andean margin's belt appears a
     * third of its real height for reasons that have nothing to do with its profile.
     *
     * Setting the bias to zero removes the step and leaves the terrain noise, which is the same
     * everywhere and averages out of a radial profile. Both sides of every comparison here are
     * measured the same way, including the one-profile control, so what is compared is the belts.
     */
    private fun platesOf(seed: Long, crustPairs: Boolean = true): Pair<WorldGenConfig, PlateResult> {
        val config = WorldGenConfig(seed = seed, width = 512, height = 512).let {
            it.copy(
                tectonics = it.tectonics.copy(
                    crustPairProfiles = crustPairs,
                    plateElevationBias = 0f
                )
            )
        }
        return config to PlateStage.generate(config, TerrainStage.generate(config))
    }

    /** The shipped world, unflattened — used only for the pair-occurrence scan. */
    private fun defaultPlatesOf(seed: Long): PlateResult {
        val config = WorldGenConfig(seed = seed, width = 512, height = 512)
        return PlateStage.generate(config, TerrainStage.generate(config))
    }

    @Test
    fun `an Andean margin and a collision plateau are different shapes`() {
        var withPairs = 0.0
        listOf(true, false).forEach { crustPairs ->
            val label = if (crustPairs) "crust pairs" else "one profile "
            val worlds = pairSeeds.map { platesOf(it, crustPairs) }

            // Pooled across every recorded seed rather than measured seed by seed, because a
            // belt's height is proportional to how hard its pair is converging and its width is
            // not. One seed's collision pairs can be closing twice as fast as its subduction
            // pairs, which moves the width-to-height ratio of both belts by that factor for
            // reasons that have nothing to do with their shapes — measured per seed the figure
            // swings between 0.8x and 4.5x on correct code. Pooling six worlds averages the
            // convergence rates out and leaves the profiles, which is what is being compared.
            val andes = profileOf(worlds, BoundaryClass.ANDEAN_MARGIN, Crust.CONTINENTAL)
            val tibet = profileOf(worlds, BoundaryClass.COLLISION_PLATEAU, Crust.CONTINENTAL)
            println("PAIRS pooled $label ${andes.describe("ANDEAN_MARGIN")}")
            println("PAIRS pooled $label ${tibet.describe("COLLISION_PLATEAU")}")

            val ratio = tibet.widthToHeight / andes.widthToHeight
            println(
                ("PAIRS pooled %s width:height %.1f (margin) against %.1f (plateau), " +
                    "plateau %.2fx broader for its height")
                    .format(label, andes.widthToHeight, tibet.widthToHeight, ratio)
            )
            if (crustPairs) withPairs = ratio

            // Per seed: the ratio is reported, because of the spread explained above, but the
            // widths themselves are not proportional to convergence rate and hold on every seed.
            worlds.forEachIndexed { index, world ->
                val a = profileOf(listOf(world), BoundaryClass.ANDEAN_MARGIN, Crust.CONTINENTAL)
                val t = profileOf(listOf(world), BoundaryClass.COLLISION_PLATEAU, Crust.CONTINENTAL)
                println(
                    "PAIRS seed %d %s margin %.1f plateau %.1f -> %.2fx, FWHM %.0f against %.0f cells"
                        .format(
                            pairSeeds[index], label, a.widthToHeight, t.widthToHeight,
                            t.widthToHeight / a.widthToHeight,
                            a.halfHeightWidth, t.halfHeightWidth
                        )
                )
                if (crustPairs) {
                    assertTrue(
                        t.halfHeightWidth >= 2f * a.halfHeightWidth,
                        "seed ${pairSeeds[index]}: the collision plateau is only " +
                            "${t.halfHeightWidth} cells across at half height against the " +
                            "margin's ${a.halfHeightWidth} — a plateau has to be the broad one"
                    )
                }
            }
        }

        // A plateau that is not at least twice as broad for its height as a coastal range is the
        // same belt under two names, which is exactly what this chunk replaced. Stated as a signed
        // ratio rather than a magnitude, because with one profile the *margin* comes out the
        // broader of the two for its height — the old code gave an oceanic-continental boundary
        // four fifths of the height at the same width — and a guard on the magnitude alone would
        // have accepted that as a difference.
        assertTrue(
            withPairs >= 2.0,
            "an Andean margin and a collision plateau came out the same shape: the plateau is " +
                "only ${withPairs}x broader for its height, wanted at least 2x"
        )
    }

    @Test
    fun `island arcs and continental rifts have the relief their names promise`() {
        val world = listOf(platesOf(arcSeed))

        val arc = profileOf(world, BoundaryClass.ISLAND_ARC, Crust.OCEANIC)
        val rift = profileOf(world, BoundaryClass.CONTINENTAL_RIFT, Crust.CONTINENTAL)
        val ridge = profileOf(world, BoundaryClass.OCEAN_RIDGE, Crust.OCEANIC)
        println("PAIRS seed $arcSeed ${arc.describe("ISLAND_ARC")}")
        println("PAIRS seed $arcSeed ${rift.describe("CONTINENTAL_RIFT")}")
        println("PAIRS seed $arcSeed ${ridge.describe("OCEAN_RIDGE")}")

        val cfg = world.first().first.tectonics

        // A rift is a trough with raised shoulders, so its profile has to go down on the axis and
        // come back up at the shoulder distance. Nothing else this stage builds has that shape.
        assertTrue(rift.cells > 0, "seed $arcSeed has no continental rift to measure")
        val axis = rift.at(0f)
        val shoulder = rift.at(cfg.riftShoulderOffset)
        println(
            "PAIRS seed %d rift axis %+.4f, shoulder at %.0f cells %+.4f"
                .format(arcSeed, axis, cfg.riftShoulderOffset, shoulder)
        )
        assertTrue(axis < 0f, "a continental rift's axis should be a trough, measured $axis")
        assertTrue(
            shoulder > axis,
            "a continental rift should rise from its axis to its shoulders, " +
                "measured $axis on the axis and $shoulder at the shoulder"
        )

        // An island arc's crest stands off the suture on the overriding plate, so the profile at
        // the arc's own distance has to beat the profile on the boundary line itself.
        assertTrue(arc.cells > 0, "seed $arcSeed has no island arc to measure")
        val suture = arc.at(0f)
        val crest = arc.at(cfg.islandArcOffset)
        println(
            "PAIRS seed %d arc suture %+.4f, crest at %.0f cells %+.4f"
                .format(arcSeed, suture, cfg.islandArcOffset, crest)
        )
        assertTrue(
            crest > suture,
            "an island arc's crest should stand off the suture, measured $suture on the " +
                "boundary and $crest at ${cfg.islandArcOffset} cells"
        )
    }

    /**
     * Which crust pairs each seed happens to produce. Reported rather than asserted — it is how
     * [pairSeed] was chosen, and it is what a later chunk would re-run if the plate RNG moved.
     */
    @Test
    fun `report which crust pairs each seed produces`() {
        (1L..24L).forEach { seed ->
            val plates = defaultPlatesOf(seed)
            val counts = IntArray(BoundaryClass.entries.size)
            plates.nearestBoundaryClass.forEach { if (it >= 0) counts[it]++ }
            val present = BoundaryClass.entries
                .filter { counts[it.ordinal] > 0 }
                .joinToString(" ") { "${it.name}=${counts[it.ordinal]}" }
            val allThree = BoundaryClass.entries
                .take(3)
                .all { counts[it.ordinal] > 0 }
            println("PAIRS scan seed $seed${if (allThree) " ALL-THREE-CONVERGENT" else ""} $present")
        }
    }

    /**
     * That the hotspot chains are actually there, and how much ground they raise.
     *
     * They are a bonus rather than the chunk's point, so this asserts only that they exist and do
     * something, and reports the size of it — a chain that raised a tenth of the ocean floor would
     * be a bug, and so would one that raised nothing.
     */
    @Test
    fun `hotspot chains raise seamounts away from every boundary`() {
        listOf(7L, 42L, 1234L).forEach { seed ->
            val base = WorldGenConfig(seed = seed, width = 512, height = 512)
            val withChains = PlateStage.generate(base, TerrainStage.generate(base))
            val without = base.copy(tectonics = base.tectonics.copy(hotspotPlateFraction = 0f))
            val flat = PlateStage.generate(without, TerrainStage.generate(without))

            // Both fields are normalized over their own range before they come back, so compare
            // against the mean difference rather than against zero: if the extra uplift moved the
            // range at all, every cell shifts by a constant that is not a seamount.
            var offset = 0.0
            for (i in withChains.height.data.indices) {
                offset += (withChains.height.data[i] - flat.height.data[i]).toDouble()
            }
            offset /= withChains.height.data.size

            var raised = 0
            var worst = 0f
            var farFromBoundary = 0
            for (i in withChains.height.data.indices) {
                val delta = (withChains.height.data[i] - flat.height.data[i] - offset).toFloat()
                if (delta <= 0.01f) continue
                raised++
                if (delta > worst) worst = delta
                if (withChains.boundaryDistance.data[i] > base.tectonics.boundaryFalloff) {
                    farFromBoundary++
                }
            }
            println(
                "PAIRS seed %d hotspots: %d cells raised (%.2f%% of the map), %d of them clear of every belt, tallest %+.3f"
                    .format(
                        seed, raised, raised * 100.0 / withChains.height.data.size,
                        farFromBoundary, worst
                    )
            )
            assertTrue(raised > 0, "seed $seed grew no seamounts at all")
        }
    }

    private enum class Crust { CONTINENTAL, OCEANIC }

    /**
     * The mean height at each whole cell of distance from a boundary of one class, reduced to the
     * two numbers the guard compares.
     */
    private class Profile(
        val cells: Int,
        val baseline: Float,
        val peak: Float,
        val peakDistance: Float,
        val troughDepth: Float,
        val troughDistance: Float,
        val halfHeightWidth: Float,
        /** Mean height above the plate interior at each whole cell of distance; NaN where thin. */
        val values: FloatArray = FloatArray(0)
    ) {
        /** The profile that many cells out from the boundary, or 0 where too few cells landed. */
        fun at(distance: Float): Float {
            val d = distance.toInt()
            if (d !in values.indices) return 0f
            return if (values[d].isNaN()) 0f else values[d]
        }

        /**
         * Width against height. Dimensionally this is cells per unit of normalized elevation; only
         * the ratio between two classes measured the same way is meaningful, which is all the
         * guard asks of it.
         */
        val widthToHeight: Double
            get() = if (peak <= 0f) 0.0 else halfHeightWidth.toDouble() / peak.toDouble()

        fun describe(name: String) =
            "%-18s %6d cells, peak %+.4f at %.0f, trough %+.4f at %.0f, FWHM %.0f cells, width:height %.1f"
                .format(
                    name, cells, peak, peakDistance, troughDepth, troughDistance,
                    halfHeightWidth, widthToHeight
                )
    }

    /**
     * Bins cells of one boundary class by their distance from it and averages the height in each
     * bin, then reads the belt's height off the peak above the plate interior and its width off
     * the full width at half that height.
     *
     * Restricted to one kind of crust because the profile is asymmetric on purpose: an Andean
     * margin has a trench on its oceanic side and a range on its continental one, and averaging
     * the two together would measure neither.
     */
    private fun profileOf(
        worlds: List<Pair<WorldGenConfig, PlateResult>>,
        cls: BoundaryClass,
        crust: Crust
    ): Profile {
        val cfg = worlds.first().first.tectonics
        val bins = 160
        val total = DoubleArray(bins)
        val count = IntArray(bins)
        var cells = 0

        // The plate interior, taken over every cell that far from any boundary rather than over
        // this class's own far tail. A class whose belts all happen to sit in one part of a world
        // has a far tail of a few hundred cells that is not a plate interior at all, and reading
        // the reference level off it moves the measured height of the belt by more than the belt.
        // With the plate-base step flattened (see [platesOf]) the interior is the terrain mean,
        // which is the same everywhere, so one figure serves every class.
        val innerBaseline = (cfg.collisionWidth * 1.6f).toInt().coerceIn(1, bins - 2)
        var baselineTotal = 0.0
        var baselineCount = 0L
        worlds.forEach { (_, plates) ->
            for (i in plates.plateId.indices) {
                if (plates.boundaryDistance.data[i] < innerBaseline) continue
                baselineTotal += plates.height.data[i].toDouble()
                baselineCount++
            }
        }
        val baseline =
            if (baselineCount == 0L) 0f else (baselineTotal / baselineCount).toFloat()

        val wanted = if (crust == Crust.CONTINENTAL) PlateType.CONTINENTAL else PlateType.OCEANIC
        worlds.forEach { (_, plates) ->
            for (i in plates.plateId.indices) {
                if (plates.nearestBoundaryClass[i] != cls.ordinal) continue
                if (plates.plates[plates.plateId[i]].type != wanted) continue
                cells++
                val bin = plates.boundaryDistance.data[i].toInt()
                if (bin !in 0 until bins) continue
                total[bin] += plates.height.data[i].toDouble()
                count[bin]++
            }
        }
        if (cells == 0) return Profile(0, 0f, 0f, 0f, 0f, 0f, 0f)

        fun at(d: Int): Float? =
            if (count[d] < 24) null else (total[d] / count[d]).toFloat() - baseline

        val values = FloatArray(bins) { at(it) ?: Float.NaN }

        var peak = 0f
        var peakDistance = 0f
        var trough = 0f
        var troughDistance = 0f
        for (d in 0 until innerBaseline) {
            val v = at(d) ?: continue
            if (v > peak) { peak = v; peakDistance = d.toFloat() }
            if (v < trough) { trough = v; troughDistance = d.toFloat() }
        }

        // Full width at half maximum, taken as twice the distance at which the mean profile first
        // falls below half its peak. A belt is measured from its boundary outward, so the distance
        // is a half-width and the width is twice it.
        var half = 0f
        if (peak > 0f) {
            var d = 0
            while (d < innerBaseline) {
                val v = at(d)
                if (v != null && v < peak / 2f) break
                d++
            }
            half = d.toFloat()
        }

        return Profile(
            cells, baseline, peak, peakDistance, trough, troughDistance, half * 2f, values
        )
    }
}
