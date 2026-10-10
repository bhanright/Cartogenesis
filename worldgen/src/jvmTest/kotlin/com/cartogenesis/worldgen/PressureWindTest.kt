package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.BoundaryLayer
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.PressureWind
import com.cartogenesis.worldgen.pipeline.Season
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The wind the pressure field drives, and what it does to the rain.
 *
 * Four claims, each with the same control: `ClimateConfig.pressureWinds` off, which is the wind
 * this generator had when the belts were the whole of it.
 *
 * The **monsoon**: on a world with a large subtropical continent, the warm half of the year blows
 * *onto* that continent's equatorward and eastern coasts and the cold half blows off them. That is
 * the deviation GEOGRAPHY.md's "The monsoon lands on the wrong coast" records, and it is the whole
 * reason this chunk exists. Measured as the coast-normal component of the wind, in metres a
 * second, averaged along those coasts: positive is onshore.
 *
 * The **interior**: rainfall inside a continent is not simply a function of how far the air has
 * come. Measured as the coefficient of variation of annual rainfall over land more than
 * [INTERIOR_REACH_KM] from the sea, which is the number the maintainer's complaint — "rainfall
 * varies little inside continents" — is about.
 *
 * The **ice edge**: W1 recorded that the sea-ice edge is a straight line of latitude, and parked
 * the finding on this chunk on the expectation that a wind that knows about longitude would bend
 * it. It does not, and the measurement below is a finding rather than a guard for that reason —
 * see docs/GEOGRAPHY.md, "A straight ice edge", and the W2 row of the ledger for the figures and
 * for what actually decides it.
 *
 * And the **control itself**: with the pressure term off, every cell of a row blows the same way,
 * which is what makes the three measurements above comparisons rather than readings.
 *
 * See docs/DESIGN_LEDGER.md, W2.
 */
class PressureWindTest : BorrowsSharedWorlds() {

    private companion object {
        val seeds = SharedWorlds.STANDARD_SEEDS

        /**
         * [SharedWorlds.COARSE_ROWS] for the interior, the ice edge and the control: a spread of
         * rainfall over an interior, the latitude of an ice edge and a row that blows one way are
         * the ground's figures, not the grid's detail.
         */
        const val size = SharedWorlds.COARSE_ROWS

        /**
         * [SharedWorlds.DETAIL_ROWS] for the monsoon, whose figure moves with the cell: at 256 rows
         * at Q2b seed 99's largest subtropical continent was centered at 29.0 degrees, where it is
         * 19.4 here, over 1.8 times the area, and the warm half blew +0.06 m/s onshore pooled
         * against +0.29 here (docs/DESIGN_LEDGER.md, Q2b).
         */
        const val MONSOON_ROWS = SharedWorlds.DETAIL_ROWS

        /**
         * How far inland "the interior" starts, in kilometres.
         *
         * Five hundred, which is the chunk's own specification and is also roughly where the
         * marine air fraction behind `ClimateStage.MARINE_REACH_KM` has faded to a third: far
         * enough in that a cell's climate is the continent's rather than the coast's.
         */
        const val INTERIOR_REACH_KM = 500.0

        /**
         * The spread of annual rainfall across Earth's own continental interiors, as a
         * coefficient of variation, and where the figure comes from.
         *
         * There is no published spatial coefficient of variation for interiors to quote: the
         * gridded records report variation *through time* at a place, which is a different
         * quantity from the variation *across places* this measures. So the reference is derived
         * here, from the annual normals of twenty-five places that all sit more than five hundred
         * kilometres from the sea and between them cover every inhabited continent and every
         * climate an interior has — [EARTH_INTERIOR_NORMALS_MM]. Its mean is 573 mm and its
         * standard deviation 488, a coefficient of variation of **0.853**.
         *
         * Stated as what it is: a reference computed from a named table, not a figure lifted from
         * GPCC. A generator whose interiors were all much the same would read far below it, which
         * is the failure this chunk is answering; a generator that read far above it would be
         * making deserts and rainforests neighbours, which is the failure at the other end.
         */
        val EARTH_INTERIOR_NORMALS_MM = listOf(
            // Siberia and central Asia.
            237f, // Yakutsk
            443f, // Novosibirsk
            472f, // Irkutsk
            267f, // Ulaanbaatar
            294f, // Urumqi
            64f, // Kashgar
            320f, // Astana
            440f, // Tashkent
            428f, // Lhasa
            707f, // Moscow
            // North America.
            356f, // Denver
            521f, // Winnipeg
            390f, // Regina
            434f, // Bismarck
            762f, // Omaha
            204f, // Phoenix
            // Africa.
            890f, // Kano
            510f, // N'Djamena
            180f, // Timbuktu
            160f, // Khartoum
            830f, // Lusaka
            // South America and Australia.
            1500f, // Brasilia
            1330f, // Santa Cruz de la Sierra
            2300f, // Manaus
            280f // Alice Springs
        )

        /**
         * How far from Earth's figure the generator's interior spread may sit and still be called
         * Earth-like, as a factor either way.
         *
         * Two, the factor this suite uses wherever a quantity is a spread rather than a level —
         * the same bar `ScaleFree`'s findings are ranked against. A factor rather than an
         * envelope because a coefficient of variation is a ratio and its errors are multiplicative.
         */
        const val INTERIOR_SPREAD_FACTOR = 2.0

        /**
         * How far a subtropical coast's mean onshore wind has to be from zero, in metres a second,
         * for the season to count as blowing on or off it.
         *
         * A tenth of a metre a second. The claim is about the *sign* of the flow, not its
         * strength, and a bar much above the arithmetic's own noise is all a sign claim can
         * honestly ask for. Earth's own monsoon flow is metres a second, not tenths.
         */
        const val ONSHORE_BAR_MPS = 0.1

        /** The tropics and subtropics, in degrees: where a monsoon continent has to sit. */
        const val SUBTROPICAL_EQUATORWARD_DEGREES = 5f
        const val SUBTROPICAL_POLEWARD_DEGREES = 45f

        /**
         * How much of a world's land a subtropical landmass has to hold before the monsoon claim
         * is made of it, as a share.
         *
         * A twentieth. A monsoon is a continental circulation: it needs a landmass deep enough to
         * heat a column of air well away from the sea that would moderate it, and India, Australia
         * and West Africa are each a few per cent of Earth's land. Without a floor the
         * largest-subtropical search happily returns a twenty-nine-cell island, whose coast is all
         * coast and whose interior does not exist, and asks it to have a monsoon.
         */
        const val LARGE_CONTINENT_SHARE_OF_LAND = 0.05
    }

