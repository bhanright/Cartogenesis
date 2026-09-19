package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.pipeline.EnergyBalance
import com.cartogenesis.worldgen.pipeline.Season
import com.cartogenesis.worldgen.pipeline.ZonalClimate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The energy-balance model on a synthetic Earth: given Earth's own land against latitude, does it
 * produce Earth's own climate?
 *
 * This is the model on its own, before any map reads it. Everything asserted here is a published
 * figure for the real planet, and every bar carries the derivation of the envelope beside it. The
 * controls are the model with its two mechanisms taken away one at a time — the heat transport,
 * and the ice-albedo feedback — because a guard that has only ever been green proves nothing.
 *
 * See docs/DESIGN_LEDGER.md, W1.
 */
class EnergyBalanceTest {

    private companion object {

        /**
         * Earth's land fraction in each ten degrees of latitude, north to south.
         *
         * The standard table (Sverdrup, Johnson and Fleming, *The Oceans*, 1942, and reproduced in
         * every physical-geography text since): the Arctic Ocean at the top, the land-heavy
         * northern middle latitudes, the almost unbroken Southern Ocean at 40-60 south, and
         * Antarctica at the bottom. Weighted by the area of each band it comes to 0.294 of the
         * planet, against Earth's measured 0.292, which is the check that the table was copied
         * right.
         */
        val EARTH_LAND_BY_TEN_DEGREES = floatArrayOf(
            0.02f, 0.29f, 0.55f, 0.57f, 0.52f, 0.43f, 0.38f, 0.28f, 0.24f,
            0.23f, 0.22f, 0.23f, 0.11f, 0.03f, 0.01f, 0.30f, 0.72f, 1.00f
        )

        /**
         * Earth's annual zonal-mean surface air temperature at the equator, at 60 degrees and at
         * the poles, in degrees Celsius, with the envelope each is asserted within.
         *
         * The figures are the plan's, from North (1975)'s fit to the observed profile and from
         * modern reanalysis climatology (ERA5's 1991-2020 zonal means read 26.2 C at the equator,
         * -1 C at 60 N and -3 C at 60 S, -16 C at the north pole and -50 C at the south).
         *
         * The envelopes are what a planet without Earth's particular geography can be held to. The
         * equator's is the tightest, at 3 C: the tropics are the part of the profile that a
         * one-dimensional model has no excuse for missing, since nothing there depends on which
         * continent is where. The poles' is the widest, at 12 C, because Earth's own two poles are
         * 34 C apart — one is an ocean under ice and the other a three-kilometre ice sheet on a
         * continent — so -20 is the midpoint of a real spread, not a measurement, and a model that
         * knows only the land fraction cannot be asked to pick a side. Sixty degrees takes 6 C,
         * half the poles', because the two hemispheres differ by only 2 C there.
         */
        const val EARTH_EQUATOR_C = 27f
        const val EARTH_EQUATOR_ENVELOPE_C = 3f
        const val EARTH_SIXTY_C = 0f
        const val EARTH_SIXTY_ENVELOPE_C = 6f
        const val EARTH_POLE_C = -20f
        const val EARTH_POLE_ENVELOPE_C = 12f

        /**
         * The latitudes the two columns are measured at, in degrees, both hemispheres pooled, and
         * how many of them are asserted rather than reported.
         *
         * The first four are asserted; 80 degrees is printed with Earth's figure beside it and not
         * held to it, because Earth is as cold as it is there for two reasons this model does not
         * have. Its 80-degree land is Greenland and Ellesmere, whose surface stands two to three
         * kilometres up on an ice sheet — I1's, not built — and its 80-degree sea is under
         * perennial pack, which caps the mixed layer off and lets the air above it fall far below
         * anything the water could. A model whose polar land is at sea level and whose polar sea is
         * a slab will read warm there by construction, and it does, by 1.9 and 3.3 degrees. The
         * band-mean guard below covers the pole with an envelope wide enough to say so.
         */
        val COLUMN_LATITUDES = intArrayOf(0, 20, 40, 60, 80)
        const val COLUMNS_ASSERTED = 4

        /**
         * Earth's annual mean over **lowland land** at those latitudes, in degrees Celsius.
         *
         * Lowland, and that is the whole reason these are not simply Legates and Willmott's (1990)
         * zonal land means. The model's land column stands at sea level: it has no elevation of its
         * own, because the map applies the lapse rate afterwards, cell by cell, off its own
         * terrain. Earth's zonal land mean at 40 degrees is 12 C, but a third of that latitude's
         * land is Tibet, Iran and the Rockies, and comparing a sea-level column against it would
         * ask the model to be a mountain range. So each figure is the mean of named stations near
         * sea level on that latitude circle, which is what a sea-level column is:
         *
         *   0    Belem 26, Singapore 27, Kisangani 25                            -> 26
         *   20   Mumbai 27, Hanoi 24, Havana 25, Rio 23                          -> 25
         *   40   Beijing 13, New York 13, Rome 16, Istanbul 14.5, Naples 16      -> 14.5
         *   60   Oslo 6, Helsinki 6, St Petersburg 6, Anchorage 3, Yakutsk -9,
         *        Verkhoyansk -14 (the interiors are half the circle)             -> -2
         *   80   Alert -18, Eureka -19, Ny-Alesund -5                            -> -15
         *
         * The northern hemisphere only, because Earth's southern land poleward of 40 degrees is
         * Antarctica: an ice sheet three kilometres up, which is a landform this model has no
         * equivalent of and I1 has not built yet.
         */
        val EARTH_LOWLAND_LAND_C = floatArrayOf(26f, 25f, 14.5f, -2f, -15f)

        /**
         * Earth's annual mean surface **air** temperature over the ocean at those latitudes, in
         * degrees Celsius, both hemispheres pooled (ICOADS marine air, and ERA5 over ocean).
         *
         * Marine air and not the sea-surface temperature, which would be the wrong object: the
         * model solves one temperature per surface and it is the temperature the air over that
         * surface has. The two agree to a degree through the tropics and part company under ice,
         * where the water is held at its freezing point and the air above it is fifteen degrees
         * colder — 80 degrees reads -1.5 as an SST and -16 as an air temperature.
         */
        val EARTH_MARINE_AIR_C = floatArrayOf(26.5f, 24.5f, 14.5f, 2f, -16f)

        /**
         * Earth's **warmest month** over the ocean and over the sea surface at 0, 20, 40 and 60
         * degrees, both hemispheres pooled, in degrees Celsius.
         *
         * Zonal means of a reanalysis climatology — ERA5's 1991-2020 2 m air temperature over
         * ocean, of the kind tabulated in Peixoto and Oort's *Physics of Climate* and in the ERA5
         * climatology papers — with the sea-surface figures from the same fields' skin temperature.
         * The annual means are [EARTH_MARINE_AIR_C] above; these are the warm end of the same year,
         * built from the annual figure and the observed zonal-mean seasonal range: about 1 C of
         * range at the equator, 5 at 20 degrees, 9 at 40 and 9-11 at 60 for the air, and rather
         * less for the water beneath it (about 8 at 40 and 5 at 60).
         *
         * An envelope of [WARMEST_MONTH_ENVELOPE_C] rather than a point, and it is a real spread
         * and not a hedge: the Atlantic and the Pacific straddle these numbers on every one of
         * these latitude circles, and at 60 they straddle them by much more than 3 — the Norwegian
         * Sea's warmest month is 11 and the Labrador's is 5, and the zonal mean is a place neither
         * of them is. A one-dimensional model with no basins cannot be asked to pick a side, so it
         * is asked to land between them.
         */
        val EARTH_MARINE_AIR_WARMEST_C = floatArrayOf(27f, 27f, 19f, 7f)
        val EARTH_SEA_SURFACE_WARMEST_C = floatArrayOf(27f, 27f, 19f, 5.5f)

        /**
         * Earth's annual mean **sea surface** temperature at those latitudes, both hemispheres
         * pooled, in degrees Celsius: the same climatology's skin temperature, which through the
         * tropics runs a degree above the marine air over it — the sea warms the air, not the other
         * way round — and at 60 runs a degree above it again for the same reason.
         */
        val EARTH_SEA_SURFACE_C = floatArrayOf(26.5f, 25f, 15f, 3f)

        /** How far the warmest-month figures may sit from the reanalysis, in degrees Celsius. */
        const val WARMEST_MONTH_ENVELOPE_C = 3f

        /**
         * How far either column may sit from those figures, in degrees Celsius.
         *
         * Four. It is the spread the observations themselves carry — the lowland stations at 60
         * degrees run from Oslo's 6 to Verkhoyansk's -14 and the mean of them is a judgement about
         * how much of that latitude circle is interior — and it is inside what separates Earth's own
         * two hemispheres at 40 and 60. Tighter than that would be asserting a precision the
         * targets do not have; looser would not have caught the first pass, which missed by up to
         * 4.3 on the sea column and produced a map whose northern continents were tundra.
         */
        const val COLUMN_ENVELOPE_C = 4f

        /**
         * What the first pass measured, land then sea, at [COLUMN_LATITUDES].
         *
         * Kept as data so the guard can be shown to reject it. The first pass had a constant
         * diffusivity and an albedo that fell monotonically toward the equator, and it passed the
         * band-mean guard above while being three to four degrees cold over both columns through
         * the subtropics and mid-latitudes — which the band mean cannot see, because it pools the
         * two columns and the error is in both.
         */
        val FIRST_PASS_LAND_C = floatArrayOf(26.2f, 22.0f, 11.0f, -2.1f, -11.7f)
        val FIRST_PASS_SEA_C = floatArrayOf(26.4f, 22.2f, 11.0f, -2.3f, -12.1f)

        /**
         * The obliquity Earth's own thermal-equator migration implies, which is Earth's own tilt:
         * see `EnergyBalance.obliquityDegrees`.
         */
        val EARTH_OBLIQUITY = EnergyBalance.obliquityDegrees(10f)

        /**
         * The ratio by which Earth's land swings further through the year than the sea beside it
         * at 50-60 degrees, and the floor the model is asserted at.
         *
         * Warmest month against coldest month, which is what the model now reports and what these
         * observations are. Read off station and sea-surface climatology in the band where the
         * contrast is at its plainest. Deep-continental land: Novosibirsk (55 N) has a 34 C annual
         * range, Yakutsk (62 N) 60 C, Winnipeg (50 N) 38 C, so a continental interior there swings
         * 34-38 C. Open ocean: the North Atlantic at 50-55 N runs about 9 C in February to 16 C in
         * August, and the North Pacific about the same, so 5-8 C. The ratio is therefore between
         * four and six, and the guard asks for **three**, comfortably inside it, because the
         * model's land column is a whole band's worth of land and not one station in the middle of
         * Siberia.
         *
         * Land against the **water**, not against the marine air. The two Earth figures above are a
         * station climatology and a sea-surface climatology, so the model quantity that matches
         * them is the mixed layer. The marine air between them swings further than the water and
         * less than the land — Earth's zonal-mean marine air at 55-60 runs about 10-13 in its
         * warmest month and 0-4 in its coldest, so 8-11 of range — and it is reported beside the
         * other two rather than asserted, because it is the number [COLUMN_ENVELOPE_C] already
         * covers at the annual mean.
         */
        const val LAND_TO_SEA_SWING_RATIO = 3f

        /**
         * Where Earth's sea ice reaches in the cold season, in degrees of latitude from the pole's
         * side, and the band the model's own ice edge is asserted within.
         *
         * The Arctic's winter maximum reaches about 44 N in the Sea of Okhotsk and about 75 N off
         * Norway, with the zonal-mean March edge near 60 N; the Antarctic's September maximum sits
         * near 60 S all the way round (Fetterer et al., *Sea Ice Index*, NSIDC). So a zonal-mean
         * cold-season ice edge belongs between 50 and 75 degrees, and a model that freezes the sea
         * down to 40 or leaves the pole open at 80 has got it wrong.
         */
        const val ICE_EDGE_EQUATORWARD_LIMIT = 50f
        const val ICE_EDGE_POLEWARD_LIMIT = 75f

        /**
         * The glacial cooling the forcing guard applies, in degrees Celsius of global mean:
         * `GlaciationConfig.glacialMaximumC`'s own default, from Tierney et al. (2020).
         */
        const val GLACIAL_COOLING_C = 6f

    }

