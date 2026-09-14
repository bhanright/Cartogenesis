package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.model.Acceleration
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
 *
 * See REALISM_PLAN.md, G3 and H4.
 */
object OceanStage {

    /**
     * Where the trade-wind belt gives way to the westerlies, in degrees of latitude.
     *
     * The three-cell circulation's own boundaries: the Hadley cell's surface leg reaches to about
     * 30 degrees and the Ferrel cell's runs from there to about 60. [ClimateStage]'s wind belts
     * use the same two edges, because they are describing the same circulation.
     */
    private const val TRADE_BELT_EDGE_DEGREES = 30f

    /** Where the westerlies give way to the polar easterlies, in degrees of latitude. */
    private const val WESTERLY_BELT_EDGE_DEGREES = 60f

    /** Middle of the westerly belt, where its eastward stress is strongest. */
    private const val WESTERLY_BELT_CENTRE_DEGREES = 45f

    /** Middle of the polar-easterly belt, where its westward stress is strongest. */
    private const val POLAR_BELT_CENTRE_DEGREES = 75f

    /**
     * Degrees of cosine phase per degree of latitude inside the trade belt.
     *
     * Three, so that the quarter cosine runs from full westward stress at the equator to zero at
     * [TRADE_BELT_EDGE_DEGREES]: 90 degrees of phase over 30 degrees of latitude.
     */
    private const val TRADE_PHASE_PER_DEGREE = 3.0

    /**
     * The same, for the two belts poleward of the trades.
     *
     * Six, because each of those belts is 30 degrees wide and its cosine is centred rather than
     * cornered: 90 degrees of phase over the 15 degrees from its centre to its edge, so the lobe
     * is zero at both edges and full in the middle.
     */
    private const val MID_AND_POLAR_PHASE_PER_DEGREE = 6.0

    /**
     * How much weaker the polar easterlies drag than the trades and the westerlies.
     *
     * The polar cell is the shallowest and weakest of the three, and left at full strength its
     * curl against the westerlies spins a subpolar gyre as strong as the subtropical one.
     */
    private const val POLAR_EASTERLY_STRENGTH = 0.6f

    /**
     * Weight of each neighbour in the five-point Laplacian the relaxation inverts.
     *
     * A quarter: `∇²ψ` at a cell is the mean of its four neighbours less the cell itself, so the
     * cell that satisfies `∇²ψ = curl` given its neighbours is `(sum of four - curl) / 4`.
     */
    private const val FIVE_POINT_LAPLACIAN_WEIGHT = 0.25f

    /**
     * Scale of a central difference over one cell: `(next - previous) / 2`.
     *
     * The grid spacing is one cell by construction, so the only factor left is the two.
     */
    private const val CENTRAL_DIFFERENCE_SCALE = 0.5f

    /**
     * Below this top speed, in stream-function units, the solve produced no circulation at all and
     * there is nothing to normalise — a world with no open water, or a forcing of zero.
     */
    private const val MIN_MEANINGFUL_SPEED = 1e-6f

    /** Smallest over-relaxation worth applying, and the largest that still converges. */
    private const val MIN_OVER_RELAXATION = 0.5f
    private const val MAX_OVER_RELAXATION = 1.95f

    /**
     * A sea with its temperature but without its currents: the base sea-surface temperature by
     * latitude and nothing else, no gyres and a zero anomaly everywhere.
     *
     * For the provisional climate, which runs before the ice is carved and only to say where the
     * ice is. The gyre solve is the expensive half of that provisional climate and is worth well
     * under two per cent of the ice mask, so it is skipped there. The real ocean, currents and
     * all, is solved once as it always was, on the carved terrain, and the climate that classifies
     * the map the reader sees is computed from it. See REALISM_PLAN.md, H2, for the measured cost
     * and the measured difference.
     */
    internal fun withoutCurrents(config: WorldGenConfig, sea: SeaLevelResult): OceanResult =
        generate(config.copy(ocean = config.ocean.copy(enabled = false)), sea)

    /**
     * Solves the surface circulation for a world and carries its temperature around it.
     *
     * [sea] supplies the land mask the gyres close against. The result's velocities are in cells
     * per advection pass, its temperature in degrees Celsius, and its anomaly in degrees away from
     * the mean of the same row's open water. With `OceanConfig.enabled` off, every velocity and
     * every anomaly is zero and the temperature is the bare latitude profile.
     */
    fun generate(config: WorldGenConfig, sea: SeaLevelResult): OceanResult =
        generateOcean(config, sea) {
            solveStreamFunction(config, sea) { _, _, _, _, _, _ -> null }
        }

