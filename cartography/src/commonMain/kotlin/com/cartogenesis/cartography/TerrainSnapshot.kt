package com.cartogenesis.cartography

import com.cartogenesis.worldgen.pipeline.ErosionAccelerator
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.Serializable

/**
 * An eroded height field, taken after the last stage a graphics card touches.
 *
 * **Unreachable, and kept deliberately.** A save has carried every stage of the world since the
 * container format, so nothing writes one; the only files that ever held one are older than the
 * format this build opens, so nothing can hand one back either. [WorldDocument.terrain], the only
 * field of the shape, is permanently null and says why it is still on the wire. The interface's
 * one use of [StoredTerrain] reads that field, so it is inert too.
 *
 * What it is *for*, when a caller has a snapshot to give: every stage is deterministic on the CPU,
 * so a seed and a config would pin a world down — but the erosion sweeps round differently on a
 * graphics card, and differently again on another card, and a world should not change under the
 * reader because the hardware did. The difference is six parts in a million of the elevation
 * range, which moved no coastline cell, no river and no border on the machine it was measured on;
 * "very small" is still not "none". The cost of pinning it is four bytes a cell — 4MB at 1024,
 * 16MB at 2048 — base64'd wherever it travels.
 *
 * The snapshot is taken after erosion, which is the last stage where hardware is involved.
 * Everything downstream (sea level, currents, climate, rivers, realms) is ordinary CPU work and
 * reproduces exactly from it, which is why one field is enough.
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
        return FloatArray(bytes.size / BYTES_PER_HEIGHT) { cell ->
            val at = cell * BYTES_PER_HEIGHT
            Float.fromBits(
                (bytes[at].toInt() and 0xFF) or
                    ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[at + 3].toInt() and 0xFF) shl 24)
            )
        }
    }

    companion object {
        /** A float is four bytes, little-endian, and [data] is that many bytes a cell. */
        private const val BYTES_PER_HEIGHT = 4

        fun of(width: Int, height: Int, heights: FloatArray): TerrainSnapshot {
            val bytes = ByteArray(heights.size * BYTES_PER_HEIGHT)
            for (cell in heights.indices) {
                val bits = heights[cell].toRawBits()
                val at = cell * BYTES_PER_HEIGHT
                bytes[at] = (bits and 0xFF).toByte()
                bytes[at + 1] = ((bits shr 8) and 0xFF).toByte()
                bytes[at + 2] = ((bits shr 16) and 0xFF).toByte()
                bytes[at + 3] = ((bits shr 24) and 0xFF).toByte()
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
 * point of view "the graphics card produced this" and "this was recorded earlier" are the same
 * kind of answer, and both are reasons the CPU should not recompute it. See [TerrainSnapshot] for
 * why nothing currently has one to replay.
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
        maxOrthogonalDrop: Float,
        passes: Int,
        rate: Float
    ): FloatArray? =
        if (width == snapshot.width && height == snapshot.height) snapshot.decode() else null
}
