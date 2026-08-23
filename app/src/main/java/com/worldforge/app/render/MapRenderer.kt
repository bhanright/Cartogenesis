package com.worldforge.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.worldforge.worldgen.model.WorldMap
import com.worldforge.worldgen.pipeline.LandmarkKind
import com.worldforge.worldgen.pipeline.NationResult
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

/** Turns a generated [WorldMap] into a bitmap. Pure output — no state of its own. */
object MapRenderer {

    fun render(world: WorldMap, options: RenderOptions = RenderOptions()): Bitmap {
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

        // Allocate then fill, rather than Bitmap.createBitmap(pixels, ...) — that overload returns
        // an immutable bitmap, which the Canvas constructor rejects when rivers or labels are
        // drawn over the top.
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)

        if (options.showRivers && world.rivers.rivers.isNotEmpty()) {
            drawRivers(world, bitmap, options.riverScale)
        }
        if (options.showLandmarks && world.landmarks.landmarks.isNotEmpty()) {
            drawLandmarks(world, bitmap, options.riverScale.coerceAtLeast(1f))
        }
        return bitmap
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

    /**
     * A small shaped glyph per landmark. Shape carries the kind, so the map stays readable in
     * greyscale and without a legend to hand.
     */
    private fun drawLandmarks(world: WorldMap, bitmap: Bitmap, scale: Float) {
        val canvas = Canvas(bitmap)
        val radius = (bitmap.width / 190f).coerceIn(2.5f, 14f) * scale
        val w = world.width

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (radius * 0.28f).coerceAtLeast(1f)
            color = 0xFF241C14.toInt()
        }

        world.landmarks.landmarks.forEach { landmark ->
            val cx = (landmark.cell % w) + 0.5f
            val cy = (landmark.cell / w) + 0.5f
            fill.color = colorFor(landmark.kind)

            when (landmark.kind) {
                LandmarkKind.MONSTER_LAIR, LandmarkKind.HAZARD -> {
                    // Triangle — reads as a warning.
                    val path = android.graphics.Path().apply {
                        moveTo(cx, cy - radius)
                        lineTo(cx + radius, cy + radius * 0.8f)
                        lineTo(cx - radius, cy + radius * 0.8f)
                        close()
                    }
                    canvas.drawPath(path, fill)
                    canvas.drawPath(path, outline)
                }

                LandmarkKind.RESOURCE -> {
                    // Diamond.
                    val path = android.graphics.Path().apply {
                        moveTo(cx, cy - radius)
                        lineTo(cx + radius, cy)
                        lineTo(cx, cy + radius)
                        lineTo(cx - radius, cy)
                        close()
                    }
                    canvas.drawPath(path, fill)
                    canvas.drawPath(path, outline)
                }

                LandmarkKind.DUNGEON, LandmarkKind.RUIN -> {
                    canvas.drawRect(cx - radius, cy - radius, cx + radius, cy + radius, fill)
                    canvas.drawRect(cx - radius, cy - radius, cx + radius, cy + radius, outline)
                }

                LandmarkKind.WONDER, LandmarkKind.SANCTUARY -> {
                    canvas.drawCircle(cx, cy, radius, fill)
                    canvas.drawCircle(cx, cy, radius, outline)
                }
            }
        }
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

    private fun drawRivers(world: WorldMap, bitmap: Bitmap, riverScale: Float) {
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = MapPalette.RIVER
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val w = world.width

        world.rivers.rivers.forEach { river ->
            for (k in 0 until river.cells.size - 1) {
                val from = river.cells[k]
                val to = river.cells[k + 1]
                val x0 = from % w
                val x1 = to % w
                // A river crossing the east-west seam would otherwise be drawn as a line straight
                // back across the whole map.
                if (abs(x1 - x0) > w / 2) continue

                paint.strokeWidth = (river.widths[k] * riverScale).coerceAtLeast(0.9f)
                canvas.drawLine(
                    x0 + 0.5f, (from / w) + 0.5f,
                    x1 + 0.5f, (to / w) + 0.5f,
                    paint
                )
            }
        }
    }

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or
            (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or
            b.coerceIn(0, 255)
}
