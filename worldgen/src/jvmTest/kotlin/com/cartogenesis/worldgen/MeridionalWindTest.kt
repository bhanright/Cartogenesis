package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ClimateStage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Whether the wind slants across the latitude lines, and what that changes about the rain.
 *
 * Three claims, in descending order of how firmly they can be pinned.
 *
 * The first is the promise the setting makes: at `meridionalWind = 0` the diagonal march is the
 * old per-row scan, arithmetic for arithmetic, and the rainfall it produces is the same down to
 * the last bit. That is checked against checksums taken from the build before the change.
 *
 * The second is the mechanism, and it is the one guard here that both discriminates sharply and
 * measures something a reader would care about: a ridge running east-west used to be invisible to
 * the rain. The march stepped along X, so the only climb it could see was a climb along X, and a
 * range lying across the wind's meridional leg — however high — wrung nothing out of the air
 * passing over it. Measured as a *paired* difference, the same cells in the same world generated
 * twice, so altitude cannot confound it: land climbing along the meridional leg gains rain when
 * the slant is switched on and land descending along it does not.
 *
 * The third is the monsoon, and it is stated here with its weakness on the record rather than
 * dressed up. See [`a tropical coast has a wet season and a dry one`].
 */
class MeridionalWindTest {

    private companion object {
        const val SIZE = 512

        /**
         * The seed the monsoon claim is stated on, found by scanning seeds 1 to 70 (see the
         * figures in that test's comment). It carries a large outer-tropical landmass whose
         * poleward side is open sea.
         */
        const val MONSOON_SEED = 26L

        /** How lopsided the year has to be to count, and how much rain the wet half must bring. */
        const val RATIO = 3f
        const val WET_SEASON = 0.25f

        /** How far out to sea a coast may look, and how far the land must run behind it. */
        const val SEA_REACH = 30
        const val LAND_BEHIND = 8

        /** The share of land the contiguous region has to cover. */
        const val MIN_SHARE = 0.02

        /**
         * Rainfall checksums of the per-row zonal scan — the march this stage used before the
         * wind was given a meridional component — with seasons on and everything else at its
         * default. Annual, warm season, cold season, in that order.
         *
         * Taken by building the tree without A3 at all and reading the numbers off it, not by
         * copying down what this code prints, which would make the guard a tautology. They were
         * re-derived once already, when continentality landed: it moves the seasonal temperature
         * the march reads, so the world is not the same world even though the march is the same
         * march. If a future chunk changes anything upstream of `buildPrecipitation` these have to
         * be re-derived the same way — from a build without the slant — rather than updated to
         * whatever this test prints.
         */
        val ZONAL_MARCH = mapOf(
            7L to Triple(-504786442719540233L, -8391930670586529367L, -6913976028233525423L),
            42L to Triple(-7658335890232693450L, -5893222121490264951L, -7606470182263391022L),
            1234L to Triple(-4852718701338356609L, -8615306492837767303L, 5875874845689984946L)
        )
    }

    @Test
    fun `a wind with no slant reproduces the old zonal march exactly`() {
        ZONAL_MARCH.keys.sorted().forEach { seed ->
            val base = WorldGenConfig(seed = seed, width = 256, height = 256)
            val world = WorldGenerationEngine.generateBlocking(
                base.copy(climate = base.climate.copy(meridionalWind = 0f))
            )
            val actual = Triple(
                checksum(world.climate.precipitation),
                checksum(world.climate.summerPrecipitation),
                checksum(world.climate.winterPrecipitation)
            )
            println("MERIDIONAL seed $seed zonal march $actual")
            assertEquals(
                ZONAL_MARCH.getValue(seed), actual,
                "seed $seed no longer reproduces the pre-slant zonal march"
            )
        }
    }

    @Test
    fun `the belts slant toward the thermal equator and away from it, in both hemispheres`() {
        val world = WorldGenerationEngine.generateBlocking(
            WorldGenConfig(seed = 42L, width = 128, height = 128)
        )
        val w = world.width
        val h = world.height
        var checked = 0
        for (y in 0 until h) {
            val lat = ClimateStage.latitudeOf(y, h)
            val belt = abs(lat)
            // Skip the rows straddling a belt edge, where either answer is defensible.
            if (abs(belt - 30f) < 2f || abs(belt - 60f) < 2f) continue
            // Toward the bottom of the map is positive, so equatorward is positive in the north.
            val equatorward = if (lat > 0f) 1f else -1f
            val drift = world.climate.windMeridional.data[y * w]
            val expected = when {
                belt < 30f -> equatorward   // trades, in toward the ITCZ
                belt < 60f -> -equatorward  // westerlies, out toward the polar front
                else -> equatorward         // polar easterlies, back down toward it
            }
            assertEquals(
                expected * 0.3f, drift, 1e-6f,
                "the wind at ${"%.1f".format(lat)} degrees drifts the wrong way"
            )
            checked++
        }
        assertTrue(checked > h / 2, "only $checked rows were checked")

        val flat = WorldGenerationEngine.generateBlocking(
            WorldGenConfig(seed = 42L, width = 128, height = 128).let {
                it.copy(climate = it.climate.copy(meridionalWind = 0f))
            }
        )
        assertTrue(
            flat.climate.windMeridional.data.all { it == 0f },
            "a meridionalWind of zero should leave the wind purely zonal"
        )
    }

