package com.cartogenesis.desktop

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL43C
import org.lwjgl.system.MemoryUtil

/**
 * The one offscreen OpenGL context this process has, and the thread it belongs to.
 *
 * There is exactly one because a context belongs to whichever thread made it current: two of them
 * on one thread means the second silently invalidates the first's programs and buffers, and two
 * threads each with their own would double the driver's footprint for no gain. So every use of the
 * graphics card — erosion, the export raster, whatever comes next — goes through here, is handed the
 * same context, and runs on the same daemon thread.
 *
 * Nothing here decides *what* to compute. It starts a context, compiles compute shaders, and runs
 * work on the right thread; the stages own their own shaders and buffers.
 */
internal object GlContext {

    /** What probing this machine found: a device, or the reason there is not one. */
    class Result(val device: String?, val unavailableBecause: String?)

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        // A daemon thread so it cannot hold the app open.
        Thread(runnable, "cartogenesis-gpu").apply { isDaemon = true }
    }

    /** Memoised: the context is created once and then reused by every caller. */
    private var context: Result? = null

    /**
     * Creates the context if this is the first ask, and reports what is there. Safe to call as
     * often as anyone likes; the second call is free.
     */
    fun ensure(): Result =
        try {
            worker.submit(Callable { createOnWorker() })
                .get(CONTEXT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (failure: Exception) {
            Result(null, failure.message ?: failure::class.simpleName ?: "unknown failure")
        }

    /**
     * How long to wait for a context, in seconds.
     *
     * Creating one is a driver call that normally returns in well under a second; a driver that
     * has not answered in half a minute is one that is not going to, and the reader is better
     * served by the processor path than by an application that never draws its first window.
     */
    private const val CONTEXT_TIMEOUT_SECONDS = 30L

    /**
     * How long a piece of graphics work may take before it is given up on, in seconds.
     *
     * An hour, which is not a limit on anything anybody would sit through — an 8192 export is
     * minutes — but a hung driver has to be given up on eventually or the thread never comes back.
     */
    private const val WORK_TIMEOUT_SECONDS = 3600L

    /**
     * Runs [body] on the thread the context belongs to, or returns null if it failed.
     *
     * A driver fault should cost the user a slower generation or a slower export, not the app, so
     * the failure is reported to stderr and the caller falls back to the CPU.
     */
    fun <T> run(
        what: String,
        timeoutSeconds: Long = WORK_TIMEOUT_SECONDS,
        body: () -> T?
    ): T? =
        try {
            worker.submit(Callable { body() }).get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (failure: Exception) {
            System.err.println(
                "$what failed on the GPU, falling back to the CPU: ${failure.message}"
            )
            null
        }

    /** Compiles and links a compute shader. Must be called on the context's thread. */
    fun compileCompute(source: String): Int {
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

    private fun createOnWorker(): Result {
        context?.let { return it }

        // macOS is refused before GLFW is touched, for two separate reasons and neither is
        // fixable here. Apple deprecated OpenGL at 4.1, and compute shaders arrived in 4.3, so
        // the context this needs cannot exist there. And GLFW must be initialised on the main
        // thread on macOS, while this runs on a thread of its own — so the attempt would not
        // fail politely, it would take the process with it.
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        if (osName.contains("mac") || osName.contains("darwin")) {
            return memoise(
                Result(
                    null,
                    "macOS caps OpenGL at 4.1 and compute shaders need 4.3. Generation runs on the " +
                        "processor here; the browser build offers WebGPU instead."
                )
            )
        }
        if (!GLFW.glfwInit()) return memoise(Result(null, "GLFW could not start"))
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
        // 4.3 is the version compute shaders arrived in, and is the whole of what this asks for.
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4)
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3)
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE)

        // One pixel by one, and never shown: the window exists only because GLFW hangs a context
        // off one, and nothing is ever drawn into its framebuffer.
        val window = GLFW.glfwCreateWindow(1, 1, "cartogenesis", MemoryUtil.NULL, MemoryUtil.NULL)
        if (window == MemoryUtil.NULL) {
            GLFW.glfwTerminate()
            return memoise(
                Result(null, "no OpenGL 4.3 context, which compute shaders need")
            )
        }
        GLFW.glfwMakeContextCurrent(window)
        GL.createCapabilities()

        return memoise(Result(GL43C.glGetString(GL43C.GL_RENDERER) ?: "unknown device", null))
    }

    /** Keeps [result] as the answer every later caller gets. Named to stay clear of Compose's. */
    private fun memoise(result: Result): Result {
        context = result
        return result
    }
}
