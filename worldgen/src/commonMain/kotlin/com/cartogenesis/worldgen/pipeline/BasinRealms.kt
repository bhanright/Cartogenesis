package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.math.LongMinHeap
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WildernessMode
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Hands the catchments out to realms.
 *
 * A realm takes whole catchments, never half of one, which is what puts its borders on watersheds.
 * Beyond that the interesting question is how *much* each takes, because realms of uniform size
 * look designed. Two things vary it.
 *
 * Appetite: each realm is given a different share of the land to aim for, drawn from a distribution
 * with a long tail, so a world has a couple of large powers, several middling ones and some small
 * states rather than a dozen equal slabs.
 *
 * Schism: a realm holding several catchments may split in two along one of its internal watersheds.
 * That is where the interesting borders come from — a line inside what is geographically one
 * region, drawn because the people either side of it stopped agreeing, which is most of the reason
 * real borders sit where they do. It costs nothing to place, because the divide is already there.
 */
internal object BasinRealms {

    class Assignment(val realmOf: IntArray, val origins: List<Int>)

    /**
     * Shares the catchments out and gives each realm a capital.
     *
     * Returns a realm id per cell, row-major and [NationResult.UNCLAIMED] over water and over
     * wilderness, together with one origin cell per realm — indexed by the same ids the map holds,
     * renumbered so they are contiguous.
     */
    suspend fun assign(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        units: BasinUnits,
        habitability: FloatField,
        random: Random
    ): Assignment {
        val nationsConfig = config.nations
        val realmOfUnit = IntArray(units.unitCount) { NationResult.UNCLAIMED }
        if (nationsConfig.nationCount <= 0 || units.unitCount == 0) {
            return Assignment(
                IntArray(config.width * config.height) { NationResult.UNCLAIMED },
                emptyList()
            )
        }

        // How good a place each catchment is to hold, and where in it a capital would sit.
        val habitabilitySum = FloatArray(units.unitCount)
        val bestCell = IntArray(units.unitCount) { -1 }
        val bestScore = FloatArray(units.unitCount) { -1f }
        for (cell in units.unitOf.indices) {
            val unit = units.unitOf[cell]
            if (unit == BasinUnits.NONE) continue
            val score = habitability.data[cell]
            habitabilitySum[unit] += score
            if (score > bestScore[unit]) {
                bestScore[unit] = score
                bestCell[unit] = cell
            }
        }
        val quality = FloatArray(units.unitCount) {
            if (units.area[it] == 0) 0f else habitabilitySum[it] / units.area[it]
        }

        // Seed on the best land, but forced apart, or every realm sprouts in the same fertile
        // valley and the rest of the world is left to whoever is nearest.
        val seeds = chooseSeeds(units, quality, nationsConfig.nationCount, random)
        seeds.forEachIndexed { realm, unit -> realmOfUnit[unit] = realm }

        // Appetite: how many fair shares of the world's land each realm is out to hold. Raised to
        // a power so the draw has a tail — most realms want a middling amount, a few want a great
        // deal — which is what stops a world reading as a dozen equal slabs.
        val totalLand = units.area.sum().toFloat()
        val appetite = FloatArray(seeds.size) {
            MIN_APPETITE + random.nextFloat().pow(APPETITE_TAIL) * APPETITE_RANGE
        }
        val fairShare = totalLand / seeds.size
        val held = IntArray(seeds.size)
        seeds.forEachIndexed { realm, unit -> held[realm] = units.area[unit] }

        // Grow by claiming whichever adjacent catchment is cheapest, cheapest meaning good land
        // that the realm still has appetite for.
        val frontier = LongMinHeap(units.unitCount * HEAP_ENTRIES_PER_UNIT + HEAP_SPARE)
        fun offer(realm: Int, from: Int, unit: Int) {
            if (realmOfUnit[unit] != NationResult.UNCLAIMED) return
            val hunger = (appetite[realm] * fairShare - held[realm]) / fairShare
            if (hunger <= 0f && nationsConfig.wilderness != WildernessMode.CLAIM_ALL_LAND) return
            // Poor ground is dear, and a realm that has eaten its fill finds everything dear.
            var cost = (1f - quality[unit]) * POOR_GROUND_COST +
                (1f - hunger).coerceAtLeast(0f) * SATED_COST
            // Water is crossable but not free. Without this a realm that reaches one strait tends
            // to island-hop the length of an archipelago, which produced a single realm spanning
            // half the world the first time this ran.
            if (units.landmass[unit] != units.landmass[from]) {
                cost += nationsConfig.straitCrossingCost
            }
            frontier.push(encode(cost, realm * units.unitCount + unit))
        }

        seeds.forEachIndexed { realm, unit ->
            units.neighbours[unit].forEach { offer(realm, unit, it) }
        }

        // The expansion itself is one walk of a heap over catchments rather than over cells, so
        // there is no useful boundary inside it; the question is asked on the way in.
        currentCoroutineContext().ensureActive()
        while (!frontier.isEmpty()) {
            val packed = decodeIndex(frontier.pop())
            val realm = packed / units.unitCount
            val unit = packed % units.unitCount
            if (realmOfUnit[unit] != NationResult.UNCLAIMED) continue

            realmOfUnit[unit] = realm
            held[realm] += units.area[unit]
            units.neighbours[unit].forEach { offer(realm, unit, it) }
        }

        // Anything still unclaimed is out of reach of every realm — an island beyond a strait, or
        // a continent nobody was seeded on. When the user has asked for a finished-looking map it
        // goes to whichever realm is nearest; otherwise it is left as wilderness, which is what
        // they asked for.
        var realmCount = seeds.size
        if (nationsConfig.wilderness == WildernessMode.CLAIM_ALL_LAND) {
            // Returns a new count, because a wholly isolated island becomes a realm of its own.
            // Passing the old one here dropped those islands off the end of every later loop and
            // left them unclaimed after all — 3% of seed 7's land, which is what this was for.
            realmCount = claimStragglers(units, realmOfUnit, realmCount, centroids(config, units))
        }

        val realms = schism(config, units, realmOfUnit, realmCount, quality, random)

        // Back down to cells.
        val realmOf = IntArray(config.width * config.height) { NationResult.UNCLAIMED }
        for (cell in realmOf.indices) {
            val unit = units.unitOf[cell]
            if (unit != BasinUnits.NONE) realmOf[cell] = realms.owner[unit]
        }

        // A capital for every realm that ended up with land, taken from its best-scoring unit —
        // and the contiguous id it will be known by, decided in the same breath.
        //
        // One loop and one predicate on purpose. These used to be two loops: one added a capital
        // when a realm had a cell to put it on, the other assigned an id when a realm owned any
        // unit at all. Those are the same condition today, but only by construction, and the day
        // they differ every realm after the gap is numbered one off from its capital — and
        // `describe` sizes every per-realm array by the capital list, so the last realm indexes
        // straight off the end.
        val origins = ArrayList<Int>()
        val renumbered = HashMap<Int, Int>()
        for (realm in 0 until realms.count) {
            var capital = -1
            var capitalScore = -1f
            for (unit in 0 until units.unitCount) {
                if (realms.owner[unit] != realm) continue
                if (bestScore[unit] > capitalScore) {
                    capitalScore = bestScore[unit]
                    capital = bestCell[unit]
                }
            }
            if (capital < 0) continue
            renumbered[realm] = origins.size
            origins.add(capital)
        }
        for (cell in realmOf.indices) {
            val realm = realmOf[cell]
            if (realm != NationResult.UNCLAIMED) {
                realmOf[cell] = renumbered[realm] ?: NationResult.UNCLAIMED
            }
        }
        for (cell in realmOf.indices) if (!sea.isLand[cell]) realmOf[cell] = NationResult.UNCLAIMED

        return Assignment(realmOf, origins)
    }

