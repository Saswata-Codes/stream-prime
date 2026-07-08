package com.stream.prime.overlay

import android.content.Context
import android.graphics.*

/**
 * Shared canvas rendering logic used by both OverlayPreviewView and LayeredOverlayRenderer.
 * This ensures the preview and actual stream overlay look identical.
 */
object LayerCanvasRenderer {
    
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
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
        layerBitmapCache: MutableMap<String, Bitmap?>
    ) {
        if (canvasWidth <= 0 || canvasHeight <= 0) return

        // Clear the canvas with transparent background
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        // Draw each enabled layer in z-order
        layers
            .filter { it.enabled && it.imageUri.isNotEmpty() }
            .sortedBy { it.zIndex }
            .forEach { layer ->
                renderSingleLayer(canvas, layer, canvasWidth, canvasHeight, context, layerBitmapCache)
            }
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
        layerBitmapCache: MutableMap<String, Bitmap?>
    ) {
        try {
            val layerBitmap = getOrLoadLayerBitmap(layer, context, layerBitmapCache) ?: return
            
            // Calculate layer position and desired box based on percentages
            val layerX = (layer.positionXPct / 100f) * canvasWidth
            val layerY = (layer.positionYPct / 100f) * canvasHeight
            val desiredW = (layer.scaleXPct / 100f) * canvasWidth
            val desiredH = (layer.scaleYPct / 100f) * canvasHeight
            
            // Set alpha for this layer
            paint.alpha = (layer.alpha * 255).toInt()
            
            // Preserve aspect ratio: compute a uniform scale based on desired box
            val bmpW = layerBitmap.width.toFloat()
            val bmpH = layerBitmap.height.toFloat()

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

            // Draw the layer bitmap directly on canvas preserving aspect ratio
            canvas.drawBitmap(layerBitmap, null, destRect, paint)
            
        } catch (e: Exception) {
            // Silent fail to prevent crashes in either preview or stream
        }
    }
    
    /**
     * Load and cache layer bitmaps.
     * Shared caching logic for consistent behavior.
     */
    private fun getOrLoadLayerBitmap(
        layer: OverlayLayer,
        context: Context,
        layerBitmapCache: MutableMap<String, Bitmap?>
    ): Bitmap? {
        val cacheKey = "${layer.id}::${layer.imageUri}"

        // Return cached if exists
        layerBitmapCache[cacheKey]?.let { return it }

        // Cleanup any stale entries for this layer id (old image URIs or legacy keys)
        val staleKeys = layerBitmapCache.keys.filter { key ->
            key == layer.id || key.startsWith("${layer.id}::") && key != cacheKey
        }
        staleKeys.forEach { key ->
            layerBitmapCache[key]?.recycle()
            layerBitmapCache.remove(key)
        }

        // Load bitmap from URI (may return null if empty)
        val bitmap = OverlayManager.decodeBitmap(context, layer.imageUri)
        layerBitmapCache[cacheKey] = bitmap
        return bitmap
    }
    
    /**
     * Clear bitmap cache and recycle bitmaps to prevent memory leaks.
     */
    fun clearBitmapCache(layerBitmapCache: MutableMap<String, Bitmap?>) {
        layerBitmapCache.values.forEach { bitmap ->
            bitmap?.recycle()
        }
        layerBitmapCache.clear()
    }
    
    /**
     * Remove specific layers from cache when they're no longer needed.
     */
    fun cleanupRemovedLayers(
        previousLayerIds: Set<String>,
        currentLayerIds: Set<String>,
        layerBitmapCache: MutableMap<String, Bitmap?>
    ) {
        val removedLayerIds = previousLayerIds - currentLayerIds
        if (removedLayerIds.isEmpty()) return
        val keysToRemove = layerBitmapCache.keys.filter { key ->
            removedLayerIds.any { id -> key == id || key.startsWith("$id::") }
        }
        keysToRemove.forEach { key ->
            layerBitmapCache[key]?.recycle()
            layerBitmapCache.remove(key)
        }
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