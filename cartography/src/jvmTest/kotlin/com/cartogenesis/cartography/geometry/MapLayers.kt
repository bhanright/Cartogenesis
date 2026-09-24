package com.cartogenesis.cartography.geometry

import com.cartogenesis.cartography.Isobaths
import com.cartogenesis.cartography.MapRasterizer
import com.cartogenesis.cartography.MapSheet
import com.cartogenesis.cartography.MapView
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.cartography.RiverSelection
import com.cartogenesis.cartography.Shoreline
import com.cartogenesis.worldgen.DepositionLayers
import com.cartogenesis.worldgen.LayerCapture
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.CultureResult
import com.cartogenesis.worldgen.pipeline.LakeResult
import com.cartogenesis.worldgen.pipeline.NationResult
import com.cartogenesis.worldgen.pipeline.River
import kotlin.math.sqrt

/**
 * One layer the map draws, as the lines the guard measures.
 *
 * [tracedTwice] marks a partition — realms, biomes, peoples, plates — whose every border is the
 * edge of the region on each side of it and so is traced once from each; the isotropy test counts
 * such a layer's blocks at half weight. [absent] says why a layer has nothing to measure on a
 * world that does not have it (no ice, say), which is reported rather than passed.
 */
internal class Layer(
    val name: String,
    val outlines: List<Outline>,
    val tracedTwice: Boolean = false,
    val absent: String? = null,
    /** For a line drawn into the raster, which way the shores it draws face; see [FacingShares]. */
    val facing: FacingShares? = null,
    /**
     * A climate field, smooth by construction — the rainfall is box-blurred, the temperatures
     * read a box-blurred exposure and a spread anomaly, and the sea's anomaly is a solved
     * advection — whose single level lines are round wherever the field is locally a paraboloid,
     * as a fractal outline's never are, and long and gently curved elsewhere. Such a layer is held
     * to the smooth natural controls ([NaturalTails]): its places to their tails, and its single
     * arcs to their rate; concentric sets are violations on every layer.
     */
    val smoothField: Boolean = false,
    /**
     * A layer whose lines follow the latitude for a real reason — the isotherms, and the biome and
     * ice edges a climate field draws — whose isotropy is held to the zonal control's ratios
     * ([ZonalFigures]) rather than to 1.
     */
    val followsLatitude: Boolean = false,
    /** Open lines by construction, river courses: no ring to measure for its shape or its axis. */
    val openLines: Boolean = false
) {
    /** The natural controls this layer's places and rates are held to. */
    val lineClass: LineClass
        get() = when {
            smoothField -> LineClass.SMOOTH
            openLines -> LineClass.COURSE
            else -> LineClass.ROUGH
        }
}

/**
 * The kinds of natural line a layer is compared with ([NaturalTails]): the edge of a rough region or
 * a level line of rough relief; a course traced from cell to cell, as a river is, which runs along
 * a row wherever its heading lies near one and so makes longer aligned stretches than any outline;
 * and a level line of a smooth field.
 */
internal enum class LineClass(val label: String) {
    ROUGH("rough"),
    COURSE("course"),
    SMOOTH("smooth")
}

/**
 * Every layer the map draws, read off one world and its [LayerCapture].
 *
 * What is here, and where each comes from, is the census's inventory:
 *
 *  - **coast**: the land mask, `SeaLevelResult.isLand`.
 *  - **coast as drawn**: the pane's generalised shoreline, [Shoreline.of] on a 900-pixel pane's
 *    sheet, which is what the overlay strokes.
 *  - **coast as inked**: the raster's own coast line, read off the rendering, for which way the
 *    shores it draws face ([FacingShares]); its shapes are the coast's and are read there.
 *  - **lakes**: every cell `LakeResult.lakeId` names, and **lakes' open water**, the part a drawn
 *    river stops at (`LakeResult.openWater`).
 *  - **river courses**: every traced course, drawn where it is on land and not in open lake water
 *    (the overlay's own rule), and **rivers as drawn**: the pane's selection,
 *    [RiverSelection.drawnOn] at Earth's density.
 *  - **isobaths**: the sea floor's level lines every [Isobaths.INTERVAL_METRES], where
 *    [Isobaths.ink] would put ink on them — the drawing's own rule for plains and crowding.
 *  - **terrain contours**: the land's level lines at [TERRAIN_LEVELS_METRES]. The map draws no
 *    contour on land; it draws the height field as a tint and a shading, and the level lines are
 *    how that field's shapes are measured. Over a sheet the land's height is the ice's surface.
 *  - **ice**: from the capture — where the ice stood, the valley glaciers' trough axes, the
 *    sheet's ground, every cell the ice cut, the outlet troughs, the valley basins and the scour
 *    basins — and the **ice surface**'s level lines every [ICE_SURFACE_INTERVAL_METRES] over the
 *    cells where the sheet stands; and **ice as drawn**, the `ICE_SHEET` biome the map paints.
 *  - **delta lobes**, **lake fans** and **floodplain deposits**: from the capture's deposition
 *    record.
 *  - **realm borders**, **biome edges** (on land), **peoples' borders** and **plate boundaries**:
 *    the political, biome, peoples and plates views.
 *  - **isotherms** (annual mean, every [ISOTHERM_STEP_C]), **isohyets** (annual, on land, at
 *    [ISOHYET_LEVELS_MM]) and the **sea temperature anomaly**'s level lines: the temperature,
 *    rainfall and currents views, which draw those fields as ramps.
 *
 * Left out, because they are drawn geometric on purpose: the graticule (lines of latitude and
 * longitude), landmark glyphs (triangles, diamonds, squares, circles), the flow arrows (laid on a
 * lattice), the scale bar, labels, the engraving's hachures, stipple and coastal water lines (a
 * pen's texture, ruled along the slope and the shore by design), and the realm hatching (a ruled
 * comb that marks the tenth realm onward). The normal-map view is the height field's derivative
 * and is measured through the terrain contours.
 */
