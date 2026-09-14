package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.math.Fft2D
import com.cartogenesis.worldgen.model.IsostasyConfig
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The crust floating on the mantle, and the crust bending under what is put on it.
 *
 * Two readings of one equation, and the pipeline needs both. [Columns] is the local reading —
 * Airy's — which asks what altitude a column of crust of a given thickness and density floats at,
 * and answers the question the generator could not previously ask at all: where is sea level? It
 * is what gives [PlateStage]'s height field an absolute vertical scale, and with it Earth's two
 * hypsometric modes, which are an isostatic fact about two crusts and not a percentile.
 *
 * [Flexure] is the regional reading: a plate with strength does not sink only where the load
 * stands, it bends around it. Every consequence of that is a landform the generator lacked. A
 * mountain belt carries a moat in front of it, which is a foreland basin; a range that erodes
 * unloads and rebounds, so its peaks rise as its mass falls; a delta subsides under its own
 * sediment; and an ice sheet holds its bed down until it melts, after which the bed comes back up
 * for ten thousand years.
 *
 * See `REALISM_AUDIT.md` 1.2 and REALISM_PLAN.md, S2. The equation and both derivations are
 * Turcotte & Schubert, *Geodynamics*, chapters 2 and 3.
 */
internal object Isostasy {

    /**
     * Airy isostasy: what altitude a crustal column floats at, in metres above the waterline.
     *
     * Every column is weighed down to a compensation depth below the deepest root, and the columns
     * that balance are the ones carrying the same mass. Write `N` for a column's mass above that
     * depth less the mass of the mantle it displaces, and the altitude falls straight out:
     *
     *     N = datum + thickness * (mantleDensity - crustDensity) + mantleDensity * buoyancy
     *     altitude = N / mantleDensity                    where the column stands in air
     *     altitude = N / (mantleDensity - waterDensity)   where it stands under water
     *
     * The two cases differ because water is not nothing. A column that would float at 1,000 m in
     * air floats 1.45 times deeper than that once the hollow above it fills with sea, since the
     * water it now carries is itself a load — which is why the ocean floor is so much further below
     * the continents than the difference in the two crusts alone would put it.
     *
     * `datum` is the constant of integration, and isostasy does not supply it: what fixes where
     * sea level sits among the columns is how much water the planet has. So it is solved from one
     * declared figure, [IsostasyConfig.continentalFreeboardMetres], and everything else is relative
     * to that.
     *
     * `buoyancy` is the air-equivalent height a column gains from being hot rather than from being
     * thick. Continental crust gets none; oceanic crust gets whatever amount puts a column of its
     * own age at the depth Parsons and Sclater's curve says floor of that age lies at — 2,357 m
     * of buoyancy at a spreading ridge, falling as the root of the age to nothing on the oldest
     * floor there is. See [IsostasyConfig.seafloorRidgeDepthMetres].
     *
     * Reading the depth-age curve backwards through Airy rather than writing it onto the map is
     * what keeps a continental margin a mixture: a cell halfway across one carries a column
     * halfway between the two crusts in thickness, in density *and* in heat, and there is no
     * special case anywhere for the shelf, the slope or the rise.
     */
    class Columns(private val isostasy: IsostasyConfig) {

        private val mantleDensity = isostasy.mantleDensity
        private val waterDensity = isostasy.seaWaterDensity
        private val continentalThicknessMetres = isostasy.continentalCrustThicknessKm * METRES_PER_KM
        private val oceanicThicknessMetres = isostasy.oceanicCrustThicknessKm * METRES_PER_KM

        /** The column mass a continental column of standard thickness contributes, less the mantle. */
        private val continentalBuoyantMass =
            continentalThicknessMetres * (mantleDensity - isostasy.continentalCrustDensity)

        private val oceanicBuoyantMass =
            oceanicThicknessMetres * (mantleDensity - isostasy.oceanicCrustDensity)

        /** Solved so a standard continental column floats at the declared freeboard. */
        val datum: Float = isostasy.continentalFreeboardMetres * mantleDensity - continentalBuoyantMass

        /**
         * How deep Parsons and Sclater put sea floor of [ageMyr] millions of years, in metres and
         * so negative.
         *
         * The root branch below the flattening age, their exponential above it. The two agree to
         * within a few tens of metres where they meet, which is the fit's own business and not
         * something this blends over.
         */
        fun seafloorDepthMetres(ageMyr: Float): Float {
            val age = ageMyr.coerceAtLeast(0f)
            val root = isostasy.seafloorRidgeDepthMetres +
                isostasy.seafloorSubsidenceMetresPerRootMyr * sqrt(age)
            val flattened = isostasy.seafloorAbyssalAsymptoteMetres -
                isostasy.seafloorFlatteningRangeMetres * exp(-age / isostasy.seafloorFlatteningTimeMyr)
            // Whichever is shallower, which is where the two branches actually meet. Parsons and
            // Sclater fitted them to different halves of their data and quote a crossover "near 70
            // Myr"; evaluated, their root runs a little above their exponential from about 50 Myr
            // on, so switching at a declared age would step the floor up by three hundred metres
            // and make it younger the deeper it got. The shallower of two curves that both rise
            // with age rises with age, and it is continuous by construction.
            return -(if (root < flattened) root else flattened)
        }

