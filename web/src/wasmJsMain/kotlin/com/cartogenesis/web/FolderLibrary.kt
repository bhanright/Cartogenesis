package com.cartogenesis.web

import com.cartogenesis.cartography.ByteWorldLibrary
import com.cartogenesis.cartography.OpeningLimit
import com.cartogenesis.cartography.Compressor
import com.cartogenesis.cartography.LibraryKeys
import com.cartogenesis.cartography.SaveProblem
import com.cartogenesis.cartography.SaveSink
import com.cartogenesis.cartography.SaveSource
import com.cartogenesis.cartography.WorldFormatException
import com.cartogenesis.cartography.WorldLibrary
import com.cartogenesis.ui.FolderChooser
import com.cartogenesis.ui.FolderPermission
import com.cartogenesis.ui.LibraryFolder
import com.cartogenesis.ui.RememberedPlace
import com.cartogenesis.ui.randomId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * The library in a folder on the disk the reader chose, through the File System Access API that
 * Chrome and Edge offer and Firefox and Safari do not.
 *
 * The folder holds what the desktop's does, file for file: one `.cgw` per world under the name the
 * library lists it by, so a folder a sync client keeps in step serves this page and the desktop
 * application alike. Everything that makes a library safe in such a folder is `ByteWorldLibrary`'s
 * and `LibraryKeys`' and holds here unchanged — keys checked, a new world never written over
 * another, one tab's writes made one at a time in the order they were asked for. What this file
 * adds is how a folder handle lists, reads and writes, and one rule a shared folder needs that the
 * desktop's own instance does not: a new file's name is chosen again once its bytes are written,
 * immediately before they are moved into place, because another writer may have taken the name
 * meanwhile. See [FolderWorldLibrary.creating].
 *
 * The ordering is one tab's, as the desktop's is one window's: a mutex inside one library object.
 * Another tab, the desktop application or a sync client writing the same folder is not ordered
 * against this one, and the browser gives a page no way to create a file only if it does not
 * exist, so between the last look and the move there is one call's width in which another writer
 * could take the name. A save never leaves half a file under any name, whatever the others do.
 */

// ---- The folder, through the handles the browser gives a page. ----
//
// Every call that answers with a promise is an `async` arrow, so a method this browser lacks, or
// one that throws before it returns a promise, rejects like any other failure and reaches
// `awaitFolder` with its own name, instead of being thrown through the call past it.

/** Every entry's name in [directory], files and folders alike, as a JavaScript array of strings. */
@JsFun(
    """(directory) => (async () => {
        const names = [];
        for await (const name of directory.keys()) names.push(name);
        return names;
    })()"""
)
internal external fun entryNames(directory: JsHandle): JsHandle

@JsFun("(names) => names.length")
internal external fun namesLength(names: JsHandle): Int

@JsFun("(names, index) => names[index]")
internal external fun nameAt(names: JsHandle, index: Int): String

/**
 * The `File` named [name], or null when there is none; any other failure — a folder of that name,
 * leave withdrawn, the folder gone — rejects.
 */
@JsFun(
    """async (directory, name) => directory.getFileHandle(name)
        .then((handle) => handle.getFile())
        .catch((e) => e && e.name === 'NotFoundError' ? null : Promise.reject(e))"""
)
private external fun fileOrNull(directory: JsHandle, name: String): JsHandle

/** The handle of the existing file [name], or null when there is none. */
@JsFun(
    """async (directory, name) => directory.getFileHandle(name)
        .catch((e) => e && e.name === 'NotFoundError' ? null : Promise.reject(e))"""
)
internal external fun existingFile(directory: JsHandle, name: String): JsHandle

/** A new or existing file [name]'s handle, which creates it, empty, when it is not there. */
@JsFun("async (directory, name) => directory.getFileHandle(name, { create: true })")
internal external fun createdFile(directory: JsHandle, name: String): JsHandle

/**
 * A writable stream over [file] that starts empty. The browser writes it to a swap file beside the
 * target and replaces the target with it only on `close`; `abort` throws the swap file away.
 */
@JsFun("async (file) => file.createWritable({ keepExistingData: false })")
internal external fun writableOf(file: JsHandle): JsHandle

