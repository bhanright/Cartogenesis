package com.cartogenesis.cartography

import com.cartogenesis.worldgen.model.WorldMap
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
    NORMALS("Normal map")
}

data class RenderOptions(
    val view: MapView = MapView.FANTASY,
    val showRivers: Boolean = true,
    val showCoastline: Boolean = true,
    val showHillshade: Boolean = true,
    /** Draws realm borders over whichever view is active, not just the political one. */
    val showBorders: Boolean = false,
    val showLandmarks: Boolean = true,
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

        val hillshade = if (options.showHillshade && options.view != MapView.NORMALS) {
            computeHillshade(world)
        } else null

        for (i in 0 until w * h) {
            var color = baseColor(world, options.view, i)
            if (hillshade != null && world.sea.isLand[i]) {
                color = MapPalette.shade(color, hillshade[i])
            }
            pixels[i] = color
        }

        if (options.showCoastline) drawCoastline(world, pixels)
        if (options.bordersVisible) drawBorders(world, pixels)
        return pixels
    }

    /** The rivers and landmarks to lay over the raster, as geometry. */
    fun overlay(world: WorldMap, options: RenderOptions = RenderOptions()): MapOverlay {
        val w = world.width
        val rivers = ArrayList<RiverSegment>()

        if (options.showRivers) {
            world.rivers.rivers.forEach { river ->
                for (k in 0 until river.cells.size - 1) {
                    val from = river.cells[k]
                    val to = river.cells[k + 1]
                    val x0 = from % w
                    val x1 = to % w
                    val y0 = (from / w) + 0.5f
                    val y1 = (to / w) + 0.5f
                    val width = (river.widths[k] * options.riverScale).coerceAtLeast(0.9f)

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

        val glyphs = ArrayList<LandmarkGlyph>()
        if (options.showLandmarks) {
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
                        fill = colorFor(landmark.kind)
                    )
                )
            }
            return MapOverlay(rivers, glyphs, MapPalette.RIVER, 0xFF241C14.toInt(),
                (radius * 0.28f).coerceAtLeast(1f))
        }
        return MapOverlay(rivers, glyphs, MapPalette.RIVER, 0xFF241C14.toInt(), 1f)
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

    private fun baseColor(world: WorldMap, view: MapView, i: Int): Int {
        val isLand = world.sea.isLand[i]
        val relative = world.sea.relativeElevation.data[i]

        return when (view) {
            MapView.FANTASY ->
                if (!isLand) {
                    MapPalette.ocean(-relative)
                } else {
                    // Hypsometric tint carries the shape; a wash of biome colour carries the
                    // climate, so both read at a glance.
                    MapPalette.blend(
                        MapPalette.land(relative),
                        MapPalette.biome(world.climate.biome[i]),
                        0.45f
                    )
                }

            MapView.POLITICAL -> {
                val owner = world.nations.nationId[i]
                when {
                    !isLand -> MapPalette.ocean(-relative)
                    owner == NationResult.UNCLAIMED -> MapPalette.WILDERNESS
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

    private fun drawCoastline(world: WorldMap, pixels: IntArray) {
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
                    pixels[i] = MapPalette.blend(pixels[i], MapPalette.COASTLINE, 0.55f)
                }
            }
        }
    }

    /**
     * Marks a cell whenever the realm to its east or south differs. Only land-to-land transitions
     * count, so a realm's coastline is left to the coastline pass rather than being outlined twice.
     */
    private fun drawBorders(world: WorldMap, pixels: IntArray) {
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
                if (differs) pixels[i] = MapPalette.blend(pixels[i], MapPalette.BORDER, 0.75f)
            }
        }
    }

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or
            (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or
            b.coerceIn(0, 255)
}
