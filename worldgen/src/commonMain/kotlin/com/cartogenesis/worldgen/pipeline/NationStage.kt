package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.math.BoxBlur
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WildernessMode
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.naming.NameForge
import com.cartogenesis.worldgen.naming.NameKind
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable

/** A generated realm. Everything here is a starting point the user is free to overrule. */
@Serializable
data class Nation(
    val id: Int,
    val name: String,
    val capitalName: String,
    /** Cell the realm grew from. */
    val originCell: Int,
    val capitalCell: Int,
    /** Seeds this realm's naming style, so neighbours sound like different peoples. */
    val cultureSeed: Long,
    val cellCount: Int,
    /** Estimated people, from the carrying capacity of the land actually held. */
    val population: Long,
    /** Share of the realm's land in each biome, largest first. */
    val biomeShare: List<Pair<Biome, Float>>,
    val coastalCells: Int,
    val riverCells: Int,
    val neighbours: Set<Int>,
    /**
     * The country its people actually occupy, weighted by habitability rather than raw area. A
     * realm can be mostly ice by the map and still be a temperate farming nation in every way that
     * matters, so this - not [biomeShare] - is what the atlas describes it by.
     */
    val heartlandBiome: Biome,
    val government: String,
    val exports: List<String>,
    val imports: List<String>,
    val lore: String
) {
    val isLandlocked: Boolean get() = coastalCells == 0
    /** Largest share of territory by area. Often polar waste on a big realm. */
    val dominantBiome: Biome get() = biomeShare.firstOrNull()?.first ?: Biome.GRASSLAND
}

data class NationResult(
    /** Realm id per cell; [UNCLAIMED] for water and for wilderness beyond any realm's reach. */
    val nationId: IntArray,
    val nations: List<Nation>,
    /** Habitability 0..1 per cell - also what the atlas uses to talk about arable land. */
    val habitability: FloatField
) {
    companion object {
        const val UNCLAIMED = -1
    }
}

/**
 * Step 6: settle the world.
 *
 * Territory is handed out in whole drainage catchments rather than grown cell by cell. A catchment
 * is a piece of country that hangs together — one river system, one set of valleys — so a border
 * between two of them falls on a watershed without anything being told to put it there, and
 * cutting a large catchment along its own trunk river puts the rest of the borders on water.
 * [BasinPartition] makes the pieces and [BasinRealms] shares them out, weighted by how liveable
 * each one is.
 *
 * Two clean-up passes then run on the cells rather than the pieces: poor country can be released
 * as unclaimed wilderness, and a pocket of one realm stranded inside another is given to whichever
 * neighbour surrounds it.
 *
 * See REALISM_PLAN.md, B2 and B3.
 */
object NationStage {

    /**
     * Highest ground, in `SeaLevelResult.relativeElevation` units, that habitability treats as
     * ordinary country, and the most of a cell's score high ground may take away.
     *
     * A slope is harder to farm and harder to hold the higher it goes, but never worthless: even
     * at the cap a mountain cell keeps 15% of its biome's score, because people do live in
     * mountains.
     */
    private const val ELEVATION_PENALTY_PER_UNIT = 1.1f
    private const val MAX_ELEVATION_PENALTY = 0.85f

    /**
     * What a drawable river is worth to the cell it runs through, added rather than multiplied.
     *
     * Fresh water is worth more than anything else on the habitability list, and it is worth it
     * even in country the biome score has already written off — which an added bonus says and a
     * multiplied one does not.
     */
    private const val RIVER_BONUS = 0.35f

    /**
     * Elevation above which a cell counts toward a realm's mountain share, in
     * `SeaLevelResult.relativeElevation` units. What the atlas means by "mountainous".
     */
    private const val HIGH_GROUND_ELEVATION = 0.4f

    /**
     * Floors on the catchment sizes, in cells, for the two shares in `NationsConfig` that are
     * expressed against the whole world's land.
     *
     * A share of a very small map rounds to nothing, and a partition into one-cell units is not a
     * partition. Both are a resolution guard rather than a modelling choice.
     */
    private const val SMALLEST_LARGE_BASIN = 16
    private const val SMALLEST_KEPT_BASIN = 4

    /**
     * Decorrelates the realm draw from every other stage's use of the world seed, so that changing
     * the realm count does not move the cultures or the landmarks.
     */
    private const val REALM_SEED_MULTIPLIER = 8191L
    private const val REALM_SEED_OFFSET = 17L

