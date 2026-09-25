package com.cartogenesis.ui

import com.cartogenesis.cartography.MapStyle
import com.cartogenesis.cartography.MapView
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.cartography.RiverSelection
import com.cartogenesis.worldgen.model.Acceleration
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A link to a world, written and read back: that it names the world it was made from, that a seed
 * alone opens that seed, that a part it cannot use is set aside and said, and that a link from a
 * later format is refused rather than misread.
 *
 * Common code, so every case runs on the JVM, which is where the desktop writes links, and in a
 * browser, which is where they are opened. The case that matters most across the two is the float
 * one: a dial's value written by one and read by the other has to come back to the last bit.
 */
class WorldLinksTest {

    private val base = WorldLinks.PUBLIC_APP_ADDRESS

    /** What a browser window would start with without a link: another seed, its own size. */
    private val starting: WorldGenConfig = WorldGenConfig(seed = 5L, width = 512, height = 512)

    /** A reader whose river density is not the default, so a link can be seen to leave it alone. */
    private val startingOptions = RenderOptions(riverInkStep = RiverSelection.EARTH_DENSITY_STEP + 1)

    private fun read(address: String, ceiling: Int = WorldCeilings.DESKTOP): LinkOpening =
        WorldLinks.read(address, starting, startingOptions, ceiling)

    /** Every keyed knob away from its default, the style and the view too, at 2048. */
    private fun everythingChanged(): Pair<WorldGenConfig, RenderOptions> {
        var config = WorldGenConfig(seed = 718106L).atResolution(2048, 2048)
        config = Knobs.oceanCoverage.set(config, 0.4137f)
        config = Knobs.plates.set(config, 23)
        config = Knobs.mountainHeight.set(config, 0.7771f)
        config = Knobs.erosionStrength.set(config, 1.337e-6f)
        config = Knobs.seasonalTiltDegrees.set(config, 3.25f)
        config = Knobs.rainShadow.set(config, 4.1f)
        config = Knobs.ice.set(config, !Knobs.ice.read(WorldGenConfig()))
        config = Knobs.dryBasins.set(config, !Knobs.dryBasins.read(WorldGenConfig()))
        config = Knobs.realms.set(config, 0)
        config = Knobs.wilderness.set(config, !Knobs.wilderness.read(WorldGenConfig()))
        config = Knobs.landmarkCount.set(config, 137f)
        var options = RenderOptions()
        WorldLinks.KEYED.map { it.second }.filterIsInstance<Mark>().forEach { mark ->
            options = mark.set(options, !mark.read(RenderOptions()))
        }
        options = options.copy(style = MapStyle.PEN_AND_INK, view = MapView.SUMMER_RAINFALL)
        return config to options
    }

    // ---- written and read back ---------------------------------------------------------------

    @Test
    fun `a default world's link reads back as the default world, and says nothing else`() {
        val config = WorldGenConfig(seed = 718106L, width = 512, height = 512)
        val link = WorldLinks.linkTo(base, config, RenderOptions())
        println("WORLD LINK default world: $link")
        assertEquals("${base}?seed=718106#v=1&size=512", link)

        val opened = read(link)
        assertEquals(config, opened.config)
        // The drawing is the reader's own, river density included: the link changed none of it.
        assertEquals(startingOptions, opened.options)
        assertNull(opened.notice)
        assertTrue(opened.generates)
    }

    @Test
    fun `a seed alone opens that seed at the size and settings a fresh window starts with`() {
        val opened = read("${base}?seed=718106")
        assertEquals(starting.copy(seed = 718106L), opened.config)
        assertEquals(startingOptions, opened.options)
        assertNull(opened.notice, "a seed alone has nothing to set aside")
        assertTrue(opened.generates, "a seed alone is a world asked for, so it is made on arrival")

        // Negative seeds are seeds too: the seed field takes a minus sign.
        assertEquals(-42L, read("${base}?seed=-42").config.seed)
    }

