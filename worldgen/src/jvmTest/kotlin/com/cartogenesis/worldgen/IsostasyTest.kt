package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.math.JumpFloodDistance
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.BoundaryClass
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.GlaciationStage
import com.cartogenesis.worldgen.pipeline.Isostasy
import com.cartogenesis.worldgen.pipeline.OceanStage
import com.cartogenesis.worldgen.pipeline.SeaLevelStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
import com.cartogenesis.worldgen.pipeline.erodeBlocking
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * The solid earth floats and it bends, and both show on the map.
 *
 * S2's guards. Each clause is measured against a stated figure of Earth's and each is shown to
 * fail against the world without the mechanism it is about — which for most of them is
 * `IsostasyConfig.enabled` off, the generator as it stood before this chunk: one level for every
 * crust and a shoreline that was a percentile through a field renormalised to its own extremes.
 *
 * What each clause is for:
 *
 *  - the two crusts float at Earth's two levels, so the hypsometry has two modes and a trough
 *    between them where the continental slope is;
 *  - the ocean-coverage slider still means the share of the world under water, now by choosing how
 *    much of the world is continental crust rather than by choosing where to cut a histogram, and
 *    the cut is the check;
 *  - relief in a belt that is being pushed up while it is being cut down settles where Whipple and
 *    Tucker say it does, going as uplift over erodibility;
 *  - the plate bends under what the water moves, so a range rebounds as it is stripped and the
 *    ground in front of it sinks under the sediment;
 *  - and ice holds its bed down.
 *
 * See docs/DESIGN_LEDGER.md, S2, and [Isostasy].
 */
class IsostasyTest : BorrowsSharedWorlds() {

    // ------------------------------------------------------------------ the columns

    /**
     * Airy's equation against the two figures it is solved from, and against the one it predicts.
     *
     * Arithmetic rather than a world, so it is exact and instant. What it holds is that the
     * constants in `IsostasyConfig` are the ones the section says they are: a standard continental
     * column floats at Earth's mean land elevation, a column of new sea floor floats at Parsons
     * and Sclater's ridge depth and one of the oldest floor at their flattened asymptote, and what
     * separates the two from a *cold* oceanic column is a thermal buoyancy inside the band their
     * subsidence curve allows.
     */
    @Test
    fun `the two crusts float where Earth's do`() {
        val isostasy = WorldGenConfig().isostasy
        val columns = Isostasy.Columns(isostasy)

        val referenceAge = columns.seafloorAgeAtDepth(isostasy.oceanicMeanFloorMetres)
        println(
            ("ISOSTASY columns continental %.0f m, a ridge %.0f m, %.0f-Myr floor %.0f m, the" +
                " oldest floor %.0f m, a cold oceanic column %.0f m; thermal buoyancy %.0f m at" +
                " the ridge and %.0f m on the oldest floor").format(
                columns.altitudeMetres(1f, 0f),
                columns.altitudeMetres(0f, 0f),
                referenceAge,
                columns.altitudeMetres(0f, referenceAge),
                columns.altitudeMetres(0f, isostasy.oldestSeafloorAgeMyr),
                columns.coldOceanicFloorMetres,
                columns.oceanicThermalBuoyancyMetres(0f),
                columns.oceanicThermalBuoyancyMetres(isostasy.oldestSeafloorAgeMyr)
            )
        )
        assertEquals(
            "a standard continental column does not float at Earth's mean land elevation",
            isostasy.continentalFreeboardMetres.toDouble(),
            columns.altitudeMetres(1f, 0f).toDouble(), 1.0
        )
        assertEquals(
            "new sea floor does not float at Parsons & Sclater's ridge depth",
            -isostasy.seafloorRidgeDepthMetres.toDouble(),
            columns.altitudeMetres(0f, 0f).toDouble(), 1.0
        )
        // Parsons & Sclater's own curve: 350 m per root of a million years below the flattening
        // age, their exponential above it, and an asymptote at 6,400 m.
        assertEquals(
            "20-Myr floor does not lie where Parsons & Sclater's root puts it",
            -(isostasy.seafloorRidgeDepthMetres +
                isostasy.seafloorSubsidenceMetresPerRootMyr * sqrt(20f)).toDouble(),
            columns.altitudeMetres(0f, 20f).toDouble(), 1.0
        )
        // The cratonic profile, which is the same equation spent on thickness rather than on
        // density: a kilometre of continental crust is worth `(mantle - crust) / mantle` of itself
        // in freeboard, so Christensen and Mooney's shield-to-extended-crust swing of
        // `cratonThickeningKm` lifts a craton this far above the rim of its own continent.
        val perKilometre = (isostasy.mantleDensity - isostasy.continentalCrustDensity) /
            isostasy.mantleDensity * 1_000f
        val half = isostasy.cratonThickeningKm / 2f
        val tiltMetres =
            columns.altitudeMetres(1f, 0f, half) - columns.altitudeMetres(1f, 0f, -half)
        println(
            "ISOSTASY craton profile %.0f m per km of crust, %.0f m of tilt across %.1f km"
                .format(perKilometre, tiltMetres, isostasy.cratonThickeningKm)
        )
        // Five metres of slack on seventeen hundred, because the two sides are not the same
        // arithmetic in the same precision: the left is one product and the right is a difference
        // of two column masses that each carry the datum, a number near a hundred million.
        assertEquals(
            "the cratonic profile is not Airy's own arithmetic on its thickness swing",
            (isostasy.cratonThickeningKm * perKilometre).toDouble(),
            tiltMetres.toDouble(), 5.0
        )
        assertEquals(
            "a column with no extra crust is not the standard continental column",
            columns.altitudeMetres(1f, 0f).toDouble(),
            columns.altitudeMetres(1f, 0f, 0f).toDouble(), 1e-6
        )

        val oldestFloor = columns.altitudeMetres(0f, isostasy.oldestSeafloorAgeMyr)
        assertTrue(
            "the oldest floor does not approach Parsons & Sclater's asymptote: " +
                "%.0f m".format(oldestFloor),
            oldestFloor in -6_400f..-5_500f
        )
        // A model whose thermal buoyancy fell outside this band would be saying the ocean is deep
        // for some reason other than the age of its crust: Parsons & Sclater put a ridge some
        // 3,900 m above the cold asymptote and the oldest floor a few hundred metres above it.
        val ridgeBuoyancy = columns.oceanicThermalBuoyancyMetres(0f)
        val oldBuoyancy = columns.oceanicThermalBuoyancyMetres(isostasy.oldestSeafloorAgeMyr)
        assertTrue(
            "the buoyancy of new sea floor is %.0f m, outside the 2,000-4,000 m Parsons &".format(
                ridgeBuoyancy
            ) + " Sclater's subsidence curve allows",
            ridgeBuoyancy in 2_000f..4_000f
        )
        assertTrue(
            "the oldest floor keeps %.0f m of buoyancy, which is not the cold column Parsons &"
                .format(oldBuoyancy) + " Sclater's asymptote describes",
            oldBuoyancy in -600f..600f
        )
        // A margin is a mixture, so its level has to be between the two and to move one way only.
        var previous = columns.altitudeMetres(0f, referenceAge)
        for (step in 1..20) {
            val here = columns.altitudeMetres(step / 20f, referenceAge)
            assertTrue("the margin's level is not monotone in the crust it is made of", here > previous)
            previous = here
        }
        // And the floor sinks with age, monotonically, across the join between the two branches.
        var deepest = columns.altitudeMetres(0f, 0f)
        for (age in 1..isostasy.oldestSeafloorAgeMyr.toInt()) {
            val here = columns.altitudeMetres(0f, age.toFloat())
            assertTrue(
                "sea floor of $age Myr does not lie below floor of one million years younger",
                here <= deepest + 1f
            )
            deepest = here
        }
    }

