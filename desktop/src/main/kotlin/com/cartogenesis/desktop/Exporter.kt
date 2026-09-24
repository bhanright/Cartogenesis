package com.cartogenesis.desktop

import com.cartogenesis.cartography.DataExports
import com.cartogenesis.cartography.DataLayer
import com.cartogenesis.cartography.ExportedWorld
import com.cartogenesis.cartography.MapRasterizer
import com.cartogenesis.cartography.MapSheet
import com.cartogenesis.cartography.RasterAccelerator
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.ui.BuildInfo
import com.cartogenesis.ui.MapImage
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import com.cartogenesis.ui.ExportFormat
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Draws a world and writes it out.
 *
 * Which world is the caller's decision — the one on screen at its own size, or one made again at
 * another size, so the detail is real rather than interpolated; see `ExportSubjects` in `:ui`.
 * This draws what it is given, at the size it was generated at.
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

    /**
     * [world] drawn with [options] on a printed sheet and written to [destination] as [format].
     *
     * Checks for cancellation before it writes, so an export superseded while it was drawing leaves
     * no file behind it.
     */
    suspend fun export(
        world: WorldMap,
        options: RenderOptions,
        destination: File,
        format: ExportFormat = ExportFormat.PNG,
        raster: RasterAccelerator? = null
    ): Result {
        val started = System.currentTimeMillis()

        val pixels = MapRasterizer.rasterize(world, options, raster)
        // A printed sheet: the whole true-shape sheet, so nothing is generalised away, and carrying
        // its own scale bar because there is no legend beside a PNG. See [MapSheet]. A world N
        // cells square comes out 2N pixels wide and N tall, one pixel the same ground either way.
        val bitmap = MapImage.toBitmap(world, options, pixels, MapSheet.PRINTED)
        // Immutable, so the encoder's Image shares the sheet's pixels rather than copying them.
        bitmap.setImmutable()

        val encoded = if (format == ExportFormat.JPEG) {
            encodeJpeg(bitmap, ExportFormat.JPEG_QUALITY)
        } else {
            // Quality 100 is lossless for WebP and ignored by the PNG encoder, so one call does
            // for both of those.
            val image = Image.makeFromBitmap(bitmap)
            val data = image.encodeToData(skiaFormat(format), quality = LOSSLESS_QUALITY)?.bytes
            image.close()
            data
        } ?: error("Could not encode the map as ${format.label}")
        currentCoroutineContext().ensureActive()
        destination.writeBytes(encoded)

        // The bitmap holds the whole sheet, width*height*4 bytes; let it go before the caller
        // renders anything else.
        bitmap.close()

        return Result(destination, System.currentTimeMillis() - started, destination.length(), format)
    }

    /**
     * [world]'s own numbers: a sixteen-bit heightmap, or a biome or realm index map, with the
     * sidecar JSON that says what its values mean — and, from [source], which world it was —
     * written beside it.
     *
     * The two files come back from one call and land in one directory, named alike, because a
     * heightmap and its metre scale are one artefact in two files and separating them is how a
     * heightmap becomes a grey rectangle nobody can use. [destination] is the PNG; the sidecar
     * takes the same name with a `.json` on it, and the reader chose the directory once.
     */
    suspend fun exportData(
        world: WorldMap,
        destination: File,
        layer: DataLayer,
        source: ExportedWorld = ExportedWorld.OnScreen
    ): Result {
        val started = System.currentTimeMillis()

        val files = DataExports.write(world, layer, GzipCompressor, BuildInfo.VERSION, source)

        currentCoroutineContext().ensureActive()
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
        // A megabyte to start with, which is about what a 2048 JPEG comes to; the stream grows.
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

/** Lossless for WebP and ignored by the PNG encoder, so one figure does for both. */
private const val LOSSLESS_QUALITY = 100

/** Skia's name for a format. Kept here so the shared enum needs no knowledge of Skia. */
private fun skiaFormat(format: ExportFormat): EncodedImageFormat =
    if (format == ExportFormat.PNG) EncodedImageFormat.PNG else EncodedImageFormat.WEBP
