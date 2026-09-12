package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.math.LongMinHeap
import com.cartogenesis.worldgen.model.CulturesConfig
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.naming.NameForge
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.serialization.Serializable

/** A people, as distinct from a state. */
@Serializable
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
 *
 * See REALISM_PLAN.md, B3.
 */
object CultureStage {

    /**
     * Who lives where: a culture id per cell, row-major and [CultureResult.UNSETTLED] over water
     * and over country nobody settles, together with one [Culture] per people that ended up
     * holding land.
     *
     * [climate] supplies the temperature, rainfall and biome a people's affinity for its homeland
     * is measured against; [rivers] supplies the drainage the cultural regions are cut from.
     */
    fun generate(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        climate: ClimateResult,
        rivers: RiverResult
    ): CultureResult {
        val cellCount = config.width * config.height
        val empty = IntArray(cellCount) { CultureResult.UNSETTLED }
        val (units, profile, hearths) = placeHearths(config, sea, climate, rivers)
            ?: return CultureResult(empty, emptyList())

        val owner = spread(config.cultures, units, profile, hearths)
        return describe(config, sea, climate, units, profile, hearths, owner)
    }

    /**
     * The catchments, what each is like, and where the hearths land — everything downstream of
     * hearth placement needs. Split out from [generate] so `CultureHearthLandmassTest` can check
     * the placement directly, against [BasinUnits.landmass] and [BasinUnits.area], rather than
     * inferring landmasses from the finished map with a second flood fill that would not
     * necessarily agree with this stage's own notion of one.
     */
    internal fun placeHearths(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        climate: ClimateResult,
        rivers: RiverResult
    ): Triple<BasinUnits, Profile, List<Int>>? {
        val culturesConfig = config.cultures
        if (!culturesConfig.enabled ||
            culturesConfig.cultureCount <= 0 ||
            sea.landCellCount == 0
        ) {
            return null
        }

        val landCells = sea.landCellCount
        val units = BasinPartition.mergeSmall(
            config, sea,
            BasinPartition.compute(
                config, sea, rivers,
                (landCells * culturesConfig.maxRegionShare).toInt()
                    .coerceAtLeast(SMALLEST_LARGE_REGION)
            ),
            (landCells * culturesConfig.minRegionShare).toInt()
                .coerceAtLeast(SMALLEST_KEPT_REGION)
        )
        if (units.unitCount == 0) return null

        val profile = profile(config, sea, climate, units)
        val random = Random(config.seed * HEARTH_SEED_MULTIPLIER + HEARTH_SEED_OFFSET)
        val hearths = chooseHearths(units, profile, culturesConfig.cultureCount, random)
        if (hearths.isEmpty()) return null
        return Triple(units, profile, hearths)
    }

    /**
     * Floors on the region sizes, in cells, for the two shares in `CulturesConfig` that are
     * expressed against the whole world's land: a share of a very small map rounds to nothing.
     */
    private const val SMALLEST_LARGE_REGION = 16
    private const val SMALLEST_KEPT_REGION = 8

    /**
     * Decorrelates the hearth draw from the realm draw, so that changing the realm count does not
     * move a single people — which is the whole reason cultures have a config section of their own.
     */
    private const val HEARTH_SEED_MULTIPLIER = 7919L
    private const val HEARTH_SEED_OFFSET = 101L

    /**
     * What each catchment is like to live in, and whether anyone lives there at all.
     *
     * Deliberately independent of the realm stage's habitability figure, which would tie the
     * peoples of the world to a political setting: change the realm count and every culture would
     * move.
     */
    internal class Profile(
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
        val unitCount = units.unitCount
        val temperature = FloatArray(unitCount)
        val rainfall = FloatArray(unitCount)
        val elevation = FloatArray(unitCount)
        val cellsInUnit = IntArray(unitCount)
        val nonIceCells = IntArray(unitCount)
        val biomeTally = Array(unitCount) { HashMap<Biome, Int>() }

        for (cell in units.unitOf.indices) {
            val unit = units.unitOf[cell]
            if (unit == BasinUnits.NONE) continue
            temperature[unit] += climate.temperature.data[cell]
            rainfall[unit] += climate.precipitation.data[cell]
            elevation[unit] += sea.relativeElevation.data[cell]
            cellsInUnit[unit]++
            val biomeHere = climate.biome[cell]
            biomeTally[unit][biomeHere] = (biomeTally[unit][biomeHere] ?: 0) + 1
            if (biomeHere != Biome.ICE_SHEET) nonIceCells[unit]++
        }

        val biome = Array(unitCount) { Biome.GRASSLAND }
        val habitable = BooleanArray(unitCount)
        for (unit in 0 until unitCount) {
            val divisor = cellsInUnit[unit].coerceAtLeast(1)
            temperature[unit] /= divisor
            rainfall[unit] /= divisor
            elevation[unit] /= divisor
            // Ties by ordinal rather than by iteration order, which differs between platforms.
            biome[unit] = biomeTally[unit].entries
                .maxWithOrNull(
                    compareBy<Map.Entry<Biome, Int>> { it.value }
                        .thenByDescending { it.key.ordinal }
                )
                ?.key ?: Biome.GRASSLAND
            // Only the ice cap is genuinely empty. Tundra is bleak and has held people for as
            // long as there have been people, so the bar is deliberately low — this is not a
            // judgement about how pleasant the ground is, and `Biome.ICE_SHEET` is already a
            // temperature test, so a second, looser one here would be a second *place* for
            // "empty" to be defined that could silently disagree with the first.
            //
            // A has-any question, not the majority vote [biome] answers for hearth scoring and
            // [climateDistance]. A unit straddling a retreating ice margin can vote ICE_SHEET by
            // majority while a large minority of its cells are not ice, and gating reachability
            // on the vote strands that minority behind a "nobody lives here" the spread cannot
            // cross. Which of a reachable unit's cells actually get settled is then decided per
            // cell in [describe], against each cell's own biome.
            habitable[unit] = nonIceCells[unit] > 0
        }
        return Profile(temperature, rainfall, elevation, habitable, biome)
    }

