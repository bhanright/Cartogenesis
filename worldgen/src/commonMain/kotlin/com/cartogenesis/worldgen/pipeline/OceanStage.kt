package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

data class OceanResult(
    /** Surface flow, in cells per advection pass. Zero on land. */
    val velocityX: FloatField,
    val velocityY: FloatField,
    /** Sea-surface temperature in degrees Celsius. */
    val temperature: FloatField,
    /**
     * How much warmer or colder the water is than the average for its latitude.
     *
     * This is the number that matters downstream: a coast is mild or arid because of how its water
     * compares to the same latitude elsewhere, not its absolute temperature.
     */
    val anomaly: FloatField
)

/**
 * Step 5b: wind-driven surface currents, and the sea temperature they carry.
 *
 * Rather than draw gyres by hand, this solves for them. Wind dragging on the ocean has a curl — the
 * trades blow west near the equator and the westerlies blow east in mid-latitudes, so the water
 * between them is spun — and the flow satisfying that curl inside a closed basin *is* a gyre.
 * Solving `∇²ψ = curl` for a stream function with `ψ = 0` along every coast produces subtropical
 * gyres of the right handedness, closed by whatever coastlines the world happens to have, without
 * anything being told the shape of an ocean.
 *
 * Using a stream function also means the flow can have no sources or sinks: velocity is taken from
 * its gradients, so water is conserved by construction rather than by care.
 *
 * The solve runs on a coarse grid. Gyres are basin-scale features, and Jacobi relaxation spreads
 * information roughly one cell per pass — at full resolution it would need tens of thousands of
 * passes to close a basin, where a few thousand on a small grid converge properly and cost far
 * less. The result is then interpolated back up.
 */
object OceanStage {

    fun generate(config: WorldGenConfig, sea: SeaLevelResult): OceanResult {
        val w = config.width
        val h = config.height
        val cfg = config.ocean

        val velocityX = FloatField(w, h)
        val velocityY = FloatField(w, h)
        val temperature = FloatField(w, h)
        val anomaly = FloatField(w, h)

        fillBaseTemperature(config, sea, temperature)
        if (!cfg.enabled) return OceanResult(velocityX, velocityY, temperature, anomaly)

        val stream = solveStreamFunction(config, sea)
        streamToVelocity(config, sea, stream, velocityX, velocityY)
        advectTemperature(config, sea, velocityX, velocityY, temperature)
        buildAnomaly(config, sea, temperature, anomaly)

        return OceanResult(velocityX, velocityY, temperature, anomaly)
    }

    /** Zonal wind stress. Its *variation* with latitude is the entire forcing. */
    private fun windStress(latitude: Float): Float {
        val a = abs(latitude)
        return when {
            a < 30f -> -cos(latitude * 3.0 * PI / 180.0).toFloat()
            a < 60f -> cos((a - 45f) * 6.0 * PI / 180.0).toFloat()
            else -> -cos((a - 75f) * 6.0 * PI / 180.0).toFloat() * 0.6f
        }
    }