    @Test
    fun `a ridge running east-west is invisible to a zonal wind and not to a slanted one`() {
        // The same world twice, so the comparison is cell against its own self and altitude,
        // latitude, coast and every other confound cancels. With the slant off the two worlds are
        // the same world and every difference below is exactly zero, which is how this guard is
        // shown to fail without the fix — it cannot even be stated there.
        listOf(7L, 42L, 1234L).forEach { seed ->
            val base = WorldGenConfig(seed = seed, width = SIZE, height = SIZE)
            val zonal = WorldGenerationEngine.generateBlocking(
                base.copy(climate = base.climate.copy(meridionalWind = 0f))
            )
            val slanted = WorldGenerationEngine.generateBlocking(base)
            val w = zonal.width
            val h = zonal.height

            var climbGain = 0.0; var climbCells = 0
            var descendGain = 0.0; var descendCells = 0
            for (y in 1 until h - 1) {
                // Where the air comes from along the slanted wind's meridional leg.
                val from = if (slanted.climate.windMeridional.data[y * w] > 0f) y - 1 else y + 1
                for (x in 0 until w) {
                    val i = y * w + x
                    if (!zonal.sea.isLand[i] || !zonal.sea.isLand[from * w + x]) continue
                    val rise = zonal.sea.relativeElevation.data[i] -
                        zonal.sea.relativeElevation.data[from * w + x]
                    // A slope that lies across the meridional leg and not across the zonal one,
                    // so it is ground the old march genuinely could not climb.
                    val alongRow = abs(
                        zonal.sea.relativeElevation.data[i] -
                            zonal.sea.relativeElevation.data[y * w + ((x + w - 1) % w)]
                    )
                    if (abs(rise) < 0.004f || alongRow > abs(rise) * 0.5f) continue
                    val gain = (slanted.climate.precipitation.data[i] -
                        zonal.climate.precipitation.data[i]).toDouble()
                    if (rise > 0f) { climbGain += gain; climbCells++ }
                    else { descendGain += gain; descendCells++ }
                }
            }

            val climbing = climbGain / climbCells.coerceAtLeast(1)
            val descending = descendGain / descendCells.coerceAtLeast(1)
            println(
                "OROGRAPHY seed $seed: " + (
                    "meridionally climbing land gains %+.4f over %d cells, " +
                        "descending land %+.4f over %d cells"
                    ).format(climbing, climbCells, descending, descendCells)
            )
            assertTrue(
                climbCells > 1000 && descendCells > 1000,
                "seed $seed had too few meridional slopes to measure: $climbCells / $descendCells"
            )
            // Direction only, deliberately: a magnitude threshold here would be a number chosen to
            // make the guard green, and the sign over ten thousand cells is the claim anyway.
            assertTrue(
                climbing > descending,
                "seed $seed: the slant did not wet meridional windward slopes relative to lee " +
                    "ones ($climbing against $descending)"
            )
        }
    }

    /**
     * A tropical coast has a wet season and a dry one — but this guard does not own the claim.
     *
     * The plan asked for this: summer rainfall beating winter by three times over a contiguous
     * region covering at least 2% of land, on the coast of a tropical landmass, shown to fail with
     * the slant off. It does not fail with the slant off, and the honest thing is to say so rather
     * than to move a threshold until it does (ground rule 5).
     *
     * Two reasons, both worth carrying forward.
     *
     * The first is that A1's belt migration already produces large seasonal ratios throughout the
     * tropics on its own: the belts move ten degrees over the year, so a row that is under the
     * ITCZ in summer is under the descending subtropical limb in winter whatever the wind does,
     * and that alone clears three times over wide areas. Scanning seventy seeds, the largest such
     * region on a coast facing the sea grew with the slant on nearly every seed but from a base
     * that was usually already past 2%: seed 1 2.82 to 3.78, seed 7 2.53 to 2.56, seed 13 2.35 to
     * 2.36. Only this seed crossed the line cleanly — 1.96% without the slant, 2.93% with it — and
     * a guard whose failing case misses by four hundredths of a percent is a guard that will flake,
     * so only the passing half is asserted here.
     *
     * The second is that rainfall is still normalised, and clamped at 1. Measured on the tropical
     * coasts of five seeds, mean warm-season rainfall sits at 0.94 to 1.00 — pinned against the
     * clamp — so the wet half of a monsoon year has no room left to get wetter and the ratio can
     * only move by drying the winter. A4 replaces the normalisation with absolute millimetres, and
     * this claim should be restated there, where it can be measured.
     *
     * What is asserted meanwhile is the weaker, true thing: the region exists and covers its 2%.
     */
    @Test
    fun `a tropical coast has a wet season and a dry one`() {
        val base = WorldGenConfig(seed = MONSOON_SEED, width = SIZE, height = SIZE)
        val figures = listOf(0f, 0.3f).map { slant ->
            val world = WorldGenerationEngine.generateBlocking(
                base.copy(climate = base.climate.copy(meridionalWind = slant))
            )
            largestRegion(world, monsoonMask(world)) / world.sea.landCellCount.toDouble()
        }
        println(
            "MONSOON seed $MONSOON_SEED: " + (
                "largest contiguous region with a wet season %.0f times its dry one — " +
                    "%.2f%% of land with a zonal wind, %.2f%% with a slanted one"
                ).format(RATIO, figures[0] * 100, figures[1] * 100)
        )
        assertTrue(
            figures[1] >= MIN_SHARE,
            "seed $MONSOON_SEED's monsoon coast covers only ${"%.2f".format(figures[1] * 100)}% " +
                "of land"
        )
    }

