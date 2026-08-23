package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.math.BoxBlur
import com.cartogenesis.worldgen.math.LongMinHeap
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.NationsConfig
import com.cartogenesis.worldgen.model.WildernessMode
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.naming.NameForge
import com.cartogenesis.worldgen.naming.NameKind
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.random.Random

/** A generated realm. Everything here is a starting point the user is free to overrule. */
data class Nation(
    val id: Int,
    val name: String,
    val capitalName: String,
    /** Cell the realm grew from. */
    val originCell: Int,
    val capitalCell: Int,
    /** Seeds this realm's naming style, so neighbours sound like different peoples. */
    val cultureSeed: Long,
    val cellCount: Int,
    /** Estimated people, from the carrying capacity of the land actually held. */
    val population: Long,
    /** Share of the realm's land in each biome, largest first. */
    val biomeShare: List<Pair<Biome, Float>>,
    val coastalCells: Int,
    val riverCells: Int,
    val neighbours: Set<Int>,
    /**
     * The country its people actually occupy, weighted by habitability rather than raw area. A
     * realm can be mostly ice by the map and still be a temperate farming nation in every way that
     * matters, so this - not [biomeShare] - is what the atlas describes it by.
     */
    val heartlandBiome: Biome,
    val government: String,
    val exports: List<String>,
    val imports: List<String>,
    val lore: String
) {
    val isLandlocked: Boolean get() = coastalCells == 0
    /** Largest share of territory by area. Often polar waste on a big realm. */
    val dominantBiome: Biome get() = biomeShare.firstOrNull()?.first ?: Biome.GRASSLAND
}

data class NationResult(
    /** Realm id per cell; [UNCLAIMED] for water and for wilderness beyond any realm's reach. */
    val nationId: IntArray,
    val nations: List<Nation>,
    /** Habitability 0..1 per cell - also what the atlas uses to talk about arable land. */
    val habitability: FloatField
) {
    companion object {
        const val UNCLAIMED = -1
    }
}

/**
 * Step 6: settle the world.
 *
 * Realms are seeded on the most liveable ground and then grown outwards by cheapest-cost
 * expansion, where mountains, deserts, ice and open water all cost more to push through than
 * gentle farmland. Borders end up where expansion ran out of steam against something hard, which
 * is why they settle onto mountain ranges, rivers and coastlines without being told to.
 *
 * Expansion is capped, so remote and hostile country is left as unclaimed wilderness rather than
 * every last cell being painted.
 */
object NationStage {

    /** Scales cost into the sortable integer key the heap uses. */
    private const val COST_SCALE = 64f

    fun generate(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        climate: ClimateResult,
        rivers: RiverResult,
        ocean: OceanResult
    ): NationResult {
        val w = config.width
        val h = config.height
        val cfg = config.nations
        val cellCount = w * h

        val habitability = habitability(config, sea, climate, rivers, ocean)
        val nationId = IntArray(cellCount) { NationResult.UNCLAIMED }

        if (sea.landCellCount == 0 || cfg.nationCount <= 0) {
            return NationResult(nationId, emptyList(), habitability)
        }

        val origins = seedOrigins(config, sea, habitability)
        if (origins.isEmpty()) return NationResult(nationId, emptyList(), habitability)

        expand(config, sea, rivers, habitability, origins, nationId)
        return NationResult(nationId, describe(config, sea, climate, rivers, habitability, nationId, origins), habitability)
    }