    @Test
    fun `the Rossby radius and the boundary layer's drag are the figures they are derived from`() {
        val rossbyKm = PressureWind.rossbyRadiusKm()
        val seaTurnAt45 = PressureWind.crossIsobarDegreesAt(45f, isLand = false)
        val landTurnAt45 = PressureWind.crossIsobarDegreesAt(45f, isLand = true)
        val seaTurnAt15 = PressureWind.crossIsobarDegreesAt(15f, isLand = false)
        val seaDrag = PressureWind.surfaceDrag(isLand = false)
        val landDrag = PressureWind.surfaceDrag(isLand = true)
        // The surface's bulk stress on the layer's mass at the belts' 7.5 m/s and the weather's
        // gusts, from its figures written out here; the code holds the density and the speed in
        // single precision, so the two agree to its rounding.
        val gusts = BoundaryLayer.TRANSIENT_WIND_MPS
        val stressRate = 1.225 * 1.2e-3 * sqrt(7.5 * 7.5 + gusts * gusts) * 9.80665 / (100_000.0 / BoundaryLayer.LEVEL_COUNT)
        println(
            ("PRESSURE WIND: Rossby radius %.0f km; the layer's drag %.2f days over sea and %.1f hours over land; " +
                "cross-isobar turn at 45 deg %.1f over sea and %.1f over land, at 15 deg %.1f over sea")
                .format(rossbyKm, 1 / seaDrag / 86_400, 1 / landDrag / 3_600, seaTurnAt45, landTurnAt45, seaTurnAt15)
        )
        assertTrue(
            rossbyKm > 900.0 && rossbyKm < 1_050.0,
            "the Rossby radius reads $rossbyKm km, not the thousand its stratification and " +
                "tropopause give"
        )
        assertTrue(
            abs(seaDrag / stressRate - 1) < 1e-6,
            "the sea's drag is $seaDrag per second, not the surface's stress on the layer, $stressRate"
        )
        assertTrue(
            landDrag > seaDrag && seaTurnAt15 > seaTurnAt45 && landTurnAt45 > seaTurnAt45,
            "the turn does not widen over land's rougher ground and toward the equator, where the Coriolis force weakens"
        )
    }

