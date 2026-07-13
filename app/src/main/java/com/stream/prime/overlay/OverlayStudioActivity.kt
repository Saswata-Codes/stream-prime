package com.stream.prime.overlay

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.stream.prime.R
import com.stream.prime.screen.ScreenService
import com.stream.prime.settings.SettingsManager

/** Full-canvas overlay composer. Screen, image and text are edited in the same workspace. */
class OverlayStudioActivity : AppCompatActivity() {
  private lateinit var canvas: OverlayStudioView
  private lateinit var nameView: TextView
  private lateinit var selectionView: TextView
  private lateinit var deleteButton: Button
  private lateinit var downButton: Button
  private lateinit var upButton: Button
  private lateinit var fitButton: Button
  private lateinit var lockButton: Button
  private lateinit var rotateButton: Button
  private lateinit var resetButton: Button
  private lateinit var portraitButton: TextView
  private lateinit var landscapeButton: TextView
  private lateinit var project: OverlayProject
  private var preset = ScreenPreset.PORTRAIT
  private var dirty = false

  private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    uri ?: return@registerForActivityResult
    runCatching {
      contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val config = canvas.getConfig()
    val layer = OverlayLayer(
      id = OverlayManager.generateLayerId(),
      name = "Image ${config.layers.count { it.type != OverlayLayerType.TEXT } + 1}",
      type = OverlayLayerType.IMAGE,
      imageUri = uri.toString(),
      positionXPct = 15f,
      positionYPct = 15f,
      scaleXPct = 45f,
      scaleYPct = 30f,
      zIndex = (config.layers.maxOfOrNull { it.zIndex } ?: -1) + 1
    )
    canvas.setConfig(config.copy(layers = config.layers + layer))
    canvas.selectLayer(layer.id)
    markDirty()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_overlay_studio)

    val requestedId = intent.getStringExtra(EXTRA_PROJECT_ID)
    project = requestedId?.let { OverlayProjectManager.get(this, it) }
      ?: OverlayProjectManager.get(this, OverlayProjectManager.activeId(this))
      ?: OverlayProjectManager.create(this)

    canvas = findViewById(R.id.overlay_studio_canvas)
    nameView = findViewById(R.id.text_project_name)
    selectionView = findViewById(R.id.text_selection)
    deleteButton = findViewById(R.id.btn_delete_selected)
    downButton = findViewById(R.id.btn_layer_down)
    upButton = findViewById(R.id.btn_layer_up)
    fitButton = findViewById(R.id.btn_screen_fit)
    lockButton = findViewById(R.id.btn_lock_selected)
    rotateButton = findViewById(R.id.btn_rotate_selected)
    resetButton = findViewById(R.id.btn_reset_selected)
    portraitButton = findViewById(R.id.btn_portrait_orientation)
    landscapeButton = findViewById(R.id.btn_landscape_orientation)

    nameView.text = project.name
    // Disabled projects remain fully editable; visibility is controlled by the library switch.
    canvas.setConfig(project.config)
    val vertical = SettingsManager.getStreamingMode(this) == "Vertical"
    canvas.setCanvasSize(
      if (vertical) SettingsManager.getVerticalWidth(this) else SettingsManager.getLandscapeWidth(this),
      if (vertical) SettingsManager.getVerticalHeight(this) else SettingsManager.getLandscapeHeight(this)
    )
    canvas.setOnConfigChanged { _, _ -> markDirty() }
    canvas.setOnSelectionChanged { updateSelectionUi(it) }

