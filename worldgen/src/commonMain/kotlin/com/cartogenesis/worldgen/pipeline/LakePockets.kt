package com.cartogenesis.worldgen.pipeline

/**
 * The pockets of one closed basin, and where the water stands in each: fill, spill and merge over
 * the basin's depression hierarchy.
 *
 * A closed basin is rarely one bowl. Its floor holds several hollows separated by saddles lower
 * than the basin's brim, and each hollow is a lake of its own until the water reaches the saddle
 * between it and its neighbor. Bonneville is the example: at its highstand one lake over the whole
 * of north-western Utah, today Great Salt Lake, Utah Lake and Sevier Lake, three surfaces at three
 * levels in the hollows the old lake's floor left, each balancing its own catchment against its own
 * evaporation, with Utah Lake still spilling into Great Salt Lake down the Jordan River.
 *
 * So each pocket balances the rain and runoff of its own catchment against the evaporation off its
 * own wetted area. A pocket full to its saddle passes only its surplus on, into the pocket across
 * the saddle; the two become one surface only when both stand at the saddle, and from there they
 * fill as one over the ground above it, up to the next saddle or the basin's brim. Nothing is
 * counted twice: a cell's rain goes to the one pocket it drains into, and a pocket that spills
 * passes on what is left after its own lake has evaporated.
 *
 * The hierarchy is built once per basin, by sweeping its cells from the lowest ground up and joining
 * each to the pockets it touches ([build]); every pocket's own cells come out as one run of an array
 * laid out so that a pocket's whole region, its children's and its own, is one contiguous range, and
 * one running sum over that array answers every question the balance asks of any pocket. No pocket
 * copies its hypsometry and nothing floods the grid. The balance is then a graph solve over the
 * pockets ([solve]), one pass per spill.
 *
 * The balance is `LakeWaterBalance`'s, per area: every cell is an equal area, rain and potential
 * evaporation are in millimeters a year, and a supply is millimeter-cells.
 */
