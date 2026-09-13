package com.cartogenesis.cartography

import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.CultureResult
import com.cartogenesis.worldgen.pipeline.LakeResult
import com.cartogenesis.worldgen.pipeline.NationResult

/**
 * Somewhere other than the CPU to draw the map's pixels.
 *
 * The same shape as [com.cartogenesis.worldgen.pipeline.ErosionAccelerator], and for the same
 * reason: [MapRasterizer.rasterize] is a pure function of a cell and its immediate neighbours, run
 * once per pixel, and at export sizes there are tens of millions of pixels. A 4096 export spends a
 * quarter of a minute on arithmetic that a graphics card does in a fraction of a second.
 *
 * The implementation lives with the front end that has the platform's graphics API. It is handed a
 * [RasterRecipe] rather than a [com.cartogenesis.worldgen.model.WorldMap], so it needs no knowledge
 * of the pipeline's types and no access to the palette's internals: the recipe is every colour,
 * every lever and every per-cell field the raster reads, flattened for upload.
 *
 * Returning null is a normal outcome, not an error path — no device, no driver, a grid that will not
 * fit — and the caller falls back to the CPU, which remains the reference. So is *declining*: an
 * accelerator that has not implemented some view or pass must return null for it rather than draw
 * something else.
 */
interface RasterAccelerator {

    /** Shown to the user, so it should name the actual device where that is knowable. */
    val name: String

    /**
     * Renders [recipe] and returns ARGB pixels, row-major, `width * height` long — the same buffer
     * [MapRasterizer.rasterize] would have returned — or null to fall back to the CPU.
     *
     * Not expected to match the CPU bit for bit. The colour arithmetic is integer and can be made
     * to agree exactly, but the hillshade is a square root and a divide, and hardware is under no
     * obligation to round either the way a JVM does. A channel out by one at a truncation boundary
     * is the expected difference; anything a person could see is a bug.
     *
     * Suspending for the same reason the erosion seam is: a WebGPU implementation hands back
     * promises for its device and for every read of a buffer, and Kotlin/Wasm cannot block on one.
     */
    suspend fun rasterize(recipe: RasterRecipe): IntArray?
}

/**
 * Which view a recipe draws.
 *
 * Deliberately not [MapView.ordinal]. A shader written against ordinals would keep compiling and
 * start lying the day someone reorders the enum; these numbers are fixed here, and the mapping in
 * [RasterRecipe.of] is an exhaustive `when`, so a new view breaks the build until it is given one.
 */
object RasterView {
    const val FANTASY = 0
    const val POLITICAL = 1
    const val CULTURES = 2
    const val ELEVATION = 3
    const val BIOMES = 4
    const val TEMPERATURE = 5
    const val RAINFALL = 6
    const val PLATES = 7
    const val CURRENTS = 8
    const val WIND = 9
    const val NORMALS = 10
}

