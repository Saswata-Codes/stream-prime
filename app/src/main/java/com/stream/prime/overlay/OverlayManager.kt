package com.stream.prime.overlay

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.FileDescriptor

enum class OverlayLayerType {
  IMAGE,
  TEXT
}

data class OverlayLayer(
  val id: String = "",
  val name: String = "Layer",
  val enabled: Boolean = true,
  val locked: Boolean = false,
  // Nullable only for Gson compatibility with projects saved before layer types existed.
  val type: OverlayLayerType? = OverlayLayerType.IMAGE,
  val imageUri: String = "",
  val text: String = "",
  val textColor: String = "#FFFFFF",
  val backgroundColor: String = "#00000000",
  val positionXPct: Float = 0f,
  val positionYPct: Float = 0f,
  val scaleXPct: Float = 30f,
  val scaleYPct: Float = 30f,
  val alpha: Float = 1f,
  val rotationDegrees: Float = 0f,
  val zIndex: Int = 0 // Layer ordering, higher values appear on top
)

data class OverlayProject(
  val id: String = "",
  val name: String = "Overlay",
  val config: OverlayConfig = OverlayConfig(),
  val updatedAt: Long = System.currentTimeMillis()
)

object OverlayLayerOrdering {
  const val SCREEN_ID = "__captured_screen__"

  data class Stack(val layers: List<OverlayLayer>, val screenPosition: Int)

  /** Returns bottom-first storage with normalized z-indices from a top-first UI order. */
  fun fromTopFirst(layers: List<OverlayLayer>, layerIds: List<String>): List<OverlayLayer> {
    if (layerIds.size != layers.size || layerIds.toSet().size != layerIds.size) return layers
    val byId = layers.associateBy { it.id }
    if (layerIds.any { it !in byId }) return layers
    return layerIds.reversed().mapIndexed { index, id -> byId.getValue(id).copy(zIndex = index) }
  }

  fun topFirstWithScreen(layers: List<OverlayLayer>, screenPosition: Int): List<String> {
    val topFirst = layers.sortedByDescending { it.zIndex }.map { it.id }.toMutableList()
    val screenTopIndex = layers.size - screenPosition.coerceIn(0, layers.size)
    topFirst.add(screenTopIndex, SCREEN_ID)
    return topFirst
  }

  fun fromTopFirstWithScreen(layers: List<OverlayLayer>, ids: List<String>): Stack? {
    if (ids.size != layers.size + 1 || ids.count { it == SCREEN_ID } != 1 || ids.toSet().size != ids.size) return null
    val byId = layers.associateBy { it.id }
    val layerIds = ids.filterNot { it == SCREEN_ID }
    if (layerIds.any { it !in byId }) return null
    val bottomFirst = ids.reversed()
    val screenPosition = bottomFirst.indexOf(SCREEN_ID)
    val reordered = bottomFirst.filterNot { it == SCREEN_ID }
      .mapIndexed { index, id -> byId.getValue(id).copy(zIndex = index) }
    return Stack(reordered, screenPosition)
  }

  fun splitAtScreen(layers: List<OverlayLayer>, screenPosition: Int): Pair<List<OverlayLayer>, List<OverlayLayer>> {
    val sorted = layers.sortedBy { it.zIndex }
    val slot = screenPosition.coerceIn(0, sorted.size)
    return sorted.take(slot) to sorted.drop(slot)
  }
}

/**
 * Placement of the captured screen inside the final stream canvas.
 * Coordinates use the same top-left, percentage-based system as overlay layers.
 * A scale below 100 creates picture-in-picture space; a scale above 100 crops/zooms.
 */
