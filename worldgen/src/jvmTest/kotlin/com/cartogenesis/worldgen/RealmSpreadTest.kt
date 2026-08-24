package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.NationResult
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Whether realms actually reach the whole world, and whether any one of them reaches too much of it.
 *
 * Both halves of this caught a real bug. Realms are built out of drainage catchments, and catchments
 * only ever border their neighbours on the same landmass — so the first version could not cross
 * water at all, and rendered an entire southern continent blank. Letting them cross for free then
 * produced the opposite: one realm island-hopped an archipelago and held most of the world.
 */
class RealmSpreadTest {

    @Test
    fun `every landmass is settled, and no realm swallows the world`() {
        listOf(42L, 7L, 1234L).forEach { seed ->
            val world = WorldGenerationEngine.generateBlocking(
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
            // And no realm should be a world empire. The runaway version of this hit 60%.
            assertTrue(
                largest <= land * 0.40,
                "seed $seed: one realm holds ${largest * 100 / land}% of all land"
            )
        }
    }

    @Test
    fun `realms are not riddled with enclaves`() {
        listOf(42L, 7L, 1234L).forEach { seed ->
            val world = WorldGenerationEngine.generateBlocking(
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

    @Test
    fun `realms differ in size`() {
        val world = WorldGenerationEngine.generateBlocking(
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
}
