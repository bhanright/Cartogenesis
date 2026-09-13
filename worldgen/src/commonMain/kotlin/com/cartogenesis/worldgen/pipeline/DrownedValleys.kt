package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.SeaConfig
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.sqrt

/**
 * A valley narrower than the cell is not a bay.
 *
 * The lowstand drops the base level for nine of the twelve hydraulic rounds, so every channel that
 * reaches the coast is cut below where the sea stands today, and the transgression then floods
 * every one of them. On the grid a channel is a whole cell wide whatever it carries, so what comes
 * back is a notch at every stream mouth: on the four standard seeds and 298405 at 512, the coast
 * measured with a ruler of one cell against one of two gives a dimension of 1.582, where the same
 * coast measured from four cells to sixteen gives 1.260. A real coast gives much the same figure at
 * every scale — Richardson's plots are straight lines, which is the whole of Mandelbrot's argument —
 * and a disc drawn on this grid reads 1.006 against 1.000, so the excess is the coast and not the
 * ruler.
 *
 * Earth has the same valleys and does not show them. At six to twelve kilometres a coastline is
 * indented by the Chesapeake, the Severn and the Gironde and by nothing smaller; the Rias Baixas are
 * two to seven kilometres across and a 1024 map cannot hold one. So the question this pass asks of
 * each drowned cell is whether the valley behind it is wide enough for the cell to read as water,
 * and where it is not, the cell goes back to being what most of it is: land.
 *
 * What it costs, at 2048 on seed 718106: 536 ms, nearly all of it the priority flood that gives the
 * land its catchments and the flood fill that finds the ocean. Plan ground rule 8 asks for a GPU
 * path for per-cell arithmetic and says in the same breath that "work that is a graph walk, a
 * priority queue or a region labelling stays on the CPU and the spec says so" — this is both of
 * those and nothing else, so it stays. It is an eighth of the sea-level stage at that grid and a
 * hundredth of a generation. If it ever needs to be cheaper, the routing it wants is the routing
 * `SeaLevelStage.drainDrownedBasins` computes eight lines earlier and throws away.
 *
 * Not a change to the erosion. The lowstand's incision is right and the estuaries it makes are the
 * point of it; what is wrong is that a channel a kilometre wide is written into a cell twelve
 * kilometres wide at the channel's own depth. This corrects that at the cut, where the mask is
 * decided, and it corrects the elevation the same way — by mixing the channel's floor with the
 * ground either side of it in the proportion the channel actually occupies.
 *
 * What it is worth, on the same five seeds at 512, as the excess of the first octave over the last:
 * 0.322 before it, 0.270 with the littoral grading alone, 0.142 with this pass alone and 0.127 with
 * both. The ceiling on the mechanism is 0.094, which is what filling *every* drowned notch gives,
 * estuaries and all — so the 0.033 between that and 0.127 is the estuaries this keeps, and the 0.094
 * under it is not channels at all. That residue is the percentile cut through the erosion's own
 * texture at the cell: with the lowstand switched off entirely the excess is still 0.288, which is
 * why the repair `GEOGRAPHY.md` used to propose — scaling the stand by the local drainage — would
 * not have closed it either.
 */
internal object DrownedValleys {

    /**
     * How wide the mouth of a drowned valley is, in kilometres, per square root of the square
     * kilometres draining to it.
     *
     * The form is Leopold and Maddock's: a channel's width goes as the square root of its
     * discharge, and a catchment's area is the discharge this generator has. The constant is five
     * of Earth's drowned valleys, measured at the mouth against the basin behind it:
     *
     *     Chesapeake  71,000 km2   30 km   0.113
     *     Delaware    35,000 km2   18 km   0.096
     *     Severn      11,000 km2   10 km   0.095
     *     Thames      13,000 km2    8 km   0.070
     *     Gironde     81,000 km2   12 km   0.042
     *
     * Eight hundredths is the middle of those. It is the *valley* rather than the channel in it —
     * the Susquehanna is about a kilometre wide where the Chesapeake is thirty — because what
     * decides whether the map shows a bay is the width of the water, and the water in a drowned
     * valley is the valley.
     */
    const val ESTUARY_KILOMETRES_PER_ROOT_SQUARE_KILOMETRE = 0.08f

