package com.cartogenesis.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.cartogenesis.cartography.DataLayer
import com.cartogenesis.cartography.MapStyle
import com.cartogenesis.cartography.MapView
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.worldgen.model.Acceleration
import com.cartogenesis.worldgen.model.WildernessMode
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The guard F2 asks for: every setting the old panel could reach is still reachable, every knob
 * writes the field it claims to and no other, and the panel left alone still asks for the default
 * world.
 *
 * It works by walking [Knobs] — the same list [CartogenesisApp] draws — rather than by driving a
 * composition. That is the point of declaring the panel as data: a knob that is dropped from the
 * panel disappears from this test's subject as well, so the coverage check below names the config
 * fields explicitly and asserts that some knob in the list writes each of them. Deleting the
 * "Ocean coverage" slider from the panel fails `every setting the old panel could reach is still
 * settable`; changing what it writes fails `each knob writes its own field and nothing else`.
 */
class PanelKnobsTest {

    private val base = WorldGenConfig(seed = 42L, width = 512, height = 512)
    private val view = RenderOptions()

    // ---- the sections, and their order -------------------------------------------------------

    @Test
    fun `the panel's sections are the pipeline's, in order`() {
        assertEquals(
            listOf("World", "Terrain", "Climate", "Water", "Peoples", "Cartography"),
            PANEL_SECTIONS.map { it.title }
        )
    }

    @Test
    fun `the atlas's settings are not on the panel`() {
        assertFalse(PanelSection.ATLAS in PANEL_SECTIONS)
        assertTrue(Knobs.inSection(PanelSection.ATLAS).isNotEmpty())
    }

    /**
     * The graphics-acceleration switch is in the header now, not in World.
     *
     * It spent F2 under Ocean coverage, where it read as something about the sea. It is not a
     * setting of the world at all — the same seed makes the same world on either processor — so it
     * sits with the working resolution, which is the other half of "how is this computed".
     */
    @Test
    fun `where the work runs is in the header, not in World`() {
        assertFalse(PanelSection.HEADER in PANEL_SECTIONS)
        assertEquals(
            listOf("Graphics acceleration"),
            Knobs.inSection(PanelSection.HEADER).map { it.label }
        )
        assertEquals(listOf("Ocean coverage"), Knobs.inSection(PanelSection.WORLD).map { it.label })
        // The header draws its knobs through the same renderer the sections use, and hands it no
        // usable `RenderOptions`, so nothing filed there may be a Mark.
        assertTrue(Knobs.inSection(PanelSection.HEADER).none { it is Mark })
    }

    @Test
    fun `only World is unrolled to begin with, and a section remembers being opened`() {
        assertEquals(
            listOf(PanelSection.WORLD),
            PANEL_SECTIONS.filter { it.openByDefault }
        )

        val state = SectionState()
        assertTrue(state.isOpen(PanelSection.WORLD))
        PANEL_SECTIONS.filterNot { it.openByDefault }.forEach {
            assertFalse(state.isOpen(it), "${it.title} should start rolled up")
        }

        state.toggle(PanelSection.CLIMATE)
        assertTrue(state.isOpen(PanelSection.CLIMATE))
        state.toggle(PanelSection.WORLD)
        assertFalse(state.isOpen(PanelSection.WORLD))
    }

    /**
     * No section of the panel is empty.
     *
     * It was two knobs apiece until the graphics-acceleration switch left World for the header, which is
     * the one section that is now a single control — and correctly so: how much of the world is
     * sea is the only thing decided before the pipeline starts. An empty section, on the other
     * hand, is a heading that rolls up to show nothing, and is always a mistake.
     */
    @Test
    fun `no section of the panel is empty`() {
        PANEL_SECTIONS.forEach { section ->
            assertTrue(Knobs.inSection(section).isNotEmpty(), "${section.title} holds nothing")
        }
        PANEL_SECTIONS.filterNot { it == PanelSection.WORLD }.forEach { section ->
            val held = Knobs.inSection(section)
            assertTrue(held.size >= 2, "${section.title} holds only ${held.size}")
        }
    }

    // ---- the guard: nothing the old panel could set has been lost --------------------------