    /**
     * Times the enclave pass runs. Giving one pocket away can join two others into a piece worth
     * keeping, so the answer is not settled in one go; three is where it stops changing, and the
     * pass returns early the moment a round gives nothing away.
     */
    private const val ENCLAVE_PASSES = 3

    /**
     * How large the piece holding a realm's capital has to be, relative to that realm's largest
     * piece, for the capital to stay where it is.
     *
     * Written as the reciprocal: the piece must be at least a quarter of the largest. Wide enough
     * that a capital is not uprooted over a marginal difference, tight enough that a realm cannot
     * be governed from a sliver.
     */
    private const val CAPITAL_KEEP_SHARE = 4

    /**
     * Land neighbours a coastal cell needs before it counts as fully sheltered.
     *
     * Seven of the eight, since a cell with all eight is not on the coast at all. A crude
     * stand-in for a bay against a headland, but it is the one the grid actually knows.
     */
    private const val MOST_SHELTERING_NEIGHBOURS = 7f

    /**
     * Sea-surface anomaly, in degrees Celsius, at which the harbour and fishery terms reach their
     * full configured value.
     *
     * Six degrees is about as far as a strong current carries water from its latitude's own mean —
     * the Gulf Stream off Norway, the Humboldt off Peru — so it is the natural full scale.
     */
    private const val FULL_ANOMALY_C = 6f

    /**
     * How much of the warm-harbour bonus an entirely exposed coast still gets, and how much the
     * rest of it is worth once the coast is fully sheltered.
     *
     * A warm current is worth something to any coast it washes; a sheltered one can also put a
     * port in it. The two sum to one, so a fully sheltered coast gets the whole configured bonus.
     */
    private const val EXPOSED_HARBOUR_SHARE = 0.45f
    private const val SHELTERED_HARBOUR_SHARE = 0.55f

    /**
     * Share of the world's runoff a cell must carry for habitability to treat it as being on a
     * river. `RiverConfig.sourceFlowShare`'s default — see [drawableRiverFlow].
     */
    private const val DRAWN_RIVER_FLOW_SHARE = 0.0006f

    /**
     * Radii for the two blurred copies [describe] judges a capital site on, as a divisor of the
     * map width so a capital is chosen on the same real country at every resolution: the
     * hinterland it can be fed from, and the ground it has to stand above to be defensible.
     */
    private const val HINTERLAND_RADIUS_DIVISOR = 64
    private const val RELIEF_RADIUS_DIVISOR = 96

    /** Smallest useful blur radius, so a small map still averages more than a single cell. */
    private const val MIN_BLUR_RADIUS = 2

    /**
     * Two, one short of [BoxBlur.PASSES_FOR_GAUSSIAN]. What these two blurs feed is a comparison
     * between neighbouring cells' scores, not a picture, so the square kernel's corners never
     * reach anything a reader sees.
     */
    private const val BLUR_PASSES = 2

    /**
     * What each thing is worth to a capital site, on one scale.
     *
     * Fresh water is the one non-negotiable and is worth more than a harbour; the hinterland comes
     * next, because a capital has to be fed; the coast is worth least, since a flat coastal bonus
     * of any size puts every coastal realm's capital on the shore. See [describe].
     */
    private const val CAPITAL_OWN_GROUND = 0.8f
    private const val CAPITAL_ON_RIVER = 0.45f
    private const val CAPITAL_ON_COAST = 0.18f
    private const val CAPITAL_HINTERLAND = 0.6f
    private const val CAPITAL_HEAD_OF_NAVIGATION = 0.10f
    private const val CAPITAL_DEFENSIBILITY = 2.5f

    /**
     * How far above its surroundings, in `SeaLevelResult.relativeElevation` units, a capital site
     * still gains from standing.
     *
     * A hill is defensible; a mountain is a different problem and not a better capital, so the
     * term is capped rather than left to run.
     */
    private const val MOST_USEFUL_RELIEF = 0.12f

    /**
     * Turns the world seed and a realm id into the seed its names and its atlas entry are drawn
     * from, so neighbouring realms sound like different peoples.
     *
     * A large prime for the world and a smaller one per realm, so that changing either the seed or
     * the realm count moves every realm's naming rather than shifting the same names along by one.
     */
    private const val CULTURE_SEED_MULTIPLIER = 1_000_003L
    private const val CULTURE_SEED_STRIDE = 7919L