    /**
     * The same circulation, optionally solving on [accelerator] when graphics acceleration is on.
     * Inputs and units are those of [generate]; a declined solve uses the CPU reference.
     */
    suspend fun generate(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        accelerator: OceanAccelerator?
    ): OceanResult = generateOcean(config, sea) {
        solveStreamFunction(config, sea) { across, down, water, forcing, passes, overRelaxation ->
            if (config.erosion.acceleration == Acceleration.GPU) {
                accelerator?.solve(across, down, water, forcing, passes, overRelaxation)
            } else null
        }
    }

    private inline fun generateOcean(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        solve: () -> FloatField
    ): OceanResult {
        val cellsAcross = config.width
        val cellsDown = config.height
        val oceanConfig = config.ocean

        val velocityX = FloatField(cellsAcross, cellsDown)
        val velocityY = FloatField(cellsAcross, cellsDown)
        val temperature = FloatField(cellsAcross, cellsDown)
        val anomaly = FloatField(cellsAcross, cellsDown)

        // The same energy balance the climate stage reads, solved again here rather than passed
        // in: this stage runs first, and a sea whose temperature came off a different curve from
        // the land's would put back the two rulers S1 spent a chunk removing.
        val zonal = ClimateStage.zonalClimate(config, sea)
        fillBaseTemperature(config, sea, zonal, temperature)
        if (!oceanConfig.enabled) return OceanResult(velocityX, velocityY, temperature, anomaly)

        val streamFunction = solve()
        streamToVelocity(config, sea, streamFunction, velocityX, velocityY)
        advectTemperature(config, sea, zonal, velocityX, velocityY, temperature)
        buildAnomaly(config, sea, temperature, anomaly)

        return OceanResult(velocityX, velocityY, temperature, anomaly)
    }

    /**
     * Zonal wind stress at a latitude, positive eastward. Its *variation* with latitude is the
     * entire forcing: a stress that did not change with latitude would have no curl and spin
     * nothing.
     */
    private fun windStress(latitude: Float): Float {
        val absoluteLatitude = abs(latitude)
        return when {
            absoluteLatitude < TRADE_BELT_EDGE_DEGREES ->
                -cos(latitude * TRADE_PHASE_PER_DEGREE * PI / 180.0).toFloat()
            absoluteLatitude < WESTERLY_BELT_EDGE_DEGREES ->
                cos(
                    (absoluteLatitude - WESTERLY_BELT_CENTRE_DEGREES) *
                        MID_AND_POLAR_PHASE_PER_DEGREE * PI / 180.0
                ).toFloat()
            else ->
                -cos(
                    (absoluteLatitude - POLAR_BELT_CENTRE_DEGREES) *
                        MID_AND_POLAR_PHASE_PER_DEGREE * PI / 180.0
                ).toFloat() * POLAR_EASTERLY_STRENGTH
        }
    }

