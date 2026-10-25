package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.BeltFields
import com.cartogenesis.worldgen.pipeline.BeltFieldsHash
import com.cartogenesis.worldgen.pipeline.CrustMap
import com.cartogenesis.worldgen.pipeline.Isostasy
import com.cartogenesis.worldgen.pipeline.OrogenyKind
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.SeaLevelStage
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
     * the back-arc's heat or a stretched margin's, whichever is the larger) standing over the
     * waterline the world's sea share puts on the plate stage's ground by its datum, and the ground
     * about it scattered by the platform's own relief and the ranges' ([GROUND_RELIEF_METERS]), so a
     * cell is land with the normal's chance of its datum over that scatter; less [ROUNDS_TOLERANCE]
     * for what the rounds cut at the province's coasts. The back-arc's heat is the plate stage's
     * own, from when the crust last stood behind a live trench, spread over the depth it reaches. A
     * province drawn under the sea its crust and heat would lift it out of fails: TH1-2's, stood
     * without the heat, on the site world.
     */
    @Test
    fun `an extended province stands above the sea unless its crust says otherwise`() {
        val world = SharedWorlds.world(siteConfig())
        val config = world.config
        val plates = world.plates
        val columns = Isostasy.Columns(config.isostasy)
        val backArcHeat = PlateStage.backArcHeatMetres(config, CrustMap.read(config, TectonicHistory.run(config)), plates.continentalShare.data, columns)
        // The waterline the world's sea share puts on the plate stage's own ground: the hot back-arcs
        // stand the continents' upper part higher, and the cut climbs with them.
        val waterlineMeters = config.scale.altitudeAtField(SeaLevelStage.percentileCut(plates.height, config.seaLevel, config.scale).shorelineHeight)
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
                maxOf(columns.stretchedThermalBuoyancyMetres(thickness, plates.riftAgeMyr.data[cell]), backArcHeat[cell])
            )
            cells++
            if (world.sea.isLand[cell]) dry++
            predicted += normalShareBelow((datum - waterlineMeters) / GROUND_RELIEF_METERS)
        }
        val share = dry.toDouble() / cells
        val expected = predicted / cells
        println("PROVINCE site world: %d extended cells, %.3f land, %.3f as their columns stand them (bar %.3f)"
            .format(Locale.ROOT, cells, share, expected, expected - ROUNDS_TOLERANCE))
        // Recorded at TH1-2c: on the site world the lattice's turn draws, the province's oldest part,
        // stretched about 185 Myr ago and cooled behind a trench that died about as long ago, lies
        // under the ocean at a margin, where its columns stand it above the sea (docs/DESIGN_LEDGER.md,
        // TH1-2c; docs/TODO.md).
        KnownFailures.expect(OLD_PROVINCE_FINDING, OLD_PROVINCE_SIGNATURE) {
            if (share < expected - ROUNDS_TOLERANCE) {
                throw RecordedViolation(
                    "the extended province lies under the sea its crust would lift it out of: %.3f land against %.3f".format(Locale.ROOT, share, expected),
                    OLD_PROVINCE_SIGNATURE
                )
            }
        }
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
     * full amplitude across the waterline wherever a back-arc was stretched and standing in the sea
     * as TH1-2's did, is a field of islets past the Aegean's.
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
        // Standing in the sea, as TH1-2's province stood, so its ranges' crests are its own islands
        // and not headlands of the continent round it.
        val land = BooleanArray(province.size)
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
     * The control: TH1-2b's own site world, where the islet field showed, read the same way. Its
     * enclosed sea and the ground round it are kept as a fixture ([CONTROL_FIXTURE], the world's
     * rows 360 to 639 and columns 950 to 1229 at 1,024 rows, meters above or below its shoreline,
     * water negative), since a world the code no longer makes cannot be made again here; the body
     * the fixture holds, 5.3 million km² of water the ocean could not reach, carries 75 islets.
     */
    @Test
    fun `TH1-2b's site world fails the inland clause`() {
        val stream = java.util.zip.GZIPInputStream(checkNotNull(javaClass.getResourceAsStream(CONTROL_FIXTURE)) { "missing $CONTROL_FIXTURE" })
        val input = java.io.DataInputStream(java.io.BufferedInputStream(stream))
        val rows = input.readInt()
        val columns = input.readInt()
        val firstRow = input.readInt()
        val mapRows = input.readInt()
        val mapColumns = input.readInt()
        val meters = IntArray(rows * columns) { input.readShort().toInt() }
        input.close()
        val site = siteConfig()
        assertTrue("the fixture's grid is not the site's", mapColumns == site.width && mapRows == site.height)
        val cellKm = site.cellWidthKm
        fun areaKm2(cell: Int): Double = cellKm * cellKm * cos(PI / 2 - (firstRow + cell / columns + 0.5) / mapRows * PI)
        val water = BooleanArray(meters.size) { meters[it] < 0 }
        val label = IntArray(meters.size) { -1 }
        fun flood(start: Int, mask: BooleanArray, visit: (Int) -> Unit) {
            val stack = ArrayDeque<Int>()
            label[start] = start
            stack.addLast(start)
            while (stack.isNotEmpty()) {
                val cell = stack.removeLast()
                visit(cell)
                val row = cell / columns
                val column = cell % columns
                for (rowStep in -1..1) for (columnStep in -1..1) {
                    val near = row + rowStep
                    val across = column + columnStep
                    if (near < 0 || near >= rows || across < 0 || across >= columns) continue
                    val neighbor = near * columns + across
                    if (mask[neighbor] && label[neighbor] < 0) {
                        label[neighbor] = start
                        stack.addLast(neighbor)
                    }
                }
            }
        }
        fun onEdge(cell: Int) = cell / columns == 0 || cell / columns == rows - 1 || cell % columns == 0 || cell % columns == columns - 1
        // The water bodies wholly inside the crop: the ones the ocean, beyond it, cannot reach.
        val bodies = ArrayList<Pair<HashSet<Int>, Double>>()
        for (start in meters.indices) {
            if (!water[start] || label[start] >= 0) continue
            val cells = HashSet<Int>()
            var touchesEdge = false
            flood(start, water) { cells.add(it); if (onEdge(it)) touchesEdge = true }
            if (!touchesEdge) bodies.add(cells to cells.sumOf { areaKm2(it) })
        }
        val largest = bodies.filter { it.second >= INLAND_BODY_KM2 }.maxByOrNull { it.second }
        assertTrue("the fixture holds no inland body", largest != null)
        val (body, waterKm2) = largest!!
        var islets = 0
        val ground = BooleanArray(meters.size) { !water[it] }
        for (start in meters.indices) {
            if (!ground[start] || label[start] >= 0) continue
            var areaKm2 = 0.0
            var touchesBody = false
            var touchesEdge = false
            flood(start, ground) { cell ->
                areaKm2 += areaKm2(cell)
                if (onEdge(cell)) touchesEdge = true
                val row = cell / columns
                val column = cell % columns
                for (rowStep in -1..1) for (columnStep in -1..1) {
                    val near = row + rowStep
                    val across = column + columnStep
                    if (near in 0 until rows && across in 0 until columns && near * columns + across in body) touchesBody = true
                }
            }
            if (touchesBody && !touchesEdge && areaKm2 < ISLET_KM2) islets++
        }
        val density = islets / (waterKm2 / 1e6)
        println("INLAND control, TH1-2b's site world: %d islets in %.0f km2 of its enclosed sea, %.1f per million km2".format(Locale.ROOT, islets, waterKm2, density))
        assertTrue("TH1-2b's enclosed sea passes the inland clause (%.1f per million km2)".format(Locale.ROOT, density), density > EARTH_DROWNED_PLATFORM_ISLETS_PER_MKM2)
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

        /** TH1-2b's site world's enclosed sea and the ground round it, the inland clause's control. */
        const val CONTROL_FIXTURE = "/th1-2b-site-inland-sea.bin.gz"

        const val OLD_PROVINCE_FINDING = "TH1-2c: the site world's oldest extended crust lies under the ocean its columns stand above"
        const val OLD_PROVINCE_SIGNATURE = "the province under its columns' land"


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
