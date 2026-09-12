package com.cartogenesis.worldgen.naming

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
        // Slice sizes, not counts of anything real: wide enough that two cultures rarely draw the
        // same inventory, narrow enough that each one still sounds like a single language rather
        // than like the whole pool.
        onsets = ONSET_POOL.pick(random, min = 7, max = 11)
        nuclei = NUCLEUS_POOL.pick(random, min = 4, max = 6)
        codas = CODA_POOL.pick(random, min = 5, max = 8)
        realmSuffixes = REALM_SUFFIXES.pick(random, min = 3, max = 5)
        settlementSuffixes = SETTLEMENT_SUFFIXES.pick(random, min = 3, max = 5)
        doublesVowels = random.nextFloat() < DOUBLED_VOWEL_CULTURE_CHANCE
        likesApostrophe = random.nextFloat() < APOSTROPHE_CULTURE_CHANCE
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

        repeat(syllables.coerceIn(1, MAX_SYLLABLES)) { index ->
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
            if (doublesVowels && vowel.length == 1 && index == 0 &&
                random.nextFloat() < DOUBLED_VOWEL_CHANCE
            ) {
                vowel += vowel
            }
            builder.append(vowel)

            // A coda on every syllable makes a word a mouthful, so only sometimes — and more often
            // at the end, where it reads as a proper ending.
            val wantsCoda = random.nextFloat() <
                (if (lastSyllable) FINAL_CODA_CHANCE else MEDIAL_CODA_CHANCE)
            if (wantsCoda) {
                val coda = if (lastSyllable) codas.random(random)
                else codas.filter { it.length == 1 }.randomOrNull(random) ?: codas.random(random)
                builder.append(coda)
                previousEndedInConsonant = true
            } else {
                previousEndedInConsonant = false
            }

            if (likesApostrophe && !lastSyllable && !previousEndedInConsonant &&
                random.nextFloat() < APOSTROPHE_CHANCE
            ) {
                builder.append('\'')
            }
        }
        return builder.toString().replaceFirstChar { it.uppercase() }
    }

    /** A short, clean stem for constructions that supply their own descriptor. */
    fun stem(random: Random): String = word(random, random.nextInt(1, MAX_SYLLABLES))

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
        return when (random.nextInt(REALM_NAME_FORMS)) {
            0 -> "The ${REALM_TITLES.random(random)} of ${word(random, random.nextInt(2, 4))}"
            1 -> word(random, MAX_SYLLABLES)
            else ->
                "${word(random, random.nextInt(1, MAX_SYLLABLES))}${realmSuffixes.random(random)}"
        }
    }

    private fun settlementName(random: Random): String {
        val stem = word(random, random.nextInt(1, MAX_SYLLABLES))
        return if (random.nextFloat() < SUFFIXED_SETTLEMENT_CHANCE) {
            "$stem${settlementSuffixes.random(random)}"
        } else {
            stem
        }
    }

    /** "<word> <descriptor>" or "<descriptor> of <word>", e.g. "the Kelmar Reach". */
    private fun feature(random: Random, descriptors: List<String>): String {
        val stem = stem(random)
        val descriptor = descriptors.random(random)
        return if (random.nextFloat() < DESCRIPTOR_FIRST_CHANCE) {
            "$descriptor of $stem"
        } else {
            "$stem $descriptor"
        }
    }

    private companion object {

        /** Longest word this generator builds, in syllables. Past three a name stops scanning. */
        const val MAX_SYLLABLES = 3

        /** How many cultures double a vowel at all, and how many use an apostrophe at all. */
        const val DOUBLED_VOWEL_CULTURE_CHANCE = 0.25f
        const val APOSTROPHE_CULTURE_CHANCE = 0.15f

        /** And how often a culture that does either actually does it, per opportunity. */
        const val DOUBLED_VOWEL_CHANCE = 0.35f
        const val APOSTROPHE_CHANCE = 0.18f

        /**
         * How often a syllable takes a coda. Far more often at the end of a word, where a
         * consonant reads as a proper ending; a coda on every syllable makes a word a mouthful.
         */
        const val FINAL_CODA_CHANCE = 0.7f
        const val MEDIAL_CODA_CHANCE = 0.3f

        /**
         * Shapes a realm name can take: a title ("The Duchy of ..."), a bare word, or a suffixed
         * stem — the last being the common case, and so taking the three remaining draws.
         */
        const val REALM_NAME_FORMS = 5

        /** How often a settlement takes a suffix rather than standing as a bare stem. */
        const val SUFFIXED_SETTLEMENT_CHANCE = 0.55f

        /** How often a feature is "Sea of Kelmar" rather than "Kelmar Sea". */
        const val DESCRIPTOR_FIRST_CHANCE = 0.35f

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
        styleFor(cultureSeed).stem(streamFor(cultureSeed, salt))

    /** Names one thing. [salt] separates the names a single culture generates from each other. */
    fun name(cultureSeed: Long, kind: NameKind, salt: Long): String =
        styleFor(cultureSeed).name(streamFor(cultureSeed, salt), kind)

    /**
     * The draw one name comes out of: the culture's own seed, offset by the salt so that two
     * things named by the same culture do not come out identical.
     *
     * The salt is multiplied by Knuth's 32-bit golden-ratio constant, because every caller passes
     * an index and consecutive indices would otherwise land in a run of neighbouring seeds.
     */
    private fun streamFor(cultureSeed: Long, salt: Long): Random =
        Random(cultureSeed * CULTURE_STRIDE + salt * GOLDEN_RATIO_32)

    private const val CULTURE_STRIDE = 31L
    private const val GOLDEN_RATIO_32 = 2_654_435_761L
}
