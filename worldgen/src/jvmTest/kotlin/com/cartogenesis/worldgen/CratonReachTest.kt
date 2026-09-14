package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.PlateStage
import kotlin.math.abs
import kotlin.test.Test
import org.junit.Assert.assertTrue

/**
 * A craton thickens over the same four hundred kilometres whichever way you walk off the coast.
 *
 * `TectonicsConfig.cratonReachKm` is a length on the ground — Watts's 200 to 500 km of margin
 * thinning — and the profile that spends it is read off a jump-flood distance field. That field
 * counts cells, and this map's cells are not square: at 512 by 512 on a world 12,000 km round and
 * 6,000 from pole to pole, a cell is 23.4 km across and 11.7 km down. Converting the count with the
 * cell's *width* therefore calls 34 rows 797 km when they are 398, so the craton reaches full
 * thickness over half the distance northward that it takes westward and every continent carries an
 * ellipse instead of a profile.
 *
 * Measured on two synthetic crusts, one whose edge runs east-west and one whose edge runs
 * north-south, at the cell that stands 398.4 km inside the crust in each — 34 rows one way, 17
 * columns the other, which are the same distance on the ground to the metre.
 */
class CratonReachTest {

    @Test
    fun `the craton profile is the same walking inland in either direction`() {
        val config = WorldGenConfig(seed = 1L, width = 512, height = 512)
        val kilometresDown = ROWS_INLAND * config.cellHeightKm
        val kilometresAcross = COLUMNS_INLAND * config.cellWidthKm

        // The last oceanic cell is the row or column before the edge, so a point that stands n
        // cells from the crust's own edge is n - 1 cells inside the first continental one.
        val acrossTheEdge = PlateStage.cratonInteriorShare(config, crustBelowRow(config))[
            (EDGE_ROW + ROWS_INLAND - 1) * config.width + config.width / 2
        ]
        val downTheEdge = PlateStage.cratonInteriorShare(config, crustRightOfColumn(config))[
            config.height / 2 * config.width + EDGE_COLUMN + COLUMNS_INLAND - 1
        ]
        val gap = abs(acrossTheEdge - downTheEdge).toDouble()
        println(
            ("CRATON %d rows inland (%.1f km) the interior share is %.4f; %d columns inland" +
                " (%.1f km) it is %.4f; they differ by %.4f")
                .format(
                    ROWS_INLAND, kilometresDown, acrossTheEdge,
                    COLUMNS_INLAND, kilometresAcross, downTheEdge, gap
                )
        )

        assertTrue(
            "the two points are ${"%.3f".format(kilometresDown)} km and" +
                " ${"%.3f".format(kilometresAcross)} km from their own crust edge, which is not" +
                " the same distance, so the two readings are not comparable",
            abs(kilometresDown - kilometresAcross) < SAME_DISTANCE_KM
        )
        assertTrue(
            "walking north off the crust's edge the craton is ${"%.4f".format(acrossTheEdge)} of" +
                " the way to full thickness and walking west it is" +
                " ${"%.4f".format(downTheEdge)}, a gap of ${"%.4f".format(gap)} over the bar of" +
                " $ORIENTATION_GAP: the reach is being measured in cells rather than kilometres",
            gap < ORIENTATION_GAP
        )
    }

    /** Oceanic crust above [EDGE_ROW] and continental below it: an edge running east-west. */
    private fun crustBelowRow(config: WorldGenConfig): FloatField =
        FloatField(
            config.width,
            config.height,
            FloatArray(config.width * config.height) {
                if (it / config.width < EDGE_ROW) OCEANIC else CONTINENTAL
            }
        )

    /** Oceanic crust left of [EDGE_COLUMN] and continental right of it: an edge running north-south. */
    private fun crustRightOfColumn(config: WorldGenConfig): FloatField =
        FloatField(
            config.width,
            config.height,
            FloatArray(config.width * config.height) {
                if (it % config.width < EDGE_COLUMN) OCEANIC else CONTINENTAL
            }
        )

    private companion object {
        const val OCEANIC = 0f
        const val CONTINENTAL = 1f

        /**
         * Where each synthetic crust starts, and how far inland the profile is read.
         *
         * A quarter of the way in, so neither edge is near a pole or near the far side of its own
         * crust. Thirty-four rows and seventeen columns because they are the same ground: at 512 on
         * a 12,000 by 6,000 km world a row is 11.71875 km and a column 23.4375, so both come to
         * 398.4375 km — a whisker inside the 400 km `cratonReachKm` asks for, and near enough the
         * knee of `1 - exp(-distance / reach)` that the two orientations are as far apart there as
         * they ever get.
         */
        const val EDGE_ROW = 128
        const val EDGE_COLUMN = 128
        const val ROWS_INLAND = 34
        const val COLUMNS_INLAND = 17

        /**
         * How far apart the two readings' own distances from the edge may be, in kilometres.
         *
         * They are the same distance exactly, by the arithmetic above; this only refuses a grid on
         * which they would not be, so that the clause below cannot be read as comparing two
         * different walks. A metre is far under a cell and far over the rounding of a double.
         */
        const val SAME_DISTANCE_KM = 1e-3

        /**
         * How far apart the two orientations' interior shares may be.
         *
         * Zero, to the resolution of the arithmetic. Both points stand 398.4375 km from their own
         * crust edge along a straight edge, so the jump flood reports exactly 17 cell widths for
         * each — 34 rows at half a width, or 17 columns at one — and `1 - exp(-d / reach)` is then
         * the same expression evaluated twice. A millionth is a few float steps beside a share of
         * about 0.63, and nothing between that and the 0.233 the unscaled flood puts between them
         * is anything but the defect.
         */
        const val ORIENTATION_GAP = 1e-6
    }
}
