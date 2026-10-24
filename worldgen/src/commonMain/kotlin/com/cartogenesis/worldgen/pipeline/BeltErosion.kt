package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.math.GaussianBlur
import com.cartogenesis.worldgen.math.GroundSteps
import com.cartogenesis.worldgen.math.LongMinHeap
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * The belts' recent history as the plate stage hands it to the erosion, one entry per map cell:
 * what tectonics did to the crust over the exposure window, km of thickness, thickening positive;
 * what the orogens' wear took over it, km; and when its thickening began, Myr ago, -1 where none
 * did ([PlateResult.windowTectonicKm]); and the window's length, Myr.
 */
class BeltWindow(
    val tectonicKm: FloatField,
    val wearKm: FloatField,
    val onsetMyrAgo: FloatField,
    val windowMyr: Double
)

/**
 * What the replay did, in cubic metres of rock, and where the rock went; and the metres each cell
 * lost, for the guards that ask how much of a belt's relief survived.
 *
 * The books close: what the rivers cut leaves the belts at their outlets, into the sea or onto the
 * land beyond them, where the foreland's own rivers take it on ([exportedToSeaCubicMetres],
 * [exportedToLandCubicMetres], the second with what a hollow inside a belt holds); what the
 * hillslopes shed onto the ground beside the belts stays there ([shedBesideCubicMetres]). The
 * replay cuts rock and lays none: the rounds that follow it lay the spoil of their own four million
 * years.
 */
internal class BeltReplay(
    val rounds: Int,
    val beltCells: Int,
    val upliftedCubicMetres: Double,
    val cutCubicMetres: Double,
    val exportedToSeaCubicMetres: Double,
    val exportedToLandCubicMetres: Double,
    val shedBesideCubicMetres: Double,
    val erodedMetres: FloatArray
) {
    companion object {
        val NOTHING = BeltReplay(0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, FloatArray(0))
    }
}

/**
 * The young belts' rise and wear over the exposure window, before the hydraulic rounds cut the
 * last four million years of every land on the map (docs/DESIGN_LEDGER.md, TH1-2).
 *
 * A range dissected as Earth's are has been rising and wearing for tens of millions of years: its
 * rivers cut as the rock rose, their incision depending on the discharge they gathered, the rock
 * they met and the uplift they had to keep up with (Whipple, *Bedrock rivers and the geomorphology
 * of active orogens*, Annual Review of Earth and Planetary Sciences 32, 2004). Four million years of
 * today's uplift on today's belt cannot make that: the belt starts at its full height and the rivers
 * have a tenth of the time. Run longer at today's uplift, the rounds would keep raising the barrier
 * they were cutting. So the window is replayed: each belt is lowered to where its crust stood at the
 * window's start, raised again at the rate its own thickening came at, from when it began, and cut
 * as it rises, its drainage routed afresh every round, so a river older than the uplift can hold its
 * course across the rising belt where its incision keeps pace (an antecedent river) and a basin
 * the belt encloses spills and cuts its outlet.
 *
 * **The belts only.** The cells whose crust thickened in the window, by more than the history
 * marks a deformation by; everything else is held as it stands, and a belt's rivers leave it where
 * it meets that ground. The rain is the opening climate's and the water from beyond the belts is
 * the opening routing's: the drainage and the climate are refreshed by the rounds that follow, over
 * the whole map, as the brief allows.
 *
 * **The same laws as the rounds.** The stream-power law at the rounds' coefficient and time step,
 * spent by Braun and Willett's implicit update over the facet routing ([FlowRouting.facetReceiver])
 * on the depression-filled surface, the lakes' floors not cut; the slopes relaxed toward their
 * critical fall by the rounds' own sweeps.
 *
 * **The rock taken off is answered as the crust is stood.** The replay stands in for the history's
 * lumped wear, which thins the crust and lets Airy's isostasy lower the ground a seventh of what
 * went, as the plate stage stands every column; so the rock the replay takes off is answered the
 * same way, Airy's rebound of the loss read at the scale the plate stage resolves the crust, the
 * map kernel's spread of the history's spacing ([CrustMap.KERNEL_SPREAD_SPACINGS]): a belt worn
 * down over its width rebounds as its crust thins, while a valley a cell or two wide is held up by
 * the ground round it, every [REBOUND_ROUNDS] rounds and at the last. The rounds after the replay
 * bend the plate by its flexure, as they always have.
 *
 * **Nothing counted twice.** The plate stage's ground is the crust as it stands, its tectonics and
 * the history's lumped wear both in it. A belt starts the window lower by Airy's freeboard of what
 * tectonics added less what the wear took, rises by the freeboard of what tectonics added, the
 * rounds' own share of today's rate left for the rounds, and is worn by the rivers in place of the
 * history's wear: with no rivers it would end where the plate stage put it plus the wear's
 * freeboard.
 */