    /** The same again for the prose, so a realm's name and its lore do not share a stream. */
    private const val ATLAS_SEED_MULTIPLIER = 17L
    private const val ATLAS_SEED_OFFSET = 3L

    /** Floor on the settled-biome weight, so a realm with no liveable ground divides by something. */
    private const val MIN_SETTLED_WEIGHT = 1e-4f

    suspend fun generate(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        climate: ClimateResult,
        rivers: RiverResult,
        ocean: OceanResult
    ): NationResult {
        /*
         * Between the sweeps below. Settling realms is the second-longest stage at export sizes and
         * the last one a reader is likely to be waiting through, so a stop asked for while the
         * frontiers are being drawn is answered at the next sweep rather than at the end.
         */
        suspend fun stopIfAsked() = currentCoroutineContext().ensureActive()

        val cellsAcross = config.width
        val cellsDown = config.height
        val nationsConfig = config.nations
        val cellCount = cellsAcross * cellsDown

        val habitability = habitability(config, sea, climate, rivers, ocean)
        val nationId = IntArray(cellCount) { NationResult.UNCLAIMED }

        if (sea.landCellCount == 0 || nationsConfig.nationCount <= 0) {
            return NationResult(nationId, emptyList(), habitability)
        }

        val landCells = sea.landCellCount
        val catchments = BasinPartition.compute(
            config, sea, rivers,
            (landCells * nationsConfig.maxBasinShare).toInt().coerceAtLeast(SMALLEST_LARGE_BASIN)
        )
        // Cut the big ones along their trunk, so some frontiers are rivers and not only divides.
        val banked = BasinPartition.splitAlongTrunks(
            config, sea, rivers, catchments,
            rivers.flowAccumulation.data.max() * nationsConfig.riverBorderShare
        )
        val units = BasinPartition.mergeSmall(
            config, sea, banked,
            (landCells * nationsConfig.minBasinShare).toInt().coerceAtLeast(SMALLEST_KEPT_BASIN)
        )
        stopIfAsked()
        val assignment = BasinRealms.assign(
            config, sea, units, habitability,
            Random(config.seed * REALM_SEED_MULTIPLIER + REALM_SEED_OFFSET)
        )
        assignment.realmOf.copyInto(nationId)
        val origins = assignment.origins
        if (origins.isEmpty()) return NationResult(nationId, emptyList(), habitability)
        checkRealmIds(nationId, origins.size, "BasinRealms.assign")

        // Wilderness first, enclaves second. Releasing poor ground can cut a realm into pieces,
        // and dissolving enclaves before that happened left the fragments it made behind.
        val capitals = origins.toMutableList()
        stopIfAsked()
        if (nationsConfig.wilderness != WildernessMode.CLAIM_ALL_LAND) {
            leaveWilderness(config, sea, habitability, nationId, capitals)
        }
        stopIfAsked()
        dissolveEnclaves(config, sea, habitability, nationId, capitals)
        checkRealmIds(nationId, capitals.size, "dissolveEnclaves")
        return NationResult(
            nationId,
            describe(config, sea, climate, rivers, habitability, nationId, capitals),
            habitability
        )
    }

    /**
     * Fails where a bad realm id was written rather than where it lands.
     *
     * Every per-realm array in [describe] is sized by the capital list, so a cell holding an id
     * past the end of that list throws an array index error out of a counting loop that had
     * nothing to do with putting it there — `counts[owner]++`, hundreds of lines and two steps
     * away from whichever of [BasinRealms.assign] and [dissolveEnclaves] actually did it. That is
     * how this arrived as a bug report, and reading the stack told nobody anything.
     *
     * Two passes over the grid against a generation that has already done thousands is free, and
     * what it buys is a message that names the step. Checked rather than assumed because the two
     * candidates are a hundred lines apart and only one of them can be wrong at a time.
     */
    private fun checkRealmIds(nationId: IntArray, realmCount: Int, after: String) {
        for (cell in nationId.indices) {
            val realm = nationId[cell]
            if (realm == NationResult.UNCLAIMED || realm in 0 until realmCount) continue
            throw IllegalStateException(
                "NationStage: after $after, cell $cell holds realm id $realm, " +
                    "but the world has only $realmCount realms"
            )
        }
    }


