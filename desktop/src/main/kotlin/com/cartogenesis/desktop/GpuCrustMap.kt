package com.cartogenesis.desktop

import com.cartogenesis.worldgen.noise.SphereNoise
import com.cartogenesis.worldgen.pipeline.CrustMapAccelerator
import com.cartogenesis.worldgen.pipeline.CrustMapInputs
import com.cartogenesis.worldgen.pipeline.CrustMapOutputs
import org.lwjgl.opengl.GL43C

/**
 * Reads the tectonic history's crust onto the map on the graphics card (docs/DESIGN_LEDGER.md, TH1-1).
 *
 * Rule 8's seam for `CrustMap`: one dispatch over the map's cells, each finding the history's points
 * within the kernel's reach by the lattice's latitude bands and longitude bins, weighing them by a
 * Gaussian of their distance on the sphere and summing — `CrustMap.readOnProcessor` line for line,
 * its warp and margin noises included, in single precision. There is nothing iterated, so the two
 * differ by how a float rounds a weight and where a point lies at the kernel's very reach, which is
 * what `CrustMapParity` bounds and `GpuCrustMapTest` measures.
 *
 * The history's points, their fields, the lattice's bins and the noises' tables cross as five
 * buffers, two of them packed, since a compute shader is promised only eight storage bindings.
 */
class GpuCrustMap private constructor(override val name: String) : CrustMapAccelerator {

    /** What probing this machine found: an accelerator, or the reason there is not one. */
    class Result(val accelerator: GpuCrustMap?, val unavailableBecause: String?)

    /** The compiled pass, or zero if it never compiled. Written once, on the probe. */
    private var program = 0

