package com.cartogenesis.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the two things a deployed website reaches into the web build for.
 *
 * The deployed site (cartogenesis.com) does not use the `index.html` the Gradle build emits — it replaces it
 * with its own shell, which shows a loading screen while 4.4 MB of compressed WebAssembly arrives.
 * That shell depends on two names in this repo, and breaking either one breaks the site *silently*:
 * the application still works perfectly, and the page around it never finds out.
 *
 * The reason there is no better signal is worth stating, because it is genuinely surprising and it
 * cost a debugging session on the website side. Compose does not put its canvas in the page. It
 * attaches a shadow root to the viewport div and puts the canvas inside that, so every obvious
 * readiness check lies — `document.querySelector("canvas")` is null, the div reports zero children,
 * and a MutationObserver watching it never fires, all while a 2560x1215 canvas is alive and
 * generating a world. `hideLoadingMessage()` deleting `#loading` is the only exact signal available
 * from outside that shadow root.
 *
 * Read from source text rather than from a running app because that is what the contract actually
 * is: an id inside a `@JsFun` body, which the browser resolves and no Kotlin type system sees. It
 * lives in the desktop module only because that is where JVM tests can read files — the web module
 * compiles to wasm, which cannot.
 */
class WebDeploymentContractTest {

    private val repoRoot: File
        get() {
            var dir = File(".").absoluteFile
            while (dir.parentFile != null) {
                if (File(dir, "settings.gradle.kts").isFile) return dir
                dir = dir.parentFile
            }
            fail("could not find the repository root from ${File(".").absolutePath}")
        }

    /**
     * The file's code with its comments taken out, so that a name mentioned in a comment — a call
     * commented out, or a note about one — cannot stand in for the code that uses it.
     */
    private fun source(path: String): String {
        val file = File(repoRoot, path)
        assertTrue(file.isFile, "expected to find $path at ${file.absolutePath}")
        return withoutComments(file.readText())
    }

    /**
     * [text] with its `//` and `/* */` comments blanked, string literals left whole: the `@JsFun`
     * bodies this reads are string literals, and a `//` inside one is JavaScript, not a comment.
     */
    private fun withoutComments(text: String): String {
        val out = StringBuilder(text.length)
        var at = 0
        var inString: String? = null
        while (at < text.length) {
            val rest = text.substring(at, minOf(at + 3, text.length))
            when {
                inString != null -> {
                    if (text.startsWith(inString, at) && (inString == "\"\"\"" || text[at - 1] != '\\')) {
                        out.append(inString); at += inString.length; inString = null
                    } else { out.append(text[at]); at++ }
                }
                rest.startsWith("\"\"\"") -> { inString = "\"\"\""; out.append(rest); at += 3 }
                rest.startsWith("\"") -> { inString = "\""; out.append('"'); at++ }
                rest.startsWith("//") -> { while (at < text.length && text[at] != '\n') at++ }
                rest.startsWith("/*") -> {
                    val end = text.indexOf("*/", at + 2)
                    at = if (end < 0) text.length else end + 2
                    out.append(' ')
                }
                else -> { out.append(text[at]); at++ }
            }
        }
        return out.toString()
    }

    @Test
    fun `the viewport id the website creates still matches`() {
        val main = source("web/src/wasmJsMain/kotlin/com/cartogenesis/web/Main.kt")
        assertTrue(
            main.contains("""private const val VIEWPORT_ID = "composeTarget""""),
            "VIEWPORT_ID is no longer \"composeTarget\". The website's shell creates a div with " +
                "that id for Compose to mount into, so renaming it leaves the app with nothing to " +
                "attach to. Coordinate the change with the site rather than renaming here."
        )
    }

    @Test
    fun `the ready signal the website watches for still exists`() {
        val browser = source("web/src/wasmJsMain/kotlin/com/cartogenesis/web/Browser.kt")
        assertTrue(
            browser.contains("internal external fun hideLoadingMessage()"),
            "hideLoadingMessage() is gone. The website treats its removal of #loading as the " +
                "only exact 'the app is ready' signal, because Compose's canvas lives in a shadow " +
                "root where nothing else can see it."
        )
        assertTrue(
            browser.contains("""document.getElementById('loading')"""),
            "hideLoadingMessage() no longer removes the element with id 'loading'. The website " +
                "keeps an empty div with that id in the page purely so this can delete it; if the " +
                "id changes, a working app sits under a permanent loading overlay."
        )

        // A call as a statement of its own, in code: not the name in a comment, and not the
        // declaration, which lives in Browser.kt.
        val main = source("web/src/wasmJsMain/kotlin/com/cartogenesis/web/Main.kt")
        assertTrue(
            Regex("""(?m)^\s*hideLoadingMessage\(\)\s*;?\s*$""").containsMatchIn(main),
            "hideLoadingMessage() is never called. It exists to be called on startup; unused, the " +
                "website's loading overlay never lifts."
        )
    }
}
