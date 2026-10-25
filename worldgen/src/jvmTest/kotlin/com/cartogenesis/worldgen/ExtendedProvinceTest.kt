package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.BeltFields
import com.cartogenesis.worldgen.pipeline.BeltFieldsHash
import com.cartogenesis.worldgen.pipeline.CrustMap
import com.cartogenesis.worldgen.pipeline.Isostasy
import com.cartogenesis.worldgen.pipeline.OrogenyKind
import com.cartogenesis.worldgen.pipeline.TectonicHistory
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
 * Water the ocean cannot reach is held the same way (docs/DESIGN_LEDGER.md, TH1-2c): the site world
 * of TH1-2b drew an enclosed sea of 5.3 million km² in the middle of its main continent, an old
 * craton's interior standing at the waterline, and the platform's relief came through as 75 islets,
 * 14.2 per million km², where Earth's drowned platforms carry no more than the Baltic's 8.4
 * ([EARTH_DROWNED_PLATFORM_ISLETS_PER_MKM2]). It was the craton's own ground, not the province's.
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
        val (islets, drownedKm2) = isletsIn(world.config, world.sea.isLand, province, drownedOnly = true)
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
     * coasts. The back-arc's heat is read from when the crust last stood behind a live trench, as
     * the plate stage reads it. A province drawn under the sea its crust and heat would lift it out
     * of fails: TH1-2's, stood without the heat, on the site world.
     */
    @Test
    fun `an extended province stands above the sea unless its crust says otherwise`() {
        val world = SharedWorlds.world(siteConfig())
        val config = world.config
        val plates = world.plates
        val columns = Isostasy.Columns(config.isostasy)
        val backArcAgeMyr = CrustMap.read(config, TectonicHistory.run(config)).backArcAgeMyr
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
                maxOf(columns.stretchedThermalBuoyancyMetres(thickness, plates.riftAgeMyr.data[cell]), columns.hotBackArcBuoyancyMetres(backArcAgeMyr[cell]))
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
        val (islets, drownedKm2) = isletsIn(config, land, province, drownedOnly = true)
        val density = if (drownedKm2 > 0.0) islets / (drownedKm2 / 1e6) else 0.0
        println("PROVINCE control: %d islets in %.0f km2, %.1f per million km2".format(Locale.ROOT, islets, drownedKm2, density))
        assertTrue("the province drawn across the waterline passes the islet clause (%.1f per million km2)".format(Locale.ROOT, density),
            density > EARTH_AEGEAN_ISLETS_PER_MKM2)
    }

    /**
     * Each body of water the ocean cannot reach, a lake or a sea the cut walled off, of at least
     * [INLAND_BODY_KM2], holds no more islets per million km² than Earth's most islanded drowned
     * platform: a platform standing at its own waterline is what lays one islet beside another, and
     * a lake or an enclosed sea is a waterline of its own.
     */
    @Test
    fun `water the ocean cannot reach is not a field of islets`() {
        val world = SharedWorlds.world(siteConfig())
        val inland = inlandWater(world)
        val ground = BooleanArray(inland.size) { world.sea.isLand[it] && !inland[it] }
        val complaints = ArrayList<String>()
        for (body in bodiesOf(world.config, inland)) {
            val (islets, waterKm2) = isletsIn(world.config, ground, body, drownedOnly = false)
            if (waterKm2 < INLAND_BODY_KM2) continue
            val density = islets / (waterKm2 / 1e6)
            println(
                "INLAND site world: a body of %.0f km2 the ocean cannot reach, %d islets under %.0f km2, %.1f per million km2 (Earth's drowned platforms at most %.1f)"
                    .format(Locale.ROOT, waterKm2, islets, ISLET_KM2, density, EARTH_DROWNED_PLATFORM_ISLETS_PER_MKM2)
            )
            if (density > EARTH_DROWNED_PLATFORM_ISLETS_PER_MKM2) complaints.add("%.0f km2 with %.1f per million km2".format(Locale.ROOT, waterKm2, density))
        }
        assertTrue("inland water carries islets past Earth's drowned platforms' %.1f per million km2: %s".format(Locale.ROOT, EARTH_DROWNED_PLATFORM_ISLETS_PER_MKM2, complaints),
            complaints.isEmpty())
    }

    /**
     * The control: TH1-2b's enclosed sea made again on this world's own ground, a craton's low
     * interior flooded to the middle of its own relief. The cratonic ground's lowest fifth sets the
     * waterline, and the largest stretch of cratonic ground within [CONTROL_BAND_METERS] of it,
     * flooded to it, is a field of islets past Earth's drowned platforms'.
     */
    @Test
    fun `a platform flooded at its own middle fails the inland clause`() {
        val world = SharedWorlds.world(siteConfig())
        val config = world.config
        val elevation = RangeInstrument.elevationMeters(world)
        val land = world.sea.isLand
        val old = BooleanArray(land.size) {
            land[it] && world.plates.orogenyKind[it] == OrogenyKind.NONE.ordinal && world.plates.continentalShare.data[it] >= CONTINENT_SHARE
        }
        val heights = (land.indices).filter { old[it] }.map { elevation[it] }.sorted()
        val lowland = heights[heights.size / CONTROL_LOWLAND_QUANTILE_DIVISOR]
        val band = BooleanArray(land.size) { old[it] && kotlin.math.abs(elevation[it] - lowland) <= CONTROL_BAND_METERS }
        val stretch = largestComponent(config, band)
        val water = BooleanArray(land.size) { stretch[it] && elevation[it] < lowland }
        val ground = BooleanArray(land.size) { land[it] && !water[it] }
        val (islets, waterKm2) = isletsIn(config, ground, water, drownedOnly = false)
        val density = if (waterKm2 > 0.0) islets / (waterKm2 / 1e6) else 0.0
        println("INLAND control: the craton's lowland at %.0f m flooded, %d islets in %.0f km2, %.1f per million km2".format(Locale.ROOT, lowland, islets, waterKm2, density))
        assertTrue("the control's flooded platform passes the inland clause (%.1f per million km2)".format(Locale.ROOT, density),
            density > EARTH_DROWNED_PLATFORM_ISLETS_PER_MKM2)
    }

    /**
     * The water the ocean cannot reach: every water body but the largest, which is the ocean (as the
     * sea-level stage labels it), and the open water of the lakes.
     */
    private fun inlandWater(world: WorldMap): BooleanArray {
        val config = world.config
        val water = BooleanArray(world.sea.isLand.size) { !world.sea.isLand[it] }
        val ocean = largestComponent(config, water)
        return BooleanArray(water.size) { (water[it] && !ocean[it]) || world.rivers.lakes.isOpenWater(it) }
    }

    /**
     * Every eight-connected body of [mask], wrapping east-west, that could cover [INLAND_BODY_KM2]
     * (as many cells as an equatorial cell's area allows), each as its own mask.
     */
    private fun bodiesOf(config: WorldGenConfig, mask: BooleanArray): List<BooleanArray> {
        val label = labels(config, mask)
        val sizes = HashMap<Int, Int>()
        for (cell in label.indices) if (label[cell] >= 0) sizes.merge(label[cell], 1, Int::plus)
        val fewestCells = INLAND_BODY_KM2 / (config.cellWidthKm * config.cellHeightKm)
        return sizes.filter { it.value >= fewestCells }.keys.sorted().map { body -> BooleanArray(mask.size) { label[it] == body } }
    }

    /** The largest eight-connected body of [mask], wrapping east-west. */
    private fun largestComponent(config: WorldGenConfig, mask: BooleanArray): BooleanArray {
        val label = labels(config, mask)
        val sizes = HashMap<Int, Int>()
        for (cell in label.indices) if (label[cell] >= 0) sizes.merge(label[cell], 1, Int::plus)
        val best = sizes.entries.sortedWith(compareBy({ -it.value }, { it.key })).firstOrNull()?.key ?: -1
        return BooleanArray(mask.size) { best >= 0 && label[it] == best }
    }

    /** Each cell of [mask]'s eight-connected body, labeled by the body's first cell; -1 off the mask. */
    private fun labels(config: WorldGenConfig, mask: BooleanArray): IntArray {
        val width = config.width
        val height = config.height
        val label = IntArray(mask.size) { -1 }
        val stack = ArrayDeque<Int>()
        for (start in mask.indices) {
            if (!mask[start] || label[start] >= 0) continue
            label[start] = start
            stack.addLast(start)
            while (stack.isNotEmpty()) {
                val cell = stack.removeLast()
                val column = cell % width
                val row = cell / width
                for (rowStep in -1..1) for (columnStep in -1..1) {
                    val near = row + rowStep
                    if (near < 0 || near >= height) continue
                    val neighbor = near * width + (column + columnStep + width) % width
                    if (mask[neighbor] && label[neighbor] < 0) {
                        label[neighbor] = start
                        stack.addLast(neighbor)
                    }
                }
            }
        }
        return label
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

    /**
     * The islands under [ISLET_KM2] that touch [province], and the province's area under the sea,
     * km²; or, not [drownedOnly], [province] being the water itself, the islands that touch it and
     * its whole area.
     */
    private fun isletsIn(config: WorldGenConfig, land: BooleanArray, province: BooleanArray, drownedOnly: Boolean): Pair<Int, Double> {
        val width = config.width
        val height = config.height
        val label = IntArray(width * height) { -1 }
        var islets = 0
        var drownedKm2 = 0.0
        val stack = ArrayDeque<Int>()
        for (start in land.indices) {
            val rowArea = config.cellWidthKm * config.cellHeightKm * cos(PI / 2 - (start / width + 0.5) / height * PI)
            if (province[start] && (!drownedOnly || !land[start])) drownedKm2 += rowArea
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
                if (!drownedOnly) {
                    for (rowStep in -1..1) for (columnStep in -1..1) {
                        val near = row + rowStep
                        if (near >= 0 && near < height && province[near * width + (column + columnStep + width) % width]) touches = true
                    }
                }
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

        /**
         * Earth's drowned platforms on ETOPO5 averaged onto 1,024 rows, islands under 10,000 km²
         * touching each box's sea per million km² of it: Hudson Bay 7 in 854,095 km² (8.2), the
         * Baltic 4 in 474,378 (8.4), the North Sea 1 in 596,340 (1.7), the Persian Gulf none in
         * 232,036, the Gulf of Carpentaria 1 in 436,671 (2.3), the Java Sea 3 in 619,821 (4.8); the
         * most islanded, the Baltic's.
         */
        const val EARTH_DROWNED_PLATFORM_ISLETS_PER_MKM2 = 4 / 0.474378

        /**
         * The least inland body held to Earth's drowned platforms, km²: about the smallest of them,
         * the Persian Gulf's 232,000 km² of sea on the same grid; a smaller lake's few islets are
         * no figure at all.
         */
        const val INLAND_BODY_KM2 = 200_000.0

        /**
         * The control's lowland: the cratonic ground's lowest fifth, where TH1-2b's enclosed sea lay,
         * and a band about it as wide as the platform's own scatter by a margin
         * ([GROUND_RELIEF_METERS]'s first part is 700 m; a fifth of it keeps the stretch one flat).
         */
        const val CONTROL_LOWLAND_QUANTILE_DIVISOR = 5
        const val CONTROL_BAND_METERS = 150.0

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
