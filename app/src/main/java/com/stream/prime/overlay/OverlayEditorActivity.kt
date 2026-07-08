package com.stream.prime.overlay

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ScaleGestureDetector
import android.view.LayoutInflater
import android.view.View
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.stream.prime.R
import com.stream.prime.screen.ScreenService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Enhanced overlay editor with pinch-zoom/pan on an ImageView canvas.
 * Now works with individual layers in a multi-layer system.
 * Stores percent-based position/scale to fit different stream sizes.
 */
class OverlayEditorActivity : AppCompatActivity() {

  private lateinit var imageView: ImageView
  private lateinit var previewOverlay: OverlayPreviewView
  private lateinit var alphaSeek: SeekBar
  private lateinit var btnSelect: TextView
  private lateinit var btnSave: TextView
  private lateinit var btnEditName: TextView
  private lateinit var btnDelete: TextView

  private var overlayBitmap: Bitmap? = null
  private var overlayUri: Uri? = null
  private val gson = Gson()
  private val layers: MutableList<OverlayLayer> = mutableListOf()
  private var selectedLayerId: String? = null
  
  // Layer properties
  private var layerId: String = ""
  private var layerName: String = "Layer"
  private var layerEnabled: Boolean = true
  private var zIndex: Int = 0
  private var isNewLayer: Boolean = false

  // Transformation state (percent units relative to container size)
  private var posXPct: Float = 10f
  private var posYPct: Float = 10f
  private var scaleXPct: Float = 30f
  private var scaleYPct: Float = 30f
  private var alphaValue: Float = 1f

  private var scaleGestureDetector: ScaleGestureDetector? = null
  private var currentScale = 1f

