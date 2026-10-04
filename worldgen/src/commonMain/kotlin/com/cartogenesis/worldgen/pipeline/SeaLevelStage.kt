package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.math.JumpFloodDistance
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.SeaConfig
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldScale

/** Where the shoreline sits, which cells are land, and how far each cell stands from the water. */
data class SeaLevelResult(
    /**
     * The height the shoreline sits at, in the height field's own 0..1 units — the same units
     * `ErosionResult.height` is in, not [relativeElevation]'s. A cell at or above it is land; a
     * cell below it is water.
     *
     * Since S2 that field is an absolute altitude, so this converts to metres through
     * `WorldScale.altitudeAtField` and comes back near zero: how far it sits from zero is how far
     * the world's water volume is from the one the crust's own levels imply, which `UnitsTest`
     * measures.
     */
    val shorelineHeight: Float,
    /** One entry per cell, row-major, true where that cell is land. */
    val isLand: BooleanArray,
    /**
     * Elevation relative to the shoreline, on the land's half of the world's ruler above it and
     * the sea's half below: 1 is `WorldScale.highestLandMetres` up and -1 is `deepestOceanMetres`
     * down. This is what climate, rivers and rendering all work from.
     *
     * A declaration rather than a normalisation since S2. Before it, each half was divided by the
     * range that particular world happened to occupy, so +1 meant "as high as this world goes" and
     * a constant read through `WorldScale.metresAboveShoreline` was only approximately the depth it
     * said. Now no cell need reach either end — a world whose tallest mountain is four kilometres
     * tops out at 0.67 — and every constant means the same metres on every seed.
     */
    val relativeElevation: FloatField,
    /** How many entries of [isLand] are true. */
    val landCellCount: Int,
    /**
     * The bed on the same ruler as [relativeElevation]: on land, each cell's trunk channel
     * (`ErosionResult.bed`), the surface the rivers stage routes the water over, never above the
     * ground; at sea, and on any cell that became land after the cut, the same as
     * [relativeElevation], since those cells have one height. Equal to [relativeElevation] wherever
     * a caller handed the stage no bed.
     */
    val relativeBed: FloatField = relativeElevation
)

/**
 * Step 3 of the pipeline: choose the height the shoreline sits at, and split the world around it.
 *
 * The shoreline is a percentile over the height field — `seaLevel = 0.62` puts 62% of the cells
 * under water — found from a histogram rather than a sort, so that dragging the sea-level slider
 * stays fast at export resolutions. Rules then run on top of that one cut, each with its own
 * switch in [SeaConfig] and its own function below: water the ocean cannot reach becomes land
 * ([markUnreachableWaterAsLand]), the coast is drawn at what the grid can hold, and the sea floor
 * near a coast is remapped onto a continental shelf. A basin the enclosure turns into land is
 * land to the hydraulic rounds too, which cut its outlet as they cut every lake's (see
 * [enclosedCut]).
 *
 * The background — what each rule is modelling, what it was measured against, and what was tried
 * and reverted on the way — is in `GEOGRAPHY.md` and in [SeaConfig]'s own documentation. See
 * docs/DESIGN_LEDGER.md, B1, B2, H5 and H5b.
 */
object SeaLevelStage {

    /**
     * Bins used to *bracket* the shoreline before [thresholdAtRank] resolves it exactly. The width
     * of a bin decides only how many cells the second pass has to sort, never the answer.
     */
    private const val HISTOGRAM_BINS = 4096

    /**
     * The smallest span the shoreline-relative normalisation is allowed to divide by.
     *
     * [SeaLevelResult.relativeElevation] divides a cell's distance from the shoreline by the range
     * available on its own side of it, and a world of uniform height — which the tests build —
     * would divide by zero. Flooring the divisor gives such a world a field of zeroes rather than
     * a field of NaN.
     */
    private const val MIN_RANGE = 1e-6f

    /**
     * Depth of the continental shelf right at the coast, in metres below the shoreline.
     *
     * Shallower than [SeaConfig.shelfDepthMetres] at the shelf break, so the plateau slopes
     * seaward instead of being a dead-flat plain up to the shore; and shallower than the 1,200 m
     * [ClimateStage] uses for `SHALLOW_OCEAN`, so the whole plateau is drawn as shallow water.
     *
     * Thirty metres, which is Earth's inner shelf: the Grand Banks, the North Sea and the Sunda
     * shelf all sit between 20 and 60 m over most of their area, and the break is at 130. It was
     * 200 while the break was 1,000; both figures came down to Earth's in S2's second pass, when
     * the sea gained a floor deep enough for a 130 m break to mean something.
     */
    internal const val SHELF_DEPTH_AT_COAST_METRES = -30f

