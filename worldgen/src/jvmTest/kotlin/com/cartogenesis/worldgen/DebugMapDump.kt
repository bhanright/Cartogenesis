package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.math.DistanceTransform
import com.cartogenesis.worldgen.math.JumpFloodDistance
import com.cartogenesis.worldgen.model.TectonicsConfig
import com.cartogenesis.worldgen.model.WildernessMode
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Not a correctness test — a visual harness. It renders generated worlds to PNGs under
 * `worldgen/build/maps/` so the pipeline's output can be eyeballed without an emulator.
 * The app has its own renderer; this one only has to be good enough to spot bad terrain.
 */
class DebugMapDump {

    private val outputDir = File("build/maps")

    @Test
    fun `dump sample worlds`() {
        outputDir.mkdirs()

        listOf(7L, 42L, 1234L).forEach { seed ->
            val world = WorldGenerationEngine.generateBlocking(
                WorldGenConfig(seed = seed, width = 512, height = 512)
            )
            write(render(world, Mode.FANTASY), "seed$seed-fantasy.png")
            // Seasons are invisible in the annual maps by construction, so every seed gets the
            // two rainfall halves and the biome map that is drawn from them.
            write(render(world, Mode.BIOME), "seed$seed-biome.png")
            write(render(world, Mode.SUMMER_RAINFALL), "seed$seed-rainfall-summer.png")
            write(render(world, Mode.WINTER_RAINFALL), "seed$seed-rainfall-winter.png")
            write(render(world, Mode.SEASON_CONTRAST), "seed$seed-rainfall-contrast.png")
            write(render(world, Mode.WIND), "seed$seed-wind.png")
            // Continentality's payoff, per seed: an interior at a given latitude should read
            // hotter in summer and colder in winter than a coast at the same latitude.
            write(render(world, Mode.SUMMER_TEMPERATURE), "seed$seed-temperature-summer.png")
            write(render(world, Mode.WINTER_TEMPERATURE), "seed$seed-temperature-winter.png")
            println(
                "seed $seed: ${(world.landFraction() * 100).toInt()}% land, " +
                    "${world.rivers.rivers.size} rivers, " +
                    "${world.plates.plates.size} plates, " +
                    riverDiagnostics(world)
            )
        }

        // One world in every view, to check each stage independently.
        val world = WorldGenerationEngine.generateBlocking(WorldGenConfig(seed = 42L, width = 512, height = 512))
        write(render(world, Mode.ELEVATION), "seed42-elevation.png")
        write(render(world, Mode.PLATES), "seed42-plates.png")
        write(render(world, Mode.BIOME), "seed42-biome.png")
        write(render(world, Mode.RAINFALL), "seed42-rainfall.png")
        write(render(world, Mode.TEMPERATURE), "seed42-temperature.png")
        write(render(world, Mode.SUMMER_TEMPERATURE), "seed42-temperature-summer.png")
        write(render(world, Mode.WINTER_TEMPERATURE), "seed42-temperature-winter.png")
        reportBiomeShares(world)
        write(render(world, Mode.NORMALS), "seed42-normals.png")
        write(render(world, Mode.NATIONS), "seed42-nations.png")
        write(render(world, Mode.CULTURES), "seed42-cultures.png")
        write(render(world, Mode.HABITABILITY), "seed42-habitability.png")

        world.cultures.cultures.sortedByDescending { it.cellCount }.forEach {
            println("  ${it.name} - ${it.cellCount} cells, mostly ${it.dominantBiome}")
        }

        world.nations.nations.sortedByDescending { it.cellCount }.take(3).forEach {
            println("  ${it.name} - ${it.government}, cap. ${it.capitalName}")
            println(
                "     pop ${"%,d".format(it.population)}, heartland ${it.heartlandBiome}, " +
                    "area-biome ${it.dominantBiome}"
            )
            println("     exports: ${it.exports.joinToString()}")
            println("     imports: ${it.imports.joinToString()}")
            println("     ${it.lore}")
        }
        val claimed = world.nations.nationId.count { it >= 0 }
        println(
            "  realms=${world.nations.nations.size} claimed=" +
                "${claimed * 100 / world.sea.landCellCount.coerceAtLeast(1)}% of land"
        )

        world.landmarks.landmarks.take(10).forEach {
            println("  [${it.kind.label}] ${it.name} � ${it.detail} (wild=${it.inWilderness})")
        }
        println("  landmarks=${world.landmarks.landmarks.size}")

        assertTrue(outputDir.listFiles()!!.isNotEmpty())
        println("Maps written to ${outputDir.absolutePath}")
    }

    /**
     * H2: where the ice went, and where it stayed.
     *
     * Biomes for the three standard seeds at 512, before (`snowBalance = false`, ice wherever the
     * annual mean is below -8 C) and after (a snow balance). Elevation alongside, because the
     * question the renders answer is not only how much ice there is but whether what is left is in
     * the places a glacier belongs — the wet highlands and the poles — rather than smeared over
     * every cold interior.
     */
    @Test
    fun `dump the H2 ice before and after`() {
        outputDir.mkdirs()
        listOf(7L, 42L, 1234L).forEach { seed ->
            val base = WorldGenConfig(seed = seed, width = 512, height = 512)
            val after = WorldGenerationEngine.generateBlocking(base)
            val before = WorldGenerationEngine.generateBlocking(
                base.copy(climate = base.climate.copy(snowBalance = false))
            )
            write(render(before, Mode.BIOME), "seed$seed-h2-before-biome.png")
            write(render(after, Mode.BIOME), "seed$seed-h2-after-biome.png")
            write(render(after, Mode.ELEVATION), "seed$seed-h2-after-elevation.png")
            listOf("before" to before, "after" to after).forEach { (tag, world) ->
                val tally = HashMap<Biome, Int>()
                for (i in world.climate.biome.indices) {
                    if (world.sea.isLand[i]) {
                        tally[world.climate.biome[i]] = (tally[world.climate.biome[i]] ?: 0) + 1
                    }
                }
                val land = world.sea.landCellCount
                println(
                    "H2 render seed $seed $tag: " + tally.entries.sortedByDescending { it.value }
                        .joinToString(", ") {
                            "${it.key} ${"%.1f".format(it.value * 100.0 / land)}%"
                        }
                )
            }
        }
        println("H2 renders written to ${outputDir.absolutePath}")
    }

    /**
     * H4: seed 26's southern-hemisphere cold-current coast (see `CurrentFeedsRainTest`), before
     * (`currentMoisture = 0`, today's field) and after (the default 0.07/deg). Annual rainfall and
     * biome only, since the effect is on the annual march's over-sea pickup rather than anything
     * seasonal.
     */
    @Test
    fun `dump the H4 current-coupled coast before and after`() {
        outputDir.mkdirs()
        val seed = 26L
        val base = WorldGenConfig(seed = seed, width = 512, height = 512)
        val after = WorldGenerationEngine.generateBlocking(base)
        val before = WorldGenerationEngine.generateBlocking(
            base.copy(climate = base.climate.copy(currentMoisture = 0f))
        )
        write(render(before, Mode.RAINFALL), "seed$seed-h4-before-rainfall.png")
        write(render(before, Mode.BIOME), "seed$seed-h4-before-biome.png")
        write(render(after, Mode.RAINFALL), "seed$seed-h4-after-rainfall.png")
        write(render(after, Mode.BIOME), "seed$seed-h4-after-biome.png")
        println("H4 render: seed $seed before/after written to ${outputDir.absolutePath}")
    }

