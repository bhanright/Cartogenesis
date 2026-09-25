package com.cartogenesis.cartography

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.LandmarkKind
import com.cartogenesis.worldgen.pipeline.CultureResult
import com.cartogenesis.worldgen.pipeline.NationResult
import com.cartogenesis.worldgen.pipeline.River
import kotlin.math.abs
import kotlin.math.sqrt

enum class MapView(val label: String) {
    FANTASY("Fantasy"),
    POLITICAL("Political"),
    CULTURES("Peoples"),
    ELEVATION("Elevation"),
    BIOMES("Biomes"),
    TEMPERATURE("Temperature"),
    // The warm and cold season of the cell's own hemisphere, not July and January, so the two
    // halves of the map can be read against each other rather than against the calendar.
    SUMMER_TEMPERATURE("Temperature, summer"),
    WINTER_TEMPERATURE("Temperature, winter"),
    RAINFALL("Rainfall"),
    SUMMER_RAINFALL("Rainfall, summer"),
    WINTER_RAINFALL("Rainfall, winter"),
    // "Tectonic plates" rather than "Plates", which in a list beside Rainfall and Winds reads as a
    // count of something. The enum constant is the wire name and does not move; see docs/CONVENTIONS.md.
    PLATES("Tectonic plates"),
    CURRENTS("Ocean currents"),
    WIND("Winds"),
    NORMALS("Normal map");

    /** Whether this view draws the land itself, and so should show standing water on it. */
    val showsTerrain: Boolean
        get() = this == FANTASY || this == POLITICAL || this == CULTURES ||
            this == ELEVATION || this == BIOMES

    /**
     * Whether the style chooses this view's colours outright.
     *
     * Narrower than [showsTerrain] by the two views that draw the land but are read against a
     * legend rather than for their looks: elevation means a height and biomes mean a vegetation,
     * and a style may rake their relief but must not repaint them. It is what decides where a
     * line-art style rules its water — a vignette across a temperature map's ocean would bury the
     * temperature.
     */
    val styled: Boolean
        get() = this == FANTASY || this == POLITICAL || this == CULTURES

    /**
     * Whether this view is about a flow field rather than the land. These carry direction arrows,
     * and suppress rivers, which would otherwise be mistaken for more of the same arrows.
     */
    val showsFlow: Boolean
        get() = this == CURRENTS || this == WIND
}

data class RenderOptions(
    val view: MapView = MapView.FANTASY,
    /**
     * How the finished map is drawn. Applies to the fantasy and political views; the diagnostic
     * views ignore it, because their colours carry meaning and a prettier ramp would make them lie.
     */
    val style: MapStyle = MapStyle.ATLAS,
    val showRivers: Boolean = true,
    /**
     * Which mark of [RiverSelection]'s density scale this sheet draws its rivers at.
     *
     * [RiverSelection.EARTH_DENSITY_STEP] is as much river line per square kilometre of land as a
     * published map at this sheet's scale draws; the marks below it thin toward a few trunks and
     * the top mark, [RiverSelection.EVERY_COURSE_STEP], removes the budget and draws every course
     * the scale allows. A setting of the drawing and not of the world, like [singleLamp] beside
     * it: turning it does not move a cell of ground, only the ink.
     */
    val riverInkStep: Int = RiverSelection.EARTH_DENSITY_STEP,
    val showCoastline: Boolean = true,
    val showHillshade: Boolean = true,
    /**
     * Light the relief from one lamp in the north-west instead of from the whole sky.
     *
     * The convention every shaded-relief map used before this one, kept because a reader may prefer
     * it and because it is the control the sky model is measured against. It is a setting of the
     * drawing rather than of the world: it lives in the panel beside the relief switch, never in a
     * save. See [ReliefShading].
     */
    val singleLamp: Boolean = false,
    /** Draws realm borders over whichever view is active, not just the political one. */
    val showBorders: Boolean = false,
    val showLandmarks: Boolean = false,
    val showLakes: Boolean = true,
    /**
     * Lines of latitude and longitude every [Graticule.DEGREES], with the edges figured.
     *
     * Off by default: a graticule is what turns a picture of a world into a chart of one, and most
     * readers want the picture first. See [Graticule].
     */
    val showGraticule: Boolean = false
) {
    /** The political view is realm colour — borders are implied by it and always drawn. */
    val bordersVisible: Boolean get() = showBorders || view == MapView.POLITICAL
}

/**
 * One straight run of a river: its two ends in cell coordinates, and how heavily it is stroked.
 *
 * A run is one cell long, so the stroke changes by a hair from run to run and the taper down a
 * river is drawn by the sequence rather than by any single segment. It is also why a confluence
 * needs no taper of its own: the step up to the trunk's width happens over a single cell, and a
 * round cap blends it.
 */
class RiverSegment(
    val fromX: Float,
    val fromY: Float,
    val toX: Float,
    val toY: Float,
    /** In output pixels, from [RiverPen]. */
    val widthPixels: Float
)

enum class GlyphShape { TRIANGLE, DIAMOND, SQUARE, CIRCLE }

/** A direction arrow for a flow field, in cell coordinates. */
class FlowArrow(
    val x: Float,
    val y: Float,
    /** Where the arrow points, as a unit vector: x eastward, y southward. */
    val directionX: Float,
    val directionY: Float,
    /** 0..1, so a platform can fade weak flow rather than drawing a forest of stubs. */
    val strength: Float,
    /** Per-arrow, because on the wind view the arrow colour is what distinguishes the belts. */
    val color: Int
)

/** A landmark marker: where, what shape, what colour. Drawing it is the platform's job. */
class LandmarkGlyph(
    val x: Float,
    val y: Float,
    val radius: Float,
    val shape: GlyphShape,
    val fill: Int
)

