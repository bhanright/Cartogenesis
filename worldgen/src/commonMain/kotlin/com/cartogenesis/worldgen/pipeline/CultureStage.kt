package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.math.LongMinHeap
import com.cartogenesis.worldgen.model.CulturesConfig
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.naming.NameForge
import kotlin.math.abs
import kotlin.random.Random

/** A people, as distinct from a state. */
data class Culture(
    val id: Int,
    /** What they are called collectively, as in "the Verrin peoples". */
    val name: String,
    /** Where the people began: the most like-itself country they hold. */
    val hearthCell: Int,
    val cellCount: Int,
    /** The country they mostly live in, which is what a culture is usually described by. */
    val dominantBiome: Biome,
    /** Seeds their language, so a culture's names sound like each other and unlike its neighbours. */
    val nameSeed: Long
)

data class CultureResult(
    /** Culture per cell; [UNSETTLED] for water and for country no people has spread into. */
    val cultureId: IntArray,
    val cultures: List<Culture>
) {
    companion object {
        const val UNSETTLED = -1
    }
}

/**
 * Who lives where, as opposed to who rules where.
 *
 * Realms are drawn from catchments, because a state's reach is a matter of the ground it can hold.
 * A people is a different thing and answers to a different pressure: it spreads through country
 * that resembles the country it came from. A steppe people follows the steppe, a forest people
 * stops where the forest does, and neither of them cares where a border was drawn. So a culture
 * grows outward from its hearth at a cost set by how unlike home the next piece of land is, and
 * comes to rest where the climate turns rather than where a realm ends.
 *
 * That difference is the whole point of the layer. Cultures and realms are grown from different
 * pressures, so they disagree: a realm holds several peoples, a people spans several realms, and
 * the mismatch between the two is where most of a world's history comes from. `CultureRealmTest`
 * measures that they really do disagree, because a layer that quietly reproduced the political map
 * would be worse than no layer at all.
 *
 * Cultures are built from catchments too, but from the unsplit ones. Realms cut a catchment along
 * its trunk river so the water can serve as a frontier; a people usually lives on both banks and
 * treats the river as its road, so the coarser partition is both cheaper and closer to right.
 */
object CultureStage {

    fun generate(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        climate: ClimateResult,
        rivers: RiverResult
    ): CultureResult {
        val cells = config.width * config.height
        val cfg = config.cultures
        val empty = IntArray(cells) { CultureResult.UNSETTLED }
        if (!cfg.enabled || cfg.cultureCount <= 0 || sea.landCellCount == 0) {
            return CultureResult(empty, emptyList())
        }

        val land = sea.landCellCount
        val units = BasinPartition.mergeSmall(
            config, sea,
            BasinPartition.compute(
                config, sea, rivers, (land * cfg.maxRegionShare).toInt().coerceAtLeast(16)
            ),
            (land * cfg.minRegionShare).toInt().coerceAtLeast(8)
        )
        if (units.unitCount == 0) return CultureResult(empty, emptyList())

        val profile = profile(config, sea, climate, units)
        val random = Random(config.seed * 7919 + 101)
        val hearths = chooseHearths(units, profile, cfg.cultureCount, random)
        if (hearths.isEmpty()) return CultureResult(empty, emptyList())

        val owner = spread(cfg, units, profile, hearths)
        return describe(config, sea, climate, units, profile, hearths, owner)
    }

    /**
     * What each catchment is like to live in, and whether anyone lives there at all.
     *
     * Deliberately independent of the realm stage's habitability figure, which would tie the
     * peoples of the world to a political setting: change the realm count and every culture would
     * move.
     */
    private class Profile(
        val temperature: FloatArray,
        val rainfall: FloatArray,
        val elevation: FloatArray,
        val habitable: BooleanArray,
        val biome: Array<Biome>
    )