    /**
     * How much of a cell's width the water has to cross before the cell is drawn as water.
     *
     * A half: a cell is what most of it is, which is the same majority the littoral fill uses on the
     * nine cells around a re-entrant and the same one a coarsening of the mask would use. Below it
     * the cell is mostly dry ground with a channel through it, and the map should say so.
     *
     * One consequence is worth writing down, because it looks like a coincidence and is not. The
     * width goes as the square root of the area and the bar goes as the cell's width, so the
     * catchment a valley needs comes out as a fixed *number of cells* at every grid — about 39 —
     * and the rule means the same thing at 512, 1024 and 2048 without being told to.
     */
    const val RESOLVED_SHARE_OF_A_CELL = 0.5f

    /**
     * How much of the nine cells around a drowned cell must be land before it counts as a notch
     * rather than as open sea.
     *
     * Five, the same majority again. Without it the rule would reach out over any shallow shelf the
     * transgression covered — every cell of it is water the sea has lately taken — and start filling
     * open sea wherever no river happened to arrive. A notch has land on most sides; a shelf does
     * not.
     */
    const val EMBAYED_LAND_IN_NINE = 5

    /**
     * Fills back every drowned valley too narrow for its cell, and returns the cut with the
     * shoreline that leaves; or the cut itself where the pass is off or there was no lowstand to
     * drown anything.
     *
     * [height] is the eroded field the cut was taken from, which this needs for two things the
     * shoreline-relative field cannot give: where the lowstand shoreline was, and the ground either
     * side of a channel in the same units as the channel's own floor. [config] is read for
     * [SeaConfig], for the grid's own scale and for nothing else, so a re-run gives the same
     * answer.
     *
     * [resolvedShareOfCell] is [RESOLVED_SHARE_OF_A_CELL] everywhere but in the diagnosis that asks
     * what the coast would measure with *every* drowned notch filled, which is how much of the
     * cell-scale excess is channels at all. The same door `erodeBlocking` opens for the receiver
     * clamp, and for the same reason: it is not a taste, so nothing but a test may move it.
     */
    fun apply(
        cut: SeaLevelResult,
        height: FloatField,
        config: WorldGenConfig,
        resolvedShareOfCell: Float = RESOLVED_SHARE_OF_A_CELL
    ): SeaLevelResult {
        if (!config.sea.drownedValleyFill) return cut
        val scale = config.scale
        val cellsAcross = height.width
        val cellsDown = height.height
        val cellCount = cellsAcross * cellsDown
        if (cut.landCellCount == 0 || cut.landCellCount == cellCount) return cut

        val threshold = cut.shorelineHeight
        val landRelief = (height.max() - threshold).coerceAtLeast(1e-6f)
        // Everything the sea took when it came back up: ground within the postglacial rise of
        // today's shoreline. Nothing deeper is this pass's business, because a valley deeper than
        // the rise was an embayment before the sea moved.
        //
        // The rise as a figure, [LittoralGrading.POSTGLACIAL_RISE_METRES], rather than
        // [SeaConfig.lowstandMetres] as a setting, and the difference matters. That setting is a
        // switch as well as a number: `SeaLevelHistoryTest` turns it off to get the control it
        // compares the drowned coast against. Reading it here would leave the control with every
        // notch this pass exists to remove and take them all off the arm under test, which is not a
        // comparison of anything — measured, it took seed 7's indentation from 5.20 in the control
        // to 4.59 in the arm and inverted the claim the guard makes.
        //
        // A level in the raw height field, so it comes off the field's own ruler and not off either
        // half of the piecewise one: 120 m of the world's whole relief, the same conversion
        // [SeaConfig.lowstandMetres] takes. See [WorldScale.fieldShareOfMetres].
        val transgression =
            threshold - scale.fieldShareOfMetres(LittoralGrading.POSTGLACIAL_RISE_METRES)

        val drowned = drownedNotches(cut, height, transgression, oceanOf(cut))
        if (drowned.isEmpty()) return cut

        val arriving = catchmentArrivingByCell(cut, cellCount)
        carryCatchmentDownTheValleys(drowned, arriving, cut.isLand, height)

        val cellWidthKilometres = scale.cellWidthKm(cellsAcross).toFloat()
        // The cell's width squared, not its width times its height: the catchment a valley needs is
        // calibrated against this and the bar below is stated in cells of it. A cell of a square
        // grid on a 2:1 world is twice as wide as it is tall, so this is twice the ground a cell
        // really covers, and correcting it would double the catchment a valley must drain. Left as
        // F17 measured it and written down in `TODO.md` rather than changed inside a merge.
        val squareKilometresPerCell = cellWidthKilometres * cellWidthKilometres
        val resolvedWidthKilometres = resolvedShareOfCell * cellWidthKilometres

        // Highest first, which is from the head of each valley down: the catchment only grows
        // seaward, so a valley is unresolved from its head down to wherever it grows wide enough.
        val unresolved = ArrayList<Int>()
        val channelShareOfCell = FloatArray(cellCount)
        var kept = 0
        for (cell in drowned) {
            val widthKilometres = ESTUARY_KILOMETRES_PER_ROOT_SQUARE_KILOMETRE *
                sqrt(arriving[cell] * squareKilometresPerCell)
            if (widthKilometres >= resolvedWidthKilometres) {
                kept++
            } else {
                unresolved.add(cell)
                channelShareOfCell[cell] = widthKilometres / cellWidthKilometres
            }
        }
        lastRun = Census(drowned.size, unresolved.size, kept)
        if (unresolved.isEmpty()) return cut

        val isLand = cut.isLand.copyOf()
        val relative = cut.relativeElevation.copy()
        val filled = raiseTheValleyFloors(
            unresolved, cut, height, isLand, relative, threshold, landRelief,
            scale.fieldShareOfMetres(1f), channelShareOfCell
        )
        lastRun = Census(drowned.size, filled, kept)
        if (filled == 0) return cut
        return SeaLevelResult(threshold, isLand, relative, cut.landCellCount + filled)
    }