        /**
         * The age, in millions of years, at which sea floor lies [depthMetres] below the water —
         * [seafloorDepthMetres] read backwards.
         *
         * Only the root branch is inverted, because the only caller wants an age near the mean and
         * the mean of any ocean this generator draws is well inside the flattening age. A depth
         * shallower than the ridge itself comes back as brand new floor.
         */
        fun seafloorAgeAtDepth(depthMetres: Float): Float {
            val below = depthMetres - isostasy.seafloorRidgeDepthMetres
            if (below <= 0f) return 0f
            val roots = below / isostasy.seafloorSubsidenceMetresPerRootMyr
            return roots * roots
        }

        /**
         * The heat in sea floor of [ageMyr], as the air-equivalent metres of buoyancy it is worth.
         *
         * Solved rather than declared: it is whatever makes an oceanic column of that age float at
         * [seafloorDepthMetres]. At the ridge it comes to 2,357 m and on the oldest floor to a
         * little under nothing, which is the same statement as the curve it was read out of.
         */
        fun oceanicThermalBuoyancyMetres(ageMyr: Float): Float =
            (seafloorDepthMetres(ageMyr) * (mantleDensity - waterDensity) - datum -
                oceanicBuoyantMass) / mantleDensity

        /**
         * The altitude a column floats at, for a cell that is [continentalShare] continental crust
         * of the rest oceanic and [seafloorAgeMyr] million years old where it is oceanic, carrying
         * [extraContinentalThicknessKm] of crust beyond the standard continental column.
         *
         * The share is not a label but a mixture, and the mixture is the point: a continental
         * margin is crust that has been stretched and thinned on its way out to the ocean floor,
         * so a cell halfway across one carries a column halfway between the two in thickness, in
         * density and in heat. That is where the shelf, the slope and the rise come from, and it
         * is why [PlateStage] blurs the share across the plate boundary rather than stepping it.
         *
         * The extra thickness is the cratonic profile
         * ([com.cartogenesis.worldgen.model.IsostasyConfig.cratonThickeningKm]) and is a signed
         * figure with a mean of zero over the map's continental crust, so it tilts a continent
         * without moving the datum. It buoys in proportion to the share, because it is continental
         * crust that is being added and there is none of it to add on the ocean floor.
         */
        fun altitudeMetres(
            continentalShare: Float,
            seafloorAgeMyr: Float,
            extraContinentalThicknessKm: Float = 0f
        ): Float {
            val share = continentalShare.coerceIn(0f, 1f)
            val extraBuoyantMass = share * extraContinentalThicknessKm * METRES_PER_KM *
                (mantleDensity - isostasy.continentalCrustDensity)
            val buoyantMass = extraBuoyantMass +
                oceanicBuoyantMass + share * (continentalBuoyantMass - oceanicBuoyantMass)
            val buoyancy = (1f - share) * oceanicThermalBuoyancyMetres(seafloorAgeMyr)
            val columnMass = datum + buoyantMass + mantleDensity * buoyancy
            return if (columnMass >= 0f) columnMass / mantleDensity
            else columnMass / (mantleDensity - waterDensity)
        }

        /**
         * What a cold oceanic column would float at, in metres — the depth the sea floor would
         * reach if the heat ran all the way out of it.
         *
         * Reported rather than used, and it is the check on the whole scheme: -5,926 m, against
         * Parsons and Sclater's cold asymptote of -6,400 measured on Earth's oldest floor. The two
         * agree to within 8%, which is as close as two independent readings of the same physics
         * come, and it is why the thermal buoyancy above is a measurement rather than a free
         * parameter.
         */
        val coldOceanicFloorMetres: Float
            get() = (datum + oceanicBuoyantMass) / (mantleDensity - waterDensity)
    }

    /**
     * Flexure of an elastic plate under a load, solved in the frequency domain.
     *
     * A thin elastic plate resting on a fluid mantle and carrying a load `L` bends by `w`, where
     *
     *     D * grad^4(w) + dRho * g * w = L
     *
     * with `D` the flexural rigidity and `dRho` the density contrast between the mantle that flows
     * out from under the plate and whatever flows into the moat above it. Transformed, the fourth
     * derivative is a fourth power and the equation stops being a differential one:
     *
     *     w(k) = L(k) / (dRho * g + D * k^4)
     *
     * which is a low-pass filter on the load, with a corner at the flexural parameter
     * `(4D / (dRho * g))^(1/4)`. Short-wavelength loads are held up by the plate's own stiffness
     * and barely deflect it; loads much broader than the corner sink until they float, which is
     * Airy isostasy again. That is the whole physics of a foreland basin, of post-glacial rebound,
     * and of why a delta subsides but a sand dune does not.
     *
     * One transform pair over the whole grid per call, which is the same machinery
     * [TerrainStage.integrate] uses for Frankot-Chellappa and the reason rule 8 of the plan calls
     * this a G-track candidate at 4096 rather than a per-cell pass needing a kernel today.
     */
    class Flexure(config: WorldGenConfig) {