    /**
     * The percentile cut on its own, without the three rules that [apply] runs on top of it.
     *
     * [seaLevelFraction] is the share of the world's cells to put under water, clamped to 0..1.
     * [lowstandShareOfField] then drops the shoreline below where the percentile puts it, as a
     * fraction of the height field's whole range; at zero the arithmetic is the plain percentile
     * cut, to the last bit.
     *
     * Of the *field*, and not of the land's relief above the shoreline, which is what it was until
     * S1. The shoreline is a level in the height field and moving it is neither a height above the
     * water nor a depth below it, so the ruler it takes is the field's own —
     * `WorldScale.reliefSpanMetres`. The old form multiplied by a measured range that is 0.25 of
     * the field on one seed and 0.59 on another, so the same setting was a different lowstand on
     * every world and at every grid; this one is 120 m everywhere.
     *
     * The hydraulic rounds call this once per round to find the base level they grade to, and
     * handing them a lower one is what lets a valley continue below today's shoreline. See
     * [SeaConfig.lowstandMetres] and docs/DESIGN_LEDGER.md, H5 and S1.
     *
     * Named apart from [apply] rather than overloading it: a caller that wanted the whole stage
     * and reached the two-float form by accident would silently lose the enclosure rule, the
     * coast's two passes and the shelf. A whole-stage entry point is `apply`; a partial one
     * says which part it does.
     */
    fun percentileCut(
        height: FloatField,
        seaLevelFraction: Float,
        scale: WorldScale,
        lowstandShareOfField: Float = 0f
    ): SeaLevelResult {
        val submergedFraction = seaLevelFraction.coerceIn(0f, 1f)
        val todaysShoreline = shorelineForFraction(height, submergedFraction)
        val shorelineHeight = todaysShoreline - lowstandShareOfField

        return landAndWaterAt(height, shorelineHeight, scale)
    }

    /**
     * [percentileCut] with the enclosure rule on top, where [SeaConfig.enclosedSeaIsLand] asks for
     * it: the cut the hydraulic rounds route against, with the same [seaLevelFraction] and
     * [lowstandShareOfField] as [percentileCut].
     *
     * A hollow below the shoreline that the ocean cannot reach is land on the map, so it is land to
     * the rounds too: the water is routed through it, and its outlet is cut by the same implicit
     * pass that cuts every lake's, with what its surface evaporates taken out of what leaves it.
     * Routed as sea, it was a base level every river round it graded to, and the cut below then
     * found a basin whose sill no round had touched, which is what a pass after the cut once
     * existed to open (docs/DESIGN_LEDGER.md, H5b and E1b).
     */
    internal fun enclosedCut(
        height: FloatField,
        seaLevelFraction: Float,
        config: WorldGenConfig,
        lowstandShareOfField: Float = 0f
    ): SeaLevelResult {
        val cut = percentileCut(height, seaLevelFraction, config.scale, lowstandShareOfField)
        if (!config.sea.enclosedSeaIsLand) return cut
        return markUnreachableWaterAsLand(cut, height, config.sea, config.scale, config.squareKilometresPerCell)
    }

    /**
     * [cut] with its bed: [bed], in the height field's units, read onto the land's half of the
     * ruler from [cut]'s shoreline on every land cell and held at or under the ground there; every
     * water cell's bed its ground.
     */
    private fun withBed(cut: SeaLevelResult, bed: FloatField, scale: WorldScale): SeaLevelResult {
        val relativeBed = cut.relativeElevation.copy()
        val landHalfOfField = scale.landHalfOfField.coerceAtLeast(MIN_RANGE)
        for (cell in relativeBed.data.indices) {
            if (!cut.isLand[cell]) continue
            val onRuler = (bed.data[cell] - cut.shorelineHeight) / landHalfOfField
            if (onRuler < relativeBed.data[cell]) relativeBed.data[cell] = onRuler
        }
        return cut.copy(relativeBed = relativeBed)
    }

