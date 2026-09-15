package com.cartogenesis.ui

import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.roundToInt

/**
 * A bug report written from the world in the window, in the two forms it is handed over in.
 *
 * A report of a generated world is worthless without the numbers that make it again, and asking a
 * reader to copy the seed, the grid and every setting they moved is asking for a report that is
 * wrong in one of them. So the application reads them off the world itself: [Report.text] is the
 * whole report as prose, which goes on the clipboard, and [Report.url] is GitHub's issue form with
 * the same facts already in its fields. The clipboard copy is the one that always works — a reader
 * who lands on a login page, or who has no account at all and writes to [ADDRESS] instead, still
 * has everything.
 *
 * Everything here is pure, so `BugReportTest` can build a report for a known world and read it
 * back without a window, a clipboard or a network.
 */
internal object BugReport {

    /** The form the URL asks GitHub to open: `.github/ISSUE_TEMPLATE/bug.yml`. */
    const val TEMPLATE = "bug.yml"

    /** Where a reader with no GitHub account sends the same text. */
    const val ADDRESS = "bugreport@cartogenesis.com"

    const val NEW_ISSUE_URL = "https://github.com/bhanright/Cartogenesis/issues/new"

    /**
     * How long the opened URL may be, in characters.
     *
     * Not a browser limit: Chrome's own is about 2 MB and every other engine's is far above
     * anything this could build. 8,000 is the smallest limit in the chain — it is what a good many
     * proxies and corporate gateways cut a request line at, and a URL cut in half arrives at GitHub
     * as a 414 or as a form with half a field in it. Only the list of changed settings can grow
     * without bound, so that is what is trimmed to fit (see [Report.url]); the clipboard text is
     * never trimmed, and the URL says so where it has been.
     */
    const val MAX_URL_CHARACTERS = 8_000

    /**
     * The field ids in `.github/ISSUE_TEMPLATE/bug.yml`.
     *
     * GitHub's issue forms are pre-filled by query parameters named after a field's own id, and a
     * parameter naming a field that does not exist is dropped without a word — the form opens
     * looking perfectly normal and empty. So the ids are spelled once, here, beside the URL that
     * uses them, and a rename of one in the yaml is a rename of one line in this file.
     */
    private const val VERSION_FIELD = "version"
    private const val PLATFORM_FIELD = "platform"
    private const val SEED_FIELD = "seed"
    private const val RESOLUTION_FIELD = "resolution"
    private const val OCEAN_FIELD = "ocean"
    private const val KNOBS_FIELD = "knobs"
    private const val ACCELERATION_FIELD = "acceleration"

    /**
     * What the report says where no setting has been touched.
     *
     * A word rather than an empty field: a blank line reads as a question nobody answered, and the
     * answer here — that the world is the one the application makes when it is left alone — is one
     * of the more useful things a report can say.
     */
    const val NOTHING_CHANGED = "None"

    /**
     * Everything the form asks for, read off the world in the window.
     *
     * [changedSettings] is one entry per knob that is not at its default, already written as the
     * reader sees it ("Plates 18"), in the panel's own order. [acceleration] is a sentence rather
     * than a flag because the device matters as much as the switch does — see [accelerationLine].
     */
    class Report(
        val version: String,
        val host: String,
        val world: String,
        val seed: Long,
        val cellsAcross: Int,
        val cellsDown: Int,
        val oceanShareOfWorld: Float,
        val acceleration: String,
        val changedSettings: List<String>
    ) {

        /** The grid the world was generated at, as the cartouche writes it. */
        val resolution: String get() = "$cellsAcross × $cellsDown"

        /** Ocean coverage as the panel shows it, which is a whole percentage. */
        val ocean: String get() = "${(oceanShareOfWorld * 100).roundToInt()}%"

        /**
         * The whole report, which is what goes on the clipboard.
         *
         * The last two lines are empty prompts on purpose. This text is what a reader without a
         * GitHub account pastes into a mail, and a mail carrying only the machine's half of the
         * story is a report nobody can act on.
         */
        val text: String
            get() = buildString {
                appendLine("Cartogenesis $version")
                appendLine("Platform: $host")
                appendLine("World: $world")
                appendLine("Seed: $seed")
                appendLine("Generation resolution: $resolution")
                appendLine("Ocean coverage: $ocean")
                appendLine("Graphics acceleration: $acceleration")
                appendLine("Settings changed from their defaults: ${settingsLine(changedSettings)}")
                appendLine()
                appendLine("What I expected:")
                appendLine("What happened:")
            }

        /** What the issue is called before anybody has written a word of it. */
        val title: String get() = "Bug: seed $seed at $cellsAcross"

        /**
         * The same facts as a new-issue URL, trimmed to [MAX_URL_CHARACTERS].
         *
         * Only the settings list can be long — twenty knobs with their values — so that is the
         * part that gives way, one entry at a time from the end, and what is left says how many
         * went and where the whole list is. Nothing else is dropped: a report that lost its seed
         * to a length limit would be a report of nothing.
         */
        val url: String
            get() {
                var kept = changedSettings
                while (true) {
                    val candidate = urlWith(kept)
                    if (candidate.length <= MAX_URL_CHARACTERS || kept.isEmpty()) return candidate
                    kept = kept.dropLast(1)
                }
            }

        private fun urlWith(kept: List<String>): String {
            val settings =
                if (kept.size == changedSettings.size) settingsLine(changedSettings)
                else settingsLine(
                    kept + "and ${changedSettings.size - kept.size} more, on the clipboard"
                )
            val fields = listOf(
                "template" to TEMPLATE,
                "title" to title,
                VERSION_FIELD to version,
                PLATFORM_FIELD to host,
                SEED_FIELD to seed.toString(),
                RESOLUTION_FIELD to resolution,
                OCEAN_FIELD to ocean,
                KNOBS_FIELD to settings,
                ACCELERATION_FIELD to acceleration
            )
            return NEW_ISSUE_URL + "?" +
                fields.joinToString("&") { (name, value) -> "$name=${encode(value)}" }
        }
    }

