package com.cartogenesis.web

import com.cartogenesis.cartography.Compressor
import com.cartogenesis.cartography.LoadOutcome
import com.cartogenesis.cartography.NoCompression
import com.cartogenesis.cartography.WorldComparison
import com.cartogenesis.cartography.WorldDocument
import com.cartogenesis.ui.BuildInfo
import com.cartogenesis.ui.randomId
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * `?foldertest`: every step the folder library takes to save a world, taken one at a time in a
 * folder the reader picks, each with the browser's own answer.
 *
 * A folder picked on a phone is Android's, not a directory's, and the browser tests' private file
 * system stands in for it in nothing but the calls' names: where a save into it fails, the only
 * place the failure can be seen is the phone. So this makes a file under the library's own kind of
 * temporary name, writes it in parts through a stream, closes it, moves it to a `.cgw` name (or,
 * where the browser cannot move a file, copies it there as the library does), lists the folder,
 * reads the file back, writes over it the way a second Save does, and removes it; then it saves,
 * lists, opens and deletes a small world through the library itself. Each step is reported with
 * what the browser said — its exception's name and message where it refused — and how long it took.
 *
 * It changes no file it did not make. Every name it makes carries a token drawn for this run and is
 * checked free first; each file it makes is remembered with its handle, and is removed only while
 * the entry under that name is still that file (`isSameEntry`), so a file another writer has put
 * under the name meanwhile is left alone and reported. The library's own round trip deletes only the
 * key its save returned. It reads more than it makes: the listing steps read every name in the
 * folder, naming only those carrying the token and counting the rest, and the library's listing
 * reads the header at the front of every save in the folder, as the Library pane does.
 */
