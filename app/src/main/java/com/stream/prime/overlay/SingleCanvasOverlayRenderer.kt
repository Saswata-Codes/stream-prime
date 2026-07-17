package com.stream.prime.overlay

import android.content.Context
import android.graphics.*
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.utils.gl.GlUtil

/**
 * Single canvas overlay renderer that renders overlays identically to OverlayPreviewView.
 * This approach creates a canvas-rendered bitmap and overlays it onto the video stream
 * using GL blending, ensuring the same visual output as the preview.
 */
class SingleCanvasOverlayRenderer(private val context: Context) : BaseFilterRender() {

    private var layers: List<OverlayLayer> = emptyList()
    private var canvasWidth: Int = 0
    private var canvasHeight: Int = 0
    private val layerAssetCache = OverlayAssetCache()
    private var overlayBitmap: Bitmap? = null
    private var overlayTextureId = IntArray(1)
    
    // Shader program for overlay blending
    private var program = -1
    private var aPositionHandle = -1
    private var aTextureHandle = -1
    private var uMVPMatrixHandle = -1
    private var uSTMatrixHandle = -1
    private var uSamplerHandle = -1
    private var uOverlayHandle = -1
    private var uAlphaHandle = -1

    companion object {
        private const val TAG = "SingleCanvasOverlayRenderer"
        
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTextureCoord;
            varying vec2 vTextureCoord;
            uniform mat4 uMVPMatrix;
            uniform mat4 uSTMatrix;
            
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTextureCoord = (uSTMatrix * vec4(aTextureCoord, 0.0, 1.0)).xy;
            }
        """
        
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform sampler2D uSampler;
            uniform sampler2D uOverlay;
            uniform float uAlpha;
            
            void main() {
                vec4 videoColor = texture2D(uSampler, vTextureCoord);
                vec4 overlayColor = texture2D(uOverlay, vTextureCoord);
                
                // Blend overlay onto video using alpha blending
                vec3 result = mix(videoColor.rgb, overlayColor.rgb, overlayColor.a * uAlpha);
                gl_FragColor = vec4(result, videoColor.a);
            }
        """
    }

    override fun initGlFilter(context: Context) {
        // Create shader program
        program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        
        if (program != 0) {
            aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            aTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
            uMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
            uSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
            uSamplerHandle = GLES20.glGetUniformLocation(program, "uSampler")
            uOverlayHandle = GLES20.glGetUniformLocation(program, "uOverlay")
            uAlphaHandle = GLES20.glGetUniformLocation(program, "uAlpha")
            
            // Generate overlay texture
            GLES20.glGenTextures(1, overlayTextureId, 0)
            
            Log.d(TAG, "Initialized single canvas overlay renderer with custom shader")
        } else {
            Log.e(TAG, "Failed to create shader program")
        }
    }

    override fun drawFilter() {
        if (layers.isEmpty() || program == 0 || overlayTextureId[0] == 0) {
            return // No overlays to draw
        }

        // Update overlay texture if needed
        updateOverlayTexture()
        
        // Use our custom shader program
        GLES20.glUseProgram(program)
        
        // Set up vertex attributes (using inherited vertex buffer)
        squareVertex.position(SQUARE_VERTEX_DATA_POS_OFFSET)
        GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false,
            SQUARE_VERTEX_DATA_STRIDE_BYTES, squareVertex)
        GLES20.glEnableVertexAttribArray(aPositionHandle)

        squareVertex.position(SQUARE_VERTEX_DATA_UV_OFFSET)
        GLES20.glVertexAttribPointer(aTextureHandle, 2, GLES20.GL_FLOAT, false,
            SQUARE_VERTEX_DATA_STRIDE_BYTES, squareVertex)
        GLES20.glEnableVertexAttribArray(aTextureHandle)

        // Set matrices
        GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, MVPMatrix, 0)
        GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, STMatrix, 0)
        
        // Bind video texture to texture unit 0
        GLES20.glUniform1i(uSamplerHandle, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previousTexId)
        
        // Bind overlay texture to texture unit 1
        GLES20.glUniform1i(uOverlayHandle, 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId[0])
        
        // Set overlay alpha
        GLES20.glUniform1f(uAlphaHandle, 1.0f)
        
        // Enable blending
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        
        // Draw
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        
        // Disable blending
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    override fun disableResources() {
        GLES20.glDisableVertexAttribArray(aPositionHandle)
        GLES20.glDisableVertexAttribArray(aTextureHandle)
    }

    private fun updateOverlayTexture() {
        // Create overlay bitmap if size changed
        if (overlayBitmap?.width != canvasWidth || overlayBitmap?.height != canvasHeight) {
            createOverlayBitmap()
        }
        
        val bitmap = overlayBitmap ?: return
        
        // Render overlays to canvas using the same logic as preview
        val canvas = Canvas(bitmap)
        
        // Clear canvas
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        
        // Use shared rendering logic to ensure identical output to preview
        LayerCanvasRenderer.renderLayersOnCanvas(
            canvas = canvas,
            layers = layers,
            canvasWidth = canvasWidth.toFloat(),
            canvasHeight = canvasHeight.toFloat(),
            context = context,
            layerAssetCache = layerAssetCache
        )
        
        // Upload bitmap to GL texture
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        
        Log.d(TAG, "Updated overlay texture with ${layers.size} layers")
    }

    private fun createOverlayBitmap() {
        if (canvasWidth <= 0 || canvasHeight <= 0) return
        
        // Recycle old bitmap
        overlayBitmap?.recycle()
        
        // Create new overlay bitmap
        overlayBitmap = LayerCanvasRenderer.createCanvasBitmap(canvasWidth, canvasHeight)
        
        Log.d(TAG, "Created overlay bitmap: ${canvasWidth}x${canvasHeight}")
    }

    fun setCanvasSize(width: Int, height: Int) {
        if (canvasWidth != width || canvasHeight != height) {
            canvasWidth = width
            canvasHeight = height
            createOverlayBitmap()
            Log.d(TAG, "Canvas size set to ${width}x${height}")
        }
    }

    fun updateLayers(newLayers: List<OverlayLayer>) {
        val previousLayerIds = layers.map { it.id }.toSet()
        val currentLayerIds = newLayers.map { it.id }.toSet()
        
        // Clean up removed layers
        LayerCanvasRenderer.cleanupRemovedLayers(
            previousLayerIds, 
            currentLayerIds, 
            layerAssetCache
        )
        
        layers = newLayers
        Log.d(TAG, "Updated layers: ${layers.size} layers for single canvas rendering")
    }

    fun refreshAllLayers() {
        LayerCanvasRenderer.clearAssetCache(layerAssetCache)
    }

    override fun release() {
        LayerCanvasRenderer.clearAssetCache(layerAssetCache)
        overlayBitmap?.recycle()
        overlayBitmap = null
        
        if (overlayTextureId[0] != 0) {
            GLES20.glDeleteTextures(1, overlayTextureId, 0)
            overlayTextureId[0] = 0
        }
        
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        
        Log.d(TAG, "Single canvas overlay renderer released")
    }
}
