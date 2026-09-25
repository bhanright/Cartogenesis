package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.math.GaussianBlur
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Gaussian blur is round on the ground and moves no material.
 *
 * A point blurred with one spread in kilometres, handed to the blur as that many columns and that
 * many rows, spreads over the same distance east-west as north-south on the ground and not at all
 * along a diagonal more than along an axis: its covariance in kilometres is the spread squared on
 * both axes and nothing across them. And a constant field comes back the same constant.
 */
class GaussianBlurTest {

    private val config = WorldGenConfig(width = 512, height = 512)

    @Test
    fun `a point spreads as far on the ground whichever way it goes`() {
        val cellsAcross = config.width
        val cellsDown = config.height
        val field = FloatField(cellsAcross, cellsDown)
        field[cellsAcross / 2, cellsDown / 2] = 1f
        GaussianBlur.apply(field, SPREAD_KM / config.cellWidthKm, SPREAD_KM / config.cellHeightKm)
        var total = 0.0
        var eastWest = 0.0
        var northSouth = 0.0
        var across = 0.0
        for (row in 0 until cellsDown) {
            for (column in 0 until cellsAcross) {
                val weight = field[column, row].toDouble()
                val eastKm = (column - cellsAcross / 2) * config.cellWidthKm
                val southKm = (row - cellsDown / 2) * config.cellHeightKm
                total += weight
                eastWest += weight * eastKm * eastKm
                northSouth += weight * southKm * southKm
                across += weight * eastKm * southKm
            }
        }
        val spreadEastWest = sqrt(eastWest / total)
        val spreadNorthSouth = sqrt(northSouth / total)
        val correlation = across / sqrt(eastWest * northSouth)
        println(
            "GAUSSIAN a point blurred by $SPREAD_KM km: %.4f of it kept, spread %.2f km east-west and %.2f north-south, correlation %.5f"
                .format(total, spreadEastWest, spreadNorthSouth, correlation)
        )
        assertTrue(abs(total - 1.0) < MASS_ROUNDING, "the blur kept $total of the point")
        assertTrue(
            abs(spreadEastWest / SPREAD_KM - 1.0) < SPREAD_TOLERANCE && abs(spreadNorthSouth / SPREAD_KM - 1.0) < SPREAD_TOLERANCE,
            "a $SPREAD_KM km blur spread a point over $spreadEastWest km east-west and $spreadNorthSouth km north-south"
        )
        assertTrue(abs(correlation) < SPREAD_TOLERANCE, "the blur leans along a diagonal: correlation $correlation")
    }

    @Test
    fun `a constant stays that constant`() {
        val field = FloatField(64, 64, FloatArray(64 * 64) { 0.625f })
        GaussianBlur.apply(field, 3.0, 6.0)
        val worst = field.data.maxOf { abs(it - 0.625f) }
        assertTrue(worst < 1e-6f, "a constant field moved by $worst")
    }

    private companion object {
        /** The spread, in kilometres: four cells across and eight rows down at 512. */
        const val SPREAD_KM = 93.75

        /** A float's rounding of each tap, summed over a few hundred of them. */
        const val MASS_ROUNDING = 1e-5

        /**
         * How far the measured spread may stand from the one asked: a hundredth. A Gaussian sampled
         * at cell centres four cells to a standard deviation carries its variance to a few parts in
         * a million, and truncated at four deviations it loses a few parts in ten thousand.
         */
        const val SPREAD_TOLERANCE = 0.01
    }
}
