package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.MarchSchedule
import com.cartogenesis.worldgen.pipeline.MoistureMarch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How a lap of the moisture march is ordered ([MarchSchedule]) and that the order and the pieces
 * the rows are marched in leave the answer alone.
 *
 * The schedule's clauses are the ones the march's lock-free reads rest on: in a slot the two layers
 * stand on different columns; each layer marches the columns in the order a plain sweep does; and
 * the layer that crosses a cell second sees the first's water of that lap, which is every cell but
 * the one the boundary layer marches westward and the free troposphere eastward. The march's
 * clauses hold the world's answer to the same bits whatever the pieces: rows of a column are
 * independent, so a race or a read across pieces would move a bit on some run.
 */
class MarchScheduleTest {

    private companion object {
        val widths = listOf(1, 5, 31, 32, 33, 64, 100, 2048)
        val stageSizes = listOf(1, 7, 32)
        const val ROWS = 128
    }

    private fun forEachSchedule(check: (MarchSchedule) -> Unit) {
        for (width in widths) for (size in stageSizes) check(MarchSchedule(width, size))
    }

    @Test
    fun `each layer marches every stage once, in order, over the columns of a plain sweep`() {
        forEachSchedule { schedule ->
            for (boundaryLayer in listOf(true, false)) {
                val stages = (0 until schedule.slotCount).map { schedule.stageAt(boundaryLayer, it) }.filter { it >= 0 }
                assertEquals((0 until schedule.stageCount).toList(), stages, "stages of ${schedule.cellsAcross} columns in ${schedule.columnsPerStage}")
                val columns = ArrayList<Int>()
                for (stage in stages) {
                    val step = if (schedule.isEastward(stage)) 1 else -1
                    var column = schedule.firstColumn(stage)
                    while (true) {
                        columns += column
                        if (column == schedule.lastColumn(stage)) break
                        column += step
                    }
                }
                val plainSweeps = (0 until schedule.cellsAcross).toList() + (schedule.cellsAcross - 1 downTo 0).toList()
                assertEquals(plainSweeps, columns, "columns of ${schedule.cellsAcross} in stages of ${schedule.columnsPerStage}")
            }
        }
    }

    @Test
    fun `in a slot the two layers stand on different columns`() {
        forEachSchedule { schedule ->
            for (slot in 0 until schedule.slotCount) {
                val boundary = schedule.stageAt(true, slot)
                val free = schedule.stageAt(false, slot)
                if (boundary < 0 || free < 0) continue
                val boundaryColumns = columnsOf(schedule, boundary)
                val freeColumns = columnsOf(schedule, free)
                assertTrue(boundaryColumns.intersect(freeColumns).isEmpty(),
                    "slot $slot of ${schedule.cellsAcross} columns in stages of ${schedule.columnsPerStage}: stages $boundary and $free share columns")
            }
        }
    }

    @Test
    fun `the free troposphere crosses a cell after the boundary layer except where it is marched eastward and the boundary layer westward`() {
        forEachSchedule { schedule ->
            val visit = HashMap<Triple<Boolean, Boolean, Int>, Int>()
            for (slot in 0 until schedule.slotCount) {
                for (boundaryLayer in listOf(true, false)) {
                    val stage = schedule.stageAt(boundaryLayer, slot)
                    if (stage < 0) continue
                    for (column in columnsOf(schedule, stage)) visit[Triple(boundaryLayer, schedule.isEastward(stage), column)] = slot
                }
            }
            for (column in 0 until schedule.cellsAcross) {
                for (boundaryEastward in listOf(true, false)) for (freeEastward in listOf(true, false)) {
                    val boundarySlot = checkNotNull(visit[Triple(true, boundaryEastward, column)])
                    val freeSlot = checkNotNull(visit[Triple(false, freeEastward, column)])
                    val freeFirst = !boundaryEastward && freeEastward
                    assertTrue(boundarySlot != freeSlot, "column $column crossed by both layers in one slot")
                    assertEquals(freeFirst, freeSlot < boundarySlot,
                        "column $column of ${schedule.cellsAcross} in stages of ${schedule.columnsPerStage}, boundary layer eastward $boundaryEastward, free troposphere eastward $freeEastward")
                }
            }
        }
    }