/**
 * Everything needed to draw one map, with nothing left to look up.
 *
 * Flat on purpose. Every colour is a packed ARGB int, every lever a float, and every per-cell field
 * a primitive array an upload can take as it stands — most of them the pipeline's own arrays,
 * handed over without copying. The three that are copies ([land], [biome], and the little colour
 * tables) are copies because the pipeline holds them as types no graphics API can read: a
 * `BooleanArray`, an array of enum objects, and a function of an id.
 *
 * The A and B of [scalarA], [indexA] and [colorsA] are the shader's own buffer names
 * (`ScalarA`, `IndexA`, `ColorsA` in `GpuRaster.SOURCE`) and stay as they are, so the two sides of
 * the upload can be read against each other line for line. What each slot carries depends on the
 * view rather than on its name, which is what the table below is for.
 *
 * The seasonal temperature and rainfall views collapse into one [RasterView] each, because they
 * differ only in which field lands in [scalarA]. Which fields are populated depends on the view:
 *
 * | view      | scalarA            | scalarB | indexA  | indexB         | biome |
 * |-----------|--------------------|---------|---------|----------------|-------|
 * | fantasy   | ground dryness     | ground coldness | - | -            | yes   |
 * | political | -                  | -       | realm   | -              | -     |
 * | peoples   | -                  | -       | people  | -              | -     |
 * | elevation | -                  | -       | -       | -              | -     |
 * | biomes    | -                  | -       | -       | -              | yes   |
 * | temperature | degrees C        | -       | -       | -              | -     |
 * | rainfall  | 0..1               | -       | -       | -              | -     |
 * | plates    | boundary distance  | -       | plate   | boundary class | -     |
 * | currents  | anomaly, degrees C | -       | -       | -              | -     |
 * | wind      | -                  | -       | -       | -              | -     |
 * | normals   | slope x            | slope y | -       | -              | -     |
 *
 * [elevation] and [land] are always present: the relief shading, the coastline pass and half the
 * views read them.
 *
 * Built by [of] from a world. The constructor is open only so that a test can put an accelerator
 * through a picture no generator would produce — a grid far larger than a world that fits in
 * memory, say — and not because anything else should be assembling one by hand.
 */