    // ------------------------------------------------------------------ the flexure

    /**
     * The flexure against its own two limits: a load much broader than the plate's flexural
     * parameter sinks until it floats, and one much narrower than it barely moves.
     *
     * Those are the two ends of `w(k) = L(k) / (dRho g + D k^4)`, and between them is everything the
     * filter is for. The broad limit is Airy's answer, `w = q / (dRho g)`, which for a kilometre of
     * rock at 2,835 kg/m3 against a mantle at 3,300 is 860 m; the narrow limit is nothing at all.
     * Also held: the bend carries no mean, because the filter drops the zero-frequency term and the
     * datum is [Isostasy.Columns]' business, not the plate's.
     */
    @Test
    fun `the flexure sinks a broad load and holds a narrow one up`() {
        val config = WorldGenConfig(seed = 1L, width = 256, height = 256)
        val flexure = Isostasy.Flexure(config)
        val isostasy = config.isostasy
        val alphaKm = flexure.flexuralParameterMetres / 1_000.0
        println(
            "ISOSTASY flexure rigidity %.3e N m, flexural parameter %.0f km, %.1f cells at %d"
                .format(flexure.rigidity, alphaKm, alphaKm / config.cellWidthKm, config.width)
        )
        // Watts (2001) puts a continent's flexural parameter between about 50 and 200 km for the
        // 20-40 km of elastic thickness it carries; outside that the plate is not a continent's.
        assertTrue(
            "a flexural parameter of ${"%.0f".format(alphaKm)} km is not a continent's",
            alphaKm in 50.0..200.0
        )

        val loadMetres = 1_000f
        val airy = loadMetres * isostasy.continentalCrustDensity /
            (isostasy.mantleDensity - isostasy.deflectionFillDensity)

        fun deflectionUnder(halfWidthCells: Int): Float {
            val load = FloatArray(config.width * config.height)
            for (row in 0 until config.height) {
                for (column in 0 until config.width) {
                    val insideAcross = abs(column - config.width / 2) <= halfWidthCells
                    val insideDown = abs(row - config.height / 2) <= halfWidthCells
                    if (insideAcross && insideDown) {
                        load[row * config.width + column] = Isostasy.loadPascals(
                            loadMetres, isostasy.continentalCrustDensity, isostasy.gravity
                        )
                    }
                }
            }
            var mean = 0.0
            flexure.deflectionMetres(load, load)
            load.forEach { mean += it.toDouble() }
            assertEquals(
                "the bend carries a mean, so the filter is moving the world's datum",
                0.0, mean / load.size, 1e-6
            )
            return load[config.height / 2 * config.width + config.width / 2]
        }

        // Thirty-three cells at 256 is 1,550 km across, twenty-three flexural parameters, and still
        // under two per cent of the map — which matters, because the filter carries no
        // zero-frequency term and a load covering a quarter of the world would be measured against
        // its own mean. One cell is 47 km, two thirds of a flexural parameter.
        val broad = deflectionUnder(16)
        val narrow = deflectionUnder(0)
        println(
            ("ISOSTASY flexure a %.0f m load bends the plate %.0f m under a 1,550 km slab and" +
                " %.0f m under a 47 km one; Airy's answer is %.0f m").format(
                loadMetres, broad, narrow, airy
            )
        )
        assertEquals(
            "a load twenty flexural parameters across does not reach Airy's answer",
            airy.toDouble(), broad.toDouble(), airy * 0.05
        )
        assertTrue(
            "a load one flexural parameter across bends the plate ${"%.0f".format(narrow)} m," +
                " which is not the ${"%.0f".format(airy)} m of Airy's answer held back by a plate" +
                " with strength in it",
            narrow < airy * 0.5
        )
    }

    /**
     * A load on one pole does not bend the other one.
     *
     * The world is a cylinder: x wraps and y does not. An FFT is periodic on both axes, so the
     * flexure solved on the map's own grid treats the top row and the bottom row as neighbours,
     * and each pole's ice then holds the other pole's ground down. [Isostasy.Flexure] mirrors the
     * load out to twice the map's height before transforming and crops afterwards, which gives the
     * bottom row a whole meridian of plate between it and the top one.
     *
     * A stripe of a kilometre of crustal rock is laid along row 0 and the bend is read on the row
     * below it, on the far pole, and in the middle of the map. All three are read against the
     * middle, because the filter carries no zero-frequency term and so leaves the whole map sharing
     * one uniform offset; what this clause is about is the part of the bend that depends on where
     * the load was put.
     *
     * The near row's own reading nearly doubles under the mirror, 73 m to 140, and that is the
     * continuation doing its job rather than a side effect: a stripe sitting *on* the pole carries
     * on over it, so the plate there is holding up twice the rock the map alone shows.
     */
    @Test
    fun `a load on one pole does not bend the other`() {
        val config = WorldGenConfig(seed = 1L, width = 512, height = 512)
        val flexure = Isostasy.Flexure(config)
        val isostasy = config.isostasy
        val alphaKm = flexure.flexuralParameterMetres / 1_000.0

        val load = FloatArray(config.width * config.height)
        for (column in 0 until config.width) {
            load[column] = Isostasy.loadPascals(
                POLAR_STRIPE_METRES, isostasy.continentalCrustDensity, isostasy.gravity
            )
        }
        val deflection = FloatArray(load.size)
        flexure.deflectionMetres(load, deflection)

        val middleOfMap = deflection[config.height / 2 * config.width].toDouble()
        val nextToTheLoad =
            deflection[config.width].toDouble() - middleOfMap
        val farPole = deflection[(config.height - 1) * config.width].toDouble() - middleOfMap
        val share = abs(farPole) / abs(nextToTheLoad)
        val poleToPoleKm = (config.height - 1) * config.cellHeightKm
        println(
            ("ISOSTASY poles a %.0f m stripe on row 0 bends row 1 by %.2f m and row %d by %.3e m," +
                " a share of %.3e; the two are %.0f km apart, %.0f flexural parameters of %.0f km")
                .format(
                    POLAR_STRIPE_METRES, nextToTheLoad, config.height - 1, farPole, share,
                    poleToPoleKm, poleToPoleKm / alphaKm, alphaKm
                )
        )
        assertTrue(
            "the row below the load barely moved (${"%.2f".format(nextToTheLoad)} m), so there is" +
                " nothing for the far pole to be measured against",
            abs(nextToTheLoad) > MIN_STRIPE_DEFLECTION_METRES
        )
        assertTrue(
            "a stripe on row 0 bends the far pole by ${"%.3e".format(farPole)} m against the" +
                " ${"%.2f".format(nextToTheLoad)} m it bends the row beside it — a share of" +
                " ${"%.3e".format(share)}, over the bar of $FAR_POLE_SHARE_OF_NEAR_ROW: the" +
                " transform is still periodic in y and the two poles are neighbours",
            share < FAR_POLE_SHARE_OF_NEAR_ROW
        )
    }

    // ------------------------------------------------------------------ the ocean as a consequence

