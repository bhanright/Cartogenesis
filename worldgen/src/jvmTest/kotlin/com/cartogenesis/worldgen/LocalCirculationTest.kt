package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.pipeline.AtmosphereLevels
import com.cartogenesis.worldgen.pipeline.BoundaryLayer
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.HadleyCirculation
import com.cartogenesis.worldgen.pipeline.LocalCirculation
import com.cartogenesis.worldgen.pipeline.PressureWind
import com.cartogenesis.worldgen.pipeline.Season
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The circulation at each longitude ([LocalCirculation]) keeps what it claims on the standard
 * worlds: each column's cells carry the zonal cells' mass between its own edges and its belts blow
 * poleward of them, the rising branch and the edges move across the columns no faster than the
 * sector's own Gaussian lets the land share move, and the column's vapor is shared out whole
 * ([BoundaryLayer.vaporShares]).
 */
class LocalCirculationTest : BorrowsSharedWorlds() {

    private companion object {
        val seeds = SharedWorlds.STANDARD_SEEDS
        const val ROWS = SharedWorlds.DETAIL_ROWS

        /** How far a column's greatest lower-branch mass flux may stand from the zonal cells', as a share: the rows' sampling of the profile. */
        const val MASS_FLUX_TOLERANCE = 0.02
    }

    /**
     * Each column's cells carry the zonal cells' mass: the lower branch's greatest mass flux, `v
     * cos(phi)`, is the zonal cells' to the rows' sampling in every column, and outside its own
     * cells every column blows its own belts.
     */
    @Test
    fun `each column's cells carry the zonal cells' mass between its own edges`() {
        for (seed in seeds) {
            val world = SharedWorlds.world(com.cartogenesis.worldgen.model.WorldGenConfig.forRows(seed, ROWS))
            val config = world.config
            val zonal = ClimateStage.zonalClimate(config, world.sea)
            for (season in listOf(Season.JULY_HALF, Season.JANUARY_HALF)) {
                val local = LocalCirculation.forHalf(config, zonal, world.sea.isLand, season)
                val belts = local.belts(world.width, world.height, config.scale.radiusMeters)
                val zonalRows = HadleyCirculation.windOfRows(local.zonal, world.height)
                fun massFlux(southward: Float, row: Int) = abs(southward.toDouble()) * cos(ClimateStage.latitudeOf(row, world.height) * PI / 180.0)
                var zonalGreatest = 0.0
                for (row in 0 until world.height) {
                    val latitude = ClimateStage.latitudeOf(row, world.height)
                    if (latitude < local.zonal.northEdgeDegrees && latitude > -local.zonal.southEdgeDegrees) {
                        zonalGreatest = maxOf(zonalGreatest, massFlux(zonalRows.southwardMps[row], row))
                    }
                }
                var worst = 0.0
                for (column in 0 until world.width) {
                    var greatest = 0.0
                    for (row in 0 until world.height) {
                        val latitude = ClimateStage.latitudeOf(row, world.height)
                        val cell = row * world.width + column
                        if (local.inCells(column, latitude.toDouble())) {
                            greatest = maxOf(greatest, massFlux(belts.southwardMps[cell], row))
                        } else {
                            val (east, north) = local.zonal.beltWindMps(latitude.toDouble(), local.northBelts[column], local.southBelts[column])
                            assertEquals(east.toFloat(), belts.eastwardMps[cell], "seed $seed $season: outside its cells column $column blows its belts")
                            assertEquals((-north).toFloat(), belts.southwardMps[cell], "seed $seed $season: outside its cells column $column blows its belts")
                        }
                    }
                    worst = maxOf(worst, abs(greatest - zonalGreatest) / zonalGreatest)
                }
                println("LOCAL CIRCULATION seed %d %s: a column's greatest mass flux against the zonal cells' at worst %.4f".format(seed, season, worst))
                assertTrue(worst <= MASS_FLUX_TOLERANCE, "seed $seed $season: a column's cells carry $worst more or less mass than the zonal cells'")
            }
        }
    }