    private fun solveStreamFunction(config: WorldGenConfig, sea: SeaLevelResult): FloatField {
        val w = config.width
        val h = config.height
        val cfg = config.ocean

        // Coarse grid, but never coarser than the basins we are trying to resolve.
        val cw = minOf(w, cfg.solveResolution)
        val ch = minOf(h, cfg.solveResolution)
        val stepX = w / cw
        val stepY = h / ch

        // A coarse cell is water only if most of the fine cells under it are, so a scatter of
        // islands does not wall off an ocean that is really open.
        val coarseWater = BooleanArray(cw * ch)
        for (cy in 0 until ch) {
            for (cx in 0 until cw) {
                var water = 0
                var total = 0
                for (dy in 0 until stepY) {
                    for (dx in 0 until stepX) {
                        val i = (cy * stepY + dy) * w + (cx * stepX + dx)
                        total++
                        if (!sea.isLand[i]) water++
                    }
                }
                coarseWater[cy * cw + cx] = water * 2 > total
            }
        }

        val curl = FloatArray(cw * ch)
        for (cy in 0 until ch) {
            val latNorth = 90f - 180f * (cy * stepY + 0.5f) / h
            val latSouth = 90f - 180f * ((cy + 1) * stepY + 0.5f) / h
            // curl of a purely zonal stress is -d(stress)/dy.
            val value = -(windStress(latSouth) - windStress(latNorth)) * cfg.forcing
            for (cx in 0 until cw) curl[cy * cw + cx] = value
        }

        val current = FloatArray(cw * ch)

        // Red-black Gauss-Seidel with over-relaxation.
        //
        // Updating in place is what makes this Gauss-Seidel rather than Jacobi, and Gauss-Seidel
        // is what makes over-relaxation legal: an omega above 1 applied to Jacobi diverges to NaN,
        // which is exactly what the first version of this did. Colouring by (x + y) parity means
        // no two cells updated together are neighbours, so each colour can still run in parallel.
        val omega = cfg.overRelaxation.coerceIn(0.5f, 1.95f)
        repeat(cfg.relaxationPasses) {
            for (colour in 0..1) {
                parallelChunks(0, ch) { startY, endY ->
                    for (cy in startY until endY) {
                        for (cx in 0 until cw) {
                            if ((cx + cy) and 1 != colour) continue
                            val i = cy * cw + cx
                            // Land pins the stream function at zero, which is what turns a coast
                            // into a wall the circulation has to follow.
                            if (!coarseWater[i]) { current[i] = 0f; continue }

                            var east = cx + 1; if (east >= cw) east = 0
                            var west = cx - 1; if (west < 0) west = cw - 1
                            val north = (cy - 1).coerceAtLeast(0)
                            val south = (cy + 1).coerceAtMost(ch - 1)

                            val sum = current[cy * cw + east] + current[cy * cw + west] +
                                current[north * cw + cx] + current[south * cw + cx]
                            val relaxed = (sum - curl[i]) * 0.25f
                            current[i] += (relaxed - current[i]) * omega
                        }
                    }
                }
            }
        }

        // Back up to full resolution, bilinearly.
        val stream = FloatField(w, h)
        parallelChunks(0, h) { startY, endY ->
            for (y in startY until endY) {
                val fy = (y.toFloat() / stepY - 0.5f).coerceIn(0f, (ch - 1).toFloat())
                val y0 = fy.toInt().coerceAtMost(ch - 1)
                val y1 = (y0 + 1).coerceAtMost(ch - 1)
                val ty = fy - y0
                for (x in 0 until w) {
                    val fx = x.toFloat() / stepX - 0.5f
                    var x0 = fx.toInt(); if (fx < 0) x0 -= 1
                    val tx = fx - x0
                    val xa = ((x0 % cw) + cw) % cw
                    val xb = ((x0 + 1) % cw + cw) % cw

                    val top = current[y0 * cw + xa] * (1 - tx) + current[y0 * cw + xb] * tx
                    val bottom = current[y1 * cw + xa] * (1 - tx) + current[y1 * cw + xb] * tx
                    stream.data[y * w + x] = top * (1 - ty) + bottom * ty
                }
            }
        }
        return stream
    }

