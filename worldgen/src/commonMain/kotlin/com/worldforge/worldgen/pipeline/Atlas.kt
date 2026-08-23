package com.worldforge.worldgen.pipeline

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

    fun government(random: Random, coastalShare: Float, cellCount: Int, neighbours: Int): String {
        val options = buildList {
            add("Kingdom")
            add("Duchy")
            if (coastalShare > 0.12f) { add("Merchant Republic"); add("Maritime League") }
            if (neighbours >= 3) { add("Confederacy"); add("Marcher Lordship") }
            if (cellCount > 6000) { add("Empire"); add("High Kingdom") }
            if (cellCount < 1500) { add("Free City"); add("Principality") }
            add("Theocracy")
            add("Elective Monarchy")
            add("Council of Elders")
        }
        return options.random(random)
    }

    /** What the land produces in surplus. */
    fun exports(
        random: Random,
        biomes: List<Pair<Biome, Float>>,
        coastalShare: Float,
        riverShare: Float,
        mountainShare: Float,
        nearbyResources: List<String>
    ): List<String> {
        val goods = LinkedHashSet<String>()
        nearbyResources.take(2).forEach { goods.add(it.lowercase()) }

        biomes.take(3).forEach { (biome, share) ->
            if (share < 0.08f) return@forEach
            goods.addAll(
                when (biome) {
                    Biome.GRASSLAND -> listOf("grain", "horses", "wool")
                    Biome.TEMPERATE_FOREST, Biome.TEMPERATE_RAINFOREST -> listOf("timber", "furs", "pitch")
                    Biome.TAIGA -> listOf("timber", "furs", "amber")
                    Biome.TROPICAL_RAINFOREST -> listOf("spices", "hardwood", "dyestuffs")
                    Biome.TROPICAL_SEASONAL_FOREST, Biome.SAVANNA -> listOf("cotton", "ivory", "cattle")
                    Biome.SHRUBLAND -> listOf("olives", "wine", "goats")
                    Biome.DESERT -> listOf("salt", "glass", "incense")
                    Biome.TUNDRA, Biome.ICE_SHEET -> listOf("furs", "whale oil", "walrus ivory")
                    Biome.ALPINE -> listOf("stone", "slate")
                    else -> emptyList()
                }.shuffled(random).take(2)
            )
        }

        if (coastalShare > 0.1f) goods.addAll(listOf("salt fish", "sea salt", "pearls").shuffled(random).take(1))
        if (mountainShare > 0.15f) goods.addAll(listOf("iron", "silver", "cut stone").shuffled(random).take(1))
        if (riverShare > 0.12f) goods.add("river trade")

        return goods.take(4).toList()
    }

    /** Staples the realm cannot supply itself, so has to buy in. */
    fun imports(
        biomes: List<Pair<Biome, Float>>,
        coastalShare: Float,
        mountainShare: Float,
        exports: List<String>
    ): List<String> {
        val shares = biomes.toMap()
        fun share(vararg wanted: Biome) = wanted.sumOf { (shares[it] ?: 0f).toDouble() }.toFloat()

        val needs = LinkedHashSet<String>()
        if (share(Biome.TEMPERATE_FOREST, Biome.TAIGA, Biome.TROPICAL_RAINFOREST,
                Biome.TEMPERATE_RAINFOREST) < 0.1f
        ) needs.add("timber")
        if (share(Biome.GRASSLAND, Biome.SAVANNA, Biome.TROPICAL_SEASONAL_FOREST) < 0.12f) {
            needs.add("grain")
        }
        if (mountainShare < 0.06f) needs.add("iron")
        if (coastalShare < 0.04f) needs.add("salt")
        if (share(Biome.TROPICAL_RAINFOREST, Biome.TROPICAL_SEASONAL_FOREST) < 0.05f) {
            needs.add("spices")
        }
        if (share(Biome.SHRUBLAND) < 0.05f) needs.add("wine")

        // Never import what you already sell.
        return needs.filterNot { need -> exports.any { it.contains(need, ignoreCase = true) } }
            .take(4)
    }

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
            population > 40_000_000 -> "Its census is the envy and the terror of the region."
            population > 12_000_000 -> "It is populous enough to matter in any quarrel nearby."
            population > 3_000_000 -> "It is a middling power, and knows it."
            else -> "It is thinly peopled, and survives by being more trouble to take than it is worth."
        }

        return "$opening $posture $scale"
    }
}
