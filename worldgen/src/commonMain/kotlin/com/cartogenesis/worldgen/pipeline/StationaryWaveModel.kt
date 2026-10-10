package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.math.ComplexBlockTridiagonal
import com.cartogenesis.worldgen.math.ComplexFft
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * What drives the stationary waves, on the basic state's grid: a heating at each interior interface
 * in kelvin a second (`Q / c_p`), `[interface][cell]`, and the ground's height in meters, `[cell]`,
 * whose slope under the surface wind lifts the air. Either may be absent.
 */
class WaveForcing(
    val heatingKelvinPerSecond: Array<DoubleArray>? = null,
    val surfaceHeightMeters: DoubleArray? = null
) {
    /** This forcing with every value times [factor]. */
    fun scaledBy(factor: Double) = WaveForcing(
        heatingKelvinPerSecond?.let { fields -> Array(fields.size) { index -> DoubleArray(fields[index].size) { fields[index][it] * factor } } },
        surfaceHeightMeters?.let { field -> DoubleArray(field.size) { field[it] * factor } }
    )

    companion object {
        /**
         * The width, as a Gaussian's standard deviation on the ground in meters, that forcing from the
         * map is spread to before the model reads it: a quarter of the storm track's deformation
         * radius (`PressureWind.rossbyRadiusKm`, 970 km on Earth), 243 km.
         *
         * The model is the large-scale atmosphere. Below about a quarter of the deformation radius a
         * response is the non-rotating flow over terrain that the ground's own lift treats, and the
         * map keeps that part (the A1a design, section 3.4). Forcing carried down as the coarse cells'
         * area mean holds every scale down to a cell, so its terrain slopes, and the pressure
         * gradients the model makes of them, grow as the grid is refined: the A1a prototype's mean
         * surface wind grew with its grid for that reason. Spread to a fixed length on the ground,
         * the forcing is the same on every grid that resolves the length, and the response converges
         * (docs/DESIGN_LEDGER.md, A1-3).
         */
        val GROUND_FORCING_WIDTH_METERS: Double = PressureWind.rossbyRadiusKm() * 1_000.0 / 4.0

        /**
         * Forcing from fields on the map's grid, as the model is to read it: [columnKelvinPerSecond]
         * (the column's mass-weighted mean heating, kelvin a second) spread over the interfaces by
         * [profile], and [surfaceHeightMeters]; each carried down by [remap]'s area mean, spread to
         * [GROUND_FORCING_WIDTH_METERS] (or the coast's filter width, [AtmosphereRemap.FILTER_WIDTH_IN_ROWS]
         * rows, on a grid too coarse for it) and filtered as [AtmosphereRemap.forcing] does. Either
         * field may be absent.
         */
        fun fromGround(
            remap: AtmosphereRemap,
            levels: AtmosphereLevels,
            columnKelvinPerSecond: FloatArray?,
            surfaceHeightMeters: FloatArray?,
            profile: (Double) -> Double
        ): WaveForcing {
            val widthInRows = groundWidthInRows(remap)
            val heating = columnKelvinPerSecond?.let { heatingFromColumn(levels, remap.forcing(it, widthInRows), profile) }
            val height = surfaceHeightMeters?.let { remap.forcing(it, widthInRows) }
            return WaveForcing(heating, height)
        }

        /**
         * The spread [fromGround] gives forcing on [remap]'s coarse grid, in coarse row spacings:
         * [GROUND_FORCING_WIDTH_METERS], or the coast's filter width on a grid too coarse for it.
         */
        fun groundWidthInRows(remap: AtmosphereRemap): Double =
            max(AtmosphereRemap.FILTER_WIDTH_IN_ROWS, GROUND_FORCING_WIDTH_METERS / remap.coarse.rowSpacingMeters)

        /**
         * A column heating [columnKelvinPerSecond] (the mass-weighted mean heating rate of the column,
         * `[cell]`) spread over [levels]' interior interfaces by the shape [profile], a function of
         * `sigma = p / p_s` (zero at both ends for a deep heating). The shape is normalized so the
         * column's mass-weighted mean is [columnKelvinPerSecond]: each interface stands for the mass
         * between the levels either side of it.
         */
        fun heatingFromColumn(levels: AtmosphereLevels, columnKelvinPerSecond: DoubleArray, profile: (Double) -> Double): Array<DoubleArray> {
            val shape = DoubleArray(levels.interiorCount) { profile(levels.interiorPressurePa[it] / levels.surfacePressurePa) }
            var columnMean = 0.0
            for (interior in 0 until levels.interiorCount) {
                val massShare = (levels.levelPressurePa[interior + 1] - levels.levelPressurePa[interior]) / levels.surfacePressurePa
                columnMean += shape[interior] * massShare
            }
            require(columnMean > 0.0) { "a heating profile with no heat in it" }
            return Array(levels.interiorCount) { interior ->
                val weight = shape[interior] / columnMean
                DoubleArray(columnKelvinPerSecond.size) { columnKelvinPerSecond[it] * weight }
            }
        }
    }
}