    @Test
    fun `at the equator the wind runs down the pressure gradient instead of along it`() {
        // The tropical limit, checked on the expression itself rather than on a world: at the
        // equator the Coriolis parameter is zero and the balance has nothing left but the drag, so
        // the flow must point straight from high pressure to low.
        val turnAtEquator =
            PressureWind.crossIsobarDegreesAt(0f, isLand = false)
        val coriolisAtEquator = PressureWind.coriolisParameter(0f)
        println(
            "PRESSURE WIND: Coriolis at the equator %.3e per second, cross-isobar turn %.1f deg"
                .format(coriolisAtEquator, turnAtEquator)
        )
        assertTrue(coriolisAtEquator == 0f, "the Coriolis parameter is not zero at the equator")
        assertTrue(
            turnAtEquator == 90.0,
            "the equatorial wind turns $turnAtEquator degrees across the isobars, not the 90 that " +
                "means straight down the gradient"
        )
    }

    @Test
    fun `with the pressure term off every cell of a row blows the same way`() {
        val world = generate(7L, pressureWinds = false)
        var rowsThatVary = 0
        for (row in 0 until world.height) {
            val firstZonal = world.climate.windDirection[row * world.width]
            val firstSlant = world.climate.windMeridional.data[row * world.width]
            for (column in 1 until world.width) {
                val cell = row * world.width + column
                if (world.climate.windDirection[cell] != firstZonal ||
                    world.climate.windMeridional.data[cell] != firstSlant
                ) {
                    rowsThatVary++
                    break
                }
            }
        }
        val withPressure = generate(7L, pressureWinds = true)
        var rowsThatVaryWithPressure = 0
        for (row in 0 until withPressure.height) {
            val firstSlant = withPressure.climate.windMeridional.data[row * withPressure.width]
            for (column in 1 until withPressure.width) {
                if (withPressure.climate.windMeridional.data[row * withPressure.width + column] !=
                    firstSlant
                ) {
                    rowsThatVaryWithPressure++
                    break
                }
            }
        }
        println(
            ("PRESSURE WIND control: %d of %d rows vary along their length with the pressure " +
                "term off, %d with it on")
                .format(rowsThatVary, world.height, rowsThatVaryWithPressure)
        )
        assertTrue(
            rowsThatVary == 0,
            "$rowsThatVary rows vary along their length with the pressure term off, so the " +
                "control is not the belt wind"
        )
        assertTrue(
            rowsThatVaryWithPressure == withPressure.height,
            "only $rowsThatVaryWithPressure of ${withPressure.height} rows vary with the pressure " +
                "term on, so the pressure field is not reaching the wind"
        )
    }

