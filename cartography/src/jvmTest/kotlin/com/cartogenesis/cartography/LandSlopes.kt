package com.cartogenesis.cartography

import com.cartogenesis.worldgen.model.WorldMap
import java.util.Locale
import kotlin.math.sqrt

/**
 * The land's slopes as a hachure reads them, on the ground: what [EngravingPlan.SLOPE_FLOOR] and
 * Pen and ink's gain are set from.
 *
 * Shared by `ReliefShadingTest`, which holds the floor's derivation beside the exaggeration that
 * sets the slopes' scale, and `PenAndInkTest`, which holds the gain and reads the fall lines.
 */
internal object LandSlopes {

    /** The land slopes of [world] in ascending order, at [plan]'s stencil and scale, on the ground. */
    fun ascending(world: WorldMap, plan: EngravingPlan): List<Float> {
        val width = world.width
        val elevation = DrawnRelief.of(world)
        val reachColumns = plan.gradientStencilColumns
        val reachRows = plan.gradientStencilRows
        val slopes = ArrayList<Float>()
        for (cell in world.sea.isLand.indices) {
            if (!world.sea.isLand[cell]) continue
            val column = cell % width
            val row = cell / width
            val gradientX =
                (elevation.sample(column + reachColumns, row) -
                    elevation.sample(column - reachColumns, row)) * plan.gradientScaleAcross
            val gradientY =
                (elevation.sample(column, row + reachRows) -
                    elevation.sample(column, row - reachRows)) * plan.gradientScaleDown
            slopes.add(groundSlope(gradientX, gradientY, world))
        }
        slopes.sort()
        return slopes
    }

    /**
     * The ground's steepness from a hachure's two differences, as `Engraving.hachure` reads it: the
     * difference down a column is over rows, a share of a cell width each.
     */
    fun groundSlope(gradientX: Float, gradientY: Float, world: WorldMap): Float {
        val southward = gradientY / world.config.cellHeightInCellWidths.toFloat()
        return sqrt(gradientX * gradientX + southward * southward)
    }

    /** The value [fraction] of the way up [sorted], which is in ascending order. */
    fun percentile(sorted: List<Float>, fraction: Double): Float =
        sorted[(sorted.size * fraction).toInt().coerceAtMost(sorted.size - 1)]

    /** [value] rounded to the hundredth and written out, for comparing figures at that digit. */
    fun hundredths(value: Float): String = String.format(Locale.ROOT, "%.2f", value)
}
