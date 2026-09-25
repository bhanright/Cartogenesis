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
 * cut is spent on the height field, whose unit is the whole `reliefSpanMetres`. Spending a limit
 * measured in the first on the second let river mouths be cut below the sea in every round
 * (Audit III's B-D1). And the stream-power coefficient's KDoc cancelled the ratio of the two
 * rulers between the slope's rise and the cut, so the years a round was labelled with were 2.67
 * times too few (B-F1).
 *
 * Since the implicit update (`HydraulicErosion.incise`) the shoreline is the base level a mouth
 * grades to rather than a cap, and the half-the-drop cap is gone; the bounds the update keeps are
 * guarded in `ImplicitIncisionTest`. The mouth case watches production's pass through
 * [IncisionWatch], and the clock's case reads the law's rate the pass was handed on ground whose
 * every term is known. See docs/DESIGN_LEDGER.md, Fix 3 and Fix 3b.
 */
class ErosionUnitsTest {

    private companion object {
        const val SEED = 42L
        const val SIDE = 512

        /** Seed 42's rounds, watched once. */
        val watched: Watched by lazy {
            val config = WorldGenConfig(seed = SEED, width = SIDE, height = SIDE)
            val plates = PlateStage.generate(config, TerrainStage.generate(config))
            val watch = Watched(config)
            erodeBlockingWatchingIncision(config, plates.height, plates.upliftRateMmPerYear, watch)
            watch
        }

        /** Metres in a kilometre, for the plane's slope. */
        const val METRES_PER_KM = 1_000.0

        /** The plane's fall, in metres per kilometre. */
        const val SLOPE_METRES_PER_KM = 1.0

        /** How deep the sea around the plane lies, well below anything the cut can reach. */
        const val DEEP_SEA_METRES = 4_000f

        /** How much deeper each sea cell lies than the one before it, in metres. */
        const val SEA_FLOOR_STEP_METRES = 0.01f

        /** How many cells of the sea the percentile may read as land, where it fills its rank. */
        const val SEA_CELLS_READ_AS_LAND = 1

        /**
         * How many cells below the crest are read: every cell of the plane but the one at its foot,
         * whose receiver is the sea. No cap limits the reading any more; the law's rate is read
         * from the pass whatever `F` it comes to, 0.24 at one cell of catchment and 1.3 at thirty.
         */
        const val CELLS_READ_BELOW_THE_CREST = 30

        /**
         * How far the law's rate may sit from `K T sqrt(A) S`: a thousandth. The slope is read off
         * floats of the height field and the catchment off a float accumulation, a few parts in a
         * hundred thousand between them.
         */
        const val LAW_TOLERANCE = 1e-3
    }

    /** What the rounds did to every cell, tallied for the mouth case. */
    private class Watched(private val config: WorldGenConfig) : IncisionWatch {
        /** Land cells draining straight into the sea that the pass cut. */
        var mouthsCut = 0
        /** Every land cell draining into the sea that a round left below its shoreline, by any mechanism. */
        val mouthsBelowShoreline = ArrayList<String>()

        /** The cells the pass lowered this round, read back once the round has been cut. */
        private val lowered = BooleanArray(config.width * config.height)

        override fun cut(
            round: Int, cell: Int, receiver: Int, courantNumber: Float, before: Float, baseBefore: Float,
            baseAfter: Float, after: Float
        ) {
            lowered[cell] = after < before
        }

        override fun incised(
            round: Int, isLand: BooleanArray, directions: IntArray, ground: FloatArray, relative: FloatArray,
            discharge: FloatArray, landCells: Float, landRange: Float, shorelineHeight: Float, surface: FloatArray
        ) {
            val spanMetres = config.scale.reliefSpanMetres
            for (cell in surface.indices) {
                val wasLowered = lowered[cell]
                lowered[cell] = false
                val receiver = directions[cell]
                if (!isLand[cell] || receiver < 0 || isLand[receiver]) continue
                if (wasLowered) mouthsCut++
                if (surface[cell] < shorelineHeight) {
                    mouthsBelowShoreline.add(
                        "round $round cell $cell %.2f m under".format((shorelineHeight - surface[cell]) * spanMetres)
                    )
                }
            }
        }
    }

    /**
     * No land cell draining into the sea is cut below the sea in the round that cuts it.
     *
     * The shoreline is the base level every river grades to, so a river mouth's floor can reach it
     * and not pass it. Every mouth is read, not only those the pass cut, so a notch run earlier in
     * the round is held to the same base level. Shown failing on the tree before Fix 3, where the
     * cap at the shoreline was a height above the sea in the land's unit spent on the field; the
     * implicit update carries it as the mouth's boundary condition.
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
     * One round of production asks `K * T * sqrt(A) * S` metres of a channel whose every term is
     * known: the stream-power law with the clock's years, the cover's factor at one and the
     * catchment the rain weights to.
     *
     * The ground is a plane of land 32 cells wide falling due west at [SLOPE_METRES_PER_KM] into a
     * deep sea, on a map a quarter land, so the percentile cut puts the shoreline at its foot. Every
     * cell of it drains due west, so a cell [cellsUpslope] from the eastern crest gathers that many
     * cells' water: the crest drains east into the sea behind it. The climate is off, so the rain
     * weights are one and the cover shields nothing; deposition, the notch, the uplift and the
     * flexure are off; the relaxation is the identity.
     *
     * What is read is the law's rate the pass was handed, `F` times the drop to the receiver as the
     * pass found it (`IncisionWatch.cut`), against the law computed here from metres. Since the
     * implicit update no cap stands between the two, so every cell of the plane is read, at `F`
     * from 0.24 to 1.3; what the pass realises from that rate is `ImplicitIncisionTest`'s. Shown
     * failing on the tree before Fix 3, whose clock read 126,179 years a round where the cut spent
     * 336,476 years' worth.
     */
    @Test
    fun `one round asks what the stream-power law and the clock say`() {
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
        val askedMetres = DoubleArray(ground.data.size) { Double.NaN }
        val watch = object : IncisionWatch {
            override fun cut(
                round: Int, cell: Int, receiver: Int, courantNumber: Float, before: Float, baseBefore: Float,
                baseAfter: Float, after: Float
            ) {
                askedMetres[cell] = courantNumber.toDouble() * (before - baseBefore) * scale.reliefSpanMetres
            }

            override fun incised(
                round: Int, isLand: BooleanArray, directions: IntArray, ground: FloatArray, relative: FloatArray,
                discharge: FloatArray, landCells: Float, landRange: Float, shorelineHeight: Float, surface: FloatArray
            ) = Unit
        }
        runBlocking {
            HydraulicErosion.apply(config, ground.copy(), config.seaLevel, incisionWatch = watch) { it }
        }

        val crest = firstLandColumn + landColumns - 1
        val landAreaKm2 = (1.0 - config.seaLevel.toDouble()) * scale.worldAreaKm2
        val cellAreaSquareMetres = landAreaKm2 / landCells * METRES_PER_KM * METRES_PER_KM
        val slope = SLOPE_METRES_PER_KM / METRES_PER_KM
        val years = scale.yearsPerHydraulicRound
        val erodibility = config.erosion.bedrockErodibilityPerYear.toDouble()
        val readings = ArrayList<String>()
        var worst = 0.0
        var read = 0
        for (cellsUpslope in 1..CELLS_READ_BELOW_THE_CREST) {
            val column = crest - cellsUpslope
            val lawMetres = erodibility * years * sqrt(cellsUpslope * cellAreaSquareMetres) * slope
            for (row in listOf(config.height / 4, config.height / 2, config.height * 3 / 4)) {
                val cell = row * cellsAcross + column
                val asked = askedMetres[cell]
                assertTrue(!asked.isNaN(), "the pass never reached the cell $cellsUpslope below the crest on row $row")
                read++
                worst = maxOf(worst, abs(asked / lawMetres - 1.0))
                if (cellsUpslope in listOf(1, 2, 3, 10, CELLS_READ_BELOW_THE_CREST)) {
                    readings.add("%d upslope, row %d: %.2f m of %.2f m".format(cellsUpslope, row, asked, lawMetres))
                }
            }
        }
        println("UNITS clock: one round of $years years, $read cells read, worst %.2e off; ".format(worst) + readings.joinToString("; "))
        assertTrue(
            worst <= LAW_TOLERANCE,
            "one round asked %.4f times what K T sqrt(A) S says at worst: %s".format(1.0 + worst, readings)
        )
    }
}
