package com.cartogenesis.cartography

import com.cartogenesis.worldgen.model.WorldMap
import kotlinx.serialization.json.Json

/**
 * Whether a world came back from a save as it went in: every field a save carries, compared.
 *
 * Driven by the writer's own list of sections, so an array added to the format is compared the day
 * it is added rather than the day someone remembers to add it here; the lists are compared as the
 * JSON they are saved as, which holds a river's cells and widths as well as its name. Public so
 * that the browser's self-test, which can reach a real IndexedDB, asks the same question the JVM's
 * round-trip tests do.
 */
object WorldComparison {

    private val json = Json { encodeDefaults = true }

    /**
     * Where [actual] first differs from [expected] in anything a save carries — the settings, the
     * labels, a per-cell array (floats by their raw bits, so a negative zero counts) or a list — or
     * null when it differs nowhere.
     */
    fun firstDifference(expected: WorldMap, actual: WorldMap): String? {
        if (expected.config != actual.config) return "the settings"
        if (expected.labels != actual.labels) return "the labels"
        val cells = expected.width * expected.height
        for (spec in WorldSections.SECTIONS) {
            val at = when (spec) {
                is FloatSection -> {
                    val before = spec.of(expected)
                    val after = spec.of(actual)
                    if (before.size != after.size) -1
                    else before.indices.firstOrNull { before[it].toRawBits() != after[it].toRawBits() }
                }
                is IntSection -> {
                    val before = spec.of(expected)
                    val after = spec.of(actual)
                    if (before.size != after.size) -1 else before.indices.firstOrNull { before[it] != after[it] }
                }
                is ByteSection -> {
                    val before = ByteArray(cells).also { spec.of(expected).copyInto(it, 0, 0, cells) }
                    val after = ByteArray(cells).also { spec.of(actual).copyInto(it, 0, 0, cells) }
                    before.indices.firstOrNull { before[it] != after[it] }
                }
            }
            if (at != null) return if (at < 0) "'${spec.name}' has another length" else "'${spec.name}' at cell $at"
        }
        val listsBefore = json.encodeToString(WorldLists.serializer(), WorldLists.of(expected))
        val listsAfter = json.encodeToString(WorldLists.serializer(), WorldLists.of(actual))
        return if (listsBefore != listsAfter) "the lists" else null
    }
}