internal class FolderCheck(
    private val directory: JsHandle,
    private val compressor: Compressor,
    private val partBytes: Int = FolderWorldLibrary.PART_BYTES,
    private val token: String = randomId(),
    /** Told of each step as it starts and as it ends, for a page that shows the check as it runs. */
    private val onProgress: (List<FolderCheckStep>, String?) -> Unit = { _, _ -> }
) {
    private val stem = "$NAME_PREFIX$token"

    /** A save's name, as the library would give a world whose id is [stem]. */
    val savedName = "$stem.cgw"

    /** The library's temporary name for [savedName]. */
    val temporaryName = FolderWorldLibrary.temporaryNameFor(savedName, token)

    /** The temporary name's earlier shape, `~<name>.<token>.tmp`, which Chrome refuses in a folder on the disk. */
    val earlierTemporaryName = "~$savedName.$token.tmp"

    private val steps = mutableListOf<FolderCheckStep>()

    /** Every file this run made, by the name it made it under and the handle it was given, in order. */
    private val made = mutableListOf<Pair<String, JsHandle>>()

    /** Runs every step, in order, and returns them all. A step that cannot run without an earlier one is reported skipped. */
    suspend fun run(): List<FolderCheckStep> {
        step("ask whether this page may read and write the folder (queryPermission)") {
            asText(awaitPromiseOrThrow(directoryPermission(directory, ask = false))!!)
        }
        step("check the check's names are free") {
            for (name in listOf(temporaryName, savedName)) {
                val there = awaitPromiseOrThrow(existingFile(directory, name))
                check(there == null || isNullish(there)) { "$name is already in the folder; nothing was made" }
            }
            // The earlier shape may be refused even as a question, which is the probe's to report.
            val earlier = runCatching { awaitPromiseOrThrow(existingFile(directory, earlierTemporaryName)) }.getOrNull()
            check(earlier == null || isNullish(earlier)) { "$earlierTemporaryName is already in the folder; nothing was made" }
            "none is there"
        } ?: return finish()

        // Not a step the library takes any longer: whether this folder refuses the name it used to
        // take, which is what stopped every new save in a folder a reader picked.
        probe("make a file under the earlier temporary name $earlierTemporaryName (getFileHandle, create)") {
            made += earlierTemporaryName to awaitPromiseOrThrow(createdFile(directory, earlierTemporaryName))!!
            "made; removed at the end"
        }
        // Whether a refused name left a file anyway: a save that failed before making anything and
        // one that made its file and then lost it look alike in a file manager, and not here.
        step("list the folder after it (keys)") { listing() }

        val bytes = pattern(PARTS * partBytes)
        val temporary = step("make the temporary file $temporaryName (getFileHandle, create)") {
            awaitPromiseOrThrow(createdFile(directory, temporaryName))!!.also { made += temporaryName to it }
        }
        if (temporary != null) {
            step("find it listed under that name (keys)") { listing() }
            step("can a file handle be moved here (typeof move)") { if (canMove(temporary)) "yes" else "no: the library copies instead" }
            val written = writeThrough("the temporary file", temporary, bytes)
            if (written) {
                step("read its size back (getFile)") { sizeOf(temporary, bytes.size) }
                val published = publish(temporary, bytes)
                if (published != null) {
                    step("find it listed under $savedName (keys)") { listing() }
                    step("read $savedName back whole (getFile, slice)") { readBack(published, bytes) }
                    val again = pattern(PARTS * partBytes, offset = 1)
                    if (writeThrough("$savedName again, as a second Save does", published, again)) {
                        step("read the second save back whole") { readBack(published, again) }
                    }
                }
            }
        } else {
            skip("everything that writes the temporary file", "the file could not be made")
        }
        for ((name, file) in made.filter { (name, _) -> name == savedName || name == temporaryName }) {
            step("remove $name if it is still the file this check made (isSameEntry, removeEntry)") { removeIfOurs(name, file) }
        }
        libraryRoundTrip()
        return finish()
    }

    /** A stream opened on [file], [bytes] written in parts, and closed: true when all of it answered. */
    private suspend fun writeThrough(what: String, file: JsHandle, bytes: ByteArray): Boolean {
        val writable = step("open a stream on $what (createWritable)") {
            awaitPromiseOrThrow(writableOf(file))!!
        } ?: return false
        for (part in 0 until PARTS) {
            val from = part * partBytes
            val taken = step("write part ${part + 1} of $PARTS, $partBytes bytes (write)") {
                awaitPromiseOrThrow(writePart(writable, bytes.toJs(from, partBytes)))
                "taken"
            }
            if (part == 0 && taken != null) step("list the folder while the stream is open (keys)") { listing() }
            if (taken == null) {
                step("abort the stream on $what (abort)") { awaitPromiseOrThrow(abortWritable(writable)); "aborted" }
                return false
            }
        }
        val closed = step("close the stream on $what, which commits it (close)") {
            awaitPromiseOrThrow(closeWritable(writable))
            "closed"
        } != null
        // A stream whose close was refused may still be open, its swap file beside the file and
        // the file not removable while it is; the library aborts it, and so does the check.
        if (!closed) step("abort the stream on $what (abort)") { awaitPromiseOrThrow(abortWritable(writable)); "aborted" }
        return closed
    }

    /**
     * The written temporary file made [savedName]: moved where the browser can, and where it
     * cannot, or its move refuses, copied as the library's fallback does. The saved file's handle,
     * or null.
     */
    private suspend fun publish(temporary: JsHandle, bytes: ByteArray): JsHandle? {
        if (canMove(temporary)) {
            val moved = step("move it to $savedName (move)") {
                awaitPromiseOrThrow(moveExactly(temporary, directory, savedName))
                "moved"
            }
            if (moved != null) {
                // The handle moved with the file, so it is this run's file under its new name.
                made += savedName to temporary
                return step("look $savedName up by name (getFileHandle)") {
                    awaitPromiseOrThrow(existingFile(directory, savedName))?.takeUnless(::isNullish)
                        ?: error("nothing is there under that name")
                }
            }
        } else {
            skip("move it to $savedName (move)", "this browser has no move here")
        }
        val target = step("make $savedName to copy into, as the library does without move (getFileHandle, create)") {
            awaitPromiseOrThrow(createdFile(directory, savedName))!!.also { made += savedName to it }
        } ?: return null
        return if (writeThrough("$savedName, the copy", target, bytes)) target else null
    }

    /** A save through the folder library itself: a 32 world saved, listed, opened and deleted. */
    private suspend fun libraryRoundTrip() {
        val library = FolderWorldLibrary(directory, compressor, "web-folder-check", partBytes)
        val world = step("make a 32 world to save") {
            WorldGenerationEngine.generate(WorldGenConfig(seed = WORLD_SEED, width = WORLD_CELLS, height = WORLD_CELLS))
        } ?: return
        val document = WorldDocument(id = stem, title = "Folder check", config = world.config, savedAt = epochMillisNow())
        val key = step("save it through the library") { library.save(document, world) } ?: return
        step("find it in the library's listing (reads the header of every save in the folder, and changes none)") {
            check(library.list().any { it.key == key }) { "$key was not listed" }
            key
        }
        step("open it from the library") {
            when (val outcome = library.load(key)) {
                is LoadOutcome.Loaded -> WorldComparison.firstDifference(world, outcome.save.world)
                    ?.let { error("opened, but differs: $it") } ?: "every field as saved"
                is LoadOutcome.Refused -> error("refused: ${outcome.refusal.message}")
            }
        }
        step("delete it through the library") {
            library.delete(key)
            "deleted"
        }
    }

    /**
     * Removes whatever this run made that is still there and still its own, then reads which names
     * carrying the token are left, and fails the step if any is: a file the check could not remove,
     * or one it did not make — a swap file the browser left, a copy a storage provider renamed —
     * which it names and leaves alone.
     */
    private suspend fun finish(): List<FolderCheckStep> {
        if (made.isNotEmpty()) {
            step("remove what else this check made, if still its own, and list what is left under its token") {
                val removed = made.mapNotNull { (name, file) -> name.takeIf { removeIfOurs(name, file) == REMOVED } }
                val left = allNames().filter { token in it }
                check(left.isEmpty()) {
                    "left in the folder, not removed: ${left.joinToString()}" +
                        (if (removed.isEmpty()) "" else "; removed ${removed.joinToString()}")
                }
                if (removed.isEmpty()) "nothing was left" else "removed ${removed.joinToString()}"
            }
        }
        onProgress(steps.toList(), null)
        return steps.toList()
    }

    /**
     * Removes [name] only while the entry under it is [file], the one this run made: [REMOVED]; or
     * says it was not there, or that another file now has the name and was left alone.
     */
    private suspend fun removeIfOurs(name: String, file: JsHandle): String {
        val there = awaitPromiseOrThrow(existingFile(directory, name))?.takeUnless(::isNullish) ?: return "was not there"
        if (!isTrue(awaitPromiseOrThrow(sameEntry(there, file)))) return "left alone: another file has that name now"
        return if (isTrue(awaitPromiseOrThrow(removeFile(directory, name)))) REMOVED else "was not there"
    }

    private suspend fun allNames(): List<String> {
        val names = awaitPromiseOrThrow(entryNames(directory))!!
        return List(namesLength(names)) { nameAt(names, it) }
    }

    /** This run's names in the folder, and how many others there are, unnamed. */
    private suspend fun listing(): String {
        val names = allNames()
        val own = names.filter { token in it }
        val others = names.size - own.size
        return "this check's: ${own.joinToString().ifEmpty { "none" }}; $others other entries"
    }

    private suspend fun sizeOf(file: JsHandle, expected: Int): String {
        val size = blobSize(awaitPromiseOrThrow(fileBehind(file))!!).toLong()
        check(size == expected.toLong()) { "$size bytes, not the $expected written" }
        return "$size bytes"
    }

    private suspend fun readBack(file: JsHandle, expected: ByteArray): String {
        val blob = awaitPromiseOrThrow(fileBehind(file))!!
        val read = awaitPromiseOrThrow(blobSlice(blob, 0.0, blobSize(blob)))!!.toKotlinBytes()
        check(read.contentEquals(expected)) { "${read.size} bytes read, not the ${expected.size} written, or not the same bytes" }
        return "${read.size} bytes, as written"
    }

    /**
     * Runs [action] as the step [what] and records how it went: its answer, or the browser's
     * exception — name and message — and the milliseconds it took. Null when it failed.
     */
    private suspend fun <T : Any> step(what: String, action: suspend () -> T): T? {
        onProgress(steps.toList(), what)
        val started = epochMillisNow()
        val result = try {
            Result.success(action())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Result.failure(failure)
        }
        val millis = epochMillisNow() - started
        steps += result.fold(
            onSuccess = { FolderCheckStep(what, FolderCheckStep.Outcome.DONE, answerOf(it), millis) },
            onFailure = { FolderCheckStep(what, FolderCheckStep.Outcome.FAILED, failureOf(it), millis) }
        )
        return result.getOrNull()
    }

    /**
     * A question rather than a step: [action]'s answer, "allowed", or the browser's refusal, either
     * of which is a finding and neither a failure of the check.
     */
    private suspend fun probe(what: String, action: suspend () -> String) {
        onProgress(steps.toList(), what)
        val started = epochMillisNow()
        val answer = try {
            "allowed: ${action()}"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            "refused: ${failureOf(failure)}"
        }
        steps += FolderCheckStep(what, FolderCheckStep.Outcome.DONE, answer, epochMillisNow() - started)
    }

    private fun skip(what: String, because: String) {
        steps += FolderCheckStep(what, FolderCheckStep.Outcome.SKIPPED, because, 0)
    }

    private fun answerOf(value: Any): String = value as? String ?: "done"

    companion object {
        /** What [removeIfOurs] answers when it removed the file. */
        private const val REMOVED = "removed"

        /** Every name this check makes starts so, which says in a file manager whose it is. */
        const val NAME_PREFIX = "cartogenesis-folder-check-"

        /** Three parts: a first, a middle and a last, which is where a stream's writes differ. */
        const val PARTS = 3

        /** A world as small as the generator makes, so the library's own round trip takes moments. */
        private const val WORLD_CELLS = 32

        /** Any seed; fixed so two runs of the check save the same world. */
        private const val WORLD_SEED = 20260925L

        /** The parts' bytes: a ramp, shifted by [offset], so a part out of place or a stale byte shows. */
        internal fun pattern(size: Int, offset: Int = 0): ByteArray = ByteArray(size) { ((it + offset) and 0xFF).toByte() }

        /**
         * A failure as the browser gave it: `String(error)` of the rejection, which for a
         * `DOMException` is its name and message, `NotAllowedError: …`; through the library, the
         * reader's words with that in brackets.
         */
        internal fun failureOf(failure: Throwable): String =
            failure.message?.ifEmpty { null } ?: failure::class.simpleName.orEmpty()
    }
}

