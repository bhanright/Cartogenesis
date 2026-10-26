package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import java.util.Locale
import kotlin.math.cos
import kotlin.test.Test
import org.junit.Assert.assertTrue

/**
 * Earth's physical rules for the crust on every world, read on the map (docs/DESIGN_LEDGER.md,
 * TH1-1): continents lie inside plates rather than being them, the crust's edge is a natural
 * outline rather than the plates' polygons, the deep floor is as deep as old floor stands, and the
 * land stands within the altitudes Earth's continents do.
 *
 * Where a bar sits between Earth's figure and the defect's, it sits at the midpoint: a world fails
 * where it stands nearer the defect than Earth. The defect's figures are main's, measured by D3 and
 * by the design on its seeds 42, 7 and 1066 with the same instruments ([CrustMeasures]); Earth's are
 * the sources each names. The same clauses run on main's tree fail there, as the ledger's row
 * records.
 */
class ContinentsInsidePlatesTest : BorrowsSharedWorlds() {

    /**
     * Land over the area of the plates that carry it: Earth's 0.41 (Bird 2003's thirteen
     * land-bearing plates against Earth's land), main's 0.699, where every continent was a plate.
     */
    @Test
    fun `continents lie inside plates`() {
        val bar = midpoint(EARTH_LAND_SHARE_OF_ITS_PLATES, MAIN_LAND_SHARE_OF_ITS_PLATES)
        for (seed in SharedWorlds.STANDARD_SEEDS) {
            val world = worldOf(seed)
            val land = CrustMeasures.landBeforeErosion(world.config, world.plates)
            val share = CrustMeasures.landShareOfItsPlates(world.config, world.plates, land)
            println("CRUST seed %d: land is %.3f of its plates (bar %.3f)".format(Locale.ROOT, seed, share, bar))
            assertTrue("seed $seed: land is $share of the plates that carry it, above $bar", share <= bar)
        }
    }

    /**
     * The crust's edge within two cells of a plate boundary, as a share of the edge: Earth's margins
     * are about 35% active (Harris et al. 2014), which is the share of its edge a boundary runs
     * along; main's was all of it, its continents being plates.
     */
    @Test
    fun `the crust's edge is not the plates' edge`() {
        val bar = midpoint(EARTH_EDGE_ON_BOUNDARY, MAIN_EDGE_ON_BOUNDARY)
        for (seed in SharedWorlds.STANDARD_SEEDS) {
            val world = worldOf(seed)
            val edge = CrustMeasures.crustEdgeCells(world.config, world.plates)
            val near = 1.0 - CrustMeasures.shareFartherThan(world.config, world.plates, edge, ON_BOUNDARY_CELLS * world.config.cellWidthKm)
            println("CRUST seed %d: %.3f of the crust's edge on a plate boundary (bar %.3f)".format(Locale.ROOT, seed, near, bar))
            assertTrue("seed $seed: $near of the crust's edge lies on a plate boundary, above $bar", near <= bar)
        }
    }

    /**
     * The crust's edge as D3 read it, pooled over the standard worlds: its divider dimension at map
     * scale, its share of sharp corners and its share of straight runs, each against Earth's
     * continental edge (ETOPO5's 2,000 m isobath at 512 rows, read by the same instruments: 1.17,
     * 0.286 and 0.146) and main's (D3: 1.04, 0.009 and 0.341). The straight share is recorded as a
     * known failure: the history's continents run straighter than Earth's at the scale of a tenth of
     * the map, along their rifted margins and their blocks' outer arcs alike; assembling the
     * supercontinent from continent-sized blocks took it from TH1-1's 0.35 to 0.30 (docs/TODO.md;
     * docs/DESIGN_LEDGER.md, TH1-1b).
     */
    @Test
    fun `the crust's edge is a natural outline`() {
        var dimension = 0.0
        var corners = 0.0
        var straight = 0.0
        for (seed in SharedWorlds.STANDARD_SEEDS) {
            val world = worldOf(seed)
            val mask = BooleanArray(world.plates.continentalShare.data.size) { world.plates.continentalShare.data[it] >= 0.5f }
            val lines = CrustMeasures.traceLines(world.config, mask)
            val d = CrustMeasures.dividerDimension(lines)
            val c = CrustMeasures.cornerShare(lines)
            val s = CrustMeasures.straightShare(lines)
            println("CRUST seed %d: edge divider dimension %.3f, sharp corners %.3f, straight runs %.3f".format(Locale.ROOT, seed, d, c, s))
            dimension += d / SharedWorlds.STANDARD_SEEDS.size
            corners += c / SharedWorlds.STANDARD_SEEDS.size
            straight += s / SharedWorlds.STANDARD_SEEDS.size
        }
        val dimensionBar = midpoint(EARTH_EDGE_DIMENSION, MAIN_EDGE_DIMENSION)
        val cornerBar = midpoint(EARTH_EDGE_CORNERS, MAIN_EDGE_CORNERS)
        val straightBar = midpoint(EARTH_EDGE_STRAIGHT, MAIN_EDGE_STRAIGHT)
        println(
            "CRUST pooled: divider dimension %.3f (bar %.3f), corners %.3f (bar %.3f), straight %.3f (bar %.3f)"
                .format(Locale.ROOT, dimension, dimensionBar, corners, cornerBar, straight, straightBar)
        )
        assertTrue("the crust's edge has a divider dimension of $dimension, under $dimensionBar", dimension >= dimensionBar)
        assertTrue("the crust's edge has $corners of its windows at a sharp corner, under $cornerBar", corners >= cornerBar)
        KnownFailures.expect(STRAIGHT_EDGES, "straight share above the midpoint") {
            if (straight > straightBar) {
                throw RecordedViolation(
                    "the crust's edge runs straight over %.3f of its tenth-of-a-map windows, above %.3f".format(Locale.ROOT, straight, straightBar),
                    "straight share above the midpoint"
                )
            }
        }
    }

