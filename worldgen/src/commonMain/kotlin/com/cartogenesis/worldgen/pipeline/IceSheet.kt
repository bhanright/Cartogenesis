package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.math.JumpFloodDistance
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldScale
import kotlin.math.sqrt

/**
 * An ice sheet as a *body*: how thick it is, how high its surface stands, and which way that
 * surface falls.
 *
 * Until this existed the generator had a frozen *mask* and nothing else. A mask has no altitude,
 * so the ice made no climate of its own; it has no surface, so the ice had nowhere to flow from
 * except the bed's own drainage, which on a sheet lies a kilometre underneath and is not what
 * steers anything; and it has no weight beyond a constant, so the crust under it sank by a number
 * somebody chose. All three follow from a thickness, and a thickness follows from one equation.
 *
 * ### The profile
 *
 * Ice is a solid that deforms until the shear stress at its base reaches about the same figure
 * wherever you measure it — Cuffey and Paterson (*The Physics of Glaciers*, 4th edn, 2010, §8.5)
 * put the basal shear stress of the ice sheets at 50 to 150 kPa and take 100 kPa as the
 * representative value. A sheet spreading under its own weight until it everywhere reaches that
 * stress has the *perfectly plastic* profile of Nye (1951) and Vialov (1958):
 *
 * ```
 * H(x) = sqrt(2 * tau0 * x / (rho_ice * g))
 * ```
 *
 * where `x` is the distance from the margin. `H` is the height of the ice *above the elevation of
 * its own margin* — the equation solves for a surface, and over a flat bed the surface and the
 * thickness are the same thing, which is why the shape is usually quoted as a thickness. The
 * constant in front is not a taste: with
 * tau0 at 100 kPa, ice at 917 kg/m3 and g at 9.81 m/s2 it is
 * [metresPerRootKilometre] = `sqrt(2 * 1e5 * 1000 / (917 * 9.81))` = 149.1 metres per root
 * kilometre.
 *
 * Checked against the two sheets there are. Greenland's divide stands about 400 km from the
 * nearest margin, which the profile puts at 2,982 m against a measured maximum near 3,000
 * (Morlighem et al. 2017). Antarctica's is about 1,000 km in, which the profile puts at 4,715 m
 * against Bedmap2's measured maximum of 4,776 (Fretwell et al. 2013).
 *
 * It is asked of sheets only. A body of ice under [SMALLEST_SHEET_SQUARE_KM] is an ice cap by
 * glaciology's own definition and gets no profile at all — see that constant, and the render that
 * made the case for it.
 *
 * ### The surface, and why it is the terrain
 *
 * A sheet's surface is its own ground: Greenland's summit is cold because it is three kilometres
 * up, not because Greenland is further north than Ellesmere. So the surface — the dome, or the
 * bed where the bed stands through it — is written into the elevation field the pipeline reads,
 * the thickness being the difference between the two, and the climate stage's
 * existing lapse rate then makes the dome colder than its bed by exactly
 * [surfaceCoolingC]. Nothing in the climate had to change for that to be true, which is the test
 * that it is the right place to put it: an altitude is a property of the ground, and the top of a
 * kilometre of ice is where the air is.
 *
 * ### The flow
 *
 * Ice flows down the slope of *its own surface*. That is the one fact that separates a sheet from
 * a valley glacier, and it is why a sheet's scour is radial about its dome while a valley
 * glacier's follows the valley: under a sheet the bed's drainage network is irrelevant, and a
 * dome whose height is `sqrt(distance from the margin)` falls away from its own summit in every
 * direction at once. [flowReceivers] is the steepest descent of that surface, and
 * the scour that follows it comes out streamlined along it — the flutes and drumlin fields of the
 * Laurentide's bed — rather than as the isotropic blobs the mask alone could justify.
 *
 * ### Where it runs
 *
 * The profile and the surface flow are per-cell arithmetic over two fields, which is rule 8's
 * case, so both go through [IceSheetAccelerator] with this object's own code as the reference and
 * `IceSheetParityTest` measuring the two against each other.
 */