    /**
     * The old panel, field by field. Seed and resolution are the header's and are checked
     * separately, since they are not knobs; the rest were sliders, chips or switches in
     * `WorldSettings`, `OutputOptions` or `AtlasSettings`, and each must now be written by some
     * knob in the list.
     *
     * The pair is a config that differs from the default in exactly that field, and the name of
     * the field, for the failure message.
     */
    private val oldPanelCouldSet: List<Pair<String, WorldGenConfig>> = listOf(
        "seaLevel" to base.copy(seaLevel = 0.4f),
        "tectonics.plateCount" to base.copy(tectonics = base.tectonics.copy(plateCount = 21)),
        "nations.nationCount" to base.copy(nations = base.nations.copy(nationCount = 3)),
        "nations.wilderness" to
            base.copy(nations = base.nations.copy(wilderness = WildernessMode.LEAVE_WILDERNESS)),
        "erosion.acceleration" to
            base.copy(erosion = base.erosion.copy(acceleration = Acceleration.GPU)),
        "landmarks.count" to base.copy(landmarks = base.landmarks.copy(count = 77))
    )

    @Test
    fun `every setting the old panel could reach is still settable`() {
        oldPanelCouldSet.forEach { (field, wanted) ->
            assertTrue(
                Knobs.all.any { reaches(it, wanted) },
                "no knob on the panel or in the atlas can still set $field"
            )
        }
    }

    @Test
    fun `every display option the old panel could reach is still settable`() {
        val wanted = listOf(
            "showRivers" to view.copy(showRivers = false),
            "showHillshade" to view.copy(showHillshade = false),
            "showBorders" to view.copy(showBorders = true),
            "showLakes" to view.copy(showLakes = false),
            "showLandmarks" to view.copy(showLandmarks = true)
        )
        wanted.forEach { (field, target) ->
            assertTrue(
                Knobs.all.filterIsInstance<Mark>().any {
                    it.set(view, target.let(it.read)) == target
                },
                "no knob can still set $field"
            )
        }
    }

    /** The header's two, which are not knobs but are still the panel's to set. */
    @Test
    fun `the header still sets the seed and the working resolution`() {
        assertEquals(base.copy(seed = 7L), Knobs.withSeed(base, 7L))
        assertEquals(listOf(512, 1024, 2048, 4096), Knobs.RESOLUTIONS)
        Knobs.RESOLUTIONS.forEach { size ->
            // `atResolution`, not a raw copy: it rescales everything measured in cells.
            assertEquals(base.atResolution(size, size), Knobs.atResolution(base, size))
            assertEquals(size, Knobs.atResolution(base, size).width)
        }
    }

    /** Whether some knob, given some value, turns [base] into exactly [wanted]. */
    private fun reaches(knob: Knob, wanted: WorldGenConfig): Boolean = when (knob) {
        is Dial -> knob.set(base, knob.read(wanted)) == wanted
        is Stepper -> knob.set(base, knob.read(wanted)) == wanted
        is Latch -> knob.set(base, knob.read(wanted)) == wanted
        is Mark -> false
    }

    // ---- each knob writes its own field, and only its own -----------------------------------

