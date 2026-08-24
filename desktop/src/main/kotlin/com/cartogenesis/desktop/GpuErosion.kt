package com.cartogenesis.desktop

import com.cartogenesis.worldgen.pipeline.ErosionAccelerator
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL43C
import org.lwjgl.system.MemoryUtil

/**
 * Runs the erosion sweeps on the graphics card.
 *
 * The algorithm is the CPU one, unchanged: each sweep works out how much material every cell holds
 * above the critical slope and what share of it to hand over, then a second pass moves it. That is
 * two dispatches per sweep with a memory barrier between them, because the second pass reads what
 * the first wrote for cells its own thread does not own.
 *
 * Two things this does *not* do, both deliberate:
 *
 * It does not skip settled ground. On the CPU that saves around 1.3x by not re-scanning quiet
 * tiles, but it costs a dependent read and a dilation between sweeps; on hardware with thousands
 * of lanes, doing the arithmetic everywhere is cheaper than deciding where to skip it.
 *
 * It does not promise the CPU's answer. Graphics hardware fuses multiplies and adds, keeps
 * intermediates at different widths, and is under no obligation to sum in any particular order.
 * The terrain that comes back is the same world in every way a person could see, and is not the
 * same numbers, which is why choosing this path makes a world carry its terrain in the save
 * instead of being regenerated from its seed.
 */
class GpuErosion private constructor(private val deviceName: String) : ErosionAccelerator {

    override val name: String get() = deviceName

    /** What probing this machine found: an accelerator, or the reason there is not one. */
    class Result(val accelerator: GpuErosion?, val unavailableBecause: String?)