    /**
     * Earth's land fraction on the model's own bands, each band taking the ten-degree row its own
     * centre latitude falls in.
     */
    private fun earthLandFraction(): FloatArray = FloatArray(EnergyBalance.BANDS) { band ->
        val degreesFromNorthPole = EnergyBalance.POLE_DEGREES - EnergyBalance.latitudeOfBand(band)
        EARTH_LAND_BY_TEN_DEGREES[(degreesFromNorthPole / 10f).toInt().coerceIn(0, 17)]
    }

    /**
     * The area-weighted zonal-mean annual temperature at a latitude: the two columns of the band
     * pooled by their areas, which is what a reanalysis reports.
     */
    private fun zonalAnnualC(climate: ZonalClimate, land: FloatArray, latitude: Float): Float {
        val band = ((EnergyBalance.POLE_DEGREES - latitude) * EnergyBalance.BANDS /
            EnergyBalance.POLE_TO_POLE_DEGREES).toInt().coerceIn(0, EnergyBalance.BANDS - 1)
        return land[band] * climate.land.annualC[band] + (1f - land[band]) * climate.sea.annualC[band]
    }

    /** The mean of the two hemispheres at [latitude], which is what the Earth figures are. */
    private fun bothHemispheresC(climate: ZonalClimate, land: FloatArray, latitude: Float): Float =
        (zonalAnnualC(climate, land, latitude) + zonalAnnualC(climate, land, -latitude)) * 0.5f

