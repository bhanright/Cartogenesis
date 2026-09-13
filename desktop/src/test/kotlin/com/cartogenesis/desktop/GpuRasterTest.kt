package com.cartogenesis.desktop

import com.cartogenesis.cartography.MapRasterizer
import com.cartogenesis.cartography.MapStyle
import com.cartogenesis.cartography.MapView
import com.cartogenesis.cartography.RasterRecipe
import com.cartogenesis.cartography.RasterView
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import kotlin.math.abs
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Whether the picture the graphics card draws is the picture the processor would have drawn.
 *
 * The colour arithmetic is integer and is meant to agree exactly: the shader blends in 0..255 and
 * truncates where `MapPalette` truncates, and every colour that would need the palette's hue
 * arithmetic — realms, peoples, plates, biomes — is looked up in a table computed on the processor
 * rather than recomputed on the device. Every expression that could be rearranged is marked
 * `precise` in the shader, so the driver may not contract a multiply and an add or turn a divide
 * into a reciprocal multiply, either of which would be *more* accurate than the JVM and land on the
 * other side of a truncation.
 *
 * What is left over is the square root the relief needs. GLSL guarantees `sqrt` only to within a
 * few units in the last place, where the JVM's is correctly rounded, so the shading disagrees in
 * the last bit on a scattering of cells. Almost always that is invisible; at a ramp node it can
 * truncate to the neighbouring colour, and the relief then multiplies that step by as much as 1.63
 * on the style that rakes the light hardest. Two of 255, on well under a thousandth of the
 * pixels, is therefore the floor of what is achievable and the bound below is set there.
 *
 * On a machine with no usable device this reports that and passes, as `GpuErosionTest` does: a
 * headless CI runner is not a broken build.
 */
class GpuRasterTest {

    private companion object {
        /**
         * How far a colour channel may drift, at the 99.9th percentile and at worst.
         *
         * Measured on seed 42 at 1024 over all fifteen views in all nine styles: the 99.9th
         * percentile is 0 in every one of them, and the worst single channel anywhere is 2. A
         * wrong ramp, a wrong style lever or a missing pass moves whole regions by tens, so these
         * bounds separate the arithmetic that cannot agree from the mistakes that matter.
         */
        const val MAX_PERCENTILE_DRIFT = 1
        const val MAX_WORST_DRIFT = 2

        /** Seed 42 at 1024, the world the export guards already use. Generated once for them all. */
        val CONFIG = WorldGenConfig(seed = 42L, width = 1024, height = 1024)

        /** Any ramp will do for a recipe made up by hand; only its length reaches the arithmetic. */
        val RAMP = intArrayOf(
            0xFF0B2239.toInt(), 0xFF2B7398.toInt(), 0xFF9DBE7A.toInt(),
            0xFFC8B072.toInt(), 0xFFEDEDE8.toInt()
        )
        val WORLD: WorldMap by lazy { WorldGenerationEngine.generateBlocking(CONFIG) }
    }

    @Test
    fun `every view and style matches the cpu raster`() {
        val found = GpuRaster.createOrNull()
        val gpu = found.accelerator
        if (gpu == null) {
            println("RASTER GPU unavailable here: ${found.unavailableBecause}")
            return
        }
        println("RASTER GPU device: ${gpu.name}")

        val world = WORLD

        var worstOverall = 0
        var worstCase = ""
        var pixelsCompared = 0L
        var pixelsDiffering = 0.0
        var worstPercentile = 0
        MapView.entries.forEach { view ->
            MapStyle.entries.forEach { style ->
                val options = RenderOptions(view = view, style = style)
                val difference = compare(world, options, gpu)
                pixelsCompared += world.width.toLong() * world.height
                pixelsDiffering += difference.differingPercent
                if (difference.percentile999 > worstPercentile) {
                    worstPercentile = difference.percentile999
                }
                if (difference.worst > worstOverall) {
                    worstOverall = difference.worst
                    worstCase = "${view.label} in ${style.label}"
                }
                assertTrue(
                    difference.percentile999 <= MAX_PERCENTILE_DRIFT,
                    "${view.label} in ${style.label}: 99.9th percentile drift " +
                        "${difference.percentile999}, past $MAX_PERCENTILE_DRIFT — $difference"
                )
                assertTrue(
                    difference.worst <= MAX_WORST_DRIFT,
                    "${view.label} in ${style.label}: worst channel drift ${difference.worst}, " +
                        "past $MAX_WORST_DRIFT — $difference"
                )
            }
        }
        val combinations = MapView.entries.size * MapStyle.entries.size
        println(
            ("RASTER %d views x %d styles, %d pixels: worst 99.9th percentile drift %d, " +
                "worst channel drift anywhere %d (%s), %.4f%% of pixels differ at all")
                .format(
                    MapView.entries.size, MapStyle.entries.size, pixelsCompared,
                    worstPercentile, worstOverall, worstCase, pixelsDiffering / combinations
                )
        )
    }

