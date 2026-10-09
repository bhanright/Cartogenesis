package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldScale
import com.cartogenesis.worldgen.noise.GroundLattice
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.MoistureMarch
import com.cartogenesis.worldgen.pipeline.FlowRouting
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `UnitsTest`'s sibling for the figures the planet's size reaches: every noise lattice, every reach
 * read as whole cells, every area carried as a share of the surface and the rain's conversion keep
 * their size on the ground on a planet half and twice as wide as the 12,000 km world, and the
 * source holds no figure in cycles round the map, in map-width divisors or in shares of the map.
 *
 * Why it exists: the Earth-size audit found the generator's lengths stated on the ground while a
 * dozen noise lattices were counted in cycles round the map, a set of radii were divisors of the
 * map's width and the rain's conversion was referred to this planet's own 512 grid, so a planet
 * three times as wide drew straight-edged plate polygons, belts as strips and rain three times as
 * heavy (docs/DESIGN_LEDGER.md, K1). `UnitsTest` held ten hand-listed reaches; the figures here are
 * found by reading the source, so a new lattice or reach is held the day it is written.
 *
 * The figures are read where the stages construct them: every `GroundLattice(config, …)` and
 * `GroundLattice.wholeCycles(…, …)` for a lattice's wavelength, every `cellsWithin(…)` for a reach
 * read as the whole cells inside it. Each is evaluated from the constants and settings it names.
 */
class GroundFiguresTest {

    /**
     * Every noise lattice keeps its wavelength on the ground at half and twice the planet's width.
     *
     * A lattice's cycles round the map are whole, so the wavelength it draws moves off the stated
     * one by at most half a cycle's share of the circumference, `wavelength / (2 × cycles)`: the
     * bar is that, and never less than one cell. A lattice counted in cycles keeps its count as the
     * planet grows and draws twice the wavelength on a planet twice as wide, which is what this
     * fails on (shown in docs/DESIGN_LEDGER.md, K1, on the counts the tree before held). Down the
     * map the lattice must advance as many cycles per kilometer as it does across it, on a grid of
     * square cells and on the 512 by 512 grid's cells, twice as wide as they are tall.
     */
    @Test
    fun `every noise lattice keeps its wavelength on the ground at half and twice the planet`() {
        val lattices = figuresCalled(LATTICE_CALLS)
        assertTrue(lattices.size >= MOST_LATTICES_EXPECTED, "found only ${lattices.size} lattices: ${lattices.map { it.where }}")
        val wrong = ArrayList<String>()
        for (figure in lattices) {
            val wavelengthKm = figure.value
            for (widthKm in PLANET_WIDTHS_KM) {
                for (config in gridsOf(widthKm)) {
                    val lattice = GroundLattice(config, wavelengthKm)
                    val drawnKm = lattice.wavelengthAcrossKm
                    val barKm = maxOf(wavelengthKm / (2.0 * lattice.period), config.cellWidthKm) + ROUNDING_KM
                    val cyclesPerKmAcross = lattice.period / widthKm
                    val cyclesPerKmDown = lattice.cyclesDown / config.scale.poleToPoleKm
                    println(
                        "GROUND lattice %-58s %9.1f km on a %6.0f km planet (%4d by %4d): %4d cycles, %9.1f km drawn, bar %.1f"
                            .format(figure.where, wavelengthKm, widthKm, config.width, config.height, lattice.period, drawnKm, barKm)
                    )
                    if (abs(drawnKm - wavelengthKm) > barKm) {
                        wrong += "${figure.where}: ${figure.text} is $wavelengthKm km, drawn $drawnKm km on a ${widthKm} km planet"
                    }
                    if (abs(cyclesPerKmDown - cyclesPerKmAcross) > cyclesPerKmAcross * FLOAT_ROUNDING) {
                        wrong += "${figure.where}: ${cyclesPerKmDown} cycles a km down against $cyclesPerKmAcross across"
                    }
                }
            }
        }
        // The smooth field the routing reads is a lattice of whole cells round the planet too.
        for (widthKm in PLANET_WIDTHS_KM) {
            val config = gridsOf(widthKm).first()
            val columns = FlowRouting.smoothFieldLatticeColumns(config)
            val drawnKm = widthKm / columns
            val barKm = maxOf(FlowRouting.SMOOTH_FIELD_PERIOD_KM / (2.0 * columns), config.cellWidthKm) + ROUNDING_KM
            if (abs(drawnKm - FlowRouting.SMOOTH_FIELD_PERIOD_KM) > barKm) {
                wrong += "the routing's smooth field draws $drawnKm km on a $widthKm km planet"
            }
        }
        assertTrue(wrong.isEmpty(), "lattices off their wavelength on the ground:\n" + wrong.joinToString("\n"))
    }

