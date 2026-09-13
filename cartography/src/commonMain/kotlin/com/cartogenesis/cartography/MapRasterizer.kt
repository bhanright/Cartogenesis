package com.cartogenesis.cartography

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
    PLATES("Plates"),
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
 * One straight run of a river: its ends in cell coordinates, its [width] in output pixels.
 *
 * A run is one cell long, so the stroke changes by a hair from run to run and the taper down a
 * river is drawn by the sequence rather than by any single segment. It is also why a confluence
 * needs no taper of its own: the step up to the trunk's width happens over a single cell, and a
 * round cap blends it.
 */
class RiverSegment(
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    val width: Float
)

enum class GlyphShape { TRIANGLE, DIAMOND, SQUARE, CIRCLE }

/** A direction arrow for a flow field, in cell coordinates. */
class FlowArrow(
    val x: Float,
    val y: Float,
    val dx: Float,
    val dy: Float,
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
 * drawing API executes it. Since F14 it is also where generalisation happens: what is in here is
 * what survives at the scale the map is being seen at, so the front end draws all of it and decides
 * none of it.
 */
class MapOverlay(
    val rivers: List<RiverSegment>,
    /** How many separate rivers those segments belong to, after [MapSheet.featuresKept]. */
    val riversDrawn: Int,
    /**
     * The coast as polylines in cell coordinates, generalised for the sheet. Each is `x, y, x, y, …`
     * and a closed ring repeats its first point; see [Shoreline].
     */
    val coastline: List<FloatArray>,
    val landmarks: List<LandmarkGlyph>,
    /** Ocean or wind arrows, on the views that show them. */
    val flow: List<FlowArrow>,
    /** Spacing of the arrow lattice in cells, so a platform can size arrows to fit between them. */
    val flowScale: Float,
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
        val w = world.width
        val h = world.height
        val pixels = IntArray(w * h)

        val style = options.style
        val reliefDrawn = options.showHillshade && options.view != MapView.NORMALS
        // A line-art style never asks for the shaded relief, so it never pays for the pass: the
        // hachures read the same central differences a cell at a time.
        val relief = if (reliefDrawn && !style.lineArt) {
            ReliefShading.of(world.sea.relativeElevation, world.sea.isLand, options.singleLamp)
        } else null

        val lakes = world.rivers.lakes
        val showLakes = options.showLakes && options.view.showsTerrain

        // The engraving. The hachures replace the hillshade wherever the relief would have been
        // shaded, on every view, since how a style expresses relief has always been the style's
        // business. The water and the ice are only drawn where the style chooses the colours: a
        // diagnostic view's sea carries a temperature or an anomaly, and ruling it would bury the
        // thing it is there to show.
        val plan = if (style.lineArt) EngravingPlan(w) else null
        val engraveWater = style.lineArt && options.view.styled
        val shore = if (plan != null) {
            ShoreDistance.of(w, h, dryLandMask(world, showLakes))
        } else null
        val elevation = world.sea.relativeElevation

        // The two things only the fantasy view draws: the ramp modulated by the climate, and the
        // contours in the sea. The other views either mean something a legend explains (elevation,
        // biomes) or are about who holds the land rather than what it is, and repainting their
        // ground would make both harder to read.
        val painted = options.view == MapView.FANTASY
        val contoured = painted && style.isobathInk > 0f
        val isobathInterval =
            if (contoured) Isobaths.interval(world.config.climate.maxAltitudeMetres) else 0f
        val flattestSlope = if (contoured) Isobaths.flattestSlope(world.config, w, h) else 0f
        val isobathStencil = Isobaths.slopeStencil(w)

        for (i in 0 until w * h) {
            val x = i % w
            val y = i / w
            if (showLakes && lakes.isLake(i)) {
                // Depth from how far the water surface sits above the ground beneath it, so a
                // deep basin reads darker than a shallow flood. The surface is the lake's own,
                // not the filled elevation: an endorheic lake stands below the brim the fill
                // raised its basin to, and reading the depth off the fill would draw a shallow
                // desert lake as if it were full to the rim.
                val depth = lakes.surfaceAt(i) - elevation.data[i]
                var water = MapPalette.blend(
                    style.lake,
                    style.lakeDeep,
                    (depth * 12f).coerceIn(0f, 1f)
                )
                if (plan != null && engraveWater) {
                    water = MapPalette.blend(
                        water, style.coastline, Engraving.lakeWater(y, shore!![i], plan)
                    )
                }
                pixels[i] = water
                continue
            }

            var color = baseColor(
                world, options.view, style, i, isobathInterval, flattestSlope, isobathStencil
            )
            val isLand = world.sea.isLand[i]
            if (plan != null && engraveWater && !isLand) {
                color = MapPalette.blend(
                    color, style.coastline, Engraving.coastalWater(shore!![i], plan)
                )
            }
            if (reliefDrawn && isLand) {
                if (plan != null) {
                    // Ink rather than shading: the paper is left alone and strokes are laid down
                    // the slope, heavier where the ground is steeper, which is how a pen draws a
                    // mountain when it has no colour to draw it with.
                    val reach = plan.gradientStencilCells
                    val gradientX =
                        (elevation.sample(x + reach, y) - elevation.sample(x - reach, y)) *
                            plan.gradientScale
                    val gradientY =
                        (elevation.sample(x, y + reach) - elevation.sample(x, y - reach)) *
                            plan.gradientScale
                    color = MapPalette.blend(
                        color,
                        style.coastline,
                        Engraving.hachure(x, y, gradientX, gradientY, plan, style.inkGain)
                    )
                } else {
                    color = MapPalette.shade(color, style.relief(relief!![i]))
                }
            }
            if (plan != null && engraveWater &&
                world.climate.biome[i] == Biome.ICE_SHEET
            ) {
                color = MapPalette.blend(
                    color, style.coastline, Engraving.stipple(x, y, plan)
                )
            }
            pixels[i] = color
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
        return ByteArray(land.size) { i ->
            if (land[i] && !(showLakes && lakes.isLake(i))) 1 else 0
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
        sheet: MapSheet = MapSheet.SHEET
    ): MapOverlay {
        val w = world.width
        val rivers = ArrayList<RiverSegment>()
        val skipInLakes = options.showLakes && options.view.showsTerrain
        val water = world.rivers.lakes

        val drawnRivers =
            if (options.showRivers && !options.view.showsFlow) {
                riversAtThisScale(world.rivers.rivers, sheet)
            } else {
                emptyList()
            }

        drawnRivers.forEach { river ->
            val course = trimmedAtTheShore(world, river, w)
            for (k in 0 until course.vertexCount - 1) {
                val from = river.cells[k]
                val to = river.cells[k + 1]
                val x0 = from % w
                val x1 = to % w
                val y0 = (from / w) + 0.5f
                val y1 = (to / w) + 0.5f
                val width = RiverPen.widthPixels(river.widthRatio[k], w)
                // The last drawn segment stops short of the water, so the round cap's outer
                // edge lands on the shoreline; [trimmedAtTheShore] says why.
                val reach = if (k == course.vertexCount - 2) course.lastFraction else 1f

                // Inside open water the river *is* the lake. Drawing it would put a channel
                // across the surface — and these are exactly the segments that run uphill on
                // raw terrain, because the basin was raised to let the water out. A strip of
                // lake one cell wide is not open water and is drawn; see `LakeResult.openWater`.
                if (skipInLakes && water.isOpenWater(from) && water.isOpenWater(to)) continue

                if (abs(x1 - x0) > w / 2) {
                    // The river crosses the east-west seam. Drawing it as-is would streak a
                    // line back across the whole map, but dropping it leaves the river visibly
                    // stopping dead at the edge. Draw it twice instead, shifted a map-width
                    // each way, so it runs off one side and arrives on the other.
                    val shifted = if (x1 > x0) x1 - w else x1 + w
                    rivers.add(
                        RiverSegment(
                            x0 + 0.5f, y0,
                            x0 + 0.5f + reach * (shifted - x0), y0 + reach * (y1 - y0), width
                        )
                    )
                    val back = if (x1 > x0) x0 + w else x0 - w
                    rivers.add(
                        RiverSegment(
                            back + 0.5f, y0,
                            back + 0.5f + reach * (x1 - back), y0 + reach * (y1 - y0), width
                        )
                    )
                    continue
                }

                rivers.add(
                    RiverSegment(
                        x0 + 0.5f, y0,
                        x0 + 0.5f + reach * (x1 - x0), y0 + reach * (y1 - y0), width
                    )
                )
            }
        }

        // Flow arrows on a coarse lattice: one per `spacing` cells, so the density stays legible
        // whatever the map resolution.
        val flow = ArrayList<FlowArrow>()
        var flowScale = 1f
        if (options.view.showsFlow) {
            val h = world.height
            // Wind is a smooth zonal field, so it needs far fewer arrows than the currents to say
            // the same thing — packed as tightly they turn into a carpet.
            val divisor = if (options.view == MapView.WIND) 22 else 48
            val spacing = (w / divisor).coerceAtLeast(6)
            flowScale = spacing * 0.5f
            var y = spacing / 2
            while (y < h) {
                var x = spacing / 2
                while (x < w) {
                    val i = y * w + x
                    if (options.view == MapView.CURRENTS) {
                        if (!world.sea.isLand[i]) {
                            val dx = world.ocean.velocityX.data[i]
                            val dy = world.ocean.velocityY.data[i]
                            val speed = kotlin.math.sqrt(dx * dx + dy * dy)
                            if (speed > 1e-3f) {
                                flow.add(
                                    FlowArrow(
                                        x + 0.5f, y + 0.5f, dx / speed, dy / speed,
                                        (speed / world.config.ocean.speed).coerceIn(0f, 1f),
                                        0xFFF2F6FA.toInt()
                                    )
                                )
                            }
                        }
                    } else {
                        // The wind is a vector: a zonal direction and a slant across the latitude
                        // lines. Drawn as one arrow, so the three circulation cells read as the
                        // cells they are — trades spiralling in toward the equator, westerlies
                        // carrying poleward — rather than as three stripes of east and west.
                        val dx = world.climate.windDirection[i].toFloat()
                        val dy = world.climate.windMeridional.data[i]
                        val length = kotlin.math.sqrt(dx * dx + dy * dy)
                        val colour = if (dx > 0) 0xFF7FC0F0.toInt() else 0xFFF0A860.toInt()
                        flow.add(
                            FlowArrow(x + 0.5f, y + 0.5f, dx / length, dy / length, 0.85f, colour)
                        )
                    }
                    x += spacing
                }
                y += spacing
            }
        }

        val glyphs = ArrayList<LandmarkGlyph>()
        // Purely a fraction of the map, so a glyph covers the same share of the picture at every
        // size — unlike the river pen beside it, which is a share of the sheet with a floor.
        val glyphRadius = (w / 190f).coerceAtLeast(2f)
        if (options.showLandmarks && !options.view.showsFlow) {
            world.landmarks.landmarks.forEach { landmark ->
                glyphs.add(
                    LandmarkGlyph(
                        x = (landmark.cell % w) + 0.5f,
                        y = (landmark.cell / w) + 0.5f,
                        radius = glyphRadius,
                        shape = shapeFor(landmark.kind),
                        fill = options.style.glyph(colorFor(landmark.kind))
                    )
                )
            }
        }

        val coast =
            if (options.showCoastline) {
                Shoreline.of(world.sea.isLand, w, world.height, sheet)
            } else {
                emptyList()
            }

        return MapOverlay(
            rivers = rivers,
            riversDrawn = drawnRivers.size,
            coastline = coast,
            landmarks = glyphs,
            flow = flow,
            flowScale = flowScale,
            graticule = if (options.showGraticule) Graticule.of(w, world.height) else null,
            scaleBar = if (sheet.carriesScaleBar) placedScaleBar(world) else null,
            riverColor = options.style.river,
            coastColor = withAlpha(options.style.coastline, options.style.coastlineStrength),
            coastWidth = coastPenPixels(w),
            graticuleColor = withAlpha(options.style.coastline, GRATICULE_INK_STRENGTH),
            graticuleWidth = RiverPen.HAIRLINE_PIXELS,
            marginInk = options.style.coastline,
            marginPaper = options.style.paper,
            glyphOutline = if (glyphs.isEmpty()) UNSTYLED_GLYPH_OUTLINE else options.style.coastline,
            glyphOutlineWidth =
                if (glyphs.isEmpty()) 1f else (glyphRadius * 0.28f).coerceAtLeast(1f)
        )
    }

    /**
     * The rivers big enough to be worth drawing at this scale, in their own order.
     *
     * A cut on the peak [River.widthRatio] is a cut on discharge: the ratio is the square root of
     * the flow accumulation normalised across the whole network (see `RiverWidth` in `:worldgen`),
     * so it rises with discharge and with nothing else, and the rivers above the cut are exactly
     * the rivers carrying the most water. How many survive is [MapSheet.featuresKept], which is
     * Töpfer's radical law.
     *
     * Ranking by the *peak* is also what keeps the network whole. A tributary's peak is its
     * discharge where it joins its trunk, and the trunk carries at least that much from the
     * junction down to its own mouth, so a trunk's peak is never below its tributaries'. Keeping
     * the largest can therefore never leave a tributary hanging off a river that is not drawn.
     *
     * A world saved before rivers were sized this way has no ratios to rank by and every peak comes
     * out zero; the cut is then at zero and every river is drawn, which is the right answer for a
     * map that cannot tell its rivers apart.
     */
    private fun riversAtThisScale(rivers: List<River>, sheet: MapSheet): List<River> {
        val kept = sheet.featuresKept(rivers.size)
        if (kept >= rivers.size) return rivers
        val cut = rivers.map(::peakWidthRatio).sortedDescending()[kept - 1]
        return rivers.filter { peakWidthRatio(it) >= cut }
    }

    /** The widest point of a river, which for a course traced source to mouth is its mouth. */
    private fun peakWidthRatio(river: River): Float {
        var peak = 0f
        river.widthRatio.forEach { if (it > peak) peak = it }
        return peak
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
            bar = MapScale.bar(
                MapScale.kilometresPerPixel(world.config.nations, world.width, 1f),
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
     * half a stroke on top of that. Measured on seeds 7/42/1234/99 at 512 under F10's five-pixel
     * pen, the ink reached 3.0 to 3.2 pixels past the shoreline — a blob of river sitting on the
     * open sea, which is what William saw at the widest mouth of seed 298405 (F15).
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
        val lastOnLandIndex = cells.size - 2
        val intoWater = !world.sea.isLand[mouth] || world.rivers.lakes.isOpenWater(mouth)
        if (!intoWater) return whole

        val half = RiverPen.widthPixels(river.widthRatio[lastOnLandIndex], cellsAcross) / 2f
        val toTheShore = stepLength(cells[lastOnLandIndex], mouth, cellsAcross) / 2f
        if (toTheShore >= half) {
            // The stroke ends on the last step, between that cell's centre and the shoreline.
            return DrawnCourse(cells.size, (toTheShore - half) / (2f * toTheShore))
        }

        var owed = half - toTheShore
        var vertex = lastOnLandIndex
        while (vertex > 0) {
            val step = stepLength(cells[vertex - 1], cells[vertex], cellsAcross)
            if (owed <= step) return DrawnCourse(vertex + 1, (step - owed) / step)
            owed -= step
            vertex--
        }
        // A course shorter than its own pen: leave it whole rather than draw a dot. It takes a
        // headwater-length reach carrying a trunk's water, which the widths make near impossible.
        return whole
    }

    /** Distance between two cells' centres, in cells, across the east-west seam if need be. */
    private fun stepLength(from: Int, to: Int, cellsAcross: Int): Float {
        var dx = to % cellsAcross - from % cellsAcross
        if (dx > cellsAcross / 2) dx -= cellsAcross
        if (dx < -cellsAcross / 2) dx += cellsAcross
        val dy = to / cellsAcross - from / cellsAcross
        return sqrt((dx * dx + dy * dy).toFloat())
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
     * would make eleven political maps out of one. Two styles are exceptions, and
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
        i: Int,
        isobathInterval: Float,
        flattestSlope: Float,
        isobathStencil: Int
    ): Int {
        val isLand = world.sea.isLand[i]
        val relative = world.sea.relativeElevation.data[i]

        return when (view) {
            MapView.FANTASY ->
                if (!isLand) {
                    val water = style.ocean(-relative)
                    if (isobathInterval <= 0f) {
                        water
                    } else {
                        val contour = seaContour(
                            world, i, -relative, isobathInterval, flattestSlope, isobathStencil
                        )
                        MapPalette.blend(water, style.coastline, style.isobathInk * contour)
                    }
                } else {
                    // The hypsometric tint carries the shape and the climate bends it — a desert is
                    // sand at any height, frozen ground is pale, a wood is dark. On top of that goes
                    // the wash of biome colour the style has always had. How much of either gets
                    // through is most of what separates one style from another.
                    val biome = world.climate.biome[i]
                    if (style.climateTint <= 0f) {
                        style.ground(relative, 0f, 0f, 0f, biome)
                    } else {
                        style.ground(
                            relative,
                            ClimateTint.drynessAt(world, i),
                            ClimateTint.coldnessAt(world, i),
                            ClimateTint.canopyClosure(biome),
                            biome
                        )
                    }
                }

            MapView.POLITICAL -> {
                val owner = world.nations.nationId[i]
                when {
                    !isLand -> politicalSea(style, relative)
                    owner == NationResult.UNCLAIMED -> style.wilderness
                    // Keep some relief showing through, so the political map still reads as a map
                    // of somewhere rather than a flat chart.
                    else -> MapPalette.blend(
                        style.realmFill(owner, i % world.width, i / world.width),
                        politicalLand(style, relative),
                        0.3f
                    )
                }
            }

            MapView.CULTURES -> {
                val people = world.cultures.cultureId[i]
                when {
                    !isLand -> politicalSea(style, relative)
                    people == CultureResult.UNSETTLED -> style.wilderness
                    // Same relief bleed as the political map, so the two read as the same world
                    // seen two ways rather than as two unrelated charts.
                    else -> MapPalette.blend(
                        style.peopleFill(people, i % world.width, i / world.width),
                        politicalLand(style, relative),
                        0.3f
                    )
                }
            }

            MapView.ELEVATION ->
                if (isLand) MapPalette.land(relative) else MapPalette.ocean(-relative)

            MapView.BIOMES -> MapPalette.biome(world.climate.biome[i])

            MapView.TEMPERATURE -> MapPalette.temperature(world.climate.temperature.data[i])

            MapView.SUMMER_TEMPERATURE ->
                MapPalette.temperature(world.climate.summerTemperature.data[i])

            MapView.WINTER_TEMPERATURE ->
                MapPalette.temperature(world.climate.winterTemperature.data[i])

            MapView.RAINFALL ->
                if (isLand) MapPalette.precipitation(world.climate.precipitation.data[i])
                else RAINFALL_SEA

            MapView.SUMMER_RAINFALL ->
                if (isLand) MapPalette.precipitation(world.climate.summerPrecipitation.data[i])
                else RAINFALL_SEA

            MapView.WINTER_RAINFALL ->
                if (isLand) MapPalette.precipitation(world.climate.winterPrecipitation.data[i])
                else RAINFALL_SEA

            MapView.PLATES -> {
                val plateColor = MapPalette.plate(world.plates.plateId[i])
                // Toward the boundaries the plate colour gives way to what that boundary builds,
                // so the view says which pair of crusts met rather than only where they met.
                val edge = (world.plates.boundaryDistance.data[i] / 12f).coerceIn(0f, 1f)
                MapPalette.blend(
                    MapPalette.boundaryClass(world.plates.nearestBoundaryClass[i]),
                    plateColor,
                    edge
                )
            }

            MapView.CURRENTS ->
                if (isLand) CURRENTS_LAND
                else MapPalette.temperatureAnomaly(world.ocean.anomaly.data[i])

            MapView.WIND -> {
                // Land and sea have to stay apart, or the arrows sit on undifferentiated ground.
                val base = if (isLand) {
                    MapPalette.blend(
                        WIND_LAND_LOW, WIND_LAND_HIGH,
                        world.sea.relativeElevation.data[i].coerceIn(0f, 1f)
                    )
                } else {
                    WIND_SEA
                }
                // The belts are carried by the arrow colours; tinting the ground as well only
                // costs the land/sea contrast the arrows are read against.
                base
            }

            MapView.NORMALS -> {
                val n = world.terrain.normals.normalAt(i % world.width, i / world.width)
                argb(
                    ((n[0] * 0.5f + 0.5f) * 255).toInt(),
                    ((n[1] * 0.5f + 0.5f) * 255).toInt(),
                    ((n[2] * 0.5f + 0.5f) * 255).toInt()
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
     */
    private fun seaContour(
        world: WorldMap,
        i: Int,
        depth: Float,
        interval: Float,
        flattestSlope: Float,
        stencil: Int
    ): Float {
        val width = world.width
        val x = i % width
        val y = i / width
        val elevation = world.sea.relativeElevation
        val span = 1f / (2f * stencil)
        val eastward = (elevation.sample(x + stencil, y) - elevation.sample(x - stencil, y)) * span
        val southward = (elevation.sample(x, y + stencil) - elevation.sample(x, y - stencil)) * span
        val slope = sqrt(eastward * eastward + southward * southward)
        return Isobaths.ink(depth, slope, interval, flattestSlope)
    }

    private fun drawCoastline(world: WorldMap, style: MapStyle, pixels: IntArray) {
        val w = world.width
        val h = world.height
        val land = world.sea.isLand
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (!land[i]) continue
                val right = land[y * w + (x + 1) % w]
                val down = if (y + 1 < h) land[(y + 1) * w + x] else true
                if (!right || !down) {
                    pixels[i] = MapPalette.blend(
                        pixels[i], style.coastline, style.coastlineStrength
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
        val w = world.width
        val h = world.height
        val owner = world.nations.nationId
        val land = world.sea.isLand

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (!land[i]) continue
                val right = y * w + (x + 1) % w
                val down = if (y + 1 < h) (y + 1) * w + x else i

                val differs = (land[right] && owner[right] != owner[i]) ||
                    (land[down] && owner[down] != owner[i])
                if (!differs) continue
                if (plan == null) {
                    pixels[i] = MapPalette.blend(pixels[i], style.border, 0.75f)
                } else if (Engraving.borderDot(x, y, plan)) {
                    pixels[i] = MapPalette.blend(pixels[i], style.border, 1f)
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
