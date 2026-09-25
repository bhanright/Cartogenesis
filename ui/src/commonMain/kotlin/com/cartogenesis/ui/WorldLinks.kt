package com.cartogenesis.ui

import com.cartogenesis.cartography.MapStyle
import com.cartogenesis.cartography.MapView
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.worldgen.model.Acceleration
import com.cartogenesis.worldgen.model.WorldGenConfig

/**
 * What the application found in the address it was opened at, and what it starts with because of
 * it: the settings, the drawing, the one line the status carries about the link, and whether the
 * world the link names is to be made straight away.
 *
 * With no link in the address [config] and [options] are the ones the caller handed in, [notice]
 * is null and [generates] is false, so a window opened without a link starts exactly as it did
 * before links existed: on a blank canvas.
 */
internal data class LinkOpening(
    val config: WorldGenConfig,
    val options: RenderOptions,
    /** What was set aside, brought down or refused, as one line; null when there is nothing to say. */
    val notice: String?,
    /** True when the address named a world this build can read, which is then made without a press of Generate. */
    val generates: Boolean
)

/**
 * A link that opens a world: the address a reader copies to hand a world to somebody else, and
 * what the browser application makes of one it is opened at.
 *
 * The seed is the one thing a reader might write by hand, so it stays in the query where it reads
 * as what it is: `/app/?seed=718106` opens that seed at the size and settings a fresh window would
 * use. Everything else a copied link carries goes after `#`, which a browser never sends to the
 * server, so nothing about a world a reader passes on reaches the host's logs: the format version,
 * the size, and every setting of the world and of the drawing that differs from its default,
 * written as `name=value` pairs in the order the panel draws them. For example
 * `https://cartogenesis.com/app/?seed=718106#v=1&size=1024&plates=18&style=vellum`.
 *
 * What a link never carries is anything that is not the recipe for the world: no saved world's
 * data, no name the reader typed, no labels, no realm or landmark edits, nothing from the library.
 * A link names a world by how to make it again, and those are things done to a world after it was
 * made. Two knobs are also left out, each for a reason given in [LEFT_OUT].
 *
 * The version is what lets a later build change the format without an older one misreading it: a
 * link whose version this build does not write is refused whole, with a line saying so, rather
 * than half-applied. Within a version, a part that cannot be read — a seed that is not a number, a
 * name no setting has, a value outside the range its control offers — is set aside with one line
 * of status naming it, and the rest of the link still applies, because a link with one bad pair in
 * it is still mostly a world somebody meant to show.
 *
 * Public because each front end's own tests hold the link its platform copies to its address; the
 * reading and the knob table stay internal.
 */
object WorldLinks {

    /**
     * The link format this build writes and reads.
     *
     * Moves when a pair's name or the meaning of its value moves, exactly as `WorldCodec`'s format
     * version does for a save. A link names a style or a view by its enum constant's name, so
     * renaming one of those is such a move; `WorldLinksTest` holds the names as they are written.
     */
    const val FORMAT_VERSION: Int = 1

    /**
     * Where a link copied from a host that is not itself a page points: the browser application
     * as it is published. The desktop copies this, so a link made there opens for anybody, with or
     * without the desktop application installed.
     */
    const val PUBLIC_APP_ADDRESS: String = "https://cartogenesis.com/app/"

    /** The query parameter the seed travels in, the one part of a link meant to be written by hand. */
    internal const val SEED_PARAMETER = "seed"

    internal const val VERSION_KEY = "v"
    internal const val SIZE_KEY = "size"
    internal const val STYLE_KEY = "style"
    internal const val VIEW_KEY = "view"

    /**
     * Marks a float written as its IEEE-754 bits in hexadecimal rather than as a decimal. See
     * [floatText] for the rare value that needs it.
     */
    private const val BITS_PREFIX = "x"

    private const val ON = "1"
    private const val OFF = "0"