/** One part of a save, resolved once the stream has taken it: the stream's own backpressure. */
@JsFun("async (writable, bytes) => writable.write(bytes)")
internal external fun writePart(writable: JsHandle, bytes: JsHandle): JsHandle

@JsFun("async (writable) => writable.close()")
internal external fun closeWritable(writable: JsHandle): JsHandle

@JsFun("async (writable) => writable.abort().catch(() => null)")
internal external fun abortWritable(writable: JsHandle): JsHandle

/** Whether this browser can rename a file handle in place. */
@JsFun("(file) => typeof file.move === 'function'")
internal external fun canMove(file: JsHandle): Boolean

/**
 * Renames [file] to [name] in [directory]. Resolves null once moved, or with the browser's refusal
 * as text — `NotSupportedError: …` from an older Chrome for a folder on the disk, and whatever
 * Chrome on Android answers for a folder there, where a picked folder's files cannot be renamed —
 * and never rejects: every refusal is a reason to copy instead, which [FolderWorldLibrary.publish]
 * decides.
 */
@JsFun(
    """async (file, directory, name) => {
        try { await file.move(directory, name); return null; }
        catch (e) { return String(e); }
    }"""
)
private external fun moveFile(file: JsHandle, directory: JsHandle, name: String): JsHandle

/** Removes the file [name], resolving `false` when there was none. */
@JsFun(
    """async (directory, name) => directory.removeEntry(name)
        .then(() => true)
        .catch((e) => e && e.name === 'NotFoundError' ? false : Promise.reject(e))"""
)
internal external fun removeFile(directory: JsHandle, name: String): JsHandle

@JsFun("(value) => value === true")
internal external fun isTrue(value: JsHandle?): Boolean

/**
 * What a failure in the folder means to a reader, from the `DOMException` name the promise was
 * rejected with. The browser's own messages name its internals; these name the reader's folder.
 */
private fun readerWords(failure: Throwable): String {
    val message = failure.message.orEmpty()
    return when {
        message.startsWith("NotAllowedError") -> "this browser no longer has leave to use the folder"
        message.startsWith("NotFoundError") -> "the folder, or the file in it, is no longer there"
        message.startsWith("NoModificationAllowedError") ->
            "another program has the file open; try again once it has let go"
        message.startsWith("QuotaExceededError") -> "the disk is full"
        message.startsWith("TypeMismatchError") -> "a folder has that name, not a file"
        message.startsWith("InvalidStateError") -> "the file changed while it was being read"
        else -> message.ifEmpty { failure::class.simpleName.orEmpty() }
    }
}

private suspend fun awaitFolder(promise: JsHandle): JsHandle? =
    try {
        awaitPromiseOrThrow(promise)
    } catch (cancelled: CancellationException) {
        // A cancellation is an IllegalStateException too, and is not the storage refusing.
        throw cancelled
    } catch (refused: IllegalStateException) {
        throw FolderException(readerWords(refused), refused.message.orEmpty())
    }

/**
 * A folder operation that failed, in the reader's words, followed by the browser's own [detail] —
 * the exception's name and message, as `NotAllowedError: …` — in brackets.
 *
 * The detail is in the message, not only kept beside it, because the message is what the library
 * pane shows, and the name is what tells one browser's refusal from another's: on a phone, where
 * the folder is Android's rather than a directory's, the reader's words alone left nothing to go on.
 */
internal class FolderException(val readerMessage: String, val detail: String) :
    IllegalStateException(if (detail.isEmpty() || detail == readerMessage) readerMessage else "$readerMessage ($detail)")

// ---- How much a save holds while it passes through. ----

/**
 * The most bytes the folder library has held at once for a save passing through it, reading or
 * writing: its own part buffer, and the copy of a part handed to the browser and not yet taken.
 *
 * A count kept by the library itself rather than a reading of the tab's heap, because the heap
 * moves with everything else the tab does and a collector that has not run yet; this moves only
 * when a part is taken or let go, so a bound on it is a bound that can be asserted. It is what the
 * guard in `FolderLibraryTest` holds to [FolderWorldLibrary.BUFFER_BOUND_PARTS] parts.
 */
internal class BufferGauge {
    var heldBytes: Long = 0
        private set
    var peakBytes: Long = 0
        private set