object IceSheet {

    /**
     * The basal shear stress an ice sheet spreads until it reaches, in pascals.
     *
     * Cuffey and Paterson, 4th edn, §8.5: 50-150 kPa across the sheets and the outlet glaciers,
     * with 100 kPa the representative figure. It is the only free constant in the profile and it
     * is an observed one.
     */
    const val BASAL_SHEAR_STRESS_PASCALS = 100_000f

    /**
     * The thickest ice measured on Earth, in metres: Bedmap2's deepest sounding in the Astrolabe
     * Subglacial Basin (Fretwell et al. 2013). The envelope guard's ceiling, and nothing a
     * generated sheet may pass.
     */
    const val THICKEST_ICE_ON_EARTH_METRES = 4_776f

    /**
     * How thick a sheet stands one kilometre in from its margin, in metres — the whole constant of
     * the profile, so a reader can check it against the arithmetic in the class comment.
     *
     * `sqrt(2 * tau0 * 1000 m / (rho_ice * g))` with the caller's own ice density and gravity, so
     * the figure moves when the planet's do rather than being written down twice.
     */
    fun metresPerRootKilometre(iceDensityKgPerM3: Float, gravityMPerS2: Float): Float =
        sqrt(
            2.0 * BASAL_SHEAR_STRESS_PASCALS * WorldScale.METRES_PER_KM /
                (iceDensityKgPerM3.toDouble() * gravityMPerS2)
        ).toFloat()

    /** Which margin each frozen cell's dome rises from, and how far off it is, in kilometres. */
    class Margin(val distanceKm: FloatArray, val nearestCell: IntArray)

