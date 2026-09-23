package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.CultureStage
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule

/**
 * Whether a hearth's landmass is decided fairly, not just its climate.
 *
 * `CultureRealmTest`'s "swallowing" guard measures the *outcome* — no people holds more than 45%
 * of habitable land — but that number also moves every time an unrelated chunk nudges a
 * coastline, so a green run there does not by itself say the placement is fair. This test checks
 * the mechanism directly: hearths are shared out between landmasses in proportion to each one's
 * *habitable* area, exactly as `BasinRealms.chooseSeeds` shares out realm capitals. Scored
 * globally without that weighting, a landmass holding the bulk of the world's habitable land can
 * still end up with fewer hearths than its share earns, because a handful of hearths land on
 * scraps of nearly-empty landmass elsewhere instead — on seed 7, one hearth used to go to a
 * landmass with under 0.1% of the world's habitable land while the landmass holding 83% of it
 * went a full hearth short of its own floor entitlement.
 *
 * Guaranteed by construction once hearths are drawn with a largest-remainder allocation per
 * landmass: every landmass gets at least `floor(itsHabitableShare * cultureCount)` hearths. That
 * is the property checked here, rather than "at least one hearth each", which this project's own
 * seed 7 already satisfied before the fix — the bug was never that a large landmass went hearth-
 * less, it was that it went under-provisioned relative to its size.
 */
class CultureHearthLandmassTest {

    @get:Rule
    val sharedWorlds = SharedWorlds.Check()

    @Test
    fun `hearths are shared out between landmasses in proportion to habitable land`() {
        listOf(42L, 7L, 1234L).forEach { seed ->
            val world = SharedWorlds.world(
                WorldGenConfig(seed = seed, width = 512, height = 512)
            )
            val placement = CultureStage.placeHearths(world.config, world.sea, world.climate, world.rivers)
            checkNotNull(placement) { "seed $seed: no hearths were placed at all" }
            val (units, profile, hearths) = placement

            val habitableArea = IntArray(units.landmassCount)
            for (u in 0 until units.unitCount) {
                if (profile.habitable[u] && units.area[u] > 0) habitableArea[units.landmass[u]] += units.area[u]
            }
            val totalHabitable = habitableArea.sum()
            if (totalHabitable == 0) return@forEach

            val hearthsPerLandmass = IntArray(units.landmassCount)
            hearths.forEach { hearthsPerLandmass[units.landmass[it]]++ }

            val wanted = world.config.cultures.cultureCount
            for (mass in 0 until units.landmassCount) {
                if (habitableArea[mass] == 0) continue
                val share = habitableArea[mass].toDouble() / totalHabitable
                val entitlement = kotlin.math.floor(share * wanted).toInt()
                if (entitlement == 0) continue
                println(
                    "HEARTH-LANDMASS seed %d: landmass %d holds %.1f%% of habitable land, floor entitlement %d, got %d hearths".format(
                        seed, mass, share * 100, entitlement, hearthsPerLandmass[mass]
                    )
                )
                assertTrue(
                    hearthsPerLandmass[mass] >= entitlement,
                    "seed $seed: landmass $mass holds ${(share * 100).toInt()}% of habitable land " +
                        "(entitled to at least $entitlement of $wanted hearths) but got only " +
                        "${hearthsPerLandmass[mass]}"
                )
            }
        }
    }
}
