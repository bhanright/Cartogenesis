package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.VegetationDensity
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Whether the cover this generator puts on its ground is Earth's cover, and whether the function
 * that puts it there behaves like a function of a water balance and a growing season.
 *
 * Two kinds of clause, and they are kept apart on purpose. The **property** clauses evaluate
 * `VegetationDensity` at points chosen from Whittaker's (1975) diagram and from the definition of
 * monotonicity; they need no world, they cannot be moved by a change to the climate, and they are
 * what says the relation is the relation the file claims. The **share** clauses generate four
 * worlds and count land, and they are wide for the reason `ColdBiomeShareTest` gives at length: a
 * generated world chooses where its continents sit, and that is decided before any climate runs.
 *
 * See docs/DESIGN_LEDGER.md, W4.
 */
class VegetationDensityTest : BorrowsSharedWorlds() {

    private companion object {

        /** The four standard seeds at the per-merge grid, as every other pooled climate guard. */
        val SEEDS = listOf(7L, 42L, 1234L, 99L)
        const val GRID = 512

        /**
         * The density below which a cell counts as barren or sparsely vegetated.
         *
         * A tenth, and it is not this file's number: the land-cover class *barren or sparsely
         * vegetated* is defined as ground under a tenth vegetated, which is the same tenth
         * `ClimateTint` already cites for the bare end of its own bands. So the count below and
         * Earth's figure beside it are counts of the same thing.
         */
        const val BARREN_DENSITY = 0.1f

        /**
         * And the density at which a cell counts as forest.
         *
         * Three fifths, the forestry convention for a *closed* canopy, which `ClimateTint`'s
         * canopy table is already written against.
         */
        const val FOREST_DENSITY = 0.6f

        /**
         * Earth's share of its land surface that is bare, sparsely vegetated or under permanent
         * ice.
         *
         * About 26%. From the MODIS MCD12Q1 IGBP land-cover census, whose class 16 *barren or
         * sparsely vegetated* takes roughly a sixth of the 149 million km2 land surface and whose
         * class 15 *snow and ice* takes roughly a tenth of it — Antarctica and Greenland being
         * nearly all of that tenth. The two classes together are what a density under
         * [BARREN_DENSITY] is: ground with less than a tenth of a cover on it, for want of water,
         * for want of a growing season, or because it is buried.
         *
         * Both are counted against the *whole* land surface and not against ice-free land, because
         * the ice is in the numerator.
         */
        const val EARTH_BARREN_AND_ICE_SHARE = 0.26f

        /**
         * Earth's forest share of land: 31%, FAO's *Global Forest Resources Assessment 2020* —
         * 4.06 billion hectares of forest against 13.0 billion hectares of land.
         *
         * A looser definition than [FOREST_DENSITY]'s: FAO counts any stand over a tenth of a
         * hectare with more than a tenth canopy cover, which takes in open woodland that a
         * three-fifths density line does not. Earth's share of land at a density of three fifths
         * or more is therefore somewhat under 31%, and this bar is if anything generous — which is
         * stated rather than corrected for, because no census this audit has counts cover at that
         * threshold directly.
         */
        const val EARTH_FOREST_SHARE = 0.31f

        /**
         * Earth's permafrost share of ice-free land.
         *
         * 17%. Zhang and others (1999) measure the permafrost region of the Northern Hemisphere at
         * 22.79 million km2, 23.9% of the hemisphere's exposed land; the Southern Hemisphere's
         * permafrost outside the Antarctic ice sheet is a rounding error. Against a global
         * ice-free land area of about 134 million km2 — the 149 million km2 land surface less the
         * 15 million under permanent ice — that is 17.0%.
         *
         * Ice-free land is the denominator rather than all land because a cell under an ice sheet
         * has no ground cover to speak of either way, and comparing a world with more ice than
         * Earth against a figure that counts Antarctica as permafrost-free would be a comparison
         * of ice sheets wearing a permafrost label.
         */
        const val EARTH_PERMAFROST_SHARE = 0.17f

        /**
         * How many times Earth's share a pooled figure may be, either way.
         *
         * Three, the factor `ColdBiomeShareTest` derives and for its reasons: where a generated
         * world puts its continents decides how much cold or dry ground it has before any climate
         * runs, and Earth's own arrangement is not the median of the possible ones. A factor of
         * three still catches what these guards exist for — a field that is all zeros, all ones,
         * or a step function with no middle.
         */
        const val EARTH_FACTOR = 3f

        /**
         * The least a single seed may fall to, as a share of Earth's own figure.
         *
         * A tenth. Pooling four worlds hides a world that produced none of something, and the
         * failure this floor catches is exactly that: a seed whose land is all tropical, or all
         * polar, and whose contribution to the pool is a rounding error. It is a floor and not a
         * band, because a seed that is *all* forest is a fact about that seed's continents while a
         * seed with no forest at all is a fact about the function.
         */
        const val PER_SEED_FLOOR = 0.1f

        /**
         * How much of the continuous-permafrost zone Koppen may call forest before the classifier
         * would have to be taught about the mask.
         *
         * A quarter. Below it the disagreement is the ordinary margin between two classifications
         * of the same ground - Koppen's D group asks about a coldest month and this mask asks
         * about an annual mean, and no two thresholds on different statistics agree cell for cell.
         * Above it the biome view would be drawing a forest over ground that cannot root one often
         * enough for a reader to notice, and that is a redesign of `classify` rather than a
         * finding.
         */
        const val MOST_OF_A_ZONE = 0.25f

        /**
         * One of the four worlds, borrowed from `SharedWorlds` by every clause that reads it rather
         * than kept here, so that each is generated once and checked after every clause.
         */
        fun world(seed: Long): WorldMap =
            SharedWorlds.world(WorldGenConfig(seed = seed, width = GRID, height = GRID))

        /** The same world with the field switched off, for the control clauses. */
        fun withoutVegetation(seed: Long): WorldMap {
            val base = WorldGenConfig(seed = seed, width = GRID, height = GRID)
            return SharedWorlds.world(base.copy(vegetation = base.vegetation.copy(enabled = false)))
        }
    }

