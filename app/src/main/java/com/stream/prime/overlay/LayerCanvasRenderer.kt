package com.stream.prime.overlay

import android.content.Context
import android.graphics.*

/**
 * Shared canvas rendering logic used by both OverlayPreviewView and LayeredOverlayRenderer.
 * This ensures the preview and actual stream overlay look identical.
 */
internal object LayerCanvasRenderer {

    data class RenderResult(val hasAnimatedLayers: Boolean)
    
    private val paintByThread = ThreadLocal.withInitial {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    }
    
    /**
     * Render all layers onto the provided canvas.
     * This method is used by both preview and stream rendering.
     */
    fun renderLayersOnCanvas(
        canvas: Canvas,
        layers: List<OverlayLayer>,
        canvasWidth: Float,
        canvasHeight: Float,
        context: Context,
        layerAssetCache: OverlayAssetCache,
        clearCanvas: Boolean = true,
        frameTimeMs: Long = android.os.SystemClock.uptimeMillis()
    ): RenderResult {
        if (canvasWidth <= 0 || canvasHeight <= 0) return RenderResult(false)

        if (clearCanvas) {
            // Stream overlay bitmaps need transparency. Preview can retain its screen placeholder.
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        }

        // Draw each enabled layer in z-order
        var hasAnimatedLayers = false
        layers
            .filter { it.enabled && (it.type == OverlayLayerType.TEXT || it.imageUri.isNotEmpty()) }
            .sortedBy { it.zIndex }
            .forEach { layer ->
                hasAnimatedLayers = renderSingleLayer(
                    canvas,
                    layer,
                    canvasWidth,
                    canvasHeight,
                    context,
                    layerAssetCache,
                    frameTimeMs
                ) || hasAnimatedLayers
            }
        return RenderResult(hasAnimatedLayers)
    }
    
    /**
     * Render a single layer on the canvas.
     * Identical positioning and scaling logic for both preview and stream.
     */
    private fun renderSingleLayer(
        canvas: Canvas,
        layer: OverlayLayer,
        canvasWidth: Float,
        canvasHeight: Float,
        context: Context,
        layerAssetCache: OverlayAssetCache,
        frameTimeMs: Long
    ): Boolean {
        try {
            val paint = paintByThread.get()
            if (layer.type == OverlayLayerType.TEXT) {
                renderTextLayer(canvas, layer, canvasWidth, canvasHeight, paint)
                return false
            }
            val asset = layerAssetCache.getOrLoad(context, layer) ?: return false
            
            // Calculate layer position and desired box based on percentages
            val layerX = (layer.positionXPct / 100f) * canvasWidth
            val layerY = (layer.positionYPct / 100f) * canvasHeight
            val desiredW = (layer.scaleXPct / 100f) * canvasWidth
            val desiredH = (layer.scaleYPct / 100f) * canvasHeight
            
            // Set alpha for this layer
            paint.alpha = (layer.alpha * 255).toInt()
            
            // Preserve aspect ratio: compute a uniform scale based on desired box
            val bmpW = asset.width.toFloat()
            val bmpH = asset.height.toFloat()

            // Determine scale factor
            val scale: Float = when {
                desiredW <= 0f && desiredH <= 0f -> 1f // draw at original size
                desiredW <= 0f -> desiredH / bmpH
                desiredH <= 0f -> desiredW / bmpW
                else -> minOf(desiredW / bmpW, desiredH / bmpH)
            }

            val drawW = bmpW * scale
            val drawH = bmpH * scale

            // Destination rectangle using top-left anchor (no stretching)
            val destRect = RectF(layerX, layerY, layerX + drawW, layerY + drawH)

            // GIF and still image assets share identical aspect/transform math.
            asset.draw(canvas, destRect, layer.rotationDegrees, paint, frameTimeMs)
            return asset.animated
            
        } catch (_: Exception) {
            // Silent fail to prevent crashes in either preview or stream
            return false
        }
    }

    private fun renderTextLayer(
        canvas: Canvas,
        layer: OverlayLayer,
        canvasWidth: Float,
        canvasHeight: Float,
        paint: Paint
    ) {
        val left = layer.positionXPct / 100f * canvasWidth
        val top = layer.positionYPct / 100f * canvasHeight
        val width = layer.scaleXPct / 100f * canvasWidth
        val height = layer.scaleYPct / 100f * canvasHeight
        val rect = RectF(left, top, left + width, top + height)
        val save = canvas.save()
        canvas.rotate(layer.rotationDegrees, rect.centerX(), rect.centerY())
        paint.alpha = (layer.alpha * 255).toInt()
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor(layer.backgroundColor)
        canvas.drawRoundRect(rect, 8f, 8f, paint)
        paint.color = Color.parseColor(layer.textColor)
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = (height * 0.55f).coerceAtLeast(12f)
        val baseline = rect.centerY() - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(layer.text, rect.centerX(), baseline, paint)
        paint.textAlign = Paint.Align.LEFT
        canvas.restoreToCount(save)
    }
    
    /**
     * Clear decoded assets to prevent bitmap/native decoder leaks.
     */
    fun clearAssetCache(layerAssetCache: OverlayAssetCache) {
        layerAssetCache.clear()
    }
    
    /**
     * Remove specific layers from cache when they're no longer needed.
     */
    fun cleanupRemovedLayers(
        previousLayerIds: Set<String>,
        currentLayerIds: Set<String>,
        layerAssetCache: OverlayAssetCache
    ) {
        val removedLayerIds = previousLayerIds - currentLayerIds
        if (removedLayerIds.isEmpty()) return
        layerAssetCache.removeLayerIds(removedLayerIds)
    }
    
    /**
     * Create an optimized bitmap for the given dimensions.
     */
    fun createCanvasBitmap(width: Int, height: Int): Bitmap? {
        return try {
            if (width <= 0 || height <= 0) null
            else Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            null
        }
    }
}
