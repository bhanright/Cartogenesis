package com.cartogenesis.desktop

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.fail

/**
 * A headless Chrome driven over the DevTools protocol, with the assembled site served to it from
 * this machine: what the site's guards use to measure the page as a browser lays it out, at a
 * screen's width, with or without scripts, with motion reduced and under a finger.
 *
 * A layout is the one thing about the page that reading its source cannot settle: whether a tag
 * runs past a phone's screen depends on the typeface's widths, the grid's arithmetic and the words,
 * all at once. So the guards that are about layout ask a browser, the same engine a reader's
 * Chrome is. Chrome is found where it is installed, or where `CARTOGENESIS_CHROME` says; a machine
 * without it fails the guard by name rather than skipping it, since a skipped guard is no guard.
 * The deploy runner's image carries Chrome.
 *
 * Spoken to over the JDK's own WebSocket, so the guard needs nothing the build does not already
 * have.
 */
class HeadlessChrome private constructor(
    private val process: Process,
    private val profile: File,
    private val server: HttpServer,
    private val socket: WebSocket,
    private val answers: ConcurrentHashMap<Int, CompletableFuture<JsonObject>>,
    private val events: LinkedBlockingQueue<JsonObject>
) : AutoCloseable {

    /** The address the site is served at, ending in a slash. */
    val siteAddress: String = "http://127.0.0.1:${server.address.port}/"

    private val nextId = AtomicInteger(1)

    /** Sends one protocol command and waits for its result. */
    fun send(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject {
        val id = nextId.getAndIncrement()
        val answer = CompletableFuture<JsonObject>()
        answers[id] = answer
        val message = buildJsonObject {
            put("id", id)
            put("method", method)
            put("params", params)
        }
        socket.sendText(message.toString(), true).get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val reply = answer.get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        reply["error"]?.let { fail("Chrome refused $method: $it") }
        return reply["result"]?.jsonObject ?: JsonObject(emptyMap())
    }

    /** Evaluates [expression] in the page, awaiting a promise, and returns its value as JSON. */
    fun evaluate(expression: String): JsonElement {
        val result = send("Runtime.evaluate", buildJsonObject {
            put("expression", expression)
            put("awaitPromise", true)
            put("returnByValue", true)
        })
        result["exceptionDetails"]?.let { fail("the page's script threw while measuring: $it") }
        return result["result"]?.jsonObject?.get("value") ?: JsonPrimitive(null as String?)
    }

    /** Waits for the next protocol event named [method], dropping any other. */
    fun waitFor(method: String, timeoutSeconds: Long = COMMAND_TIMEOUT_SECONDS): JsonObject {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (true) {
            val left = until - System.nanoTime()
            if (left <= 0) fail("Chrome sent no $method within $timeoutSeconds s")
            val event = events.poll(left, TimeUnit.NANOSECONDS) ?: continue
            if (event["method"]?.jsonPrimitive?.content == method) return event
        }
    }

    /**
     * Opens [path] of the site at a screen [width] by [height] CSS pixels, one device pixel to
     * each, with or without scripts and with motion reduced or not, and waits until it has loaded
     * and its typefaces are in. A width under 768 is a phone, told that it is one and given touch;
     * [touch] gives a wider screen a finger too, as a tablet has.
     */
    fun open(
        path: String, width: Int, height: Int, scripts: Boolean = true, reducedMotion: Boolean = false,
        touch: Boolean = width < PHONE_BELOW_PX
    ) {
        val phone = width < PHONE_BELOW_PX
        send("Emulation.setScriptExecutionDisabled", buildJsonObject { put("value", !scripts) })
        send("Emulation.setDeviceMetricsOverride", buildJsonObject {
            put("width", width); put("height", height); put("deviceScaleFactor", 1); put("mobile", phone)
        })
        send("Emulation.setTouchEmulationEnabled", buildJsonObject {
            put("enabled", touch)
            if (touch) put("maxTouchPoints", 1)
        })
        send("Emulation.setEmulatedMedia", buildJsonObject {
            put("features", Json.parseToJsonElement(
                """[{"name":"prefers-reduced-motion","value":"${if (reducedMotion) "reduce" else "no-preference"}"}]"""
            ))
        })
        // A blank page between two loads, so the load waited for below is the site's own and not
        // one left over from the page before.
        send("Page.navigate", buildJsonObject { put("url", "about:blank") })
        pause(BLANK_PAGE_SETTLES_MS)
        events.clear()
        send("Page.navigate", buildJsonObject { put("url", siteAddress + path) })
        waitFor("Page.loadEventFired", PAGE_LOAD_TIMEOUT_SECONDS)
        // Measuring needs a script of this test's own even where the page's are off; turning them
        // back on runs none of the page's, which ran, or did not, as the page was read.
        if (!scripts) send("Emulation.setScriptExecutionDisabled", buildJsonObject { put("value", false) })
        evaluate("document.fonts.ready.then(() => true)")
    }

    /** A finger put down at (x, y) on the screen, in CSS pixels. */
    fun touchDown(x: Double, y: Double) = touchEvent("touchStart", """[{"x":$x,"y":$y}]""")

    /** The finger moved to (x, y). */
    fun touchMove(x: Double, y: Double) = touchEvent("touchMove", """[{"x":$x,"y":$y}]""")

    /** The finger lifted. */
    fun touchUp() = touchEvent("touchEnd", "[]")

    private fun touchEvent(type: String, points: String) {
        send("Input.dispatchTouchEvent", buildJsonObject {
            put("type", type)
            put("touchPoints", Json.parseToJsonElement(points))
        })
    }

    /** Lets the page run for [milliseconds]: a timer of its own to finish, a transition to end. */
    fun pause(milliseconds: Long) = Thread.sleep(milliseconds)

    override fun close() {
        runCatching { socket.abort() }
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        runCatching { process.waitFor(10, TimeUnit.SECONDS) }
        server.stop(0)
        runCatching { profile.deleteRecursively() }
    }

    companion object {
        /** Below this width the page is opened as a phone, as the page's own breakpoints read one. */
        const val PHONE_BELOW_PX = 768

        /** A command answers in milliseconds; this long is a browser that has stopped answering. */
        private const val COMMAND_TIMEOUT_SECONDS = 30L

        /** The page loads from this machine; a minute is a page that will not load. */
        private const val PAGE_LOAD_TIMEOUT_SECONDS = 60L

        /** A blank page has loaded well inside this, so its load event is gone before the next. */
        private const val BLANK_PAGE_SETTLES_MS = 200L

        private val TYPES = mapOf(
            "html" to "text/html; charset=utf-8", "webp" to "image/webp", "png" to "image/png",
            "woff2" to "font/woff2", "js" to "text/javascript", "wasm" to "application/wasm",
            "json" to "application/json", "txt" to "text/plain; charset=utf-8", "css" to "text/css"
        )

        /** Where Chrome is, or a failure that says how to tell the guard. */
        fun chromeExecutable(): File {
            System.getenv("CARTOGENESIS_CHROME")?.let { named ->
                val file = File(named)
                if (file.canExecute()) return file
                fail("CARTOGENESIS_CHROME names $named, which is not a program")
            }
            val installed = listOfNotNull(
                System.getenv("ProgramFiles")?.let { "$it\\Google\\Chrome\\Application\\chrome.exe" },
                System.getenv("ProgramFiles(x86)")?.let { "$it\\Google\\Chrome\\Application\\chrome.exe" },
                System.getenv("LOCALAPPDATA")?.let { "$it\\Google\\Chrome\\Application\\chrome.exe" },
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
            ).map(::File)
            val onPath = listOf("google-chrome", "google-chrome-stable", "chromium", "chromium-browser").flatMap { name ->
                (System.getenv("PATH") ?: "").split(File.pathSeparatorChar).map { File(it, name) }
            }
            return (installed + onPath).firstOrNull { it.isFile && it.canExecute() }
                ?: fail(
                    "no Chrome on this machine for the page's layout guards; install Chrome or set " +
                        "CARTOGENESIS_CHROME to its program"
                )
        }

        /** Serves [site] and starts a headless Chrome at it, with one blank page to drive. */
        fun start(site: File): HeadlessChrome {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                val path = URI(exchange.requestURI.rawPath).path.let { if (it.endsWith("/")) it + "index.html" else it }
                val file = File(site, path.removePrefix("/")).canonicalFile
                if (!file.path.startsWith(site.canonicalPath) || !file.isFile) {
                    exchange.sendResponseHeaders(404, -1)
                } else {
                    exchange.responseHeaders.add("Content-Type", TYPES[file.extension] ?: "application/octet-stream")
                    exchange.responseHeaders.add("Cache-Control", "no-cache")
                    val bytes = file.readBytes()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                exchange.close()
            }
            server.start()

            val profile = kotlin.io.path.createTempDirectory("site-chrome").toFile()
            val arguments = mutableListOf(
                chromeExecutable().path, "--headless=new", "--hide-scrollbars", "--no-first-run",
                "--no-default-browser-check", "--disable-extensions", "--remote-debugging-port=0",
                "--user-data-dir=${profile.path}"
            )
            // Chrome will not start its sandbox as root, which is how a container runs.
            if (System.getProperty("user.name") == "root") arguments += "--no-sandbox"
            arguments += "about:blank"
            val process = ProcessBuilder(arguments).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            val address = CompletableFuture<String>()
            Thread {
                process.errorStream.bufferedReader().forEachLine { line ->
                    Regex("""DevTools listening on (ws://\S+)""").find(line)?.let { address.complete(it.groupValues[1]) }
                }
            }.apply { isDaemon = true }.start()
            val browser = runCatching { address.get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS) }.getOrElse {
                process.destroyForcibly()
                server.stop(0)
                fail("Chrome did not open its DevTools port within $COMMAND_TIMEOUT_SECONDS s")
            }
            val port = URI(browser).port
            val http = HttpClient.newHttpClient()
            val target = Json.parseToJsonElement(
                http.send(
                    HttpRequest.newBuilder(URI("http://127.0.0.1:$port/json/new?about:blank"))
                        .PUT(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString()
                ).body()
            ).jsonObject
            val pageSocket = target["webSocketDebuggerUrl"]?.jsonPrimitive?.content
                ?: fail("Chrome opened no page to drive: $target")

            val answers = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
            val events = LinkedBlockingQueue<JsonObject>()
            val listener = object : WebSocket.Listener {
                private val partial = StringBuilder()
                override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                    partial.append(data)
                    if (last) {
                        val message = Json.parseToJsonElement(partial.toString()).jsonObject
                        partial.setLength(0)
                        val id = message["id"]?.jsonPrimitive?.content?.toIntOrNull()
                        if (id != null) answers.remove(id)?.complete(message) else events.add(message)
                    }
                    webSocket.request(1)
                    return null
                }
            }
            val socket = http.newWebSocketBuilder().buildAsync(URI(pageSocket), listener)
                .get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val chrome = HeadlessChrome(process, profile, server, socket, answers, events)
            chrome.send("Page.enable")
            chrome.send("Runtime.enable")
            return chrome
        }
    }
}