    /**
     * Gives away any pocket of a realm that is stranded inside its neighbours.
     *
     * Growth is a race between realms, and a race leaves debris: ground reached late by a realm
     * whose route home was then taken by somebody else, or a fragment orphaned when a schism cut
     * the land between it and its capital. Each one renders as a speck of the wrong colour in the
     * middle of another country, and a map speckled with them reads as noise rather than history.
     *
     * Done on cells rather than on catchments, because that is where they actually appear: a
     * catchment cut along its trunk river can leave a bank in two pieces, so a partition that looks
     * whole at the unit level is not whole on the map. An earlier version worked on units and
     * removed almost none of them.
     *
     * Only pieces you can *walk* out of are given away. A realm's overseas islands touch no other
     * realm by land, and they stay — that is the difference between an accident and a colony. Nor
     * is the piece holding a realm's capital ever given away, whatever its size.
     */
    private suspend fun dissolveEnclaves(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        habitability: FloatField,
        nationId: IntArray,
        // Mutable, because a capital can move: see the keep rule below.
        origins: MutableList<Int>
    ) {
        val cellsAcross = config.width
        val cellsDown = config.height
        val cellCount = cellsAcross * cellsDown
        val capitalCells = HashSet<Int>()
        origins.forEach { capitalCells.add(it) }

        // Repeated, because giving one pocket away can join two others into a piece worth keeping.
        repeat(ENCLAVE_PASSES) {
            currentCoroutineContext().ensureActive()
            // Every connected run of one realm's own land, numbered. A realm normally has one; the
            // extras are the islands, the exclaves and the debris this function is here to clear.
            val pieceOfCell = IntArray(cellCount) { -1 }
            val pieces = ArrayList<MutableList<Int>>()
            for (start in 0 until cellCount) {
                if (pieceOfCell[start] >= 0 || !sea.isLand[start]) continue
                val realm = nationId[start]
                if (realm == NationResult.UNCLAIMED) continue
                val pieceId = pieces.size
                val pieceCells = ArrayList<Int>()
                val toVisit = ArrayDeque<Int>()
                toVisit.addLast(start)
                pieceOfCell[start] = pieceId
                while (toVisit.isNotEmpty()) {
                    val cell = toVisit.removeLast()
                    pieceCells.add(cell)
                    val column = cell % cellsAcross
                    val row = cell / cellsAcross
                    for (rowStep in -1..1) {
                        val neighbourRow = row + rowStep
                        if (neighbourRow !in 0 until cellsDown) continue
                        for (columnStep in -1..1) {
                            val neighbour = neighbourRow * cellsAcross +
                                ((column + columnStep + cellsAcross) % cellsAcross)
                            if (pieceOfCell[neighbour] < 0 && sea.isLand[neighbour] &&
                                nationId[neighbour] == realm
                            ) {
                                pieceOfCell[neighbour] = pieceId
                                toVisit.addLast(neighbour)
                            }
                        }
                    }
                }
                pieces.add(pieceCells)
            }

            // What each realm keeps: its largest piece. A nation is its territory, so if the
            // capital is not on the territory, the capital moves — the capital's piece keeps its
            // status only when it is within [CAPITAL_KEEP_SHARE] of the largest, so a capital is
            // not uprooted over a marginal difference. Letting the capital's piece win outright
            // produced a nineteen-cell sovereign state whose real country had been given away.
            val largestPieceOfRealm = HashMap<Int, Int>()
            val largestPieceCells = HashMap<Int, Int>()
            pieces.forEachIndexed { pieceId, pieceCells ->
                val realm = nationId[pieceCells[0]]
                if (pieceCells.size > (largestPieceCells[realm] ?: -1)) {
                    largestPieceCells[realm] = pieceCells.size
                    largestPieceOfRealm[realm] = pieceId
                }
            }
            val keptPieceOfRealm = HashMap<Int, Int>()
            pieces.forEachIndexed { pieceId, pieceCells ->
                val realm = nationId[pieceCells[0]]
                if (pieceCells.none { it in capitalCells }) return@forEachIndexed
                val largest = largestPieceOfRealm[realm] ?: pieceId
                if (pieceId == largest ||
                    pieceCells.size * CAPITAL_KEEP_SHARE >= (largestPieceCells[realm] ?: 0)
                ) {
                    keptPieceOfRealm[realm] = pieceId
                } else {
                    keptPieceOfRealm[realm] = largest
                    // Best ground in the piece being kept. Scan order breaks ties, which is the
                    // same on every platform.
                    val movedCapital = pieces[largest].maxByOrNull { habitability.data[it] }
                        ?: return@forEachIndexed
                    capitalCells.remove(origins[realm])
                    origins[realm] = movedCapital
                    capitalCells.add(movedCapital)
                }
            }
            largestPieceOfRealm.forEach { (realm, pieceId) ->
                if (realm !in keptPieceOfRealm) keptPieceOfRealm[realm] = pieceId
            }

            var changed = false
            pieces.forEachIndexed { pieceId, pieceCells ->
                val realm = nationId[pieceCells[0]]
                if (keptPieceOfRealm[realm] == pieceId) return@forEachIndexed

                // Who is next door on foot, and how much of this pocket's edge each of them holds.
                val edgeHeldBy = HashMap<Int, Int>()
                pieceCells.forEach { cell ->
                    val column = cell % cellsAcross
                    val row = cell / cellsAcross
                    for (rowStep in -1..1) {
                        val neighbourRow = row + rowStep
                        if (neighbourRow !in 0 until cellsDown) continue
                        for (columnStep in -1..1) {
                            val neighbour = neighbourRow * cellsAcross +
                                ((column + columnStep + cellsAcross) % cellsAcross)
                            if (!sea.isLand[neighbour]) continue
                            val other = nationId[neighbour]
                            if (other != NationResult.UNCLAIMED && other != realm) {
                                edgeHeldBy[other] = (edgeHeldBy[other] ?: 0) + 1
                            }
                        }
                    }
                }
                // No land neighbours at all means an island, not an enclave. Leave it be.
                // Ties go to the lower realm id: a HashMap's iteration order differs between the
                // JVM and Wasm, and "whichever came first" is not a tie-break, it is a coin toss.
                val host = edgeHeldBy.entries
                    .maxWithOrNull(
                        compareBy<Map.Entry<Int, Int>> { it.value }.thenByDescending { it.key }
                    )
                    ?.key ?: return@forEachIndexed
                pieceCells.forEach { nationId[it] = host }
                changed = true
            }
            if (!changed) return
        }
    }