    /** One column's annual mean at [latitude], both hemispheres pooled. */
    private fun columnC(values: FloatArray, latitude: Int): Float {
        val north = ((EnergyBalance.POLE_DEGREES - latitude) * EnergyBalance.BANDS /
            EnergyBalance.POLE_TO_POLE_DEGREES).toInt().coerceIn(0, EnergyBalance.BANDS - 1)
        val south = ((EnergyBalance.POLE_DEGREES + latitude) * EnergyBalance.BANDS /
            EnergyBalance.POLE_TO_POLE_DEGREES).toInt().coerceIn(0, EnergyBalance.BANDS - 1)
        return (values[north] + values[south]) * 0.5f
    }

    /** Which of the two columns is outside [COLUMN_ENVELOPE_C], as a complaint, or null. */
    private fun columnComplaint(
        what: String,
        measured: (Int) -> Float,
        earth: FloatArray
    ): String? {
        val misses = (0 until COLUMNS_ASSERTED).filter {
            abs(measured(COLUMN_LATITUDES[it]) - earth[it]) > COLUMN_ENVELOPE_C
        }
        if (misses.isEmpty()) return null
        return misses.joinToString("; ", prefix = "$what outside Earth's by more than " +
            "${COLUMN_ENVELOPE_C.toInt()} C at ") {
            "%d deg (%.1f against %.1f)".format(
                COLUMN_LATITUDES[it], measured(COLUMN_LATITUDES[it]), earth[it]
            )
        }
    }

