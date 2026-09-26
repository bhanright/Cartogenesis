package com.cartogenesis.cartography

import com.cartogenesis.cartography.geometry.KnownFailures
import com.cartogenesis.cartography.geometry.RecordedViolation
import com.cartogenesis.worldgen.BorrowsSharedWorlds
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.model.WorldScale
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Whether [MapStyle.PEN_AND_INK] draws what an engraver would have drawn.
 *
 * A style is a claim about appearance, and most of what this one claims is settled by looking at
 * the gallery. Two of its claims are not, because the picture it replaced looked plausible at a
 * glance and was wrong in a way only a measurement finds:
 *
 *  - **The strokes follow the ground.** The hatch this replaced laid every stroke at one fixed
 *    bearing whatever the slope faced, so a range read as a scribble. The measurement below takes
 *    the *rendered pixels*, recovers the direction the ink actually runs in with a structure
 *    tensor, and compares it with the aspect. The old rule is reproduced here as the control and
 *    fails by the width of the whole answer.
 *  - **A mark is a fixed count of pixels, not a share of the sheet.** A pen does not grow with the
 *    plate: an engraver handed a larger one draws the same hachure with the same nib and fits more
 *    of them on it. So the pitch below is measured in pixels and asserted identical at 512, 1024,
 *    2048 and 4096, and the stroke *count* over the same ground is asserted to grow as the square
 *    of the grid ratio. The control is the drawing enlarged with the sheet, which is what shipped
 *    first and what the review sent back: at 2048 its hachures are dashes thirty pixels long and
 *    its stipple is polka dots.
 */
class PenAndInkTest : BorrowsSharedWorlds() {