    fun hold(bytes: Int) {
        heldBytes += bytes
        if (heldBytes > peakBytes) peakBytes = heldBytes
    }

    fun release(bytes: Int) {
        heldBytes -= bytes
    }

    fun resetPeak() {
        peakBytes = heldBytes
    }
}

/**
 * Points in a write at which the browser tests step in: to cancel while a part is with the
 * browser, to put another writer's file in the way just before one is made, or to fail at a step
 * no stand-in compressor reaches. The application never sets one, and each does nothing.
 */
internal open class WriteSteps {
    /** A part has been handed to the stream and its promise not yet waited for. */
    open suspend fun partHandedToStream(index: Int) {}

    /** The fallback copy is about to make, or open, the file [name]. */
    open suspend fun beforeTargetCreated(name: String) {}

    /** A writable stream is about to be opened on a file. */
    open suspend fun beforeStreamOpened() {}

    /** A save is published and its temporary file [name] is about to be removed. */
    open suspend fun beforeTemporaryRemoved(name: String) {}
}

/**
 * A save written into a writable stream a part at a time: [partBytes] are gathered, handed to the
 * browser, and waited for before the next part is gathered, so what the tab holds for the save is
 * one part and the copy of it the stream is taking — never the file.
 */
private class FolderSink(
    private val writable: JsHandle,
    partBytes: Int,
    private val gauge: BufferGauge,
    private val steps: WriteSteps
) : SaveSink {
    private val buffer = ByteArray(partBytes)
    private var filled = 0
    private var handed = 0

    init {
        gauge.hold(buffer.size)
    }

    override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
        var from = offset
        val end = offset + length
        while (from < end) {
            val taken = minOf(end - from, buffer.size - filled)
            bytes.copyInto(buffer, filled, from, from + taken)
            filled += taken
            from += taken
            if (filled == buffer.size) flush()
        }
    }

    suspend fun flush() {
        if (filled == 0) return
        val part = filled
        gauge.hold(part)
        try {
            val taking = writePart(writable, buffer.toJs(0, part))
            steps.partHandedToStream(handed++)
            awaitFolder(taking)
        } finally {
            gauge.release(part)
        }
        filled = 0
    }

    fun close() {
        gauge.release(buffer.size)
    }
}

/**
 * A file read back a slice of [partBytes] at a time through `Blob.slice`, which reads only that
 * slice from the disk: the tab holds one slice and the copy it arrived in, never the file.
 */
private class FolderSource(
    private val file: JsHandle,
    private val partBytes: Int,
    private val gauge: BufferGauge
) : SaveSource {
    private val size = blobSize(file)
    private var fetchedTo = 0.0
    private var buffer = ByteArray(0)
    private var position = 0

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (position == buffer.size) {
            if (fetchedTo >= size) return -1
            val end = minOf(size, fetchedTo + partBytes)
            val sliceBytes = (end - fetchedTo).toInt()
            gauge.release(buffer.size)
            buffer = ByteArray(0)
            // The slice arrives as a JavaScript array and is copied into the heap: both are held
            // while the copy is made, and the copy alone afterwards.
            gauge.hold(2 * sliceBytes)
            val slice = try {
                awaitFolder(blobSlice(file, fetchedTo, end))?.toKotlinBytes()
            } catch (refused: FolderException) {
                gauge.release(2 * sliceBytes)
                throw WorldFormatException(SaveProblem.UNREADABLE, refused.message.orEmpty())
            } catch (cancelled: CancellationException) {
                gauge.release(2 * sliceBytes)
                throw cancelled
            }
            gauge.release(sliceBytes)
            if (slice == null) {
                gauge.release(sliceBytes)
                throw WorldFormatException(SaveProblem.UNREADABLE, "the browser returned nothing for part of the file")
            }
            buffer = slice
            fetchedTo = end
            position = 0
            if (buffer.isEmpty()) return -1
        }
        val count = minOf(length, buffer.size - position)
        buffer.copyInto(into, offset, position, position + count)
        position += count
        return count
    }

    fun close() {
        gauge.release(buffer.size)
        buffer = ByteArray(0)
    }
}