    /**
     * The ocean-coverage slider still means the share of the world under water, and the crust is
     * what delivers it.
     *
     * Two readings, and the gap between them is the measurement. The plate stage draws
     * `(1 - seaLevel) / (1 - continentalCrustSubmergedShare)` of the world as continental crust, so
     * the share of the map standing above the *isostatic datum* — zero metres, which is
     * `WorldScale.shorelineFieldLevel` of the height field — is a consequence of what the crust is.
     * The sea-level percentile then cuts where the slider asks, which is a statement about how much
     * water the planet has and is the one thing isostasy cannot supply. How far the two answers sit
     * apart is what this measures, in metres of sea level, and it is the same residual `UnitsTest`
     * reads off the declared ruler.
     *
     * The control is Earth's own submerged share, 29%, which is the wrong conversion for this
     * generator — its continents are drier than Earth's, for the two reasons
     * `TectonicsConfig.continentalCrustSubmergedShare` sets out — and misses by twice the bar.
     */
    @Test
    fun `the crust draws the ocean the slider asked for`() {
        val worst = ArrayList<Pair<Long, Double>>()
        SEEDS.forEach { seed ->
            val world = worldAt(seed)
            val residual = shorelineResidualMetres(world)
            val isostatic = isostaticLandShare(world)
            worst.add(seed to residual)
            println(
                ("ISOSTASY coverage seed %-6d crust puts %.3f of the world above the datum, the" +
                    " slider asks %.3f, the cut lands %+.0f m from it").format(
                    seed, isostatic, 1f - world.config.seaLevel, residual
                )
            )
        }
        val furthest = worst.maxByOrNull { abs(it.second) }!!
        assertTrue(
            "seed ${furthest.first}: the sea-level cut lands ${"%.0f".format(furthest.second)} m" +
                " from the level isostasy puts the shoreline at, outside the stated" +
                " $SHORELINE_RESIDUAL_BAR_METRES m — the crust the plate stage drew is not the" +
                " crust the ocean-coverage slider asked for",
            abs(furthest.second) <= SHORELINE_RESIDUAL_BAR_METRES
        )

        // The control: the aim told to draw far more continental crust than the slider's coverage
        // needs. Nothing else changes — the same seeds, the same plates, the same erosion — so what
        // it isolates is the conversion itself, and the shoreline has to climb a long way to find
        // 38% of a world that is nearly all continent.
        val control = SEEDS.map { seed ->
            val base = WorldGenConfig(seed = seed, width = 512, height = 512)
            shorelineResidualMetres(
                SharedWorlds.world(
                    base.copy(
                        tectonics = base.tectonics.copy(
                            continentalCrustSubmergedShare = CONTROL_SUBMERGED_SHARE
                        )
                    )
                )
            )
        }
        println(
            "ISOSTASY coverage control at $CONTROL_SUBMERGED_SHARE submerged: " +
                control.joinToString(", ") { "%+.0f m".format(it) }
        )
        assertTrue(
            "the control passes, so the crust fraction the plate stage draws is not what is" +
                " landing the sea-level cut near the datum",
            control.any { abs(it) > SHORELINE_RESIDUAL_BAR_METRES }
        )
    }

    /**
     * The hypsometry has two modes with a trough between them, and does not without isostasy.
     *
     * `EarthLikenessTest` asserts the same two clauses on every standard seed; this is where they
     * are shown to bite, against the world S2 replaced. The figures either way are printed.
     */
    @Test
    fun `the hypsometry is bimodal, and is one mode without the two crusts`() {
        SEEDS.take(3).forEach { seed ->
            val world = worldAt(seed)
            val flat = SharedWorlds.world(
                world.config.copy(isostasy = world.config.isostasy.copy(enabled = false))
            )
            listOf("isostatic" to world, "control" to flat).forEach { (label, measured) ->
                val hypsometry = EarthLikeness.hypsometryOf(measured)
                println(
                    ("ISOSTASY hypsometry seed %-6d %-9s land mode %s, sea mode %s, trough %s of" +
                        " the smaller mode").format(
                        seed, label,
                        hypsometry.landModeMetres?.let { "%.0f m".format(it) } ?: "none",
                        hypsometry.seaModeMetres?.let { "%.0f m".format(it) } ?: "none",
                        hypsometry.troughShareOfSmallerMode?.let { "%.3f".format(it) } ?: "none"
                    )
                )
            }
            val here = EarthLikeness.hypsometryOf(world)
            val there = EarthLikeness.hypsometryOf(flat)
            assertTrue(
                "seed $seed: " + (EarthLikeness.bimodalityComplaint("$seed", here) ?: ""),
                EarthLikeness.bimodalityComplaint("$seed", here) == null
            )
            assertTrue(
                "seed $seed: " + (EarthLikeness.seaModeComplaint("$seed", here) ?: ""),
                EarthLikeness.seaModeComplaint("$seed", here) == null
            )
            assertTrue(
                "seed $seed: the curve is still two modes with a trough with isostasy switched" +
                    " off, so neither clause is measuring the two crusts",
                EarthLikeness.bimodalityComplaint("$seed", there) != null ||
                    EarthLikeness.seaModeComplaint("$seed", there) != null
            )
        }
    }

    // ------------------------------------------------------------------ uplift against erosion

    /**
     * Steady-state relief in a belt goes as uplift over erodibility, which is Whipple and Tucker's
     * result and the reason the two have to run together.
     *
     * `E = K A^m S^n` balanced against an uplift `U` gives `S = (U / (K A^m))^(1/n)`, so a channel's
     * whole relief scales as `(U / K)^(1/n)` — with this model's `n` of 1, in proportion. Measured
     * on a synthetic belt: a strip of ground pushed up at a fixed rate across a flat world, run
     * long enough for the rivers to catch it, its relief read at four uplift rates and four
     * erodibilities.
     *
     * Both sweeps are run because either alone can be passed by accident. Relief that is simply
     * uplift piling up untouched also goes as `U` to the first power, and would pass the first
     * sweep while telling us nothing; what it cannot do is *fall* when the rock is made softer,
     * which is what the second sweep asks. So the erodibility exponent is the guard and the uplift
     * exponent is its corroboration, and the share of the uplift the rivers have taken away by the
     * end is printed beside them.
     */
    @Test
    fun `steady-state relief goes as uplift over erodibility`() {
        val upliftRates = listOf(0.01875f, 0.0375f, 0.075f, 0.15f)
        val erodibilities = listOf(0.5e-6f, 1e-6f, 2e-6f, 4e-6f)

        val byUplift = upliftRates.map { rate -> syntheticBelt(rate, 1e-6f) }
        val byErodibility = erodibilities.map { erodibility -> syntheticBelt(0.0375f, erodibility) }

        upliftRates.zip(byUplift).forEach { (rate, belt) ->
            println(
                "ISOSTASY steady state U=%.4f mm/yr K=1.0e-6: relief %.0f m of %.0f m uplifted (%.2f removed)"
                    .format(rate, belt.reliefMetres, belt.upliftedMetres, belt.removedShare)
            )
        }
        erodibilities.zip(byErodibility).forEach { (erodibility, belt) ->
            println(
                "ISOSTASY steady state U=0.0375 mm/yr K=%.1e: relief %.0f m of %.0f m uplifted (%.2f removed)"
                    .format(erodibility, belt.reliefMetres, belt.upliftedMetres, belt.removedShare)
            )
        }

        val upliftExponent = EarthLikeness.fitLine(
            upliftRates.map { ln(it.toDouble()) },
            byUplift.map { ln(it.reliefMetres) }
        ).slope
        val erodibilityExponent = EarthLikeness.fitLine(
            erodibilities.map { ln(it.toDouble()) },
            byErodibility.map { ln(it.reliefMetres) }
        ).slope
        println(
            "ISOSTASY steady state relief goes as U^%.2f and as K^%.2f, against Whipple & Tucker's"
                .format(upliftExponent, erodibilityExponent) + " 1/n = 1.00 and -1/n = -1.00"
        )

        assertTrue(
            "the belts never reached a state the rivers were working on: the least eroded of them" +
                " kept ${"%.2f".format(1.0 - byUplift.minOf { it.removedShare })} of everything" +
                " pushed into it, so the sweeps below measure uplift piling up and not a balance",
            byUplift.all { it.removedShare > 0.3 } && byErodibility.all { it.removedShare > 0.3 }
        )
        assertTrue(
            "relief goes as K^${"%.2f".format(erodibilityExponent)} where Whipple & Tucker's" +
                " steady state with n = 1 makes it K^-1: outside -1 +/-" +
                " $STREAM_POWER_EXPONENT_TOLERANCE, the belts are not balancing uplift against" +
                " erosion at all",
            abs(erodibilityExponent + 1.0) <= STREAM_POWER_EXPONENT_TOLERANCE
        )
        assertTrue(
            "relief goes as U^${"%.2f".format(upliftExponent)}, outside 1 +/-" +
                " $STREAM_POWER_EXPONENT_TOLERANCE",
            abs(upliftExponent - 1.0) <= STREAM_POWER_EXPONENT_TOLERANCE
        )
    }

