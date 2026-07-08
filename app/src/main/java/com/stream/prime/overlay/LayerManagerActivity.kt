package com.stream.prime.overlay

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.stream.prime.R
import com.stream.prime.screen.ScreenService
import java.util.*

class LayerManagerActivity : AppCompatActivity() {

  private lateinit var recyclerView: RecyclerView
  private lateinit var layerAdapter: LayerAdapter
  private lateinit var btnAddLayer: TextView
  private lateinit var btnSave: TextView
  private lateinit var switchEnabled: Switch
  private lateinit var previewOverlay: OverlayPreviewView
  
  private val layers = mutableListOf<OverlayLayer>()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_layer_manager)
    
    recyclerView = findViewById(R.id.recycler_layers)
    btnAddLayer = findViewById(R.id.btn_add_layer)
    btnSave = findViewById(R.id.btn_save)
    switchEnabled = findViewById(R.id.switch_enabled)
    previewOverlay = findViewById(R.id.preview_overlay)
    
    setupRecyclerView()
    loadLayers()
    setupClickListeners()
  }
  
  private fun setupRecyclerView() {
    layerAdapter = LayerAdapter(
      layers = layers,
      onLayerAction = { layer, action ->
        when (action) {
          LayerAction.EDIT -> editLayer(layer)
          LayerAction.DELETE -> deleteLayer(layer)
          LayerAction.TOGGLE -> toggleLayer(layer)
        }
      },
      onLayersReordered = {
        persistLayers()
      }
    )
    
    recyclerView.layoutManager = LinearLayoutManager(this)
    recyclerView.adapter = layerAdapter
    
    // Add drag-and-drop functionality for reordering
    val itemTouchHelper = ItemTouchHelper(LayerMoveCallback(layerAdapter))
    itemTouchHelper.attachToRecyclerView(recyclerView)
  }
  
  private fun loadLayers() {
    val config = OverlayManager.load(this)
    switchEnabled.isChecked = config.enabled
    layers.clear()
    layers.addAll(config.layers)
    layerAdapter.notifyDataSetChanged()
    updatePreview()
  }
  
  private fun setupClickListeners() {
    btnAddLayer.setOnClickListener { addNewLayer() }
    btnSave.setOnClickListener { saveAndFinish() }
    switchEnabled.setOnCheckedChangeListener { _, _ ->
      persistLayers()
    }
  }
  
  private fun addNewLayer() {
    val newLayer = OverlayLayer(
      id = OverlayManager.generateLayerId(),
      name = "Layer ${layers.size + 1}",
      enabled = true,
      zIndex = layers.size
    )
    editLayer(newLayer, isNew = true)
  }
  
  private fun editLayer(layer: OverlayLayer, isNew: Boolean = false) {
    val intent = Intent(this, OverlayEditorActivity::class.java)
    intent.putExtra("layer_id", layer.id)
    intent.putExtra("layer_name", layer.name)
    intent.putExtra("layer_enabled", layer.enabled)
    intent.putExtra("layer_image_uri", layer.imageUri)
    intent.putExtra("layer_position_x", layer.positionXPct)
    intent.putExtra("layer_position_y", layer.positionYPct)
    intent.putExtra("layer_scale_x", layer.scaleXPct)
    intent.putExtra("layer_scale_y", layer.scaleYPct)
    intent.putExtra("layer_alpha", layer.alpha)
    intent.putExtra("layer_z_index", layer.zIndex)
    intent.putExtra("is_new_layer", isNew)
    
    startActivityForResult(intent, if (isNew) REQUEST_CODE_ADD_LAYER else REQUEST_CODE_EDIT_LAYER)
  }
  
  private fun deleteLayer(layer: OverlayLayer) {
    AlertDialog.Builder(this)
      .setTitle("Delete Layer")
      .setMessage("Are you sure you want to delete '${layer.name}'?")
      .setPositiveButton("Delete") { _, _ ->
        layers.removeAll { it.id == layer.id }
        layerAdapter.notifyDataSetChanged()
        persistLayers()
      }
      .setNegativeButton("Cancel", null)
      .show()
  }
  
  private fun toggleLayer(layer: OverlayLayer) {
    val index = layers.indexOfFirst { it.id == layer.id }
    if (index >= 0) {
      layers[index] = layer.copy(enabled = !layer.enabled)
      layerAdapter.notifyItemChanged(index)
      persistLayers()
    }
  }
  
  private fun saveAndFinish() {
    val config = OverlayConfig(
      enabled = switchEnabled.isChecked,
      layers = layers
    )
    OverlayManager.save(this, config)
    
    // Notify ScreenService to re-apply overlays
    try {
      val intent = Intent(this, ScreenService::class.java).apply {
        action = "com.stream.prime.APPLY_OVERLAY"
      }
      startService(intent)
    } catch (_: Exception) { }
    
    setResult(Activity.RESULT_OK)
    finish()
  }
  
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    
    if (resultCode == Activity.RESULT_OK && data != null) {
      // If editor returned a full layer set, apply it first
      data.getStringExtra("layers_json")?.let { json ->
        try {
          val type = object : com.google.gson.reflect.TypeToken<List<OverlayLayer>>() {}.type
          val restored: List<OverlayLayer> = com.google.gson.Gson().fromJson(json, type)
          layers.clear()
          layers.addAll(restored)
          layerAdapter.notifyDataSetChanged()
          persistLayers()
        } catch (_: Exception) { }
      }

      // No hard layer deletion from editor anymore (remove only clears image)

      val layerId = data.getStringExtra("layer_id") ?: return
      val layerName = data.getStringExtra("layer_name") ?: "Layer"
      val layerEnabled = data.getBooleanExtra("layer_enabled", true)
      val imageUri = data.getStringExtra("layer_image_uri") ?: ""
      val posX = data.getFloatExtra("layer_position_x", 10f)
      val posY = data.getFloatExtra("layer_position_y", 10f)
      val scaleX = data.getFloatExtra("layer_scale_x", 30f)
      val scaleY = data.getFloatExtra("layer_scale_y", 30f)
      val alpha = data.getFloatExtra("layer_alpha", 1f)
      val zIndex = data.getIntExtra("layer_z_index", 0)
      
      val updatedLayer = OverlayLayer(
        id = layerId,
        name = layerName,
        enabled = layerEnabled,
        imageUri = imageUri,
        positionXPct = posX,
        positionYPct = posY,
        scaleXPct = scaleX,
        scaleYPct = scaleY,
        alpha = alpha,
        zIndex = zIndex
      )
      
      when (requestCode) {
        REQUEST_CODE_ADD_LAYER -> {
          val existing = layers.indexOfFirst { it.id == updatedLayer.id }
          if (existing >= 0) {
            layers[existing] = updatedLayer
            layerAdapter.notifyItemChanged(existing)
          } else {
            layers.add(updatedLayer)
            layerAdapter.notifyItemInserted(layers.size - 1)
          }
          persistLayers()
        }
        REQUEST_CODE_EDIT_LAYER -> {
          val index = layers.indexOfFirst { it.id == layerId }
          if (index >= 0) {
            layers[index] = updatedLayer
            layerAdapter.notifyItemChanged(index)
            persistLayers()
          }
        }
      }
    }
  }

  private fun persistLayers() {
    // Normalize z-index based on current list order (bottom=0 ... top=n)
    val normalized = layers.mapIndexed { idx, l -> l.copy(zIndex = idx) }
    layers.clear()
    layers.addAll(normalized)

    // Save immediately so inner editor Save persists without needing outer Save
    OverlayManager.save(this, OverlayConfig(enabled = switchEnabled.isChecked, layers = layers))

    // Update preview and notify streaming service to re-apply
    updatePreview()
    try {
      val intent = Intent(this, ScreenService::class.java).apply { action = "com.stream.prime.APPLY_OVERLAY" }
      startService(intent)
    } catch (_: Exception) { }
  }
  
  private fun updatePreview() {
    // Set preview viewport to match current stream resolution for identical aspect
    val cfg = OverlayManager.load(this)
    // Try to infer stream size from settings if available; otherwise use a safe default
    val prefs = getSharedPreferences("StreamSettings", 0)
    val mode = prefs.getString("streaming_mode", "Landscape") ?: "Landscape"
    val width = if (mode == "Vertical") prefs.getInt("vertical_width", 720) else prefs.getInt("landscape_width", 1280)
    val height = if (mode == "Vertical") prefs.getInt("vertical_height", 1280) else prefs.getInt("landscape_height", 720)
    previewOverlay.setStreamCanvasSize(width, height)
    previewOverlay.updateLayers(layers)
  }
  
  override fun onDestroy() {
    super.onDestroy()
    previewOverlay.clearCache()
  }
  
  companion object {
    private const val REQUEST_CODE_ADD_LAYER = 1001
    private const val REQUEST_CODE_EDIT_LAYER = 1002
  }
}