    /**
     * The three convergent pairs, side by side against the world that could not tell them apart.
     *
     * Every seed is rendered twice, once with
     * [com.cartogenesis.worldgen.model.TectonicsConfig.crustPairProfiles] on and once off, so the
     * Andes-versus-Tibet claim can be checked against the belt it replaced rather than against a
     * memory of it. The boundary-class view says which pair built which piece of ground; the point
     * of the elevation view is that it should not be needed to see the difference.
     */
    @Test
    fun `dump crust-pair boundary renders`() {
        outputDir.mkdirs()

        listOf(7L, 42L, 1234L).forEach { seed ->
            listOf(true, false).forEach { pairs ->
                val base = WorldGenConfig(seed = seed, width = 512, height = 512)
                val config = base.copy(
                    tectonics = base.tectonics.copy(crustPairProfiles = pairs)
                )
                val world = WorldGenerationEngine.generateBlocking(config)
                val tag = if (pairs) "pairs" else "single"
                write(render(world, Mode.ELEVATION), "pairs-seed$seed-$tag-elevation.png")
                write(render(world, Mode.FANTASY), "pairs-seed$seed-$tag-fantasy.png")
                if (pairs) {
                    write(render(world, Mode.BOUNDARY_CLASS), "pairs-seed$seed-boundaries.png")
                    write(render(world, Mode.PLATES), "pairs-seed$seed-plates.png")
                }

                // How much of the land each pair built, which is the tally behind the renders.
                val counts = IntArray(6)
                var land = 0
                for (i in world.sea.isLand.indices) {
                    if (!world.sea.isLand[i]) continue
                    land++
                    val cls = world.plates.nearestBoundaryClass[i]
                    if (cls in counts.indices) counts[cls]++
                }
                println(
                    "PAIRSMAP seed $seed $tag: ${(world.landFraction() * 100).toInt()}% land, " +
                        counts.mapIndexed { cls, n ->
                            "${boundaryClassName(cls)}=${n * 100 / land.coerceAtLeast(1)}%"
                        }.joinToString(" ")
                )
            }
        }
        println("Crust-pair maps written to ${outputDir.absolutePath}")
    }

    private fun boundaryClassName(ordinal: Int): String =
        com.cartogenesis.worldgen.pipeline.BoundaryClass.entries.getOrNull(ordinal)?.name ?: "none"

    /**
     * The two seasons' rainfall with the wind slanted and with it purely zonal, side by side.
     *
     * The whole of the monsoon is the difference between these two sets, and it is a difference no
     * summary statistic states as plainly as the pictures do: with a zonal wind the seasonal
     * pattern is a set of latitude stripes, because a zonal march cannot tell which side of a
     * continent faces the equator; with the slant the stripes acquire a coastline.
     */
    @Test
    fun `sweep the meridional wind`() {
        outputDir.mkdirs()
        listOf(7L, 42L, 1234L).forEach { seed ->
            val base = WorldGenConfig(seed = seed, width = 512, height = 512)
            listOf(0f, 0.3f).forEach { slant ->
                val world = WorldGenerationEngine.generateBlocking(
                    base.copy(climate = base.climate.copy(meridionalWind = slant))
                )
                val tag = "seed$seed-slant$slant"
                write(render(world, Mode.SUMMER_RAINFALL), "$tag-rainfall-summer.png")
                write(render(world, Mode.WINTER_RAINFALL), "$tag-rainfall-winter.png")
                write(render(world, Mode.SEASON_CONTRAST), "$tag-rainfall-contrast.png")
                write(render(world, Mode.BIOME), "$tag-biome.png")
            }
        }
        println("Meridional sweep written to ${outputDir.absolutePath}")
    }

    /**
     * Coastline detail is a trade-off between the smooth blurred plate base and the fractal noise
     * terrain. This renders the corners of that trade-off so it can be judged by eye.
     */
    @Test
    fun `sweep terrain roughness against tectonic influence`() {
        outputDir.mkdirs()
        val base = WorldGenConfig(seed = 42L, width = 512, height = 512)

        listOf(0.30f, 0.45f, 0.60f).forEach { weight ->
            listOf(0.92f, 1.0f).forEach { gain ->
                val config = base.copy(
                    terrain = base.terrain.copy(gain = gain),
                    tectonics = base.tectonics.copy(tectonicWeight = weight)
                )
                val world = WorldGenerationEngine.generateBlocking(config)
                write(render(world, Mode.FANTASY), "sweep-w${weight}-g$gain.png")
            }
        }
        println("Sweep written to ${outputDir.absolutePath}")
    }

    /** Both wilderness modes, side by side. */
    @Test
    fun `sweep wilderness mode`() {
        outputDir.mkdirs()
        val base = WorldGenConfig(seed = 42L, width = 512, height = 512)
        WildernessMode.entries.forEach { mode ->
            val world = WorldGenerationEngine.generateBlocking(
                base.copy(nations = base.nations.copy(wilderness = mode))
            )
            write(render(world, Mode.NATIONS), "nations-$mode.png")
            val claimed = world.nations.nationId.count { it >= 0 }
            println(
                "$mode realms=${world.nations.nations.size} " +
                    "claimed=${claimed * 100 / world.sea.landCellCount.coerceAtLeast(1)}%"
            )
        }
    }