/**
 * The vector work that sits on top of the raster, described rather than drawn.
 *
 * Keeping this as geometry means Android and desktop make the same decisions about which segments
 * to skip, how wide a river runs and which glyph a landmark gets — and differ only in which
 * drawing API executes it. It is also where generalisation happens: what is in here is what
 * survives at the scale the map is being seen at, so the front end draws all of it and decides none
 * of it. See [MapSheet].
 */
class MapOverlay(
    val rivers: List<RiverSegment>,
    /** How many separate rivers those segments belong to, after [RiverSelection.drawnOn]. */
    val riversDrawn: Int,
    /**
     * The coast as polylines in cell coordinates, generalised for the sheet. Each is `x, y, x, y, …`
     * and a closed ring repeats its first point; see [Shoreline].
     */
    val coastline: List<FloatArray>,
    val landmarks: List<LandmarkGlyph>,
    /** Ocean or wind arrows, on the views that show them. */
    val flow: List<FlowArrow>,
    /**
     * How far a flow arrow may reach from its own point, in cells.
     *
     * Half the lattice pitch, so two neighbours drawn at full length meet nose to tail and no
     * further. A platform scales its whole arrow — shaft, head and barbs — off this one figure.
     */
    val flowArrowReachCells: Float,
    /** The graticule, when the reader asked for one. */
    val graticule: Graticule?,
    /** The scale bar, on a sheet that carries its own. See [MapSheet.carriesScaleBar]. */
    val scaleBar: PlacedScaleBar?,
    val riverColor: Int,
    /** The coast's ink, its alpha already carrying [MapStyle.coastlineStrength]. */
    val coastColor: Int,
    val coastWidth: Float,
    val graticuleColor: Int,
    val graticuleWidth: Float,
    /** The style's own ink and paper, for the marks that are writing rather than geography. */
    val marginInk: Int,
    val marginPaper: Int,
    val glyphOutline: Int,
    val glyphOutlineWidth: Float
) {
    /** True when there is nothing to draw, so a front end can leave the raster alone. */
    val isEmpty: Boolean
        get() = rivers.isEmpty() && coastline.isEmpty() && landmarks.isEmpty() &&
            flow.isEmpty() && graticule == null && scaleBar == null
}

/**
 * Renders a world to a pixel buffer.
 *
 * No graphics toolkit involved: the result is plain ARGB in an IntArray, which every platform can
 * wrap in its own bitmap type. This is also the part that dominates render cost, so sharing it
 * means both platforms get the same output and the same performance work.
 */
object MapRasterizer {

    const val WILDERNESS = MapPalette.WILDERNESS
    const val BORDER = MapPalette.BORDER

    /*
     * The flat colours the diagnostic views use where a ramp would say nothing: the sea on a
     * rainfall map, the land on a currents map, and the land/sea pair the wind arrows are read
     * against. Named rather than written inline so a [RasterAccelerator] can be handed the same
     * numbers instead of a second copy of them.
     */
    internal const val RAINFALL_SEA = 0xFF20303C.toInt()
    internal const val CURRENTS_LAND = 0xFF3A3A32.toInt()
    internal const val WIND_LAND_LOW = 0xFF4A4638.toInt()
    internal const val WIND_LAND_HIGH = 0xFF9A9384.toInt()
    internal const val WIND_SEA = 0xFF16242F.toInt()

    /**
     * Draws the map on [accelerator] if it will take the job, and on the CPU if it will not.
     *
     * The CPU remains the reference: an accelerator that cannot describe a view, or cannot reach a
     * device, returns null and this falls through to [rasterize] with nothing lost but the speed.
     */
    suspend fun rasterize(
        world: WorldMap,
        options: RenderOptions,
        accelerator: RasterAccelerator?
    ): IntArray {
        if (accelerator != null) {
            val recipe = RasterRecipe.of(world, options)
            if (recipe != null) accelerator.rasterize(recipe)?.let { return it }
        }
        return rasterize(world, options)
    }

