package com.cartogenesis.desktop

import com.cartogenesis.cartography.SheetGeometry
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.BoundaryClass
import kotlin.math.hypot

/**
 * The places the "Read the land" map pins, found in the world's own fields rather than placed by
 * hand, so that a pin still stands on what its note names after the site's pictures are made again
 * from a changed generator.
 *
 * Each kind is a question asked of the fields inside one window of the sheet, and the answer is
 * the cell that answers it best: the river mouth that drains the most land, the lee cell with the
 * sharpest drop in rain behind a crest, and so on. When a window holds no answer to a question the
 * site cannot be assembled, which is the honest outcome: the note would otherwise sit under a pin
 * pointing at nothing, and the window wants choosing again.
 *
 * `SiteAssemblyTest` checks every pin against the saved world with predicates of its own, written
 * from the fields and not from this file, so a finder that drifted onto the wrong cell is caught
 * rather than trusted.
 */
object SiteLandmarks {

    /**
     * What a pin can stand on, in the order the page numbers them. [id] is the note's id on the
     * page, `land-<id>`, and the word the assembled pin carries in `data-kind`.
     */
    enum class Kind(val id: String) {
        COASTAL_RANGE("coastal-range"),
        RAIN_SHADOW("rain-shadow"),
        DROWNED_VALLEY("drowned-valley"),
        DELTA("delta"),
        SHELF("shelf")
    }

    /** One pin: what it names, the cell it stands on, and the cell's centre on the sheet. */
    data class Landmark(val kind: Kind, val cell: Int, val sheetX: Float, val sheetY: Float)

    /**
     * How far along the wind a rain shadow is read, in kilometres each way from the crest's lee.
     *
     * Two hundred and fifty: the Andes' wet windward slopes and the Patagonian steppe behind them
     * are about that far apart, as are the Cascades' west foot and the dry country east of them,
     * so a range of Earth's size casts its shadow inside this reach.
     */
    const val RAIN_SHADOW_REACH_KM = 250.0

    /**
     * How much higher than both ends of the reach the ground between them must rise before the
     * contrast counts as a range's doing and not a coast's or a latitude's, in metres.
     */
    const val RAIN_SHADOW_CREST_METRES = 1_000f

    /** The least ratio of windward to leeward rain a rain-shadow pin may stand on: a third. */
    const val RAIN_SHADOW_LEAST_RATIO = 3f

    /**
     * The highest a rain-shadow pin may stand, in metres: on the dry country behind the range, not
     * in a high valley inside it, where the drop in rain is the height's doing as much as the lee's.
     */
    const val RAIN_SHADOW_LEE_MOST_METRES = 1_000f

    /**
     * The lowest ground a coastal-range pin may stand on, in metres: a range, not its foothills.
     */
    const val COASTAL_RANGE_LEAST_METRES = 1_500f

    /**
     * Radius, in kilometres, over which a drowned valley is told from open coast: a valley reads as
     * one where the sea within this reach of it is mostly land on either side.
     */
    const val INLET_RADIUS_KM = 60.0

    /** The most of that disc that may be sea for the cell to stand in an inlet: under a third. */
    const val INLET_MOST_SEA_SHARE = 0.33f

    /**
     * Radius, in kilometres, of the disc a shelf pin is scored over: the pin goes where the most of
     * the disc is shelf, which is where the shelf runs widest before its break.
     */
    const val SHELF_RADIUS_KM = 60.0

    /**
     * How close to the window's edge a pin may stand, in sheet pixels: far enough in that the whole
     * of a pin's square is on the picture at every width the page is read at.
     */
    const val EDGE_MARGIN_PIXELS = 40

    /**
     * How close two pins may stand, in sheet pixels, before the second is moved to its kind's next
     * answer: two squares that overlap cannot both be pressed. The map is narrowest on a phone,
     * 343 pixels across for 1120 of the sheet, where 112 sheet pixels is 34 of the reader's: the
     * 24-pixel square the page draws there, with a gap round it a finger can find.
     */
    const val LEAST_PIN_SPACING_PIXELS = 112

    /** Every kind's best answer inside [window], in [Kind] order. Throws when one has none. */
    fun find(world: WorldMap, window: SiteImagery.Window): List<Landmark> {
        val chosen = ArrayList<Landmark>()
        for (kind in Kind.entries) {
            val candidates = candidates(world, window, kind)
            val pick = candidates.firstOrNull { candidate ->
                chosen.none { hypot(it.sheetX - candidate.sheetX, it.sheetY - candidate.sheetY) < LEAST_PIN_SPACING_PIXELS }
            } ?: error(
                "no ${kind.id} in the landmark window $window (${candidates.size} candidates, all too " +
                    "close to another pin): the window wants choosing again"
            )
            chosen.add(pick)
        }
        return chosen
    }