    /**
     * Every reach read as the whole cells inside a length is within one cell of that length at
     * half and twice the planet's width, on a grid of square cells and on the 512 by 512 grid.
     */
    @Test
    fun `every reach read as whole cells is its length on the ground at half and twice the planet`() {
        val reaches = figuresCalled(REACH_CALLS)
        assertTrue(reaches.size >= MOST_REACHES_EXPECTED, "found only ${reaches.size} reaches: ${reaches.map { it.where }}")
        val wrong = ArrayList<String>()
        for (figure in reaches) {
            for (widthKm in PLANET_WIDTHS_KM) {
                for (config in gridsOf(widthKm)) {
                    val cells = config.cellsWithin(figure.value)
                    val drawnKm = cells * config.cellWidthKm
                    println(
                        "GROUND reach %-58s %8.2f km on a %6.0f km planet (%4d by %4d): %3d cells, %8.2f km"
                            .format(figure.where, figure.value, widthKm, config.width, config.height, cells, drawnKm)
                    )
                    if (drawnKm > figure.value + ROUNDING_KM || drawnKm <= figure.value - config.cellWidthKm) {
                        wrong += "${figure.where}: ${figure.text} is ${figure.value} km, read as $drawnKm km on a $widthKm km planet"
                    }
                }
            }
        }
        assertTrue(wrong.isEmpty(), "reaches off their length on the ground:\n" + wrong.joinToString("\n"))
    }

    /**
     * The areas Earth's figures are carried by — the Caspian's and Superior's shares of the surface
     * — grow with the planet's area, are the square kilometers they were held at on the 12,000 km
     * world, and are counted as cells within one cell's area of themselves at every planet width.
     */
    @Test
    fun `every area carried as a share of the surface tracks the planet's area`() {
        val stock = WorldGenConfig(scale = WorldScale(worldWidthKm = CALIBRATION_PLANET_KM))
        val onTheStockWorld = mapOf(
            "sea.enclosedSeaMax" to (stock.sea.enclosedSeaMaxKm2(stock.scale) to 52_560.0),
            "glaciation.maxLake" to (stock.glaciation.maxLakeAreaKm2(stock.scale) to 11_520.0),
            "glaciation.minLake" to (stock.glaciation.minLakeAreaKm2(stock.scale) to 1_152.0)
        )
        onTheStockWorld.forEach { (name, figures) ->
            assertEquals(figures.second, figures.first, figures.second * FLOAT_ROUNDING, "$name on the 12,000 km world")
        }
        for (widthKm in PLANET_WIDTHS_KM) {
            for (config in gridsOf(widthKm)) {
                val areas = mapOf(
                    "sea.enclosedSeaMax" to config.sea.enclosedSeaMaxKm2(config.scale),
                    "glaciation.maxLake" to config.glaciation.maxLakeAreaKm2(config.scale),
                    "glaciation.minLake" to config.glaciation.minLakeAreaKm2(config.scale)
                )
                val areaRatio = config.scale.worldAreaKm2 / stock.scale.worldAreaKm2
                areas.forEach { (name, km2) ->
                    val expected = onTheStockWorld.getValue(name).second * areaRatio
                    assertEquals(expected, km2, expected * FLOAT_ROUNDING, "$name on a $widthKm km planet")
                    val cells = (km2 / config.squareKilometresPerCell).toInt()
                    val drawnKm2 = cells * config.squareKilometresPerCell
                    assertTrue(
                        drawnKm2 <= km2 && drawnKm2 > km2 - config.squareKilometresPerCell,
                        "$name: $km2 km2 counted as $cells cells, $drawnKm2 km2, on a $widthKm km planet"
                    )
                }
            }
        }
    }