    /**
     * Gives the worst country back to nobody.
     *
     * Under the basin model every catchment is claimed by somebody, because a catchment is a
     * geographic fact rather than a statement about who lives there. That suits a map where the
     * borders are meant to look complete, and not one where the ice cap and the deep desert are
     * supposed to belong to no state — so when the user asks for wilderness, ground too poor to
     * settle is released.
     *
     * Released by habitability rather than by distance from a capital, which is the honest reason
     * such country is empty: nobody bothers with it, however near it happens to be.
     */
    private fun leaveWilderness(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        habitability: FloatField,
        nationId: IntArray,
        origins: List<Int>
    ) {
        val settleableFrom = config.nations.minSeedHabitability
        val capitals = origins.toHashSet()
        for (cell in nationId.indices) {
            if (!sea.isLand[cell]) continue
            // A capital is never wilderness, whatever the ground around it scores.
            if (cell in capitals) continue
            if (habitability.data[cell] < settleableFrom) nationId[cell] = NationResult.UNCLAIMED
        }
    }

    /**
     * How well people could live on each cell, 0..1, zero over water. Driven by biome, then nudged
     * by fresh water, by how punishing the terrain is, and — on the coast — by what the sea
     * offshore is doing.
     */
    private fun habitability(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        climate: ClimateResult,
        rivers: RiverResult,
        ocean: OceanResult
    ): FloatField {
        val cellsAcross = config.width
        val cellsDown = config.height
        val field = FloatField(cellsAcross, cellsDown)
        val drawableRiverFlow = drawableRiverFlow(sea, climate)

        for (cell in 0 until cellsAcross * cellsDown) {
            if (!sea.isLand[cell]) continue

            // What the ground itself is worth, before water, relief and the sea have their say.
            var score = when (climate.biome[cell]) {
                Biome.TEMPERATE_FOREST, Biome.GRASSLAND -> 1.0f
                // Wheat, olive and vine country, and the densest wet-rice country there is: two
                // of the most thickly settled landscapes on Earth, so neither may fall through to
                // the "some other biome" rate.
                Biome.MEDITERRANEAN -> 0.95f
                Biome.MONSOON_FOREST -> 0.9f
                Biome.TROPICAL_SEASONAL_FOREST, Biome.SAVANNA -> 0.85f
                Biome.TEMPERATE_RAINFOREST -> 0.75f
                Biome.SHRUBLAND -> 0.6f
                Biome.TROPICAL_RAINFOREST -> 0.5f
                Biome.TAIGA -> 0.4f
                Biome.TUNDRA -> 0.15f
                Biome.DESERT -> 0.12f
                Biome.ALPINE -> 0.08f
                Biome.ICE_SHEET -> 0.02f
                else -> 0.05f
            }

            // High ground is hard to farm and hard to hold.
            val elevation = sea.relativeElevation.data[cell]
            score *= (1f - (elevation * ELEVATION_PENALTY_PER_UNIT)
                .coerceIn(0f, MAX_ELEVATION_PENALTY))

            // Fresh water is worth more than anything else on this list.
            if (rivers.flowAccumulation.data[cell] >= drawableRiverFlow) {
                score = (score + RIVER_BONUS).coerceAtMost(1f)
            }

            field.data[cell] = score.coerceIn(0f, 1f)
        }

        if (config.ocean.enabled) applyCoastalValue(config, sea, field, ocean)
        return field
    }