    /**
     * Every knob a link carries, under the name it is written with, in the panel's order.
     *
     * Names rather than the knobs' labels, because a label is prose that is reworded ("Graphics
     * card" became "Graphics acceleration") and a link written before a rewording must still open.
     */
    internal val KEYED: List<Pair<String, Knob>> = listOf(
        "ocean" to Knobs.oceanCoverage,
        "plates" to Knobs.plates,
        "mountains" to Knobs.mountainHeight,
        "erosion" to Knobs.erosionStrength,
        "tilt" to Knobs.seasonalTiltDegrees,
        "rainshadow" to Knobs.rainShadow,
        "ice" to Knobs.ice,
        "rivers" to Knobs.rivers,
        "lakes" to Knobs.lakes,
        "drybasins" to Knobs.dryBasins,
        "realms" to Knobs.realms,
        "wilderness" to Knobs.wilderness,
        "borders" to Knobs.borders,
        "relief" to Knobs.hillshade,
        "lamp" to Knobs.singleLamp,
        "coast" to Knobs.coastline,
        "graticule" to Knobs.graticule,
        "places" to Knobs.landmarkCount,
        "landmarks" to Knobs.landmarks
    )

    /**
     * The knobs a link deliberately does not carry, each with the reason. `WorldLinksTest` holds
     * that every knob is in [KEYED] or here, so a knob added to the panel cannot silently fall out
     * of links.
     */
    internal val LEFT_OUT: Map<Knob, String> = mapOf(
        Knobs.graphicsAcceleration to
            "where the work runs is a fact about the machine opening the link, not about the world",
        Knobs.riverDensity to
            "how much river a map draws is the reader's own standing preference, kept with their " +
            "settings, and a link opened must not overwrite it"
    )

    /**
     * The link that makes [config] again, drawn as [options], at [base]: `base?seed=…#v=…&size=…`
     * with every keyed setting that differs from a fresh [WorldGenConfig] or [RenderOptions].
     *
     * [base] is a page address with no query or fragment of its own — see
     * [Platform.worldLinkBase]. The size is always written, even at a host's default, because the
     * two hosts' defaults differ and a link has to make the same world on either.
     */
    fun linkTo(base: String, config: WorldGenConfig, options: RenderOptions): String {
        val plainConfig = WorldGenConfig()
        val plainOptions = RenderOptions()
        val pairs = mutableListOf("$VERSION_KEY=$FORMAT_VERSION", "$SIZE_KEY=${config.width}")
        for ((key, knob) in KEYED) {
            val text = when (knob) {
                is Dial -> knob.read(config)
                    .takeIf { it.toRawBits() != knob.read(plainConfig).toRawBits() }
                    ?.let { floatText(it) }
                is Stepper -> knob.read(config).takeIf { it != knob.read(plainConfig) }?.toString()
                is Latch -> knob.read(config).takeIf { it != knob.read(plainConfig) }?.let { onOff(it) }
                is Mark -> knob.read(options).takeIf { it != knob.read(plainOptions) }?.let { onOff(it) }
                is Gauge -> error("no drawing dial is keyed; see LEFT_OUT")
            }
            if (text != null) pairs += "$key=$text"
        }
        if (options.style != plainOptions.style) pairs += "$STYLE_KEY=${wireName(options.style)}"
        if (options.view != plainOptions.view) pairs += "$VIEW_KEY=${wireName(options.view)}"
        return "$base?$SEED_PARAMETER=${config.seed}#${pairs.joinToString("&")}"
    }