    /** ARGB pixels, row-major, `world.width * world.height` long. */
    fun rasterize(world: WorldMap, options: RenderOptions = RenderOptions()): IntArray {
        val cellsAcross = world.width
        val cellsDown = world.height
        val cellCount = cellsAcross * cellsDown
        val pixels = IntArray(cellCount)

        val style = options.style
        val reliefDrawn = options.showHillshade && options.view != MapView.NORMALS
        // A line-art style never asks for the shaded relief, so it never pays for the pass: the
        // hachures read the same central differences a cell at a time.
        val relief = if (reliefDrawn && !style.lineArt) {
            ReliefShading.of(
                world.sea.relativeElevation, world.sea.isLand, options.singleLamp,
                world.config.cellHeightInCellWidths
            )
        } else null

        val lakes = world.rivers.lakes
        val showLakes = options.showLakes && options.view.showsTerrain

        // The engraving. The hachures replace the hillshade wherever the relief would have been
        // shaded, on every view, since how a style expresses relief has always been the style's
        // business. The water and the ice are only drawn where the style chooses the colours: a
        // diagnostic view's sea carries a temperature or an anomaly, and ruling it would bury the
        // thing it is there to show.
        val plan = if (style.lineArt) EngravingPlan(cellsAcross) else null
        val engraveWater = style.lineArt && options.view.styled
        val shore = if (plan != null) {
            ShoreDistance.of(cellsAcross, cellsDown, dryLandMask(world, showLakes))
        } else null
        val elevation = world.sea.relativeElevation

        // The two things only the fantasy view draws: the ramp modulated by the climate, and the
        // contours in the sea. The other views either mean something a legend explains (elevation,
        // biomes) or are about who holds the land rather than what it is, and repainting their
        // ground would make both harder to read.
        val painted = options.view == MapView.FANTASY
        val contoured = painted && style.isobathInk > 0f
        val isobathInterval =
            if (contoured) Isobaths.interval(world.config.scale) else 0f
        val flattestSlope =
            if (contoured) Isobaths.flattestSlope(world.config, cellsAcross, cellsDown) else 0f
        val isobathStencil = Isobaths.slopeStencil(cellsAcross)

        for (cell in 0 until cellCount) {
            val column = cell % cellsAcross
            val row = cell / cellsAcross
            if (showLakes && lakes.isLake(cell)) {
                // Depth from how far the water surface sits above the ground beneath it, so a
                // deep basin reads darker than a shallow flood. The surface is the lake's own,
                // not the filled elevation: an endorheic lake stands below the brim the fill
                // raised its basin to, and reading the depth off the fill would draw a shallow
                // desert lake as if it were full to the rim.
                val depthAboveBed = lakes.surfaceAt(cell) - elevation.data[cell]
                var water = MapPalette.blend(
                    style.lake,
                    style.lakeDeep,
                    (depthAboveBed * DARKEST_LAKE_PER_UNIT_DEPTH).coerceIn(0f, 1f)
                )
                if (plan != null && engraveWater) {
                    water = MapPalette.blend(
                        water, style.coastline, Engraving.lakeWater(row, shore!![cell], plan)
                    )
                }
                pixels[cell] = water
                continue
            }

            var color = baseColor(
                world, options.view, style, cell, isobathInterval, flattestSlope, isobathStencil
            )
            val isLand = world.sea.isLand[cell]
            if (plan != null && engraveWater && !isLand) {
                color = MapPalette.blend(
                    color, style.coastline, Engraving.coastalWater(shore!![cell], plan)
                )
            }
            if (reliefDrawn && isLand) {
                if (plan != null) {
                    // Ink rather than shading: the paper is left alone and strokes are laid down
                    // the slope, heavier where the ground is steeper, which is how a pen draws a
                    // mountain when it has no colour to draw it with.
                    val reach = plan.gradientStencilCells
                    val gradientX =
                        (elevation.sample(column + reach, row) -
                            elevation.sample(column - reach, row)) * plan.gradientScale
                    val gradientY =
                        (elevation.sample(column, row + reach) -
                            elevation.sample(column, row - reach)) * plan.gradientScale
                    color = MapPalette.blend(
                        color,
                        style.coastline,
                        Engraving.hachure(
                            column, row, gradientX, gradientY, world.config.cellHeightInCellWidths.toFloat(),
                            plan, style.inkGain
                        )
                    )
                } else {
                    color = MapPalette.shade(color, style.relief(relief!![cell]))
                }
            }
            if (plan != null && engraveWater &&
                world.climate.biome[cell] == Biome.ICE_SHEET
            ) {
                color = MapPalette.blend(
                    color, style.coastline, Engraving.stipple(column, row, plan)
                )
            }
            pixels[cell] = color
        }

        if (options.showCoastline) drawCoastline(world, style, pixels)
        if (options.bordersVisible) drawBorders(world, style, plan, pixels)
        return pixels
    }

    /**
     * Where the engraving's distance field is seeded: land that is not under standing water.
     *
     * One field answers both questions the drawing asks of it. Out at sea it is the distance to the
     * coast, which the vignette's lines follow; inside a lake it is the distance to that lake's own
     * shore, which the water lines fade with — because a lake is surrounded by dry land, its cells
     * measure to their own bank and to nothing else.
     */
    internal fun dryLandMask(world: WorldMap, showLakes: Boolean): ByteArray {
        val land = world.sea.isLand
        val lakes = world.rivers.lakes
        return ByteArray(land.size) { cell ->
            if (land[cell] && !(showLakes && lakes.isLake(cell))) 1 else 0
        }
    }