    internal companion object {

        /**
         * How far the ink may run from the aspect, in degrees, averaged over the land it is drawn
         * on.
         *
         * Not zero, and could not be: the structure tensor is measured over a window a stroke and
         * a half across, and on real ground the aspect turns within that window — every ridge crest
         * and every valley floor is a place where two directions meet inside one window and the
         * answer is the average of them. What the bar has to separate is ink that follows the
         * ground from ink that ignores it, and those two are nowhere near each other: read on the
         * true-shape sheet, where a stroke running along a column is as wide as a cell, the
         * engraving measures 25.5 degrees over 8419 windows and the fixed-bearing comb it replaced
         * 41.8, near what a bearing chosen at random scores against an aspect that is uniform. The
         * bar sits at 30, between the two. (Read cell for pixel on the square sheet that preceded it, the figures
         * were 20.0 and 47.0; docs/DESIGN_LEDGER.md, Fix A.)
         */
        const val MAX_MEAN_ASPECT_ERROR_DEGREES = 30.0

        /**
         * How far the stroke pitch may drift between resolutions, measured in pixels.
         *
         * Nought, near enough, and that is the point: every mark is a fixed count of pixels, so the
         * only thing that can move this is how the cone's own geometry falls across the grid.
         * Measured at 0.7% across 512, 1024, 2048 and 4096 — 8.96, 9.02, 9.02 and 9.03 pixels —
         * and the bar is 5%. The control, the same drawing enlarged with the sheet, goes 8.96,
         * 17.69, 35.18, 70.13: out by a factor of nearly eight over the same range.
         */
        const val MAX_PITCH_DRIFT = 0.05

        /**
         * How far the stroke *count* may fall from the square of the grid ratio.
         *
         * The other half of the same property, and the half a reader sees: a pitch fixed in pixels
         * over a grid four times finer puts sixteen strokes where there was one, so the larger plate
         * carries more of the country at the same weight of line. Asserted rather than assumed,
         * because a mark that quietly kept a share of the width would hold the count instead and
         * this is what would catch it. Measured at 3.97, 15.90 and 63.55 times the 512 count for
         * 1024, 2048 and 4096, against four, sixteen and sixty-four. Bar 8%, which is loose enough
         * for the run-counting to miss the odd stroke where two nearly touch and tight enough that
         * a factor of four or of one fails outright.
         */
        const val MAX_DENSITY_DRIFT = 0.08

        /**
         * Every style's 512 fantasy render, hashed, as it stands today.
         *
         * A change detector rather than a claim about any particular colour. What it is good for
         * is the *shape* of a change: a chunk meaning to redraw one style can check here that it
         * redrew only that one, and a chunk that moves the land under all eleven — a new sky, a
         * climate that reaches the land ramp, a shoreline cut a different way — shows up as all
         * eleven moving together. One entry out of step with the rest is the thing to look at.
         *
         * A chunk that means to move them all re-records the lot and says so in its report; how
         * far a coastline actually moved is measured rather than hashed, in `LittoralCoastTest`
         * and in `GEOGRAPHY.md`. See docs/DESIGN_LEDGER.md, F9, F13, F14 and F17, for which chunk moved
         * what, and the 2.0.3 forward-merge row for the re-recording before this one.
         *
         * Re-recorded again for W1, which is the plainest case there is of the world moving under
         * all of them rather than one style being redrawn: the temperature is solved by an energy
         * balance now instead of drawn from a curve, so every biome the tints are read off comes
         * from a different climate. Every style moved, which is what this guard is for.
         *
         * And there are twelve of them now, because the 2.0.x line brought Natural. A new style is
         * a row of levers the raster already reads, so a chunk that adds one and moves an existing
         * hash has reached outside its own palette — and on the release line F23 moved none of the
         * eleven.
         *
         * The whole set is recorded once more on the merged tree, and neither side's numbers would
         * have done. The 2.0.x line moved the world twice over — the water is routed by the
         * steepest triangular facet rather than the steepest of eight neighbours, so twelve rounds
         * of erosion cut different rock; and the outlet notch no longer reads a sill lying level to
         * the water as having no gradient, so basins that had stood undrained for the life of the
         * world are opened and the coast around them is a different coast — and this line moved it
         * under all eleven with the energy balance. What moved is the world, not the drawing:
         * nothing in this file's own arithmetic changed on either side. How far the rivers actually
         * moved is measured rather than hashed, in `StraightRunAuditTest`.
         *
         * One thing in the drawing did move with them, and it is a consequence of the same
         * ground: [ReliefShading.ORDINARY_GROUND] is the median illumination over a fixed seed's
         * land, so a world cut differently measures a different median, and it was re-derived from
         * 0.936 to 0.9318 in the same commit. Pen and ink is the one style these records show
         * untouched by that, because it has no tint for the shading to multiply.
         *
         * And once more at S2's fourth pass, for the same two reasons at once. The crust has a
         * thickness that rises inland and the base relief a texture proportional to the ground's
         * own relief, so the world under all twelve moved again; and ordinary country came out
         * smoother, so [ReliefShading.ORDINARY_GROUND] was re-derived from 0.9318 to 0.9582 in the
         * same commit and every tinted style moved with it. Pen and ink is again the one whose
         * record does not move at all — `-588733464` before and after — which is the cleanest
         * evidence there is that what moved is the shading and the ground and not the drawing.
         *
         * And at S2b and I2 together, which is why neither side's numbers stand here. S2b measures
         * the craton's reach in kilometres instead of cells, so a continent's crust thickens over
         * twice the distance north-south that it did; it stops the flexure letting one pole bend
         * the other's bed; and it lets the depression flood reach land standing below the water
         * beside it. I2 shares the ice's cut by how deeply a cell lies under the ice as well as by
         * how far across it lies, so every glaciated valley is cut to a different profile. Each
         * side recorded twelve figures against its own world and the merged world is neither, so
         * all twelve are re-taken once more on this tree. `ReliefShading.ORDINARY_GROUND` did
         * *not* move with them — `ReliefShadingTest` re-derives it from the median illumination
         * over this same world's land and still finds 0.9582 — and pen and ink's record moved
         * anyway, which is the other half of the same evidence: the one style with no tint for the
         * shading to multiply moves when, and only when, the ground does.
         *
         * And at I1, which is the plainest instance of the world moving under all twelve that this
         * comment has yet recorded: the ice sheet is a body with a surface now, and that surface is
         * written into the elevation field, so every world with ice on it stands two to four
         * kilometres higher where the ice is and the renderer shades, tints and contours the top of
         * the ice instead of the rock beneath it. [ReliefShading.ORDINARY_GROUND] moved with it,
         * from 0.9582 to 0.9421, for the reason that constant's own comment gives — a dome with a
         * kilometre-high flank round it is steeper country than the bed was — and pen and ink moved
         * too, which it does when and only when the ground does. Nothing in this file's own
         * arithmetic changed.
         *
         * And once more inside I1, for the margin: the profile is the mean of the plastic curve
         * over a cell now rather than its value at the cell's middle, and a body of ice too small
         * to be a sheet gets no profile at all, so every sheet's edge stands a few hundred metres
         * lower and the small caps are off the map altogether. All twelve moved together again.
         * `ReliefShading.ORDINARY_GROUND` did *not* move with them this time - the median
         * illumination over seed 234475's land reads 0.944 against the declared 0.9421, inside
         * what `ReliefShadingTest` allows - because what the margin changed is the edge of the
         * ice and not the slope of ordinary country.
         *
         * W2 moved all twelve again. The pressure wind changed the climate every style tints by,
         * and it reaches the *provisional* climate the glaciation stage carves from as well, so a
         * change to the air moved a little of the ground with it. Re-taken from this test own
         * PENINK fingerprints at 512 line, and taken only after the climate guards were settled:
         * a fingerprint recorded while a climate is still moving records nothing.
         *
         * And on the merge of I1 and W2, which is why neither side's numbers stand here. Each
         * recorded twelve figures against its own world - I1's ice standing in the elevation
         * field, W2's pressure wind reaching the provisional climate the glaciation stage carves
         * from - and the merged world is neither, so all twelve are re-taken once more on this
         * tree. `ReliefShading.ORDINARY_GROUND` is re-derived with them, from 0.9421, because
         * both sides moved the land the median illumination is measured over.
         *
         * W4 moved **ten of the twelve**, and which two stood still is the record's own check on
         * itself. The canopy the ground is darkened under stopped being one figure per biome and
         * became the cell's own vegetation density, so every style whose `climateTint` is above
         * zero draws different ground; `PEN_AND_INK` and `CLEAR` hold that lever at zero and their
         * fingerprints are unchanged to the bit, 198761330 and -383169912, the same two numbers
         * they carried before this chunk. Nothing else moved: W4 leaves the world itself alone -
         * the biome, elevation and every climate field are identical - so `ORDINARY_GROUND` is not
         * re-derived with them this time, because the land the median illumination is measured
         * over did not move at all. Re-taken last, after the two module suites had settled.
         *
         * F30b moved all twelve. Water crossing a flat the depression fill raised follows a
         * potential rather than the flood's staircase, in every routing pass a world makes, so the
         * incision cut different ground in every hydraulic round and the world this test draws is
         * a different world by a little everywhere a flat lies: `DepositionTest`'s land count at
         * 128 moved by three cells. Every style draws that land, `PEN_AND_INK` and `CLEAR`
         * included this time, since what moved is the ground and not the tint.
         * `ReliefShading.ORDINARY_GROUND` is *not* re-derived: `ReliefShadingTest` measures the
         * median illumination over seed 234475's land on this tree and still finds the recorded
         * figure, because a river's course across a flat moved the land by cells here and there
         * and not the roughness of ordinary country. Re-taken last, after the two module suites
         * had settled.
         *
         * I3 moved all twelve, and it moved the ice rather than the tint: every world with a sheet
         * on it stands at a different height where the ice is. The sheet's surface is the lower
         * envelope of the plastic profiles rising from its whole margin instead of the profile
         * rising from the nearest margin cell, so the steps of up to 1,965 m a nearest-cell datum
         * left between one ice cell and the next are gone and the dome is one surface; and the
         * frozen mask has a floor under it at the arithmetic's own precision, so the comb of ice
         * walls a cell wide and four hundred metres tall that the bare sign of a cancelled
         * subtraction was growing over the polar desert is gone with it. The elevation field the
         * renderer shades, tints and contours is that surface, so `PEN_AND_INK` and `CLEAR` move
         * with the other ten. `ReliefShading.ORDINARY_GROUND` is re-derived with them, from 0.9473
         * to 0.9526, and it is the first re-derivation in this comment that makes the ground
         * *smoother*: both of those artefacts were sheer faces in the elevation field and both
         * were catching a shadow. Re-taken last, after the two module suites had settled.
         *
         * S3 moved all twelve, and it is the plainest case of the ground moving there has been
         * since W1: the hydraulic rounds weight their flow accumulation by a provisional rainfall
         * and hold their incision back by the plant cover, so every valley on every world is cut
         * by a different amount and the elevation field the renderer shades is a different field
         * everywhere. `ReliefShading.ORDINARY_GROUND` is *not* re-derived, and it is worth saying
         * why it did not need to be, because it very nearly did. The cover's multiplier is spent
         * relative to its own mean over the land, so the world loses as much rock as it always
         * did and only the places it comes off move; built the other way first, as an absolute
         * multiplier, it took a third of the erosion away and the median land cell came out
         * measurably less shaded, at 0.9566. With the relative form the median is back inside the
         * tolerance of the recorded 0.9526 and the datum stands. Re-taken last, after the two
         * module suites had settled.
         *
         * S3 moved them a second time when it met R1 on the merged tree, and three things did it
         * at once. R1 draws a watercourse where the ground can cut one rather than where a share
         * of the world's runoff passes a bar, so the ink on the map is a different network on
         * every style that draws rivers. The runoff floor every stage reads was corrected from a
         * hundred and fifty millimetres a year to sixty — the first was read off a rainfall scale
         * that is not the one the climate normalises against — which thins the channel network in
         * dry country. And `TectonicsConfig.collisionUpliftMmPerYear` was re-derived from the
         * denudation the climate-fed rounds leave, 0.77 to 0.718 mm/yr, which moves the height of
         * every active belt on every world. Again `ReliefShading.ORDINARY_GROUND` is not
         * re-derived: `ReliefShadingTest` re-measures the median illumination over seed 234475's
         * land on this tree and still finds the recorded figure, because a gentler uplift and a
         * different set of drawn rivers move the belts and the valleys rather than the roughness
         * of ordinary country. Re-taken last, after the two module suites had settled.
         *
         * Fix 2 moved all twelve twice. First the ground: every operator that shaped it was put on
         * the ground's ruler, so every continent is new, and the relief was drawn for the ground.
         * Then the light: the sky's horizon had read the ground half as steep as the lamps did, and
         * reads the one exaggeration now, so ordinary country sees less of the sky and
         * `ReliefShading.ORDINARY_GROUND` is re-derived with it, 0.9526 to 0.9362 to 0.9225
         * (docs/DESIGN_LEDGER.md, Fix 2). Re-taken last each time.
         */
        /** The twelve records live in [RecordedRenders], with the one script that re-takes them. */
        val RECORDED_STYLES: Map<MapStyle, Int> get() = RecordedRenders.STYLES_AT_512

        /** The gallery's world, at the size the guards measure on. See [TestWorlds]. */
        val WORLD: WorldMap get() = TestWorlds.gallery

        /**
         * The grid the plan is built at, the pen's own reference size, and the band of the cone
         * the stroke scan measures over.
         *
         * The band avoids the summit, where every bearing meets and the strokes cross, and the
         * foot, where the flank runs out below the slope floor and there is no ink to count.
         */
        const val REFERENCE_SIDE = 512
        const val SCANNED_BAND_FROM = 0.12f
        const val SCANNED_BAND_TO = 0.45f

        /** Where a pixel counts as inked, on the 0-to-1 scale [Engraving.hachure] returns. */
        const val INKED = 0.5f

        /** How far the structure tensor looks, in stroke pitches. */
        const val TENSOR_WINDOW_PITCHES = 1.5f

        /** Only ground with real ink on it is asked about: below this the paper is meant to be blank. */
        const val MEASURED_SLOPE_FLOOR = 0.14f

        /**
         * The known failure the aspect clause records since Fix 3b. On the implicit incision's
         * terrain, whose ground is cut into finer valleys, the engraved ink runs 31.8 to 31.9
         * degrees from the aspect on average over the windows the clause reads, past its 30; the
         * comb it replaced is still further off. Whether the stroke's reach or the terrain's
         * shorter slopes carry the two degrees is not diagnosed (docs/DESIGN_LEDGER.md, Fix 3b).
         */
        const val INK_OFF_THE_FALL_LINE_ON_THE_LAWS_TERRAIN =
            "the ink: on the law's terrain the engraved stroke runs further off the fall line than its bar"

        /** The known failure the ink gain's derivation guard records, by the audit finding. */
        const val INK_GAIN_STALE =
            "Audit III F-I9: Pen and ink's widest stroke is not at the seventy-fifth percentile it was set at"

        /** A quarter turn: the angle from the strokes to the steepest change in the picture. */
        val QUARTER_TURN = Math.PI / 2

        /** Every second pixel each way: four times fewer windows, and the mean does not move. */
        const val SAMPLE_STEP = 2

        /** Where two neighbouring strokes stop reading as one stroke and start reading as a break. */
        val SEAM_RADIANS = Math.toRadians(60.0)

        /**
         * The fixed-bearing hatch exactly as it shipped, so the control is the control that
         * shipped: the north-west lamp at 32 degrees, its ambient and swing, its clamp, and a
         * diagonal comb of five cells stepped in sixths. See [fixedBearingHatch].
         */
        const val OLD_INK_GAIN = 1.15f
        const val OLD_LAMP_EAST = -0.6f
        const val OLD_LAMP_SOUTH = -0.6f
        const val OLD_LAMP_HEIGHT = 0.53f
        const val OLD_LAMP_AMBIENT = 0.72f
        const val OLD_LAMP_SWING = 0.55f
        const val OLD_DARKEST = 0.45f
        const val OLD_BRIGHTEST = 1.35f
        const val OLD_COMB_PITCH = 5
        const val OLD_COMB_STEPS = 6f
    }

