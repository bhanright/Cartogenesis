package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.BeltFields
import com.cartogenesis.worldgen.pipeline.BeltFieldsHash
import com.cartogenesis.worldgen.pipeline.Isostasy
import com.cartogenesis.worldgen.pipeline.OrogenyKind
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import org.junit.Assert.assertTrue

/**
 * The back-arc's extended province stands on its own column, and where its crust puts it under the
 * sea it is drowned basin, not a field of islets (docs/DESIGN_LEDGER.md, TH1-2b).
 *
 * TH1-2 drew the Basin and Range's sub-cell ranges wherever a back-arc was stretched, whatever its
 * ground stood at; on the site world 718106 the province lay at the sea's surface and its ranges'
 * cell-mean relief, one wavelength of Gabor noise, came through the water as hundreds of regular
 * islets, a pattern the eye reads as the grid's (rule 13). Earth's Basin and Range stands high on
 * thin crust because its mantle is hot (Hyndman, Currie and Mazzotti 2005), and where extended
 * crust does lie under the sea, as the Aegean's does, the islands are few: five under 10,000 km² in
 * its 274,000 km² of sea on ETOPO5 averaged onto 1,024 rows, the most islanded extended crust Earth
 * has ([EARTH_AEGEAN_ISLETS_PER_MKM2]).
 *
 * Read on the site world at the site's settings and the application's 1,024 rows, where the islets
 * showed; deep, for that world's cost.
 */
class ExtendedProvinceTest : BorrowsSharedWorlds() {

    /** Islets in the drowned extended crust, per million km² of it, no more than the Aegean's. */
    @Test
    fun `a drowned province is not a field of islets`() {
        val world = SharedWorlds.world(siteConfig())
        val province = provinceOf(world)
        val (islets, drownedKm2) = isletsIn(world.config, world.sea.isLand, province)
        val density = if (drownedKm2 > 0.0) islets / (drownedKm2 / 1e6) else 0.0
        println(
            "PROVINCE site world: %d islets under %.0f km2 touching %.0f km2 of drowned extended crust, %.1f per million km2 (Earth's Aegean %.1f)"
                .format(Locale.ROOT, islets, ISLET_KM2, drownedKm2, density, EARTH_AEGEAN_ISLETS_PER_MKM2)
        )
        assertTrue("the drowned province carries %.1f islets per million km2, past the Aegean's %.1f".format(Locale.ROOT, density, EARTH_AEGEAN_ISLETS_PER_MKM2),
            density <= EARTH_AEGEAN_ISLETS_PER_MKM2)
    }

    /**
     * The province stands where its crust and its mantle's heat put it: its share of land on the
     * finished map is no less than its own columns predict, each cell's column (Airy's of its crust,
     * the back-arc's heat or a stretched margin's, whichever is the larger) standing over the plate
     * stage's waterline by its datum and the ground about it scattered by the platform's own relief
     * and the ranges' ([GROUND_RELIEF_METERS]), so a cell is land with the normal's chance of its
     * datum over that scatter; less [ROUNDS_TOLERANCE] for what the rounds cut at the province's
     * coasts. A province drawn under the sea its crust and heat would lift it out of fails: TH1-2's,
     * stood without the heat, on the site world.
     */
    @Test
    fun `an extended province stands above the sea unless its crust says otherwise`() {
        val world = SharedWorlds.world(siteConfig())
        val config = world.config
        val plates = world.plates
        val columns = Isostasy.Columns(config.isostasy)
        val province = provinceOf(world)
        var cells = 0
        var dry = 0
        var predicted = 0.0
        for (cell in province.indices) {
            if (!province[cell]) continue
            val thickness = plates.crustThicknessKm.data[cell]
            val datum = columns.altitudeMetres(
                plates.continentalShare.data[cell], plates.seafloorAgeMyr.data[cell],
                thickness - config.isostasy.continentalCrustThicknessKm,
                maxOf(columns.stretchedThermalBuoyancyMetres(thickness, plates.riftAgeMyr.data[cell]), columns.hotBackArcBuoyancyMetres(plates.riftAgeMyr.data[cell]))
            )
            cells++
            if (world.sea.isLand[cell]) dry++
            predicted += normalShareBelow(datum / GROUND_RELIEF_METERS)
        }
        val share = dry.toDouble() / cells
        val expected = predicted / cells
        println("PROVINCE site world: %d extended cells, %.3f land, %.3f as their columns stand them (bar %.3f)"
            .format(Locale.ROOT, cells, share, expected, expected - ROUNDS_TOLERANCE))
        assertTrue("the extended province lies under the sea its crust would lift it out of: %.3f land against %.3f".format(Locale.ROOT, share, expected),
            share >= expected - ROUNDS_TOLERANCE)
    }