    /** Prints the scale-consistency numbers used to pick thresholds for the resolution guard. */
    @Test
    fun `report resolution consistency metrics`() {
        listOf(128, 256, 512).forEach { size ->
            val world = WorldGenerationEngine.generateBlocking(
                WorldGenConfig(seed = 42L, width = 128, height = 128).atResolution(size, size)
            )
            val e = world.sea.relativeElevation
            var nearTotal = 0.0; var nearCount = 0
            var farTotal = 0.0; var farCount = 0
            var desert = 0; var land = 0
            val nearRadius = world.width * 0.03f

            for (y in 1 until world.height - 1) {
                for (x in 1 until world.width - 1) {
                    val i = y * world.width + x
                    if (!world.sea.isLand[i]) continue
                    land++
                    if (world.climate.biome[i] == Biome.DESERT) desert++
                    val dx = e.sample(x + 1, y) - e.sample(x - 1, y)
                    val dy = e.sample(x, y + 1) - e.sample(x, y - 1)
                    val slope = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()) * world.width
                    if (world.plates.boundaryDistance.data[i] < nearRadius) {
                        nearTotal += slope; nearCount++
                    } else {
                        farTotal += slope; farCount++
                    }
                }
            }
            val near = if (nearCount == 0) 0.0 else nearTotal / nearCount
            val far = if (farCount == 0) 0.0 else farTotal / farCount
            println(
                "size=%d land=%.3f nearSlope=%.2f farSlope=%.2f contrast=%.2f desert=%.3f"
                    .format(
                        size, world.landFraction(), near, far,
                        if (far == 0.0) 0.0 else near / far,
                        desert.toFloat() / land.coerceAtLeast(1)
                    )
            )
        }
    }

    private enum class Mode {
        FANTASY, ELEVATION, PLATES, BIOME, RAINFALL, TEMPERATURE, NORMALS, NATIONS, CULTURES,
        HABITABILITY,
        // The local warm and cold season, not July and January. Side by side these are where the
        // subtropical dry belt's migration shows: it sits some ten degrees poleward in the summer
        // map and the same distance equatorward in the winter one.
        SUMMER_RAINFALL, WINTER_RAINFALL, SUMMER_TEMPERATURE, WINTER_TEMPERATURE,
        // Which half of the year the rain arrives in, and the wind vector that decides it.
        SEASON_CONTRAST, WIND,

        // What each plate boundary is building, by crust pair: an Andean margin, a collision
        // plateau, an island arc, a spreading ridge, a continental rift or a transform fault.
        BOUNDARY_CLASS,

        // H1: how long ago each cell's crust was last built, from the present epoch's belts
        // through the older ones to the cratonic ground no epoch ever deformed.
        CRUST_AGE
    }

    /**
     * The high-latitude coasts, close enough to see a trough, magnified four times.
     *
     * Glaciation is the one stage whose work is invisible at whole-world scale: a trough is five
     * cells wide and a tarn is twenty cells, so on a 512-pixel map of the world they are two pixels
     * and a speck. Everything else in this file renders the world; this renders a corner of it.
     */
    @Test
    fun `dump the glaciated coasts`() {
        outputDir.mkdirs()
        listOf(7L, 42L, 1234L, 718106L).forEach { seed ->
            val config = WorldGenConfig(seed = seed, width = 512, height = 512)
            val world = WorldGenerationEngine.generateBlocking(config)
            val bare = WorldGenerationEngine.generateBlocking(
                config.copy(glaciation = config.glaciation.copy(enabled = false))
            )
            // Seed 718106's cold northern continent is the ground the D8 lattice was found on, and
            // it reaches well south of the polar eighth the belt crops cover: the lattice was in
            // the flat interior, not on the coast. A wider band of it, plus the whole-map view it
            // sits in, so the interior can be judged as well as the fjords.
            if (seed == 718106L) {
                write(
                    crop(render(world, Mode.FANTASY), 0, 0, world.width, world.height / 4, 3),
                    "seed$seed-glacier-northland.png"
                )
                write(
                    crop(render(bare, Mode.FANTASY), 0, 0, world.width, world.height / 4, 3),
                    "seed$seed-glacier-northland-off.png"
                )
                write(render(world, Mode.FANTASY), "seed$seed-fantasy.png")
                write(render(world, Mode.BIOME), "seed$seed-biome.png")

                // The standing check, and the one that matters. Every crop in this file used to be
                // 512, which is how the D8 lattice got through review: at 512 it reads as scattered
                // lakes and short streaks, and at 1024 — the resolution the desktop app actually
                // opens at — it is a cross-hatched mesh over every cold region. Same world, same
                // config path the app uses, twice the grid.
                val fine = WorldGenerationEngine.generateBlocking(config.atResolution(1024, 1024))
                val bareFine = WorldGenerationEngine.generateBlocking(
                    config.atResolution(1024, 1024)
                        .let { it.copy(glaciation = it.glaciation.copy(enabled = false)) }
                )
                write(
                    crop(render(fine, Mode.FANTASY), 410, 40, 340, 255, 3),
                    "seed$seed-glacier-1024-northland.png"
                )
                write(
                    crop(render(bareFine, Mode.FANTASY), 410, 40, 340, 255, 3),
                    "seed$seed-glacier-1024-northland-off.png"
                )
                val fineLakeCells = fine.rivers.lakes.lakeId.count { it >= 0 }
                val coarseLakeCells = world.rivers.lakes.lakeId.count { it >= 0 }
                println(
                    "GLACIER seed $seed resolution invariance:" +
                        " 512 ${coarseLakeCells} lake cells of ${world.sea.landCellCount} land" +
                        " (${"%.2f".format(coarseLakeCells * 100f / world.sea.landCellCount)}%)," +
                        " 1024 ${fineLakeCells} of ${fine.sea.landCellCount}" +
                        " (${"%.2f".format(fineLakeCells * 100f / fine.sea.landCellCount)}%);" +
                        " lakes ${world.rivers.lakes.lakes.size} against" +
                        " ${fine.rivers.lakes.lakes.size}"
                )
            }
            // The northern and southern cold belts, full width, top and bottom eighth of the map.
            listOf("north" to 0, "south" to world.height * 7 / 8).forEach { (half, top) ->
                write(
                    crop(render(world, Mode.FANTASY), 0, top, world.width, world.height / 8, 4),
                    "seed$seed-glacier-$half.png"
                )
                write(
                    crop(render(bare, Mode.FANTASY), 0, top, world.width, world.height / 8, 4),
                    "seed$seed-glacier-$half-off.png"
                )
            }
            println(
                "GLACIER seed $seed: ${world.rivers.lakes.lakes.size} lakes " +
                    "(${bare.rivers.lakes.lakes.size} without ice), " +
                    "${world.rivers.rivers.size} rivers (${bare.rivers.rivers.size})"
            )
        }
        println("Glacier crops written to ${outputDir.absolutePath}")
    }

    /**
     * A hotspot chain's cones, close up, with [TectonicsConfig.hotspotConeDetail] on and off.
     *
     * A single seamount is only a handful of cells across, which is invisible at whole-world
     * scale and exactly where a rasterized-circle artefact would live if there were one. Seed
     * 718106 carries a chain, with the author's settings (sea level 0.62, 14 plates, 12 realms
     * are all defaults). 1024 and 2048 are reached via [WorldGenConfig.atResolution] from the 512
     * base, exactly as the app does, so [TectonicsConfig.hotspotRadius] and friends scale up with
     * the grid rather than staying pinned to their 512 cell count -- a plain `WorldGenConfig(width
     * = 2048, ...)` would not rescale them and the cone would come out the same handful of cells
     * across at every resolution instead of genuinely finer or coarser. The vent is located fresh
     * at each resolution from the with/without-chains plate height difference, rather than scaled
     * from a lower-resolution position, because the plate RNG is not a simple rescaling between
     * resolutions.
     */
    @Test
    fun `dump the hotspot cone`() {
        outputDir.mkdirs()
        val seed = 718106L
        val base512 = WorldGenConfig(seed = seed, width = 512, height = 512)

        listOf(512, 1024, 2048).forEach { size ->
            val base = if (size == 512) base512 else base512.atResolution(size, size)
            val radiusScale = size / 512

            listOf(false to "before", true to "after").forEach { (detail, tag) ->
                val config = base.copy(tectonics = base.tectonics.copy(hotspotConeDetail = detail))
                val terrain = TerrainStage.generate(config)
                val withChains = PlateStage.generate(config, terrain)
                val without = config.copy(tectonics = config.tectonics.copy(hotspotPlateFraction = 0f))
                val flat = PlateStage.generate(without, TerrainStage.generate(without))

                var peakI = -1
                var peakV = 0f
                for (i in withChains.height.data.indices) {
                    val d = withChains.height.data[i] - flat.height.data[i]
                    if (d > peakV) { peakV = d; peakI = i }
                }
                val px = peakI % size
                val py = peakI / size

                // The raw cone, isolated: with-chains minus without-chains, before erosion or any
                // other stage touches it, so whatever shape the stamp itself makes is what shows.
                // The window scales with the cone's own radius so the crop frames it the same way
                // at every resolution instead of clipping it at 2048 or drowning it in margin at
                // 512.
                val cellsAcross = 24 * radiusScale
                val zoom = (480 / cellsAcross).coerceAtLeast(1)
                val image = BufferedImage(cellsAcross * zoom, cellsAcross * zoom, BufferedImage.TYPE_INT_RGB)
                for (yy in 0 until cellsAcross) {
                    for (xx in 0 until cellsAcross) {
                        val sx = (px - cellsAcross / 2 + xx).coerceIn(0, size - 1)
                        val sy = (py - cellsAcross / 2 + yy).coerceIn(0, size - 1)
                        val d = (withChains.height.data[sy * size + sx] - flat.height.data[sy * size + sx])
                            .coerceIn(0f, peakV)
                        val grey = (255 * (d / peakV)).toInt().coerceIn(0, 255)
                        val rgb = (grey shl 16) or (grey shl 8) or grey
                        for (by in 0 until zoom) for (bx in 0 until zoom) {
                            image.setRGB(xx * zoom + bx, yy * zoom + by, rgb)
                        }
                    }
                }
                write(image, "seed$seed-hotspot-cone-isolated-$size-$tag.png")

                // The same vent as it actually renders in the finished world -- through erosion,
                // sea level and biomes -- so a change to the raw stamp can be checked against what
                // the player would see, not just against the isolated diagnostic above. Only at
                // 1024 (the spec's whole-pipeline render size): a full engine run at three
                // resolutions times two states is unneeded cost the isolated crop above already
                // covers for 512 and 2048.
                if (size == 1024) {
                    val world = WorldGenerationEngine.generateBlocking(config)
                    val span = 90
                    write(
                        crop(
                            render(world, Mode.ELEVATION),
                            (px - span / 2).coerceIn(0, size - span), (py - span / 2).coerceIn(0, size - span),
                            span, span, 6
                        ),
                        "seed$seed-hotspot-$size-$tag.png"
                    )
                }
            }
        }
        println("Hotspot cone crops written to ${outputDir.absolutePath}")
    }

    /**
     * E4, before and after: seed 59758's long continental rift with the segmentation off and on.
     *
     * This is the seed the author circled at 2048 — a sinuous twenty-to-one strait of uniform width
     * running the whole length of a rift. The whole-map fantasy view says whether the seam still
     * reads as a channel, and the crop is aimed where the two worlds actually differ: the largest
     * cluster of cells that changed between land and water when the rift was segmented. That finds
     * the drowned segments and the sills between them wherever they fell, rather than at a
     * remembered coordinate or at the rift's bounding-box centre, which is out in open ocean.
     */
    @Test
    fun `dump the segmented rift`() {
        outputDir.mkdirs()
        val seed = 59758L
        val rift = com.cartogenesis.worldgen.pipeline.BoundaryClass.CONTINENTAL_RIFT.ordinal

        listOf(512, 1024).forEach { size ->
            val base = WorldGenConfig(seed = seed, width = 512, height = 512)
                .let { if (size == 512) it else it.atResolution(size, size) }
            val worlds = listOf(false, true).map { segmented ->
                WorldGenerationEngine.generateBlocking(
                    base.copy(tectonics = base.tectonics.copy(riftSegmentation = segmented))
                )
            }

            // Ground inside the rift trough that the segmentation lifted out of the water: the
            // sills, and the shoulders the half-grabens raised. The largest connected cluster of
            // it is the window both crops use, so the two pictures frame the same ground.
            val changed = BooleanArray(size * size) { i ->
                !worlds[0].sea.isLand[i] && worlds[1].sea.isLand[i] &&
                    worlds[1].plates.nearestBoundaryClass[i] == rift &&
                    worlds[1].plates.boundaryDistance.data[i] <= base.tectonics.riftShoulderOffset
            }
            val span = (size / 4).coerceAtLeast(64)
            val seen = BooleanArray(size * size)
            var best = 0
            var cx = 0
            var cy = 0
            for (start in 0 until size * size) {
                if (!changed[start] || seen[start]) continue
                var count = 0
                var sumX = 0L
                var sumY = 0L
                val stack = ArrayDeque<Int>()
                seen[start] = true
                stack.add(start)
                while (stack.isNotEmpty()) {
                    val i = stack.removeLast()
                    count++
                    sumX += (i % size).toLong()
                    sumY += (i / size).toLong()
                    val x = i % size
                    val y = i / size
                    for (dy in -2..2) {
                        val ny = y + dy
                        if (ny < 0 || ny >= size) continue
                        for (dx in -2..2) {
                            val n = ny * size + ((x + dx + size) % size)
                            if (changed[n] && !seen[n]) { seen[n] = true; stack.add(n) }
                        }
                    }
                }
                if (count > best) {
                    best = count
                    cx = ((sumX / count).toInt() - span / 2).coerceIn(0, size - span)
                    cy = ((sumY / count).toInt() - span / 2).coerceIn(0, size - span)
                }
            }

            worlds.forEachIndexed { index, world ->
                val tag = if (index == 0) "before" else "after"
                write(render(world, Mode.FANTASY), "rift-seed$seed-$size-$tag-fantasy.png")
                write(render(world, Mode.ELEVATION), "rift-seed$seed-$size-$tag-elevation.png")
                write(
                    crop(render(world, Mode.FANTASY), cx, cy, span, span, 4),
                    "rift-seed$seed-$size-$tag-crop.png"
                )
                if (index == 1) {
                    write(
                        render(world, Mode.BOUNDARY_CLASS),
                        "rift-seed$seed-$size-boundaries.png"
                    )
                }

                var cells = 0
                var flooded = 0
                for (i in 0 until size * size) {
                    if (world.plates.nearestBoundaryClass[i] != rift) continue
                    if (world.plates.boundaryDistance.data[i] > base.tectonics.riftWidth) continue
                    cells++
                    if (!world.sea.isLand[i]) flooded++
                }
                println(
                    ("RIFTMAP seed %d %d %s: trough %d cells, %d%% of it under water, %d%% land, " +
                        "%d cells changed, crop at %d,%d")
                        .format(
                            seed, size, tag, cells, flooded * 100 / cells.coerceAtLeast(1),
                            (world.landFraction() * 100).toInt(), best, cx, cy
                        )
                )
            }
        }
        println("Rift maps written to ${outputDir.absolutePath}")
    }

    /**
     * E2, before and after: the same worlds with the lake water balance off and on.
     *
     * Seed 43 carries the largest basin in dry country found by searching 1..120 — 1775 cells at
     * (416,384) at 512, about 172 mm of rain a year — so it is rendered whole and again as a crop
     * around that basin, where the difference between a filled bowl and a desert lake is a thing
     * you can see rather than a number. Seed 718106 is the author's world at the resolution the
     * desktop app opens at.
     */
    @Test
    fun `dump the lake water balance`() {
        outputDir.mkdirs()

        fun pair(seed: Long, size: Int): Pair<WorldMap, WorldMap> {
            val base = WorldGenConfig(seed = seed, width = size, height = size)
            val off = WorldGenerationEngine.generateBlocking(
                base.copy(lakes = base.lakes.copy(waterBalance = false))
            )
            val on = WorldGenerationEngine.generateBlocking(base)
            return off to on
        }

        // The window around the biggest basin the balance drained, so the crop finds it whatever
        // the world does rather than being aimed at a remembered coordinate.
        fun basinWindow(off: WorldMap, on: WorldMap, span: Int): Triple<Int, Int, Int> {
            val shrunk = off.rivers.lakes.lakes.maxByOrNull { lake ->
                val cells = off.rivers.lakes.lakeId.indices.count { off.rivers.lakes.lakeId[it] == lake.id }
                val wet = off.rivers.lakes.lakeId.indices.count {
                    off.rivers.lakes.lakeId[it] == lake.id && on.rivers.lakes.isLake(it)
                }
                cells - wet
            } ?: return Triple(0, 0, span)
            val cells = off.rivers.lakes.lakeId.indices.filter { off.rivers.lakes.lakeId[it] == shrunk.id }
            val cx = cells.map { it % off.width }.average().toInt()
            val cy = cells.map { it / off.width }.average().toInt()
            return Triple(
                (cx - span / 2).coerceIn(0, off.width - span),
                (cy - span / 2).coerceIn(0, off.height - span),
                span
            )
        }

        val (dryOff, dryOn) = pair(43L, 512)
        write(render(dryOff, Mode.FANTASY), "balance-seed43-off.png")
        write(render(dryOn, Mode.FANTASY), "balance-seed43-on.png")
        val (bx, by, span) = basinWindow(dryOff, dryOn, 180)
        write(crop(render(dryOff, Mode.FANTASY), bx, by, span, span, 4), "balance-seed43-basin-off.png")
        write(crop(render(dryOn, Mode.FANTASY), bx, by, span, span, 4), "balance-seed43-basin-on.png")
        println("BALANCE-RENDER seed 43 basin crop at ($bx,$by) ${span}x$span")

        val (authorOff, authorOn) = pair(718106L, 1024)
        write(render(authorOff, Mode.FANTASY), "balance-seed718106-1024-off.png")
        write(render(authorOn, Mode.FANTASY), "balance-seed718106-1024-on.png")
        write(render(authorOn, Mode.BIOME), "balance-seed718106-1024-biome.png")
        val (ax, ay, aspan) = basinWindow(authorOff, authorOn, 300)
        write(crop(render(authorOff, Mode.FANTASY), ax, ay, aspan, aspan, 3), "balance-seed718106-basin-off.png")
        write(crop(render(authorOn, Mode.FANTASY), ax, ay, aspan, aspan, 3), "balance-seed718106-basin-on.png")
        println("BALANCE-RENDER seed 718106 basin crop at ($ax,$ay) ${aspan}x$aspan")

        listOf(7L, 42L, 1234L).forEach { seed ->
            val (off, on) = pair(seed, 512)
            write(render(off, Mode.FANTASY), "balance-seed$seed-off.png")
            write(render(on, Mode.FANTASY), "balance-seed$seed-on.png")
        }

        listOf(dryOff to "43 off", dryOn to "43 on", authorOff to "718106 off", authorOn to "718106 on")
            .forEach { (world, label) ->
                val lakes = world.rivers.lakes
                println(
                    "BALANCE-RENDER $label: ${lakes.lakes.size} lakes, " +
                        "${lakes.lakeId.count { it >= 0 }} lake cells, " +
                        "${lakes.playa.count { it }} playa cells, " +
                        "${lakes.lakes.count { it.endorheic }} endorheic, " +
                        "${world.rivers.rivers.size} rivers"
                )
            }
        println("Balance renders written to ${outputDir.absolutePath}")
    }

    /**
     * The lakes, close up, with the channels drawn over them.
     *
     * [drawRivers] draws every segment of every river, lakes included, which is how the artefact
     * this exists for became visible: under a lake the routing runs on the depression-filled
     * surface, and that surface is flat to within the 1e-6 the fill nudges a flat cell by as the
     * priority flood passes over it. The flood takes equal ground in cell-index order, so the nudge
     * grows west to east and north to south, and D8 reads a gradient of one nudge per cell pointing
     * due east or due south — which beats every diagonal, whose drop is divided by the root of two.
     * Every row of the lake did the same thing. On seed 59758 at 2048 that was four horizontal runs
     * of 36 to 44 cells across one 2163-cell lake and a vertical one of 44 across another; on
     * 718106, 45 cells due west across a third.
     *
     * The crops are aimed at whichever lake the world actually has rather than at a remembered
     * coordinate, so they keep finding it when the terrain moves.
     */
    @Test
    fun `dump the lake crossings`() {
        outputDir.mkdirs()

        listOf(59758L, 718106L).forEach { seed ->
            val world = WorldGenerationEngine.generateBlocking(
                WorldGenConfig(seed = seed, width = 512, height = 512, seaLevel = 0.62f)
                    .atResolution(1024, 1024)
            )
            val lakes = world.rivers.lakes
            var crossings = 0
            var longest = 0
            world.rivers.rivers.forEach { river ->
                var run = 0
                river.cells.forEach { cell ->
                    if (lakes.isLake(cell)) {
                        crossings++
                        run++
                        if (run > longest) longest = run
                    } else {
                        run = 0
                    }
                }
            }

            val image = render(world, Mode.FANTASY)
            lakes.lakes.sortedByDescending { it.cellCount }.take(2).forEachIndexed { rank, lake ->
                val cells = (0 until world.width * world.height).filter { lakes.lakeId[it] == lake.id }
                if (cells.isEmpty()) return@forEachIndexed
                val span = 160
                val cx = (cells.map { it % world.width }.average().toInt() - span / 2)
                    .coerceIn(0, world.width - span)
                val cy = (cells.map { it / world.width }.average().toInt() - span / 2)
                    .coerceIn(0, world.height - span)
                write(crop(image, cx, cy, span, span, 4), "crossing-seed$seed-lake$rank.png")
                println(
                    "CROSSING seed $seed lake $rank: ${lake.cellCount} cells, crop at ($cx,$cy)"
                )
            }
            println(
                "CROSSING seed $seed at 1024: $crossings drawn river cells on a lake, " +
                    "longest unbroken run across water $longest"
            )
        }
        println("Lake crossing crops written to ${outputDir.absolutePath}")
    }

    /**
     * G4: the two places the distance transform's contours are visible — the outer edge of a
     * collision plateau, and the shelf break — on seed 42 at 1024, whole and at 4x.
     *
     * Both crops are located from the world itself rather than from remembered coordinates, on a
     * 64-cell lattice so the window cannot slide about between two runs of a slightly different
     * generator: the plateau window holds the most cells sitting on a collision plateau's *outer
     * rim* (boundary distance within a fifth of `collisionWidth` of it), and the shelf window the
     * most ocean cells on the continental *slope* — distance to land between one and two
     * `shelfWidth`, the band `SeaLevelStage` smoothsteps back down to the natural sea floor.
     * Those two bands are the iso-contours of the distance field, which is what this is looking
     * at. The chosen corners are printed so a before-and-after pair can be checked to be looking
     * at the same ground.
     */
    /**
     * H1, before and after: the same worlds with the tectonic history off and on.
     *
     * "Off" is `historyEpochs = 1`, which is the generator this chunk replaced bit for bit, so the
     * pair of elevation maps is a genuine before-and-after of the same seed rather than two
     * different worlds. The plates view says where the present boundaries are, and the crust-age
     * view says which epoch built which country — the picture to look for is an old worn range
     * standing well inside a plate interior, with a young sharp one on the edge beyond it.
     */
    @Test
    fun `dump the tectonic history`() {
        outputDir.mkdirs()
        listOf(7L, 42L, 1234L).forEach { seed ->
            val base = WorldGenConfig(seed = seed, width = 512, height = 512)
                .atResolution(1024, 1024)
            listOf(1 to "before", base.tectonics.historyEpochs to "after").forEach { (epochs, tag) ->
                val config = base.copy(tectonics = base.tectonics.copy(historyEpochs = epochs))
                val world = WorldGenerationEngine.generateBlocking(config)
                write(render(world, Mode.ELEVATION), "history-seed$seed-1024-$tag-elevation.png")
                write(render(world, Mode.PLATES), "history-seed$seed-1024-$tag-plates.png")
                write(render(world, Mode.FANTASY), "history-seed$seed-1024-$tag-fantasy.png")
                if (epochs > 1) {
                    write(render(world, Mode.CRUST_AGE), "history-seed$seed-1024-crustage.png")
                }

                // How much of the land each epoch built, and how much of it is cratonic — the
                // tally behind the pictures.
                val k = config.tectonics.historyEpochs.coerceAtLeast(1)
                val bands = IntArray(k + 1)
                var land = 0
                for (i in world.sea.isLand.indices) {
                    if (!world.sea.isLand[i]) continue
                    land++
                    val age = world.plates.crustAge.data[i]
                    bands[if (age >= 1f) k else (age * k).toInt().coerceIn(0, k - 1)]++
                }
                val text = (0 until k).joinToString(" ") {
                    "%d-ago %d%%".format(it, bands[it] * 100 / land.coerceAtLeast(1))
                }
                println(
                    "HISTORYMAP seed %d %s: %d%% land, %d rivers, crust %s cratonic %d%%".format(
                        seed, tag, (world.landFraction() * 100).toInt(),
                        world.rivers.rivers.size, text, bands[k] * 100 / land.coerceAtLeast(1)
                    )
                )
            }
        }
        println("History maps written to ${outputDir.absolutePath}")
    }

    @Test
    fun `dump the distance field edges`() {
        outputDir.mkdirs()
        val size = 1024
        val config = WorldGenConfig(seed = 42L, width = 512, height = 512).atResolution(size, size)
        val world = WorldGenerationEngine.generateBlocking(config)
        val image = render(world, Mode.ELEVATION)
        write(image, "distfield-seed42-$size-elevation.png")

        val plateau = com.cartogenesis.worldgen.pipeline.BoundaryClass.COLLISION_PLATEAU.ordinal
        val span = 160

        fun bestWindow(interesting: (Int) -> Boolean): Pair<Int, Int> {
            var bestX = 0
            var bestY = 0
            var best = -1
            var y = 0
            while (y + span <= size) {
                var x = 0
                while (x + span <= size) {
                    var count = 0
                    for (yy in y until y + span step 2) {
                        for (xx in x until x + span step 2) {
                            if (interesting(yy * size + xx)) count++
                        }
                    }
                    if (count > best) { best = count; bestX = x; bestY = y }
                    x += 64
                }
                y += 64
            }
            return bestX to bestY
        }

        val rim = config.tectonics.collisionWidth
        val (px, py) = bestWindow { i ->
            world.sea.isLand[i] && world.plates.nearestBoundaryClass[i] == plateau &&
                abs(world.plates.boundaryDistance.data[i] - rim) < rim * 0.2f
        }

        // Distance to land, the field the shelf remap is keyed on, by the transform the pipeline
        // itself uses.
        val toLand = FloatArray(size * size) { JumpFloodDistance.INFINITE }
        val label = IntArray(size * size) { -1 }
        for (i in 0 until size * size) {
            if (world.sea.isLand[i]) { toLand[i] = 0f; label[i] = i }
        }
        JumpFloodDistance.run(size, size, toLand, label)
        val shelf = config.sea.shelfWidth
        val (sx, sy) = bestWindow { i ->
            !world.sea.isLand[i] && toLand[i] > shelf && toLand[i] <= 2f * shelf
        }
        write(crop(image, px, py, span, span, 4), "distfield-seed42-$size-plateau.png")
        write(crop(image, sx, sy, span, span, 4), "distfield-seed42-$size-shelf.png")
        write(
            crop(render(world, Mode.PLATES), px, py, span, span, 4),
            "distfield-seed42-$size-plateau-plates.png"
        )

        // The fields themselves, banded every two cells: the contours the two crops above inherit
        // their shape from, drawn so a facet is a straight run of band edge rather than something
        // to be inferred from a shaded slope.
        write(
            crop(bands(world.plates.boundaryDistance.data, size, size), px, py, span, span, 4),
            "distfield-seed42-$size-plateau-contours.png"
        )
        write(
            crop(bands(toLand, size, size), sx, sy, span, span, 4),
            "distfield-seed42-$size-shelf-contours.png"
        )
        // The same window, same seeds, by the chamfer transform: the octagonal contours the shelf
        // used to be cut from, drawn in whichever run this is so the pair can be compared without
        // reverting anything.
        val chamfer = FloatArray(size * size) { DistanceTransform.INFINITE }
        val chamferLabel = IntArray(size * size) { -1 }
        for (i in 0 until size * size) {
            if (world.sea.isLand[i]) { chamfer[i] = 0f; chamferLabel[i] = i }
        }
        DistanceTransform.run(size, size, chamfer, chamferLabel)
        write(
            crop(bands(chamfer, size, size), sx, sy, span, span, 4),
            "distfield-seed42-$size-shelf-contours-chamfer.png"
        )
        println(
            "DISTFIELD seed 42 at $size: plateau crop at ($px,$py), shelf crop at ($sx,$sy), " +
                "land=${world.sea.landCellCount}"
        )
    }

    /** A distance field as alternating two-cell bands — its iso-contours, drawn. */
    private fun bands(field: FloatArray, width: Int, height: Int): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (i in field.indices) {
            val d = field[i]
            val band = (d / 2f).toInt()
            val shade = if (band % 2 == 0) 210 else 70
            val tint = ((d / 60f).coerceIn(0f, 1f) * 60).toInt()
            image.setRGB(
                i % width, i / width,
                ((shade - tint).coerceIn(0, 255) shl 16) or
                    (shade shl 8) or ((shade + tint).coerceIn(0, 255))
            )
        }
        return image
    }

    /** A rectangle of an image, blown up by [zoom] with no smoothing, so cells stay cells. */
    private fun crop(
        source: BufferedImage,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        zoom: Int
    ): BufferedImage {
        val out = BufferedImage(width * zoom, height * zoom, BufferedImage.TYPE_INT_RGB)
        for (oy in 0 until height * zoom) {
            for (ox in 0 until width * zoom) {
                val sx = (x + ox / zoom).coerceIn(0, source.width - 1)
                val sy = (y + oy / zoom).coerceIn(0, source.height - 1)
                out.setRGB(ox, oy, source.getRGB(sx, sy))
            }
        }
        return out
    }

    /** The wind vector field, drawn over whichever ground [render] laid down. */
    private fun drawWind(world: WorldMap, image: BufferedImage) {
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val w = world.width
        val spacing = (w / 24).coerceAtLeast(6)
        val reach = spacing * 0.45f
        var y = spacing / 2
        while (y < world.height) {
            var x = spacing / 2
            while (x < w) {
                val i = y * w + x
                val dx = world.climate.windDirection[i].toFloat()
                val dy = world.climate.windMeridional.data[i]
                val length = sqrt(dx * dx + dy * dy)
                val ux = dx / length
                val uy = dy / length
                g.color = if (dx > 0) Color(0x7FC0F0) else Color(0xF0A860)
                g.stroke = BasicStroke(1.4f)
                val x0 = x - ux * reach
                val y0 = y - uy * reach
                val x1 = x + ux * reach
                val y1 = y + uy * reach
                g.drawLine(x0.toInt(), y0.toInt(), x1.toInt(), y1.toInt())
                // A head, so the arrow says which way it points rather than merely how it lies.
                g.drawLine(
                    x1.toInt(), y1.toInt(),
                    (x1 - (ux * 0.8f + uy * 0.5f) * reach * 0.5f).toInt(),
                    (y1 - (uy * 0.8f - ux * 0.5f) * reach * 0.5f).toInt()
                )
                g.drawLine(
                    x1.toInt(), y1.toInt(),
                    (x1 - (ux * 0.8f - uy * 0.5f) * reach * 0.5f).toInt(),
                    (y1 - (uy * 0.8f + ux * 0.5f) * reach * 0.5f).toInt()
                )
                x += spacing
            }
            y += spacing
        }
        g.dispose()
    }

    /** Mirrors RiverStage's threshold maths so the network can be inspected from outside. */
    private fun riverDiagnostics(world: WorldMap): String {
        val acc = world.rivers.flowAccumulation.data
        val land = world.sea.isLand
        var totalRunoff = 0f
        for (i in acc.indices) {
            if (land[i]) totalRunoff += 0.05f + world.climate.precipitation.data[i]
        }
        val threshold = (totalRunoff * world.config.rivers.sourceThreshold).coerceAtLeast(1e-4f)

        val channel = BooleanArray(acc.size) { land[it] && acc[it] >= threshold }
        val channelCount = channel.count { it }

        val hasUpstream = BooleanArray(acc.size)
        for (i in acc.indices) {
            if (!channel[i]) continue
            val t = world.rivers.flowTarget[i]
            if (t >= 0 && channel[t]) hasUpstream[t] = true
        }
        val sources = (acc.indices).count { channel[it] && !hasUpstream[it] }
        val traced = world.rivers.rivers.sumOf { it.length }

        // Degenerate drainage — every cell on a plain flowing the same way — shows up as a spike
        // in one compass direction. A healthy dendritic network is spread across all eight.
        val w = world.width
        val directions = IntArray(9)
        var routed = 0
        for (i in acc.indices) {
            if (!land[i]) continue
            val t = world.rivers.flowTarget[i]
            if (t < 0) { directions[8]++; continue }
            var dx = (t % w) - (i % w)
            if (dx > w / 2) dx -= w
            if (dx < -w / 2) dx += w
            val dy = (t / w) - (i / w)
            directions[(dy + 1) * 3 + (dx + 1)]++
            routed++
        }
        val worst = directions.take(8).max() * 100 / routed.coerceAtLeast(1)

        return "threshold=%.1f channels=%d sources=%d tracedCells=%d meanLen=%d topDir=%d%%"
            .format(
                threshold, channelCount, sources, traced,
                if (world.rivers.rivers.isEmpty()) 0 else traced / world.rivers.rivers.size,
                worst
            )
    }

    /** What the land is made of, in order, so a new biome class can be seen to have arrived. */
    private fun reportBiomeShares(world: WorldMap) {
        // A sorted array rather than a map, because the printed order should be the same on every
        // run and a hash map's is not.
        val counts = IntArray(Biome.entries.size)
        var land = 0
        for (i in world.climate.biome.indices) {
            if (!world.sea.isLand[i]) continue
            land++
            counts[world.climate.biome[i].ordinal]++
        }
        Biome.entries
            .filter { counts[it.ordinal] > 0 }
            .sortedByDescending { counts[it.ordinal] }
            .forEach {
                println(
                    "  BIOME ${it.name}: ${counts[it.ordinal]} cells, " +
                        "${"%.1f".format(counts[it.ordinal] * 100.0 / land.coerceAtLeast(1))}% of land"
                )
            }
    }

    private fun write(image: BufferedImage, name: String) {
        ImageIO.write(image, "png", File(outputDir, name))
    }

    private fun render(world: WorldMap, mode: Mode): BufferedImage {
        val w = world.width
        val h = world.height
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val shade = hillshade(world)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val land = world.sea.isLand[i]
                val rel = world.sea.relativeElevation.data[i]

                // Standing fresh water sits on top of whatever the land would have been. Depth
                // comes off the lake's own surface, not the fill: an endorheic lake stands below
                // the brim its basin was raised to.
                if (land && world.rivers.lakes.isLake(i) &&
                    (mode == Mode.FANTASY || mode == Mode.ELEVATION)
                ) {
                    val depth = world.rivers.lakes.surfaceAt(i) - rel
                    image.setRGB(x, y, mix(0x4E92B4, 0x2F6B8C, (depth * 12f).coerceIn(0f, 1f)))
                    continue
                }

                // A basin too dry to hold water at all. Pale salt, so the flats can be told from
                // the desert around them while E2's follow-up decides how to draw them properly.
                if (land && world.rivers.lakes.isPlaya(i) &&
                    (mode == Mode.FANTASY || mode == Mode.ELEVATION)
                ) {
                    image.setRGB(x, y, 0xEDE6D6)
                    continue
                }

                var rgb = when (mode) {
                    Mode.FANTASY ->
                        if (land) mix(landColor(rel), biomeColor(world.climate.biome[i]), 0.45f)
                        else oceanColor(-rel)

                    Mode.ELEVATION -> if (land) landColor(rel) else oceanColor(-rel)

                    Mode.PLATES -> {
                        val edge = (world.plates.boundaryDistance.data[i] / 12f).coerceIn(0f, 1f)
                        mix(
                            boundaryClassColor(world.plates.nearestBoundaryClass[i]),
                            plateColor(world.plates.plateId[i]),
                            edge
                        )
                    }

                    Mode.BOUNDARY_CLASS -> {
                        // Saturated on the boundary and washing out into the plate interiors, with
                        // the land/sea split kept so the relief can be matched against its cause.
                        val fade = (world.plates.boundaryDistance.data[i] /
                            (world.width * 0.11f)).coerceIn(0f, 1f)
                        mix(
                            boundaryClassColor(world.plates.nearestBoundaryClass[i]),
                            if (land) 0xF2EFE6 else 0xB6C6D2,
                            fade
                        )
                    }

                    // Warm where the crust is young and cold where it is old, with cratonic
                    // ground left grey: the map of what built each piece of country and when.
                    Mode.CRUST_AGE -> {
                        val age = world.plates.crustAge.data[i]
                        val tint = if (age >= 1f) 0x8C8C86
                        else if (age < 0.5f) mix(0xC1272D, 0xE0A020, age * 2f)
                        else mix(0xE0A020, 0x2E6B8C, (age - 0.5f) * 2f)
                        if (land) tint else mix(tint, 0x14202C, 0.62f)
                    }

                    Mode.BIOME -> biomeColor(world.climate.biome[i])

                    Mode.RAINFALL ->
                        if (land) grad(world.climate.precipitation.data[i], 0xE8D9A8, 0x1F4E79)
                        else 0x20303C

                    Mode.TEMPERATURE ->
                        grad(
                            ((world.climate.temperature.data[i] + 30f) / 70f).coerceIn(0f, 1f),
                            0x3B4CC0, 0xB40426
                        )

                    // Black water rather than the dark slate the annual view uses: the wet end of
                    // the rainfall ramp is itself a deep blue, and against slate a soaked coast
                    // and the sea beside it were the same colour, which is precisely the thing
                    // these two maps exist to let you tell apart.
                    Mode.SUMMER_RAINFALL ->
                        if (land) grad(world.climate.summerPrecipitation.data[i], 0xE8D9A8, 0x1F4E79)
                        else 0x000000

                    Mode.WINTER_RAINFALL ->
                        if (land) grad(world.climate.winterPrecipitation.data[i], 0xE8D9A8, 0x1F4E79)
                        else 0x000000

                    // Which half of the year the rain arrives in, rather than how much of it
                    // there is: red where the warm half dominates, blue where the cold half does.
                    // A monsoon coast is a red band with the sea on its equatorward side.
                    Mode.SEASON_CONTRAST -> if (!land) 0x000000 else {
                        val summer = world.climate.summerPrecipitation.data[i]
                        val winter = world.climate.winterPrecipitation.data[i]
                        val lopsided = kotlin.math.ln(
                            ((summer + 0.02f) / (winter + 0.02f)).toDouble()
                        ).toFloat() / kotlin.math.ln(3.0).toFloat()
                        if (lopsided >= 0f) mix(0xF2F0E6, 0xB32020, lopsided)
                        else mix(0xF2F0E6, 0x1F4E79, -lopsided)
                    }

                    // The wind vector, as a coarse arrow field over a faint land/sea ground.
                    Mode.WIND -> if (land) 0x3A3A34 else 0x14202C

                    Mode.SUMMER_TEMPERATURE ->
                        grad(
                            ((world.climate.summerTemperature.data[i] + 30f) / 70f).coerceIn(0f, 1f),
                            0x3B4CC0, 0xB40426
                        )

                    Mode.WINTER_TEMPERATURE ->
                        grad(
                            ((world.climate.winterTemperature.data[i] + 30f) / 70f).coerceIn(0f, 1f),
                            0x3B4CC0, 0xB40426
                        )

                    Mode.HABITABILITY ->
                        if (land) grad(world.nations.habitability.data[i], 0x5B3A29, 0x9BE564)
                        else 0x18262F

                    Mode.NATIONS -> {
                        val owner = world.nations.nationId[i]
                        when {
                            !land -> 0x16405F
                            owner < 0 -> 0x6E6A5E // unclaimed wilderness
                            else -> mix(nationColor(owner), landColor(rel), 0.35f)
                        }
                    }

                    Mode.CULTURES -> {
                        val people = world.cultures.cultureId[i]
                        when {
                            !land -> 0x16405F
                            people < 0 -> 0x6E6A5E // nobody lives here
                            else -> mix(cultureColor(people), landColor(rel), 0.35f)
                        }
                    }

                    Mode.NORMALS -> {
                        val n = world.terrain.normals.normalAt(x, y)
                        rgbOf(
                            ((n[0] * 0.5f + 0.5f) * 255).toInt(),
                            ((n[1] * 0.5f + 0.5f) * 255).toInt(),
                            ((n[2] * 0.5f + 0.5f) * 255).toInt()
                        )
                    }
                }

                if (land && (mode == Mode.FANTASY || mode == Mode.ELEVATION)) {
                    rgb = shadeBy(rgb, shade[i])
                }
                image.setRGB(x, y, rgb)
            }
        }

        if (mode == Mode.FANTASY || mode == Mode.ELEVATION) drawRivers(world, image)
        if (mode == Mode.WIND) drawWind(world, image)
        return image
    }

    private fun drawRivers(world: WorldMap, image: BufferedImage) {
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0x3C7EA8)
        val w = world.width

        world.rivers.rivers.forEach { river ->
            for (k in 0 until river.cells.size - 1) {
                val from = river.cells[k]
                val to = river.cells[k + 1]
                val x0 = from % w
                val x1 = to % w
                if (abs(x1 - x0) > w / 2) continue
                g.stroke = BasicStroke(river.widths[k].coerceAtLeast(0.9f))
                g.drawLine(x0, from / w, x1, to / w)
            }
        }
        g.dispose()
    }

    private fun hillshade(world: WorldMap): FloatArray {
        val w = world.width
        val h = world.height
        val e = world.sea.relativeElevation
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = (e.sample(x + 1, y) - e.sample(x - 1, y)) * 12f
                val dy = (e.sample(x, y + 1) - e.sample(x, y - 1)) * 12f
                val len = sqrt(dx * dx + dy * dy + 1f)
                val dot = (dx * 0.6f + dy * 0.6f + 0.53f) / len
                out[y * w + x] = (0.72f + 0.55f * dot).coerceIn(0.45f, 1.35f)
            }
        }
        return out
    }

    private fun landColor(elevation: Float): Int = ramp(
        elevation,
        intArrayOf(0x9DBE7A, 0x8AAE63, 0xB9C070, 0xC8B072, 0xA98A63, 0x8A6F58, 0x7C6656, 0xEDEDE8)
    )

    private fun oceanColor(depth: Float): Int = ramp(
        1f - depth.coerceIn(0f, 1f),
        intArrayOf(0x0B2239, 0x11395B, 0x1B5479, 0x2B7398, 0x57A5C4)
    )

    private fun biomeColor(biome: Biome): Int = when (biome) {
        Biome.OCEAN -> 0x16405F
        Biome.SHALLOW_OCEAN -> 0x3C82A8
        Biome.ICE_SHEET -> 0xEFF4F7
        Biome.TUNDRA -> 0xB5BBA6
        Biome.TAIGA -> 0x5C7A5C
        Biome.TEMPERATE_FOREST -> 0x4F7B45
        Biome.TEMPERATE_RAINFOREST -> 0x2F5F3C
        Biome.GRASSLAND -> 0xB3BF6E
        Biome.SHRUBLAND -> 0x9BA86A
        Biome.DESERT -> 0xDCC493
        Biome.SAVANNA -> 0xC6B95F
        Biome.TROPICAL_SEASONAL_FOREST -> 0x5E8F3E
        Biome.TROPICAL_RAINFOREST -> 0x2C6B33
        Biome.ALPINE -> 0xA9A29B
        Biome.MEDITERRANEAN -> 0xA89A4E
        Biome.MONSOON_FOREST -> 0x3E8C5E
    }

    private fun nationColor(id: Int): Int {
        val hue = (id * 47.5f + 15f) % 360f
        return Color.HSBtoRGB(hue / 360f, 0.55f, 0.9f) and 0xFFFFFF
    }

    /** Distinct from [nationColor], so the two layers cannot be confused at a glance. */
    private fun cultureColor(id: Int): Int {
        val hue = (id * 73.5f + 200f) % 360f
        return hsvToRgbInt(hue, 0.42f, if (id % 2 == 0) 0.85f else 0.7f)
    }

    private fun hsvToRgbInt(hue: Float, s: Float, v: Float): Int {
        val c = v * s
        val x = c * (1f - kotlin.math.abs((hue / 60f) % 2f - 1f))
        val m = v - c
        val (r, g, b) = when {
            hue < 60f -> Triple(c, x, 0f)
            hue < 120f -> Triple(x, c, 0f)
            hue < 180f -> Triple(0f, c, x)
            hue < 240f -> Triple(0f, x, c)
            hue < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return rgbOf(((r + m) * 255).toInt(), ((g + m) * 255).toInt(), ((b + m) * 255).toInt())
    }

    private fun plateColor(id: Int): Int {
        val hue = (id * 137.508f) % 360f
        return Color.HSBtoRGB(hue / 360f, 0.45f, 0.85f) and 0xFFFFFF
    }

    /** Mirrors `MapPalette.boundaryClass`; see [com.cartogenesis.worldgen.pipeline.BoundaryClass]. */
    private fun boundaryClassColor(ordinal: Int): Int = when (ordinal) {
        0 -> 0xD9683A // Andean margin
        1 -> 0xE0B33C // collision plateau
        2 -> 0xC94F7C // island arc
        3 -> 0x3FA9A0 // ocean ridge
        4 -> 0x6D7FD6 // continental rift
        5 -> 0x8C8F99 // transform fault
        else -> 0x404040
    }

    private fun ramp(t: Float, colors: IntArray): Int {
        val scaled = t.coerceIn(0f, 1f) * (colors.size - 1)
        val index = scaled.toInt().coerceAtMost(colors.size - 2)
        return mix(colors[index], colors[index + 1], scaled - index)
    }

    private fun grad(t: Float, from: Int, to: Int): Int = mix(from, to, t.coerceIn(0f, 1f))

    private fun mix(a: Int, b: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        return rgbOf(
            (((a shr 16) and 0xFF) + ((((b shr 16) and 0xFF) - ((a shr 16) and 0xFF)) * f)).toInt(),
            (((a shr 8) and 0xFF) + ((((b shr 8) and 0xFF) - ((a shr 8) and 0xFF)) * f)).toInt(),
            ((a and 0xFF) + (((b and 0xFF) - (a and 0xFF)) * f)).toInt()
        )
    }

    private fun shadeBy(rgb: Int, factor: Float): Int = rgbOf(
        (((rgb shr 16) and 0xFF) * factor).toInt(),
        (((rgb shr 8) and 0xFF) * factor).toInt(),
        ((rgb and 0xFF) * factor).toInt()
    )

    private fun rgbOf(r: Int, g: Int, b: Int): Int =
        (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
}