    @Test
    fun `a subtropical continent draws the wind on in summer and pushes it off in winter`() {
        var summerCoastMps = 0.0
        var winterCoastMps = 0.0
        var controlSummerCoastMps = 0.0
        var controlWinterCoastMps = 0.0
        var coastCells = 0
        var continentsMeasured = 0
        seeds.forEach { seed ->
            val world = generate(seed, pressureWinds = true, rows = MONSOON_ROWS)
            val continent = largestSubtropicalContinent(world) ?: run {
                println("MONSOON seed $seed: no large subtropical continent to measure")
                return@forEach
            }
            val control = generate(seed, pressureWinds = false, rows = MONSOON_ROWS)
            val measured = onshoreFlow(world, continent)
            val controlMeasured = onshoreFlow(control, continent)
            continentsMeasured++
            coastCells += measured.coastCells
            summerCoastMps += measured.summerOnshoreMps * measured.coastCells
            winterCoastMps += measured.winterOnshoreMps * measured.coastCells
            controlSummerCoastMps += controlMeasured.summerOnshoreMps * measured.coastCells
            controlWinterCoastMps += controlMeasured.winterOnshoreMps * measured.coastCells
            println(
                ("MONSOON seed %d: continent of %d cells centred on %.1f deg, %d equatorward " +
                    "and eastern coast cells; summer onshore %+.2f m/s, winter onshore %+.2f " +
                    "(control %+.2f and %+.2f)")
                    .format(
                        seed, continent.cellCount, continent.meanLatitude, measured.coastCells,
                        measured.summerOnshoreMps, measured.winterOnshoreMps,
                        controlMeasured.summerOnshoreMps, controlMeasured.winterOnshoreMps
                    )
            )
        }
        assertTrue(
            continentsMeasured > 0,
            "no seed offered a subtropical continent holding a twentieth of its land"
        )
        val summer = summerCoastMps / coastCells
        val winter = winterCoastMps / coastCells
        val controlSummer = controlSummerCoastMps / coastCells
        val controlWinter = controlWinterCoastMps / coastCells
        println(
            ("MONSOON pooled over %d continents and %d coast cells: summer onshore %+.2f m/s, " +
                "winter onshore %+.2f (control %+.2f and %+.2f); the bar is %+.2f, which is " +
                "%.1f%% of the belt's own %.1f m/s")
                .format(
                    continentsMeasured, coastCells, summer, winter, controlSummer, controlWinter,
                    ONSHORE_BAR_MPS, ONSHORE_BAR_MPS * 100 / PressureWind.BELT_SPEED_MPS,
                    PressureWind.BELT_SPEED_MPS
                )
        )
        // Recorded at A1-5: the boundary layer's single drag and the latent heat leave the summer's
        // inflow under the bar, the equatorial continent of seed 1234 blowing off its coasts in
        // both halves (docs/TODO.md).
        KnownFailures.expect("A1-5: the warm half barely blows onto the subtropical coasts", "+0.06") {
            if (summer <= ONSHORE_BAR_MPS) {
                throw RecordedViolation(
                    ("the warm half blows %+.2f m/s onto the subtropical continents' equatorward and " +
                        "eastern coasts, which is not onshore").format(summer),
                    "%+.2f".format(summer)
                )
            }
        }
        // A known failure on square cells from Q2 to L1, armed at K2, where the Earth-sized
        // planet's cold half blows off these coasts again (docs/DESIGN_LEDGER.md, Q2 and K2).
        assertTrue(
            winter < -ONSHORE_BAR_MPS,
            ("the cold half blows %+.2f m/s onto the same coasts, which is not offshore").format(winter)
        )
        // The control, which is the whole of the claim's meaning: with the belts as the entire
        // wind the same coasts do not reverse between the halves of the year.
        // Recorded at C1b2: the belts' zonal wind is continuous now, falling to nothing at each
        // edge, so where a subtropical coast stands near an edge the belts alone turn its wind
        // between the halves of the year (docs/TODO.md, "The march's misses against Earth, after
        // C1b2").
        KnownFailures.expect("C1b2: the belts alone reverse the subtropical coasts", "+0.83 against -0.19") {
            if (!(controlWinter > -ONSHORE_BAR_MPS || controlSummer < ONSHORE_BAR_MPS)) {
                throw RecordedViolation(
                    ("the belts alone already reverse these coasts between the seasons, %+.2f m/s in " +
                        "summer against %+.2f in winter, so the pressure term is not what does it")
                        .format(controlSummer, controlWinter),
                    "%+.2f against %+.2f".format(controlSummer, controlWinter)
                )
            }
        }
    }