    /**
     * [after] with [before]'s bed carried across a pass that moved the coast or the ground: a cell
     * that was land in both keeps its bed, held at or under its new ground; any other cell's bed is
     * its ground, a cell the pass turned from water into land having one height.
     */
    internal fun carryBed(before: SeaLevelResult, after: SeaLevelResult): SeaLevelResult {
        if (before.relativeBed === before.relativeElevation) return after
        val relativeBed = after.relativeElevation.copy()
        for (cell in relativeBed.data.indices) {
            if (!after.isLand[cell] || !before.isLand[cell]) continue
            val carried = before.relativeBed.data[cell]
            if (carried < relativeBed.data[cell]) relativeBed.data[cell] = carried
        }
        return after.copy(relativeBed = relativeBed)
    }

    /**
     * Land, water and the shoreline-relative field, for a shoreline already decided.
     *
     * The two halves of [SeaLevelResult.relativeElevation] are divided by the two halves of the
     * *declared* ruler — `highestLandMetres` above the water and `deepestOceanMetres` below it,
     * each as a share of the height field — and not by the range this particular world happens to
     * occupy. That is only possible since S2: isostasy gives the field an absolute vertical scale,
     * so `+1` means six kilometres up on every seed rather than "as high as this world happens to
     * go", and `WorldScale.metresAboveShoreline` is exactly true instead of approximately so.
     *
     * What it costs is that no cell need reach 1 or -1, which is the honest answer — a world whose
     * tallest mountain is four kilometres has a tallest mountain of four kilometres. What it buys
     * is that every constant read through the ruler means the same depth everywhere: the shelf
     * break really is at 1,000 m, the navigable depth really is where it says, and the hypsometric
     * curve is a measurement rather than a normalisation. See `UnitsTest`.
     */
    private fun landAndWaterAt(
        height: FloatField,
        shorelineHeight: Float,
        scale: WorldScale
    ): SeaLevelResult {
        val cellCount = height.data.size
        val isLand = BooleanArray(cellCount)
        val relativeElevation = FloatField(height.width, height.height)

        val landHalfOfField = scale.landHalfOfField.coerceAtLeast(MIN_RANGE)
        val seaHalfOfField = scale.seaHalfOfField.coerceAtLeast(MIN_RANGE)

        var landCellCount = 0
        for (cell in 0 until cellCount) {
            val groundHeight = height.data[cell]
            if (groundHeight >= shorelineHeight) {
                isLand[cell] = true
                landCellCount++
                relativeElevation.data[cell] = (groundHeight - shorelineHeight) / landHalfOfField
            } else {
                relativeElevation.data[cell] = (groundHeight - shorelineHeight) / seaHalfOfField
            }
        }

        return SeaLevelResult(shorelineHeight, isLand, relativeElevation, landCellCount)
    }

    /**
     * The whole cut: the percentile, the two rules that decide which water is sea, the two that
     * decide what shape the shoreline is, and the continental shelf under all of it.
     *
     * In that order, and the order is the argument. The percentile ([percentileCut]) decides where
     * the coastline is; [markUnreachableWaterAsLand] decides which of the water below it the ocean
     * can actually reach; [DrownedValleys] and [LittoralGrading] move the
     * shoreline itself, so they have to run before anything is measured from it, and the valleys go
     * first because the grading should be asked about a coast the grid can hold rather than about
     * the channels through it; and the shelf remap is measured from the finished shoreline and
     * touches only water, so it runs last. Each runs as its [SeaConfig] switch asks for it.
     *
     * The shelf is a remap of the ocean floor *after* the cut has already fixed the coastline, and
     * it touches only cells [SeaLevelResult.isLand] marks as water, so no coastline moves. Three
     * bands, keyed on distance to the nearest land cell in cells:
     *
     *  - out to the shelf's width, a shallow plateau sloping from [SHELF_DEPTH_AT_COAST_METRES]
     *    at the coast to `shelfDepthMetres` at the shelf break;
     *  - from there to twice that width, a smoothstep from the shelf break back down to whatever the
     *    unshelved depth at that cell already was — the continental slope;
     *  - beyond that, untouched: the natural sea floor is deep enough on its own once clear of the
     *    coast, so only the margin needed fixing.
     *
     * It fills rather than replaces, which is S2's second pass and matters more than it sounds.
     * What this wedge stands for is the sediment shed off the continent and laid on the margin,
     * and sediment fills a hollow without shaving a rise: so a cell takes the *shallower* of its
     * own floor and the wedge's surface, and any bank, rise or ridge flank the crust put inside the
     * band goes on showing through. Replacing outright — which is what it did until now — turned
     * the whole margin into a function of one number, the distance to the nearest land, and drew
     * it as the concentric bands around every landmass that S2's first pass was called out for.
     *
     * Shaping the shelf earlier, as a depression on oceanic crust before the percentile ran, was
     * tried and reverted; see [SeaConfig] and docs/DESIGN_LEDGER.md, B1.
     */
    fun apply(height: FloatField, config: WorldGenConfig, bed: FloatField = height): SeaLevelResult =
        applyWithValleyBar(height, config, DrownedValleys.RESOLVED_SHARE_OF_A_CELL, bed)