/**
 * Saved worlds as `.cgw` files in [directory], a `FileSystemDirectoryHandle` the reader chose (or,
 * in the tests, one from the origin private file system, which behaves the same way for everything
 * here but permission).
 *
 * **Writing.** A save of a file that is there is written through the file's own writable stream,
 * which the browser keeps in a swap file beside it (`<name>.crswap`, which the listing ignores) and
 * moves over the file only when the stream is closed whole; a failure or a cancellation before the
 * close aborts the stream and the file is as it was. A new file is written under a temporary name
 * that does not end in `.cgw` — `.<name>.<token>.tmp`, see [temporaryNameFor] — and moved to its
 * name only once it is whole, so no reader, listing or sync client sees a half of one, or the empty
 * file a writable stream's target is from the moment it is created. A failure, or a cancellation
 * before the move, removes the temporary file.
 *
 * **On Android** none of that is atomic, and nothing a page can do makes it so: Chrome keeps the
 * swap file in its own cache rather than beside the file, and a close empties the file and copies
 * the swap file into it, so a close that fails partway can leave a file short; and a file cannot be
 * renamed there, so a new save is always copied into its name. See docs/DESIGN_LEDGER.md, Fix
 * 2c-android, for the source lines.
 *
 * **Where the browser cannot move a file** ([canMove] false, or `move` refusing for any reason),
 * the temporary file is copied into the new name instead: see [publish] and [copyIntoNewFile]. The
 * new name is then visible, empty, while the copy runs.
 *
 * **Publishing is one step, letting go of the temporary file another.** Once the save is whole
 * under its name nothing undoes it: a temporary file that will not be removed stays, under a name
 * no listing reads.
 *
 * **Cancellation** is seen at every wait on the browser. The close and the move are the commits,
 * and each is seen to its end once begun, so a cancelled save is either not made or made whole.
 *
 * **Reading.** A save is read a slice of [partBytes] at a time through `Blob.slice`, and a listing
 * reads only the front of each file, so no file is ever held whole in the tab.
 *
 * [gauge] counts what a save holds while it passes through, for the memory guard; [steps] is where
 * the browser tests step into a write.
 */