        private val cellsAcross = config.width
        private val cellsDown = config.height
        private val isostasy = config.isostasy

        /** `dRho * g`, the restoring term: what the mantle pushes back with per metre of bend. */
        private val restoringPerMetre =
            (isostasy.mantleDensity - isostasy.deflectionFillDensity).toDouble() * isostasy.gravity

        /**
         * `D = E * Te^3 / (12 * (1 - v^2))`, in newton-metres: how hard the plate is to bend.
         */
        val rigidity: Double = run {
            val elasticThicknessMetres = isostasy.elasticThicknessKm.toDouble() * METRES_PER_KM
            val youngsModulus = isostasy.youngsModulusGPa.toDouble() * PASCALS_PER_GPA
            val poisson = isostasy.poissonRatio.toDouble()
            youngsModulus * elasticThicknessMetres * elasticThicknessMetres * elasticThicknessMetres /
                (12.0 * (1.0 - poisson * poisson))
        }

        /** `(4D / (dRho * g))^(1/4)`, in metres: how far a load is felt. */
        val flexuralParameterMetres: Double
            get() = kotlin.math.sqrt(kotlin.math.sqrt(4.0 * rigidity / restoringPerMetre))

        private val transform = Fft2D(cellsAcross, cellsDown)
        private val real = DoubleArray(cellsAcross * cellsDown)
        private val imaginary = DoubleArray(cellsAcross * cellsDown)

        // Angular wavenumbers, in radians per metre, per cycle across the map. The two axes have
        // their own cell size — an equirectangular map is twice as wide as it is tall and its cells
        // are only square when the grid is — so a load is not filtered isotropically in cells but
        // is in kilometres, which is the one that matters.
        private val radiansPerCycleAcross =
            2.0 * PI / (cellsAcross * config.cellWidthKm * METRES_PER_KM)
        private val radiansPerCycleDown =
            2.0 * PI / (cellsDown * config.cellHeightKm * METRES_PER_KM)

        /**
         * How much of the surface a load bends downward, in metres, one entry per cell.
         *
         * [loadPascals] is the weight per unit area the plate is carrying beyond the state it was
         * in equilibrium with — positive where mass has been added, negative where it has been
         * taken away, so an eroded range comes back with a negative deflection and rises.
         * [deflection] is written in place and is positive downward.
         *
         * The mean of the load is removed before the transform and the zero-frequency term is left
         * at zero. That is not a numerical convenience: a uniform load over a whole planet does not
         * bend anything, it changes where the datum is, and the datum is [Columns]' business. Left
         * in, the term would sink or raise the entire map by the average of whatever erosion had
         * done that round.
         */
        fun deflectionMetres(loadPascals: FloatArray, deflection: FloatArray) {
            val cellCount = cellsAcross * cellsDown
            var loadSum = 0.0
            for (cell in 0 until cellCount) loadSum += loadPascals[cell].toDouble()
            val meanLoad = loadSum / cellCount
            for (cell in 0 until cellCount) {
                real[cell] = loadPascals[cell].toDouble() - meanLoad
                imaginary[cell] = 0.0
            }

            transform.forward(real, imaginary)

            parallelChunks(0, cellsDown) { startRow, endRow ->
                for (row in startRow until endRow) {
                    val cyclesDown = if (row <= cellsDown / 2) row else row - cellsDown
                    val wavenumberDown = cyclesDown * radiansPerCycleDown
                    for (column in 0 until cellsAcross) {
                        val cyclesAcross =
                            if (column <= cellsAcross / 2) column else column - cellsAcross
                        val wavenumberAcross = cyclesAcross * radiansPerCycleAcross
                        val cell = row * cellsAcross + column
                        val squared =
                            wavenumberAcross * wavenumberAcross + wavenumberDown * wavenumberDown
                        if (squared == 0.0) {
                            real[cell] = 0.0
                            imaginary[cell] = 0.0
                            continue
                        }
                        val response = 1.0 / (restoringPerMetre + rigidity * squared * squared)
                        real[cell] *= response
                        imaginary[cell] *= response
                    }
                }
            }

            transform.inverse(real, imaginary)
            for (cell in 0 until cellCount) deflection[cell] = real[cell].toFloat()
        }
    }

    /** The weight per unit area of [metres] of rock at [density], in pascals. */
    fun loadPascals(metres: Float, density: Float, gravity: Float): Float = metres * density * gravity
}

/** Metres in a kilometre, for the thicknesses this file is handed in kilometres. */
private const val METRES_PER_KM = 1_000.0f

/** Pascals in a gigapascal, for Young's modulus. */
private const val PASCALS_PER_GPA = 1e9