    @Test
    fun `a world with every setting changed reads back setting for setting`() {
        val (config, options) = everythingChanged()
        val link = WorldLinks.linkTo(base, config, options)
        println("WORLD LINK every setting changed (${link.length} characters): $link")

        WorldLinks.KEYED.forEach { (name, _) ->
            assertTrue("&$name=" in link, "the world has $name changed and the link does not carry it: $link")
        }

        val opened = read(link)
        assertEquals(config, opened.config, "the link did not make the same settings")
        // Everything but the river density, which is the reader's and stays theirs.
        assertEquals(options.copy(riverInkStep = startingOptions.riverInkStep), opened.options)
        assertNull(opened.notice, "a link the application wrote had a part it could not read: ${opened.notice}")
        assertTrue(WorldLinks.reproduces(config, link))
    }

    @Test
    fun `where the work runs travels with the machine, not with the link`() {
        val onDevice = Knobs.graphicsAcceleration.set(WorldGenConfig(seed = 9L), true)
        val link = WorldLinks.linkTo(base, onDevice, RenderOptions())
        assertEquals(Acceleration.CPU, read(link).config.erosion.acceleration)
        val machineWithDevice = Knobs.graphicsAcceleration.set(starting, true)
        val opened = WorldLinks.read(link, machineWithDevice, RenderOptions(), WorldCeilings.DESKTOP)
        assertEquals(Acceleration.GPU, opened.config.erosion.acceleration)
    }

    @Test
    fun `a link written by a later format is refused whole, and the window starts as it would have`() {
        val later = read("${base}?seed=718106#v=2&plates=18")
        assertEquals(starting, later.config, "a refused link still changed the settings")
        assertEquals(startingOptions, later.options)
        assertFalse(later.generates, "a refused link still started a world")
        val notice = assertNotNull(later.notice)
        assertTrue("later version" in notice && "format 2" in notice, notice)

        val nonsense = read("${base}?seed=718106#v=one&plates=18")
        assertEquals(starting, nonsense.config)
        assertFalse(nonsense.generates)
        assertTrue("\"one\"" in assertNotNull(nonsense.notice))
    }

    // ---- what a link cannot use --------------------------------------------------------------

    @Test
    fun `a malformed seed, an unknown setting and an out-of-range value are set aside and the rest applies`() {
        val opened = read("${base}?seed=12x#v=1&size=1024&plates=99&glaciers=1&tilt=-3&realms=7&style=sepia")
        val notice = assertNotNull(opened.notice)
        println("WORLD LINK set aside: $notice")
        listOf("\"12x\"", "plates=99", "3 to 40", "\"glaciers\"", "tilt=-3", "style=sepia").forEach {
            assertTrue(it in notice, "the line does not name $it: $notice")
        }
        // The seed the window already had, and every part of the link that could be read.
        assertEquals(starting.seed, opened.config.seed)
        assertEquals(1024, opened.config.width)
        assertEquals(7, Knobs.realms.read(opened.config))
        assertEquals(Knobs.plates.read(starting), Knobs.plates.read(opened.config))
        assertEquals(Knobs.seasonalTiltDegrees.read(starting), Knobs.seasonalTiltDegrees.read(opened.config))
        assertTrue(opened.generates)

        // A switch is 1 or 0, a size is one the panel offers, a view is one the map has.
        val more = read("${base}?seed=3#v=1&ice=yes&size=1000&view=satellite&ocean=0.5")
        val moreNotice = assertNotNull(more.notice)
        listOf("ice=yes", "size=1000", "view=satellite").forEach {
            assertTrue(it in moreNotice, "the line does not name $it: $moreNotice")
        }
        assertEquals(0.5f, Knobs.oceanCoverage.read(more.config))
    }

    @Test
    fun `a fragment with no format version is set aside, and the seed still opens`() {
        val opened = read("${base}?seed=718106#plates=18")
        assertEquals(718106L, opened.config.seed)
        assertEquals(Knobs.plates.read(starting), Knobs.plates.read(opened.config))
        assertTrue("no link format" in assertNotNull(opened.notice))
        assertTrue(opened.generates)
    }

    @Test
    fun `a size above the host's ceiling is brought down to it, with the ceiling's reason`() {
        val opened = read("${base}?seed=718106#v=1&size=4096", ceiling = WorldCeilings.BROWSER_TAB)
        assertEquals(2048, opened.config.width)
        assertEquals(2048, opened.config.height)
        val notice = assertNotNull(opened.notice)
        val reason = assertNotNull(WorldCeilings.whyOutOfReach(4096, WorldCeilings.BROWSER_TAB))
        assertTrue(reason in notice && "4096" in notice && "2048" in notice, notice)
        // Brought down with atResolution, as the panel's chips do, so the cell-measured widths
        // follow the grid rather than being left at the 512 values.
        assertEquals(starting.atResolution(2048, 2048).tectonics, opened.config.tectonics)
    }

