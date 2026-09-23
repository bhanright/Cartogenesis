package com.cartogenesis.desktop

import com.cartogenesis.cartography.MapRasterizer
import com.cartogenesis.cartography.MapStyle
import com.cartogenesis.cartography.MapView
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.cartography.RiverSelection
import com.cartogenesis.cartography.RiverPen
import com.cartogenesis.ui.MapImage
import com.cartogenesis.worldgen.SharedWorlds
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Whether a river is drawn as wide as the water it carries.
 *
 * The complaint this answers is that every river looked alike (the author, 2026-09-12). They did not
 * quite — the old rule raised the discharge to the 0.28 power and clamped the answer between half a
 * cell and 2.8 — but a thousandfold range of flow came out as one pixel beside three, which at a
 * glance is one pen. What a channel's width actually does is Leopold and Maddock's: it goes as the
 * square root of the discharge, so a trunk is unmistakable and a headwater is a thread.
 *
 * Every guard here is run twice, once against the drawing this build makes and once against the
 * rule it replaced ([supersededPen]), and asserts that the old rule *fails* it. A guard that has
 * only ever been green proves nothing, and keeping the superseded rule beside the new one is the
 * cheapest way to keep proving that this one can discriminate.
 */
@ExtendWith(SharedWorldsCheck::class)
class RiverWidthTest {

    private companion object {

        /** Ground rule 1's seeds, at the size a preview is drawn at. */
        val SEEDS = listOf(7L, 42L, 1234L)
        const val SIDE = 512

        /** The seeds for the mouth: the author's own, and the four the audit standardised on. */
        val MOUTH_SEEDS = listOf(298405L, 7L, 42L, 1234L, 99L)

        /**
         * The full pen as a constant, in output pixels, whatever the size of the sheet.
         *
         * Kept as the control the pen guard is run against, for the reason [supersededPen] is
         * kept: a rule that has been replaced is the cheapest proof that its replacement can be
         * told apart from it.
         */
        const val SUPERSEDED_FULL_PIXELS: Float = 5f

        /**
         * How closely the drawn pen must track the square root of the discharge, as a Pearson
         * correlation over every drawn point of every river on a seed.
         *
         * The law is a proportionality, so a drawing that obeys it has its width on a straight line
         * in the square root of discharge and correlates at unity — the hairline moves that line's
         * intercept off zero, which a correlation does not see. The bar is therefore unity to the
         * precision the arithmetic offers rather than a figure fitted to anything: a rule that is
         * some other power of the discharge cannot reach it, however nearly straight it looks.
         */
        const val MIN_CORRELATION = 0.999

        /**
         * What share of confluences must show the trunk *strictly* wider than either branch.
         *
         * Discharge sums at a junction, so every one of them should; a rule that ties at a clamp
         * is a rule that has stopped answering. Short of every last one because two branches whose
         * flows differ in the sixth digit can land on the same float.
         */
        const val MIN_WIDENING_JUNCTIONS = 0.99
    }

    /** One world, its drawn network flattened to what a guard needs to ask about. */
    private class Network(val world: WorldMap) {
        val discharge: FloatArray = world.rivers.flowAccumulation.data

        /** Every cell any drawn river runs through, and how wide it is drawn there. */
        val ratioByCell = HashMap<Int, Float>()

        /** The smallest positive discharge on the network: the channel the pen's hairline is for. */
        var smallest = Float.MAX_VALUE
            private set

        init {
            world.rivers.rivers.forEach { river ->
                river.cells.forEachIndexed { index, cell ->
                    ratioByCell[cell] = river.widthRatio[index]
                    val flow = discharge[cell]
                    if (flow > 0f && flow < smallest) smallest = flow
                }
            }
            if (smallest == Float.MAX_VALUE) smallest = 1f
        }

