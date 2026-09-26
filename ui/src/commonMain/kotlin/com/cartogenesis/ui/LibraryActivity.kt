package com.cartogenesis.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What the library is doing now and what it last did, for the library pane to show at its head.
 *
 * The status line is in the panel's header, and on a phone the header is inside the settings sheet,
 * which is down whenever the library is up: a save that failed there, or one still being written,
 * showed the reader nothing at all, and Save seemed to do nothing. So every operation on the
 * library — a save, an open, a delete, a copy — is begun here when it is asked for and ended here
 * with its outcome, and the pane draws both on every layout. The status line still says the same.
 *
 * Snapshot state, so the pane follows it without being told.
 */
class LibraryActivity {

    /** One operation under way: what it is [doing], and for a save, how many bytes have reached the library. */
    class Work internal constructor(val doing: String) {
        /** Null until the library reports a count, and for the operations that report none. */
        var bytesWritten: Long? by mutableStateOf(null)
            internal set
    }

    /** How an operation ended: the [line] said, and whether it [failed]. */
    data class Outcome(val line: String, val failed: Boolean)

    /** Every operation under way, oldest first. Identity, not equality: two saves of one title are two. */
    var underWay: List<Work> by mutableStateOf(emptyList())
        private set

    /** How the last operation to end ended, or null before any has. */
    var last: Outcome? by mutableStateOf(null)
        private set

    /** Starts showing [doing] as under way. Called when the reader asks, before anything suspends. */
    fun begin(doing: String): Work = Work(doing).also { underWay = underWay + it }

    /** Ends [work] with [line], a failure when [failed]. */
    fun end(work: Work, line: String, failed: Boolean) {
        underWay = underWay.filterNot { it === work }
        last = Outcome(line, failed)
    }

    /** Ends [work] saying nothing: it was cancelled with the window it belonged to. */
    fun drop(work: Work) {
        underWay = underWay.filterNot { it === work }
    }

    /** An outcome with no operation behind it, such as a Save refused before it began. */
    fun say(line: String, failed: Boolean) {
        last = Outcome(line, failed)
    }

    companion object {
        /**
         * Bytes in the megabyte a count is written in: the decimal one, which is what a phone's file
         * manager and a desktop's file browser outside Windows show beside the same file.
         */
        private const val BYTES_PER_MEGABYTE = 1_000_000L

        /** Tenths of a megabyte, the precision a count moving while a save is written is read at. */
        private const val TENTHS = 10L

        /** [bytes] as "3.4 MB", to a tenth, rounded to the nearest. */
        fun megabytes(bytes: Long): String {
            val tenths = (bytes * TENTHS + BYTES_PER_MEGABYTE / 2) / BYTES_PER_MEGABYTE
            return "${tenths / TENTHS}.${tenths % TENTHS} MB"
        }
    }
}
