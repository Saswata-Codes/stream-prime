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

data class OverlayLayer(
  val id: String = "",
  val name: String = "Layer",
  val enabled: Boolean = true,
  val imageUri: String = "",
  val positionXPct: Float = 0f,
  val positionYPct: Float = 0f,
  val scaleXPct: Float = 30f,
  val scaleYPct: Float = 30f,
  val alpha: Float = 1f,
  val zIndex: Int = 0 // Layer ordering, higher values appear on top
)

data class OverlayConfig(
  val enabled: Boolean = false,
  val layers: List<OverlayLayer> = emptyList()
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
    prefs.edit()
      .putBoolean(KEY_ENABLED, config.enabled)
      .putString(KEY_LAYERS, layersJson)
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
          layers = layers
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
        layers = listOf(legacyLayer)
      )
    }
    
    return OverlayConfig(enabled = false, layers = emptyList())
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