    /** One world's counts, kept as counts so several worlds pool honestly. */
    private class Counts(
        val land: Long,
        val iceFreeLand: Long,
        val barrenOrIce: Long,
        val forest: Long,
        val permafrostAny: Long,
        val permafrostContinuous: Long
    ) {
        operator fun plus(other: Counts) = Counts(
            land + other.land,
            iceFreeLand + other.iceFreeLand,
            barrenOrIce + other.barrenOrIce,
            forest + other.forest,
            permafrostAny + other.permafrostAny,
            permafrostContinuous + other.permafrostContinuous
        )
    }

    private fun countsOf(world: WorldMap): Counts {
        var land = 0L
        var iceFree = 0L
        var barren = 0L
        var forest = 0L
        var anyPermafrost = 0L
        var continuous = 0L
        for (cell in world.sea.isLand.indices) {
            if (!world.sea.isLand[cell]) continue
            land++
            val density = world.climate.vegetationDensity.data[cell]
            if (density < BARREN_DENSITY) barren++
            val underIce = world.climate.biome[cell] == Biome.ICE_SHEET
            if (!underIce) {
                iceFree++
                if (density >= FOREST_DENSITY) forest++
                when (VegetationDensity.Permafrost.ofOrdinal(world.climate.permafrost[cell].toInt())) {
                    VegetationDensity.Permafrost.CONTINUOUS -> {
                        anyPermafrost++
                        continuous++
                    }

                    VegetationDensity.Permafrost.DISCONTINUOUS -> anyPermafrost++
                    VegetationDensity.Permafrost.NONE -> Unit
                }
            }
        }
        return Counts(land, iceFree, barren, forest, anyPermafrost, continuous)
    }

    @Test
    fun `the share of land that is bare or under ice is Earth's`() {
        var pooled = Counts(0, 0, 0, 0, 0, 0)
        val perSeed = ArrayList<Pair<Long, Float>>()
        SEEDS.forEach { seed ->
            val counts = countsOf(world(seed))
            pooled += counts
            val share = counts.barrenOrIce.toFloat() / counts.land
            perSeed.add(seed to share)
            println(
                "VEGETATION seed %d: barren-or-ice %.1f%% of land (%d of %d)"
                    .format(seed, share * 100, counts.barrenOrIce, counts.land)
            )
        }
        val share = pooled.barrenOrIce.toFloat() / pooled.land
        println(
            "VEGETATION pooled barren-or-ice %.1f%% of land against Earth's %.0f%%, x%.2f"
                .format(share * 100, EARTH_BARREN_AND_ICE_SHARE * 100, share / EARTH_BARREN_AND_ICE_SHARE)
        )
        assertTrue(
            share <= EARTH_BARREN_AND_ICE_SHARE * EARTH_FACTOR,
            "pooled barren-or-ice share $share is more than $EARTH_FACTOR times Earth's " +
                "$EARTH_BARREN_AND_ICE_SHARE"
        )
        assertTrue(
            share >= EARTH_BARREN_AND_ICE_SHARE / EARTH_FACTOR,
            "pooled barren-or-ice share $share is less than a $EARTH_FACTOR-th of Earth's " +
                "$EARTH_BARREN_AND_ICE_SHARE"
        )
        perSeed.forEach { (seed, seedShare) ->
            assertTrue(
                seedShare >= EARTH_BARREN_AND_ICE_SHARE * PER_SEED_FLOOR,
                "seed $seed has almost no bare ground: $seedShare"
            )
        }
    }

