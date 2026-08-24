package com.cartogenesis.desktop

import com.cartogenesis.cartography.MapRasterizer
import com.cartogenesis.cartography.MapView
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.ui.MapImage
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * The wind and current layers, rendered and measured.
 *
 * Rendering them is the only way to tell whether the arrow density reads as a flow field or as
 * noise, so this writes the PNGs out. The assertions cover the parts that can be checked without
 * looking: that arrows exist, that they sit on water for currents, and that the wind arrows
 * actually reverse across the circulation belts rather than all pointing one way.
 */
class FlowLayerTest {

    private val outputDir = File("build/maps")

    @Test
    fun `flow layers render and point the right ways`() {
        outputDir.mkdirs()
        val world = WorldGenerationEngine.generateBlocking(
            WorldGenConfig(seed = 42L, width = 512, height = 512)
        )

        val currents = MapRasterizer.overlay(world, RenderOptions(view = MapView.CURRENTS))
        val winds = MapRasterizer.overlay(world, RenderOptions(view = MapView.WIND))

        assertTrue(currents.flow.size > 100, "too few current arrows: ${currents.flow.size}")
        assertTrue(winds.flow.size > 100, "too few wind arrows: ${winds.flow.size}")

        // Currents are sampled on water only, so every arrow must land on a sea cell.
        val onLand = currents.flow.count { arrow ->
            val i = arrow.y.toInt() * world.width + arrow.x.toInt()
            world.sea.isLand[i]
        }
        assertTrue(onLand == 0, "$onLand current arrows drawn over land")

        // Wind is easterly in the tropics and westerly in mid-latitudes; if the belts were not
        // being read, every arrow would share a sign.
        val eastward = winds.flow.count { it.dx > 0 }
        assertTrue(
            eastward > winds.flow.size / 10 && eastward < winds.flow.size * 9 / 10,
            "wind arrows do not reverse across belts: $eastward of ${winds.flow.size} eastward"
        )

        val fastest = currents.flow.maxOf { it.strength }
        val moving = currents.flow.count { it.strength > 0.25f }
        println(
            "FLOW currents: ${currents.flow.size} arrows, " +
                "${moving * 100 / currents.flow.size}% above quarter speed, peak $fastest"
        )
        println("FLOW winds: ${winds.flow.size} arrows, $eastward eastward")

        // Arrows have to be unit length, or the barb geometry skews with speed.
        val badLength = currents.flow.count {
            abs(it.dx * it.dx + it.dy * it.dy - 1f) > 1e-3f
        }
        assertTrue(badLength == 0, "$badLength current arrows are not unit vectors")

        listOf(MapView.CURRENTS to "currents", MapView.WIND to "wind").forEach { (view, name) ->
            val bitmap = MapImage.toBitmap(world, RenderOptions(view = view))
            val data = Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)!!
            File(outputDir, "seed42-$name.png").writeBytes(data.bytes)
            bitmap.close()
        }
        println("FLOW wrote ${outputDir.absolutePath}")
    }
}
