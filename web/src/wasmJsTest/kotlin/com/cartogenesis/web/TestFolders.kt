package com.cartogenesis.web

import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap

/**
 * Folders for the browser tests, in the origin private file system: a real
 * `FileSystemDirectoryHandle` the page can have without a picker, which a test cannot open.
 *
 * What it shares with a folder the reader picks, and what it does not, is the subject of
 * `FolderLibraryTest`'s class comment. Every folder here is made fresh under a name of its own and
 * removed afterwards, so no test reads what another left.
 */

@JsFun(
    """(name) => navigator.storage.getDirectory()
        .then((root) => root.getDirectoryHandle(name, { create: true }))"""
)
private external fun privateFolderPromise(name: String): JsHandle

@JsFun(
    """(name) => navigator.storage.getDirectory()
        .then((root) => root.removeEntry(name, { recursive: true }))
        .catch(() => null)"""
)
private external fun removePrivateFolderPromise(name: String): JsHandle

@JsFun(
    """(directory) => (async () => {
        const names = [];
        for await (const name of directory.keys()) names.push(name);
        return names.sort().join('\n');
    })()"""
)
private external fun allNamesPromise(directory: JsHandle): JsHandle

@JsFun(
    """(directory, name, bytes) => directory.getFileHandle(name, { create: true })
        .then((file) => file.createWritable())
        .then((writable) => writable.write(bytes).then(() => writable.close()))"""
)
private external fun writeRawPromise(directory: JsHandle, name: String, bytes: JsHandle): JsHandle

@JsFun(
    """(directory, name) => directory.getFileHandle(name)
        .then((file) => file.getFile())
        .then((blob) => blob.arrayBuffer())
        .then((buffer) => new Uint8Array(buffer))"""
)
private external fun readRawPromise(directory: JsHandle, name: String): JsHandle

@JsFun("(directory, name) => directory.getDirectoryHandle(name, { create: true })")
private external fun makeFolderPromise(directory: JsHandle, name: String): JsHandle

@JsFun("(value) => String(value)")
private external fun textOf(value: JsHandle): String

/** A fresh, empty folder in the private file system, and what the tests do with it. */
internal class TestFolder private constructor(val name: String, val handle: JsHandle) {

    /** Every entry in the folder, sorted: saves, temporary files and swap files alike. */
    suspend fun entries(): List<String> {
        val joined = textOf(awaitPromiseOrThrow(allNamesPromise(handle))!!)
        return if (joined.isEmpty()) emptyList() else joined.split('\n')
    }

    /** Puts [bytes] in the folder as [file], as another program would: not through the library. */
    suspend fun writeRaw(file: String, bytes: ByteArray) {
        awaitPromiseOrThrow(writeRawPromise(handle, file, bytes.toJs()))
    }

    suspend fun readRaw(file: String): ByteArray = awaitPromiseOrThrow(readRawPromise(handle, file))!!.toKotlinBytes()

    /** A folder inside this one called [folder], which is a name a listing takes for a file. */
    suspend fun makeFolder(folder: String) {
        awaitPromiseOrThrow(makeFolderPromise(handle, folder))
    }

    suspend fun remove() {
        awaitPromiseOrThrow(removePrivateFolderPromise(name))
    }

    companion object {
        private var made = 0

        suspend fun fresh(purpose: String): TestFolder {
            val name = "test-${purpose}-${epochMillisNow()}-${made++}"
            return TestFolder(name, awaitPromiseOrThrow(privateFolderPromise(name))!!)
        }
    }
}

/** Runs [block] over a fresh folder and removes it afterwards, pass or fail. */
internal suspend fun <T> withTestFolder(purpose: String, block: suspend (TestFolder) -> T): T {
    val folder = TestFolder.fresh(purpose)
    try {
        return block(folder)
    } finally {
        folder.remove()
    }
}

/**
 * Takes `move` off every file-system handle prototype that has one of its own, returning what was
 * taken for [restoreMove] to put back: a browser without it, as older Chrome is for a folder on
 * the disk, which the private file system cannot otherwise stand in for.
 */