    /**
     * The same cut with [DrownedValleys]' bar moved, which only the diagnosis that asks what the
     * coast would measure with every drowned notch filled ever does. See that constant.
     */
    internal fun applyWithValleyBar(
        height: FloatField,
        config: WorldGenConfig,
        resolvedShareOfCell: Float,
        bed: FloatField = height
    ): SeaLevelResult {
        val seaConfig = config.sea
        // Today's stand, always: the lowstand belongs to the rounds that carved the terrain this is
        // cutting, not to the map that is drawn.
        val plainCut = percentileCut(height, config.seaLevel, config.scale)
        val enclosed =
            if (seaConfig.enclosedSeaIsLand) {
                markUnreachableWaterAsLand(
                    plainCut, height, seaConfig, config.scale, config.squareKilometresPerCell
                )
            } else {
                plainCut
            }
        // Then the two passes that decide what the coastline the map draws actually is. First the
        // drowned valleys the grid cannot hold: the lowstand cut a channel to every shore and the
        // transgression flooded all of them, and a channel a kilometre wide has no business filling
        // a cell twelve kilometres wide. Then the six thousand years since, in which the waves grade
        // the coasts that are low enough to be graded and leave the rest alone.
        //
        // The enclosure rule is not run again over what either of them leaves: both only ever turn
        // water into land, and neither will touch a cell whose filling would cut the water around it
        // in two, so no body of water can be enclosed by them. See [WaterTopology], and
        // `LittoralCoastTest`, which counts the bodies the ocean cannot reach on both sides.
        // The bed rides along: each pass below decides the coast on the ground, and a cell keeps
        // its bed only while it stays land.
        val withBed = if (bed === height) enclosed else withBed(enclosed, bed, config.scale)
        val resolved = carryBed(withBed, DrownedValleys.apply(withBed, height, config, resolvedShareOfCell))
        val beforeShelf = carryBed(resolved, LittoralGrading.apply(resolved, config))
        if (seaConfig.shelfWidthKm <= 0.0) return beforeShelf

        val cellsAcross = beforeShelf.relativeElevation.width
        val cellsDown = beforeShelf.relativeElevation.height
        val cellCount = cellsAcross * cellsDown
        val isLand = beforeShelf.isLand

        // Euclidean distance on the ground to the nearest land cell, in cell widths, by jump
        // flooding: the three bands below are read straight off it, so the shelf break is one of
        // this field's iso-contours and stands as far off a northern coast as off a western one.
        val distanceToLand = FloatArray(cellCount) { JumpFloodDistance.INFINITE }
        val nearestLandCell = IntArray(cellCount) { -1 }
        for (cell in 0 until cellCount) {
            if (isLand[cell]) {
                distanceToLand[cell] = 0f
                nearestLandCell[cell] = cell
            }
        }
        if (beforeShelf.landCellCount > 0) {
            JumpFloodDistance.run(
                cellsAcross, cellsDown, distanceToLand, nearestLandCell, config.cellHeightInCellWidths
            )
        }

        // The three bands, converted from the world's own scale once: the shelf's width in cell
        // widths of this grid, and its two depths as shares of the sea's own range below the
        // shoreline.
        val shelfBreakCells = config.cellsFor(seaConfig.shelfWidthKm)
        val slopeFootCells = 2f * shelfBreakCells
        val shelfBreakDepth = -config.scale.depthShareOfMetres(seaConfig.shelfDepthMetres)
        val shelfDepthAtCoast = config.scale.depthShareOfMetres(SHELF_DEPTH_AT_COAST_METRES)
        val withShelf = FloatField(cellsAcross, cellsDown)
        beforeShelf.relativeElevation.data.copyInto(withShelf.data)

        for (cell in 0 until cellCount) {
            if (isLand[cell]) continue
            val distance = distanceToLand[cell]
            val naturalFloor = beforeShelf.relativeElevation.data[cell]
            val wedgeSurface = when {
                distance <= shelfBreakCells -> {
                    val acrossPlateau = (distance / shelfBreakCells).coerceIn(0f, 1f)
                    shelfDepthAtCoast +
                        acrossPlateau * (shelfBreakDepth - shelfDepthAtCoast)
                }
                distance <= slopeFootCells -> {
                    val downSlope =
                        ((distance - shelfBreakCells) / shelfBreakCells).coerceIn(0f, 1f)
                    val eased = downSlope * downSlope * (3f - 2f * downSlope)
                    shelfBreakDepth + eased * (naturalFloor - shelfBreakDepth)
                }
                else -> naturalFloor
            }
            // Sediment fills; it does not shave. Whichever of the two stands higher is the floor.
            withShelf.data[cell] = if (wedgeSurface > naturalFloor) wedgeSurface else naturalFloor
        }

        // The shelf moves only water, whose bed is its ground.
        val shelfBed =
            if (beforeShelf.relativeBed === beforeShelf.relativeElevation) withShelf
            else FloatField(cellsAcross, cellsDown, FloatArray(cellCount) { cell ->
                if (isLand[cell]) beforeShelf.relativeBed.data[cell] else withShelf.data[cell]
            })
        return beforeShelf.copy(relativeElevation = withShelf, relativeBed = shelfBed)
    }

