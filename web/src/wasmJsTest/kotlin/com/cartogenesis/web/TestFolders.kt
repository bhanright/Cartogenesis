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
