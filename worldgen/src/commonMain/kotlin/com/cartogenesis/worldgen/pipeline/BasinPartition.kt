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
     * @param maxUnitCells the largest catchment that will be left whole. Anything draining more
     *   than this is cut at its confluences, so the pieces are its tributaries.
     */
    fun compute(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        rivers: RiverResult,
        maxUnitCells: Int
    ): BasinUnits {
        val w = config.width
        val h = config.height
        val cells = w * h
        val target = rivers.flowTarget
        val accumulation = rivers.flowAccumulation.data
        val unitOf = IntArray(cells) { BasinUnits.NONE }

        // Lowest first, so a cell's downstream neighbour is always already settled and can simply
        // be asked which unit it joined.
        val ordered = LongArray(sea.landCellCount)
        var n = 0
        for (i in 0 until cells) {
            if (sea.isLand[i]) ordered[n++] = FlowRouting.encode(rivers.filledElevation.data[i], i)
        }
        ordered.sort()

        var unitCount = 0
        val areas = ArrayList<Int>()
        for (k in ordered.indices) {
            val i = FlowRouting.decodeIndex(ordered[k])
            val downstream = target[i]

            val isTerminus = downstream < 0 || !sea.isLand[downstream]
            // A confluence cut: this cell drains less than a whole unit's worth, but the water it
            // joins drains more. That is precisely a tributary meeting a trunk, and it is where a
            // basin naturally comes apart.
            val isTributaryMouth = !isTerminus &&
                accumulation[i] < maxUnitCells &&
                accumulation[downstream] >= maxUnitCells

            val unit = if (isTerminus || isTributaryMouth) {
                areas.add(0)
                unitCount++
            } else {
                unitOf[downstream]
            }
            // A downstream cell should always be settled by now, but a flow target pointing at an
            // equal elevation could in principle break that; such a cell starts its own unit
            // rather than corrupting another.
            val settled = if (unit == BasinUnits.NONE) {
                areas.add(0)
                unitCount++
            } else {
                unit
            }
            unitOf[i] = settled
            areas[settled] = areas[settled] + 1
        }

        val area = IntArray(unitCount) { areas[it] }
        return build(w, h, sea, unitOf, unitCount, area)
    }

    /**
     * Merges units below [minUnitCells] into whichever neighbour they share the most edge with.
     *
     * Coastlines produce a great many tiny catchments — every gully reaching the sea is its own
     * terminus — and left alone they would make realms out of slivers. Merging by shared edge keeps
     * the result compact rather than stringy.
     */
    fun mergeSmall(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        units: BasinUnits,
        minUnitCells: Int
    ): BasinUnits {
        val w = config.width
        val h = config.height
        val remap = IntArray(units.unitCount) { it }

        fun resolve(u: Int): Int {
            var r = u
            while (remap[r] != r) r = remap[r]
            var walk = u
            while (remap[walk] != walk) {
                val next = remap[walk]
                remap[walk] = r
                walk = next
            }
            return r
        }

        // Smallest first, so a merged unit can itself go on to absorb or be absorbed sensibly.
        val order = (0 until units.unitCount).sortedBy { units.area[it] }
        val area = units.area.copyOf()
        for (u in order) {
            val root = resolve(u)
            if (area[root] >= minUnitCells) continue
            val host = units.neighbours[u]
                .map { resolve(it) }
                .filter { it != root }
                .maxByOrNull { area[it] } ?: continue
            remap[root] = host
            area[host] += area[root]
        }

        // Renumber so the ids are contiguous again.
        val renumber = HashMap<Int, Int>()
        val unitOf = IntArray(units.unitOf.size) { BasinUnits.NONE }
        for (i in units.unitOf.indices) {
            val u = units.unitOf[i]
            if (u == BasinUnits.NONE) continue
            val root = resolve(u)
            unitOf[i] = renumber.getOrPut(root) { renumber.size }
        }
        val count = renumber.size
        val areas = IntArray(count)
        for (i in unitOf.indices) if (unitOf[i] != BasinUnits.NONE) areas[unitOf[i]]++

        return build(w, h, sea, unitOf, count, areas)
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
        val w = config.width
        val h = config.height
        val target = rivers.flowTarget
        val accumulation = rivers.flowAccumulation.data

        // The outlet of each unit: the cell it all drains through, which is the one carrying most.
        val outlet = IntArray(units.unitCount) { -1 }
        for (i in units.unitOf.indices) {
            val u = units.unitOf[i]
            if (u == BasinUnits.NONE) continue
            val best = outlet[u]
            if (best < 0 || accumulation[i] > accumulation[best]) outlet[u] = i
        }

        // Upstream neighbours, so a trunk can be walked back from the mouth.
        val upstream = HashMap<Int, MutableList<Int>>()
        for (i in units.unitOf.indices) {
            if (units.unitOf[i] == BasinUnits.NONE) continue
            val t = target[i]
            if (t >= 0 && sea.isLand[t]) upstream.getOrPut(t) { ArrayList() }.add(i)
        }

        val side = IntArray(units.unitOf.size) { -1 }
        val onTrunk = BooleanArray(units.unitOf.size)
        var splitAny = false

        for (u in 0 until units.unitCount) {
            val mouth = outlet[u]
            if (mouth < 0 || accumulation[mouth] < minTrunkFlow) continue

            // Walk up the trunk, always following the branch carrying the most water.
            val trunk = ArrayList<Int>()
            var cursor = mouth
            while (true) {
                trunk.add(cursor)
                onTrunk[cursor] = true
                val next = upstream[cursor]
                    ?.filter { units.unitOf[it] == u }
                    ?.maxByOrNull { accumulation[it] } ?: break
                if (accumulation[next] < minTrunkFlow * 0.15f) break
                cursor = next
            }
            if (trunk.size < 8) continue

            // Which way the trunk runs at each of its cells, for the cross product below.
            val flowX = HashMap<Int, Int>()
            val flowY = HashMap<Int, Int>()
            for (k in trunk.indices) {
                val c = trunk[k]
                val d = if (k == 0) target[c] else trunk[k - 1]
                if (d < 0) continue
                flowX[c] = wrapDelta(d % w - c % w, w)
                flowY[c] = d / w - c / w
            }

            // Everything else follows its water down to the trunk and takes a bank from where it
            // arrived. Memoised, so a long tributary is walked once rather than once per cell.
            for (i in units.unitOf.indices) {
                if (units.unitOf[i] != u || side[i] >= 0) continue
                val path = ArrayList<Int>()
                var c = i
                var answer = -1
                while (true) {
                    if (onTrunk[c]) { answer = 0; break }
                    if (side[c] >= 0) { answer = side[c]; break }
                    path.add(c)
                    val t = target[c]
                    if (t < 0 || units.unitOf[t] != u) { answer = 0; break }
                    // The cell that flows into the trunk decides the bank for everything behind it.
                    if (onTrunk[t]) {
                        val tx = flowX[t] ?: 0
                        val ty = flowY[t] ?: 0
                        val ex = wrapDelta(c % w - t % w, w)
                        val ey = c / w - t / w
                        answer = if (tx * ey - ty * ex >= 0) 0 else 1
                        break
                    }
                    c = t
                }
                path.forEach { side[it] = answer }
            }
            trunk.forEach { side[it] = 0 }
            splitAny = true
        }

        if (!splitAny) return units

        // Renumber: each unit becomes at most two.
        val unitOf = IntArray(units.unitOf.size) { BasinUnits.NONE }
        val renumber = HashMap<Long, Int>()
        for (i in units.unitOf.indices) {
            val u = units.unitOf[i]
            if (u == BasinUnits.NONE) continue
            val bank = if (side[i] == 1) 1L else 0L
            unitOf[i] = renumber.getOrPut(u * 2L + bank) { renumber.size }
        }
        val count = renumber.size
        val areas = IntArray(count)
        for (i in unitOf.indices) if (unitOf[i] != BasinUnits.NONE) areas[unitOf[i]]++
        return build(w, h, sea, unitOf, count, areas)
    }

    /** Column difference on a cylinder, where a step across the seam is still one cell. */
    private fun wrapDelta(delta: Int, width: Int): Int = when {
        delta > width / 2 -> delta - width
        delta < -width / 2 -> delta + width
        else -> delta
    }


    /** Assembles a [BasinUnits] from a labelling: neighbours, and which landmass each unit is on. */
    private fun build(
        w: Int,
        h: Int,
        sea: SeaLevelResult,
        unitOf: IntArray,
        count: Int,
        area: IntArray
    ): BasinUnits {
        val (neighbours, touching) = adjacency(w, h, sea, unitOf, count)

        // Landmasses: units joined by dry ground only. Straits are deliberately excluded, since the
        // whole point is to know when a realm would have to put to sea.
        val landmass = IntArray(count) { -1 }
        var landmassCount = 0
        for (start in 0 until count) {
            if (landmass[start] >= 0 || area[start] == 0) continue
            val id = landmassCount++
            val frontier = ArrayDeque<Int>()
            frontier.addLast(start)
            landmass[start] = id
            while (frontier.isNotEmpty()) {
                val u = frontier.removeFirst()
                touching[u].forEach {
                    if (landmass[it] < 0) {
                        landmass[it] = id
                        frontier.addLast(it)
                    }
                }
            }
        }
        return BasinUnits(unitOf, count, area, neighbours, touching, landmass, landmassCount)
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
        w: Int,
        h: Int,
        sea: SeaLevelResult,
        unitOf: IntArray,
        unitCount: Int
    ): Pair<Array<IntArray>, Array<IntArray>> {
        val sets = Array(unitCount) { HashSet<Int>() }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val u = unitOf[i]
                if (u == BasinUnits.NONE) continue
                FlowRouting.forEachNeighbour(w, h, x, y) { nb ->
                    val other = unitOf[nb]
                    if (other != BasinUnits.NONE && other != u) sets[u].add(other)
                }
            }
        }

        // Kept before the straits are added, because a landmass is what you can walk.
        val touching = Array(unitCount) { sets[it].toIntArray() }

        // Now the straits. Only coastal cells look, and only straight out, which is enough to find
        // the far shore of a channel without turning every bay into a shortcut.
        val reach = (w / 40).coerceAtLeast(4)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val u = unitOf[i]
                if (u == BasinUnits.NONE) continue
                var coastal = false
                FlowRouting.forEachNeighbour(w, h, x, y) { nb -> if (!sea.isLand[nb]) coastal = true }
                if (!coastal) continue

                for (dir in 0 until 8) {
                    val dx = DIR_X[dir]
                    val dy = DIR_Y[dir]
                    var crossedWater = false
                    for (step in 1..reach) {
                        val ny = y + dy * step
                        if (ny < 0 || ny >= h) break
                        val n = ny * w + ((x + dx * step) % w + w) % w
                        if (!sea.isLand[n]) { crossedWater = true; continue }
                        // Land again, having crossed water: the far bank of a strait.
                        if (crossedWater) {
                            val other = unitOf[n]
                            if (other != BasinUnits.NONE && other != u) {
                                sets[u].add(other)
                                sets[other].add(u)
                            }
                        }
                        break
                    }
                }
            }
        }
        return Array(unitCount) { sets[it].toIntArray() } to touching
    }

    private val DIR_X = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
    private val DIR_Y = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
}