    /** What one synthetic belt came out at. */
    private class Belt(
        val reliefMetres: Double,
        val upliftedMetres: Double,
        /** Of everything pushed up, the share the rivers and the hillslopes took away again. */
        val removedShare: Double
    )

    /**
     * A strip of ground pushed up at [upliftMmPerYear] across an otherwise flat world, run until
     * the rivers have caught it, and the relief it settles at.
     *
     * Deliberately not a generated world. What Whipple and Tucker's relation is about is one belt
     * with one uplift rate and one erodibility, and a map's belts have neither: their profiles vary
     * along strike, their catchments differ by an order of magnitude, and their crust pairs push at
     * four different rates. So this is the experiment rather than the world — a flat plain at the
     * waterline with a band raised through it — and the world's own belts are what the render is
     * for.
     *
     * Flexure off and deposition off, because neither is in the relation being tested: the
     * stream-power law is detachment-limited and says nothing about a plate with strength.
     */
    private fun syntheticBelt(upliftMmPerYear: Float, erodibilityPerYear: Float): Belt {
        val rounds = BELT_ROUNDS
        val base = WorldGenConfig(seed = 4242L, width = 128, height = 128)
        val config = base.copy(
            seaLevel = 0.5f,
            // A small world and a short round, because the relation being tested is about one
            // channel and this map's own are the wrong size for it. Steady-state relief is
            // `(U / K) / sqrt(A)` along a channel, and on a 12,000 km world a mid-belt catchment
            // is 10^12 m², so the relief a millimetre a year would hold up is a few centimetres.
            // Two hundred kilometres across puts the catchments where a real orogen's are and the
            // relief in hundreds of metres.
            //
            // Twenty thousand years and sixteen hundred rounds, 32 million years. The round was
            // sized while an explicit update capped each round's bite at half the drop and had to
            // stay inside that cap for the law to be what was read (docs/DESIGN_LEDGER.md, Fix 2
            // and Fix 3). The implicit update has no cap and its steady state is the law's at any
            // round, so the length now matters only for how finely the approach to balance is
            // resolved, and the run keeps the years it was given.
            scale = base.scale.copy(worldWidthKm = 200.0, yearsPerHydraulicRound = BELT_ROUND_YEARS),
            isostasy = base.isostasy.copy(flexure = false),
            erosion = base.erosion.copy(
                hydraulicRounds = rounds,
                bedrockErodibilityPerYear = erodibilityPerYear,
                deposition = false,
                // Neither belongs in the relation. The stream-power law is detachment-limited and
                // says nothing about a hillslope failing at an angle, and the outlet notch is a
                // second rate with a multiplier of its own.
                criticalFallMetresPerKm = 100_000f,
                debrisTravelKm = 0.0,
                outletIncision = false
            ),
            sea = base.sea.copy(lowstandMetres = 0f)
        )
        val cellsAcross = config.width
        val cellsDown = config.height
        val scale = config.scale

        // The plain: the terrain noise alone, a couple of hundred metres of it, half of it under
        // water once the percentile cuts. Nothing tectonic, so the only relief that appears is the
        // relief the band's own uplift and the rivers make between them.
        val noise = TerrainStage.generate(config).height
        val ground = FloatField(cellsAcross, cellsDown)
        for (cell in ground.data.indices) {
            ground.data[cell] = scale.fieldAtAltitude((noise.data[cell] - 0.5f) * PLAIN_RELIEF_METRES)
        }

        // The belt: a band a quarter of the map deep, pushed up at one rate over its whole width.
        val uplift = FloatField(cellsAcross, cellsDown)
        val beltFirstRow = cellsDown * 3 / 8
        val beltLastRow = cellsDown * 5 / 8
        for (row in beltFirstRow until beltLastRow) {
            for (column in 0 until cellsAcross) uplift.data[row * cellsAcross + column] = upliftMmPerYear
        }

        val eroded = erodeBlocking(config, ground, upliftRateMmPerYear = uplift)
        val upliftedMetres =
            upliftMmPerYear.toDouble() * scale.yearsPerHydraulicRound * rounds / 1_000.0

        var beltSum = 0.0
        var beltCells = 0
        var plainSum = 0.0
        var plainCells = 0
        for (row in 0 until cellsDown) {
            val inBelt = row in beltFirstRow until beltLastRow
            for (column in 0 until cellsAcross) {
                val altitude = scale.altitudeAtField(eroded.height.data[row * cellsAcross + column])
                if (inBelt) {
                    beltSum += altitude
                    beltCells++
                } else {
                    plainSum += altitude
                    plainCells++
                }
            }
        }
        val relief = beltSum / beltCells - plainSum / plainCells
        return Belt(relief, upliftedMetres, 1.0 - relief / upliftedMetres)
    }

    // ------------------------------------------------------------------ the loads

