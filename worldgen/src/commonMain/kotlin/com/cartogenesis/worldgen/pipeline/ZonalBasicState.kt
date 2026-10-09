package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.model.WorldScale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * The horizontal mean of the atmosphere's temperature as a function of pressure, which sets the
 * static stability the stationary waves feel.
 */
interface MeanTemperatureProfile {
    /** The mean temperature at [pressurePa], kelvin. */
    fun kelvin(pressurePa: Double): Double

    /** Its derivative with pressure, kelvin per pascal. */
    fun kelvinPerPascal(pressurePa: Double): Double
}

/**
 * The zonal-mean state the stationary waves are linearized about: an eastward wind at each level and
 * at the ground, as a function of latitude, and a mean temperature profile. Prescribed, and balanced
 * by construction: the meridional temperature gradient the thermodynamics reads at each interior
 * interface is the one the wind's own shear across it implies by the gradient-wind form of thermal
 * wind,
 *
 * `(f + (U_i + U_{i+1}) tan(phi) / a) (U_i - U_{i+1}) = -(R ln(p_{i+1} / p_i) / a) dT/dphi`,
 *
 * which is the difference of the zonal-mean meridional momentum balance
 * `(f + U tan(phi) / a) U = -(1/a) dPhi/dphi` between the two levels. So no basic state here can be
 * out of balance with itself (the review's point 1).
 *
 * There is no mean meridional circulation: a balanced state has none (Jablonowski and Williamson set
 * `v = 0` exactly), so the Hadley cell's advection is not linearized about here. It enters with a
 * basic state derived from the energy balance (docs/TODO.md).
 *
 * [zonalWindAtLevels] is `[level][row]` on [grid]'s rows, meters a second; [surfaceZonalWind] the
 * wind at the ground the terrain's lift reads, `[row]`.
 */