    override suspend fun erode(
        width: Int,
        height: Int,
        heights: FloatArray,
        talus: Float,
        passes: Int,
        rate: Float
    ): FloatArray? = runOnContext {
        val cells = width * height
        val orthogonal = talus / width
        val diagonal = orthogonal * kotlin.math.sqrt(2f)
        val settled = orthogonal * 1e-3f

        val compiled = programs ?: return@runOnContext null
        val phaseA = compiled.first
        val phaseB = compiled.second

        // Two height buffers to ping-pong between, and one for the transfer ratios.
        val buffers = IntArray(3)
        GL43C.glGenBuffers(buffers)
        val (readBuffer, writeBuffer, rateBuffer) = Triple(buffers[0], buffers[1], buffers[2])

        try {
            GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, readBuffer)
            GL43C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, heights, GL43C.GL_DYNAMIC_COPY)
            GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, writeBuffer)
            GL43C.glBufferData(
                GL43C.GL_SHADER_STORAGE_BUFFER, (cells * 4).toLong(), GL43C.GL_DYNAMIC_COPY
            )
            GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, rateBuffer)
            GL43C.glBufferData(
                GL43C.GL_SHADER_STORAGE_BUFFER, (cells * 4).toLong(), GL43C.GL_DYNAMIC_COPY
            )

            val groupsX = (width + GROUP - 1) / GROUP
            val groupsY = (height + GROUP - 1) / GROUP

            var source = readBuffer
            var destination = writeBuffer

            repeat(passes) {
                GL43C.glUseProgram(phaseA)
                setUniforms(phaseA, width, height, orthogonal, diagonal, rate, settled)
                GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, source)
                GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 2, rateBuffer)
                GL43C.glDispatchCompute(groupsX, groupsY, 1)
                GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT)

                GL43C.glUseProgram(phaseB)
                setUniforms(phaseB, width, height, orthogonal, diagonal, rate, settled)
                GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, source)
                GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, destination)
                GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 2, rateBuffer)
                GL43C.glDispatchCompute(groupsX, groupsY, 1)
                GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT)

                val swap = source
                source = destination
                destination = swap
            }

            val result = FloatArray(cells)
            GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, source)
            GL43C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0L, result)
            result
        } finally {
            GL43C.glDeleteBuffers(buffers)
        }
    }

    private fun setUniforms(
        program: Int,
        width: Int,
        height: Int,
        orthogonal: Float,
        diagonal: Float,
        rate: Float,
        settled: Float
    ) {
        GL43C.glUniform1i(GL43C.glGetUniformLocation(program, "uWidth"), width)
        GL43C.glUniform1i(GL43C.glGetUniformLocation(program, "uHeight"), height)
        GL43C.glUniform1f(GL43C.glGetUniformLocation(program, "uOrthogonal"), orthogonal)
        GL43C.glUniform1f(GL43C.glGetUniformLocation(program, "uDiagonal"), diagonal)
        GL43C.glUniform1f(GL43C.glGetUniformLocation(program, "uRate"), rate)
        GL43C.glUniform1f(GL43C.glGetUniformLocation(program, "uSettled"), settled)
    }

    private var programs: Pair<Int, Int>? = null

    private fun <T> runOnContext(body: () -> T?): T? =
        try {
            worker.submit(Callable { body() }).get(1, TimeUnit.HOURS)
        } catch (e: Exception) {
            // A driver fault here should cost the user a slower generation, not the app.
            System.err.println("GPU erosion failed, falling back to the CPU: ${e.message}")
            null
        }

    companion object {
        /**
         * Work group side. 16x16 is 256 invocations, which every device supporting compute
         * shaders is required to allow, and sits well with how the grid is walked.
         */
        private const val GROUP = 16

        private val worker = Executors.newSingleThreadExecutor { runnable ->
            // The OpenGL context belongs to whichever thread made it current, so every call has to
            // come back to this one. A daemon thread so it cannot hold the app open.
            Thread(runnable, "cartogenesis-gpu").apply { isDaemon = true }
        }

        /**
         * Creates an offscreen context and compiles the shaders, or returns null with a reason if
         * this machine cannot offer what is needed. No window is ever shown.
         */
        fun createOrNull(): Result {
            return try {
                worker.submit(Callable { initialise() }).get(30, TimeUnit.SECONDS)
            } catch (e: Exception) {
                Result(null, e.message ?: e::class.simpleName ?: "unknown failure")
            }
        }

        private fun initialise(): Result {
            // macOS is refused before GLFW is touched, for two separate reasons and neither is
            // fixable here. Apple deprecated OpenGL at 4.1, and compute shaders arrived in 4.3, so
            // the context this needs cannot exist there. And GLFW must be initialised on the main
            // thread on macOS, while this runs on a thread of its own — so the attempt would not
            // fail politely, it would take the process with it.
            val os = System.getProperty("os.name").orEmpty().lowercase()
            if (os.contains("mac") || os.contains("darwin")) {
                return Result(
                    null,
                    "macOS caps OpenGL at 4.1 and compute shaders need 4.3. Generation runs on the " +
                        "processor here; the browser build offers WebGPU instead."
                )
            }
            if (!GLFW.glfwInit()) return Result(null, "GLFW could not start")
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4)
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3)
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE)

            val window = GLFW.glfwCreateWindow(1, 1, "cartogenesis", MemoryUtil.NULL, MemoryUtil.NULL)
            if (window == MemoryUtil.NULL) {
                GLFW.glfwTerminate()
                return Result(null, "no OpenGL 4.3 context, which compute shaders need")
            }
            GLFW.glfwMakeContextCurrent(window)
            GL.createCapabilities()

            val device = GL43C.glGetString(GL43C.GL_RENDERER) ?: "unknown device"
            val gpu = GpuErosion(device)
            gpu.programs = try {
                compile(PHASE_A_SOURCE) to compile(PHASE_B_SOURCE)
            } catch (e: Exception) {
                return Result(null, "shader would not compile: ${e.message}")
            }
            return Result(gpu, null)
        }

        private fun compile(source: String): Int {
            val shader = GL43C.glCreateShader(GL43C.GL_COMPUTE_SHADER)
            GL43C.glShaderSource(shader, source)
            GL43C.glCompileShader(shader)
            if (GL43C.glGetShaderi(shader, GL43C.GL_COMPILE_STATUS) == GL43C.GL_FALSE) {
                error(GL43C.glGetShaderInfoLog(shader))
            }
            val program = GL43C.glCreateProgram()
            GL43C.glAttachShader(program, shader)
            GL43C.glLinkProgram(program)
            if (GL43C.glGetProgrami(program, GL43C.GL_LINK_STATUS) == GL43C.GL_FALSE) {
                error(GL43C.glGetProgramInfoLog(program))
            }
            GL43C.glDeleteShader(shader)
            return program
        }

        /** Shared preamble: the grid, the neighbourhood, and how a cell is addressed. */
        private val COMMON = """
            #version 430
            layout(local_size_x = 16, local_size_y = 16) in;

            uniform int uWidth;
            uniform int uHeight;
            uniform float uOrthogonal;
            uniform float uDiagonal;
            uniform float uRate;
            uniform float uSettled;

            const ivec2 NEIGHBOURS[8] = ivec2[8](
                ivec2( 1, 0), ivec2(-1, 0), ivec2(0,  1), ivec2(0, -1),
                ivec2( 1, 1), ivec2( 1,-1), ivec2(-1, 1), ivec2(-1,-1)
            );

            // The world is a cylinder: x wraps, y does not.
            int indexOf(int x, int y) {
                return y * uWidth + ((x + uWidth) % uWidth);
            }
        """.trimIndent()

        private val PHASE_A_SOURCE = COMMON + "\n" + """
            layout(std430, binding = 0) readonly buffer Source { float source[]; };
            layout(std430, binding = 2) writeonly buffer Rates { float rates[]; };

            void main() {
                int x = int(gl_GlobalInvocationID.x);
                int y = int(gl_GlobalInvocationID.y);
                if (x >= uWidth || y >= uHeight) return;

                int i = y * uWidth + x;
                float here = source[i];
                float excess = 0.0;
                float steepest = 0.0;

                for (int n = 0; n < 8; n++) {
                    int ny = y + NEIGHBOURS[n].y;
                    if (ny < 0 || ny >= uHeight) continue;
                    float drop = here - source[indexOf(x + NEIGHBOURS[n].x, ny)];
                    if (drop <= 0.0) continue;
                    steepest = max(steepest, drop);
                    float limit = n < 4 ? uOrthogonal : uDiagonal;
                    if (drop > limit) excess += drop - limit;
                }

                rates[i] = excess <= uSettled
                    ? 0.0
                    : min(uRate * excess, steepest * 0.5) / excess;
            }
        """.trimIndent()

        private val PHASE_B_SOURCE = COMMON + "\n" + """
            layout(std430, binding = 0) readonly buffer Source { float source[]; };
            layout(std430, binding = 1) writeonly buffer Target { float target[]; };
            layout(std430, binding = 2) readonly buffer Rates { float rates[]; };

            void main() {
                int x = int(gl_GlobalInvocationID.x);
                int y = int(gl_GlobalInvocationID.y);
                if (x >= uWidth || y >= uHeight) return;

                int i = y * uWidth + x;
                float here = source[i];
                float received = 0.0;
                float given = 0.0;

                for (int n = 0; n < 8; n++) {
                    int ny = y + NEIGHBOURS[n].y;
                    if (ny < 0 || ny >= uHeight) continue;
                    int j = indexOf(x + NEIGHBOURS[n].x, ny);
                    float limit = n < 4 ? uOrthogonal : uDiagonal;

                    float incoming = source[j] - here;
                    if (incoming > limit) {
                        received += rates[j] * (incoming - limit);
                    } else if (-incoming > limit) {
                        given += rates[i] * (-incoming - limit);
                    }
                }

                target[i] = here - given + received;
            }
        """.trimIndent()
    }
}