    /**
     * Velocity is the perpendicular gradient of ψ, so flow follows its contours.
     *
     * Normalised afterwards to a target top speed. The raw gradient magnitude depends on forcing,
     * grid size and how far the relaxation converged — none of which should decide how fast water
     * moves, so it is scaled to something meaningful instead.
     */
    private fun streamToVelocity(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        stream: FloatField,
        velocityX: FloatField,
        velocityY: FloatField
    ) {
        val w = config.width
        val h = config.height

        var fastest = 0f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (sea.isLand[i]) continue

                var east = x + 1; if (east >= w) east = 0
                var west = x - 1; if (west < 0) west = w - 1
                val north = (y - 1).coerceAtLeast(0)
                val south = (y + 1).coerceAtMost(h - 1)

                val vx = (stream.data[south * w + x] - stream.data[north * w + x]) * 0.5f
                val vy = -(stream.data[y * w + east] - stream.data[y * w + west]) * 0.5f
                velocityX.data[i] = vx
                velocityY.data[i] = vy
                fastest = maxOf(fastest, sqrt(vx * vx + vy * vy))
            }
        }

        if (fastest <= 1e-6f) return
        val scale = config.ocean.speed / fastest
        parallelChunks(0, w * h) { start, end ->
            for (i in start until end) {
                velocityX.data[i] *= scale
                velocityY.data[i] *= scale
            }
        }
    }

    private fun fillBaseTemperature(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        temperature: FloatField
    ) {
        val w = config.width
        val h = config.height
        val cfg = config.climate
        for (y in 0 until h) {
            val lat = 90f - 180f * (y + 0.5f) / h
            val t = zonalTemperature(lat, cfg.equatorTemperatureC, cfg.poleTemperatureC)
            for (x in 0 until w) {
                if (!sea.isLand[y * w + x]) temperature.data[y * w + x] = t
            }
        }
    }

    /**
     * Carries temperature along the flow.
     *
     * Each pass moves every parcel a little way upstream and blends, so warm water reaches poleward
     * along one side of a gyre and cold water reaches equatorward along the other. This is the
     * entire reason the stage exists: it is what separates Bergen from Labrador.
     */
    private fun advectTemperature(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        velocityX: FloatField,
        velocityY: FloatField,
        temperature: FloatField
    ) {
        val w = config.width
        val h = config.height
        val cfg = config.climate

        var current = temperature.data.copyOf()
        var next = current.copyOf()

        repeat(config.ocean.advectionPasses) {
            val read = current
            val write = next
            parallelChunks(0, h) { startY, endY ->
                for (y in startY until endY) {
                    for (x in 0 until w) {
                        val i = y * w + x
                        // Land keeps its previous value rather than zero: a later blur or sample
                        // that touched a zeroed land cell would drag coastal water toward freezing
                        // and invent an anomaly along every shoreline.
                        if (sea.isLand[i]) { write[i] = read[i]; continue }

                        val sampled = sampleUpstream(
                            read, sea, w, h,
                            x - velocityX.data[i], y - velocityY.data[i], read[i]
                        )

                        val lat = 90f - 180f * (y + 0.5f) / h
                        val local = zonalTemperature(lat, cfg.equatorTemperatureC, cfg.poleTemperatureC)
                        // Relax back toward the latitude's own temperature, or a current would
                        // eventually carry tropical water all the way to the pole.
                        val carried = read[i] + (sampled - read[i]) * config.ocean.advectionRate
                        write[i] = carried + (local - carried) * config.ocean.relaxationRate
                    }
                }
            }
            val swap = current; current = next; next = swap
        }
        current.copyInto(temperature.data)
    }

    private fun buildAnomaly(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        temperature: FloatField,
        anomaly: FloatField
    ) {
        val w = config.width
        val h = config.height
        for (y in 0 until h) {
            var sum = 0.0
            var count = 0
            for (x in 0 until w) {
                if (!sea.isLand[y * w + x]) { sum += temperature.data[y * w + x]; count++ }
            }
            if (count == 0) continue
            val mean = (sum / count).toFloat()
            for (x in 0 until w) {
                val i = y * w + x
                if (!sea.isLand[i]) anomaly.data[i] = temperature.data[i] - mean
            }
        }
    }

    private fun sampleUpstream(
        data: FloatArray,
        sea: SeaLevelResult,
        w: Int,
        h: Int,
        fx: Float,
        fy: Float,
        fallback: Float
    ): Float {
        var x = fx.toInt() % w
        if (x < 0) x += w
        val y = fy.toInt().coerceIn(0, h - 1)
        val i = y * w + x
        // Upstream of a coastal cell is often land, and no water arrives from there.
        return if (sea.isLand[i]) fallback else data[i]
    }

    /** A smooth pole-to-equator profile, matching the one land temperature uses. */
    private fun zonalTemperature(latitude: Float, equator: Float, pole: Float): Float {
        val t = cos(latitude * PI / 180.0).toFloat().coerceIn(0f, 1f)
        return pole + (equator - pole) * t
    }
}
