package com.cartogenesis.desktop

import com.cartogenesis.worldgen.noise.SphereNoise
import com.cartogenesis.worldgen.pipeline.BeltFieldInputs
import com.cartogenesis.worldgen.pipeline.BeltFieldOutputs
import com.cartogenesis.worldgen.pipeline.BeltFieldsHash
import com.cartogenesis.worldgen.pipeline.CrustMapAccelerator
import com.cartogenesis.worldgen.pipeline.CrustMapInputs
import com.cartogenesis.worldgen.pipeline.CrustMapOutputs
import org.lwjgl.opengl.GL43C
import kotlin.math.ln

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
 *
 * The belts' map pass (`BeltFields.readOnProcessor`, TH1-2) is the plate stage's other per-cell
 * work and runs here too, a second program: each cell's rock erodibility and its ridges' and
 * province's sparse Gabor noise, the same impulses from the same integer hash, summed in single
 * precision (`BeltFieldsParity`, `GpuBeltFieldsTest`).
 */
class GpuCrustMap private constructor(override val name: String) : CrustMapAccelerator {

    /** What probing this machine found: an accelerator, or the reason there is not one. */
    class Result(val accelerator: GpuCrustMap?, val unavailableBecause: String?)

    /** The compiled passes, or zero if they never compiled. Written once, on the probe. */
    private var program = 0
    private var beltProgram = 0