    /**
     * Solves `∇²ψ = curl` on the coarse grid and interpolates ψ back to full resolution.
     *
     * Returns one value per full-resolution cell, in stream-function units — arbitrary, since
     * [streamToVelocity] normalises the gradients it takes from them.
     */
    private inline fun solveStreamFunction(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        accelerate: (Int, Int, BooleanArray, FloatArray, Int, Float) -> FloatArray?
    ): FloatField {
        val cellsAcross = config.width
        val cellsDown = config.height
        val oceanConfig = config.ocean

        // Coarse grid, but never coarser than the basins we are trying to resolve.
        val coarseAcross = minOf(cellsAcross, oceanConfig.solveResolution)
        val coarseDown = minOf(cellsDown, oceanConfig.solveResolution)
        val cellsPerCoarseColumn = cellsAcross / coarseAcross
        val cellsPerCoarseRow = cellsDown / coarseDown

        // A coarse cell is water only if most of the fine cells under it are, so a scatter of
        // islands does not wall off an ocean that is really open.
        val coarseIsWater = BooleanArray(coarseAcross * coarseDown)
        for (coarseRow in 0 until coarseDown) {
            for (coarseColumn in 0 until coarseAcross) {
                var waterCells = 0
                var totalCells = 0
                for (rowWithin in 0 until cellsPerCoarseRow) {
                    for (columnWithin in 0 until cellsPerCoarseColumn) {
                        val cell = (coarseRow * cellsPerCoarseRow + rowWithin) * cellsAcross +
                            (coarseColumn * cellsPerCoarseColumn + columnWithin)
                        totalCells++
                        if (!sea.isLand[cell]) waterCells++
                    }
                }
                coarseIsWater[coarseRow * coarseAcross + coarseColumn] =
                    waterCells * 2 > totalCells
            }
        }

        val curl = FloatArray(coarseAcross * coarseDown)
        for (coarseRow in 0 until coarseDown) {
            val latitudeNorth =
                ClimateStage.latitudeOf(coarseRow * cellsPerCoarseRow, cellsDown)
            val latitudeSouth =
                ClimateStage.latitudeOf((coarseRow + 1) * cellsPerCoarseRow, cellsDown)
            // The curl of a purely zonal stress is -d(stress)/dy.
            val rowCurl =
                -(windStress(latitudeSouth) - windStress(latitudeNorth)) * oceanConfig.forcing
            for (coarseColumn in 0 until coarseAcross) {
                curl[coarseRow * coarseAcross + coarseColumn] = rowCurl
            }
        }

        val overRelaxation =
            oceanConfig.overRelaxation.coerceIn(MIN_OVER_RELAXATION, MAX_OVER_RELAXATION)
        val accelerated = accelerate(
            coarseAcross, coarseDown, coarseIsWater, curl,
            oceanConfig.relaxationPasses, overRelaxation
        )
        val coarseStream = accelerated ?: solveOnCpu(
            coarseAcross, coarseDown, coarseIsWater, curl,
            oceanConfig.relaxationPasses, overRelaxation
        )

        return interpolateStream(config, coarseAcross, coarseDown, coarseStream)
    }

    private fun solveOnCpu(
        coarseAcross: Int,
        coarseDown: Int,
        coarseIsWater: BooleanArray,
        curl: FloatArray,
        passes: Int,
        overRelaxation: Float
    ): FloatArray {
        val coarseStream = FloatArray(coarseAcross * coarseDown)

        // Red-black Gauss-Seidel with over-relaxation.
        //
        // Updating in place is what makes this Gauss-Seidel rather than Jacobi, and Gauss-Seidel
        // is what makes over-relaxation legal: an omega above 1 applied to Jacobi diverges to NaN.
        // Colouring by (x + y) parity on an even-width cylinder means
        // no two cells updated together are neighbours, so each colour can still run in parallel.
        repeat(passes) {
            for (colour in 0..1) {
                parallelChunks(0, coarseDown) { startRow, endRow ->
                    for (coarseRow in startRow until endRow) {
                        for (coarseColumn in 0 until coarseAcross) {
                            if ((coarseColumn + coarseRow) and 1 != colour) continue
                            val coarseCell = coarseRow * coarseAcross + coarseColumn
                            // Land pins the stream function at zero, which is what turns a coast
                            // into a wall the circulation has to follow.
                            if (!coarseIsWater[coarseCell]) {
                                coarseStream[coarseCell] = 0f
                                continue
                            }

                            var east = coarseColumn + 1; if (east >= coarseAcross) east = 0
                            var west = coarseColumn - 1
                            if (west < 0) west = coarseAcross - 1
                            val north = (coarseRow - 1).coerceAtLeast(0)
                            val south = (coarseRow + 1).coerceAtMost(coarseDown - 1)

                            val neighbourSum = coarseStream[coarseRow * coarseAcross + east] +
                                coarseStream[coarseRow * coarseAcross + west] +
                                coarseStream[north * coarseAcross + coarseColumn] +
                                coarseStream[south * coarseAcross + coarseColumn]
                            val relaxed =
                                (neighbourSum - curl[coarseCell]) * FIVE_POINT_LAPLACIAN_WEIGHT
                            coarseStream[coarseCell] +=
                                (relaxed - coarseStream[coarseCell]) * overRelaxation
                        }
                    }
                }
            }
        }

        return coarseStream
    }