    /** Every cell answering [kind] inside [window], best first. */
    fun candidates(world: WorldMap, window: SiteImagery.Window, kind: Kind): List<Landmark> {
        val sheet = SheetGeometry.of(world)
        val scored = when (kind) {
            Kind.COASTAL_RANGE -> coastalRange(world)
            Kind.RAIN_SHADOW -> rainShadows(world)
            Kind.DROWNED_VALLEY -> drownedValleys(world, window, sheet)
            Kind.DELTA -> deltas(world)
            Kind.SHELF -> shelves(world, window, sheet)
        }
        return scored.asSequence()
            .map { (cell, score) -> landmark(world, sheet, kind, cell) to score }
            .filter { (landmark, _) -> inside(landmark, window, sheet) }
            .sortedByDescending { it.second }
            .map { it.first }
            .toList()
    }

    private fun landmark(world: WorldMap, sheet: SheetGeometry, kind: Kind, cell: Int) = Landmark(
        kind, cell,
        sheet.sheetX(cell % world.width + 0.5f),
        sheet.sheetY(cell / world.width + 0.5f)
    )

    /** Whether [landmark] stands at least [EDGE_MARGIN_PIXELS] inside [window], wrapping east-west. */
    fun inside(landmark: Landmark, window: SiteImagery.Window, sheet: SheetGeometry): Boolean {
        val across = ((landmark.sheetX - window.x) % sheet.widthPixels + sheet.widthPixels) % sheet.widthPixels
        val down = landmark.sheetY - window.y
        return across >= EDGE_MARGIN_PIXELS && across <= window.width - EDGE_MARGIN_PIXELS &&
            down >= EDGE_MARGIN_PIXELS && down <= window.height - EDGE_MARGIN_PIXELS
    }

    private fun metres(world: WorldMap, cell: Int): Float {
        val relative = world.sea.relativeElevation.data[cell]
        return if (world.sea.isLand[cell]) world.config.scale.metresAboveShoreline(relative)
        else world.config.scale.metresBelowShoreline(relative)
    }

    /**
     * Land on the continental side of an ocean plate diving under a continent, within the reach of
     * the coastal range and its volcanic arc, scored by height: the pin goes on the range's summit.
     */
    private fun coastalRange(world: WorldMap): List<Pair<Int, Float>> {
        val tectonics = world.config.tectonics
        val reach = tectonics.andeanWidthCells + tectonics.arcOffsetCells + tectonics.arcWidthCells
        val andean = BoundaryClass.ANDEAN_MARGIN.ordinal
        return (0 until world.width * world.height).filter { cell ->
            world.sea.isLand[cell] && world.plates.nearestBoundaryClass[cell] == andean &&
                world.plates.boundaryDistance.data[cell] <= reach &&
                metres(world, cell) >= COASTAL_RANGE_LEAST_METRES
        }.map { it to metres(world, it) }
    }

    /**
     * Lee cells, scored by how many times more rain falls on the wettest land upwind of the crest
     * behind them, within [RAIN_SHADOW_REACH_KM].
     */
    private fun rainShadows(world: WorldMap): List<Pair<Int, Float>> {
        val found = ArrayList<Pair<Int, Float>>()
        for (lee in 0 until world.width * world.height) {
            if (metres(world, lee) > RAIN_SHADOW_LEE_MOST_METRES) continue
            if (world.rivers.lakes.isLake(lee)) continue
            val ratio = rainShadowRatio(world, lee) ?: continue
            if (ratio >= RAIN_SHADOW_LEAST_RATIO) found.add(lee to ratio)
        }
        return found
    }