    /**
     * Reads a report off [config] and [options], as the world in the window stands.
     *
     * [host] is the front end's own word for itself — see [Platform.hostName] — and is one of the
     * words the form's dropdown offers, so that it arrives selected rather than as a string GitHub
     * cannot match. [acceleration] is the line [accelerationLine] writes from the switch and the
     * device.
     */
    fun of(
        version: String,
        host: String,
        world: String,
        config: WorldGenConfig,
        options: RenderOptions,
        acceleration: String
    ): Report = Report(
        version = version,
        host = host,
        world = world,
        seed = config.seed,
        cellsAcross = config.width,
        cellsDown = config.height,
        oceanShareOfWorld = config.seaLevel,
        acceleration = acceleration,
        changedSettings = changedFromDefaults(config, options)
    )

    /**
     * The acceleration line: whether the work ran on the graphics device, and which device it is.
     *
     * Both halves are needed and neither stands on its own. "Off" on a machine that has a device
     * is a different report from "Off" on one that has none — the first says the reader chose the
     * processor, the second says there was no choice — and "On" without the device's name cannot
     * be matched against a driver's bug.
     */
    fun accelerationLine(on: Boolean, device: String?): String = when {
        on && device != null -> "On, $device"
        on -> "On"
        device != null -> "Off. Available here: $device"
        else -> "Off. No graphics device here."
    }

    /**
     * Every knob that is not where the application left it, with its value, in the panel's order.
     *
     * The defaults are read from a fresh [WorldGenConfig] and [RenderOptions] rather than listed
     * here, so a default that moves in the generator moves here with it. Resolution is not
     * consulted: `atResolution` scales the settings measured in cells, and no knob reads one of
     * those, so a world at 2048 and the same world at 512 report the same list.
     *
     * Graphics acceleration is a knob like the others and is deliberately not in this list: it has
     * a line of its own, with the device beside it, and a report that named it twice would be
     * inviting the reader to wonder which of the two was the truth.
     */
    fun changedFromDefaults(config: WorldGenConfig, options: RenderOptions): List<String> {
        val plainConfig = WorldGenConfig()
        val plainOptions = RenderOptions()
        return Knobs.all.filter { it !== Knobs.graphicsAcceleration }.mapNotNull { knob ->
            when (knob) {
                is Dial -> knob.read(config).takeIf { it != knob.read(plainConfig) }
                    ?.let { "${knob.label} ${knob.show(it)}" }

                is Stepper -> knob.read(config).takeIf { it != knob.read(plainConfig) }
                    ?.let { "${knob.label} $it" }

                is Latch -> knob.read(config).takeIf { it != knob.read(plainConfig) }
                    ?.let { "${knob.label} ${onOrOff(it)}" }

                is Mark -> knob.read(options).takeIf { it != knob.read(plainOptions) }
                    ?.let { "${knob.label} ${onOrOff(it)}" }
            }
        }
    }

    private fun onOrOff(on: Boolean): String = if (on) "on" else "off"

    /** The settings list as one line, or [NOTHING_CHANGED] where there is nothing in it. */
    private fun settingsLine(settings: List<String>): String =
        if (settings.isEmpty()) NOTHING_CHANGED else settings.joinToString("; ")

    private const val HEX = "0123456789ABCDEF"

    /**
     * Percent-encodes one query parameter's value, RFC 3986's unreserved set left alone.
     *
     * Written here rather than taken from a platform: there is no such function in common code,
     * and the two front ends would otherwise encode a seed differently on a bad day. The bytes are
     * UTF-8, which is what a `×` in the resolution and anything a reader typed into a world's name
     * need.
     */
    private fun encode(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val code = byte.toInt() and 0xFF
            val char = code.toChar()
            val unreserved = char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
                char == '-' || char == '.' || char == '_' || char == '~'
            if (unreserved) {
                append(char)
            } else {
                append('%')
                append(HEX[code shr 4])
                append(HEX[code and 0xF])
            }
        }
    }
}