    private fun interpolateStream(
        config: WorldGenConfig,
        coarseAcross: Int,
        coarseDown: Int,
        coarseStream: FloatArray
    ): FloatField {
        val cellsAcross = config.width
        val cellsDown = config.height
        val cellsPerCoarseColumn = cellsAcross / coarseAcross
        val cellsPerCoarseRow = cellsDown / coarseDown
        // Back up to full resolution, bilinearly.
        val streamFunction = FloatField(cellsAcross, cellsDown)
        parallelChunks(0, cellsDown) { startRow, endRow ->
            for (row in startRow until endRow) {
                val coarseRowPosition = (row.toFloat() / cellsPerCoarseRow - 0.5f)
                    .coerceIn(0f, (coarseDown - 1).toFloat())
                val coarseRowAbove = coarseRowPosition.toInt().coerceAtMost(coarseDown - 1)
                val coarseRowBelow = (coarseRowAbove + 1).coerceAtMost(coarseDown - 1)
                val rowBlend = coarseRowPosition - coarseRowAbove
                for (column in 0 until cellsAcross) {
                    val coarseColumnPosition = column.toFloat() / cellsPerCoarseColumn - 0.5f
                    var coarseColumnLeft = coarseColumnPosition.toInt()
                    if (coarseColumnPosition < 0) coarseColumnLeft -= 1
                    val columnBlend = coarseColumnPosition - coarseColumnLeft
                    // The map wraps east to west, so the left and right samples are taken modulo
                    // the coarse width rather than clamped the way the rows above are.
                    val leftWrapped = ((coarseColumnLeft % coarseAcross) + coarseAcross) %
                        coarseAcross
                    val rightWrapped = ((coarseColumnLeft + 1) % coarseAcross + coarseAcross) %
                        coarseAcross

                    val alongTop = coarseStream[coarseRowAbove * coarseAcross + leftWrapped] *
                        (1 - columnBlend) +
                        coarseStream[coarseRowAbove * coarseAcross + rightWrapped] * columnBlend
                    val alongBottom = coarseStream[coarseRowBelow * coarseAcross + leftWrapped] *
                        (1 - columnBlend) +
                        coarseStream[coarseRowBelow * coarseAcross + rightWrapped] * columnBlend
                    streamFunction.data[row * cellsAcross + column] =
                        alongTop * (1 - rowBlend) + alongBottom * rowBlend
                }
            }
        }
        return streamFunction
    }

    /**
     * Velocity is the perpendicular gradient of ψ, so flow follows its contours.
     *
     * Writes [velocityX] and [velocityY] in cells per advection pass, zero on land, with the
     * fastest water in the world sitting at exactly `OceanConfig.speedCellsPerPass`. Normalising
     * is the point: the raw gradient magnitude depends on forcing, grid size and how far the
     * relaxation converged — none of which should decide how fast water moves.
     */
    private fun streamToVelocity(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        streamFunction: FloatField,
        velocityX: FloatField,
        velocityY: FloatField
    ) {
        val cellsAcross = config.width
        val cellsDown = config.height

        var fastest = 0f
        for (row in 0 until cellsDown) {
            for (column in 0 until cellsAcross) {
                val cell = row * cellsAcross + column
                if (sea.isLand[cell]) continue

                var east = column + 1; if (east >= cellsAcross) east = 0
                var west = column - 1; if (west < 0) west = cellsAcross - 1
                val north = (row - 1).coerceAtLeast(0)
                val south = (row + 1).coerceAtMost(cellsDown - 1)

                val eastward = (streamFunction.data[south * cellsAcross + column] -
                    streamFunction.data[north * cellsAcross + column]) * CENTRAL_DIFFERENCE_SCALE
                val southward = -(streamFunction.data[row * cellsAcross + east] -
                    streamFunction.data[row * cellsAcross + west]) * CENTRAL_DIFFERENCE_SCALE
                velocityX.data[cell] = eastward
                velocityY.data[cell] = southward
                fastest = maxOf(fastest, sqrt(eastward * eastward + southward * southward))
            }
        }

        if (fastest <= MIN_MEANINGFUL_SPEED) return
        val scale = config.ocean.speedCellsPerPass / fastest
        parallelChunks(0, cellsAcross * cellsDown) { startCell, endCell ->
            for (cell in startCell until endCell) {
                velocityX.data[cell] *= scale
                velocityY.data[cell] *= scale
            }
        }
    }

    /**
     * Fills [temperature] with the bare latitude profile over water, in degrees Celsius, leaving
     * land untouched.
     */
    private fun fillBaseTemperature(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        zonal: ZonalClimate,
        temperature: FloatField
    ) {
        val cellsAcross = config.width
        val cellsDown = config.height
        for (row in 0 until cellsDown) {
            val latitudeTemperatureC =
                zonal.waterC(ClimateStage.latitudeOf(row, cellsDown), Season.ANNUAL)
            for (column in 0 until cellsAcross) {
                if (!sea.isLand[row * cellsAcross + column]) {
                    temperature.data[row * cellsAcross + column] = latitudeTemperatureC
                }
            }
        }
    }

