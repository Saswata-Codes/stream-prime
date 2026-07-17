package com.stream.prime.overlay

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.Log
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender

/**
 * IDENTICAL CANVAS RENDERER: Uses the exact same Canvas rendering logic as OverlayPreviewView.
 * The preview and stream now use IDENTICAL canvas rendering - truly "same canvas".
 * This ensures pixel-perfect matching between what you see in preview and what gets streamed.
 */
class LayeredOverlayRenderer(private val context: Context) : ImageObjectFilterRender() {

    init {
        // GIF frames redraw into one composition bitmap; the GL uploader must retain it.
        recycleStreamObjectBitmapsAfterLoad = false
        reuseStreamObjectTexture = true
    }

    private var layers: List<OverlayLayer> = emptyList()
    private var canvasWidth: Int = 0
    private var canvasHeight: Int = 0
    private var compositeBitmap: Bitmap? = null
    
    private val layerAssetCache = OverlayAssetCache()
    private var hasAnimatedLayers = false
    private var lastAnimationRequestMs = 0L
    private var animationCompositor: AsyncOverlayFrameCompositor? = null

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
        closeAnimationCompositor()
        val previousLayerIds = layers.map { it.id }.toSet()
        val currentLayerIds = newLayers.map { it.id }.toSet()
        
        // Use shared cleanup logic
        LayerCanvasRenderer.cleanupRemovedLayers(
            previousLayerIds, 
            currentLayerIds, 
            layerAssetCache
        )
        
        // Update layers and recompose on single canvas
        layers = newLayers
        renderAllLayersToCanvas(SystemClock.uptimeMillis())
        
        Log.d(TAG, "Updated layers: ${layers.size} layers")
    }

    private fun createCompositeBitmap() {
        if (canvasWidth <= 0 || canvasHeight <= 0) return

        closeAnimationCompositor()
        // Recycle old bitmap
        compositeBitmap?.recycle()
        
        // Create new composite bitmap using shared logic
        compositeBitmap = LayerCanvasRenderer.createCanvasBitmap(canvasWidth, canvasHeight)
        
        if (compositeBitmap != null) {
            Log.d(TAG, "Created single composite canvas: ${canvasWidth}x${canvasHeight}")
            renderAllLayersToCanvas(SystemClock.uptimeMillis())
        } else {
            Log.e(TAG, "Failed to create composite canvas")
        }
    }

    /**
     * Render all layers using shared rendering logic.
     * Identical to OverlayPreviewView rendering.
     */
    private fun renderAllLayersToCanvas(frameTimeMs: Long) {
        val bitmap = compositeBitmap ?: return
        hasAnimatedLayers = renderLayersInto(bitmap, layers, frameTimeMs)
        lastAnimationRequestMs = if (hasAnimatedLayers) frameTimeMs else 0L
        
        // Set the composed bitmap as the GL texture
        setImage(bitmap)

        if (hasAnimatedLayers) {
            val layerSnapshot = layers.toList()
            val backBuffer = LayerCanvasRenderer.createCanvasBitmap(canvasWidth, canvasHeight)
            if (backBuffer != null) {
                animationCompositor = AsyncOverlayFrameCompositor(
                    threadName = "OverlayGifForeground",
                    initialBackBuffer = backBuffer
                ) { target, timeMs ->
                    renderLayersInto(target, layerSnapshot, timeMs)
                }
            }
        }
    }

    private fun renderLayersInto(
        target: Bitmap,
        layerSnapshot: List<OverlayLayer>,
        frameTimeMs: Long
    ): Boolean {
        return LayerCanvasRenderer.renderLayersOnCanvas(
            canvas = Canvas(target),
            layers = layerSnapshot,
            canvasWidth = canvasWidth.toFloat(),
            canvasHeight = canvasHeight.toFloat(),
            context = context,
            layerAssetCache = layerAssetCache,
            frameTimeMs = frameTimeMs
        ).hasAnimatedLayers
    }

    /**
     * The filter still draws on every encoder frame. Only GIF software composition is throttled,
     * and that work runs asynchronously so it can never turn the GIF rate into the video rate.
     */
    override fun drawFilter() {
        val now = SystemClock.uptimeMillis()
        val currentFront = compositeBitmap
        if (currentFront != null) {
            animationCompositor?.consume(currentFront)?.let { completed ->
                compositeBitmap = completed.bitmap
                hasAnimatedLayers = completed.hasAnimatedLayers
                setImage(completed.bitmap)
            }
        }
        if (
            hasAnimatedLayers &&
            now - lastAnimationRequestMs >= OVERLAY_ANIMATION_FRAME_INTERVAL_MS &&
            animationCompositor?.request(now) == true
        ) {
            lastAnimationRequestMs = now
        }
        super.drawFilter()
    }

    private fun closeAnimationCompositor() {
        animationCompositor?.close()
        animationCompositor = null
    }

    private fun clearCache() {
        LayerCanvasRenderer.clearAssetCache(layerAssetCache)
        Log.d(TAG, "Overlay asset cache cleared")
    }

    override fun release() {
        closeAnimationCompositor()
        clearCache()
        super.release()
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
        closeAnimationCompositor()
        clearCache()
        renderAllLayersToCanvas(SystemClock.uptimeMillis())
    }
}