    @Test
    fun `the share of land under closed forest is Earth's`() {
        var pooled = Counts(0, 0, 0, 0, 0, 0)
        val perSeed = ArrayList<Pair<Long, Float>>()
        SEEDS.forEach { seed ->
            val counts = countsOf(world(seed))
            pooled += counts
            val share = counts.forest.toFloat() / counts.iceFreeLand
            perSeed.add(seed to share)
            println(
                "VEGETATION seed %d: forest %.1f%% of ice-free land (%d of %d)"
                    .format(seed, share * 100, counts.forest, counts.iceFreeLand)
            )
        }
        val share = pooled.forest.toFloat() / pooled.iceFreeLand
        println(
            "VEGETATION pooled forest %.1f%% of ice-free land against Earth's %.0f%%, x%.2f"
                .format(share * 100, EARTH_FOREST_SHARE * 100, share / EARTH_FOREST_SHARE)
        )
        assertTrue(
            share <= EARTH_FOREST_SHARE * EARTH_FACTOR,
            "pooled forest share $share is more than $EARTH_FACTOR times Earth's $EARTH_FOREST_SHARE"
        )
        assertTrue(
            share >= EARTH_FOREST_SHARE / EARTH_FACTOR,
            "pooled forest share $share is less than a $EARTH_FACTOR-th of Earth's $EARTH_FOREST_SHARE"
        )
        perSeed.forEach { (seed, seedShare) ->
            assertTrue(
                seedShare >= EARTH_FOREST_SHARE * PER_SEED_FLOOR,
                "seed $seed has almost no forest: $seedShare"
            )
        }
    }

    @Test
    fun `the share of land over permafrost is Earth's`() {
        var pooled = Counts(0, 0, 0, 0, 0, 0)
        SEEDS.forEach { seed ->
            val counts = countsOf(world(seed))
            pooled += counts
            println(
                ("VEGETATION seed %d: permafrost %.1f%% of ice-free land, of it continuous %.1f%%; " +
                    "ice %.1f%% of land")
                    .format(
                        seed,
                        counts.permafrostAny * 100f / counts.iceFreeLand,
                        counts.permafrostContinuous * 100f / counts.iceFreeLand,
                        (counts.land - counts.iceFreeLand) * 100f / counts.land
                    )
            )
        }
        val share = pooled.permafrostAny.toFloat() / pooled.iceFreeLand
        println(
            "VEGETATION pooled permafrost %.1f%% of ice-free land against Earth's %.0f%%, x%.2f"
                .format(share * 100, EARTH_PERMAFROST_SHARE * 100, share / EARTH_PERMAFROST_SHARE)
        )
        assertTrue(
            share <= EARTH_PERMAFROST_SHARE * EARTH_FACTOR,
            "pooled permafrost share $share is more than $EARTH_FACTOR times Earth's " +
                "$EARTH_PERMAFROST_SHARE"
        )
        assertTrue(
            share >= EARTH_PERMAFROST_SHARE / EARTH_FACTOR,
            "pooled permafrost share $share is less than a $EARTH_FACTOR-th of Earth's " +
                "$EARTH_PERMAFROST_SHARE"
        )
    }

