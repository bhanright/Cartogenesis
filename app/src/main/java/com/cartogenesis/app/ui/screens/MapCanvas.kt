package com.cartogenesis.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.cartogenesis.worldgen.model.MapLabel
import kotlin.math.min

/**
 * Pan/zoom map view. The bitmap is drawn under a user transform; labels are drawn on top in screen
 * space so they stay readable at any zoom level.
 */
@Composable
fun MapCanvas(
    bitmap: Bitmap?,
    labels: List<MapLabel>,
    labelPlacementMode: Boolean,
    onMapTap: (normalizedX: Float, normalizedY: Float) -> Unit,
    onLabelTap: (MapLabel) -> Unit,
    modifier: Modifier = Modifier
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    val density = LocalDensity.current
    val labelTextSize = with(density) { 13.dp.toPx() }
    val labelHitRadius = with(density) { 22.dp.toPx() }
    val labelColor = Color(0xFF1A1A1A).toArgb()
    val haloColor = Color(0xFFF2E4C6).toArgb()

    Box(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, panChange, zoomChange, _ ->
                        val newZoom = (zoom * zoomChange).coerceIn(0.5f, 24f)
                        // Anchors the zoom at the gesture centroid instead of the corner.
                        pan = (pan - centroid) * (newZoom / zoom) + centroid + panChange
                        zoom = newZoom
                    }
                }
                .pointerInput(labelPlacementMode, labels, image) {
                    detectTapGestures { tap ->
                        val img = image ?: return@detectTapGestures
                        val layout = MapLayout.fit(
                            size.width.toFloat(), size.height.toFloat(), img.width, img.height
                        )

                        if (!labelPlacementMode) {
                            val hit = labels.firstOrNull { label ->
                                (layout.labelToScreen(label, zoom, pan) - tap).getDistance() <
                                    labelHitRadius
                            }
                            if (hit != null) onLabelTap(hit)
                            return@detectTapGestures
                        }

                        val point = layout.screenToImage(tap, zoom, pan)
                        val nx = point.x / img.width
                        val ny = point.y / img.height
                        if (nx in 0f..1f && ny in 0f..1f) onMapTap(nx, ny)
                    }
                }
        ) {
            val img = image ?: return@Canvas
            val layout = MapLayout.fit(size.width, size.height, img.width, img.height)

            withTransform({
                translate(pan.x, pan.y)
                scale(zoom, zoom, pivot = Offset.Zero)
                translate(layout.offsetX, layout.offsetY)
                scale(layout.scale, layout.scale, pivot = Offset.Zero)
            }) {
                drawImage(img)
            }

            drawLabels(labels, layout, zoom, pan, labelTextSize, labelColor, haloColor)
        }
    }
}

/** Maps between image pixels and screen pixels for a centred, aspect-preserving fit. */
private class MapLayout(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val imageWidth: Int,
    val imageHeight: Int
) {
    fun screenToImage(screen: Offset, zoom: Float, pan: Offset): Offset {
        val unpanned = (screen - pan) / zoom
        return Offset((unpanned.x - offsetX) / scale, (unpanned.y - offsetY) / scale)
    }

    fun labelToScreen(label: MapLabel, zoom: Float, pan: Offset): Offset = Offset(
        (label.x * imageWidth * scale + offsetX) * zoom + pan.x,
        (label.y * imageHeight * scale + offsetY) * zoom + pan.y
    )

    companion object {
        fun fit(canvasWidth: Float, canvasHeight: Float, imageWidth: Int, imageHeight: Int): MapLayout {
            val scale = min(canvasWidth / imageWidth, canvasHeight / imageHeight)
            return MapLayout(
                scale = scale,
                offsetX = (canvasWidth - imageWidth * scale) / 2f,
                offsetY = (canvasHeight - imageHeight * scale) / 2f,
                imageWidth = imageWidth,
                imageHeight = imageHeight
            )
        }
    }
}

private fun DrawScope.drawLabels(
    labels: List<MapLabel>,
    layout: MapLayout,
    zoom: Float,
    pan: Offset,
    textSize: Float,
    textColor: Int,
    haloColor: Int
) {
    if (labels.isEmpty()) return
    val canvas = drawContext.canvas.nativeCanvas

    val halo = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = haloColor
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = textSize / 5f
        this.textSize = textSize
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.SERIF
    }
    val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        this.textSize = textSize
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.SERIF
    }

    labels.forEach { label ->
        val position = layout.labelToScreen(label, zoom, pan)
        canvas.drawText(label.text, position.x, position.y, halo)
        canvas.drawText(label.text, position.x, position.y, fill)
    }
}