    /**
     * The rain's lifetime is the same per kilometer of travel on any planet and any grid: the
     * share of a column the march rains in crossing one cell is that cell's ground width over the
     * transport speed times the lifetime, so it over the width is one figure everywhere.
     *
     * The march carried its rain through a conversion fitted on one grid until C1, and the tree
     * before that referred the conversion to the planet's own 512 grid, so a planet twice as wide
     * rained twice as much per unit of the march (the Earth-size audit's D1).
     * `PlanetWidthRainTest` holds the rain itself.
     */
    @Test
    fun `the rain's lifetime is the same per kilometer of travel on any planet`() {
        val perKm = 1_000.0 / (MoistureMarch.TRANSPORT_SPEED_MPS * MoistureMarch.RAIN_LIFETIME_DAYS * 86_400.0)
        for (widthKm in PLANET_WIDTHS_KM) {
            for (config in gridsOf(widthKm)) {
                for (row in listOf(0, config.height / 3, config.height / 2)) {
                    val groundKm = config.cellWidthKm * kotlin.math.cos(ClimateStage.latitudeOf(row, config.height) * kotlin.math.PI / 180.0)
                    val share = MoistureMarch.lifetimeSharePerColumn(config, row)
                    assertEquals(perKm, share / groundKm, perKm * FLOAT_ROUNDING, "the lifetime per km on a $widthKm km planet at ${config.width} by ${config.height}, row $row")
                }
                println("GROUND rain lifetime on a %6.0f km planet (%4d by %4d): %.3e of a column per km".format(widthKm, config.width, config.height, perKm))
            }
        }
    }

    /**
     * No figure in the source is a count of cycles round the map, a divisor of the map's width or
     * a share of it, and every noise is read off a lattice stated on the ground.
     *
     * Four rules, each a way the figures this chunk restated were written:
     *  - a declaration named for cycles, a frequency, a divisor or a share of the map's width
     *    (`*_CYCLES`, `*Cycles`, `*Frequency`, `*_DIVISOR`, `*_SHARE_OF_MAP_WIDTH` and their kin)
     *    must be computed from kilometers or from a [GroundLattice], or be one of [NOT_ON_THE_MAP],
     *    each with its reason;
     *  - every Perlin noise call reads its coordinates off a lattice's `x` and `y`, so a noise
     *    sampled at `column * cycles / width` cannot be written without a lattice;
     *  - every lattice's wavelength and every reach read as whole cells is computed from a figure
     *    named in kilometers;
     *  - no grid dimension is divided by a named constant or a number other than two, the half
     *    width every wrap of a column offset uses.
     *
     * The next figure counted in cycles or cells of the map has to break one of them: name it in
     * cycles and the first catches it; sample a noise without a lattice and the second does; hand a
     * lattice a count and the third does; divide the map's width by it and the fourth does.
     */
    @Test
    fun `no figure in the source is counted in cycles round the map or as a share of it`() {
        val wrong = ArrayList<String>()
        for (source in sources()) {
            val code = codeLines(source)
            code.forEach { (lineNumber, line) ->
                NAMED_ON_THE_MAP.findAll(line).forEach { match ->
                    val name = match.groupValues[1]
                    val initializer = match.groupValues[2]
                    val derived = DERIVED_FROM_THE_GROUND.containsMatchIn(initializer)
                    if (!derived && name !in NOT_ON_THE_MAP) wrong += "${source.name}:$lineNumber declares $name = ${initializer.trim()}"
                }
                GRID_DIVIDED.findAll(line).forEach { match ->
                    if (match.groupValues[1] != "2" && source.name !in NOT_A_WORLD) {
                        wrong += "${source.name}:$lineNumber divides the grid: ${match.value.trim()}"
                    }
                }
            }
            if (source.name != "PerlinNoise.kt") {
                val text = source.readText()
                NOISE_CALL.findAll(text).forEach { match ->
                    val arguments = argumentsAt(text, match.range.last + 1)
                    if (!(arguments.contains("attice.x(") && arguments.contains("attice.y("))) {
                        wrong += "${source.name}:${lineOf(text, match.range.first)} samples a noise off no lattice: ${arguments.take(120)}"
                    }
                }
            }
        }
        (figuresCalled(LATTICE_CALLS, evaluated = false) + figuresCalled(REACH_CALLS, evaluated = false)).forEach { figure ->
            if (!NAMED_IN_KILOMETERS.containsMatchIn(figure.text)) wrong += "${figure.where} reads ${figure.text}, no figure in kilometers"
        }
        assertTrue(wrong.isEmpty(), "figures on the map rather than on the ground:\n" + wrong.joinToString("\n"))
    }

    /** One figure a stage reads at a call: where, the argument's text, and what it evaluates to (NaN unread). */
    private class Figure(val where: String, val text: String, val value: Double)