    /**
     * The plate bends under what the water moves: a range that is being stripped rebounds, and the
     * ground in front of it goes down under the sediment.
     *
     * One pair of worlds, the same seed with the flexure on and off, read as the altitude
     * difference between them binned by distance from the nearest continental collision. Over the
     * belt itself the difference is positive — the range has lost mass and the plate has answered —
     * and out in the foreland it is negative, which is a foreland basin: DeCelles and Giles' (1996)
     * flexural moat, filled by the orogen's own debris.
     *
     * Earth's are deeper than this, and the reason is worth stating rather than glossing. The
     * Ganges basin holds 5 km of sediment over 300 km and the Alpine molasse 4 km over 100, but
     * those are *thrust* loads: an orogen emplaces its own crust on the undeformed plate beside it,
     * and the plate bends under the weight. This model's orogens do not do that. A belt's height
     * arrives already compensated — the stamp is the surface a thickened column supports, and the
     * uplift the rounds add is more of the same thickening — so the only load the plate ever feels
     * is what the water moves, and the moat that makes is the debris the range sheds rather than
     * the range itself. Measured on seed 42 the belt comes up by 233 to 791 m over its own width
     * and the ground beyond 32 cells goes down by 12 to 71, which is a real basin and a shallow
     * one. Giving the orogen a thrust load of its own means building a belt out of crustal
     * thickness rather than out of a stamped profile, and it is in `TODO.md`.
     */
    /**
     * The uplift rate against the erosion it is racing, which is where the rate came from.
     *
     * England and Molnar (*Surface uplift, uplift of rocks, and exhumation of rocks*, Geology 18,
     * 1990) exist to insist that rock uplift, surface uplift and exhumation are three quantities:
     * rock uplift is surface uplift plus exhumation. The Himalaya's rock rises five millimetres a
     * year and its surface gains about half of one, because the rest comes off as sediment. A model
     * can only copy the *surface* figure; the rock rate it needs is that plus whatever its own
     * rivers and hillslopes remove, which is a property of this grid and this erodibility and has
     * to be measured.
     *
     * So this runs the standard worlds with every uplift rate at zero and the flexure off, and
     * measures what the rounds take off the present belts: the mean lowering of the ground over the
     * collisional and Andean belts' land, over the twelve rounds' years. With the flexure off that
     * lowering is the net denudation and nothing else; with it on, the plate's rebound under the
     * unloading would hide part of it, and the rebound is the third quantity, the flexural response,
     * which the rounds apply to the uplift the setting feeds them and which this derivation keeps
     * out. The rate held is Earth's surface uplift plus that denudation, run rather than remembered.
     */
    @Test
    fun `the collision rate is Earth's surface uplift plus this model's own denudation`() {
        val rates = SEEDS.map { seed ->
            val base = WorldGenConfig(seed = seed, width = 512, height = 512)
            val still = base.copy(
                tectonics = base.tectonics.copy(
                    collisionUpliftMmPerYear = 0f,
                    andeanUpliftMmPerYear = 0f,
                    islandArcUpliftMmPerYear = 0f,
                    riftShoulderUpliftMmPerYear = 0f
                ),
                isostasy = base.isostasy.copy(flexure = false)
            )
            val world = SharedWorlds.world(still)
            val rate = beltDenudationMmPerYear(world)
            println("ISOSTASY denudation seed %d: %.3f mm/yr off an active belt, uplift off"
                .format(seed, rate))
            rate
        }
        val denudation = rates.average()
        val tectonics = WorldGenConfig().tectonics
        val implied = EARTH_COLLISION_SURFACE_UPLIFT_MM_PER_YEAR + denudation
        println(
            ("ISOSTASY denudation pooled %.3f mm/yr; Earth's collision surface uplift %.2f, so the" +
                " rock uplift is %.2f mm/yr against the %.2f the setting carries").format(
                denudation, EARTH_COLLISION_SURFACE_UPLIFT_MM_PER_YEAR, implied,
                tectonics.collisionUpliftMmPerYear
            )
        )
        // Armed again at Fix 3b, whose implicit incision lets the law and not the cap set the
        // denudation this is derived from (docs/DESIGN_LEDGER.md, Fix 3 and Fix 3b).
        assertTrue(
            "the collision uplift rate is not Earth's surface uplift plus what this model's own rivers take off a " +
                "belt: %.3f implied against the %.3f the setting carries".format(implied, tectonics.collisionUpliftMmPerYear),
            abs(implied - tectonics.collisionUpliftMmPerYear) <= UPLIFT_RATE_TOLERANCE_MM_PER_YEAR
        )
        // And the ratios between the four are England & Molnar's, unchanged by the scale above.
        assertEquals(
            "the Andean rate is not England & Molnar's 2-in-5 of the collision rate",
            0.4, tectonics.andeanUpliftMmPerYear / tectonics.collisionUpliftMmPerYear.toDouble(),
            0.02
        )
    }

    /** Metres of rock the rounds took off the present belts, as a rate over the time they stand for. */
    private fun beltDenudationMmPerYear(world: WorldMap): Double {
        val scale = world.config.scale
        val falloff = world.config.tectonics.boundaryFalloffCells
        var sum = 0.0
        var cells = 0
        for (cell in world.sea.isLand.indices) {
            if (!world.sea.isLand[cell]) continue
            if (world.plates.boundaryDistance.data[cell] > falloff) continue
            val pairClass = world.plates.nearestBoundaryClass[cell]
            if (pairClass != BoundaryClass.COLLISION_PLATEAU.ordinal &&
                pairClass != BoundaryClass.ANDEAN_MARGIN.ordinal
            ) continue
            sum += (
                scale.altitudeAtField(world.plates.height.data[cell]) -
                    scale.altitudeAtField(world.erosion.height.data[cell])
                ).toDouble()
            cells++
        }
        if (cells == 0) return 0.0
        val years = scale.yearsPerHydraulicRound * world.config.erosion.hydraulicRounds
        return (sum / cells) * METRES_TO_MILLIMETRES / years
    }

