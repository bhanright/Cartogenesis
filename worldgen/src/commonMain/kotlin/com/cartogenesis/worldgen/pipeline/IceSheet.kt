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
 * against Bedmap2's measured maximum of 4,776 (Fretwell et al. 2013). A small cap 50 km across
 * stands 745 m, which is an ice cap and not a sheet, and that is the profile doing the work rather
 * than a second rule for small ice.
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

    /** Where each frozen cell's nearest margin is, and how far off, in kilometres. */
    class Margin(val distanceKm: FloatArray, val nearestCell: IntArray)

    /**
     * How far each frozen cell stands from the nearest ice-free ground, in kilometres, and which
     * cell that is.
     *
     * By [JumpFloodDistance] rather than a chamfer transform, so the contours are circles and the
     * margin the profile is measured from is the real one; and with the grid's own row scale, so
     * the answer is a length on the ground. Before S2b every distance field in this stage counted
     * cells, which on a map twice as wide as it is tall made a sheet twice as thick northward as
     * eastward for no reason but the grid.
     *
     * The nearest cell is carried as well as the distance because the profile is a *surface* and a
     * surface has to start somewhere: see [surfaceMetres].
     */
    fun marginDistanceKm(config: WorldGenConfig, frozen: BooleanArray): Margin {
        val cellCount = config.width * config.height
        val distance = FloatArray(cellCount) { JumpFloodDistance.INFINITE }
        val nearest = IntArray(cellCount) { -1 }
        for (cell in 0 until cellCount) {
            if (!frozen[cell]) {
                distance[cell] = 0f
                nearest[cell] = cell
            }
        }
        JumpFloodDistance.run(
            config.width, config.height, distance, nearest, config.cellHeightInCellWidths
        )
        val kilometresPerCellWidth = config.cellWidthKm.toFloat()
        for (cell in 0 until cellCount) {
            // A world entirely under ice has no margin to measure from, and the flood leaves its
            // sentinel behind rather than an answer. Zero is the honest reading of "no margin
            // anywhere", and it makes such a world bare rather than infinitely thick.
            distance[cell] =
                if (distance[cell] >= JumpFloodDistance.INFINITE) 0f
                else distance[cell] * kilometresPerCellWidth
        }
        return Margin(distance, nearest)
    }

    /** The plastic profile at one cell, in metres above the margin it is measured from. */
    fun profileMetres(marginDistanceKm: Float, metresPerRootKilometre: Float): Float =
        if (marginDistanceKm <= 0f) 0f else metresPerRootKilometre * sqrt(marginDistanceKm)

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
        metresPerRootKilometre: Float
    ): Float =
        marginBedMetres.coerceAtLeast(0f) + profileMetres(marginDistanceKm, metresPerRootKilometre)

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
        metresPerFieldUnit: Float
    ): FloatArray = FloatArray(bedRelative.size) { cell ->
        if (!onTheSheet[cell]) 0f else {
            val nearest = margin.nearestCell[cell]
            val marginBed = if (nearest < 0) 0f else bedRelative[nearest] * metresPerFieldUnit
            val surface =
                surfaceMetres(marginBed, margin.distanceKm[cell], metresPerRootKilometre)
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