    /**
     * Every call matching [call] in the main sources, its figure argument [evaluated] or left as
     * text, which is all the source rule reads and all a figure in some other unit allows.
     */
    private fun figuresCalled(call: Call, evaluated: Boolean = true): List<Figure> {
        val figures = ArrayList<Figure>()
        for (source in sources()) {
            val text = source.readText()
            call.pattern.findAll(text).forEach { match ->
                val start = match.range.first
                if (isInComment(text, start) || isDeclaration(text, start)) return@forEach
                val arguments = splitArguments(argumentsAt(text, match.range.last + 1))
                val argument = arguments.getOrNull(call.argument) ?: return@forEach
                val where = "${source.name}:${lineOf(text, start)}"
                figures += Figure(where, argument, if (evaluated) evaluate(argument, where) else Double.NaN)
            }
        }
        return figures
    }

    /** Evaluates a product or quotient of numbers, constants and settings, as the stages write them. */
    private fun evaluate(expression: String, where: String): Double {
        val operands = expression.split(Regex("""\s*([*/])\s*""")).map { it.trim() }
        val operators = Regex("""[*/]""").findAll(expression).map { it.value }.toList()
        var value = operand(operands[0], where)
        operators.forEachIndexed { index, operator ->
            val next = operand(operands[index + 1], where)
            value = if (operator == "*") value * next else value / next
        }
        return value
    }

    private fun operand(text: String, where: String): Double {
        val number = text.replace("_", "").removeSuffix("f").removeSuffix("F").toDoubleOrNull()
        if (number != null) return number
        val name = text.substringAfterLast('.')
        if (name.all { it.isUpperCase() || it.isDigit() || it == '_' }) {
            val constant = constants()[name] ?: fail("$where: no constant $name in the sources")
            return evaluate(constant, where)
        }
        return setting(name) ?: fail("$where: no setting $name in WorldGenConfig")
    }

    /** A setting's default, found by name in any section of the stock config. */
    private fun setting(name: String): Double? {
        val stock = WorldGenConfig()
        val getter = "get" + name.replaceFirstChar { it.uppercase() }
        for (section in WorldGenConfig::class.java.declaredFields) {
            section.isAccessible = true
            val holder = section.get(stock) ?: continue
            val method = holder.javaClass.methods.firstOrNull { it.name == getter && it.parameterCount == 0 } ?: continue
            return (method.invoke(holder) as Number).toDouble()
        }
        return null
    }

    private val constantsRead: Map<String, String> by lazy {
        val found = HashMap<String, String>()
        for (source in sources()) {
            CONSTANT.findAll(source.readText()).forEach { found.putIfAbsent(it.groupValues[1], it.groupValues[2].trim()) }
        }
        found
    }

    private fun constants(): Map<String, String> = constantsRead

    private fun sources(): List<File> = SOURCE_ROOTS.flatMap { root ->
        File(repositoryRoot, root).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private val repositoryRoot: File by lazy {
        var directory: File? = File("").absoluteFile
        while (directory != null && !File(directory, "settings.gradle.kts").exists()) directory = directory.parentFile
        directory ?: error("no settings.gradle.kts above ${File("").absolutePath}")
    }

    /** The lines of [source] that are code, numbered from one, with comment lines and trailing comments dropped. */
    private fun codeLines(source: File): List<Pair<Int, String>> =
        source.readLines().mapIndexedNotNull { index, line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) null
            else (index + 1) to line.substringBefore("//")
        }

    private fun isInComment(text: String, at: Int): Boolean {
        val lineStart = text.lastIndexOf('\n', at) + 1
        val before = text.substring(lineStart, at).trimStart()
        return before.startsWith("*") || before.startsWith("//") || before.startsWith("/*") ||
            text.substring(lineStart, at).contains("//") || text.substring(lineStart, at).count { it == '`' } % 2 == 1
    }

    private fun isDeclaration(text: String, at: Int): Boolean {
        val lineStart = text.lastIndexOf('\n', at) + 1
        val before = text.substring(lineStart, at)
        return before.contains("class ") || before.contains("fun ")
    }

    /** The text between the parenthesis that opens at [open] - 1 and the one that closes it. */
    private fun argumentsAt(text: String, open: Int): String {
        var depth = 1
        var index = open
        while (index < text.length && depth > 0) {
            when (text[index]) {
                '(' -> depth++
                ')' -> depth--
            }
            index++
        }
        return text.substring(open, index - 1)
    }