    /**
     * Raises the floors of the unresolved valleys, seaward end first, and returns how many came up.
     *
     * Three things have to be true of the surface this leaves, and one order of work gets all three.
     * It has to stand *above* the waterline, or the cell is land the map draws darker than the sea
     * and `DataExportTest` is right to complain. It has to fall towards the sea, or the fill is a
     * dam across the valley it filled and the drainage behind it ponds — which it did: standing
     * water on seed 59758 went to 1.21% of the land at 1024 against 0.62% at 512, and
     * `OutletResolutionTest` reads that as a different world at each size. And it must not stand
     * above the ground either side, because new ground laps onto a valley's walls rather than
     * burying them.
     *
     * So the floors are lifted a ring at a time out from the water, each cell taking its own
     * sub-grid height — the channel's floor and the ground either side of it, mixed in the
     * proportion the channel really occupies — and each having to stand above the ring seaward of
     * it, which is the shoreline itself at the mouth. Where it does not, the cell is not lifted and
     * the valley is left as water from there inland: the honest answer, because a valley whose walls
     * are no higher than the water at its mouth is a valley the sea is entitled to.
     *
     * [oneMetre] is one metre of altitude in the raw height field's units, which is that least
     * fall; [landRelief] is the field's own span above the shoreline, which is what the raised
     * floors are renormalised against so they read like every other land cell.
     */
    private fun raiseTheValleyFloors(
        unresolved: List<Int>,
        cut: SeaLevelResult,
        height: FloatField,
        isLand: BooleanArray,
        relative: FloatField,
        threshold: Float,
        landRelief: Float,
        oneMetre: Float,
        channelShareOfCell: FloatArray
    ): Int {
        val cellsAcross = height.width
        val cellsDown = height.height
        val wanted = HashSet(unresolved)
        val raised = HashMap<Int, Float>(unresolved.size * 2)

        var frontier = unresolved.filter { cell ->
            neighboursOf(cell, cellsAcross, cellsDown).any { !isLand[it] && it !in wanted }
        }
        var filled = 0
        while (frontier.isNotEmpty()) {
            val next = ArrayList<Int>()
            frontier.forEach { cell ->
                if (isLand[cell]) return@forEach
                val row = cell / cellsAcross
                val column = cell % cellsAcross
                if (WaterTopology.severs(isLand, row, column, cellsAcross, cellsDown)) return@forEach
                // The ground this cell must stand above: the shoreline where it touches open water,
                // and whatever the ring seaward of it was lifted to where it does not.
                var below = Float.MAX_VALUE
                neighboursOf(cell, cellsAcross, cellsDown).forEach { neighbour ->
                    val standing = raised[neighbour] ?: if (!isLand[neighbour]) threshold else null
                    if (standing != null && standing < below) below = standing
                }
                if (below == Float.MAX_VALUE) return@forEach
                val wall = middleOfTheGroundBeside(cell, height, cut.isLand)
                if (wall == Float.MAX_VALUE) return@forEach
                // The height the cell has once the channel is given the share of it it really
                // covers — and at least a metre above the ground seaward of it, because a floor
                // that does not fall towards the sea is a dam across the valley rather than a floor
                // under it. A metre is the least fall a surface can have and still be one; over a
                // cell that is four hundredths of a metre per kilometre, gentler than any coastal
                // plain, so it never argues with the mixture, it only breaks ties with it.
                val share = channelShareOfCell[cell]
                val mixed = height.data[cell] * share + wall * (1f - share)
                val floor = maxOf(mixed, below + oneMetre)
                // Never above the ground either side: new ground laps onto a valley's walls rather
                // than burying them, and where the walls are no higher than the water at the mouth
                // the valley is left as water from there inland.
                if (floor > wall) return@forEach

                isLand[cell] = true
                raised[cell] = floor
                relative.data[cell] = (floor - threshold) / landRelief
                filled++
                neighboursOf(cell, cellsAcross, cellsDown).forEach { neighbour ->
                    if (neighbour in wanted && !isLand[neighbour]) next.add(neighbour)
                }
            }
            frontier = next
        }
        return filled
    }