    /**
     * Where the peoples begin: habitable, spread apart, and preferring the middle of a climate
     * rather than its edge, so a culture has somewhere to expand into on every side.
     *
     * Seats are shared out between landmasses in proportion to their *habitable* area before
     * quality is considered at all, exactly as `BasinRealms.chooseSeeds` shares out realm
     * capitals — and for the same reason. Left unweighted, every hearth crowds onto whichever
     * landmass has the best-scoring ground, which is nearly always the largest one; the few
     * hearths that land there then split it between too few competitors, and one of them swallows
     * the rest. See REALISM_PLAN.md, B3, for what that measured.
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
        val scored = candidates.map { unit ->
            val habitableNeighbours = units.neighbours[unit].filter { profile.habitable[it] }
            val likeness = if (habitableNeighbours.isEmpty()) 0f else {
                habitableNeighbours.map { neighbour ->
                    1f - climateDistance(profile, unit, neighbour).coerceAtMost(1f)
                }.average().toFloat()
            }
            unit to likeness * units.area[unit] *
                (MIN_HEARTH_JITTER + HEARTH_JITTER_RANGE * random.nextFloat())
        }.sortedByDescending { it.second }

        // How many hearths each landmass has earned, by its share of *habitable* area (not all
        // land — an ice-bound landmass should draw no seats). Largest remainder, so the seats add
        // up exactly and a small habitable island is not rounded out of existence.
        val habitableArea = IntArray(units.landmassCount)
        for (unit in candidates) habitableArea[units.landmass[unit]] += units.area[unit]
        val totalHabitable = habitableArea.sum()
        val seatsPerLandmass = IntArray(units.landmassCount)
        if (totalHabitable > 0) {
            var handedOut = 0
            val exactSeats = DoubleArray(units.landmassCount) {
                habitableArea[it].toDouble() * wanted / totalHabitable
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
            blocked.add(unit)
            // Two hearths sharing a neighbourhood would produce one people split down the middle.
            units.neighbours[unit].forEach { near ->
                blocked.add(near)
                units.neighbours[near].forEach { blocked.add(it) }
            }
        }
        // If a landmass's quota could not be filled from its own candidates (too few units, or
        // all blocked by the exclusion ring), fill the remaining seats from whatever is left
        // rather than returning too few — same fallback as BasinRealms.chooseSeeds.
        if (chosen.size < wanted) {
            for ((unit, _) in scored) {
                if (chosen.size >= wanted) break
                if (unit !in chosen) chosen.add(unit)
            }
        }
        if (chosen.isEmpty()) chosen.add(candidates.first())
        return chosen
    }

    /**
     * How unlike each other two catchments are to live in, 0 for identical and about 1 for
     * as-different-as-it-gets.
     *
     * Temperature is divided by [FULL_TEMPERATURE_SPREAD_C] and rainfall is already normalised to
     * 0..1, so the three terms land on roughly the same scale and none of them drowns the others.
     */
    private fun climateDistance(profile: Profile, one: Int, other: Int): Float {
        val temperature = abs(profile.temperature[one] - profile.temperature[other]) /
            FULL_TEMPERATURE_SPREAD_C
        val rainfall = abs(profile.rainfall[one] - profile.rainfall[other])
        val height = abs(profile.elevation[one] - profile.elevation[other])
        return temperature + rainfall * RAINFALL_UNLIKENESS + height * HEIGHT_UNLIKENESS
    }