/**
 * The stationary waves' fields on the grid, departures from the basic state.
 *
 * - [eastward] and [geopotential], `[level][cell]` at the row centers: meters a second and square
 *   meters a second squared.
 * - [northwardAtFaces], `[level][face * columns + column]`, on the `rows + 1` faces, the poles' faces
 *   carrying the wave-1 part a regular vector has there.
 * - [verticalMotion] and [temperature], `[interface][cell]` at the interior interfaces: pascals a
 *   second (positive down) and kelvin.
 * - [surfaceVerticalMotion], `[cell]`, the ground's `omega` from the terrain, pascals a second.
 * - [surfacePressurePa], `[cell]`: `rho_s` times the geopotential carried hydrostatically from the
 *   lowest level to the ground at the lowest interface's temperature. With the zonal mean solved its
 *   area mean is zero, so the waves move no mass.
 */
class WaveResponse(
    val grid: SphericalGrid,
    val levels: AtmosphereLevels,
    val eastward: Array<DoubleArray>,
    val northwardAtFaces: Array<DoubleArray>,
    val geopotential: Array<DoubleArray>,
    val verticalMotion: Array<DoubleArray>,
    val temperature: Array<DoubleArray>,
    val surfaceVerticalMotion: DoubleArray,
    val surfacePressurePa: DoubleArray
) {
    /** [level]'s northward wind at the row centers, the mean of each cell's two faces. */
    fun northwardAtCenters(level: Int): DoubleArray =
        SphericalOperators(grid).northAtCenters(SphericalOperators.Vector(eastward[level], northwardAtFaces[level]))
}

/**
 * A steady, linear, primitive-equation atmosphere about a zonal-mean [basicState]: the stationary
 * waves a heating and the terrain make (Hoskins and Karoly 1981; the dry linear baroclinic model of
 * Watanabe and Kimoto 2000, reduced). The boundary layer reads it ([BoundaryLayer]).
 *
 * **The equations**, linearized about the zonal wind `U_k(phi)` at each level, with `m` the zonal
 * wavenumber, `a` the radius, `f` the Coriolis parameter and `zeta` the basic state's vorticity:
 *
 * - zonal momentum: `i m U u / (a cos) + R u - (f + zeta) v + i m Phi / (a cos) + omega dU/dp - A lap(u) = 0`;
 * - meridional momentum: `i m U v / (a cos) + R v + (f + 2 U tan / a) u + (1/a) dPhi/dphi - A lap(v) = 0`;
 * - continuity: `div(u, v) + d(omega)/dp = 0`, `omega = 0` at the top and the terrain's
 *   `-rho_s g U_s (1 / (a cos)) dh/dlambda` at the ground;
 * - thermodynamics at each interior interface: `i m U T / (a cos) + G T + v dT/(a dphi) - S omega - A lap(T) = Q / c_p`,
 *   `T` the geopotential's hydrostatic fall across the interface.
 *
 * `R` and `G` are [damping]'s momentum and thermal matrices on the vertical modes; `lap` of the wind is
 * the vector Laplacian `grad(div) + k x grad(curl)` ([WaveDamping]). The vertical advection of the
 * basic state's momentum by the waves, `omega dU/dp`, is kept, half from each interface either side
 * of a level; the terrain's lift enters it at the ground. The basic state's temperature gradient is
 * the one its wind's shear implies ([ZonalBasicState]).
 *
 * **The grid** is [SphericalGrid]'s, an Arakawa C-grid in latitude: `u`, `Phi` and the interfaces'
 * `omega` and `T` at the row centers, `v` on the faces between rows, Fourier in longitude. Each zonal
 * wave is one block-tridiagonal system down the rows ([ComplexBlockTridiagonal]), a row's block
 * holding `u`, `v` (on its southern face), `Phi` at every level and `omega` at every interior
 * interface, `4K - 1` unknowns. The divergence, the curl and the Laplacian are [SphericalOperators']
 * own stencils, so the model's continuity is the operators' divergence exactly.
 *
 * **The poles.** A scalar's zonal waves 1 and up vanish at a pole by the flux form (no face there).
 * A vector's northward component on a polar face is a regular vector's: the mean of the faces either
 * side of the pole along the continued meridian, `v_pole = (1 - (-1)^m) v_next / 2`, which is the
 * wave-1 part a vector crossing the pole has and zero for every other wave (Swarztrauber's condition;
 * docs/DESIGN_LEDGER.md, A1-2). It is not held at zero.
 *
 * **The zonal mean** (wave 0) is solved only when [solveZonalMean] is set; the geopotential is then
 * fixed up to a constant (the rigid lid holds no mass budget), which is settled by replacing one
 * continuity equation, redundant with the rest, by a pin (the lowest level's geopotential on
 * [zonalMeanPinRow]) and then removing the area mean of the surface pressure. The answer does not
 * depend on where the pin is (`StationaryWaveModelTest`).
 *
 * **What it solves**: zonal waves 0 or 1 to [highestZonalWave], by default a third of the columns, the
 * transform's two-thirds rule; anything finer in the forcing is not seen.
 *
 * **What it has been shown to do** (docs/DESIGN_LEDGER.md, A1-3): Gill's (1980) Kelvin and planetary
 * waves on a resting state, Hoskins and Karoly's (1981) great-circle trains and their stationary
 * wavelength, Rodwell and Hoskins' (2001) descent west of a monsoon; second-order convergence on the
 * atmosphere's grid; and, against 24 levels, a surface pressure that four levels miss by 50 to 65%
 * (relative RMS) and sixteen by 3 to 6% (docs/TODO.md).
 */