    /**
     * The coast, the rivers, the graticule and the landmarks to lay over the raster, as geometry.
     *
     * [sheet] is what the drawing is for, and is the whole of the generalisation: at one pixel to
     * the cell — an export, or any offline render — every river is drawn and the coast keeps every
     * bend it has; shown smaller than that, the smallest rivers go and the coast is simplified to
     * the tolerance the sheet can actually show. See [MapSheet].
     */
    fun overlay(
        world: WorldMap,
        options: RenderOptions = RenderOptions(),
        sheet: MapSheet = MapSheet.UNGENERALISED
    ): MapOverlay {
        val cellsAcross = world.width
        val rivers = ArrayList<RiverSegment>()
        val skipInLakes = options.showLakes && options.view.showsTerrain
        val water = world.rivers.lakes

        val drawnRivers =
            if (options.showRivers && !options.view.showsFlow) {
                RiverSelection.drawnOn(world, sheet, options.riverInkStep)
            } else {
                emptyList()
            }

        drawnRivers.forEach { river ->
            val course = trimmedAtTheShore(world, river, cellsAcross)
            for (vertex in 0 until course.vertexCount - 1) {
                val fromCell = river.cells[vertex]
                val toCell = river.cells[vertex + 1]
                val fromColumn = fromCell % cellsAcross
                val toColumn = toCell % cellsAcross
                val fromY = (fromCell / cellsAcross) + HALF_A_CELL
                val toY = (toCell / cellsAcross) + HALF_A_CELL
                val widthPixels =
                    RiverPen.widthPixels(river.widthRatio[vertex], cellsAcross)
                // The last drawn segment stops short of the water, so the round cap's outer
                // edge lands on the shoreline; [trimmedAtTheShore] says why.
                val reach =
                    if (vertex == course.vertexCount - 2) course.lastFraction else 1f

                // Inside open water the river *is* the lake. Drawing it would put a channel
                // across the surface — and these are exactly the segments that run uphill on
                // raw terrain, because the basin was raised to let the water out. A strip of
                // lake one cell wide is not open water and is drawn; see `LakeResult.openWater`.
                if (skipInLakes && water.isOpenWater(fromCell) && water.isOpenWater(toCell)) {
                    continue
                }

                if (abs(toColumn - fromColumn) > cellsAcross / 2) {
                    // The river crosses the east-west seam. Drawing it as-is would streak a
                    // line back across the whole map, but dropping it leaves the river visibly
                    // stopping dead at the edge. Draw it twice instead, shifted a map-width
                    // each way, so it runs off one side and arrives on the other.
                    val toColumnAhead =
                        if (toColumn > fromColumn) toColumn - cellsAcross
                        else toColumn + cellsAcross
                    rivers.add(
                        RiverSegment(
                            fromColumn + HALF_A_CELL, fromY,
                            fromColumn + HALF_A_CELL + reach * (toColumnAhead - fromColumn),
                            fromY + reach * (toY - fromY), widthPixels
                        )
                    )
                    val fromColumnBehind =
                        if (toColumn > fromColumn) fromColumn + cellsAcross
                        else fromColumn - cellsAcross
                    rivers.add(
                        RiverSegment(
                            fromColumnBehind + HALF_A_CELL, fromY,
                            fromColumnBehind + HALF_A_CELL + reach * (toColumn - fromColumnBehind),
                            fromY + reach * (toY - fromY), widthPixels
                        )
                    )
                    continue
                }

                rivers.add(
                    RiverSegment(
                        fromColumn + HALF_A_CELL, fromY,
                        fromColumn + HALF_A_CELL + reach * (toColumn - fromColumn),
                        fromY + reach * (toY - fromY), widthPixels
                    )
                )
            }
        }

        // Flow arrows on a coarse lattice: one every `latticePitchCells`, so the density stays
        // legible whatever the map resolution.
        val flow = ArrayList<FlowArrow>()
        var flowArrowReachCells = 1f
        if (options.view.showsFlow) {
            val cellsDown = world.height
            val arrowsAcross =
                if (options.view == MapView.WIND) WIND_ARROWS_ACROSS else CURRENT_ARROWS_ACROSS
            val latticePitchCells = (cellsAcross / arrowsAcross).coerceAtLeast(CLOSEST_ARROWS_CELLS)
            flowArrowReachCells = latticePitchCells * HALF_A_CELL
            var row = latticePitchCells / 2
            while (row < cellsDown) {
                var column = latticePitchCells / 2
                while (column < cellsAcross) {
                    val cell = row * cellsAcross + column
                    if (options.view == MapView.CURRENTS) {
                        if (!world.sea.isLand[cell]) {
                            val eastward = world.ocean.velocityX.data[cell]
                            val southward = world.ocean.velocityY.data[cell]
                            val speed =
                                kotlin.math.sqrt(eastward * eastward + southward * southward)
                            if (speed > STILLEST_DRAWN_CURRENT) {
                                flow.add(
                                    FlowArrow(
                                        column + HALF_A_CELL, row + HALF_A_CELL,
                                        eastward / speed, southward / speed,
                                        (speed / world.config.ocean.speedCellsPerPass)
                                            .coerceIn(0f, 1f),
                                        CURRENT_ARROW_INK
                                    )
                                )
                            }
                        }
                    } else {
                        // The wind is a vector: a zonal direction and a slant across the latitude
                        // lines. Drawn as one arrow, so the three circulation cells read as the
                        // cells they are — trades spiralling in toward the equator, westerlies
                        // carrying poleward — rather than as three stripes of east and west.
                        val eastward = world.climate.windDirection[cell].toFloat()
                        val southward = world.climate.windMeridional.data[cell]
                        val length =
                            kotlin.math.sqrt(eastward * eastward + southward * southward)
                        val ink = if (eastward > 0) WESTERLY_ARROW_INK else EASTERLY_ARROW_INK
                        flow.add(
                            FlowArrow(
                                column + HALF_A_CELL, row + HALF_A_CELL,
                                eastward / length, southward / length, WIND_ARROW_STRENGTH, ink
                            )
                        )
                    }
                    column += latticePitchCells
                }
                row += latticePitchCells
            }
        }

        val glyphs = ArrayList<LandmarkGlyph>()
        // Purely a fraction of the map, so a glyph covers the same share of the picture at every
        // size — unlike the river pen beside it, which is a share of the sheet with a floor.
        val glyphRadiusPixels =
            (cellsAcross / GLYPH_RADII_ACROSS_MAP).coerceAtLeast(SMALLEST_GLYPH_PIXELS)
        if (options.showLandmarks && !options.view.showsFlow) {
            world.landmarks.landmarks.forEach { landmark ->
                glyphs.add(
                    LandmarkGlyph(
                        x = (landmark.cell % cellsAcross) + HALF_A_CELL,
                        y = (landmark.cell / cellsAcross) + HALF_A_CELL,
                        radius = glyphRadiusPixels,
                        shape = shapeFor(landmark.kind),
                        fill = options.style.glyph(colorFor(landmark.kind))
                    )
                )
            }
        }

        val coast =
            if (options.showCoastline) {
                Shoreline.of(world.sea.isLand, cellsAcross, world.height, sheet)
            } else {
                emptyList()
            }

        return MapOverlay(
            rivers = rivers,
            riversDrawn = drawnRivers.size,
            coastline = coast,
            landmarks = glyphs,
            flow = flow,
            flowArrowReachCells = flowArrowReachCells,
            graticule =
                if (options.showGraticule) Graticule.of(cellsAcross, world.height) else null,
            scaleBar = if (sheet.carriesScaleBar) placedScaleBar(world) else null,
            riverColor = options.style.river,
            coastColor = withAlpha(options.style.coastline, options.style.coastlineStrength),
            coastWidth = coastPenPixels(cellsAcross),
            graticuleColor = withAlpha(options.style.coastline, GRATICULE_INK_STRENGTH),
            graticuleWidth = RiverPen.HAIRLINE_PIXELS,
            marginInk = options.style.coastline,
            marginPaper = options.style.paper,
            glyphOutline = if (glyphs.isEmpty()) UNSTYLED_GLYPH_OUTLINE else options.style.coastline,
            glyphOutlineWidth =
                if (glyphs.isEmpty()) THINNEST_LINE_PIXELS
                else (glyphRadiusPixels * GLYPH_OUTLINE_SHARE_OF_RADIUS)
                    .coerceAtLeast(THINNEST_LINE_PIXELS)
        )
    }