    @Test
    fun `where the permafrost mask and Koppen disagree, and by how much`() {
        // The one place the two classifications could be made to read each other, measured rather
        // than wired. Continuous permafrost thaws a few tens of centimetres a summer and a tree
        // cannot root in that, so a cell the mask calls continuous and Koppen calls closed forest
        // is a disagreement a reader could see: the biome view would draw taiga where no taiga can
        // stand. The chunk was asked to report the size of it and not to redraw the classifier, so
        // this asserts only that the disagreement is small enough for that to have been the right
        // call, and prints the figure either way.
        //
        // The density already answers the disagreement for anything that reads *it*: the cap holds
        // those cells to two fifths whatever name they carry. What no cap can do is change the
        // name, and changing the name is `classify`'s business.
        val forests = setOf(
            Biome.TAIGA, Biome.TEMPERATE_FOREST, Biome.TEMPERATE_RAINFOREST,
            Biome.TROPICAL_SEASONAL_FOREST, Biome.TROPICAL_RAINFOREST, Biome.MONSOON_FOREST
        )
        var continuous = 0L
        var forestOverContinuous = 0L
        SEEDS.forEach { seed ->
            val world = world(seed)
            var seedContinuous = 0L
            var seedForest = 0L
            for (cell in world.sea.isLand.indices) {
                if (!world.sea.isLand[cell]) continue
                if (VegetationDensity.Permafrost.ofOrdinal(world.climate.permafrost[cell].toInt()) !=
                    VegetationDensity.Permafrost.CONTINUOUS
                ) {
                    continue
                }
                seedContinuous++
                if (world.climate.biome[cell] in forests) seedForest++
            }
            continuous += seedContinuous
            forestOverContinuous += seedForest
            println(
                "PERMAFROST seed %d: %d cells of continuous permafrost, %d of them (%.1f%%) classed forest"
                    .format(seed, seedContinuous, seedForest, seedForest * 100f / seedContinuous)
            )
        }
        val disagreement = forestOverContinuous.toFloat() / continuous
        println(
            ("PERMAFROST pooled: %.1f%% of continuous-permafrost land is classed as forest by " +
                "Koppen, which the density caps at %.1f and the classifier does not see")
                .format(disagreement * 100, VegetationDensity.PERMAFROST_CANOPY_CEILING)
        )
        assertTrue(
            disagreement < MOST_OF_A_ZONE,
            ("Koppen calls %.1f%% of the continuous-permafrost land forest, which is too much of " +
                "it to leave as a finding: the classifier would have to read the mask")
                .format(disagreement * 100)
        )
    }

    @Test
    fun `density rises with the water balance and with the growing season`() {
        // A property of the function and not of any world: nothing is generated here, and no
        // change to the climate can move it.
        //
        // Monotone in the water balance at a fixed biotemperature, and monotone in the
        // biotemperature at a fixed **balance** - the dryness index held while the growing season
        // moves, not the rainfall held. Holding the rainfall instead would be a different claim
        // and a false one: warming a cell raises its potential evapotranspiration, so at ten
        // millimetres a year a warmer cell is a drier one and its cover falls. That is the
        // relation behaving correctly, and it is why a desert is hot.
        val biotemperatures = listOf(0.5f, 1f, 3f, 8f, 15f, 25f, 30f)
        val drynessIndices = listOf(0.1f, 0.25f, 0.5f, 1f, 2f, 4f, 8f, 16f)

        biotemperatures.forEach { biotemperature ->
            val potentialMm = VegetationDensity.potentialEvapotranspirationMm(biotemperature)
            var previous = Float.MAX_VALUE
            drynessIndices.forEach { dryness ->
                val here = VegetationDensity.density(biotemperature, potentialMm / dryness)
                assertTrue(
                    here <= previous,
                    "density rose from $previous to $here at biotemperature $biotemperature " +
                        "when the dryness index rose to $dryness"
                )
                assertTrue(here in 0f..1f, "density $here is outside 0..1")
                previous = here
            }
        }

        drynessIndices.forEach { dryness ->
            var previous = -1f
            biotemperatures.forEach { biotemperature ->
                val potentialMm = VegetationDensity.potentialEvapotranspirationMm(biotemperature)
                val here = VegetationDensity.density(biotemperature, potentialMm / dryness)
                assertTrue(
                    here >= previous,
                    "density fell from $previous to $here at a dryness index of $dryness " +
                        "when the biotemperature rose to $biotemperature"
                )
                previous = here
            }
        }
    }