    /**
     * The rising branch moves across the columns no faster than the sector lets it: the land share a
     * Gaussian of `sigma` columns weighs changes from one column to the next by at most `1 /
     * (sigma sqrt(2 pi))`, the kernel's own steepest slope integrated, and the blend's energy flux
     * equator by at most that times its steepest response to the share, `(phi_L - phi_S)` times the
     * domes' larger curvature over the smaller. Shown failing on the control with every sector
     * held to all land or all sea, whose branch jumps the whole way between two columns.
     */
    @Test
    fun `the rising branch moves smoothly across the columns, and the control's jumps`() {
        for (seed in seeds) {
            val world = SharedWorlds.world(com.cartogenesis.worldgen.model.WorldGenConfig.forRows(seed, ROWS))
            val config = world.config
            val zonal = ClimateStage.zonalClimate(config, world.sea)
            val radiusMeters = LocalCirculation.equatorialRossbyRadiusMeters(config.scale)
            val radiusDegrees = radiusMeters / config.scale.radiusMeters * 180.0 / PI
            val sigmaColumns = radiusMeters / (config.scale.radiusMeters * 2.0 * PI / world.width)
            for (season in listOf(Season.JULY_HALF, Season.JANUARY_HALF)) {
                val landBands = zonal.land.at(season)
                val seaBands = zonal.sea.at(season)
                val landWarmest = LocalCirculation.warmestLatitudeDegrees(landBands)
                val seaWarmest = LocalCirculation.warmestLatitudeDegrees(seaBands)
                val south = minOf(landWarmest, seaWarmest) - radiusDegrees
                val north = maxOf(landWarmest, seaWarmest) + radiusDegrees
                val land = LocalCirculation.domeOver(landBands, south, north)
                val sea = LocalCirculation.domeOver(seaBands, south, north)
                val curvatureRatio = maxOf(land.curvatureKPerDegreeSquared / sea.curvatureKPerDegreeSquared,
                    sea.curvatureKPerDegreeSquared / land.curvatureKPerDegreeSquared)
                val bound = abs(land.latitudeDegrees - sea.latitudeDegrees) * curvatureRatio / (sigmaColumns * sqrt(2.0 * PI))
                fun steepest(ascent: DoubleArray) = (0 until world.width).maxOf { abs(ascent[(it + 1) % world.width] - ascent[it]) }
                val smooth = steepest(LocalCirculation.forHalf(config, zonal, world.sea.isLand, season).ascentLatitudeDegrees)
                val held = steepest(
                    LocalCirculation.forHalf(config, zonal, world.sea.isLand, season, heldToLandOrSea = true).ascentLatitudeDegrees
                )
                println("LOCAL CIRCULATION seed %d %s: the rising branch moves at most %.3f degrees a column, bound %.3f; the control's %.2f"
                    .format(seed, season, smooth, bound, held))
                assertTrue(smooth <= bound, "seed $seed $season: the rising branch moves $smooth degrees between two columns, over the sector's $bound")
                assertTrue(held > bound, "seed $seed $season: the control moves no faster than the bound, so the clause shows nothing")
            }
        }
    }