internal object MapLayers {

    val TERRAIN_LEVELS_METRES = floatArrayOf(200f, 500f, 1000f, 1500f, 2000f, 3000f, 4000f)
    const val ICE_SURFACE_INTERVAL_METRES = 250f
    const val ISOTHERM_STEP_C = 5f
    val ISOHYET_LEVELS_MM = floatArrayOf(250f, 500f, 1000f, 2000f)
    val ANOMALY_LEVELS_C = floatArrayOf(-4f, -2f, 2f, 4f)

    /** The pane the drawn layers are generalised for, as `RiverSelectionTest` takes it. */
    const val PANE_PIXELS_ACROSS = 900f

    fun of(world: WorldMap, capture: LayerCapture): List<Layer> {
        val frame = GridFrame.of(world.config)
        val cells = frame.cellCount
        val land = world.sea.isLand
        val sea = BooleanArray(cells) { !land[it] }
        val layers = ArrayList<Layer>()
        fun mask(name: String, inside: BooleanArray, valid: BooleanArray? = null, smooth: Boolean = false, zonal: Boolean = false) =
            layers.add(Layer(name, Contours.ofMask(inside, frame, valid), smoothField = smooth, followsLatitude = zonal))

        mask("coast", land)
        val pane = MapSheet.onScreen(PANE_PIXELS_ACROSS / world.width)
        layers.add(Layer("coast as drawn", shorelineAsDrawn(world, frame, pane)))
        layers.add(Layer("coast as inked", emptyList(), facing = coastInk(world, frame)))
        mask("lakes", BooleanArray(cells) { world.rivers.lakes.lakeId[it] != LakeResult.NO_LAKE })
        mask("lakes' open water", world.rivers.lakes.openWater)
        layers.add(Layer("river courses", courses(world, world.rivers.rivers, frame), openLines = true))
        layers.add(Layer("rivers as drawn", courses(world, RiverSelection.drawnOn(world, pane, RiverSelection.EARTH_DENSITY_STEP), frame), openLines = true))
        layers.add(Layer("isobaths", isobaths(world, frame)))
        layers.add(Layer("terrain contours", terrainContours(world, frame)))

        val ice = capture.ice
        if (ice == null) {
            listOf("ice occupancy", "valley glaciers", "ice sheet ground", "ice carving", "outlet troughs",
                "valley basins", "scour basins", "ice surface").forEach {
                layers.add(Layer(it, emptyList(), absent = "the ice cut nothing on this world"))
            }
        } else {
            // Where the provisional climate's snow balance passes its threshold: a smooth field's
            // level set, like every climate layer below.
            mask("ice occupancy", ice.frozen, smooth = true, zonal = true)
            mask("valley glaciers", ice.valleyGlacier)
            mask("ice sheet ground", ice.sheet)
            mask("ice carving", ice.cutByIce)
            mask("outlet troughs", ice.cutByOutlets)
            mask("valley basins", BooleanArray(cells) { ice.basinFloor[it] in 0 until ice.valleyBasinCount })
            mask("scour basins", BooleanArray(cells) { ice.basinFloor[it] >= ice.valleyBasinCount })
            layers.add(Layer("ice surface", iceSurface(world, ice.iceThicknessMetres, frame)))
        }
        mask("ice as drawn", BooleanArray(cells) { world.climate.biome[it] == Biome.ICE_SHEET }, smooth = true, zonal = true)

        val deposition = capture.deposition
        if (deposition == null) {
            listOf("delta lobes", "lake fans", "floodplain deposits").forEach {
                layers.add(Layer(it, emptyList(), absent = "the erosion stage did not run"))
            }
        } else {
            mask("delta lobes", BooleanArray(cells) { deposition.mechanism[it] == DepositionLayers.SEA_LOBE })
            mask("lake fans", BooleanArray(cells) { deposition.mechanism[it] == DepositionLayers.LAKE_FAN })
            mask("floodplain deposits", BooleanArray(cells) { deposition.mechanism[it] == DepositionLayers.FLOODPLAIN })
        }

        layers.add(partition("realm borders", world.nations.nationId, NationResult.UNCLAIMED, land, frame))
        // Every biome is a class of the climate's own smooth fields, so its edges are their level lines.
        layers.add(partition("biome edges", IntArray(cells) { world.climate.biome[it].ordinal }, -1, land, frame, smooth = true, zonal = true))
        layers.add(partition("peoples' borders", world.cultures.cultureId, CultureResult.UNSETTLED, land, frame))
        layers.add(partition("plate boundaries", world.plates.plateId, -1, null, frame))

        layers.add(Layer("isotherms", levelLines(world.climate.temperature.data,
            (-6..6).map { it * ISOTHERM_STEP_C }.toFloatArray(), frame, null), smoothField = true, followsLatitude = true))
        layers.add(Layer("isohyets", levelLines(world.climate.precipitationMm.data, ISOHYET_LEVELS_MM, frame, land), smoothField = true))
        layers.add(Layer("sea temperature anomaly", levelLines(world.ocean.anomaly.data, ANOMALY_LEVELS_C, frame, sea), smoothField = true))
        return layers
    }