    override suspend fun read(inputs: CrustMapInputs): CrustMapOutputs? {
        val columns = inputs.mapColumns
        val rows = inputs.mapRows
        if (columns <= 0 || rows <= 0 || inputs.maxPlatesNear > MAX_PLATES_NEAR) return null
        val points = inputs.pointPlate.size
        val cells = columns * rows

        // The points' floats eight to a point and their integers four, as the shader reads them.
        val pointFloats = FloatArray(points * POINT_FLOATS)
        val pointInts = IntArray(points * POINT_INTS)
        for (point in 0 until points) {
            val f = point * POINT_FLOATS
            pointFloats[f] = inputs.pointXyz[3 * point].toFloat()
            pointFloats[f + 1] = inputs.pointXyz[3 * point + 1].toFloat()
            pointFloats[f + 2] = inputs.pointXyz[3 * point + 2].toFloat()
            pointFloats[f + 3] = inputs.pointThicknessKm[point]
            pointFloats[f + 4] = inputs.pointExcessKm[point]
            pointFloats[f + 5] = inputs.pointFormedMyrAgo[point]
            pointFloats[f + 6] = inputs.pointCrustAgeMyr[point]
            pointFloats[f + 7] = inputs.pointRiftMyrAgo[point]
            val i = point * POINT_INTS
            pointInts[i] = inputs.pointContinental[point]
            pointInts[i + 1] = inputs.pointPlate[point]
            pointInts[i + 2] = inputs.pointOrogenyKind[point]
            pointInts[i + 3] = inputs.pointTerrane[point]
        }
        // The lattice's bins and the noises' tables end to end, each found by its offset.
        val firstBinOffset = inputs.binsInBand.size
        val binStartOffset = firstBinOffset + inputs.firstBinOfBand.size
        val binPointsOffset = binStartOffset + inputs.binStart.size
        val permutationsOffset = binPointsOffset + inputs.binPoints.size
        val tables = IntArray(permutationsOffset + inputs.permutations.size)
        inputs.binsInBand.copyInto(tables, 0)
        inputs.firstBinOfBand.copyInto(tables, firstBinOffset)
        inputs.binStart.copyInto(tables, binStartOffset)
        inputs.binPoints.copyInto(tables, binPointsOffset)
        inputs.permutations.copyInto(tables, permutationsOffset)

        return GlContext.run("Reading the crust onto the map") {
            if (program == 0) return@run null
            val buffers = IntArray(BUFFERS)
            GL43C.glGenBuffers(buffers)
            try {
                upload(buffers[POINT_FLOATS_BINDING], POINT_FLOATS_BINDING, pointFloats)
                upload(buffers[POINT_INTS_BINDING], POINT_INTS_BINDING, pointInts)
                upload(buffers[TABLES_BINDING], TABLES_BINDING, tables)
                upload(buffers[OUT_FLOATS_BINDING], OUT_FLOATS_BINDING, FloatArray(cells * OUT_FLOATS))
                upload(buffers[OUT_INTS_BINDING], OUT_INTS_BINDING, IntArray(cells * OUT_INTS))
                if (GL43C.glGetError() != GL43C.GL_NO_ERROR) return@run null

                GL43C.glUseProgram(program)
                fun int(name: String, value: Int) = GL43C.glUniform1i(GL43C.glGetUniformLocation(program, name), value)
                fun float(name: String, value: Double) = GL43C.glUniform1f(GL43C.glGetUniformLocation(program, name), value.toFloat())
                int("uColumns", columns)
                int("uRows", rows)
                int("uPointCount", points)
                int("uBandCount", inputs.bandCount)
                int("uFirstBinOffset", firstBinOffset)
                int("uBinStartOffset", binStartOffset)
                int("uBinPointsOffset", binPointsOffset)
                int("uPermutationsOffset", permutationsOffset)
                float("uRadiusKm", inputs.radiusKm)
                float("uSpread", inputs.kernelSpreadRadians)
                float("uReach", inputs.kernelReachRadians)
                float("uWarpRadians", inputs.warpRadians)
                float("uWarpWavelengthKm", inputs.warpWavelengthKm)
                int("uWarpOctaves", inputs.warpOctaves)
                float("uMarginRoughness", inputs.marginRoughness.toDouble())
                float("uMarginWavelengthKm", inputs.marginWavelengthKm)
                int("uMarginOctaves", inputs.marginOctaves)
                float("uMarginPersistence", inputs.marginPersistence)
                float("uStandardThicknessKm", inputs.standardThicknessKm.toDouble())
                GL43C.glDispatchCompute(
                    (columns + WORK_GROUP_SIDE - 1) / WORK_GROUP_SIDE,
                    (rows + WORK_GROUP_SIDE - 1) / WORK_GROUP_SIDE,
                    1
                )
                GL43C.glMemoryBarrier(GL43C.GL_BUFFER_UPDATE_BARRIER_BIT)

                val outFloats = FloatArray(cells * OUT_FLOATS)
                val outInts = IntArray(cells * OUT_INTS)
                GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, buffers[OUT_FLOATS_BINDING])
                GL43C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0L, outFloats)
                GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, buffers[OUT_INTS_BINDING])
                GL43C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0L, outInts)
                if (GL43C.glGetError() != GL43C.GL_NO_ERROR) return@run null
                CrustMapOutputs(
                    continentalShare = FloatArray(cells) { outFloats[it * OUT_FLOATS] },
                    thicknessKm = FloatArray(cells) { outFloats[it * OUT_FLOATS + 1] },
                    excessKm = FloatArray(cells) { outFloats[it * OUT_FLOATS + 2] },
                    seafloorAgeMyr = FloatArray(cells) { outFloats[it * OUT_FLOATS + 3] },
                    crustAgeMyr = FloatArray(cells) { outFloats[it * OUT_FLOATS + 4] },
                    riftAgeMyr = FloatArray(cells) { outFloats[it * OUT_FLOATS + 5] },
                    orogenyKind = IntArray(cells) { outInts[it * OUT_INTS] },
                    terrane = IntArray(cells) { outInts[it * OUT_INTS + 1] },
                    plateId = IntArray(cells) { outInts[it * OUT_INTS + 2] }
                )
            } finally {
                // However the run ended, the buffers go back: the context outlives every
                // generation that uses it.
                GL43C.glDeleteBuffers(buffers)
            }
        }
    }

    private fun upload(buffer: Int, binding: Int, data: FloatArray) {
        GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, buffer)
        GL43C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, data, GL43C.GL_DYNAMIC_COPY)
        GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, buffer)
    }

    private fun upload(buffer: Int, binding: Int, data: IntArray) {
        GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, buffer)
        GL43C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, data, GL43C.GL_DYNAMIC_COPY)
        GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, buffer)
    }

    companion object {
        /** Storage binding points, matching the `layout(binding = ...)` lines in the source. */
        private const val POINT_FLOATS_BINDING = 0
        private const val POINT_INTS_BINDING = 1
        private const val TABLES_BINDING = 2
        private const val OUT_FLOATS_BINDING = 3
        private const val OUT_INTS_BINDING = 4
        private const val BUFFERS = 5

        /** A point's floats (x, y, z, thickness, excess, floor age, crust age, rift age) and integers (continental, plate, kind, terrane). */
        private const val POINT_FLOATS = 8
        private const val POINT_INTS = 4

        /** A cell's floats (share, thickness, excess, floor age, crust age, rift age) and integers (kind, terrane, plate). */
        private const val OUT_FLOATS = 6
        private const val OUT_INTS = 3

        /** The votes a cell keeps, the shader's fixed array; inputs asking for more are declined. */
        private const val MAX_PLATES_NEAR = 8

        /** The side of a work group, in invocations; the same figure is in the source's `local_size`. */
        private const val WORK_GROUP_SIDE = 16

        /** How long a driver may take over the shader before it is given up on. */
        private const val COMPILE_TIMEOUT_SECONDS = 30L

        /** Takes the shared offscreen context and compiles the pass, or says why it cannot. */
        fun createOrNull(): Result {
            val context = GlContext.ensure()
            val device = context.device ?: return Result(null, context.unavailableBecause ?: "unknown failure")
            val crust = GpuCrustMap(device)
            val compiled = GlContext.run("Compiling the crust's map pass", timeoutSeconds = COMPILE_TIMEOUT_SECONDS) {
                crust.program = GlContext.compileCompute(SOURCE)
                crust.program != 0
            }
            if (compiled != true) return Result(null, "the crust's map pass would not compile")
            return Result(crust, null)
        }

        /**
         * `CrustMap.readOnProcessor`, a cell an invocation, in the same order of operations: the
         * warp, the bins' search, the Gaussian's sums, the margin's roughening, the votes. The angle
         * between a point and the cell is taken from their chord, `2 asin(|p - q| / 2)`, which a
         * float keeps to its last bits at these small angles where an arc cosine of a float would
         * not.
         */
        private val SOURCE = """
            #version 430
            layout(local_size_x = $WORK_GROUP_SIDE, local_size_y = $WORK_GROUP_SIDE) in;

            layout(std430, binding = $POINT_FLOATS_BINDING) readonly buffer PointFloats { float pointFloats[]; };
            layout(std430, binding = $POINT_INTS_BINDING) readonly buffer PointInts { int pointInts[]; };
            layout(std430, binding = $TABLES_BINDING) readonly buffer Tables { int tables[]; };
            layout(std430, binding = $OUT_FLOATS_BINDING) writeonly buffer OutFloats { float outFloats[]; };
            layout(std430, binding = $OUT_INTS_BINDING) writeonly buffer OutInts { int outInts[]; };

            uniform int uColumns;
            uniform int uRows;
            uniform int uPointCount;
            uniform int uBandCount;
            uniform int uFirstBinOffset;
            uniform int uBinStartOffset;
            uniform int uBinPointsOffset;
            uniform int uPermutationsOffset;
            uniform float uRadiusKm;
            uniform float uSpread;
            uniform float uReach;
            uniform float uWarpRadians;
            uniform float uWarpWavelengthKm;
            uniform int uWarpOctaves;
            uniform float uMarginRoughness;
            uniform float uMarginWavelengthKm;
            uniform int uMarginOctaves;
            uniform float uMarginPersistence;
            uniform float uStandardThicknessKm;

            const float PI = 3.141592653589793;
            const int TABLE_ENTRIES = ${SphereNoise.PERMUTATION_ENTRIES};
            const float OCTAVE_OFFSET = ${SphereNoise.OCTAVE_OFFSET};
            const int MAX_NEAR = $MAX_PLATES_NEAR;
            const int MARGIN_NOISE = 3;

            int permutation(int noise, int index) { return tables[uPermutationsOffset + noise * TABLE_ENTRIES + index]; }
            float fade(float t) { return t * t * t * (t * (t * 6.0 - 15.0) + 10.0); }
            float lerpTo(float from, float to, float t) { return from + t * (to - from); }
            float gradientDot(int hash, float x, float y, float z) {
                int selector = hash & 15;
                float first = selector < 8 ? x : y;
                float second = selector < 4 ? y : ((selector == 12 || selector == 14) ? x : z);
                return ((selector & 1) == 0 ? first : -first) + ((selector & 2) == 0 ? second : -second);
            }
            float noise3(int n, float x, float y, float z) {
                float fx = floor(x); float fy = floor(y); float fz = floor(z);
                int hx = int(fx) & 255; int hy = int(fy) & 255; int hz = int(fz) & 255;
                float ox = x - fx; float oy = y - fy; float oz = z - fz;
                float bx = fade(ox); float by = fade(oy); float bz = fade(oz);
                int a = permutation(n, hx) + hy;
                int aa = permutation(n, a) + hz;
                int ab = permutation(n, a + 1) + hz;
                int b = permutation(n, hx + 1) + hy;
                int ba = permutation(n, b) + hz;
                int bb = permutation(n, b + 1) + hz;
                float near = lerpTo(
                    lerpTo(gradientDot(permutation(n, aa), ox, oy, oz), gradientDot(permutation(n, ba), ox - 1.0, oy, oz), bx),
                    lerpTo(gradientDot(permutation(n, ab), ox, oy - 1.0, oz), gradientDot(permutation(n, bb), ox - 1.0, oy - 1.0, oz), bx),
                    by);
                float far = lerpTo(
                    lerpTo(gradientDot(permutation(n, aa + 1), ox, oy, oz - 1.0), gradientDot(permutation(n, ba + 1), ox - 1.0, oy, oz - 1.0), bx),
                    lerpTo(gradientDot(permutation(n, ab + 1), ox, oy - 1.0, oz - 1.0), gradientDot(permutation(n, bb + 1), ox - 1.0, oy - 1.0, oz - 1.0), bx),
                    by);
                return lerpTo(near, far, bz);
            }
            float fbmOnSphere(int n, vec3 p, float wavelengthKm, int octaves, float persistence) {
                float frequency = uRadiusKm / wavelengthKm;
                float amplitude = 1.0;
                float sum = 0.0;
                float amplitudes = 0.0;
                for (int octave = 0; octave < octaves; ++octave) {
                    sum += amplitude * noise3(n, p.x * frequency + OCTAVE_OFFSET * float(octave), p.y * frequency, p.z * frequency);
                    amplitudes += amplitude;
                    frequency *= 2.0;
                    amplitude *= persistence;
                }
                return sum / amplitudes;
            }
            vec3 pointAt(int point) {
                return vec3(pointFloats[point * 8], pointFloats[point * 8 + 1], pointFloats[point * 8 + 2]);
            }
            // GLSL leaves `%` undefined for a negative operand; a bin's index is never below minus
            // half the band's bins (the reach is under half a turn), so one turn added makes it safe.
            int positiveModulo(int value, int divisor) { return (value + divisor) % divisor; }

            void main() {
                int column = int(gl_GlobalInvocationID.x);
                int row = int(gl_GlobalInvocationID.y);
                if (column >= uColumns || row >= uRows) return;
                int cell = row * uColumns + column;
                float latitude = PI / 2.0 - (float(row) + 0.5) / float(uRows) * PI;
                float longitude = (float(column) + 0.5) / float(uColumns) * 2.0 * PI;
                vec3 p = vec3(cos(latitude) * cos(longitude), cos(latitude) * sin(longitude), sin(latitude));
                vec3 q = p + uWarpRadians * vec3(
                    fbmOnSphere(0, p, uWarpWavelengthKm, uWarpOctaves, 0.5),
                    fbmOnSphere(1, p, uWarpWavelengthKm, uWarpOctaves, 0.5),
                    fbmOnSphere(2, p, uWarpWavelengthKm, uWarpOctaves, 0.5));
                q = q / length(q);

                float weightSum = 0.0; float continentWeight = 0.0; float thicknessSum = 0.0; float excessSum = 0.0;
                float oceanWeight = 0.0; float floorAgeSum = 0.0; float crustAgeSum = 0.0; float riftWeight = 0.0; float riftSum = 0.0;
                int heaviest = -1; float heaviestWeight = -1.0;
                int votePlate[MAX_NEAR]; float voteWeight[MAX_NEAR]; int votes = 0;

                float bandHeight = PI / float(uBandCount);
                float colatitude = acos(clamp(q.z, -1.0, 1.0));
                int firstBand = clamp(int((colatitude - uReach) / bandHeight), 0, uBandCount - 1);
                int lastBand = clamp(int((colatitude + uReach) / bandHeight), 0, uBandCount - 1);
                float queryLongitude = atan(q.y, q.x);
                if (queryLongitude < 0.0) queryLongitude += 2.0 * PI;
                float centerSine = sin(colatitude);
                float reachSine = sin(uReach);
                bool wholeRing = colatitude - uReach <= 0.0 || colatitude + uReach >= PI || centerSine <= reachSine;
                float halfWidth = wholeRing ? PI : asin(reachSine / centerSine);
                float minimumCosine = cos(uReach);
                for (int band = firstBand; band <= lastBand; ++band) {
                    int bins = tables[band];
                    int first = tables[uFirstBinOffset + band];
                    float binWidth = 2.0 * PI / float(bins);
                    int lowBin = int(floor((queryLongitude - halfWidth) / binWidth));
                    int highBin = int(floor((queryLongitude + halfWidth) / binWidth));
                    int span = (wholeRing || bins <= 3 || highBin - lowBin + 1 >= bins) ? bins : highBin - lowBin + 1;
                    int start = span == bins ? 0 : lowBin;
                    for (int step = 0; step < span; ++step) {
                        int bin = first + positiveModulo(start + step, bins);
                        int end = tables[uBinStartOffset + bin + 1];
                        for (int index = tables[uBinStartOffset + bin]; index < end; ++index) {
                            int point = tables[uBinPointsOffset + index];
                            vec3 at = pointAt(point);
                            if (dot(at, q) < minimumCosine) continue;
                            float angle = 2.0 * asin(min(1.0, 0.5 * length(at - q)));
                            float scaled = angle / uSpread;
                            float weight = exp(-scaled * scaled);
                            weightSum += weight;
                            crustAgeSum += weight * pointFloats[point * 8 + 6];
                            if (pointInts[point * 4] == 1) {
                                continentWeight += weight;
                                thicknessSum += weight * pointFloats[point * 8 + 3];
                                excessSum += weight * pointFloats[point * 8 + 4];
                            } else {
                                oceanWeight += weight;
                                floorAgeSum += weight * pointFloats[point * 8 + 5];
                            }
                            if (pointFloats[point * 8 + 7] >= 0.0) {
                                riftWeight += weight;
                                riftSum += weight * pointFloats[point * 8 + 7];
                            }
                            if (weight > heaviestWeight) { heaviestWeight = weight; heaviest = point; }
                            int pointPlate = pointInts[point * 4 + 1];
                            int slot = -1;
                            for (int v = 0; v < votes; ++v) if (votePlate[v] == pointPlate) slot = v;
                            if (slot < 0 && votes < MAX_NEAR) { slot = votes; votes++; votePlate[slot] = pointPlate; voteWeight[slot] = 0.0; }
                            if (slot >= 0) voteWeight[slot] += weight;
                        }
                    }
                }
                if (heaviest < 0) {
                    // Only where the lattice is sparser than its covering radius, which it never is.
                    float best = -2.0;
                    for (int point = 0; point < uPointCount; ++point) {
                        float cosine = dot(pointAt(point), q);
                        if (cosine > best) { best = cosine; heaviest = point; }
                    }
                    weightSum = 1.0;
                    if (pointInts[heaviest * 4] == 1) continentWeight = 1.0; else oceanWeight = 1.0;
                    thicknessSum = pointFloats[heaviest * 8 + 3];
                    excessSum = pointFloats[heaviest * 8 + 4];
                    floorAgeSum = pointFloats[heaviest * 8 + 5];
                    crustAgeSum = pointFloats[heaviest * 8 + 6];
                    votes = 1; votePlate[0] = pointInts[heaviest * 4 + 1]; voteWeight[0] = 1.0;
                }
                float mixture = continentWeight / weightSum;
                float band = 4.0 * mixture * (1.0 - mixture);
                if (band > 0.0) {
                    mixture += uMarginRoughness * band * fbmOnSphere(MARGIN_NOISE, p, uMarginWavelengthKm, uMarginOctaves, uMarginPersistence);
                }
                outFloats[cell * 6] = clamp(mixture, 0.0, 1.0);
                outFloats[cell * 6 + 1] = continentWeight > 0.0 ? thicknessSum / continentWeight : uStandardThicknessKm;
                outFloats[cell * 6 + 2] = continentWeight > 0.0 ? excessSum / continentWeight : 0.0;
                outFloats[cell * 6 + 3] = oceanWeight > 0.0 ? floorAgeSum / oceanWeight : pointFloats[heaviest * 8 + 5];
                outFloats[cell * 6 + 4] = crustAgeSum / weightSum;
                outFloats[cell * 6 + 5] = riftWeight * 2.0 >= weightSum ? riftSum / riftWeight : -1.0;
                outInts[cell * 3] = pointInts[heaviest * 4 + 2];
                outInts[cell * 3 + 1] = pointInts[heaviest * 4 + 3];
                int winner = 0;
                for (int v = 1; v < votes; ++v) {
                    if (voteWeight[v] > voteWeight[winner] || (voteWeight[v] == voteWeight[winner] && votePlate[v] < votePlate[winner])) winner = v;
                }
                outInts[cell * 3 + 2] = votePlate[winner];
            }
        """.trimIndent()
    }
}