internal object BeltErosion {

    fun replay(
        config: WorldGenConfig,
        surface: FloatArray,
        window: BeltWindow,
        presentUpliftMmPerYear: FloatArray?,
        rainfallMm: FloatArray,
        coverDensity: FloatArray,
        rockErodibility: FloatArray?,
        provisionalSeaLevel: Float,
        shieldCut: Boolean
    ): BeltReplay {
        val erosion = config.erosion
        val scale = config.scale
        val cellsAcross = config.width
        val cellsDown = config.height
        val cellCount = cellsAcross * cellsDown
        val roundYears = scale.yearsPerHydraulicRound
        val roundMyr = roundYears / 1e6
        val mainMyr = erosion.hydraulicRounds * roundMyr
        val windowMyr = window.windowMyr
        val rounds = ((windowMyr - mainMyr) / roundMyr + ROUND_ROUNDING).toInt()
        if (rounds <= 0 || erosion.hydraulicRounds <= 0 || erosion.bedrockErodibilityPerYear <= 0f) return BeltReplay.NOTHING

        val isostasy = config.isostasy
        val freeboardMetresPerKm = (isostasy.mantleDensity - isostasy.continentalCrustDensity) / isostasy.mantleDensity * METRES_PER_KM
        val metresPerFieldUnit = scale.reliefSpanMetres
        val tectonic = window.tectonicKm.data
        val wear = window.wearKm.data
        val onset = window.onsetMyrAgo.data

        // The belts, and each one's rise: lowered to the window's start, then raised round by round.
        val belt = (0 until cellCount).filter { onset[it] >= 0f }.toIntArray()
        if (belt.isEmpty()) return BeltReplay.NOTHING
        val localOf = IntArray(cellCount) { -1 }
        belt.forEachIndexed { local, cell -> localOf[cell] = local }
        val beltCells = belt.size
        val risePerRoundField = FloatArray(beltCells)
        val firstRound = IntArray(beltCells)
        for (local in 0 until beltCells) {
            val cell = belt[local]
            surface[cell] -= freeboardMetresPerKm * (tectonic[cell] - wear[cell]) / metresPerFieldUnit
            val roundsShare = if (presentUpliftMmPerYear == null) 0.0 else presentUpliftMmPerYear[cell] * erosion.hydraulicRounds * roundYears / MILLIMETRES_PER_METRE
            val riseMetres = freeboardMetresPerKm * tectonic[cell] - roundsShare
            // The first round whose start is no earlier than the onset, counted from the window's start.
            val first = ceil((windowMyr - onset[cell]) / roundMyr - ROUND_ROUNDING).toInt().coerceIn(0, rounds - 1)
            firstRound[local] = first
            risePerRoundField[local] = (riseMetres / (rounds - first) / metresPerFieldUnit).toFloat()
        }

        // The sea the belts' rivers reach, cut once on the ground as the window opens.
        val opening = SeaLevelStage.percentileCut(FloatField(cellsAcross, cellsDown, surface), provisionalSeaLevel, scale, 0f)
        val isLand = opening.isLand
        val shoreline = opening.shorelineHeight
        val landCells = opening.landCellCount.toFloat().coerceAtLeast(1f)
        val weights = FloatArray(cellCount)
        var rainSum = 0.0
        for (cell in 0 until cellCount) if (isLand[cell]) rainSum += rainfallMm[cell].toDouble()
        val rainMean = (rainSum / landCells).toFloat()
        for (cell in 0 until cellCount) weights[cell] = if (rainMean > 0f) rainfallMm[cell] / rainMean else 1f
        // The cover's shielding against its land mean, as the rounds take it, times the rock's own factor.
        val factor = FloatArray(cellCount)
        var coverSum = 0.0
        for (cell in 0 until cellCount) if (isLand[cell]) coverSum += (1f - COVER_SHIELDING * coverDensity[cell]).toDouble()
        val coverMean = (coverSum / landCells).toFloat()
        for (cell in 0 until cellCount) {
            val cover = if (shieldCut && coverMean > 0f) (1f - COVER_SHIELDING * coverDensity[cell]) / coverMean else 1f
            factor[cell] = cover * (rockErodibility?.get(cell) ?: 1f)
        }

        // The water reaching the belts from beyond them, routed once over the opening ground.
        val inflow = DoubleArray(beltCells)
        run {
            val field = FloatField(cellsAcross, cellsDown, opening.relativeElevation.data)
            val filled = FlowRouting.fillDepressions(cellsAcross, cellsDown, isLand, field)
            val directions = FlowRouting.flowDirections(
                cellsAcross, cellsDown, isLand, field, filled, config.seed, config.cellHeightInCellWidths,
                FlowRouting.smoothFieldLatticeColumns(config), config.facetRouting, config.flatPotential
            )
            val area = FlowRouting.accumulate(cellsAcross, cellsDown, isLand, filled, directions, opening.landCellCount) { weights[it] }
            for (cell in 0 until cellCount) {
                val receiver = directions[cell]
                if (receiver < 0 || localOf[cell] >= 0) continue
                val local = localOf[receiver]
                if (local >= 0) inflow[local] += area.data[cell].toDouble()
            }
        }

        // The ground just beyond the belts, which the slopes may shed onto.
        val frontier = ArrayList<Int>()
        val onFrontier = BooleanArray(cellCount)
        for (cell in belt) {
            forEachNeighbor(cellsAcross, cellsDown, cell) { neighbor ->
                if (localOf[neighbor] < 0 && !onFrontier[neighbor]) {
                    onFrontier[neighbor] = true
                    frontier.add(neighbor)
                }
            }
        }
        frontier.sort()
        val swept = (belt.toList() + frontier).toIntArray()

        val rates = HydraulicErosion.Rates(config)
        val steps = GroundSteps(config.cellHeightInCellWidths)
        val pondDepthField = HydraulicErosion.POND_DEPTH_METRES / metresPerFieldUnit
        val sweepsPerRound = (ErosionStage.sweepsFor(config) / erosion.hydraulicRounds.coerceAtLeast(1)).coerceAtLeast(1)
        val limits = ErosionStage.thermalLimits(config)
        // The courant coefficient is set for the shoreline-relative field the rounds route on; here
        // the drop is taken on the height field, whose land half is the relative field's unit.
        val courant = rates.courantCoefficient

        val routing = DoubleArray(cellCount) { surface[it].toDouble() }
        val filled = FloatArray(beltCells)
        val receiver = IntArray(beltCells)
        val discharge = DoubleArray(beltCells)
        val flux = DoubleArray(beltCells)
        val donors = IntArray(beltCells)
        val runOrder = IntArray(beltCells)
        val erodedMetres = FloatArray(cellCount)
        val visited = BooleanArray(beltCells)
        val heap = LongMinHeap(beltCells.coerceAtLeast(16))
        val cellAreaM2 = config.squareKilometresPerCell * SQUARE_METRES_PER_KM2

        val rebounding = isostasy.enabled
        val reference = if (rebounding) surface.copyOf() else FloatArray(0)
        val upliftedMetres = if (rebounding) FloatArray(cellCount) else FloatArray(0)
        val reboundMetres = if (rebounding) FloatArray(cellCount) else FloatArray(0)
        val lossMetres = FloatField(cellsAcross, cellsDown)
        val reboundShare = isostasy.continentalCrustDensity / isostasy.mantleDensity
        val crustSpreadKm = CrustMap.KERNEL_SPREAD_SPACINGS * config.tectonics.historyCellKm

        var uplifted = 0.0
        var cut = 0.0
        var toSea = 0.0
        var toLand = 0.0
        var shed = 0.0
        val next = FloatArray(swept.size)
        val giveRate = FloatArray(beltCells)

        for (round in 0 until rounds) {
            // The rock rises where the belt has begun to.
            for (local in 0 until beltCells) {
                if (round < firstRound[local]) continue
                val rise = risePerRoundField[local]
                val cell = belt[local]
                surface[cell] += rise
                if (upliftedMetres.isNotEmpty()) upliftedMetres[cell] += rise * metresPerFieldUnit
                uplifted += rise.toDouble() * metresPerFieldUnit * cellAreaM2
            }
            for (cell in swept) routing[cell] = surface[cell].toDouble()

            // Every belt cell's spill level, by a priority flood from the cells where its water can
            // leave it: beside the sea, beside ground beyond the belt, or under the sea itself.
            visited.fill(false)
            for (local in 0 until beltCells) {
                val cell = belt[local]
                if (!isLand[cell] || surface[cell] <= shoreline) {
                    filled[local] = surface[cell]
                    visited[local] = true
                    heap.push(FlowRouting.encode(surface[cell], local))
                    continue
                }
                var edge = false
                forEachNeighbor(cellsAcross, cellsDown, cell) { neighbor -> if (localOf[neighbor] < 0) edge = true }
                if (cell / cellsAcross == 0 || cell / cellsAcross == cellsDown - 1) edge = true
                if (edge) {
                    filled[local] = surface[cell]
                    visited[local] = true
                    heap.push(FlowRouting.encode(surface[cell], local))
                }
            }
            while (!heap.isEmpty()) {
                val local = FlowRouting.decodeIndex(heap.pop())
                val level = filled[local]
                forEachNeighbor(cellsAcross, cellsDown, belt[local]) { neighbor ->
                    val other = localOf[neighbor]
                    if (other >= 0 && !visited[other]) {
                        visited[other] = true
                        filled[other] = if (surface[neighbor] <= level) level + FlowRouting.FLAT_GRADIENT_STEP else surface[neighbor]
                        heap.push(FlowRouting.encode(filled[other], other))
                    }
                }
            }
            for (local in 0 until beltCells) {
                val cell = belt[local]
                if (isLand[cell] && surface[cell] > shoreline) routing[cell] = filled[local].toDouble()
            }
            // Where each land cell's water goes: the facet rule over the filled ground.
            parallelChunks(0, beltCells) { from, to ->
                for (local in from until to) {
                    val cell = belt[local]
                    receiver[local] = if (!isLand[cell] || surface[cell] <= shoreline) -1
                    else FlowRouting.facetReceiver(cellsAcross, cellsDown, routing, cell % cellsAcross, cell / cellsAcross, steps, config.seed)
                }
            }
            // The order the water runs in, every cell before the cell it drains to: counted from the
            // receivers themselves, not read off the flood, whose keys round two levels a few
            // hundred-millionths apart to one and may then pop a receiver after its donor.
            donors.fill(0)
            for (local in 0 until beltCells) {
                val down = receiver[local]
                if (down < 0) continue
                val downLocal = localOf[down]
                if (downLocal >= 0) donors[downLocal]++
            }
            var downstream = 0
            for (local in 0 until beltCells) if (donors[local] == 0) runOrder[downstream++] = local
            var walked = 0
            while (walked < downstream) {
                val down = receiver[runOrder[walked++]]
                if (down < 0) continue
                val downLocal = localOf[down]
                if (downLocal >= 0 && --donors[downLocal] == 0) runOrder[downstream++] = downLocal
            }
            // Water gathered from the top down.
            for (local in 0 until beltCells) {
                discharge[local] = weights[belt[local]] + inflow[local]
                flux[local] = 0.0
            }
            for (index in 0 until downstream) {
                val local = runOrder[index]
                val down = receiver[local]
                if (down < 0) continue
                val downLocal = localOf[down]
                if (downLocal >= 0) discharge[downLocal] += discharge[local]
            }
            // The cut, from the outlets up: each cell toward the level its receiver now grades to.
            for (index in downstream - 1 downTo 0) {
                val local = runOrder[index]
                val cell = belt[local]
                val down = receiver[local]
                if (down < 0) continue
                if (filled[local] - surface[cell] > pondDepthField) continue
                val downLocal = localOf[down]
                val base = when {
                    !isLand[down] || surface[down] <= shoreline -> shoreline
                    downLocal >= 0 && filled[downLocal] - surface[down] > pondDepthField -> filled[downLocal]
                    else -> surface[down]
                }
                val height = surface[cell]
                if (height <= base) continue
                val share = discharge[local] / landCells
                val courantNumber = courant * sqrt(share) * factor[cell] / steps.between(cell, down, cellsAcross)
                val after = (base + (height - base).toDouble() / (1.0 + courantNumber)).toFloat()
                surface[cell] = after
                val lostMetres = (height - after) * metresPerFieldUnit
                erodedMetres[cell] += lostMetres
                cut += lostMetres.toDouble() * cellAreaM2
                flux[local] += lostMetres.toDouble() * cellAreaM2
            }
            // The spoil carried down to where it leaves the belts, or to the hollow or the sea inside
            // one that holds it.
            for (index in 0 until downstream) {
                val local = runOrder[index]
                val down = receiver[local]
                if (down < 0) {
                    val cell = belt[local]
                    if (!isLand[cell] || surface[cell] <= shoreline) toSea += flux[local] else toLand += flux[local]
                    continue
                }
                val downLocal = localOf[down]
                when {
                    downLocal >= 0 -> flux[downLocal] += flux[local]
                    !isLand[down] || surface[down] <= shoreline -> toSea += flux[local]
                    else -> toLand += flux[local]
                }
            }

            // The slopes, relaxed toward their critical fall by the rounds' own sweeps.
            repeat(sweepsPerRound) {
                parallelChunks(0, beltCells) { from, to ->
                    for (local in from until to) {
                        val cell = belt[local]
                        val here = surface[cell]
                        var excess = 0f
                        var steepest = 0f
                        forEachNeighborWithLimit(cellsAcross, cellsDown, cell, limits) { neighbor, limit ->
                            val drop = here - surface[neighbor]
                            if (drop > 0f) {
                                if (drop > steepest) steepest = drop
                                if (drop > limit) excess += drop - limit
                            }
                        }
                        giveRate[local] = if (excess <= limits.eastWest * SETTLED_SHARE_OF_CRITICAL_SLOPE) 0f
                        else minOf(erosion.rate * excess, steepest * MAX_SHARE_OF_STEEPEST_DROP) / excess
                    }
                }
                parallelChunks(0, swept.size) { from, to ->
                    for (index in from until to) {
                        val cell = swept[index]
                        val here = surface[cell]
                        val own = localOf[cell]
                        var received = 0f
                        var given = 0f
                        forEachNeighborWithLimit(cellsAcross, cellsDown, cell, limits) { neighbor, limit ->
                            val incoming = surface[neighbor] - here
                            val giver = localOf[neighbor]
                            if (incoming > limit && giver >= 0) received += giveRate[giver] * (incoming - limit)
                            else if (-incoming > limit && own >= 0) given += giveRate[own] * (-incoming - limit)
                        }
                        next[index] = here - given + received
                    }
                }
                for (index in swept.indices) {
                    val cell = swept[index]
                    if (localOf[cell] < 0) shed += (next[index] - surface[cell]).toDouble() * metresPerFieldUnit * cellAreaM2
                    surface[cell] = next[index]
                }
            }

            // The crust answering everything the rivers and the slopes have taken so far: the rock
            // lost, less the rebound already given, smoothed to the crust's resolution, rebounds by
            // the crust's share of the mantle's density.
            if (rebounding && ((round + 1) % REBOUND_ROUNDS == 0 || round == rounds - 1)) {
                for (cell in 0 until cellCount) {
                    lossMetres.data[cell] = upliftedMetres[cell] + reboundMetres[cell] - (surface[cell] - reference[cell]) * metresPerFieldUnit
                }
                GaussianBlur.apply(lossMetres, crustSpreadKm / config.cellWidthKm, crustSpreadKm / config.cellHeightKm)
                for (cell in 0 until cellCount) {
                    val rebound = reboundShare * lossMetres.data[cell]
                    surface[cell] += (rebound - reboundMetres[cell]) / metresPerFieldUnit
                    reboundMetres[cell] = rebound
                }
            }
        }
        return BeltReplay(rounds, beltCells, uplifted, cut, toSea, toLand, shed, erodedMetres)
    }