    /**
     * Floor between 5 and 6 km deep, as a share of the sea floor: Earth's 0.21 (ETOPO5 at 39 km, the
     * design's measures), where old floor has cooled and sunk; main's 0.011, whose floor was never
     * older than its boundary blur. Recorded on seed 99, whose floor's mean age is 34.5 Myr against
     * Earth's about 60 and only 0.048 of it older than 100 Myr: the deep floor is old floor, and
     * this world's history has left little (docs/TODO.md, "Sea-floor age is young on some worlds").
     */
    @Test
    fun `the deep floor stands as deep as old floor does`() {
        val bar = midpoint(EARTH_FLOOR_DEEPER_THAN_FIVE_KM, MAIN_FLOOR_DEEPER_THAN_FIVE_KM)
        val shallow = ArrayList<String>()
        for (seed in SharedWorlds.STANDARD_SEEDS) {
            val world = worldOf(seed)
            val share = deepFloorShare(world)
            val ages = CrustMeasures.floorAges(world.config, world.plates)
            println(
                "CRUST seed %d: %.3f of the floor 5-6 km deep (bar %.3f); floor mean age %.1f Myr, %.3f older than 100"
                    .format(Locale.ROOT, seed, share, bar, ages.meanMyr, ages.olderThan100Share)
            )
            if (share < bar) shallow += "seed $seed"
        }
        // Recorded from TH1-1 to TH1-2 on seed 99, whose floor was young; armed at TH1-2b, where every
        // standard world holds 0.118 to 0.131 of its floor deep (docs/DESIGN_LEDGER.md, TH1-2b).
        assertTrue("the floor 5-6 km deep is under $bar of the floor on ${shallow.joinToString()}", shallow.isEmpty())
    }

    /**
     * Each world's land, mean altitude, within the span of Earth's seven continents' means (ETOPO5,
     * 337 m for Australia to 2,083 m for Antarctica's ice, as `HypsometryTest` cites them): the
     * physically possible range a world of several continents stands in.
     */
    @Test
    fun `each world's land stands within Earth's continents' altitudes`() {
        for (seed in SharedWorlds.STANDARD_SEEDS) {
            val world = worldOf(seed)
            val mean = meanLandMetres(world)
            println("CRUST seed %d: the land's mean altitude %.0f m".format(Locale.ROOT, seed, mean))
            assertTrue("seed $seed: the land stands $mean m on average", mean in EARTH_LOWEST_CONTINENT_METRES..EARTH_HIGHEST_CONTINENT_METRES)
        }
    }

    private fun worldOf(seed: Long): WorldMap = SharedWorlds.world(WorldGenConfig.forRows(seed, SharedWorlds.DETAIL_ROWS))

    private fun deepFloorShare(world: WorldMap): Double {
        val scale = world.config.scale
        var sea = 0.0
        var deep = 0.0
        val relative = world.sea.relativeElevation.data
        for (cell in relative.indices) {
            if (world.sea.isLand[cell]) continue
            val weight = cos(Math.toRadians(CrustMeasures.latitudeDegrees(world.config, cell / world.width)))
            sea += weight
            // The scale gives a water cell's altitude, negative; its depth is the opposite.
            if (-scale.metresBelowShoreline(relative[cell]) in DEEP_FLOOR_METRES..DEEPEST_BAND_METRES) deep += weight
        }
        return deep / sea
    }

    private fun meanLandMetres(world: WorldMap): Double {
        val scale = world.config.scale
        var land = 0.0
        var metres = 0.0
        val relative = world.sea.relativeElevation.data
        for (cell in relative.indices) {
            if (!world.sea.isLand[cell]) continue
            val weight = cos(Math.toRadians(CrustMeasures.latitudeDegrees(world.config, cell / world.width)))
            land += weight
            metres += weight * scale.metresAboveShoreline(relative[cell])
        }
        return metres / land
    }

    private companion object {
        fun midpoint(earth: Double, defect: Double) = (earth + defect) / 2.0

        const val EARTH_LAND_SHARE_OF_ITS_PLATES = 0.41
        const val MAIN_LAND_SHARE_OF_ITS_PLATES = 0.699

        /** Within two cells of a boundary is on it: a boundary drawn on the map is a cell or two wide. */
        const val ON_BOUNDARY_CELLS = 2.0
        const val EARTH_EDGE_ON_BOUNDARY = 0.35
        const val MAIN_EDGE_ON_BOUNDARY = 1.0

        const val EARTH_EDGE_DIMENSION = 1.17
        const val MAIN_EDGE_DIMENSION = 1.04
        const val EARTH_EDGE_CORNERS = 0.286
        const val MAIN_EDGE_CORNERS = 0.009
        const val EARTH_EDGE_STRAIGHT = 0.146
        const val MAIN_EDGE_STRAIGHT = 0.341
        const val STRAIGHT_EDGES = "TH1-1: the crust's edge runs straighter than Earth's at a tenth of the map"

        const val DEEP_FLOOR_METRES = 5_000f
        const val DEEPEST_BAND_METRES = 6_000f
        const val EARTH_FLOOR_DEEPER_THAN_FIVE_KM = 0.21
        const val MAIN_FLOOR_DEEPER_THAN_FIVE_KM = 0.011

        const val EARTH_LOWEST_CONTINENT_METRES = 337.0
        const val EARTH_HIGHEST_CONTINENT_METRES = 2_083.0
    }
}