    /**
     * The passes that are not the base colour: the coast, the realm borders, the relief and the
     * lakes, each switched off in turn on the views that carry them. A pass that ran on one side
     * and not the other would be invisible in the sweep above, which leaves them all on.
     */
    @Test
    fun `every pass can be switched off and still matches`() {
        val found = GpuRaster.createOrNull()
        val gpu = found.accelerator
        if (gpu == null) {
            println("RASTER GPU unavailable here: ${found.unavailableBecause}")
            return
        }

        val world = WORLD
        val cases = listOf(
            "borders drawn over the fantasy view" to
                RenderOptions(view = MapView.FANTASY, showBorders = true),
            "borders over line art" to
                RenderOptions(view = MapView.FANTASY, style = MapStyle.PEN_AND_INK, showBorders = true),
            "no coastline" to RenderOptions(showCoastline = false),
            "no relief" to RenderOptions(showHillshade = false),
            "no relief, line art" to
                RenderOptions(style = MapStyle.PEN_AND_INK, showHillshade = false),
            "no lakes" to RenderOptions(showLakes = false),
            "nothing but the base colour" to RenderOptions(
                showCoastline = false, showHillshade = false, showLakes = false
            ),
            "political with everything on" to RenderOptions(
                view = MapView.POLITICAL, style = MapStyle.VELLUM, showBorders = true
            ),
            "peoples with borders" to RenderOptions(
                view = MapView.CULTURES, style = MapStyle.MIDNIGHT, showBorders = true
            )
        )

        cases.forEach { (label, options) ->
            val difference = compare(world, options, gpu)
            println("RASTER $label: $difference")
            assertTrue(
                difference.worst <= MAX_WORST_DRIFT,
                "$label: worst channel drift ${difference.worst}, past $MAX_WORST_DRIFT"
            )
        }
    }

    /** The same device, the same world, twice: the export must not change between runs. */
    @Test
    fun `the same world renders identically twice`() {
        val found = GpuRaster.createOrNull()
        val gpu = found.accelerator
        if (gpu == null) {
            println("RASTER GPU unavailable here: ${found.unavailableBecause}")
            return
        }

        val world = WORLD
        val options = RenderOptions(view = MapView.FANTASY, style = MapStyle.ATLAS)
        val recipe = assertNotNull(RasterRecipe.of(world, options))
        val once = runBlocking { assertNotNull(gpu.rasterize(recipe)) }
        val twice = runBlocking { assertNotNull(gpu.rasterize(recipe)) }
        assertTrue(once.contentEquals(twice), "the same world drew differently the second time")
    }

    /** What the graphics card buys at the size a preview is drawn at, and at an export size. */
    @Test
    fun `how much faster the raster is`() {
        val found = GpuRaster.createOrNull()
        val gpu = found.accelerator
        if (gpu == null) {
            println("RASTER GPU unavailable here: ${found.unavailableBecause}")
            return
        }

        val world = WORLD
        val options = RenderOptions()
        val recipe = assertNotNull(RasterRecipe.of(world, options))

        // Once to warm the driver and the JIT, then the measurement.
        runBlocking { gpu.rasterize(recipe) }
        MapRasterizer.rasterize(world, options)

        val cpuMs = measureTimeMillis { MapRasterizer.rasterize(world, options) }
        val gpuMs = measureTimeMillis { runBlocking { gpu.rasterize(recipe) } }
        println(
            "RASTER 1024x1024: CPU ${cpuMs}ms, GPU ${gpuMs}ms " +
                "(%.1fx)".format(cpuMs.toDouble() / gpuMs.coerceAtLeast(1))
        )
    }

    /**
     * An 8192-square raster, on fields invented for the purpose.
     *
     * An 8192 *export* cannot be reached at all: generating a world that size wants more heap than
     * the app has, and `GpuExportBenchmarkTest` records how far it gets. That says nothing about
     * whether the drawing scales, which is what this chunk changed and what this checks — sixteen
     * tiles, 67.1 million pixels, and half a gigabyte of fields sitting on the device while they
     * are drawn, with no world in memory to pay for.
     */
    @Test
    fun `an 8192 raster, on fields made up for the purpose`() {
        val found = GpuRaster.createOrNull()
        val gpu = found.accelerator
        if (gpu == null) {
            println("RASTER GPU unavailable here: ${found.unavailableBecause}")
            return
        }

        val side = 8192
        val cells = side * side
        val elevation = FloatArray(cells)
        val land = ByteArray(cells)
        for (i in 0 until cells) {
            // A ridged diagonal, so the relief has something to shade and the coast pass has an
            // edge to find rather than a flat field the compiler could see through.
            val x = i % side
            val y = i / side
            val value = (((x * 7 + y * 13) % 512) / 512f) - 0.35f
            elevation[i] = value
            if (value > 0f) land[i] = 1
        }

        val recipe = syntheticRecipe(side, elevation, land)
        val first = requireNotNull(runBlocking { gpu.rasterize(recipe) }) {
            "the accelerator declined an 8192 raster"
        }
        val millis = measureTimeMillis { runBlocking { gpu.rasterize(recipe) } }
        val second = requireNotNull(runBlocking { gpu.rasterize(recipe) })

        println("RASTER 8192 in sixteen tiles: %.2f s for 67.1 million pixels".format(millis / 1000.0))
        assertTrue(first.contentEquals(second), "the 8192 raster changed between runs")
        assertTrue(first.all { (it ushr 24) == 0xFF }, "the 8192 raster left pixels transparent")
    }

