package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.model.WorldGenConfig

/**
 * The land divided into catchments — the pieces realms are actually built from.
 *
 * A realm grown cell by cell puts its border wherever two expansions happened to meet, which is
 * roughly equidistant from two capitals and has nothing to do with the ground. Measured, such
 * borders followed rivers 0.89 times as often as blank land did and sat on slopes 1.12 times the
 * average: near enough to arbitrary, and not fixable by making ridges dear, because a ridge is a
 * few cells against a journey of hundreds.
 *
 * Building from catchments answers it by construction rather than by persuasion. The edge of a
 * catchment *is* the watershed, and a watershed *is* a ridge — so a border between two units runs
 * along high ground because there is nowhere else for it to run. And a river inside a catchment
 * stays inside it, whole, rather than being sliced down the middle by a frontier that happened to
 * stop there.
 *
 * The pieces are sub-catchments rather than whole river basins. A single basin can be a fifth of a
 * continent, which would make every realm enormous and identical; cutting each one at its
 * confluences gives tributary catchments of varied size, which is both what a real river system
 * looks like from above and what gives realms room to differ from each other.
 */
internal class BasinUnits(
    /** Which unit each cell belongs to. [NONE] for water. */
    val unitOf: IntArray,
    val unitCount: Int,
    /** Cells in each unit. */
    val area: IntArray,
    /** Units sharing a border with each one, whether by land or across a narrow strait. */
    val neighbours: Array<IntArray>,
    /**
     * Units sharing a *land* border with each one — [neighbours] without the sea crossings.
     *
     * Needed to tell an enclave from an island. A pocket of one realm surrounded by another is an
     * accident of the growth order and should be dissolved; an island that belongs to a realm
     * overseas is a real thing, and the difference between them is whether you can walk out.
     */
    val landNeighbours: Array<IntArray>,
    /**
     * Which landmass each unit sits on — units reachable from each other on foot.
     *
     * Realms are seeded per landmass rather than purely on the best ground, so a second continent
     * gets countries of its own instead of being annexed by whoever is nearest across the water.
     */
    val landmass: IntArray,
    val landmassCount: Int
) {
    companion object {
        const val NONE = -1
    }
}

internal object BasinPartition {