/** One step of a [FolderCheck]: what was asked of the browser, how it went, what it answered, and in how long. */
internal class FolderCheckStep(val what: String, val outcome: Outcome, val answer: String, val millis: Long) {
    enum class Outcome { DONE, FAILED, SKIPPED }

    override fun toString(): String = "${outcome.name.lowercase()}: $what: $answer (${millis} ms)"
}

/** The report the page shows and the reader copies: the browser, the build and every step, numbered. */
internal fun folderCheckReport(folder: String, steps: List<FolderCheckStep>, running: String?): String = buildString {
    append(reportHead())
    appendLine("Folder: \"$folder\"")
    val failed = steps.count { it.outcome == FolderCheckStep.Outcome.FAILED }
    appendLine(
        when {
            running != null -> "Running."
            failed == 0 -> "Finished: every step answered."
            else -> "Finished: $failed step${if (failed == 1) "" else "s"} failed."
        }
    )
    steps.forEachIndexed { index, step -> appendLine("${index + 1}. $step") }
    running?.let { appendLine("${steps.size + 1}. under way: $it") }
}

@JsFun("() => location.search.indexOf('foldertest') >= 0")
internal external fun folderCheckRequested(): Boolean

@JsFun("() => String(navigator.userAgent)")
private external fun userAgent(): String

