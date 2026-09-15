package com.cartogenesis.ui

/**
 * The names a world can be given.
 *
 * A world's name used to be a bare word in its largest people's language, assembled syllable by
 * syllable the way realm and settlement names still are. Those words were pronounceable by
 * construction and rarely good: the assembler has no ear. So the world's name is drawn from
 * this list instead, three hundred names written to read aloud and to sit in a serif on a map
 * title, mixed across three flavours (English and Norse compounds, Latin and Romance shapes,
 * softer invented ones), none of them a real place or a name from published fiction. The
 * people's language still has a say: it picks the name together with the seed, so the same
 * world named by a different people gets a different name, and the same seed and people always
 * get the same one. Realm and settlement names keep the assembler until the atlas release
 * gives them lists of their own.
 */
internal object WorldNames {

    /** Every name, alphabetical. The order is part of what a seed means, so it is never re-sorted. */
    val ALL: List<String> = listOf(
        "Alderhush", "Alenvi", "Almureza", "Ambermere", "Ambravelle", "Anovari", "Ardelune",
        "Arevona", "Arimel", "Arvenoo", "Ashelow", "Ashenmere", "Auneth", "Avelcor",
        "Avenwold", "Balemora", "Balmadow", "Barunel", "Belaviso", "Belori", "Berunai",
        "Birchollow", "Bolefen", "Boradune", "Bovarel", "Bramblebay", "Bravessa", "Brelumi",
        "Brinemoor", "Buneri", "Calderose", "Caluneth", "Candlefen", "Carovena", "Cavirel",
        "Cedarmere", "Celadune", "Cemori", "Cindervale", "Cirema", "Clavessa", "Clovemoor",
        "Colunai", "Coravelle", "Covehollow", "Dalenvi", "Damerosa", "Dapplefen", "Darumel",
        "Davenora", "Dawnmere", "Delaviso", "Delunai", "Demerune", "Dewbarrow", "Dimberel",
        "Doremiro", "Doruneth", "Dunefallow", "Duskarbor", "Ebonfen", "Elaruvi", "Eldemora",
        "Elemay", "Elovessa", "Elunari", "Emarune", "Emberhush", "Emovai", "Enadelle",
        "Enderose", "Erilun", "Evenmere", "Evenwold", "Eversedge", "Fablefen", "Falumera",
        "Faronel", "Faverosa", "Felori", "Fenbarrow", "Feradune", "Fesumai", "Firalune",
        "Firrowan", "Flamehollow", "Folavessa", "Foluneth", "Foxenmere", "Furemi", "Galenvi",
        "Gavemora", "Geladune", "Gemori", "Genavelle", "Gildemere", "Glimmerfen", "Glovari",
        "Glowbarrow", "Gomelai", "Goraviso", "Gorsehollow", "Grevessa", "Gullow", "Guluneth",
        "Halenoo", "Halverosa", "Harumel", "Havodune", "Hazelmere", "Hedarune", "Helovai",
        "Hemerelle", "Herimay", "Hesavora", "Hethun", "Hollowglen", "Honeybarrow",
        "Hushenmoor", "Hushwillow", "Ibravelle", "Icemeadow", "Iderosa", "Idunel", "Ilarven",
        "Ilaviso", "Ilemay", "Imerdune", "Inderune", "Inovai", "Irilune", "Ironfallow",
        "Ivereth", "Iverfen", "Ivybarrow", "Kalenvi", "Kamberosa", "Karumel", "Kaverune",
        "Keldavelle", "Kelmere", "Kelovai", "Kelvenmoor", "Kemerune", "Kesavora", "Kevuneth",
        "Kinebarrow", "Kiremi", "Kitehollow", "Kovemoor", "Lakerowan", "Lamberosa", "Lamiwen",
        "Lanemoor", "Larevune", "Larunai", "Laverose", "Leafbarrow", "Lemori", "Lenaviso",
        "Levarelle", "Limeneth", "Lindenhush", "Lomay", "Lowenfen", "Maderune", "Malenvi",
        "Mallowfen", "Maraviso", "Marelune", "Mavuneth", "Meladore", "Menovai", "Meravelle",
        "Miremallow", "Moleri", "Moonbarrow", "Moravessa", "Morunai", "Mosselwold",
        "Nacrevale", "Nalemay", "Namberosa", "Narovelle", "Navirel", "Neladune", "Nemorai",
        "Neraviso", "Nettlewold", "Nevelune", "Nimel", "Niveneth", "Nookenfen", "Noonbarrow",
        "Novemoor", "Oakenhush", "Obravelle", "Odelum", "Olarune", "Olevai", "Olmerosa",
        "Omaleth", "Onaviso", "Opalbarrow", "Oravelle", "Orelun", "Orimay", "Osenfen",
        "Ottermere", "Owlmeadow", "Palemoor", "Pamberosa", "Pavenel", "Peladune", "Pemori",
        "Penavelle", "Peraviso", "Petalwold", "Pevunai", "Pinemallow", "Plumbarrow",
        "Polavessa", "Poluneth", "Porimel", "Pyrehollow", "Ralemay", "Ravelune", "Reedbarrow",
        "Relaviso", "Remori", "Renavelle", "Rimewillow", "Rivemere", "Rolunai", "Romadune",
        "Rosefallow", "Rovessa", "Rovuneth", "Rowanfen", "Ruleni", "Sablehush", "Salemvi",
        "Samerosa", "Sarunel", "Sedgewillow", "Seladune", "Semovai", "Senavelle", "Serimay",
        "Sivorune", "Snowbarrow", "Solavessa", "Soruneth", "Summerfen", "Sunmallow", "Talenvi",
        "Tamberosa", "Tarumel", "Tavirelle", "Teladune", "Temovai", "Tenaviso", "Thawbarrow",
        "Thistlefen", "Tidemallow", "Tinderhush", "Tiruneth", "Tolavessa", "Torimay",
        "Tumblemoor", "Valuneth", "Vamberosa", "Vanewold", "Varimel", "Vavirelle", "Veladune",
        "Velunai", "Venaviso", "Veremay", "Vesperfen", "Veyavora", "Vinemallow", "Virelune",
        "Volebarrow", "Vowenmere", "Waderfen", "Walemay", "Wamberosa", "Warunel", "Wavirelle",
        "Waxenmoor", "Weladune", "Wemovai", "Wenaviso", "Wickerhush", "Willowmere", "Wimelun",
        "Wolavessa", "Wrenmallow", "Wuneri",
    )

    /**
     * The name for [seed] in the language [languageSeed]. Both numbers are folded into one index
     * with two large odd multipliers and a shift, so neighbouring seeds and neighbouring languages
     * do not land on the same name.
     */
    fun pick(seed: Long, languageSeed: Long): String {
        val mixed = seed * SEED_MULTIPLIER + languageSeed * LANGUAGE_MULTIPLIER
        val folded = mixed xor (mixed ushr 29)
        val index = ((folded % ALL.size) + ALL.size) % ALL.size
        return ALL[index.toInt()]
    }

    /** Knuth's multiplier from the 64-bit linear congruential generator. */
    private const val SEED_MULTIPLIER = 6_364_136_223_846_793_005L

    /** Its companion increment, used here as a second multiplier for the language. */
    private const val LANGUAGE_MULTIPLIER = 1_442_695_040_888_963_407L
}