    /** The eight neighbours of a cell, wrapping in x and clamping at the poles. */
    private fun neighboursOf(cell: Int, cellsAcross: Int, cellsDown: Int): List<Int> {
        val row = cell / cellsAcross
        val column = cell % cellsAcross
        val neighbours = ArrayList<Int>(8)
        for (rowStep in -1..1) {
            val neighbourRow = row + rowStep
            if (neighbourRow < 0 || neighbourRow >= cellsDown) continue
            for (columnStep in -1..1) {
                if (rowStep == 0 && columnStep == 0) continue
                neighbours.add(neighbourRow * cellsAcross + (column + columnStep + cellsAcross) % cellsAcross)
            }
        }
        return neighbours
    }

    /**
     * Which water the ocean can reach, by the eight-connected flood fill the enclosure rule uses.
     *
     * The transgression is the *sea* coming back up, so an inland sea is not this pass's business:
     * whatever drowned its valleys, it was not the last ten thousand years, and it has no shoreline
     * the postglacial rise ran up. `SeaConfig.enclosedSeaIsLand` turns the small ones into land
     * before this runs and leaves the ones larger than any lake Earth has as water, and those are
     * the ones this skips.
     *
     * The ocean is the largest body rather than the one on some edge, for the reason
     * [SeaLevelStage.enclose] gives: the map has no edge in x and its poles are land as often as not.
     */
    private fun oceanOf(cut: SeaLevelResult): BooleanArray {
        val cellsAcross = cut.relativeElevation.width
        val cellsDown = cut.relativeElevation.height
        val size = cellsAcross * cellsDown
        val body = IntArray(size) { -1 }
        val stack = IntArray(size - cut.landCellCount)
        var bodies = 0
        var ocean = -1
        var oceanCells = 0
        for (start in 0 until size) {
            if (cut.isLand[start] || body[start] >= 0) continue
            val id = bodies++
            var top = 0
            body[start] = id
            stack[top++] = start
            var found = 0
            while (top > 0) {
                val cell = stack[--top]
                found++
                FlowRouting.forEachNeighbour(cellsAcross, cellsDown, cell % cellsAcross, cell / cellsAcross) { n ->
                    if (!cut.isLand[n] && body[n] < 0) {
                        body[n] = id
                        stack[top++] = n
                    }
                }
            }
            if (found > oceanCells) {
                oceanCells = found
                ocean = id
            }
        }
        return BooleanArray(size) { body[it] == ocean }
    }