    override suspend fun read(inputs: CrustMapInputs): CrustMapOutputs? {
        val columns = inputs.mapColumns
        val rows = inputs.mapRows
        if (columns <= 0 || rows <= 0 || inputs.maxPlatesNear > MAX_PLATES_NEAR) return null
        val points = inputs.pointPlate.size
        val cells = columns * rows

        // The points' floats eighteen to a point and their integers four, as the shader reads them.
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
            pointFloats[f + 8] = inputs.pointWindowTectonicKm[point]
            pointFloats[f + 9] = inputs.pointWindowWearKm[point]
            pointFloats[f + 10] = inputs.pointWindowOnsetMyrAgo[point]
            pointFloats[f + 11] = inputs.pointPresentThickeningKmPerMyr[point]
            for (component in 0 until STRIKE_COMPONENTS) {
                pointFloats[f + 12 + component] = inputs.pointStrikeTensor[STRIKE_COMPONENTS * point + component]
            }
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
                    plateId = IntArray(cells) { outInts[it * OUT_INTS + 2] },
                    windowTectonicKm = FloatArray(cells) { outFloats[it * OUT_FLOATS + 6] },
                    windowWearKm = FloatArray(cells) { outFloats[it * OUT_FLOATS + 7] },
                    windowOnsetMyrAgo = FloatArray(cells) { outFloats[it * OUT_FLOATS + 8] },
                    presentThickeningKmPerMyr = FloatArray(cells) { outFloats[it * OUT_FLOATS + 9] },
                    strikeEastEast = FloatArray(cells) { outFloats[it * OUT_FLOATS + 10] },
                    strikeEastNorth = FloatArray(cells) { outFloats[it * OUT_FLOATS + 11] },
                    strikeNorthNorth = FloatArray(cells) { outFloats[it * OUT_FLOATS + 12] }
                )
            } finally {
                // However the run ended, the buffers go back: the context outlives every
                // generation that uses it.
                GL43C.glDeleteBuffers(buffers)
            }
        }
    }

    override suspend fun beltFields(inputs: BeltFieldInputs): BeltFieldOutputs? {
        val columns = inputs.mapColumns
        val rows = inputs.mapRows
        val octaves = inputs.ridgeWavelengthsKm.size
        if (columns <= 0 || rows <= 0 || octaves > MAX_RIDGE_OCTAVES || octaves != inputs.ridgeOctaveWeights.size) return null
        val cells = columns * rows
        val cellFloats = FloatArray(cells * BELT_IN_FLOATS)
        for (cell in 0 until cells) {
            val f = cell * BELT_IN_FLOATS
            cellFloats[f] = inputs.strikeEastEast[cell]
            cellFloats[f + 1] = inputs.strikeEastNorth[cell]
            cellFloats[f + 2] = inputs.strikeNorthNorth[cell]
            cellFloats[f + 3] = inputs.ridgeAmplitudeMetres[cell]
            cellFloats[f + 4] = inputs.provinceAmplitudeMetres[cell]
            cellFloats[f + 5] = inputs.softness[cell]
            cellFloats[f + 6] = inputs.weakZoneKm[cell]
        }
        return GlContext.run("Drawing the belts' ridges and rock") {
            if (beltProgram == 0) return@run null
            val buffers = IntArray(BELT_BUFFERS)
            GL43C.glGenBuffers(buffers)
            try {
                upload(buffers[BELT_IN_BINDING], BELT_IN_BINDING, cellFloats)
                upload(buffers[BELT_OUT_BINDING], BELT_OUT_BINDING, FloatArray(cells * BELT_OUT_FLOATS))
                if (GL43C.glGetError() != GL43C.GL_NO_ERROR) return@run null
                GL43C.glUseProgram(beltProgram)
                fun int(name: String, value: Int) = GL43C.glUniform1i(GL43C.glGetUniformLocation(beltProgram, name), value)
                fun float(name: String, value: Double) = GL43C.glUniform1f(GL43C.glGetUniformLocation(beltProgram, name), value.toFloat())
                int("uColumns", columns)
                int("uRows", rows)
                float("uRadiusKm", inputs.radiusKm)
                // The seed's bits as the hash reads them: GLSL takes it as a signed integer and the
                // shader turns it to unsigned, which keeps every bit.
                int("uSeed", inputs.seed)
                int("uOctaves", octaves)
                for (octave in 0 until octaves) {
                    float("uRidgeWavelengthsKm[$octave]", inputs.ridgeWavelengthsKm[octave])
                    float("uRidgeWeights[$octave]", inputs.ridgeOctaveWeights[octave])
                }
                float("uProvinceWavelengthKm", inputs.provinceWavelengthKm)
                float("uElongation", inputs.elongation)
                int("uImpulses", inputs.impulsesPerCube)
                float("uWeakZoneEfoldKm", inputs.weakZoneEfoldKm)
                float("uLogBand", ln(inputs.erodibilityBandFactor))
                GL43C.glDispatchCompute(
                    (columns + WORK_GROUP_SIDE - 1) / WORK_GROUP_SIDE,
                    (rows + WORK_GROUP_SIDE - 1) / WORK_GROUP_SIDE,
                    1
                )
                GL43C.glMemoryBarrier(GL43C.GL_BUFFER_UPDATE_BARRIER_BIT)
                val out = FloatArray(cells * BELT_OUT_FLOATS)
                GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, buffers[BELT_OUT_BINDING])
                GL43C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0L, out)
                if (GL43C.glGetError() != GL43C.GL_NO_ERROR) return@run null
                BeltFieldOutputs(
                    ridgeMetres = FloatArray(cells) { out[it * BELT_OUT_FLOATS] },
                    provinceMetres = FloatArray(cells) { out[it * BELT_OUT_FLOATS + 1] },
                    rockErodibility = FloatArray(cells) { out[it * BELT_OUT_FLOATS + 2] }
                )
            } finally {
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

        /**
         * A point's floats (x, y, z, thickness, excess, floor age, crust age, rift age, the window's
         * tectonic change, wear and onset, the present thickening rate, the strike tensor's six) and
         * integers (continental, plate, kind, terrane).
         */
        private const val POINT_FLOATS = 18
        private const val POINT_INTS = 4
        private const val STRIKE_COMPONENTS = 6

        /**
         * A cell's floats (share, thickness, excess, floor age, crust age, rift age, the window's
         * tectonic change, wear and onset, the present rate, the strike tensor's east-east,
         * east-north and north-north) and integers (kind, terrane, plate).
         */
        private const val OUT_FLOATS = 13
        private const val OUT_INTS = 3

        /** The belts' pass: its storage bindings, a cell's floats in and out, and the most ridge octaves its uniforms hold. */
        private const val BELT_IN_BINDING = 0
        private const val BELT_OUT_BINDING = 1
        private const val BELT_BUFFERS = 2
        private const val BELT_IN_FLOATS = 7
        private const val BELT_OUT_FLOATS = 3
        private const val MAX_RIDGE_OCTAVES = 4

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
                crust.beltProgram = GlContext.compileCompute(BELT_SOURCE)
                crust.program != 0 && crust.beltProgram != 0
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
            const int PF = $POINT_FLOATS;
            const int OF = $OUT_FLOATS;
            vec3 pointAt(int point) {
                return vec3(pointFloats[point * PF], pointFloats[point * PF + 1], pointFloats[point * PF + 2]);
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
                float windowTectonicSum = 0.0; float windowWearSum = 0.0; float presentRateSum = 0.0; float onsetWeight = 0.0; float onsetSum = 0.0;
                float txx = 0.0; float txy = 0.0; float txz = 0.0; float tyy = 0.0; float tyz = 0.0; float tzz = 0.0;
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
                            crustAgeSum += weight * pointFloats[point * PF + 6];
                            txx += weight * pointFloats[point * PF + 12];
                            txy += weight * pointFloats[point * PF + 13];
                            txz += weight * pointFloats[point * PF + 14];
                            tyy += weight * pointFloats[point * PF + 15];
                            tyz += weight * pointFloats[point * PF + 16];
                            tzz += weight * pointFloats[point * PF + 17];
                            if (pointInts[point * 4] == 1) {
                                continentWeight += weight;
                                thicknessSum += weight * pointFloats[point * PF + 3];
                                excessSum += weight * pointFloats[point * PF + 4];
                                windowTectonicSum += weight * pointFloats[point * PF + 8];
                                windowWearSum += weight * pointFloats[point * PF + 9];
                                presentRateSum += weight * pointFloats[point * PF + 11];
                                if (pointFloats[point * PF + 10] >= 0.0) {
                                    onsetWeight += weight;
                                    onsetSum += weight * pointFloats[point * PF + 10];
                                }
                            } else {
                                oceanWeight += weight;
                                floorAgeSum += weight * pointFloats[point * PF + 5];
                            }
                            if (pointFloats[point * PF + 7] >= 0.0) {
                                riftWeight += weight;
                                riftSum += weight * pointFloats[point * PF + 7];
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
                    thicknessSum = pointFloats[heaviest * PF + 3];
                    excessSum = pointFloats[heaviest * PF + 4];
                    windowTectonicSum = pointFloats[heaviest * PF + 8];
                    windowWearSum = pointFloats[heaviest * PF + 9];
                    presentRateSum = pointFloats[heaviest * PF + 11];
                    if (pointFloats[heaviest * PF + 10] >= 0.0) {
                        onsetWeight = continentWeight;
                        onsetSum = continentWeight * pointFloats[heaviest * PF + 10];
                    }
                    txx = pointFloats[heaviest * PF + 12]; txy = pointFloats[heaviest * PF + 13]; txz = pointFloats[heaviest * PF + 14];
                    tyy = pointFloats[heaviest * PF + 15]; tyz = pointFloats[heaviest * PF + 16]; tzz = pointFloats[heaviest * PF + 17];
                    floorAgeSum = pointFloats[heaviest * PF + 5];
                    crustAgeSum = pointFloats[heaviest * PF + 6];
                    votes = 1; votePlate[0] = pointInts[heaviest * 4 + 1]; voteWeight[0] = 1.0;
                }
                float mixture = continentWeight / weightSum;
                float band = 4.0 * mixture * (1.0 - mixture);
                if (band > 0.0) {
                    mixture += uMarginRoughness * band * fbmOnSphere(MARGIN_NOISE, p, uMarginWavelengthKm, uMarginOctaves, uMarginPersistence);
                }
                outFloats[cell * OF] = clamp(mixture, 0.0, 1.0);
                outFloats[cell * OF + 1] = continentWeight > 0.0 ? thicknessSum / continentWeight : uStandardThicknessKm;
                outFloats[cell * OF + 2] = continentWeight > 0.0 ? excessSum / continentWeight : 0.0;
                outFloats[cell * OF + 3] = oceanWeight > 0.0 ? floorAgeSum / oceanWeight : pointFloats[heaviest * PF + 5];
                outFloats[cell * OF + 4] = crustAgeSum / weightSum;
                outFloats[cell * OF + 5] = riftWeight * 2.0 >= weightSum ? riftSum / riftWeight : -1.0;
                outFloats[cell * OF + 6] = continentWeight > 0.0 ? windowTectonicSum / continentWeight : 0.0;
                outFloats[cell * OF + 7] = continentWeight > 0.0 ? windowWearSum / continentWeight : 0.0;
                outFloats[cell * OF + 8] = (continentWeight > 0.0 && onsetWeight * 2.0 >= continentWeight) ? onsetSum / onsetWeight : -1.0;
                outFloats[cell * OF + 9] = continentWeight > 0.0 ? presentRateSum / continentWeight : 0.0;
                // The tensor in the cell's own east and north, the frame a map direction is read in.
                float eastX = -sin(longitude);
                float eastY = cos(longitude);
                float northX = -sin(latitude) * cos(longitude);
                float northY = -sin(latitude) * sin(longitude);
                float northZ = cos(latitude);
                outFloats[cell * OF + 10] = (txx * eastX * eastX + 2.0 * txy * eastX * eastY + tyy * eastY * eastY) / weightSum;
                outFloats[cell * OF + 11] = (txx * eastX * northX + txy * (eastX * northY + eastY * northX) + txz * eastX * northZ +
                    tyy * eastY * northY + tyz * eastY * northZ) / weightSum;
                outFloats[cell * OF + 12] = (txx * northX * northX + tyy * northY * northY + tzz * northZ * northZ +
                    2.0 * (txy * northX * northY + txz * northX * northZ + tyz * northY * northZ)) / weightSum;
                outInts[cell * 3] = pointInts[heaviest * 4 + 2];
                outInts[cell * 3 + 1] = pointInts[heaviest * 4 + 3];
                int winner = 0;
                for (int v = 1; v < votes; ++v) {
                    if (voteWeight[v] > voteWeight[winner] || (voteWeight[v] == voteWeight[winner] && votePlate[v] < votePlate[winner])) winner = v;
                }
                outInts[cell * 3 + 2] = votePlate[winner];
            }
        """.trimIndent()

        /**
         * `BeltFields.readOnProcessor`, a cell an invocation: the erodibility from the softness and
         * the weak zone, then the ridges' octaves and the province's sparse Gabor noise across the
         * strike's leading direction, from the same Wellons hash in unsigned arithmetic, which gives
         * the processor's bits for every impulse's place, weight and phase.
         */
        private val BELT_SOURCE = """
            #version 430
            layout(local_size_x = $WORK_GROUP_SIDE, local_size_y = $WORK_GROUP_SIDE) in;

            layout(std430, binding = $BELT_IN_BINDING) readonly buffer CellFloats { float cellFloats[]; };
            layout(std430, binding = $BELT_OUT_BINDING) writeonly buffer OutFloats { float outFloats[]; };

            uniform int uColumns;
            uniform int uRows;
            uniform float uRadiusKm;
            uniform int uSeed;
            uniform int uOctaves;
            uniform float uRidgeWavelengthsKm[$MAX_RIDGE_OCTAVES];
            uniform float uRidgeWeights[$MAX_RIDGE_OCTAVES];
            uniform float uProvinceWavelengthKm;
            uniform float uElongation;
            uniform int uImpulses;
            uniform float uWeakZoneEfoldKm;
            uniform float uLogBand;

            const float PI = 3.141592653589793;
            const uint OCTAVE_STEP = ${BeltFieldsHash.OCTAVE_STEP_UNSIGNED}u;
            const uint IMPULSE_STEP = ${BeltFieldsHash.IMPULSE_STEP_UNSIGNED}u;
            const int PROVINCE_OCTAVE = ${BeltFieldsHash.PROVINCE_OCTAVE};
            const float UNIT_DIVISOR = 16777216.0;
            const float VARIANCE_DIVISOR = ${BeltFieldsHash.VARIANCE_DIVISOR};

            uint mixHash(uint value) {
                uint bits = value;
                bits ^= bits >> 16;
                bits *= 0x7feb352du;
                bits ^= bits >> 15;
                bits *= 0x846ca68bu;
                bits ^= bits >> 16;
                return bits;
            }
            float unitOf(uint hash) { return float(hash >> 8) / UNIT_DIVISOR; }
            uint cubeHash(int octave, ivec3 cube) {
                uint hash = mixHash(uint(uSeed) + OCTAVE_STEP * uint(octave + 1));
                hash = mixHash(hash ^ uint(cube.x));
                hash = mixHash(hash ^ uint(cube.y));
                hash = mixHash(hash ^ uint(cube.z));
                return hash;
            }
            float gaborAt(vec3 p, vec3 omega, float wavelengthKm, int octave) {
                float cubeKm = uElongation * wavelengthKm;
                float bandwidth = 1.0 / cubeKm;
                float frequency = 1.0 / wavelengthKm;
                float reach2 = cubeKm * cubeKm;
                ivec3 home = ivec3(floor(p / cubeKm));
                float sum = 0.0;
                for (int ox = -1; ox <= 1; ++ox) for (int oy = -1; oy <= 1; ++oy) for (int oz = -1; oz <= 1; ++oz) {
                    ivec3 cube = home + ivec3(ox, oy, oz);
                    uint hash = cubeHash(octave, cube);
                    for (int impulse = 0; impulse < uImpulses; ++impulse) {
                        hash = mixHash(hash + IMPULSE_STEP);
                        float ix = (float(cube.x) + unitOf(hash)) * cubeKm;
                        hash = mixHash(hash);
                        float iy = (float(cube.y) + unitOf(hash)) * cubeKm;
                        hash = mixHash(hash);
                        float iz = (float(cube.z) + unitOf(hash)) * cubeKm;
                        hash = mixHash(hash);
                        float weight = 2.0 * unitOf(hash) - 1.0;
                        hash = mixHash(hash);
                        float phase = 2.0 * PI * unitOf(hash);
                        vec3 d = p - vec3(ix, iy, iz);
                        float distance2 = dot(d, d);
                        if (distance2 >= reach2) continue;
                        float along = dot(omega, d);
                        sum += weight * exp(-PI * bandwidth * bandwidth * distance2) * cos(2.0 * PI * frequency * along + phase);
                    }
                }
                return sum * sqrt(VARIANCE_DIVISOR / float(uImpulses));
            }

            void main() {
                int column = int(gl_GlobalInvocationID.x);
                int row = int(gl_GlobalInvocationID.y);
                if (column >= uColumns || row >= uRows) return;
                int cell = row * uColumns + column;
                int f = cell * $BELT_IN_FLOATS;
                float weakKm = cellFloats[f + 6];
                float weak = weakKm < 0.0 ? 0.0 : exp(-weakKm / uWeakZoneEfoldKm);
                float softness = max(cellFloats[f + 5], weak);
                float ridge = 0.0;
                float province = 0.0;
                float ridgeAmplitude = cellFloats[f + 3];
                float provinceAmplitude = cellFloats[f + 4];
                if (ridgeAmplitude > 0.0 || provinceAmplitude > 0.0) {
                    float latitude = PI / 2.0 - (float(row) + 0.5) / float(uRows) * PI;
                    float longitude = (float(column) + 0.5) / float(uColumns) * 2.0 * PI;
                    vec3 unit = vec3(cos(latitude) * cos(longitude), cos(latitude) * sin(longitude), sin(latitude));
                    float across = 0.5 * atan(2.0 * cellFloats[f + 1], cellFloats[f] - cellFloats[f + 2]);
                    float eastShare = cos(across);
                    float northShare = sin(across);
                    vec3 omega = vec3(
                        eastShare * -sin(longitude) + northShare * -sin(latitude) * cos(longitude),
                        eastShare * cos(longitude) + northShare * -sin(latitude) * sin(longitude),
                        northShare * cos(latitude));
                    vec3 p = unit * uRadiusKm;
                    if (ridgeAmplitude > 0.0) {
                        float sum = 0.0;
                        for (int octave = 0; octave < uOctaves; ++octave) {
                            sum += uRidgeWeights[octave] * gaborAt(p, omega, uRidgeWavelengthsKm[octave], octave);
                        }
                        ridge = ridgeAmplitude * sum;
                    }
                    if (provinceAmplitude > 0.0) province = provinceAmplitude * gaborAt(p, omega, uProvinceWavelengthKm, PROVINCE_OCTAVE);
                }
                outFloats[cell * $BELT_OUT_FLOATS] = ridge;
                outFloats[cell * $BELT_OUT_FLOATS + 1] = province;
                outFloats[cell * $BELT_OUT_FLOATS + 2] = exp(softness * uLogBand);
            }
        """.trimIndent()
    }
}
