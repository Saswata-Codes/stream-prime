package com.stream.prime.overlay

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Custom view that shows a preview of all overlay layers composited on a single canvas.
 * This matches exactly what the user will see in their stream.
 */
class OverlayPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var layers: List<OverlayLayer> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }
    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#1a1a1a") // Dark background to show overlay clearly
    }
    private val layerBitmapCache = mutableMapOf<String, Bitmap?>()
    
    // Grid overlay to help with positioning
    private val gridPaint = Paint().apply {
        color = Color.parseColor("#33ffffff") // Semi-transparent white
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private var showGrid = true

    // Target stream canvas size to match aspect ratio
    private var streamCanvasWidth: Int = 0
    private var streamCanvasHeight: Int = 0

    fun updateLayers(newLayers: List<OverlayLayer>) {
        val previousLayerIds = layers.map { it.id }.toSet()
        val currentLayerIds = newLayers.map { it.id }.toSet()
        
        // Use shared cleanup logic
        LayerCanvasRenderer.cleanupRemovedLayers(
            previousLayerIds,
            currentLayerIds,
            layerBitmapCache
        )
        
        layers = newLayers
        invalidate() // Trigger redraw
    }

    /**
     * Set the target stream canvas size so preview keeps identical aspect ratio with letterboxing.
     */
    fun setStreamCanvasSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (streamCanvasWidth != width || streamCanvasHeight != height) {
            streamCanvasWidth = width
            streamCanvasHeight = height
            invalidate()
        }
    }

    fun setShowGrid(show: Boolean) {
        showGrid = show
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        
        if (viewWidth <= 0 || viewHeight <= 0) return

        // Draw background
        canvas.drawRect(0f, 0f, viewWidth, viewHeight, backgroundPaint)

        // Draw grid if enabled
        if (showGrid) {
            drawGrid(canvas, viewWidth, viewHeight)
        }

        // Compute viewport that preserves stream aspect ratio within this view
        val (vpLeft, vpTop, vpWidth, vpHeight) = computeViewport(viewWidth, viewHeight)

        // Draw letterbox background (already drawn as dark bg)

        // Clip to viewport and translate so (0,0) is viewport origin
        val save = canvas.save()
        canvas.clipRect(vpLeft, vpTop, vpLeft + vpWidth, vpTop + vpHeight)
        canvas.translate(vpLeft, vpTop)

        // Use shared rendering logic to ensure identical output to stream inside viewport
        LayerCanvasRenderer.renderLayersOnCanvas(
            canvas = canvas,
            layers = layers,
            canvasWidth = vpWidth,
            canvasHeight = vpHeight,
            context = context,
            layerBitmapCache = layerBitmapCache
        )

        canvas.restoreToCount(save)
    }

    private fun drawGrid(canvas: Canvas, viewWidth: Float, viewHeight: Float) {
        val gridSpacing = 50f
        
        // Vertical lines
        var x = gridSpacing
        while (x < viewWidth) {
            canvas.drawLine(x, 0f, x, viewHeight, gridPaint)
            x += gridSpacing
        }
        
        // Horizontal lines
        var y = gridSpacing
        while (y < viewHeight) {
            canvas.drawLine(0f, y, viewWidth, y, gridPaint)
            y += gridSpacing
        }
    }

    private fun computeViewport(viewWidth: Float, viewHeight: Float): Viewport {
        if (streamCanvasWidth <= 0 || streamCanvasHeight <= 0) {
            // Fallback: use full view
            return Viewport(0f, 0f, viewWidth, viewHeight)
        }
        val targetRatio = streamCanvasWidth.toFloat() / streamCanvasHeight.toFloat()
        val viewRatio = viewWidth / viewHeight
        return if (targetRatio > viewRatio) {
            // Full width, letterbox top/bottom
            val vpWidth = viewWidth
            val vpHeight = viewWidth / targetRatio
            val vpTop = (viewHeight - vpHeight) / 2f
            Viewport(0f, vpTop, vpWidth, vpHeight)
        } else {
            // Full height, letterbox left/right
            val vpHeight = viewHeight
            val vpWidth = viewHeight * targetRatio
            val vpLeft = (viewWidth - vpWidth) / 2f
            Viewport(vpLeft, 0f, vpWidth, vpHeight)
        }
    }

    private data class Viewport(val left: Float, val top: Float, val width: Float, val height: Float)


    fun clearCache() {
        LayerCanvasRenderer.clearBitmapCache(layerBitmapCache)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        clearCache()
    }

    /**
     * Convert screen coordinates to layer percentages
     * Useful for hit testing or repositioning layers
     */
    fun screenToLayerPercent(screenX: Float, screenY: Float): Pair<Float, Float> {
        val xPercent = (screenX / width) * 100f
        val yPercent = (screenY / height) * 100f
        return Pair(xPercent.coerceIn(0f, 100f), yPercent.coerceIn(0f, 100f))
    }

    /**
     * Find which layer (if any) is at the given screen coordinates
     * Returns the topmost layer (highest zIndex)
     */
    fun getLayerAt(screenX: Float, screenY: Float): OverlayLayer? {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        
        return layers
            .filter { it.enabled && it.imageUri.isNotEmpty() }
            .sortedByDescending { it.zIndex } // Check top layers first
            .find { layer ->
                val layerX = (layer.positionXPct / 100f) * viewWidth
                val layerY = (layer.positionYPct / 100f) * viewHeight
                val layerWidth = (layer.scaleXPct / 100f) * viewWidth
                val layerHeight = (layer.scaleYPct / 100f) * viewHeight
                
                screenX >= layerX && screenX <= layerX + layerWidth &&
                screenY >= layerY && screenY <= layerY + layerHeight
            }
    }
}