    /**
     * Degrees of mean annual temperature that count as wholly unlike country.
     *
     * Twenty-five: about the gap between the Mediterranean and the Arctic, which is as far as a
     * people is ever asked to consider moving in one step.
     */
    private const val FULL_TEMPERATURE_SPREAD_C = 25f

    /**
     * What a full swing of rainfall and of elevation are worth against that.
     *
     * Both under one, because temperature is the axis that decides where a people will and will
     * not settle; rainfall matters nearly as much, and altitude least, since a people that farms
     * an upland valley is still that people.
     */
    private const val RAINFALL_UNLIKENESS = 0.8f
    private const val HEIGHT_UNLIKENESS = 0.5f

    /**
     * How much a candidate hearth's score is jittered: seven tenths of it at least, and up to
     * three tenths above it. As in `BasinRealms`, so two worlds that differ only in their culture
     * count are not the same world twice.
     */
    private const val MIN_HEARTH_JITTER = 0.7f
    private const val HEARTH_JITTER_RANGE = 0.6f

    /**
     * Multi-source cheapest-path spread, where a step costs what the new country differs from the
     * *hearth* by, not from the neighbour it is entered from.
     *
     * Measuring against the hearth is what keeps a culture coherent. Measured against the
     * neighbour, a people drifts: every step is a small change, and a chain of small changes walks
     * a steppe people into a rainforest without any one step ever looking wrong.
     */
    private fun spread(
        culturesConfig: CulturesConfig,
        units: BasinUnits,
        profile: Profile,
        hearths: List<Int>
    ): IntArray {
        val owner = IntArray(units.unitCount) { CultureResult.UNSETTLED }
        val frontier = LongMinHeap(units.unitCount * hearths.size + HEAP_SPARE)

        fun push(culture: Int, unit: Int, cost: Float) {
            frontier.push(encode(cost, culture * units.unitCount + unit))
        }

        hearths.forEachIndexed { culture, unit ->
            owner[unit] = culture
            units.neighbours[unit].forEach {
                push(culture, it, stepCost(culturesConfig, units, profile, unit, it, unit))
            }
        }

        while (!frontier.isEmpty()) {
            val entry = frontier.pop()
            val packed = decodeIndex(entry)
            val culture = packed / units.unitCount
            val unit = packed % units.unitCount
            if (owner[unit] != CultureResult.UNSETTLED) continue

            // Claimed even when nobody lives there. Treating hostile ground as a wall stranded
            // everything behind it — on one seed a third of the world's land, including a whole
            // southern landmass reached only across tundra. People have always crossed such
            // country even where they did not stay, so it is dear to pass and empty on the map,
            // not solid.
            owner[unit] = culture
            val costSoFar = decodeCost(entry)
            val hearth = hearths[culture]
            units.neighbours[unit].forEach { next ->
                if (owner[next] == CultureResult.UNSETTLED) {
                    push(
                        culture,
                        next,
                        costSoFar + stepCost(culturesConfig, units, profile, unit, next, hearth)
                    )
                }
            }
        }
        return owner
    }

    /**
     * What it costs one people to take the next catchment: one for the step itself, plus how
     * unlike its own [hearth] the new country is, plus whatever water or waste lies between.
     */
    private fun stepCost(
        culturesConfig: CulturesConfig,
        units: BasinUnits,
        profile: Profile,
        from: Int,
        to: Int,
        hearth: Int
    ): Float {
        var cost = STEP_COST +
            climateDistance(profile, hearth, to) * culturesConfig.climateAffinity
        // People do cross water, and an island people is a real thing, but a strait is still more
        // of a barrier to settlement than the same distance of open ground.
        if (units.landmass[from] != units.landmass[to]) cost += culturesConfig.seaCrossingCost
        // Ground nobody settles is crossed rather than lived in, so it is expensive to pass and
        // does not become anybody's territory when the map is drawn.
        if (!profile.habitable[to]) cost += culturesConfig.hostileCrossingCost
        return cost
    }

    /**
     * What one step costs before any of the terrain terms, which is what sets the scale the
     * `CulturesConfig` costs are read against: a sea crossing at 3 is three ordinary steps.
     */
    private const val STEP_COST = 1f

    /** Room reserved in the spread's queue beyond one entry per culture per catchment. */
    private const val HEAP_SPARE = 64