    /**
     * The spec's "construct the panel state, set each field, and assert the emitted
     * `WorldGenConfig` equals the expected copy", written once per knob.
     *
     * Every expectation is spelled out as a `copy` of the default rather than derived from the
     * knob, or the test would be asserting that the knob agrees with itself.
     */
    @Test
    fun `each knob writes its own field and nothing else`() {
        assertEquals(base.copy(seaLevel = 0.31f), Knobs.oceanCoverage.set(base, 0.31f))
        assertEquals(
            base.copy(erosion = base.erosion.copy(acceleration = Acceleration.GPU)),
            Knobs.graphicsAcceleration.set(base, true)
        )
        assertEquals(
            base.copy(tectonics = base.tectonics.copy(plateCount = 9)),
            Knobs.plates.set(base, 9)
        )
        assertEquals(
            base.copy(tectonics = base.tectonics.copy(andeanHeight = 0.7f)),
            Knobs.mountainHeight.set(base, 0.7f)
        )
        assertEquals(
            base.copy(erosion = base.erosion.copy(bedrockErodibilityPerYear = 1.5e-6f)),
            Knobs.erosionStrength.set(base, 1.5e-6f)
        )
        assertEquals(
            base.copy(climate = base.climate.copy(seasonalTiltDegrees = 18f)),
            Knobs.seasonalTiltDegrees.set(base, 18f)
        )
        assertEquals(
            base.copy(climate = base.climate.copy(orographicStrength = 3.5f)),
            Knobs.rainShadow.set(base, 3.5f)
        )
        assertEquals(
            base.copy(glaciation = base.glaciation.copy(enabled = false)),
            Knobs.ice.set(base, false)
        )
        assertEquals(
            base.copy(lakes = base.lakes.copy(waterBalance = false)),
            Knobs.dryBasins.set(base, false)
        )
        assertEquals(
            base.copy(nations = base.nations.copy(nationCount = 25)),
            Knobs.realms.set(base, 25)
        )
        assertEquals(
            base.copy(nations = base.nations.copy(wilderness = WildernessMode.LEAVE_WILDERNESS)),
            Knobs.wilderness.set(base, true)
        )
        assertEquals(
            base.copy(nations = base.nations.copy(wilderness = WildernessMode.CLAIM_ALL_LAND)),
            Knobs.wilderness.set(base, false)
        )
        assertEquals(
            base.copy(landmarks = base.landmarks.copy(count = 55)),
            Knobs.landmarkCount.set(base, 55f)
        )

        assertEquals(view.copy(showRivers = false), Knobs.rivers.set(view, false))
        assertEquals(view.copy(showLakes = false), Knobs.lakes.set(view, false))
        assertEquals(view.copy(showBorders = true), Knobs.borders.set(view, true))
        assertEquals(view.copy(showHillshade = false), Knobs.hillshade.set(view, false))
        assertEquals(view.copy(showCoastline = false), Knobs.coastline.set(view, false))
        assertEquals(view.copy(showLandmarks = true), Knobs.landmarks.set(view, true))
    }

    /**
     * Setting a knob to what it already reads must be the identity, for every knob at once. This
     * is the cheap general form of the check above: a knob whose writer touched a second field
     * would fail here even if nobody thought to assert that field.
     */
    @Test
    fun `writing back what a knob reads changes nothing`() {
        Knobs.all.forEach { knob ->
            when (knob) {
                is Dial -> assertEquals(base, knob.set(base, knob.read(base)), knob.label)
                is Stepper -> assertEquals(base, knob.set(base, knob.read(base)), knob.label)
                is Latch -> assertEquals(base, knob.set(base, knob.read(base)), knob.label)
                is Mark -> assertEquals(view, knob.set(view, knob.read(view)), knob.label)
            }
        }
    }

    // ---- defaults, and clamping --------------------------------------------------------------

    /**
     * The four settings F2 exposes for the first time must sit at the config's own defaults, or a
     * reader who touches nothing gets a different world than they got yesterday.
     */
    @Test
    fun `the new knobs read the generator's own defaults`() {
        val stock = WorldGenConfig()
        assertEquals(stock.tectonics.andeanHeight, Knobs.mountainHeight.read(stock))
        assertEquals(stock.erosion.bedrockErodibilityPerYear, Knobs.erosionStrength.read(stock))
        assertEquals(stock.climate.seasonalTiltDegrees, Knobs.seasonalTiltDegrees.read(stock))
        assertEquals(stock.climate.orographicStrength, Knobs.rainShadow.read(stock))
        assertEquals(stock.glaciation.enabled, Knobs.ice.read(stock))
        assertEquals(stock.lakes.waterBalance, Knobs.dryBasins.read(stock))
        assertEquals(RenderOptions().showCoastline, Knobs.coastline.read(RenderOptions()))

        // And every default is inside the range its control offers, so the control can show it.
        Knobs.all.filterIsInstance<Dial>().forEach {
            val value = it.read(stock)
            assertTrue(
                value in it.range,
                "${it.label} defaults to $value, outside ${it.range}"
            )
        }
        Knobs.all.filterIsInstance<Stepper>().forEach {
            assertTrue(it.read(stock) in it.range, "${it.label} defaults outside ${it.range}")
        }
    }

    /** The steppers replaced sliders, and must reach exactly as far as those sliders did. */
    @Test
    fun `the steppers clamp to the ranges the sliders had`() {
        assertEquals(3..40, Knobs.plates.range)
        assertEquals(0..40, Knobs.realms.range)

        assertEquals(3, Knobs.plates.read(Knobs.plates.set(base, 0)))
        assertEquals(3, Knobs.plates.read(Knobs.plates.set(base, -5)))
        assertEquals(40, Knobs.plates.read(Knobs.plates.set(base, 99)))
        assertEquals(0, Knobs.realms.read(Knobs.realms.set(base, -1)))
        assertEquals(40, Knobs.realms.read(Knobs.realms.set(base, 41)))
    }