        /**
         * Where two or more drawn channels meet, as the trunk cell below and the branches above.
         *
         * Only cells that carry water of their own. A river's last point sits in the sea, where the
         * flow was never routed and there is no discharge to compare — and where two rivers reaching
         * the coast side by side can share the cell, which would read as a confluence of one river
         * with another it never meets.
         */
        fun confluences(): List<Pair<Int, List<Int>>> {
            val feeding = HashMap<Int, MutableList<Int>>()
            for (cell in ratioByCell.keys) {
                if (discharge[cell] <= 0f) continue
                val below = world.rivers.flowTarget[cell]
                if (below < 0 || discharge[below] <= 0f || !ratioByCell.containsKey(below)) continue
                feeding.getOrPut(below) { ArrayList() }.add(cell)
            }
            return feeding.entries
                .filter { it.value.size >= 2 }
                .map { it.key to it.value.sorted() }
                .sortedBy { it.first }
        }
    }

    /**
     * The pen the 2.0.0 renderer drew with: `RiverStage`'s width in cells through
     * `MapRasterizer`'s floor, at the scale a 512 preview used.
     *
     * The threshold it divided by was the world's total runoff times `RiverConfig.sourceThreshold`,
     * which no world carries afterwards; the smallest discharge on the drawn network is the same
     * quantity to within one source cell's overshoot of it, and that is well inside what any of
     * these guards turn on.
     */
    private fun supersededPen(discharge: Float, smallest: Float): Float =
        (0.55f * (discharge / smallest).pow(0.28f)).coerceIn(0.5f, 2.8f).coerceAtLeast(0.9f)

    private fun pen(ratio: Float, cellsAcross: Int = SIDE): Float =
        RiverPen.widthPixels(ratio, cellsAcross)

    private fun world(seed: Long, side: Int = SIDE): WorldMap =
        SharedWorlds.world(
            WorldGenConfig(seed = seed, width = side, height = side)
        )

    @Test
    fun `the drawn width tracks the square root of the discharge`() {
        var worstNow = 1.0
        var bestBefore = 0.0
        SEEDS.forEach { seed ->
            val network = Network(world(seed))
            val root = ArrayList<Double>()
            val now = ArrayList<Double>()
            val before = ArrayList<Double>()
            network.ratioByCell.forEach { (cell, ratio) ->
                val flow = network.discharge[cell]
                if (flow <= 0f) return@forEach
                root.add(sqrt(flow.toDouble()))
                now.add(pen(ratio).toDouble())
                before.add(supersededPen(flow, network.smallest).toDouble())
            }

            val nowCorrelation = correlation(root, now)
            val beforeCorrelation = correlation(root, before)
            worstNow = minOf(worstNow, nowCorrelation)
            bestBefore = maxOf(bestBefore, beforeCorrelation)
            println(
                "RIVERWIDTH seed=$seed points=${root.size} correlation with sqrt(discharge): " +
                    "%.4f now, %.4f under the superseded rule".format(nowCorrelation, beforeCorrelation)
            )
            assertTrue(
                nowCorrelation >= MIN_CORRELATION,
                ("seed $seed: the drawn width correlates %.4f with the square root of " +
                    "discharge, under $MIN_CORRELATION").format(nowCorrelation)
            )
        }
        assertTrue(
            bestBefore < MIN_CORRELATION,
            "the superseded rule now passes this guard at %.4f, so the guard has stopped discriminating"
                .format(bestBefore)
        )
        println(
            ("RIVERWIDTH worst correlation now %.4f, best under the superseded rule %.4f, " +
                "bar $MIN_CORRELATION").format(worstNow, bestBefore)
        )
    }

