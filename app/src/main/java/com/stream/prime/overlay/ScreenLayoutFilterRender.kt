package com.stream.prime.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.Matrix
import com.pedro.encoder.input.gl.render.BaseRenderOffScreen
import com.pedro.encoder.input.gl.TextureLoader
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.utils.gl.GlUtil
import com.pedro.encoder.utils.gl.ImageStreamObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.round

/**
 * Places the captured screen inside the output canvas before image overlays are applied.
 * Pixels outside the screen rectangle use the same dark background as OverlayPreviewView.
 */
class ScreenLayoutFilterRender(
  initialFramePreset: ScreenPreset,
  initialPortraitLayout: ScreenLayout,
  initialLandscapeLayout: ScreenLayout,
  initialPortraitFitMode: ScreenFitMode,
  initialLandscapeFitMode: ScreenFitMode,
  initialTheme: CanvasTheme,
  initialCaptureLandscapeAspect: Float
) : BaseFilterRender() {

  private var framePreset = initialFramePreset
  private var portraitLayout = initialPortraitLayout.normalized()
  private var landscapeLayout = initialLandscapeLayout.normalized()
  private var portraitFitMode = initialPortraitFitMode
  private var landscapeFitMode = initialLandscapeFitMode
  private var backgroundColor = colorComponents(initialTheme.backgroundColor)
  private var program = -1
  private var aPositionHandle = -1
  private var aTextureHandle = -1
  private var uMvpMatrixHandle = -1
  private var uStMatrixHandle = -1
  private var uSamplerHandle = -1
  private var uScreenPositionHandle = -1
  private var uScreenSizeHandle = -1
  private var uScreenRotationHandle = -1
  private var uCanvasAspectHandle = -1
  private var uSourceCropPositionHandle = -1
  private var uSourceCropSizeHandle = -1
  private var uSourceTexelSizeHandle = -1
  private var uContentSizeHandle = -1
  private var uBackgroundColorHandle = -1
  private var uBackgroundSamplerHandle = -1
  private var uHasBackgroundHandle = -1
  private val backgroundImage = ImageStreamObject()
  private val textureLoader = TextureLoader()
  private var backgroundTextureId = -1
  @Volatile private var shouldLoadBackground = false
  @Volatile private var hasBackground = false
  private var canvasAspect = 1f
  private var canvasWidth = 1
  private var canvasHeight = 1
  @Volatile private var captureLandscapeAspect = initialCaptureLandscapeAspect.coerceAtLeast(0.0001f)
  @Volatile private var landscapeSource = false

  init {
    val vertexData = floatArrayOf(
      -1f, -1f, 0f, 0f, 0f,
      1f, -1f, 0f, 1f, 0f,
      -1f, 1f, 0f, 0f, 1f,
      1f, 1f, 0f, 1f, 1f
    )
    squareVertex = ByteBuffer.allocateDirect(vertexData.size * BaseRenderOffScreen.FLOAT_SIZE_BYTES)
      .order(ByteOrder.nativeOrder())
      .asFloatBuffer()
      .apply {
        put(vertexData)
        position(0)
      }
    Matrix.setIdentityM(MVPMatrix, 0)
    Matrix.setIdentityM(STMatrix, 0)
  }

  override fun initGlFilter(context: Context) {
    program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
    aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
    aTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
    uMvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
    uStMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
    uSamplerHandle = GLES20.glGetUniformLocation(program, "uSampler")
    uScreenPositionHandle = GLES20.glGetUniformLocation(program, "uScreenPosition")
    uScreenSizeHandle = GLES20.glGetUniformLocation(program, "uScreenSize")
    uScreenRotationHandle = GLES20.glGetUniformLocation(program, "uScreenRotation")
    uCanvasAspectHandle = GLES20.glGetUniformLocation(program, "uCanvasAspect")
    uSourceCropPositionHandle = GLES20.glGetUniformLocation(program, "uSourceCropPosition")
    uSourceCropSizeHandle = GLES20.glGetUniformLocation(program, "uSourceCropSize")
    uSourceTexelSizeHandle = GLES20.glGetUniformLocation(program, "uSourceTexelSize")
    uContentSizeHandle = GLES20.glGetUniformLocation(program, "uContentSize")
    uBackgroundColorHandle = GLES20.glGetUniformLocation(program, "uBackgroundColor")
    uBackgroundSamplerHandle = GLES20.glGetUniformLocation(program, "uBackgroundSampler")
    uHasBackgroundHandle = GLES20.glGetUniformLocation(program, "uHasBackground")
  }

  override fun drawFilter() {
    if (shouldLoadBackground) {
      releaseBackgroundTexture()
      backgroundTextureId = textureLoader.load(backgroundImage.bitmaps).firstOrNull() ?: -1
      shouldLoadBackground = false
    }
    val sourcePreset = if (landscapeSource) ScreenPreset.LANDSCAPE else ScreenPreset.PORTRAIT
    val fitMode = if (framePreset == ScreenPreset.PORTRAIT) portraitFitMode else landscapeFitMode
    val baseSize = CaptureOrientation.contentSize(
      canvasAspect,
      framePreset,
      fitMode,
      captureLandscapeAspect
    )
    val layout = (if (framePreset == ScreenPreset.PORTRAIT) portraitLayout else landscapeLayout)
      .normalized(baseSize.first, baseSize.second)
    val rawScreenX = layout.positionXPct / 100f
    val rawScreenY = layout.positionYPct / 100f
    val rawScreenWidth = layout.scalePct / 100f * baseSize.first
    val rawScreenHeight = layout.scalePct / 100f * baseSize.second
    // Axis-aligned frame edges must land between output pixels. Fractional boundaries can
    // expose a partially sampled row/column that looks like a soft or dirty outline.
    val screenX = snapToPixel(rawScreenX, canvasWidth)
    val screenY = snapToPixel(rawScreenY, canvasHeight)
    val screenRight = snapToPixel(rawScreenX + rawScreenWidth, canvasWidth)
    val screenBottom = snapToPixel(rawScreenY + rawScreenHeight, canvasHeight)
    val screenWidth = (screenRight - screenX).coerceAtLeast(1f / canvasWidth)
    val screenHeight = (screenBottom - screenY).coerceAtLeast(1f / canvasHeight)
    val sourceCrop = CaptureOrientation.sourceCrop(
      canvasAspect,
      sourcePreset,
      captureLandscapeAspect
    )
    val contentSize = CaptureOrientation.sourceFitSize(
      canvasAspect,
      framePreset,
      sourcePreset,
      fitMode,
      captureLandscapeAspect
    )
    GLES20.glUseProgram(program)

    squareVertex.position(BaseRenderOffScreen.SQUARE_VERTEX_DATA_POS_OFFSET)
    GLES20.glVertexAttribPointer(
      aPositionHandle,
      3,
      GLES20.GL_FLOAT,
      false,
      BaseRenderOffScreen.SQUARE_VERTEX_DATA_STRIDE_BYTES,
      squareVertex
    )
    GLES20.glEnableVertexAttribArray(aPositionHandle)

    squareVertex.position(BaseRenderOffScreen.SQUARE_VERTEX_DATA_UV_OFFSET)
    GLES20.glVertexAttribPointer(
      aTextureHandle,
      2,
      GLES20.GL_FLOAT,
      false,
      BaseRenderOffScreen.SQUARE_VERTEX_DATA_STRIDE_BYTES,
      squareVertex
    )
    GLES20.glEnableVertexAttribArray(aTextureHandle)

    GLES20.glUniformMatrix4fv(uMvpMatrixHandle, 1, false, MVPMatrix, 0)
    GLES20.glUniformMatrix4fv(uStMatrixHandle, 1, false, STMatrix, 0)
    GLES20.glUniform2f(
      uScreenPositionHandle,
      screenX,
      screenY
    )
    GLES20.glUniform2f(uScreenSizeHandle, screenWidth, screenHeight)
    GLES20.glUniform1f(uScreenRotationHandle, layout.rotationDegrees)
    GLES20.glUniform1f(uCanvasAspectHandle, canvasAspect)
    GLES20.glUniform2f(uSourceCropPositionHandle, sourceCrop.x, sourceCrop.y)
    GLES20.glUniform2f(uSourceCropSizeHandle, sourceCrop.width, sourceCrop.height)
    GLES20.glUniform2f(uSourceTexelSizeHandle, 1f / canvasWidth, 1f / canvasHeight)
    GLES20.glUniform2f(uContentSizeHandle, contentSize.first, contentSize.second)
    setColorUniform(uBackgroundColorHandle, backgroundColor)
    GLES20.glUniform1f(uHasBackgroundHandle, if (hasBackground && backgroundTextureId > 0) 1f else 0f)

    GLES20.glUniform1i(uSamplerHandle, 0)
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previousTexId)
    // The capture FBO defaults to nearest-neighbor sampling. That turns thin rounded
    // webpage/UI borders into stair-stepped segments whenever the screen layer is resized.
    // Linear filtering preserves smooth curves and diagonal edges during composition.
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glUniform1i(uBackgroundSamplerHandle, 1)
    GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (backgroundTextureId > 0) backgroundTextureId else 0)
  }

  override fun disableResources() {
    GlUtil.disableResources(aTextureHandle, aPositionHandle)
  }

  fun updateLayouts(newPortraitLayout: ScreenLayout, newLandscapeLayout: ScreenLayout) {
    portraitLayout = newPortraitLayout.normalized()
    val landscapeBaseSize = CaptureOrientation.contentSize(
      canvasAspect,
      ScreenPreset.LANDSCAPE,
      landscapeFitMode,
      captureLandscapeAspect
    )
    landscapeLayout = newLandscapeLayout.normalized(
      landscapeBaseSize.first,
      landscapeBaseSize.second
    )
  }

  fun updateStyle(
    newFramePreset: ScreenPreset,
    newPortraitFitMode: ScreenFitMode,
    newLandscapeFitMode: ScreenFitMode,
    newTheme: CanvasTheme
  ) {
    framePreset = newFramePreset
    portraitFitMode = newPortraitFitMode
    landscapeFitMode = newLandscapeFitMode
    backgroundColor = colorComponents(newTheme.backgroundColor)
  }

  private fun setColorUniform(handle: Int, color: FloatArray) {
    GLES20.glUniform4f(handle, color[0], color[1], color[2], color[3])
  }

  private fun colorComponents(colorString: String): FloatArray {
    val color = Color.parseColor(colorString)
    return floatArrayOf(
      Color.red(color) / 255f,
      Color.green(color) / 255f,
      Color.blue(color) / 255f,
      Color.alpha(color) / 255f
    )
  }

  fun setCanvasSize(width: Int, height: Int) {
    if (width > 0 && height > 0) {
      canvasWidth = width
      canvasHeight = height
      canvasAspect = width.toFloat() / height.toFloat()
    }
  }

  private fun snapToPixel(value: Float, pixels: Int): Float {
    return round(value * pixels.coerceAtLeast(1)) / pixels.coerceAtLeast(1)
  }

  fun updateCaptureRotation(rotationDegrees: Int) {
    // Device pose describes the source crop only. The user-selected frame stays fixed.
    landscapeSource = CaptureOrientation.isLandscapeScene(rotationDegrees)
  }

  fun updateCaptureLandscapeAspect(aspect: Float) {
    if (aspect > 0f) captureLandscapeAspect = aspect
  }

  fun setBackgroundImage(bitmap: Bitmap?) {
    hasBackground = bitmap != null
    if (bitmap != null) {
      backgroundImage.load(bitmap)
      shouldLoadBackground = true
    }
  }

  private fun releaseBackgroundTexture() {
    if (backgroundTextureId > 0) GLES20.glDeleteTextures(1, intArrayOf(backgroundTextureId), 0)
    backgroundTextureId = -1
  }

  override fun release() {
    if (program > 0) GLES20.glDeleteProgram(program)
    releaseBackgroundTexture()
    backgroundImage.recycle()
    program = -1
  }

  companion object {
    private const val VERTEX_SHADER = """
      attribute vec4 aPosition;
      attribute vec4 aTextureCoord;
      uniform mat4 uMVPMatrix;
      uniform mat4 uSTMatrix;
      varying vec2 vTextureCoord;

      void main() {
        gl_Position = uMVPMatrix * aPosition;
        vTextureCoord = (uSTMatrix * aTextureCoord).xy;
      }
    """

    private const val FRAGMENT_SHADER = """
      precision highp float;
      uniform sampler2D uSampler;
      uniform vec2 uScreenPosition;
      uniform vec2 uScreenSize;
      uniform float uScreenRotation;
      uniform float uCanvasAspect;
      uniform vec2 uSourceCropPosition;
      uniform vec2 uSourceCropSize;
      uniform vec2 uSourceTexelSize;
      uniform vec2 uContentSize;
      uniform vec4 uBackgroundColor;
      uniform sampler2D uBackgroundSampler;
      uniform float uHasBackground;
      varying vec2 vTextureCoord;

      void main() {
        // Editor coordinates start at top-left; GL texture coordinates start at bottom-left.
        vec2 canvasCoord = vec2(vTextureCoord.x, 1.0 - vTextureCoord.y);
        vec4 canvasBackground = uBackgroundColor;
        if (uHasBackground > 0.5) {
          // Android Canvas bitmaps are uploaded top-row first. canvasCoord is already
          // top-left based, so flipping Y again would make every background layer upside down.
          vec4 backgroundLayer = texture2D(uBackgroundSampler, canvasCoord);
          canvasBackground = vec4(
            // Android bitmap textures use premultiplied alpha; multiplying RGB by alpha
            // again creates a dark fringe around transparent image edges.
            backgroundLayer.rgb + uBackgroundColor.rgb * (1.0 - backgroundLayer.a),
            1.0
          );
        }
        vec2 screenCenter = uScreenPosition + uScreenSize * 0.5;
        // Rotate in output-pixel space so non-square canvases match the editor preview.
        vec2 centered = vec2(
          (canvasCoord.x - screenCenter.x) * uCanvasAspect,
          canvasCoord.y - screenCenter.y
        );
        float radiansValue = radians(-uScreenRotation);
        float cosine = cos(radiansValue);
        float sine = sin(radiansValue);
        vec2 rotated = vec2(
          cosine * centered.x - sine * centered.y,
          sine * centered.x + cosine * centered.y
        );
        vec2 unrotated = vec2(
          rotated.x / uCanvasAspect + screenCenter.x,
          rotated.y + screenCenter.y
        );
        vec2 localCoord = (unrotated - uScreenPosition) / uScreenSize;
        // A binary inside/outside test aliases the screen frame whenever it is rotated.
        // Estimate one output pixel in local frame coordinates and use it as a coverage
        // ramp. Pixel-snapped, unrotated edges remain exact; angled edges get a clean
        // one-pixel antialias instead of visible stair steps.
        float rotatedPixelFootprint = abs(cosine) + abs(sine);
        vec2 frameAaWidth = max(
          0.5 * uSourceTexelSize * rotatedPixelFootprint / uScreenSize,
          vec2(0.000001)
        );
        vec2 signedFrameDistance = min(localCoord, vec2(1.0) - localCoord);
        float screenCoverage = min(
          smoothstep(-frameAaWidth.x, frameAaWidth.x, signedFrameDistance.x),
          smoothstep(-frameAaWidth.y, frameAaWidth.y, signedFrameDistance.y)
        );
        if (screenCoverage <= 0.0) {
          gl_FragColor = canvasBackground;
          return;
        }
        vec2 frameCoord = clamp(localCoord, 0.0, 1.0);

        // Frame orientation is user-selected. Source orientation is independent. Aspect-fit
        // keeps the entire source visible inside a differently oriented frame.
        vec2 contentPosition = (vec2(1.0) - uContentSize) * 0.5;
        bool insideContent = frameCoord.x >= contentPosition.x &&
          frameCoord.x < contentPosition.x + uContentSize.x &&
          frameCoord.y >= contentPosition.y &&
          frameCoord.y < contentPosition.y + uContentSize.y;
        vec4 frameColor;
        if (!insideContent) {
          // Letterbox/pillarbox space belongs to the screen frame, so it is always black.
          frameColor = vec4(0.0, 0.0, 0.0, 1.0);
        } else {
          vec2 contentCoord = (frameCoord - contentPosition) / uContentSize;

          // A landscape source is letterboxed into the fixed portrait MediaProjection texture.
          // Sample only its active band without cropping portrait sources.
          // Sample at least one texel inside the active crop. This prevents a neighboring
          // letterbox/system-bar row from bleeding into the frame edge during scaling.
          vec2 sourceInset = min(uSourceTexelSize * 1.25, uSourceCropSize * 0.01);
          vec2 sourceMin = uSourceCropPosition + sourceInset;
          vec2 sourceMax = uSourceCropPosition + uSourceCropSize - sourceInset;
          vec2 sourceTopLeft = mix(sourceMin, sourceMax, clamp(contentCoord, 0.0, 1.0));
          vec2 sourceCoord = vec2(sourceTopLeft.x, 1.0 - sourceTopLeft.y);
          frameColor = texture2D(uSampler, sourceCoord);
        }
        gl_FragColor = mix(canvasBackground, frameColor, screenCoverage);
      }
    """
  }
}
