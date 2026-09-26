package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.model.WorldScale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.expm1
import kotlin.math.min
import kotlin.math.sin

/**
 * The sea-surface temperature the currents carry: the steady state of
 *
 * `u·∇T - K ∇²T + (T - T_latitude) / τ = 0`
 *
 * solved on the circulation's own grid by the same multigrid ([OceanCirculation.solve]).
 *
 * Three processes, each with an Earth figure behind it. The current carries the water's heat at its
 * own speed. Mesoscale eddies mix it sideways, at the eddy diffusivity `K` ([diffusivity]); without
 * them a front between two gyres is as sharp as the grid and runs straight along a line of latitude
 * for as far as the gyres do, which is not what any ocean looks like. And the surface exchanges heat
 * with the air above it, relaxing toward its latitude's own temperature over τ
 * ([OceanStage.RELAXATION_SECONDS]).
 *
 * **The discretization** is finite volume on the grid's cells, in the advective form `u·∇T`.
 * Across each face the flux is exponentially fitted (Scharfetter and Gummel 1969, *IEEE Trans.
 * Electron Devices* 16, 64-77; the same fitting as the circulation's β term): with the face's
 * Péclet number `P = u Δ / K`, the neighbor's weight is `K B(P) / Δ²` with `B(x) = x / (e^x - 1)`.
 * That is the central difference where the eddies dominate and the upwind difference where the
 * current does, exact at the nodes for the one-dimensional balance of the two, and never negative.
 * In the advective form the center weight is the neighbors' sum plus `1/τ` for any velocity field,
 * so the operator is strictly diagonally dominant with a margin of `1/τ`: monotone, and its solution
 * cannot be further from the converged one than τ times the largest residual.
 *
 * **The velocity through a face** is taken from ψ at the face's two corners, each the mean of the
 * four cell centers around it, so the discrete divergence of the face velocities is exactly zero
 * over every cell of open water.
 *
 * **The coast** is a face with no flux: no current crosses it and no eddy mixes across it, so no
 * heat leaves the water through land, and a strip of land a cell wide keeps two seas apart. Nor
 * does any heat cross a pole.
 */
object OceanHeat {

    /**
     * The eddy diffusivity at the latitudes where it is least, in square meters a second: 2,500.
     *
     * Zhurbas and Oh (2003, *J. Geophys. Res.* 108(C5), doi:10.1029/2002JC001596), from the Global Drifter Program's
     * surface drifters over the whole Pacific in 5-degree bins, 1979-1999: the lowest values,
     * 2-3 × 10³ m²/s, are typical of the forty-odd degrees poleward of the subpolar fronts; the
     * highest, 2-3 × 10⁴, of the eastern equatorial Pacific; about 1 × 10⁴ along the whole equator
     * and in the Kuroshio Extension. The middle of their lowest range, at their "forty-odd degrees",
     * taken here as 45.
     */
    const val MIDLATITUDE_DIFFUSIVITY_M2_PER_S = 2_500.0

    /** Where [MIDLATITUDE_DIFFUSIVITY_M2_PER_S] is read, in degrees of latitude. */
    const val MIDLATITUDE_DEGREES = 45.0

    /**
     * The eddy diffusivity along the equator, in square meters a second: 10,000, Zhurbas and Oh's
     * "about 1 × 10⁴ ... along the whole length of the equator" (see [MIDLATITUDE_DIFFUSIVITY_M2_PER_S]).
     * The ceiling [diffusivity] rises to toward the equator.
     */
    const val EQUATORIAL_DIFFUSIVITY_M2_PER_S = 10_000.0