    /**
     * How many times the widest drawn river beats the narrowest is a property of the *sheet* now,
     * not a bar of its own.
     *
     * That ratio was held at four, which separated "these vary" from "these are alike" while the
     * pen was five pixels wide whatever the size of the map. The full pen is now 0.24% of the
     * width and the hairline is still 0.8 px, so the nib spans 1.5x at 512, 3.1x at 1024, 6.1x at
     * 2048 and 12.3x at 4096: a small sheet cannot show a trunk six times a headwater, because the
     * headwater is already the finest mark a nib leaves. What is still worth asserting is that a
     * map uses the *whole* of the nib it has — the biggest river drawn at the full pen and the
     * smallest at the hairline, which is also what says `RiverWidth` normalised against the
     * network and not against something else. The superseded rule's two ends are its own clamps,
     * 0.9 and 2.8 px, and belong to no sheet at all.
     */
    @Test
    fun `the rivers on a map use the whole of the pen the sheet allows`() {
        val nib = RiverPen.fullPixels(SIDE) / RiverPen.HAIRLINE_PIXELS
        var worstNow = Double.MAX_VALUE
        var bestBefore = 0.0
        SEEDS.forEach { seed ->
            val network = Network(world(seed))
            var narrowNow = Float.MAX_VALUE
            var wideNow = 0f
            var narrowBefore = Float.MAX_VALUE
            var wideBefore = 0f
            network.ratioByCell.forEach { (cell, ratio) ->
                val flow = network.discharge[cell]
                if (flow <= 0f) return@forEach
                val nowPen = pen(ratio)
                narrowNow = minOf(narrowNow, nowPen)
                wideNow = maxOf(wideNow, nowPen)
                val beforePen = supersededPen(flow, network.smallest)
                narrowBefore = minOf(narrowBefore, beforePen)
                wideBefore = maxOf(wideBefore, beforePen)
            }

            val spreadNow = wideNow.toDouble() / narrowNow
            val spreadBefore = wideBefore.toDouble() / narrowBefore
            worstNow = minOf(worstNow, spreadNow)
            bestBefore = maxOf(bestBefore, spreadBefore)
            println(
                ("RIVERWIDTH seed=$seed pen %.2f-%.2f px, spread %.2fx against the nib's %.2fx; " +
                    "superseded %.2f-%.2f px, spread %.2fx").format(
                    narrowNow, wideNow, spreadNow, nib, narrowBefore, wideBefore, spreadBefore
                )
            )
            assertTrue(
                abs(narrowNow - RiverPen.HAIRLINE_PIXELS) < 0.01f,
                "seed $seed: the finest river is drawn %.2f px, not the hairline".format(narrowNow)
            )
            assertTrue(
                abs(wideNow - RiverPen.fullPixels(SIDE)) < 0.01f,
                "seed $seed: the biggest river is drawn %.2f px, not the full pen %.2f px"
                    .format(wideNow, RiverPen.fullPixels(SIDE))
            )
        }
        // The superseded rule's ends are its own clamps, 0.9 and 2.8 px, and have nothing to do
        // with the sheet: on a 512 sheet it draws a trunk wider than the whole nib, on a 4096 one
        // less than a third of it.
        assertTrue(
            abs(bestBefore - nib) > 0.1,
            "the superseded rule now spans the nib exactly, so the guard has stopped discriminating"
        )
        println(
            ("RIVERWIDTH the nib spans %.2fx at 512, %.2fx at 1024 and %.2fx at 2048; the drawn " +
                "spread is %.2fx at 512, %.2fx under the superseded rule").format(
                nib, RiverPen.fullPixels(1024) / RiverPen.HAIRLINE_PIXELS,
                RiverPen.fullPixels(2048) / RiverPen.HAIRLINE_PIXELS, worstNow, bestBefore
            )
        )
    }