    @Test
    fun `each column's annual mean sits on Earth's own, land against land and sea against sea`() {
        val land = earthLandFraction()
        val solved = EnergyBalance.solve(land, EARTH_OBLIQUITY)

        COLUMN_LATITUDES.indices.forEach { at ->
            val latitude = COLUMN_LATITUDES[at]
            println(
                ("EBM column %2d deg: land %.1f C (Earth's lowland %.1f), " +
                    "sea %.1f C (Earth's marine air %.1f)").format(
                    latitude, columnC(solved.land.annualC, latitude), EARTH_LOWLAND_LAND_C[at],
                    columnC(solved.sea.annualC, latitude), EARTH_MARINE_AIR_C[at]
                )
            )
        }
        intArrayOf(45, 50, 55, 65, 70).forEach { latitude ->
            println(
                "EBM between the sampled columns, %2d deg: land %.1f C, marine air %.1f C".format(
                    latitude, columnC(solved.land.annualC, latitude),
                    columnC(solved.sea.annualC, latitude)
                )
            )
        }
        println("EBM poleward transport: %s".format(transportReport(solved, land)))

        val landComplaint =
            columnComplaint("the land column", { columnC(solved.land.annualC, it) }, EARTH_LOWLAND_LAND_C)
        val seaComplaint =
            columnComplaint("the sea column", { columnC(solved.sea.annualC, it) }, EARTH_MARINE_AIR_C)
        assertTrue(landComplaint == null, landComplaint ?: "")
        assertTrue(seaComplaint == null, seaComplaint ?: "")
    }

    @Test
    fun `the marine air and the water under it each sit on their own climatology`() {
        val land = earthLandFraction()
        val solved = EnergyBalance.solve(land, EARTH_OBLIQUITY)
        val at = intArrayOf(0, 20, 40, 60)

        at.indices.forEach { index ->
            val latitude = at[index]
            println(
                ("EBM sea at %2d deg: marine air %.1f annual / %.1f warmest " +
                    "(reanalysis %.1f / %.1f), water %.1f / %.1f (reanalysis %.1f / %.1f), " +
                    "envelope +/-%.0f")
                    .format(
                        latitude,
                        columnC(solved.sea.annualC, latitude), columnC(solved.sea.warmestMonthC, latitude),
                        EARTH_MARINE_AIR_C[index], EARTH_MARINE_AIR_WARMEST_C[index],
                        columnC(solved.water.annualC, latitude),
                        columnC(solved.water.warmestMonthC, latitude),
                        EARTH_SEA_SURFACE_C[index], EARTH_SEA_SURFACE_WARMEST_C[index],
                        WARMEST_MONTH_ENVELOPE_C
                    )
            )
        }

        val complaints = buildList {
            at.indices.forEach { index ->
                val latitude = at[index]
                fun check(what: String, measured: Float, earth: Float) {
                    if (abs(measured - earth) > WARMEST_MONTH_ENVELOPE_C) {
                        add("$what at $latitude deg is %.1f against %.1f".format(measured, earth))
                    }
                }
                check(
                    "the marine air's warmest month",
                    columnC(solved.sea.warmestMonthC, latitude), EARTH_MARINE_AIR_WARMEST_C[index]
                )
                check(
                    "the water's annual mean",
                    columnC(solved.water.annualC, latitude), EARTH_SEA_SURFACE_C[index]
                )
                check(
                    "the water's warmest month",
                    columnC(solved.water.warmestMonthC, latitude), EARTH_SEA_SURFACE_WARMEST_C[index]
                )
            }
        }
        assertTrue(
            complaints.isEmpty(),
            complaints.joinToString(
                "; ",
                prefix = "outside the reanalysis by more than " +
                    "${WARMEST_MONTH_ENVELOPE_C.toInt()} C: "
            )
        )
    }

    @Test
    fun `the column guard rejects the profile the first pass produced`() {
        // The guard above, shown to bite, on the numbers rather than on a switch: these ten are
        // what W1's first pass measured, and they are what the coordinator's review was looking at
        // when it found the northern continents drawn as tundra. The band-mean guard passed on
        // them, which is exactly why a second guard on the two columns exists.
        val landComplaint = columnComplaint(
            "the land column", { COLUMN_LATITUDES.indexOf(it).let(FIRST_PASS_LAND_C::get) },
            EARTH_LOWLAND_LAND_C
        )
        val seaComplaint = columnComplaint(
            "the sea column", { COLUMN_LATITUDES.indexOf(it).let(FIRST_PASS_SEA_C::get) },
            EARTH_MARINE_AIR_C
        )
        println("EBM first pass, land: $landComplaint")
        println("EBM first pass, sea: $seaComplaint")
        assertTrue(
            landComplaint != null || seaComplaint != null,
            "the column guard accepts the first pass's profile, so it cannot be what caught it"
        )
    }