    /**
     * How many fair shares of the world's land a realm sets out to hold: [MIN_APPETITE] at least,
     * and up to that plus [APPETITE_RANGE].
     *
     * The draw is raised to [APPETITE_TAIL] before it is scaled, which bends a flat draw into one
     * with a long tail: most realms come out middling and a few come out hungry, so a world has a
     * couple of powers and several small states rather than a dozen equal slabs.
     */
    private const val MIN_APPETITE = 0.35f
    private const val APPETITE_TAIL = 2.2f
    private const val APPETITE_RANGE = 2.6f

    /**
     * What a catchment costs a realm to take: the worst possible ground costs [POOR_GROUND_COST],
     * and a realm that has eaten its whole appetite pays [SATED_COST] on top of whatever the ground
     * itself costs.
     *
     * Being sated costs more than poor land does, which is what makes appetite the thing that
     * decides a realm's size and quality the thing that decides its shape. Both are on the same
     * scale as `NationsConfig.straitCrossingCost`.
     */
    private const val POOR_GROUND_COST = 4f
    private const val SATED_COST = 6f

    /**
     * Room reserved in the growth queue: a unit can be offered once per neighbour it has, and the
     * spare covers the seeds' own first offers on a world with very few units.
     */
    private const val HEAP_ENTRIES_PER_UNIT = 4
    private const val HEAP_SPARE = 64

