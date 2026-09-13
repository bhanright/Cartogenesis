package com.cartogenesis.desktop

import com.cartogenesis.cartography.DataExports
import com.cartogenesis.cartography.DataLayer
import com.cartogenesis.cartography.MapRasterizer
import com.cartogenesis.cartography.MapSheet
import com.cartogenesis.cartography.RasterAccelerator
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.ui.BuildInfo
import com.cartogenesis.ui.MapImage
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import com.cartogenesis.ui.ExportFormat
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * Renders a world at export resolution and writes it out.
 *
 * The whole pipeline is re-run at the target size rather than upscaling the preview, so the detail
 * is real rather than interpolated, and there is enough heap to actually finish: 4096 wants roughly
 * 2GB.
 *
 * Nothing about the drawing is scaled to the size. Every mark the renderer makes — the engraving's
 * strokes, the river pen, a landmark's glyph — is either a fixed count of output pixels or a share
 * of the sheet, decided where the mark is made, so an export is drawn by the same hand as the
 * preview and simply has more room for it.
 *
 * Where the machine has a graphics device the per-pixel half of the drawing runs on it. That is not
 * governed by the acceleration switch in the panel, which is about whether the *world* can be
 * reproduced from its seed: the raster changes no part of the world, only how quickly the same
 * picture is drawn, and it is held to within a channel step of what the processor would have drawn.
 */
object Exporter {

    class Result(
        val file: File,
        val millis: Long,
        val bytes: Long,
        val format: ExportFormat
    )

    suspend fun export(
        config: WorldGenConfig,
        options: RenderOptions,
        size: Int,
        destination: File,
        format: ExportFormat = ExportFormat.PNG,
        raster: RasterAccelerator? = null
    ): Result {
        val started = System.currentTimeMillis()

        val exportConfig = config.atResolution(size, size)
        val world = WorldGenerationEngine.generateBlocking(exportConfig)

        val pixels = MapRasterizer.rasterize(world, options, raster)
        // A printed sheet: drawn cell for pixel, so nothing is generalised away, and carrying its
        // own scale bar because there is no legend beside a PNG. See [MapSheet].
        val bitmap = MapImage.toBitmap(world, options, pixels, MapSheet.PRINTED)

        val encoded = if (format == ExportFormat.JPEG) {
            encodeJpeg(bitmap, ExportFormat.JPEG_QUALITY)
        } else {
            // Quality 100 is lossless for WebP and ignored by the PNG encoder, so one call does
            // for both of those.
            Image.makeFromBitmap(bitmap).encodeToData(skiaFormat(format), quality = 100)?.bytes
        } ?: error("Could not encode the map as ${format.label}")
        destination.writeBytes(encoded)

        // The bitmap holds size*size*4 bytes; let it go before the caller renders anything else.
        bitmap.close()

        return Result(destination, System.currentTimeMillis() - started, destination.length(), format)
    }

    /**
     * The world's own numbers at [size]: a sixteen-bit heightmap, or a biome or realm index map,
     * with the sidecar JSON that says what its values mean written beside it.
     *
     * The two files come back from one call and land in one directory, named alike, because a
     * heightmap and its metre scale are one artefact in two files and separating them is how a
     * heightmap becomes a grey rectangle nobody can use. [destination] is the PNG; the sidecar
     * takes the same name with a `.json` on it, and the reader chose the directory once.
     */
    suspend fun exportData(
        config: WorldGenConfig,
        size: Int,
        destination: File,
        layer: DataLayer
    ): Result {
        val started = System.currentTimeMillis()

        val exportConfig = config.atResolution(size, size)
        val world = WorldGenerationEngine.generateBlocking(exportConfig)
        val files = DataExports.write(world, layer, GzipCompressor, BuildInfo.VERSION)

        destination.writeBytes(files.image)
        val sidecar = File(destination.parentFile, sidecarNameFor(destination.name))
        sidecar.writeBytes(files.sidecar)

        return Result(
            destination,
            System.currentTimeMillis() - started,
            destination.length() + sidecar.length(),
            // A data export is not one of the picture formats and does not pretend to be; the
            // result records PNG because that is what the image beside the JSON actually is.
            ExportFormat.PNG
        )
    }

    fun defaultName(config: WorldGenConfig, size: Int, format: ExportFormat): String =
        "cartogenesis-${config.seed}-$size.${format.extension}"

    fun defaultDataName(config: WorldGenConfig, size: Int, layer: DataLayer): String =
        "${DataExports.baseName(config, size, layer)}.png"

    /**
     * The sidecar's name, taken from whatever the reader called the image.
     *
     * From the chosen name rather than from the default, so that renaming the PNG in the save
     * dialog renames the JSON with it and the pair stays a pair.
     */
    internal fun sidecarNameFor(imageName: String): String =
        imageName.substringBeforeLast('.', imageName) + ".json"

    /**
     * The map as a JPEG, through the JDK's own encoder.
     *
     * `ImageIO` rather than Skia, which encodes the other two formats, for the one thing this
     * format needs that the others do not: an explicit quality. Skia's `encodeToData` takes a
     * quality too, but the JDK's writer is also where the "no alpha" half of the requirement is
     * settled honestly — the image is built as `TYPE_3BYTE_BGR`, which has no alpha channel to
     * discard, rather than handed to an encoder that will drop one however it sees fit.
     *
     * [quality] is a parameter rather than [ExportFormat.JPEG_QUALITY] read here so that the guard
     * can encode the same rendered map at two qualities and show that the bound it holds the
     * shipped one to is a bound that discriminates. Nothing in the application passes anything but
     * the shipped figure.
     */
    internal fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val image = toBufferedImage(bitmap)
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val parameters = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality / 100f
        }
        val bytes = java.io.ByteArrayOutputStream(1 shl 20)
        ImageIO.createImageOutputStream(bytes).use { stream ->
            writer.output = stream
            writer.write(null, IIOImage(image, null, null), parameters)
        }
        writer.dispose()
        return bytes.toByteArray()
    }

    /**
     * Skia's pixels as an AWT image with no alpha.
     *
     * Skia hands back BGRA in that order on this platform, which is exactly the byte order
     * `TYPE_3BYTE_BGR` wants with the fourth byte dropped, so the copy below is a stride change
     * and not a colour conversion.
     */
    private fun toBufferedImage(bitmap: Bitmap): BufferedImage {
        val width = bitmap.width
        val height = bitmap.height
        val source = bitmap.readPixels() ?: error("Could not read the rendered map back")
        val image = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
        val target = (image.raster.dataBuffer as java.awt.image.DataBufferByte).data
        for (pixel in 0 until width * height) {
            val from = pixel * 4
            val to = pixel * 3
            target[to] = source[from]
            target[to + 1] = source[from + 1]
            target[to + 2] = source[from + 2]
        }
        return image
    }
}

/** Skia's name for a format. Kept here so the shared enum needs no knowledge of Skia. */
private fun skiaFormat(format: ExportFormat): EncodedImageFormat =
    if (format == ExportFormat.PNG) EncodedImageFormat.PNG else EncodedImageFormat.WEBP