    /**
     * Water the ocean cannot reach is not sea: every body of water that is not the ocean and is no
     * larger than the largest lake Earth has becomes land, at the height it already stands at.
     *
     * The percentile cut is a statement about the height field and nothing else, so every hollow
     * below it comes out as ocean whether or not a drop of ocean could get there — and near a coast
     * the hydraulic rounds leave a great many such hollows one cell across, each of which stops a
     * D8 river dead. This is the smallest thing that can be true about them: label the water, find
     * the ocean, mark the rest land. Nothing is raised, nothing is moved, and no coastline the
     * ocean actually touches changes by a cell. Where the water goes next is the river stage's
     * business — its depression fill raises each hollow to its lowest outlet and the water balance
     * decides whether it keeps a lake or dries to a playa, which is the right way round, because a
     * lake below sea level is a real landform.
     *
     * Three things here are load-bearing:
     *
     *  - **Eight-connectivity, wrapping in x.** Every neighbour walk in this generator is the
     *    eight-cell one and the map is a cylinder, so this has to be as well. It also decides the
     *    case that made an earlier attempt fail: a rift's gulfs hang off the ocean through sills
     *    that can be a single cell wide, and under four-connectivity a diagonal sill reads as
     *    closed, turning the whole chain of gulfs into lakes.
     *  - **The ocean is the largest body**, not the one touching some edge. The map has no edge in
     *    x and its poles are land as often as not.
     *  - **A converted cell's [SeaLevelResult.relativeElevation] is renormalised into the land's
     *    units** — negative, since it is below the shoreline, but scaled by the same relief its new
     *    neighbours are. Left in the sea's units it would look several times deeper or shallower
     *    than it is to the depression fill, which compares it against exactly those neighbours.
     *
     * The cut itself stays where the percentile put it. Solving instead for the rank at which the
     * *ocean* covers what the slider asks for was written and reverted; see
     * [SeaConfig.enclosedSeaMaxKm2], `GEOGRAPHY.md` and docs/DESIGN_LEDGER.md, H5.
     */
    private fun markUnreachableWaterAsLand(
        base: SeaLevelResult,
        height: FloatField,
        seaConfig: SeaConfig,
        scale: WorldScale,
        squareKilometresPerCell: Double
    ): SeaLevelResult {
        val cellsAcross = height.width
        val cellsDown = height.height
        val cellCount = cellsAcross * cellsDown
        val waterCellCount = cellCount - base.landCellCount
        if (waterCellCount == 0) return base

        // Body id per water cell, -1 on land. One flood fill per body over an explicit stack: the
        // largest body is most of the map and recursion would not survive it.
        val bodyOfCell = IntArray(cellCount) { -1 }
        val frontier = IntArray(waterCellCount)
        val cellsInBody = ArrayList<Int>()
        var bodyCount = 0
        var oceanBody = -1
        var oceanCellCount = 0
        for (start in 0 until cellCount) {
            if (base.isLand[start] || bodyOfCell[start] >= 0) continue
            val body = bodyCount++
            var frontierSize = 0
            bodyOfCell[start] = body
            frontier[frontierSize++] = start
            var found = 0
            while (frontierSize > 0) {
                val cell = frontier[--frontierSize]
                found++
                FlowRouting.forEachNeighbour(
                    cellsAcross, cellsDown, cell % cellsAcross, cell / cellsAcross
                ) { neighbour ->
                    if (!base.isLand[neighbour] && bodyOfCell[neighbour] < 0) {
                        bodyOfCell[neighbour] = body
                        frontier[frontierSize++] = neighbour
                    }
                }
            }
            cellsInBody.add(found)
            if (found > oceanCellCount) {
                oceanCellCount = found
                oceanBody = body
            }
        }
        if (bodyCount <= 1) return base

        // Anything bigger than the largest lake Earth has is a sea, whatever the connectivity says.
        // See [SeaConfig.enclosedSeaMaxKm2], which carries the measurements this cap comes from.
        val largestLakeCells =
            (seaConfig.enclosedSeaMaxKm2 / squareKilometresPerCell).toInt()
        val isLand = base.isLand.copyOf()
        val relativeElevation = base.relativeElevation.copy()
        val landHalfOfField = scale.landHalfOfField.coerceAtLeast(MIN_RANGE)
        var landCellCount = base.landCellCount
        for (cell in 0 until cellCount) {
            val body = bodyOfCell[cell]
            if (body < 0 || body == oceanBody || cellsInBody[body] > largestLakeCells) continue
            isLand[cell] = true
            landCellCount++
            relativeElevation.data[cell] =
                (height.data[cell] - base.shorelineHeight) / landHalfOfField
        }

        return SeaLevelResult(base.shorelineHeight, isLand, relativeElevation, landCellCount)
    }