/**
 * `move` itself, answering with its own rejection whatever the name: the library's own call reads
 * `NotSupportedError` as "copy instead", which is right for a save and hides the answer here.
 */
@JsFun("async (file, directory, name) => file.move(directory, name)")
private external fun moveExactly(file: JsHandle, directory: JsHandle, name: String): JsHandle

/** Whether two handles are the same entry in the folder, resolving `true` or `false`. */
@JsFun("async (one, other) => one.isSameEntry(other)")
private external fun sameEntry(one: JsHandle, other: JsHandle): JsHandle

// ---- The page `?foldertest` shows in place of the application. ----

/**
 * Puts the check's page over everything: what it does, a button that opens the folder picker, the
 * report as text in a box the reader can select, and a button that copies it. Plain elements
 * rather than the application's canvas, so the report is text a phone's own selection and
 * clipboard work on. The picker is opened inside the click, which is the only moment a browser
 * allows it; [onPicked] is handed the folder, or null and the browser's refusal.
 */
@JsFun(
    """(introduction, onPicked) => {
        const page = document.createElement('div');
        page.style.cssText = 'position:fixed;inset:0;overflow:auto;padding:16px;box-sizing:border-box;' +
            'background:#fbf8f1;color:#1d1b16;font:16px/1.45 system-ui,sans-serif;z-index:2147483647';
        const title = document.createElement('h1');
        title.textContent = 'Folder check';
        title.style.cssText = 'font-size:22px;margin:0 0 8px';
        const about = document.createElement('p');
        about.textContent = introduction;
        const run = document.createElement('button');
        run.textContent = 'Choose the folder and run the check';
        const report = document.createElement('textarea');
        report.readOnly = true;
        report.style.cssText = 'display:block;width:100%;box-sizing:border-box;height:60vh;margin:12px 0;' +
            'font:13px/1.4 ui-monospace,monospace';
        const copy = document.createElement('button');
        copy.textContent = 'Copy the report';
        for (const button of [run, copy]) button.style.cssText = 'font:inherit;padding:10px 14px;margin:4px 8px 4px 0';
        copy.onclick = () => {
            report.focus();
            report.select();
            const copied = () => { copy.textContent = 'Copied'; };
            const byHand = () => { try { if (document.execCommand('copy')) copied(); } catch (e) {} };
            try {
                if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(report.value).then(copied, byHand);
                else byHand();
            } catch (e) { byHand(); }
        };
        run.onclick = () => {
            if (typeof window.showDirectoryPicker !== 'function') {
                onPicked(null, 'showDirectoryPicker is not offered by this browser');
                return;
            }
            run.disabled = true;
            window.showDirectoryPicker({ id: 'cartogenesis-library', mode: 'readwrite' })
                .then((folder) => onPicked(folder, null), (e) => { run.disabled = false; onPicked(null, String(e)); });
        };
        page.append(title, about, run, report, copy);
        document.body.appendChild(page);
        return { report: report, run: run, copy: copy };
    }"""
)
private external fun buildFolderCheckPage(introduction: String, onPicked: (JsHandle?, String?) -> Unit): JsHandle

