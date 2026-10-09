package com.cartogenesis.worldgen.pipeline

/**
 * The stationary-wave model's dissipation, every term at a stated total rate.
 *
 * - **Free-atmosphere friction**, per vertical mode: [barotropicFrictionPerSecond] on the barotropic
 *   mode and [baroclinicFrictionPerSecond] on each baroclinic one, built as projectors
 *   ([VerticalModes.momentumDamping]), so a mode's rate is exactly the one stated.
 * - **Newtonian cooling**, [thermalPerSecond] on each baroclinic mode's temperature, the same way.
 * - **Extra friction or cooling at chosen levels**, [levelFrictionPerSecond] (one rate per level, the
 *   boundary layer's surface drag on the lowest) and [interfaceThermalPerSecond] (one per interior
 *   interface). These are not modal; [VerticalModes.rateOnMode] reports what they add to each mode.
 * - **Mixing**, [mixingSquareMetersPerSecond]: Laplacian diffusion of vorticity, divergence and
 *   temperature, the scale-selective damping that keeps a critical line's response finite.
 *
 * The mixing is a Laplacian, not the biharmonic of Ting and Yu (1998) or Hoskins and Karoly (1981):
 * the Laplacian twice is not convergent within a few rows of a pole for zonal waves 1 and up on this
 * grid (docs/DESIGN_LEDGER.md, A1-2), and a fourth difference would couple rows two apart, which the
 * block-tridiagonal solve cannot hold. The divergence, curl and Laplacian it is built from converge at
 * second order over the whole sphere (`SphericalOperatorsTest`). See docs/DESIGN_LEDGER.md, A1-3, for
 * which published set each benchmark runs with.
 */
class WaveDamping(
    val barotropicFrictionPerSecond: Double,
    val baroclinicFrictionPerSecond: Double,
    val thermalPerSecond: Double,
    val mixingSquareMetersPerSecond: Double,
    val levelFrictionPerSecond: DoubleArray? = null,
    val interfaceThermalPerSecond: DoubleArray? = null
) {
    /** The momentum damping on [modes]' levels, `[level][level]`. */
    fun momentumMatrix(modes: VerticalModes): Array<DoubleArray> {
        val rates = DoubleArray(modes.count) { if (it == 0) barotropicFrictionPerSecond else baroclinicFrictionPerSecond }
        val matrix = modes.momentumDamping(rates)
        levelFrictionPerSecond?.let { extra ->
            require(extra.size == modes.count) { "one level friction per level" }
            for (level in extra.indices) matrix[level][level] += extra[level]
        }
        return matrix
    }

    /** The thermal damping on [modes]' interior interfaces, `[interface][interface]`. */
    fun thermalMatrix(modes: VerticalModes): Array<DoubleArray> {
        val interiors = modes.levels.interiorCount
        val matrix = modes.thermalDamping(DoubleArray(interiors) { thermalPerSecond })
        interfaceThermalPerSecond?.let { extra ->
            require(extra.size == interiors) { "one interface cooling per interior interface" }
            for (interior in extra.indices) matrix[interior][interior] += extra[interior]
        }
        return matrix
    }

    /** Every rate and the mixing times [factor]: the sensitivity runs' halved and doubled damping. */
    fun scaledBy(factor: Double) = WaveDamping(
        barotropicFrictionPerSecond * factor,
        baroclinicFrictionPerSecond * factor,
        thermalPerSecond * factor,
        mixingSquareMetersPerSecond * factor,
        levelFrictionPerSecond?.let { rates -> DoubleArray(rates.size) { rates[it] * factor } },
        interfaceThermalPerSecond?.let { rates -> DoubleArray(rates.size) { rates[it] * factor } }
    )

    companion object {
        const val SECONDS_PER_DAY = 86_400.0

        /** A rate of once per [days], per second. */
        fun perDays(days: Double): Double = 1.0 / (days * SECONDS_PER_DAY)

        /**
         * Lee, Wang and Mapes (2009), section 3, as the A1a design read it and the review confirmed:
         * barotropic friction (20 days)^-1, baroclinic (10 days)^-1, thermal damping (2 days)^-1 after
         * Gill, and mixing of 1e6 m^2/s. Their model is a tropical one, and the 2-day cooling is
         * Gill's choice for heating balanced in place.
         */
        const val LEE_BAROTROPIC_DAYS = 20.0
        const val LEE_BAROCLINIC_DAYS = 10.0
        const val LEE_THERMAL_DAYS = 2.0
        const val LEE_MIXING_M2_PER_S = 1.0e6

        /**
         * Ting and Yu (1998): a linear damping of 15 days on every variable, chosen so their linear
         * model's response to tropical heating matched their nonlinear model's, with a biharmonic
         * of 1e17 m^4/s. The paper could not be opened in this chunk (the publisher refused the
         * request); the figures are the review's transcription of its abstract and text.
         */
        const val TING_YU_DAYS = 15.0

        /**
         * The surface's drag coefficient, Large and Pond's 1.2e-3 over the sea, as `OceanStage`
         * takes it, and the belts' 7.5 m/s it is linearized about (`PressureWind.BELT_SPEED_MPS`):
         * the boundary layer's stress `rho C_D |V| V` as a linear drag on the lowest layer.
         */
        const val SURFACE_DRAG_COEFFICIENT = 1.2e-3

        /** Lee and others' (2009) set, with no boundary layer of its own. */
        fun leeAndOthers2009() = WaveDamping(
            perDays(LEE_BAROTROPIC_DAYS), perDays(LEE_BAROCLINIC_DAYS), perDays(LEE_THERMAL_DAYS), LEE_MIXING_M2_PER_S
        )

        /**
         * The same rate [ratePerSecond] on every mode's momentum and temperature and no mixing:
         * Gill's (1980) `epsilon`, for which his analytic solution is written.
         */
        fun uniform(ratePerSecond: Double, mixingSquareMetersPerSecond: Double = 0.0) =
            WaveDamping(ratePerSecond, ratePerSecond, ratePerSecond, mixingSquareMetersPerSecond)

        /**
         * The rate the surface's stress puts on the lowest layer of [levels] over air of
         * [surfaceDensity]: `rho C_D |V| g / dp`, with `|V|` the belts' speed. A thinner lowest layer
         * takes the same stress on less mass and is damped faster, so the column's drag is the same
         * at every vertical resolution.
         */
        fun surfaceStressRate(levels: AtmosphereLevels, surfaceDensity: Double): Double =
            surfaceDensity * SURFACE_DRAG_COEFFICIENT * PressureWind.BELT_SPEED_MPS * DryAir.GRAVITY_MPS2 /
                levels.thicknessPa[levels.levelCount - 1]

        /**
         * The set the coupled model will start from: Ting and Yu's 15 days on every free mode's
         * momentum and temperature, the surface's stress on the lowest layer, and Lee and others'
         * mixing in place of the biharmonic. Why these and not Lee and others' 2-day cooling:
         * docs/DESIGN_LEDGER.md, A1-3.
         */
        fun forWorlds(levels: AtmosphereLevels, surfaceDensity: Double) = WaveDamping(
            perDays(TING_YU_DAYS), perDays(TING_YU_DAYS), perDays(TING_YU_DAYS), LEE_MIXING_M2_PER_S,
            levelFrictionPerSecond = DoubleArray(levels.levelCount) {
                if (it == levels.levelCount - 1) surfaceStressRate(levels, surfaceDensity) else 0.0
            }
        )
    }
}