    /**
     * The eddy diffusivity at [latitudeDegrees], in square meters a second.
     *
     * Zhurbas and Oh find the drifters' Lagrangian length scale in the midlatitudes close to the
     * first baroclinic Rossby radius of deformation, and suggest `K = V · R_d` there, with `V` the
     * eddies' velocity scale. `R_d = c / |f|`: the first baroclinic mode's speed `c` is the water
     * column's stratification, and `f = 2Ω sin φ` the planet's spin. So `K` goes as `1 / |sin φ|`
     * from its midlatitude figure, held constant in `V` and `c`, which this generator has no
     * stratification or eddy field to vary. Neither reads the planet's radius, and the rotation is
     * [WorldScale.ROTATION_RATE_PER_S]: a world of any size has Earth's midlatitude diffusivities.
     *
     * Toward the equator the same drifters show the scaling break down, with the Lagrangian length
     * falling below `R_d`, which grows without bound as `f` goes to zero; so the diffusivity is
     * capped at the equator's measured figure, [EQUATORIAL_DIFFUSIVITY_M2_PER_S]. The cap is reached at
     * 10.2 degrees. The equatorial deformation radius `sqrt(c / 2β)` would put a scaling with the
     * planet's radius there, as its square root; the drifters say the mixing there is not the
     * midlatitude eddies', and no published scaling of the equatorial figure with β was found, so
     * the measured figure is carried to every radius and this is stated rather than guessed at.
     */
    fun diffusivity(latitudeDegrees: Double): Double {
        val midlatitudeSine = sin(MIDLATITUDE_DEGREES * PI / 180.0)
        val sine = abs(sin(latitudeDegrees * PI / 180.0))
        val scaled = MIDLATITUDE_DIFFUSIVITY_M2_PER_S * midlatitudeSine / sine
        return if (sine == 0.0) EQUATORIAL_DIFFUSIVITY_M2_PER_S else min(scaled, EQUATORIAL_DIFFUSIVITY_M2_PER_S)
    }

    /**
     * The heat problem on one grid.
     *
     * [stream] is the circulation's ψ on this grid, in square meters a second; [targetC] is the
     * temperature each cell relaxes toward, in degrees Celsius, zero on land; [relaxationSeconds] is
     * τ. With [withTarget] off the right-hand side is zero, for a coarse grid of the cycle that
     * solves for a correction. Rows are latitude bands pole to pole, as the map's. [diffusivityAt] is
     * [diffusivity] except where a guard holds it constant to compare directions.
     */
    fun stencil(
        cellsAcross: Int,
        cellsDown: Int,
        cellWidthMeters: Double,
        cellHeightMeters: Double,
        isWater: BooleanArray,
        stream: FloatArray,
        targetC: FloatArray,
        relaxationSeconds: Double,
        withTarget: Boolean,
        diffusivityAt: (latitudeDegrees: Double) -> Double = ::diffusivity
    ): OceanStencil {
        val cells = cellsAcross * cellsDown
        val east = FloatArray(cells)
        val west = FloatArray(cells)
        val north = FloatArray(cells)
        val south = FloatArray(cells)
        val centre = DoubleArray(cells) { 1.0 }
        val balance = DoubleArray(cells)
        val relaxationRate = 1.0 / relaxationSeconds
        // Diffusivity along each row and across each boundary between two rows.
        val alongRow = DoubleArray(cellsDown) { diffusivityAt(ClimateStage.latitudeOf(it, cellsDown).toDouble()) }
        val acrossBoundary = DoubleArray(cellsDown + 1) { boundary ->
            diffusivityAt(90.0 - 180.0 * boundary / cellsDown)
        }
        for (row in 0 until cellsDown) {
            for (column in 0 until cellsAcross) {
                val cell = row * cellsAcross + column
                if (!isWater[cell]) continue
                val columnEast = if (column + 1 == cellsAcross) 0 else column + 1
                val columnWest = if (column == 0) cellsAcross - 1 else column - 1
                // ψ at the four corners of this cell, each the mean of the four centers around it.
                val northEast = corner(stream, isWater, cellsAcross, cellsDown, row, column, columnEast, north = true)
                val southEast = corner(stream, isWater, cellsAcross, cellsDown, row, column, columnEast, north = false)
                val northWest = corner(stream, isWater, cellsAcross, cellsDown, row, columnWest, column, north = true)
                val southWest = corner(stream, isWater, cellsAcross, cellsDown, row, columnWest, column, north = false)
                // Outward velocities through each face, meters a second: u = -∂ψ/∂y, v = ∂ψ/∂x.
                val outEast = -(northEast - southEast) / cellHeightMeters
                val outWest = (northWest - southWest) / cellHeightMeters
                val outNorth = (northEast - northWest) / cellWidthMeters
                val outSouth = -(southEast - southWest) / cellWidthMeters
                val eastWeight = if (isWater[row * cellsAcross + columnEast]) {
                    faceWeight(alongRow[row], outEast, cellWidthMeters)
                } else 0.0
                val westWeight = if (isWater[row * cellsAcross + columnWest]) {
                    faceWeight(alongRow[row], outWest, cellWidthMeters)
                } else 0.0
                val northWeight = if (row > 0 && isWater[cell - cellsAcross]) {
                    faceWeight(acrossBoundary[row], outNorth, cellHeightMeters)
                } else 0.0
                val southWeight = if (row + 1 < cellsDown && isWater[cell + cellsAcross]) {
                    faceWeight(acrossBoundary[row + 1], outSouth, cellHeightMeters)
                } else 0.0
                val centreWeight = eastWeight + westWeight + northWeight + southWeight + relaxationRate
                east[cell] = (eastWeight / centreWeight).toFloat()
                west[cell] = (westWeight / centreWeight).toFloat()
                north[cell] = (northWeight / centreWeight).toFloat()
                south[cell] = (southWeight / centreWeight).toFloat()
                centre[cell] = centreWeight
                // The balance `Σ a T_neighbor - a_center T = -T_target / τ`.
                if (withTarget) balance[cell] = -targetC[cell] * relaxationRate
            }
        }
        return OceanCirculation.withBalance(
            OceanStencil(cellsAcross, cellsDown, isWater, east, west, north, south, FloatArray(cells), centre),
            balance
        )
    }

