package com.cartogenesis.ui

import java.io.File

/** To the file the JVM test task names; nowhere when run without it, from an IDE. */
internal actual fun appendToKnownFailuresReport(line: String) {
    val path = System.getProperty("cartogenesis.knownFailures") ?: return
    val file = File(path)
    file.parentFile?.mkdirs()
    file.appendText(line + "\n")
}
