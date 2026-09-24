package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldMap
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * S2b's renders: the author's two worlds at 2048, whole and at each pole, before and after.
 *
 * Three of S2b's four repairs move the ground. The flexure stops each pole's load bending the
 * other's bed, which is why the poles get a crop of their own; the craton reach stops being half as
 * long north-south as east-west, which changes the shape of every continent's interior; and the
 * depression fill raises land the enclosed-water rule had left below the sea beside it. So the
 * whole map is the picture that says whether the land is still the same land, and the top and
 * bottom [POLAR_ROWS] rows are the picture that says what the flexure did.
 *
 * A harness, not a guard, and written so that it compiles against the code either side of S2b —
 * everything it reads is `WorldMap`'s public surface — so the same frames can be taken twice and
 * put side by side. Set `S2B_RENDER_LABEL` to `before` or `after` to say which run this is.
 * Excluded from the per-merge tier by name, like the rest of the render harness.
 */
class S2bRenderDump {

    private val label: String = System.getenv("S2B_RENDER_LABEL") ?: "after"
    private val outputDir = File("../desktop/build/s2b-crops/$label")

    @Test
    fun `dump the author's worlds at 2048, whole and at both poles`() {
        outputDir.mkdirs()
        val dump = DebugMapDump()
        listOf(718106L, 59758L).forEach { seed ->
            val started = System.currentTimeMillis()
            val world = WorldGenerationEngine.generateBlocking(
                authorsConfig(seed).atResolution(SIDE, SIDE)
            )
            println(
                "S2B RENDER seed $seed at $SIDE in ${(System.currentTimeMillis() - started) / 1000}s," +
                    " ${"%.1f".format(world.landFraction() * 100)}% land"
            )
            val whole = dump.render(world, DebugMapDump.Mode.FANTASY)
            write(whole, "$seed-whole-$label.png")
            write(whole.getSubimage(0, 0, SIDE, POLAR_ROWS), "$seed-north-pole-$label.png")
            write(
                whole.getSubimage(0, SIDE - POLAR_ROWS, SIDE, POLAR_ROWS),
                "$seed-south-pole-$label.png"
            )
        }
    }

    private fun write(image: BufferedImage, name: String) {
        ImageIO.write(image, "png", File(outputDir, name))
    }

    private companion object {
        const val SIDE = 2048

        /** How deep a polar crop reaches, in rows: 200 at 2048 is a little over 500 km of ground. */
        const val POLAR_ROWS = 200
    }
}