    @Test
    fun `a trunk is wider than the branches that feed it, and never narrows downstream`() {
        var worstShareNow = 1.0
        var worstShareBefore = 1.0
        SEEDS.forEach { seed ->
            val network = Network(world(seed))

            // Monotone downstream. A drawn river never crosses standing water — the trace stops at
            // the shore and the outflow below a lake is a channel of its own — so there is no
            // interruption to make an exception for, and the width must never fall while the water
            // it stands for does not.
            //
            // It can still fall where the water does, and on rare occasions the water does: the
            // routing hands a handful of cells a downstream neighbour that carries less than they
            // do, six of the 101415 land cells of seed 1234 among them. That is the flow graph's
            // own inconsistency and not the pen's — the superseded rule, monotone in the same
            // quantity, narrows at exactly the same points — so it is counted against the land it
            // is a property of rather than treated as a width failure.
            var narrowings = 0
            var againstTheWater = 0
            var steps = 0
            network.world.rivers.rivers.forEach { river ->
                for (k in 1 until river.widthRatio.size) {
                    steps++
                    if (river.widthRatio[k] >= river.widthRatio[k - 1]) continue
                    narrowings++
                    val fell = network.discharge[river.cells[k]] <
                        network.discharge[river.cells[k - 1]]
                    if (!fell) againstTheWater++
                }
            }
            assertTrue(
                againstTheWater == 0,
                "seed $seed: a drawn river narrowed at $againstTheWater points " +
                    "where it carried no less water"
            )
            val landCells = network.world.sea.isLand.count { it }
            println(
                "RIVERWIDTH seed=$seed $steps drawn steps, $narrowings of them into less water, " +
                    "on $landCells land cells"
            )
            // Against the **land**, not against the drawn course, and W1 is why. What is being
            // counted is cells where the routing hands a cell a downstream neighbour carrying less
            // water than it does — a property of the flow graph over the whole world, a handful per
            // map, and nothing to do with how much of that graph the pen happens to draw.
            //
            // Stating it as a share of the drawn steps made it move whenever the drawn network did,
            // and it moved twice. It was a thousandth until the terrain shortened seed 1234's
            // course to 2517 steps, which made a thousandth two steps against the graph's own three
            // inconsistent cells, so it became a five-hundredth; then W1's rainfall moved both the
            // course and the count again. One in ten thousand land cells is the same claim stated
            // about the thing it is a property of, and it does not move when the pen draws more or
            // less of the world. What holds the *pen* to account is `againstTheWater`, which is
            // zero on every seed.
            assertTrue(
                narrowings * 10_000 <= landCells,
                "seed $seed: $narrowings drawn steps run into less water than the step above, " +
                    "on $landCells land cells — past one in ten thousand, so the routing is " +
                    "inconsistent more often than a handful of cells"
            )

            // Only the confluences the flow graph agrees are confluences: the same handful of cells
            // that carry less water than the cell above them can sit under one, and there the claim
            // being tested — the trunk is wider because discharge sums — has no premise.
            val confluences = network.confluences()
                .filter { (trunk, branches) ->
                    branches.all { network.discharge[trunk] > network.discharge[it] }
                }
            var widerNow = 0
            var widerBefore = 0
            confluences.forEach { (trunk, branches) ->
                val trunkNow = pen(network.ratioByCell.getValue(trunk))
                val branchNow = branches.maxOf { pen(network.ratioByCell.getValue(it)) }
                assertTrue(
                    trunkNow >= branchNow,
                    ("seed $seed: the trunk below the confluence at $trunk is %.3f px, " +
                        "narrower than the %.3f px branch above it").format(trunkNow, branchNow)
                )
                if (trunkNow > branchNow) widerNow++

                val trunkBefore = supersededPen(network.discharge[trunk], network.smallest)
                val branchBefore =
                    branches.maxOf { supersededPen(network.discharge[it], network.smallest) }
                if (trunkBefore > branchBefore) widerBefore++
            }

            val shareNow = widerNow.toDouble() / confluences.size
            val shareBefore = widerBefore.toDouble() / confluences.size
            worstShareNow = minOf(worstShareNow, shareNow)
            worstShareBefore = minOf(worstShareBefore, shareBefore)
            println(
                ("RIVERWIDTH seed=$seed confluences=${confluences.size}, trunk strictly wider at " +
                    "%.1f%% now against %.1f%% under the superseded rule").format(
                    shareNow * 100, shareBefore * 100
                )
            )
            assertTrue(
                shareNow >= MIN_WIDENING_JUNCTIONS,
                ("seed $seed: only %.1f%% of confluences widen the trunk, under " +
                    "%.1f%%").format(shareNow * 100, MIN_WIDENING_JUNCTIONS * 100)
            )
        }
        // Weaker than the other two cross-checks, and it has to be: the clamp only bites where a
        // map has a river big enough to reach it, so a seed of small drainages passes this under
        // the old rule as well. Failing on any standard seed is enough to show the guard can tell
        // the two apart.
        assertTrue(
            worstShareBefore < MIN_WIDENING_JUNCTIONS,
            ("the superseded rule now widens at least %.1f%% of confluences on every standard " +
                "seed, so the guard has stopped discriminating").format(worstShareBefore * 100)
        )
        println(
            "RIVERWIDTH worst confluence share %.1f%% now, %.1f%% under the superseded rule, bar %.1f%%"
                .format(worstShareNow * 100, worstShareBefore * 100, MIN_WIDENING_JUNCTIONS * 100)
        )
    }