internal class FolderWorldLibrary(
    private val directory: JsHandle,
    compressor: Compressor,
    writtenBy: String,
    private val partBytes: Int = PART_BYTES,
    private val steps: WriteSteps = WriteSteps()
) : ByteWorldLibrary(compressor, writtenBy) {

    override val openingLimit: OpeningLimit = BROWSER_OPENING_LIMIT

    val gauge = BufferGauge()

    override suspend fun names(): List<String> {
        val names = awaitFolder(entryNames(directory)) ?: return emptyList()
        return List(namesLength(names)) { nameAt(names, it) }.filter { it.endsWith(LibraryKeys.EXTENSION) }
    }

    override suspend fun <T> reading(name: String, block: suspend (SaveSource) -> T): T? {
        val file = fileOf(name) ?: return null
        val source = FolderSource(file, partBytes, gauge)
        try {
            return block(source)
        } finally {
            source.close()
        }
    }

    override suspend fun readPrefix(name: String, limitBytes: Int): ByteArray? {
        val file = fileOf(name) ?: return null
        val end = minOf(blobSize(file), limitBytes.toDouble())
        return try {
            awaitFolder(blobSlice(file, 0.0, end))?.toKotlinBytes() ?: ByteArray(0)
        } catch (refused: FolderException) {
            throw WorldFormatException(SaveProblem.UNREADABLE, refused.message.orEmpty())
        }
    }

    /**
     * The file [name] replaced whole through its own stream, or — when a key is given whose file is
     * no longer there, deleted by another writer since it was listed — created under that name the
     * way a new file is, so it never appears half-made either.
     */
    override suspend fun replacing(name: String, contents: suspend (SaveSink) -> Unit) {
        val existing = awaitFolder(existingFile(directory, name))
        if (existing == null || isNullish(existing)) {
            publishNew(name, contents, replacesTakenName = true) { name }
        } else {
            writeThrough(existing, contents)
        }
    }

    /** A new file, named once it is whole: see the class comment. */
    override suspend fun creating(wanted: String, contents: suspend (SaveSink) -> Unit): String =
        publishNew(wanted, contents, replacesTakenName = false) { freeName(wanted) }

    override suspend fun remove(name: String) {
        awaitFolder(removeFile(directory, name))
    }

    private suspend fun fileOf(name: String): JsHandle? {
        val file = try {
            awaitFolder(fileOrNull(directory, name))
        } catch (refused: FolderException) {
            throw WorldFormatException(SaveProblem.UNREADABLE, refused.message.orEmpty())
        }
        return if (file == null || isNullish(file)) null else file
    }

    /**
     * Streams [contents] into [file] and commits it, or aborts the stream and rethrows. A cancel
     * that has arrived stops the write before the close; the close, once begun, is seen to its
     * end, so the file is the old one or the new one and this call knows which.
     */
    private suspend fun writeThrough(file: JsHandle, contents: suspend (SaveSink) -> Unit) {
        steps.beforeStreamOpened()
        // Opening the stream is seen to its end: a wait abandoned partway would leave the browser
        // holding an open stream, and its swap file beside the file, with nothing to abort it. A
        // cancel that arrived meanwhile is met just below, where the stream is aborted.
        val writable = withContext(NonCancellable) { awaitFolder(writableOf(file)) }
            ?: error("the browser gave no stream to write the file with")
        val sink = FolderSink(writable, partBytes, gauge, steps)
        try {
            currentCoroutineContext().ensureActive()
            contents(sink)
            sink.flush()
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) { awaitFolder(closeWritable(writable)) }
        } catch (failure: Throwable) {
            withContext(NonCancellable) { awaitPromise(abortWritable(writable)) }
            throw failure
        } finally {
            sink.close()
        }
    }

    /**
     * Writes [contents] under a temporary name, then publishes it under the name [finalName] gives
     * — asked only once the bytes are written, as the last look at the folder — and returns that
     * name. [replacesTakenName] is true when the name is a key being written back, whose file is
     * the one to replace; otherwise a name found taken is passed over. Until the save is published,
     * whatever fails or is cancelled leaves no file this call made; after it, nothing undoes it.
     */
    private suspend fun publishNew(
        wanted: String,
        contents: suspend (SaveSink) -> Unit,
        replacesTakenName: Boolean,
        finalName: suspend () -> String
    ): String {
        val temporaryName = temporaryNameFor(wanted, randomId())
        // Made whole or not at all, as the copy's file is; a cancel meanwhile is met in the write,
        // inside the `try` that removes it.
        val temporary = withContext(NonCancellable) { awaitFolder(createdFile(directory, temporaryName)) }
            ?: error("the browser gave no file to write the save into")
        val name = try {
            writeThrough(temporary, contents)
            currentCoroutineContext().ensureActive()
            publish(temporary, replacesTakenName, finalName)
        } catch (failure: Throwable) {
            withContext(NonCancellable) { runCatching { awaitFolder(removeFile(directory, temporaryName)) } }
            throw failure
        }
        // Published. A move has taken the temporary name already, and after a copy the file is
        // let go on its own: failing to, it stays under a name no listing reads.
        withContext(NonCancellable) {
            runCatching {
                steps.beforeTemporaryRemoved(temporaryName)
                awaitFolder(removeFile(directory, temporaryName))
            }
        }
        return name
    }

    /**
     * Makes the written [temporary] the save: by a rename where the browser can, by a copy where
     * not.
     *
     * Any refusal of the rename sends the save to the copy, not only the `NotSupportedError` an
     * older Chrome gives. Chrome on Android offers `move` on every file handle but cannot rename a
     * file in a folder a phone picked, whose files are Android's documents rather than a
     * directory's entries (`FileSystemURL::CreateSibling` returns nothing for them), and its answer
     * there is not known to be `NotSupportedError`; read as a failure, it would end every new save.
     * A move refused after the browser had already copied the file into the name is seen as a file
     * under that name at the temporary's size, and taken as done.
     */
    private suspend fun publish(temporary: JsHandle, replacesTakenName: Boolean, finalName: suspend () -> String): String {
        if (canMove(temporary)) {
            val name = finalName()
            // The rename is the commit, seen to its end once begun.
            val refusal = withContext(NonCancellable) { awaitFolder(moveFile(temporary, directory, name)) }
            if (refusal == null || isNullish(refusal)) return name
            if (withContext(NonCancellable) { holdsTheSave(name, temporary) }) return name
        }
        return copyIntoNewFile(temporary, replacesTakenName, finalName)
    }

    /**
     * Whether [name] now holds a file the size of [temporary], which is the save moved there. No,
     * when either cannot be read: the copy that follows then says why.
     */
    private suspend fun holdsTheSave(name: String, temporary: JsHandle): Boolean {
        val sizes = runCatching {
            val there = awaitFolder(fileOrNull(directory, name))?.takeUnless(::isNullish)
            val written = awaitFolder(fileBehind(temporary))?.takeUnless(::isNullish)
            if (there == null || written == null) null else blobSize(there) to blobSize(written)
        }.getOrNull() ?: return false
        return sizes.first == sizes.second
    }

    /**
     * The fallback where a file cannot be moved: [temporary]'s bytes streamed into a file under the
     * name [finalName] gives, looked up again immediately before the file is made, and the name
     * returned.
     *
     * `getFileHandle(create)` opens a file that is there as readily as it makes one, and the page is
     * given no way to make one only if it is not. So the file it hands back is looked at before a
     * byte goes into it: one with anything in it is another writer's, arrived since the last look,
     * and the save moves on to the next free name and leaves it be — unless [replacesTakenName], when
     * that file is the one being written back. On a failure the file is removed only while it is
     * still the empty file this call made; one another writer has filled meanwhile is theirs.
     */
    private suspend fun copyIntoNewFile(
        temporary: JsHandle,
        replacesTakenName: Boolean,
        finalName: suspend () -> String
    ): String {
        val saved = awaitFolder(fileBehind(temporary)) ?: error("the browser could not read back the save it wrote")
        repeat(MOST_NAMES_TRIED) {
            val name = finalName()
            steps.beforeTargetCreated(name)
            // Making the file and looking at it are seen to their end: abandoned between the two,
            // an empty .cgw would be left that no step below knew to clear. A cancel that arrived
            // meanwhile is met inside the `try`, where the file is cleared.
            val target = withContext(NonCancellable) { awaitFolder(createdFile(directory, name)) }
                ?: error("the browser gave no file to copy the save into")
            val bytesFound = try {
                withContext(NonCancellable) { awaitFolder(fileBehind(target)) }?.let(::blobSize)
                    ?: error("the browser could not read the file it made")
            } catch (failure: Throwable) {
                withContext(NonCancellable) { removeIfStillEmpty(target, name) }
                throw failure
            }
            if (bytesFound > 0 && !replacesTakenName) return@repeat
            try {
                currentCoroutineContext().ensureActive()
                writeThrough(target) { sink -> copy(saved, sink) }
                return name
            } catch (failure: Throwable) {
                withContext(NonCancellable) { removeIfStillEmpty(target, name) }
                throw failure
            }
        }
        error("every name this save tried was taken by another writer while it was being written")
    }

    /** [source] into [sink], a slice at a time, through a buffer of one part. */
    private suspend fun copy(source: JsHandle, sink: SaveSink) {
        val input = FolderSource(source, partBytes, gauge)
        val buffer = ByteArray(partBytes)
        gauge.hold(buffer.size)
        try {
            while (true) {
                val count = input.read(buffer, 0, buffer.size)
                if (count < 0) break
                if (count > 0) sink.write(buffer, 0, count)
            }
        } finally {
            input.close()
            gauge.release(buffer.size)
        }
    }

    private suspend fun removeIfStillEmpty(target: JsHandle, name: String) {
        runCatching {
            val now = awaitFolder(fileBehind(target))
            if (now != null && !isNullish(now) && blobSize(now) == 0.0) awaitFolder(removeFile(directory, name))
        }
    }

    companion object {
        /**
         * A mebibyte a part: the codec's own chunk, and the IndexedDB library's part, so a part is a
         * chunk or so and a 2048 save's two hundred or so megabytes go through in two hundred steps.
         */
        const val PART_BYTES = 1 shl 20

        /**
         * The most the library may hold for a save at once, in parts, where the browser can move a
         * file: the part being gathered and the copy of the last one the stream is still taking when
         * writing, or a slice and the copy it arrived in when reading. Two, whatever the file's size.
         */
        const val BUFFER_BOUND_PARTS = 2

        /**
         * The same where the save is copied into its name instead: the part being gathered and the
         * copy the stream is taking, as before, and beside them the slice being read back from the
         * temporary file and the buffer it passes through — or, while a slice arrives, the slice
         * twice over. Four, whatever the file's size.
         */
        const val FALLBACK_BOUND_PARTS = 4

        /** Ends a temporary file's name so no listing takes it for a save; the desktop's store uses the same. */
        private const val TEMPORARY_SUFFIX = ".tmp"

        /**
         * The name a new save [wanted] is written under before it is published, [token] making it
         * this save's own: `.<wanted>.<token>.tmp`.
         *
         * Chrome checks every name a page gives a folder on the disk (`IsSafePathComponent` in its
         * File System Access manager, the same on Android as on a computer): with one leading dot
         * set aside, a name may not begin or end with white space, a dot or a tilde, may hold none
         * of `"` `*` `/` `:` `<` `>` `?` `\` `|` or a control character, may not be a Windows device name,
         * and its extension may not be `lnk`, `scf`, `url`, a `{CLSID}` or one Safe Browsing counts
         * dangerous. A name it refuses is a `TypeError`, "Name is not allowed.", before the folder is
         * touched. The desktop store's `~<name>.<token>.tmp` begins with a tilde, so every new save
         * into a folder a reader picked was refused; the origin private file system the browser
         * tests use checks none of this, which is how it passed them. The leading dot is the one
         * mark the rule allows that keeps the file out of a file manager's ordinary view.
         */
        internal fun temporaryNameFor(wanted: String, token: String): String = ".$wanted.$token$TEMPORARY_SUFFIX"

        /**
         * How many names the fallback copy tries, each found taken by another writer in the moment
         * since the last look, before it gives up: more than a handful in a row is something making
         * files as fast as this looks, and a save that says so beats one that loops.
         */
        private const val MOST_NAMES_TRIED = 16
    }
}