    private fun profile(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        climate: ClimateResult,
        units: BasinUnits
    ): Profile {
        val n = units.unitCount
        val temperature = FloatArray(n)
        val rainfall = FloatArray(n)
        val elevation = FloatArray(n)
        val counts = IntArray(n)
        val biomeTally = Array(n) { HashMap<Biome, Int>() }

        for (i in units.unitOf.indices) {
            val u = units.unitOf[i]
            if (u == BasinUnits.NONE) continue
            temperature[u] += climate.temperature.data[i]
            rainfall[u] += climate.precipitation.data[i]
            elevation[u] += sea.relativeElevation.data[i]
            counts[u]++
            val b = climate.biome[i]
            biomeTally[u][b] = (biomeTally[u][b] ?: 0) + 1
        }

        val biome = Array(n) { Biome.GRASSLAND }
        val habitable = BooleanArray(n)
        for (u in 0 until n) {
            val c = counts[u].coerceAtLeast(1)
            temperature[u] /= c
            rainfall[u] /= c
            elevation[u] /= c
            biome[u] = biomeTally[u].maxByOrNull { it.value }?.key ?: Biome.GRASSLAND
            // Only the ice cap is genuinely empty. Tundra is bleak and has held people for as
            // long as there have been people, so the bar is deliberately low — this is not a
            // judgement about how pleasant the ground is.
            habitable[u] = counts[u] > 0 &&
                biome[u] != Biome.ICE_SHEET &&
                temperature[u] > config.cultures.minTemperatureC
        }
        return Profile(temperature, rainfall, elevation, habitable, biome)
    }

    /**
     * Where the peoples begin: habitable, spread apart, and preferring the middle of a climate
     * rather than its edge, so a culture has somewhere to expand into on every side.
     */
    private fun chooseHearths(
        units: BasinUnits,
        profile: Profile,
        wanted: Int,
        random: Random
    ): List<Int> {
        val candidates = (0 until units.unitCount)
            .filter { profile.habitable[it] && units.area[it] > 0 }
        if (candidates.isEmpty()) return emptyList()

        // A hearth is worth more where its neighbours are like it: that is a heartland rather than
        // a frontier, and it is where a people would actually have come from. Scored once and then
        // sorted, never inside the comparator, which is what made an earlier sort elsewhere in this
        // pipeline non-deterministic and then throw from the sort itself.
        val scored = candidates.map { u ->
            val neighbours = units.neighbours[u].filter { profile.habitable[it] }
            val likeness = if (neighbours.isEmpty()) 0f else neighbours.map { nb ->
                1f - climateDistance(profile, u, nb).coerceAtMost(1f)
            }.average().toFloat()
            u to likeness * units.area[u] * (0.7f + 0.6f * random.nextFloat())
        }.sortedByDescending { it.second }

        val chosen = ArrayList<Int>()
        val blocked = HashSet<Int>()
        for ((unit, _) in scored) {
            if (chosen.size >= wanted) break
            if (unit in blocked) continue
            chosen.add(unit)
            blocked.add(unit)
            // Two hearths sharing a neighbourhood would produce one people split down the middle.
            units.neighbours[unit].forEach { near ->
                blocked.add(near)
                units.neighbours[near].forEach { blocked.add(it) }
            }
        }
        if (chosen.isEmpty()) chosen.add(candidates.first())
        return chosen
    }

    /**
     * How unlike each other two pieces of country are, 0 for identical.
     *
     * Temperature is divided by 25 degrees and rainfall is already normalised to 0..1, so the two
     * terms land on roughly the same scale and neither drowns the other.
     */
    private fun climateDistance(profile: Profile, a: Int, b: Int): Float {
        val temperature = abs(profile.temperature[a] - profile.temperature[b]) / 25f
        val rainfall = abs(profile.rainfall[a] - profile.rainfall[b])
        val height = abs(profile.elevation[a] - profile.elevation[b])
        return temperature + rainfall * 0.8f + height * 0.5f
    }

    /**
     * Multi-source cheapest-path spread, where a step costs what the new country differs from the
     * *hearth* by, not from the neighbour it is entered from.
     *
     * Measuring against the hearth is what keeps a culture coherent. Measured against the
     * neighbour, a people drifts: every step is a small change, and a chain of small changes walks
     * a steppe people into a rainforest without any one step ever looking wrong.
     */
    private fun spread(
        cfg: CulturesConfig,
        units: BasinUnits,
        profile: Profile,
        hearths: List<Int>
    ): IntArray {
        val owner = IntArray(units.unitCount) { CultureResult.UNSETTLED }
        val heap = LongMinHeap(units.unitCount * hearths.size + 64)

        fun push(culture: Int, unit: Int, cost: Float) {
            heap.push(encode(cost, culture * units.unitCount + unit))
        }

        hearths.forEachIndexed { culture, unit ->
            owner[unit] = culture
            units.neighbours[unit].forEach {
                push(culture, it, stepCost(cfg, units, profile, unit, it, unit))
            }
        }

        while (!heap.isEmpty()) {
            val entry = heap.pop()
            val packed = decodeIndex(entry)
            val culture = packed / units.unitCount
            val unit = packed % units.unitCount
            if (owner[unit] != CultureResult.UNSETTLED) continue

            // Claimed even when nobody lives there. Treating hostile ground as a wall stranded
            // everything behind it — a third of seed 42's land, including a whole southern
            // landmass reached only across tundra. People have always crossed such country even
            // where they did not stay, so it is dear to pass and empty on the map, not solid.
            owner[unit] = culture
            val soFar = decodeCost(entry)
            val hearth = hearths[culture]
            units.neighbours[unit].forEach { next ->
                if (owner[next] == CultureResult.UNSETTLED) {
                    push(culture, next, soFar + stepCost(cfg, units, profile, unit, next, hearth))
                }
            }
        }
        return owner
    }

