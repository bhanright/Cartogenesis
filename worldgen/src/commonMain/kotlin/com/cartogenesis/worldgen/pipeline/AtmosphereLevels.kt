package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.math.SymmetricEigen
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The atmosphere's vertical: layers of air between pressure interfaces, from the model's top (zero
 * pressure) down to the ground.
 *
 * Each layer carries one **level** at its mass midpoint, where the wind and the geopotential live.
 * The **interior interfaces** between two levels carry the temperature, which is the geopotential's
 * fall across them by the hydrostatic law, `T = (Phi_above - Phi_below) / (R ln(p_below / p_above))`,
 * and the vertical motion `omega` in pascals a second. The top carries no vertical motion; the
 * ground carries the motion the terrain forces. With equal layers this is the stationary-wave
 * models' Lorenz grid in pressure (Hoskins and Karoly 1981 used five equal layers in sigma).
 *
 * [interfacePressuresPa] runs from 0 at the top to the surface's pressure, strictly rising.
 */
class AtmosphereLevels(val interfacePressuresPa: DoubleArray) {

    init {
        require(interfacePressuresPa.size >= 3) { "an atmosphere needs two levels at least, got ${interfacePressuresPa.size - 1}" }
        require(interfacePressuresPa[0] == 0.0) { "the top interface is the model's top, at zero pressure" }
        for (index in 1 until interfacePressuresPa.size) {
            require(interfacePressuresPa[index] > interfacePressuresPa[index - 1]) { "interfaces must rise in pressure downward" }
        }
    }

    /** Levels, top first. */
    val levelCount: Int = interfacePressuresPa.size - 1

    /** Interfaces between two levels, where temperature and vertical motion live. */
    val interiorCount: Int = levelCount - 1

    /** The ground's pressure, the bottom interface, in pascals. */
    val surfacePressurePa: Double = interfacePressuresPa.last()

    /** Each layer's mass per area times `g`, its pressure thickness in pascals. */
    val thicknessPa = DoubleArray(levelCount) { interfacePressuresPa[it + 1] - interfacePressuresPa[it] }

    /** Each level's pressure: the layer's mass midpoint, in pascals. */
    val levelPressurePa = DoubleArray(levelCount) { 0.5 * (interfacePressuresPa[it] + interfacePressuresPa[it + 1]) }

    /** Interior interface `i`'s pressure, between level `i` above and `i + 1` below, in pascals. */
    val interiorPressurePa = DoubleArray(interiorCount) { interfacePressuresPa[it + 1] }

    /** `ln(p_{i+1} / p_i)` between the levels either side of interior interface `i`: the hydrostatic law's factor. */
    val logPressureRatio = DoubleArray(interiorCount) { ln(levelPressurePa[it + 1] / levelPressurePa[it]) }

    /** `ln(p_s / p_K)`, from the lowest level down to the ground, for carrying the geopotential to the surface. */
    val logPressureToGround: Double = ln(surfacePressurePa / levelPressurePa[levelCount - 1])

    companion object {
        /**
         * The reference ground pressure the levels hang from, in pascals: 1,000 hectopascals, the
         * surface pressure of Jablonowski and Williamson's (2006) balanced state and the pressure
         * coordinate's conventional reference. The model is linear and flat-bottomed, so this is
         * where `sigma = 1`; the response's dependence on it is measured, not assumed away
         * (`StationaryWaveModelTest`).
         */
        const val REFERENCE_SURFACE_PRESSURE_PA = 100_000.0

        /**
         * The top of the boundary layer in the two-level variant, as a share of the ground's
         * pressure: 900 hectopascals, the depth over which Held and Suarez (1994) apply their surface
         * friction's upper part and Rodwell and Hoskins (2001) their two drag levels (sigma 0.967 and
         * 0.887).
         */
        const val BOUNDARY_LAYER_TOP_SHARE = 0.9

        /** [count] layers of equal mass down to [surfacePressurePa]. */
        fun equalMass(count: Int, surfacePressurePa: Double = REFERENCE_SURFACE_PRESSURE_PA): AtmosphereLevels =
            AtmosphereLevels(DoubleArray(count + 1) { it * surfacePressurePa / count })

        /**
         * Two equal free-tropospheric layers above a boundary layer from [BOUNDARY_LAYER_TOP_SHARE] of
         * the ground's pressure down: the review's smallest model that tells deep heating from shallow,
         * three levels in all.
         */
        fun twoLevelsAndBoundaryLayer(surfacePressurePa: Double = REFERENCE_SURFACE_PRESSURE_PA): AtmosphereLevels {
            val boundaryTop = BOUNDARY_LAYER_TOP_SHARE * surfacePressurePa
            return AtmosphereLevels(doubleArrayOf(0.0, 0.5 * boundaryTop, boundaryTop, surfacePressurePa))
        }
    }
}

