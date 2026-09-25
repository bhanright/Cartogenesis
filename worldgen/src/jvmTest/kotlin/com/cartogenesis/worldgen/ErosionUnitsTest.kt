package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.HydraulicErosion
import com.cartogenesis.worldgen.pipeline.IncisionWatch
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.SeaLevelStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
import com.cartogenesis.worldgen.pipeline.erodeBlockingWatchingIncision
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The incision has one unit: every limit on the cut is spent in the unit it is measured in, and
 * the clock's years are the years the cut is worth.
 *
 * The drop to a cell's receiver and its height above the shoreline are measured on the
 * shoreline-relative field, whose unit is the land's half of the ruler, `highestLandMetres`; the
 * cut is spent on the height field, whose unit is the whole `reliefSpanMetres`. Spending a cap
 * measured in the first on the second let a cell lose 1.33 times its drop where it was capped at
 * half of it, and 2.67 times its height above the sea where it was capped at that height, so river
 * mouths were cut below the sea in every round (Audit III's B-D1). And the stream-power
 * coefficient's KDoc cancelled the ratio of the two rulers between the slope's rise and the cut,
 * so the years a round was labelled with were 2.67 times too few (B-F1).
 *
 * The two production cases watch the ordered incision pass through [IncisionWatch], because the
 * receiver clamp and the finished heights both hide what the cut asked for. The clock's case runs
 * one round of production on ground whose every term is known. See docs/DESIGN_LEDGER.md, Fix 3.
 */
class ErosionUnitsTest {

    private companion object {
        const val SEED = 42L
        const val SIDE = 512

        /** Seed 42's rounds, watched once and read by both production cases. */
        val watched: Watched by lazy {
            val config = WorldGenConfig(seed = SEED, width = SIDE, height = SIDE)
            val plates = PlateStage.generate(config, TerrainStage.generate(config))
            val watch = Watched(config)
            erodeBlockingWatchingIncision(config, plates.height, plates.upliftRateMmPerYear, watch)
            watch
        }

        /**
         * How far a cut may stand past its bound and still be the bound, as a share of it: the
         * cap is a float product of the drop and the ruler, and the guard reads the drop and the
         * ruler again on its own.
         */
        const val ROUNDING_SHARE = 1e-5f

        /** Metres in a kilometre, for the plane's slope. */
        const val METRES_PER_KM = 1_000.0

        /** The plane's fall, in metres per kilometre: gentle enough that three cells stay under the cap. */
        const val SLOPE_METRES_PER_KM = 1.0

        /** How deep the sea around the plane lies, well below anything the cut can reach. */
        const val DEEP_SEA_METRES = 4_000f

        /** How much deeper each sea cell lies than the one before it, in metres. */
        const val SEA_FLOOR_STEP_METRES = 0.01f

        /** How many cells of the sea the percentile may read as land, where it fills its rank. */
        const val SEA_CELLS_READ_AS_LAND = 1

        /**
         * How many cells below the crest are read. At `K T` the law's cut over one cell of drop runs
         * `0.24 sqrt(A)` in cells of catchment on every grid, so four cells reach the cap at half the
         * drop and three stay under it.
         */
        const val LAW_SET_CATCHMENT_CELLS = 3

        /**
         * How far one round's cut may sit from the law's: a thousandth. The slope is read off floats of
         * the shoreline-relative field and the cut off floats of the height field, a few parts in a
         * hundred thousand between them.
         */
        const val LAW_TOLERANCE = 1e-3
    }

    /** What the rounds asked of every cell and where they left it, tallied for the two cases. */
    private class Watched(private val config: WorldGenConfig) : IncisionWatch {
        private val cellCount = config.width * config.height
        private val streamPower = FloatArray(cellCount) { Float.NaN }
        private val halfDropCap = FloatArray(cellCount)
        private val shorelineCap = FloatArray(cellCount)

        /** Land cells draining straight into the sea that the pass asked to cut by something. */
        var mouthsCut = 0
        /** Every land cell draining into the sea that a round left below its shoreline, by any mechanism. */
        val mouthsBelowShoreline = ArrayList<String>()
        /** Cells whose cut was the half-the-drop cap rather than the stream-power law. */
        var capped = 0
        /** Cells asked to cut more than half the drop to their receiver, in the height field. */
        val pastHalfTheDrop = ArrayList<String>()

        override fun asked(round: Int, cell: Int, receiver: Int, streamPower: Float, halfDropCap: Float, shorelineCap: Float) {
            this.streamPower[cell] = streamPower
            this.halfDropCap[cell] = halfDropCap
            this.shorelineCap[cell] = shorelineCap
        }

        override fun incised(
            round: Int, isLand: BooleanArray, directions: IntArray, ground: FloatArray, relative: FloatArray,
            discharge: FloatArray, landCells: Float, landRange: Float, shorelineHeight: Float, surface: FloatArray
        ) {
            // The ruler read here and not taken from the pass: the land's half of the height field
            // is the declared figure, `WorldScale.landHalfOfField`.
            val landHalfOfField = config.scale.landHalfOfField
            val spanMetres = config.scale.reliefSpanMetres
            for (cell in 0 until cellCount) {
                val receiver = directions[cell]
                if (isLand[cell] && receiver >= 0 && !isLand[receiver] && surface[cell] < shorelineHeight) {
                    mouthsBelowShoreline.add(
                        "round $round cell $cell %.2f m under".format((shorelineHeight - surface[cell]) * spanMetres)
                    )
                }
                val asked = streamPower[cell]
                if (asked.isNaN()) continue
                streamPower[cell] = Float.NaN
                val toSea = !isLand[receiver]
                val requested = minOf(asked, halfDropCap[cell], shorelineCap[cell])
                if (halfDropCap[cell] < asked && halfDropCap[cell] <= shorelineCap[cell]) capped++
                // A drop to the sea is to its surface, the shoreline, which is zero on this field.
                val dropInField = (ground[cell] - if (toSea) 0f else ground[receiver]) * landHalfOfField
                if (requested > 0.5f * dropInField * (1f + ROUNDING_SHARE)) {
                    pastHalfTheDrop.add(
                        "round $round cell $cell asked %.2f m of a %.2f m drop".format(
                            requested * spanMetres, dropInField * spanMetres
                        )
                    )
                }
                if (toSea && requested > 0f) mouthsCut++
            }
        }
    }

    /**
     * No land cell draining into the sea is cut below the sea in the round that cuts it.
     *
     * The shoreline is the base level every river grades to, so a river mouth's floor can reach it
     * and not pass it; the ordered pass skips the receiver clamp where the receiver is water, and
     * the cap at the shoreline is all that holds a mouth up. Every mouth is read, not only those the
     * pass cut, so a notch run earlier in the round is held to the same base level. Shown failing
     * on the tree before Fix 3, where that cap was a height above the sea in the land's unit spent
     * on the field.
     */
    @Test
    fun `no river mouth is cut below the shoreline`() {
        val watch = watched
        println("UNITS seed $SEED@$SIDE: ${watch.mouthsCut} mouth cuts over the rounds, ${watch.mouthsBelowShoreline.size} below the shoreline")
        assertTrue(watch.mouthsCut > 0, "the rounds cut no river mouth at all, so this case saw nothing to test")
        assertTrue(
            watch.mouthsBelowShoreline.isEmpty(),
            "${watch.mouthsBelowShoreline.size} mouths ended a round below the shoreline, over ${watch.mouthsCut} mouth cuts; " +
                "the first ${watch.mouthsBelowShoreline.take(5)}"
        )
    }

    /**
     * No cell is asked to cut more than half the drop to its receiver, measured in the height field
     * the cut is spent on.
     *
     * Read before the receiver clamp, because the clamp refuses a cut past the receiver's new height
     * and the finished heights would hide the request. Shown failing on the tree before Fix 3, where
     * half the drop in the land's unit was 1.33 times the drop in the field's.
     */
    @Test
    fun `no cell is asked to cut more than half its drop`() {
        val watch = watched
        println("UNITS seed $SEED@$SIDE: ${watch.capped} cuts set by the half-the-drop cap, ${watch.pastHalfTheDrop.size} past half the drop")
        assertTrue(watch.capped > 0, "the half-the-drop cap never set a cut, so this case saw nothing to test")
        assertTrue(
            watch.pastHalfTheDrop.isEmpty(),
            "${watch.pastHalfTheDrop.size} cuts asked for more than half the drop in the height field, " +
                "the first ${watch.pastHalfTheDrop.take(5)}"
        )
    }

    /**
     * One round of production removes `K * T * sqrt(A) * S` metres from a channel whose every term
     * is known: the stream-power law with the clock's years, the cover's factor at one and the
     * catchment the rain weights to.
     *
     * The ground is a plane of land 32 cells wide falling due west at [SLOPE_METRES_PER_KM] into a
     * deep sea, on a map a quarter land, so the percentile cut puts the shoreline at its foot. Every
     * cell of it drains due west, so a cell
     * [cellsUpslope] from the eastern crest gathers that many cells' water: the crest drains east
     * into the sea behind it. Only the first few cells below the crest are read, because further
     * down the cut reaches the cap at half the drop and the law no longer sets it. The climate is
     * off, so the rain weights are one and the cover shields nothing; deposition, the notch, the
     * uplift and the flexure are off, so the stream-power incision is the only thing that moves the
     * ground; the relaxation is the identity. Shown failing on the tree before Fix 3, whose clock
     * read 126,179 years a round where the cut spent 336,476 years' worth.
     */
    @Test
    fun `one round removes what the stream-power law and the clock say`() {
        val cellsAcross = 128
        val base = WorldGenConfig(seed = 7L, width = cellsAcross, height = cellsAcross)
        val landColumns = cellsAcross / 4
        val config = base.copy(
            seaLevel = 1f - landColumns.toFloat() / cellsAcross,
            erosion = base.erosion.copy(
                hydraulicRounds = 1,
                climateFeed = false,
                deposition = false,
                outletIncision = false
            ),
            isostasy = base.isostasy.copy(flexure = false),
            sea = base.sea.copy(lowstandMetres = 0f)
        )
        val scale = config.scale
        val firstLandColumn = (cellsAcross - landColumns) / 2
        val fallPerColumnMetres = (SLOPE_METRES_PER_KM * config.cellWidthKm).toFloat()
        val ground = FloatField(cellsAcross, config.height)
        for (row in 0 until config.height) {
            for (column in 0 until cellsAcross) {
                val stepsUp = column - firstLandColumn + 1
                ground.data[row * cellsAcross + column] =
                    if (stepsUp in 1..landColumns) scale.fieldAtAltitude(stepsUp * fallPerColumnMetres)
                    // Every sea cell at its own depth: a sea floor all at one level ties the
                    // percentile, and the cut then reads the whole map as land.
                    else scale.fieldAtAltitude(-DEEP_SEA_METRES - (row * cellsAcross + column) * SEA_FLOOR_STEP_METRES)
            }
        }
        // The percentile hands the sea's highest cell to the land where the sea fills its rank
        // exactly, so the land is the plane and a cell or so of deep water; what the law is held
        // to is the catchment as the stage defines it, the configured land's area over the land's
        // count of cells, which is the plane's own cell to a part in four thousand.
        val landCells = SeaLevelStage.percentileCut(ground, config.seaLevel, scale).landCellCount
        assertTrue(
            landCells - landColumns * config.height in 0..SEA_CELLS_READ_AS_LAND,
            "the sea-level cut did not put the shoreline at the plane's foot: $landCells land cells"
        )
        val before = ground.data.copyOf()
        val after = runBlocking {
            HydraulicErosion.apply(config, ground.copy(), config.seaLevel) { it }
        }

        val crest = firstLandColumn + landColumns - 1
        val landAreaKm2 = (1.0 - config.seaLevel.toDouble()) * scale.worldAreaKm2
        val cellAreaSquareMetres = landAreaKm2 / landCells * METRES_PER_KM * METRES_PER_KM
        val slope = SLOPE_METRES_PER_KM / METRES_PER_KM
        val years = scale.yearsPerHydraulicRound
        val erodibility = config.erosion.bedrockErodibilityPerYear.toDouble()
        val readings = ArrayList<String>()
        var worst = 0.0
        for (cellsUpslope in 1..LAW_SET_CATCHMENT_CELLS) {
            val column = crest - cellsUpslope
            val lawMetres = erodibility * years * sqrt(cellsUpslope * cellAreaSquareMetres) * slope
            // The cap the law has to stay under for the law to be what is read.
            assertTrue(
                lawMetres < 0.5 * fallPerColumnMetres,
                "a cell $cellsUpslope below the crest would be cut %.1f m by the law, past the cap at half its %.1f m drop"
                    .format(lawMetres, fallPerColumnMetres)
            )
            for (row in listOf(config.height / 4, config.height / 2, config.height * 3 / 4)) {
                val cell = row * cellsAcross + column
                val removedMetres = (before[cell] - after.data[cell]).toDouble() * scale.reliefSpanMetres
                val share = removedMetres / lawMetres
                worst = maxOf(worst, abs(share - 1.0))
                readings.add("%d upslope, row %d: %.2f m of %.2f m".format(cellsUpslope, row, removedMetres, lawMetres))
            }
        }
        println("UNITS clock: one round of $years years, " + readings.joinToString("; "))
        assertTrue(
            worst <= LAW_TOLERANCE,
            "one round removed %.3f times what K T sqrt(A) S says at worst: %s".format(1.0 + worst, readings)
        )
    }
}