    /**
     * What the water offshore is worth to the coast beside it.
     *
     * Two separate things, pulling opposite ways on temperature. A warm current gives an ice-free
     * harbour and a mild hinterland; a cold one gives fog and a short growing season. But cold
     * water rising over a shallow shelf is where the fish are, and a fishery will support a coast
     * whose own soil never could. So a warm coast is a better place to live and a cold shelf is a
     * better place to eat, and both end up on the map.
     *
     * Only the seaward cells get this, and it is applied after the main loop so the biome score it
     * modifies is already final.
     */
    private fun applyCoastalValue(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        field: FloatField,
        ocean: OceanResult
    ) {
        val cellsAcross = config.width
        val cellsDown = config.height
        val nationsConfig = config.nations

        for (row in 0 until cellsDown) {
            for (column in 0 until cellsAcross) {
                val cell = row * cellsAcross + column
                if (!sea.isLand[cell]) continue

                var anomalySumC = 0f
                var shelfUpwellingC = 0f
                var waterNeighbours = 0
                var landNeighbours = 0

                for (rowStep in -1..1) {
                    val neighbourRow = row + rowStep
                    if (neighbourRow !in 0 until cellsDown) continue
                    for (columnStep in -1..1) {
                        if (columnStep == 0 && rowStep == 0) continue
                        val neighbourColumn = (column + columnStep + cellsAcross) % cellsAcross
                        val neighbour = neighbourRow * cellsAcross + neighbourColumn
                        if (sea.isLand[neighbour]) {
                            landNeighbours++
                            continue
                        }
                        waterNeighbours++
                        val anomalyC = ocean.anomaly.data[neighbour]
                        anomalySumC += anomalyC
                        // Shelf, not open ocean: depth below sea level, small means shallow.
                        val depth = -sea.relativeElevation.data[neighbour]
                        if (anomalyC < 0f && depth < nationsConfig.navigableDepth) {
                            shelfUpwellingC += -anomalyC
                        }
                    }
                }
                if (waterNeighbours == 0) continue

                val meanAnomalyC = anomalySumC / waterNeighbours
                // A bay ringed by land is sheltered; an exposed headland is not. Neighbouring land
                // count is a crude stand-in for that, but it is the one the grid actually knows.
                val shelter = (landNeighbours / MOST_SHELTERING_NEIGHBOURS).coerceIn(0f, 1f)
                val harbour = (meanAnomalyC / FULL_ANOMALY_C).coerceIn(-1f, 1f) *
                    nationsConfig.warmHarbourBonus *
                    (EXPOSED_HARBOUR_SHARE + SHELTERED_HARBOUR_SHARE * shelter)
                val fishery =
                    (shelfUpwellingC / waterNeighbours / FULL_ANOMALY_C).coerceIn(0f, 1f) *
                        nationsConfig.upwellingFisheryBonus

                field.data[cell] = (field.data[cell] + harbour + fishery).coerceIn(0f, 1f)
            }
        }
    }

