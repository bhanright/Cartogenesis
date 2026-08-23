package com.worldforge.worldgen.naming

import kotlin.random.Random

/** What a name is for. Shapes which patterns and endings get used. */
enum class NameKind { REALM, SETTLEMENT, MOUNTAIN_RANGE, SEA, RIVER, ISLAND, REGION }

/**
 * A single people's way of naming things.
 *
 * Each culture draws its own sound inventory from the shared pools below, so two neighbouring
 * realms end up with recognisably different phonetics — one full of harsh stops and one of soft
 * sibilants — rather than every name on the map sounding like it came from the same place.
 */
class NameStyle(seed: Long) {

    private val onsets: List<String>
    private val nuclei: List<String>
    private val codas: List<String>
    private val realmSuffixes: List<String>
    private val settlementSuffixes: List<String>
    private val doublesVowels: Boolean
    private val likesApostrophe: Boolean

    init {
        val random = Random(seed)
        onsets = ONSET_POOL.pick(random, 7, 11)
        nuclei = NUCLEUS_POOL.pick(random, 4, 6)
        codas = CODA_POOL.pick(random, 5, 8)
        realmSuffixes = REALM_SUFFIXES.pick(random, 3, 5)
        settlementSuffixes = SETTLEMENT_SUFFIXES.pick(random, 3, 5)
        doublesVowels = random.nextFloat() < 0.25f
        likesApostrophe = random.nextFloat() < 0.15f
    }

    /**
     * A bare word in this culture's phonetics, with no title or suffix attached.
     *
     * Syllables are assembled with an eye on what the previous one ended with: a coda is dropped
     * when the next onset is itself a cluster, which is what stops names collapsing into
     * unpronounceable runs like "Drousloskslask".
     */
    fun word(random: Random, syllables: Int = random.nextInt(2, 4)): String {
        val builder = StringBuilder()
        var previousEndedInConsonant = false

        repeat(syllables.coerceIn(1, 3)) { index ->
            val lastSyllable = index == syllables - 1

            // After a consonant ending, favour a simple single-letter onset.
            val onset = if (previousEndedInConsonant) {
                onsets.filter { it.length == 1 }.randomOrNull(random) ?: onsets.random(random)
            } else {
                onsets.random(random)
            }
            builder.append(onset)

            var vowel = nuclei.random(random)
            // Only ever lengthen a plain vowel; doubling a diphthong gives "ouu" and "aeu".
            if (doublesVowels && vowel.length == 1 && index == 0 && random.nextFloat() < 0.35f) {
                vowel += vowel
            }
            builder.append(vowel)

            // A coda on every syllable makes a word a mouthful, so only sometimes — and more often
            // at the end, where it reads as a proper ending.
            val wantsCoda = random.nextFloat() < (if (lastSyllable) 0.7f else 0.3f)
            if (wantsCoda) {
                val coda = if (lastSyllable) codas.random(random)
                else codas.filter { it.length == 1 }.randomOrNull(random) ?: codas.random(random)
                builder.append(coda)
                previousEndedInConsonant = true
            } else {
                previousEndedInConsonant = false
            }

            if (likesApostrophe && !lastSyllable && !previousEndedInConsonant &&
                random.nextFloat() < 0.18f
            ) {
                builder.append('\'')
            }
        }
        return builder.toString().replaceFirstChar { it.uppercase() }
    }

    /** A short, clean stem for constructions that supply their own descriptor. */
    fun stem(random: Random): String = word(random, random.nextInt(1, 3))

    fun name(random: Random, kind: NameKind): String = when (kind) {
        NameKind.REALM -> realmName(random)
        NameKind.SETTLEMENT -> settlementName(random)
        NameKind.MOUNTAIN_RANGE -> feature(random, MOUNTAIN_WORDS)
        NameKind.SEA -> feature(random, SEA_WORDS)
        NameKind.RIVER -> feature(random, RIVER_WORDS)
        NameKind.ISLAND -> feature(random, ISLAND_WORDS)
        NameKind.REGION -> feature(random, REGION_WORDS)
    }