  private lateinit var pickerLauncher: ActivityResultLauncher<String>
  private var streamCanvasWidthPx: Int = 0
  private var streamCanvasHeightPx: Int = 0

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_overlay_editor)

    // MUST register before Lifecycle is STARTED to avoid IllegalStateException
    pickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
      if (uri != null) {
        overlayUri = uri
        OverlayManager.persistUriPermission(this, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        overlayBitmap = OverlayManager.decodeBitmap(this, uri.toString())
        imageView.setImageBitmap(overlayBitmap)
        applyTransform()

        // Apply picked image to the selected layer and refresh preview
        val selId = selectedLayerId ?: layerId
        val idx = layers.indexOfFirst { it.id == selId }
        if (idx >= 0) {
          layers[idx] = layers[idx].copy(imageUri = uri.toString(), enabled = true)
          // Persist immediately to avoid stale caches elsewhere
          try {
            val enabled = OverlayManager.load(this).enabled
            OverlayManager.save(this, OverlayConfig(enabled = enabled, layers = layers))
          } catch (_: Exception) { }
          // Clear preview caches by forcing update
          previewOverlay.updateLayers(layers)
        }
      }
    }

    imageView = findViewById(R.id.overlay_image)
    previewOverlay = findViewById(R.id.preview_overlay)
    alphaSeek = findViewById(R.id.alpha_seek)
    btnSelect = findViewById(R.id.btn_select_image)
    btnSave = findViewById(R.id.btn_save)
    btnEditName = findViewById(R.id.btn_edit_name)
    btnDelete = findViewById(R.id.btn_delete)

    // Load layer properties from intent
    layerId = intent.getStringExtra("layer_id") ?: OverlayManager.generateLayerId()
    layerName = intent.getStringExtra("layer_name") ?: "Layer"
    layerEnabled = intent.getBooleanExtra("layer_enabled", true)
    zIndex = intent.getIntExtra("layer_z_index", 0)
    isNewLayer = intent.getBooleanExtra("is_new_layer", false)
    
    posXPct = intent.getFloatExtra("layer_position_x", 10f)
    posYPct = intent.getFloatExtra("layer_position_y", 10f)
    scaleXPct = intent.getFloatExtra("layer_scale_x", 30f)
    scaleYPct = intent.getFloatExtra("layer_scale_y", 30f)
    alphaValue = intent.getFloatExtra("layer_alpha", 1f)
    
    val imageUri = intent.getStringExtra("layer_image_uri") ?: ""
    if (imageUri.isNotEmpty()) {
      overlayBitmap = OverlayManager.decodeBitmap(this, imageUri)
      overlayUri = Uri.parse(imageUri)
    }

    // Load all existing layers so they are visible while editing this one
    val cfg = OverlayManager.load(this)
    layers.clear()
    layers.addAll(cfg.layers)

    // Ensure the current layer exists in the list (especially for new layers)
    val existingIndex = layers.indexOfFirst { it.id == layerId }
    if (existingIndex == -1) {
      layers.add(
        OverlayLayer(
          id = layerId,
          name = layerName,
          enabled = layerEnabled,
          imageUri = overlayUri?.toString() ?: imageUri,
          positionXPct = posXPct,
          positionYPct = posYPct,
          scaleXPct = scaleXPct,
          scaleYPct = scaleYPct,
          alpha = alphaValue,
          zIndex = if (isNewLayer) layers.size else zIndex
        )
      )
    }
    selectedLayerId = layerId

    // Match preview aspect to stream canvas, like in LayerManagerActivity
    val prefs = getSharedPreferences("StreamSettings", 0)
    val mode = prefs.getString("streaming_mode", "Landscape") ?: "Landscape"
    val width = if (mode == "Vertical") prefs.getInt("vertical_width", 720) else prefs.getInt("landscape_width", 1280)
    val height = if (mode == "Vertical") prefs.getInt("vertical_height", 1280) else prefs.getInt("landscape_height", 720)
    streamCanvasWidthPx = width
    streamCanvasHeightPx = height
    previewOverlay.setStreamCanvasSize(width, height)
    previewOverlay.updateLayers(layers)

    // Set up events
    btnEditName.setOnClickListener { showEditNameSheet() }

    imageView.setImageBitmap(overlayBitmap)
    imageView.imageAlpha = (alphaValue * 255f).toInt()
    alphaSeek.progress = (alphaValue * 100).toInt()
    alphaSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
      override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
        alphaValue = progress / 100f
        imageView.imageAlpha = (alphaValue * 255f).toInt()
        // Apply to selected layer
        selectedLayerId?.let { id ->
          val idx = layers.indexOfFirst { it.id == id }
          if (idx >= 0) {
            layers[idx] = layers[idx].copy(alpha = alphaValue)
            previewOverlay.updateLayers(layers)
          }
        }
      }
      override fun onStartTrackingTouch(seekBar: SeekBar?) {}
      override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    })

    btnSelect.setOnClickListener { pickerLauncher.launch("image/*") }
    // Optional: long press save to clear image
    btnSave.setOnLongClickListener { clearLayer(); true }
    btnSave.setOnClickListener { saveLayer() }
    btnDelete.setOnClickListener { removeItemFromLayer() }

    // Hide single-image editor, we edit directly on the preview of all layers
    imageView.visibility = View.INVISIBLE

    // Pan and pinch-zoom handling over the preview canvas
    var lastX = 0f
    var lastY = 0f
    previewOverlay.setOnTouchListener { _, event ->
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          // Lock selection to the layer being edited; do not change selection by tapping other layers
          selectedLayerId = layerId
          lastX = event.x
          lastY = event.y
        }
        MotionEvent.ACTION_MOVE -> {
          val dx = event.x - lastX
          val dy = event.y - lastY
          lastX = event.x
          lastY = event.y
          selectedLayerId?.let { id ->
            val idx = layers.indexOfFirst { it.id == id }
            if (idx >= 0) {
              val vpW = previewOverlay.width.coerceAtLeast(1)
              val vpH = previewOverlay.height.coerceAtLeast(1)
              val current = layers[idx]

              // Movement in percent relative to canvas viewport size
              val moveXPct = (dx / vpW) * 100f
              val moveYPct = (dy / vpH) * 100f

              // Compute actual occupied size in percent considering aspect-ratio preserve
              val (usedWPct, usedHPct) = computeUsedPercents(
                current.scaleXPct, current.scaleYPct, overlayBitmap, streamCanvasWidthPx, streamCanvasHeightPx
              )

              val maxX = (100f - usedWPct).coerceAtLeast(0f)
              val maxY = (100f - usedHPct).coerceAtLeast(0f)
              val newX = (current.positionXPct + moveXPct).coerceIn(0f, maxX)
              val newY = (current.positionYPct + moveYPct).coerceIn(0f, maxY)
              layers[idx] = layers[idx].copy(positionXPct = newX, positionYPct = newY)
              previewOverlay.updateLayers(layers)
            }
          }
        }
      }
      scaleGestureDetector?.onTouchEvent(event)
      true
    }
    scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
      override fun onScale(detector: ScaleGestureDetector): Boolean {
        selectedLayerId?.let { id ->
          val idx = layers.indexOfFirst { it.id == id }
          if (idx >= 0) {
            val current = layers[idx]
            var newScaleX = (current.scaleXPct * detector.scaleFactor).coerceAtLeast(5f)
            var newScaleY = (current.scaleYPct * detector.scaleFactor).coerceAtLeast(5f)

            // Recompute used size with tentative scales
            var (usedWPct, usedHPct) = computeUsedPercents(
              newScaleX, newScaleY, overlayBitmap, streamCanvasWidthPx, streamCanvasHeightPx
            )

            // If exceeds bounds, scale back proportionally
            val fitWPct = (100f - current.positionXPct).coerceAtLeast(5f)
            val fitHPct = (100f - current.positionYPct).coerceAtLeast(5f)
            val overW = if (usedWPct > fitWPct) usedWPct / fitWPct else 1f
            val overH = if (usedHPct > fitHPct) usedHPct / fitHPct else 1f
            val reduceFactor = 1f / maxOf(overW, overH)
            if (reduceFactor < 1f) {
              newScaleX *= reduceFactor
              newScaleY *= reduceFactor
              val recomputed = computeUsedPercents(newScaleX, newScaleY, overlayBitmap, streamCanvasWidthPx, streamCanvasHeightPx)
              usedWPct = recomputed.first
              usedHPct = recomputed.second
            }

            layers[idx] = current.copy(scaleXPct = newScaleX, scaleYPct = newScaleY)
            previewOverlay.updateLayers(layers)
          }
        }
        return true
      }
    })

    // Initial render already set via updateLayers
  }

  private fun applyTransform() {
    // Kept for backward compatibility, but editing is now done on previewOverlay
    // Sync the single image view only if needed
    val bmp = overlayBitmap ?: return
    val w = imageView.width.toFloat().coerceAtLeast(1f)
    val h = imageView.height.toFloat().coerceAtLeast(1f)
    val m = Matrix()
    val desiredW = (scaleXPct / 100f) * w
    val desiredH = (scaleYPct / 100f) * h
    val bmpW = bmp.width.toFloat().coerceAtLeast(1f)
    val bmpH = bmp.height.toFloat().coerceAtLeast(1f)
    val scale = when {
      desiredW <= 0f && desiredH <= 0f -> 1f
      desiredW <= 0f -> desiredH / bmpH
      desiredH <= 0f -> desiredW / bmpW
      else -> minOf(desiredW / bmpW, desiredH / bmpH)
    }
    m.postScale(scale, scale)
    val tx = (posXPct / 100f) * w
    val ty = (posYPct / 100f) * h
    m.postTranslate(tx, ty)
    imageView.imageMatrix = m
    imageView.scaleType = ImageView.ScaleType.MATRIX
  }

  /**
   * Compute actual used width/height in percent of canvas when preserving aspect ratio.
   * Returns Pair(usedWidthPct, usedHeightPct).
   */
  private fun computeUsedPercents(
    targetScaleXPct: Float,
    targetScaleYPct: Float,
    bmp: Bitmap?,
    canvasW: Int,
    canvasH: Int
  ): Pair<Float, Float> {
    if (bmp == null || canvasW <= 0 || canvasH <= 0) return Pair(targetScaleXPct, targetScaleYPct)
    val desiredW = (targetScaleXPct / 100f) * canvasW
    val desiredH = (targetScaleYPct / 100f) * canvasH
    val bmpW = bmp.width.toFloat().coerceAtLeast(1f)
    val bmpH = bmp.height.toFloat().coerceAtLeast(1f)
    val scale = when {
      desiredW <= 0f && desiredH <= 0f -> 1f
      desiredW <= 0f -> desiredH / bmpH
      desiredH <= 0f -> desiredW / bmpW
      else -> minOf(desiredW / bmpW, desiredH / bmpH)
    }
    val drawW = bmpW * scale
    val drawH = bmpH * scale
    val usedWPct = (drawW / canvasW) * 100f
    val usedHPct = (drawH / canvasH) * 100f
    return Pair(usedWPct, usedHPct)
  }
  
  private fun clearLayer() {
    overlayBitmap = null
    overlayUri = null
    imageView.setImageBitmap(null)
    selectedLayerId?.let { id ->
      val idx = layers.indexOfFirst { it.id == id }
      if (idx >= 0) {
        layers[idx] = layers[idx].copy(imageUri = "")
        previewOverlay.updateLayers(layers)
      }
    }
  }
  
  private fun saveLayer() {
    val resultIntent = Intent()
    // Find the latest data for the originally requested layer
    val idx = layers.indexOfFirst { it.id == layerId }
    val layer = if (idx >= 0) layers[idx] else OverlayLayer(id = layerId, name = layerName)
    resultIntent.putExtra("layer_id", layer.id)
    resultIntent.putExtra("layer_name", layer.name)
    resultIntent.putExtra("layer_enabled", layer.enabled)
    resultIntent.putExtra("layer_image_uri", layer.imageUri)
    resultIntent.putExtra("layer_position_x", layer.positionXPct)
    resultIntent.putExtra("layer_position_y", layer.positionYPct)
    resultIntent.putExtra("layer_scale_x", layer.scaleXPct)
    resultIntent.putExtra("layer_scale_y", layer.scaleYPct)
    resultIntent.putExtra("layer_alpha", layer.alpha)
    resultIntent.putExtra("layer_z_index", layer.zIndex)

    // Also return the entire layer set so changes to other layers are preserved
    val layersJson = gson.toJson(layers)
    resultIntent.putExtra("layers_json", layersJson)
    
    // Persist immediately so inner save truly saves without requiring the outer button
    try {
      val enabled = OverlayManager.load(this).enabled
      OverlayManager.save(this, OverlayConfig(enabled = enabled, layers = layers))
    } catch (_: Exception) { }
    
    setResult(Activity.RESULT_OK, resultIntent)
    finish()
  }

  private fun removeItemFromLayer() {
    // Clear only the item (image) in this layer, keep the layer itself
    val idx = layers.indexOfFirst { it.id == layerId }
    if (idx >= 0) {
      val cleared = layers[idx].copy(imageUri = "")
      layers[idx] = cleared
    }

    overlayBitmap = null
    overlayUri = null
    imageView.setImageBitmap(null)
    previewOverlay.updateLayers(layers)

    // Persist immediately
    try {
      val enabled = OverlayManager.load(this).enabled
      OverlayManager.save(this, OverlayConfig(enabled = enabled, layers = layers))
    } catch (_: Exception) { }

    // Clear any stale caches in preview by updating
    previewOverlay.updateLayers(layers)
    // Stay on this screen; no finish. Optionally notify caller later on Save
  }

  private fun showEditNameSheet() {
    val view = LayoutInflater.from(this).inflate(R.layout.bottomsheet_edit_layer_name, null, false)
    val dialog = android.app.Dialog(this, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
    dialog.setContentView(view)
    val input = view.findViewById<android.widget.EditText>(R.id.input_layer_name)
    val btnCancel = view.findViewById<TextView>(R.id.btn_cancel)
    val btnOk = view.findViewById<TextView>(R.id.btn_ok)
    input.setText(layerName)
    btnCancel.setOnClickListener { dialog.dismiss() }
    btnOk.setOnClickListener {
      val newName = input.text?.toString()?.trim().orEmpty()
      if (newName.isNotEmpty()) {
        layerName = newName
        // Apply to selected layer
        selectedLayerId?.let { id ->
          val idx = layers.indexOfFirst { it.id == id }
          if (idx >= 0) {
            layers[idx] = layers[idx].copy(name = layerName)
          }
        }
      }
      dialog.dismiss()
    }
    dialog.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    dialog.show()
  }
}