class ZonalBasicState(
    val grid: SphericalGrid,
    val levels: AtmosphereLevels,
    val zonalWindAtLevels: Array<DoubleArray>,
    val surfaceZonalWind: DoubleArray,
    val meanTemperature: MeanTemperatureProfile,
    val spinPerSecond: Double = WorldScale.ROTATION_RATE_PER_S.toDouble()
) {
    init {
        require(zonalWindAtLevels.size == levels.levelCount && zonalWindAtLevels.all { it.size == grid.rows }) {
            "one zonal wind per level and row"
        }
        require(surfaceZonalWind.size == grid.rows) { "one surface wind per row" }
    }

    private val radius = grid.radiusMeters

    /** `S = kappa T / p - dT/dp` at each interior interface, kelvin per pascal, from [meanTemperature]. */
    val stabilityKelvinPerPascal = DoubleArray(levels.interiorCount) { interior ->
        val pressure = levels.interiorPressurePa[interior]
        DryAir.KAPPA * meanTemperature.kelvin(pressure) / pressure - meanTemperature.kelvinPerPascal(pressure)
    }

    /** This state's vertical modes. */
    val modes: VerticalModes by lazy { VerticalModes(levels, stabilityKelvinPerPascal) }

    /** The air's density at the ground, kilograms a cubic meter, `p_s / (R T_s)`. */
    val surfaceDensity: Double = levels.surfacePressurePa / (DryAir.GAS_CONSTANT_J_PER_KG_K * meanTemperature.kelvin(levels.surfacePressurePa))

    /** The Coriolis parameter at each row's center, per second. */
    val coriolisAtRows = DoubleArray(grid.rows) { 2.0 * spinPerSecond * sin(grid.latitudeRadians[it]) }

    /** The Coriolis parameter on each face, per second; zero length faces at the poles included. */
    val coriolisAtFaces = DoubleArray(grid.rows + 1) { 2.0 * spinPerSecond * grid.sinFace[it] }

    /** Each level's wind on the faces between rows, the mean of the two rows; at the poles the polar row's. */
    val zonalWindAtFaces: Array<DoubleArray> = Array(levels.levelCount) { level ->
        val rows = zonalWindAtLevels[level]
        DoubleArray(grid.rows + 1) { face ->
            when (face) {
                0 -> rows[0]
                grid.rows -> rows[grid.rows - 1]
                else -> 0.5 * (rows[face - 1] + rows[face])
            }
        }
    }

    /**
     * Each level's relative vorticity at the row centers, per second: `-(1 / (a cos phi)) d(U cos phi)/dphi`
     * as the circulation round the row's band over its area, the face winds on its edges.
     */
    val relativeVorticity: Array<DoubleArray> = Array(levels.levelCount) { level ->
        DoubleArray(grid.rows) { row ->
            val north = zonalWindAtFaces[level][row] * grid.cosFace[row]
            val south = zonalWindAtFaces[level][row + 1] * grid.cosFace[row + 1]
            -(north - south) / (radius * grid.sinSpanOfRow[row])
        }
    }

    /** The wind at each interior interface, the mean of the levels either side, `[interior][row]`. */
    val zonalWindAtInteriors: Array<DoubleArray> = Array(levels.interiorCount) { interior ->
        DoubleArray(grid.rows) { 0.5 * (zonalWindAtLevels[interior][it] + zonalWindAtLevels[interior + 1][it]) }
    }

    /**
     * `dT/dphi` at each interior interface and row, kelvin per radian, from the gradient-wind thermal
     * wind across the interface: the temperature gradient this wind is balanced by.
     */
    val temperatureGradientPerRadian: Array<DoubleArray> = Array(levels.interiorCount) { interior ->
        DoubleArray(grid.rows) { row ->
            val above = zonalWindAtLevels[interior][row]
            val below = zonalWindAtLevels[interior + 1][row]
            val metric = (above + below) * tan(grid.latitudeRadians[row]) / radius
            -(coriolisAtRows[row] + metric) * (above - below) * radius /
                (DryAir.GAS_CONSTANT_J_PER_KG_K * levels.logPressureRatio[interior])
        }
    }

    companion object {
        /**
         * Jablonowski and Williamson's (2006) horizontal-mean temperature, their equations (4) and
         * (5): `T0 eta^(R Gamma / g)` below the tropopause at `eta_t = 0.2` and an added
         * `Delta T (eta_t - eta)^5` above it, `eta = p / p_s`, with `T0 = 288 K`, `Gamma = 0.005 K/m`
         * and `Delta T = 4.8e5 K`.
         */
        val JABLONOWSKI_WILLIAMSON_TEMPERATURE: MeanTemperatureProfile = object : MeanTemperatureProfile {
            private val exponent = DryAir.GAS_CONSTANT_J_PER_KG_K * JW_LAPSE_RATE_K_PER_M / DryAir.GRAVITY_MPS2

            override fun kelvin(pressurePa: Double): Double {
                val eta = pressurePa / AtmosphereLevels.REFERENCE_SURFACE_PRESSURE_PA
                val troposphere = JW_SURFACE_KELVIN * eta.pow(exponent)
                return if (eta >= JW_TROPOPAUSE_ETA) troposphere else troposphere + JW_STRATOSPHERE_KELVIN * (JW_TROPOPAUSE_ETA - eta).pow(5)
            }

            override fun kelvinPerPascal(pressurePa: Double): Double {
                val reference = AtmosphereLevels.REFERENCE_SURFACE_PRESSURE_PA
                val eta = pressurePa / reference
                val troposphere = JW_SURFACE_KELVIN * exponent * eta.pow(exponent - 1) / reference
                return if (eta >= JW_TROPOPAUSE_ETA) troposphere
                else troposphere - 5.0 * JW_STRATOSPHERE_KELVIN * (JW_TROPOPAUSE_ETA - eta).pow(4) / reference
            }
        }

        /** Their surface temperature, 288 K. */
        private const val JW_SURFACE_KELVIN = 288.0

        /** Their lapse rate, 0.005 K/m, "similar to the observed diabatic lapse rate". */
        private const val JW_LAPSE_RATE_K_PER_M = 0.005

        /** Their tropopause, `eta_t = 0.2`. */
        private const val JW_TROPOPAUSE_ETA = 0.2

        /** Their empirical stratospheric temperature difference, `Delta T = 4.8e5 K`. */
        private const val JW_STRATOSPHERE_KELVIN = 4.8e5

        /** Their jets' strength, `u0 = 35 m/s`, "close to the wind speed of the zonal-mean time-mean jet streams". */
        const val JW_JET_SPEED_MPS = 35.0

        /** Their `eta_0 = 0.252`, which puts the jets' maximum near 250 hPa. */
        private const val JW_JET_ETA = 0.252

        /** A state at rest: Gill's (1980) unperturbed atmosphere. */
        fun resting(grid: SphericalGrid, levels: AtmosphereLevels): ZonalBasicState =
            ZonalBasicState(grid, levels, Array(levels.levelCount) { DoubleArray(grid.rows) }, DoubleArray(grid.rows), JABLONOWSKI_WILLIAMSON_TEMPERATURE)

        /**
         * Solid-body rotation at [equatorialSpeedMps] at every level and at the ground: `U = a w cos(phi)`.
         * It has no shear, so its temperature is the same at every latitude. Hoskins and Karoly's (1981,
         * section 5c) constant-angular-velocity flow, on which barotropic Rossby rays are great circles.
         */
        fun solidBodyRotation(grid: SphericalGrid, levels: AtmosphereLevels, equatorialSpeedMps: Double): ZonalBasicState {
            val profile = DoubleArray(grid.rows) { equatorialSpeedMps * grid.cosLatitude[it] }
            return ZonalBasicState(grid, levels, Array(levels.levelCount) { profile.copyOf() }, profile.copyOf(), JABLONOWSKI_WILLIAMSON_TEMPERATURE)
        }

        /**
         * Jablonowski and Williamson's (2006) balanced state, their equation (2):
         * `u = u0 cos^(3/2)(eta_v) sin^2(2 phi)`, `eta_v = (eta - eta_0) pi / 2`, `eta = p / p_s`: two
         * jets at 45 degrees of [jetSpeedMps] near 250 hPa, westerly at every level and latitude, with
         * their horizontal-mean temperature. The ground's wind is the same formula at `eta = 1`. It is
         * an exact steady state of the adiabatic primitive equations on any sphere; its temperature
         * gradient here follows from its wind on [grid]'s radius and the planet's spin.
         */
        fun jablonowskiWilliamson(grid: SphericalGrid, levels: AtmosphereLevels, jetSpeedMps: Double = JW_JET_SPEED_MPS): ZonalBasicState {
            fun wind(eta: Double, latitude: Double): Double {
                val verticalAngle = (eta - JW_JET_ETA) * PI / 2
                val doubled = sin(2 * latitude)
                return jetSpeedMps * cos(verticalAngle).pow(1.5) * doubled * doubled
            }
            val atLevels = Array(levels.levelCount) { level ->
                val eta = levels.levelPressurePa[level] / levels.surfacePressurePa
                DoubleArray(grid.rows) { wind(eta, grid.latitudeRadians[it]) }
            }
            val atGround = DoubleArray(grid.rows) { wind(1.0, grid.latitudeRadians[it]) }
            return ZonalBasicState(grid, levels, atLevels, atGround, JABLONOWSKI_WILLIAMSON_TEMPERATURE)
        }
    }
}
