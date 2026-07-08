package com.stream.prime.overlay

import android.content.Context
import android.graphics.*
import android.util.Log
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender

/**
 * IDENTICAL CANVAS RENDERER: Uses the exact same Canvas rendering logic as OverlayPreviewView.
 * The preview and stream now use IDENTICAL canvas rendering - truly "same canvas".
 * This ensures pixel-perfect matching between what you see in preview and what gets streamed.
 */
class LayeredOverlayRenderer(private val context: Context) : ImageObjectFilterRender() {

    private var layers: List<OverlayLayer> = emptyList()
    private var canvasWidth: Int = 0
    private var canvasHeight: Int = 0
    private var compositeBitmap: Bitmap? = null
    
    // Use the same rendering logic as OverlayPreviewView
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }
    private val layerBitmapCache = mutableMapOf<String, Bitmap?>()

    companion object {
        private const val TAG = "LayeredOverlayRenderer"
    }

    fun setCanvasSize(width: Int, height: Int) {
        if (canvasWidth != width || canvasHeight != height) {
            canvasWidth = width
            canvasHeight = height
            createCompositeBitmap()
        }
    }

    fun updateLayers(newLayers: List<OverlayLayer>) {
        val previousLayerIds = layers.map { it.id }.toSet()
        val currentLayerIds = newLayers.map { it.id }.toSet()
        
        // Use shared cleanup logic
        LayerCanvasRenderer.cleanupRemovedLayers(
            previousLayerIds, 
            currentLayerIds, 
            layerBitmapCache
        )
        
        // Update layers and recompose on single canvas
        layers = newLayers
        renderAllLayersToCanvas()
        
        Log.d(TAG, "Updated layers: ${layers.size} layers")
    }

    private fun createCompositeBitmap() {
        if (canvasWidth <= 0 || canvasHeight <= 0) return
        
        // Recycle old bitmap
        compositeBitmap?.recycle()
        
        // Create new composite bitmap using shared logic
        compositeBitmap = LayerCanvasRenderer.createCanvasBitmap(canvasWidth, canvasHeight)
        
        if (compositeBitmap != null) {
            Log.d(TAG, "Created single composite canvas: ${canvasWidth}x${canvasHeight}")
            renderAllLayersToCanvas()
        } else {
            Log.e(TAG, "Failed to create composite canvas")
        }
    }

    /**
     * Render all layers using shared rendering logic.
     * Identical to OverlayPreviewView rendering.
     */
    private fun renderAllLayersToCanvas() {
        val bitmap = compositeBitmap ?: return
        val canvas = Canvas(bitmap)
        
        // Use shared rendering logic to ensure identical output
        LayerCanvasRenderer.renderLayersOnCanvas(
            canvas = canvas,
            layers = layers,
            canvasWidth = canvasWidth.toFloat(),
            canvasHeight = canvasHeight.toFloat(),
            context = context,
            layerBitmapCache = layerBitmapCache
        )
        
        // Set the composed bitmap as the GL texture
        setImage(bitmap)
        
        Log.d(TAG, "Rendered ${layers.size} layers on single canvas using shared renderer")
    }

    private fun clearCache() {
        LayerCanvasRenderer.clearBitmapCache(layerBitmapCache)
        Log.d(TAG, "Bitmap cache cleared")
    }

    override fun release() {
        clearCache()
        compositeBitmap?.recycle()
        compositeBitmap = null
        Log.d(TAG, "Single canvas renderer released")
    }

    /**
     * Get the current composite bitmap (for debugging or additional preview)
     */
    fun getCompositeBitmap(): Bitmap? = compositeBitmap

    /**
     * Force refresh - recreates the single composite canvas
     */
    fun refreshAllLayers() {
        clearCache()
        renderAllLayersToCanvas()
    }
}