    /** Each of the eight neighbors of [cell], wrapping east-west and stopping at the poles. */
    private inline fun forEachNeighbor(cellsAcross: Int, cellsDown: Int, cell: Int, visit: (Int) -> Unit) {
        val column = cell % cellsAcross
        val row = cell / cellsAcross
        for (rowStep in -1..1) {
            val neighborRow = row + rowStep
            if (neighborRow < 0 || neighborRow >= cellsDown) continue
            for (columnStep in -1..1) {
                if (rowStep == 0 && columnStep == 0) continue
                visit(neighborRow * cellsAcross + (column + columnStep + cellsAcross) % cellsAcross)
            }
        }
    }

    /** The same, with the critical drop toward each, as the thermal sweeps read it ([ErosionStage.thermalLimits]). */
    private inline fun forEachNeighborWithLimit(
        cellsAcross: Int,
        cellsDown: Int,
        cell: Int,
        limits: ThermalLimits,
        visit: (Int, Float) -> Unit
    ) {
        val column = cell % cellsAcross
        val row = cell / cellsAcross
        for (rowStep in -1..1) {
            val neighborRow = row + rowStep
            if (neighborRow < 0 || neighborRow >= cellsDown) continue
            for (columnStep in -1..1) {
                if (rowStep == 0 && columnStep == 0) continue
                val limit = when {
                    rowStep != 0 && columnStep != 0 -> limits.diagonal
                    rowStep != 0 -> limits.northSouth
                    else -> limits.eastWest
                }
                visit(neighborRow * cellsAcross + (column + columnStep + cellsAcross) % cellsAcross, limit)
            }
        }
    }

    /**
     * How often the crust answers the rock the replay takes off, in rounds: the rebound is the whole
     * loss's, so how often it is asked changes only how soon the rivers feel it, not where it ends;
     * every ten rounds is every 3.4 Myr, and the last round always asks.
     */
    private const val REBOUND_ROUNDS = 10

    /** [HydraulicErosion]'s cover shielding, the same half. */
    private const val COVER_SHIELDING = 0.5f

    /** The thermal sweeps' settled share and largest give, as [ErosionStage] holds them. */
    private const val SETTLED_SHARE_OF_CRITICAL_SLOPE = 1e-3f
    private const val MAX_SHARE_OF_STEEPEST_DROP = 0.5f

    /** Rounding only, so a window a whole number of rounds long counts all of them. */
    private const val ROUND_ROUNDING = 1e-9

    private const val METRES_PER_KM = 1_000f
    private const val MILLIMETRES_PER_METRE = 1_000.0
    private const val SQUARE_METRES_PER_KM2 = 1e6
}
