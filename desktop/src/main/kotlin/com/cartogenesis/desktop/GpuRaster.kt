package com.cartogenesis.desktop

import com.cartogenesis.cartography.RasterAccelerator
import com.cartogenesis.cartography.RasterRecipe
import org.lwjgl.opengl.GL43C
import org.lwjgl.system.MemoryUtil

/**
 * Draws the map's pixels on the graphics card.
 *
 * Every decision the raster makes — which ramp, how much biome wash, where the coast is, how hard
 * the light rakes — is per-pixel and independent of every other pixel, which is the one shape
 * hardware with thousands of lanes is built for. The CPU walks 16.7 million cells at 4096 and
 * blends each one four or five times; here the same arithmetic runs as one compute dispatch a tile.
 *
 * Three things are worth knowing about how it is arranged.
 *
 * **The shader is dumb on purpose.** It is handed a [RasterRecipe] — every colour already packed,
 * every ramp already chosen, a colour table per realm, people and plate — so it never reproduces the
 * palette's hue arithmetic or the style's rules. Anything that could drift from `MapRasterizer` is
 * computed once on the processor and uploaded; what is left on the device is blending, a ramp
 * lookup, a square root for the relief, and the neighbour tests the coast and border passes need.
 *
 * **The colour arithmetic is integer, so it can agree exactly.** `MapPalette` blends in 0..255 and
 * truncates; this does the same, in floats that hold integers, with a `floor` wherever the Kotlin
 * has a `toInt`. The one place that genuinely cannot agree is the hillshade, which is a square root
 * and a divide: hardware rounds those its own way, and a channel one step off at a truncation
 * boundary is the expected result.
 *
 * **The output is tiled, the input is not.** The world's fields go up once per render; the pixels
 * come back in bands of a few million, so an 8192 export never needs a 268MB staging buffer on
 * either side of the bus, and a machine short of video memory fails on the fields rather than
 * halfway through a picture.
 */
class GpuRaster private constructor(private val deviceName: String) : RasterAccelerator {

    override val name: String get() = deviceName

    /** What probing this machine found: an accelerator, or the reason there is not one. */
    class Result(val accelerator: GpuRaster?, val unavailableBecause: String?)

    private var program: Int = 0
    private val uniforms = HashMap<String, Int>()