    /**
     * The scale bar, tucked into the sheet's bottom-left corner clear of the margin figures.
     *
     * Above the row a graticule puts its longitude figures on and to the right of the column it
     * puts its latitudes in, so a sheet drawn with the graticule on and one drawn with it off put
     * the bar in the same place and neither writes anything over anything else.
     */
    private fun placedScaleBar(world: WorldMap): PlacedScaleBar {
        val figure = Graticule.labelHeightPixels(
            world.width.toFloat() * Graticule.DEGREES / DEGREES_OF_LONGITUDE
        )
        return PlacedScaleBar(
            bar = MapScale.longestBarThatFits(
                MapScale.kilometresPerPixel(world.config.scale, world.width, 1f),
                world.width.toFloat()
            ),
            x = figure * MARGIN_FIGURES_CLEARED,
            y = world.height - figure * MARGIN_FIGURES_CLEARED,
            figureHeightPixels = figure
        )
    }

    /**
     * How heavily the traced coast is stroked, in output pixels, on a sheet [cellsAcross] wide.
     *
     * The raster inks one cell of coast, which on the sheet is one pixel, and the stroke matches
     * that at 2048 so that turning it on sharpens the coast rather than doubling it. Held as a
     * share of the sheet from there — the reasoning [RiverPen] gives for the river pen — so a plate
     * twice as large carries a coast twice as heavy, with a floor of one whole pixel because below
     * that a line is a grey suggestion rather than a coast.
     */
    internal fun coastPenPixels(cellsAcross: Int): Float =
        (cellsAcross * COAST_SHARE_OF_MAP_WIDTH).coerceAtLeast(1f)

    private const val COAST_SHARE_OF_MAP_WIDTH = 0.0005f

    /**
     * Half a cell, in cell coordinates: the offset from a cell's index to its centre.
     *
     * Everything the overlay describes is placed at cell *centres*, because that is where the
     * raster puts a cell's colour; a river drawn through cell corners would run half a cell north
     * and west of the water it is meant to be.
     */
    private const val HALF_A_CELL = 0.5f

    /**
     * How many arrows the wind and current lattices lay across the map.
     *
     * Wind is a smooth zonal field and says all it has to say in twenty-two arrows a row; the
     * currents turn round every gyre and need twice that again before the pattern reads. Packed
     * any tighter than this either becomes a carpet.
     */
    private const val WIND_ARROWS_ACROSS = 22
    private const val CURRENT_ARROWS_ACROSS = 48

    /**
     * How close two arrows may ever be, in cells.
     *
     * At six cells an arrow and its head still have a cell of paper round them. It only binds on a
     * small map: a 128 world would otherwise put the currents' forty-eight arrows two cells apart.
     */
    private const val CLOSEST_ARROWS_CELLS = 6

    /**
     * The slowest current that still gets an arrow, in cells per pass.
     *
     * A thousandth of a cell a pass is a gyre's dead centre and the corners of enclosed seas, where
     * the direction is numerical noise: an arrow there points somewhere definite and means nothing.
     */
    private const val STILLEST_DRAWN_CURRENT = 1e-3f

    /**
     * How strongly a wind arrow is drawn.
     *
     * Flat, unlike a current's, because the strength a front end fades an arrow by is a *speed* and
     * the wind view carries direction rather than speed — the belts are told apart by the arrow
     * colours below. Short of 1 so the wind still reads as lighter than the strongest current.
     */
    private const val WIND_ARROW_STRENGTH = 0.85f

    /** Near-white, so a current reads over the whole anomaly ramp, warm end and cold. */
    private const val CURRENT_ARROW_INK = 0xFFF2F6FA.toInt()

    /** Cool for the eastward belts, warm for the westward, so the three cells read at a glance. */
    private const val WESTERLY_ARROW_INK = 0xFF7FC0F0.toInt()
    private const val EASTERLY_ARROW_INK = 0xFFF0A860.toInt()

    /**
     * How big a landmark marker is: how many of its radii fit across the map, and its floor in
     * output pixels.
     *
     * A hundred and ninety radii across the sheet puts about a dozen marker widths across a
     * continent, which is where a marker is a mark on the map rather than a symbol competing with
     * it. Purely a share of the width, unlike the river pen beside it: a marker stands for a thing
     * on the ground rather than being a stroke of the nib, so it grows with the sheet. The floor is
     * two pixels, below which a triangle and a diamond are the same four dots.
     */
    private const val GLYPH_RADII_ACROSS_MAP = 190f
    private const val SMALLEST_GLYPH_PIXELS = 2f

    /** A glyph's outline, as a share of its radius: heavy enough to read over a busy ground. */
    private const val GLYPH_OUTLINE_SHARE_OF_RADIUS = 0.28f

    /** The finest stroke a front end is ever asked for. Below one pixel a line is a grey hint. */
    private const val THINNEST_LINE_PIXELS = 1f

    /**
     * How fast a lake darkens with the water standing over its bed, per unit of relative
     * elevation.
     *
     * A twelfth of the height field's land range, which at the generator's default 6,000 m of
     * relief is 500 m of water: a lake that deep is drawn in [MapStyle.lakeDeep] outright and
     * everything shallower is somewhere between the two. Off the water *above the bed* rather than
     * off the filled surface, because an endorheic lake stands below the brim the fill raised its
     * basin to and reading the fill would draw a shallow desert lake as if it were full.
     */
    private const val DARKEST_LAKE_PER_UNIT_DEPTH = 12f