    /**
     * Level lines of [field] at each of [levels], where [valid] allows, each marked with its level:
     * the lines of one level are a family of their own, and lines of successive levels down an even
     * slope lie side by side at an even spacing by the slope's nature, not the grid's.
     */
    fun levelLines(field: FloatArray, levels: FloatArray, frame: GridFrame, valid: BooleanArray?): List<Outline> =
        levels.withIndex().flatMap { (index, level) ->
            Contours.ofField(field, level, frame, valid).map { it.atLevel(index + 1) }
        }

    /**
     * A partition's borders: each labelled region's outline, where [valid] allows. [unlabelled]
     * is itself a region where it is valid (the wilderness between realms is drawn as its own
     * colour), so its edges are borders too.
     */
    fun partition(
        name: String,
        label: IntArray,
        unlabelled: Int,
        valid: BooleanArray?,
        frame: GridFrame,
        smooth: Boolean = false,
        zonal: Boolean = false
    ): Layer {
        val labels = HashSet<Int>()
        for (cell in label.indices) if (valid == null || valid[cell]) labels.add(label[cell])
        if (labels.size < 2) return Layer(name, emptyList(), tracedTwice = true, absent = "one region only")
        val outlines = labels.sorted().flatMap { id ->
            Contours.ofMask(BooleanArray(label.size) { label[it] == id }, frame, valid)
        }
        return Layer(name, outlines, tracedTwice = true, smoothField = smooth, followsLatitude = zonal)
    }

    private fun terrainContours(world: WorldMap, frame: GridFrame): List<Outline> {
        val metres = world.config.scale.highestLandMetres
        val levels = FloatArray(TERRAIN_LEVELS_METRES.size) { TERRAIN_LEVELS_METRES[it] / metres }
        return levelLines(world.sea.relativeElevation.data, levels, frame, world.sea.isLand)
    }

    private fun iceSurface(world: WorldMap, thicknessMetres: FloatArray, frame: GridFrame): List<Outline> {
        val onIce = BooleanArray(frame.cellCount) { thicknessMetres[it] > 0f && world.sea.isLand[it] }
        if (onIce.none { it }) return emptyList()
        val metres = world.config.scale.highestLandMetres
        val elevation = world.sea.relativeElevation.data
        var highest = 0f
        for (cell in elevation.indices) if (onIce[cell] && elevation[cell] > highest) highest = elevation[cell]
        val levels = ArrayList<Float>()
        var level = ICE_SURFACE_INTERVAL_METRES
        while (level / metres <= highest) {
            levels.add(level / metres)
            level += ICE_SURFACE_INTERVAL_METRES
        }
        return levelLines(elevation, levels.toFloatArray(), frame, onIce)
    }

