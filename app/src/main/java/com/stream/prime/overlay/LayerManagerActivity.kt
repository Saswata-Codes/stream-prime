package com.stream.prime.overlay

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.stream.prime.R
import com.stream.prime.screen.ScreenService

/** Overlay library: create compositions, choose the live one, then edit it in Studio. */
class LayerManagerActivity : AppCompatActivity() {
  private lateinit var list: RecyclerView
  private var projects = emptyList<OverlayProject>()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_layer_manager)
    list = findViewById(R.id.recycler_overlay_projects)
    list.layoutManager = LinearLayoutManager(this)
    findViewById<View>(R.id.btn_close_library).setOnClickListener { finish() }
    findViewById<View>(R.id.btn_create_overlay).setOnClickListener {
      openStudio(OverlayProjectManager.create(this).id)
    }
  }

  override fun onResume() {
    super.onResume()
    refresh()
  }

  private fun refresh() {
    projects = OverlayProjectManager.load(this)
    list.adapter = ProjectAdapter(
      projects,
      OverlayProjectManager.activeId(this),
      onEdit = { openStudio(it.id) },
      onActivate = {
        OverlayProjectManager.activate(this, it.id)
        applyLiveOverlay()
        refresh()
        Toast.makeText(this, "${it.name} is live", Toast.LENGTH_SHORT).show()
      },
      onEnabled = { project, enabled ->
        OverlayProjectManager.setEnabled(this, project.id, enabled)
        applyLiveOverlay()
        refresh()
        Toast.makeText(
          this,
          if (enabled) "${project.name} is live" else "Overlay turned off",
          Toast.LENGTH_SHORT
        ).show()
      },
      onDelete = { confirmDelete(it) }
    )
  }

  private fun openStudio(id: String) {
    startActivity(Intent(this, OverlayStudioActivity::class.java).putExtra(OverlayStudioActivity.EXTRA_PROJECT_ID, id))
  }

  private fun confirmDelete(project: OverlayProject) {
    AlertDialog.Builder(this)
      .setTitle("Delete ${project.name}?")
      .setMessage("This overlay composition cannot be restored.")
      .setPositiveButton("Delete") { _, _ ->
        OverlayProjectManager.delete(this, project.id)
        applyLiveOverlay()
        refresh()
      }
      .setNegativeButton("Cancel", null).show()
  }

  private fun applyLiveOverlay() {
    runCatching {
      ScreenService.INSTANCE?.applyConfiguredOverlay()
    }
  }
}

private class ProjectAdapter(
  private val items: List<OverlayProject>,
  private val activeId: String,
  private val onEdit: (OverlayProject) -> Unit,
  private val onActivate: (OverlayProject) -> Unit,
  private val onEnabled: (OverlayProject, Boolean) -> Unit,
  private val onDelete: (OverlayProject) -> Unit
) : RecyclerView.Adapter<ProjectAdapter.Holder>() {

  class Holder(view: View) : RecyclerView.ViewHolder(view) {
    val name: TextView = view.findViewById(R.id.text_overlay_project_name)
    val summary: TextView = view.findViewById(R.id.text_overlay_project_summary)
    val enabled: Switch = view.findViewById(R.id.switch_overlay_project_enabled)
    val live: TextView = view.findViewById(R.id.text_overlay_project_live)
    val edit: Button = view.findViewById(R.id.btn_edit_overlay_project)
    val activate: Button = view.findViewById(R.id.btn_activate_overlay_project)
    val delete: Button = view.findViewById(R.id.btn_delete_overlay_project)
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
    LayoutInflater.from(parent.context).inflate(R.layout.item_overlay_project, parent, false)
  )

  override fun getItemCount() = items.size

  override fun onBindViewHolder(holder: Holder, position: Int) {
    val project = items[position]
    val active = project.id == activeId
    val images = project.config.layers.count { it.type != OverlayLayerType.TEXT }
    val texts = project.config.layers.count { it.type == OverlayLayerType.TEXT }
    holder.name.text = project.name
    holder.summary.text = "$images images · $texts text · ${project.config.canvasTheme.displayName}"
    val live = active && project.config.enabled
    holder.live.visibility = if (live) View.VISIBLE else View.GONE
    holder.activate.visibility = if (live) View.GONE else View.VISIBLE
    holder.enabled.setOnCheckedChangeListener(null)
    holder.enabled.isChecked = project.config.enabled
    holder.enabled.setOnCheckedChangeListener { _, checked -> onEnabled(project, checked) }
    holder.edit.setOnClickListener { onEdit(project) }
    holder.activate.setOnClickListener { onActivate(project) }
    holder.delete.setOnClickListener { onDelete(project) }
    holder.itemView.setOnClickListener { onEdit(project) }
  }
}
