package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.CultureResult
import com.cartogenesis.worldgen.pipeline.NationResult
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Whether the peoples layer says anything the political map does not.
 *
 * This is the one thing the layer has to get right. A culture map that lined up with the borders
 * would be decoration — it would tell you nothing you could not read off the political view, and
 * the whole reason to draw peoples separately is that they do not match the states drawn over them.
 * So the test is not "do cultures exist" but "do they disagree": a realm should usually hold more
 * than one people, and a people should usually span more than one realm.
 *
 * Measured as area-weighted shares rather than raw counts, because a realm clipping the corner of a
 * neighbouring culture by nine cells is not meaningfully two-cultured, and counting it as such would
 * let the layer pass while looking like a copy of the borders.
 */
class CultureRealmTest : BorrowsSharedWorlds() {

    @Test
    fun `cultures and realms disagree`() {
        val pooled = ArrayList<Double>()
        listOf(42L, 7L, 1234L).forEach { seed ->
            val world = SharedWorlds.world(
                WorldGenConfig(seed = seed, width = 512, height = 512)
            )
            val realmOf = world.nations.nationId
            val cultureOf = world.cultures.cultureId

            // Land shared by each (realm, culture) pair.
            val overlap = HashMap<Long, Int>()
            val realmArea = HashMap<Int, Int>()
            val cultureArea = HashMap<Int, Int>()
            for (i in realmOf.indices) {
                if (!world.sea.isLand[i]) continue
                val realm = realmOf[i]
                val culture = cultureOf[i]
                if (realm == NationResult.UNCLAIMED || culture == CultureResult.UNSETTLED) continue
                realmArea[realm] = (realmArea[realm] ?: 0) + 1
                cultureArea[culture] = (cultureArea[culture] ?: 0) + 1
                val key = realm.toLong() * 100_000L + culture
                overlap[key] = (overlap[key] ?: 0) + 1
            }
            if (realmArea.isEmpty() || cultureArea.isEmpty()) return@forEach

            // A minority counts as present only once it holds a real share of the territory.
            val share = 0.10f
            val culturesPerRealm = realmArea.map { (realm, area) ->
                overlap.count { (key, shared) ->
                    key / 100_000L == realm.toLong() && shared >= area * share
                }
            }
            val realmsPerCulture = cultureArea.map { (culture, area) ->
                overlap.count { (key, shared) ->
                    key % 100_000L == culture.toLong() && shared >= area * share
                }
            }

            val meanCultures = culturesPerRealm.average()
            val meanRealms = realmsPerCulture.average()
            println(
                "CULTURE seed %d: %d peoples over %d realms; %.2f peoples per realm, %.2f realms per people".format(
                    seed, cultureArea.size, realmArea.size, meanCultures, meanRealms
                )
            )

            // Where the two layers draw their lines. A culture boundary that is not also a realm
            // boundary is a cultural frontier running *through* a country, which is the thing this
            // layer exists to show and the thing a copy of the political map could not produce.
            var cultureEdge = 0
            var cultureEdgeInsideARealm = 0
            val w = world.width
            val h = world.height
            for (y in 1 until h - 1) {
                for (x in 0 until w) {
                    val i = y * w + x
                    if (!world.sea.isLand[i] || cultureOf[i] == CultureResult.UNSETTLED) continue
                    var culturalFrontier = false
                    var politicalFrontier = false
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val n = (y + dy) * w + ((x + dx + w) % w)
                            if (!world.sea.isLand[n]) continue
                            if (cultureOf[n] != CultureResult.UNSETTLED &&
                                cultureOf[n] != cultureOf[i]
                            ) culturalFrontier = true
                            if (realmOf[n] != realmOf[i]) politicalFrontier = true
                        }
                    }
                    if (culturalFrontier) {
                        cultureEdge++
                        if (!politicalFrontier) cultureEdgeInsideARealm++
                    }
                }
            }
            val throughCountries =
                if (cultureEdge == 0) 0.0 else cultureEdgeInsideARealm.toDouble() / cultureEdge

            println(
                "CULTURE seed %d: %.0f%% of cultural frontier runs inside a country".format(
                    seed, throughCountries * 100
                )
            )