    /**
     * How far a realm's or a people's colour is let down toward the relief beneath it.
     *
     * Three tenths keeps the mountains showing through, so a political map still reads as a map of
     * somewhere rather than as a flat chart, and leaves seven tenths of the fill — which is what
     * `ClearStyleTest` measures the colour-blind set's separations over.
     */
    internal const val REALM_RELIEF_BLEED = 0.3f

    /**
     * Over how many cells the plate colour gives way to what its nearest boundary builds.
     *
     * Twelve, which is about the width of the belts the tectonics stage raises, so the view says
     * which pair of crusts met rather than only where they met.
     */
    private const val PLATE_BOUNDARY_FADE_CELLS = 12f

    /** How firmly a realm border is inked over the ground it crosses, where the style has no pen. */
    private const val BORDER_INK_STRENGTH = 0.75f

    /**
     * How firmly the graticule is inked, against the coast's own strength.
     *
     * A graticule is a reference grid and not a feature: it has to be findable and must never be
     * mistaken for a coast or a border, so it is drawn in the same ink at a third of the weight.
     */
    private const val GRATICULE_INK_STRENGTH = 0.34f

    /** How many figure-heights in from the corner the scale bar sits. See [placedScaleBar]. */
    private const val MARGIN_FIGURES_CLEARED = 3.2f

    private const val DEGREES_OF_LONGITUDE = 360

    /**
     * The glyph outline on a map with no glyphs on it.
     *
     * Kept because [MapOverlay.glyphOutline] has always carried a value whether or not there were
     * landmarks to outline, and a front end may read it either way.
     */
    private const val UNSTYLED_GLYPH_OUTLINE = 0xFF241C14.toInt()