/** The `File` a file handle holds now. */
@JsFun("async (file) => file.getFile()")
internal external fun fileBehind(file: JsHandle): JsHandle

// ---- The folder as the interface asks about it, and the reader's choice kept between visits. ----

@JsFun("() => typeof window !== 'undefined' && typeof window.showDirectoryPicker === 'function'")
internal external fun directoryPickerAvailable(): Boolean

/**
 * The folder picker, asking for leave to write as well as read. Resolves null when the reader
 * cancels. `id` makes the browser open the picker where it was last left for this page.
 */
@JsFun(
    """() => window.showDirectoryPicker({ id: 'cartogenesis-library', mode: 'readwrite' })
        .catch((e) => e && e.name === 'AbortError' ? null : Promise.reject(e))"""
)
private external fun pickDirectory(): JsHandle

@JsFun("(directory) => directory.name")
internal external fun directoryName(directory: JsHandle): String

/**
 * `queryPermission` or, when [ask] is true, `requestPermission` for reading and writing. A browser
 * whose handles have neither — the private file system in one that is not Chrome — has nothing to
 * refuse, and answers `granted`.
 */
@JsFun(
    """(directory, ask) => {
        const method = ask ? directory.requestPermission : directory.queryPermission;
        if (typeof method !== 'function') return Promise.resolve('granted');
        return method.call(directory, { mode: 'readwrite' });
    }"""
)
internal external fun directoryPermission(directory: JsHandle, ask: Boolean): JsHandle