    /**
     * Gives every remaining catchment to the nearest realm, spreading outward so the result stays
     * contiguous rather than handing a far island to whoever happens to score best.
     */
    private suspend fun claimStragglers(
        units: BasinUnits,
        owner: IntArray,
        realmCount: Int,
        centroids: Array<FloatArray>
    ): Int {
        var changed = true
        while (changed) {
            currentCoroutineContext().ensureActive()
            changed = false
            for (unit in 0 until units.unitCount) {
                if (owner[unit] != NationResult.UNCLAIMED) continue
                val settledNeighbour = units.neighbours[unit].firstOrNull {
                    owner[it] != NationResult.UNCLAIMED
                } ?: continue
                owner[unit] = owner[settledNeighbour]
                changed = true
            }
        }

        // Anything still unclaimed has no path to a realm at all — a lone island past every strait.
        // A substantial one becomes a realm of its own; a rock in the ocean does not, because a
        // three-cell sovereign state is not a country, it is a rendering artefact. Those go to
        // whichever realm lies nearest across the water.
        val smallestIslandRealm = smallestRealmCells(units.area.sum())
        var nextRealm = realmCount
        for (start in 0 until units.unitCount) {
            if (owner[start] != NationResult.UNCLAIMED || units.area[start] == 0) continue

            val island = ArrayList<Int>()
            val frontier = ArrayDeque<Int>()
            frontier.addLast(start)
            island.add(start)
            val reached = HashSet<Int>()
            reached.add(start)
            while (frontier.isNotEmpty()) {
                val unit = frontier.removeFirst()
                units.neighbours[unit].forEach { neighbour ->
                    if (owner[neighbour] == NationResult.UNCLAIMED && reached.add(neighbour)) {
                        island.add(neighbour)
                        frontier.addLast(neighbour)
                    }
                }
            }

            val islandCells = island.sumOf { units.area[it] }
            if (islandCells >= smallestIslandRealm) {
                val islandRealm = nextRealm++
                island.forEach { owner[it] = islandRealm }
            } else {
                val host = nearestRealm(units, owner, centroids, island)
                island.forEach { owner[it] = host }
            }
        }
        return nextRealm
    }

    /** The realm whose nearest territory is closest to [island], by centroid distance. */
    private fun nearestRealm(
        units: BasinUnits,
        owner: IntArray,
        centroids: Array<FloatArray>,
        island: List<Int>
    ): Int {
        var nearest = 0
        var nearestDistanceSquared = Float.MAX_VALUE
        for (other in 0 until units.unitCount) {
            val realm = owner[other]
            if (realm == NationResult.UNCLAIMED || units.area[other] == 0) continue
            island.forEach { mine ->
                val across = centroids[other][CENTROID_COLUMN] - centroids[mine][CENTROID_COLUMN]
                val down = centroids[other][CENTROID_ROW] - centroids[mine][CENTROID_ROW]
                val distanceSquared = across * across + down * down
                if (distanceSquared < nearestDistanceSquared) {
                    nearestDistanceSquared = distanceSquared
                    nearest = realm
                }
            }
        }
        return nearest
    }