    @Test
    fun `every style renders the pixels it is recorded as rendering`() {
        val drawn = MapStyle.entries.associateWith {
            fingerprint(MapRasterizer.rasterize(WORLD, RenderOptions(style = it)))
        }
        println(
            "PENINK fingerprints at 512: " +
                drawn.entries.joinToString(", ") { "${it.key.name} ${it.value}" }
        )
        // The property beside the records: no two styles collapse onto one drawing. A style
        // whose levers all read as another's would pass its record only by coincidence and
        // fail this outright.
        val collapsed = drawn.entries.groupBy { it.value }.filter { it.value.size > 1 }
        assertTrue(
            collapsed.isEmpty(),
            "styles rendering identical pixels: " + collapsed.values.joinToString { group -> group.joinToString("/") { it.key.name } }
        )
        RECORDED_STYLES.forEach { (style, expected) ->
            assertEquals(
                expected,
                drawn[style],
                "${style.label} no longer renders the pixels recorded for it"
            )
        }
    }

    @Test
    fun `the ink runs down the slope, and the fixed-bearing hatch did not`() {
        val world = WORLD
        val plan = EngravingPlan(SheetGeometry.of(world))

        val engraved = MapRasterizer.rasterize(
            world,
            RenderOptions(style = MapStyle.PEN_AND_INK, showCoastline = false, showLakes = false)
        )
        val comb = fixedBearingHatch(world)

        val engravedError = meanAspectError(world, engraved, plan)
        val combError = meanAspectError(world, comb, plan)

        println(
            "PENINK aspect error over %d windows: engraved %.1f degrees, the comb it replaced %.1f"
                .format(engravedError.windows, engravedError.meanDegrees, combError.meanDegrees)
        )
        println("PENINK %s".format(slopeReport(world, plan)))
        println("PENINK %s".format(seamReport(world, plan)))

        assertTrue(
            engravedError.windows > 3000,
            "only ${engravedError.windows} windows qualified; the measurement says nothing"
        )
        // Recorded since Fix 3b: see [INK_OFF_THE_FALL_LINE_ON_THE_LAWS_TERRAIN].
        KnownFailures.expect(INK_OFF_THE_FALL_LINE_ON_THE_LAWS_TERRAIN, "past 30.0 degrees") {
            if (engravedError.meanDegrees > MAX_MEAN_ASPECT_ERROR_DEGREES) {
                throw RecordedViolation(
                    "the ink runs %.1f degrees from the aspect on average, past %.1f"
                        .format(engravedError.meanDegrees, MAX_MEAN_ASPECT_ERROR_DEGREES),
                    "past %.1f degrees".format(MAX_MEAN_ASPECT_ERROR_DEGREES)
                )
            }
        }
        assertTrue(
            combError.meanDegrees > MAX_MEAN_ASPECT_ERROR_DEGREES,
            "the control passed, so the measurement cannot tell the two apart"
        )
    }

