package com.cartogenesis.cartography

import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.LandmarkKind
import com.cartogenesis.worldgen.pipeline.CultureResult
import com.cartogenesis.worldgen.pipeline.NationResult
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
    /** Draws realm borders over whichever view is active, not just the political one. */
    val showBorders: Boolean = false,
    val showLandmarks: Boolean = false,
    val showLakes: Boolean = true
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
 * drawing API executes it.
 */
class MapOverlay(
    val rivers: List<RiverSegment>,
    val landmarks: List<LandmarkGlyph>,
    /** Ocean or wind arrows, on the views that show them. */
    val flow: List<FlowArrow>,
    /** Spacing of the arrow lattice in cells, so a platform can size arrows to fit between them. */
    val flowScale: Float,
    val riverColor: Int,
    val glyphOutline: Int,
    val glyphOutlineWidth: Float
)

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
     * How far the hillshade's central differences are exaggerated, at a map [width].
     *
     * Gentle relief still has to read at map scale, and these are differences between adjacent
     * cells: at four times the grid a step covers a quarter of the ground, and the relief would
     * otherwise render four times flatter.
     */
    internal fun hillshadeScale(width: Int): Float = 12f * (width / 512f)

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
        val hillshade = if (reliefDrawn && !style.lineArt) computeHillshade(world) else null

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

            var color = baseColor(world, options.view, style, i)
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
                    color = MapPalette.shade(color, style.relief(hillshade!![i]))
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

    /** The rivers and landmarks to lay over the raster, as geometry. */
    fun overlay(world: WorldMap, options: RenderOptions = RenderOptions()): MapOverlay {
        val w = world.width
        val rivers = ArrayList<RiverSegment>()
        val skipInLakes = options.showLakes && options.view.showsTerrain

        if (options.showRivers && !options.view.showsFlow) {
            world.rivers.rivers.forEach { river ->
                for (k in 0 until river.cells.size - 1) {
                    val from = river.cells[k]
                    val to = river.cells[k + 1]
                    val x0 = from % w
                    val x1 = to % w
                    val y0 = (from / w) + 0.5f
                    val y1 = (to / w) + 0.5f
                    val width = RiverPen.widthPixels(river.widthRatio[k])

                    // Inside a lake the river *is* the lake. Drawing it would put a channel across
                    // open water — and these are exactly the segments that run uphill on raw
                    // terrain, because the basin was raised to let the water out.
                    if (skipInLakes && world.rivers.lakes.isLake(from) &&
                        world.rivers.lakes.isLake(to)
                    ) continue

                    if (abs(x1 - x0) > w / 2) {
                        // The river crosses the east-west seam. Drawing it as-is would streak a
                        // line back across the whole map, but dropping it leaves the river visibly
                        // stopping dead at the edge. Draw it twice instead, shifted a map-width
                        // each way, so it runs off one side and arrives on the other.
                        val shifted = if (x1 > x0) x1 - w else x1 + w
                        rivers.add(RiverSegment(x0 + 0.5f, y0, shifted + 0.5f, y1, width))
                        val back = if (x1 > x0) x0 + w else x0 - w
                        rivers.add(RiverSegment(back + 0.5f, y0, x1 + 0.5f, y1, width))
                        continue
                    }

                    rivers.add(RiverSegment(x0 + 0.5f, y0, x1 + 0.5f, y1, width))
                }
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
                                        (speed / world.config.ocean.speedCellsPerPass)
                                            .coerceIn(0f, 1f),
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
        if (options.showLandmarks && !options.view.showsFlow) {
            // Purely a fraction of the map, so a glyph covers the same share of the picture at
            // every size — unlike the river pen beside it, which is a fixed count of output pixels
            // and grows with nothing.
            val radius = (w / 190f).coerceAtLeast(2f)
            world.landmarks.landmarks.forEach { landmark ->
                glyphs.add(
                    LandmarkGlyph(
                        x = (landmark.cell % w) + 0.5f,
                        y = (landmark.cell / w) + 0.5f,
                        radius = radius,
                        shape = shapeFor(landmark.kind),
                        fill = options.style.glyph(colorFor(landmark.kind))
                    )
                )
            }
            return MapOverlay(
                rivers, glyphs, flow, flowScale,
                options.style.river, options.style.coastline, (radius * 0.28f).coerceAtLeast(1f)
            )
        }
        return MapOverlay(rivers, glyphs, flow, flowScale,
            options.style.river, 0xFF241C14.toInt(), 1f)
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

    private fun baseColor(world: WorldMap, view: MapView, style: MapStyle, i: Int): Int {
        val isLand = world.sea.isLand[i]
        val relative = world.sea.relativeElevation.data[i]

        return when (view) {
            MapView.FANTASY ->
                if (!isLand) {
                    style.ocean(-relative)
                } else {
                    // Hypsometric tint carries the shape; a wash of biome colour carries the
                    // climate, so both read at a glance. How much of that wash gets through is
                    // most of what separates one style from another.
                    style.tint(style.land(relative), world.climate.biome[i])
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

    /** Lambertian shading from a light in the north-west, the cartographic convention. */
    private fun computeHillshade(world: WorldMap): FloatArray {
        val w = world.width
        val h = world.height
        val elevation = world.sea.relativeElevation
        val shade = FloatArray(w * h)

        val zScale = hillshadeScale(w)
        val lightX = -0.6f
        val lightY = -0.6f
        val lightZ = 0.53f

        for (y in 0 until h) {
            for (x in 0 until w) {
                val dzdx = (elevation.sample(x + 1, y) - elevation.sample(x - 1, y)) * zScale
                val dzdy = (elevation.sample(x, y + 1) - elevation.sample(x, y - 1)) * zScale
                val len = sqrt(dzdx * dzdx + dzdy * dzdy + 1f)
                val dot = (-dzdx * lightX - dzdy * lightY + lightZ) / len
                shade[y * w + x] = (0.72f + 0.55f * dot).coerceIn(0.45f, 1.35f)
            }
        }
        return shade
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