    /**
     * The weight of one neighbor across a face, per second: `K B(P) / Δ²` with `P = w Δ / K` and
     * [outwardMps] the velocity leaving through the face. Water leaving toward the neighbor gives it
     * little say over this cell's temperature, and water arriving from it gives it most.
     */
    private fun faceWeight(diffusivityM2PerS: Double, outwardMps: Double, spacingMeters: Double): Double {
        val peclet = outwardMps * spacingMeters / diffusivityM2PerS
        return diffusivityM2PerS / (spacingMeters * spacingMeters) * bernoulli(peclet)
    }

    /**
     * `x / (e^x - 1)`: one at zero, `-x` far upstream, zero far downstream. Its series `1 - x/2`
     * below a thousandth, where the direct form would lose digits dividing by `e^x - 1`.
     */
    private fun bernoulli(x: Double): Double = when {
        abs(x) < BERNOULLI_SERIES_BELOW -> 1.0 - x / 2.0
        x > BERNOULLI_ZERO_ABOVE -> 0.0
        else -> x / expm1(x)
    }

    private const val BERNOULLI_SERIES_BELOW = 1e-3

    /** Beyond this the exponential overflows a double and the weight is zero to its last digit. */
    private const val BERNOULLI_ZERO_ABOVE = 700.0

    /**
     * ψ at the corner between columns [westColumn] and [eastColumn] on the [north] or south side
     * of [row]: the mean of the four cell centers around it, land holding ψ at zero, and beyond a
     * pole minus the edge row's, the circulation's wall, which puts the corner on the pole at zero.
     */
    private fun corner(
        stream: FloatArray,
        isWater: BooleanArray,
        cellsAcross: Int,
        cellsDown: Int,
        row: Int,
        westColumn: Int,
        eastColumn: Int,
        north: Boolean
    ): Double {
        val otherRow = if (north) row - 1 else row + 1
        fun at(anyRow: Int, column: Int): Double {
            if (anyRow < 0 || anyRow >= cellsDown) return -at(row, column)
            val cell = anyRow * cellsAcross + column
            return if (isWater[cell]) stream[cell].toDouble() else 0.0
        }
        return (at(row, westColumn) + at(row, eastColumn) + at(otherRow, westColumn) + at(otherRow, eastColumn)) / 4.0
    }
}