    /**
     * The sea floor's level lines at every multiple of [Isobaths.INTERVAL_METRES], over the sea
     * cells where [Isobaths.ink] would ink a line passing through them: the floor's fall measured
     * over the same stencil and in the same units as the rasteriser's own `seaContour`.
     */
    private fun isobaths(world: WorldMap, frame: GridFrame): List<Outline> {
        val config = world.config
        val interval = Isobaths.interval(config.scale)
        val flattest = Isobaths.flattestSlope(config, world.width, world.height)
        val stencil = Isobaths.slopeStencil(world.width)
        val elevation = world.sea.relativeElevation
        val perCell = 1f / (2f * stencil)
        val rowScale = config.cellHeightInCellWidths.toFloat()
        val inked = BooleanArray(frame.cellCount) { cell ->
            if (world.sea.isLand[cell]) return@BooleanArray false
            val column = cell % world.width
            val row = cell / world.width
            val eastward = (elevation.sample(column + stencil, row) - elevation.sample(column - stencil, row)) * perCell
            val southward = (elevation.sample(column, row + stencil) - elevation.sample(column, row - stencil)) * perCell
            val slope = sqrt(eastward * eastward + southward * southward)
            val southwardOnTheGround = southward / rowScale
            val slopeOnTheGround = sqrt(eastward * eastward + southwardOnTheGround * southwardOnTheGround)
            // A pixel lying on a line: depth exactly one interval, so the ink is the line's own.
            Isobaths.ink(interval, slope, slopeOnTheGround, interval, flattest) > 0f
        }
        val levels = ArrayList<Float>()
        var depth = interval
        while (depth < 1f) {
            levels.add(-depth)
            depth += interval
        }
        return levelLines(elevation.data, levels.toFloatArray(), frame, inked)
    }

    /**
     * Which shores the raster's own coast ink covers, read off the rendering: the atlas drawn with
     * its coast and without, and the cells whose colour the coast changed.
     */
    private fun coastInk(world: WorldMap, frame: GridFrame): FacingShares {
        val withCoast = MapRasterizer.rasterize(world, RenderOptions(view = MapView.FANTASY, showCoastline = true))
        val withoutCoast = MapRasterizer.rasterize(world, RenderOptions(view = MapView.FANTASY, showCoastline = false))
        val inked = BooleanArray(frame.cellCount) { withCoast[it] != withoutCoast[it] }
        return FacingShares.of(world.sea.isLand, inked, frame)
    }

    /** The pane's stroked coast, in kilometres; a ring the tracer closed by repeating its start is closed. */
    private fun shorelineAsDrawn(world: WorldMap, frame: GridFrame, sheet: MapSheet): List<Outline> =
        Shoreline.of(world.sea.isLand, world.width, world.height, sheet).map { line ->
            val count = line.size / 2
            val repeats = count > 2 && line[0] == line[line.size - 2] && line[1] == line[line.size - 1]
            val kept = if (repeats) count - 1 else count
            Outline(
                DoubleArray(kept) { line[it * 2].toDouble() * frame.cellWidthKm },
                DoubleArray(kept) { line[it * 2 + 1].toDouble() * frame.cellHeightKm },
                closed = repeats, belt = false
            )
        }

    /**
     * Courses as open lines through their cells' centres, broken where the overlay would not draw
     * them: off the land, or where both ends of a step are open lake water.
     */
    private fun courses(world: WorldMap, rivers: List<River>, frame: GridFrame): List<Outline> {
        val across = world.width
        val lakes = world.rivers.lakes
        val lines = ArrayList<Outline>()
        for (river in rivers) {
            var xs = DoubleArrayBuilder()
            var ys = DoubleArrayBuilder()
            fun flush() {
                if (xs.size >= 2) lines.add(Outline(xs.toArray(), ys.toArray(), closed = false, belt = false))
                xs = DoubleArrayBuilder()
                ys = DoubleArrayBuilder()
            }
            var previousX = Double.NaN
            for (step in river.cells.indices) {
                val cell = river.cells[step]
                val drawn = world.sea.isLand[cell] &&
                    !(lakes.isOpenWater(cell) && step > 0 && lakes.isOpenWater(river.cells[step - 1]))
                if (!drawn) { flush(); previousX = Double.NaN; continue }
                var x = (cell % across) + 0.5
                if (!previousX.isNaN()) {
                    while (x - previousX > across / 2.0) x -= across
                    while (previousX - x > across / 2.0) x += across
                }
                xs.add(x * frame.cellWidthKm)
                ys.add((cell / across + 0.5) * frame.cellHeightKm)
                previousX = x
            }
            flush()
        }
        return lines
    }
}