    @Test
    fun `the function agrees with Whittaker's diagram about which biome is denser`() {
        // Whittaker (1975), Communities and Ecosystems, plots biomes against mean annual
        // temperature and annual precipitation. Each entry here is the centre of one of his boxes,
        // read off that diagram, with the year's swing chosen from the same climate rather than
        // measured: a tropical year is nearly flat and a boreal one swings hard, which is what
        // biotemperature is there to notice.
        class Box(val name: String, val meanC: Float, val swingC: Float, val millimetres: Float)

        val forests = listOf(
            Box("tropical rainforest", 25f, 4f, 3_000f),
            Box("temperate rainforest", 12f, 14f, 2_500f),
            Box("temperate forest", 10f, 24f, 1_000f),
            Box("boreal forest", -3f, 35f, 450f)
        )
        val open = listOf(
            Box("woodland and shrubland", 15f, 20f, 600f),
            Box("temperate grassland", 8f, 28f, 400f),
            Box("savanna", 25f, 6f, 900f)
        )
        val sparse = listOf(Box("tundra", -12f, 32f, 250f))
        val bare = listOf(Box("subtropical desert", 25f, 14f, 100f))

        fun densityOf(box: Box): Float {
            val biotemperature = VegetationDensity.biotemperatureC(
                box.meanC, box.meanC + box.swingC / 2f, box.meanC - box.swingC / 2f
            )
            return VegetationDensity.density(biotemperature, box.millimetres).also {
                println(
                    "WHITTAKER %-24s biotemperature %5.1f C, %5.0f mm -> density %.3f"
                        .format(box.name, biotemperature, box.millimetres, it)
                )
            }
        }

        // Asserted as the diagram's own four groups and not as a total order over nine boxes.
        // Whittaker's picture says which community stands where, not how much cover each carries,
        // and it does not claim that a tropical rainforest is denser than a temperate one - the
        // function says the temperate one is, because its potential evapotranspiration is half the
        // tropical one's at nearly the same rainfall, and that is Budyko's curve answering
        // correctly rather than the diagram being contradicted. What the diagram does claim is
        // that every one of these four groups is thinner than the one above it, and that is a
        // claim a constant field or a step function fails.
        val forestDensities = forests.map(::densityOf)
        val openDensities = open.map(::densityOf)
        val sparseDensities = sparse.map(::densityOf)
        val bareDensities = bare.map(::densityOf)

        forests.zip(forestDensities).forEach { (box, density) ->
            assertTrue(density >= FOREST_DENSITY, "${box.name} is not closed forest: $density")
        }
        assertTrue(
            openDensities.max() < forestDensities.min(),
            "an open woodland is as dense as a forest: $openDensities against $forestDensities"
        )
        assertTrue(
            openDensities.min() > sparseDensities.max(),
            "tundra is as dense as an open woodland: $sparseDensities against $openDensities"
        )
        assertTrue(
            sparseDensities.min() > bareDensities.max(),
            "a desert is as dense as tundra: $bareDensities against $sparseDensities"
        )
        assertTrue(
            bareDensities.max() < BARREN_DENSITY,
            "a subtropical desert should be barren: $bareDensities"
        )
    }

    @Test
    fun `with the field off the three land shares are outside Earth's bars`() {
        // The control every share clause above is shown failing against, measured rather than
        // asserted to be obvious: `VegetationConfig.enabled` off leaves the field at zero, so
        // every land cell is barren, no cell is forest, and the permafrost mask is empty.
        var pooled = Counts(0, 0, 0, 0, 0, 0)
        SEEDS.forEach { pooled += countsOf(withoutVegetation(it)) }
        val barren = pooled.barrenOrIce.toFloat() / pooled.land
        val forest = pooled.forest.toFloat() / pooled.iceFreeLand
        val permafrost = pooled.permafrostAny.toFloat() / pooled.iceFreeLand
        println(
            "VEGETATION control (field off): barren-or-ice %.1f%%, forest %.1f%%, permafrost %.1f%%"
                .format(barren * 100, forest * 100, permafrost * 100)
        )
        assertTrue(
            barren > EARTH_BARREN_AND_ICE_SHARE * EARTH_FACTOR,
            "the control should fail the barren bar, and reads $barren"
        )
        assertTrue(
            forest < EARTH_FOREST_SHARE / EARTH_FACTOR,
            "the control should fail the forest bar, and reads $forest"
        )
        assertTrue(
            permafrost < EARTH_PERMAFROST_SHARE / EARTH_FACTOR,
            "the control should fail the permafrost bar, and reads $permafrost"
        )
    }

    @Test
    fun `continuous permafrost caps the cover and discontinuous permafrost does not`() {
        // A boreal year warm and wet enough for closed forest, which is what the Siberian larch
        // stands in: the cap has to be the permafrost's doing and not the climate's.
        val biotemperature = 5f
        val open = VegetationDensity.density(biotemperature, 600f)
        assertTrue(open > VegetationDensity.PERMAFROST_CANOPY_CEILING, "the test case is too thin: $open")

        assertTrue(
            VegetationDensity.density(
                biotemperature, 600f, VegetationDensity.Permafrost.DISCONTINUOUS
            ) == open,
            "discontinuous permafrost should not cap the cover"
        )
        assertTrue(
            VegetationDensity.density(
                biotemperature, 600f, VegetationDensity.Permafrost.CONTINUOUS
            ) == VegetationDensity.PERMAFROST_CANOPY_CEILING,
            "continuous permafrost should cap the cover at the ceiling"
        )
    }
}