    @Test
    fun `the pen is the same size at every resolution, and lays more strokes on a bigger plate`() {
        val sizes = listOf(REFERENCE_SIDE, 1024, 2048, 4096)
        val pen = sizes.associateWith { strokeScan(it, enlarged = false) }
        val enlarged = sizes.associateWith { strokeScan(it, enlarged = true) }

        println(
            "PENINK stroke pitch in pixels (and strokes over the same share of the map) — the pen " +
                sizes.joinToString(", ") { "$it: %.2f (%d)".format(pen[it]!!.pixelPitch, pen[it]!!.runs) }
        )
        println(
            "PENINK stroke pitch in pixels (and strokes over the same share of the map) — enlarged " +
                "with the sheet " +
                sizes.joinToString(", ") {
                    "$it: %.2f (%d)".format(enlarged[it]!!.pixelPitch, enlarged[it]!!.runs)
                }
        )

        val drift = pen.values.maxOf { it.pixelPitch } / pen.values.minOf { it.pixelPitch } - 1.0
        println("PENINK the pen's pitch drifts %.1f%% across 512..4096".format(drift * 100))
        assertTrue(
            drift <= MAX_PITCH_DRIFT,
            "the stroke pitch drifts %.1f%% in pixels across 512..4096, past %.1f%%"
                .format(drift * 100, MAX_PITCH_DRIFT * 100)
        )

        // The other half of the same property, and the one a reader sees: a fixed pitch in pixels
        // over a grid n times finer means n squared times as many strokes on the same ground.
        sizes.filter { it != REFERENCE_SIDE }.forEach { side ->
            val ratio = pen[side]!!.runs.toDouble() / pen[REFERENCE_SIDE]!!.runs
            val expected =
                (side.toDouble() / REFERENCE_SIDE) * (side.toDouble() / REFERENCE_SIDE)
            println(
                "PENINK $side lays %.2f times as many strokes as 512 over the same ground, against %.0f"
                    .format(ratio, expected)
            )
            assertTrue(
                abs(ratio / expected - 1.0) <= MAX_DENSITY_DRIFT,
                ("$side lays %.2f times as many strokes as 512 over the same ground, not %.0f — " +
                    "the marks are not a fixed size in pixels").format(ratio, expected)
            )
        }

        val enlargedDrift =
            enlarged.values.maxOf { it.pixelPitch } / enlarged.values.minOf { it.pixelPitch } - 1.0
        assertTrue(
            enlargedDrift > MAX_PITCH_DRIFT,
            "the control passed, so the measurement cannot tell a pen from a magnifying glass"
        )
    }