    @Test
    fun `a stripped range rebounds and its foreland sinks`() {
        val seed = 42L
        val world = worldAt(seed)
        val without = SharedWorlds.world(
            world.config.copy(isostasy = world.config.isostasy.copy(flexure = false))
        )
        val scale = world.config.scale
        val cellCount = world.config.width * world.config.height
        val distance = world.plates.boundaryDistance.data
        val boundaryClass = world.plates.nearestBoundaryClass

        val bins = DoubleArray(FLEXURE_BINS)
        val counts = IntArray(FLEXURE_BINS)
        for (cell in 0 until cellCount) {
            if (boundaryClass[cell] != BoundaryClass.COLLISION_PLATEAU.ordinal) continue
            val bin = (distance[cell] / FLEXURE_BIN_CELLS).toInt()
            if (bin >= FLEXURE_BINS) continue
            bins[bin] += (
                scale.altitudeAtField(world.erosion.height.data[cell]) -
                    scale.altitudeAtField(without.erosion.height.data[cell])
                ).toDouble()
            counts[bin]++
        }
        val profile = DoubleArray(FLEXURE_BINS) {
            if (counts[it] == 0) 0.0 else bins[it] / counts[it]
        }
        println(
            "ISOSTASY foreland seed $seed, metres the flexure moved the ground, by distance from" +
                " the collision: " + profile.mapIndexed { bin, metres ->
                "%d-%d cells %+.0f".format(
                    bin * FLEXURE_BIN_CELLS, (bin + 1) * FLEXURE_BIN_CELLS, metres
                )
            }.joinToString(", ")
        )

        val overTheBelt = profile.take(2).average()
        // The moat is a *trough* in the profile and not a negative number in it, and the difference
        // is the whole continent. Erosion strips the land everywhere, so the plate under it rebounds
        // everywhere and the compensating downward bend is out under the ocean where the filter's
        // missing mean has to go; measured against the same world with the flexure off, every bin
        // in front of a belt can be positive and the ground still be bent. What a load does that no
        // uniform rise can is put a *local minimum* between the range it is under and the swell
        // beyond it, which is a moat and a forebulge. So that is what is measured: the lowest bin
        // clear of the belt, and the requirement that the ground comes back up past it.
        val forelandBins = FIRST_FORELAND_BIN..LAST_FORELAND_BIN
        val moatBin = forelandBins.filter { counts[it] > 0 }.minByOrNull { profile[it] }
            ?: FIRST_FORELAND_BIN
        val inTheForeland = profile[moatBin]
        val beyondTheMoat = (moatBin + 1..LAST_FORELAND_BIN)
            .filter { counts[it] > 0 }.maxOfOrNull { profile[it] } ?: inTheForeland
        println(
            ("ISOSTASY foreland seed %d: the belt stands %+.0f m higher, the moat is %.0f m below" +
                " it at %d-%d cells (%.0f-%.0f km) from the suture, and the ground rises %.0f m" +
                " again beyond it").format(
                seed, overTheBelt, overTheBelt - inTheForeland, moatBin * FLEXURE_BIN_CELLS,
                (moatBin + 1) * FLEXURE_BIN_CELLS,
                moatBin * FLEXURE_BIN_CELLS * world.config.cellWidthKm,
                (moatBin + 1) * FLEXURE_BIN_CELLS * world.config.cellWidthKm,
                beyondTheMoat - inTheForeland
            )
        )
        assertTrue(
            "the belt is ${"%.0f".format(overTheBelt)} m higher with the flexure on, not the" +
                " $MIN_REBOUND_METRES m a range that has lost this much rock should rebound by",
            overTheBelt >= MIN_REBOUND_METRES
        )
        assertTrue(
            "the moat in front of the belt lies ${"%.0f".format(overTheBelt - inTheForeland)} m" +
                " below the belt's own rebound, not the $MIN_FORELAND_METRES m a flexed plate" +
                " carries there",
            overTheBelt - inTheForeland >= MIN_FORELAND_METRES
        )
        // Failing since the boundary distance was measured on the ground, and kept running as a
        // known failure rather than widened. On the ground's ruler no ground the collision owns
        // lies further than 53 cell widths from it on seed 42 (13,205 cells in the first bin, 272
        // in the seventh, none in the eighth), and the profile falls through the last foreland
        // bin, so the moat is found at the edge of the window with nothing beyond it to rise.
        // Widening the window would be reading the far field [LAST_FORELAND_BIN] was set to keep
        // out; the plates' shape, queued as its own chunk, is where the collision's ground is decided.
        KnownFailures.expect(
            FORELAND_AT_THE_EDGE_OF_THE_COLLISION,
            "moat at 48-56 cell widths, 586 m under the belt, rising 0 m beyond it"
        ) {
            if (beyondTheMoat - inTheForeland < MIN_FOREBULGE_METRES) {
                throw RecordedViolation(
                    "the ground beyond the moat does not come back up, so what was measured is a slope" +
                        " away from the belt and not a trough — and a trough with a rise beyond it is the" +
                        " one thing no uniform bend can make",
                    String.format(
                        java.util.Locale.ROOT, "moat at %d-%d cell widths, %.0f m under the belt, rising %.0f m beyond it",
                        moatBin * FLEXURE_BIN_CELLS, (moatBin + 1) * FLEXURE_BIN_CELLS,
                        overTheBelt - inTheForeland, beyondTheMoat - inTheForeland
                    )
                )
            }
        }
    }

    /**
     * Ice holds its bed down, by Airy's share of its own thickness.
     *
     * The same world with the ice load on and off. Ice at 917 kg/m3 floating on mantle at 3,300
     * displaces its own weight once the mantle has flowed, so a sheet standing `H` thick presses
     * its bed down by `iceDensity / mantleDensity` of `H` - 27.8% of it, about a third, and the
     * most directly observed number in this whole section: Greenland's bed lies below sea level
     * over most of its interior for exactly this reason, and Scandinavia is still coming back up
     * at a centimetre a year from a load that left ten thousand years ago.
     *
     * A plate does not let the whole of it through. The flexure spreads a load over a few
     * flexural parameters - 67 km here - so a narrow load is held up by the rock beside it and
     * only a load broad against that parameter reaches Airy's answer. The clause therefore asks
     * for between [CAP_SHARE_OF_AIRY_FLOOR] of Airy and all of it, the floor being where a load
     * has stopped being broad, and reads the ratio at the cell that sank furthest rather than
     * pooling, because a sheet's margin is genuinely meant to sink less than its middle.
     *
     * The thickness is `IceSheet`'s Vialov profile, read off the stage's own tally, and it cancels
     * out of the comparison: both worlds carry the ice on the map, since the surface write is not
     * an isostasy setting, so the difference between them is the bed's and the bed's only.
     *
     * I1 restated this clause. S2's version asserted that the ground under the middle of a cap did
     * *not* move, because with only a mask on the map the bend had to be withheld in proportion to
     * the ice or the climate would read a bed as a surface. The ice is on the map now, so the bend
     * is spent in full and the ground the reader sees is the top of it; the withheld-bend clause
     * would now be asserting the opposite of the physics. What survives of it is the moat, which
     * was always the half of it that was about the rock.
     */
    @Test
    fun `ice holds its bed down by Airy's share of its own thickness`() {
        val seed = 7L
        val world = worldAt(seed)
        val without = SharedWorlds.world(
            world.config.copy(isostasy = world.config.isostasy.copy(iceLoad = false))
        )
        val config = world.config
        val scale = config.scale
        // The profile, off the stage's own tally, run on the same ground the engine ran it on.
        // The mask is struck on the provisional climate, which neither world's isostasy setting
        // can reach, so one array describes both.
        val sea = SeaLevelStage.apply(world.erosion.height, config)
        val balance =
            if (config.climate.snowBalance) {
                ClimateStage.provisionalSnowBalance(
                    config, sea, OceanStage.withoutCurrents(config, sea)
                )
            } else null
        var thickness = FloatArray(0)
        var onTheSheet = BooleanArray(0)
        runBlocking {
            GlaciationStage.apply(config, sea, balance, null) { mass ->
                thickness = mass.iceThicknessMetres
                onTheSheet = mass.onTheSheet
            }
        }
        val thickest = thickness.max()
        val airyRatio = config.isostasy.iceDensity / config.isostasy.mantleDensity

        // The edge of the load: ground off the sheet within [MOAT_REACH_FLEXURAL_PARAMETERS]
        // flexural parameters of it on the ground, which is where a load's basin lies. Measured
        // from the sheet with the row scale, so the reach is kilometres and not cells.
        val cellsAcross = world.width
        val cellsDown = world.height
        val fromTheSheet = FloatArray(cellsAcross * cellsDown) { if (onTheSheet[it]) 0f else JumpFloodDistance.INFINITE }
        JumpFloodDistance.run(
            cellsAcross, cellsDown, fromTheSheet, IntArray(cellsAcross * cellsDown) { if (onTheSheet[it]) it else -1 },
            config.cellHeightInCellWidths
        )
        val moatReachCells = MOAT_REACH_FLEXURAL_PARAMETERS * Isostasy.Flexure(config).flexuralParameterMetres /
            1_000.0 / config.cellWidthKm

        var deepestMoat = 0.0
        var deepestUnderIce = 0.0
        var thicknessThere = 0f
        var interiorCells = 0
        var moved = 0
        for (cell in world.sea.relativeElevation.data.indices) {
            if (!world.sea.isLand[cell] || !without.sea.isLand[cell]) continue
            val here = scale.metresAboveShoreline(world.sea.relativeElevation.data[cell])
            val there = scale.metresAboveShoreline(without.sea.relativeElevation.data[cell])
            val down = (there - here).toDouble()
            if (down > 1.0) moved++
            // The interior, where the sheet is at least half as thick as it gets: the margin is
            // supposed to sink less, so reading the ratio there would measure the profile rather
            // than the mantle.
            if (onTheSheet[cell] && thickness[cell] >= thickest * INTERIOR_SHARE_OF_THICKEST) {
                interiorCells++
                if (down > deepestUnderIce) {
                    deepestUnderIce = down
                    thicknessThere = thickness[cell]
                }
            } else if (!onTheSheet[cell] && fromTheSheet[cell] <= moatReachCells && down > deepestMoat) {
                deepestMoat = down
            }
        }
        val realised = if (thicknessThere > 0f) deepestUnderIce / thicknessThere else 0.0
        println(
            ("ISOSTASY ice seed %d: %d cells pressed down; the bed under the cap's %d interior" +
                " cells deepest by %.0f m under %.0f m of ice, a ratio of %.3f against Airy's" +
                " %.3f; the moat deepest by %.0f m against the %.0f m the thickest ice floats out")
                .format(
                    seed, moved, interiorCells, deepestUnderIce, thicknessThere, realised,
                    airyRatio, deepestMoat, thickest * airyRatio
                )
        )
        assertTrue("seed $seed carries no ice, so there is no load to weigh", moved > 0)
        assertTrue(
            "seed $seed grows no sheet with an interior half as thick as its thickest ice, so" +
                " there is nowhere the clause below can be read, and the seed has to be re-picked" +
                " rather than the clause dropped",
            interiorCells > 0 && thicknessThere > 0f
        )
        // Over Airy's own share from Fix 2 to Fix 3 (0.279 against 0.278), inside it at Fix 3, over
        // it on the implicit update before the uplift was re-derived (0.284), and inside it again
        // with the uplift re-derived (docs/DESIGN_LEDGER.md, Fix 2 and Fix 3b).
        assertTrue(
            "the bed under the cap sank ${"%.0f".format(deepestUnderIce)} m under" +
                " ${"%.0f".format(thicknessThere)} m of ice, a ratio of ${"%.3f".format(realised)}," +
                " which is not between ${"%.3f".format(airyRatio * CAP_SHARE_OF_AIRY_FLOOR)} and" +
                " ${"%.3f".format(airyRatio)} - Airy's `iceDensity / mantleDensity` and the share" +
                " of it a plate of this stiffness lets through",
            realised in (airyRatio * CAP_SHARE_OF_AIRY_FLOOR)..airyRatio.toDouble()
        )
        assertTrue(
            "the moat round the ice is ${"%.0f".format(deepestMoat)} m deep, which is not between" +
                " a fifth and the whole of the ${"%.0f".format(thickest * airyRatio)} m the" +
                " thickest ice on this world floats out at",
            deepestMoat in
                (thickest * airyRatio * MOAT_SHARE_OF_AIRY_FLOOR)..(thickest * airyRatio).toDouble()
        )
    }