    /** The standard normal's share below [z]: Abramowitz and Stegun's 7.1.26 for the error function, good to 1.5e-7. */
    private fun normalShareBelow(z: Double): Double {
        val x = kotlin.math.abs(z) / sqrt(2.0)
        val t = 1.0 / (1.0 + 0.3275911 * x)
        val erf = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * kotlin.math.exp(-x * x)
        return if (z >= 0) 0.5 * (1.0 + erf) else 0.5 * (1.0 - erf)
    }

    /**
     * The control: TH1-2's province drawn back on the same world, its ranges' cell-mean relief at
     * full amplitude across the waterline wherever a back-arc was stretched, is a field of islets
     * past the Aegean's.
     */
    @Test
    fun `the province drawn across the waterline fails the guard`() {
        val world = SharedWorlds.world(siteConfig())
        val config = world.config
        val province = provinceOf(world)
        val cellKm = sqrt(config.cellWidthKm * config.cellHeightKm)
        val wavelengthKm = maxOf(PROVINCE_SPACING_KM, PROVINCE_NYQUIST_CELLS * cellKm)
        val standardMeters = PROVINCE_RELIEF_METERS / 2 / sqrt(2.0) * BeltFields.cellMeanShare(PROVINCE_SPACING_KM, cellKm)
        val radiusKm = config.scale.radiusMeters / 1_000.0
        val land = world.sea.isLand.copyOf()
        for (cell in province.indices) {
            if (!province[cell]) continue
            val latitude = PI / 2 - (cell / config.width + 0.5) / config.height * PI
            val longitude = (cell % config.width + 0.5) / config.width * 2 * PI
            val noise = BeltFields.gaborAt(
                radiusKm * cos(latitude) * cos(longitude), radiusKm * cos(latitude) * sin(longitude), radiusKm * sin(latitude),
                -sin(longitude), cos(longitude), 0.0, wavelengthKm, CONTROL_ELONGATION, CONTROL_IMPULSES, config.seed.toInt(), BeltFieldsHash.PROVINCE_OCTAVE
            )
            land[cell] = standardMeters * noise > 0.0
        }
        val (islets, drownedKm2) = isletsIn(config, land, province)
        val density = if (drownedKm2 > 0.0) islets / (drownedKm2 / 1e6) else 0.0
        println("PROVINCE control: %d islets in %.0f km2, %.1f per million km2".format(Locale.ROOT, islets, drownedKm2, density))
        assertTrue("the province drawn across the waterline passes the islet clause (%.1f per million km2)".format(Locale.ROOT, density),
            density > EARTH_AEGEAN_ISLETS_PER_MKM2)
    }

    private fun siteConfig(): WorldGenConfig {
        val base = WorldGenConfig(seed = SITE_SEED, width = 512, height = 512).atResolution(2 * SITE_ROWS, SITE_ROWS)
        return base.copy(
            seaLevel = SITE_SEA_LEVEL,
            tectonics = base.tectonics.withPlateCount(SITE_PLATES, base.scale),
            nations = base.nations.copy(nationCount = SITE_NATIONS)
        )
    }