    // ---- measurements ----

    /** The ordinary 17-and-31 hash over every pixel. Order matters, which is the point. */
    internal fun fingerprint(pixels: IntArray): Int {
        var hash = 17
        for (pixel in pixels) hash = hash * 31 + pixel
        return hash
    }

    private class AspectError(val meanDegrees: Double, val windows: Int)

    /**
     * How far the direction the ink actually runs in sits from the aspect, over the whole land.
     *
     * The direction of the ink is recovered from the picture rather than asked of the code: the
     * structure tensor of the image gradient over a window a stroke pitch and a half across has its
     * principal axis along the *steepest change* in the picture, which for a field of parallel
     * strokes is across them. So the strokes run a quarter turn from that axis, and the question is
     * how far that is from the aspect. Orientation is modulo half a turn — a stroke has no head or
     * tail — so the error folds into 0 to 90 degrees.
     *
     * Read on the true-shape sheet the raster is copied out to ([SheetGeometry.expand]), which is
     * where the reader sees the strokes and where a pixel is the same ground both ways, so the
     * aspect the ink is held to is the ground's own fall line.
     */
    private fun meanAspectError(
        world: WorldMap,
        pixels: IntArray,
        plan: EngravingPlan
    ): AspectError {
        val width = world.width
        val height = world.height
        val geometry = SheetGeometry.of(world)
        val sheet = geometry.expand(pixels)
        val sheetWidth = geometry.widthPixels
        val land = world.sea.isLand
        val elevation = world.sea.relativeElevation
        val reachColumns = plan.gradientStencilColumns
        val reachRows = plan.gradientStencilRows
        val window = (plan.hachureLatticePixels * TENSOR_WINDOW_PITCHES).toInt().coerceAtLeast(2)
        // The window in cells, each way, rounded up: what has to be land under it.
        val windowColumns = window / geometry.pixelsPerCellAcross + 1
        val windowRows = window / geometry.pixelsPerCellDown + 1

        var total = 0.0
        var windows = 0
        var row = windowRows + reachRows
        while (row < height - windowRows - reachRows) {
            var column = windowColumns + reachColumns
            while (column < width - windowColumns - reachColumns) {
                val gradientX =
                    (elevation.sample(column + reachColumns, row) -
                        elevation.sample(column - reachColumns, row)) * plan.gradientScaleAcross
                val gradientY =
                    (elevation.sample(column, row + reachRows) -
                        elevation.sample(column, row - reachRows)) * plan.gradientScaleDown
                val slope = groundSlope(gradientX, gradientY, world)
                if (slope >= MEASURED_SLOPE_FLOOR &&
                    allLand(land, width, column, row, windowColumns, windowRows)
                ) {
                    val tensor = structureTensor(
                        sheet, sheetWidth, plan.sheetX(column), plan.sheetY(row), window
                    )
                    if (tensor != null) {
                        // The picture's steepest change runs across the strokes; the strokes run a
                        // quarter turn from it, and the ground's fall line is what they should be
                        // along.
                        val inkDirection = tensor + QUARTER_TURN
                        total += foldedDifference(inkDirection, groundFallLine(gradientX, gradientY, world))
                        windows++
                    }
                }
                column += SAMPLE_STEP
            }
            row += SAMPLE_STEP
        }
        return AspectError(
            if (windows == 0) 0.0 else Math.toDegrees(total / windows),
            windows
        )
    }