    @Test
    fun `the interior's rainfall spreads as widely as Earth's`() {
        val earthSpread = coefficientOfVariation(EARTH_INTERIOR_NORMALS_MM.map { it.toDouble() })
        val earthMean = EARTH_INTERIOR_NORMALS_MM.map { it.toDouble() }.average()
        var pooledWith = 0.0
        var pooledWithout = 0.0
        var pooledMean = 0.0
        var seedsMeasured = 0
        seeds.forEach { seed ->
            val world = generate(seed, pressureWinds = true)
            val control = generate(seed, pressureWinds = false)
            val withPressure = interiorRainSpread(world) ?: return@forEach
            val withoutPressure = interiorRainSpread(control) ?: return@forEach
            seedsMeasured++
            pooledWith += withPressure
            pooledWithout += withoutPressure
            pooledMean += interiorRainMeanMm(world) ?: 0.0
            println(
                ("INTERIOR RAIN seed %d: coefficient of variation %.3f with the pressure term, " +
                    "%.3f without, against Earth's %.3f; mean %.0f mm against Earth's %.0f")
                    .format(
                        seed, withPressure, withoutPressure, earthSpread,
                        interiorRainMeanMm(world) ?: 0.0, earthMean
                    )
            )
        }
        assertTrue(seedsMeasured > 0, "no seed had an interior to measure")
        val meanWith = pooledWith / seedsMeasured
        val meanWithout = pooledWithout / seedsMeasured
        println(
            ("INTERIOR RAIN pooled over %d seeds: %.3f with the pressure term, %.3f without, " +
                "against Earth's %.3f from %d interior normals")
                .format(
                    seedsMeasured, meanWith, meanWithout, earthSpread,
                    EARTH_INTERIOR_NORMALS_MM.size
                )
        )
        assertTrue(
            meanWith > meanWithout,
            "the pressure term does not widen the interior's rainfall at all: %.3f against %.3f"
                .format(meanWith, meanWithout)
        )
        // Recorded at C1b2: the interior's rain runs from the windward rises' to the dry plateaus'
        // more widely than Earth's interiors do (docs/TODO.md, "The march's misses against Earth,
        // after C1b2").
        KnownFailures.expect("C1b2: the interior's rainfall spreads wider than Earth's", "2.113") {
            if (!(meanWith > earthSpread / INTERIOR_SPREAD_FACTOR && meanWith < earthSpread * INTERIOR_SPREAD_FACTOR)) {
                throw RecordedViolation(
                    ("the interior's rainfall spreads by %.3f, outside a factor of %.0f either side of " +
                        "Earth's %.3f").format(meanWith, INTERIOR_SPREAD_FACTOR, earthSpread),
                    "%.3f".format(meanWith)
                )
            }
        }
        // The level as well as the spread, which is W3's addition to this measurement: the
        // complaint that started the chunk was about variation, and a uniformly pale interior may
        // be uniformly dry rather than uniformly anything. Same table, same twenty-five places.
        //
        // **A finding and not an assertion**, because it is outside the factor this suite calls
        // Earth-like and the cause is not this chunk's to fix. The generator's interiors run at
        // roughly a third of Earth's level: its seas are the only moisture source the march has,
        // its lakes have never fed it (TODO.md, "Lakes never feed the moisture march"), and its
        // monsoon flow is tenths of a metre a second where Earth's is metres, so a parcel that has
        // crossed a thousand kilometres of land has nothing left to give and nothing to refill it.
        // W3 moved the figure by removing a per-cell unit from the millimetre conversion and by
        // giving the ground's return a length; what is left is a moisture supply question. See
        // docs/DESIGN_LEDGER.md, W3, and docs/GEOGRAPHY.md, "The interior is drier than Earth's".
        val interiorMean = pooledMean / seedsMeasured
        println(
            ("INTERIOR RAIN pooled mean %.0f mm against Earth's %.0f from the same normals, " +
                "%.2f times it - a finding, see the ledger")
                .format(interiorMean, earthMean, interiorMean / earthMean)
        )
    }

    @Test
    fun `what the regional wind did to the sea-ice edge, and to the water under it`() {
        seeds.forEach { seed ->
            val world = generate(seed, pressureWinds = true)
            val control = generate(seed, pressureWinds = false)
            val spread = iceEdgeSpreadDegrees(world)
            val controlSpread = iceEdgeSpreadDegrees(control)
            // What the stress did to the water underneath, so the finding says whether the edge
            // held still because the currents held still or for some other reason.
            var anomalyShiftC = 0.0
            var seaCells = 0
            for (cell in 0 until world.width * world.height) {
                if (world.sea.isLand[cell]) continue
                anomalyShiftC +=
                    abs(world.ocean.anomaly.data[cell] - control.ocean.anomaly.data[cell])
                        .toDouble()
                seaCells++
            }
            println(
                ("SEA ICE EDGE seed %d: the cold-season edge varies by %.1f deg of latitude " +
                    "across the basin with the pressure term and %.1f without; the wind stress " +
                    "moved the current anomaly under it by %.3f C on average " +
                    "(Earth's Arctic winter edge runs 44 N in the Okhotsk to 75 N off Norway, " +
                    "31 deg; Fetterer and others, Sea Ice Index, NSIDC)")
                    .format(
                        seed, spread, controlSpread, anomalyShiftC / seaCells
                    )
            )
        }
    }

    private fun generate(seed: Long, pressureWinds: Boolean, rows: Int = size): WorldMap {
        val base = WorldGenConfig.forRows(seed, rows)
        return SharedWorlds.world(
            base.copy(climate = base.climate.copy(pressureWinds = pressureWinds))
        )
    }

    /** A landmass, as the cells in it and where its weight sits. */
    private class Continent(
        val cells: BooleanArray,
        val cellCount: Int,
        val meanLatitude: Float
    )