    /** The height below which [submergedFraction] of the world's cells lie. */
    private fun shorelineForFraction(height: FloatField, submergedFraction: Float): Float =
        thresholdAtRank(height, (height.data.size * submergedFraction).toLong())

    /**
     * The height with exactly [targetRank] cells below it — resolved exactly, not to the nearest
     * histogram bin.
     *
     * The histogram only brackets the answer, and taking the bracketing bin's lower edge instead
     * leaves every cell *inside* that bin above water, so the land fraction overshoots by whatever
     * share of the map the bin holds. That share is not small: a sea-level cut lands, by its
     * nature, in a lump of ocean floor at a near-uniform depth, and the bracketing bin has measured
     * as much as 5% of the map. A second pass over the few hundred cells of that one bin picks the
     * height that puts exactly the right number of cells below it, for one extra scan and an array
     * a few thousand floats long. See docs/DESIGN_LEDGER.md, B2.
     */
    private fun thresholdAtRank(height: FloatField, targetRank: Long): Float {
        val lowest = height.min()
        val highest = height.max()
        if (highest - lowest <= 0f) return lowest

        val binsPerUnitHeight = (HISTOGRAM_BINS - 1) / (highest - lowest)
        fun binOf(value: Float) =
            ((value - lowest) * binsPerUnitHeight).toInt().coerceIn(0, HISTOGRAM_BINS - 1)

        val histogram = IntArray(HISTOGRAM_BINS)
        for (value in height.data) histogram[binOf(value)]++

        if (targetRank <= 0L) return lowest

        var cellsBelowBracket = 0L
        var bracketBin = HISTOGRAM_BINS - 1
        for (bin in 0 until HISTOGRAM_BINS) {
            if (cellsBelowBracket + histogram[bin] >= targetRank) {
                bracketBin = bin
                break
            }
            cellsBelowBracket += histogram[bin]
        }

        val bracketValues = FloatArray(histogram[bracketBin])
        if (bracketValues.isEmpty()) return highest
        var written = 0
        for (value in height.data) {
            if (binOf(value) == bracketBin) bracketValues[written++] = value
        }
        bracketValues.sort()
        // Everything strictly below the returned value is sea, so the value wanted is the one with
        // exactly `targetRank` cells beneath it: `cellsBelowBracket` of them under the bracketing
        // bin, the rest inside it.
        val indexInBracket = (targetRank - cellsBelowBracket).toInt()
            .coerceIn(0, bracketValues.size - 1)
        return bracketValues[indexInBracket]
    }
}