    /**
     * Which margin cell each frozen cell's surface is measured from, and how far away it is in
     * kilometres — the margin whose profile reaches the cell *lowest*, which is not always the
     * nearest one.
     *
     * By [JumpFloodDistance] rather than a chamfer transform, so the contours are circles and the
     * margin the profile is measured from is the real one; and with the grid's own row scale, so
     * the answer is a length on the ground. Before S2b every distance field in this stage counted
     * cells, which on a map twice as wide as it is tall made a sheet twice as thick northward as
     * eastward for no reason but the grid.
     *
     * ### Why the lowest and not the nearest
     *
     * The surface is the margin's own elevation plus the profile ([surfaceMetres]), and until I3
     * that elevation was read off the *nearest* margin cell. A nearest-cell lookup is a piecewise
     * constant field: every cell of a sheet that shares one nearest margin reads exactly one
     * datum, and the boundary between two such regions is a Voronoi edge, across which the datum
     * steps by however much the ground at the two margin cells differs. On seed 878210 at 1024
     * that step reached 1,965 m between two neighbouring cells and averaged 40.9 m over every
     * east-west pair on the ice — as much as the dome's own fall across the same cell. What it
     * drew is the defect it was reported as: a sheet in flat facets with hard edges, and, where
     * the margin runs east and west so that each column takes a margin cell of its own, a flank
     * ruled in vertical stripes one cell wide.
     *
     * The plastic condition says which margin is the right one. Ice yields until
     * `|grad S| = tau0 / (rho g H)`, and the surface satisfying that with `S = z` along a margin
     * of varying height is the *lower envelope* of the profiles rising from every margin point:
     *
     * ```
     * S(x) = min over margin cells m of ( z_m + k * sqrt(distance from x to m) )
     * ```
     *
     * which is that equation's viscosity solution and not a smoothing of anything. Differentiate
     * one branch: `|grad S| = k^2 / (2 (S - z_m))`, which with `k^2 = 2 tau0 / (rho g)` is the
     * yield condition itself. It is continuous wherever the branches are, a minimum of continuous
     * functions being continuous; where two branches meet the surface has a crease rather than a
     * step, and a crease between two margins is an ice divide, which is what that ground really
     * carries. Over a level margin every branch shares one datum and the envelope is exactly the
     * nearest-margin answer it replaces.
     *
     * So the flood is run over the plastic cost instead of over the distance: each cell takes the
     * margin that puts the lowest surface over it and reports how far off that margin is, which
     * [profileMetres] turns into the height above it. Its sources are the margin itself — ice-free
     * ground with ice against it — and not every ice-free cell, which is a distinction the plain
     * flood never had to make, the nearest ice-free cell being on the edge whatever else is seeded.
     * An envelope will rise a profile from anything it is offered, and seeded with the whole ocean
     * it hands a dome eight hundred kilometres of open water to start from: on the reported world
     * that took the thickest ice from 2,739 m to 1,730 and the continental clause with it. A weighted flood is not exact the way the
     * plain one is — see [JumpFloodDistance.run] — and what it can leave behind is a cell whose
     * surface is a little too high, which is why `IceSheetTest` measures the finished surface for
     * steps rather than taking the flood's word for it.
     */
    fun marginDistanceKm(
        config: WorldGenConfig,
        frozen: BooleanArray,
        /** The shoreline-relative ground the margins stand on. */
        bedRelative: FloatArray,
        /** What one unit of [bedRelative] is worth in metres. */
        metresPerFieldUnit: Float,
        metresPerRootKilometre: Float,
        cellSpanKm: Float
    ): Margin {
        val cellsAcross = config.width
        val cellsDown = config.height
        val cellCount = cellsAcross * cellsDown
        val distance = FloatArray(cellCount) { JumpFloodDistance.INFINITE }
        val nearest = IntArray(cellCount) { -1 }
        // The margin, and only the margin: ice-free ground with ice against it. The plain
        // nearest-margin flood could seed every ice-free cell, because the nearest one of those is
        // always on the edge anyway; an envelope cannot, because a profile is allowed to rise from
        // any source it is offered and the open ocean a thousand kilometres from the nearest ice
        // is not a place an ice sheet's surface starts. A margin is where the ice ends.
        for (cell in 0 until cellCount) {
            if (frozen[cell]) continue
            val column = cell % cellsAcross
            val row = cell / cellsAcross
            var touchesIce = false
            for (rowStep in -1..1) {
                val neighbourRow = row + rowStep
                if (neighbourRow < 0 || neighbourRow >= cellsDown) continue
                for (columnStep in -1..1) {
                    if (rowStep == 0 && columnStep == 0) continue
                    var neighbourColumn = (column + columnStep) % cellsAcross
                    if (neighbourColumn < 0) neighbourColumn += cellsAcross
                    if (frozen[neighbourRow * cellsAcross + neighbourColumn]) touchesIce = true
                }
            }
            if (touchesIce) {
                distance[cell] = 0f
                nearest[cell] = cell
            }
        }
        val kilometresPerCellWidth = config.cellWidthKm.toFloat()
        JumpFloodDistance.run(
            config.width, config.height, distance, nearest, config.cellHeightInCellWidths
        ) { source, squaredCellWidths ->
            // The surface this margin would put over the cell, in metres: the ground it stands on,
            // floored at the waterline as [surfaceMetres] floors it, plus the profile over the
            // distance. Compared as a height and not as a distance, which is the whole change.
            val km = sqrt(squaredCellWidths).toFloat() * kilometresPerCellWidth
            val datum = (bedRelative[source] * metresPerFieldUnit).coerceAtLeast(0f)
            (datum + profileMetres(km, metresPerRootKilometre, cellSpanKm)).toDouble()
        }
        for (cell in 0 until cellCount) {
            // Ground with no ice on it has no profile and stands where it stands, so its reading
            // is nothing however far off the ice happens to be. A world entirely under ice has no
            // margin to measure from at all, and the flood leaves its sentinel behind rather than
            // an answer; zero is the honest reading of "no margin anywhere", and it makes such a
            // world bare rather than infinitely thick.
            distance[cell] =
                if (!frozen[cell] || distance[cell] >= JumpFloodDistance.INFINITE) 0f
                else distance[cell] * kilometresPerCellWidth
            if (!frozen[cell]) nearest[cell] = cell
        }
        return Margin(distance, nearest)
    }