    @Test
    fun `the dials clamp to their own ranges`() {
        Knobs.all.filterIsInstance<Dial>().forEach { dial ->
            val low = dial.read(dial.set(base, dial.range.start - 100f))
            val high = dial.read(dial.set(base, dial.range.endInclusive + 100f))
            assertEquals(dial.range.start, low, "${dial.label} did not clamp below")
            assertEquals(dial.range.endInclusive, high, "${dial.label} did not clamp above")
        }
    }

    @Test
    fun `no two knobs are called the same thing`() {
        val labels = Knobs.all.map { it.label }
        assertEquals(labels.size, labels.toSet().size, "duplicate label in $labels")
    }

    // ---- the toolbar over the map, which is where style and view went ------------------------

    /**
     * F3's half of the same guard.
     *
     * Style and view were two of the things the old panel could set, and F3 took them off the
     * panel. That is exactly the move this test exists to catch, so the coverage does not lapse
     * because the control moved: it now asks the *toolbar* whether the interface can still reach
     * every style and every view, in the same walk-the-declaration way. Deleting a style from
     * `MapChrome.styles`, or having `withStyle` write the view by mistake, fails here.
     */
    @Test
    fun `style and view are no longer knobs on the panel`() {
        assertTrue(
            Knobs.inSection(PanelSection.CARTOGRAPHY).none {
                it.label == "Style" || it.label == "View"
            }
        )
        // Cartography keeps its own two marks, so the section is not left empty.
        assertEquals(
            listOf("Relief shading", "Coastline"),
            Knobs.inSection(PanelSection.CARTOGRAPHY).map { it.label }
        )
    }

    @Test
    fun `the toolbar offers every style and every view`() {
        assertEquals(MapStyle.entries.toList(), MapChrome.styles)
        assertEquals(MapView.entries.toList(), MapChrome.views)
        // Eleven since F6 added Colour-blind, which is the eleventh cell in the segmented row
        // (Mars was the tenth, added by F4).
        assertEquals(11, MapChrome.styles.size)
        assertEquals(15, MapChrome.views.size)
    }

    @Test
    fun `the toolbar can still set every style and every view, and nothing else`() {
        MapChrome.styles.forEach { style ->
            assertEquals(view.copy(style = style), MapChrome.withStyle(view, style), style.label)
        }
        MapChrome.views.forEach { seen ->
            assertEquals(view.copy(view = seen), MapChrome.withView(view, seen), seen.label)
        }
        // Writing back what is already showing changes nothing, the cheap general form.
        assertEquals(view, MapChrome.withStyle(view, view.style))
        assertEquals(view, MapChrome.withView(view, view.view))
    }

    /** Neither one regenerates: both are `RenderOptions`, so the world is untouched by both. */
    @Test
    fun `the toolbar's two choices leave the marks beside them alone`() {
        val marked = view.copy(showBorders = true, showHillshade = false, showLakes = false)
        val restyled = MapChrome.withView(MapChrome.withStyle(marked, MapStyle.SCROLL), MapView.WIND)
        assertEquals(MapStyle.SCROLL, restyled.style)
        assertEquals(MapView.WIND, restyled.view)
        assertTrue(restyled.showBorders)
        assertFalse(restyled.showHillshade)
        assertFalse(restyled.showLakes)
    }

    /**
     * The toolbar's small print. A diagnostic view's colours mean something, so the style is not
     * applied to it and the strip has to say so rather than leaving nine controls that do nothing.
     */
    @Test
    fun `the toolbar says when the style is not being used`() {
        assertTrue(MapChrome.styleApplies(MapView.FANTASY))
        assertTrue(MapChrome.styleApplies(MapView.POLITICAL))
        assertFalse(MapChrome.styleApplies(MapView.RAINFALL))

        assertEquals(MapStyle.ATLAS.detail, MapChrome.note(view))
        val note = MapChrome.note(view.copy(view = MapView.RAINFALL))
        assertTrue("rainfall" in note, note)
        assertTrue("ignores the style" in note, note)
    }

    // ---- the export ceiling -------------------------------------------------------------------

