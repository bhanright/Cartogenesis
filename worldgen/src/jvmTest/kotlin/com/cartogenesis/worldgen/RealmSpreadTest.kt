package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.NationResult
import com.cartogenesis.worldgen.pipeline.NationStage
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Whether realms actually reach the whole world, and whether any one of them reaches too much of it.
 *
 * Both halves of this caught a real bug. Realms are built out of drainage catchments, and catchments
 * only ever border their neighbours on the same landmass — so the first version could not cross
 * water at all, and rendered an entire southern continent blank. Letting them cross for free then
 * produced the opposite: one realm island-hopped an archipelago and held most of the world.
 */
class RealmSpreadTest : BorrowsSharedWorlds() {

    @Test
    fun `every landmass is settled, and no realm swallows the world`() {
        val largestShares = ArrayList<Pair<Long, Double>>()
        listOf(42L, 7L, 1234L).forEach { seed ->
            val world = SharedWorlds.world(
                WorldGenConfig(seed = seed, width = 512, height = 512)
            )
            val land = world.sea.isLand.count { it }
            val realmArea = HashMap<Int, Int>()
            for (i in world.nations.nationId.indices) {
                val r = world.nations.nationId[i]
                if (r == NationResult.UNCLAIMED || !world.sea.isLand[i]) continue
                realmArea[r] = (realmArea[r] ?: 0) + 1
            }
            val claimed = realmArea.values.sum()
            val largest = realmArea.values.maxOrNull() ?: 0

            println(
                "SPREAD seed %d: %d realms, %.0f%% of land claimed, largest holds %.0f%%".format(
                    seed, realmArea.size, claimed * 100.0 / land, largest * 100.0 / land
                )
            )

            // The default mode is to settle everything. Before realms could cross a strait this
            // sat around 87% on seed 42, with a whole continent unclaimed.
            assertTrue(
                claimed >= land * 0.999,
                "seed $seed left ${land - claimed} of $land land cells unclaimed"
            )
            // And no realm should be a world empire. The runaway version of this hit 60%; the bar
            // is the stage's own cap, `NationsConfig.maxRealmShare`, since a realm over it is split
            // until it is not. It was 40% until Audit III (its E-T10), which could not see the cap
            // failing anywhere between the two: seed 7's largest realm stood at 33.6%, because the
            // cap was enforced on the catchments and the enclave pass then gave the realm pieces
            // of its neighbours. Armed at chunk 6, which made that pass respect the cap.
            largestShares += seed to largest.toDouble() / land
        }
        val cap = WorldGenConfig().nations.maxRealmShare.toDouble()
        val over = largestShares.filter { it.second > cap }
        assertTrue(
            over.isEmpty(),
            "one realm holds more of the land than the stage's own cap of %.0f%%: %s".format(
                Locale.ROOT, cap * 100,
                over.joinToString { (seed, share) -> String.format(Locale.ROOT, "seed %d %.1f%%", seed, share * 100) }
            )
        )
    }

    @Test
    fun `realms are not riddled with enclaves`() {
        listOf(42L, 7L, 1234L).forEach { seed ->
            val world = SharedWorlds.world(
                WorldGenConfig(seed = seed, width = 512, height = 512)
            )
            val w = world.width
            val h = world.height
            val id = world.nations.nationId

            // Every connected piece of every realm, walking land only.
            class Piece(val realm: Int, val size: Int, val touchesOtherRealmByLand: Boolean)
            val pieces = ArrayList<Piece>()
            val seen = BooleanArray(w * h)
            for (start in 0 until w * h) {
                if (seen[start] || !world.sea.isLand[start]) continue
                val realm = id[start]
                if (realm == -1) continue
                var size = 0
                var touches = false
                val stack = ArrayDeque<Int>()
                stack.addLast(start)
                seen[start] = true
                while (stack.isNotEmpty()) {
                    val c = stack.removeLast()
                    size++
                    val x = c % w
                    val y = c / w
                    for (dy in -1..1) {
                        val ny = y + dy
                        if (ny !in 0 until h) continue
                        for (dx in -1..1) {
                            val n = ny * w + ((x + dx + w) % w)
                            if (!world.sea.isLand[n]) continue
                            when {
                                id[n] == realm && !seen[n] -> { seen[n] = true; stack.addLast(n) }
                                id[n] != realm && id[n] != -1 -> touches = true
                            }
                        }
                    }
                }
                pieces.add(Piece(realm, size, touches))
            }

            // A realm's largest piece is its mainland, whichever order it was found in. Every other
            // piece you can walk out of into a neighbour is an enclave; pieces you cannot walk out
            // of are islands, which are a real thing and are left alone.
            val mainland = pieces.groupBy { it.realm }.mapValues { (_, ps) -> ps.maxOf { it.size } }
            val enclaves = pieces.filter {
                it.touchesOtherRealmByLand && it.size < mainland.getValue(it.realm) && it.size >= 12
            }

            println(
                "ENCLAVES seed %d: %d realms, %d pieces, %d inland enclaves (largest %d cells)".format(
                    seed, mainland.size, pieces.size, enclaves.size,
                    enclaves.maxOfOrNull { it.size } ?: 0
                )
            )
            // A handful of enclaves is true to life. A map speckled with them reads as noise.
            assertTrue(
                enclaves.size <= 3,
                "seed $seed has ${enclaves.size} realm fragments stranded inside other realms"
            )
        }
    }