    override suspend fun rasterize(recipe: RasterRecipe): IntArray? = GlContext.run("The export raster") {
        val w = recipe.width
        val h = recipe.height
        val cells = w * h
        if (program == 0) return@run null

        // Bands of a few million pixels: one dispatch for anything up to 2048, sixteen at 8192.
        val rowsPerTile = (TILE_PIXELS / w).coerceIn(1, h)
        val buffers = ArrayList<Int>()

        try {
            GL43C.glUseProgram(program)

            val dummy = buffer(buffers)
            GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, dummy)
            GL43C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, 16L, GL43C.GL_STATIC_DRAW)
            for (binding in 0..BINDING_OUTPUT) {
                GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, dummy)
            }

            bindFloats(buffers, BINDING_ELEVATION, recipe.elevation)
            bindBytes(buffers, BINDING_LAND, recipe.land)
            recipe.biome?.let { bindBytes(buffers, BINDING_BIOME, it) }
            recipe.scalarA?.let { bindFloats(buffers, BINDING_SCALAR_A, it) }
            recipe.scalarB?.let { bindFloats(buffers, BINDING_SCALAR_B, it) }
            val indexABuffer = recipe.indexA?.let { bindInts(buffers, BINDING_INDEX_A, it) }
            recipe.indexB?.let { bindInts(buffers, BINDING_INDEX_B, it) }
            recipe.colorsA?.let { bindInts(buffers, BINDING_COLORS_A, it) }
            recipe.colorsB?.let { bindInts(buffers, BINDING_COLORS_B, it) }
            recipe.lakeId?.let { bindInts(buffers, BINDING_LAKE_ID, it) }
            recipe.lakeSurface?.let { bindFloats(buffers, BINDING_LAKE_SURFACE, it) }
            bindInts(buffers, BINDING_BIOME_COLORS, recipe.biomeColors)

            // The realm field is the same array as the political view's own, so it is uploaded once
            // and bound twice rather than sent up the bus a second time.
            val nation = recipe.nation
            if (nation != null) {
                if (nation === recipe.indexA && indexABuffer != null) {
                    GL43C.glBindBufferBase(
                        GL43C.GL_SHADER_STORAGE_BUFFER, BINDING_NATION, indexABuffer
                    )
                } else {
                    bindInts(buffers, BINDING_NATION, nation)
                }
            }

            bindRamps(buffers, recipe)

            val output = buffer(buffers)
            GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, output)
            GL43C.glBufferData(
                GL43C.GL_SHADER_STORAGE_BUFFER,
                (rowsPerTile.toLong() * w * 4), GL43C.GL_DYNAMIC_COPY
            )
            GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, BINDING_OUTPUT, output)

            // Video memory is the one failure that is likely rather than exotic: the fields for an
            // 8192 world are the better part of half a gigabyte. Declining here costs an export its
            // speed; carrying on would cost it its pixels.
            val uploadError = GL43C.glGetError()
            if (uploadError != GL43C.GL_NO_ERROR) {
                System.err.println(
                    "The export raster could not fit ${w}x$h on the device (GL error $uploadError); " +
                        "drawing it on the processor instead"
                )
                return@run null
            }

            setUniforms(recipe)

            val pixels = IntArray(cells)
            val tile = IntArray(rowsPerTile * w)
            var rowStart = 0
            while (rowStart < h) {
                val rows = minOf(rowsPerTile, h - rowStart)
                GL43C.glUniform1i(uniform("uRowStart"), rowStart)
                GL43C.glUniform1i(uniform("uRows"), rows)
                GL43C.glDispatchCompute((w + GROUP - 1) / GROUP, (rows + GROUP - 1) / GROUP, 1)
                GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT)

                GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, output)
                GL43C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0L, tile)
                tile.copyInto(pixels, rowStart * w, 0, rows * w)
                rowStart += rows
            }
            pixels
        } finally {
            GL43C.glDeleteBuffers(buffers.toIntArray())
        }
    }

    private fun buffer(buffers: MutableList<Int>): Int {
        val id = GL43C.glGenBuffers()
        buffers.add(id)
        return id
    }

    private fun bindFloats(buffers: MutableList<Int>, binding: Int, data: FloatArray): Int {
        val id = buffer(buffers)
        GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, id)
        GL43C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, data, GL43C.GL_STATIC_DRAW)
        GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, id)
        return id
    }

    private fun bindInts(buffers: MutableList<Int>, binding: Int, data: IntArray): Int {
        val id = buffer(buffers)
        GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, id)
        GL43C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, data, GL43C.GL_STATIC_DRAW)
        GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, id)
        return id
    }

    /**
     * A byte a cell, read four to a word on the device. Padded to a whole word, so the last cells
     * of an odd-sized grid do not send the shader reading past the end of the buffer.
     */
    private fun bindBytes(buffers: MutableList<Int>, binding: Int, data: ByteArray) {
        val id = buffer(buffers)
        val padded = ((data.size + 3) / 4) * 4
        val staging = MemoryUtil.memAlloc(padded)
        try {
            staging.put(data)
            while (staging.hasRemaining()) staging.put(0.toByte())
            staging.flip()
            GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, id)
            GL43C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, staging, GL43C.GL_STATIC_DRAW)
        } finally {
            MemoryUtil.memFree(staging)
        }
        GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, id)
    }

    /** Every ramp end to end in one buffer, with an offset and a length per ramp as uniforms. */
    private fun bindRamps(buffers: MutableList<Int>, recipe: RasterRecipe) {
        val ramps = listOf(
            recipe.oceanRamp, recipe.landRamp,
            recipe.plainOceanRamp, recipe.plainLandRamp,
            recipe.temperatureRamp, recipe.precipitationRamp
        )
        val packed = IntArray(ramps.sumOf { it.size })
        var offset = 0
        ramps.forEachIndexed { index, ramp ->
            ramp.copyInto(packed, offset)
            GL43C.glUniform2i(uniform("uRamp[$index]"), offset, ramp.size)
            offset += ramp.size
        }
        bindInts(buffers, BINDING_RAMPS, packed)
    }

    private fun setUniforms(recipe: RasterRecipe) {
        GL43C.glUniform1i(uniform("uWidth"), recipe.width)
        GL43C.glUniform1i(uniform("uHeight"), recipe.height)
        GL43C.glUniform1i(uniform("uView"), recipe.view)
        GL43C.glUniform1i(uniform("uHillshade"), recipe.hillshade.toGl())
        GL43C.glUniform1i(uniform("uLineArt"), recipe.lineArt.toGl())
        GL43C.glUniform1i(uniform("uShowLakes"), recipe.showLakes.toGl())
        GL43C.glUniform1i(uniform("uShowCoastline"), recipe.showCoastline.toGl())
        GL43C.glUniform1i(uniform("uShowBorders"), recipe.showBorders.toGl())

        GL43C.glUniform1f(uniform("uZScale"), recipe.hillshadeScale)
        GL43C.glUniform1f(uniform("uBiomeWash"), recipe.biomeWash)
        GL43C.glUniform1f(uniform("uBiomeMuting"), recipe.biomeMuting)
        GL43C.glUniform1f(uniform("uCoastlineStrength"), recipe.coastlineStrength)
        GL43C.glUniform1f(uniform("uReliefStrength"), recipe.reliefStrength)
        GL43C.glUniform1f(uniform("uInkGain"), recipe.inkGain)

        colour("uPaper", recipe.paper)
        colour("uLake", recipe.lake)
        colour("uLakeDeep", recipe.lakeDeep)
        colour("uCoastline", recipe.coastline)
        colour("uBorder", recipe.border)
        colour("uWilderness", recipe.wilderness)
        colour("uRainfallSea", recipe.rainfallSea)
        colour("uCurrentsLand", recipe.currentsLand)
        colour("uWindLandLow", recipe.windLandLow)
        colour("uWindLandHigh", recipe.windLandHigh)
        colour("uWindSea", recipe.windSea)
        colour("uAnomalyMid", recipe.anomalyMid)
        colour("uAnomalyWarm", recipe.anomalyWarm)
        colour("uAnomalyCold", recipe.anomalyCold)
    }

    /** A packed ARGB int as the shader wants it: three channels, each an integer 0..255. */
    private fun colour(name: String, argb: Int) {
        GL43C.glUniform3f(
            uniform(name),
            ((argb shr 16) and 0xFF).toFloat(),
            ((argb shr 8) and 0xFF).toFloat(),
            (argb and 0xFF).toFloat()
        )
    }

    private fun uniform(name: String): Int =
        uniforms.getOrPut(name) { GL43C.glGetUniformLocation(program, name) }

    private fun Boolean.toGl(): Int = if (this) 1 else 0

    companion object {

        /** 16x16 is 256 invocations, which every device with compute shaders must allow. */
        private const val GROUP = 16

        /**
         * Pixels a tile: four million, which is 16MB coming back over the bus. Small enough that
         * nothing needs a buffer the size of the picture, large enough that a 4096 export is four
         * dispatches rather than four hundred.
         */
        private const val TILE_PIXELS = 4 shl 20

        private const val BINDING_ELEVATION = 0
        private const val BINDING_LAND = 1
        private const val BINDING_BIOME = 2
        private const val BINDING_SCALAR_A = 3
        private const val BINDING_SCALAR_B = 4
        private const val BINDING_INDEX_A = 5
        private const val BINDING_INDEX_B = 6
        private const val BINDING_NATION = 7
        private const val BINDING_LAKE_ID = 8
        private const val BINDING_COLORS_A = 9
        private const val BINDING_COLORS_B = 10
        private const val BINDING_BIOME_COLORS = 11
        private const val BINDING_LAKE_SURFACE = 12
        private const val BINDING_RAMPS = 13
        private const val BINDING_OUTPUT = 14

        /**
         * Takes the shared offscreen context and compiles the raster, or returns null with a reason
         * if this machine cannot offer what is needed.
         */
        fun createOrNull(): Result {
            val context = GlContext.ensure()
            val device = context.device
                ?: return Result(null, context.unavailableBecause ?: "unknown failure")

            val raster = GpuRaster(device)
            raster.program = GlContext.run("Compiling the export raster", seconds = 30) {
                GlContext.compileCompute(SOURCE)
            } ?: return Result(null, "the raster shader would not compile")
            return Result(raster, null)
        }

        private val SOURCE = """
            #version 430
            layout(local_size_x = 16, local_size_y = 16) in;

            layout(std430, binding = 0) readonly buffer Elevation { float elevation[]; };
            layout(std430, binding = 1) readonly buffer LandBits { uint landBits[]; };
            layout(std430, binding = 2) readonly buffer BiomeBits { uint biomeBits[]; };
            layout(std430, binding = 3) readonly buffer ScalarA { float scalarA[]; };
            layout(std430, binding = 4) readonly buffer ScalarB { float scalarB[]; };
            layout(std430, binding = 5) readonly buffer IndexA { int indexA[]; };
            layout(std430, binding = 6) readonly buffer IndexB { int indexB[]; };
            layout(std430, binding = 7) readonly buffer Nation { int nationId[]; };
            layout(std430, binding = 8) readonly buffer LakeIds { int lakeId[]; };
            layout(std430, binding = 9) readonly buffer ColorsA { uint colorsA[]; };
            layout(std430, binding = 10) readonly buffer ColorsB { uint colorsB[]; };
            layout(std430, binding = 11) readonly buffer BiomeColors { uint biomeColors[]; };
            layout(std430, binding = 12) readonly buffer LakeSurface { float lakeSurface[]; };
            layout(std430, binding = 13) readonly buffer Ramps { uint ramps[]; };
            layout(std430, binding = 14) writeonly buffer Output { uint pixels[]; };

            uniform int uWidth;
            uniform int uHeight;
            uniform int uRowStart;
            uniform int uRows;
            uniform int uView;
            uniform int uHillshade;
            uniform int uLineArt;
            uniform int uShowLakes;
            uniform int uShowCoastline;
            uniform int uShowBorders;

            uniform float uZScale;
            uniform float uBiomeWash;
            uniform float uBiomeMuting;
            uniform float uCoastlineStrength;
            uniform float uReliefStrength;
            uniform float uInkGain;

            uniform vec3 uPaper;
            uniform vec3 uLake;
            uniform vec3 uLakeDeep;
            uniform vec3 uCoastline;
            uniform vec3 uBorder;
            uniform vec3 uWilderness;
            uniform vec3 uRainfallSea;
            uniform vec3 uCurrentsLand;
            uniform vec3 uWindLandLow;
            uniform vec3 uWindLandHigh;
            uniform vec3 uWindSea;
            uniform vec3 uAnomalyMid;
            uniform vec3 uAnomalyWarm;
            uniform vec3 uAnomalyCold;

            // offset and length of each ramp within `ramps`
            uniform ivec2 uRamp[6];

            const int RAMP_OCEAN = 0;
            const int RAMP_LAND = 1;
            const int RAMP_PLAIN_OCEAN = 2;
            const int RAMP_PLAIN_LAND = 3;
            const int RAMP_TEMPERATURE = 4;
            const int RAMP_PRECIPITATION = 5;

            const int V_FANTASY = 0;
            const int V_POLITICAL = 1;
            const int V_CULTURES = 2;
            const int V_ELEVATION = 3;
            const int V_BIOMES = 4;
            const int V_TEMPERATURE = 5;
            const int V_RAINFALL = 6;
            const int V_PLATES = 7;
            const int V_CURRENTS = 8;
            const int V_WIND = 9;
            const int V_NORMALS = 10;

            // Lambertian light in the north-west, the cartographic convention. The same numbers as
            // MapRasterizer.computeHillshade; the two are one formula in two languages and have to
            // be changed together.
            const float LIGHT_X = -0.6;
            const float LIGHT_Y = -0.6;
            const float LIGHT_Z = 0.53;

            vec3 unpack(uint c) {
                return vec3(
                    float((c >> 16u) & 0xFFu),
                    float((c >> 8u) & 0xFFu),
                    float(c & 0xFFu)
                );
            }

            /*
             * MapPalette.blend, to the letter: clamp the fraction, interpolate each channel as a
             * float, truncate toward zero, clamp into 0..255. Channels hold integers throughout,
             * so this agrees with the processor exactly rather than approximately.
             */
            vec3 blend(vec3 a, vec3 b, float t) {
                float f = clamp(t, 0.0, 1.0);
                // `precise` throughout the colour arithmetic. Without it the driver is free to
                // contract a multiply and an add into one instruction and to turn a divide into a
                // reciprocal multiply, both of which are more accurate than what the JVM does and
                // therefore land on the other side of a truncation now and then.
                precise vec3 mixed = a + (b - a) * f;
                return clamp(floor(mixed), vec3(0.0), vec3(255.0));
            }

            /** MapPalette.shade: 1 leaves the colour alone, below darkens, above lightens. */
            vec3 shade(vec3 c, float factor) {
                precise vec3 lit = c * factor;
                return clamp(floor(lit), vec3(0.0), vec3(255.0));
            }

            vec3 rampAt(int which, float t) {
                int offset = uRamp[which].x;
                int size = uRamp[which].y;
                float clamped = clamp(t, 0.0, 1.0);
                precise float scaled = clamped * float(size - 1);
                int index = min(int(scaled), size - 2);
                return blend(
                    unpack(ramps[offset + index]),
                    unpack(ramps[offset + index + 1]),
                    scaled - float(index)
                );
            }

            bool isLand(int i) {
                return ((landBits[i >> 2] >> ((uint(i) & 3u) * 8u)) & 0xFFu) != 0u;
            }

            int biomeAt(int i) {
                return int((biomeBits[i >> 2] >> ((uint(i) & 3u) * 8u)) & 0xFFu);
            }

            float elevationAt(int x, int y) {
                int wrapped = x % uWidth;
                if (wrapped < 0) wrapped += uWidth;
                return elevation[clamp(y, 0, uHeight - 1) * uWidth + wrapped];
            }

            float hillshadeAt(int x, int y) {
                precise float dzdx = (elevationAt(x + 1, y) - elevationAt(x - 1, y)) * uZScale;
                precise float dzdy = (elevationAt(x, y + 1) - elevationAt(x, y - 1)) * uZScale;
                precise float len = sqrt(dzdx * dzdx + dzdy * dzdy + 1.0);
                precise float lambert = (-dzdx * LIGHT_X - dzdy * LIGHT_Y + LIGHT_Z) / len;
                precise float shading = 0.72 + 0.55 * lambert;
                return clamp(shading, 0.45, 1.35);
            }

            vec3 tint(vec3 base, int biome) {
                if (uBiomeWash <= 0.0) return base;
                vec3 muted = blend(unpack(biomeColors[biome]), uPaper, uBiomeMuting);
                return blend(base, muted, uBiomeWash);
            }

            vec3 temperatureColour(float celsius) {
                precise float warmed = celsius + 30.0;
                precise float t = warmed / 70.0;
                return rampAt(RAMP_TEMPERATURE, clamp(t, 0.0, 1.0));
            }

            vec3 anomalyColour(float degrees) {
                precise float t = clamp(degrees / 7.0, -1.0, 1.0);
                if (t >= 0.0) return blend(uAnomalyMid, uAnomalyWarm, t);
                return blend(uAnomalyMid, uAnomalyCold, -t);
            }

            vec3 baseColour(int x, int y, int i, bool land, float relative) {
                if (uView == V_FANTASY) {
                    if (!land) return rampAt(RAMP_OCEAN, 1.0 - clamp(-relative, 0.0, 1.0));
                    return tint(rampAt(RAMP_LAND, clamp(relative, 0.0, 1.0)), biomeAt(i));
                }
                if (uView == V_POLITICAL || uView == V_CULTURES) {
                    if (!land) return rampAt(RAMP_PLAIN_OCEAN, 1.0 - clamp(-relative, 0.0, 1.0));
                    int owner = indexA[i];
                    if (owner < 0) return uWilderness;
                    return blend(
                        unpack(colorsA[owner]),
                        rampAt(RAMP_PLAIN_LAND, clamp(relative, 0.0, 1.0)),
                        0.3
                    );
                }
                if (uView == V_ELEVATION) {
                    if (land) return rampAt(RAMP_PLAIN_LAND, clamp(relative, 0.0, 1.0));
                    return rampAt(RAMP_PLAIN_OCEAN, 1.0 - clamp(-relative, 0.0, 1.0));
                }
                if (uView == V_BIOMES) return unpack(biomeColors[biomeAt(i)]);
                if (uView == V_TEMPERATURE) return temperatureColour(scalarA[i]);
                if (uView == V_RAINFALL) {
                    if (!land) return uRainfallSea;
                    return rampAt(RAMP_PRECIPITATION, clamp(scalarA[i], 0.0, 1.0));
                }
                if (uView == V_PLATES) {
                    precise float edge = clamp(scalarA[i] / 12.0, 0.0, 1.0);
                    return blend(unpack(colorsB[indexB[i]]), unpack(colorsA[indexA[i]]), edge);
                }
                if (uView == V_CURRENTS) {
                    if (land) return uCurrentsLand;
                    return anomalyColour(scalarA[i]);
                }
                if (uView == V_WIND) {
                    if (!land) return uWindSea;
                    return blend(uWindLandLow, uWindLandHigh, clamp(relative, 0.0, 1.0));
                }
                // V_NORMALS: the tangent-space normal, written out as a colour.
                precise float nx = -scalarA[i];
                precise float ny = -scalarB[i];
                precise float len = sqrt(nx * nx + ny * ny + 1.0);
                precise vec3 channels = vec3(
                    ((nx / len) * 0.5 + 0.5) * 255.0,
                    ((ny / len) * 0.5 + 0.5) * 255.0,
                    ((1.0 / len) * 0.5 + 0.5) * 255.0
                );
                return clamp(floor(channels), vec3(0.0), vec3(255.0));
            }

            void main() {
                int x = int(gl_GlobalInvocationID.x);
                int row = int(gl_GlobalInvocationID.y);
                if (x >= uWidth || row >= uRows) return;

                int y = uRowStart + row;
                int i = y * uWidth + x;
                bool land = isLand(i);
                float relative = elevation[i];

                vec3 colour;
                bool standingWater = uShowLakes != 0 && lakeId[i] != -1;
                if (standingWater) {
                    // Depth from how far the water surface sits above the ground beneath it, so a
                    // deep basin reads darker than a shallow flood.
                    precise float depth = lakeSurface[lakeId[i]] - relative;
                    precise float shallowness = depth * 12.0;
                    colour = blend(uLake, uLakeDeep, clamp(shallowness, 0.0, 1.0));
                } else {
                    colour = baseColour(x, y, i, land, relative);
                    if (uHillshade != 0 && land) {
                        precise float relief = 1.0 + (hillshadeAt(x, y) - 1.0) * uReliefStrength;
                        if (uLineArt != 0) {
                            // Ink rather than shading: the paper is left alone and a stroke is laid
                            // on where the ground is steep, in a diagonal comb so the texture runs
                            // across the slope instead of smearing grey over it.
                            float steepness = max(1.0 - relief, 0.0);
                            float hatch = float(((x + y) % 5) + 1) / 6.0;
                            if (steepness * uInkGain > hatch) colour = uCoastline;
                        } else {
                            colour = shade(colour, relief);
                        }
                    }
                }

                if (uShowCoastline != 0 && land) {
                    bool right = isLand(y * uWidth + (x + 1) % uWidth);
                    bool down = y + 1 < uHeight ? isLand((y + 1) * uWidth + x) : true;
                    if (!right || !down) colour = blend(colour, uCoastline, uCoastlineStrength);
                }

                if (uShowBorders != 0 && land) {
                    int right = y * uWidth + (x + 1) % uWidth;
                    int down = y + 1 < uHeight ? (y + 1) * uWidth + x : i;
                    bool differs =
                        (isLand(right) && nationId[right] != nationId[i]) ||
                        (isLand(down) && nationId[down] != nationId[i]);
                    if (differs) colour = blend(colour, uBorder, 0.75);
                }

                uvec3 channels = uvec3(colour);
                pixels[row * uWidth + x] =
                    0xFF000000u | (channels.r << 16u) | (channels.g << 8u) | channels.b;
            }
        """.trimIndent()
    }
}
