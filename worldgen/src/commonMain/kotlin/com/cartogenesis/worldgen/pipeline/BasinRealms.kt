package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.math.LongMinHeap
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WildernessMode
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.pow
import kotlin.random.Random

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

    fun assign(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        units: BasinUnits,
        habitability: FloatField,
        random: Random
    ): Assignment {
        val cfg = config.nations
        val realmOfUnit = IntArray(units.unitCount) { NationResult.UNCLAIMED }
        if (cfg.nationCount <= 0 || units.unitCount == 0) {
            return Assignment(IntArray(config.width * config.height) { NationResult.UNCLAIMED }, emptyList())
        }

        // How good a place each catchment is to hold, and where in it a capital would sit.
        val worth = FloatArray(units.unitCount)
        val bestCell = IntArray(units.unitCount) { -1 }
        val bestScore = FloatArray(units.unitCount) { -1f }
        for (i in units.unitOf.indices) {
            val u = units.unitOf[i]
            if (u == BasinUnits.NONE) continue
            val score = habitability.data[i]
            worth[u] += score
            if (score > bestScore[u]) {
                bestScore[u] = score
                bestCell[u] = i
            }
        }
        val quality = FloatArray(units.unitCount) {
            if (units.area[it] == 0) 0f else worth[it] / units.area[it]
        }

        // Seed on the best land, but forced apart, or every realm sprouts in the same fertile
        // valley and the rest of the world is left to whoever is nearest.
        val seeds = chooseSeeds(units, quality, cfg.nationCount, random)
        seeds.forEachIndexed { realm, unit -> realmOfUnit[unit] = realm }

        // Appetite. Raised to a power so the draw has a tail: most realms want a middling amount,
        // a few want a great deal.
        val totalLand = units.area.sum().toFloat()
        val appetite = FloatArray(seeds.size) {
            (0.35f + random.nextFloat().pow(2.2f) * 2.6f)
        }
        val share = FloatArray(seeds.size)
        val fairShare = totalLand / seeds.size
        val held = IntArray(seeds.size)
        seeds.forEachIndexed { realm, unit -> held[realm] = units.area[unit] }

        // Grow by claiming whichever adjacent catchment is cheapest, cheapest meaning good land
        // that the realm still has appetite for.
        val heap = LongMinHeap(units.unitCount * 4 + 64)
        fun offer(realm: Int, from: Int, unit: Int) {
            if (realmOfUnit[unit] != NationResult.UNCLAIMED) return
            val hunger = (appetite[realm] * fairShare - held[realm]) / fairShare
            if (hunger <= 0f && cfg.wilderness != WildernessMode.CLAIM_ALL_LAND) return
            // Poor ground is dear, and a realm that has eaten its fill finds everything dear.
            var cost = (1f - quality[unit]) * 4f + (1f - hunger).coerceAtLeast(0f) * 6f
            // Water is crossable but not free. Without this a realm that reaches one strait tends
            // to island-hop the length of an archipelago, which produced a single realm spanning
            // half the world the first time this ran.
            if (units.landmass[unit] != units.landmass[from]) cost += cfg.straitCrossingCost
            heap.push(encode(cost, realm * units.unitCount + unit))
        }

        seeds.forEachIndexed { realm, unit ->
            units.neighbours[unit].forEach { offer(realm, unit, it) }
        }

        while (!heap.isEmpty()) {
            val entry = heap.pop()
            val packed = decodeIndex(entry)
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
        if (cfg.wilderness == WildernessMode.CLAIM_ALL_LAND) {
            // Returns a new count, because a wholly isolated island becomes a realm of its own.
            // Passing the old one here dropped those islands off the end of every later loop and
            // left them unclaimed after all — 3% of seed 7's land, which is what this was for.
            realmCount = claimStragglers(units, realmOfUnit, realmCount, centroids(config, units))
        }

        val realms = schism(config, units, realmOfUnit, realmCount, quality, random)

        // Back down to cells.
        val realmOf = IntArray(config.width * config.height) { NationResult.UNCLAIMED }
        for (i in realmOf.indices) {
            val u = units.unitOf[i]
            if (u != BasinUnits.NONE) realmOf[i] = realms.owner[u]
        }

        // A capital for every realm that ended up with land, taken from its best-scoring unit.
        val origins = ArrayList<Int>()
        for (realm in 0 until realms.count) {
            var pick = -1
            var pickScore = -1f
            for (u in 0 until units.unitCount) {
                if (realms.owner[u] != realm) continue
                if (bestScore[u] > pickScore) {
                    pickScore = bestScore[u]
                    pick = bestCell[u]
                }
            }
            if (pick >= 0) origins.add(pick)
        }

        // Renumber so realm ids are contiguous and match the origin list.
        val renumber = HashMap<Int, Int>()
        for (realm in 0 until realms.count) {
            var hasLand = false
            for (u in 0 until units.unitCount) if (realms.owner[u] == realm) { hasLand = true; break }
            if (hasLand) renumber[realm] = renumber.size
        }
        for (i in realmOf.indices) {
            val r = realmOf[i]
            if (r != NationResult.UNCLAIMED) realmOf[i] = renumber[r] ?: NationResult.UNCLAIMED
        }
        for (i in realmOf.indices) if (!sea.isLand[i]) realmOf[i] = NationResult.UNCLAIMED

        return Assignment(realmOf, origins)
    }

    /**
     * Gives every remaining catchment to the nearest realm, spreading outward so the result stays
     * contiguous rather than handing a far island to whoever happens to score best.
     */
    private fun claimStragglers(
        units: BasinUnits,
        owner: IntArray,
        realmCount: Int,
        centroids: Array<FloatArray>
    ): Int {
        var changed = true
        while (changed) {
            changed = false
            for (u in 0 until units.unitCount) {
                if (owner[u] != NationResult.UNCLAIMED) continue
                val neighbour = units.neighbours[u].firstOrNull {
                    owner[it] != NationResult.UNCLAIMED
                } ?: continue
                owner[u] = owner[neighbour]
                changed = true
            }
        }

        // Anything still unclaimed has no path to a realm at all — a lone island past every strait.
        // A substantial one becomes a realm of its own; a rock in the ocean does not, because a
        // three-cell sovereign state is not a country, it is a rendering artefact. Those go to
        // whichever realm lies nearest across the water.
        val minIslandRealm = (units.area.sum() * MIN_ISLAND_REALM_SHARE).toInt().coerceAtLeast(24)
        var next = realmCount
        for (u in 0 until units.unitCount) {
            if (owner[u] != NationResult.UNCLAIMED || units.area[u] == 0) continue

            val group = ArrayList<Int>()
            val frontier = ArrayDeque<Int>()
            frontier.addLast(u)
            group.add(u)
            val visiting = HashSet<Int>()
            visiting.add(u)
            while (frontier.isNotEmpty()) {
                val c = frontier.removeFirst()
                units.neighbours[c].forEach {
                    if (owner[it] == NationResult.UNCLAIMED && visiting.add(it)) {
                        group.add(it)
                        frontier.addLast(it)
                    }
                }
            }

            val area = group.sumOf { units.area[it] }
            if (area >= minIslandRealm) {
                val island = next++
                group.forEach { owner[it] = island }
            } else {
                val host = nearestRealm(units, owner, centroids, group)
                group.forEach { owner[it] = host }
            }
        }
        return next
    }

    /** The realm whose nearest territory is closest to [group], by centroid distance. */
    private fun nearestRealm(
        units: BasinUnits,
        owner: IntArray,
        centroids: Array<FloatArray>,
        group: List<Int>
    ): Int {
        var best = 0
        var bestDistance = Float.MAX_VALUE
        for (other in 0 until units.unitCount) {
            val realm = owner[other]
            if (realm == NationResult.UNCLAIMED || units.area[other] == 0) continue
            group.forEach { mine ->
                val dx = centroids[other][0] - centroids[mine][0]
                val dy = centroids[other][1] - centroids[mine][1]
                val d = dx * dx + dy * dy
                if (d < bestDistance) {
                    bestDistance = d
                    best = realm
                }
            }
        }
        return best
    }

    /** Rocks below this share of all land are not given a flag of their own. */
    private const val MIN_ISLAND_REALM_SHARE = 0.004f

    /** Mean position of each unit's cells, for judging which realm an island lies nearest. */
    private fun centroids(config: WorldGenConfig, units: BasinUnits): Array<FloatArray> {
        val w = config.width
        val sums = Array(units.unitCount) { FloatArray(2) }
        for (i in units.unitOf.indices) {
            val u = units.unitOf[i]
            if (u == BasinUnits.NONE) continue
            sums[u][0] += (i % w).toFloat()
            sums[u][1] += (i / w).toFloat()
        }
        for (u in 0 until units.unitCount) {
            val n = units.area[u].coerceAtLeast(1).toFloat()
            sums[u][0] /= n
            sums[u][1] /= n
        }
        return sums
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
        val chance = config.nations.schismChance.coerceIn(0f, 1f)
        if (chance <= 0f) return Realms(owner, count)

        for (realm in 0 until realmCount) {
            val mine = (0 until units.unitCount).filter { owner[it] == realm }
            // Needs enough pieces that a split leaves two believable countries rather than a
            // country and an enclave.
            if (mine.size < 4) continue
            if (random.nextFloat() > chance) continue

            val targetSize = mine.size / 2
            // Start from the piece furthest from the realm's best land, so the breakaway is the
            // periphery rather than the heartland — which is the way these usually go.
            val start = mine.minByOrNull { quality[it] } ?: continue

            val taken = HashSet<Int>()
            val frontier = ArrayDeque<Int>()
            frontier.addLast(start)
            taken.add(start)
            while (frontier.isNotEmpty() && taken.size < targetSize) {
                val u = frontier.removeFirst()
                units.neighbours[u].forEach { nb ->
                    if (owner[nb] == realm && taken.add(nb)) frontier.addLast(nb)
                }
            }
            if (taken.size < 2 || taken.size == mine.size) continue

            val breakaway = count++
            taken.forEach { owner[it] = breakaway }
        }
        return Realms(owner, count)
    }

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
            .map { it to quality[it] * (0.75f + 0.5f * random.nextFloat()) }
            .sortedByDescending { it.second }

        // How many realms each landmass has earned. Largest remainder, so the seats add up exactly
        // and a small island is not rounded out of existence.
        val landArea = IntArray(units.landmassCount)
        for (u in 0 until units.unitCount) {
            if (units.area[u] > 0) landArea[units.landmass[u]] += units.area[u]
        }
        val total = landArea.sum()
        val allocation = IntArray(units.landmassCount)
        if (total > 0) {
            var handed = 0
            val exact = DoubleArray(units.landmassCount) { landArea[it].toDouble() * wanted / total }
            for (m in 0 until units.landmassCount) {
                allocation[m] = exact[m].toInt()
                handed += allocation[m]
            }
            (0 until units.landmassCount)
                .sortedByDescending { exact[it] - allocation[it] }
                .take((wanted - handed).coerceAtLeast(0))
                .forEach { allocation[it]++ }
        }

        val chosen = ArrayList<Int>()
        val blocked = HashSet<Int>()
        for ((unit, _) in scored) {
            if (chosen.size >= wanted) break
            if (unit in blocked) continue
            val mass = units.landmass[unit]
            if (allocation[mass] <= 0) continue
            allocation[mass]--
            chosen.add(unit)
            // Keep the immediate neighbourhood clear so two capitals do not share a valley.
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

    private fun encode(cost: Float, index: Int): Long {
        val bits = (cost + 1f).toRawBits()
        return (bits.toLong() shl 32) or index.toLong()
    }

    private fun decodeIndex(encoded: Long): Int = (encoded and 0xFFFFFFFFL).toInt()
}