/** The dry air the atmosphere is made of, and the gravity it sits in. */
object DryAir {
    /** The gas constant of dry air, joules per kilogram per kelvin, as `ColumnWater` takes it. */
    const val GAS_CONSTANT_J_PER_KG_K = 287.04

    /** Dry air's heat capacity at constant pressure, joules per kilogram per kelvin. */
    const val HEAT_CAPACITY_J_PER_KG_K = 1004.0

    /** `R / c_p`, the exponent of potential temperature. */
    const val KAPPA = GAS_CONSTANT_J_PER_KG_K / HEAT_CAPACITY_J_PER_KG_K

    /** Standard gravity, meters a second squared. */
    const val GRAVITY_MPS2 = 9.80665
}

/**
 * This discretization's own vertical modes: the eigenvectors of its vertical operator, not the
 * continuous atmosphere's `N H / (n pi)`.
 *
 * On a resting atmosphere the levels' geopotential obeys `div = -G d(Phi)/dt` level by level, with
 * `G = M^-1 D^T W D`: `D` takes the geopotential's fall across each interior interface, `W` turns it
 * into temperature (`1 / (R ln(p ratio))`) and then into vertical motion (`1 / S`, the static
 * stability `S = kappa T / p - dT/dp`), `D^T` differences the vertical motion across each layer and
 * `M` is the layers' thickness. `G`'s eigenvector `e_n` with eigenvalue `lambda_n` is a mode obeying
 * the shallow-water equations with gravity-wave speed `c_n = lambda_n^(-1/2)` and equivalent depth
 * `c_n^2 / g`. `G` is `M^-1` times a symmetric matrix, so its modes are real and orthogonal in the
 * mass-weighted product `e_m^T M e_n`; they are computed from the symmetric form
 * `M^(-1/2) D^T W D M^(-1/2)`. Mode 0 is the barotropic mode (`lambda_0 = 0`, the same at every
 * level, an infinitely fast gravity wave under the rigid lid); modes 1 onward are baroclinic, each
 * with one more change of sign down the column.
 *
 * Damping is built from these modes as projectors ([momentumDamping], [thermalDamping]): each mode
 * is damped at its own stated total rate, and nothing is the silent sum of two damping terms acting on
 * the same mode (the review's point 2).
 */
class VerticalModes(val levels: AtmosphereLevels, val stabilityKelvinPerPascal: DoubleArray) {

    init {
        require(stabilityKelvinPerPascal.size == levels.interiorCount) { "one static stability per interior interface" }
        require(stabilityKelvinPerPascal.all { it > 0.0 }) { "the static stability must be positive at every interface" }
    }

    val count: Int = levels.levelCount

    /** `1 / c_n^2` for each mode, ascending: mode 0 the barotropic. */
    val eigenvalues: DoubleArray

    /** Mode `n`'s geopotential at each level, `[n][level]`, normalized so `e_n^T M e_n = 1` and its top level is positive. */
    val levelStructure: Array<DoubleArray>