enum class LayerAction {
  EDIT, DELETE, TOGGLE
}

class LayerAdapter(
  private val layers: MutableList<OverlayLayer>,
  private val onLayerAction: (OverlayLayer, LayerAction) -> Unit,
  private val onLayersReordered: (() -> Unit)? = null
) : RecyclerView.Adapter<LayerAdapter.LayerViewHolder>() {

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LayerViewHolder {
    val view = LayoutInflater.from(parent.context)
      .inflate(R.layout.item_overlay_layer, parent, false)
    return LayerViewHolder(view)
  }

  override fun onBindViewHolder(holder: LayerViewHolder, position: Int) {
    holder.bind(layers[position])
  }

  override fun getItemCount() = layers.size
  
  fun moveItem(fromPosition: Int, toPosition: Int) {
    if (fromPosition < toPosition) {
      for (i in fromPosition until toPosition) {
        Collections.swap(layers, i, i + 1)
      }
    } else {
      for (i in fromPosition downTo toPosition + 1) {
        Collections.swap(layers, i, i - 1)
      }
    }
    notifyItemMoved(fromPosition, toPosition)
    onLayersReordered?.invoke()
  }

  inner class LayerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    private val textName: TextView = itemView.findViewById(R.id.text_layer_name)
    private val switchEnabled: Switch = itemView.findViewById(R.id.switch_layer_enabled)
    private val btnEdit: TextView = itemView.findViewById(R.id.btn_edit_layer)
    private val btnDelete: TextView = itemView.findViewById(R.id.btn_delete_layer)
    private val dragHandle: View = itemView.findViewById(R.id.drag_handle)

    fun bind(layer: OverlayLayer) {
      textName.text = layer.name
      switchEnabled.isChecked = layer.enabled
      
      switchEnabled.setOnCheckedChangeListener { _, _ ->
        onLayerAction(layer, LayerAction.TOGGLE)
      }
      
      btnEdit.setOnClickListener {
        onLayerAction(layer, LayerAction.EDIT)
      }
      
      btnDelete.setOnClickListener {
        onLayerAction(layer, LayerAction.DELETE)
      }
    }
  }
}

class LayerMoveCallback(private val adapter: LayerAdapter) : ItemTouchHelper.SimpleCallback(
  ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
) {
  
  override fun onMove(
    recyclerView: RecyclerView,
    viewHolder: RecyclerView.ViewHolder,
    target: RecyclerView.ViewHolder
  ): Boolean {
    adapter.moveItem(viewHolder.adapterPosition, target.adapterPosition)
    return true
  }
  
  override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
    // Not implemented - we don't want swipe to delete
  }
  
  override fun isLongPressDragEnabled(): Boolean {
    return true
  }
}