    /**
     * Copies the link to the world on screen through [platform], and returns the status line that
     * says what was copied — or, where the host has no clipboard, the link itself to copy by hand.
     *
     * [config] and [options] are the world's own settings and the drawing on screen. [hasEdits] is
     * whether the reader has labelled the map or edited a realm or landmark, which the line then
     * says the link does not carry, so a reader who sends a link is not surprised by what arrives.
     * The line also says so when the link cannot make this world to the last bit: when it was made
     * on the graphics device, which rounds differently, or when it came from a save whose settings
     * include some no link can carry.
     */
    fun copy(
        platform: Platform,
        config: WorldGenConfig,
        options: RenderOptions,
        hasEdits: Boolean = false
    ): String {
        val link = linkTo(platform.worldLinkBase, config, options)
        val copied = platform.canCopyToClipboard
        if (copied) platform.copyToClipboard(link)
        val caveats = buildList {
            if (!reproduces(config, link)) {
                add(
                    "This world was opened from a save made with settings a link cannot carry, so " +
                        "the link makes the nearest world to it"
                )
            } else if (config.erosion.acceleration == Acceleration.GPU) {
                add(
                    "This world was made on the graphics device, so the link makes it again the " +
                        "same to the eye rather than to the last cell"
                )
            }
            if (hasEdits) add("Labels and edits stay here; the link carries the settings")
        }
        val lead =
            if (copied) "Copied a link to this world: $link"
            else "This host has no clipboard; the link to this world is $link"
        return (listOf(lead) + caveats).joinToString(". ") + "."
    }

    /**
     * Whether [link] makes [config] again, setting for setting: the whole config, not only the
     * keyed ones, with where the work runs set aside since a link never carries it.
     */
    internal fun reproduces(config: WorldGenConfig, link: String): Boolean {
        val opened = read(link, WorldGenConfig(), RenderOptions(), WorldCeilings.DESKTOP).config
        return onProcessor(opened) == onProcessor(config)
    }

    private fun onProcessor(config: WorldGenConfig): WorldGenConfig =
        config.copy(erosion = config.erosion.copy(acceleration = Acceleration.CPU))

    /**
     * What the application opened at [address] starts with.
     *
     * [starting] and [startingOptions] are what the window would start with without a link: a
     * fresh seed, the reader's own size and graphics preference, the reader's river density. Of
     * [starting] only those three are read: the world is the generator's default at the link's seed
     * and size — its size brought down to [ceiling], with the ceiling's reason, where it is above
     * it — or at the window's where the link names none, and each of the link's settings is
     * written through the same knob the panel writes it with, so a setting reaches the world by
     * exactly one path.
     *
     * An address with no seed and nothing after `#` is no link at all. One whose format version is
     * not [FORMAT_VERSION] is refused whole, and the window starts as it would have without it.
     * Never throws: whatever the address holds, the window opens.
     */
    internal fun read(
        address: String?,
        starting: WorldGenConfig,
        startingOptions: RenderOptions,
        ceiling: Int
    ): LinkOpening {
        val unchanged = LinkOpening(starting, startingOptions, notice = null, generates = false)
        if (address == null) return unchanged
        val beforeFragment = address.substringBefore('#')
        val fragment = if ('#' in address) address.substringAfter('#') else ""
        val query = if ('?' in beforeFragment) beforeFragment.substringAfter('?') else ""
        val seedText = pairsOf(query).lastOrNull { it.first == SEED_PARAMETER }?.second
        if (seedText == null && fragment.isEmpty()) return unchanged

        val pairs = pairsOf(fragment)
        val setAside = mutableListOf<String>()
        var fragmentApplies = fragment.isNotEmpty()
        if (fragmentApplies) {
            val version = pairs.lastOrNull { it.first == VERSION_KEY }?.second
            if (version == null) {
                setAside += "the part after # names no link format"
                fragmentApplies = false
            } else if (version.toIntOrNull() != FORMAT_VERSION) {
                return unchanged.copy(notice = refusal(version))
            }
        }

        var seed = starting.seed
        if (seedText != null) {
            val linked = seedText.toLongOrNull()
            if (linked == null) setAside += "the seed \"$seedText\" is not a whole number"
            else seed = linked
        }
        // Everything but the seed, the size and where the work runs is the generator's own
        // default, taken to the size the way the panel's chips take a world between sizes, rather
        // than whatever else [starting] may hold: the link then names the whole world, and a
        // window that started from an odd config cannot leak it into the one the link makes.
        var config = Knobs.graphicsAcceleration.set(
            Knobs.atResolution(WorldGenConfig(seed = seed), starting.width),
            Knobs.graphicsAcceleration.read(starting)
        )

        var options = startingOptions
        var sizeNotice: String? = null
        if (fragmentApplies) {
            val byKey = KEYED.toMap()
            for ((key, value) in pairs) {
                when (key) {
                    VERSION_KEY -> Unit
                    SIZE_KEY -> {
                        val size = value.toIntOrNull()
                        if (size == null || size !in Knobs.RESOLUTIONS) {
                            setAside += "$key=$value is not one of ${Knobs.RESOLUTIONS.joinToString()}"
                        } else {
                            val made = if (size <= ceiling) size
                            else Knobs.RESOLUTIONS.filter { it <= ceiling }.max()
                            if (made != size) {
                                sizeNotice = "The link asks for a $size world and this one is made " +
                                    "at $made. ${WorldCeilings.whyOutOfReach(size, ceiling)}."
                            }
                            config = Knobs.atResolution(config, made)
                        }
                    }
                    STYLE_KEY -> MapStyle.entries.firstOrNull { wireName(it) == value }
                        ?.let { options = MapChrome.withStyle(options, it) }
                        ?: run { setAside += "$key=$value is not a style" }
                    VIEW_KEY -> MapView.entries.firstOrNull { wireName(it) == value }
                        ?.let { options = MapChrome.withView(options, it) }
                        ?: run { setAside += "$key=$value is not a view" }
                    else -> {
                        val knob = byKey[key]
                        if (knob == null) {
                            setAside += "\"$key\" is not a setting a link carries"
                        } else {
                            val applied = apply(knob, value, config, options)
                            if (applied == null) setAside += "$key=$value ${outOfReach(knob)}"
                            else {
                                config = applied.first
                                options = applied.second
                            }
                        }
                    }
                }
            }
        }

        val setAsideLine = setAside.takeIf { it.isNotEmpty() }?.let {
            "Set aside from this link: ${it.joinToString("; ")}. The rest of it applies."
        }
        val notice = listOfNotNull(setAsideLine, sizeNotice).joinToString(" ").ifEmpty { null }
        return LinkOpening(config, options, notice, generates = true)
    }