    /** The back-arc's extended crust: continental cells the history marked stretched behind an arc. */
    private fun provinceOf(world: WorldMap): BooleanArray =
        BooleanArray(world.plates.orogenyKind.size) {
            world.plates.orogenyKind[it] == OrogenyKind.BACK_ARC_EXTENSION.ordinal && world.plates.continentalShare.data[it] >= CONTINENT_SHARE
        }

    /** The islands under [ISLET_KM2] that touch [province], and the province's area under the sea, km². */
    private fun isletsIn(config: WorldGenConfig, land: BooleanArray, province: BooleanArray): Pair<Int, Double> {
        val width = config.width
        val height = config.height
        val label = IntArray(width * height) { -1 }
        var islets = 0
        var drownedKm2 = 0.0
        val stack = ArrayDeque<Int>()
        for (start in land.indices) {
            val rowArea = config.cellWidthKm * config.cellHeightKm * cos(PI / 2 - (start / width + 0.5) / height * PI)
            if (province[start] && !land[start]) drownedKm2 += rowArea
            if (!land[start] || label[start] >= 0) continue
            var areaKm2 = 0.0
            var touches = false
            label[start] = start
            stack.addLast(start)
            while (stack.isNotEmpty()) {
                val cell = stack.removeLast()
                areaKm2 += config.cellWidthKm * config.cellHeightKm * cos(PI / 2 - (cell / width + 0.5) / height * PI)
                if (province[cell]) touches = true
                val column = cell % width
                val row = cell / width
                for (rowStep in -1..1) for (columnStep in -1..1) {
                    val near = row + rowStep
                    if (near < 0 || near >= height) continue
                    val neighbor = near * width + (column + columnStep + width) % width
                    if (land[neighbor] && label[neighbor] < 0) {
                        label[neighbor] = start
                        stack.addLast(neighbor)
                    }
                }
            }
            if (touches && areaKm2 < ISLET_KM2) islets++
        }
        return islets to drownedKm2
    }

    companion object {
        const val SITE_SEED = 718106L
        const val SITE_ROWS = 1024
        const val SITE_SEA_LEVEL = 0.62f
        const val SITE_PLATES = 14
        const val SITE_NATIONS = 12

        /** A cell is the province's where it is more continent than floor. */
        const val CONTINENT_SHARE = 0.5f

        /** An islet: an island under 10,000 km², Crete's size and less. */
        const val ISLET_KM2 = 10_000.0

        /** The Aegean on ETOPO5 averaged onto 1,024 rows, 34.5 to 41 N and 22.5 to 28.5 E: five islands under 10,000 km² in 274,032 km² of sea. */
        val EARTH_AEGEAN_ISLETS_PER_MKM2: Double = 5 / 0.274032

        /** The Basin and Range's ranges over their basins, m, as the plate stage draws them, and their spacing, km (Stewart 1971). */
        const val PROVINCE_RELIEF_METERS = 1_200.0
        const val PROVINCE_SPACING_KM = 28.0
        const val PROVINCE_NYQUIST_CELLS = 3.0

        /** The control's noise as the plate stage draws the province: three crests to a kernel, four impulses to a cube. */
        const val CONTROL_ELONGATION = 3.0
        const val CONTROL_IMPULSES = 4

        /**
         * The ground's scatter about a column's datum, m: the platform's relief by a continental
         * margin (`TectonicsConfig.marginReliefStandardDeviationMetres`, 700) and the province's
         * ranges' cell-mean relief at 1,024 rows (156), in quadrature.
         */
        val GROUND_RELIEF_METERS: Double = sqrt(700.0 * 700.0 + 156.0 * 156.0)

        /** What the rounds may drown of the province's land at its coasts, as a share: a twentieth. */
        const val ROUNDS_TOLERANCE = 0.05
    }
}