    /**
     * 8192 does not complete on this build: G2 measured it exhausting a 10 GB heap inside the
     * generator after about nineteen minutes, before a pixel of the map is drawn. So the size that
     * reaches the platform can never be 8192 while the ceiling stands at 4096 — not from the
     * button, which is disabled, and not from a preference written by an older build, which is why
     * [Exports.clamp] and not the button is what this test asks.
     *
     * The ceiling is [Platform.exportCeiling] rather than a constant here, so the build that fixes
     * the memory raises one number on the platform and this test starts letting 8192 through.
     */
    @Test
    fun `the export size can never be 8192 while the ceiling is 4096`() {
        val ceiling = 4096
        Exports.SIZES.forEach { offered ->
            assertTrue(
                Exports.clamp(offered, ceiling) != 8192,
                "$offered reached the platform as 8192"
            )
            assertTrue(Exports.clamp(offered, ceiling) <= ceiling)
        }
        // The two that do work are passed through untouched, and the one that does not falls back
        // to the largest that does.
        assertEquals(2048, Exports.clamp(2048, ceiling))
        assertEquals(4096, Exports.clamp(4096, ceiling))
        assertEquals(4096, Exports.clamp(8192, ceiling))
        // Whatever an old preference held, including a size the row never offered.
        assertEquals(4096, Exports.clamp(16384, ceiling))
    }

    @Test
    fun `the row still offers three sizes, and says why one of them is out of reach`() {
        assertEquals(listOf(2048, 4096, 8192), Exports.SIZES)
        assertTrue(Exports.reachable(2048, 4096))
        assertTrue(Exports.reachable(4096, 4096))
        assertFalse(Exports.reachable(8192, 4096))
        assertEquals(
            "8192 needs more memory than this build can hold",
            Exports.unreachableNote(8192)
        )
    }

    /** Raising the ceiling is the whole of the fix, and it needs nothing from the interface. */
    @Test
    fun `a build that can hold 8192 gets 8192`() {
        assertTrue(Exports.reachable(8192, 8192))
        assertEquals(8192, Exports.clamp(8192, 8192))
        // And a build that could not even manage 4096 still has something to fall back to.
        assertEquals(2048, Exports.clamp(8192, 2048))
        assertEquals(2048, Exports.clamp(4096, 1024))
    }

    // ---- the camera, whose readout is the other half of the legend ---------------------------

    @Test
    fun `the zoom buttons step and clamp, and Fit returns to the whole sheet`() {
        val camera = MapCamera()
        assertEquals(100, camera.percent)

        camera.step(MapCamera.STEP)
        assertEquals(115, camera.percent)
        camera.step(1f / MapCamera.STEP)
        assertEquals(100, camera.percent)

        repeat(100) { camera.step(MapCamera.STEP) }
        assertEquals(MapCamera.MAX_ZOOM, camera.zoom)
        repeat(200) { camera.step(1f / MapCamera.STEP) }
        assertEquals(MapCamera.MIN_ZOOM, camera.zoom)

        camera.about(Offset(120f, 80f), 2f)
        camera.fit()
        assertEquals(1f, camera.zoom)
        assertEquals(Offset.Zero, camera.pan)
    }

    // ---- F5: the second arrangement, and what it does and does not lose ----------------------

    /**
     * Which window gets which arrangement.
     *
     * The threshold is a width in dp and not a device: a desktop window dragged to 700 dp is a
     * compact window and gets the sheet, because the question the layout is answering is whether
     * the panel fits beside the map. The pointer is the second, independent reason — a tablet in
     * landscape is as wide as a laptop and still cannot be driven with a 13 dp slider thumb.
     */
    @Test
    fun `a narrow window or a coarse pointer asks for the compact arrangement`() {
        assertEquals(WindowShape.WIDE, Layouts.shape(1440f, coarsePointer = false))
        assertEquals(WindowShape.WIDE, Layouts.shape(1024f, coarsePointer = false))
        assertEquals(WindowShape.WIDE, Layouts.shape(800f, coarsePointer = false))
        assertEquals(WindowShape.COMPACT, Layouts.shape(799f, coarsePointer = false))
        // The two sizes `ChromeGalleryTest` photographs: a phone and a tablet in portrait.
        assertEquals(WindowShape.COMPACT, Layouts.shape(390f, coarsePointer = false))
        assertEquals(WindowShape.COMPACT, Layouts.shape(768f, coarsePointer = false))
        assertEquals(WindowShape.COMPACT, Layouts.shape(1180f, coarsePointer = true))
    }