internal class LakePockets private constructor(
    /** The basin's cells, laid out so that each pocket's region is `regionStart until regionEnd`. */
    val layout: IntArray,
    /** Ground under each laid-out cell, in the elevation's units. */
    private val groundAt: FloatArray,
    /**
     * Running sum over [layout] of what each cell does to a lake that covers it, in millimeter-cells:
     * the rain it receives above the share that would have run in anyway, less its evaporation.
     */
    private val netPrefix: DoubleArray,
    /** Within each pocket's own run of cells, the least the running sum has reached from its start. */
    private val lowestSinceOwnStart: DoubleArray,
    val parent: IntArray,
    val firstChild: IntArray,
    val secondChild: IntArray,
    val regionStart: IntArray,
    val ownStart: IntArray,
    val regionEnd: IntArray,
    /** The cell whose arrival joined a pocket's two children; -1 for a hollow's own pocket. */
    val saddleCell: IntArray,
    /** The ground a pocket began at: its saddle where it is joined, its floor where it is a hollow. */
    private val levelAtStart: FloatArray,
    /**
     * For a joined pocket, the leaf pocket the water that spills over its saddle into each child
     * reaches first: the one the saddle's lowest neighbor in that child drains to.
     */
    private val entryLeafOfFirst: IntArray,
    private val entryLeafOfSecond: IntArray,
    /** The leaf pocket each laid-out cell's rain drains to, down the lowest neighbor. */
    val leafOfLaidOut: IntArray,
    /** The basin's brim, where the root pocket spills. */
    private val brim: Float
) {
    val pocketCount: Int get() = parent.size
    val root: Int get() = pocketCount - 1

    fun isLeaf(pocket: Int) = firstChild[pocket] < 0

    /** The level a pocket fills to before it spills: its parent's saddle, or the basin's brim. */
    fun topLevel(pocket: Int): Float {
        val above = parent[pocket]
        return if (above < 0) brim else levelAtStart[above]
    }

    /** The saddle a joined pocket began at, or the floor of a hollow. */
    fun baseLevel(pocket: Int): Float = levelAtStart[pocket]

    fun groundOfLaidOut(index: Int): Float = groundAt[index]

    /** Where the water stands once [solve] has run: per pocket, and what it left in each. */
    class Water(
        /** Whether each pocket stands at its top level and passes a surplus on. */
        val full: BooleanArray,
        /** Whether each joined pocket holds one lake over both its children. */
        val merged: BooleanArray,
        /**
         * For a pocket that holds its own lake and is not full, how many of its own cells lie under
         * water, lowest first; its children's regions are all under water too where it is joined.
         */
        val ownCellsUnderWater: IntArray,
        /** Whether the basin as a whole spills at its brim. */
        val rootFull: Boolean
    ) {
        /** A pocket with a lake of its own that is not full: a leaf, or a joined pocket over both. */
        fun holdsItsOwnLevel(pockets: LakePockets, pocket: Int): Boolean =
            !full[pocket] && (pockets.isLeaf(pocket) || merged[pocket])
    }

    /**
     * Where the water stands in every pocket, given the water each leaf pocket's catchment sends it.
     *
     * [leafSupplyMm] is, per pocket, the runoff that reaches it in millimeter-cells before any lake
     * is counted (zero for a joined pocket); [runoffFraction] is the share of rain that runs off
     * land, so a cell under water swaps that share for the whole of its rain.
     *
     * The supplies are added leaf by leaf in index order, each carried as far as it goes: into its
     * pocket, over the saddle when the pocket fills, down into the pocket across the saddle or, when
     * that one is full too, into the pair joined as one lake. The steady state does not depend on the
     * order, and the order fixes every rounding, so the answer is one specific answer on every
     * platform.
     *
     * A lake fills from empty and stops at the first level where it would lose more than it gains,
     * which on ground whose evaporation varies need not be the last such level: net gain need not
     * fall as the flooded area grows, where the high ground round a hollow is frozen and the hollow is
     * not. So a pocket's level is the first place its running gain goes negative, found on the
     * running minimum rather than by bisecting a gain assumed to fall.
     */
    fun solve(leafSupplyMm: DoubleArray): Water {
        val count = pocketCount
        val supply = DoubleArray(count)
        val surplus = DoubleArray(count)
        val routed = DoubleArray(count)
        val full = BooleanArray(count)
        val merged = BooleanArray(count)

        // What a lake over all of the pocket's children and none of its own cells gains a year.
        fun gainWithNoOwnCells(pocket: Int): Double =
            supply[pocket] + netPrefix[ownStart[pocket]] - netPrefix[regionStart[pocket]]
        fun fills(pocket: Int): Boolean {
            if (ownStart[pocket] == regionEnd[pocket]) return gainWithNoOwnCells(pocket) >= 0.0
            return gainWithNoOwnCells(pocket) + lowestSinceOwnStart[regionEnd[pocket] - 1] >= 0.0
        }
        fun gainWhenFull(pocket: Int): Double =
            supply[pocket] + netPrefix[regionEnd[pocket]] - netPrefix[regionStart[pocket]]

        fun addSupply(firstPocket: Int, amountMm: Double) {
            var pocket = firstPocket
            var extra = amountMm
            while (true) {
                supply[pocket] += extra
                var overflow: Double
                if (full[pocket]) {
                    surplus[pocket] += extra
                    overflow = extra
                } else if (fills(pocket)) {
                    full[pocket] = true
                    surplus[pocket] = gainWhenFull(pocket)
                    overflow = surplus[pocket]
                } else {
                    return
                }
                // The overflow leaves this pocket over its top.
                var handedOn = false
                while (!handedOn) {
                    val above = parent[pocket]
                    if (above < 0) return
                    if (merged[above]) {
                        // One lake over both children: the supply is the parent's own.
                        pocket = above
                        handedOn = true
                        continue
                    }
                    val first = firstChild[above] == pocket
                    val sibling = if (first) secondChild[above] else firstChild[above]
                    if (full[sibling]) {
                        // Both stand at the saddle: one surface from here up, fed by both
                        // catchments, less what one already passed the other across it.
                        merged[above] = true
                        supply[above] = supply[firstChild[above]] + supply[secondChild[above]] - routed[above]
                        if (!fills(above)) return
                        full[above] = true
                        surplus[above] = gainWhenFull(above)
                        overflow = surplus[above]
                        pocket = above
                        continue
                    }
                    routed[above] += overflow
                    pocket = if (first) entryLeafOfSecond[above] else entryLeafOfFirst[above]
                    extra = overflow
                    handedOn = true
                }
            }
        }

        for (pocket in 0 until count) {
            if (isLeaf(pocket) && leafSupplyMm[pocket] > 0.0) addSupply(pocket, leafSupplyMm[pocket])
        }

        // A pocket that holds its own level: the first own cell whose flooding the lake cannot pay
        // for is the first one left dry.
        val ownUnder = IntArray(count)
        for (pocket in 0 until count) {
            if (full[pocket] || !(isLeaf(pocket) || merged[pocket])) continue
            val base = gainWithNoOwnCells(pocket)
            var low = ownStart[pocket]
            var high = regionEnd[pocket]
            // lowestSinceOwnStart falls monotonically across the run: find the first index below -base.
            while (low < high) {
                val middle = (low + high) ushr 1
                if (base + lowestSinceOwnStart[middle] < 0.0) high = middle else low = middle + 1
            }
            ownUnder[pocket] = low - ownStart[pocket]
        }
        return Water(full, merged, ownUnder, full[root])
    }

    companion object {
        /**
         * The hierarchy of one basin's pockets, built by one sweep of its cells from the lowest ground
         * up: a cell touching no pocket yet starts a leaf, a cell touching one joins it, and a cell
         * touching two or more is the saddle where they join, in pairs, lowest pocket number first.
         *
         * [cells] are the basin's cells, [byGround] the same cells packed by [FlowRouting.encode] and
         * sorted, so equal ground falls to the lower index on every platform. [localIndex] is scratch,
         * one entry per cell of the grid, all -1 on entry and left all -1. [rainMm] and
         * [evaporationMm] are per cell of the grid. The basin must be one eight-connected piece, as
         * the lakes' basins are.
         */
        fun build(
            cellsAcross: Int,
            cellsDown: Int,
            cells: IntArray,
            byGround: LongArray,
            ground: FloatArray,
            rainMm: FloatArray,
            evaporationMm: FloatArray,
            runoffFraction: Float,
            brim: Float,
            localIndex: IntArray
        ): LakePockets {
            val count = cells.size
            // Local index = rank by ground.
            val cellAtRank = IntArray(count) { FlowRouting.decodeIndex(byGround[it]) }
            for (rank in 0 until count) localIndex[cellAtRank[rank]] = rank

            val unionParent = IntArray(count) { it }
            fun findRoot(rank: Int): Int {
                var at = rank
                while (unionParent[at] != at) {
                    unionParent[at] = unionParent[unionParent[at]]
                    at = unionParent[at]
                }
                return at
            }
            val topPocketOfRoot = IntArray(count) { -1 }
            val pocketOfRank = IntArray(count)
            val lowestNeighborRank = IntArray(count) { -1 }

            val parent = ArrayList<Int>()
            val firstChild = ArrayList<Int>()
            val secondChild = ArrayList<Int>()
            val saddleRank = ArrayList<Int>()
            fun newPocket(first: Int, second: Int, rank: Int): Int {
                parent.add(-1); firstChild.add(first); secondChild.add(second); saddleRank.add(rank)
                val pocket = parent.size - 1
                if (first >= 0) parent[first] = pocket
                if (second >= 0) parent[second] = pocket
                return pocket
            }

            val touched = ArrayList<Int>(8)
            for (rank in 0 until count) {
                val cell = cellAtRank[rank]
                touched.clear()
                var lowest = -1
                FlowRouting.forEachNeighbour(cellsAcross, cellsDown, cell % cellsAcross, cell / cellsAcross) { neighbor ->
                    val neighborRank = localIndex[neighbor]
                    if (neighborRank in 0 until rank) {
                        if (lowest < 0 || neighborRank < lowest) lowest = neighborRank
                        val root = findRoot(neighborRank)
                        if (root !in touched) touched.add(root)
                    }
                }
                lowestNeighborRank[rank] = lowest
                when (touched.size) {
                    0 -> {
                        pocketOfRank[rank] = newPocket(-1, -1, rank)
                        topPocketOfRoot[rank] = pocketOfRank[rank]
                    }
                    1 -> {
                        val root = touched[0]
                        unionParent[rank] = root
                        pocketOfRank[rank] = topPocketOfRoot[root]
                    }
                    else -> {
                        val tops = touched.map { topPocketOfRoot[it] }.sorted()
                        var joined = newPocket(tops[0], tops[1], rank)
                        for (index in 2 until tops.size) joined = newPocket(joined, tops[index], rank)
                        for (root in touched) unionParent[root] = rank
                        topPocketOfRoot[rank] = joined
                        pocketOfRank[rank] = joined
                    }
                }
            }
            val pocketCount = parent.size

            // Own cells per pocket, in rank order: a counting sort by pocket keeps ground ascending.
            val ownCount = IntArray(pocketCount)
            for (rank in 0 until count) ownCount[pocketOfRank[rank]]++

            // Lay each pocket's region out as one range: children first, its own cells after.
            val regionStart = IntArray(pocketCount)
            val ownStart = IntArray(pocketCount)
            val regionEnd = IntArray(pocketCount)
            var cursor = 0
            val stack = ArrayList<Int>()
            val expanded = BooleanArray(pocketCount)
            stack.add(pocketCount - 1)
            while (stack.isNotEmpty()) {
                val pocket = stack[stack.size - 1]
                if (!expanded[pocket]) {
                    expanded[pocket] = true
                    regionStart[pocket] = cursor
                    if (secondChild[pocket] >= 0) stack.add(secondChild[pocket])
                    if (firstChild[pocket] >= 0) stack.add(firstChild[pocket])
                    continue
                }
                stack.removeAt(stack.size - 1)
                // Children laid out; the region started where the first child's did.
                if (firstChild[pocket] >= 0) regionStart[pocket] = regionStart[firstChild[pocket]]
                ownStart[pocket] = cursor
                cursor += ownCount[pocket]
                regionEnd[pocket] = cursor
            }
            val filledOwn = IntArray(pocketCount)
            val laidOutRank = IntArray(count)
            val laidOutIndexOfRank = IntArray(count)
            for (rank in 0 until count) {
                val pocket = pocketOfRank[rank]
                val at = ownStart[pocket] + filledOwn[pocket]++
                laidOutRank[at] = rank
                laidOutIndexOfRank[rank] = at
            }

            val layout = IntArray(count) { cellAtRank[laidOutRank[it]] }
            val groundAt = FloatArray(count) { ground[layout[it]] }
            val lakeShareOfRain = 1.0 - runoffFraction
            val netPrefix = DoubleArray(count + 1)
            for (index in 0 until count) {
                val cell = layout[index]
                netPrefix[index + 1] = netPrefix[index] + lakeShareOfRain * rainMm[cell] - evaporationMm[cell]
            }
            val lowestSinceOwnStart = DoubleArray(count)
            for (pocket in 0 until pocketCount) {
                var lowest = Double.MAX_VALUE
                for (index in ownStart[pocket] until regionEnd[pocket]) {
                    val sinceStart = netPrefix[index + 1] - netPrefix[ownStart[pocket]]
                    if (sinceStart < lowest) lowest = sinceStart
                    lowestSinceOwnStart[index] = lowest
                }
            }

            // The leaf each cell's rain reaches, down its lowest neighbor below it.
            val leafOfRank = IntArray(count)
            for (rank in 0 until count) {
                val below = lowestNeighborRank[rank]
                leafOfRank[rank] = if (below < 0) pocketOfRank[rank] else leafOfRank[below]
            }
            val leafOfLaidOut = IntArray(count) { leafOfRank[laidOutRank[it]] }

            // Where a joined pocket's saddle sends spilled water into each child.
            val entryOfFirst = IntArray(pocketCount) { -1 }
            val entryOfSecond = IntArray(pocketCount) { -1 }
            val saddleCell = IntArray(pocketCount) { -1 }
            val inPocketRegion = { pocket: Int, rank: Int ->
                val at = laidOutIndexOfRank[rank]
                at >= regionStart[pocket] && at < regionEnd[pocket]
            }
            for (pocket in 0 until pocketCount) {
                if (firstChild[pocket] < 0) continue
                val rank = saddleRank[pocket]
                val cell = cellAtRank[rank]
                saddleCell[pocket] = cell
                var intoFirst = -1
                var intoSecond = -1
                FlowRouting.forEachNeighbour(cellsAcross, cellsDown, cell % cellsAcross, cell / cellsAcross) { neighbor ->
                    val neighborRank = localIndex[neighbor]
                    if (neighborRank !in 0 until rank) return@forEachNeighbour
                    if (inPocketRegion(firstChild[pocket], neighborRank) && (intoFirst < 0 || neighborRank < intoFirst)) {
                        intoFirst = neighborRank
                    }
                    if (inPocketRegion(secondChild[pocket], neighborRank) && (intoSecond < 0 || neighborRank < intoSecond)) {
                        intoSecond = neighborRank
                    }
                }
                entryOfFirst[pocket] = leafOfRank[intoFirst]
                entryOfSecond[pocket] = leafOfRank[intoSecond]
            }

            for (cell in cells) localIndex[cell] = -1
            return LakePockets(
                layout, groundAt, netPrefix, lowestSinceOwnStart,
                parent.toIntArray(), firstChild.toIntArray(), secondChild.toIntArray(),
                regionStart, ownStart, regionEnd, saddleCell,
                FloatArray(pocketCount) { ground[cellAtRank[saddleRank[it]]] }, entryOfFirst, entryOfSecond,
                leafOfLaidOut, brim
            )
        }
    }
}