    /**
     * The land cut into catchments no larger than [maxUnitAreaKm2] of ground each, every cell in
     * the unit of the water it drains to.
     *
     * **Receivers first.** A cell joins the unit its receiver is in, so its receiver has to be
     * settled before it is: the walk is [FlowRouting.drainageOrder], which lists sources first,
     * taken in reverse. Ordering by the filled surface instead, lowest first, was right only while
     * every receiver stood lower on the fill than its donor, and since the flats and the closed
     * basins are routed along the true ground — [FlatRouting]'s potential, and
     * [LakeWaterBalance.routeIntoWater] — a receiver is often higher. The old walk opened a new unit
     * wherever it met one not yet settled, and those orphan units, a row or a column of a flat at a
     * time, drew realm borders ruler-straight along the grid. Not [FlowRouting.heightOrder], for the
     * same reason: it is the filled surface's order and not the network's.
     *
     * **Closed basins whole.** Every cell of an endorheic lake or a playa is a sink, and a sink opens
     * a unit; so each of them opened its own, and a closed basin came apart into as many units as
     * it had cells of water. The water of one lake, or one connected playa, is taken as one sink
     * instead, and the land draining to it joins it up to the size rule below.
     *
     * **The size rule is an area on the ground.** Each cell's open area is its own ground plus
     * whatever of its tributaries' it keeps; walking sources first, a cell keeps its tributaries
     * smallest first while the total stays within [maxUnitAreaKm2] and cuts the rest at their
     * mouths, where each becomes a unit of its own. So a unit is cut at a confluence, as before, and
     * no unit holds more ground than the limit (a lake's own water is never cut, so a lake larger
     * than the limit is the one exception). What it replaced compared the rain-weighted flow with a
     * count of cells, so a dry catchment, whose cells weigh a fraction of a wet one's, ran seven to
     * twenty times the configured share before anything cut it.
     *
     * [rivers] supplies the routing and the lakes; [sea] the land. Every land cell ends in exactly
     * one unit and every unit lies on one landmass, since each cell drains to a neighbour.
     */
    fun compute(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        rivers: RiverResult,
        maxUnitAreaKm2: Double
    ): BasinUnits {
        val cellsAcross = config.width
        val cellsDown = config.height
        val cellCount = cellsAcross * cellsDown
        val cellAreaKm2 = config.squareKilometresPerCell
        val receiver = receiversWithClosedBasinsWhole(cellsAcross, cellsDown, sea, rivers)
        val mustKeep = BooleanArray(cellCount)
        for (cell in 0 until cellCount) {
            val downstream = receiver[cell]
            if (downstream >= 0 && rivers.flowTarget[cell] < 0) mustKeep[cell] = true
        }
        val sourcesFirst = FlowRouting.drainageOrder(
            cellsAcross, cellsDown, sea.isLand, receiver, sea.landCellCount
        )

        // Tributaries of each cell, in cell-index order, as one flat table: the cells draining into
        // `cell` are tributary[firstTributary[cell] until firstTributary[cell + 1]].
        val firstTributary = IntArray(cellCount + 1)
        for (cell in 0 until cellCount) {
            val downstream = receiver[cell]
            if (sea.isLand[cell] && downstream >= 0) firstTributary[downstream + 1]++
        }
        for (cell in 0 until cellCount) firstTributary[cell + 1] += firstTributary[cell]
        val tributary = IntArray(firstTributary[cellCount])
        val written = firstTributary.copyOf(cellCount)
        for (cell in 0 until cellCount) {
            val downstream = receiver[cell]
            if (sea.isLand[cell] && downstream >= 0) tributary[written[downstream]++] = cell
        }

        // Sources first: each cell's open area, and the mouths where a tributary is cut off.
        val openAreaKm2 = DoubleArray(cellCount)
        val opensUnit = BooleanArray(cellCount)
        val kept = ArrayList<Int>()
        for (cell in sourcesFirst) {
            var area = cellAreaKm2
            kept.clear()
            for (slot in firstTributary[cell] until firstTributary[cell + 1]) {
                val upstream = tributary[slot]
                if (mustKeep[upstream]) area += openAreaKm2[upstream] else kept.add(upstream)
            }
            kept.sortWith(compareBy<Int> { openAreaKm2[it] }.thenBy { it })
            for (upstream in kept) {
                if (area + openAreaKm2[upstream] <= maxUnitAreaKm2) {
                    area += openAreaKm2[upstream]
                } else {
                    opensUnit[upstream] = true
                }
            }
            openAreaKm2[cell] = area
            if (receiver[cell] < 0) opensUnit[cell] = true
        }

        // Receivers first: every cell joins its receiver's unit, or opens its own.
        val unitOf = IntArray(cellCount) { BasinUnits.NONE }
        var unitCount = 0
        for (rank in sourcesFirst.indices.reversed()) {
            val cell = sourcesFirst[rank]
            unitOf[cell] = if (opensUnit[cell]) {
                unitCount++
            } else {
                val joined = unitOf[receiver[cell]]
                check(joined != BasinUnits.NONE) {
                    "BasinPartition: cell $cell reached before its receiver ${receiver[cell]}; " +
                        "the drainage order is not topological"
                }
                joined
            }
        }

        val area = IntArray(unitCount)
        for (cell in 0 until cellCount) if (unitOf[cell] != BasinUnits.NONE) area[unitOf[cell]]++
        return build(cellsAcross, cellsDown, sea, unitOf, unitCount, area)
    }