    /**
     * Carries temperature along the flow, in place in [temperature] and in degrees Celsius.
     *
     * Each pass moves every parcel a little way upstream and blends, so warm water reaches poleward
     * along one side of a gyre and cold water reaches equatorward along the other. This is the
     * entire reason the stage exists: it is what separates Bergen from Labrador. Land cells keep
     * whatever they came in with.
     */
    private fun advectTemperature(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        zonal: ZonalClimate,
        velocityX: FloatField,
        velocityY: FloatField,
        temperature: FloatField
    ) {
        val cellsAcross = config.width
        val cellsDown = config.height
        // The temperature each row relaxes back toward. One lookup per row rather than one per
        // cell per pass: two hundred passes over a four-million-cell grid is not the place for an
        // interpolation that only ever depends on the latitude.
        val relaxTowardC = FloatArray(cellsDown) { row ->
            zonal.waterC(ClimateStage.latitudeOf(row, cellsDown), Season.ANNUAL)
        }

        var current = temperature.data.copyOf()
        var next = current.copyOf()

        repeat(config.ocean.advectionPasses) {
            val read = current
            val write = next
            parallelChunks(0, cellsDown) { startRow, endRow ->
                for (row in startRow until endRow) {
                    for (column in 0 until cellsAcross) {
                        val cell = row * cellsAcross + column
                        // Land keeps its previous value rather than zero: a later blur or sample
                        // that touched a zeroed land cell would drag coastal water toward freezing
                        // and invent an anomaly along every shoreline.
                        if (sea.isLand[cell]) { write[cell] = read[cell]; continue }

                        val upstreamTemperatureC = sampleUpstream(
                            read, sea, cellsAcross, cellsDown,
                            column - velocityX.data[cell], row - velocityY.data[cell], read[cell]
                        )

                        // Relax back toward the latitude's own temperature, or a current would
                        // eventually carry tropical water all the way to the pole.
                        val carriedC = read[cell] +
                            (upstreamTemperatureC - read[cell]) * config.ocean.advectionRate
                        write[cell] = carriedC +
                            (relaxTowardC[row] - carriedC) * config.ocean.relaxationRate
                    }
                }
            }
            val swap = current; current = next; next = swap
        }
        current.copyInto(temperature.data)
    }

    /**
     * Fills [anomaly] with each water cell's departure, in degrees Celsius, from the mean
     * temperature of the open water on its own row. Land is left at zero.
     */
    private fun buildAnomaly(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        temperature: FloatField,
        anomaly: FloatField
    ) {
        val cellsAcross = config.width
        val cellsDown = config.height
        for (row in 0 until cellsDown) {
            var temperatureSum = 0.0
            var waterCells = 0
            for (column in 0 until cellsAcross) {
                if (!sea.isLand[row * cellsAcross + column]) {
                    temperatureSum += temperature.data[row * cellsAcross + column]
                    waterCells++
                }
            }
            if (waterCells == 0) continue
            val rowMeanC = (temperatureSum / waterCells).toFloat()
            for (column in 0 until cellsAcross) {
                val cell = row * cellsAcross + column
                if (!sea.isLand[cell]) anomaly.data[cell] = temperature.data[cell] - rowMeanC
            }
        }
    }

    /**
     * The temperature one advection step upwind of a cell, at the fractional position
     * ([upstreamColumn], [upstreamRow]) — wrapping east to west and clamping at the poles.
     * Returns [fallback] where the upstream point is land.
     */
    private fun sampleUpstream(
        data: FloatArray,
        sea: SeaLevelResult,
        cellsAcross: Int,
        cellsDown: Int,
        upstreamColumn: Float,
        upstreamRow: Float,
        fallback: Float
    ): Float {
        var column = upstreamColumn.toInt() % cellsAcross
        if (column < 0) column += cellsAcross
        val row = upstreamRow.toInt().coerceIn(0, cellsDown - 1)
        val cell = row * cellsAcross + column
        // Upstream of a coastal cell is often land, and no water arrives from there.
        return if (sea.isLand[cell]) fallback else data[cell]
    }

}