    /** How many notches the last run found, filled and left as water. Diagnostics only. */
    class Census(val found: Int, val filled: Int, val kept: Int)

    /**
     * What the last call to [apply] did, for the report that asks how much of the coast is channels.
     *
     * Nothing reads it back and nothing depends on it, which is the only reason a mutable field is
     * tolerable in a stage: a second call overwrites it and the world is unchanged either way.
     */
    var lastRun: Census = Census(0, 0, 0)
        private set

    /**
     * The drowned cells this pass may act on, highest first: water the transgression made, with land
     * on most of the nine cells around it.
     */
    private fun drownedNotches(
        cut: SeaLevelResult,
        height: FloatField,
        lowstandShoreline: Float,
        isOcean: BooleanArray
    ): IntArray {
        val cellsAcross = height.width
        val cellsDown = height.height
        val candidates = ArrayList<Int>()
        for (row in 0 until cellsDown) {
            for (column in 0 until cellsAcross) {
                val cell = row * cellsAcross + column
                if (!isOcean[cell] || height.data[cell] < lowstandShoreline) continue
                var land = 0
                for (rowStep in -1..1) {
                    val neighbourRow = (row + rowStep).coerceIn(0, cellsDown - 1)
                    for (columnStep in -1..1) {
                        val neighbourColumn = (column + columnStep + cellsAcross) % cellsAcross
                        if (cut.isLand[neighbourRow * cellsAcross + neighbourColumn]) land++
                    }
                }
                if (land >= EMBAYED_LAND_IN_NINE) candidates.add(cell)
            }
        }
        // Highest first, and ties broken by the cell's own index, so the order is the same every
        // time this runs and the world does not depend on how the sort happened to settle.
        candidates.sortWith(
            compareByDescending<Int> { height.data[it] }.thenBy { it }
        )
        return candidates.toIntArray()
    }

    /**
     * How many cells of land drain straight into each cell of water.
     *
     * The engine's own routing over the land — a priority flood, a D8 step and one accumulation —
     * so a catchment here means what it means everywhere else in the pipeline. A graph walk and a
     * priority queue, which by plan ground rule 8 is work that stays on the processor.
     */
    private fun catchmentArrivingByCell(cut: SeaLevelResult, cellCount: Int): FloatArray {
        val cellsAcross = cut.relativeElevation.width
        val cellsDown = cut.relativeElevation.height
        val filled = FlowRouting.fillDepressions(
            cellsAcross, cellsDown, cut.isLand, cut.relativeElevation
        )
        val directions = FlowRouting.flowDirections(
            cellsAcross, cellsDown, cut.isLand, cut.relativeElevation, filled
        )
        val catchment = FlowRouting.accumulate(
            cellsAcross, cellsDown, cut.isLand, filled, directions, cut.landCellCount
        ) { 1f }
        val arriving = FloatArray(cellCount)
        for (cell in 0 until cellCount) {
            if (!cut.isLand[cell]) continue
            val target = directions[cell]
            if (target >= 0 && !cut.isLand[target]) arriving[target] += catchment.data[cell]
        }
        return arriving
    }