    @Test
    fun `an address with no seed and nothing after the hash is no link`() {
        listOf(null, base, "${base}?selftest", "${base}#", "${base}?foldertest#").forEach { address ->
            val opened = WorldLinks.read(address, starting, startingOptions, WorldCeilings.DESKTOP)
            assertEquals(LinkOpening(starting, startingOptions, notice = null, generates = false), opened, "$address")
        }
    }

    // ---- what the link is made of ------------------------------------------------------------

    /**
     * Every knob is carried or named as left out with its reason, and no name is used twice. A knob
     * added to the panel and forgotten here would be a setting a link silently drops.
     */
    @Test
    fun `every knob is carried by a link or left out by name`() {
        val keyed = WorldLinks.KEYED.map { it.second }
        val left = WorldLinks.LEFT_OUT.keys
        Knobs.all.forEach { knob ->
            assertTrue(knob in keyed != knob in left, "${knob.label} is ${if (knob in keyed) "both keyed and left out" else "neither keyed nor left out"}")
        }
        val names = WorldLinks.KEYED.map { it.first } +
            listOf(WorldLinks.VERSION_KEY, WorldLinks.SIZE_KEY, WorldLinks.STYLE_KEY, WorldLinks.VIEW_KEY)
        assertEquals(names.size, names.toSet().size, "two parts of a link share a name")
        assertEquals(
            Knobs.all.filter { it in keyed },
            keyed,
            "the link does not write its settings in the panel's order"
        )
    }

    /**
     * A style or a view is named by its constant; these are wire names now, and renaming one breaks
     * every link that used it. If one has to move, [WorldLinks.FORMAT_VERSION] moves with it.
     */
    @Test
    fun `the styles and views are named in links as they were when the format was fixed`() {
        assertEquals(
            listOf(
                "atlas", "vellum", "ink_wash", "nautical", "midnight", "schoolroom", "verdant",
                "scroll", "pen_and_ink", "mars", "natural", "clear"
            ),
            MapStyle.entries.map { WorldLinks.wireName(it) }
        )
        assertEquals(
            listOf(
                "fantasy", "political", "cultures", "elevation", "biomes", "temperature",
                "summer_temperature", "winter_temperature", "rainfall", "summer_rainfall",
                "winter_rainfall", "plates", "currents", "wind", "normals"
            ),
            MapView.entries.map { WorldLinks.wireName(it) }
        )
        assertEquals(1, WorldLinks.FORMAT_VERSION)
    }

    // ---- floats, to the last bit, on both platforms ------------------------------------------

    /**
     * Every dial, walked across its range with values a slider could leave behind, comes back from
     * its written form to the last bit. Run on the JVM and in the browser, so each platform reads
     * its own writing; the fixed strings below are what the other platform's writing looks like.
     */
    @Test
    fun `a dial's value comes back to the last bit`() {
        val random = Random(20260925)
        val dials = Knobs.all.filterIsInstance<Dial>()
        var asBits = 0
        dials.forEach { dial ->
            repeat(SAMPLES_PER_DIAL) {
                val fraction = random.nextFloat()
                val value = dial.range.start + (dial.range.endInclusive - dial.range.start) * fraction
                val text = WorldLinks.floatText(value)
                if (text.startsWith("x")) asBits++
                val back = assertNotNull(WorldLinks.readFloat(text), "$text reads as nothing")
                assertEquals(value.toRawBits(), back.toRawBits(), "${dial.label} $value was written $text and read $back")
            }
        }
        println("WORLD LINK ${dials.size * SAMPLES_PER_DIAL} dial values round-tripped, $asBits of them as bits")

        // The JVM's spellings, read wherever this runs.
        assertEquals(0.62f.toRawBits(), WorldLinks.readFloat("0.62")?.toRawBits())
        assertEquals(2.0e-7f.toRawBits(), WorldLinks.readFloat("2.0E-7")?.toRawBits())
        assertEquals(1.337e-6f.toRawBits(), WorldLinks.readFloat("1.337E-6")?.toRawBits())
        assertEquals(137f.toRawBits(), WorldLinks.readFloat("137")?.toRawBits())
        assertEquals(0x3f1eb852, WorldLinks.readFloat("x3f1eb852")?.toRawBits())
        listOf("", "0x1p3", "1.0f", "NaN", "Infinity", "1e", ".5", "x3f1e").forEach {
            assertNull(WorldLinks.readFloat(it), "\"$it\" read as a number")
        }
    }