    /**
     * The pen is a share of the sheet, so a map drawn twice as large has rivers twice as wide.
     *
     * The world is regenerated at the export's size rather than upscaled, so the two renders share
     * no cell and cannot be compared pixel for pixel; what has to agree is the nib, which is the
     * span of stroke widths the overlay asks for. A constant pen holds that span fixed in output
     * pixels, which
     * is the rule this replaces: the same country at 1024 got the same five-pixel trunk it got at
     * 2048, twice the weight of ink against half as much map.
     */
    @Test
    fun `the pen is the same share of the sheet at every size`() {
        // Every traced course, at the top mark of the density scale: this clause is about
        // `RiverPen`'s span from hairline to full pen, and the sheet's own selection (X1c) draws
        // only the largest rivers, whose thinnest headwater need not reach the hairline at all —
        // at 512 the thinnest drawn stroke came out 0.0009 px above it. What the pen spans is not
        // the selection's business.
        val options = RenderOptions(
            view = MapView.FANTASY,
            style = MapStyle.ATLAS,
            riverInkStep = RiverSelection.EVERY_COURSE_STEP
        )
        val spans = listOf(512, 1024).map { side ->
            val widths = MapRasterizer.overlay(world(42L, side), options).rivers.map { it.widthPixels }
            val span = widths.min() to widths.max()
            println(
                "RIVERWIDTH ${side}x$side draws %.2f-%.2f px, full is %.3f%% of the width"
                    .format(span.first, span.second, span.second * 100f / side)
            )
            span
        }

        // The widest drawn stroke is a hair under the full pen, and has to be: the mouth's own
        // cell is trimmed away at the shoreline, so the last stroke carries the width of the cell
        // above it. A hundredth of a pixel is the room that needs.
        listOf(512, 1024).forEachIndexed { k, side ->
            assertTrue(
                abs(spans[k].first - RiverPen.HAIRLINE_PIXELS) < 1e-4f &&
                    abs(spans[k].second - RiverPen.fullPixels(side)) < 0.01f,
                "at $side the drawn pen ${spans[k]} is not the pen RiverPen declares"
            )
        }
        // Twice the sheet, twice the stroke. The hairline is a nib, not a width, and stays put.
        assertTrue(
            abs(spans[1].second - 2f * spans[0].second) < 0.02f,
            "the pen did not double with the sheet: %.3f px at 512, %.3f px at 1024"
                .format(spans[0].second, spans[1].second)
        )
        assertTrue(
            abs(spans[1].first - spans[0].first) < 1e-4f,
            "the hairline moved with the sheet: ${spans[0].first} then ${spans[1].first}"
        )
        // A constant pen is the same stroke on both sheets, so it fails the line above.
        val supersededAt512 = SUPERSEDED_FULL_PIXELS
        val supersededAt1024 = SUPERSEDED_FULL_PIXELS
        assertTrue(
            abs(supersededAt1024 - 2f * supersededAt512) > 1e-3f,
            "the superseded pen now doubles with the sheet, so this guard has stopped discriminating"
        )
        println(
            "RIVERWIDTH full pen %.2f px at 512, %.2f px at 1024, against a constant %.2f px"
                .format(spans[0].second, spans[1].second, SUPERSEDED_FULL_PIXELS)
        )
    }

