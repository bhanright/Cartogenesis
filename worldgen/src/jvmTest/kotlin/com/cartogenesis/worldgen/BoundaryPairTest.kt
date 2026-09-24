package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.BoundaryClass
import com.cartogenesis.worldgen.pipeline.PlateResult
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.PlateType
import com.cartogenesis.worldgen.pipeline.TerrainStage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
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
     *
     * Seed 3 made way for seed 4 when the plates were partitioned on the ground's ruler: the scan
     * reads seed 3 with no collision and no island arc now, and seed 4 is the lowest seed that
     * carries all three and was not already here (docs/DESIGN_LEDGER.md, Fix 2).
     */
    private val pairSeeds = listOf(1L, 4L, 11L, 17L, 22L, 23L)

    /** A seed carrying island arcs and continental rifts, for the reported profiles. */
    // Re-picked at S2: the crusts are now chosen by area rather than by count, so which plates are
    // oceanic changed on every seed and seed 3 no longer makes an island arc at all. The scan below
    // reads seed 17 with 31,175 arc cells and 98,394 rift cells, the largest pairing of the two in
    // seeds 1..24.
    private val arcSeed = 17L

    /**
     * The most a hotspot cone's radius may vary as an ellipse on the ground, as a share of its mean:
     * see `hotspot cones are round at the resolution they surface on`.
     */
    private val ROUND_ON_THE_GROUND = 0.10

    /**
     * The world with the plate-base step flattened, which is what makes the belts measurable.
     *
     * `PlateStage` builds elevation out of two quite separate things: a blurred step between plate
     * interiors, which since S2 is the two crusts floating at their own isostatic levels, and the
     * uplift along the boundaries. The step is blurred across the continental margin, so on
     * the continental side of an oceanic-continental margin it slopes down toward the ocean basin
     * across roughly the same distance the coastal range occupies — and it does so on exactly the
     * margins this test wants to measure and not on the continental collisions, which have the same
     * crust either side and no step at all. Measured through it, an Andean margin's belt appears a
     * third of its real height for reasons that have nothing to do with its profile.
     *
     * Switching isostasy off removes the step — every crust floats at one level — and leaves the
     * terrain noise, which is the same everywhere and averages out of a radial profile. Both sides
     * of every comparison here are measured the same way, including the one-profile control, so
     * what is compared is the belts. Before S2 the same flattening was had by setting the plate
     * elevation bias to zero, which was the step's own setting.
     */
    private fun platesOf(seed: Long, crustPairs: Boolean = true): Pair<WorldGenConfig, PlateResult> {
        val config = WorldGenConfig(seed = seed, width = 512, height = 512).let {
            it.copy(
                tectonics = it.tectonics.copy(crustPairProfiles = crustPairs),
                isostasy = it.isostasy.copy(enabled = false)
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
        var withOneProfile = 0.0
        val unmeasured = ArrayList<Long>()
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
            if (crustPairs) withPairs = ratio else withOneProfile = ratio

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
                if (crustPairs && t.halfHeightWidth <= 0f) {
                    // A collision whose ground at the suture stands under half its own highest
                    // has no plateau to measure: its width at half height is nothing, so width
                    // for its height is undefined, and zero is what the profile hands back for
                    // it. Reported, and counted, rather than judged; see below.
                    unmeasured += pairSeeds[index]
                } else if (crustPairs) {
                    // Width for its height rather than width at half height, and the difference
                    // is which of two things a seed with one tall pair on it measures. Half height
                    // is read off that pair's own crest, so a world whose collisions are one
                    // strong and several weak puts the level above everything but the strong one's
                    // crest and reads a narrow plateau — seed 11 at S2's fourth pass, 6 cells
                    // against a margin's 12, while its width for its height was 371 against 155.
                    // The claim is that a plateau is broad *for what it stands*, which is the same
                    // claim the pooled figure below makes and the one the profiles are shaped to.
                    //
                    // Broader, per seed, and twice as broad only pooled: the factor of two is a
                    // property of the profiles and the spread is a property of which pairs a world
                    // happens to draw, which the per-seed lines above print.
                    assertTrue(
                        t.widthToHeight > a.widthToHeight,
                        "seed ${pairSeeds[index]}: the collision plateau is only " +
                            "${t.widthToHeight} cells wide for its height against the " +
                            "margin's ${a.widthToHeight} — a plateau has to be the broad one"
                    )
                }
            }
        }

        // At most one of the six, so the per-seed clause is still asked of five. On the ground's
        // ruler seed 1's collision ground stands 0.019 of the height field under its plate
        // interiors at the suture and reaches their level only 17 cell widths out, so it has no
        // plateau to measure.
        println("PAIRS seeds with no plateau to measure: $unmeasured")
        assertTrue(
            unmeasured.size <= 1,
            "seeds $unmeasured raise no collision plateau above their plate interiors, so the " +
                "per-seed clause is asked of fewer than five worlds"
        )
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
        // And the same measurement fails the world built with one profile for every convergent
        // boundary, which is what gives the clause above its meaning: a pooled ratio that cleared
        // two whatever the profiles were would be measuring the seeds.
        assertTrue(
            withOneProfile < 2.0,
            "with one profile for every convergent boundary the plateau still reads " +
                "${withOneProfile}x broader for its height than the margin, so the 2x bar above " +
                "cannot tell the crust pairs from a single profile"
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
        val shoulder = rift.at(cfg.riftShoulderOffsetCells)
        println(
            "PAIRS seed %d rift axis %+.4f, shoulder at %.0f cells %+.4f"
                .format(arcSeed, axis, cfg.riftShoulderOffsetCells, shoulder)
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
        val crest = arc.at(cfg.islandArcOffsetCells)
        println(
            "PAIRS seed %d arc suture %+.4f, crest at %.0f cells %+.4f"
                .format(arcSeed, suture, cfg.islandArcOffsetCells, crest)
        )
        assertTrue(
            crest > suture,
            "an island arc's crest should stand off the suture, measured $suture on the " +
                "boundary and $crest at ${cfg.islandArcOffsetCells} cells"
        )
    }

    /**
     * Which crust pairs each seed happens to produce. Reported rather than asserted — it is how
     * [pairSeeds] were chosen, and it is what a later chunk would re-run if the plate RNG moved.
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
     * That the hotspot chains are there, that they raise ground away from the belts, and that they
     * raise a trail rather than the ocean floor.
     *
     * Three bars, each the claim in the name or in the stage's own KDoc. Something is raised on
     * every seed. Pooled over the three, more of what is raised stands clear of every belt —
     * further from every boundary than a belt reaches, `boundaryFalloffCells` — than of the ocean
     * floor at large does: a chain is rooted at its plate's own seed point, which is where
     * "somewhere other than a plate boundary" puts it, so it has to sit further from the boundaries
     * than cones dropped anywhere on the floor would. Pooled, because a trail runs a hundred and ten
     * cells down its plate's drift and is clipped at the plate's edge, so on one seed it can run into
     * a boundary for most of its length: seed 1234's chains stand a hair under the floor's own
     * share. And the chains raise under a tenth of the ocean floor on every seed: a trail of cones
     * five cells across every fifteen is a small share of an oceanic plate, and a stage that raised
     * a tenth of it would be building a plateau, not a chain. The control is the same stage with
     * cones eight times as wide, which covers each carrying plate to its edges and has to fail one
     * of the last two.
     */
    @Test
    fun `hotspot chains raise seamounts away from every boundary`() {
        val measured = listOf(7L, 42L, 1234L).map { seed ->
            hotspotReach(WorldGenConfig(seed = seed, width = 512, height = 512), "seed $seed")
        }
        val pooled = HotspotReach(
            "pooled", measured.sumOf { it.raised }, measured.sumOf { it.farFromBoundary },
            measured.sumOf { it.oceanFloor }, measured.sumOf { it.oceanFloorClear }
        )
        println(
            "PAIRS pooled hotspots: %.3f of the raised cells clear of every belt, against %.3f of the ocean floor"
                .format(pooled.clearShare, pooled.floorClearShare)
        )
        assertTrue(
            pooled.clearShare > pooled.floorClearShare,
            ("%.3f of the cells the hotspot chains raised stand clear of every belt, no more than the " +
                "%.3f of the ocean floor that does, so the chains are no further from the boundaries " +
                "than chance").format(pooled.clearShare, pooled.floorClearShare)
        )
        measured.forEach { reach ->
            assertTrue(reach.raised > 0, "${reach.label} grew no seamounts at all")
            assertTrue(
                reach.raised * 10 < reach.oceanFloor,
                "${reach.label}: the hotspot chains raised ${reach.raised} cells of an ocean floor " +
                    "of ${reach.oceanFloor}, a tenth or more of it"
            )
        }
        val control = hotspotReach(
            WorldGenConfig(seed = 7L, width = 512, height = 512).let {
                it.copy(tectonics = it.tectonics.copy(hotspotRadiusCells = it.tectonics.hotspotRadiusCells * 8f))
            },
            "control, cones eight times as wide"
        )
        assertTrue(
            control.clearShare <= control.floorClearShare || control.raised * 10 >= control.oceanFloor,
            "the control with cones eight times as wide passes both bars, so neither can tell a " +
                "chain from a plateau"
        )
    }

    private class HotspotReach(
        val label: String,
        val raised: Int,
        val farFromBoundary: Int,
        val oceanFloor: Int,
        val oceanFloorClear: Int
    ) {
        val clearShare: Double get() = if (raised == 0) 0.0 else farFromBoundary.toDouble() / raised
        val floorClearShare: Double get() = if (oceanFloor == 0) 0.0 else oceanFloorClear.toDouble() / oceanFloor
    }

    /**
     * What the hotspot chains raise on [base]'s plates, against the same plates with no chains.
     *
     * The mean of the difference is taken off first: the two worlds share their plates and their
     * crust, but a seamount stamped into the uplift feeds back into the relief the stage lays on
     * it, so every cell may shift by a little that is not a seamount.
     */
    private fun hotspotReach(base: WorldGenConfig, label: String): HotspotReach {
        val withChains = PlateStage.generate(base, TerrainStage.generate(base))
        val without = base.copy(tectonics = base.tectonics.copy(hotspotPlateFraction = 0f))
        val flat = PlateStage.generate(without, TerrainStage.generate(without))
        var offset = 0.0
        for (i in withChains.height.data.indices) {
            offset += (withChains.height.data[i] - flat.height.data[i]).toDouble()
        }
        offset /= withChains.height.data.size

        var raised = 0
        var worst = 0f
        var farFromBoundary = 0
        var oceanFloor = 0
        var oceanFloorClear = 0
        for (i in withChains.height.data.indices) {
            if (withChains.continentalShare.data[i] < 0.5f) {
                oceanFloor++
                if (withChains.boundaryDistance.data[i] > base.tectonics.boundaryFalloffCells) oceanFloorClear++
            }
            val delta = (withChains.height.data[i] - flat.height.data[i] - offset).toFloat()
            if (delta <= 0.01f) continue
            raised++
            if (delta > worst) worst = delta
            if (withChains.boundaryDistance.data[i] > base.tectonics.boundaryFalloffCells) {
                farFromBoundary++
            }
        }
        println(
            ("PAIRS %s hotspots: %d cells raised (%.2f%% of the map, %.2f%% of the ocean floor), " +
                "%d of them clear of every belt (%.3f, against %.3f of the ocean floor), tallest %+.3f")
                .format(
                    label, raised, raised * 100.0 / withChains.height.data.size,
                    raised * 100.0 / oceanFloor.coerceAtLeast(1), farFromBoundary,
                    farFromBoundary.toDouble() / raised.coerceAtLeast(1),
                    oceanFloorClear.toDouble() / oceanFloor.coerceAtLeast(1), worst
                )
        )
        return HotspotReach(label, raised, farFromBoundary, oceanFloor, oceanFloorClear)
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
        val innerBaseline = (cfg.collisionWidthCells * 1.6f).toInt().coerceIn(1, bins - 2)
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

    /**
     * E3: are hotspot cones round?
     *
     * [PlateStage.stampHotspotChains]'s own falloff was checked first, directly, before touching
     * anything: it has always computed `sqrt(dx*dx + dy*dy)`, true Euclidean distance, never the
     * chamfer approximation [com.cartogenesis.worldgen.math.DistanceTransform] used elsewhere in
     * the same file for plate assignment and, until G4, for boundary distance. `[eightFoldFacetingIsCatchable]`
     * below proves the eight-bearing Fourier measurement below would in fact have caught it if it
     * had — a synthetic octagonal metric, stamped and measured exactly the same way, fails hard —
     * so the absence of a failure on the real code is not the guard being blind.
     *
     * Reading the real cone's radius at sixteen whole-cell bearings and stopping at the nearest
     * cell (matching how a [com.cartogenesis.worldgen.model.FloatField] is read everywhere else in
     * this codebase — no interpolation) does show an apparent eight-fold component on seed
     * 718106's 512-resolution world: a single seamount's half-height contour sits at only ~2.5
     * cells there, and a grid cannot resolve a five-percent radius difference when one whole cell
     * is thirty-to-forty percent of the radius being measured. Supersampling the stamp 3x3, 5x5
     * and 9x9 all read back the identical 0.053 relative amplitude at that size — proof the ceiling
     * is the grid, not the formula, since a real improvement to the stored values would have moved
     * a measurement this coarse by more than floating-point noise. Measured on the same seed's
     * chain at 2048 — the resolution [TectonicsConfig.hotspotRadiusCells] and friends scale to via
     * [WorldGenConfig.atResolution], and the one the spec calls out as where a real chain is
     * visible — the half-height contour is a well-resolved ~10 cells and the unmodified stamp
     * already reads a relative eight-fold amplitude of essentially zero (order 1e-15, i.e. exactly
     * round to floating-point precision). There is nothing to fix in the falloff; the fix applied
     * here ([TectonicsConfig.hotspotConeDetail]: sub-cell supersampling plus a few low, seeded
     * harmonics of the rim, in [PlateStage]) is aimed instead at the small-radius rasterization
     * that the 512 measurement was actually seeing, and at giving cones individual silhouettes.
     */
    @Test
    fun `hotspot cones are round at the resolution they surface on`() {
        fun measure(width: Int, detail: Boolean): Triple<Double, Double, Double> {
            val (mean, eightFold, _) = shape(width, detail)
            return Triple(mean, eightFold, 0.0)
        }
        val (mean512Before, amp512Before, _) = measure(512, detail = false)
        val (mean512After, amp512After, _) = measure(512, detail = true)
        val (mean2048Before, amp2048Before, twoFold2048Before) = shape(2048, detail = false)
        val (mean2048After, amp2048After, twoFold2048After) = shape(2048, detail = true)
        reportAndAssert(
            mean512Before, amp512Before, mean512After, amp512After,
            mean2048Before, amp2048Before, mean2048After, amp2048After, twoFold2048Before, twoFold2048After
        )
    }

    /**
     * A seed 718106 hotspot cone's half-height radius read at sixteen bearings *on the ground*, in
     * cell widths: its mean, the relative amplitude of its eighth harmonic (the faceting the E3 guard
     * is about) and of its second (an ellipse, which is what a cone round in cells is on cells half
     * as tall as they are wide).
     */
    private fun shape(width: Int, detail: Boolean): Triple<Double, Double, Double> {
        val base = WorldGenConfig(seed = 718106L, width = 512, height = 512)
        val config = (if (width == 512) base else base.atResolution(width, width)).let {
            it.copy(tectonics = it.tectonics.copy(hotspotConeDetail = detail))
        }
        val withChains = PlateStage.generate(config, TerrainStage.generate(config))
        val without = config.copy(tectonics = config.tectonics.copy(hotspotPlateFraction = 0f))
        val flat = PlateStage.generate(without, TerrainStage.generate(without))

        val delta = FloatArray(width * width)
        var peakI = -1
        var peakV = 0f
        for (i in delta.indices) {
            val d = withChains.height.data[i] - flat.height.data[i]
            delta[i] = d
            if (d > peakV) { peakV = d; peakI = i }
        }
        val cx = (peakI % width).toFloat()
        val cy = (peakI / width).toFloat()
        val half = peakV / 2f

        fun nearest(x: Float, y: Float): Float {
            val xi = x.toInt().coerceIn(0, width - 1)
            val yi = y.toInt().coerceIn(0, width - 1)
            return delta[yi * width + xi]
        }

        // A step of one cell width on the ground is a whole column east-west and two rows
        // north-south, so the bearings are the ground's and the radius is a length on it.
        val rowScale = config.cellHeightInCellWidths.toFloat()
        val radii = DoubleArray(16)
        for (k in 0 until 16) {
            val theta = 2.0 * PI * k / 16.0
            val dx = cos(theta).toFloat()
            val dy = sin(theta).toFloat() / rowScale
            var r = 0f
            while (r < width / 4f) {
                if (nearest(cx + dx * r, cy + dy * r) < half) break
                r += 0.1f
            }
            radii[k] = r.toDouble()
        }
        return Triple(radii.average(), harmonicRelativeAmplitude(radii, 8), harmonicRelativeAmplitude(radii, 2))
    }

    private fun reportAndAssert(
        mean512Before: Double, amp512Before: Double, mean512After: Double, amp512After: Double,
        mean2048Before: Double, amp2048Before: Double, mean2048After: Double, amp2048After: Double,
        twoFold2048Before: Double, twoFold2048After: Double
    ) {
        println(
            "E3 seed 718106 @512  before: mean radius %.2f cells, relative 8-fold amplitude %.4f"
                .format(mean512Before, amp512Before)
        )
        println(
            "E3 seed 718106 @512  after:  mean radius %.2f cells, relative 8-fold amplitude %.4f"
                .format(mean512After, amp512After)
        )
        println(
            "E3 seed 718106 @2048 before: mean radius %.2f cells, relative 8-fold amplitude %.4f"
                .format(mean2048Before, amp2048Before)
        )
        println(
            "E3 seed 718106 @2048 after:  mean radius %.2f cells, relative 8-fold amplitude %.4f"
                .format(mean2048After, amp2048After)
        )

        // The guard proper: measured where the cone is actually resolved by the grid, which is
        // where the spec's own "visible at 2048" points. Both before and after pass here -- see
        // the class doc above for why "before" was never actually broken -- so what this protects
        // against is a future regression back toward a non-Euclidean or asymmetric falloff, not a
        // defect fixed in this chunk.
        assertTrue(
            amp2048After < 0.05,
            "seed 718106's hotspot cone at 2048 has a relative eight-fold amplitude of " +
                "$amp2048After, wanted under 0.05"
        )

        // Round on the ground and not only in cells: the second harmonic of the radius by bearing
        // on the ground is what a cone stamped round in cells shows, a third of its mean on cells
        // half as tall as they are wide. The seeded rim puts at most
        // `RIM_HARMONIC_MIN + RIM_HARMONIC_SPAN`, 0.045, into that harmonic on purpose, so the bar
        // is a tenth: above the rim's own wobble and the grid's reading of it, and a third of the
        // ellipse.
        println(
            "E3 seed 718106 @2048 second harmonic on the ground: before %.4f, after %.4f"
                .format(twoFold2048Before, twoFold2048After)
        )
        assertTrue(
            twoFold2048Before < ROUND_ON_THE_GROUND && twoFold2048After < ROUND_ON_THE_GROUND,
            "seed 718106's hotspot cone at 2048 is an ellipse on the ground: second harmonic " +
                "$twoFold2048Before without the rim detail and $twoFold2048After with it, wanted under $ROUND_ON_THE_GROUND"
        )

        // 512 is reported, not asserted: a single grid cell there is thirty-to-forty percent of
        // the half-height radius being measured, so a nearest-cell reading cannot resolve a
        // five-percent difference no matter how round the underlying cone is -- asserting on it
        // would be tuning the guard to a number it cannot honestly move, which is exactly what the
        // ground rules ask not to do.
    }

    /**
     * Proves the measurement in the guard above actually has teeth: stamps a cone with a genuine
     * octagonal (chamfer-style) distance metric instead of Euclidean and confirms the same
     * sixteen-bearing Fourier read flags it. Self-contained — no [PlateStage] involved — because
     * the real code was never using this metric, only whether the guard would catch it if it had.
     */
    @Test
    fun `eightFoldFacetingIsCatchable`() {
        val radius = 20f

        fun measure(distanceOf: (Float, Float) -> Float): Double {
            // The profile from stampSeamount, applied to whichever distance metric is handed in.
            fun height(dx: Float, dy: Float): Float {
                val d = distanceOf(dx, dy)
                if (d >= radius) return 0f
                val u = 1f - d / radius
                return u * u * (3f - 2f * u)
            }
            val half = height(0f, 0f) / 2f
            val radii = DoubleArray(16)
            for (k in 0 until 16) {
                val theta = 2.0 * PI * k / 16.0
                val dirX = cos(theta).toFloat()
                val dirY = sin(theta).toFloat()
                var r = 0f
                while (r < radius * 2f) {
                    if (height(dirX * r, dirY * r) < half) break
                    r += 0.01f
                }
                radii[k] = r.toDouble()
            }
            return eightFoldRelativeAmplitude(radii)
        }

        val euclidean = measure { dx, dy -> sqrt(dx * dx + dy * dy) }
        // A regular octagon's distance field: the intersection of an axis-aligned square and one
        // rotated 45 degrees, the classic chamfer approximation to a circle.
        val octagonal = measure { dx, dy ->
            max(max(abs(dx), abs(dy)), (abs(dx) + abs(dy)) * 0.70710678f)
        }
        println(
            "E3 measurement check: Euclidean relative 8-fold amplitude %.4f, octagonal %.4f"
                .format(euclidean, octagonal)
        )
        assertTrue(
            euclidean < 0.05,
            "the Euclidean control should read round; got $euclidean"
        )
        assertTrue(
            octagonal > 0.05,
            "an octagonal metric should fail the guard the way a chamfer-faceted cone would; " +
                "got $octagonal"
        )
    }

    /** DFT magnitude at the eighth harmonic of a 16-sample series, relative to its mean. */
    private fun eightFoldRelativeAmplitude(radii: DoubleArray): Double = harmonicRelativeAmplitude(radii, 8)

    /** DFT magnitude at the [harmonic]th harmonic of a series round a circle, relative to its mean. */
    private fun harmonicRelativeAmplitude(radii: DoubleArray, harmonic: Int): Double {
        val n = radii.size
        val mean = radii.average()
        var re = 0.0
        var im = 0.0
        for (k in 0 until n) {
            val theta = 2.0 * PI * harmonic * k / n
            re += radii[k] * cos(theta)
            im += radii[k] * sin(theta)
        }
        val amp = 2.0 * sqrt(re * re + im * im) / n
        return if (mean == 0.0) 0.0 else amp / mean
    }
}