    /**
     * Land in the outer tropics where the warm half of the year brings [RATIO] times the rain of
     * the cold half and brings a real wet season while it does it, on a coast whose *poleward*
     * side is open sea.
     *
     * Poleward, which is the reverse of the geometry the plan assumed, and the reason is worth
     * recording. The plan pictured the ITCZ sitting over a tropical landmass and drawing air in
     * off the sea on its equatorward side, which is the Indian monsoon. That needs the thermal
     * equator to migrate past the coast, and A1's tilt is ten degrees — the zonal-mean figure,
     * not the twenty-five or thirty degrees the great continents manage. So in this world the
     * summer ITCZ sits at ten degrees, land in the outer tropics is poleward of it, and the summer
     * trades there blow equatorward: onshore for a coast whose sea lies poleward. That is correct
     * trade-wind physics for a modest tilt — it is the north-east trades — but it is not the
     * Indian monsoon, and it will not be until the thermal equator is allowed to run further over
     * a heated continent than over the ocean beside it.
     */
    private fun monsoonMask(world: WorldMap): BooleanArray {
        val w = world.width
        val h = world.height
        val mask = BooleanArray(w * h)
        for (y in 0 until h) {
            val lat = ClimateStage.latitudeOf(y, h)
            if (abs(lat) < 12f || abs(lat) > 32f) continue
            val seaward = if (lat > 0f) -1 else 1
            for (x in 0 until w) {
                val i = y * w + x
                if (!world.sea.isLand[i]) continue
                val summer = world.climate.summerPrecipitation.data[i]
                val winter = world.climate.winterPrecipitation.data[i]
                if (summer < WET_SEASON || summer <= RATIO * winter) continue
                if (!facesSea(world, x, y, seaward)) continue
                mask[i] = true
            }
        }
        return mask
    }

    /** Open water within [SEA_REACH] cells that way, and land for [LAND_BEHIND] cells the other. */
    private fun facesSea(world: WorldMap, x: Int, y: Int, seaward: Int): Boolean {
        val w = world.width
        val h = world.height
        var found = false
        for (step in 1..SEA_REACH) {
            val ny = y + seaward * step
            if (ny !in 0 until h) break
            if (!world.sea.isLand[ny * w + x]) { found = true; break }
        }
        if (!found) return false
        for (step in 1..LAND_BEHIND) {
            val ny = y - seaward * step
            if (ny !in 0 until h || !world.sea.isLand[ny * w + x]) return false
        }
        return true
    }

    /** The largest four-connected block of the mask, in cells. X wraps; Y does not. */
    private fun largestRegion(world: WorldMap, mask: BooleanArray): Int {
        val w = world.width
        val h = world.height
        val seen = BooleanArray(w * h)
        var largest = 0
        val stack = ArrayDeque<Int>()
        for (start in 0 until w * h) {
            if (!mask[start] || seen[start]) continue
            var size = 0
            stack.addLast(start)
            seen[start] = true
            while (stack.isNotEmpty()) {
                val cell = stack.removeLast()
                size++
                val cx = cell % w
                val cy = cell / w
                val around = intArrayOf(
                    cy * w + ((cx + 1) % w),
                    cy * w + ((cx - 1 + w) % w),
                    if (cy > 0) (cy - 1) * w + cx else -1,
                    if (cy < h - 1) (cy + 1) * w + cx else -1
                )
                for (n in around) {
                    if (n < 0 || seen[n] || !mask[n]) continue
                    seen[n] = true
                    stack.addLast(n)
                }
            }
            if (size > largest) largest = size
        }
        return largest
    }

    private fun checksum(field: FloatField): Long {
        var c = 0L
        field.data.forEach { c = c * 31 + it.toRawBits() }
        return c
    }
}