    /**
     * The ink stops at the shoreline: nothing pools on the open sea beyond a river's mouth.
     *
     * Two measurements of the same thing. The geometry is exact and is what the drawing decides:
     * no stroke may *end* over water, because the round cap that blends one cell-long segment into
     * the next carries half the stroke's width past wherever it ends. The untrimmed course this
     * replaces ended every mouth at the centre of the water cell itself, so it fails by
     * construction, and how far its ink then reached past the coast is measured beside it. The
     * pixels are what a reader sees: rendered with the rivers on and with them off, no differing
     * pixel may lie on water with no land anywhere around it.
     */
    @Test
    fun `a river's ink stops at the shoreline`() {
        val options = RenderOptions(view = MapView.FANTASY, style = MapStyle.ATLAS)
        var worstUntrimmed = 0f
        var untrimmedEndsOverWater = 0
        MOUTH_SEEDS.forEach { seed ->
            val world = world(seed)
            val w = world.width
            val h = world.height
            val water = world.rivers.lakes

            // Where every drawn stroke finishes. A stroke that ends over water carries a round cap
            // of half its own width out there with it, which is the blob this trims away.
            var endsOverWater = 0
            MapRasterizer.overlay(world, options).rivers.forEach { segment ->
                var x = floor(segment.toX).toInt() % w
                if (x < 0) x += w
                val y = floor(segment.toY).toInt().coerceIn(0, h - 1)
                val cell = y * w + x
                if (!world.sea.isLand[cell] || water.isOpenWater(cell)) endsOverWater++
            }

            // The control: the untrimmed course ended at the centre of the water cell itself, so
            // every mouth on the map put a stroke's end and a round cap out on the water.
            var mouths = 0
            var untrimmed = 0f
            world.rivers.rivers.forEach { river ->
                val cells = river.cells
                val mouth = cells.last()
                if (world.sea.isLand[mouth] && !water.isOpenWater(mouth)) return@forEach
                mouths++
                val lastOnLand = cells[cells.size - 2]
                val half = pen(river.widthRatio[cells.size - 2], w) / 2f
                untrimmed = maxOf(untrimmed, stepLength(lastOnLand, mouth, w) / 2f + half)
            }
            worstUntrimmed = maxOf(worstUntrimmed, untrimmed)
            untrimmedEndsOverWater += mouths

            val withRivers = pixelsOf(world, options)
            val without = pixelsOf(world, options.copy(showRivers = false))
            var onWater = 0
            var offshore = 0
            for (i in withRivers.indices) {
                if (withRivers[i] == without[i] || world.sea.isLand[i]) continue
                onWater++
                if (!touchesLand(world, i)) offshore++
            }
            println(
                ("RIVERMOUTH seed=$seed $mouths mouths at water, $endsOverWater strokes ending " +
                    "over it, untrimmed ink would reach %.2f px past the shore; $onWater sea " +
                    "pixels inked, $offshore of them offshore").format(untrimmed)
            )
            assertTrue(
                endsOverWater == 0,
                "seed $seed: $endsOverWater river strokes end over open water"
            )
            assertTrue(
                offshore == 0,
                "seed $seed: $offshore river pixels lie on water with no land beside them"
            )
        }
        assertTrue(
            untrimmedEndsOverWater > 0 && worstUntrimmed > 1f,
            "the untrimmed course no longer ends over water, so this guard has stopped discriminating"
        )
        println(
            "RIVERMOUTH $untrimmedEndsOverWater mouths would have ended over water, the worst " +
                "%.2f px past the shore; none do".format(worstUntrimmed)
        )
    }

    /** Distance between two cells' centres, in cells, across the east-west seam if need be. */
    private fun stepLength(from: Int, to: Int, cellsAcross: Int): Float {
        var dx = to % cellsAcross - from % cellsAcross
        if (dx > cellsAcross / 2) dx -= cellsAcross
        if (dx < -cellsAcross / 2) dx += cellsAcross
        val dy = to / cellsAcross - from / cellsAcross
        return sqrt((dx * dx + dy * dy).toFloat())
    }

    /** Whether any of a cell's eight neighbours is land. */
    private fun touchesLand(world: WorldMap, cell: Int): Boolean {
        val w = world.width
        val cx = cell % w
        val cy = cell / w
        for (dy in -1..1) {
            val y = cy + dy
            if (y < 0 || y >= world.height) continue
            for (dx in -1..1) {
                var x = (cx + dx) % w
                if (x < 0) x += w
                if (world.sea.isLand[y * w + x]) return true
            }
        }
        return false
    }

