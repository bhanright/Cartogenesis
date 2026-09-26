package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ChannelInitiation
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The comb on the flanks, counted: channel reaches that run straight along one grid axis for a
 * sustained length, with another such reach running the same way a short ground distance across,
 * and a ridge standing between the two. See `CombGuardTest` for why each of the three.
 *
 * Every length is on the ground, in kilometres, so the two axes are asked the same question: a
 * reach down a column counts its rows at a row's height each, a reach along a row its columns at a
 * cell's width, and the partner is looked for at the same kilometres across on both.
 */
internal object CombCensus {

    /** One axis's figures, per thousand square kilometres of land. */
    class Axis(
        val name: String,
        /** Channel along this axis in straight reaches at least the sustained length. */
        val sustainedKmPer1000Km2: Double,
        /** Of that, the channel with a partner reach across and a ridge at least the depth between. */
        val combedKmPer1000Km2: Double
    )

    class Result(val column: Axis, val row: Axis, val channelKmPer1000Km2: Double)

    /**
     * The census over [world]'s own network: the channels its channel-head criterion initiates, on
     * the drainage it drew, over the ground it ended on.
     */
    fun of(world: WorldMap, sustainedKm: Double, nearestKm: Double, furthestKm: Double, ridgeMetres: Double): Result {
        val scale = world.config.scale
        val rel = world.sea.relativeElevation.data
        return of(
            world.config, world.sea.isLand, ChannelInitiation.channelMaskOf(world), world.rivers.flowTarget,
            DoubleArray(rel.size) { scale.metresAboveShoreline(rel[it]).toDouble() },
            sustainedKm, nearestKm, furthestKm, ridgeMetres
        )
    }

    fun of(
        config: WorldGenConfig,
        isLand: BooleanArray,
        channel: BooleanArray,
        target: IntArray,
        heightMetres: DoubleArray,
        sustainedKm: Double,
        nearestKm: Double,
        furthestKm: Double,
        ridgeMetres: Double
    ): Result {
        val w = config.width
        val h = config.height
        val cellWidthKm = config.cellWidthKm
        val cellHeightKm = config.cellHeightKm
        val landKm2 = isLand.count { it } * cellWidthKm * cellHeightKm
        var channelKm = 0.0
        for (c in 0 until w * h) {
            if (!channel[c]) continue
            val t = target[c]
            if (t < 0 || !isLand[t]) continue
            channelKm += config.groundSteps.between(c, t, w) * cellWidthKm
        }
        val axes = ArrayList<Axis>()
        for (columnwise in listOf(true, false)) {
            val stepKm = if (columnwise) cellHeightKm else cellWidthKm
            val acrossKm = if (columnwise) cellWidthKm else cellHeightKm
            // Each channel cell's step along this axis: +1 or -1, or 0 if it steps any other way.
            val step = IntArray(w * h)
            for (c in 0 until w * h) {
                if (!channel[c]) continue
                val t = target[c]
                if (t < 0 || !isLand[t]) continue
                val dc = ((t % w - c % w) + w + w / 2) % w - w / 2
                val dr = t / w - c / w
                step[c] = if (columnwise) (if (dc == 0 && dr != 0) dr else 0) else (if (dr == 0 && dc != 0) dc else 0)
            }
            // Each straight reach's length in cells, written on every cell of it.
            val reach = IntArray(w * h)
            for (c in 0 until w * h) {
                if (step[c] == 0 || reach[c] != 0) continue
                var head = c
                while (true) {
                    val up = if (columnwise) head - step[c] * w else (head / w) * w + ((head % w - step[c]) + w) % w
                    if (up < 0 || up >= w * h || step[up] != step[c] || target[up] != head) break
                    head = up
                }
                val cells = ArrayList<Int>()
                var at = head
                while (at >= 0 && step[at] == step[c] && cells.size <= w + h) {
                    cells.add(at)
                    at = target[at]
                }
                for (x in cells) reach[x] = cells.size
            }
            val sustainedCells = ceil(sustainedKm / stepKm).toInt()
            val nearest = ceil(nearestKm / acrossKm).toInt().coerceAtLeast(2)
            val furthest = floor(furthestKm / acrossKm).toInt()
            var sustained = 0.0
            var combed = 0.0
            for (c in 0 until w * h) {
                if (step[c] == 0 || reach[c] < sustainedCells) continue
                sustained += stepKm
                val row = c / w
                val column = c % w
                var deepest = Double.NEGATIVE_INFINITY
                for (k in nearest..furthest) for (side in intArrayOf(-1, 1)) {
                    val other = if (columnwise) row * w + ((column + side * k) % w + w) % w
                    else (row + side * k).let { if (it < 0 || it >= h) -1 else it * w + column }
                    if (other < 0 || step[other] != step[c] || reach[other] < sustainedCells) continue
                    var ridge = Double.MAX_VALUE
                    for (j in 1 until k) {
                        val between = if (columnwise) row * w + ((column + side * j) % w + w) % w else (row + side * j) * w + column
                        ridge = minOf(ridge, heightMetres[between])
                    }
                    deepest = maxOf(deepest, ridge - maxOf(heightMetres[c], heightMetres[other]))
                }
                if (deepest >= ridgeMetres) combed += stepKm
            }
            axes.add(
                Axis(
                    if (columnwise) "down a column" else "along a row",
                    1000.0 * sustained / landKm2, 1000.0 * combed / landKm2
                )
            )
        }
        return Result(axes[0], axes[1], 1000.0 * channelKm / landKm2)
    }
}