    /**
     * The least land, in cells, that is given a flag of its own when it has nowhere else to go:
     * [MIN_ISLAND_REALM_SHARE] of [landCells], and never under [MIN_ISLAND_REALM_CELLS]. Shared with
     * `NationStage.dissolveEnclaves`, which asks the same question of a stranded piece.
     */
    internal fun smallestRealmCells(landCells: Int): Int =
        (landCells * MIN_ISLAND_REALM_SHARE).toInt().coerceAtLeast(MIN_ISLAND_REALM_CELLS)

    /** Rocks below this share of all land are not given a flag of their own. */
    private const val MIN_ISLAND_REALM_SHARE = 0.004f

    /**
     * And below this many cells outright, so a very small map cannot make a sovereign state out of
     * a handful of cells the share alone would let through.
     */
    private const val MIN_ISLAND_REALM_CELLS = 24

    /** The two slots of a centroid. */
    private const val CENTROID_COLUMN = 0
    private const val CENTROID_ROW = 1

    /** Mean position of each unit's cells, for judging which realm an island lies nearest. */
    private fun centroids(config: WorldGenConfig, units: BasinUnits): Array<FloatArray> {
        val cellsAcross = config.width
        val centroids = Array(units.unitCount) { FloatArray(2) }
        for (cell in units.unitOf.indices) {
            val unit = units.unitOf[cell]
            if (unit == BasinUnits.NONE) continue
            centroids[unit][CENTROID_COLUMN] += (cell % cellsAcross).toFloat()
            centroids[unit][CENTROID_ROW] += (cell / cellsAcross).toFloat()
        }
        for (unit in 0 until units.unitCount) {
            val cellsInUnit = units.area[unit].coerceAtLeast(1).toFloat()
            centroids[unit][CENTROID_COLUMN] /= cellsInUnit
            centroids[unit][CENTROID_ROW] /= cellsInUnit
        }
        return centroids
    }

    private class Realms(val owner: IntArray, val count: Int)

    /**
     * Splits some realms along one of their own watersheds.
     *
     * The split is a flood fill from one of the realm's catchments through its neighbours until
     * roughly half the territory has changed hands, so the two halves are each contiguous and the
     * line between them follows divides the realm already contained.
     */
    private fun schism(
        config: WorldGenConfig,
        units: BasinUnits,
        realmOfUnit: IntArray,
        realmCount: Int,
        quality: FloatArray,
        random: Random
    ): Realms {
        val owner = realmOfUnit.copyOf()
        var count = realmCount

        // Empires come apart. Appetite is a comparative brake — a realm bids against its
        // neighbours — so a realm that is the only bidder for a region takes it whatever its
        // appetite. Rather than move the seeds, which thins the contest for river valleys and so
        // costs the world its river borders, a realm past `NationsConfig.maxRealmShare` is cut in
        // two along its own internal watersheds, largest first, until none is over. Deterministic:
        // no random draw, and the flood fill walks sorted neighbour lists.
        // See GEOGRAPHY.md, "Realms of uneven size".
        val capCells = config.nations.maxRealmShare * units.area.sum().toFloat()
        var attempts = 0
        // A realm that cannot be split is set aside rather than ending the pass, so one realm
        // over the cap in a single catchment does not leave every other realm over it too.
        val unsplittable = HashSet<Int>()
        while (attempts++ < MAX_CAP_SPLITS) {
            val held = IntArray(count)
            for (unit in 0 until units.unitCount) {
                if (owner[unit] >= 0) held[owner[unit]] += units.area[unit]
            }
            var largestOverCap = -1
            for (realm in 0 until count) {
                if (held[realm] > capCells && realm !in unsplittable &&
                    (largestOverCap < 0 || held[realm] > held[largestOverCap])
                ) {
                    largestOverCap = realm
                }
            }
            if (largestOverCap < 0) break
            val mine = (0 until units.unitCount).filter { owner[it] == largestOverCap }
            val breakawayUnits = if (mine.size < MIN_CAP_SPLIT_UNITS) null else growBreakaway(
                units, owner, quality, largestOverCap, mine, minTakenUnits = 1
            )
            if (breakawayUnits == null) {
                unsplittable.add(largestOverCap)
                continue
            }
            val breakaway = count++
            breakawayUnits.forEach { owner[it] = breakaway }
        }
        val chance = config.nations.schismChance.coerceIn(0f, 1f)
        if (chance <= 0f) return Realms(owner, count)

        for (realm in 0 until realmCount) {
            val mine = (0 until units.unitCount).filter { owner[it] == realm }
            // Needs enough pieces that a split leaves two believable countries rather than a
            // country and an enclave.
            if (mine.size < MIN_SCHISM_UNITS) continue
            if (random.nextFloat() > chance) continue
            val breakawayUnits = growBreakaway(
                units, owner, quality, realm, mine, minTakenUnits = MIN_SCHISM_UNITS / 2
            ) ?: continue
            val breakaway = count++
            breakawayUnits.forEach { owner[it] = breakaway }
        }
        return Realms(owner, count)
    }