            // A people spanning several states is the strong, reliable signal: cultures are the
            // larger unit, so this sits comfortably above 1 on every seed. Its mirror - peoples per
            // realm - is *not* reliable and is only reported above: with cultures roughly twice the
            // size of realms, most realms sit inside one culture by simple geometry, and on seed 7
            // it lands at 1.15. Asserting on it would be tuning a threshold to whatever the last
            // run produced.
            // The qualitative claim, per seed: a people spans more than one state. Above one, and
            // nothing tighter, because the strong form of the claim is pooled over the three seeds
            // below rather than asserted on whichever of them the loop reached first.
            //
            // It used to read `> 1.3` here, which stopped at seed 42 and so was a bar set by one
            // world, and a realm map is built from drainage catchments, so anything that moves the
            // river network moves it. Same shape as the desert guard: the claim per seed, the
            // strength pooled, every figure printed.
            assertTrue(
                meanRealms > 1.0,
                "seed $seed: peoples do not cross borders at all, $meanRealms realms per people"
            )
            pooled.add(meanRealms)
            // If cultures merely reproduced the borders this would be 0: every cultural frontier
            // would also be a political one.
            assertTrue(
                throughCountries > 0.25,
                "seed $seed: only ${(throughCountries * 100).toInt()}% of cultural frontier runs " +
                    "inside a country, so the peoples layer is close to a copy of the political map"
            )
        }
        // And the strong form, pooled. The bar is 1.4, set under the worst of the three seeds when
        // it was written (their figures are printed below) and well over the 1.0 that would mean
        // the two layers agree: a pin, not a derivation. Pooling is what makes it a statement about
        // the generator rather than about seed 42: one world's realm count is a property of where
        // its catchments happened to fall, and the claim is that peoples cross borders in general.
        val mean = pooled.average()
        println(
            "CULTURE pooled over ${pooled.size} seeds: %.2f realms per people (%s)"
                .format(mean, pooled.joinToString { "%.2f".format(it) })
        )
        assertTrue(
            pooled.size == 3 && mean >= 1.4,
            "pooled over ${pooled.size} seeds a people spans only $mean realms, so the peoples " +
                "layer is close to a copy of the political map"
        )
    }

    @Test
    fun `peoples cover the habitable world without one swallowing it`() {
        listOf(42L, 7L, 1234L).forEach { seed ->
            val world = SharedWorlds.world(
                WorldGenConfig(seed = seed, width = 512, height = 512)
            )
            val land = world.sea.isLand.count { it }
            // Measured against land people could actually live on, not all land. Seed 42 is a third
            // ice sheet, and against the raw total a perfectly good culture map looks like it has
            // abandoned a third of the world.
            val habitable = world.sea.isLand.indices.count {
                world.sea.isLand[it] && world.climate.biome[it] != Biome.ICE_SHEET
            }
            val areas = HashMap<Int, Int>()
            for (i in world.cultures.cultureId.indices) {
                val c = world.cultures.cultureId[i]
                if (c == CultureResult.UNSETTLED || !world.sea.isLand[i]) continue
                areas[c] = (areas[c] ?: 0) + 1
            }
            val settled = areas.values.sum()
            val largest = areas.values.maxOrNull() ?: 0
            println(
                ("CULTURE seed %d: %d peoples, %.0f%% of habitable land settled " +
                    "(%.0f%% of all land, which is %.0f%% ice), largest holds %.0f%%").format(
                    seed, areas.size, settled * 100.0 / habitable, settled * 100.0 / land,
                    (land - habitable) * 100.0 / land, largest * 100.0 / habitable
                )
            )

            // Ice caps are meant to be empty; everywhere else should belong to somebody.
            assertTrue(
                settled >= habitable * 0.90,
                "seed $seed left ${100 - settled * 100 / habitable}% of habitable land unsettled"
            )
            // Under 45% of the habitable land, which is no figure of Earth's and no setting of the
            // stage's: a pin against a runaway people, with nothing derived behind
            // it (Audit III's E-T10). Several settings elsewhere cite their margin against this
            // bar; it is not a derivation they can lean on.
            assertTrue(
                largest <= habitable * 0.45,
                "seed $seed: one people holds ${largest * 100 / habitable}% of habitable land"
            )
            assertTrue(areas.size >= 4, "seed $seed produced only ${areas.size} peoples")
        }
    }
}