    /**
     * The biggest landmass whose weight sits in the subtropics, which is where a monsoon lives.
     *
     * Four-connected and wrapping east to west, because the map is a cylinder. Null when the world
     * has no such landmass, which a very oceanic seed can manage.
     */
    private fun largestSubtropicalContinent(world: WorldMap): Continent? {
        val cellsAcross = world.width
        val cellsDown = world.height
        val label = IntArray(cellsAcross * cellsDown) { -1 }
        var best: Continent? = null
        var nextLabel = 0
        val stack = ArrayDeque<Int>()
        for (start in 0 until cellsAcross * cellsDown) {
            if (!world.sea.isLand[start] || label[start] >= 0) continue
            val mine = BooleanArray(cellsAcross * cellsDown)
            var count = 0
            var latitudeSum = 0.0
            label[start] = nextLabel
            stack.addLast(start)
            while (stack.isNotEmpty()) {
                val cell = stack.removeLast()
                mine[cell] = true
                count++
                latitudeSum += ClimateStage.latitudeOf(cell / cellsAcross, cellsDown).toDouble()
                forEachNeighbour(cell, cellsAcross, cellsDown) { neighbour ->
                    if (world.sea.isLand[neighbour] && label[neighbour] < 0) {
                        label[neighbour] = nextLabel
                        stack.addLast(neighbour)
                    }
                }
            }
            nextLabel++
            val meanLatitude = (latitudeSum / count).toFloat()
            val subtropical = abs(meanLatitude) in
                SUBTROPICAL_EQUATORWARD_DEGREES..SUBTROPICAL_POLEWARD_DEGREES
            val large = count >= world.sea.landCellCount * LARGE_CONTINENT_SHARE_OF_LAND
            val previous = best
            if (subtropical && large && (previous == null || count > previous.cellCount)) {
                best = Continent(mine, count, meanLatitude)
            }
        }
        return best
    }

    /**
     * The mean onshore wind on a continent's equatorward and eastern coasts, in each season: each
     * coast's own summer, the calendar half about July north of the equator and about January south
     * of it, which on a subtropical coast is the warmer half by a wide margin.
     */
    private class Onshore(
        val summerOnshoreMps: Double,
        val winterOnshoreMps: Double,
        val coastCells: Int
    )

    private fun onshoreFlow(world: WorldMap, continent: Continent): Onshore {
        val julyHalf = ClimateStage.seasonalSurfaceWindMps(
            world.config, world.sea, world.ocean, Season.JULY_HALF
        )
        val januaryHalf = ClimateStage.seasonalSurfaceWindMps(
            world.config, world.sea, world.ocean, Season.JANUARY_HALF
        )
        val cellsAcross = world.width
        val cellsDown = world.height
        var summerSum = 0.0
        var winterSum = 0.0
        var coastCells = 0
        for (cell in 0 until cellsAcross * cellsDown) {
            if (!continent.cells[cell]) continue
            // The outward normal: a unit step toward each sea neighbour, summed. A cell with no
            // sea neighbour is not a coast.
            var outwardEast = 0f
            var outwardSouth = 0f
            forEachNeighbour(cell, cellsAcross, cellsDown) { neighbour ->
                if (!world.sea.isLand[neighbour]) {
                    val rowStep = neighbour / cellsAcross - cell / cellsAcross
                    if (rowStep != 0) {
                        outwardSouth += rowStep.toFloat()
                    } else {
                        val columnStep = neighbour % cellsAcross - cell % cellsAcross
                        // The seam: a step of one either way round the cylinder.
                        outwardEast += if (abs(columnStep) > 1) {
                            -columnStep / abs(columnStep).toFloat()
                        } else {
                            columnStep.toFloat()
                        }
                    }
                }
            }
            val length = sqrt(outwardEast * outwardEast + outwardSouth * outwardSouth)
            if (length == 0f) continue

            // Equatorward means the sea lies toward the equator, which is southward in the
            // northern hemisphere and northward in the southern one; eastern means the sea lies
            // to the east. A coast that is both counts once.
            val latitude = ClimateStage.latitudeOf(cell / cellsAcross, cellsDown)
            val equatorwardSouth = if (latitude > 0f) 1f else -1f
            val summer = if (latitude > 0f) julyHalf else januaryHalf
            val winter = if (latitude > 0f) januaryHalf else julyHalf
            val facesEquator = outwardSouth * equatorwardSouth > 0f
            val facesEast = outwardEast > 0f
            if (!facesEquator && !facesEast) continue

            val normalEast = outwardEast / length
            val normalSouth = outwardSouth / length
            // Onshore is the wind blowing *against* the outward normal.
            summerSum += -(summer.eastwardMps[cell] * normalEast +
                summer.southwardMps[cell] * normalSouth).toDouble()
            winterSum += -(winter.eastwardMps[cell] * normalEast +
                winter.southwardMps[cell] * normalSouth).toDouble()
            coastCells++
        }
        if (coastCells == 0) return Onshore(0.0, 0.0, 0)
        return Onshore(summerSum / coastCells, winterSum / coastCells, coastCells)
    }