    /**
     * The cells' edges move across the columns no faster than the sector lets them: the land share a
     * Gaussian of the storm track's deformation radius weighs changes from one column to the next by
     * at most `1 / (sigma sqrt(2 pi))`, `sigma` that radius in columns at the edges' latitude, and the
     * blended onset by at most that times its steepest response to the share, the land's and the
     * sea's onsets apart times their slopes' larger ratio ([LocalCirculation.blendedOnsetDegrees]).
     * Shown failing on the control with every sector held to all land or all sea, whose edges jump
     * wherever a sector's land at the edges' latitudes holds a majority.
     */
    @Test
    fun `the cells' edges move smoothly across the columns, and the control's jump`() {
        val controlOverBound = ArrayList<String>()
        for (seed in seeds) {
            val world = SharedWorlds.world(com.cartogenesis.worldgen.model.WorldGenConfig.forRows(seed, ROWS))
            val config = world.config
            val zonal = ClimateStage.zonalClimate(config, world.sea)
            for (season in listOf(Season.JULY_HALF, Season.JANUARY_HALF)) {
                val zonalCirculation = HadleyCirculation.forHalf(config, zonal, season)
                val local = LocalCirculation.forHalf(config, zonal, world.sea.isLand, season, zonalCirculation)
                val held = LocalCirculation.forHalf(config, zonal, world.sea.isLand, season, zonalCirculation, heldToLandOrSea = true)
                for ((hemisphere, edges, heldEdges) in listOf(
                    Triple("north", local.northEdgeDegrees, held.northEdgeDegrees), Triple("south", local.southEdgeDegrees, held.southEdgeDegrees)
                )) {
                    val north = hemisphere == "north"
                    val from = maxOf(if (north) zonalCirculation.ascentLatitudeDegrees else -zonalCirculation.ascentLatitudeDegrees, 0.0) + 0.25
                    val land = LocalCirculation.onsetOf(zonal.land.at(season), zonal, north, from) ?: continue
                    val sea = LocalCirculation.onsetOf(zonal.sea.at(season), zonal, north, from) ?: continue
                    val latitude = 0.5 * (land.latitudeDegrees + sea.latitudeDegrees)
                    val sigmaColumns = PressureWind.rossbyRadiusKm() * 1_000.0 /
                        (config.scale.radiusMeters * 2.0 * PI * cos(latitude * PI / 180.0) / world.width)
                    val slopeRatio = maxOf(land.slopePerDegree / sea.slopePerDegree, sea.slopePerDegree / land.slopePerDegree)
                    val bound = abs(land.latitudeDegrees - sea.latitudeDegrees) * slopeRatio / (sigmaColumns * sqrt(2.0 * PI))
                    fun steepest(values: DoubleArray) = (0 until world.width).maxOf { abs(values[(it + 1) % world.width] - values[it]) }
                    val smooth = steepest(edges)
                    val jumped = steepest(heldEdges)
                    println("LOCAL CIRCULATION seed %d %s %s edge: %.1f to %.1f degrees, at most %.3f a column, bound %.3f; the control's %.2f"
                        .format(seed, season, hemisphere, edges.min(), edges.max(), smooth, bound, jumped))
                    assertTrue(smooth <= bound, "seed $seed $season: the $hemisphere edge moves $smooth degrees between two columns, over the sector's $bound")
                    if (jumped > bound) controlOverBound += "seed $seed $season $hemisphere"
                }
            }
        }
        // A control's sectors held to all land or all sea move nothing where no sector has land at
        // the edges' latitudes, so the control is asked to jump somewhere, not everywhere.
        println("LOCAL CIRCULATION the control's edges jump past the bound in ${controlOverBound.size} halves: $controlOverBound")
        assertTrue(controlOverBound.size >= seeds.size, "the control's edges jump past the bound in only $controlOverBound")
    }

    /**
     * The column's vapor is shared out whole, the boundary layer's share the vapor's density
     * integrated through its depth on the levels' mean temperature, `1 - exp(-z / H_v)`.
     */
    @Test
    fun `the column's vapor is shared out whole`() {
        val levels = AtmosphereLevels.equalMass(BoundaryLayer.LEVEL_COUNT)
        val shares = BoundaryLayer.vaporShares(levels)
        assertEquals(1.0, shares.sum(), 1e-12)
        assertTrue(shares.all { it >= 0.0 })
        // The boundary layer's top by the hypsometric law on the mean temperature, independently.
        val profile = com.cartogenesis.worldgen.pipeline.ZonalBasicState.JABLONOWSKI_WILLIAMSON_TEMPERATURE
        val top = levels.interfacePressuresPa[levels.levelCount - 1]
        val steps = 10_000
        var height = 0.0
        for (step in 0 until steps) {
            val pressure = levels.surfacePressurePa + (top - levels.surfacePressurePa) * (step + 0.5) / steps
            height += com.cartogenesis.worldgen.pipeline.DryAir.GAS_CONSTANT_J_PER_KG_K * profile.kelvin(pressure) /
                (com.cartogenesis.worldgen.pipeline.DryAir.GRAVITY_MPS2 * pressure) * (levels.surfacePressurePa - top) / steps
        }
        val expected = 1.0 - exp(-height / BoundaryLayer.VAPOR_SCALE_HEIGHT_M)
        println("VAPOR the boundary layer %.0f m deep holds %.3f of the column's vapor; the shares %s".format(
            height, shares.last(), shares.joinToString(" ") { "%.3f".format(it) }))
        assertEquals(expected, shares.last(), 1e-4)
    }
}