@JsFun(
    """() => {
        const held = [];
        for (const proto of [FileSystemHandle.prototype, FileSystemFileHandle.prototype]) {
            const own = Object.getOwnPropertyDescriptor(proto, 'move');
            if (own) { held.push([proto, own]); delete proto.move; }
        }
        return held;
    }"""
)
private external fun hideMove(): JsHandle

@JsFun("(held) => { for (const [proto, own] of held) Object.defineProperty(proto, 'move', own); }")
private external fun restoreMove(held: JsHandle)

@JsFun("() => typeof FileSystemFileHandle.prototype.move === 'function'")
private external fun moveOffered(): Boolean

/** Runs [block] as this browser is, or, when [canMove] is false, as one with no `move`. */
internal suspend fun <T> withMoveIf(canMove: Boolean, block: suspend () -> T): T {
    if (canMove) return block()
    val held = hideMove()
    try {
        check(!moveOffered()) { "move was not taken away" }
        return block()
    } finally {
        restoreMove(held)
    }
}

/**
 * Chrome's rule for a name a page gives a folder on the disk, transcribed from
 * `FileSystemAccessManagerImpl::IsSafePathComponent` and the `base::i18n::IsFilenameLegal` and
 * `base::IsReservedNameOnWindows` it calls; the origin private file system skips all of it, which
 * is why the tests' folders need it put back. Everything but Safe Browsing's list of dangerous
 * extensions, which is data Chrome updates on its own and no name here ends in.
 *
 * With one leading dot set aside: no white space, dot or tilde at either end; none of
 * `"` `*` `/` `:` `<` `>` `?` `\` `|`, control or format characters; not ending in a dot; no `lnk`,
 * `scf`, `url` or `{CLSID}` extension; not a Windows device name, by the part before the first dot,
 * nor `desktop.ini`, `thumbs.db`, `conin$` or `conout$`.
 */
private const val CHROME_DISK_NAME_RULE = """((name) => {
    if (typeof name !== 'string' || name === '' || name === '.' || name === '..' || /[\/\\]/.test(name)) return false;
    const rest = name[0] === '.' ? name.slice(1) : name;
    if (/["*\/:<>?\\|\u0000-\u001f\u007f-\u009f­؀-؅؜۝܏​-‏‪-‮⁠-⁤⁦-⁯﻿￹-￻]/.test(rest)) return false;
    const atEnds = /[\s.~]/;
    if (rest !== '' && (atEnds.test(rest[0]) || atEnds.test(rest[rest.length - 1]))) return false;
    const dot = name.lastIndexOf('.');
    const extension = dot >= 0 ? name.slice(dot + 1).toLowerCase() : '';
    if (['lnk', 'scf', 'url'].includes(extension) || /^\{.*\}$/.test(extension)) return false;
    if (name.endsWith('.')) return false;
    const lower = name.toLowerCase().replace(/[ .]+$/, '');
    const devices = ['con', 'prn', 'aux', 'nul', 'clock$'];
    for (let n = 1; n <= 9; n++) devices.push('com' + n, 'lpt' + n);
    if (devices.includes(lower.split('.')[0])) return false;
    return !['desktop.ini', 'thumbs.db', 'conin$', 'conout$'].includes(lower);
})"""

/** Whether Chrome would let a page name a file [name] in a folder on the disk: see [CHROME_DISK_NAME_RULE]. */
@JsFun("(name) => $CHROME_DISK_NAME_RULE(name)")
internal external fun chromeAllowsOnTheDisk(name: String): Boolean

/**
 * Makes the private file system refuse, as a folder on the disk does, every name Chrome refuses
 * there — in `getFileHandle`, `removeEntry` and a file's `move` — with the `TypeError` Chrome
 * gives, "Name is not allowed."; returns what it replaced, for [restoreNames].
 */