    /**
     * The guard F5 asks for: the sheet on a phone can set everything the 320 dp column can.
     *
     * [Arrangements] declares the two arrangements as two independently written lists, and the
     * composables draw from those lists — so a control dropped from the compact arrangement is
     * dropped from this comparison too. Deleting a section from `Arrangements.compact`, or
     * shortening its style list to the six that fit, fails here.
     */
    @Test
    fun `the compact arrangement exposes every knob the wide one does`() {
        val platform = FakePlatform(accelerator = FakeAccelerator)
        val wide = Arrangements.of(WindowShape.WIDE, platform)
        val compact = Arrangements.of(WindowShape.COMPACT, platform)

        assertEquals(
            wide.knobs.map { it.label },
            compact.knobs.map { it.label },
            "the compact panel cannot reach every knob the wide one can"
        )
        // And what both reach is the whole panel: the header's knob and all six sections, in the
        // order the generator runs. Spelled out against [Knobs] rather than against each other, or
        // two arrangements that had both lost the same knob would agree with one another.
        assertEquals(
            Knobs.all.filterNot { it.section == PanelSection.ATLAS }.map { it.label },
            wide.knobs.map { it.label }
        )
    }

    @Test
    fun `the compact arrangement reaches every style, view, size and menu command`() {
        val platform = FakePlatform(canQuit = true, accelerator = FakeAccelerator)
        val wide = Arrangements.of(WindowShape.WIDE, platform)
        val compact = Arrangements.of(WindowShape.COMPACT, platform)

        // Ten styles: a segmented row when there is room for one, a menu when there is not.
        assertEquals(wide.styles, compact.styles)
        assertEquals(MapChrome.styles, compact.styles)
        // Fifteen views, a menu in both.
        assertEquals(wide.views, compact.views)
        // Three export sizes, whichever of them this build can finish.
        assertEquals(wide.exportSizes, compact.exportSizes)
        // Three picture formats and three data layers, both rows in the sheet as well as in the
        // column. F12 added JPEG to the first row and the second row entirely, and a phone gets
        // both: the ceiling decides how large an export may be, not what kinds there are.
        assertEquals(wide.pictureFormats, compact.pictureFormats)
        assertEquals(wide.dataLayers, compact.dataLayers)
        // Three menus folded into one button, with nothing dropped on the way.
        assertEquals(wide.commands, compact.commands)
        assertTrue(MenuCommand.SETTINGS in compact.commands)
        assertTrue(MenuCommand.ABOUT in compact.commands)
        assertTrue(MenuCommand.QUIT in compact.commands)
    }

    // ---- F12: the second kind of export --------------------------------------------------------

    /**
     * The export row's two lines of chips, against the enums rather than against each other.
     *
     * Written out here for the reason the arrangement lists are written out twice: a row that draws
     * `ExportFormat.entries` and a test that asserts it draws `ExportFormat.entries` agree with each
     * other whatever either of them has lost. The chips are named.
     */
    @Test
    fun `the export row offers three picture formats and three data layers`() {
        assertEquals(listOf("PNG", "WebP", "JPEG"), Exports.PICTURES.map { it.label })
        assertEquals(listOf("Heightmap", "Biomes", "Realms"), Exports.LAYERS.map { it.label })
        assertEquals(ExportFormat.entries.toList(), Exports.PICTURES)
        assertEquals(DataLayer.entries.toList(), Exports.LAYERS)

        // Every chip has its own line of small print, and no two chips share a word.
        val labels = Exports.PICTURES.map { it.label } + Exports.LAYERS.map { it.label }
        assertEquals(labels.size, labels.toSet().size, "two export chips are called the same thing")
        Exports.PICTURES.forEach { assertTrue(it.detail.isNotBlank(), it.label) }
        Exports.LAYERS.forEach { assertTrue(it.detail.isNotBlank(), it.label) }
    }

    /**
     * The JPEG chip says what it is for, because on the merits nobody should choose it.
     *
     * The reason it is offered is a program that will not open a WebP, and the chip has to name
     * WebP for that sentence to mean anything; a chip that reads as a third equally good option is
     * a chip that misleads. What it costs — softer thin lines than WebP for a smaller file — is
     * measured by `DataExportTest` and `ExportSmokeTest`, and the wording follows those numbers.
     */
    @Test
    fun `the JPEG chip says it is for compatibility, not for quality`() {
        val note = ExportFormat.JPEG.detail
        assertTrue("WebP" in note, note)
        assertTrue("lines" in note, note)
        assertEquals("jpg", ExportFormat.JPEG.extension)
        assertEquals(90, ExportFormat.JPEG_QUALITY)
    }