    /** [color] at [strength] of its opacity, for ink laid over a finished raster. */
    private fun withAlpha(color: Int, strength: Float): Int {
        val alpha = (255f * strength.coerceIn(0f, 1f)).toInt()
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    /**
     * How much of a river's course to draw: every vertex up to [vertexCount], with the last
     * segment run only [lastFraction] of the way to its far end.
     */
    private class DrawnCourse(val vertexCount: Int, val lastFraction: Float)

    /**
     * A course cut back so that the ink stops at the shoreline instead of pooling beyond it.
     *
     * A traced course ends *in* the water: the last cell is the sea cell, or the open-water cell,
     * that the last cell on land drains into, kept so the line reaches the water rather than
     * stopping a step short of it. Drawn literally that puts the centre of the stroke a whole cell
     * past the coast, and the round cap that blends one cell-long segment into the next then adds
     * half a stroke on top of that. Under the five-pixel pen that preceded this the ink reached
     * about three pixels past the shoreline — a blob of river sitting on the open sea. See
     * docs/DESIGN_LEDGER.md, F15, for the per-seed figures.
     *
     * A round cap centred half a stroke back from the shore, on the other hand, is tangent to it:
     * the last pixel of the stroke is the shoreline pixel and the sea takes over with no seam. So
     * the course is walked back from the shore — which is the edge between the last cell on land
     * and the water, half a step past that cell's centre — until half a stroke has been given up,
     * and the stroke ends there. Every vertex closer than that goes, because a round cap at a
     * vertex within half a stroke of the shore would cross it just as the end cap did.
     *
     * A course that ends on land is a tributary stopping on the trunk it joins, and is left whole.
     */
    private fun trimmedAtTheShore(world: WorldMap, river: River, cellsAcross: Int): DrawnCourse {
        val cells = river.cells
        val whole = DrawnCourse(cells.size, 1f)
        if (cells.size < 3) return whole
        val mouth = cells[cells.size - 1]
        val lastOnLand = cells.size - 2
        val intoWater = !world.sea.isLand[mouth] || world.rivers.lakes.isOpenWater(mouth)
        if (!intoWater) return whole

        val halfStrokePixels =
            RiverPen.widthPixels(river.widthRatio[lastOnLand], cellsAcross) / 2f
        val toTheShoreCells = stepLength(cells[lastOnLand], mouth, cellsAcross) / 2f
        if (toTheShoreCells >= halfStrokePixels) {
            // The stroke ends on the last step, between that cell's centre and the shoreline.
            return DrawnCourse(
                cells.size, (toTheShoreCells - halfStrokePixels) / (2f * toTheShoreCells)
            )
        }

        var owedCells = halfStrokePixels - toTheShoreCells
        var vertex = lastOnLand
        while (vertex > 0) {
            val stepCells = stepLength(cells[vertex - 1], cells[vertex], cellsAcross)
            if (owedCells <= stepCells) {
                return DrawnCourse(vertex + 1, (stepCells - owedCells) / stepCells)
            }
            owedCells -= stepCells
            vertex--
        }
        // A course shorter than its own pen: leave it whole rather than draw a dot. It takes a
        // headwater-length reach carrying a trunk's water, which the widths make near impossible.
        return whole
    }

    /** Distance between two cells' centres, in cells, across the east-west seam if need be. */
    private fun stepLength(from: Int, to: Int, cellsAcross: Int): Float {
        var columns = to % cellsAcross - from % cellsAcross
        if (columns > cellsAcross / 2) columns -= cellsAcross
        if (columns < -cellsAcross / 2) columns += cellsAcross
        val rows = to / cellsAcross - from / cellsAcross
        return sqrt((columns * columns + rows * rows).toFloat())
    }

    /** Shape carries the kind, so the map stays readable in greyscale and without a legend. */
    private fun shapeFor(kind: LandmarkKind): GlyphShape = when (kind) {
        LandmarkKind.MONSTER_LAIR, LandmarkKind.HAZARD -> GlyphShape.TRIANGLE
        LandmarkKind.RESOURCE -> GlyphShape.DIAMOND
        LandmarkKind.DUNGEON, LandmarkKind.RUIN -> GlyphShape.SQUARE
        LandmarkKind.WONDER, LandmarkKind.SANCTUARY -> GlyphShape.CIRCLE
    }

    private fun colorFor(kind: LandmarkKind): Int = when (kind) {
        LandmarkKind.MONSTER_LAIR -> 0xFFB4443A.toInt()
        LandmarkKind.HAZARD -> 0xFFD8862E.toInt()
        LandmarkKind.DUNGEON -> 0xFF4A3B63.toInt()
        LandmarkKind.RUIN -> 0xFF8C8175.toInt()
        LandmarkKind.RESOURCE -> 0xFFC8A63C.toInt()
        LandmarkKind.WONDER -> 0xFF3E9C8F.toInt()
        LandmarkKind.SANCTUARY -> 0xFFE8E2D0.toInt()
    }

    /**
     * The water on the two views that colour land by who holds it.
     *
     * Ordinarily the shared ocean ramp, because these views are about the land and their sea is
     * only the shape around it — and because a political map that changed colour with the style
     * would make twelve political maps out of one. Two styles are exceptions, and
     * [MapStyle.ownsPoliticalGround] says why.
     */
    private fun politicalSea(style: MapStyle, relative: Float): Int =
        if (style.ownsPoliticalGround) style.ocean(-relative) else MapPalette.ocean(-relative)

    /** The relief the realm colour is blended toward, from the same style-or-shared rule. */
    private fun politicalLand(style: MapStyle, relative: Float): Int =
        if (style.ownsPoliticalGround) style.land(relative) else MapPalette.land(relative)

    /**
     * The colour a cell starts as, before the relief, the air, the engraving and the coast.
     *
     * [isobathInterval] is the depth between contours as a fraction of the field's own range, or 0
     * on the views and styles that draw none, and [flattestSlope] the gradient below which the sea
     * floor is a plain and carries none.
     */
    private fun baseColor(
        world: WorldMap,
        view: MapView,
        style: MapStyle,
        cell: Int,
        isobathInterval: Float,
        flattestSlope: Float,
        isobathStencil: Int
    ): Int {
        val isLand = world.sea.isLand[cell]
        val relative = world.sea.relativeElevation.data[cell]

        return when (view) {
            MapView.FANTASY ->
                if (!isLand) {
                    val water = style.ocean(-relative)
                    if (isobathInterval <= 0f) {
                        water
                    } else {
                        val contour = seaContour(
                            world, cell, -relative, isobathInterval, flattestSlope, isobathStencil
                        )
                        MapPalette.blend(water, style.coastline, style.isobathInk * contour)
                    }
                } else {
                    // The hypsometric tint carries the shape and the climate bends it — a desert is
                    // sand at any height, frozen ground is pale, a wood is dark. On top of that goes
                    // the wash of biome colour the style has always had. How much of either gets
                    // through is most of what separates one style from another.
                    val biome = world.climate.biome[cell]
                    if (style.climateTint <= 0f) {
                        style.ground(relative, 0f, 0f, 0f, biome)
                    } else {
                        style.ground(
                            relative,
                            ClimateTint.drynessAt(world, cell),
                            ClimateTint.coldnessAt(world, cell),
                            ClimateTint.canopyAt(world, cell),
                            biome
                        )
                    }
                }

            MapView.POLITICAL -> {
                val owner = world.nations.nationId[cell]
                when {
                    !isLand -> politicalSea(style, relative)
                    owner == NationResult.UNCLAIMED -> style.wilderness
                    else -> MapPalette.blend(
                        style.realmFill(owner, cell % world.width, cell / world.width),
                        politicalLand(style, relative),
                        REALM_RELIEF_BLEED
                    )
                }
            }

            MapView.CULTURES -> {
                val people = world.cultures.cultureId[cell]
                when {
                    !isLand -> politicalSea(style, relative)
                    people == CultureResult.UNSETTLED -> style.wilderness
                    // The same bleed as the political map, so the two read as the same world seen
                    // two ways rather than as two unrelated charts.
                    else -> MapPalette.blend(
                        style.peopleFill(people, cell % world.width, cell / world.width),
                        politicalLand(style, relative),
                        REALM_RELIEF_BLEED
                    )
                }
            }

            MapView.ELEVATION ->
                if (isLand) MapPalette.land(relative) else MapPalette.ocean(-relative)

            MapView.BIOMES -> MapPalette.biome(world.climate.biome[cell])

            MapView.TEMPERATURE -> MapPalette.temperature(world.climate.temperature.data[cell])

            MapView.SUMMER_TEMPERATURE ->
                MapPalette.temperature(world.climate.summerTemperature.data[cell])

            MapView.WINTER_TEMPERATURE ->
                MapPalette.temperature(world.climate.winterTemperature.data[cell])

            MapView.RAINFALL ->
                if (isLand) MapPalette.precipitation(world.climate.precipitation.data[cell])
                else RAINFALL_SEA

            MapView.SUMMER_RAINFALL ->
                if (isLand) MapPalette.precipitation(world.climate.summerPrecipitation.data[cell])
                else RAINFALL_SEA

            MapView.WINTER_RAINFALL ->
                if (isLand) MapPalette.precipitation(world.climate.winterPrecipitation.data[cell])
                else RAINFALL_SEA

            MapView.PLATES -> {
                val plateColor = MapPalette.plate(world.plates.plateId[cell])
                // Toward the boundaries the plate colour gives way to what that boundary builds,
                // so the view says which pair of crusts met rather than only where they met.
                val awayFromBoundary = (world.plates.boundaryDistance.data[cell] /
                    PLATE_BOUNDARY_FADE_CELLS).coerceIn(0f, 1f)
                MapPalette.blend(
                    MapPalette.boundaryClass(world.plates.nearestBoundaryClass[cell]),
                    plateColor,
                    awayFromBoundary
                )
            }

            MapView.CURRENTS ->
                if (isLand) CURRENTS_LAND
                else MapPalette.temperatureAnomaly(world.ocean.anomaly.data[cell])

            // Land and sea have to stay apart, or the arrows sit on undifferentiated ground. The
            // belts themselves are carried by the arrow colours; tinting the ground as well only
            // costs the land/sea contrast the arrows are read against.
            MapView.WIND ->
                if (isLand) {
                    MapPalette.blend(
                        WIND_LAND_LOW, WIND_LAND_HIGH,
                        world.sea.relativeElevation.data[cell].coerceIn(0f, 1f)
                    )
                } else {
                    WIND_SEA
                }

            MapView.NORMALS -> {
                val normal =
                    world.terrain.normals.normalAt(cell % world.width, cell / world.width)
                // A normal runs -1..1 on each axis and a channel runs 0..255, which is what the
                // half-and-half is: the flat ground of an unlit normal map comes out mid-grey.
                argb(
                    ((normal[0] * 0.5f + 0.5f) * 255).toInt(),
                    ((normal[1] * 0.5f + 0.5f) * 255).toInt(),
                    ((normal[2] * 0.5f + 0.5f) * 255).toInt()
                )
            }
        }
    }

    /**
     * How strongly this sea pixel takes the contour ink.
     *
     * The line's width is held in pixels rather than in metres of depth, so the arithmetic needs to
     * know how fast the floor falls here — a central difference over [stencil] cells each way,
     * divided by the distance it spans, which is the depth a single pixel of travel covers. Over a
     * stencil rather than between neighbours, for the reason [Isobaths.slopeStencil] gives.
     *
     * Internal rather than private so that `IsobathTest` holds its own measurement to this one on a
     * generated world instead of trusting a copy of it.
     */
    internal fun seaContour(
        world: WorldMap,
        cell: Int,
        depth: Float,
        interval: Float,
        flattestSlope: Float,
        stencil: Int
    ): Float = seaContour(
        world.sea.relativeElevation, world.config.cellHeightInCellWidths, cell, depth, interval,
        flattestSlope, stencil
    )

    /**
     * [seaContour] on a bare [elevation] field whose rows are [cellHeightInCellWidths] as tall as
     * its columns are wide: what the raster draws, on a sea floor `IsobathTest` can make.
     */
    internal fun seaContour(
        elevation: FloatField,
        cellHeightInCellWidths: Double,
        cell: Int,
        depth: Float,
        interval: Float,
        flattestSlope: Float,
        stencil: Int
    ): Float {
        val cellsAcross = elevation.width
        val column = cell % cellsAcross
        val row = cell / cellsAcross
        val perCell = 1f / (2f * stencil)
        val eastward =
            (elevation.sample(column + stencil, row) -
                elevation.sample(column - stencil, row)) * perCell
        val southward =
            (elevation.sample(column, row + stencil) -
                elevation.sample(column, row - stencil)) * perCell
        // Per pixel for the line's width on the sheet, and per cell width of ground for whether
        // the floor is a plain: down a column a pixel is a row, a share of a cell width.
        val slopePerPixel = sqrt(eastward * eastward + southward * southward)
        val southwardOnTheGround = southward / cellHeightInCellWidths.toFloat()
        val slopeOnTheGround = sqrt(eastward * eastward + southwardOnTheGround * southwardOnTheGround)
        return Isobaths.ink(depth, slopePerPixel, slopeOnTheGround, interval, flattestSlope)
    }

    private fun drawCoastline(world: WorldMap, style: MapStyle, pixels: IntArray) {
        val cellsAcross = world.width
        val cellsDown = world.height
        val land = world.sea.isLand
        for (row in 0 until cellsDown) {
            for (column in 0 until cellsAcross) {
                val cell = row * cellsAcross + column
                if (!land[cell]) continue
                val toTheEast = land[row * cellsAcross + (column + 1) % cellsAcross]
                // The southern edge of the sheet has no cell beyond it, and a pole is not a coast:
                // taken as land, so the bottom row is never inked along its whole width.
                val toTheSouth =
                    if (row + 1 < cellsDown) land[(row + 1) * cellsAcross + column] else true
                if (!toTheEast || !toTheSouth) {
                    pixels[cell] = MapPalette.blend(
                        pixels[cell], style.coastline, style.coastlineStrength
                    )
                }
            }
        }
    }

    /**
     * Marks a cell whenever the realm to its east or south differs. Only land-to-land transitions
     * count, so a realm's coastline is left to the coastline pass rather than being outlined twice.
     *
     * A [plan] means the style draws with a pen, and a pen draws a boundary as a dotted line: the
     * cells that qualify are broken up by [Engraving.borderDot] and the ones that survive take the
     * border colour outright rather than three quarters of it.
     */
    private fun drawBorders(
        world: WorldMap,
        style: MapStyle,
        plan: EngravingPlan?,
        pixels: IntArray
    ) {
        val cellsAcross = world.width
        val cellsDown = world.height
        val owner = world.nations.nationId
        val land = world.sea.isLand

        for (row in 0 until cellsDown) {
            for (column in 0 until cellsAcross) {
                val cell = row * cellsAcross + column
                if (!land[cell]) continue
                val toTheEast = row * cellsAcross + (column + 1) % cellsAcross
                val toTheSouth =
                    if (row + 1 < cellsDown) (row + 1) * cellsAcross + column else cell

                val differs = (land[toTheEast] && owner[toTheEast] != owner[cell]) ||
                    (land[toTheSouth] && owner[toTheSouth] != owner[cell])
                if (!differs) continue
                if (plan == null) {
                    pixels[cell] =
                        MapPalette.blend(pixels[cell], style.border, BORDER_INK_STRENGTH)
                } else if (Engraving.borderDot(column, row, plan)) {
                    pixels[cell] = MapPalette.blend(pixels[cell], style.border, 1f)
                }
            }
        }
    }

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or
            (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or
            b.coerceIn(0, 255)
}