    bindActions()
    choosePreset(project.config.screenPreset, persistChoice = false)
    updateSelectionUi(canvas.getSelection())
  }

  private fun bindActions() {
    findViewById<View>(R.id.btn_close).setOnClickListener { closeStudio() }
    findViewById<View>(R.id.btn_save_project).setOnClickListener { saveProject(true) }
    nameView.setOnClickListener { renameProject() }
    portraitButton.setOnClickListener { choosePreset(ScreenPreset.PORTRAIT) }
    landscapeButton.setOnClickListener { choosePreset(ScreenPreset.LANDSCAPE) }
    findViewById<View>(R.id.btn_add_image).setOnClickListener { imagePicker.launch(arrayOf("image/*")) }
    findViewById<View>(R.id.btn_add_text).setOnClickListener { addTextLayer() }
    findViewById<View>(R.id.btn_select_screen).setOnClickListener { canvas.selectScreen() }
    findViewById<View>(R.id.btn_show_layers).setOnClickListener { showLayers() }
    findViewById<View>(R.id.btn_theme).setOnClickListener { chooseTheme() }
    findViewById<View>(R.id.btn_grid).setOnClickListener {
      val config = canvas.getConfig(); canvas.setConfig(config.copy(showGrid = !config.showGrid)); markDirty()
    }
    findViewById<View>(R.id.btn_snap).setOnClickListener {
      val config = canvas.getConfig(); canvas.setConfig(config.copy(snapToCenter = !config.snapToCenter)); markDirty()
    }
    findViewById<View>(R.id.btn_fit_scene).setOnClickListener {
      when (canvas.fitSceneToCanvas()) {
        SceneFitResult.FITTED -> Toast.makeText(this, "Scene fitted with a small safe margin", Toast.LENGTH_SHORT).show()
        SceneFitResult.LOCKED -> Toast.makeText(this, "Unlock visible layers before fitting the scene", Toast.LENGTH_SHORT).show()
        SceneFitResult.NOTHING_VISIBLE -> Toast.makeText(this, "Nothing visible to fit", Toast.LENGTH_SHORT).show()
      }
    }
    deleteButton.setOnClickListener { confirmDeleteSelected() }
    lockButton.setOnClickListener { canvas.toggleSelectedLock(); updateSelectionUi(canvas.getSelection()) }
    rotateButton.setOnClickListener { canvas.rotateSelected() }
    resetButton.setOnClickListener { canvas.resetSelected() }
    downButton.setOnClickListener { canvas.moveSelectedLayer(-1) }
    upButton.setOnClickListener { canvas.moveSelectedLayer(1) }
    fitButton.setOnClickListener { canvas.toggleFitMode(); updateSelectionUi(canvas.getSelection()) }
  }

  private fun choosePreset(value: ScreenPreset, persistChoice: Boolean = true) {
    preset = value
    canvas.setScreenPreset(value, persistChoice)
    val portraitActive = value == ScreenPreset.PORTRAIT
    portraitButton.isSelected = portraitActive
    landscapeButton.isSelected = !portraitActive
    portraitButton.setTextColor(if (portraitActive) Color.rgb(0, 230, 118) else Color.rgb(184, 189, 199))
    landscapeButton.setTextColor(if (!portraitActive) Color.rgb(0, 230, 118) else Color.rgb(184, 189, 199))
    portraitButton.setBackgroundResource(if (portraitActive) R.drawable.overlay_orientation_active else R.drawable.overlay_orientation_inactive)
    landscapeButton.setBackgroundResource(if (!portraitActive) R.drawable.overlay_orientation_active else R.drawable.overlay_orientation_inactive)
    portraitButton.setTypeface(null, if (portraitActive) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    landscapeButton.setTypeface(null, if (!portraitActive) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    updateSelectionUi(canvas.getSelection())
  }

  private fun updateSelectionUi(selection: StudioSelection) {
    val isLayer = selection.type == StudioSelectionType.LAYER
    val layer = canvas.getConfig().layers.firstOrNull { it.id == selection.layerId }
    val locked = canvas.isSelectionLocked()
    selectionView.text = if (isLayer) {
      "${layer?.name ?: "Layer"} selected · ${if (locked) "LOCKED" else "drag / pinch / twist"}"
    } else {
      "Screen capture · ${if (preset == ScreenPreset.PORTRAIT) "Portrait" else "Landscape"} · ${if (locked) "LOCKED" else "drag / pinch / twist"}"
    }
    deleteButton.visibility = if (isLayer) View.VISIBLE else View.GONE
    downButton.visibility = View.VISIBLE
    upButton.visibility = View.VISIBLE
    fitButton.visibility = if (isLayer) View.GONE else View.VISIBLE
    lockButton.text = if (locked) "Unlock" else "Lock"
    listOf(deleteButton, rotateButton, resetButton, downButton, upButton, fitButton).forEach {
      it.isEnabled = !locked
      it.alpha = if (locked) 0.45f else 1f
    }
    val config = canvas.getConfig()
    val fit = if (preset == ScreenPreset.PORTRAIT) config.portraitScreenFitMode else config.landscapeScreenFitMode
    fitButton.text = if (fit == ScreenFitMode.ASPECT) "Aspect fit" else "Stretch"
  }

  private fun addTextLayer() {
    val input = EditText(this).apply { hint = "Text shown on stream"; setPadding(48, 24, 48, 24) }
    AlertDialog.Builder(this)
      .setTitle("Add text")
      .setView(input)
      .setPositiveButton("Add") { _, _ ->
        val text = input.text.toString().trim()
        if (text.isEmpty()) return@setPositiveButton
        val config = canvas.getConfig()
        val layer = OverlayLayer(
          id = OverlayManager.generateLayerId(),
          name = text.take(24),
          type = OverlayLayerType.TEXT,
          text = text,
          backgroundColor = "#99000000",
          positionXPct = 15f,
          positionYPct = 15f,
          scaleXPct = 70f,
          scaleYPct = 12f,
          zIndex = (config.layers.maxOfOrNull { it.zIndex } ?: -1) + 1
        )
        canvas.setConfig(config.copy(layers = config.layers + layer))
        canvas.selectLayer(layer.id)
        markDirty()
      }
      .setNegativeButton("Cancel", null)
      .show()
  }

  private fun confirmDeleteSelected() {
    val layer = canvas.getConfig().layers.firstOrNull { it.id == canvas.getSelection().layerId } ?: return
    if (layer.locked) {
      Toast.makeText(this, "Unlock ${layer.name} before deleting", Toast.LENGTH_SHORT).show()
      return
    }
    AlertDialog.Builder(this)
      .setTitle("Delete ${layer.name}?")
      .setMessage("This removes the layer from both portrait and landscape scenes.")
      .setPositiveButton("Delete") { _, _ -> canvas.deleteSelected() }
      .setNegativeButton("Cancel", null)
      .show()
  }

  private fun showLayers() {
    val panel = layoutInflater.inflate(R.layout.dialog_layer_order, null)
    val recycler = panel.findViewById<RecyclerView>(R.id.recycler_layer_order)
    val config = canvas.getConfig()
    val layersById = config.layers.associateBy { it.id }
    val screenLocked = if (preset == ScreenPreset.PORTRAIT) config.portraitScreenLocked else config.landscapeScreenLocked
    val items = OverlayLayerOrdering.topFirstWithScreen(config.layers, config.screenLayerPosition).map { id ->
      if (id == OverlayLayerOrdering.SCREEN_ID) {
        StudioStackItem(id, "Screen capture", "${if (preset == ScreenPreset.PORTRAIT) "Portrait" else "Landscape"} shape", screenLocked)
      } else {
        val layer = layersById.getValue(id)
        StudioStackItem(
          id,
          layer.name,
          "${if (layer.type == OverlayLayerType.TEXT) "Text" else "Image"} · ${if (layer.enabled) "Visible" else "Hidden"}",
          layer.locked
        )
      }
    }.toMutableList()
    lateinit var dialog: AlertDialog
    lateinit var adapter: StudioLayerOrderAdapter

    adapter = StudioLayerOrderAdapter(
      items = items,
      onSelect = { id ->
        if (id == OverlayLayerOrdering.SCREEN_ID) canvas.selectScreen() else canvas.selectLayer(id)
        dialog.dismiss()
      },
      onLock = { id ->
        if (id == OverlayLayerOrdering.SCREEN_ID) canvas.selectScreen() else canvas.selectLayer(id)
        canvas.toggleSelectedLock()
        val current = canvas.getConfig()
        val locked = if (id == OverlayLayerOrdering.SCREEN_ID) {
          if (preset == ScreenPreset.PORTRAIT) current.portraitScreenLocked else current.landscapeScreenLocked
        } else current.layers.firstOrNull { it.id == id }?.locked == true
        adapter.updateLock(id, locked)
        updateSelectionUi(canvas.getSelection())
      },
      onDelete = { id ->
        val layer = canvas.getConfig().layers.firstOrNull { it.id == id }
        if (layer != null) {
          if (layer.locked) {
            Toast.makeText(this, "Unlock ${layer.name} before deleting", Toast.LENGTH_SHORT).show()
          } else {
            AlertDialog.Builder(this)
              .setTitle("Delete ${layer.name}?")
              .setMessage("This removes the layer from both portrait and landscape scenes.")
              .setPositiveButton("Delete") { _, _ ->
                canvas.selectLayer(id)
                canvas.deleteSelected()
                adapter.remove(id)
                updateSelectionUi(canvas.getSelection())
              }
              .setNegativeButton("Cancel", null)
              .show()
          }
        }
      }
    )
    recycler.layoutManager = LinearLayoutManager(this)
    recycler.adapter = adapter
    ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
      override fun onMove(rv: RecyclerView, source: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
        if (!adapter.canMove(source.adapterPosition, target.adapterPosition)) return false
        adapter.move(source.adapterPosition, target.adapterPosition)
        canvas.reorderStackTopFirst(adapter.itemIds())
        return true
      }
      override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
    }).attachToRecyclerView(recycler)

    dialog = AlertDialog.Builder(this)
      .setTitle("Arrange layers · top first")
      .setView(panel)
      .setNegativeButton("Done", null)
      .create()
    dialog.show()
  }

  private fun chooseTheme() {
    val themes = CanvasTheme.values()
    val checked = themes.indexOf(canvas.getConfig().canvasTheme)
    AlertDialog.Builder(this)
      .setTitle("Canvas theme")
      .setSingleChoiceItems(themes.map { it.displayName }.toTypedArray(), checked) { dialog, which ->
        canvas.setConfig(canvas.getConfig().copy(canvasTheme = themes[which])); markDirty(); dialog.dismiss()
      }
      .show()
  }

  private fun renameProject() {
    val input = EditText(this).apply { setText(project.name); selectAll() }
    AlertDialog.Builder(this).setTitle("Overlay name").setView(input)
      .setPositiveButton("Rename") { _, _ ->
        val value = input.text.toString().trim()
        if (value.isNotEmpty()) { project = project.copy(name = value); nameView.text = value; markDirty() }
      }
      .setNegativeButton("Cancel", null).show()
  }

  private fun markDirty() { dirty = true }

  private fun saveProject(showMessage: Boolean) {
    project = project.copy(config = canvas.getConfig())
    OverlayProjectManager.save(this, project)
    applyLiveOverlay()
    dirty = false
    setResult(RESULT_OK)
    if (showMessage) Toast.makeText(this, "Overlay saved", Toast.LENGTH_SHORT).show()
  }

  private fun applyLiveOverlay() {
    runCatching {
      startService(Intent(this, ScreenService::class.java).apply { action = ACTION_APPLY_OVERLAY })
    }
  }

  private fun closeStudio() {
    if (!dirty) { finish(); return }
    AlertDialog.Builder(this).setTitle("Save changes?")
      .setMessage("Keep your latest canvas edits.")
      .setPositiveButton("Save") { _, _ -> saveProject(false); finish() }
      .setNegativeButton("Discard") { _, _ -> finish() }
      .setNeutralButton("Cancel", null).show()
  }

  @Deprecated("Deprecated in Java")
  override fun onBackPressed() = closeStudio()

  companion object {
    const val EXTRA_PROJECT_ID = "project_id"
    private const val ACTION_APPLY_OVERLAY = "com.stream.prime.APPLY_OVERLAY"
  }
}