    /**
     * The smallest body of ice that is an ice *sheet*, in square kilometres.
     *
     * Fifty thousand is the figure glaciology uses, and it is a definition rather than a
     * threshold somebody picked: a mass of land ice larger than about 50,000 km2 is an ice sheet
     * and one smaller than it is an ice cap or an ice field (Cuffey and Paterson, 4th edn, §1.2;
     * Benn and Evans, *Glaciers and Glaciation*, 2nd edn, §1.5). Earth keeps the two sides of it
     * well apart: the sheets are Greenland at 1.71 million km2 and Antarctica at 13.9 million,
     * and the largest caps that are *not* sheets are Severny Island's at 20,500 km2,
     * Austfonna's at 8,100 and Vatnajokull's at 7,900.
     *
     * The line is here because the profile is a sheet's and only a sheet's. Nye's and Vialov's
     * equation describes a body spreading under its own weight until the shear stress at its base
     * everywhere reaches yield, which is what a sheet does and what a small cap on a plain does
     * not; applied to one anyway it gives a few hundred metres of ice with a margin the grid
     * cannot draw, and the render showed exactly that — a cream mesa with a one-cell cliff round
     * it, dropped on a green plain. A body under the line keeps no thickness and nothing is
     * written into the elevation field for it: it stays the frozen ground it was before this
     * chunk, which is the honest answer, because a cap of that size *is* frozen ground and the
     * map has never claimed to know how thick it is.
     *
     * An area rather than a radius in cells, so the same world at 512, 1024 and 2048 draws the
     * same caps. What it comes to on the standard grids, at a world 12,000 km across: 91 cells at
     * 512, 364 at 1024 and 1,458 at 2048 — a cap about 10, 19 and 38 cells across.
     */
    const val SMALLEST_SHEET_SQUARE_KM = 50_000.0

    /**
     * Two thirds, which is the mean of `sqrt(x)` over `0..x` as a share of `sqrt(x)` itself.
     *
     * Named because it is the whole of why a margin is no longer a cliff: see [profileMetres].
     */
    private const val MEAN_OF_A_ROOT_OVER_ITS_SPAN = 2f / 3f

    /**
     * The plastic profile over one cell, in metres above the margin it is measured from: the
     * *mean* of `H(x)` across the ground the cell covers, not its value at the cell's middle.
     *
     * `H = k * sqrt(x)` has an infinite slope at `x = 0`, so a cell's middle is a bad place to
     * ask it anything near the margin. Sampled there, the first cell of ice at 2048 on a world
     * 12,000 km across stood `k * sqrt(5.86)` = 361 m up and the ice ended in a step; Earth's
     * margins taper over a kilometre or two, which is a fifth of a cell here, so the step was the
     * equation telling the truth at a resolution that cannot draw it.
     *
     * A cell covers a span of distances, not a point, and the height a map cell should carry is
     * the mean over that span. The margin line — where the ice is nothing — lies half a cell
     * outside the first cell of ice, since the distance field measures centre to centre and the
     * last ice-free centre reads zero; so a cell reading [marginDistanceKm] covers
     * `marginDistanceKm - cellSpanKm` to `marginDistanceKm` of distance from that line, floored
     * at nothing.
     *
     * [cellSpanKm] is the side of the square with a cell's own area, and not its width, because
     * this is a distance measured in every direction at once. An equirectangular cell is twice as
     * wide as it is tall — 5.86 km by 2.93 at 2048 on a world 12,000 km across — so the width
     * over-smooths a margin running east and west by a factor of two, and the height
     * under-smooths one running north and south by the same. The side of the equal-area square,
     * 4.14 km there, is the one figure that is right on average whatever bearing the margin
     * happens to lie along, and it is the figure a cell would have if the grid were square. Over `x1..x2` the mean of `k * sqrt(x)` is
     * `(2/3) * k * (x2^1.5 - x1^1.5) / (x2 - x1)`, which at the first cell is
     * [MEAN_OF_A_ROOT_OVER_ITS_SPAN] of what the point sample gave — 241 m rather than 361 — and
     * which falls to nothing as the margin line is approached rather than stepping to it. Far
     * from the margin the two agree to a metre, because a root is very nearly straight there:
     * three cells in it is 440 m against the point sample's 442.
     *
     * Written with the roots factored out rather than as that fraction, and the difference is not
     * cosmetic. With `a = sqrt(x1)` and `b = sqrt(x2)` the quotient is `(b^3 - a^3) / (b^2 - a^2)`,
     * whose common factor `(b - a)` cancels to leave `(a^2 + a*b + b^2) / (a + b)` — the same
     * number with nothing subtracted. Spelt as the difference, five hundred kilometres in from a
     * margin it takes two values near 11,000 to make one near 200, which throws away most of a
     * float's precision: the parity clause measured the card 2.05e-6 of the thickest ice from the
     * processor against a bar of a part in a million, and this is what it was measuring. The
     * factored form is exact arithmetic on quantities of the size of the answer.
     */
    fun profileMetres(
        marginDistanceKm: Float,
        metresPerRootKilometre: Float,
        cellSpanKm: Float
    ): Float {
        val far = marginDistanceKm
        if (far <= 0f) return 0f
        val near = (marginDistanceKm - cellSpanKm).coerceAtLeast(0f)
        val rootFar = sqrt(far)
        val rootNear = sqrt(near)
        val mean = (near + rootNear * rootFar + far) / (rootNear + rootFar)
        return MEAN_OF_A_ROOT_OVER_ITS_SPAN * metresPerRootKilometre * mean
    }