    /**
     * Half of one realm's catchments, grown outward from its poorest, or null when the fill took
     * fewer than [minTakenUnits] or swallowed the whole realm.
     *
     * [mine] is every catchment the realm holds. Starting from the poorest is what makes the
     * breakaway the periphery rather than the heartland, which is the way these usually go;
     * growing by flood fill through the realm's own neighbour lists is what keeps both halves
     * contiguous and puts the line between them on divides the realm already contained.
     */
    private fun growBreakaway(
        units: BasinUnits,
        owner: IntArray,
        quality: FloatArray,
        realm: Int,
        mine: List<Int>,
        minTakenUnits: Int
    ): Set<Int>? {
        val start = mine.minByOrNull { quality[it] } ?: return null

        val taken = HashSet<Int>()
        val frontier = ArrayDeque<Int>()
        frontier.addLast(start)
        taken.add(start)
        while (frontier.isNotEmpty() && taken.size < mine.size / 2) {
            val unit = frontier.removeFirst()
            units.neighbours[unit].forEach { neighbour ->
                if (owner[neighbour] == realm && taken.add(neighbour)) frontier.addLast(neighbour)
            }
        }
        if (taken.size < minTakenUnits || taken.size == mine.size) return null

        // The fill keeps the breakaway in one piece and not what it leaves: a breakaway grown
        // across the middle of a realm can cut part of the rest off from the rest's main body, and
        // that part would be an exclave of a realm it no longer touches. So what the realm keeps
        // is its largest run of land-joined catchments, and every other run the breakaway touches
        // by land goes with the breakaway.
        val left = mine.filter { it !in taken }.toHashSet()
        val runOf = HashMap<Int, Int>()
        val runArea = ArrayList<Int>()
        for (start in mine) {
            if (start !in left || start in runOf) continue
            val run = runArea.size
            var area = 0
            val stack = ArrayDeque<Int>()
            stack.addLast(start)
            runOf[start] = run
            while (stack.isNotEmpty()) {
                val unit = stack.removeLast()
                area += units.area[unit]
                for (neighbour in units.landNeighbours[unit]) {
                    if (neighbour in left && neighbour !in runOf) {
                        runOf[neighbour] = run
                        stack.addLast(neighbour)
                    }
                }
            }
            runArea.add(area)
        }
        if (runArea.size > 1) {
            val kept = runArea.indices.maxWith(compareBy<Int> { runArea[it] }.thenByDescending { it })
            val cutOff = mine.filter { unit ->
                val run = runOf[unit] ?: return@filter false
                run != kept && units.landNeighbours[unit].any { it in taken }
            }
            // A run touching the breakaway is joined to it whole: every unit of the run goes.
            val runsJoining = cutOff.map { runOf.getValue(it) }.toHashSet()
            for (unit in mine) if (runOf[unit]?.let { it in runsJoining } == true) taken.add(unit)
            if (taken.size == mine.size) return null
        }
        return taken
    }

    /**
     * Most times the over-cap split runs. Each pass takes one realm apart, so a world would have
     * to be pathological to need more; the loop leaves the moment nothing is over the cap.
     */
    private const val MAX_CAP_SPLITS = 64

    /**
     * Fewest catchments a realm must hold to be split.
     *
     * Two for the cap, because an over-large realm has to come apart somehow; four for a voluntary
     * schism, so that it leaves two believable countries rather than a country and an enclave.
     */
    private const val MIN_CAP_SPLIT_UNITS = 2
    private const val MIN_SCHISM_UNITS = 4

