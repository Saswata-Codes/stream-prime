package com.stream.prime.overlay

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2

enum class StudioSelectionType { NONE, SCREEN, LAYER }
data class StudioSelection(val type: StudioSelectionType, val layerId: String? = null)
enum class SceneFitResult { FITTED, LOCKED, NOTHING_VISIBLE }

/** Direct-manipulation editor canvas used by the new overlay studio. */
class OverlayStudioView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

  private var config = OverlayConfig(enabled = true)
  private var preset = ScreenPreset.PORTRAIT
  private var selection = StudioSelection(StudioSelectionType.SCREEN)
  private var streamWidth = 720
  private var streamHeight = 1280
  private var captureLandscapeAspect = CaptureDisplayAspect.landscapeAspect(context)
  private val cache = mutableMapOf<String, Bitmap?>()
  private var onChanged: ((OverlayConfig, Boolean) -> Unit)? = null
  private var onSelectionChanged: ((StudioSelection) -> Unit)? = null
  private var lastX = 0f
  private var lastY = 0f
  private var lastAngle: Float? = null
  private var guideX = false
  private var guideY = false
  private var gestureChanged = false

  private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val screenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 230, 118) }
  private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 230, 118); style = Paint.Style.STROKE; strokeWidth = density(2f) }
  private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF; strokeWidth = density(1f) }
  private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = density(13f); typeface = Typeface.DEFAULT_BOLD }

  private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
    override fun onScale(detector: ScaleGestureDetector): Boolean {
      if (isSelectionLocked()) return false
      when (selection.type) {
        StudioSelectionType.SCREEN -> {
          val layout = activeScreenLayout()
          setActiveScreenLayout(layout.copy(scalePct = (layout.scalePct * detector.scaleFactor).coerceIn(10f, 300f)))
        }
        StudioSelectionType.LAYER -> updateSelectedLayer { layer ->
          layer.copy(
            scaleXPct = (layer.scaleXPct * detector.scaleFactor).coerceIn(5f, 300f),
            scaleYPct = (layer.scaleYPct * detector.scaleFactor).coerceIn(5f, 300f)
          )
        }
        else -> return false
      }
      gestureChanged = true
      emitChanged(false)
      return true
    }
  })

  fun setConfig(value: OverlayConfig) { config = value; invalidate() }
  fun getConfig(): OverlayConfig = config
  fun setCanvasSize(width: Int, height: Int) { if (width > 0 && height > 0) { streamWidth = width; streamHeight = height; invalidate() } }
  fun setOnConfigChanged(listener: ((OverlayConfig, Boolean) -> Unit)?) { onChanged = listener }
  fun setOnSelectionChanged(listener: ((StudioSelection) -> Unit)?) { onSelectionChanged = listener }
  fun getSelection(): StudioSelection = selection
  fun isSelectionLocked(): Boolean = when (selection.type) {
    StudioSelectionType.SCREEN -> activeScreenLocked()
    StudioSelectionType.LAYER -> selectedLayer()?.locked == true
    else -> false
  }

  fun setScreenPreset(value: ScreenPreset, persistChoice: Boolean = true) {
    preset = value
    if (persistChoice && config.screenPreset != value) {
      config = config.copy(screenPreset = value)
      emitChanged(true)
    }
    selectScreen()
    invalidate()
  }

  fun selectScreen() { setSelection(StudioSelection(StudioSelectionType.SCREEN)) }
  fun selectLayer(id: String) { setSelection(StudioSelection(StudioSelectionType.LAYER, id)) }

  fun rotateSelected(degrees: Float = 90f) {
    if (isSelectionLocked()) return
    if (selection.type == StudioSelectionType.SCREEN) {
      val layout = activeScreenLayout()
      setActiveScreenLayout(layout.copy(rotationDegrees = normalizeRotation(layout.rotationDegrees + degrees)))
    } else updateSelectedLayer { it.copy(rotationDegrees = normalizeRotation(it.rotationDegrees + degrees)) }
    emitChanged(true)
  }

  fun resetSelected() {
    if (isSelectionLocked()) return
    if (selection.type == StudioSelectionType.SCREEN) {
      setActiveScreenLayout(
        CaptureOrientation.defaultLayout(
          canvasAspect(),
          preset,
          activeFitMode(),
          captureLandscapeAspect
        )
      )
    } else updateSelectedLayer { it.copy(positionXPct = 10f, positionYPct = 10f, scaleXPct = 30f, scaleYPct = 30f, rotationDegrees = 0f, alpha = 1f) }
    emitChanged(true)
  }

  fun deleteSelected() {
    if (isSelectionLocked()) return
    val id = selection.layerId ?: return
    val removed = selectedLayer() ?: return
    val screenPosition = if (removed.zIndex < config.screenLayerPosition) {
      (config.screenLayerPosition - 1).coerceAtLeast(0)
    } else config.screenLayerPosition
    config = config.copy(
      layers = config.layers.filterNot { it.id == id }.sortedBy { it.zIndex }.mapIndexed { index, layer -> layer.copy(zIndex = index) },
      screenLayerPosition = screenPosition
    )
    selectScreen()
    emitChanged(true)
  }

  fun moveSelectedLayer(delta: Int) {
    if (isSelectionLocked()) return
    val id = if (selection.type == StudioSelectionType.SCREEN) OverlayLayerOrdering.SCREEN_ID else selection.layerId ?: return
    val ids = OverlayLayerOrdering.topFirstWithScreen(config.layers, config.screenLayerPosition).toMutableList()
    val from = ids.indexOf(id)
    val to = (from - delta).coerceIn(0, ids.lastIndex)
    if (from < 0 || from == to || stackItemLocked(ids[to])) return
    ids.removeAt(from); ids.add(to, id)
    reorderStackTopFirst(ids)
  }

  fun reorderStackTopFirst(ids: List<String>) {
    val currentIds = OverlayLayerOrdering.topFirstWithScreen(config.layers, config.screenLayerPosition)
    val lockedPositionsChanged = currentIds.withIndex().any { (index, id) ->
      stackItemLocked(id) && ids.indexOf(id) != index
    }
    if (lockedPositionsChanged) return
    val reordered = OverlayLayerOrdering.fromTopFirstWithScreen(config.layers, ids) ?: return
    config = config.copy(layers = reordered.layers, screenLayerPosition = reordered.screenPosition)
    emitChanged(true)
  }

  fun toggleSelectedLock() {
    config = when (selection.type) {
      StudioSelectionType.SCREEN -> if (preset == ScreenPreset.PORTRAIT) {
        config.copy(portraitScreenLocked = !config.portraitScreenLocked)
      } else {
        config.copy(landscapeScreenLocked = !config.landscapeScreenLocked)
      }
      StudioSelectionType.LAYER -> {
        val id = selection.layerId ?: return
        config.copy(layers = config.layers.map { if (it.id == id) it.copy(locked = !it.locked) else it })
      }
      else -> return
    }
    emitChanged(true)
    onSelectionChanged?.invoke(selection)
  }

  fun toggleFitMode() {
    if (isSelectionLocked()) return
    val mode = if (activeFitMode() == ScreenFitMode.ASPECT) ScreenFitMode.STRETCH else ScreenFitMode.ASPECT
    config = if (preset == ScreenPreset.PORTRAIT) config.copy(portraitScreenFitMode = mode) else config.copy(landscapeScreenFitMode = mode)
    emitChanged(true)
  }

  /**
   * Fits the complete visible composition into the canvas as one group. Relative positions,
   * rotations and aspect ratios are preserved; only uniform scale and translation are applied.
   */
  fun fitSceneToCanvas(safeMarginPct: Float = 3f): SceneFitResult {
    val visibleLayers = config.layers.filter {
      it.enabled && (it.type == OverlayLayerType.TEXT || it.imageUri.isNotEmpty())
    }
    if (activeScreenLocked() || visibleLayers.any { it.locked }) return SceneFitResult.LOCKED

    val screenLayout = activeScreenLayout()
    val screenBase = CaptureOrientation.contentSize(
      canvasAspect(),
      preset,
      activeFitMode(),
      captureLandscapeAspect
    )
    val screenBounds = SceneBounds(
      screenLayout.positionXPct,
      screenLayout.positionYPct,
      screenLayout.positionXPct + screenLayout.scalePct * screenBase.first,
      screenLayout.positionYPct + screenLayout.scalePct * screenBase.second
    )
    val allBounds = mutableListOf(
      SceneLayoutOptimizer.rotatedBounds(screenBounds, screenLayout.rotationDegrees)
    )
    visibleLayers.forEach { layer ->
      val bounds = layerVisibleBounds(layer)
      allBounds += SceneLayoutOptimizer.rotatedBounds(bounds, layer.rotationDegrees)
    }
    val sceneBounds = SceneLayoutOptimizer.union(allBounds) ?: return SceneFitResult.NOTHING_VISIBLE

    var minAllowedScale = ScreenLayout.MIN_SCALE_PCT / screenLayout.scalePct.coerceAtLeast(0.0001f)
    var maxAllowedScale = ScreenLayout.MAX_SCALE_PCT / screenLayout.scalePct.coerceAtLeast(0.0001f)
    visibleLayers.forEach { layer ->
      minAllowedScale = maxOf(
        minAllowedScale,
        5f / layer.scaleXPct.coerceAtLeast(0.0001f),
        5f / layer.scaleYPct.coerceAtLeast(0.0001f)
      )
      maxAllowedScale = minOf(
        maxAllowedScale,
        300f / layer.scaleXPct.coerceAtLeast(0.0001f),
        300f / layer.scaleYPct.coerceAtLeast(0.0001f)
      )
    }
    val transform = SceneLayoutOptimizer.fit(
      sceneBounds,
      safeMarginPct,
      minAllowedScale,
      maxAllowedScale
    ) ?: return SceneFitResult.NOTHING_VISIBLE

    val fittedScreen = screenLayout.copy(
      positionXPct = transform.mapX(screenLayout.positionXPct),
      positionYPct = transform.mapY(screenLayout.positionYPct),
      scalePct = screenLayout.scalePct * transform.scale
    ).normalized(screenBase.first, screenBase.second)
    setActiveScreenLayout(fittedScreen)
    val visibleIds = visibleLayers.mapTo(mutableSetOf()) { it.id }
    config = config.copy(layers = config.layers.map { layer ->
      if (layer.id !in visibleIds) layer else layer.copy(
        positionXPct = transform.mapX(layer.positionXPct),
        positionYPct = transform.mapY(layer.positionYPct),
        scaleXPct = layer.scaleXPct * transform.scale,
        scaleYPct = layer.scaleYPct * transform.scale
      )
    })
    emitChanged(true)
    return SceneFitResult.FITTED
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val viewport = viewport()
    backgroundPaint.color = Color.parseColor(config.canvasTheme.backgroundColor)
    canvas.drawColor(backgroundPaint.color)
    val save = canvas.save()
    canvas.clipRect(viewport)
    canvas.translate(viewport.left, viewport.top)
    val (belowScreen, aboveScreen) = OverlayLayerOrdering.splitAtScreen(config.layers, config.screenLayerPosition)
    LayerCanvasRenderer.renderLayersOnCanvas(canvas, belowScreen, viewport.width(), viewport.height(), context, cache, false)
    drawScreen(canvas, viewport.width(), viewport.height())
    LayerCanvasRenderer.renderLayersOnCanvas(canvas, aboveScreen, viewport.width(), viewport.height(), context, cache, false)
    if (config.showGrid) drawGrid(canvas, viewport.width(), viewport.height())
    drawSelection(canvas, viewport.width(), viewport.height())
    if (guideX) canvas.drawLine(viewport.width() / 2f, 0f, viewport.width() / 2f, viewport.height(), selectedPaint)
    if (guideY) canvas.drawLine(0f, viewport.height() / 2f, viewport.width(), viewport.height() / 2f, selectedPaint)
    canvas.restoreToCount(save)
  }

  private fun drawScreen(canvas: Canvas, width: Float, height: Float) {
    val rect = screenRect(width, height)
    val layout = activeScreenLayout()
    val save = canvas.save(); canvas.rotate(layout.rotationDegrees, rect.centerX(), rect.centerY())
    canvas.drawRect(rect, screenPaint)
    val centerY = rect.centerY() - (labelPaint.ascent() + labelPaint.descent()) / 2f
    val orientation = if (preset == ScreenPreset.PORTRAIT) "PORTRAIT" else "LANDSCAPE"
    val fit = if (activeFitMode() == ScreenFitMode.ASPECT) "ASPECT RATIO" else "STRETCH"
    labelPaint.color = Color.BLACK
    canvas.drawText("SCREEN CAPTURE", rect.centerX(), centerY - density(18f), labelPaint)
    canvas.drawText(orientation, rect.centerX(), centerY, labelPaint)
    canvas.drawText(fit, rect.centerX(), centerY + density(18f), labelPaint)
    labelPaint.color = Color.WHITE
    canvas.restoreToCount(save)
  }

  private fun drawGrid(canvas: Canvas, width: Float, height: Float) {
    for (i in 1 until 4) {
      canvas.drawLine(width * i / 4f, 0f, width * i / 4f, height, gridPaint)
      canvas.drawLine(0f, height * i / 4f, width, height * i / 4f, gridPaint)
    }
  }

  private fun drawSelection(canvas: Canvas, width: Float, height: Float) {
    val rect = if (selection.type == StudioSelectionType.SCREEN) screenRect(width, height) else selectedLayerRect(width, height) ?: return
    val rotation = if (selection.type == StudioSelectionType.SCREEN) activeScreenLayout().rotationDegrees else selectedLayer()?.rotationDegrees ?: 0f
    val locked = isSelectionLocked()
    selectedPaint.color = if (locked) Color.rgb(255, 193, 7) else Color.rgb(0, 230, 118)
    val save = canvas.save(); canvas.rotate(rotation, rect.centerX(), rect.centerY())
    canvas.drawRect(rect, selectedPaint)
    val r = density(5f)
    canvas.drawCircle(rect.left, rect.top, r, selectedPaint); canvas.drawCircle(rect.right, rect.bottom, r, selectedPaint)
    if (locked) {
      labelPaint.color = Color.rgb(255, 193, 7)
      canvas.drawText("LOCKED", rect.centerX(), rect.top + density(18f), labelPaint)
      labelPaint.color = Color.WHITE
    }
    canvas.restoreToCount(save)
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    scaleDetector.onTouchEvent(event)
    val vp = viewport()
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        lastX = event.x; lastY = event.y; lastAngle = null; guideX = false; guideY = false; gestureChanged = false
        selectAt((event.x - vp.left) / vp.width() * 100f, (event.y - vp.top) / vp.height() * 100f)
        parent?.requestDisallowInterceptTouchEvent(true)
      }
      MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount >= 2) lastAngle = angle(event)
      MotionEvent.ACTION_MOVE -> {
        if (isSelectionLocked()) {
          lastX = event.x; lastY = event.y
        } else if (event.pointerCount >= 2) {
          val current = angle(event)
          lastAngle?.let { rotateSelected(shortAngle(current - it)); gestureChanged = true }
          lastAngle = current
        } else if (!scaleDetector.isInProgress) {
          val dx = (event.x - lastX) / vp.width() * 100f
          val dy = (event.y - lastY) / vp.height() * 100f
          moveSelected(dx, dy)
          gestureChanged = true
          emitChanged(false)
        }
        lastX = event.x; lastY = event.y
      }
      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        guideX = false; guideY = false; lastAngle = null
        parent?.requestDisallowInterceptTouchEvent(false)
        if (gestureChanged) emitChanged(true) else invalidate()
        performClick()
      }
      MotionEvent.ACTION_POINTER_UP -> lastAngle = null
    }
    return true
  }

  private fun selectAt(xPct: Float, yPct: Float) {
    val (below, above) = OverlayLayerOrdering.splitAtScreen(config.layers, config.screenLayerPosition)
    val hitAbove = above.filter { it.enabled }.sortedByDescending { it.zIndex }.firstOrNull {
      xPct in it.positionXPct..(it.positionXPct + it.scaleXPct) && yPct in it.positionYPct..(it.positionYPct + it.scaleYPct)
    }
    if (hitAbove != null) { selectLayer(hitAbove.id); return }
    val screen = screenRect(100f, 100f)
    if (screen.contains(xPct, yPct)) { selectScreen(); return }
    val hitBelow = below.filter { it.enabled }.sortedByDescending { it.zIndex }.firstOrNull {
      xPct in it.positionXPct..(it.positionXPct + it.scaleXPct) && yPct in it.positionYPct..(it.positionYPct + it.scaleYPct)
    }
    if (hitBelow != null) selectLayer(hitBelow.id) else selectScreen()
  }

  private fun moveSelected(dx: Float, dy: Float) {
    if (isSelectionLocked()) return
    if (selection.type == StudioSelectionType.SCREEN) {
      var layout = activeScreenLayout().copy(positionXPct = activeScreenLayout().positionXPct + dx, positionYPct = activeScreenLayout().positionYPct + dy)
      val base = CaptureOrientation.contentSize(
        canvasAspect(),
        preset,
        activeFitMode(),
        captureLandscapeAspect
      )
      if (config.snapToCenter) {
        val cx = layout.positionXPct + layout.scalePct * base.first / 2f
        val cy = layout.positionYPct + layout.scalePct * base.second / 2f
        guideX = abs(cx - 50f) < 1.5f; guideY = abs(cy - 50f) < 1.5f
        if (guideX) layout = layout.copy(positionXPct = 50f - layout.scalePct * base.first / 2f)
        if (guideY) layout = layout.copy(positionYPct = 50f - layout.scalePct * base.second / 2f)
      }
      setActiveScreenLayout(layout.normalized(base.first, base.second))
    } else updateSelectedLayer { layer ->
      var x = layer.positionXPct + dx; var y = layer.positionYPct + dy
      if (config.snapToCenter) {
        guideX = abs(x + layer.scaleXPct / 2f - 50f) < 1.5f; guideY = abs(y + layer.scaleYPct / 2f - 50f) < 1.5f
        if (guideX) x = 50f - layer.scaleXPct / 2f
        if (guideY) y = 50f - layer.scaleYPct / 2f
      }
      layer.copy(positionXPct = x, positionYPct = y)
    }
  }

  private fun emitChanged(finished: Boolean) { invalidate(); onChanged?.invoke(config, finished) }
  private fun setSelection(value: StudioSelection) { selection = value; invalidate(); onSelectionChanged?.invoke(value) }
  private fun selectedLayer(): OverlayLayer? = config.layers.firstOrNull { it.id == selection.layerId }
  private fun stackItemLocked(id: String): Boolean = if (id == OverlayLayerOrdering.SCREEN_ID) activeScreenLocked() else config.layers.firstOrNull { it.id == id }?.locked == true
  private fun updateSelectedLayer(block: (OverlayLayer) -> OverlayLayer) { val id = selection.layerId ?: return; config = config.copy(layers = config.layers.map { if (it.id == id) block(it) else it }) }
  private fun activeScreenLayout() = if (preset == ScreenPreset.PORTRAIT) config.screenLayout else config.landscapeScreenLayout
  private fun activeScreenLocked() = if (preset == ScreenPreset.PORTRAIT) config.portraitScreenLocked else config.landscapeScreenLocked
  private fun setActiveScreenLayout(layout: ScreenLayout) { config = if (preset == ScreenPreset.PORTRAIT) config.copy(screenLayout = layout) else config.copy(landscapeScreenLayout = layout) }
  private fun activeFitMode() = if (preset == ScreenPreset.PORTRAIT) config.portraitScreenFitMode else config.landscapeScreenFitMode
  private fun screenRect(w: Float, h: Float): RectF { val l = activeScreenLayout(); val base = CaptureOrientation.contentSize(canvasAspect(), preset, activeFitMode(), captureLandscapeAspect); val x=l.positionXPct/100f*w; val y=l.positionYPct/100f*h; return RectF(x,y,x+l.scalePct/100f*base.first*w,y+l.scalePct/100f*base.second*h) }
  private fun selectedLayerRect(w: Float,h: Float): RectF? { val l=selectedLayer()?:return null; return RectF(l.positionXPct/100f*w,l.positionYPct/100f*h,(l.positionXPct+l.scaleXPct)/100f*w,(l.positionYPct+l.scaleYPct)/100f*h) }
  private fun layerVisibleBounds(layer: OverlayLayer): SceneBounds {
    if (layer.type == OverlayLayerType.TEXT) {
      return SceneBounds(
        layer.positionXPct,
        layer.positionYPct,
        layer.positionXPct + layer.scaleXPct,
        layer.positionYPct + layer.scaleYPct
      )
    }
    val cacheKey = "${layer.id}::${layer.imageUri}"
    if (!cache.containsKey(cacheKey)) cache[cacheKey] = OverlayManager.decodeBitmap(context, layer.imageUri)
    val bitmap = cache[cacheKey]
    if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) {
      return SceneBounds(
        layer.positionXPct,
        layer.positionYPct,
        layer.positionXPct + layer.scaleXPct,
        layer.positionYPct + layer.scaleYPct
      )
    }
    val scale = minOf(layer.scaleXPct / bitmap.width, layer.scaleYPct / bitmap.height)
    val visibleWidth = bitmap.width * scale
    val visibleHeight = bitmap.height * scale
    return SceneBounds(
      layer.positionXPct,
      layer.positionYPct,
      layer.positionXPct + visibleWidth,
      layer.positionYPct + visibleHeight
    )
  }
  private fun viewport(): RectF { val vr=width.toFloat()/height.coerceAtLeast(1); val ar=canvasAspect(); return if(ar>vr){val h=width/ar;RectF(0f,(height-h)/2f,width.toFloat(),(height+h)/2f)}else{val w=height*ar;RectF((width-w)/2f,0f,(width+w)/2f,height.toFloat())} }
  private fun canvasAspect() = streamWidth.toFloat()/streamHeight.coerceAtLeast(1)
  private fun angle(e:MotionEvent)=Math.toDegrees(atan2((e.getY(1)-e.getY(0)).toDouble(),(e.getX(1)-e.getX(0)).toDouble())).toFloat()
  private fun shortAngle(value:Float):Float { var d=value; while(d>180)d-=360; while(d< -180)d+=360; return d }
  private fun normalizeRotation(v:Float)=((v+180f)%360f+360f)%360f-180f
  private fun density(v:Float)=v*resources.displayMetrics.density
  override fun performClick():Boolean { super.performClick(); return true }
  override fun onDetachedFromWindow(){super.onDetachedFromWindow();LayerCanvasRenderer.clearBitmapCache(cache)}
}