    /**
     * How high the sheet's *surface* stands at one cell, in metres above the shoreline.
     *
     * The profile is a surface and not a drape, and the difference is the whole of what makes a
     * sheet a sheet. Nye's and Vialov's equation solves for the height of the ice *above the
     * elevation of its own margin*: a plastic body spreading until it everywhere reaches its yield
     * stress has a smooth dome for a top, and the bed it happens to be sitting on does not show
     * through it. That is why Greenland's surface is a plain sloping evenly to the coast over a
     * bed with two-kilometre mountains in it, and why a sheet's flow is radial while a valley
     * glacier's is not: the surface the ice runs down has no valleys.
     *
     * Adding the profile to the bed instead — the first thing I1 tried — gives a surface that
     * follows every hill under it, and with it a flow that is the bed's flow again. Measured on
     * the four standard worlds at 512: only 57 to 81% of the ice within 500 km of the dome then
     * flowed away from it, at a mean bearing 58 to 80 degrees off radial, which is a sheet that
     * has not noticed it is a sheet.
     *
     * The margin's own elevation is floored at the waterline. A marine margin is where the ice
     * meets the sea, and the sea is where its surface starts; reading the sea floor there would
     * begin the dome a kilometre down.
     */
    fun surfaceMetres(
        marginBedMetres: Float,
        marginDistanceKm: Float,
        metresPerRootKilometre: Float,
        cellSpanKm: Float
    ): Float =
        marginBedMetres.coerceAtLeast(0f) +
            profileMetres(marginDistanceKm, metresPerRootKilometre, cellSpanKm)

    /**
     * How much colder the top of [thicknessMetres] of ice is than its bed, in degrees.
     *
     * The climate's own lapse rate, read off the configuration rather than restated: the dome is
     * cold because it is high, by the same arithmetic that makes a mountain cold, and there is no
     * second rule for ice. `ClimateStage` applies this without knowing it exists, because what it
     * is handed is the surface's altitude.
     */
    fun surfaceCoolingC(thicknessMetres: Float, lapseRateCPerKm: Float): Float =
        thicknessMetres / WorldScale.METRES_PER_KM * lapseRateCPerKm