    private fun splitArguments(arguments: String): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        var start = 0
        arguments.forEachIndexed { index, character ->
            when (character) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) {
                    parts += arguments.substring(start, index).trim()
                    start = index + 1
                }
            }
        }
        parts += arguments.substring(start).trim()
        return parts
    }

    private fun lineOf(text: String, at: Int): Int = text.substring(0, at).count { it == '\n' } + 1

    /** Grids of square cells and of the 512 by 512 grid's cells, on a planet [widthKm] round. */
    private fun gridsOf(widthKm: Double): List<WorldGenConfig> {
        val scale = WorldScale(worldWidthKm = widthKm)
        return listOf(
            WorldGenConfig.forRows(42L, 1024).copy(scale = scale),
            WorldGenConfig.forRows(42L, 128).copy(scale = scale),
            WorldGenConfig(seed = 42L, width = 512, height = 512, scale = scale)
        )
    }

    /** A call whose [argument]th argument is a figure on the ground. */
    private class Call(val pattern: Regex, val argument: Int)

    private companion object {
        /** Half, the calibration planet and twice its width, and the default, Earth's, in kilometers. */
        val PLANET_WIDTHS_KM = doubleArrayOf(6_000.0, 12_000.0, 24_000.0, WorldScale.EARTH_EQUATOR_KM)

        /** Where the figures live: the generator's and the drawing's main sources. */
        val SOURCE_ROOTS = listOf("worldgen/src/commonMain/kotlin", "cartography/src/commonMain/kotlin")

        /** A lattice made from a wavelength, and a whole count of cycles taken from one. */
        val LATTICE_CALLS = Call(Regex("""\bGroundLattice\(|\bGroundLattice\.wholeCycles\("""), 1)

        /** A reach read as the whole cells inside a length. */
        val REACH_CALLS = Call(Regex("""\bcellsWithin\("""), 0)

        /**
         * The lattices and reaches there are in the sources the day this was written, as a floor
         * under the reading: fewer means the reading broke, not that a lattice went away.
         */
        const val MOST_LATTICES_EXPECTED = 14
        const val MOST_REACHES_EXPECTED = 4

        /** A declaration named for a count round the map, a frequency, a divisor or a share of the map. */
        val NAMED_ON_THE_MAP = Regex(
            """\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*(?:_CYCLES|Cycles|_FREQUENCY|Frequency|_DIVISOR|Divisor|_SHARE_OF_MAP_WIDTH|ShareOfMapWidth))\b[^=]*=(.*)"""
        )

        /** What an initializer computed from the ground mentions. */
        val DERIVED_FROM_THE_GROUND = Regex("""Km\b|KM\b|_KM_|Km[A-Z0-9]|GroundLattice|wholeCycles|\.period\b""")

        /** A figure named in kilometers. */
        val NAMED_IN_KILOMETERS = Regex("""(?:_KM\b|Km\b)""")

        /**
         * The declarations the first rule lets stand, and why each is not a figure on the map.
         *  - `MOST_CYCLES`: the multigrid solver's most V-cycles, an iteration count.
         *  - `COAST_SHARE_OF_MAP_WIDTH`: the coast pen, one pixel of the sheet it was matched on, a
         *    statement about the drawing's raster rather than the ground; whether it should be a
         *    width on the ground like the river pen is in `TODO.md`.
         */
        val NOT_ON_THE_MAP = setOf("MOST_CYCLES", "COAST_SHARE_OF_MAP_WIDTH")

        /**
         * The sources the fourth rule lets divide a grid, and why each is not a world.
         *  - `IceSheetParity.kt`: the synthetic fixture the ice sheet's graphics-card path is held
         *    to the processor's answer on, frozen in its outer thirds of rows; a test pattern on a
         *    fixed grid, not a world on a planet.
         */
        val NOT_A_WORLD = setOf("IceSheetParity.kt")

        /** A grid dimension divided by a named constant or a number. */
        val GRID_DIVIDED = Regex("""\b(?:config\.width|config\.height|cellsAcross|cellsDown|width|height)\s*/\s*([A-Z][A-Z0-9_]*|(\d+))\b""")

        /** A Perlin noise sampled: one octave or several. */
        val NOISE_CALL = Regex("""\.(?:fbm|noise)\(""")

        /** A constant declared in a source: its name and its initializer, to the end of the line. */
        val CONSTANT = Regex("""const val ([A-Z][A-Z0-9_]*)\s*(?::\s*\w+)?\s*=\s*([^\n]+)""")

        /**
         * The planet the figures were first set on, in kilometers round: the default until K2,
         * kept as the reference the restatements are held to.
         */
        const val CALIBRATION_PLANET_KM = 12_000.0

        /** Kilometers a float's last places are worth on a wavelength of a few thousand. */
        const val ROUNDING_KM = 1e-6

        /** A float product's last few places, as a share of it. */
        const val FLOAT_ROUNDING = 1e-6
    }
}