    private fun realmName(random: Random): String {
        // Suffixes add length of their own, so the stem stays short when one is attached.
        return when (random.nextInt(5)) {
            0 -> "The ${REALM_TITLES.random(random)} of ${word(random, random.nextInt(2, 4))}"
            1 -> word(random, 3)
            else -> "${word(random, random.nextInt(1, 3))}${realmSuffixes.random(random)}"
        }
    }

    private fun settlementName(random: Random): String {
        val stem = word(random, random.nextInt(1, 3))
        return if (random.nextFloat() < 0.55f) "$stem${settlementSuffixes.random(random)}" else stem
    }

    /** "<word> <descriptor>" or "<descriptor> of <word>", e.g. "the Kelmar Reach". */
    private fun feature(random: Random, descriptors: List<String>): String {
        val stem = stem(random)
        val descriptor = descriptors.random(random)
        return if (random.nextFloat() < 0.35f) "$descriptor of $stem" else "$stem $descriptor"
    }

    private companion object {
        val ONSET_POOL = listOf(
            "b", "br", "d", "dr", "f", "g", "gr", "h", "k", "kr", "l", "m", "n", "p", "pr",
            "r", "s", "sh", "sk", "sl", "st", "t", "th", "tr", "v", "w", "y", "z", "kh", "gl",
            "ch", "dh", "mor", "ael", "vy"
        )
        val NUCLEUS_POOL = listOf("a", "e", "i", "o", "u", "ae", "ei", "ia", "ou", "y", "au", "eo")
        val CODA_POOL = listOf(
            "n", "r", "l", "s", "th", "m", "k", "d", "g", "rn", "ld", "st", "sk", "ndr", "rk", "ss"
        )
        val REALM_SUFFIXES = listOf(
            "ia", "and", "mark", "gard", "heim", "or", "esse", "ath", "une", "ovia", "adar",
            "wyn", "arra", "oth", "ene", "stan"
        )
        val SETTLEMENT_SUFFIXES = listOf(
            "ford", "burg", "haven", "hold", "gate", "wick", "mere", "keep", "bury", "dale",
            "crest", "port", "fell", "watch"
        )
        val REALM_TITLES = listOf(
            "Kingdom", "Realm", "Dominion", "Free Cities", "Principality", "League", "Reach",
            "Confederacy", "Duchy", "Protectorate"
        )
        val MOUNTAIN_WORDS = listOf("Mountains", "Range", "Peaks", "Spine", "Teeth", "Heights", "Crags")
        val SEA_WORDS = listOf("Sea", "Gulf", "Strait", "Bay", "Deep", "Sound", "Expanse")
        val RIVER_WORDS = listOf("River", "Water", "Run", "Flow", "Course")
        val ISLAND_WORDS = listOf("Isle", "Island", "Atoll", "Rock", "Isles")
        val REGION_WORDS = listOf("Plains", "Downs", "Wold", "Waste", "Marches", "Vale", "Expanse", "Wilds")

        /** Takes a random slice of a pool — this is what gives each culture its own sound. */
        fun List<String>.pick(random: Random, min: Int, max: Int): List<String> =
            shuffled(random).take(random.nextInt(min, max + 1))
    }
}

/**
 * Names everything in a world. Deterministic for a given seed, so a saved world regenerates with
 * the names it had — and every name can be replaced by the user.
 */
object NameForge {

    fun styleFor(cultureSeed: Long): NameStyle = NameStyle(cultureSeed)

    /** A bare place-word with no descriptor, for callers that add their own wording. */
    fun stem(cultureSeed: Long, salt: Long): String =
        styleFor(cultureSeed).stem(Random(cultureSeed * 31 + salt * 2_654_435_761L))

    /** Names one thing. [salt] separates the names a single culture generates from each other. */
    fun name(cultureSeed: Long, kind: NameKind, salt: Long): String {
        val style = styleFor(cultureSeed)
        return style.name(Random(cultureSeed * 31 + salt * 2_654_435_761L), kind)
    }
}
