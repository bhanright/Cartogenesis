package com.cartogenesis.cartography

import java.io.File
import kotlin.test.Test

/**
 * The one script that re-takes the render records: renders every style once and rewrites
 * [RecordedRenders] in place, so a chunk that moved the ground re-pins in one commit rather than
 * by reading twelve failures off a run. Runs only when asked (`REGENERATE_RENDER_RECORDS=1`); the
 * tier never touches the source tree.
 */
class RegenerateRecordedRenders {

    @Test
    fun `rewrite the records this build would take`() {
        if (System.getenv("REGENERATE_RENDER_RECORDS") == null) return
        val world = PenAndInkTest.WORLD
        val taken = MapStyle.entries.associateWith {
            PenAndInkTest().fingerprint(MapRasterizer.rasterize(world, RenderOptions(style = it)))
        }
        val source = File("src/jvmTest/kotlin/com/cartogenesis/cartography/RecordedRenders.kt")
        val text = source.readText()
        val start = text.indexOf("mapOf(")
        val end = text.indexOf(")", start)
        val entries = taken.entries.joinToString(",\n") { "        MapStyle.${it.key.name} to ${it.value}" }
        source.writeText(text.substring(0, start) + "mapOf(\n" + entries + "\n    " + text.substring(end))
        println("RECORDS rewritten: " + taken.entries.joinToString(", ") { "${it.key.name} ${it.value}" })
    }
}