    /**
     * How well people could live on a cell, 0..1. Driven by biome, then nudged by fresh water, by
     * how punishing the terrain is, and — on the coast — by what the sea offshore is doing.
     */
    private fun habitability(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        climate: ClimateResult,
        rivers: RiverResult,
        ocean: OceanResult
    ): FloatField {
        val w = config.width
        val h = config.height
        val field = FloatField(w, h)
        val riverThreshold = riverThreshold(sea, climate)

        for (i in 0 until w * h) {
            if (!sea.isLand[i]) continue

            var score = when (climate.biome[i]) {
                Biome.TEMPERATE_FOREST, Biome.GRASSLAND -> 1.0f
                Biome.TROPICAL_SEASONAL_FOREST, Biome.SAVANNA -> 0.85f
                Biome.TEMPERATE_RAINFOREST -> 0.75f
                Biome.SHRUBLAND -> 0.6f
                Biome.TROPICAL_RAINFOREST -> 0.5f
                Biome.TAIGA -> 0.4f
                Biome.TUNDRA -> 0.15f
                Biome.DESERT -> 0.12f
                Biome.ALPINE -> 0.08f
                Biome.ICE_SHEET -> 0.02f
                else -> 0.05f
            }

            // High ground is hard to farm and hard to hold.
            val elevation = sea.relativeElevation.data[i]
            score *= (1f - (elevation * 1.1f).coerceIn(0f, 0.85f))

            // Fresh water is worth more than anything else on this list.
            if (rivers.flowAccumulation.data[i] >= riverThreshold) score = (score + 0.35f).coerceAtMost(1f)

            field.data[i] = score.coerceIn(0f, 1f)
        }

        if (config.ocean.enabled) applyCoastalValue(config, sea, field, ocean)
        return field
    }