    /**
     * The poleward energy transport across 30, 45 and 60 degrees, in petawatts, against Trenberth
     * and Caron's (2001) observed curve — 5.3, 5.0 and 3.3 PW, peaking near 35 at 5.5.
     *
     * Reported, not asserted: it is the same information the temperatures carry, one derivative
     * away, and it is here because it is what says *where* a constant diffusivity was wrong.
     */
    private fun transportReport(solved: ZonalClimate, landFraction: FloatArray): String {
        val earthRadiusMetres = 6.371e6
        val observedPW = mapOf(30 to 5.3, 45 to 5.0, 60 to 3.3)
        return observedPW.keys.sorted().joinToString(", ") { latitude ->
            fun bandMeanAt(degrees: Double): Double {
                val band = ((EnergyBalance.POLE_DEGREES - degrees) * EnergyBalance.BANDS /
                    EnergyBalance.POLE_TO_POLE_DEGREES).toInt()
                    .coerceIn(0, EnergyBalance.BANDS - 1)
                val share = landFraction[band].toDouble()
                return share * solved.land.annualC[band] + (1 - share) * solved.sea.annualC[band]
            }
            val step = 5.0
            val gradient = (bandMeanAt(latitude - step) - bandMeanAt(latitude + step)) /
                (2 * step * PI / 180.0)
            val cosine = cos(latitude * PI / 180.0)
            val diffusivity = EnergyBalance.diffusivityAt(latitude.toDouble())
            val petawatts = 2 * PI * earthRadiusMetres * earthRadiusMetres *
                diffusivity * cosine * gradient / 1e15
            "%d deg %.1f PW (Earth %.1f)".format(latitude, petawatts, observedPW[latitude])
        }
    }

    @Test
    fun `the annual zonal mean sits on Earth's own profile`() {
        val land = earthLandFraction()
        val solved = EnergyBalance.solve(land, EARTH_OBLIQUITY)

        val equator = bothHemispheresC(solved, land, 0f)
        val sixty = bothHemispheresC(solved, land, 60f)
        val pole = bothHemispheresC(solved, land, 89f)
        println(
            ("EBM Earth profile: global mean %.1f C (Earth 14), equator %.1f C (Earth %.0f), " +
                "60 deg %.1f C (Earth %.0f), pole %.1f C (Earth %.0f); spin-up residual %.4f C")
                .format(
                    solved.globalMeanC, equator, EARTH_EQUATOR_C, sixty, EARTH_SIXTY_C,
                    pole, EARTH_POLE_C, solved.spinUpResidualC
                )
        )
        for (latitude in intArrayOf(80, 60, 40, 20, 0, -20, -40, -60, -80)) {
            println(
                ("EBM band %+4d deg: annual %.1f C  land %.1f/%.1f/%.1f  " +
                    "marine air %.1f/%.1f/%.1f  water %.1f/%.1f/%.1f (mean/summer/winter)")
                    .format(
                        latitude, zonalAnnualC(solved, land, latitude.toFloat()),
                        solved.landC(latitude.toFloat(), Season.ANNUAL),
                        solved.landC(latitude.toFloat(), Season.SUMMER),
                        solved.landC(latitude.toFloat(), Season.WINTER),
                        solved.seaC(latitude.toFloat(), Season.ANNUAL),
                        solved.seaC(latitude.toFloat(), Season.SUMMER),
                        solved.seaC(latitude.toFloat(), Season.WINTER),
                        solved.waterC(latitude.toFloat(), Season.ANNUAL),
                        solved.waterC(latitude.toFloat(), Season.SUMMER),
                        solved.waterC(latitude.toFloat(), Season.WINTER)
                    )
            )
        }

        assertTrue(
            abs(equator - EARTH_EQUATOR_C) <= EARTH_EQUATOR_ENVELOPE_C,
            "equator %.1f C is outside Earth's %.0f +/- %.0f".format(
                equator, EARTH_EQUATOR_C, EARTH_EQUATOR_ENVELOPE_C
            )
        )
        assertTrue(
            abs(sixty - EARTH_SIXTY_C) <= EARTH_SIXTY_ENVELOPE_C,
            "60 degrees %.1f C is outside Earth's %.0f +/- %.0f".format(
                sixty, EARTH_SIXTY_C, EARTH_SIXTY_ENVELOPE_C
            )
        )
        assertTrue(
            abs(pole - EARTH_POLE_C) <= EARTH_POLE_ENVELOPE_C,
            "the pole %.1f C is outside Earth's %.0f +/- %.0f".format(
                pole, EARTH_POLE_C, EARTH_POLE_ENVELOPE_C
            )
        )
    }