@JsFun("(value) => String(value)")
internal external fun asText(value: JsHandle): String

/** Resolves null when [directory] can be listed, or with the name of the exception that stopped it. */
@JsFun(
    """(directory) => (async () => {
        try { for await (const name of directory.keys()) break; return null; }
        catch (e) { return (e && e.name) ? e.name : String(e); }
    })()"""
)
private external fun directoryUnreachable(directory: JsHandle): JsHandle

internal fun permissionOf(answer: String): FolderPermission = when (answer) {
    "granted" -> FolderPermission.GRANTED
    "prompt" -> FolderPermission.PROMPT
    else -> FolderPermission.DENIED
}

/** A folder the reader chose, over its `FileSystemDirectoryHandle`. */
internal class BrowserLibraryFolder(
    val handle: JsHandle,
    compressor: Compressor
) : LibraryFolder {

    override val name: String = directoryName(handle)

    override val library: WorldLibrary = FolderWorldLibrary(handle, compressor, "web")

    override suspend fun permission(): FolderPermission =
        permissionOf(awaitPromiseOrThrow(directoryPermission(handle, ask = false))?.let(::asText).orEmpty())

    override suspend fun requestPermission(): FolderPermission =
        permissionOf(awaitPromiseOrThrow(directoryPermission(handle, ask = true))?.let(::asText).orEmpty())

    override suspend fun unreachableBecause(): String? {
        val failure = awaitPromiseOrThrow(directoryUnreachable(handle))
        if (failure == null || isNullish(failure)) return null
        return when (val name = asText(failure)) {
            "NotFoundError" -> "it is no longer where it was"
            "NotAllowedError" -> "this browser no longer has leave to use it"
            else -> name
        }
    }
}