    /**
     * Picks where the realms start.
     *
     * Seeds are shared out between landmasses in proportion to their size before quality is
     * considered at all. Taking the best ground first sounds right and is not: the best ground is
     * nearly always on the largest continent, so every realm sprouted there and every island and
     * second continent was left to be annexed by whoever happened to reach a strait first. A world
     * where a distant archipelago belongs to itself is both likelier and more interesting.
     */
    private fun chooseSeeds(
        units: BasinUnits,
        quality: FloatArray,
        wanted: Int,
        random: Random
    ): List<Int> {
        // Scored once, then sorted — never inside the comparator, which is what made an earlier
        // version of this non-deterministic and threw from the sort itself.
        val scored = (0 until units.unitCount)
            .filter { units.area[it] > 0 }
            .map {
                it to quality[it] *
                    (MIN_SEED_JITTER + SEED_JITTER_RANGE * random.nextFloat())
            }
            .sortedByDescending { it.second }

        // How many realms each landmass has earned. Largest remainder, so the seats add up exactly
        // and a small island is not rounded out of existence.
        val landmassArea = IntArray(units.landmassCount)
        for (unit in 0 until units.unitCount) {
            if (units.area[unit] > 0) landmassArea[units.landmass[unit]] += units.area[unit]
        }
        val totalLand = landmassArea.sum()
        val seatsPerLandmass = IntArray(units.landmassCount)
        if (totalLand > 0) {
            var handedOut = 0
            val exactSeats = DoubleArray(units.landmassCount) {
                landmassArea[it].toDouble() * wanted / totalLand
            }
            for (landmass in 0 until units.landmassCount) {
                seatsPerLandmass[landmass] = exactSeats[landmass].toInt()
                handedOut += seatsPerLandmass[landmass]
            }
            (0 until units.landmassCount)
                .sortedByDescending { exactSeats[it] - seatsPerLandmass[it] }
                .take((wanted - handedOut).coerceAtLeast(0))
                .forEach { seatsPerLandmass[it]++ }
        }

        val chosen = ArrayList<Int>()
        val blocked = HashSet<Int>()
        for ((unit, _) in scored) {
            if (chosen.size >= wanted) break
            if (unit in blocked) continue
            val landmass = units.landmass[unit]
            if (seatsPerLandmass[landmass] <= 0) continue
            seatsPerLandmass[landmass]--
            chosen.add(unit)
            // One ring clear, so two capitals do not share a valley — and no more than one ring,
            // which was measured rather than assumed: two rings cost the world its river borders,
            // because river valleys are the richest ground and spacing the seeds out of them
            // leaves both banks to a single realm. Sprawl is handled where it belongs instead, by
            // splitting an oversized realm along its own watersheds; see [schism]. See
            // GEOGRAPHY.md, "Realms of uneven size".
            blocked.add(unit)
            units.neighbours[unit].forEach { blocked.add(it) }
        }
        // If spacing left us short, fill from whatever is left rather than returning too few.
        if (chosen.size < wanted) {
            for ((unit, _) in scored) {
                if (chosen.size >= wanted) break
                if (unit !in chosen) chosen.add(unit)
            }
        }
        return chosen
    }

    /**
     * How much a catchment's quality is jittered before the seeds are picked: three quarters of it
     * at least, and up to a quarter above it.
     *
     * Without the jitter the same map always seeds the same catchments, which makes two worlds
     * that differ only in their realm count look like the same world; with it too wide, a realm
     * takes root on ground nobody would settle.
     */
    private const val MIN_SEED_JITTER = 0.75f
    private const val SEED_JITTER_RANGE = 0.5f

    /**
     * Packs a growth cost and a realm-and-unit index into one sortable long: the cost's raw bits
     * in the high half, the index in the low half, so [LongMinHeap] orders by cost and breaks ties
     * on the index the same way on every platform.
     *
     * Biased by one so the bits sort in the same order as the values — a cost is never below -1,
     * and a negative float's raw bits sort backwards.
     */
    private fun encode(cost: Float, index: Int): Long {
        val bits = (cost + COST_BIAS).toRawBits()
        return (bits.toLong() shl 32) or index.toLong()
    }

    private fun decodeIndex(encoded: Long): Int = (encoded and 0xFFFFFFFFL).toInt()

    private const val COST_BIAS = 1f
}
