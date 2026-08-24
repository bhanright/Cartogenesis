package com.cartogenesis.cartography

import com.cartogenesis.worldgen.pipeline.ErosionAccelerator
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.Serializable

/**
 * The terrain itself, stored in a save.
 *
 * Normally a world is a seed and a config: every stage is deterministic, so the file needs to
 * record only what to generate and the terrain follows. That stops being true when the erosion
 * sweeps run on the graphics card, which rounds differently from the CPU and differently again
 * from another card. The difference is very small — measured at six parts in a million of the
 * elevation range, and on the machine it was measured on it changed no coastline cell, no river
 * and no border — but "very small" is not "none", and a saved world should not depend on the
 * hardware that happens to open it.
 *
 * So a world generated on the GPU carries its terrain. This is the whole cost of that: four bytes
 * per cell, which is 4MB at 1024 and 16MB at 2048, base64'd into the file. Worlds generated on the
 * CPU store nothing extra, because for them the seed really is enough.
 *
 * The snapshot is taken after erosion, which is the last stage where hardware is involved.
 * Everything downstream — sea level, currents, climate, rivers, realms — is ordinary CPU work and
 * reproduces exactly from it.
 */
@Serializable
data class TerrainSnapshot(
    val width: Int,
    val height: Int,
    /** Base64 of the raw float bits, little-endian, one per cell in row-major order. */
    val data: String
) {

    fun decode(): FloatArray {
        val bytes = decodeBase64(data)
        return FloatArray(bytes.size / 4) { i ->
            val o = i * 4
            Float.fromBits(
                (bytes[o].toInt() and 0xFF) or
                    ((bytes[o + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[o + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[o + 3].toInt() and 0xFF) shl 24)
            )
        }
    }

    companion object {
        fun of(width: Int, height: Int, heights: FloatArray): TerrainSnapshot {
            val bytes = ByteArray(heights.size * 4)
            for (i in heights.indices) {
                val bits = heights[i].toRawBits()
                val o = i * 4
                bytes[o] = (bits and 0xFF).toByte()
                bytes[o + 1] = ((bits shr 8) and 0xFF).toByte()
                bytes[o + 2] = ((bits shr 16) and 0xFF).toByte()
                bytes[o + 3] = ((bits shr 24) and 0xFF).toByte()
            }
            return TerrainSnapshot(width, height, encodeBase64(bytes))
        }

        @OptIn(ExperimentalEncodingApi::class)
        private fun encodeBase64(bytes: ByteArray): String = Base64.encode(bytes)

        @OptIn(ExperimentalEncodingApi::class)
        private fun decodeBase64(text: String): ByteArray = Base64.decode(text)
    }
}

/**
 * Replays a stored terrain instead of computing one.
 *
 * It arrives through the same seam an accelerator does, which is exactly right: from the engine's
 * point of view "the graphics card produced this" and "the save file produced this" are the same
 * kind of answer, and both are reasons the CPU should not recompute it.
 *
 * Returns null for any grid the snapshot was not taken at, so exporting at a larger size falls
 * through to generating properly rather than trying to stretch what was stored.
 */
class StoredTerrain(private val snapshot: TerrainSnapshot) : ErosionAccelerator {

    override val name: String get() = "terrain stored in the save"

    override suspend fun erode(
        width: Int,
        height: Int,
        heights: FloatArray,
        talus: Float,
        passes: Int,
        rate: Float
    ): FloatArray? =
        if (width == snapshot.width && height == snapshot.height) snapshot.decode() else null
}