    /**
     * The windward-to-leeward rain ratio behind [lee], or null where the cell is not in the lee of
     * a crest: sea under it, no wind, no ground upwind rising [RAIN_SHADOW_CREST_METRES] above it
     * within the reach, or no land upwind of that crest to be the windward side.
     *
     * The walk runs upwind from the lee to the reach. The crest is the highest ground on it, and
     * the windward rain is the most that falls on any land cell of the walk beyond the crest: the
     * windward slope is where the air rising off the sea drops its water, and that slope may be a
     * narrow one between the crest and the coast.
     *
     * The wind is a vector in cells, and a cell is twice as wide on the ground as it is tall, so
     * the walk is taken in kilometres and turned back into cells at each step; a walk in cells
     * would reach twice as far east as north.
     */
    fun rainShadowRatio(world: WorldMap, lee: Int): Float? {
        val width = world.width
        if (!world.sea.isLand[lee]) return null
        val cellWidthKm = world.config.scale.cellWidthKm(width)
        val cellHeightKm = world.config.scale.cellHeightKm(world.height)
        val eastKm = world.climate.windDirection[lee] * cellWidthKm
        val southKm = world.climate.windMeridional.data[lee] * cellHeightKm
        val speedKm = hypot(eastKm, southKm)
        if (speedKm < cellHeightKm * 0.1) return null
        val x = lee % width
        val y = lee / width
        val stepKm = cellHeightKm
        val steps = (RAIN_SHADOW_REACH_KM / stepKm).toInt()
        val walk = IntArray(steps)
        for (step in 1..steps) {
            val distanceKm = step * stepKm
            val row = y - Math.round(southKm / speedKm * distanceKm / cellHeightKm).toInt()
            if (row < 0 || row >= world.height) return null
            val column = Math.floorMod(x - Math.round(eastKm / speedKm * distanceKm / cellWidthKm).toInt(), width)
            walk[step - 1] = row * width + column
        }
        var crestAt = 0
        for (at in walk.indices) if (metres(world, walk[at]) > metres(world, walk[crestAt])) crestAt = at
        if (metres(world, walk[crestAt]) < metres(world, lee) + RAIN_SHADOW_CREST_METRES) return null
        var windwardRain = -1f
        for (at in crestAt + 1 until walk.size) {
            if (world.sea.isLand[walk[at]]) windwardRain = maxOf(windwardRain, world.climate.precipitationMm.data[walk[at]])
        }
        val leeRain = world.climate.precipitationMm.data[lee]
        if (windwardRain < 0f || leeRain <= 0f) return null
        return windwardRain / leeRain
    }

    /**
     * The last land cell of every river that reaches the sea, scored by how many cells drain
     * through it; only rivers draining at least the share of all land a river must drain before
     * the erosion builds a delta at its mouth (`ErosionConfig.deltaMinCatchment`) are answers.
     */
    private fun deltas(world: WorldMap): List<Pair<Int, Float>> {
        val drained = drainedCells(world)
        val least = world.config.erosion.deltaMinCatchment * world.sea.landCellCount
        val found = ArrayList<Pair<Int, Float>>()
        for (river in world.rivers.rivers) {
            val mouth = river.cells.lastOrNull { world.sea.isLand[it] } ?: continue
            val next = world.rivers.flowTarget[mouth]
            if (next < 0 || world.sea.isLand[next]) continue
            if (drained[mouth] >= least) found.add(mouth to drained[mouth].toFloat())
        }
        return found.distinctBy { it.first }
    }

    /**
     * How many land cells drain through each cell, itself included, down the routing's own tree:
     * each cell passes its count to its target once every cell draining into it has passed theirs.
     */
    fun drainedCells(world: WorldMap): IntArray {
        val cells = world.width * world.height
        val target = world.rivers.flowTarget
        val land = world.sea.isLand
        val waiting = IntArray(cells)
        for (cell in 0 until cells) {
            if (land[cell] && target[cell] >= 0) waiting[target[cell]]++
        }
        val count = IntArray(cells) { if (land[it]) 1 else 0 }
        val ready = ArrayDeque<Int>()
        for (cell in 0 until cells) if (land[cell] && waiting[cell] == 0) ready.addLast(cell)
        while (ready.isNotEmpty()) {
            val cell = ready.removeFirst()
            val next = target[cell]
            if (next < 0) continue
            count[next] += count[cell]
            if (land[next] && --waiting[next] == 0) ready.addLast(next)
        }
        return count
    }