    /**
     * [RiverResult.flowTarget] with each closed basin's water gathered into one sink: every cell of
     * one endorheic lake, or of one connected stretch of playa, drains to the lowest-indexed cell
     * of it, which drains nowhere. Water cells are sinks in the routing itself (see
     * [LakeWaterBalance.routeIntoWater]), so this adds edges only between cells that had none, and
     * the result is still a forest. -1 wherever the water leaves the land.
     */
    private fun receiversWithClosedBasinsWhole(
        cellsAcross: Int,
        cellsDown: Int,
        sea: SeaLevelResult,
        rivers: RiverResult
    ): IntArray {
        val cellCount = cellsAcross * cellsDown
        val lakes = rivers.lakes
        val receiver = IntArray(cellCount) { cell ->
            val downstream = rivers.flowTarget[cell]
            if (sea.isLand[cell] && downstream >= 0 && sea.isLand[downstream]) downstream else -1
        }
        val sinkOf = IntArray(cellCount) { -1 }
        val stack = ArrayDeque<Int>()
        for (start in 0 until cellCount) {
            if (!sea.isLand[start] || receiver[start] >= 0 || sinkOf[start] >= 0) continue
            val closedLake = lakes.isLake(start) && lakes.lakes[lakes.lakeId[start]].endorheic
            if (!closedLake && !lakes.isPlaya(start)) continue
            sinkOf[start] = start
            stack.addLast(start)
            while (stack.isNotEmpty()) {
                val cell = stack.removeLast()
                FlowRouting.forEachNeighbour(
                    cellsAcross, cellsDown, cell % cellsAcross, cell / cellsAcross
                ) { neighbour ->
                    val sameWater = sinkOf[neighbour] < 0 && receiver[neighbour] < 0 &&
                        sea.isLand[neighbour] &&
                        (if (closedLake) lakes.lakeId[neighbour] == lakes.lakeId[start]
                        else lakes.isPlaya(neighbour))
                    if (sameWater) {
                        sinkOf[neighbour] = start
                        receiver[neighbour] = start
                        stack.addLast(neighbour)
                    }
                }
            }
        }
        return receiver
    }

    /**
     * Merges each unit smaller than [minUnitAreaKm2] into the neighbour on its own landmass it
     * shares the longest border with, so long as the two together stay within [maxUnitAreaKm2].
     *
     * Coastlines produce a great many tiny catchments — every gully reaching the sea is its own
     * terminus — and left alone they would make realms out of slivers. Merging by shared edge keeps
     * the result compact rather than stringy.
     *
     * **Over land only.** The strait crossings in [BasinUnits.neighbours] are for realms, which do
     * put to sea; a catchment does not, and a small island merged into a unit across the water made
     * one unit of two landmasses, which the landmass flood in [build] then read as one. **Never
     * past the limit**, because a merge is the one step after [compute] that can grow a unit, and
     * [compute]'s bound is only worth what the merge keeps of it. A unit with no land neighbour it
     * fits beside stays as it is. The border is measured on the ground, a column's side being a
     * row's height and a row's side a column's width; a neighbour met only corner to corner comes
     * last. Ties go to the lower unit id.
     */
    fun mergeSmall(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        units: BasinUnits,
        minUnitAreaKm2: Double,
        maxUnitAreaKm2: Double
    ): BasinUnits {
        val cellsAcross = config.width
        val cellsDown = config.height
        val cellAreaKm2 = config.squareKilometresPerCell
        val border = SharedBorders.of(cellsAcross, cellsDown, units)
        // Union-find: each unit points at the one it was merged into, or at itself.
        val mergedInto = IntArray(units.unitCount) { it }

        fun resolve(unit: Int): Int {
            var root = unit
            while (mergedInto[root] != root) root = mergedInto[root]
            // Point every unit on the way at the root, so the next walk is one step.
            var walk = unit
            while (mergedInto[walk] != walk) {
                val next = mergedInto[walk]
                mergedInto[walk] = root
                walk = next
            }
            return root
        }

        // Smallest first, so a merged unit can itself go on to absorb or be absorbed sensibly.
        val smallestFirst = (0 until units.unitCount).sortedWith(
            compareBy<Int> { units.area[it] }.thenBy { it }
        )
        val area = units.area.copyOf()
        for (unit in smallestFirst) {
            val root = resolve(unit)
            if (area[root] * cellAreaKm2 >= minUnitAreaKm2) continue
            var host = -1
            var hostBorderKm = -1.0
            var hostCorners = -1
            border.forEachNeighbourOf(root) { other, borderSides ->
                val mergedKm2 = (area[root] + area[other]) * cellAreaKm2
                if (mergedKm2 > maxUnitAreaKm2) return@forEachNeighbourOf
                val borderKm = borderSides.kilometres(config.cellWidthKm, config.cellHeightKm)
                val better = borderKm > hostBorderKm ||
                    (borderKm == hostBorderKm && borderSides.corners > hostCorners) ||
                    (borderKm == hostBorderKm && borderSides.corners == hostCorners && other < host)
                if (better) {
                    host = other
                    hostBorderKm = borderKm
                    hostCorners = borderSides.corners
                }
            }
            if (host < 0) continue
            mergedInto[root] = host
            area[host] += area[root]
            border.merge(root, into = host)
        }

        // Renumber so the ids are contiguous again.
        val renumbered = HashMap<Int, Int>()
        val unitOf = IntArray(units.unitOf.size) { BasinUnits.NONE }
        for (cell in units.unitOf.indices) {
            val unit = units.unitOf[cell]
            if (unit == BasinUnits.NONE) continue
            val root = resolve(unit)
            unitOf[cell] = renumbered.getOrPut(root) { renumbered.size }
        }
        val unitCount = renumbered.size
        val areas = IntArray(unitCount)
        for (cell in unitOf.indices) if (unitOf[cell] != BasinUnits.NONE) areas[unitOf[cell]]++

        return build(cellsAcross, cellsDown, sea, unitOf, unitCount, areas)
    }