    /**
     * The cap and the enclave rule together, on a hand-made map: realm H holds twenty columns of a
     * forty-row strip less a six-by-six piece of realm R inside it, whose mainland lies further
     * east. H taking the piece would pass the cap, and no other realm touches it.
     *
     * Before chunk 6 the enclave pass gave the piece to H regardless and H went over the cap; once
     * the pass respected the cap it left the piece where it was, an exclave of a realm it no longer
     * touches. The piece is now a realm of its own, with its capital on it, and no piece is
     * stranded and no realm over the cap.
     */
    @Test
    fun `a piece no neighbour can take within the cap becomes a realm of its own`() {
        val config = HandMadeWorlds.config()
        fun land(x: Int, y: Int) = y in 10..49
        val sea = HandMadeWorlds.sea(config, ::land) { x, y -> if (land(x, y)) 0.1f else -0.1f }
        fun inPiece(x: Int, y: Int) = x in 5..10 && y in 20..25
        val realmColumns = listOf(0..19, 20..35, 36..49, 50..63)
        val nationId = IntArray(config.width * config.height) { cell ->
            val x = cell % config.width
            val y = cell / config.width
            when {
                !land(x, y) -> NationResult.UNCLAIMED
                inPiece(x, y) -> 1
                else -> realmColumns.indexOfFirst { x in it }
            }
        }
        val origins = realmColumns.map { HandMadeWorlds.cellAt(config, (it.first + it.last) / 2 + if (it.first == 0) 6 else 0, 40) }.toMutableList()
        val habitability = FloatField(config.width, config.height)
        for (cell in habitability.data.indices) if (sea.isLand[cell]) habitability.data[cell] = 0.5f
        val capCells = config.nations.maxRealmShare * sea.landCellCount
        assertTrue(20 * 40 > capCells && 20 * 40 - 36 <= capCells, "the fixture's realm H is not the case: the cap is $capCells cells")

        runBlocking { NationStage.dissolveEnclaves(config, sea, habitability, nationId, origins) }

        val pieces = RealmPieces(config.width, config.height, sea.isLand, nationId, NationResult.UNCLAIMED)
        val held = nationId.filter { it != NationResult.UNCLAIMED }.groupingBy { it }.eachCount()
        val pieceRealm = nationId[HandMadeWorlds.cellAt(config, 7, 22)]
        println("PIECE WITHIN THE CAP realms hold $held; the piece is realm $pieceRealm; ${pieces.describe()}")
        assertEquals(0, pieces.stranded.size, "a piece was left stranded")
        assertTrue(held.values.all { it <= capCells }, "a realm went over the cap of $capCells cells: $held")
        assertEquals(realmColumns.size, pieceRealm, "the piece did not become a realm of its own")
        assertTrue(inPiece(origins[pieceRealm] % config.width, origins[pieceRealm] / config.width), "the new realm's capital is not on it")
    }

    /**
     * No realm leaves a piece stranded in its neighbours, and no more realms sit landlocked inside
     * a single neighbour than did before chunk 6's cap. Counted with [RealmPieces] on the standard
     * seeds at 512: origin/main had no stranded piece and three realms landlocked inside one
     * neighbour (one each on seeds 7, 42 and 99). Realms inside one neighbour with a coast are
     * printed and not asserted: a country on a stretch of shore with one land neighbour is
     * Portugal, and on origin/main there were 15 of them over these four seeds.
     */
    @Test
    fun `no piece is stranded and no more realms are landlocked inside one neighbour`() {
        var stranded = 0
        var landlocked = 0
        for (seed in listOf(7L, 42L, 1234L, 99L)) {
            val world = SharedWorlds.world(WorldGenConfig(seed = seed, width = 512, height = 512))
            val pieces = RealmPieces(world.width, world.height, world.sea.isLand, world.nations.nationId, NationResult.UNCLAIMED)
            println("PIECES seed $seed: ${pieces.describe()}")
            stranded += pieces.stranded.size
            landlocked += pieces.enclosedRealms.count { !it.touchesTheSea }
        }
        assertEquals(0, stranded, "pieces stranded in other realms over the four standard seeds")
        assertTrue(landlocked <= ORIGIN_LANDLOCKED_INSIDE_ONE, "$landlocked realms landlocked inside one neighbour, against origin/main's $ORIGIN_LANDLOCKED_INSIDE_ONE")
    }

    @Test
    fun `realms differ in size`() {
        val world = SharedWorlds.world(
            WorldGenConfig(seed = 42L, width = 512, height = 512)
        )
        val sizes = world.nations.nations.map { it.cellCount }.sortedDescending()
        assertTrue(sizes.size >= 6, "only ${sizes.size} realms")
        // A world of equal slabs looks designed. Largest against median is the plainest way to say
        // "these are not all the same country in different colours".
        val median = sizes[sizes.size / 2].toDouble()
        val spread = sizes.first() / median.coerceAtLeast(1.0)
        println("SIZES $sizes (largest/median %.1f)".format(spread))
        assertTrue(spread >= 3.0, "realm sizes are too uniform: largest/median $spread")
    }

    private companion object {
        /** origin/main's count over seeds 7, 42, 1234 and 99 at 512, taken at chunk 6. */
        const val ORIGIN_LANDLOCKED_INSIDE_ONE = 3
    }
}