    /** The coefficient of variation of annual rainfall over deep interior land. */
    private fun interiorRainSpread(world: WorldMap): Double? {
        val distance = ClimateStage.waterDistance(world.config, world.sea)
        val reachCells = world.config.cellsFor(INTERIOR_REACH_KM)
        val totals = ArrayList<Double>()
        for (cell in 0 until world.width * world.height) {
            if (!world.sea.isLand[cell]) continue
            if (distance.data[cell] < reachCells) continue
            totals.add(world.climate.precipitationMm.data[cell].toDouble())
        }
        // A world whose land is all coast has no interior, and a handful of cells is not a spread.
        if (totals.size < 100) return null
        return coefficientOfVariation(totals)
    }

    /** Mean annual rainfall over the same deep interior land, in millimetres. */
    private fun interiorRainMeanMm(world: WorldMap): Double? {
        val distance = ClimateStage.waterDistance(world.config, world.sea)
        val reachCells = world.config.cellsFor(INTERIOR_REACH_KM)
        var total = 0.0
        var cells = 0
        for (cell in 0 until world.width * world.height) {
            if (!world.sea.isLand[cell]) continue
            if (distance.data[cell] < reachCells) continue
            total += world.climate.precipitationMm.data[cell].toDouble()
            cells++
        }
        if (cells < 100) return null
        return total / cells
    }

    private fun coefficientOfVariation(values: List<Double>): Double {
        val mean = values.average()
        if (mean <= 0.0) return 0.0
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return sqrt(variance) / mean
    }

    /**
     * How far the cold-season ice edge wanders in latitude around one hemisphere's basin, in
     * degrees: the standard deviation across the columns that have any ice at all.
     *
     * The northern hemisphere's, and only where the edge is genuinely an ocean edge — a column
     * whose ice runs to the pole with no open water behind it is a frozen sea, not an edge.
     */
    private fun iceEdgeSpreadDegrees(world: WorldMap): Double {
        val cellsAcross = world.width
        val cellsDown = world.height
        val edges = ArrayList<Double>()
        for (column in 0 until cellsAcross) {
            var edgeLatitude: Double? = null
            for (row in 0 until cellsDown) {
                val latitude = ClimateStage.latitudeOf(row, cellsDown)
                if (latitude <= 0f) break
                val cell = row * cellsAcross + column
                if (world.sea.isLand[cell]) continue
                // The northern winter is the calendar's half about January.
                if (world.climate.januaryHalfSeaIce[cell]) continue
                // The first open water met walking equatorward from the pole is the edge, and the
                // walk stops there: the ice edge is the poleward limit of the open sea.
                edgeLatitude = latitude.toDouble()
                break
            }
            // Only columns whose ice actually reaches somewhere count.
            val hasIce = (0 until cellsDown).any { row ->
                ClimateStage.latitudeOf(row, cellsDown) > 0f &&
                    world.climate.januaryHalfSeaIce[row * cellsAcross + column]
            }
            if (hasIce && edgeLatitude != null) edges.add(edgeLatitude)
        }
        if (edges.size < 2) return 0.0
        val mean = edges.average()
        return sqrt(edges.sumOf { (it - mean) * (it - mean) } / edges.size)
    }

    /** The four orthogonal neighbours, wrapping east to west and stopping at the poles. */
    private inline fun forEachNeighbour(
        cell: Int,
        cellsAcross: Int,
        cellsDown: Int,
        body: (Int) -> Unit
    ) {
        val row = cell / cellsAcross
        val column = cell % cellsAcross
        body(row * cellsAcross + if (column + 1 == cellsAcross) 0 else column + 1)
        body(row * cellsAcross + if (column == 0) cellsAcross - 1 else column - 1)
        if (row > 0) body((row - 1) * cellsAcross + column)
        if (row + 1 < cellsDown) body((row + 1) * cellsAcross + column)
    }
}