    /**
     * How long a border two units share, as counts of cell sides: [acrossRows] pairs side by side
     * in a row, each sharing a side a row's height long; [acrossColumns] pairs one above the other,
     * each a column's width; and [corners], pairs that touch only corner to corner.
     */
    private class BorderSides(
        var acrossRows: Int = 0,
        var acrossColumns: Int = 0,
        var corners: Int = 0
    ) {
        fun kilometres(cellWidthKm: Double, cellHeightKm: Double): Double =
            acrossRows * cellHeightKm + acrossColumns * cellWidthKm

        fun add(other: BorderSides) {
            acrossRows += other.acrossRows
            acrossColumns += other.acrossColumns
            corners += other.corners
        }
    }

    /**
     * Every pair of units that touch on land, with the border between them, kept current through
     * merges. Counts rather than lengths, so the order a merge adds them up in cannot move a bit.
     */
    private class SharedBorders(private val bordersOf: Array<HashMap<Int, BorderSides>>) {

        /** [action] on each unit touching [unit] by land, in ascending id order. */
        fun forEachNeighbourOf(unit: Int, action: (Int, BorderSides) -> Unit) {
            val borders = bordersOf[unit]
            for (other in borders.keys.sorted()) action(other, borders.getValue(other))
        }

        fun merge(unit: Int, into: Int) {
            val moving = bordersOf[unit]
            val host = bordersOf[into]
            for ((other, sides) in moving) {
                if (other == into) continue
                host.getOrPut(other) { BorderSides() }.add(sides)
                val back = bordersOf[other]
                back.remove(unit)
                back.getOrPut(into) { BorderSides() }.add(sides)
            }
            host.remove(unit)
            moving.clear()
        }

        companion object {
            fun of(cellsAcross: Int, cellsDown: Int, units: BasinUnits): SharedBorders {
                val bordersOf = Array(units.unitCount) { HashMap<Int, BorderSides>() }
                fun touch(unit: Int, other: Int, record: (BorderSides) -> Unit) {
                    if (unit == BasinUnits.NONE || other == BasinUnits.NONE || unit == other) return
                    record(bordersOf[unit].getOrPut(other) { BorderSides() })
                    record(bordersOf[other].getOrPut(unit) { BorderSides() })
                }
                for (row in 0 until cellsDown) {
                    for (column in 0 until cellsAcross) {
                        val unit = units.unitOf[row * cellsAcross + column]
                        if (unit == BasinUnits.NONE) continue
                        val east = row * cellsAcross + (column + 1) % cellsAcross
                        touch(unit, units.unitOf[east]) { it.acrossRows++ }
                        if (row + 1 == cellsDown) continue
                        val south = (row + 1) * cellsAcross + column
                        touch(unit, units.unitOf[south]) { it.acrossColumns++ }
                        val southEast = (row + 1) * cellsAcross + (column + 1) % cellsAcross
                        touch(unit, units.unitOf[southEast]) { it.corners++ }
                        val southWest =
                            (row + 1) * cellsAcross + (column + cellsAcross - 1) % cellsAcross
                        touch(unit, units.unitOf[southWest]) { it.corners++ }
                    }
                }
                return SharedBorders(bordersOf)
            }
        }
    }