    /**
     * One selection across both rows, and pressing a size acts on whichever it is.
     *
     * The invariant [ExportChoice] exists for: a picture and a layer are never both chosen, so the
     * size buttons below always mean one thing.
     */
    @Test
    fun `choosing a data layer unchooses the picture format, and the other way round`() {
        val png = ExportChoice.Picture(ExportFormat.PNG)
        val heightmap = ExportChoice.Layer(DataLayer.HEIGHTMAP)

        assertEquals(png, ExportChoice.Picture(ExportFormat.PNG))
        assertTrue(png != heightmap)
        assertTrue(png != ExportChoice.Picture(ExportFormat.JPEG))
        assertTrue(heightmap != ExportChoice.Layer(DataLayer.BIOMES))

        // The chip's own word and its own small print, whichever kind it is: the row draws one
        // note, and it comes from the selection rather than from a branch in the composable.
        assertEquals("PNG", png.label)
        assertEquals(ExportFormat.PNG.detail, png.detail)
        assertEquals("Heightmap", heightmap.label)
        assertEquals(DataLayer.HEIGHTMAP.detail, heightmap.detail)
    }

    /** The ceiling is about size and knows nothing about kind, so it applies to both alike. */
    @Test
    fun `the phone's export ceiling applies to the data layers too`() {
        val phone = FakePlatform(ceiling = 2048, coarsePointer = true)
        val ceiling = phone.exportCeiling(compact = true)
        assertEquals(2048, ceiling)
        assertEquals(2048, Exports.clamp(4096, ceiling))
        assertEquals(2048, Exports.clamp(8192, ceiling))
        // Both rows are still offered at that ceiling: it caps the size, not the kind.
        val compact = Arrangements.of(WindowShape.COMPACT, phone)
        assertEquals(DataLayer.entries.toList(), compact.dataLayers)
        assertEquals(ExportFormat.entries.toList(), compact.pictureFormats)
    }

    /**
     * The one thing a phone deliberately loses, stated so that losing anything else is a failure.
     *
     * Pinch is what a phone already does for zoom and a double tap now fits, so a readout and two
     * step buttons on a 390 dp strip are three targets spent on a gesture the device has. Fit
     * stays: there is no gesture anybody would guess for "show me all of it".
     */
    @Test
    fun `the phone's legend keeps the cartouche and Fit and loses the zoom steps`() {
        val platform = FakePlatform()
        val wide = Arrangements.of(WindowShape.WIDE, platform).legend
        val compact = Arrangements.of(WindowShape.COMPACT, platform).legend

        assertEquals(LegendPart.entries.toList(), wide)
        assertEquals(listOf(LegendPart.CARTOUCHE, LegendPart.FIT), compact)
        assertTrue(LegendPart.ZOOM_IN !in compact)
        assertTrue(LegendPart.ZOOM_OUT !in compact)
    }

    /**
     * A device with no graphics API at all is offered no graphics-acceleration switch.
     *
     * Not the same as a device whose graphics card declined — that one keeps the switch, disabled,
     * with [Platform.accelerationUnavailableBecause] printed beside it, which is why this asks
     * [Platform.graphicsApiPresent] and not whether the accelerator is null. Most phone browsers
     * have no `navigator.gpu`, and a disabled switch explaining a feature the device does not have
     * is 60 dp of a 390 dp screen spent on nothing.
     */
    @Test
    fun `a host with no graphics API is offered no graphics-acceleration switch`() {
        val none = FakePlatform(graphicsApiPresent = false)
        val present = FakePlatform(graphicsApiPresent = true, accelerator = FakeAccelerator)
        // Present but refused: the switch stays, because there is something to explain.
        val refused = FakePlatform(graphicsApiPresent = true, accelerator = null)

        listOf(WindowShape.WIDE, WindowShape.COMPACT).forEach { shape ->
            assertTrue(
                Arrangements.of(shape, none).knobs.none { it.needsGraphicsDevice },
                "$shape still draws the graphics-acceleration switch with no graphics API"
            )
            assertTrue(Arrangements.of(shape, present).knobs.any { it.needsGraphicsDevice })
            assertTrue(Arrangements.of(shape, refused).knobs.any { it.needsGraphicsDevice })
        }

        // Exactly one knob goes, and it is that one.
        assertEquals(
            Arrangements.of(WindowShape.WIDE, present).knobs
                .filterNot { it.needsGraphicsDevice }.map { it.label },
            Arrangements.of(WindowShape.WIDE, none).knobs.map { it.label }
        )
        assertEquals(listOf(Knobs.graphicsAcceleration), Knobs.all.filter { it.needsGraphicsDevice })
    }