    private fun columnsOf(schedule: MarchSchedule, stage: Int): Set<Int> {
        val first = schedule.firstColumn(stage)
        val last = schedule.lastColumn(stage)
        return (minOf(first, last)..maxOf(first, last)).toSet()
    }

    /**
     * A small world with land, a ridge, and winds that reverse along the rows and differ between
     * the layers, so that both sweeps and every pairing of the two layers' sweeps are marched.
     */
    private fun marched(rowPieces: Int): List<FloatArray> {
        val config = WorldGenConfig.forRows(42L, ROWS)
        val columns = config.width
        val cells = columns * config.height
        val isLand = BooleanArray(cells) { cell ->
            val column = cell % columns
            val row = cell / columns
            (column in columns / 8 until columns / 3 && row in config.height / 5 until 4 * config.height / 5) ||
                (column in columns / 2 until 3 * columns / 5 && row in config.height / 3 until config.height / 2)
        }
        val elevationM = FloatArray(cells) { cell ->
            if (!isLand[cell]) 0f else (200.0 + 1_800.0 * (0.5 + 0.5 * sin((cell % columns) * 0.45))).toFloat()
        }
        val relative = FloatArray(cells) { cell -> if (isLand[cell]) 0.2f + elevationM[cell] / 4_000f else -0.4f }
        fun season(shift: Double): MoistureMarch.Season {
            val east = FloatArray(cells)
            val south = FloatArray(cells)
            val freeEast = FloatArray(cells)
            val freeSouth = FloatArray(cells)
            for (cell in 0 until cells) {
                val latitude = ClimateStage.latitudeOf(cell / columns, config.height) * PI / 180
                val longitude = (cell % columns + 0.5) * 2 * PI / columns
                east[cell] = (7.5 * sin(3 * latitude + shift) + 2.0 * cos(2 * longitude)).toFloat()
                south[cell] = (1.5 * sin(2 * longitude) * cos(latitude)).toFloat()
                freeEast[cell] = (9.0 * sin(2 * latitude + 0.7 + shift) + 1.5 * sin(3 * longitude)).toFloat()
                freeSouth[cell] = (-1.0 * sin(longitude) * cos(latitude)).toFloat()
            }
            return MoistureMarch.Season(
                airTemperatureC = FloatArray(cells) { cell -> (28.0 - 40.0 * kotlin.math.abs(sin(ClimateStage.latitudeOf(cell / columns, config.height) * PI / 180)) - elevationM[cell] / 160.0).toFloat() },
                seaSurfaceC = FloatArray(cells) { cell -> (29.0 - 35.0 * kotlin.math.abs(sin(ClimateStage.latitudeOf(cell / columns, config.height) * PI / 180))).toFloat() },
                seaIce = BooleanArray(cells),
                eastwardMps = east,
                southwardMps = south,
                inversionSuppression = null,
                extraterrestrialOfRow = DoubleArray(config.height) { 10.0 },
                freeEastwardMps = freeEast,
                freeSouthwardMps = freeSouth
            )
        }
        val result = MoistureMarch.run(
            MoistureMarch.Inputs(config, isLand, relative, elevationM, 0.1f, 50.0, rowPieces)
        ) { season(0.0) to season(0.9) }
        return listOf(
            result.julyHalfRainMm.data, result.januaryHalfRainMm.data, result.landOriginRainMm.data,
            result.potentialEvapotranspirationMm.data, result.julyHalfCondensationMm, result.januaryHalfCondensationMm
        )
    }

    @Test
    fun `the rain is the same bits whatever the pieces the rows are marched in, run after run`() {
        val inOnePiece = marched(1)
        for (pieces in listOf(2, 5, 12, 5)) {
            val inPieces = marched(pieces)
            for ((field, expected) in inOnePiece.withIndex()) {
                assertContentEquals(expected, inPieces[field], "field $field marched in $pieces pieces differs from one piece")
            }
        }
        assertTrue(inOnePiece[0].any { it > 0f }, "the world rained nowhere, so nothing was compared")
    }

}