    /**
     * [value] written through [knob], or null when it is not a value that knob's control offers:
     * not a number, not a whole number where a count is asked for, not 0 or 1 for a switch, or
     * outside the control's range. Set aside rather than clamped, because a clamped value is a
     * world the link's author did not ask for.
     */
    private fun apply(
        knob: Knob,
        value: String,
        config: WorldGenConfig,
        options: RenderOptions
    ): Pair<WorldGenConfig, RenderOptions>? = when (knob) {
        is Dial -> readFloat(value)
            ?.takeIf { it.isFinite() && it in knob.range }
            ?.let { knob.set(config, it) to options }
        is Stepper -> value.toIntOrNull()
            ?.takeIf { it in knob.range }
            ?.let { knob.set(config, it) to options }
        is Latch -> onOffOrNull(value)?.let { knob.set(config, it) to options }
        is Mark -> onOffOrNull(value)?.let { config to knob.set(options, it) }
        is Gauge -> null
    }

    /** What an unusable value was, in words, for the set-aside line. */
    private fun outOfReach(knob: Knob): String = when (knob) {
        is Dial -> "is outside ${floatText(knob.range.start)} to ${floatText(knob.range.endInclusive)}"
        is Stepper -> "is outside ${knob.range.first} to ${knob.range.last}"
        is Latch, is Mark -> "is neither 1 nor 0"
        is Gauge -> "is not carried"
    }

    private fun refusal(version: String): String {
        val number = version.toIntOrNull()
        return if (number != null && number > FORMAT_VERSION) {
            "This link was written by a later version of Cartogenesis (link format $number; this " +
                "version reads format $FORMAT_VERSION), so the world it names was not opened " +
                "rather than opened wrongly."
        } else {
            "This link's format \"$version\" is not one Cartogenesis writes, so the world it " +
                "names was not opened."
        }
    }

