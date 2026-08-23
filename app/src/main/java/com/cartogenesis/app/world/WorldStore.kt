package com.cartogenesis.app.world

import android.content.Context
import com.cartogenesis.cartography.TextWorldLibrary
import java.io.File

/**
 * Saved worlds in the app's private storage.
 *
 * Everything about the format lives in `:cartography`, so Android and desktop write files that
 * open in either. All this supplies is where the bytes go.
 */
class WorldStore(context: Context) : TextWorldLibrary() {

    private val directory = File(context.filesDir, "worlds").apply { mkdirs() }

    override fun names(): List<String> =
        directory.listFiles { file -> file.extension == "json" }?.map { it.name } ?: emptyList()

    override fun read(name: String): String? =
        File(directory, name).takeIf { it.exists() }?.readText()

    override fun write(name: String, text: String) {
        File(directory, name).writeText(text)
    }

    override fun remove(name: String) {
        File(directory, name).delete()
    }
}