data class ScreenLayout(
  val positionXPct: Float = 0f,
  val positionYPct: Float = 0f,
  val scalePct: Float = 100f,
  val rotationDegrees: Float = 0f
) {
  fun normalized(widthFactor: Float = 1f, heightFactor: Float = 1f): ScreenLayout {
    val safeScale = scalePct.coerceIn(MIN_SCALE_PCT, MAX_SCALE_PCT)
    // Allow free placement while keeping at least a small grab area visible.
    val minPositionX = MIN_VISIBLE_PCT - safeScale * widthFactor.coerceAtLeast(0f)
    val minPositionY = MIN_VISIBLE_PCT - safeScale * heightFactor.coerceAtLeast(0f)
    val maxPosition = 100f - MIN_VISIBLE_PCT
    val safeRotation = ((rotationDegrees + 180f) % 360f + 360f) % 360f - 180f
    return copy(
      positionXPct = positionXPct.coerceIn(minPositionX, maxPosition),
      positionYPct = positionYPct.coerceIn(minPositionY, maxPosition),
      scalePct = safeScale,
      rotationDegrees = safeRotation
    )
  }

  fun isDefault(): Boolean {
    val layout = normalized()
    return kotlin.math.abs(layout.positionXPct) < 0.001f &&
      kotlin.math.abs(layout.positionYPct) < 0.001f &&
      kotlin.math.abs(layout.scalePct - 100f) < 0.001f &&
      kotlin.math.abs(layout.rotationDegrees) < 0.001f
  }

  companion object {
    const val MIN_SCALE_PCT = 10f
    const val MAX_SCALE_PCT = 300f
    const val MIN_VISIBLE_PCT = 10f
  }
}

enum class ScreenPreset {
  PORTRAIT,
  LANDSCAPE
}

enum class ScreenFitMode {
  ASPECT,
  STRETCH
}

enum class CanvasTheme(
  val displayName: String,
  val backgroundColor: String,
  val screenBorderColor: String
) {
  MIDNIGHT("Midnight", "#1A1A1A", "#FFFFFF"),
  PURE_BLACK("Pure Black", "#000000", "#5F6368"),
  NEON("Neon Studio", "#06110D", "#00E676"),
  VIOLET("Violet Stage", "#120A1F", "#B388FF")
}

data class OverlayConfig(
  val enabled: Boolean = false,
  val layers: List<OverlayLayer> = emptyList(),
  /** Frame shape chosen by the user. Source orientation is detected independently. */
  val screenPreset: ScreenPreset = ScreenPreset.PORTRAIT,
  /** Placement used when the selected frame is portrait. Kept under the original name for migration. */
  val screenLayout: ScreenLayout = ScreenLayout(),
  /** Placement used when the selected frame is landscape. */
  val landscapeScreenLayout: ScreenLayout = ScreenLayout(),
  val portraitScreenFitMode: ScreenFitMode = ScreenFitMode.ASPECT,
  val landscapeScreenFitMode: ScreenFitMode = ScreenFitMode.ASPECT,
  val portraitScreenLocked: Boolean = false,
  val landscapeScreenLocked: Boolean = false,
  /** Number of visual layers below Screen in the unified bottom-first stack. */
  val screenLayerPosition: Int = 0,
  val canvasTheme: CanvasTheme = CanvasTheme.MIDNIGHT,
  val showGrid: Boolean = true,
  val snapToCenter: Boolean = true
)

// Legacy single overlay config for backward compatibility
data class LegacyOverlayConfig(
  val enabled: Boolean = false,
  val imageUri: String = "",
  val positionXPct: Float = 0f,
  val positionYPct: Float = 0f,
  val scaleXPct: Float = 0f,
  val scaleYPct: Float = 0f,
  val alpha: Float = 1f
)

object OverlayManager {
  private const val PREFS = "overlay_prefs"
  private const val KEY_ENABLED = "enabled"
  private const val KEY_LAYERS = "layers_json"
  private const val KEY_SCREEN_PRESET = "screen_preset"
  private const val KEY_SCREEN_POSITION_X = "screen_position_x"
  private const val KEY_SCREEN_POSITION_Y = "screen_position_y"
  private const val KEY_SCREEN_SCALE = "screen_scale"
  private const val KEY_SCREEN_ROTATION = "screen_rotation"
  private const val KEY_LANDSCAPE_SCREEN_POSITION_X = "landscape_screen_position_x"
  private const val KEY_LANDSCAPE_SCREEN_POSITION_Y = "landscape_screen_position_y"
  private const val KEY_LANDSCAPE_SCREEN_SCALE = "landscape_screen_scale"
  private const val KEY_LANDSCAPE_SCREEN_ROTATION = "landscape_screen_rotation"
  private const val KEY_PORTRAIT_SCREEN_LOCKED = "portrait_screen_locked"
  private const val KEY_LANDSCAPE_SCREEN_LOCKED = "landscape_screen_locked"
  private const val KEY_SCREEN_LAYER_POSITION = "screen_layer_position"
  private const val KEY_PORTRAIT_SCREEN_FIT_MODE = "portrait_screen_fit_mode"
  private const val KEY_LANDSCAPE_SCREEN_FIT_MODE = "landscape_screen_fit_mode"
  private const val KEY_CANVAS_THEME = "canvas_theme"
  private const val KEY_SHOW_GRID = "show_grid"
  private const val KEY_SNAP_TO_CENTER = "snap_to_center"
  