private data class StudioStackItem(
  val id: String,
  val name: String,
  val type: String,
  val locked: Boolean
)

private class StudioLayerOrderAdapter(
  private val items: MutableList<StudioStackItem>,
  private val onSelect: (String) -> Unit,
  private val onLock: (String) -> Unit,
  private val onDelete: (String) -> Unit
) : RecyclerView.Adapter<StudioLayerOrderAdapter.Holder>() {

  class Holder(view: View) : RecyclerView.ViewHolder(view) {
    val name: TextView = view.findViewById(R.id.text_layer_order_name)
    val type: TextView = view.findViewById(R.id.text_layer_order_type)
    val lock: Button = view.findViewById(R.id.btn_layer_order_lock)
    val delete: Button = view.findViewById(R.id.btn_layer_order_delete)
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
    android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_layer_order, parent, false)
  )

  override fun getItemCount(): Int = items.size

  override fun onBindViewHolder(holder: Holder, position: Int) {
    val item = items[position]
    holder.name.text = item.name
    holder.type.text = "${item.type}${if (item.locked) " · LOCKED" else ""}"
    holder.lock.text = if (item.locked) "Unlock" else "Lock"
    holder.delete.visibility = if (item.id == OverlayLayerOrdering.SCREEN_ID) View.GONE else View.VISIBLE
    holder.delete.isEnabled = !item.locked
    holder.delete.alpha = if (item.locked) 0.45f else 1f
    holder.itemView.setOnClickListener { onSelect(item.id) }
    holder.lock.setOnClickListener { onLock(item.id) }
    holder.delete.setOnClickListener { onDelete(item.id) }
  }

  fun move(from: Int, to: Int) {
    if (from !in items.indices || to !in items.indices || from == to) return
    val item = items.removeAt(from)
    items.add(to, item)
    notifyItemMoved(from, to)
  }

  fun itemIds(): List<String> = items.map { it.id }

  fun canMove(from: Int, to: Int): Boolean {
    return from in items.indices && to in items.indices && !items[from].locked && !items[to].locked
  }

  fun updateLock(id: String, locked: Boolean) {
    val index = items.indexOfFirst { it.id == id }
    if (index < 0) return
    items[index] = items[index].copy(locked = locked)
    notifyItemChanged(index)
  }

  fun remove(id: String) {
    val index = items.indexOfFirst { it.id == id }
    if (index < 0) return
    items.removeAt(index)
    notifyItemRemoved(index)
  }
}
