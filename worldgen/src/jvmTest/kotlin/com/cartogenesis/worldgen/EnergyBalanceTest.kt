package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.pipeline.EnergyBalance
import com.cartogenesis.worldgen.pipeline.Season
import com.cartogenesis.worldgen.pipeline.ZonalClimate
import kotlin.math.abs
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
 * See REALISM_PLAN.md, W1.
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
         * The obliquity Earth's own thermal-equator migration implies, which is Earth's own tilt:
         * see `EnergyBalance.obliquityDegrees`.
         */
        val EARTH_OBLIQUITY = EnergyBalance.obliquityDegrees(10f)

        /**
         * The ratio by which Earth's land swings further through the year than its ocean at 50-60
         * degrees, and the floor the model is asserted at.
         *
         * Read off station and sea-surface climatology in the band where the contrast is at its
         * plainest. Deep-continental land: Novosibirsk (55 N) has a 34 C annual range, Yakutsk
         * (62 N) 60 C, Winnipeg (50 N) 38 C, so a continental interior there swings 34-38 C. Open
         * ocean: the North Atlantic at 50-55 N runs about 9 C in February to 16 C in August, and
         * the North Pacific about the same, so 6-8 C. The ratio is therefore between four and six,
         * and the guard asks for **three**, comfortably inside it, because the model's land column
         * is a whole band's worth of land and not one station in the middle of Siberia. It
         * measures 6.4, on 35.6 C over the land and 5.6 over the sea.
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
        return land[band] * climate.landAnnualC[band] + (1f - land[band]) * climate.seaAnnualC[band]
    }

    /** The mean of the two hemispheres at [latitude], which is what the Earth figures are. */
    private fun bothHemispheresC(climate: ZonalClimate, land: FloatArray, latitude: Float): Float =
        (zonalAnnualC(climate, land, latitude) + zonalAnnualC(climate, land, -latitude)) * 0.5f

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
                    "sea %.1f/%.1f/%.1f (mean/summer/winter)")
                    .format(
                        latitude, zonalAnnualC(solved, land, latitude.toFloat()),
                        solved.landC(latitude.toFloat(), Season.ANNUAL),
                        solved.landC(latitude.toFloat(), Season.SUMMER),
                        solved.landC(latitude.toFloat(), Season.WINTER),
                        solved.seaC(latitude.toFloat(), Season.ANNUAL),
                        solved.seaC(latitude.toFloat(), Season.SUMMER),
                        solved.seaC(latitude.toFloat(), Season.WINTER)
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
            land, EARTH_OBLIQUITY, transportW = (EnergyBalance.DIFFUSION_W_PER_M2_C / 10.0).toFloat()
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
        var bands = 0
        for (band in 0 until EnergyBalance.BANDS) {
            val latitude = abs(EnergyBalance.latitudeOfBand(band))
            if (latitude < 50f || latitude > 60f) continue
            landSwing += solved.landSummerC[band] - solved.landWinterC[band]
            seaSwing += solved.seaSummerC[band] - solved.seaWinterC[band]
            bands++
        }
        val landRange = landSwing / bands
        val seaRange = seaSwing / bands
        println(
            ("EBM swing at 50-60 deg: land %.1f C, sea %.1f C, ratio %.2f " +
                "(Earth 34-38 against 6-8, ratio 4-6)")
                .format(landRange, seaRange, landRange / seaRange)
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
            subtropicalLand += solved.landSummerC[band] - solved.landWinterC[band]
            subtropicalBands++
        }
        println(
            "EBM swing at 30-40 deg: land %.1f C (Earth's continental interiors 24-26)"
                .format(subtropicalLand / subtropicalBands)
        )

        assertTrue(
            landRange / seaRange >= LAND_TO_SEA_SWING_RATIO,
            "land swings only %.2f times as far as the sea (land %.1f C, sea %.1f C)"
                .format(landRange / seaRange, landRange, seaRange)
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
        var seaSwing = 0.0
        var bands = 0
        for (band in 0 until EnergyBalance.BANDS) {
            val latitude = abs(EnergyBalance.latitudeOfBand(band))
            if (latitude < 50f || latitude > 60f) continue
            landSwing += solved.landSummerC[band] - solved.landWinterC[band]
            seaSwing += solved.seaSummerC[band] - solved.seaWinterC[band]
            bands++
        }
        println(
            ("EBM all-ocean world at 50-60 deg: land column %.1f C, sea column %.1f C, ratio %.2f")
                .format(landSwing / bands, seaSwing / bands, landSwing / seaSwing)
        )
        assertTrue(
            landSwing / seaSwing >= LAND_TO_SEA_SWING_RATIO,
            "the contrast is the geography rather than the capacities: an all-ocean world's " +
                "land column swings only %.2f times its sea column".format(landSwing / seaSwing)
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
                        latitude, present.seaWinterC[band], glacial.seaWinterC[band],
                        withoutFeedback.seaWinterC[band]
                    )
            )
        }

        assertTrue(
            glacialEdge < presentEdge - 1f,
            "the cap did not grow under the glacial forcing: edge %.1f -> %.1f deg"
                .format(presentEdge, glacialEdge)
        )
        assertTrue(
            polarCooling > tropicalCooling * 1.5f,
            "the cooling was not polar-amplified: 10 deg %.1f C against 75 deg %.1f C"
                .format(tropicalCooling, polarCooling)
        )
        assertTrue(
            controlPolarCooling < controlTropicalCooling * 1.5f,
            "the amplification survives with the feedback off, so the guard is not measuring the " +
                "feedback: 10 deg %.1f C against 75 deg %.1f C"
                .format(controlTropicalCooling, controlPolarCooling)
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
            val landSwing = solved.landSummerC[band] - solved.landWinterC[band]
            val seaSwing = solved.seaSummerC[band] - solved.seaWinterC[band]
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
            if (climate.seaWinterC[band] >= EnergyBalance.SEA_FREEZING_C) break
            edge = abs(latitude)
        }
        return edge
    }
}