    /**
     * Carries what arrives at the head of a drowned valley down it, so a cell's figure is the whole
     * catchment above it rather than the strip of hillside beside it.
     *
     * Down the steepest water neighbour, which in a drowned valley is the valley, and taken highest
     * first so a cell's own total is complete before it passes it on — the same order
     * [FlowRouting.accumulate] walks the land in, for the same reason.
     */
    private fun carryCatchmentDownTheValleys(
        drowned: IntArray,
        arriving: FloatArray,
        isLand: BooleanArray,
        height: FloatField
    ) {
        val cellsAcross = height.width
        val cellsDown = height.height
        for (cell in drowned) {
            val row = cell / cellsAcross
            val column = cell % cellsAcross
            var lowest = -1
            var lowestHeight = height.data[cell]
            for (rowStep in -1..1) {
                val neighbourRow = row + rowStep
                if (neighbourRow < 0 || neighbourRow >= cellsDown) continue
                for (columnStep in -1..1) {
                    if (rowStep == 0 && columnStep == 0) continue
                    val neighbourColumn = (column + columnStep + cellsAcross) % cellsAcross
                    val neighbour = neighbourRow * cellsAcross + neighbourColumn
                    if (isLand[neighbour]) continue
                    if (height.data[neighbour] < lowestHeight) {
                        lowestHeight = height.data[neighbour]
                        lowest = neighbour
                    }
                }
            }
            if (lowest >= 0) arriving[lowest] += arriving[cell]
        }
    }

    /**
     * The ground either side of the channel: the middle of the land next door, not the lowest of it.
     *
     * The cell's own height is the channel's floor, which the erosion cut. Mixing the two in the
     * proportion the channel occupies is the whole of the sub-grid correction this pass makes: where
     * a channel is a tenth of a cell, the cell stands a tenth of the way down from the ground beside
     * it towards the channel, which is above the shoreline and so land.
     *
     * The middle rather than the lowest, because what the mixture wants is a figure for the nine
     * tenths of the cell that is not channel, and the minimum of eight neighbours is a biased
     * estimate of it — on a valley floor at least one of the eight is the next cell of the same
     * valley wall, at the same height as the water. Taking the minimum stops the fill a cell or two
     * up most valleys and leaves about a hundredth more excess at the cell. It is still a ceiling
     * and never a target, so a cell is only ever lifted as far as the ground around it.
     */
    private fun middleOfTheGroundBeside(
        cell: Int,
        height: FloatField,
        wasLand: BooleanArray
    ): Float {
        val cellsAcross = height.width
        val cellsDown = height.height
        val row = cell / cellsAcross
        val column = cell % cellsAcross
        val beside = ArrayList<Float>(8)
        for (rowStep in -1..1) {
            val neighbourRow = row + rowStep
            if (neighbourRow < 0 || neighbourRow >= cellsDown) continue
            for (columnStep in -1..1) {
                if (rowStep == 0 && columnStep == 0) continue
                val neighbourColumn = (column + columnStep + cellsAcross) % cellsAcross
                val neighbour = neighbourRow * cellsAcross + neighbourColumn
                if (wasLand[neighbour]) beside.add(height.data[neighbour])
            }
        }
        if (beside.isEmpty()) return Float.MAX_VALUE
        beside.sort()
        return beside[beside.size / 2]
    }
}