class RasterRecipe(
    val width: Int,
    val height: Int,
    val view: Int,

    // ---- per-cell fields ----
    /** Elevation relative to the shoreline: positive on land, negative at sea. */
    val elevation: FloatArray,
    /** 1 where the cell is land, 0 at sea. One byte a cell, so an upload can pack four to a word. */
    val land: ByteArray,
    /** Biome ordinal per cell, as an index into [biomeColors]. */
    val biome: ByteArray?,
    val scalarA: FloatArray?,
    val scalarB: FloatArray?,
    /** An id per cell, negative where there is none, indexing [colorsA]. */
    val indexA: IntArray?,
    val colorsA: IntArray?,
    val indexB: IntArray?,
    val colorsB: IntArray?,
    /** Realm per cell, for the border pass. The same array as [indexA] on the political view. */
    val nation: IntArray?,
    /** Lake id per cell, [LakeResult.NO_LAKE] where there is no standing water. */
    val lakeId: IntArray?,
    /** Water-surface elevation per lake id. */
    val lakeSurface: FloatArray?,

    // ---- the style, unpacked ----
    val oceanRamp: IntArray,
    val landRamp: IntArray,
    /** The unstyled ramps the diagnostic views use, which ignore the style on purpose. */
    val plainOceanRamp: IntArray,
    val plainLandRamp: IntArray,
    /**
     * The water and the relief the political and peoples views read.
     *
     * The plain ramps for every style but the two that paint their own political ground — see
     * [MapStyle.ownsPoliticalGround]. Carried as two more ramps rather than as a flag, so the
     * device is still only looking colours up and the shader has no idea which style it is drawing.
     */
    val politicalOceanRamp: IntArray = plainOceanRamp,
    val politicalLandRamp: IntArray = plainLandRamp,
    /**
     * How many colours the realm set holds, or 0 where there is no declared set.
     *
     * Non-zero means each further turn of the cycle is hatched: see [MapStyle.hatched], of which
     * the shader's copy is the other half.
     */
    val realmSetSize: Int = 0,
    /** How far a hatch stroke is dragged toward [coastline]. */
    val hatchStrength: Float = MapStyle.HATCH_STRENGTH,
    val temperatureRamp: IntArray,
    val precipitationRamp: IntArray,
    val biomeColors: IntArray,
    /**
     * How much of each biome's ground is under a closed canopy, in [Biome] order.
     *
     * The one thing about a cell's climate that is a property of its vegetation rather than of its
     * weather, so it travels as a table indexed by the biome the device already has rather than as
     * a third field the size of the map. See [ClimateTint].
     */
    val biomeCanopy: FloatArray,
    val paper: Int,
    val biomeWash: Float,
    val biomeMuting: Float,
    /** How far the land ramp follows the climate. See [MapStyle.climateTint]. */
    val climateTint: Float,
    /**
     * How black the depth contours run, and how far apart they are as a fraction of the elevation
     * field's own range. See [Isobaths]. The interval depends on the world's metre scale, which is
     * why it travels rather than being a constant on the device, and so does the gradient below
     * which the floor is a plain and carries no contour at all.
     */
    val isobathInk: Float,
    val isobathInterval: Float,
    val isobathFlattestSlope: Float,
    /** How far the central difference that measures the floor's fall reaches, in cells. */
    val isobathSlopeStencil: Int,
    val lake: Int,
    val lakeDeep: Int,
    val coastline: Int,
    val coastlineStrength: Float,
    val border: Int,
    val wilderness: Int,
    val reliefStrength: Float,
    val lineArt: Boolean,
    val inkGain: Float,
    /**
     * How big each mark of the engraving is, in pixels, or null where the style does not engrave.
     * Built once here so neither path can derive a different pitch from the same width.
     */
    val engraving: EngravingPlan? = null,
    /**
     * Euclidean distance in cells from every cell to the nearest dry land, or null where nothing
     * reads it.
     *
     * The engraving's coastal vignette and its ruled lake water are both drawn from it. Computed on
     * the processor and uploaded rather than recomputed on the device, for the same reason the
     * colour tables are: a distance field solved twice would put the vignette's lines a pixel apart
     * and the two pictures would no longer be the same picture.
     */
    val shoreDistance: FloatArray? = null,
    /**
     * Which entry of [biomeColors] is ice, so the engraving can stipple it, or -1 where the biome
     * is not uploaded.
     */
    val iceBiome: Int = -1,
    /** Whether the sea and the lakes are engraved. False on the views whose water carries data. */
    val engraveWater: Boolean = false,

    // ---- the fixed colours the diagnostic views carry ----
    val rainfallSea: Int,
    val currentsLand: Int,
    val windLandLow: Int,
    val windLandHigh: Int,
    val windSea: Int,
    val anomalyMid: Int,
    val anomalyWarm: Int,
    val anomalyCold: Int,

    // ---- which passes run ----
    val hillshade: Boolean,
    /** Light the relief from one lamp rather than from the sky. See [RenderOptions.singleLamp]. */
    val singleLamp: Boolean,
    /**
     * How far the relief's central differences are exaggerated. Scales with resolution, because
     * at four times the grid a step covers a quarter of the ground.
     */
    val slopeScale: Float,
    /**
     * How far the openness stencil's shortest step reaches, in cells. Scales with resolution too,
     * and for the opposite reason: it measures the country rather than the sheet.
     */
    val opennessStep: Int,
    val showLakes: Boolean,
    val showCoastline: Boolean,
    val showBorders: Boolean
) {

    companion object {

        /**
         * Describes what [MapRasterizer.rasterize] would draw, or null if this world and these
         * options cannot be described — which today means only a world missing a field the view
         * needs. The `when` over [MapView] is exhaustive, so a view added later stops the build
         * here rather than silently rendering as something else.
         */
        fun of(world: WorldMap, options: RenderOptions): RasterRecipe? {
            val cellsAcross = world.width
            val cellsDown = world.height
            val cellCount = cellsAcross * cellsDown
            val style = options.style
            val view = options.view

            val land = ByteArray(cellCount)
            val isLand = world.sea.isLand
            for (cell in 0 until cellCount) if (isLand[cell]) land[cell] = 1

            var biomes: ByteArray? = null
            var scalarA: FloatArray? = null
            var scalarB: FloatArray? = null
            var indexA: IntArray? = null
            var colorsA: IntArray? = null
            var indexB: IntArray? = null
            var colorsB: IntArray? = null

            val viewId = when (view) {
                MapView.FANTASY -> {
                    biomes = biomeOrdinals(world)
                    // The climate's two per-cell numbers, computed here rather than on the device
                    // for the reason the colour tables are: an aridity index solved twice would be
                    // two slightly different deserts.
                    if (style.climateTint > 0f) {
                        scalarA = ClimateTint.drynessField(world)
                        scalarB = ClimateTint.coldnessField(world)
                    }
                    RasterView.FANTASY
                }

                MapView.POLITICAL -> {
                    indexA = world.nations.nationId
                    colorsA = colourTable(indexA) { style.realm(it) }
                    RasterView.POLITICAL
                }

                MapView.CULTURES -> {
                    indexA = world.cultures.cultureId
                    colorsA = colourTable(indexA) { style.people(it) }
                    RasterView.CULTURES
                }

                MapView.ELEVATION -> RasterView.ELEVATION

                MapView.BIOMES -> {
                    biomes = biomeOrdinals(world)
                    RasterView.BIOMES
                }

                MapView.TEMPERATURE -> {
                    scalarA = world.climate.temperature.data
                    RasterView.TEMPERATURE
                }

                MapView.SUMMER_TEMPERATURE -> {
                    scalarA = world.climate.summerTemperature.data
                    RasterView.TEMPERATURE
                }

                MapView.WINTER_TEMPERATURE -> {
                    scalarA = world.climate.winterTemperature.data
                    RasterView.TEMPERATURE
                }

                MapView.RAINFALL -> {
                    scalarA = world.climate.precipitation.data
                    RasterView.RAINFALL
                }

                MapView.SUMMER_RAINFALL -> {
                    scalarA = world.climate.summerPrecipitation.data
                    RasterView.RAINFALL
                }

                MapView.WINTER_RAINFALL -> {
                    scalarA = world.climate.winterPrecipitation.data
                    RasterView.RAINFALL
                }

                MapView.PLATES -> {
                    scalarA = world.plates.boundaryDistance.data
                    indexA = world.plates.plateId
                    colorsA = colourTable(indexA) { MapPalette.plate(it) }
                    indexB = world.plates.nearestBoundaryClass
                    colorsB = colourTable(indexB) { MapPalette.boundaryClass(it) }
                    RasterView.PLATES
                }

                MapView.CURRENTS -> {
                    scalarA = world.ocean.anomaly.data
                    RasterView.CURRENTS
                }

                MapView.WIND -> RasterView.WIND

                MapView.NORMALS -> {
                    scalarA = world.terrain.normals.gradientX.data
                    scalarB = world.terrain.normals.gradientY.data
                    RasterView.NORMALS
                }
            }

            // The engraving reads the ice, so the biome goes up on every view it draws on rather
            // than only on the two that colour by it.
            val engraveWater = style.lineArt && view.styled
            if (engraveWater && biomes == null) biomes = biomeOrdinals(world)

            if (scalarA != null && scalarA.size != cellCount) return null
            if (scalarB != null && scalarB.size != cellCount) return null
            if (indexA != null && indexA.size != cellCount) return null
            if (indexB != null && indexB.size != cellCount) return null

            val showLakes = options.showLakes && view.showsTerrain
            val lakes = world.rivers.lakes
            val lakeId = if (showLakes) lakes.lakeId else null
            val lakeSurface = if (showLakes) {
                FloatArray(lakes.lakes.size) { lakes.lakes[it].surfaceElevation }
            } else null
            if (lakeId != null && lakeId.size != cellCount) return null

            val borders = options.bordersVisible
            val nation = if (borders) world.nations.nationId else null
            if (nation != null && nation.size != cellCount) return null

            return RasterRecipe(
                width = cellsAcross,
                height = cellsDown,
                view = viewId,
                elevation = world.sea.relativeElevation.data,
                land = land,
                biome = biomes,
                scalarA = scalarA,
                scalarB = scalarB,
                indexA = indexA,
                colorsA = colorsA,
                indexB = indexB,
                colorsB = colorsB,
                nation = nation,
                lakeId = lakeId,
                lakeSurface = lakeSurface,
                oceanRamp = style.oceanRamp,
                landRamp = style.landRamp,
                plainOceanRamp = MapPalette.plainOceanRamp,
                plainLandRamp = MapPalette.plainLandRamp,
                politicalOceanRamp =
                    if (style.ownsPoliticalGround) style.oceanRamp else MapPalette.plainOceanRamp,
                politicalLandRamp =
                    if (style.ownsPoliticalGround) style.landRamp else MapPalette.plainLandRamp,
                realmSetSize = style.realmRamp?.size ?: 0,
                temperatureRamp = MapPalette.temperatureRamp,
                precipitationRamp = MapPalette.precipitationRamp,
                biomeColors = IntArray(Biome.entries.size) { MapPalette.biome(Biome.entries[it]) },
                biomeCanopy = ClimateTint.canopyTable(),
                paper = style.paper,
                biomeWash = style.biomeWash,
                biomeMuting = style.biomeMuting,
                climateTint = if (view == MapView.FANTASY) style.climateTint else 0f,
                isobathInk = if (view == MapView.FANTASY) style.isobathInk else 0f,
                isobathInterval = Isobaths.interval(world.config.scale),
                isobathFlattestSlope =
                    Isobaths.flattestSlope(world.config, cellsAcross, cellsDown),
                isobathSlopeStencil = Isobaths.slopeStencil(cellsAcross),
                lake = style.lake,
                lakeDeep = style.lakeDeep,
                coastline = style.coastline,
                coastlineStrength = style.coastlineStrength,
                border = style.border,
                wilderness = style.wilderness,
                reliefStrength = style.reliefStrength,
                lineArt = style.lineArt,
                inkGain = style.inkGain,
                engraving = if (style.lineArt) EngravingPlan(cellsAcross) else null,
                shoreDistance = if (style.lineArt) {
                    ShoreDistance.of(
                        cellsAcross, cellsDown, MapRasterizer.dryLandMask(world, showLakes)
                    )
                } else null,
                iceBiome = if (biomes != null) Biome.ICE_SHEET.ordinal else -1,
                engraveWater = engraveWater,
                rainfallSea = MapRasterizer.RAINFALL_SEA,
                currentsLand = MapRasterizer.CURRENTS_LAND,
                windLandLow = MapRasterizer.WIND_LAND_LOW,
                windLandHigh = MapRasterizer.WIND_LAND_HIGH,
                windSea = MapRasterizer.WIND_SEA,
                anomalyMid = MapPalette.ANOMALY_MID,
                anomalyWarm = MapPalette.ANOMALY_WARM,
                anomalyCold = MapPalette.ANOMALY_COLD,
                hillshade = options.showHillshade && view != MapView.NORMALS,
                singleLamp = options.singleLamp,
                slopeScale = ReliefShading.slopeScale(cellsAcross),
                opennessStep = ReliefShading.opennessStep(cellsAcross),
                showLakes = showLakes,
                showCoastline = options.showCoastline,
                showBorders = borders
            )
        }

        private fun biomeOrdinals(world: WorldMap): ByteArray {
            val biome = world.climate.biome
            return ByteArray(biome.size) { cell -> biome[cell].ordinal.toByte() }
        }

        /**
         * One colour per id, so the shader looks a colour up rather than reproducing the palette's
         * hue arithmetic — which would be a second implementation of it, free to drift.
         *
         * Sized from the largest id actually present rather than from the list of realms, plates or
         * peoples, because an id out of range would read off the end of the table on the device,
         * where there is nothing to catch it.
         */
        private inline fun colourTable(ids: IntArray, colour: (Int) -> Int): IntArray {
            var highestId = 0
            for (id in ids) if (id > highestId) highestId = id
            return IntArray(highestId + 1) { id -> colour(id) }
        }
    }
}