    /** `name=value` pairs split on `&`, in order; a pair with no `=` has an empty value. */
    private fun pairsOf(text: String): List<Pair<String, String>> =
        text.split('&').filter { it.isNotEmpty() }
            .map { it.substringBefore('=') to it.substringAfter('=', missingDelimiterValue = "") }

    private fun onOff(on: Boolean): String = if (on) ON else OFF

    private fun onOffOrNull(value: String): Boolean? = when (value) {
        ON -> true
        OFF -> false
        else -> null
    }

    /** A style's or a view's name in a link: its constant's name, lower case. */
    internal fun wireName(entry: Enum<*>): String = entry.name.lowercase()

    /** A plain decimal: digits, an optional fraction and an optional exponent, nothing else. */
    private val DECIMAL = Regex("-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?")

    private val BITS = Regex("$BITS_PREFIX[0-9a-f]{8}")

    /**
     * [value] as the decimal this platform writes for it, or as its bits where that decimal might
     * be read back as a neighbouring float on another platform.
     *
     * A link made on the desktop is read in a browser, and the two do not parse a decimal the same
     * way: the JVM rounds the decimal straight to the nearest float, while [readFloat] reads it as
     * a double and rounds that — the one route every platform shares. The two agree except where
     * the double lands exactly halfway between two floats, where rounding a second time can go the
     * other way. So a decimal whose double is such a midpoint, or that does not come back as
     * [value] at all, is not written; the bits are, which every platform reads alike. On the
     * values the dials make this is vanishingly rare, and `WorldLinksTest` walks the dials' ranges
     * on both platforms to hold it.
     */
    internal fun floatText(value: Float): String {
        val decimal = value.toString().removeSuffix(".0")
        return if (DECIMAL.matches(decimal) && readsBackExactly(decimal, value)) decimal
        else BITS_PREFIX + value.toRawBits().toUInt().toString(16).padStart(8, '0')
    }

    /** The float [text] names, written by [floatText] or by hand, or null if it names none. */
    internal fun readFloat(text: String): Float? = when {
        BITS.matches(text) -> Float.fromBits(text.drop(BITS_PREFIX.length).toUInt(16).toInt())
        DECIMAL.matches(text) -> text.toDoubleOrNull()?.toFloat()
        else -> null
    }

    /** Whether [decimal] reads back through [readFloat] as [value] on every platform. */
    internal fun readsBackExactly(decimal: String, value: Float): Boolean {
        val wide = decimal.toDoubleOrNull() ?: return false
        return wide.toFloat().toRawBits() == value.toRawBits() && !isHalfwayBetweenFloats(wide)
    }

    /**
     * Whether [wide] lies exactly halfway between two adjacent floats, where rounding it to a float
     * is a tie and a decimal that was not quite at the midpoint can round the wrong way.
     */
    internal fun isHalfwayBetweenFloats(wide: Double): Boolean {
        if (!wide.isFinite()) return false
        val nearest = wide.toFloat()
        val nearestWide = nearest.toDouble()
        if (nearestWide == wide || !nearest.isFinite()) return false
        val beyond = adjacentFloat(nearest, upward = wide > nearestWide)
        return wide - nearestWide == beyond.toDouble() - wide
    }

    /**
     * The float next to [value] above it or below it. By its bits, because `nextUp` and `nextDown`
     * exist for a float on the JVM only: adjacent floats of one sign have adjacent bit patterns,
     * counting away from zero.
     */
    private fun adjacentFloat(value: Float, upward: Boolean): Float {
        if (value == 0f) return if (upward) Float.MIN_VALUE else -Float.MIN_VALUE
        val awayFromZero = upward == (value > 0f)
        return Float.fromBits(value.toRawBits() + if (awayFromZero) 1 else -1)
    }
}
