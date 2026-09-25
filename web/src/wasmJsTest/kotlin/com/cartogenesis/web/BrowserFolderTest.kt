package com.cartogenesis.web

import com.cartogenesis.ui.FolderPermission
import com.cartogenesis.ui.RememberedPlace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/**
 * The browser's side of choosing a folder that a test can reach without the picker: whether the
 * choice is offered at all, and whether the folder chosen survives the visit.
 */
class BrowserFolderTest {

    @Test
    fun `without showDirectoryPicker there is no folder choice and nothing else about the platform changes`() {
        // Firefox and Safari have no picker; this headless Chrome does, so it is taken away here.
        assertTrue(directoryPickerAvailable(), "this browser was expected to offer a folder picker")
        val withPicker = WebPlatform(accelerator = null, accelerationUnavailableBecause = "a test")
        assertNotNull(withPicker.folderChooser)

        val hidden = hideDirectoryPicker()
        try {
            assertTrue(!directoryPickerAvailable(), "the picker was not hidden")
            val without = WebPlatform(accelerator = null, accelerationUnavailableBecause = "a test")
            assertNull(without.folderChooser, "a folder was offered by a browser that cannot pick one")
            assertIs<IndexedDbLibrary>(without.library)
            assertEquals(withPicker.libraryLocation, without.libraryLocation)
            assertEquals(withPicker.supportsFileTransfer, without.supportsFileTransfer)
            assertSame(withPicker.compressor, without.compressor)
            assertEquals(withPicker.defaultResolution, without.defaultResolution)
            assertEquals(withPicker.settingsStore.location, without.settingsStore.location)
        } finally {
            restoreDirectoryPicker(hidden)
        }
        assertTrue(directoryPickerAvailable())
    }

    @Test
    fun `a chosen folder and the choice to leave it are kept across visits`() = runTest(timeout = 5.minutes) {
        withTestFolder("remembered") { folder ->
            val chooser = BrowserFolderChooser(WebGzipCompressor)
            val before = chooser.remembered()
            try {
                chooser.remember(RememberedPlace(BrowserLibraryFolder(folder.handle, WebGzipCompressor), inFolder = true))
                val kept = BrowserFolderChooser(WebGzipCompressor).remembered()
                val keptFolder = assertIs<BrowserLibraryFolder>(kept.folder)
                assertTrue(kept.inFolder)
                assertEquals(folder.name, keptFolder.name)
                assertTrue(sameEntry(folder.handle, keptFolder.handle), "a different folder came back")
                // The private file system's handles always have leave; a picked folder's would read prompt here.
                assertEquals(FolderPermission.GRANTED, keptFolder.permission())
                assertNull(keptFolder.unreachableBecause())

                chooser.remember(RememberedPlace(keptFolder, inFolder = false))
                val left = BrowserFolderChooser(WebGzipCompressor).remembered()
                assertEquals(false, left.inFolder, "the choice of this browser's storage was not kept")
                assertEquals(folder.name, left.folder?.name, "the folder left was forgotten")
            } finally {
                chooser.remember(before)
            }
        }
    }

    @Test
    fun `a folder removed from under the page is reported as gone`() = runTest(timeout = 5.minutes) {
        val folder = TestFolder.fresh("gone")
        val chosen = BrowserLibraryFolder(folder.handle, WebGzipCompressor)
        folder.remove()
        assertEquals("it is no longer where it was", chosen.unreachableBecause())
        val failure = runCatching { chosen.library.list() }.exceptionOrNull()
        assertIs<FolderException>(failure)
        assertEquals("the folder, or the file in it, is no longer there", failure.message)
    }

    @Test
    fun `the browser's permission answers are read as the interface's`() {
        assertEquals(FolderPermission.GRANTED, permissionOf("granted"))
        assertEquals(FolderPermission.PROMPT, permissionOf("prompt"))
        assertEquals(FolderPermission.DENIED, permissionOf("denied"))
        assertEquals(FolderPermission.DENIED, permissionOf(""))
    }
}

/** Takes `showDirectoryPicker` off the window, returning it to be put back. */
@JsFun(
    """() => {
        const held = window.showDirectoryPicker;
        window.showDirectoryPicker = undefined;
        return held;
    }"""
)
private external fun hideDirectoryPicker(): JsHandle

@JsFun("(held) => { window.showDirectoryPicker = held; }")
private external fun restoreDirectoryPicker(held: JsHandle)

@JsFun("(a, b) => a.isSameEntry(b)")
private external fun sameEntryPromise(a: JsHandle, b: JsHandle): JsHandle

@JsFun("(value) => value === true")
private external fun jsTrue(value: JsHandle?): Boolean

private suspend fun sameEntry(a: JsHandle, b: JsHandle): Boolean = jsTrue(awaitPromiseOrThrow(sameEntryPromise(a, b)))