  // Legacy keys for backward compatibility
  private const val KEY_URI = "image_uri"
  private const val KEY_POS_X = "pos_x"
  private const val KEY_POS_Y = "pos_y"
  private const val KEY_SCALE_X = "scale_x"
  private const val KEY_SCALE_Y = "scale_y"
  private const val KEY_ALPHA = "alpha"
  
  private val gson = Gson()

  fun save(context: Context, config: OverlayConfig) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val layersJson = gson.toJson(config.layers)
    val screenLayout = config.screenLayout.normalized()
    val landscapeBaseSize = CaptureOrientation.contentSize(
      streamCanvasAspect(context),
      ScreenPreset.LANDSCAPE,
      config.landscapeScreenFitMode,
      CaptureDisplayAspect.landscapeAspect(context)
    )
    val landscapeScreenLayout = config.landscapeScreenLayout.normalized(
      landscapeBaseSize.first,
      landscapeBaseSize.second
    )
    prefs.edit()
      .putBoolean(KEY_ENABLED, config.enabled)
      .putString(KEY_LAYERS, layersJson)
      .putString(KEY_SCREEN_PRESET, config.screenPreset.name)
      .putFloat(KEY_SCREEN_POSITION_X, screenLayout.positionXPct)
      .putFloat(KEY_SCREEN_POSITION_Y, screenLayout.positionYPct)
      .putFloat(KEY_SCREEN_SCALE, screenLayout.scalePct)
      .putFloat(KEY_SCREEN_ROTATION, screenLayout.rotationDegrees)
      .putFloat(KEY_LANDSCAPE_SCREEN_POSITION_X, landscapeScreenLayout.positionXPct)
      .putFloat(KEY_LANDSCAPE_SCREEN_POSITION_Y, landscapeScreenLayout.positionYPct)
      .putFloat(KEY_LANDSCAPE_SCREEN_SCALE, landscapeScreenLayout.scalePct)
      .putFloat(KEY_LANDSCAPE_SCREEN_ROTATION, landscapeScreenLayout.rotationDegrees)
      .putBoolean(KEY_PORTRAIT_SCREEN_LOCKED, config.portraitScreenLocked)
      .putBoolean(KEY_LANDSCAPE_SCREEN_LOCKED, config.landscapeScreenLocked)
      .putInt(KEY_SCREEN_LAYER_POSITION, config.screenLayerPosition.coerceIn(0, config.layers.size))
      .putString(KEY_PORTRAIT_SCREEN_FIT_MODE, config.portraitScreenFitMode.name)
      .putString(KEY_LANDSCAPE_SCREEN_FIT_MODE, config.landscapeScreenFitMode.name)
      .putString(KEY_CANVAS_THEME, config.canvasTheme.name)
      .putBoolean(KEY_SHOW_GRID, config.showGrid)
      .putBoolean(KEY_SNAP_TO_CENTER, config.snapToCenter)
      .apply()
  }

  fun load(context: Context): OverlayConfig {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    
    // Try loading new format first
    val layersJson = prefs.getString(KEY_LAYERS, null)
    if (!layersJson.isNullOrEmpty()) {
      try {
        val type = object : TypeToken<List<OverlayLayer>>() {}.type
        val layers: List<OverlayLayer> = gson.fromJson(layersJson, type) ?: emptyList()
        return OverlayConfig(
          enabled = prefs.getBoolean(KEY_ENABLED, false),
          layers = layers,
          screenPreset = loadScreenPreset(prefs),
          screenLayout = loadScreenLayout(prefs),
          landscapeScreenLayout = loadLandscapeScreenLayout(
            context,
            prefs,
            loadFitMode(prefs, KEY_LANDSCAPE_SCREEN_FIT_MODE)
          ),
          portraitScreenFitMode = loadFitMode(prefs, KEY_PORTRAIT_SCREEN_FIT_MODE),
          landscapeScreenFitMode = loadFitMode(prefs, KEY_LANDSCAPE_SCREEN_FIT_MODE),
          portraitScreenLocked = prefs.getBoolean(KEY_PORTRAIT_SCREEN_LOCKED, false),
          landscapeScreenLocked = prefs.getBoolean(KEY_LANDSCAPE_SCREEN_LOCKED, false),
          screenLayerPosition = prefs.getInt(KEY_SCREEN_LAYER_POSITION, 0).coerceIn(0, layers.size),
          canvasTheme = loadCanvasTheme(prefs),
          showGrid = prefs.getBoolean(KEY_SHOW_GRID, true),
          snapToCenter = prefs.getBoolean(KEY_SNAP_TO_CENTER, true)
        )
      } catch (e: Exception) {
        // Fall through to legacy format
      }
    }
    
    // Fallback to legacy format for backward compatibility
    val legacyUri = prefs.getString(KEY_URI, "") ?: ""
    if (legacyUri.isNotEmpty()) {
      val legacyLayer = OverlayLayer(
        id = "legacy_layer",
        name = "Legacy Layer",
        enabled = true,
        imageUri = legacyUri,
        positionXPct = prefs.getFloat(KEY_POS_X, 10f),
        positionYPct = prefs.getFloat(KEY_POS_Y, 10f),
        scaleXPct = prefs.getFloat(KEY_SCALE_X, 30f),
        scaleYPct = prefs.getFloat(KEY_SCALE_Y, 30f),
        alpha = prefs.getFloat(KEY_ALPHA, 1f),
        zIndex = 0
      )
      return OverlayConfig(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        layers = listOf(legacyLayer),
        screenPreset = loadScreenPreset(prefs),
        screenLayout = loadScreenLayout(prefs),
        landscapeScreenLayout = loadLandscapeScreenLayout(
          context,
          prefs,
          loadFitMode(prefs, KEY_LANDSCAPE_SCREEN_FIT_MODE)
        ),
        portraitScreenFitMode = loadFitMode(prefs, KEY_PORTRAIT_SCREEN_FIT_MODE),
        landscapeScreenFitMode = loadFitMode(prefs, KEY_LANDSCAPE_SCREEN_FIT_MODE),
        portraitScreenLocked = prefs.getBoolean(KEY_PORTRAIT_SCREEN_LOCKED, false),
        landscapeScreenLocked = prefs.getBoolean(KEY_LANDSCAPE_SCREEN_LOCKED, false),
        screenLayerPosition = prefs.getInt(KEY_SCREEN_LAYER_POSITION, 0).coerceIn(0, 1),
        canvasTheme = loadCanvasTheme(prefs),
        showGrid = prefs.getBoolean(KEY_SHOW_GRID, true),
        snapToCenter = prefs.getBoolean(KEY_SNAP_TO_CENTER, true)
      )
    }
    
    return OverlayConfig(
      enabled = prefs.getBoolean(KEY_ENABLED, false),
      layers = emptyList(),
      screenPreset = loadScreenPreset(prefs),
      screenLayout = loadScreenLayout(prefs),
      landscapeScreenLayout = loadLandscapeScreenLayout(
        context,
        prefs,
        loadFitMode(prefs, KEY_LANDSCAPE_SCREEN_FIT_MODE)
      ),
      portraitScreenFitMode = loadFitMode(prefs, KEY_PORTRAIT_SCREEN_FIT_MODE),
      landscapeScreenFitMode = loadFitMode(prefs, KEY_LANDSCAPE_SCREEN_FIT_MODE),
      portraitScreenLocked = prefs.getBoolean(KEY_PORTRAIT_SCREEN_LOCKED, false),
      landscapeScreenLocked = prefs.getBoolean(KEY_LANDSCAPE_SCREEN_LOCKED, false),
      screenLayerPosition = 0,
      canvasTheme = loadCanvasTheme(prefs),
      showGrid = prefs.getBoolean(KEY_SHOW_GRID, true),
      snapToCenter = prefs.getBoolean(KEY_SNAP_TO_CENTER, true)
    )
  }

  private fun loadScreenLayout(prefs: android.content.SharedPreferences): ScreenLayout {
    return ScreenLayout(
      positionXPct = prefs.getFloat(KEY_SCREEN_POSITION_X, 0f),
      positionYPct = prefs.getFloat(KEY_SCREEN_POSITION_Y, 0f),
      scalePct = prefs.getFloat(KEY_SCREEN_SCALE, 100f),
      rotationDegrees = prefs.getFloat(KEY_SCREEN_ROTATION, 0f)
    ).normalized()
  }

  private fun loadLandscapeScreenLayout(
    context: Context,
    prefs: android.content.SharedPreferences,
    fitMode: ScreenFitMode
  ): ScreenLayout {
    if (!prefs.contains(KEY_LANDSCAPE_SCREEN_POSITION_X)) {
      return CaptureOrientation.defaultLayout(
        streamCanvasAspect(context),
        ScreenPreset.LANDSCAPE,
        fitMode,
        CaptureDisplayAspect.landscapeAspect(context)
      )
    }
    val baseSize = CaptureOrientation.contentSize(
      streamCanvasAspect(context),
      ScreenPreset.LANDSCAPE,
      fitMode,
      CaptureDisplayAspect.landscapeAspect(context)
    )
    return ScreenLayout(
      positionXPct = prefs.getFloat(KEY_LANDSCAPE_SCREEN_POSITION_X, 0f),
      positionYPct = prefs.getFloat(KEY_LANDSCAPE_SCREEN_POSITION_Y, 0f),
      scalePct = prefs.getFloat(KEY_LANDSCAPE_SCREEN_SCALE, 100f),
      rotationDegrees = prefs.getFloat(KEY_LANDSCAPE_SCREEN_ROTATION, 0f)
    ).normalized(baseSize.first, baseSize.second)
  }

  private fun loadFitMode(
    prefs: android.content.SharedPreferences,
    key: String
  ): ScreenFitMode {
    return runCatching {
      ScreenFitMode.valueOf(prefs.getString(key, ScreenFitMode.ASPECT.name)!!)
    }.getOrDefault(ScreenFitMode.ASPECT)
  }

  private fun loadScreenPreset(prefs: android.content.SharedPreferences): ScreenPreset {
    return runCatching {
      ScreenPreset.valueOf(prefs.getString(KEY_SCREEN_PRESET, ScreenPreset.PORTRAIT.name)!!)
    }.getOrDefault(ScreenPreset.PORTRAIT)
  }

  private fun loadCanvasTheme(prefs: android.content.SharedPreferences): CanvasTheme {
    return runCatching {
      CanvasTheme.valueOf(prefs.getString(KEY_CANVAS_THEME, CanvasTheme.MIDNIGHT.name)!!)
    }.getOrDefault(CanvasTheme.MIDNIGHT)
  }

  private fun streamCanvasAspect(context: Context): Float {
    val prefs = context.getSharedPreferences("StreamSettings", Context.MODE_PRIVATE)
    val width = prefs.getInt("vertical_width", 720).coerceAtLeast(1)
    val height = prefs.getInt("vertical_height", 1280).coerceAtLeast(1)
    return width.toFloat() / height.toFloat()
  }

  fun clear(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
  }

  // Layer management functions
  fun addLayer(context: Context, layer: OverlayLayer) {
    val config = load(context)
    val newLayers = config.layers.toMutableList()
    newLayers.add(layer)
    save(context, config.copy(layers = newLayers))
  }

  fun removeLayer(context: Context, layerId: String) {
    val config = load(context)
    val newLayers = config.layers.filter { it.id != layerId }
    save(context, config.copy(layers = newLayers))
  }

  fun updateLayer(context: Context, layer: OverlayLayer) {
    val config = load(context)
    val newLayers = config.layers.map { if (it.id == layer.id) layer else it }
    save(context, config.copy(layers = newLayers))
  }

  fun reorderLayers(context: Context, layerIds: List<String>) {
    val config = load(context)
    val layerMap = config.layers.associateBy { it.id }
    val reorderedLayers = layerIds.mapNotNull { id ->
      layerMap[id]?.copy(zIndex = layerIds.indexOf(id))
    }
    save(context, config.copy(layers = reorderedLayers))
  }

  fun generateLayerId(): String {
    return "layer_${System.currentTimeMillis()}_${(0..999).random()}"
  }

  fun decodeBitmap(context: Context, uriString: String): Bitmap? {
    if (uriString.isEmpty()) return null
    return try {
      val uri = Uri.parse(uriString)
      context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input)
      }
    } catch (_: Throwable) {
      null
    }
  }

  fun persistUriPermission(context: Context, uri: Uri, intentFlags: Int) {
    try {
      val takeFlags = intentFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
      context.contentResolver.takePersistableUriPermission(uri, takeFlags)
    } catch (_: SecurityException) {
      // Ignore if we cannot persist
    }
  }
}