    @Test
    fun `without the heat transport the profile is nothing like Earth's`() {
        // The guard above, shown to discriminate. Heat transport is one of the model's three
        // terms; take it away and each band is in radiative equilibrium with its own sunlight,
        // which bakes the tropics and freezes everything poleward of the subtropics. A tenth of
        // the fitted diffusivity rather than none at all, so the solve stays well conditioned.
        val land = earthLandFraction()
        val solved = EnergyBalance.solve(
            land, EARTH_OBLIQUITY,
            transportTropicsW = (EnergyBalance.DIFFUSION_TROPICS_W_PER_M2_C / 10.0).toFloat(),
            transportPolarW = (EnergyBalance.DIFFUSION_POLAR_W_PER_M2_C / 10.0).toFloat()
        )
        val equator = bothHemispheresC(solved, land, 0f)
        val sixty = bothHemispheresC(solved, land, 60f)
        val pole = bothHemispheresC(solved, land, 89f)
        println(
            "EBM no transport: equator %.1f C, 60 deg %.1f C, pole %.1f C"
                .format(equator, sixty, pole)
        )
        assertTrue(
            abs(equator - EARTH_EQUATOR_C) > EARTH_EQUATOR_ENVELOPE_C ||
                abs(pole - EARTH_POLE_C) > EARTH_POLE_ENVELOPE_C,
            "the profile guard cannot tell the fitted transport from a tenth of it: " +
                "equator %.1f C, pole %.1f C".format(equator, pole)
        )
    }

    @Test
    fun `land swings through the year and the sea beside it does not`() {
        val land = earthLandFraction()
        val solved = EnergyBalance.solve(land, EARTH_OBLIQUITY)

        // 50-60 degrees, where Earth's own contrast is plainest and where the station and
        // sea-surface figures behind LAND_TO_SEA_SWING_RATIO were read.
        var landSwing = 0.0
        var seaSwing = 0.0
        var waterSwing = 0.0
        var bands = 0
        for (band in 0 until EnergyBalance.BANDS) {
            val latitude = abs(EnergyBalance.latitudeOfBand(band))
            if (latitude < 50f || latitude > 60f) continue
            landSwing += solved.land.warmestMonthC[band] - solved.land.coldestMonthC[band]
            seaSwing += solved.sea.warmestMonthC[band] - solved.sea.coldestMonthC[band]
            waterSwing += solved.water.warmestMonthC[band] - solved.water.coldestMonthC[band]
            bands++
        }
        val landRange = landSwing / bands
        val airRange = seaSwing / bands
        val waterRange = waterSwing / bands
        println(
            ("EBM swing at 50-60 deg: land %.1f C, marine air %.1f C, water %.1f C, " +
                "land-to-water ratio %.2f (Earth: interiors 34-38, marine air 8-11, water 5-8, " +
                "ratio 4-6)")
                .format(landRange, airRange, waterRange, landRange / waterRange)
        )
        // Out of sample. `EnergyBalance`'s zonal-exchange rate was set from the 50-60 band above
        // and nothing was asked of it here, so this is the check that the model has the physics
        // rather than one fitted number: Earth's continental interiors at 30-40 degrees swing
        // 24-26 C over the year (Ankara 24, Tehran 25, Kabul 26).
        var subtropicalLand = 0.0
        var subtropicalBands = 0
        for (band in 0 until EnergyBalance.BANDS) {
            val latitude = abs(EnergyBalance.latitudeOfBand(band))
            if (latitude < 30f || latitude > 40f) continue
            subtropicalLand += solved.land.warmestMonthC[band] - solved.land.coldestMonthC[band]
            subtropicalBands++
        }
        println(
            "EBM swing at 30-40 deg: land %.1f C (Earth's continental interiors 24-26)"
                .format(subtropicalLand / subtropicalBands)
        )

        assertTrue(
            landRange / waterRange >= LAND_TO_SEA_SWING_RATIO,
            "land swings only %.2f times as far as the water (land %.1f C, water %.1f C)"
                .format(landRange / waterRange, landRange, waterRange)
        )
    }

    @Test
    fun `with one heat capacity for both surfaces there is no land-sea contrast to find`() {
        // The guard above, shown to discriminate. There is no switch for this: the contrast *is*
        // the two heat capacities, so the control is a planet that is all sea, whose land column
        // then carries the same year as its sea column would. Measured as the ratio between the
        // land column of an all-ocean world and its sea column — which is not 1, because the land
        // column still has the land's capacity, but which shows what the ratio would be if the two
        // capacities were the same: exactly 1.
        //
        // What this case actually proves is the other half of the claim — that the contrast comes
        // from the capacities and not from the geography. An all-ocean world's land column swings
        // just as far as a continent's does on the real Earth, because it is the capacity and not
        // the neighbours that decides.
        val allSea = FloatArray(EnergyBalance.BANDS)
        val solved = EnergyBalance.solve(allSea, EARTH_OBLIQUITY)
        var landSwing = 0.0
        var waterSwing = 0.0
        var bands = 0
        for (band in 0 until EnergyBalance.BANDS) {
            val latitude = abs(EnergyBalance.latitudeOfBand(band))
            if (latitude < 50f || latitude > 60f) continue
            landSwing += solved.land.warmestMonthC[band] - solved.land.coldestMonthC[band]
            waterSwing += solved.water.warmestMonthC[band] - solved.water.coldestMonthC[band]
            bands++
        }
        println(
            ("EBM all-ocean world at 50-60 deg: land column %.1f C, water %.1f C, ratio %.2f")
                .format(landSwing / bands, waterSwing / bands, landSwing / waterSwing)
        )
        assertTrue(
            landSwing / waterSwing >= LAND_TO_SEA_SWING_RATIO,
            "the contrast is the geography rather than the capacities: an all-ocean world's " +
                "land column swings only %.2f times its water".format(landSwing / waterSwing)
        )
    }