    /**
     * Cuts a catchment in two along its trunk river, so the water becomes an edge rather than a
     * spine.
     *
     * Without this the basin model produces only one kind of border. A catchment contains its
     * river, so the river is interior and every frontier is a watershed — measured, borders went
     * from following rivers 1.14 times as often as blank land to 0.60, which is to say they began
     * avoiding them. Real borders are both kinds: the Pyrenees are a divide and the Rio Grande is
     * a river, and a world with only divides is as one-note as a world with neither.
     *
     * A cell is put on the left or right bank by walking downstream until it meets the trunk, then
     * taking the sign of the cross product between the trunk's own direction and the direction the
     * water came in from. Cells on the trunk itself go with the left bank, so the channel stays
     * whole and the seam runs along its far edge.
     */
    fun splitAlongTrunks(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        rivers: RiverResult,
        units: BasinUnits,
        minTrunkFlow: Float
    ): BasinUnits {
        val cellsAcross = config.width
        val cellsDown = config.height
        val flowTarget = rivers.flowTarget
        val accumulation = rivers.flowAccumulation.data

        // The outlet of each unit: the cell it all drains through, which is the one carrying most.
        val outlet = IntArray(units.unitCount) { -1 }
        for (cell in units.unitOf.indices) {
            val unit = units.unitOf[cell]
            if (unit == BasinUnits.NONE) continue
            val best = outlet[unit]
            if (best < 0 || accumulation[cell] > accumulation[best]) outlet[unit] = cell
        }

        // Upstream neighbours, so a trunk can be walked back from the mouth.
        val upstreamOf = HashMap<Int, MutableList<Int>>()
        for (cell in units.unitOf.indices) {
            if (units.unitOf[cell] == BasinUnits.NONE) continue
            val downstream = flowTarget[cell]
            if (downstream >= 0 && sea.isLand[downstream]) {
                upstreamOf.getOrPut(downstream) { ArrayList() }.add(cell)
            }
        }

        val bankOf = IntArray(units.unitOf.size) { NO_BANK }
        val onTrunk = BooleanArray(units.unitOf.size)
        var splitAny = false

        for (unit in 0 until units.unitCount) {
            val mouth = outlet[unit]
            if (mouth < 0 || accumulation[mouth] < minTrunkFlow) continue

            // Walk up the trunk, always following the branch carrying the most water.
            val trunk = ArrayList<Int>()
            var cursor = mouth
            while (true) {
                trunk.add(cursor)
                onTrunk[cursor] = true
                val next = upstreamOf[cursor]
                    ?.filter { units.unitOf[it] == unit }
                    ?.maxByOrNull { accumulation[it] } ?: break
                if (accumulation[next] < minTrunkFlow * TRUNK_HEADWATER_SHARE) break
                cursor = next
            }
            if (trunk.size < MIN_TRUNK_CELLS) continue

            // Which way the trunk runs at each of its cells, for the cross product below.
            val trunkStepAcross = HashMap<Int, Int>()
            val trunkStepDown = HashMap<Int, Int>()
            for (step in trunk.indices) {
                val cell = trunk[step]
                val downstream = if (step == 0) flowTarget[cell] else trunk[step - 1]
                if (downstream < 0) continue
                trunkStepAcross[cell] = wrapDelta(
                    downstream % cellsAcross - cell % cellsAcross, cellsAcross
                )
                trunkStepDown[cell] = downstream / cellsAcross - cell / cellsAcross
            }

            // Everything else follows its water down to the trunk and takes a bank from where it
            // arrived. Memoised, so a long tributary is walked once rather than once per cell.
            for (start in units.unitOf.indices) {
                if (units.unitOf[start] != unit || bankOf[start] >= 0) continue
                val path = ArrayList<Int>()
                var cell = start
                var bank = NO_BANK
                while (true) {
                    if (onTrunk[cell]) { bank = LEFT_BANK; break }
                    if (bankOf[cell] >= 0) { bank = bankOf[cell]; break }
                    path.add(cell)
                    val downstream = flowTarget[cell]
                    if (downstream < 0 || units.unitOf[downstream] != unit) {
                        bank = LEFT_BANK
                        break
                    }
                    // The cell that flows into the trunk decides the bank for everything behind it.
                    if (onTrunk[downstream]) {
                        val trunkAcross = trunkStepAcross[downstream] ?: 0
                        val trunkDown = trunkStepDown[downstream] ?: 0
                        val joinAcross = wrapDelta(
                            cell % cellsAcross - downstream % cellsAcross, cellsAcross
                        )
                        val joinDown = cell / cellsAcross - downstream / cellsAcross
                        // Sign of the cross product: which side of the trunk the water came in on.
                        bank = if (trunkAcross * joinDown - trunkDown * joinAcross >= 0) {
                            LEFT_BANK
                        } else {
                            RIGHT_BANK
                        }
                        break
                    }
                    cell = downstream
                }
                path.forEach { bankOf[it] = bank }
            }
            // The channel itself goes with the left bank, so it stays whole and the seam runs
            // along its far edge.
            trunk.forEach { bankOf[it] = LEFT_BANK }
            splitAny = true
        }

        if (!splitAny) return units

        // Renumber: each unit becomes at most two.
        val unitOf = IntArray(units.unitOf.size) { BasinUnits.NONE }
        val renumbered = HashMap<Long, Int>()
        for (cell in units.unitOf.indices) {
            val unit = units.unitOf[cell]
            if (unit == BasinUnits.NONE) continue
            val bank = if (bankOf[cell] == RIGHT_BANK) 1L else 0L
            unitOf[cell] = renumbered.getOrPut(unit * 2L + bank) { renumbered.size }
        }
        val unitCount = renumbered.size
        val areas = IntArray(unitCount)
        for (cell in unitOf.indices) if (unitOf[cell] != BasinUnits.NONE) areas[unitOf[cell]]++
        return build(cellsAcross, cellsDown, sea, unitOf, unitCount, areas)
    }