    /**
     * What the water offshore is worth to the coast beside it.
     *
     * Two separate things, pulling opposite ways on temperature. A warm current gives an ice-free
     * harbour and a mild hinterland; a cold one gives fog and a short growing season. But cold
     * water rising over a shallow shelf is where the fish are, and a fishery will support a coast
     * whose own soil never could. So a warm coast is a better place to live and a cold shelf is a
     * better place to eat, and both end up on the map.
     *
     * Only the seaward cells get this, and it is applied after the main loop so the biome score it
     * modifies is already final.
     */
    private fun applyCoastalValue(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        field: FloatField,
        ocean: OceanResult
    ) {
        val w = config.width
        val h = config.height
        val cfg = config.nations

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (!sea.isLand[i]) continue

                var anomalySum = 0f
                var shelfUpwelling = 0f
                var waterNeighbours = 0
                var landNeighbours = 0

                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny !in 0 until h) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = (x + dx + w) % w
                        val n = ny * w + nx
                        if (sea.isLand[n]) {
                            landNeighbours++
                            continue
                        }
                        waterNeighbours++
                        val anomaly = ocean.anomaly.data[n]
                        anomalySum += anomaly
                        // Shelf, not open ocean: depth below sea level, small means shallow.
                        val depth = -sea.relativeElevation.data[n]
                        if (anomaly < 0f && depth < cfg.navigableDepth) {
                            shelfUpwelling += -anomaly
                        }
                    }
                }
                if (waterNeighbours == 0) continue

                val meanAnomaly = anomalySum / waterNeighbours
                // A bay ringed by land is sheltered; an exposed headland is not. Neighbouring land
                // count is a crude stand-in for that, but it is the one the grid actually knows.
                val shelter = (landNeighbours / 7f).coerceIn(0f, 1f)
                val harbour = (meanAnomaly / 6f).coerceIn(-1f, 1f) *
                    cfg.warmHarbourBonus * (0.45f + 0.55f * shelter)
                val fishery = (shelfUpwelling / waterNeighbours / 6f).coerceIn(0f, 1f) *
                    cfg.upwellingFisheryBonus

                field.data[i] = (field.data[i] + harbour + fishery).coerceIn(0f, 1f)
            }
        }
    }

    /** Matches RiverStage's notion of "big enough to be drawn", without re-tracing anything. */
    private fun riverThreshold(sea: SeaLevelResult, climate: ClimateResult): Float {
        var totalRunoff = 0f
        for (i in sea.isLand.indices) {
            if (sea.isLand[i]) totalRunoff += 0.05f + climate.precipitation.data[i]
        }
        return (totalRunoff * 0.0006f).coerceAtLeast(1e-4f)
    }

    /**
     * Picks starting cells: the most liveable ground, but forced apart so realms do not all sprout
     * inside the same fertile valley.
     */
    private fun seedOrigins(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        habitability: FloatField
    ): List<Int> {
        val w = config.width
        val h = config.height
        val random = Random(config.seed * 31337 + 7)

        // Each cell is scored once and then sorted on that fixed value. Drawing the jitter inside
        // the comparator instead would give a cell a different score on each comparison, which
        // both breaks the sort contract and destroys the determinism the whole pipeline rests on.
        val candidates = (0 until w * h)
            .filter { sea.isLand[it] && habitability.data[it] > config.nations.minSeedHabitability }
            .map { it to habitability.data[it] * (0.75f + 0.5f * random.nextFloat()) }
            .sortedByDescending { it.second }
            .map { it.first }

        if (candidates.isEmpty()) return emptyList()

        val wanted = config.nations.nationCount
        // Natural spacing for this many realms over this much land.
        val natural = kotlin.math.sqrt(sea.landCellCount.toDouble() / wanted).toFloat()

        // Wide spacing spreads realms onto continents that greedy picking would otherwise skip,
        // but too wide and there is nowhere left to put the last few. Rather than trade coverage
        // against count with a fixed figure, start wide and relax until the count is met.
        var spacing = natural * config.nations.seedSpacing
        var best: List<Int> = emptyList()
        repeat(6) {
            val chosen = pickSpaced(candidates, w, spacing, wanted)
            if (chosen.size > best.size) best = chosen
            if (chosen.size >= wanted) return chosen
            spacing *= 0.75f
        }
        return best
    }

    private fun pickSpaced(candidates: List<Int>, width: Int, spacing: Float, wanted: Int): List<Int> {
        val chosen = ArrayList<Int>(wanted)
        val minimumSquared = spacing * spacing
        for (candidate in candidates) {
            if (chosen.size >= wanted) break
            val cx = candidate % width
            val cy = candidate / width
            val clear = chosen.none { other ->
                val ox = other % width
                val oy = other / width
                var dx = abs(cx - ox).toFloat()
                if (dx > width / 2f) dx = width - dx
                val dy = (cy - oy).toFloat()
                dx * dx + dy * dy < minimumSquared
            }
            if (clear) chosen.add(candidate)
        }
        return chosen
    }

    /**
     * Multi-source cheapest-path expansion. Every realm pushes outwards at once and each cell
     * falls to whichever reaches it most cheaply, so the frontier stalls on exactly the terrain
     * that is expensive to cross.
     */
    private fun expand(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        rivers: RiverResult,
        habitability: FloatField,
        origins: List<Int>,
        nationId: IntArray
    ) {
        val w = config.width
        val h = config.height
        val cfg = config.nations

        val best = FloatArray(w * h) { Float.MAX_VALUE }
        val heap = LongMinHeap(origins.size * 8 + 1024)

        // Budget scales with the map so a realm covers a similar share of the world at any size.
        // Claiming everything just lifts the cap: the frontier keeps going until it meets another
        // realm rather than until it runs out of momentum.
        val budget = if (cfg.wilderness == WildernessMode.CLAIM_ALL_LAND) {
            Float.MAX_VALUE
        } else {
            cfg.reach * kotlin.math.sqrt(w.toFloat() * h / config.nations.nationCount)
        }

        origins.forEachIndexed { index, cell ->
            best[cell] = 0f
            nationId[cell] = index
            heap.push(encode(0f, cell))
        }

        while (!heap.isEmpty()) {
            val entry = heap.pop()
            val cell = decodeIndex(entry)
            val cost = decodeCost(entry)
            // Stale queue entry - this cell was already reached more cheaply.
            if (cost > best[cell] + 1e-4f) continue

            val owner = nationId[cell]
            val x = cell % w
            val y = cell / w

            forEachNeighbour(w, h, x, y) { next, distance ->
                val step = stepCost(sea, rivers, habitability, cell, next, cfg) * distance
                val total = cost + step
                if (total < best[next] && total <= budget) {
                    best[next] = total
                    nationId[next] = owner
                    heap.push(encode(total, next))
                }
            }
        }

        // Water was only ever a way to reach the far shore; it is not territory.
        for (i in nationId.indices) {
            if (!sea.isLand[i]) nationId[i] = NationResult.UNCLAIMED
        }
    }

    /** What it costs to push settlement from [from] into [to]. */
    private fun stepCost(
        sea: SeaLevelResult,
        rivers: RiverResult,
        habitability: FloatField,
        from: Int,
        to: Int,
        cfg: NationsConfig
    ): Float {
        if (!sea.isLand[to]) {
            // Crossing water is possible but dear, and only worth it over a narrow strait. Deep
            // ocean is effectively a wall.
            val depth = -sea.relativeElevation.data[to]
            return if (depth > cfg.navigableDepth) cfg.seaCrossingCost * 8f else cfg.seaCrossingCost
        }

        // Poor land is slow to settle; good land is quick.
        var cost = 1f + (1f - habitability.data[to]) * cfg.terrainResistance

        // Climbing is what really stops an expanding realm, so borders find the ridgelines.
        val climb = sea.relativeElevation.data[to] - sea.relativeElevation.data[from]
        if (climb > 0f) cost += climb * cfg.slopeResistance

        // A river is a natural line to stop at as well as a prize to hold.
        if (rivers.flowAccumulation.data[to] > rivers.flowAccumulation.data[from] * 4f) {
            cost += cfg.riverBorderCost
        }
        return cost
    }

    private fun describe(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        climate: ClimateResult,
        rivers: RiverResult,
        habitability: FloatField,
        nationId: IntArray,
        origins: List<Int>
    ): List<Nation> {
        val w = config.width
        val h = config.height
        val riverThreshold = riverThreshold(sea, climate)
        val cellArea = config.nations.squareKilometresPerCell(w, h)

        // Resources discovered near a realm are folded in later by the landmark stage; realms
        // start from what their own land yields.
        val resourcesNear = HashMap<Int, List<String>>()
        val counts = IntArray(origins.size)
        val coastal = IntArray(origins.size)
        val riverine = IntArray(origins.size)
        val capacity = DoubleArray(origins.size)
        val biomes = Array(origins.size) { HashMap<Biome, Int>() }
        val neighbours = Array(origins.size) { HashSet<Int>() }
        // Blurred copies, so a capital can be judged on the country around it rather than the
        // single cell it stands on: how much food its hinterland could grow, and whether it sits
        // above the surrounding ground or in a hollow.
        val hinterland = habitability.copy()
        BoxBlur.apply(hinterland, radius = (config.width / 64).coerceAtLeast(2), passes = 2)
        val smoothedElevation = sea.relativeElevation.copy()
        BoxBlur.apply(smoothedElevation, radius = (config.width / 96).coerceAtLeast(2), passes = 2)

        val bestCapital = FloatArray(origins.size) { -1f }
        val capitalCell = IntArray(origins.size) { -1 }
        // Biomes weighted by how liveable they are, so the heartland reflects where people are
        // rather than how much frozen waste the realm happens to enclose.
        val heartlandBiome = Array(origins.size) { HashMap<Biome, Float>() }
        val highGround = IntArray(origins.size)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val owner = nationId[i]
                if (owner == NationResult.UNCLAIMED) continue

                counts[owner]++
                biomes[owner][climate.biome[i]] = (biomes[owner][climate.biome[i]] ?: 0) + 1
                capacity[owner] += habitability.data[i].toDouble()
                heartlandBiome[owner][climate.biome[i]] =
                    (heartlandBiome[owner][climate.biome[i]] ?: 0f) + habitability.data[i]
                if (sea.relativeElevation.data[i] > 0.4f) highGround[owner]++

                var touchesSea = false
                forEachNeighbour(w, h, x, y) { n, _ ->
                    if (!sea.isLand[n]) touchesSea = true
                    val other = nationId[n]
                    if (other != NationResult.UNCLAIMED && other != owner) neighbours[owner].add(other)
                }
                if (touchesSea) coastal[owner]++

                val onRiver = rivers.flowAccumulation.data[i] >= riverThreshold
                if (onRiver) riverine[owner]++

                // Why a capital ends up somewhere, in roughly the order history cares about.
                //
                // Fresh water first: it is the one non-negotiable, and a river is worth more than
                // a harbour. Then the hinterland, since a capital needs land around it that can
                // feed the place. Then defensibility — high ground relative to its surroundings.
                // A harbour counts, but modestly: an earlier version gave it a flat bonus large
                // enough that every realm touching a coast put its capital on the shore, and the
                // audit found 12 of 12 capitals coastal on every seed, which no real map shows.
                var score = habitability.data[i] * 0.8f
                if (onRiver) score += 0.45f
                if (touchesSea) score += 0.18f
                score += hinterland.data[i] * 0.6f

                // The head of navigation, not the river mouth. Historically a capital sits where
                // boats coming upriver have to stop and unload — inland enough to be defensible
                // and out of the floodplain, but still reachable by water. Without this the best
                // score is always the river mouth, which put nearly every capital on the shore.
                if (onRiver && !touchesSea) score += 0.10f

                val relief = sea.relativeElevation.data[i] - smoothedElevation.data[i]
                score += relief.coerceIn(0f, 0.12f) * 2.5f

                if (score > bestCapital[owner]) {
                    bestCapital[owner] = score
                    capitalCell[owner] = i
                }
            }
        }

        return origins.indices.mapNotNull { id ->
            if (counts[id] == 0) return@mapNotNull null
            val total = counts[id].toFloat()
            val cultureSeed = config.seed * 1_000_003L + id * 7919L
            val random = Random(cultureSeed * 17 + 3)

            val name = NameForge.name(cultureSeed, NameKind.REALM, 0L)
            val capitalName = NameForge.name(cultureSeed, NameKind.SETTLEMENT, 1L)
            val shares = biomes[id].entries.sortedByDescending { it.value }
                .map { it.key to it.value / total }
            val heartland = heartlandBiome[id].entries.maxByOrNull { it.value }?.key
                ?: shares.firstOrNull()?.first ?: Biome.GRASSLAND

            val population =
                (capacity[id] * cellArea * config.nations.peoplePerArableKm2).roundToLong()
            val coastalShare = coastal[id] / total
            val riverShare = riverine[id] / total
            val mountainShare = highGround[id] / total
            val landlocked = coastal[id] == 0

            val government = Atlas.government(random, coastalShare, counts[id], neighbours[id].size)
            // Production follows people, not acreage — a realm whose bulk is polar waste still
            // makes its living off the temperate ground its farmers actually work.
            val settledShares = heartlandBiome[id].entries
                .sortedByDescending { it.value }
                .let { entries ->
                    val weight = entries.sumOf { it.value.toDouble() }.toFloat().coerceAtLeast(1e-4f)
                    entries.map { it.key to it.value / weight }
                }
            val exports = Atlas.exports(
                random, settledShares, coastalShare, riverShare, mountainShare,
                resourcesNear[id].orEmpty()
            )

            Nation(
                id = id,
                name = name,
                capitalName = capitalName,
                originCell = origins[id],
                capitalCell = capitalCell[id].takeIf { it >= 0 } ?: origins[id],
                cultureSeed = cultureSeed,
                cellCount = counts[id],
                population = population,
                biomeShare = shares,
                coastalCells = coastal[id],
                riverCells = riverine[id],
                neighbours = neighbours[id],
                heartlandBiome = heartland,
                government = government,
                exports = exports,
                imports = Atlas.imports(settledShares, coastalShare, mountainShare, exports),
                lore = Atlas.lore(
                    random, name, government, heartland, landlocked,
                    neighbours[id].size, population, capitalName
                )
            )
        }
    }

    private fun encode(cost: Float, index: Int): Long {
        val key = (cost * COST_SCALE).toLong().coerceIn(0L, 0x7FFF_FFFFL)
        return (key shl 32) or index.toLong()
    }

    private fun decodeIndex(encoded: Long): Int = (encoded and 0xFFFFFFFFL).toInt()

    private fun decodeCost(encoded: Long): Float = (encoded ushr 32).toFloat() / COST_SCALE

    private inline fun forEachNeighbour(
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        action: (index: Int, distance: Float) -> Unit
    ) {
        for (dy in -1..1) {
            val ny = y + dy
            if (ny < 0 || ny >= height) continue
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                var nx = (x + dx) % width
                if (nx < 0) nx += width
                action(ny * width + nx, if (dx != 0 && dy != 0) 1.41421356f else 1f)
            }
        }
    }
}
