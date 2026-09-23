package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The geometry guard's capture changes nothing about the world it watches.
 *
 * [LayerCapture] is a sink handed to the engine so the ice's and the fans' layers can be measured
 * as they were made rather than reconstructed afterwards. A sink that moved a bit would be
 * measuring a different world from the one the map draws, so this generates the same world with
 * the capture and without it and compares a fingerprint of every field of every stage's result:
 * every array, every list, every number, walked by reflection so that a field added to a result
 * later is covered without anyone remembering to add it here.
 *
 * Seed 7 at 512 because it carries all of what the capture reports — sea lobes, lake fans, a sheet
 * and valley glaciers — so the capture is exercised on every path it has, not merely allocated.
 */
class LayerCaptureTest {

    @Test
    fun `a world generated with the capture is the world generated without it, to the bit`() {
        val config = WorldGenConfig(seed = 7L, width = 512, height = 512)
        val plain = WorldGenerationEngine.generateBlocking(config)
        val capture = LayerCapture()
        val watched = WorldGenerationEngine.generateBlocking(config, capture = capture)

        val deposition = assertNotNull(capture.deposition, "the erosion stage ran and reported nothing")
        val ice = assertNotNull(capture.ice, "seed 7 carries ice and the ice reported nothing")
        assertTrue(deposition.mechanism.any { it == DepositionLayers.SEA_LOBE }, "no sea lobe recorded")
        assertTrue(ice.frozen.any { it } && ice.cutByIce.any { it }, "the ice recorded no occupancy or no cut")

        val plainPrint = fingerprint(plain)
        val watchedPrint = fingerprint(watched)
        println("LAYER CAPTURE fingerprint without %016x, with %016x".format(plainPrint, watchedPrint))
        assertEquals(plainPrint, watchedPrint, "the capture changed the world it was watching")
    }

    /**
     * Every reachable value of [world], mixed into one 64-bit number.
     *
     * Floats by their raw bits, so a difference in the last place moves it. Objects of this
     * project's own classes are walked field by field; anything else is taken by its value.
     */
    private fun fingerprint(world: WorldMap): Long {
        var mixed = FNV_OFFSET
        fun mix(value: Long) {
            mixed = (mixed xor value) * FNV_PRIME
        }

        val visited = IdentityHashMap<Any, Unit>()
        fun walk(value: Any?) {
            when (value) {
                null -> mix(0x9E3779B97F4A7C15uL.toLong())
                is FloatArray -> { mix(value.size.toLong()); value.forEach { mix(it.toRawBits().toLong()) } }
                is DoubleArray -> { mix(value.size.toLong()); value.forEach { mix(it.toRawBits()) } }
                is IntArray -> { mix(value.size.toLong()); value.forEach { mix(it.toLong()) } }
                is LongArray -> { mix(value.size.toLong()); value.forEach { mix(it) } }
                is ByteArray -> { mix(value.size.toLong()); value.forEach { mix(it.toLong()) } }
                is BooleanArray -> { mix(value.size.toLong()); value.forEach { mix(if (it) 1L else 2L) } }
                is Float -> mix(value.toRawBits().toLong())
                is Double -> mix(value.toRawBits())
                is Number -> mix(value.toLong())
                is Boolean -> mix(if (value) 1L else 2L)
                is String -> mix(value.hashCode().toLong())
                is Enum<*> -> mix(value.ordinal.toLong())
                is Array<*> -> { mix(value.size.toLong()); value.forEach { walk(it) } }
                is Iterable<*> -> value.forEach { walk(it) }
                is Map<*, *> -> value.entries.forEach { walk(it.key); walk(it.value) }
                is Pair<*, *> -> { walk(value.first); walk(value.second) }
                else -> {
                    if (visited.put(value, Unit) != null) return
                    var type: Class<*>? = value.javaClass
                    while (type != null && type.name.startsWith(PROJECT_PACKAGE)) {
                        type.declaredFields
                            .filter { !Modifier.isStatic(it.modifiers) }
                            .sortedBy { it.name }
                            .forEach { field ->
                                field.isAccessible = true
                                mix(field.name.hashCode().toLong())
                                walk(field.get(value))
                            }
                        type = type.superclass
                    }
                }
            }
        }
        walk(world)
        return mixed
    }

    private companion object {
        const val FNV_OFFSET = -0x340d631b7bdddcdbL
        const val FNV_PRIME = 0x100000001b3L
        const val PROJECT_PACKAGE = "com.cartogenesis"
    }
}