class StationaryWaveModel(
    val basicState: ZonalBasicState,
    val damping: WaveDamping,
    val solveZonalMean: Boolean = false,
    val highestZonalWave: Int = basicState.grid.columns / 3,
    val zonalMeanPinRow: Int = basicState.grid.rows - 1,
    /**
     * Holds the northward wind on the polar faces at zero, as the A1a prototype did, in place of a
     * regular vector's wave-1 part: only for the test that shows what that costs.
     */
    internal val polarWindHeldAtZero: Boolean = false
) {
    val grid: SphericalGrid = basicState.grid
    val levels: AtmosphereLevels = basicState.levels

    private val levelCount = levels.levelCount
    private val interiorCount = levels.interiorCount

    /** Unknowns, and equations, in each row's block: `u`, `v` and `Phi` at each level and `omega` at each interior interface. */
    val blockSize: Int = 4 * levelCount - 1

    private val momentumDamping = damping.momentumMatrix(basicState.modes)
    private val thermalDamping = damping.thermalMatrix(basicState.modes)
    private val firstWave = if (solveZonalMean) 0 else 1

    init {
        require(highestZonalWave in firstWave until grid.columns / 2) { "zonal waves up to $highestZonalWave on ${grid.columns} columns" }
        require(zonalMeanPinRow in 0 until grid.rows) { "the zonal mean's pin on row $zonalMeanPinRow of ${grid.rows}" }
    }

    /** The waves this model solves. */
    val waves: IntRange get() = firstWave..highestZonalWave

    private fun eastSlot(level: Int) = level
    private fun northSlot(level: Int) = levelCount + level
    private fun geopotentialSlot(level: Int) = 2 * levelCount + level
    private fun verticalMotionSlot(interior: Int) = 3 * levelCount + interior

    /**
     * Every wave's system factored, ready for any number of forcings at a back-substitution each.
     * The factors take `(waves) * rows * 3 * blockSize^2` complex numbers: about 160 MB on Earth's
     * planet at four levels, so a caller that solves once should call [solve] instead.
     */
    inner class Factored internal constructor(private val systems: Array<ComplexBlockTridiagonal>, private val rowScales: Array<DoubleArray>) {
        /** The response to [forcing], by back-substitution in every wave. */
        fun solve(forcing: WaveForcing): WaveResponse {
            val spectra = ForcingSpectra(forcing)
            val solutionReal = Array(waves.count()) { DoubleArray(grid.rows * blockSize) }
            val solutionImaginary = Array(waves.count()) { DoubleArray(grid.rows * blockSize) }
            parallelChunks(0, waves.count()) { start, end ->
                for (index in start until end) {
                    rightHandSide(waves.first + index, spectra, rowScales[index], solutionReal[index], solutionImaginary[index])
                    systems[index].solve(solutionReal[index], solutionImaginary[index])
                }
            }
            return response(solutionReal, solutionImaginary, spectra)
        }
    }

    /** Factors every wave's system once (see [Factored]). */
    fun factorize(): Factored {
        val count = waves.count()
        val systems = arrayOfNulls<ComplexBlockTridiagonal>(count)
        val scales = arrayOfNulls<DoubleArray>(count)
        // In chunks of waves, one per worker: `parallelFor` keeps a hundred items on one thread.
        parallelChunks(0, count) { start, end ->
            for (index in start until end) {
                val system = ComplexBlockTridiagonal(grid.rows, blockSize)
                scales[index] = assemble(waves.first + index, system)
                system.factorize()
                systems[index] = system
            }
        }
        @Suppress("UNCHECKED_CAST")
        return Factored(systems as Array<ComplexBlockTridiagonal>, scales as Array<DoubleArray>)
    }

    /** The response to [forcing], each wave assembled, factored and solved in turn and then let go. */
    fun solve(forcing: WaveForcing): WaveResponse = solveEach(listOf(forcing)).single()

    /**
     * The responses to every one of [forcings], each wave assembled and factored once and
     * back-substituted for each forcing in turn, then let go: one factoring serves them all, and
     * no more than one wave's factors per worker are held at once (the whole set is about 700 MB
     * on Earth's planet at eight levels).
     */
    fun solveEach(forcings: List<WaveForcing>): List<WaveResponse> {
        val spectra = forcings.map { ForcingSpectra(it) }
        val count = waves.count()
        val solutionReal = Array(forcings.size) { Array(count) { DoubleArray(grid.rows * blockSize) } }
        val solutionImaginary = Array(forcings.size) { Array(count) { DoubleArray(grid.rows * blockSize) } }
        parallelChunks(0, count) { start, end ->
            // One system per worker, cleared for each of its waves.
            val system = ComplexBlockTridiagonal(grid.rows, blockSize)
            for (index in start until end) {
                val wave = waves.first + index
                system.clear()
                val scales = assemble(wave, system)
                system.factorize()
                for (which in forcings.indices) {
                    rightHandSide(wave, spectra[which], scales, solutionReal[which][index], solutionImaginary[which][index])
                    system.solve(solutionReal[which][index], solutionImaginary[which][index])
                }
            }
        }
        return forcings.indices.map { response(solutionReal[it], solutionImaginary[it], spectra[it]) }
    }

    /** The forcing's zonal spectra row by row: raw transform coefficients, `sum_c x_c exp(-2 pi i m c / N)`. */
    private inner class ForcingSpectra(forcing: WaveForcing) {
        val heatingReal: Array<DoubleArray>
        val heatingImaginary: Array<DoubleArray>
        val heightReal: DoubleArray
        val heightImaginary: DoubleArray
        val hasHeight = forcing.surfaceHeightMeters != null

        init {
            val heating = forcing.heatingKelvinPerSecond
            require(heating == null || (heating.size == interiorCount && heating.all { it.size == grid.cellCount })) {
                "one heating field of ${grid.cellCount} cells per interior interface"
            }
            heatingReal = Array(interiorCount) { DoubleArray(grid.cellCount) }
            heatingImaginary = Array(interiorCount) { DoubleArray(grid.cellCount) }
            heating?.forEachIndexed { interior, field -> transformRows(field, heatingReal[interior], heatingImaginary[interior]) }
            heightReal = DoubleArray(grid.cellCount)
            heightImaginary = DoubleArray(grid.cellCount)
            forcing.surfaceHeightMeters?.let { field ->
                require(field.size == grid.cellCount) { "a height field of ${field.size} cells" }
                transformRows(field, heightReal, heightImaginary)
            }
        }

        /**
         * The terrain's `omega` at the ground for [wave] on [row]: `-rho_s g U_s (i m / (a cos)) h`, as a
         * real and imaginary pair.
         */
        fun surfaceVerticalMotion(wave: Int, row: Int): Pair<Double, Double> {
            if (!hasHeight || wave == 0) return 0.0 to 0.0
            val factor = -basicState.surfaceDensity * DryAir.GRAVITY_MPS2 * basicState.surfaceZonalWind[row] * wave /
                (grid.radiusMeters * grid.cosLatitude[row])
            val slot = row * grid.columns + wave
            return -factor * heightImaginary[slot] to factor * heightReal[slot]
        }
    }

    private fun transformRows(field: DoubleArray, outReal: DoubleArray, outImaginary: DoubleArray) {
        val columns = grid.columns
        parallelChunks(0, grid.rows) { startRow, endRow ->
            val scratch = ComplexFft.Scratch(columns)
            val real = DoubleArray(columns)
            val imaginary = DoubleArray(columns)
            for (row in startRow until endRow) {
                for (column in 0 until columns) {
                    real[column] = field[row * columns + column]
                    imaginary[column] = 0.0
                }
                grid.rowTransform.forward(real, imaginary, scratch)
                real.copyInto(outReal, row * columns)
                imaginary.copyInto(outImaginary, row * columns)
            }
        }
    }

    /**
     * Writes [wave]'s equations into [system] and returns each equation's scale: every equation is
     * divided by its largest coefficient, so the pivoting compares like with like across equations
     * whose natural sizes differ by orders of magnitude.
     */
    private fun assemble(wave: Int, system: ComplexBlockTridiagonal): DoubleArray {
        val writer = EquationWriter(wave, system)
        for (row in 0 until grid.rows) {
            writer.row = row
            for (level in 0 until levelCount) {
                writer.zonalMomentum(level)
                writer.meridionalMomentum(level)
                writer.continuity(level)
            }
            for (interior in 0 until interiorCount) writer.thermodynamics(interior)
        }
        return equilibrate(system)
    }

    private fun equilibrate(system: ComplexBlockTridiagonal): DoubleArray {
        val size = blockSize
        val scales = DoubleArray(grid.rows * size)
        for (row in 0 until grid.rows) {
            for (equation in 0 until size) {
                var largest = 0.0
                for (column in 0 until size) {
                    val at = system.at(row, equation, column)
                    largest = max(largest, abs(system.lowerReal[at]) + abs(system.lowerImaginary[at]))
                    largest = max(largest, abs(system.diagonalReal[at]) + abs(system.diagonalImaginary[at]))
                    largest = max(largest, abs(system.upperReal[at]) + abs(system.upperImaginary[at]))
                }
                check(largest > 0.0) { "equation $equation of row $row has no coefficient" }
                val scale = 1.0 / largest
                scales[row * size + equation] = scale
                for (column in 0 until size) {
                    val at = system.at(row, equation, column)
                    system.lowerReal[at] *= scale; system.lowerImaginary[at] *= scale
                    system.diagonalReal[at] *= scale; system.diagonalImaginary[at] *= scale
                    system.upperReal[at] *= scale; system.upperImaginary[at] *= scale
                }
            }
        }
        return scales
    }

    /** [wave]'s right-hand side from [spectra], each equation times its [scales], into [real] and [imaginary]. */
    private fun rightHandSide(wave: Int, spectra: ForcingSpectra, scales: DoubleArray, real: DoubleArray, imaginary: DoubleArray) {
        real.fill(0.0)
        imaginary.fill(0.0)
        val bottom = levelCount - 1
        for (row in 0 until grid.rows) {
            val base = row * blockSize
            for (interior in 0 until interiorCount) {
                val slot = row * grid.columns + wave
                real[base + verticalMotionSlot(interior)] = spectra.heatingReal[interior][slot]
                imaginary[base + verticalMotionSlot(interior)] = spectra.heatingImaginary[interior][slot]
            }
            val (groundReal, groundImaginary) = spectra.surfaceVerticalMotion(wave, row)
            if (groundReal != 0.0 || groundImaginary != 0.0) {
                // The ground's omega closes the lowest layer's continuity and carries the basic state's
                // momentum between the lowest level and the ground.
                if (!isPinned(wave, row, bottom)) {
                    real[base + geopotentialSlot(bottom)] -= groundReal / levels.thicknessPa[bottom]
                    imaginary[base + geopotentialSlot(bottom)] -= groundImaginary / levels.thicknessPa[bottom]
                }
                val shear = 0.5 * (basicState.surfaceZonalWind[row] - basicState.zonalWindAtLevels[bottom][row]) /
                    (levels.surfacePressurePa - levels.levelPressurePa[bottom])
                real[base + eastSlot(bottom)] -= shear * groundReal
                imaginary[base + eastSlot(bottom)] -= shear * groundImaginary
            }
            for (equation in 0 until blockSize) {
                real[base + equation] *= scales[base + equation]
                imaginary[base + equation] *= scales[base + equation]
            }
        }
    }

    /** Whether [level]'s continuity on [row] is replaced by the zonal mean's pin. */
    private fun isPinned(wave: Int, row: Int, level: Int) = wave == 0 && row == zonalMeanPinRow && level == levelCount - 1

    /**
     * Writes one row's equations for one wave into the block system: each term is a coefficient on an
     * unknown of this row or the rows either side.
     */
    private inner class EquationWriter(val wave: Int, val system: ComplexBlockTridiagonal) {
        var row = 0
        private val m = wave.toDouble()
        private val radius = grid.radiusMeters
        private val spacing = grid.rowSpacingRadians
        private val mixing = damping.mixingSquareMetersPerSecond
        private val oddWave = wave % 2 == 1
        private val rows = grid.rows

        private fun add(equation: Int, targetRow: Int, slot: Int, real: Double, imaginary: Double) {
            if (real == 0.0 && imaginary == 0.0) return
            val at = system.at(row, equation, slot)
            when (targetRow - row) {
                -1 -> { system.lowerReal[at] += real; system.lowerImaginary[at] += imaginary }
                0 -> { system.diagonalReal[at] += real; system.diagonalImaginary[at] += imaginary }
                1 -> { system.upperReal[at] += real; system.upperImaginary[at] += imaginary }
                else -> error("row $row reaches row $targetRow")
            }
        }

        /** A term on `v` at [face] and [level]; the polar faces carry a regular vector's wave-1 part. */
        private fun addNorthAtFace(equation: Int, face: Int, level: Int, real: Double, imaginary: Double) {
            when (face) {
                0 -> if (oddWave && !polarWindHeldAtZero) add(equation, 0, northSlot(level), real, imaginary)
                rows -> if (oddWave && !polarWindHeldAtZero) add(equation, rows - 2, northSlot(level), real, imaginary)
                else -> add(equation, face - 1, northSlot(level), real, imaginary)
            }
        }

        /** `(real + i imaginary)` times the divergence on [divergenceRow] at [level], `SphericalOperators.divergence`'s stencil. */
        private fun addDivergence(equation: Int, divergenceRow: Int, level: Int, real: Double, imaginary: Double) {
            val span = grid.sinSpanOfRow[divergenceRow]
            val zonal = m * spacing / (radius * span)
            add(equation, divergenceRow, eastSlot(level), -imaginary * zonal, real * zonal)
            val north = grid.cosFace[divergenceRow] / (radius * span)
            val south = grid.cosFace[divergenceRow + 1] / (radius * span)
            addNorthAtFace(equation, divergenceRow, level, real * north, imaginary * north)
            addNorthAtFace(equation, divergenceRow + 1, level, -real * south, -imaginary * south)
        }

        /** `(real + i imaginary)` times the vorticity on [face] at [level], `SphericalOperators.curl`'s stencil. */
        private fun addVorticity(equation: Int, face: Int, level: Int, real: Double, imaginary: Double) {
            if (face == 0 || face == rows) {
                // The polar cap's circulation: one value, so only the zonal mean has any.
                if (wave != 0) return
                val polarRow = if (face == 0) 0 else rows - 1
                val sign = if (face == 0) 1.0 else -1.0
                val capSpan = 1.0 - abs(sin(grid.latitudeRadians[polarRow]))
                val coefficient = sign * grid.cosLatitude[polarRow] / (radius * capSpan)
                add(equation, polarRow, eastSlot(level), real * coefficient, imaginary * coefficient)
                return
            }
            val bandSpan = sin(grid.latitudeRadians[face - 1]) - sin(grid.latitudeRadians[face])
            val zonal = m * spacing / (radius * bandSpan)
            addNorthAtFace(equation, face, level, -imaginary * zonal, real * zonal)
            val south = grid.cosLatitude[face] / (radius * bandSpan)
            val north = grid.cosLatitude[face - 1] / (radius * bandSpan)
            add(equation, face, eastSlot(level), real * south, imaginary * south)
            add(equation, face - 1, eastSlot(level), -real * north, -imaginary * north)
        }

        /** `(real + i imaginary)` times interior [interior]'s temperature on [temperatureRow]. */
        private fun addTemperature(equation: Int, temperatureRow: Int, interior: Int, real: Double, imaginary: Double) {
            val toKelvin = 1.0 / (DryAir.GAS_CONSTANT_J_PER_KG_K * levels.logPressureRatio[interior])
            add(equation, temperatureRow, geopotentialSlot(interior), real * toKelvin, imaginary * toKelvin)
            add(equation, temperatureRow, geopotentialSlot(interior + 1), -real * toKelvin, -imaginary * toKelvin)
        }

        fun zonalMomentum(level: Int) {
            val equation = eastSlot(level)
            val cosine = grid.cosLatitude[row]
            val wind = basicState.zonalWindAtLevels[level][row]
            for (other in 0 until levelCount) {
                val advection = if (other == level) m * wind / (radius * cosine) else 0.0
                add(equation, row, eastSlot(other), momentumDamping[level][other], advection)
            }
            val coriolis = -0.5 * (basicState.coriolisAtRows[row] + basicState.relativeVorticity[level][row])
            addNorthAtFace(equation, row, level, coriolis, 0.0)
            addNorthAtFace(equation, row + 1, level, coriolis, 0.0)
            add(equation, row, geopotentialSlot(level), 0.0, m / (radius * cosine))
            // omega dU/dp, half from each interface either side of the level.
            if (level >= 1) {
                val shear = (wind - basicState.zonalWindAtLevels[level - 1][row]) / (levels.levelPressurePa[level] - levels.levelPressurePa[level - 1])
                add(equation, row, verticalMotionSlot(level - 1), 0.5 * shear, 0.0)
            }
            if (level <= levelCount - 2) {
                val shear = (basicState.zonalWindAtLevels[level + 1][row] - wind) / (levels.levelPressurePa[level + 1] - levels.levelPressurePa[level])
                add(equation, row, verticalMotionSlot(level), 0.5 * shear, 0.0)
            }
            if (mixing > 0.0) {
                // -A (grad(div) + k x grad(curl)), eastward part.
                addDivergence(equation, row, level, 0.0, -mixing * m / (radius * cosine))
                addVorticity(equation, row, level, mixing / (radius * spacing), 0.0)
                addVorticity(equation, row + 1, level, -mixing / (radius * spacing), 0.0)
            }
        }

        fun meridionalMomentum(level: Int) {
            val equation = northSlot(level)
            if (row == rows - 1) {
                // The south pole's face is not an unknown; this slot is held at zero.
                add(equation, row, northSlot(level), 1.0, 0.0)
                return
            }
            val face = row + 1
            val faceCosine = grid.cosFace[face]
            val wind = basicState.zonalWindAtFaces[level][face]
            for (other in 0 until levelCount) {
                val advection = if (other == level) m * wind / (radius * faceCosine) else 0.0
                add(equation, row, northSlot(other), momentumDamping[level][other], advection)
            }
            val coriolis = 0.5 * (basicState.coriolisAtFaces[face] + 2.0 * wind * grid.sinFace[face] / (faceCosine * radius))
            add(equation, row, eastSlot(level), coriolis, 0.0)
            add(equation, row + 1, eastSlot(level), coriolis, 0.0)
            add(equation, row, geopotentialSlot(level), 1.0 / (radius * spacing), 0.0)
            add(equation, row + 1, geopotentialSlot(level), -1.0 / (radius * spacing), 0.0)
            if (mixing > 0.0) {
                // -A (grad(div) + k x grad(curl)), northward part.
                addDivergence(equation, row, level, -mixing / (radius * spacing), 0.0)
                addDivergence(equation, row + 1, level, mixing / (radius * spacing), 0.0)
                addVorticity(equation, face, level, 0.0, -mixing * m / (radius * faceCosine))
            }
        }

        fun continuity(level: Int) {
            val equation = geopotentialSlot(level)
            if (isPinned(wave, row, level)) {
                add(equation, row, geopotentialSlot(level), 1.0, 0.0)
                return
            }
            addDivergence(equation, row, level, 1.0, 0.0)
            val thickness = levels.thicknessPa[level]
            if (level <= levelCount - 2) add(equation, row, verticalMotionSlot(level), 1.0 / thickness, 0.0)
            if (level >= 1) add(equation, row, verticalMotionSlot(level - 1), -1.0 / thickness, 0.0)
        }

        fun thermodynamics(interior: Int) {
            val equation = verticalMotionSlot(interior)
            val cosine = grid.cosLatitude[row]
            val wind = basicState.zonalWindAtInteriors[interior][row]
            for (other in 0 until interiorCount) {
                val advection = if (other == interior) m * wind / (radius * cosine) else 0.0
                addTemperature(equation, row, other, thermalDamping[interior][other], advection)
            }
            // v dT/(a dphi): the interface's v is the mean of the levels either side, at the center the
            // mean of the two faces.
            val gradient = 0.25 * basicState.temperatureGradientPerRadian[interior][row] / radius
            for (level in interior..interior + 1) {
                addNorthAtFace(equation, row, level, gradient, 0.0)
                addNorthAtFace(equation, row + 1, level, gradient, 0.0)
            }
            add(equation, row, verticalMotionSlot(interior), -basicState.stabilityKelvinPerPascal[interior], 0.0)
            if (mixing > 0.0) {
                val span = grid.sinSpanOfRow[row]
                val zonal = -m * m * spacing / (radius * radius * span * cosine)
                val toNorth = grid.cosFace[row] / (radius * radius * spacing * span)
                val toSouth = grid.cosFace[row + 1] / (radius * radius * spacing * span)
                addTemperature(equation, row, interior, -mixing * (zonal - toNorth - toSouth), 0.0)
                if (row > 0) addTemperature(equation, row - 1, interior, -mixing * toNorth, 0.0)
                if (row < rows - 1) addTemperature(equation, row + 1, interior, -mixing * toSouth, 0.0)
            }
        }
    }

    /** The solved waves back on the grid. */
    private fun response(solutionReal: Array<DoubleArray>, solutionImaginary: Array<DoubleArray>, spectra: ForcingSpectra): WaveResponse {
        val columns = grid.columns
        val rows = grid.rows
        fun field(slot: Int): DoubleArray {
            val result = DoubleArray(grid.cellCount)
            parallelChunks(0, rows) { startRow, endRow ->
                val scratch = ComplexFft.Scratch(columns)
                val real = DoubleArray(columns)
                val imaginary = DoubleArray(columns)
                for (row in startRow until endRow) {
                    real.fill(0.0)
                    imaginary.fill(0.0)
                    for ((index, wave) in waves.withIndex()) {
                        val valueReal = solutionReal[index][row * blockSize + slot]
                        val valueImaginary = solutionImaginary[index][row * blockSize + slot]
                        real[wave] = valueReal
                        imaginary[wave] = valueImaginary
                        if (wave > 0) {
                            real[columns - wave] = valueReal
                            imaginary[columns - wave] = -valueImaginary
                        } else {
                            imaginary[0] = 0.0
                        }
                    }
                    grid.rowTransform.inverse(real, imaginary, scratch)
                    for (column in 0 until columns) result[row * columns + column] = real[column] / columns
                }
            }
            return result
        }
        val eastward = Array(levelCount) { field(eastSlot(it)) }
        val geopotential = Array(levelCount) { field(geopotentialSlot(it)) }
        val verticalMotion = Array(interiorCount) { field(verticalMotionSlot(it)) }
        val northwardAtFaces = Array(levelCount) { level ->
            val rowField = field(northSlot(level))
            val faces = DoubleArray((rows + 1) * columns)
            for (face in 1 until rows) rowField.copyInto(faces, face * columns, (face - 1) * columns, face * columns)
            // The polar faces: the odd waves of the next face in, a regular vector's part there.
            if (!polarWindHeldAtZero) {
                faces.polarFace(0, rowField, 0)
                faces.polarFace(rows, rowField, rows - 2)
            }
            faces
        }
        val groundOmega = run {
            val result = DoubleArray(grid.cellCount)
            if (spectra.hasHeight) {
                val real = DoubleArray(columns)
                val imaginary = DoubleArray(columns)
                for (row in 0 until rows) {
                    real.fill(0.0)
                    imaginary.fill(0.0)
                    for (wave in waves) {
                        if (wave == 0) continue
                        val (valueReal, valueImaginary) = spectra.surfaceVerticalMotion(wave, row)
                        real[wave] = valueReal; imaginary[wave] = valueImaginary
                        real[columns - wave] = valueReal; imaginary[columns - wave] = -valueImaginary
                    }
                    grid.rowTransform.inverse(real, imaginary)
                    for (column in 0 until columns) result[row * columns + column] = real[column] / columns
                }
            }
            result
        }
        if (solveZonalMean) {
            // The rigid lid fixes the geopotential only up to a constant: take the one under which the
            // waves move no mass, the surface pressure's area mean zero.
            var weighted = 0.0
            var area = 0.0
            for (cell in 0 until grid.cellCount) {
                val cellArea = grid.cellAreaSquareMeters[cell / columns]
                weighted += groundGeopotential(geopotential, cell) * cellArea
                area += cellArea
            }
            val offset = weighted / area
            for (level in 0 until levelCount) for (cell in 0 until grid.cellCount) geopotential[level][cell] -= offset
        }
        val temperature = Array(interiorCount) { interior ->
            val factor = 1.0 / (DryAir.GAS_CONSTANT_J_PER_KG_K * levels.logPressureRatio[interior])
            DoubleArray(grid.cellCount) { (geopotential[interior][it] - geopotential[interior + 1][it]) * factor }
        }
        val surfacePressure = DoubleArray(grid.cellCount) { basicState.surfaceDensity * groundGeopotential(geopotential, it) }
        return WaveResponse(grid, levels, eastward, northwardAtFaces, geopotential, verticalMotion, temperature, groundOmega, surfacePressure)
    }

    /** The geopotential at the ground in [cell]: the lowest level's, carried down at the lowest interface's temperature. */
    private fun groundGeopotential(geopotential: Array<DoubleArray>, cell: Int): Double {
        val bottom = levelCount - 1
        val lowestTemperature = (geopotential[bottom - 1][cell] - geopotential[bottom][cell]) /
            (DryAir.GAS_CONSTANT_J_PER_KG_K * levels.logPressureRatio[bottom - 1])
        return geopotential[bottom][cell] - DryAir.GAS_CONSTANT_J_PER_KG_K * lowestTemperature * levels.logPressureToGround
    }

    /**
     * Fills polar face [face] of this face field from the row field's face-row [sourceRow]: its odd
     * zonal waves, the wave-1 part (and the higher odd waves' vanishing remainder) a regular vector
     * carries across the pole. `v(lambda) - v(lambda + pi)` over two keeps exactly the odd waves.
     */
    private fun DoubleArray.polarFace(face: Int, rowField: DoubleArray, sourceRow: Int) {
        val columns = grid.columns
        val half = columns / 2
        for (column in 0 until columns) {
            val here = rowField[sourceRow * columns + column]
            val opposite = rowField[sourceRow * columns + (column + half) % columns]
            this[face * columns + column] = 0.5 * (here - opposite)
        }
    }
}
