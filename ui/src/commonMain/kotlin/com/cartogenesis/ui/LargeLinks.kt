package com.cartogenesis.ui

/**
 * Where a world is made, as far as how long it takes is concerned: the three kinds of machine a
 * generation time has been measured on.
 *
 * A browser on a phone and a browser on a desktop are the same front end and differ by minutes, so
 * the window's arrangement tells them apart: a browser drawn in the compact arrangement is taken to
 * be a phone. That is the arrangement's question, not the device's — a desktop browser dragged
 * narrow is told the phone's figure — which is why the prompt words every figure as a measurement
 * made elsewhere rather than as a promise about this machine.
 */
internal enum class GenerationHost(
    /** Where the measurement was made, as the prompt says it: "In $words that took …". */
    val words: String,
    /** Whether the page stops answering while it works, which a browser's one thread does. */
    val pausesWhileWorking: Boolean
) {
    DESKTOP_APP("the desktop application", pausesWhileWorking = false),
    DESKTOP_BROWSER("a browser on a desktop", pausesWhileWorking = true),
    PHONE_BROWSER("a browser on a phone", pausesWhileWorking = true);

    companion object {
        /**
         * The word [Platform.hostName] answers with in a browser. The bug form's vocabulary, and the
         * only statement of which front end this is that the platform makes.
         */
        private const val BROWSER_HOST_NAME = "Browser"

        fun of(platform: Platform, compact: Boolean): GenerationHost = when {
            platform.hostName != BROWSER_HOST_NAME -> DESKTOP_APP
            compact -> PHONE_BROWSER
            else -> DESKTOP_BROWSER
        }
    }
}

/**
 * One timed generation: a world of the size named [sizeRows] made on [host] took about [about],
 * and [source] is where and when it was timed. The source is not shown to the reader; it is here
 * so the figure can be checked, and timed again when the generator moves.
 *
 * [estimated] is true where the figure was not timed on [host] at all but worked out from another
 * host's timing, which [source] then states; the reader is told so in the same sentence.
 */
internal class MeasuredGeneration(
    val sizeRows: Int,
    val host: GenerationHost,
    val about: String,
    val source: String,
    val estimated: Boolean = false
)

/**
 * The question a window opened at a large link asks before it makes anything.
 *
 * A link carries its size, and a browser honours it up to its ceiling, so a 1024 link opened on a
 * phone would otherwise start most of a minute of paused page the moment it arrived, with no
 * chance to say no. So a link naming a size above the one this host starts a fresh window at,
 * [Platform.defaultResolution], is asked about first: make it at the link's size, or open it at the
 * default size with every other setting the link carries. A link at or below the default, or naming
 * no size at all, opens as it always has. The desktop, which opens a link only when one is handed to
 * it, asks by the same rule against its own default.
 */
internal object LargeLinks {

    /**
     * How long a world of each size takes, where it has been timed, and nowhere else: the question
     * gives a figure only from this table, never an estimate made on the spot, and says plainly
     * when there is none. Each row names its own timing. Only sizes above a host's default and
     * within its ceiling are listed, because only those are asked about.
     */
    val MEASURED: List<MeasuredGeneration> = listOf(
        MeasuredGeneration(
            2048, GenerationHost.DESKTOP_APP, "about three and a half minutes",
            "210.8 s: seed 42 at 2048 rows, on the processor, Ryzen 7 5700X, 12 GB heap, " +
                "2026-09-28 (docs/DESIGN_LEDGER.md, Q5)"
        ),
        MeasuredGeneration(
            1024, GenerationHost.DESKTOP_BROWSER, "about three minutes",
            "171.6 s, generated and drawn: seed 42 at 1024 rows, the production build in a " +
                "Chrome 152 tab, graphics acceleration off, Ryzen 7 5700X, 2026-09-28 " +
                "(docs/DESIGN_LEDGER.md, Q5)"
        ),
        MeasuredGeneration(
            1024, GenerationHost.PHONE_BROWSER, "about four minutes",
            "estimated from the desktop tab's 171.6 s, a phone's core taken as up to half again " +
                "slower; the stage that dominates, the incision, has no graphics-card path, so " +
                "the phone's WebGPU does not shorten it. Not measured on a phone (docs/TODO.md)",
            estimated = true
        )
    )

    /** Whether [opening] names a world larger than [defaultSize], and so is asked about first. */
    fun asks(opening: LinkOpening, defaultSize: Int): Boolean =
        opening.generates && (opening.linkedSize ?: 0) > defaultSize

    /** The timing for a world of the size named [size] on [host], or null where there is none. */
    fun measured(size: Int, host: GenerationHost): MeasuredGeneration? =
        MEASURED.firstOrNull { it.sizeRows == size && it.host == host }

    /**
     * The question's text for a link making a world of the size named [size] where this host
     * starts at [defaultSize]: the size, the time on the nearest kind of machine worded as a
     * measurement made elsewhere, or as an estimate where it is one, or a plain statement that
     * there is none.
     */
    fun question(size: Int, defaultSize: Int, host: GenerationHost): String {
        val lead = "This link makes a $size world, larger than the $defaultSize this window starts at."
        val time = measured(size, host)?.let {
            if (it.estimated) {
                "In ${host.words} that is expected to take ${it.about}, an estimate that has not " +
                    "been measured there; this one may be quicker or slower."
            } else {
                "In ${host.words} that took ${it.about} when it was measured; this one may be " +
                    "quicker or slower."
            }
        } ?: "How long that takes in ${host.words} has not been measured."
        val pause = if (host.pausesWhileWorking) " The page may pause while it works." else ""
        return "$lead $time$pause"
    }

    /**
     * The phone's small print under the size chips: what each size above its starting one costs
     * on a phone, from [MEASURED], each worded as timed or as estimated, and that the page may
     * pause, since a browser works on its one thread.
     */
    fun phoneCostLine(): String {
        val costs = MEASURED.filter { it.host == GenerationHost.PHONE_BROWSER }
            .sortedBy { it.sizeRows }
            .joinToString(" and ") {
                if (it.estimated) "${it.sizeRows} is expected to take ${it.about}"
                else "${it.sizeRows} takes ${it.about}"
            }
        return "On a phone, $costs; the screen may pause while it works."
    }

    /** The button that makes the world as the link names it. */
    fun makeItLabel(size: Int): String = "Make it at $size"

    /** The button that opens the link's world at this host's default size instead. */
    fun atDefaultLabel(defaultSize: Int): String = "Open at $defaultSize"
}