    /**
     * The one case where reading a decimal through a double can land on the other float: a decimal
     * just past the midpoint of two floats, whose nearest double *is* the midpoint. Such a decimal
     * is never written; the bits are.
     */
    @Test
    fun `a decimal whose double is a midpoint between floats is not trusted`() {
        // 1 + 2^-24, exactly halfway between 1 and the next float up.
        assertTrue(WorldLinks.isHalfwayBetweenFloats(1.0 + 1.0 / (1 shl 24)))
        assertFalse(WorldLinks.isHalfwayBetweenFloats(1.0))
        assertFalse(WorldLinks.isHalfwayBetweenFloats(0.62))
        // Just past that midpoint, so its nearest float is the one above; its double is the
        // midpoint, which rounds to even, below. A reader going through the double would get 1.0.
        val pastMidpoint = "1.0000000596046447753906251"
        assertFalse(WorldLinks.readsBackExactly(pastMidpoint, Float.fromBits(0x3f800001)))
        assertTrue(WorldLinks.readsBackExactly("0.62", 0.62f))
        // And the midpoint itself, which does read back as the float below by rounding to even,
        // is still not trusted: the decimal a platform writes near it is the one in question.
        assertFalse(WorldLinks.readsBackExactly("1.000000059604644775390625", 1.0f))
    }

    // ---- copying -----------------------------------------------------------------------------

    @Test
    fun `copying puts the link on the clipboard and says so, and says what it does not carry`() {
        val platform = FakePlatform()
        val (config, options) = everythingChanged()
        val line = WorldLinks.copy(platform, config, options)
        val copied = platform.clipboard.single()
        assertTrue(copied.startsWith(WorldLinks.PUBLIC_APP_ADDRESS), copied)
        assertEquals(WorldLinks.linkTo(platform.worldLinkBase, config, options), copied)
        assertTrue(line.startsWith("Copied a link to this world: $copied"), line)

        val edited = WorldLinks.copy(platform, config, options, hasEdits = true)
        assertTrue("Labels and edits stay here" in edited, edited)

        val onDevice = WorldLinks.copy(platform, Knobs.graphicsAcceleration.set(config, true), options)
        assertTrue("graphics device" in onDevice, onDevice)

        // A setting no knob reaches, as a save written with other defaults would carry: the link
        // cannot make this world, and the line says so rather than promising it.
        val unreachable = config.copy(facetRouting = !config.facetRouting)
        assertFalse(WorldLinks.reproduces(unreachable, WorldLinks.linkTo(base, unreachable, options)))
        assertTrue("cannot carry" in WorldLinks.copy(platform, unreachable, options))

        // A host with no clipboard is handed the link to copy by hand, and no copy is claimed.
        val noClipboard = object : FakePlatform() {
            override val canCopyToClipboard: Boolean = false
        }
        val shown = WorldLinks.copy(noClipboard, config, options)
        assertTrue(noClipboard.clipboard.isEmpty())
        assertFalse(shown.startsWith("Copied"), shown)
        assertTrue(WorldLinks.linkTo(base, config, options) in shown, shown)
    }

    @Test
    fun `a link carries nothing but the seed, the format, the size and the settings`() {
        val (config, options) = everythingChanged()
        val link = WorldLinks.linkTo(base, config, options)
        val query = link.substringAfter('?').substringBefore('#')
        assertEquals("seed=718106", query, "the query carries more than the seed")
        val allowed = WorldLinks.KEYED.map { it.first }.toSet() +
            setOf(WorldLinks.VERSION_KEY, WorldLinks.SIZE_KEY, WorldLinks.STYLE_KEY, WorldLinks.VIEW_KEY)
        link.substringAfter('#').split('&').forEach { pair ->
            assertTrue(pair.substringBefore('=') in allowed, "the link carries $pair")
        }
    }

    private companion object {
        /** Enough to cross every binade a dial's range spans many times over; a few milliseconds. */
        const val SAMPLES_PER_DIAL = 4000
    }
}