    /** Which side of its trunk a cell drains in from, and "not yet decided". */
    private const val NO_BANK = -1
    private const val LEFT_BANK = 0
    private const val RIGHT_BANK = 1

    /**
     * Where the walk up a trunk stops, as a share of the flow that made the trunk worth splitting
     * on in the first place.
     *
     * A river's headwaters are not a border — a seam drawn up a gully is a line through country
     * nobody thinks of as divided — so the walk stops once the channel has thinned to a seventh of
     * the flow at its mouth.
     */
    private const val TRUNK_HEADWATER_SHARE = 0.15f

    /**
     * Shortest trunk worth cutting a catchment along, in cells. Below this the two banks are not
     * two pieces of country, they are one piece with a stream in it.
     */
    private const val MIN_TRUNK_CELLS = 8

    /** Column difference on a cylinder, where a step across the seam is still one cell. */
    private fun wrapDelta(delta: Int, width: Int): Int = when {
        delta > width / 2 -> delta - width
        delta < -width / 2 -> delta + width
        else -> delta
    }


    /** Assembles a [BasinUnits] from a labelling: neighbours, and which landmass each unit is on. */
    private fun build(
        cellsAcross: Int,
        cellsDown: Int,
        sea: SeaLevelResult,
        unitOf: IntArray,
        unitCount: Int,
        area: IntArray
    ): BasinUnits {
        val (neighbours, landNeighbours) =
            adjacency(cellsAcross, cellsDown, sea, unitOf, unitCount)

        // Landmasses: units joined by dry ground only. Straits are deliberately excluded, since the
        // whole point is to know when a realm would have to put to sea.
        val landmass = IntArray(unitCount) { -1 }
        var landmassCount = 0
        for (start in 0 until unitCount) {
            if (landmass[start] >= 0 || area[start] == 0) continue
            val landmassId = landmassCount++
            val frontier = ArrayDeque<Int>()
            frontier.addLast(start)
            landmass[start] = landmassId
            while (frontier.isNotEmpty()) {
                val unit = frontier.removeFirst()
                landNeighbours[unit].forEach { neighbour ->
                    if (landmass[neighbour] < 0) {
                        landmass[neighbour] = landmassId
                        frontier.addLast(neighbour)
                    }
                }
            }
        }
        return BasinUnits(
            unitOf, unitCount, area, neighbours, landNeighbours, landmass, landmassCount
        )
    }

