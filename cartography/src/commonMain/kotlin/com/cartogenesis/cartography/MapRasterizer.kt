package com.cartogenesis.cartography

import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.LandmarkKind
import com.cartogenesis.worldgen.pipeline.NationResult
import kotlin.math.abs
import kotlin.math.sqrt

enum class MapView(val label: String) {
    FANTASY("Fantasy"),
    POLITICAL("Political"),
    ELEVATION("Elevation"),
    BIOMES("Biomes"),
    TEMPERATURE("Temperature"),
    RAINFALL("Rainfall"),
    PLATES("Plates"),
    CURRENTS("Ocean currents"),
    WIND("Winds"),
    NORMALS("Normal map");

    /** Whether this view draws the land itself, and so should show standing water on it. */
    val showsTerrain: Boolean
        get() = this == FANTASY || this == POLITICAL || this == ELEVATION || this == BIOMES

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
    val showLandmarks: Boolean = true,
    /**
     * Draw terrain marks — little mountains, woods, dunes and waves — over the ground.
     *
     * Off by default, because on the atlas style they would be clutter over information the colour
     * already gives plainly. On the drawn styles they are most of what makes a map look drawn.
     */
    val showSymbols: Boolean = false,
    val showLakes: Boolean = true,
    /** Multiplies river widths; HD exports scale this up with resolution. */
    val riverScale: Float = 1f
) {
    /** The political view is realm colour — borders are implied by it and always drawn. */
    val bordersVisible: Boolean get() = showBorders || view == MapView.POLITICAL
}

/** One straight run of a river, in cell coordinates. */
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
/** The little drawn things a cartographer scatters over the ground. */
enum class SymbolShape { MOUNTAIN, HILL, CONIFER, BROADLEAF, DUNE, WAVE }

/**
 * One hand-drawn mark, in cell coordinates.
 *
 * Described rather than drawn, like everything else in the overlay, so the decision about *what*
 * belongs where is made once and every front end merely executes it.
 */
class MapSymbol(
    val x: Float,
    val y: Float,
    /** Height of the mark in cells; a platform scales its drawing to this. */
    val size: Float,
    val shape: SymbolShape
)

