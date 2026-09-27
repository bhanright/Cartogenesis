package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.model.WorldScale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.expm1
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The sea-surface temperature the currents carry: the steady state of
 *
 * `u·∇T - K ∇²T + (T - T_latitude) / τ + (w/h) (T - T_sub) = 0`
 *
 * solved on the circulation's own grid by the same multigrid ([OceanCirculation.solve]).
 *
 * Four processes, each with an Earth figure behind it. The current carries the water's heat at its
 * own speed. Mesoscale eddies mix it sideways, at the eddy diffusivity `K` ([diffusivity]); without
 * them a front between two gyres is as sharp as the grid and runs straight along a line of latitude
 * for as far as the gyres do, which is not what any ocean looks like. And the surface exchanges heat
 * with the air above it, relaxing toward its latitude's own temperature over τ
 * ([OceanStage.RELAXATION_SECONDS]). Where the wind's Ekman transport diverges, water rises into the
 * mixed layer of depth `h` at `w` meters a second and replaces it with water at `T_sub`
 * ([OceanStage.upwellingMps], [OceanStage.subsurfaceTemperatureC]); where it converges, water
 * leaves downward at the layer's own temperature and changes nothing, so `w` counts only rising.
 *
 * **The discretization** is finite volume on the grid's cells, in the advective form `u·∇T`.
 * Across each face the flux is exponentially fitted (Scharfetter and Gummel 1969, *IEEE Trans.
 * Electron Devices* 16, 64-77; the same fitting as the circulation's β term): with the face's
 * Péclet number `P = u Δ / K`, the neighbor's weight is `K B(P) / Δ²` with `B(x) = x / (e^x - 1)`.
 * That is the central difference where the eddies dominate and the upwind difference where the
 * current does, exact at the nodes for the one-dimensional balance of the two, and never negative.
 * In the advective form the center weight is the neighbors' sum plus `1/τ + w/h` for any velocity
 * field, so the operator is strictly diagonally dominant with a margin of at least `1/τ`: monotone,
 * and its solution cannot be further from the converged one than τ times the largest residual. The
 * rising water is a reaction in the center weight and a source in the balance, so the stencil keeps
 * its form and crosses [OceanAccelerator] unchanged.
 *
 * **The velocity through a face** is taken from ψ at the face's two corners, so the flux through a
 * cell's four faces sums to zero whatever the corners hold: `(ψ_NE - ψ_SE) - (ψ_NW - ψ_SW) - (ψ_NE -
 * ψ_NW) + (ψ_SE - ψ_SW)` is zero term by term. A corner in open water is the mean of the four cell
 * centers around it.
 *
 * **The coast** is a face with no flux: no current crosses it and no eddy mixes across it, so no
 * heat leaves the water through land, and a strip of land a cell wide keeps two seas apart. Nor
 * does any heat cross a pole. For the flux left on the open faces to still sum to zero over each
 * cell, the velocity through a shut face must itself be zero, which is the coast's own condition,
 * no flow through it: so a corner that touches land takes the coast's ψ, zero, which the
 * circulation holds on every land body alike, and a face shut at the coast has both its corners
 * there. A corner averaged across the coast instead, land counted as zero among three waters, is
 * not zero, the shut face carries a velocity the heat never sees, and the advective form then
 * makes or loses heat at the coast in proportion to it (`a closed basin neither makes nor loses
 * heat at its coast`, `OceanHeatTest`).
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

    /** Chelton et al.'s near-equatorial deformation radius on Earth, in meters; see [BAROCLINIC_WAVE_SPEED_M_PER_S]. */
    private const val EQUATORIAL_DEFORMATION_RADIUS_EARTH_M = 240_000.0

    /**
     * The speed of the first baroclinic mode's gravity waves, in meters a second: 2.64, the one
     * figure for the water column's stratification this generator takes, held the same at every
     * latitude as it has no stratification of its own to vary.
     *
     * Derived from Chelton et al. (1998, *J. Phys. Oceanogr.* 28, 433-460), whose deformation radius
     * "decreases from about 240 km in the near-equatorial band to less than 10 km at latitudes
     * higher than about 60 degrees", and who take it within about five degrees of the equator as the
     * equatorial radius `sqrt(c / 2β)`: so `c = 2 β R²` with Earth's β at the equator,
     * `2Ω / a`, and R = 240 km. Their mapped speeds run from under 1 m/s at high latitudes to near 3
     * in the tropics, so one speed read at the equator is the tropics' own and too fast poleward,
     * where the radius it makes is still the one [diffusivity] is scaled from at 45 degrees.
     */
    const val BAROCLINIC_WAVE_SPEED_M_PER_S: Double =
        2.0 * (2.0 * WorldScale.ROTATION_RATE_PER_S / OceanStage.EARTH_MEAN_RADIUS_M) *
            EQUATORIAL_DEFORMATION_RADIUS_EARTH_M * EQUATORIAL_DEFORMATION_RADIUS_EARTH_M

    /**
     * The first baroclinic deformation radius at [latitudeDegrees] on a planet of [radiusMeters], in
     * meters: `c / |f|` away from the equator and the equatorial radius `sqrt(c / 2β)` near it,
     * whichever is the smaller (Chelton et al. 1998, as [BAROCLINIC_WAVE_SPEED_M_PER_S]). The two
     * meet at 4.3 degrees on Earth; β grows as the planet shrinks, so on a smaller world the
     * equatorial radius is shorter and takes over further from the equator, 7.9 degrees at a
     * radius of 1,910 km.
     */
    fun deformationRadiusMeters(latitudeDegrees: Double, radiusMeters: Double): Double {
        val latitude = latitudeDegrees * PI / 180.0
        val coriolis = abs(2.0 * WorldScale.ROTATION_RATE_PER_S * sin(latitude))
        val beta = 2.0 * WorldScale.ROTATION_RATE_PER_S * cos(latitude) / radiusMeters
        val offEquator = if (coriolis == 0.0) Double.POSITIVE_INFINITY else BAROCLINIC_WAVE_SPEED_M_PER_S / coriolis
        val equatorial = if (beta <= 0.0) Double.POSITIVE_INFINITY else sqrt(BAROCLINIC_WAVE_SPEED_M_PER_S / (2.0 * beta))
        return min(offEquator, equatorial)
    }

    /**
     * The eddies' velocity scale `V` in `K = V R_d`, in meters a second: 0.098, what makes `K`
     * [MIDLATITUDE_DIFFUSIVITY_M2_PER_S] at [MIDLATITUDE_DEGREES], where the deformation radius is
     * `c / |f|` on any planet, since `f` does not read the radius.
     */
    val EDDY_VELOCITY_MPS: Double =
        MIDLATITUDE_DIFFUSIVITY_M2_PER_S * 2.0 * WorldScale.ROTATION_RATE_PER_S * sin(MIDLATITUDE_DEGREES * PI / 180.0) /
            BAROCLINIC_WAVE_SPEED_M_PER_S

    /**
     * The eddy diffusivity at [latitudeDegrees] on a planet of [radiusMeters], in square meters a
     * second: `K = V R_d`.
     *
     * Zhurbas and Oh find the drifters' Lagrangian length scale in the midlatitudes close to the
     * first baroclinic Rossby radius of deformation, and suggest `K = V · R_d` there, with `V` the
     * eddies' velocity scale ([EDDY_VELOCITY_MPS]), held constant, as this generator has no eddy
     * field to vary it. Away from the equator `R_d = c / |f|`, so `K` goes as `1 / |sin φ|` from its
     * midlatitude figure, and reads no radius: a world of any size has Earth's midlatitude
     * diffusivities. Near the equator `c / |f|` grows without bound, and the deformation radius
     * there is the equatorial one, `sqrt(c / 2β)` ([deformationRadiusMeters]), which is finite, so
     * no cap is needed: `K` rises toward the equator and levels off at `V sqrt(c / 2β)`. That reads
     * the radius through β, as its square root: 23,500 m²/s on Earth, inside the 2-3 × 10⁴ Zhurbas
     * and Oh measure in the eastern equatorial Pacific and above their 1 × 10⁴ along the rest of the
     * equator, and 12,900 at this generator's radius of 1,910 km.
     */
    fun diffusivity(latitudeDegrees: Double, radiusMeters: Double): Double =
        EDDY_VELOCITY_MPS * deformationRadiusMeters(latitudeDegrees, radiusMeters)

    /**
     * The heat problem on one grid.
     *
     * [stream] is the circulation's ψ on this grid, in square meters a second; [targetC] is the
     * temperature each cell relaxes toward, in degrees Celsius, zero on land; [relaxationSeconds] is
     * τ. With [withTarget] off the right-hand side is zero, for a coarse grid of the cycle that
     * solves for a correction. Rows are latitude bands pole to pole, as the map's. [diffusivityAt] is
     * [diffusivity] on the world's radius, except where a guard holds it constant to compare
     * directions. [entrainmentPerS] is `w/h` per cell, the rate rising water renews the mixed layer,
     * per second, and [subsurfaceC] the temperature it rises at, degrees Celsius; both null for no
     * upwelling, and [subsurfaceC] null on a coarse grid, whose right-hand side is zero.
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
        diffusivityAt: (latitudeDegrees: Double) -> Double,
        entrainmentPerS: FloatArray? = null,
        subsurfaceC: FloatArray? = null
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
                val entrainment = entrainmentPerS?.get(cell)?.toDouble() ?: 0.0
                val centreWeight = eastWeight + westWeight + northWeight + southWeight + relaxationRate + entrainment
                east[cell] = (eastWeight / centreWeight).toFloat()
                west[cell] = (westWeight / centreWeight).toFloat()
                north[cell] = (northWeight / centreWeight).toFloat()
                south[cell] = (southWeight / centreWeight).toFloat()
                centre[cell] = centreWeight
                // The balance `Σ a T_neighbor - a_center T = -T_target / τ - (w/h) T_sub`.
                if (withTarget) {
                    balance[cell] = -targetC[cell] * relaxationRate - entrainment * (subsurfaceC?.get(cell)?.toDouble() ?: 0.0)
                }
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
    internal fun faceWeight(diffusivityM2PerS: Double, outwardMps: Double, spacingMeters: Double): Double {
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

    /** ψ on every coast, in square meters a second: the circulation holds every land cell at zero. */
    private const val COAST_STREAM_M2_PER_S = 0.0

    /** Beyond this the exponential overflows a double and the weight is zero to its last digit. */
    private const val BERNOULLI_ZERO_ABOVE = 700.0

    /**
     * ψ at the corner between columns [westColumn] and [eastColumn] on the [north] or south side
     * of [row]: zero where any of the four cells around it is land, the coast's ψ, and otherwise
     * their mean; beyond a pole the edge row's negated, the circulation's wall, which puts the
     * corner on the pole at zero.
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
        fun onLand(anyRow: Int, column: Int): Boolean =
            anyRow in 0 until cellsDown && !isWater[anyRow * cellsAcross + column]
        if (onLand(row, westColumn) || onLand(row, eastColumn) || onLand(otherRow, westColumn) || onLand(otherRow, eastColumn)) {
            return COAST_STREAM_M2_PER_S
        }
        fun at(anyRow: Int, column: Int): Double {
            if (anyRow < 0 || anyRow >= cellsDown) return -at(row, column)
            return stream[anyRow * cellsAcross + column].toDouble()
        }
        return (at(row, westColumn) + at(row, eastColumn) + at(otherRow, westColumn) + at(otherRow, eastColumn)) / 4.0
    }
}