    private fun stepCost(
        cfg: CulturesConfig,
        units: BasinUnits,
        profile: Profile,
        from: Int,
        to: Int,
        hearth: Int
    ): Float {
        var cost = 1f + climateDistance(profile, hearth, to) * cfg.climateAffinity
        // People do cross water, and an island people is a real thing, but a strait is still more
        // of a barrier to settlement than the same distance of open ground.
        if (units.landmass[from] != units.landmass[to]) cost += cfg.seaCrossingCost
        // Ground nobody settles is crossed rather than lived in, so it is expensive to pass and
        // does not become anybody's territory when the map is drawn.
        if (!profile.habitable[to]) cost += cfg.hostileCrossingCost
        return cost
    }

    private fun describe(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        climate: ClimateResult,
        units: BasinUnits,
        profile: Profile,
        hearths: List<Int>,
        owner: IntArray
    ): CultureResult {
        val cells = config.width * config.height
        val cultureId = IntArray(cells) { CultureResult.UNSETTLED }
        for (i in 0 until cells) {
            val u = units.unitOf[i]
            // Hostile country was claimed only so the spread could pass through it. Nobody lives
            // there, so it is drawn empty.
            if (u != BasinUnits.NONE && sea.isLand[i] && profile.habitable[u]) cultureId[i] = owner[u]
        }

        val counts = HashMap<Int, Int>()
        val biomeTally = HashMap<Int, HashMap<Biome, Int>>()
        for (i in 0 until cells) {
            val c = cultureId[i]
            if (c == CultureResult.UNSETTLED) continue
            counts[c] = (counts[c] ?: 0) + 1
            val tally = biomeTally.getOrPut(c) { HashMap() }
            val b = climate.biome[i]
            tally[b] = (tally[b] ?: 0) + 1
        }

        // A cell of the hearth catchment, for a label to sit on.
        val hearthCell = IntArray(hearths.size) { -1 }
        for (i in 0 until cells) {
            val u = units.unitOf[i]
            if (u == BasinUnits.NONE) continue
            hearths.forEachIndexed { id, hearthUnit ->
                if (u == hearthUnit && hearthCell[id] < 0) hearthCell[id] = i
            }
        }

        val described = ArrayList<Culture>()
        hearths.forEachIndexed { id, hearthUnit ->
            val area = counts[id] ?: 0
            if (area == 0) return@forEachIndexed
            val nameSeed = config.seed * 31 + id * 7919L + 13
            described.add(
                Culture(
                    id = id,
                    // A bare stem rather than a region name: `NameKind.REGION` produces things
                    // like "Expanse of Progrus", which reads as nonsense once "peoples" is added.
                    // Real names for this are plain adjectives — Slavic, Turkic, Han.
                    name = "${NameForge.stem(nameSeed, 0L).replaceFirstChar { it.uppercase() }} peoples",
                    hearthCell = hearthCell[id],
                    cellCount = area,
                    dominantBiome = biomeTally[id]?.maxByOrNull { it.value }?.key
                        ?: profile.biome[hearthUnit],
                    nameSeed = nameSeed
                )
            )
        }

        // Renumber so ids are contiguous and match the list, since a hearth boxed in by ice can
        // end up holding no land at all.
        val renumber = HashMap<Int, Int>()
        described.forEach { renumber[it.id] = renumber.size }
        for (i in 0 until cells) {
            val c = cultureId[i]
            if (c != CultureResult.UNSETTLED) cultureId[i] = renumber[c] ?: CultureResult.UNSETTLED
        }
        return CultureResult(
            cultureId,
            described.mapIndexed { index, culture -> culture.copy(id = index) }
        )
    }

    private fun encode(cost: Float, index: Int): Long {
        val bits = (cost + 1f).toRawBits()
        return (bits.toLong() shl 32) or index.toLong()
    }

    private fun decodeIndex(encoded: Long): Int = (encoded and 0xFFFFFFFFL).toInt()

    private fun decodeCost(encoded: Long): Float =
        Float.fromBits((encoded ushr 32).toInt()) - 1f
}