    private fun describe(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        climate: ClimateResult,
        units: BasinUnits,
        profile: Profile,
        hearths: List<Int>,
        owner: IntArray
    ): CultureResult {
        val cellCount = config.width * config.height
        val cultureId = IntArray(cellCount) { CultureResult.UNSETTLED }
        for (cell in 0 until cellCount) {
            val unit = units.unitOf[cell]
            // Hostile country was claimed only so the spread could pass through it. Nobody lives
            // there, so it is drawn empty. Gated per cell against that cell's own biome, not the
            // unit's majority vote: a mixed unit at the ice margin is reachable as soon as any of
            // it is livable, but the ice half of it still is not, and people live on the tundra
            // half of a catchment even when the other half is ice.
            if (unit != BasinUnits.NONE && sea.isLand[cell] && profile.habitable[unit] &&
                climate.biome[cell] != Biome.ICE_SHEET
            ) {
                cultureId[cell] = owner[unit]
            }
        }

        val settledCells = HashMap<Int, Int>()
        val biomeTally = HashMap<Int, HashMap<Biome, Int>>()
        for (cell in 0 until cellCount) {
            val culture = cultureId[cell]
            if (culture == CultureResult.UNSETTLED) continue
            settledCells[culture] = (settledCells[culture] ?: 0) + 1
            val tally = biomeTally.getOrPut(culture) { HashMap() }
            val biomeHere = climate.biome[cell]
            tally[biomeHere] = (tally[biomeHere] ?: 0) + 1
        }

        // A cell of the hearth catchment, for a label to sit on.
        val hearthCell = IntArray(hearths.size) { -1 }
        val hearthOfUnit = IntArray(units.unitCount) { -1 }
        hearths.forEachIndexed { id, unit -> hearthOfUnit[unit] = id }
        for (cell in 0 until cellCount) {
            val unit = units.unitOf[cell]
            if (unit == BasinUnits.NONE) continue
            val id = hearthOfUnit[unit]
            if (id >= 0 && hearthCell[id] < 0) hearthCell[id] = cell
        }

        val described = ArrayList<Culture>()
        hearths.forEachIndexed { id, hearthUnit ->
            val area = settledCells[id] ?: 0
            if (area == 0) return@forEachIndexed
            val nameSeed = config.seed * NAME_SEED_MULTIPLIER +
                id * NAME_SEED_STRIDE + NAME_SEED_OFFSET
            described.add(
                Culture(
                    id = id,
                    // A bare stem rather than a region name: `NameKind.REGION` produces things
                    // like "Expanse of Progrus", which reads as nonsense once "peoples" is added.
                    // Real names for this are plain adjectives — Slavic, Turkic, Han.
                    name = "${NameForge.stem(nameSeed, 0L).replaceFirstChar { it.uppercase() }} peoples",
                    hearthCell = hearthCell[id],
                    cellCount = area,
                    dominantBiome = biomeTally[id]?.entries
                        ?.maxWithOrNull(
                            compareBy<Map.Entry<Biome, Int>> { it.value }
                                .thenByDescending { it.key.ordinal }
                        )
                        ?.key ?: profile.biome[hearthUnit],
                    nameSeed = nameSeed
                )
            )
        }

        // Renumber so ids are contiguous and match the list, since a hearth boxed in by ice can
        // end up holding no land at all.
        val renumbered = HashMap<Int, Int>()
        described.forEach { renumbered[it.id] = renumbered.size }
        for (cell in 0 until cellCount) {
            val culture = cultureId[cell]
            if (culture != CultureResult.UNSETTLED) {
                cultureId[cell] = renumbered[culture] ?: CultureResult.UNSETTLED
            }
        }
        return CultureResult(
            cultureId,
            described.mapIndexed { index, culture -> culture.copy(id = index) }
        )
    }

    /**
     * Turns the world seed and a culture's index into the seed its name is drawn from. Distinct
     * from `NationStage`'s realm seeds, so a people and a realm never share a naming style by
     * accident.
     */
    private const val NAME_SEED_MULTIPLIER = 31L
    private const val NAME_SEED_STRIDE = 7919L
    private const val NAME_SEED_OFFSET = 13L

    /**
     * Packs a spread cost and a culture-and-unit index into one sortable long: the cost's raw bits
     * in the high half, the index in the low half, so [LongMinHeap] orders by cost and breaks ties
     * on the index the same way on every platform.
     *
     * Biased by [COST_BIAS] so the bits sort in the same order as the values, and unbiased again
     * on the way out — a cost is never below -1, and a negative float's raw bits sort backwards.
     */
    private fun encode(cost: Float, index: Int): Long {
        val bits = (cost + COST_BIAS).toRawBits()
        return (bits.toLong() shl 32) or index.toLong()
    }

    private fun decodeIndex(encoded: Long): Int = (encoded and 0xFFFFFFFFL).toInt()

    private fun decodeCost(encoded: Long): Float =
        Float.fromBits((encoded ushr 32).toInt()) - COST_BIAS

    private const val COST_BIAS = 1f
}
