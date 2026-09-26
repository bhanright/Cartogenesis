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
 * One measured generation: a world [sizeCells] across made on [host] took about [about], and
 * [source] is where and when that was measured. The source is not shown to the reader; it is here
 * so the figure can be checked, and re-measured when the generator moves.
 */
internal class MeasuredGeneration(
    val sizeCells: Int,
    val host: GenerationHost,
    val about: String,
    val source: String
)

/**
 * The question a window opened at a large link asks before it makes anything.
 *
 * A link carries its size, and a browser honours it up to its ceiling, so a 2048 link opened on a
 * phone would otherwise start a minute and a half of paused page the moment it arrived, with no
 * chance to say no. So a link naming a size above the one this host starts a fresh window at,
 * [Platform.defaultResolution], is asked about first: make it at the link's size, or open it at the
 * default size with every other setting the link carries. A link at or below the default, or naming
 * no size at all, opens as it always has. The desktop, which opens a link only when one is handed to
 * it, asks by the same rule against its own default.
 */
internal object LargeLinks {

    /**
     * How long a world of each size takes, where it has been measured, and nowhere else: the
     * question gives a figure only from this table, never an estimate made on the spot, and says
     * plainly when there is none. `docs/PERFORMANCE.md` holds the measurements; each row names its
     * own. Only sizes above a host's default are listed, because only those are asked about.
     */
    val MEASURED: List<MeasuredGeneration> = listOf(
        MeasuredGeneration(
            2048, GenerationHost.DESKTOP_APP, "about a minute",
            "60.5 s: StageProfileTest, seed 42, on the processor, Ryzen 7 5700X, 2026-09-15 " +
                "(docs/PERFORMANCE.md, where generation time goes)"
        ),
        MeasuredGeneration(
            4096, GenerationHost.DESKTOP_APP, "about three minutes",
            "173.8 s: ExportAuditTest, seed 42, on the processor, Ryzen 7 5700X, 2026-09-12 " +
                "(docs/PERFORMANCE.md, what each export costs); taken when 2048 took 39.8 s, so " +
                "it is likely longer now"
        ),
        MeasuredGeneration(
            2048, GenerationHost.DESKTOP_BROWSER, "about four minutes",
            "Edge 153 on an RTX 3070 Ti, 2026-09-25 (docs/TODO.md, a 4096 world cannot be made " +
                "in a browser tab)"
        ),
        MeasuredGeneration(
            1024, GenerationHost.PHONE_BROWSER, "about twenty seconds",
            "18-23 s on a 2026 Qualcomm handset with WebGPU on (docs/PERFORMANCE.md, the " +
                "browser's one thread)"
        ),
        MeasuredGeneration(
            2048, GenerationHost.PHONE_BROWSER, "about a minute and a half",
            "92.7 s on a 2026 Qualcomm handset with WebGPU on (docs/PERFORMANCE.md, the " +
                "browser's one thread)"
        )
    )

    /** Whether [opening] names a world larger than [defaultSize], and so is asked about first. */
    fun asks(opening: LinkOpening, defaultSize: Int): Boolean =
        opening.generates && (opening.linkedSize ?: 0) > defaultSize

    /** The measured time for a world [size] cells across on [host], or null where none was measured. */
    fun measured(size: Int, host: GenerationHost): MeasuredGeneration? =
        MEASURED.firstOrNull { it.sizeCells == size && it.host == host }

    /**
     * The question's text for a link making a world [size] cells across where this host starts at
     * [defaultSize]: the size, the measured time on the nearest kind of machine worded as a
     * measurement made elsewhere, or a plain statement that there is none.
     */
    fun question(size: Int, defaultSize: Int, host: GenerationHost): String {
        val lead = "This link makes a $size world, larger than the $defaultSize this window starts at."
        val time = measured(size, host)?.let {
            "In ${host.words} that took ${it.about} when it was measured; this one may be " +
                "quicker or slower."
        } ?: "How long that takes in ${host.words} has not been measured."
        val pause = if (host.pausesWhileWorking) " The page may pause while it works." else ""
        return "$lead $time$pause"
    }

    /** The button that makes the world as the link names it. */
    fun makeItLabel(size: Int): String = "Make it at $size"

    /** The button that opens the link's world at this host's default size instead. */
    fun atDefaultLabel(defaultSize: Int): String = "Open at $defaultSize"
}
