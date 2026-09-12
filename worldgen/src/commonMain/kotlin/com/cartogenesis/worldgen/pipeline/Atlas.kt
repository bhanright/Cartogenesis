package com.cartogenesis.worldgen.pipeline

import kotlin.random.Random

/**
 * Turns what a realm physically holds into the sort of thing an atlas says about it: how it is
 * governed, what it sells, what it has to buy in, and a line of description.
 *
 * All of it is inference from the generated world — a realm exports grain because it holds
 * grassland, and imports timber because it holds no forest. It is invented, and every field is
 * meant to be overwritten by the user, but it should never contradict the map.
 */
internal object Atlas {

    /**
     * A form of government drawn from the ones a realm of this shape could plausibly have: a
     * maritime one where it has coast, a marcher one where it has many neighbours, an imperial one
     * where it is large.
     *
     * [coastalShare] is the share of the realm's cells that touch water and [cellCount] its area
     * in cells; [neighbours] is how many realms it borders. See [IMPERIAL_CELLS] on the one thing
     * here that does not travel between resolutions.
     */
    fun government(random: Random, coastalShare: Float, cellCount: Int, neighbours: Int): String {
        val options = buildList {
            add("Kingdom")
            add("Duchy")
            if (coastalShare > MARITIME_COASTAL_SHARE) {
                add("Merchant Republic"); add("Maritime League")
            }
            if (neighbours >= MANY_NEIGHBOURS) { add("Confederacy"); add("Marcher Lordship") }
            if (cellCount > IMPERIAL_CELLS) { add("Empire"); add("High Kingdom") }
            if (cellCount < CITY_STATE_CELLS) { add("Free City"); add("Principality") }
            add("Theocracy")
            add("Elective Monarchy")
            add("Council of Elders")
        }
        return options.random(random)
    }

    /** Coast enough to make a living from the sea, as a share of the realm's own cells. */
    private const val MARITIME_COASTAL_SHARE = 0.12f

    /** Neighbours enough that holding the frontier is the realm's central problem. */
    private const val MANY_NEIGHBOURS = 3

    /**
     * Area, in cells, above which a realm may call itself an empire and below which it may call
     * itself a free city.
     *
     * In cells rather than in square kilometres, which means the same world exported at a finer
     * grid promotes every realm: at 512 a typical realm already clears the imperial bar. Left as
     * it stands because changing it moves every world's prose; recorded here because it is a
     * difference and not a design.
     */
    private const val IMPERIAL_CELLS = 6000
    private const val CITY_STATE_CELLS = 1500

    /**
     * What the land produces in surplus: at most four goods, drawn from the realm's largest
     * biomes and from its coast, its mountains and its rivers.
     *
     * [biomes] is the realm's biome shares, largest first, and [nearbyResources] whatever the
     * landmark stage has found on its ground.
     */
    fun exports(
        random: Random,
        biomes: List<Pair<Biome, Float>>,
        coastalShare: Float,
        riverShare: Float,
        mountainShare: Float,
        nearbyResources: List<String>
    ): List<String> {
        val goods = LinkedHashSet<String>()
        nearbyResources.take(MOST_NEARBY_RESOURCES).forEach { goods.add(it.lowercase()) }

        biomes.take(MOST_EXPORTING_BIOMES).forEach { (biome, share) ->
            if (share < WORTH_EXPORTING_SHARE) return@forEach
            goods.addAll(
                when (biome) {
                    Biome.GRASSLAND -> listOf("grain", "horses", "wool")
                    Biome.TEMPERATE_FOREST, Biome.TEMPERATE_RAINFOREST -> listOf("timber", "furs", "pitch")
                    Biome.TAIGA -> listOf("timber", "furs", "amber")
                    Biome.TROPICAL_RAINFOREST -> listOf("spices", "hardwood", "dyestuffs")
                    Biome.TROPICAL_SEASONAL_FOREST, Biome.SAVANNA -> listOf("cotton", "ivory", "cattle")
                    Biome.SHRUBLAND -> listOf("olives", "wine", "goats")
                    Biome.MEDITERRANEAN -> listOf("olive oil", "wine", "citrus")
                    Biome.MONSOON_FOREST -> listOf("rice", "tea", "lacquer")
                    Biome.DESERT -> listOf("salt", "glass", "incense")
                    Biome.TUNDRA, Biome.ICE_SHEET -> listOf("furs", "whale oil", "walrus ivory")
                    Biome.ALPINE -> listOf("stone", "slate")
                    else -> emptyList()
                }.shuffled(random).take(GOODS_PER_BIOME)
            )
        }

        if (coastalShare > EXPORTING_COAST_SHARE) {
            goods.addAll(listOf("salt fish", "sea salt", "pearls").shuffled(random).take(1))
        }
        if (mountainShare > EXPORTING_MOUNTAIN_SHARE) {
            goods.addAll(listOf("iron", "silver", "cut stone").shuffled(random).take(1))
        }
        if (riverShare > EXPORTING_RIVER_SHARE) goods.add("river trade")

        return goods.take(MOST_GOODS_LISTED).toList()
    }

    /**
     * How much of a realm each thing has to be before the atlas mentions it.
     *
     * All read against the realm's own area rather than the world's, so a small realm that is all
     * grassland exports grain and a large one with a corner of it does not.
     */
    private const val WORTH_EXPORTING_SHARE = 0.08f
    private const val EXPORTING_COAST_SHARE = 0.1f
    private const val EXPORTING_MOUNTAIN_SHARE = 0.15f
    private const val EXPORTING_RIVER_SHARE = 0.12f

    /**
     * How long the lists get. An atlas entry is a sentence, not an inventory, so a realm is known
     * by a handful of goods rather than by everything its ground could yield.
     */
    private const val MOST_NEARBY_RESOURCES = 2
    private const val MOST_EXPORTING_BIOMES = 3
    private const val GOODS_PER_BIOME = 2
    private const val MOST_GOODS_LISTED = 4