@JsFun(
    """() => {
        const allowed = $CHROME_DISK_NAME_RULE;
        const directory = FileSystemDirectoryHandle.prototype;
        const file = FileSystemFileHandle.prototype;
        const held = {
            getFileHandle: directory.getFileHandle,
            removeEntry: directory.removeEntry,
            ownMove: Object.getOwnPropertyDescriptor(file, 'move'),
            move: file.move
        };
        const refuse = () => Promise.reject(new TypeError('Name is not allowed.'));
        directory.getFileHandle = function (name, options) {
            return allowed(name) ? held.getFileHandle.call(this, name, options) : refuse();
        };
        directory.removeEntry = function (name, options) {
            return allowed(name) ? held.removeEntry.call(this, name, options) : refuse();
        };
        if (typeof held.move === 'function') {
            file.move = function (...args) {
                const name = args[args.length - 1];
                return typeof name === 'string' && !allowed(name) ? refuse() : held.move.apply(this, args);
            };
        }
        return held;
    }"""
)
private external fun applyDiskNameRule(): JsHandle

@JsFun(
    """(held) => {
        const directory = FileSystemDirectoryHandle.prototype;
        const file = FileSystemFileHandle.prototype;
        directory.getFileHandle = held.getFileHandle;
        directory.removeEntry = held.removeEntry;
        if (held.ownMove) Object.defineProperty(file, 'move', held.ownMove); else delete file.move;
    }"""
)
private external fun restoreNames(held: JsHandle)

/** Runs [block] with the private file system refusing the names a folder on the disk refuses. */
internal suspend fun <T> withDiskNameRule(block: suspend () -> T): T {
    val held = applyDiskNameRule()
    try {
        return block()
    } finally {
        restoreNames(held)
    }
}

/**
 * Makes every file handle's `move` refuse, doing nothing, with a `DOMException` called [name], as
 * Chrome on Android does in a folder a phone picked, where a file cannot be renamed; returns what it
 * replaced, for [restoreRefusedMove].
 */
@JsFun(
    """(name) => {
        const file = FileSystemFileHandle.prototype;
        const held = { ownMove: Object.getOwnPropertyDescriptor(file, 'move') };
        file.move = function () { return Promise.reject(new DOMException('refused by the test', name)); };
        return held;
    }"""
)
private external fun refuseEveryMove(name: String): JsHandle

@JsFun(
    """(held) => {
        const file = FileSystemFileHandle.prototype;
        if (held.ownMove) Object.defineProperty(file, 'move', held.ownMove); else delete file.move;
    }"""
)
private external fun restoreRefusedMove(held: JsHandle)

/** Runs [block] with every `move` refused with a `DOMException` called [name]. */
internal suspend fun <T> withEveryMoveRefused(name: String, block: suspend () -> T): T {
    val held = refuseEveryMove(name)
    try {
        return block()
    } finally {
        restoreRefusedMove(held)
    }
}

/**
 * Takes `createWritable` off every file handle, returning it for [restoreCreateWritable]: a method
 * the browser lacks, which a call throws on before any promise is made.
 */
@JsFun(
    """() => {
        const file = FileSystemFileHandle.prototype;
        const held = Object.getOwnPropertyDescriptor(file, 'createWritable');
        delete file.createWritable;
        return held;
    }"""
)
internal external fun hideCreateWritable(): JsHandle

@JsFun("(held) => { Object.defineProperty(FileSystemFileHandle.prototype, 'createWritable', held); }")
internal external fun restoreCreateWritable(held: JsHandle)

/**
 * Fails unless [actual] is [expected], byte for byte, saying where they part. Not
 * `assertContentEquals`, whose message prints both arrays whole: a save's hundred and eighty
 * thousand numbers overran the test reporter between the browser and Gradle, which then lost the
 * results of the tests around it.
 */
internal fun assertSameBytes(expected: ByteArray, actual: ByteArray, message: String? = null) {
    if (expected.contentEquals(actual)) return
    val firstDifference = (0 until minOf(expected.size, actual.size)).firstOrNull { expected[it] != actual[it] }
        ?: minOf(expected.size, actual.size)
    kotlin.test.fail(
        (message?.let { "$it: " } ?: "") +
            "expected ${expected.size} bytes, found ${actual.size}, first differing at byte $firstDifference"
    )
}

/** A 32 world, made once for every test that needs one: it is the same world each time. */
internal object TestWorlds {
    private var small: WorldMap? = null

    suspend fun small(): WorldMap =
        small ?: WorldGenerationEngine.generate(WorldGenConfig(seed = 31L, width = 32, height = 32)).also { small = it }
}