    /**
     * The thickness over a whole grid, in metres: the dome's surface less the bed under it, and
     * never less than nothing.
     *
     * Where the bed stands above the dome the answer is zero, and that is a nunatak — a peak
     * standing out of the ice, which Greenland and Antarctica both have and which falls out of the
     * arithmetic rather than having to be drawn.
     *
     * The CPU reference the accelerator is measured against.
     */
    fun profile(
        margin: Margin,
        bedRelative: FloatArray,
        onTheSheet: BooleanArray,
        metresPerRootKilometre: Float,
        metresPerFieldUnit: Float,
        cellSpanKm: Float
    ): FloatArray = FloatArray(bedRelative.size) { cell ->
        if (!onTheSheet[cell]) 0f else {
            val nearest = margin.nearestCell[cell]
            val marginBed = if (nearest < 0) 0f else bedRelative[nearest] * metresPerFieldUnit
            val surface = surfaceMetres(
                marginBed, margin.distanceKm[cell], metresPerRootKilometre, cellSpanKm
            )
            (surface - bedRelative[cell] * metresPerFieldUnit).coerceAtLeast(0f)
        }
    }

    /**
     * Which neighbour the ice at each cell flows to: the steepest descent of the ice *surface*,
     * and -1 where there is no sheet or no lower neighbour.
     *
     * The surface is `bed + thickness` in the elevation field's own units, so [metresPerFieldUnit]
     * is what turns the profile's metres into them. Steepest descent is measured per unit of
     * ground distance rather than per cell, for the reason the distance field is: a step down the
     * map is half a step across it on this grid, and a flow that did not know it would drift
     * north-south.
     *
     * The bearing this returns is what makes a sheet's scour radial. It is *not* the bed's D8
     * network: the whole point of the sheet regime is that the bed's network is under a kilometre
     * of ice and steers nothing.
     */
    fun flowReceivers(
        cellsAcross: Int,
        cellsDown: Int,
        bedRelative: FloatArray,
        thicknessMetres: FloatArray,
        onTheSheet: BooleanArray,
        metresPerFieldUnit: Float,
        cellHeightInCellWidths: Float
    ): IntArray {
        val cellCount = cellsAcross * cellsDown
        val surface = FloatArray(cellCount) { cell ->
            bedRelative[cell] + thicknessMetres[cell] / metresPerFieldUnit
        }
        val receiver = IntArray(cellCount) { -1 }
        for (cell in 0 until cellCount) {
            if (!onTheSheet[cell]) continue
            receiver[cell] = steepestDescent(
                cellsAcross, cellsDown, cell, surface, cellHeightInCellWidths
            )
        }
        return receiver
    }

    /**
     * The neighbour of [cell] that [surface] falls to fastest per unit of ground walked, or -1 if
     * none of the eight is lower.
     *
     * Shared by the CPU reference and by every accelerator's shader, which is why it is one
     * function: two copies of a tie-breaking rule drift, and a tie here decides a bearing.
     */
    fun steepestDescent(
        cellsAcross: Int,
        cellsDown: Int,
        cell: Int,
        surface: FloatArray,
        cellHeightInCellWidths: Float
    ): Int {
        val column = cell % cellsAcross
        val row = cell / cellsAcross
        val here = surface[cell]
        var best = -1
        var bestGradient = 0f
        for (rowStep in -1..1) {
            val neighbourRow = row + rowStep
            if (neighbourRow < 0 || neighbourRow >= cellsDown) continue
            for (columnStep in -1..1) {
                if (rowStep == 0 && columnStep == 0) continue
                var neighbourColumn = (column + columnStep) % cellsAcross
                if (neighbourColumn < 0) neighbourColumn += cellsAcross
                val neighbour = neighbourRow * cellsAcross + neighbourColumn
                val fall = here - surface[neighbour]
                if (fall <= 0f) continue
                val acrossCells = columnStep.toFloat()
                val downCells = rowStep * cellHeightInCellWidths
                val walked = sqrt(acrossCells * acrossCells + downCells * downCells)
                val gradient = fall / walked
                // Ties to the lower cell index, as the jump flood's do, so the answer does not
                // depend on the order the eight were looked at.
                if (gradient > bestGradient || (gradient == bestGradient && neighbour < best)) {
                    bestGradient = gradient
                    best = neighbour
                }
            }
        }
        return best
    }
}