    /**
     * Whatever the river pen does, it must do it only where a river is.
     *
     * Rendered with the rivers on and with them off, in every style: a pixel that differs between
     * the two is a pixel the river drawing touched, and every one of them has to lie within the
     * pen's reach of a cell some river runs through. This is what makes a change of pen safe to
     * make — a fingerprint that moves can only have moved on the water.
     */
    @Test
    fun `the river pen touches nothing but the rivers`() {
        val world = world(42L)
        val reach = (RiverPen.fullPixels(world.width) / 2f).toInt() + 2
        val nearRiver = dilatedRiverMask(world, reach)

        MapStyle.entries.forEach { style ->
            val base = RenderOptions(view = MapView.FANTASY, style = style)
            val withRivers = pixelsOf(world, base)
            val without = pixelsOf(world, base.copy(showRivers = false))

            var differing = 0
            var strayed = 0
            for (i in withRivers.indices) {
                if (withRivers[i] == without[i]) continue
                differing++
                if (!nearRiver[i]) strayed++
            }
            println(
                "RIVERWIDTH ${style.label}: $differing pixels differ with rivers on, " +
                    "$strayed of them off the water"
            )
            assertTrue(
                strayed == 0,
                "${style.label}: $strayed pixels changed further than $reach px from any river cell"
            )
            assertTrue(differing > 0, "${style.label}: turning the rivers off changed nothing at all")
        }
    }

    /** How long a world and its overlay cost, so a change of pen can be seen not to have. */
    @Test
    fun `how long the widths cost`() {
        val options = RenderOptions()
        var world: WorldMap? = null
        // Generated here rather than borrowed from `SharedWorlds`, because the generation's time
        // is one of the two figures printed.
        val generateMs = measureTimeMillis {
            world = WorldGenerationEngine.generateBlocking(WorldGenConfig(seed = 42L, width = SIDE, height = SIDE))
        }
        val ready = world!!
        MapRasterizer.overlay(ready, options)
        var segments = 0
        val overlayMs = measureTimeMillis {
            segments = MapRasterizer.overlay(ready, options).rivers.size
        }
        println(
            "RIVERWIDTH seed 42 at $SIDE: generated in $generateMs ms, " +
                "$segments river segments laid out in $overlayMs ms"
        )
    }

    /** True within [reach] pixels of a cell any drawn river runs through. */
    private fun dilatedRiverMask(world: WorldMap, reach: Int): BooleanArray {
        val w = world.width
        val h = world.height
        val mask = BooleanArray(w * h)
        world.rivers.rivers.forEach { river ->
            river.cells.forEach { cell ->
                val cx = cell % w
                val cy = cell / w
                for (dy in -reach..reach) {
                    val y = cy + dy
                    if (y < 0 || y >= h) continue
                    for (dx in -reach..reach) {
                        var x = (cx + dx) % w
                        if (x < 0) x += w
                        mask[y * w + x] = true
                    }
                }
            }
        }
        return mask
    }

    private fun pixelsOf(world: WorldMap, options: RenderOptions): IntArray {
        val bitmap = MapImage.toBitmap(world, options)
        val bytes = bitmap.readPixels()!!
        bitmap.close()
        return IntArray(bytes.size / 4) { i ->
            val o = i * 4
            (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or
                ((bytes[o + 2].toInt() and 0xFF) shl 16) or ((bytes[o + 3].toInt() and 0xFF) shl 24)
        }
    }

    private fun correlation(a: List<Double>, b: List<Double>): Double {
        val n = a.size
        if (n < 2) return 0.0
        val meanA = a.sum() / n
        val meanB = b.sum() / n
        var covariance = 0.0
        var varianceA = 0.0
        var varianceB = 0.0
        for (i in 0 until n) {
            val da = a[i] - meanA
            val db = b[i] - meanB
            covariance += da * db
            varianceA += da * da
            varianceB += db * db
        }
        if (varianceA <= 0.0 || varianceB <= 0.0) return 0.0
        return covariance / sqrt(varianceA * varianceB)
    }
}