    /**
     * Sea cells in a narrow arm of the sea, scored by how little of the disc of [INLET_RADIUS_KM]
     * round them is sea: the pin goes where the valley is narrowest between its walls.
     *
     * The generator's rivers cut their valleys to a sea standing `SeaConfig.lowstandMetres` below
     * today's and the sea then rose into them, so a long narrow arm of the sea on its map is a
     * drowned valley by the way it was made.
     */
    private fun drownedValleys(world: WorldMap, window: SiteImagery.Window, sheet: SheetGeometry): List<Pair<Int, Float>> {
        val found = ArrayList<Pair<Int, Float>>()
        val firstRow = maxOf(0, window.y / sheet.pixelsPerCellDown)
        val lastRow = minOf(world.height - 1, (window.y + window.height) / sheet.pixelsPerCellDown)
        val firstColumn = window.x / sheet.pixelsPerCellAcross
        val columns = window.width / sheet.pixelsPerCellAcross
        for (row in firstRow..lastRow) for (offset in 0..columns) {
            val cell = row * world.width + Math.floorMod(firstColumn + offset, world.width)
            val share = seaShareAround(world, cell, INLET_RADIUS_KM) ?: continue
            if (share < INLET_MOST_SEA_SHARE) found.add(cell to (1f - share))
        }
        return found
    }

    /**
     * The share of the ground within [radiusKm] of a sea cell that is sea, or null for land or a
     * cell too near a pole for the disc to fit. A disc on the ground, which on this grid is twice
     * as many cells down as across.
     */
    fun seaShareAround(world: WorldMap, cell: Int, radiusKm: Double): Float? {
        if (world.sea.isLand[cell]) return null
        val cellWidthKm = world.config.scale.cellWidthKm(world.width)
        val cellHeightKm = world.config.scale.cellHeightKm(world.height)
        val across = (radiusKm / cellWidthKm).toInt()
        val down = (radiusKm / cellHeightKm).toInt()
        val x = cell % world.width
        val y = cell / world.width
        if (y - down < 0 || y + down >= world.height) return null
        var sea = 0
        var all = 0
        for (dy in -down..down) for (dx in -across..across) {
            val eastKm = dx * cellWidthKm
            val southKm = dy * cellHeightKm
            if (eastKm * eastKm + southKm * southKm > radiusKm * radiusKm) continue
            all++
            if (!world.sea.isLand[(y + dy) * world.width + Math.floorMod(x + dx, world.width)]) sea++
        }
        return sea.toFloat() / all
    }

    /**
     * Sea cells no deeper than the shelf break (`SeaConfig.shelfDepthMetres`), scored by how much of
     * the disc of [SHELF_RADIUS_KM] round them is shelf too: the pin goes out on the widest of it.
     */
    private fun shelves(world: WorldMap, window: SiteImagery.Window, sheet: SheetGeometry): List<Pair<Int, Float>> {
        val found = ArrayList<Pair<Int, Float>>()
        val firstRow = maxOf(0, window.y / sheet.pixelsPerCellDown)
        val lastRow = minOf(world.height - 1, (window.y + window.height) / sheet.pixelsPerCellDown)
        val firstColumn = window.x / sheet.pixelsPerCellAcross
        val columns = window.width / sheet.pixelsPerCellAcross
        for (row in firstRow..lastRow) for (offset in 0..columns) {
            val cell = row * world.width + Math.floorMod(firstColumn + offset, world.width)
            if (!onShelf(world, cell)) continue
            found.add(cell to shareAround(world, cell, SHELF_RADIUS_KM) { onShelf(world, it) })
        }
        return found
    }

    /** Whether [cell] is sea no deeper than the shelf break. */
    fun onShelf(world: WorldMap, cell: Int): Boolean =
        !world.sea.isLand[cell] && metres(world, cell) >= -world.config.sea.shelfDepthMetres

    /** The share of the ground within [radiusKm] of [cell] for which [counts] holds. */
    private fun shareAround(world: WorldMap, cell: Int, radiusKm: Double, counts: (Int) -> Boolean): Float {
        val cellWidthKm = world.config.scale.cellWidthKm(world.width)
        val cellHeightKm = world.config.scale.cellHeightKm(world.height)
        val across = (radiusKm / cellWidthKm).toInt()
        val down = (radiusKm / cellHeightKm).toInt()
        val x = cell % world.width
        val y = cell / world.width
        var yes = 0
        var all = 0
        for (dy in -down..down) for (dx in -across..across) {
            val row = y + dy
            if (row < 0 || row >= world.height) continue
            val eastKm = dx * cellWidthKm
            val southKm = dy * cellHeightKm
            if (eastKm * eastKm + southKm * southKm > radiusKm * radiusKm) continue
            all++
            if (counts(row * world.width + Math.floorMod(x + dx, world.width))) yes++
        }
        return yes.toFloat() / all
    }
}