// The choice, in IndexedDB: a `FileSystemDirectoryHandle` is structured-cloneable, which is how a
// page keeps one across visits, and nothing else a page can store will hold one.

@JsFun(
    """() => new Promise((resolve, reject) => {
        const request = indexedDB.open('cartogenesis-library-place', 1);
        request.onupgradeneeded = () => request.result.createObjectStore('place');
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error);
    })"""
)
private external fun openPlaceDatabase(): JsHandle

@JsFun(
    """(db) => new Promise((resolve, reject) => {
        const transaction = db.transaction('place', 'readonly');
        const store = transaction.objectStore('place');
        const folder = store.get('folder');
        const inFolder = store.get('inFolder');
        transaction.oncomplete = () => {
            db.close();
            resolve([folder.result === undefined ? null : folder.result, inFolder.result === true]);
        };
        transaction.onerror = () => { db.close(); reject(transaction.error); };
    })"""
)
private external fun readPlace(db: JsHandle): JsHandle

@JsFun(
    """(db, folder, inFolder) => new Promise((resolve, reject) => {
        const transaction = db.transaction('place', 'readwrite');
        const store = transaction.objectStore('place');
        if (folder === null || folder === undefined) store.delete('folder'); else store.put(folder, 'folder');
        store.put(inFolder, 'inFolder');
        transaction.oncomplete = () => { db.close(); resolve(null); };
        transaction.onerror = () => { db.close(); reject(transaction.error); };
    })"""
)
private external fun writePlace(db: JsHandle, folder: JsHandle?, inFolder: Boolean): JsHandle

@JsFun("(pair) => pair[0]")
private external fun first(pair: JsHandle): JsHandle?

@JsFun("(pair) => pair[1]")
private external fun secondIsTrue(pair: JsHandle): Boolean

/**
 * Chrome's and Edge's folder picker, and the reader's choice kept in this browser between visits.
 *
 * The choice lives in IndexedDB under a database of its own, beside the library's rather than in
 * it, so the library's database keeps the version it has; and not in the settings document, which
 * is text in local storage and cannot hold a handle. The desktop's `libraryFolder` setting is a
 * path and has no meaning here.
 */
internal class BrowserFolderChooser(private val compressor: Compressor) : FolderChooser {

    override suspend fun pick(): LibraryFolder? {
        val handle = awaitPromiseOrThrow(pickDirectory())
        if (handle == null || isNullish(handle)) return null
        return BrowserLibraryFolder(handle, compressor)
    }

    override suspend fun remembered(): RememberedPlace {
        val db = awaitPromiseOrThrow(openPlaceDatabase()) ?: return RememberedPlace.NOTHING
        val pair = awaitPromiseOrThrow(readPlace(db)) ?: return RememberedPlace.NOTHING
        val handle = first(pair)
        val folder = if (handle == null || isNullish(handle)) null else BrowserLibraryFolder(handle, compressor)
        return RememberedPlace(folder, inFolder = folder != null && secondIsTrue(pair))
    }

    override suspend fun remember(place: RememberedPlace) {
        val db = awaitPromiseOrThrow(openPlaceDatabase()) ?: return
        val handle = (place.folder as? BrowserLibraryFolder)?.handle
        awaitPromiseOrThrow(writePlace(db, handle, place.inFolder))
    }
}