    /**
     * A phone exports at 2048, and the cap is the platform's rather than the panel's.
     *
     * Same mechanism as the 8192 ceiling above, asked with the window's shape: an export re-runs
     * the whole pipeline at the target size on the page's only thread, and 4096 of that on a phone
     * is a tab the browser kills. The desktop ignores the argument, so narrowing a desktop window
     * does not narrow what it can export.
     */
    @Test
    fun `a phone browser's export ceiling caps the size at 2048`() {
        val phone = object : FakePlatform(coarsePointer = true) {
            override fun exportCeiling(compact: Boolean): Int = if (compact) 2048 else 4096
        }
        assertEquals(2048, phone.exportCeiling(compact = true))
        assertEquals(4096, phone.exportCeiling(compact = false))
        assertEquals(2048, Exports.clamp(4096, phone.exportCeiling(compact = true)))
        assertEquals(2048, Exports.clamp(8192, phone.exportCeiling(compact = true)))
        assertFalse(Exports.reachable(4096, phone.exportCeiling(compact = true)))
        // A platform that does not care answers the same either way.
        assertEquals(4096, FakePlatform().exportCeiling(compact = true))
    }

    /** A phone starts at 512 whatever a settings file carried over from a desktop says. */
    @Test
    fun `a compact window starts at 512 however the preference was written`() {
        val platform = FakePlatform(defaultResolution = 1024)
        val big = AppSettings(workingResolution = 2048)

        assertEquals(2048, SettingsEffects.resolution(big, platform, compact = false))
        assertEquals(512, SettingsEffects.resolution(big, platform, compact = true))
        assertEquals(1024, SettingsEffects.resolution(AppSettings(), platform, compact = false))
        assertEquals(512, SettingsEffects.resolution(AppSettings(), platform, compact = true))
        assertEquals(
            512,
            SettingsEffects.startingConfig(big, platform, seed = 1L, compact = true).width
        )
        // And it is a ceiling, not a setting: a preference already below it is left alone.
        val small = AppSettings(workingResolution = 512)
        assertEquals(512, SettingsEffects.resolution(small, platform, compact = true))
    }

    /**
     * The touch targets are the theme's, and the mouse's are exactly what F1 shipped.
     *
     * The second half of that is what keeps the wide window pixel-for-pixel where it was: every
     * control that grew under a fingertip reads these numbers, and under a pointer they are the
     * literals that used to be written into `Controls.kt`.
     */
    @Test
    fun `the pointer's touch targets are the numbers F1 drew with`() {
        assertEquals(26.dp, TouchTargets.POINTER.sliderHeight)
        assertEquals(13.dp, TouchTargets.POINTER.sliderThumb)
        assertEquals(0.dp, TouchTargets.POINTER.minTarget)
        assertEquals(0.dp, TouchTargets.POINTER.extraRowPadding)

        assertTrue(TouchTargets.TOUCH.sliderHeight > TouchTargets.POINTER.sliderHeight)
        assertTrue(TouchTargets.TOUCH.sliderThumb > TouchTargets.POINTER.sliderThumb)
        assertTrue(TouchTargets.TOUCH.minTarget >= 48.dp)
    }

    /** Zooming about a point has to leave that point where it was, or the map slides away. */
    @Test
    fun `zooming about a point keeps that point under the cursor`() {
        val camera = MapCamera()
        val anchor = Offset(300f, 200f)
        // The point of the map that is under the anchor: it must be the same point afterwards.
        val before = (anchor - camera.pan) / camera.zoom
        camera.about(anchor, 2f)
        val after = (anchor - camera.pan) / camera.zoom
        assertEquals(2f, camera.zoom)
        assertEquals(before.x, after.x, 0.01f)
        assertEquals(before.y, after.y, 0.01f)
    }
}