    private fun allLand(
        land: BooleanArray,
        width: Int,
        x: Int,
        y: Int,
        windowColumns: Int,
        windowRows: Int
    ): Boolean {
        for (offsetRow in -windowRows..windowRows) {
            val rowStart = (y + offsetRow) * width
            for (offsetColumn in -windowColumns..windowColumns) {
                if (!land[rowStart + x + offsetColumn]) return false
            }
        }
        return true
    }

    /** The principal axis of the image gradient's structure tensor, or null on flat ink. */
    private fun structureTensor(
        pixels: IntArray,
        width: Int,
        x: Int,
        y: Int,
        window: Int
    ): Double? {
        var eastEast = 0.0
        var southSouth = 0.0
        var eastSouth = 0.0
        for (offsetRow in -window..window) {
            for (offsetColumn in -window..window) {
                val cell = (y + offsetRow) * width + x + offsetColumn
                val eastward = (luminance(pixels[cell + 1]) - luminance(pixels[cell - 1])).toDouble()
                val southward =
                    (luminance(pixels[cell + width]) - luminance(pixels[cell - width])).toDouble()
                eastEast += eastward * eastward
                southSouth += southward * southward
                eastSouth += eastward * southward
            }
        }
        // Flat ink: no gradient anywhere in the window, so there is no direction to report.
        if (eastEast + southSouth < 1.0) return null
        return 0.5 * atan2(2.0 * eastSouth, eastEast - southSouth)
    }

    private fun luminance(argb: Int): Int =
        ((argb shr 16 and 0xFF) + (argb shr 8 and 0xFF) + (argb and 0xFF)) / 3

    /** The angle between two orientations, in radians, folded into 0..pi/2. */
    private fun foldedDifference(a: Double, b: Double): Double {
        // Doubling the angles makes two orientations a half turn apart the same direction, which is
        // what an undirected stroke is; halving the answer brings it back into 0..pi/2.
        val doubled = 2 * (a - b)
        return abs(atan2(sin(doubled), cos(doubled))) / 2
    }

    /**
     * The hatch this style used to draw, reproduced so the guards have something to fail against.
     *
     * A diagonal comb at one bearing with a five-cell period, thresholded against the hillshade —
     * `MapStyle.inked` and the line-art branch of `MapRasterizer` as they stood at 2.0.0. The
     * lamp and the ramp are written out rather than called, so that what this test fails against
     * is a control it owns and cannot lose to a change in [ReliefShading].
     */
    private fun fixedBearingHatch(world: WorldMap): IntArray {
        val width = world.width
        val height = world.height
        val elevation = world.sea.relativeElevation
        val style = MapStyle.PEN_AND_INK
        val slopeScale = ReliefShading.slopeScale(width)
        val pixels = IntArray(width * height) { style.paper }
        for (row in 0 until height) {
            for (column in 0 until width) {
                val cell = row * width + column
                if (!world.sea.isLand[cell]) continue
                val eastward =
                    (elevation.sample(column + 1, row) -
                        elevation.sample(column - 1, row)) * slopeScale
                val southward =
                    (elevation.sample(column, row + 1) -
                        elevation.sample(column, row - 1)) * slopeScale
                val normalLength = sqrt(eastward * eastward + southward * southward + 1f)
                val lambert =
                    (-eastward * OLD_LAMP_EAST - southward * OLD_LAMP_SOUTH + OLD_LAMP_HEIGHT) /
                        normalLength
                val shade = (OLD_LAMP_AMBIENT + OLD_LAMP_SWING * lambert)
                    .coerceIn(OLD_DARKEST, OLD_BRIGHTEST)
                val steepness = (1f - shade).coerceAtLeast(0f)
                val comb = (((column + row) % OLD_COMB_PITCH) + 1) / OLD_COMB_STEPS
                if (steepness * OLD_INK_GAIN > comb) pixels[cell] = style.coastline
            }
        }
        return pixels
    }