    /**
     * How far inside the ice every frozen cell stands, in cells, by a two-pass chamfer sweep from
     * the ice-free ground. The same distance the glaciation stage ramps the sheet's thickness over.
     */
    private fun insideTheIce(world: WorldMap, frozen: BooleanArray): FloatArray {
        val cellsAcross = world.width
        val cellsDown = world.height
        val far = (cellsAcross + cellsDown).toFloat()
        val inside = FloatArray(cellsAcross * cellsDown) { if (frozen[it]) far else 0f }
        val diagonal = 1.41421356f
        for (pass in 0..1) {
            val rows = if (pass == 0) 0 until cellsDown else cellsDown - 1 downTo 0
            for (row in rows) {
                val columns = if (pass == 0) 0 until cellsAcross else cellsAcross - 1 downTo 0
                for (column in columns) {
                    val cell = row * cellsAcross + column
                    if (!frozen[cell]) continue
                    var best = inside[cell]
                    for (rowStep in -1..1) {
                        val neighbourRow = row + rowStep
                        if (neighbourRow < 0 || neighbourRow >= cellsDown) continue
                        for (columnStep in -1..1) {
                            if (rowStep == 0 && columnStep == 0) continue
                            val neighbourColumn =
                                (column + columnStep + cellsAcross) % cellsAcross
                            val step = if (rowStep != 0 && columnStep != 0) diagonal else 1f
                            val reached = inside[neighbourRow * cellsAcross + neighbourColumn] + step
                            if (reached < best) best = reached
                        }
                    }
                    inside[cell] = best
                }
            }
        }
        return inside
    }

    private fun worldAt(seed: Long): WorldMap = SharedWorlds.world(
        WorldGenConfig(seed = seed, width = 512, height = 512)
    )

    /** Where the sea-level cut landed, in metres above or below the isostatic datum. */
    private fun shorelineResidualMetres(world: WorldMap): Double =
        world.config.scale.altitudeAtField(world.sea.shorelineHeight).toDouble()

    /**
     * The share of the map the crust alone puts above zero metres, before any water is poured and
     * before any river has cut it: the plate stage's field, not the eroded one.
     */
    private fun isostaticLandShare(world: WorldMap): Double {
        val datum = world.config.scale.shorelineFieldLevel
        var above = 0
        world.plates.height.data.forEach { if (it >= datum) above++ }
        return above.toDouble() / world.plates.height.data.size
    }

