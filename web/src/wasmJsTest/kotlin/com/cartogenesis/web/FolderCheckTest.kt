package com.cartogenesis.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/**
 * `?foldertest`'s steps, run on the private file system, which stands in here for the folder a
 * phone picks: every step reported, each refusal reported with the browser's own name for it, and
 * nothing left in the folder or touched in it that the check did not make.
 */
class FolderCheckTest {

    @Test
    fun `every step answers on the private file system, and only the check's own files are touched`() = runTest(timeout = 5.minutes) {
        withTestFolder("check") { folder ->
            val stranger = FolderCheck.pattern(STRANGER_BYTES, offset = 7)
            folder.writeRaw(STRANGER, stranger)

            val steps = FolderCheck(folder.handle, WebGzipCompressor, partBytes = PART_BYTES).run()
            println("FOLDER CHECK\n" + folderCheckReport(folder.name, steps, running = null))

            assertEquals(emptyList(), steps.filter { it.outcome != FolderCheckStep.Outcome.DONE }, "a step did not answer")
            for (asked in listOf("(getFileHandle, create)", "(createWritable)", "(write)", "(close)", "(move)", "(keys)", "(isSameEntry, removeEntry)", "through the library")) {
                assertTrue(steps.any { asked in it.what }, "no step asked $asked")
            }
            assertEquals(listOf(STRANGER), folder.entries(), "the check left something behind, or took what was not its own")
            assertSameBytes(stranger, folder.readRaw(STRANGER), "the check changed a file it did not make")
        }
    }

    @Test
    fun `a file carrying the check's token that the check did not make is named and left alone`() = runTest(timeout = 5.minutes) {
        // The clean-up removed every name holding the run's token, which takes a sync client's
        // copy of the check's file, or anything else that happens to carry it, with its own.
        withTestFolder("check-token-stranger") { folder ->
            val token = "0f8fad5b-d9cb-469f-a165-70867728950e"
            val stranger = "${FolderCheck.NAME_PREFIX}$token (conflicted copy).cgw"
            val bytes = FolderCheck.pattern(STRANGER_BYTES, offset = 3)
            folder.writeRaw(stranger, bytes)

            val steps = FolderCheck(folder.handle, WebGzipCompressor, partBytes = PART_BYTES, token = token).run()
            println("FOLDER CHECK WITH A STRANGER CARRYING THE TOKEN\n" + folderCheckReport(folder.name, steps, running = null))

            assertEquals(listOf(stranger), folder.entries(), "the check removed a file it did not make, or left one of its own")
            assertSameBytes(bytes, folder.readRaw(stranger), "the check changed a file it did not make")
            val last = steps.last()
            assertEquals(FolderCheckStep.Outcome.FAILED, last.outcome, "the file left under the token was not reported")
            assertTrue(stranger in last.answer, "the report did not name what was left: ${last.answer}")
            assertEquals(emptyList(), steps.dropLast(1).filter { it.outcome != FolderCheckStep.Outcome.DONE })
        }
    }

    @Test
    fun `where move is missing the check says so and copies, as the library does`() = runTest(timeout = 5.minutes) {
        withTestFolder("check-no-move") { folder ->
            val steps = withMoveIf(canMove = false) { FolderCheck(folder.handle, WebGzipCompressor, partBytes = PART_BYTES).run() }
            println("FOLDER CHECK WITHOUT MOVE\n" + folderCheckReport(folder.name, steps, running = null))

            assertEquals(emptyList(), steps.filter { it.outcome == FolderCheckStep.Outcome.FAILED }, "a step failed")
            val asked = assertNotNull(steps.firstOrNull { "(typeof move)" in it.what })
            assertTrue(asked.answer.startsWith("no"), "a missing move was not reported: ${asked.answer}")
            assertEquals(FolderCheckStep.Outcome.SKIPPED, steps.first { it.what.endsWith("(move)") }.outcome)
            assertTrue(steps.any { "the copy" in it.what && "(close)" in it.what && it.outcome == FolderCheckStep.Outcome.DONE })
            assertEquals(emptyList(), folder.entries())
        }
    }

    @Test
    fun `a step the browser refuses is reported with the browser's name for the refusal`() = runTest(timeout = 5.minutes) {
        withTestFolder("check-refused") { folder ->
            val held = failEveryClose(REFUSAL_NAME, REFUSAL_MESSAGE)
            val steps = try {
                FolderCheck(folder.handle, WebGzipCompressor, partBytes = PART_BYTES).run()
            } finally {
                restoreClose(held)
            }
            val report = folderCheckReport(folder.name, steps, running = null)
            println("FOLDER CHECK WITH CLOSE REFUSED\n$report")

            val close = assertNotNull(steps.firstOrNull { "(close)" in it.what }, "the close was not reported")
            assertEquals(FolderCheckStep.Outcome.FAILED, close.outcome)
            assertTrue(close.answer.startsWith("$REFUSAL_NAME: $REFUSAL_MESSAGE"), "the browser's name was not reported: ${close.answer}")
            // Through the library too: its save fails on the same close, and says whose failure it was.
            val save = steps.first { it.what == "save it through the library" }
            assertEquals(FolderCheckStep.Outcome.FAILED, save.outcome)
            assertTrue(REFUSAL_NAME in save.answer, "the library's failure lost the browser's name: ${save.answer}")
            assertTrue("$REFUSAL_NAME: $REFUSAL_MESSAGE" in report && "Browser: " in report)
            assertEquals(emptyList(), folder.entries(), "a refused check left its files behind")
        }
    }

    private companion object {
        /** Small parts, so the three the check writes cost the test nothing. */
        const val PART_BYTES = 16 shl 10

        const val STRANGER = "someone else's.cgw"
        const val STRANGER_BYTES = 1000

        const val REFUSAL_NAME = "InvalidModificationError"
        const val REFUSAL_MESSAGE = "made to fail by the test"
    }
}

/**
 * Makes every writable stream's `close` throw its swap file away and reject with a `DOMException`
 * called [name], as a browser refusing the commit does; returns what it replaced, for [restoreClose].
 */
@JsFun(
    """(name, message) => {
        const proto = FileSystemWritableFileStream.prototype;
        const held = proto.close;
        proto.close = function () {
            return this.abort().then(() => Promise.reject(new DOMException(message, name)));
        };
        return held;
    }"""
)
private external fun failEveryClose(name: String, message: String): JsHandle

@JsFun("(held) => { FileSystemWritableFileStream.prototype.close = held; }")
private external fun restoreClose(held: JsHandle)