    /** What share of the land the aspect turns hard enough on to break a stroke. */
    private fun seamReport(world: WorldMap, plan: EngravingPlan): String {
        val width = world.width
        val height = world.height
        val elevation = world.sea.relativeElevation
        var measured = 0
        var seams = 0
        for (row in 1 until height - 1) {
            for (column in 1 until width - 1) {
                val cell = row * width + column
                if (!world.sea.isLand[cell]) continue
                val here = aspectOrNull(world, elevation, column, row, plan) ?: continue
                measured++
                val east = aspectOrNull(world, elevation, column + 1, row, plan)
                val south = aspectOrNull(world, elevation, column, row + 1, plan)
                val turned = (east != null && foldedDifference(here, east) > SEAM_RADIANS) ||
                    (south != null && foldedDifference(here, south) > SEAM_RADIANS)
                if (turned) seams++
            }
        }
        return "the aspect turns more than %d degrees between neighbours on %.2f%% of the %d inked cells"
            .format(
                Math.toDegrees(SEAM_RADIANS).toInt(),
                seams * 100.0 / measured.coerceAtLeast(1),
                measured
            )
    }

    private fun aspectOrNull(
        world: WorldMap,
        elevation: com.cartogenesis.worldgen.model.FloatField,
        x: Int,
        y: Int,
        plan: EngravingPlan
    ): Double? {
        val reachColumns = plan.gradientStencilColumns
        val reachRows = plan.gradientStencilRows
        val gradientX = (elevation.sample(x + reachColumns, y) - elevation.sample(x - reachColumns, y)) *
            plan.gradientScaleAcross
        val gradientY = (elevation.sample(x, y + reachRows) - elevation.sample(x, y - reachRows)) *
            plan.gradientScaleDown
        val slope = groundSlope(gradientX, gradientY, world)
        if (slope < MEASURED_SLOPE_FLOOR) return null
        return groundFallLine(gradientX, gradientY, world)
    }

    /**
     * The ground's steepness from a hachure's two differences, as `Engraving.hachure` reads it: the
     * difference down a column is over rows, a share of a cell width each.
     */
    private fun groundSlope(gradientX: Float, gradientY: Float, world: WorldMap): Float {
        val southward = gradientY / world.config.cellHeightInCellWidths.toFloat()
        return sqrt(gradientX * gradientX + southward * southward)
    }

    /**
     * The ground's fall line, the bearing a hachure runs along, in radians: on the true-shape
     * sheet the bearing on the ground is the bearing as drawn.
     */
    private fun groundFallLine(gradientX: Float, gradientY: Float, world: WorldMap): Double {
        val rowScale = world.config.cellHeightInCellWidths
        return atan2(gradientY / rowScale, gradientX.toDouble())
    }

    /**
     * The floor below which the ground is left blank is "the tenth percentile of the land slope of
     * seed 234475 measured at this stencil" ([EngravingPlan.SLOPE_FLOOR]). Held to it at the digit
     * it is stated to: the percentile of the gallery world, which is that seed, rounds to the
     * floor's hundredth. It ran as a known failure while the world stood moved from the floor
     * (Audit III, F-I9), the percentile at 0.05, and is asserted again since the slope is read on
     * the ground, where a hachure now reads it: the tenth percentile is 0.065 there
     * (docs/DESIGN_LEDGER.md, Fix 2).
     */
    @Test
    fun `the slope floor is the tenth percentile of the land it was read off`() {
        val slopes = landSlopes(WORLD, EngravingPlan(SheetGeometry.of(WORLD)))
        val tenth = hundredths(percentile(slopes, 0.10))
        val floor = hundredths(EngravingPlan.SLOPE_FLOOR)
        println("PENINK the tenth percentile of the land slope rounds to $tenth; the floor is $floor")
        assertEquals(
            floor, tenth,
            "the tenth percentile of seed 234475's land slope is ${percentile(slopes, 0.10)}; the floor is ${EngravingPlan.SLOPE_FLOOR}"
        )
    }

    /**
     * Pen and ink's gain puts a stroke at its widest at "a slope of 0.40, which is the
     * seventy-fifth percentile of this world's land" (the style's own comment): the floor plus one
     * over the gain. Held to it at the hundredth the 0.40 is stated to. Stale on the moved world
     * (Audit III, F-I9), and recorded by where the percentile rounds: read on the ground, as a
     * hachure now reads a slope, it stands past the widest stroke rather than short of it.
     */
    @Test
    fun `the ink gain puts the widest stroke at the seventy-fifth percentile of the land`() {
        val slopes = landSlopes(WORLD, EngravingPlan(SheetGeometry.of(WORLD)))
        val seventyFifth = hundredths(percentile(slopes, 0.75))
        val widest = hundredths(EngravingPlan.SLOPE_FLOOR + 1f / MapStyle.PEN_AND_INK.inkGain)
        println("PENINK the seventy-fifth percentile of the land slope rounds to $seventyFifth; the widest stroke is at $widest")
        KnownFailures.expect(INK_GAIN_STALE, "the seventy-fifth percentile rounds to 0.53, the widest stroke is at 0.41") {
            if (seventyFifth != widest) {
                throw RecordedViolation(
                    "the seventy-fifth percentile of seed 234475's land slope is ${percentile(slopes, 0.75)}; " +
                        "the widest stroke is at ${EngravingPlan.SLOPE_FLOOR + 1f / MapStyle.PEN_AND_INK.inkGain}",
                    "the seventy-fifth percentile rounds to $seventyFifth, the widest stroke is at $widest"
                )
            }
        }
    }