    private companion object {
        /** `GeographyAuditTest`'s standard seeds, plus the author's own world. */
        val SEEDS = listOf(7L, 42L, 1234L, 99L, 718106L)

        /**
         * How far the sea-level cut may land from the level isostasy puts the shoreline at, in
         * metres.
         *
         * A thousand, and it is a regression pin rather than a derivation: set above the residuals
         * this generator produces, which the case prints, and below the control's, which misses by
         * about three times as much. What the residual itself is, is a statement about this
         * generator: the crust puts more of the world above the isostatic datum than the slider
         * asks for, so the sea-level cut has to come up to meet it, because this generator's
         * continents drown a smaller share of their own crust than Earth's 29%, for the two reasons
         * `TectonicsConfig.continentalCrustSubmergedShare` sets out; closing it is a change to what
         * a continental interior looks like rather than to the aim. The figures each chunk measured
         * are in docs/DESIGN_LEDGER.md, and four sets of them disagree (Audit III's A-F-DOC-1),
         * which is why none is quoted here.
         */
        const val SHORELINE_RESIDUAL_BAR_METRES = 1_000.0

        /**
         * What the control tells the plate stage a continent drowns, against Earth's 29%.
         *
         * Seven tenths, which asks for `0.38 / 0.3` — the whole world — as continental crust. The
         * clause being shown to bite is the conversion from the ocean-coverage slider to a share of
         * crust, so the control is that conversion set wrong and nothing else.
         */
        const val CONTROL_SUBMERGED_SHARE = 0.7f

        /**
         * The surface uplift an active continental collision manages on Earth, in millimetres a
         * year.
         *
         * Half of one. England and Molnar (Geology 18, 1990) put the Himalaya's *rock* uplift near
         * five and its surface uplift near a half, the rest going out as sediment, and the Southern
         * Alps and Taiwan are the same story at higher rates. A model has to copy the surface
         * figure and add its own exhumation to get the rock figure, which is what the guard above
         * does.
         */
        const val EARTH_COLLISION_SURFACE_UPLIFT_MM_PER_YEAR = 0.5

        /**
         * How far the collision rate may sit from Earth's surface uplift plus the measured
         * denudation, in millimetres a year.
         *
         * Two hundredths: half the spread of the five worlds' own figures, which the case prints.
         * On the implicit incision they run 0.220 to 0.261 mm a year, four hundredths apart; half
         * that is tight enough that the constant cannot drift away from its derivation unnoticed
         * and loose enough that a seed's chaos cannot fail it. By the same rule it was sixteen
         * thousandths under the cap (0.059 to 0.091) and a twentieth on the clock before Fix 3.
         */
        const val UPLIFT_RATE_TOLERANCE_MM_PER_YEAR = 0.02


        /** The known failure the forebulge clause records. See docs/DESIGN_LEDGER.md, Fix 2. */
        const val FORELAND_AT_THE_EDGE_OF_THE_COLLISION =
            "the plates: on the ground's ruler seed 42's foreland falls to the edge of the collision's own ground"

        /** Millimetres in a metre, for the denudation rate above. */
        const val METRES_TO_MILLIMETRES = 1_000.0

        /**
         * The synthetic belt's round, in years, and how many of them it runs: 32 million years in
         * all, long enough for the belt to reach its balance. The round was sized under the
         * explicit update's cap; see [syntheticBelt].
         */
        const val BELT_ROUND_YEARS = 20_000.0
        const val BELT_ROUNDS = 1_600

        /** How much relief the plain under a synthetic belt carries, peak to peak, in metres. */
        const val PLAIN_RELIEF_METRES = 100f

        /**
         * How far a measured stream-power exponent may sit from the one the law predicts.
         *
         * A quarter. The relation is exact only for a single channel at a single catchment area,
         * and what is measured here is the mean relief of a band of ground carrying a whole
         * drainage network whose catchments span three orders of magnitude, over the six hundred
         * rounds `syntheticBelt` runs rather than to convergence. A quarter admits that and still refuses everything the guard
         * is for: an exponent near zero, which is relief that does not answer the rock at all, and
         * an exponent near a half, which is a different `n`.
         */
        const val STREAM_POWER_EXPONENT_TOLERANCE = 0.25

        /** Cells per bin, and how many bins, in the profile away from a collision suture. */
        /** A kilometre of rock, the same load the two-limits clause above uses. */
        const val POLAR_STRIPE_METRES = 1_000f

        /**
         * The least the row beside the stripe may bend for the far pole's reading to mean anything,
         * in metres.
         *
         * Airy's answer for a kilometre of continental rock is 860 m and a one-row stripe is a
         * fifth of a flexural parameter wide, so the plate holds nearly all of it up; the row below
         * read 73 m before the mirror and reads about 140 under it (the clause's own KDoc). Ten
         * metres is well under either and well over nothing, and all this clause needs of it is
         * that the denominator is a real deflection.
         */
        const val MIN_STRIPE_DEFLECTION_METRES = 10.0

        /**
         * How much of the near row's bend the far pole may share.
         *
         * The physics says none at all. A line load on an elastic plate falls off as
         * `exp(-distance / flexuralParameter)`, the parameter is 67 km at Te 30 km, and the two
         * polar rows are 5,988 km apart — eighty-nine parameters, so `exp(-89)` is about `1e-39`
         * of the near row. Nothing near that is representable beside a seventy-metre reading: a
         * float resolves it to about `6e-8` of itself, and the transform's own round trip leaves a
         * few parts in `1e-15`. So the bar is the arithmetic's floor rather than the physics' —
         * a millionth, about sixteen float steps beside the near row — and everything above it is
         * the wrap this clause exists to refuse, which on the unpadded transform is the whole bend.
         */
        const val FAR_POLE_SHARE_OF_NEAR_ROW = 1e-6

        const val FLEXURE_BIN_CELLS = 8
        const val FLEXURE_BINS = 10

        /**
         * The least the flexure must lift a stripped belt and sink its foreland, in metres.
         *
         * Both are floors rather than figures, and both are set well under what was measured so
         * that a chaotic pipeline's run-to-run wander cannot carry them: the pair of worlds this
         * compares differ in one setting, but a coastline that moves by one cell moves a realm, and
         * the two runs are not otherwise pinned to each other.
         */
        const val MIN_REBOUND_METRES = 20.0
        const val MIN_FORELAND_METRES = 20.0

        /**
         * The first bin clear of the belt itself, and the least the ground must rise again beyond
         * the moat for the trough to be a trough.
         *
         * The belt's own half-width is `boundaryFalloffCells`, 26 at 512, so three bins of eight
         * cells is the first ground that is foreland rather than range. The forebulge floor is a
         * tenth of the moat's, because a forebulge is a tenth of a moat: Turcotte and Schubert's
         * solution puts the peripheral swell at about four per cent of the deflection under the
         * load, and Earth's - the Deccan swell in front of the Himalaya, the Ozark dome in front of
         * the Ouachitas - is a few tens of metres against a moat of kilometres.
         */
        const val FIRST_FORELAND_BIN = 3

        /**
         * The last bin a load can still be felt in, so that the moat is looked for where a moat
         * can be and not in the far field.
         *
         * A load's basin and its peripheral swell lie within about three flexural parameters of it
         * (Turcotte & Schubert), which at Te 30 km is some 200 km, or nine cells at 512; the
         * sediment the belt sheds into the moat and the round of rivers that answers each bend
         * carry it further, and measured on seed 42 the profile peaks at 16-24 cells, troughs at
         * 32-48 and rises again by 56. Bin 6 is 48 cells, 1,100 km, five times the reach the
         * closed form gives and comfortably past where the answer is. Beyond it the numbers are
         * the far field of a filter whose mean has been removed — on seed 42 the ground reads
         * +0, -50 and +0 m across bins 7, 8 and 9 — and taking the minimum of *those* as the moat
         * finds a trough with nothing beyond it, which is how this clause failed at S2's fourth
         * pass without the flexure having changed at all.
         */
        const val LAST_FORELAND_BIN = 6
        const val MIN_FOREBULGE_METRES = 2.0

        /**
         * What the moat round an ice sheet may be, as a share of the Airy depression the thickest
         * ice on the world floats out at.
         *
         * The moat is a real bend and the whole of it reaches the surface, but it is measured at
         * the edge of the load rather than under the middle of it, where a plate holding the sheet
         * up from both sides carries part of the weight; a fifth is a floor under that with room
         * for how much ice a given seed happens to grow. The edge is ground off the sheet within
         * [MOAT_REACH_FLEXURAL_PARAMETERS] of it; until Audit III (its A-I10) the moat was the
         * deepest bend on any off-sheet land cell on the map, which is not the edge of anything.
         */
        const val MOAT_SHARE_OF_AIRY_FLOOR = 0.2

        /**
         * How far off a sheet its moat is looked for, in flexural parameters: three, which is where
         * a load's basin and its peripheral swell lie (Turcotte and Schubert), the same reach
         * [LAST_FORELAND_BIN] starts from.
         */
        const val MOAT_REACH_FLEXURAL_PARAMETERS = 3.0

        /**
         * The least of Airy's ratio a plate of this stiffness may let through under a sheet.
         *
         * A load narrower than a flexural parameter is carried by the rock beside it and barely
         * bends the plate at all; one several parameters across bends it by very nearly the whole
         * Airy answer. Half is where a load has stopped being broad against the 67 km parameter
         * this world's 30 km elastic thickness gives, which is a sheet a couple of hundred
         * kilometres across - under that it is an ice cap rather than a sheet.
         */
        const val CAP_SHARE_OF_AIRY_FLOOR = 0.5

        /** How thick, as a share of the thickest ice on the world, counts as a sheet's interior. */
        const val INTERIOR_SHARE_OF_THICKEST = 0.5f
    }
}