    /** An elevation view in a plain style: the fewest fields that still put the relief and the
     * coastline through their paces at this size. */
    private fun syntheticRecipe(side: Int, elevation: FloatArray, land: ByteArray) = RasterRecipe(
        width = side,
        height = side,
        view = RasterView.ELEVATION,
        elevation = elevation,
        land = land,
        biome = null,
        scalarA = null,
        scalarB = null,
        indexA = null,
        colorsA = null,
        indexB = null,
        colorsB = null,
        nation = null,
        lakeId = null,
        lakeSurface = null,
        oceanRamp = RAMP,
        landRamp = RAMP,
        plainOceanRamp = RAMP,
        plainLandRamp = RAMP,
        temperatureRamp = RAMP,
        precipitationRamp = RAMP,
        biomeColors = RAMP,
        biomeCanopy = FloatArray(RAMP.size),
        paper = 0xFFF2E4C6.toInt(),
        biomeWash = 0f,
        biomeMuting = 0f,
        climateTint = 0f,
        isobathInk = 0f,
        isobathInterval = 1f / 12f,
        isobathFlattestSlope = 0f,
        isobathSlopeStencil = 2 * side / 512,
        lake = 0xFF4E92B4.toInt(),
        lakeDeep = 0xFF2F6B8C.toInt(),
        coastline = 0xFF3E4A52.toInt(),
        coastlineStrength = 0.55f,
        border = 0xFF2A2118.toInt(),
        wilderness = 0xFF6E6A5E.toInt(),
        reliefStrength = 1f,
        lineArt = false,
        inkGain = 0f,
        rainfallSea = 0xFF20303C.toInt(),
        currentsLand = 0xFF3A3A32.toInt(),
        windLandLow = 0xFF4A4638.toInt(),
        windLandHigh = 0xFF9A9384.toInt(),
        windSea = 0xFF16242F.toInt(),
        anomalyMid = 0xFF20384C.toInt(),
        anomalyWarm = 0xFFC4442E.toInt(),
        anomalyCold = 0xFF3E86C4.toInt(),
        hillshade = true,
        singleLamp = false,
        slopeScale = 12f * (side / 512f),
        opennessStep = 2 * side / 512,
        showLakes = false,
        showCoastline = true,
        showBorders = false
    )

    private fun compare(world: WorldMap, options: RenderOptions, gpu: GpuRaster): Difference {
        val recipe = assertNotNull(
            RasterRecipe.of(world, options),
            "the recipe declined ${options.view.label} in ${options.style.label}"
        )
        val onGpu = assertNotNull(
            runBlocking { gpu.rasterize(recipe) },
            "the accelerator declined ${options.view.label} in ${options.style.label}"
        )
        val onCpu = MapRasterizer.rasterize(world, options)
        return difference(onCpu, onGpu)
    }

    /**
     * Per-pixel worst channel difference, as a distribution rather than a single number: one stray
     * pixel says nothing about a picture of a million, and the tail is what a person would see.
     */
    private fun difference(cpu: IntArray, gpu: IntArray): Difference {
        require(cpu.size == gpu.size)
        val histogram = IntArray(256)
        var differing = 0
        var total = 0L
        var worst = 0
        var opaque = true
        for (i in cpu.indices) {
            if ((gpu[i] ushr 24) != 0xFF) opaque = false
            var pixel = 0
            for (shift in 0..16 step 8) {
                val delta = abs(((cpu[i] shr shift) and 0xFF) - ((gpu[i] shr shift) and 0xFF))
                if (delta > pixel) pixel = delta
            }
            if (pixel > 0) differing++
            total += pixel
            histogram[pixel]++
            if (pixel > worst) worst = pixel
        }
        assertTrue(opaque, "the accelerator returned pixels that were not fully opaque")

        var seen = 0
        var percentile999 = 0
        val target = (cpu.size * 0.999).toInt()
        for (d in 0 until 256) {
            seen += histogram[d]
            if (seen >= target) {
                percentile999 = d
                break
            }
        }
        return Difference(differing * 100.0 / cpu.size, total.toDouble() / cpu.size, percentile999, worst)
    }

    private class Difference(
        val differingPercent: Double,
        val mean: Double,
        val percentile999: Int,
        val worst: Int
    ) {
        override fun toString(): String =
            "%.3f%% of pixels differ, mean %.4f, 99.9th percentile %d, worst %d (of 255)"
                .format(differingPercent, mean, percentile999, worst)
    }
}