@JsFun(
    """(page, text, finished) => {
        page.report.value = text;
        page.copy.textContent = 'Copy the report';
        if (finished) page.run.disabled = false;
    }"""
)
private external fun showReport(page: JsHandle, text: String, finished: Boolean)

/**
 * `?foldertest`, in place of the application: the page, and the check run on the folder the reader
 * picks, its report rewritten as each step starts and ends, so a step that never answers is seen
 * as the one under way.
 */
internal fun showFolderCheckPage() {
    val compressor = if (compressionStreamsAvailable()) WebGzipCompressor else NoCompression
    val scope = MainScope()
    var page: JsHandle? = null
    page = buildFolderCheckPage(FOLDER_CHECK_INTRODUCTION) { picked, refusal ->
        val shown = page ?: return@buildFolderCheckPage
        if (picked == null || isNullish(picked)) {
            showReport(shown, reportHead() + "No folder: ${refusal ?: "the picker was closed"}\n", finished = true)
            return@buildFolderCheckPage
        }
        val folder = directoryName(picked)
        scope.launch {
            FolderCheck(picked, compressor, onProgress = { steps, running ->
                showReport(shown, folderCheckReport(folder, steps, running), finished = running == null)
            }).run()
        }
    }
    showReport(page, reportHead(), finished = true)
}

private fun reportHead(): String = "Cartogenesis folder check, ${BuildInfo.VERSION}\nBrowser: ${userAgent()}\n"

private const val FOLDER_CHECK_INTRODUCTION =
    "This takes, one at a time, each step the library takes to save a world into a folder, and " +
        "says what this browser answered to each. Choose the folder the library uses. The check " +
        "makes its own files there, named cartogenesis-folder-check-…, and removes them again; it " +
        "changes and removes nothing else in the folder, and reads only the names in it and, as the " +
        "Library pane does, the header at the front of each save. When it has finished, copy " +
        "the report and send it back."