    init {
        val size = count
        val inverseRootThickness = DoubleArray(size) { 1.0 / sqrt(levels.thicknessPa[it]) }
        val symmetric = DoubleArray(size * size)
        for (interior in 0 until levels.interiorCount) {
            // D's row for this interface is +1 at the level above and -1 below; D^T W D adds the
            // outer product of that row weighted by W.
            val weight = 1.0 / (stabilityKelvinPerPascal[interior] * DryAir.GAS_CONSTANT_J_PER_KG_K * levels.logPressureRatio[interior])
            val above = interior
            val below = interior + 1
            symmetric[above * size + above] += weight * inverseRootThickness[above] * inverseRootThickness[above]
            symmetric[below * size + below] += weight * inverseRootThickness[below] * inverseRootThickness[below]
            symmetric[above * size + below] -= weight * inverseRootThickness[above] * inverseRootThickness[below]
            symmetric[below * size + above] -= weight * inverseRootThickness[below] * inverseRootThickness[above]
        }
        val eigen = SymmetricEigen(symmetric, size)
        eigenvalues = eigen.values.copyOf()
        // The barotropic mode is exactly a constant: the column's fall is zero for every pair of levels.
        eigenvalues[0] = 0.0
        levelStructure = Array(size) { mode ->
            val structure = DoubleArray(size) { level ->
                if (mode == 0) 1.0 / sqrt(levels.surfacePressurePa) else eigen.vectors[level * size + mode] * inverseRootThickness[level]
            }
            if (structure[0] < 0.0) for (level in 0 until size) structure[level] = -structure[level]
            structure
        }
    }

    /** Each mode's gravity-wave speed in meters a second; the barotropic mode's is infinite under the rigid lid. */
    val speedMetersPerSecond = DoubleArray(count) { if (it == 0) Double.POSITIVE_INFINITY else 1.0 / sqrt(eigenvalues[it]) }

    /** Each mode's equivalent depth `c^2 / g`, in meters. */
    val equivalentDepthMeters = DoubleArray(count) { if (it == 0) Double.POSITIVE_INFINITY else 1.0 / (eigenvalues[it] * DryAir.GRAVITY_MPS2) }

    /**
     * Baroclinic mode `n`'s temperature at each interior interface, `[n - 1][interface]`, in kelvin
     * per unit of its geopotential: the hydrostatic fall of [levelStructure] across each interface.
     */
    val temperatureStructure: Array<DoubleArray> = Array(count - 1) { index ->
        val mode = index + 1
        DoubleArray(levels.interiorCount) { interior ->
            (levelStructure[mode][interior] - levelStructure[mode][interior + 1]) /
                (DryAir.GAS_CONSTANT_J_PER_KG_K * levels.logPressureRatio[interior])
        }
    }

    /**
     * The momentum damping as a matrix on the levels, `[level][level]`: `sum_n rate_n e_n e_n^T M`,
     * each mode damped at its own [ratesPerSecond]`[n]` and nothing else.
     */
    fun momentumDamping(ratesPerSecond: DoubleArray): Array<DoubleArray> {
        require(ratesPerSecond.size == count) { "one momentum rate per mode" }
        return Array(count) { row ->
            DoubleArray(count) { column ->
                var sum = 0.0
                for (mode in 0 until count) sum += ratesPerSecond[mode] * levelStructure[mode][row] * levelStructure[mode][column] * levels.thicknessPa[column]
                sum
            }
        }
    }

    /**
     * The thermal damping as a matrix on the interior interfaces: each baroclinic mode `n`'s
     * temperature damped at [ratesPerSecond]`[n - 1]`. The temperature structures are orthogonal in
     * the product weighted by `R ln(p ratio) / S`, with `t_n^T N t_n = lambda_n`, so the projector
     * onto mode `n` is `t_n t_n^T N / lambda_n`.
     */
    fun thermalDamping(ratesPerSecond: DoubleArray): Array<DoubleArray> {
        val interiors = levels.interiorCount
        require(ratesPerSecond.size == interiors) { "one thermal rate per baroclinic mode" }
        val metric = DoubleArray(interiors) {
            DryAir.GAS_CONSTANT_J_PER_KG_K * levels.logPressureRatio[it] / stabilityKelvinPerPascal[it]
        }
        return Array(interiors) { row ->
            DoubleArray(interiors) { column ->
                var sum = 0.0
                for (index in 0 until interiors) {
                    val structure = temperatureStructure[index]
                    sum += ratesPerSecond[index] * structure[row] * structure[column] * metric[column] / eigenvalues[index + 1]
                }
                sum
            }
        }
    }

    /** Each mode's share of a damping matrix on the levels, `e_n^T M R e_n`: the total rate it puts on mode `n`. */
    fun rateOnMode(matrix: Array<DoubleArray>, mode: Int): Double {
        var sum = 0.0
        for (row in 0 until count) for (column in 0 until count) {
            sum += levelStructure[mode][row] * levels.thicknessPa[row] * matrix[row][column] * levelStructure[mode][column]
        }
        return sum
    }
}