    /**
     * Staples the realm cannot supply itself, so has to buy in: at most [MOST_GOODS_LISTED] of
     * them, and never something it already sells.
     */
    fun imports(
        biomes: List<Pair<Biome, Float>>,
        coastalShare: Float,
        mountainShare: Float,
        exports: List<String>
    ): List<String> {
        val shares = biomes.toMap()
        fun share(vararg wanted: Biome) = wanted.sumOf { (shares[it] ?: 0f).toDouble() }.toFloat()

        val needs = LinkedHashSet<String>()
        if (share(
                Biome.TEMPERATE_FOREST, Biome.TAIGA, Biome.TROPICAL_RAINFOREST,
                Biome.TEMPERATE_RAINFOREST
            ) < SELF_SUFFICIENT_TIMBER_SHARE
        ) needs.add("timber")
        if (share(Biome.GRASSLAND, Biome.SAVANNA, Biome.TROPICAL_SEASONAL_FOREST) <
            SELF_SUFFICIENT_GRAIN_SHARE
        ) {
            needs.add("grain")
        }
        if (mountainShare < SELF_SUFFICIENT_IRON_SHARE) needs.add("iron")
        if (coastalShare < SELF_SUFFICIENT_SALT_SHARE) needs.add("salt")
        if (share(Biome.TROPICAL_RAINFOREST, Biome.TROPICAL_SEASONAL_FOREST) <
            SELF_SUFFICIENT_SPICE_SHARE
        ) {
            needs.add("spices")
        }
        if (share(Biome.SHRUBLAND) < SELF_SUFFICIENT_WINE_SHARE) needs.add("wine")

        // Never import what you already sell.
        return needs.filterNot { need -> exports.any { it.contains(need, ignoreCase = true) } }
            .take(MOST_GOODS_LISTED)
    }

    /**
     * How much of the country that yields each staple a realm needs before it can feed itself.
     *
     * Lower than the export shares above, because a realm can supply its own timber off far less
     * forest than it needs to sell any.
     */
    private const val SELF_SUFFICIENT_TIMBER_SHARE = 0.1f
    private const val SELF_SUFFICIENT_GRAIN_SHARE = 0.12f
    private const val SELF_SUFFICIENT_IRON_SHARE = 0.06f
    private const val SELF_SUFFICIENT_SALT_SHARE = 0.04f
    private const val SELF_SUFFICIENT_SPICE_SHARE = 0.05f
    private const val SELF_SUFFICIENT_WINE_SHARE = 0.05f

    /**
     * A line or three of description, inferred from the same facts the rest of this file reads:
     * what the heartland is like, whether the realm has a coast, how many neighbours it has, and
     * how many people live in it.
     */
    fun lore(
        random: Random,
        name: String,
        government: String,
        heartland: Biome,
        landlocked: Boolean,
        neighbourCount: Int,
        population: Long,
        capital: String
    ): String {
        val terrain = when (heartland) {
            Biome.GRASSLAND -> "open grassland"
            Biome.TEMPERATE_FOREST, Biome.TEMPERATE_RAINFOREST -> "deep forest"
            Biome.TAIGA -> "cold pine forest"
            Biome.TUNDRA, Biome.ICE_SHEET -> "frozen waste"
            Biome.DESERT -> "burning desert"
            Biome.SAVANNA -> "dry savanna"
            Biome.SHRUBLAND -> "sun-scrubbed hills"
            Biome.MEDITERRANEAN -> "olive country"
            Biome.MONSOON_FOREST -> "rain-fed forest"
            Biome.TROPICAL_RAINFOREST -> "steaming jungle"
            Biome.TROPICAL_SEASONAL_FOREST -> "monsoon forest"
            Biome.ALPINE -> "high stone country"
            else -> "mixed country"
        }

        val article = if (government.first().uppercaseChar() in "AEIOU") "an" else "a"
        val opening = listOf(
            "$name is $article $government of $terrain, ruled from $capital.",
            "Seated at $capital, the $government of $name holds a stretch of $terrain.",
            "$name rose out of the $terrain, and is governed from $capital."
        ).random(random)

        val posture = when {
            landlocked && neighbourCount >= 3 ->
                "Hemmed in on every side and with no coast to escape to, it has learned to play its neighbours against one another."
            landlocked ->
                "Without a coastline of its own, it depends on the goodwill of whoever holds the river mouths."
            neighbourCount == 0 ->
                "It shares no land border with anyone, and its people regard the sea as their only road."
            neighbourCount >= 4 ->
                "It borders more realms than it can comfortably watch, and its frontier garrisons are never stood down."
            else ->
                "Its ports do more to set its fortunes than its armies ever have."
        }

        val scale = when {
            population > GREAT_POWER_PEOPLE -> "Its census is the envy and the terror of the region."
            population > REGIONAL_POWER_PEOPLE ->
                "It is populous enough to matter in any quarrel nearby."
            population > MIDDLING_POWER_PEOPLE -> "It is a middling power, and knows it."
            else -> "It is thinly peopled, and survives by being more trouble to take than it is worth."
        }

        return "$opening $posture $scale"
    }

    /**
     * Population above which a realm reads as a great, a regional or a middling power.
     *
     * Loosely Earth's own orders of magnitude for a pre-industrial state: forty million is Han
     * China or Mughal India, twelve is France under Louis XIV, three is a substantial kingdom.
     */
    private const val GREAT_POWER_PEOPLE = 40_000_000
    private const val REGIONAL_POWER_PEOPLE = 12_000_000
    private const val MIDDLING_POWER_PEOPLE = 3_000_000
}
