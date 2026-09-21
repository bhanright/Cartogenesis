package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.GlaciationStage
import com.cartogenesis.worldgen.pipeline.OceanStage
import com.cartogenesis.worldgen.pipeline.SeaLevelStage
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

class I3Probe {

    @Test
    fun probe() {
        assumeTrue(System.getenv("I3_RENDER") != null)
        val out = File("../desktop/build/i3-crops")
        out.mkdirs()
        val config = WorldGenConfig(seed = 878210L, width = 512, height = 512)
            .atResolution(1024, 1024)
        val world = WorldGenerationEngine.generateBlocking(config)
        val bed = SeaLevelStage.apply(world.erosion.height, config)
        val balance = ClimateStage.provisionalSnowBalance(
            config, bed, OceanStage.withoutCurrents(config, bed)
        )
        var mass: com.cartogenesis.worldgen.pipeline.GlacialMass? = null
        val carved =
            runBlocking { GlaciationStage.apply(config, bed, balance, null) { mass = it } }
        val m = mass!!
        val across = config.width
        val down = config.height
        fun grey(name: String, value: (Int) -> Float) {
            var top = 0f
            for (c in 0 until across * down) { val v = value(c); if (v > top) top = v }
            if (top <= 0f) top = 1f
            val img = BufferedImage(across, down, BufferedImage.TYPE_INT_RGB)
            for (c in 0 until across * down) {
                val t = (255f * value(c) / top).toInt().coerceIn(0, 255)
                img.setRGB(c % across, c / across, (t shl 16) or (t shl 8) or t)
            }
            ImageIO.write(img, "png", File(out, "probe-$name.png"))
            println("I3 PROBE $name top=$top")
        }
        grey("thickness") { m.iceThicknessMetres[it] }
        grey("margin") { m.marginDistanceKm[it] }
        grey("sheet") { if (m.onTheSheet[it]) 1f else 0f }
        grey("balance") { balance.data[it].coerceAtLeast(0f) }
        run {
            val r = 940
            val b = StringBuilder()
            for (col in 430..470) b.append("%d:%.2f ".format(col, balance.data[r * across + col]))
            println("I3 PROBE balance row $r: $b")
            val c = 440
            val d = StringBuilder()
            for (rr in 900..1000 step 4) d.append("%d:%.2f ".format(rr, balance.data[rr * across + c]))
            println("I3 PROBE balance col $c: $d")
        }
        val carving = GlaciationStage.Carving(config)
        val reliefRadius =
            (config.glaciation.reliefWindow * carving.valleyWidthCells).toInt().coerceIn(2, 64)
        val relief = GlaciationStage.localRelief(
            across, down, bed.relativeElevation.data, reliefRadius,
            config.glaciation.reliefWindowOctagon
        )
        val frozen = BooleanArray(across * down) {
            bed.isLand[it] && com.cartogenesis.worldgen.pipeline.SnowBalance.isGlaciated(balance.data[it])
        }
        grey("frozen") { if (frozen[it]) 1f else 0f }
        grey("relief") { relief[it] }
        grey("channelled") { if (bed.isLand[it] && relief[it] >= carving.valleyRelief) 1f else 0f }
        println("I3 PROBE reliefRadius=$reliefRadius valleyRelief=${carving.valleyRelief}")
        run {
            val r = 940
            val b = StringBuilder()
            for (col in 430..470) {
                b.append("%d:%s%s rel=%.5f  ".format(col, if (frozen[r * across + col]) "F" else ".",
                    if (relief[r * across + col] >= carving.valleyRelief) "C" else ".",
                    relief[r * across + col]))
            }
            println("I3 PROBE relief row $r: $b")
        }
        // A row through the striped flank, printed cell by cell.
        val row = 940
        val line = StringBuilder()
        for (col in 430..470) {
            line.append(
                "%d:%s d=%.1f t=%.0f  ".format(
                    col, if (m.onTheSheet[row * across + col]) "S" else ".",
                    m.marginDistanceKm[row * across + col],
                    m.iceThicknessMetres[row * across + col]
                )
            )
        }
        println("I3 PROBE row $row: $line")
        println("I3 PROBE sheetCells=${m.sheetCells} frozen=${m.frozenCells} channelled=${m.channelledCells} glacier=${m.glacierCells} outlets=${m.outlets} outletCells=${m.outletCells}")
        run {
            val mg = com.cartogenesis.worldgen.pipeline.IceSheet.marginDistanceKm(config, frozen, bed.relativeElevation.data, config.scale.highestLandMetres, com.cartogenesis.worldgen.pipeline.IceSheet.metresPerRootKilometre(config.isostasy.iceDensity, config.isostasy.gravity), kotlin.math.sqrt(config.squareKilometresPerCell).toFloat())
            val metres = config.scale.highestLandMetres
            val bedR = bed.relativeElevation.data
            val r = 850
            val b = StringBuilder()
            for (col in 360..410) {
                val c = r * across + col
                val n = mg.nearestCell[c]
                val datum = if (n < 0) 0f else (bedR[n] * metres).coerceAtLeast(0f)
                b.append("%d:%s d=%.0f datum=%.0f t=%.0f | ".format(
                    col, if (m.onTheSheet[c]) "S" else ".", mg.distanceKm[c], datum,
                    m.iceThicknessMetres[c]))
            }
            println("I3 PROBE datum row $r: $b")
            // How far the datum jumps between neighbouring sheet cells, against how far the
            // profile term does.
            var pairs = 0; var datumJump = 0.0; var profileJump = 0.0; var worst = 0f
            val k = com.cartogenesis.worldgen.pipeline.IceSheet.metresPerRootKilometre(
                config.isostasy.iceDensity, config.isostasy.gravity)
            val span = kotlin.math.sqrt(config.squareKilometresPerCell).toFloat()
            fun datumOf(c: Int): Float {
                val n = mg.nearestCell[c]
                return if (n < 0) 0f else (bedR[n] * metres).coerceAtLeast(0f)
            }
            for (c in 0 until across * down) {
                if (m.iceThicknessMetres[c] <= 0f) continue
                val col = c % across
                if (col == 0 || col == across - 1) continue
                if (m.iceThicknessMetres[c + 1] <= 0f) continue
                pairs++
                val dj = kotlin.math.abs(datumOf(c) - datumOf(c + 1))
                val pj = kotlin.math.abs(
                    com.cartogenesis.worldgen.pipeline.IceSheet.profileMetres(mg.distanceKm[c], k, span) -
                    com.cartogenesis.worldgen.pipeline.IceSheet.profileMetres(mg.distanceKm[c + 1], k, span))
                datumJump += dj.toDouble(); profileJump += pj.toDouble()
                if (dj > worst) worst = dj
            }
            println("I3 PROBE east-west pairs=%d mean datum jump=%.1f m, mean profile jump=%.1f m, worst datum jump=%.0f m"
                .format(pairs, datumJump / pairs, profileJump / pairs, worst))
        }
        run {
            val metres = config.scale.highestLandMetres
            val t = m.iceThicknessMetres
            val surfaceField = carved.relativeElevation.data
            val k = com.cartogenesis.worldgen.pipeline.IceSheet.metresPerRootKilometre(
                config.isostasy.iceDensity, config.isostasy.gravity)
            val span = kotlin.math.sqrt(config.squareKilometresPerCell).toFloat()
            val bar = com.cartogenesis.worldgen.pipeline.IceSheet.profileMetres(
                config.cellWidthKm.toFloat(), k, span)
            var worstAcross = 0f; var worstDown = 0f; var overAcross = 0; var overDown = 0
            var pairsAcross = 0; var pairsDown = 0
            for (c in 0 until across * down) {
                if (t[c] <= 0f) continue
                val col = c % across; val row = c / across
                if (col < across - 1 && t[c + 1] > 0f) {
                    pairsAcross++
                    val step = kotlin.math.abs(surfaceField[c] - surfaceField[c + 1]) * metres
                    if (step > worstAcross) worstAcross = step
                    if (step > bar) overAcross++
                }
                if (row < down - 1 && t[c + across] > 0f) {
                    pairsDown++
                    val step = kotlin.math.abs(surfaceField[c] - surfaceField[c + across]) * metres
                    if (step > worstDown) worstDown = step
                    if (step > bar) overDown++
                }
            }
            println(("I3 PROBE surface step bar=%.0f m: across %d pairs, worst %.0f m, %d over;" +
                " down %d pairs, worst %.0f m, %d over")
                .format(bar, pairsAcross, worstAcross, overAcross, pairsDown, worstDown, overDown))
        }
        run {
            var tiny = 0; var under1 = 0; var under20 = 0; var under50 = 0; var all = 0
            for (c in 0 until across * down) {
                if (!frozen[c]) continue
                all++
                val v = balance.data[c]
                if (v < 1e-3f) tiny++
                if (v < 1f) under1++
                if (v < 20f) under20++
                if (v < 50f) under50++
            }
            println("I3 PROBE frozen=$all balance<1e-3mm=$tiny <1mm=$under1 <20mm=$under20 <50mm=$under50")
            // One-cell-wide filaments of sheet ice: a cell whose ice stands over both its east and
            // west neighbours, or both its north and south, neither of which carries any.
            var acrossWalls = 0; var downWalls = 0; var sheetCells = 0
            val t = m.iceThicknessMetres
            for (c in 0 until across * down) {
                if (t[c] <= 0f) continue
                sheetCells++
                val col = c % across; val row = c / across
                if (col > 0 && col < across - 1 && t[c - 1] <= 0f && t[c + 1] <= 0f) acrossWalls++
                if (row > 0 && row < down - 1 && t[c - across] <= 0f && t[c + across] <= 0f) downWalls++
            }
            println("I3 PROBE sheet=$sheetCells one-cell walls across=$acrossWalls down=$downWalls")
            val r = 940
            val b = StringBuilder()
            for (col in 430..455) b.append("%d:%.3e ".format(col, balance.data[r * across + col]))
            println("I3 PROBE balance exact row $r: $b")
        }
    }
}
