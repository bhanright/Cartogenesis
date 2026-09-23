package com.cartogenesis.desktop

import com.cartogenesis.cartography.RiverSelection
import com.cartogenesis.ui.AppSettings
import com.cartogenesis.ui.SettingsCodec
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The desktop's settings file under a burst of writes: the last one asked for is the one kept.
 *
 * The river density slider writes the preferences on every mark it passes, so a quick drag is a
 * burst of writes launched one after another, each of which used to go to the IO pool on its own
 * through one shared temporary file. Nothing ordered them: an older write could land last, and
 * two could interleave on the temporary file, so the mark stored was not always the mark set.
 * `SettingsTest` in `:ui` cannot see this, because its store is a fake held in memory; this runs
 * the real [FileSettings] against a real file in a temporary directory.
 */
class FileSettingsTest {

    private companion object {
        /** One drag of the slider across its whole scale and back, twice over. */
        const val WRITES = 40

        /**
         * How many bursts, each on a fresh file.
         *
         * A race either loses or it does not on a given run, so one burst proves little; twenty
         * bursts of forty writes lost on most runs of the unordered writer before it was fixed.
         */
        const val BURSTS = 20

        /**
         * Padding in the library folder's name, so each write takes long enough to overlap.
         *
         * A settings document is a few hundred bytes and writes in microseconds; sixty-four
         * kilobytes of it keeps each write on the disk long enough for the IO pool to run several
         * at once, which is what a loaded machine does to the real few hundred bytes.
         */
        const val PADDING_CHARACTERS = 64 * 1024
    }

    @Test
    fun `a burst of writes leaves the last one on disk`() {
        val directory = Files.createTempDirectory("cartogenesis-settings").toFile()
        try {
            repeat(BURSTS) { burst ->
                val file = File(directory, "settings-$burst.json")
                val store = FileSettings(file)
                val asked = (0 until WRITES).map { write ->
                    AppSettings(
                        riverInkStep = RiverSelection.INK_STEPS.elementAt(
                            write % RiverSelection.INK_STEPS.count()
                        ),
                        libraryFolder = "D:/worlds/$write/" + "x".repeat(PADDING_CHARACTERS)
                    )
                }
                // Launched in order from one thread, as the window launches them from its own:
                // each reaches the store before the next one starts.
                runBlocking {
                    asked.map { settings -> launch { store.write(SettingsCodec.encode(settings)) } }
                        .joinAll()
                }
                val stored = SettingsCodec.decode(runBlocking { store.read() })
                assertEquals(
                    asked.last().libraryFolder, stored.libraryFolder,
                    "burst $burst kept an earlier write, not the last one asked for"
                )
                assertEquals(asked.last().riverInkStep, stored.riverInkStep)
                assertFalse(
                    File(directory, file.name + ".tmp").exists(),
                    "burst $burst left its temporary file behind"
                )
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