    /**
     * The accumulated flow at which `RiverStage` would draw a channel, so habitability and the map
     * agree about which cells are on a river — computed from the rainfall rather than by
     * re-tracing anything.
     *
     * This pins `RiverConfig.sourceFlowShare`'s default rather than reading the setting, so a
     * world generated with that slider moved has a habitability field built against the default
     * river density while the map draws a different one. Reading the setting would leave a default
     * world untouched and move every other one, which is a change to what the generator produces
     * and not a rename; noted here rather than made, because it is a difference and not a design.
     */
    private fun drawableRiverFlow(sea: SeaLevelResult, climate: ClimateResult): Float {
        var totalRunoff = 0f
        for (cell in sea.isLand.indices) {
            if (sea.isLand[cell]) {
                totalRunoff += RiverStage.runoffWeight(climate.precipitation.data[cell])
            }
        }
        return (totalRunoff * DRAWN_RIVER_FLOW_SHARE).coerceAtLeast(RiverStage.MIN_SOURCE_FLOW)
    }

    /**
     * Everything the atlas says about each realm, from the map it was given: area, coast, rivers,
     * neighbours, biome shares, a capital, a population and the prose.
     *
     * [origins] is one cell per realm, indexed by realm id, and every per-realm array below is
     * sized by it — which is what [checkRealmIds] exists to protect.
     */
    private fun describe(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        climate: ClimateResult,
        rivers: RiverResult,
        habitability: FloatField,
        nationId: IntArray,
        origins: List<Int>
    ): List<Nation> {
        val cellsAcross = config.width
        val cellsDown = config.height
        val drawableRiverFlow = drawableRiverFlow(sea, climate)
        val squareKmPerCell = config.squareKilometresPerCell

        // Resources discovered near a realm are folded in later by the landmark stage; realms
        // start from what their own land yields.
        val resourcesNear = HashMap<Int, List<String>>()
        val counts = IntArray(origins.size)
        val coastal = IntArray(origins.size)
        val riverine = IntArray(origins.size)
        val capacity = DoubleArray(origins.size)
        val biomes = Array(origins.size) { HashMap<Biome, Int>() }
        val neighbours = Array(origins.size) { HashSet<Int>() }
        // Blurred copies, so a capital can be judged on the country around it rather than the
        // single cell it stands on: how much food its hinterland could grow, and whether it sits
        // above the surrounding ground or in a hollow.
        val hinterland = habitability.copy()
        BoxBlur.apply(
            hinterland,
            radius = (config.width / HINTERLAND_RADIUS_DIVISOR).coerceAtLeast(MIN_BLUR_RADIUS),
            passes = BLUR_PASSES
        )
        val smoothedElevation = sea.relativeElevation.copy()
        BoxBlur.apply(
            smoothedElevation,
            radius = (config.width / RELIEF_RADIUS_DIVISOR).coerceAtLeast(MIN_BLUR_RADIUS),
            passes = BLUR_PASSES
        )

        val bestCapital = FloatArray(origins.size) { -1f }
        val capitalCell = IntArray(origins.size) { -1 }
        // Biomes weighted by how liveable they are, so the heartland reflects where people are
        // rather than how much frozen waste the realm happens to enclose.
        val heartlandBiome = Array(origins.size) { HashMap<Biome, Float>() }
        val highGround = IntArray(origins.size)

        for (row in 0 until cellsDown) {
            for (column in 0 until cellsAcross) {
                val cell = row * cellsAcross + column
                val owner = nationId[cell]
                if (owner == NationResult.UNCLAIMED) continue

                counts[owner]++
                biomes[owner][climate.biome[cell]] =
                    (biomes[owner][climate.biome[cell]] ?: 0) + 1
                capacity[owner] += habitability.data[cell].toDouble()
                heartlandBiome[owner][climate.biome[cell]] =
                    (heartlandBiome[owner][climate.biome[cell]] ?: 0f) + habitability.data[cell]
                if (sea.relativeElevation.data[cell] > HIGH_GROUND_ELEVATION) highGround[owner]++

                var touchesSea = false
                FlowRouting.forEachNeighbour(cellsAcross, cellsDown, column, row) { neighbour ->
                    if (!sea.isLand[neighbour]) touchesSea = true
                    val other = nationId[neighbour]
                    if (other != NationResult.UNCLAIMED && other != owner) {
                        neighbours[owner].add(other)
                    }
                }
                if (touchesSea) coastal[owner]++

                val onRiver = rivers.flowAccumulation.data[cell] >= drawableRiverFlow
                if (onRiver) riverine[owner]++

                // Why a capital ends up somewhere, in roughly the order history cares about.
                // Fresh water first: it is the one non-negotiable, and a river is worth more than
                // a harbour. Then the hinterland, since a capital needs land around it that can
                // feed the place. Then defensibility — high ground relative to its surroundings.
                // A harbour counts, but modestly: a flat bonus large enough to matter put every
                // coastal realm's capital on the shore, which no real map shows.
                var score = habitability.data[cell] * CAPITAL_OWN_GROUND
                if (onRiver) score += CAPITAL_ON_RIVER
                if (touchesSea) score += CAPITAL_ON_COAST
                score += hinterland.data[cell] * CAPITAL_HINTERLAND

                // The head of navigation, not the river mouth. Historically a capital sits where
                // boats coming upriver have to stop and unload — inland enough to be defensible
                // and out of the floodplain, but still reachable by water. Without this the best
                // score is always the river mouth, which put nearly every capital on the shore.
                if (onRiver && !touchesSea) score += CAPITAL_HEAD_OF_NAVIGATION

                val relief = sea.relativeElevation.data[cell] - smoothedElevation.data[cell]
                score += relief.coerceIn(0f, MOST_USEFUL_RELIEF) * CAPITAL_DEFENSIBILITY

                if (score > bestCapital[owner]) {
                    bestCapital[owner] = score
                    capitalCell[owner] = cell
                }
            }
        }

        return origins.indices.mapNotNull { id ->
            if (counts[id] == 0) return@mapNotNull null
            val realmCells = counts[id].toFloat()
            val cultureSeed = config.seed * CULTURE_SEED_MULTIPLIER + id * CULTURE_SEED_STRIDE
            val random = Random(cultureSeed * ATLAS_SEED_MULTIPLIER + ATLAS_SEED_OFFSET)

            val name = NameForge.name(cultureSeed, NameKind.REALM, 0L)
            val capitalName = NameForge.name(cultureSeed, NameKind.SETTLEMENT, 1L)
            val shares = biomes[id].entries.sortedByDescending { it.value }
                .map { it.key to it.value / realmCells }
            val heartland = heartlandBiome[id].entries.maxByOrNull { it.value }?.key
                ?: shares.firstOrNull()?.first ?: Biome.GRASSLAND

            val population = (capacity[id] * squareKmPerCell *
                config.nations.peoplePerArableKm2).roundToLong()
            val coastalShare = coastal[id] / realmCells
            val riverShare = riverine[id] / realmCells
            val mountainShare = highGround[id] / realmCells
            val landlocked = coastal[id] == 0

            val government = Atlas.government(random, coastalShare, counts[id], neighbours[id].size)
            // Production follows people, not acreage — a realm whose bulk is polar waste still
            // makes its living off the temperate ground its farmers actually work.
            val settledShares = heartlandBiome[id].entries
                .sortedByDescending { it.value }
                .let { entries ->
                    val weight = entries.sumOf { it.value.toDouble() }.toFloat()
                        .coerceAtLeast(MIN_SETTLED_WEIGHT)
                    entries.map { it.key to it.value / weight }
                }
            val exports = Atlas.exports(
                random, settledShares, coastalShare, riverShare, mountainShare,
                resourcesNear[id].orEmpty()
            )

            Nation(
                id = id,
                name = name,
                capitalName = capitalName,
                originCell = origins[id],
                capitalCell = capitalCell[id].takeIf { it >= 0 } ?: origins[id],
                cultureSeed = cultureSeed,
                cellCount = counts[id],
                population = population,
                biomeShare = shares,
                coastalCells = coastal[id],
                riverCells = riverine[id],
                neighbours = neighbours[id],
                heartlandBiome = heartland,
                government = government,
                exports = exports,
                imports = Atlas.imports(settledShares, coastalShare, mountainShare, exports),
                lore = Atlas.lore(
                    random, name, government, heartland, landlocked,
                    neighbours[id].size, population, capitalName
                )
            )
        }
    }

}