    /**
     * Which catchments touch which — across a narrow strait as well as across dry ground.
     *
     * The sea crossing is not a nicety. Catchments only ever border their neighbours on the same
     * landmass, so a realm built from them cannot reach an island or a second continent at all,
     * and the first render of this showed an entire southern landmass left blank. People have
     * always crossed narrow water more readily than they cross a mountain range, and a model that
     * cannot is worse than the cell-by-cell one it replaced.
     */
    private fun adjacency(
        cellsAcross: Int,
        cellsDown: Int,
        sea: SeaLevelResult,
        unitOf: IntArray,
        unitCount: Int
    ): Pair<Array<IntArray>, Array<IntArray>> {
        val neighboursOf = Array(unitCount) { HashSet<Int>() }
        for (row in 0 until cellsDown) {
            for (column in 0 until cellsAcross) {
                val cell = row * cellsAcross + column
                val unit = unitOf[cell]
                if (unit == BasinUnits.NONE) continue
                FlowRouting.forEachNeighbour(
                    cellsAcross, cellsDown, column, row
                ) { neighbourCell ->
                    val other = unitOf[neighbourCell]
                    if (other != BasinUnits.NONE && other != unit) neighboursOf[unit].add(other)
                }
            }
        }

        // Kept before the straits are added, because a landmass is what you can walk.
        //
        // Sorted, and this is not tidiness. A HashSet iterates in bucket order on the JVM and in
        // insertion order on Kotlin/Wasm, so without the sort the same world produced 14 realms on
        // one and 13 on the other: every `firstOrNull`, tie-broken `maxByOrNull` and flood-fill
        // cutoff downstream reads these arrays, and each one silently followed its platform's
        // hash order. CI's fingerprint comparison caught it and was red for two weeks before
        // anyone looked.
        val landNeighbours = Array(unitCount) {
            neighboursOf[it].toIntArray().also { ids -> ids.sort() }
        }

        // Now the straits. Only coastal cells look, and only straight out, which is enough to find
        // the far shore of a channel without turning every bay into a shortcut.
        val straitReachCells =
            (cellsAcross / STRAIT_REACH_DIVISOR).coerceAtLeast(MIN_STRAIT_REACH_CELLS)
        for (row in 0 until cellsDown) {
            for (column in 0 until cellsAcross) {
                val cell = row * cellsAcross + column
                val unit = unitOf[cell]
                if (unit == BasinUnits.NONE) continue
                var coastal = false
                FlowRouting.forEachNeighbour(
                    cellsAcross, cellsDown, column, row
                ) { neighbourCell -> if (!sea.isLand[neighbourCell]) coastal = true }
                if (!coastal) continue

                for (direction in DIRECTION_ACROSS.indices) {
                    val stepAcross = DIRECTION_ACROSS[direction]
                    val stepDown = DIRECTION_DOWN[direction]
                    var crossedWater = false
                    for (distance in 1..straitReachCells) {
                        val probeRow = row + stepDown * distance
                        if (probeRow < 0 || probeRow >= cellsDown) break
                        val probe = probeRow * cellsAcross +
                            ((column + stepAcross * distance) % cellsAcross + cellsAcross) %
                            cellsAcross
                        if (!sea.isLand[probe]) { crossedWater = true; continue }
                        // Land again, having crossed water: the far bank of a strait.
                        if (crossedWater) {
                            val other = unitOf[probe]
                            if (other != BasinUnits.NONE && other != unit) {
                                neighboursOf[unit].add(other)
                                neighboursOf[other].add(unit)
                            }
                        }
                        break
                    }
                }
            }
        }
        val allNeighbours = Array(unitCount) {
            neighboursOf[it].toIntArray().also { ids -> ids.sort() }
        }
        return allNeighbours to landNeighbours
    }

    /**
     * How far a coast looks for the far bank of a strait, as a divisor of the map width — a
     * fortieth, so the crossing a realm will make is the same real distance at every resolution —
     * with a floor for small maps.
     *
     * A fortieth of a 12,000 km world is 300 km: wider than the Channel, the Strait of Malacca or
     * the Aegean, and far short of an ocean.
     */
    private const val STRAIT_REACH_DIVISOR = 40
    private const val MIN_STRAIT_REACH_CELLS = 4

    /** The eight compass directions a coast probes along, as steps in map coordinates. */
    private val DIRECTION_ACROSS = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
    private val DIRECTION_DOWN = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
}