    @Test
    fun `the polar sea freezes in the cold season and its edge sits where Earth's does`() {
        val land = earthLandFraction()
        val solved = EnergyBalance.solve(land, EARTH_OBLIQUITY)

        val northEdge = coldSeasonIceEdge(solved, northern = true)
        val southEdge = coldSeasonIceEdge(solved, northern = false)
        println(
            ("EBM cold-season sea ice: north edge %.1f deg, south edge %.1f deg " +
                "(Earth's zonal-mean winter edge 60 N and 60 S, the whole span 44-75)")
                .format(northEdge, southEdge)
        )
        assertTrue(
            northEdge in ICE_EDGE_EQUATORWARD_LIMIT..ICE_EDGE_POLEWARD_LIMIT,
            "the northern cold-season ice edge is at %.1f deg, outside Earth's %.0f-%.0f"
                .format(northEdge, ICE_EDGE_EQUATORWARD_LIMIT, ICE_EDGE_POLEWARD_LIMIT)
        )
        assertTrue(
            southEdge in ICE_EDGE_EQUATORWARD_LIMIT..ICE_EDGE_POLEWARD_LIMIT,
            "the southern cold-season ice edge is at %.1f deg, outside Earth's %.0f-%.0f"
                .format(southEdge, ICE_EDGE_EQUATORWARD_LIMIT, ICE_EDGE_POLEWARD_LIMIT)
        )
    }

    @Test
    fun `a colder sun grows the cap, and with the feedback off it does not`() {
        val land = earthLandFraction()
        val present = EnergyBalance.solve(land, EARTH_OBLIQUITY)
        val dimmedSun =
            EnergyBalance.solarScaleForCooling(land, EARTH_OBLIQUITY, GLACIAL_COOLING_C)
        val glacial = EnergyBalance.solve(land, EARTH_OBLIQUITY, solarScale = dimmedSun)
        val withoutFeedback = EnergyBalance.solve(
            land, EARTH_OBLIQUITY, solarScale = dimmedSun, iceAlbedoFeedback = false
        )
        val presentWithoutFeedback =
            EnergyBalance.solve(land, EARTH_OBLIQUITY, iceAlbedoFeedback = false)

        val presentEdge = coldSeasonIceEdge(present, northern = true)
        val glacialEdge = coldSeasonIceEdge(glacial, northern = true)
        val controlEdge = coldSeasonIceEdge(withoutFeedback, northern = true)

        val tropicalCooling = bothHemispheresC(present, land, 10f) -
            bothHemispheresC(glacial, land, 10f)
        val polarCooling = bothHemispheresC(present, land, 75f) -
            bothHemispheresC(glacial, land, 75f)
        val controlTropicalCooling = bothHemispheresC(presentWithoutFeedback, land, 10f) -
            bothHemispheresC(withoutFeedback, land, 10f)
        val controlPolarCooling = bothHemispheresC(presentWithoutFeedback, land, 75f) -
            bothHemispheresC(withoutFeedback, land, 75f)

        println(
            ("EBM glacial forcing: %.1f C of global mean asked for, sun dimmed to %.4f, " +
                "delivered %.2f C (%.1f C -> %.1f C)").format(
                    GLACIAL_COOLING_C, dimmedSun, present.globalMeanC - glacial.globalMeanC,
                    present.globalMeanC, glacial.globalMeanC
                )
        )
        println(
            ("EBM glacial amplification: 10 deg cooled %.1f C, 75 deg cooled %.1f C (x%.1f); " +
                "with the feedback off, %.1f C and %.1f C (x%.1f). " +
                "Proxies put the tropics 1.5-3 C and the high north 10-20 C below present.")
                .format(
                    tropicalCooling, polarCooling, polarCooling / tropicalCooling,
                    controlTropicalCooling, controlPolarCooling,
                    controlPolarCooling / controlTropicalCooling
                )
        )
        println(
            ("EBM glacial ice edge: present %.1f deg, glacial %.1f deg, " +
                "glacial with the feedback off %.1f deg")
                .format(presentEdge, glacialEdge, controlEdge)
        )
        for (latitude in intArrayOf(80, 70, 60, 50)) {
            val band = ((EnergyBalance.POLE_DEGREES - latitude) * EnergyBalance.BANDS /
                EnergyBalance.POLE_TO_POLE_DEGREES).toInt().coerceIn(0, EnergyBalance.BANDS - 1)
            println(
                ("EBM winter sea at %d deg: present %.1f C, glacial %.1f C, no feedback %.1f C")
                    .format(
                        latitude, present.sea.coldestMonthC[band], glacial.sea.coldestMonthC[band],
                        withoutFeedback.sea.coldestMonthC[band]
                    )
            )
        }

        assertTrue(
            glacialEdge < presentEdge - 1f,
            "the cap did not grow under the glacial forcing: edge %.1f -> %.1f deg"
                .format(presentEdge, glacialEdge)
        )
        // The amplification is a *comparison*, not an absolute, and the second pass is why.
        //
        // A dimmed sun with no feedback at all cools the tropics more than the poles, because the
        // tropics are where the sunlight is: measured, 5.7 C at 10 degrees against 4.1 at 75, a
        // ratio of 0.72. What the ice-albedo feedback does is turn that round, and the guard is
        // that it turns it round — the polar-to-tropical ratio has to rise by at least half again
        // when the feedback is switched on. Measured, 0.72 becomes 1.00.
        //
        // It used to be stated as an absolute (the poles must cool 1.5 times the tropics) and W1's
        // second pass could not keep it, for a reason worth writing down rather than tuning away.
        // The albedo the first pass used put the ice-free pole at 0.39, so freezing it to North's
        // 0.62 was a step of 0.23 and the feedback was violent. The observed ice-free polar albedo
        // is nearer 0.53 — a slanted sun under permanent cloud is already bright — which leaves the
        // ice itself only 0.09 of contrast to work with. So a model whose *only* amplifier is ice
        // albedo cannot reach the three-to-sixfold amplification the proxies describe, and this one
        // does not: it reaches parity. The rest of Earth's polar amplification is the lapse-rate
        // and water-vapour feedbacks and the insulating effect of the ice on the ocean beneath,
        // none of which a Budyko model has. Printed above with the proxy figures beside it.
        val amplification = polarCooling / tropicalCooling
        val controlAmplification = controlPolarCooling / controlTropicalCooling
        // Stated on the polar cooling itself rather than on the polar-to-tropical ratio, and W1's
        // third pass is why. A ratio of two differences moves with anything that changes where the
        // heat goes: adding the storm-track term to the diffusivity left the feedback doing the
        // same work and moved the ratio-of-ratios from 1.39 to 1.28, which says nothing about the
        // albedo and everything about the transport. The direct statement — the poles cool a third
        // further when the surface is allowed to turn white, 5.8 C against 4.3 — is what the
        // feedback actually claims, and it is the number the ice edge below agrees with.
        assertTrue(
            polarCooling > controlPolarCooling * 1.2f,
            ("the feedback barely deepened the polar cooling: %.1f C at 75 deg with it, %.1f C " +
                "without").format(polarCooling, controlPolarCooling)
        )
        assertTrue(
            controlAmplification < 1f,
            ("a dimmed sun with no feedback already cools the poles more than the tropics, so the " +
                "guard is not measuring the feedback: %.2f").format(controlAmplification)
        )
        assertTrue(
            glacialEdge < controlEdge - 1f,
            "the feedback added nothing to the cap the plain cooling would have grown anyway: " +
                "%.1f deg with it, %.1f deg without".format(glacialEdge, controlEdge)
        )
    }