class MapOverlay(
    val rivers: List<RiverSegment>,
    val landmarks: List<LandmarkGlyph>,
    /** Ocean or wind arrows, on the views that show them. */
    val flow: List<FlowArrow>,
    /** Spacing of the arrow lattice in cells, so a platform can size arrows to fit between them. */
    val flowScale: Float,
    /** Terrain marks: mountains, woods, dunes, waves. Empty unless the style asks for them. */
    val symbols: List<MapSymbol>,
    val symbolColor: Int,
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

    /** ARGB pixels, row-major, `world.width * world.height` long. */
    fun rasterize(world: WorldMap, options: RenderOptions = RenderOptions()): IntArray {
        val w = world.width
        val h = world.height
        val pixels = IntArray(w * h)

        val style = options.style
        val hillshade = if (options.showHillshade && options.view != MapView.NORMALS) {
            computeHillshade(world)
        } else null

        val lakes = world.rivers.lakes
        val showLakes = options.showLakes && options.view.showsTerrain

        for (i in 0 until w * h) {
            if (showLakes && lakes.isLake(i)) {
                // Depth from how far the water surface sits above the ground beneath it, so a
                // deep basin reads darker than a shallow flood.
                val depth = world.rivers.filledElevation.data[i] -
                    world.sea.relativeElevation.data[i]
                pixels[i] = MapPalette.blend(
                    style.lake,
                    style.lakeDeep,
                    (depth * 12f).coerceIn(0f, 1f)
                )
                continue
            }

            var color = baseColor(world, options.view, style, i)
            if (hillshade != null && world.sea.isLand[i]) {
                if (style.lineArt) {
                    // Ink rather than shading: the paper is left alone and strokes are laid on
                    // where the ground is steep, which is how a pen draws a mountain.
                    if (style.inked(i % w, i / w, style.relief(hillshade[i]))) color = style.coastline
                } else {
                    color = MapPalette.shade(color, style.relief(hillshade[i]))
                }
            }
            pixels[i] = color
        }

        if (options.showCoastline) drawCoastline(world, style, pixels)
        if (options.bordersVisible) drawBorders(world, style, pixels)
        return pixels
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
                    val width = (river.widths[k] * options.riverScale).coerceAtLeast(0.9f)

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
                                        (speed / world.config.ocean.speed).coerceIn(0f, 1f),
                                        0xFFF2F6FA.toInt()
                                    )
                                )
                            }
                        }
                    } else {
                        // Wind is purely zonal in this model, so the arrow only has a direction.
                        val direction = world.climate.windDirection[i].toFloat()
                        val colour = if (direction > 0) 0xFF7FC0F0.toInt() else 0xFFF0A860.toInt()
                        flow.add(FlowArrow(x + 0.5f, y + 0.5f, direction, 0f, 0.85f, colour))
                    }
                    x += spacing
                }
                y += spacing
            }
        }

        // Terrain marks on a jittered lattice. The jitter matters more than it looks: on a plain
        // grid the eye reads the rows before it reads the mountains, and the map stops looking
        // drawn and starts looking printed. The offsets come from the cell position rather than a
        // random source, so the same world always gets the same marks.
        val symbols = ArrayList<MapSymbol>()
        if (options.showSymbols && options.view.showsTerrain) {
            val h = world.height
            // Sparse. The first version put a mark every fourteen cells and the result was a
            // peppered map rather than an annotated one: at that density the marks stop being
            // things standing in a landscape and become a texture laid over it.
            val spacing = (w / 38).coerceAtLeast(8)
            val size = spacing * 0.62f
            var y = spacing
            while (y < h - spacing) {
                var x = spacing
                while (x < w - spacing) {
                    val jx = x + (hash(x, y) % spacing) - spacing / 2
                    val jy = y + (hash(y, x) % spacing) - spacing / 2
                    val cx = ((jx % w) + w) % w
                    val cy = jy.coerceIn(0, h - 1)
                    val i = cy * w + cx
                    val shape = symbolFor(world, i)
                    if (shape != null) {
                        symbols.add(MapSymbol(cx + 0.5f, cy + 0.5f, size, shape))
                    }
                    x += spacing
                }
                y += spacing
            }
        }

        val glyphs = ArrayList<LandmarkGlyph>()
        if (options.showLandmarks && !options.view.showsFlow) {
            // Purely a fraction of the map, so a glyph covers the same share of the picture at
            // every size. It must NOT also take riverScale: rivers need that because their widths
            // are fixed in cells, but this radius already derives from the width, and applying
            // both made glyphs four times too big on a 4096 export.
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
                rivers, glyphs, flow, flowScale, symbols, options.style.symbolInk,
                options.style.river, options.style.coastline, (radius * 0.28f).coerceAtLeast(1f)
            )
        }
        return MapOverlay(rivers, glyphs, flow, flowScale, symbols, options.style.symbolInk,
            options.style.river, 0xFF241C14.toInt(), 1f)
    }

    /** Shape carries the kind, so the map stays readable in greyscale and without a legend. */
    private fun shapeFor(kind: LandmarkKind): GlyphShape = when (kind) {
        LandmarkKind.MONSTER_LAIR, LandmarkKind.HAZARD -> GlyphShape.TRIANGLE
        LandmarkKind.RESOURCE -> GlyphShape.DIAMOND
        LandmarkKind.DUNGEON, LandmarkKind.RUIN -> GlyphShape.SQUARE
        LandmarkKind.WONDER, LandmarkKind.SANCTUARY -> GlyphShape.CIRCLE
    }

    /**
     * Which mark, if any, belongs on this cell.
     *
     * Read straight off the world rather than scattered decoratively: mountains where the ground is
     * high, conifers where the forest is cold, dunes in the sand, and a few waves on the shelf. It
     * is the same information the colour already carries, said a second way — which is exactly what
     * a drawn map does, and why these read as description rather than ornament.
     */
    private fun symbolFor(world: WorldMap, i: Int): SymbolShape? {
        val elevation = world.sea.relativeElevation.data[i]
        if (!world.sea.isLand[i]) {
            // Only on the shelf. Waves over the deep ocean would be wallpaper.
            return if (elevation > -0.05f && elevation < -0.01f) SymbolShape.WAVE else null
        }
        if (world.rivers.lakes.isLake(i)) return null
        if (elevation > 0.52f) return SymbolShape.MOUNTAIN
        if (elevation > 0.30f) return SymbolShape.HILL
        return when (world.climate.biome[i]) {
            Biome.TAIGA, Biome.TEMPERATE_RAINFOREST -> SymbolShape.CONIFER
            Biome.TEMPERATE_FOREST, Biome.TROPICAL_RAINFOREST,
            Biome.TROPICAL_SEASONAL_FOREST -> SymbolShape.BROADLEAF
            Biome.DESERT -> SymbolShape.DUNE
            else -> null
        }
    }

    /** Deterministic jitter. Any cheap hash will do; this one only has to look unpatterned. */
    private fun hash(a: Int, b: Int): Int {
        var x = a * 73856093 xor b * 19349663
        x = x xor (x shr 13)
        x *= 1274126177
        return (x xor (x shr 16)) and 0x7FFFFFFF
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
                    !isLand -> MapPalette.ocean(-relative)
                    owner == NationResult.UNCLAIMED -> style.wilderness
                    // Keep some relief showing through, so the political map still reads as a map
                    // of somewhere rather than a flat chart.
                    else -> MapPalette.blend(MapPalette.nation(owner), MapPalette.land(relative), 0.3f)
                }
            }

            MapView.ELEVATION ->
                if (isLand) MapPalette.land(relative) else MapPalette.ocean(-relative)

            MapView.BIOMES -> MapPalette.biome(world.climate.biome[i])

            MapView.TEMPERATURE -> MapPalette.temperature(world.climate.temperature.data[i])

            MapView.RAINFALL ->
                if (isLand) MapPalette.precipitation(world.climate.precipitation.data[i])
                else 0xFF20303C.toInt()

            MapView.PLATES -> {
                val plateColor = MapPalette.plate(world.plates.plateId[i])
                // Darken toward the boundaries so plate edges are readable.
                val edge = (world.plates.boundaryDistance.data[i] / 12f).coerceIn(0f, 1f)
                MapPalette.blend(0xFF202020.toInt(), plateColor, edge)
            }

            MapView.CURRENTS ->
                if (isLand) 0xFF3A3A32.toInt()
                else MapPalette.temperatureAnomaly(world.ocean.anomaly.data[i])

            MapView.WIND -> {
                // Land and sea have to stay apart, or the arrows sit on undifferentiated ground.
                val base = if (isLand) {
                    MapPalette.blend(
                        0xFF4A4638.toInt(), 0xFF9A9384.toInt(),
                        world.sea.relativeElevation.data[i].coerceIn(0f, 1f)
                    )
                } else {
                    0xFF16242F.toInt()
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

        // Exaggerated so gentle relief still reads at map scale. Scaled with resolution because
        // these are central differences between adjacent cells: at 4x the grid size each step
        // covers a quarter of the ground and the relief would otherwise render four times flatter.
        val zScale = 12f * (w / 512f)
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
     */
    private fun drawBorders(world: WorldMap, style: MapStyle, pixels: IntArray) {
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
                if (differs) pixels[i] = MapPalette.blend(pixels[i], style.border, 0.75f)
            }
        }
    }

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or
            (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or
            b.coerceIn(0, 255)
}
