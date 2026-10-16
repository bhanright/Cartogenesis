package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.pipeline.ColumnWater
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The saturated column the column's humidity divides by ([ColumnWater.saturatedColumnMm]): the
 * saturation humidity summed up the boundary layer's rising parcel, dry to its condensation level
 * and moist above, from the ground's own pressure.
 *
 * The control is the column the march read before, the moist adiabat climbed from air saturated at
 * the surface's own temperature, which stands warmer aloft than any sounding of the convecting
 * tropics.
 */
class SaturatedColumnTest {

    private companion object {
        /**
         * Bretherton, Peters and Back's (2004, section 3b) saturated column in the regions of
         * active tropical convection, millimeters, from analyzed temperatures.
         */
        const val CONVECTING_SATURATED_COLUMN_MM = 72.0

        /**
         * How far from it a column read at the tropical sea's air may stand, as a factor either
         * way: the temperature above the boundary layer varies by one to two kelvin over the
         * tropical oceans (their section 2), and the saturated column grows by Clausius and
         * Clapeyron's `L / (R_v T²)`, 6% a kelvin at 300 K, so two kelvin's worth.
         */
        val CONVECTING_FACTOR = exp(2.0 * 2.5e6 / (461.5 * 300.0 * 300.0))

        /**
         * The air over the tropical sea, degrees Celsius: the sea surface between 10 S and 10 N in
         * the 1971-2000 optimum-interpolation climatology, 27.7 C (NOAA Climate Prediction Center's
         * Climate Diagnostics Bulletin, August 2003, table T2, as docs/DESIGN_LEDGER.md, W2, reads
         * it), less Kara, Hurlburt and Loh's 0.78 C between the sea and the air above it.
         */
        val CONVECTING_AIR_C = listOf(27.7 - 0.78)

        /** How far the table may stand from the integral it is read from, as a share. */
        const val TABLE_SHARE = 1.0e-3
    }

    @Test
    fun `the parcel's column over the convecting sea stands near Bretherton's, and the saturated adiabat's does not`() {
        for (airC in CONVECTING_AIR_C) {
            val column = ColumnWater.saturatedColumnMm(airC)
            val adiabat = ColumnWater.integratedSaturatedColumnMm(airC, ColumnWater.SEA_LEVEL_PRESSURE_KPA, parcelRelativeHumidity = 1.0)
            println("SATURATED COLUMN at %.2f C: the parcel's %.1f mm, the saturated adiabat's %.1f, against Bretherton's %.0f (within x%.2f)"
                .format(airC, column, adiabat, CONVECTING_SATURATED_COLUMN_MM, CONVECTING_FACTOR))
            assertTrue(abs(kotlin.math.ln(column / CONVECTING_SATURATED_COLUMN_MM)) <= kotlin.math.ln(CONVECTING_FACTOR), "the parcel's column at $airC C is $column mm")
            assertTrue(abs(kotlin.math.ln(adiabat / CONVECTING_SATURATED_COLUMN_MM)) > kotlin.math.ln(CONVECTING_FACTOR), "the control should stand outside the band: $adiabat mm")
        }
    }

    @Test
    fun `over high ground the column starts at the ground's pressure`() {
        for (airC in listOf(-10.0, 5.0, 15.0, 25.0)) {
            val sea = ColumnWater.saturatedColumnMm(airC)
            val upland = ColumnWater.saturatedColumnMm(airC, 70.0)
            val high = ColumnWater.saturatedColumnMm(airC, 50.0)
            println("SATURATED COLUMN at %.0f C: %.2f mm at sea level, %.2f at 70 kPa, %.2f at 50 kPa".format(airC, sea, upland, high))
            // The same surface air at a lower pressure climbs a gentler moist adiabat, its saturated
            // mixing ratio larger at the same temperature, so its column stands warmer aloft and
            // holds more: a quarter more at 50 kPa and 15 C.
            assertTrue(upland >= sea * (1 - TABLE_SHARE) && high >= upland * (1 - TABLE_SHARE), "the column fell with the pressure at $airC C")
        }
        // The hypsometric pressure: 3 km over ground whose air stands at 10 C, about 70 kPa.
        val pressure = ColumnWater.surfacePressureKpa(3_000.0, 10.0, 6.5)
        assertTrue(pressure in 69.0..72.0, "3 km stands at $pressure kPa")
    }

    @Test
    fun `the table reads the integral`() {
        val random = Random(7)
        var worst = 0.0
        repeat(300) {
            val airC = random.nextDouble(-40.0, 35.0)
            val pressure = random.nextDouble(40.0, 101.325)
            val table = ColumnWater.saturatedColumnMm(airC, pressure)
            val integral = ColumnWater.integratedSaturatedColumnMm(airC, pressure)
            worst = maxOf(worst, abs(table - integral) / integral)
        }
        println("SATURATED COLUMN table against the integral: worst %.2e".format(worst))
        assertTrue(worst <= TABLE_SHARE, "the table stands %.2e from the integral".format(worst))
    }
}
