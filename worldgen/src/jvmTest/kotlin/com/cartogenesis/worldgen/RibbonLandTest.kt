package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.math.JumpFloodDistance
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * How much of the land is ribbon — long thin strips a couple of cells wide.
 *
 * Convergent boundaries raise a linear belt, and where that belt crosses a submerged region only
 * its crest clears sea level, leaving a strip of land with a strait on either side. Island arcs do
 * look like that, but real ones are segmented, curved and volcanic, not continuous ruler-edged
 * walls of uniform width.
 *
 * Measured as the share of land in bodies no cell of which stands further from water than a
 * hundred and seventieth of the map's width, in cell widths of ground, which is what "thin" means
 * here, alongside the longest single strip so a few big continents cannot hide a bad one.
 */
class RibbonLandTest : BorrowsSharedWorlds() {

    /**
     * The most of its land a world may hold in strips, as a percentage — see the assertion below
     * for the derivation from Earth's peninsulas and island arcs.
     */
    private val EARTH_RIBBON_SHARE = 1.5

    /**
     * The claim being tested is about *erosion*: that widening a belt's footprint stops its crest
     * reading as a strip. So the same world is measured un-eroded and eroded, and the eroded one,
     * which is the world the app builds, is held besides to Earth's own share of such land.
     */
    @Test
    fun `erosion widens the strips a belt leaves in shallow sea`() {
        // Seed 7 and not 234475 since the ground was put on its ruler (docs/DESIGN_LEDGER.md, Fix
        // 2): a belt is as wide north-south as east-west now, and 234475's un-eroded world at 1024
        // has no strip left for erosion to widen, measured either way. Of seeds 1 to 22 scanned
        // with the same measure, ten leave strips before erosion and seed 7 the most of them, four
        // holding 0.324% of its land; 5, 8, 11, 13, 14, 17, 18, 20 and 21 leave one each, seed
        // 20's the largest at 0.487%.
        //
        // On the 12,000 km planet ([CalibrationPlanet]), where that scan was made: a strip here is a
        // share of the map's width, `w / 170`, 71 km of half-width there and 236 km on the
        // Earth-sized planet, whose seed 7 leaves no strip before erosion (0.0% either way), so the
        // case had nothing to widen. Restating the strip on the ground is docs/TODO.md's.
        val base = CalibrationPlanet.of(WorldGenConfig.forRows(7L, 512))
            .atResolution(2048, 1024)
        listOf(
            "no erosion" to base.copy(erosion = base.erosion.copy(enabled = false)),
            "eroded" to base
        ).map { (name, config) ->
            val world = SharedWorlds.world(config)
            val w = world.width
            val h = world.height
            val land = world.sea.isLand

            // How far each land cell stands from the nearest water on the ground, in cell widths: a
            // row is half as tall as a column is wide, so a strip running east-west is as many
            // cell widths across as it is rows across times the row's height. Counted in cells,
            // as this case first was, a strip running east-west could be twice as wide on the
            // ground as one running north-south and still count.
            val depth = FloatArray(w * h) { if (land[it]) JumpFloodDistance.INFINITE else 0f }
            JumpFloodDistance.run(
                w, h, depth, IntArray(w * h) { if (land[it]) -1 else it }, world.config.cellHeightInCellWidths
            )

            // Group the land into bodies, and judge each by its own half-width. A strip stays
            // shallow however long it runs; a continent does not.
            val component = IntArray(w * h) { -1 }
            var components = 0
            val area = ArrayList<Int>()
            val halfWidth = ArrayList<Float>()
            for (start in 0 until w * h) {
                if (!land[start] || component[start] != -1) continue
                val id = components++
                var cells = 0
                var deepest = 0f
                val stack = ArrayDeque<Int>()
                stack.add(start)
                component[start] = id
                while (stack.isNotEmpty()) {
                    val i = stack.removeLast()
                    cells++
                    if (depth[i] > deepest) deepest = depth[i]
                    val x = i % w
                    val y = i / w
                    for (dy in -1..1) {
                        val ny = y + dy
                        if (ny !in 0 until h) continue
                        for (dx in -1..1) {
                            val n = ny * w + ((x + dx + w) % w)
                            if (land[n] && component[n] == -1) {
                                component[n] = id
                                stack.add(n)
                            }
                        }
                    }
                }
                area.add(cells)
                halfWidth.add(deepest)
            }

            // Scale-free: a strip is thin relative to the map, and long enough to be a feature.
            val thin = (w / 170f).coerceAtLeast(2f)
            val longEnough = w / 4

            val landCells = area.sum()
            var ribbonArea = 0
            var ribbonCount = 0
            var longest = 0
            val strip = BooleanArray(components)
            for (id in 0 until components) {
                if (halfWidth[id] <= thin && area[id] >= longEnough) {
                    strip[id] = true
                    ribbonCount++
                    ribbonArea += area[id]
                    if (area[id] > longest) longest = area[id]
                }
            }

            // What kind of boundary each strip sits on. A strip of land along a mountain belt is
            // the failure this test exists for; an island arc is thin by nature and a chain of
            // volcanoes really is what an oceanic-oceanic margin builds, so the two want telling
            // apart in the report rather than averaging together in the figure.
            val classNames = com.cartogenesis.worldgen.pipeline.BoundaryClass.entries
            for (id in 0 until components) {
                if (!strip[id]) continue
                val tally = IntArray(classNames.size)
                for (i in 0 until w * h) {
                    if (component[i] != id) continue
                    val cls = world.plates.nearestBoundaryClass[i]
                    if (cls in tally.indices) tally[cls]++
                }
                val dominant = tally.indices.maxByOrNull { tally[it] } ?: 0
                println(
                    "RIBBON   strip of %d cells, mostly %s (%d%% of it)".format(
                        area[id], classNames[dominant].name,
                        tally[dominant] * 100 / area[id].coerceAtLeast(1)
                    )
                )
            }
            val share = ribbonArea * 100.0 / landCells
            println(
                "RIBBON %-22s %d bodies, %d are strips (half-width <= %.0f) holding %.1f%% of land, largest strip %d cells"
                    .format(name, components, ribbonCount, thin, share, longest)
            )
            share
        }.let { (withoutErosion, withErosion) ->
            // Erosion cannot remove a strip, and is not meant to: a belt crossing shallow sea will
            // always leave land above water. What it does is take material off the crest and pile
            // it against the flanks, which widens the footprint until the strip stops being one.
            assertTrue(
                withErosion < withoutErosion,
                "erosion left as much ribbon land as before: $withErosion% vs $withoutErosion%"
            )
            // The finished world, against Earth (it was against the single-epoch generator until
            // H5b, docs/DESIGN_LEDGER.md, and that generator is gone with TH1-1). A strip here is a body whose half-width is at most w/170 —
            // six cells at 1024, so twelve across, which at this map's working scale is of the
            // order of a hundred kilometres. Earth's land of that description is its peninsulas and
            // island arcs: Baja California 143,000 km2, the Kamchatka-Kuril-Aleutian-Ryukyu chain
            // and the Japanese arc's narrower half together some 500,000, the Malay peninsula's
            // southern half, Florida, Nova Scotia and Newfoundland 163,000, Tierra del Fuego and
            // the Antilles, the Lesser Sundas 70,000 — of the order of one to one and a half
            // million square kilometres of Earth's 148.94 million, which is 0.7 to 1.0% of its
            // land. The bound is set at 1.5%, half again above the upper end of that estimate,
            // since one world is one sample.
            println("RIBBON eroded %.4f%% of land; Earth's own peninsulas and arcs are 0.7-1.0%%".format(withErosion))
            assertTrue(
                withErosion <= EARTH_RIBBON_SHARE,
                "a world holds more than $EARTH_RIBBON_SHARE% of its land in strips, which is half " +
                    "again more than Earth's peninsulas and island arcs: $withErosion%"
            )
        }
    }
}
