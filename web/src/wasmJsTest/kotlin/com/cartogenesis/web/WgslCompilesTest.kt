package com.cartogenesis.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/**
 * Every WGSL module this build holds compiles on the browser's own compiler, where there is one.
 *
 * `WgslReservedWordsTest` catches the one mistake that kept the ice's kernel from compiling, by
 * reading the text; this asks the compiler itself, which catches everything else, through
 * `getCompilationInfo` on a real device and failing on any message of type `error`. It needs an
 * adapter, and a headless browser often has none, so it says in the test output which it was:
 * `WGSL COMPILE CHECK executed` with the device's name, or `WGSL COMPILE CHECK skipped` with the
 * reason. A skip passes, because a browser with no device has nothing to compile on; it is
 * reported rather than hidden, so a tier that never executes it can be seen to.
 */
class WgslCompilesTest {

    @Test
    fun `every WGSL module compiles on the browser's device`() = runTest(timeout = 2.minutes) {
        if (!webGpuPresent()) {
            println("WGSL COMPILE CHECK skipped: this browser has no navigator.gpu")
            return@runTest
        }
        val device = awaitPromise(requestDevice())
        if (device == null || isNullish(device)) {
            println("WGSL COMPILE CHECK skipped: navigator.gpu offered no adapter or device")
            return@runTest
        }
        val refusals = WGSL_MODULES.mapNotNull { module ->
            val errors = awaitPromise(compilationErrors(device, module.source))
                ?.let { textOf(it) }
                .orEmpty()
            if (errors.isEmpty()) null else "${module.name}: $errors"
        }
        println(
            "WGSL COMPILE CHECK executed on ${deviceLabel(device)}: ${WGSL_MODULES.size} " +
                "module(s), ${refusals.size} refused"
        )
        assertEquals(emptyList(), refusals, "the device's compiler refused these modules")
    }
}

/** The error messages the compiler gave [source], as `line:column message`, joined; or nothing. */
@JsFun(
    """(device, source) => (async () => {
        const module = device.createShaderModule({code: source});
        const info = await module.getCompilationInfo();
        return info.messages
            .filter(message => message.type === 'error')
            .map(message => message.lineNum + ':' + message.linePos + ' ' + message.message)
            .join('; ');
    })()"""
)
private external fun compilationErrors(device: JsHandle, source: String): JsHandle

@JsFun("(value) => String(value)")
private external fun textOf(value: JsHandle): String