    /** [value] rounded to the hundredth and written out, for comparing figures at that digit. */
    private fun hundredths(value: Float): String = String.format(Locale.ROOT, "%.2f", value)

    /** The land slopes in ascending order, read the way the raster reads them for a hachure: on the ground. */
    private fun landSlopes(world: WorldMap, plan: EngravingPlan): List<Float> {
        val width = world.width
        val elevation = world.sea.relativeElevation
        val reachColumns = plan.gradientStencilColumns
        val reachRows = plan.gradientStencilRows
        val slopes = ArrayList<Float>()
        for (cell in world.sea.isLand.indices) {
            if (!world.sea.isLand[cell]) continue
            val column = cell % width
            val row = cell / width
            val gradientX =
                (elevation.sample(column + reachColumns, row) -
                    elevation.sample(column - reachColumns, row)) * plan.gradientScaleAcross
            val gradientY =
                (elevation.sample(column, row + reachRows) -
                    elevation.sample(column, row - reachRows)) * plan.gradientScaleDown
            slopes.add(groundSlope(gradientX, gradientY, world))
        }
        slopes.sort()
        return slopes
    }

    private fun percentile(sorted: List<Float>, fraction: Double): Float =
        sorted[(sorted.size * fraction).toInt().coerceAtMost(sorted.size - 1)]

    /** Where the land's slopes actually sit, which is what the ink gain and the floor are set from. */
    private fun slopeReport(world: WorldMap, plan: EngravingPlan): String {
        val slopes = landSlopes(world, plan)
        fun at(fraction: Double) = percentile(slopes, fraction)
        val gain = MapStyle.PEN_AND_INK.inkGain
        return ("land slope over %d cells: 10th %.3f, median %.3f, 75th %.3f, 90th %.3f, 99th %.3f " +
            "(blank below %.2f, fully black at %.2f, widest stroke at %.2f)").format(
            slopes.size, at(0.10), at(0.50), at(0.75), at(0.90), at(0.99),
            EngravingPlan.SLOPE_FLOOR,
            EngravingPlan.SLOPE_FLOOR + EngravingPlan.FULL_INK_AT_STEEPNESS / gain,
            EngravingPlan.SLOPE_FLOOR + 1f / gain
        )
    }

    /** What one scan of the cone found: how far apart the marks are, and how many there were. */
    private class StrokeScan(val pixelPitch: Double, val runs: Long)

    /**
     * A sheet [side] pixels square, a cell to a pixel: the cone below is laid out on the sheet
     * directly, so its pixels are its cells and the same ground either way.
     */
    private fun squarePixelSheet(side: Int): SheetGeometry =
        SheetGeometry.cellForPixel(side, side, WorldScale().worldWidthKm / (2 * side))

    /**
     * How far apart the marks are in pixels, and how many of them fall on the same share of the map.
     *
     * Measured on a cone rather than on a world, because a cone is the same shape at every
     * resolution: the ground is identical at 512 and at 4096, so anything that differs between the
     * two answers belongs to the drawing. The cone also turns the aspect through every bearing, so
     * the measurement is not an artefact of one direction — a horizontal scan crosses the strokes
     * at every angle and what it counts is how often ink starts.
     *
     * [enlarged] is the control: the same drawing blown up with the sheet, which is what sizing a
     * mark as a share of the width does and what shipped first. It is the [REFERENCE_SIDE] plan's
     * ink read at map coordinates, so a stroke that is twelve pixels long at that size is
     * forty-eight at four times it — which is exactly the woodcut the review sent back.
     */
    private fun strokeScan(width: Int, enlarged: Boolean): StrokeScan {
        val plan = EngravingPlan(squarePixelSheet(if (enlarged) REFERENCE_SIDE else width))
        val gain = MapStyle.PEN_AND_INK.inkGain
        // Half way up the ink ramp, so the strokes are neither hairlines nor a solid mass.
        val slope = EngravingPlan.SLOPE_FLOOR + 0.5f / gain
        val centre = width / 2f
        val innerRadius = width * SCANNED_BAND_FROM
        val outerRadius = width * SCANNED_BAND_TO

        var runs = 0L
        var scanned = 0L
        for (row in 0 until width) {
            var wasInk = false
            for (column in 0 until width) {
                val fromCentreX = column - centre
                val fromCentreY = row - centre
                val radius = sqrt(fromCentreX * fromCentreX + fromCentreY * fromCentreY)
                if (radius in innerRadius..outerRadius) {
                    // Under the control the same map position is read at the reference grid's
                    // coordinates, so the whole picture arrives magnified by the grid ratio.
                    val readX = if (enlarged) column * REFERENCE_SIDE / width else column
                    val readY = if (enlarged) row * REFERENCE_SIDE / width else row
                    // Square pixels of ground: the strokes' own geometry on the sheet is what this
                    // measures, and a row counts as a column.
                    val ink = Engraving.hachure(
                        readX, readY,
                        fromCentreX / radius * slope, fromCentreY / radius * slope, 1f, plan, gain
                    ) > INKED
                    scanned++
                    if (ink && !wasInk) runs++
                    wasInk = ink
                } else {
                    wasInk = false
                }
            }
        }
        return StrokeScan(scanned.toDouble() / runs.coerceAtLeast(1), runs)
    }

}