    @Test
    fun `an upright axis has no seasons at all`() {
        // `ClimateConfig.seasons` off is a thermal-equator migration of zero, which is an obliquity
        // of zero, which is a planet whose every day is the same length everywhere. Nothing about
        // the model needs to know that seasons were switched off: with no tilt there is no
        // seasonal forcing, so summer and winter are the same number to the last bit the solver
        // carries.
        val land = earthLandFraction()
        val solved = EnergyBalance.solve(land, obliquityDegrees = 0f)
        var widest = 0f
        var widestBand = 0
        var widestColumn = "land"
        for (band in 0 until EnergyBalance.BANDS) {
            val landSwing = solved.land.warmestMonthC[band] - solved.land.coldestMonthC[band]
            val seaSwing = solved.sea.warmestMonthC[band] - solved.sea.coldestMonthC[band]
            if (landSwing > widest) { widest = landSwing; widestBand = band; widestColumn = "land" }
            if (seaSwing > widest) { widest = seaSwing; widestBand = band; widestColumn = "sea" }
        }
        println(
            ("EBM upright axis: widest seasonal swing anywhere %.5f C, in the %s column of band " +
                "%d at %.1f deg where the land fraction is %.2f")
                .format(
                    widest, widestColumn, widestBand,
                    EnergyBalance.latitudeOfBand(widestBand), land[widestBand]
                )
        )
        assertTrue(widest < 0.01f, "an upright planet still swings %.5f C".format(widest))
    }

    /**
     * The latitude the cold-season sea-ice edge reaches, in degrees from the equator: the
     * equatorward-most band such that every band between it and the pole has a cold-season sea
     * surface below the freezing point of sea water. 90 when the polar band itself is open.
     */
    private fun coldSeasonIceEdge(climate: ZonalClimate, northern: Boolean): Float {
        val bands = 0 until EnergyBalance.BANDS
        val ordered = if (northern) bands.toList() else bands.reversed().toList()
        var edge = EnergyBalance.POLE_DEGREES
        for (band in ordered) {
            val latitude = EnergyBalance.latitudeOfBand(band)
            if (northern && latitude < 0f) break
            if (!northern && latitude > 0f) break
            if (climate.water.coldestMonthC[band] >= EnergyBalance.SEA_FREEZING_C) break
            edge = abs(latitude)
        }
        return edge
    }
}
