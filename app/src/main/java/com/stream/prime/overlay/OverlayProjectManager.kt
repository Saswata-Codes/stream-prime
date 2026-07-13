package com.stream.prime.overlay

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Named overlay compositions. The active project is mirrored into OverlayManager for live rendering. */
object OverlayProjectManager {
  private const val PREFS = "overlay_projects"
  private const val KEY_PROJECTS = "projects_json"
  private const val KEY_ACTIVE = "active_project_id"
  private val gson = Gson()

  fun load(context: Context): List<OverlayProject> {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val json = prefs.getString(KEY_PROJECTS, null)
    if (!json.isNullOrBlank()) {
      runCatching {
        val type = object : TypeToken<List<OverlayProject>>() {}.type
        val projects: List<OverlayProject> = gson.fromJson(json, type)
        if (projects.isNotEmpty()) return projects
      }
    }
    val migrated = OverlayProject(
      id = newId(),
      name = "My Overlay",
      config = OverlayManager.load(context)
    )
    saveAll(context, listOf(migrated), migrated.id)
    return listOf(migrated)
  }

  fun activeId(context: Context): String {
    val projects = load(context)
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .getString(KEY_ACTIVE, projects.first().id) ?: projects.first().id
  }

  fun get(context: Context, id: String): OverlayProject? = load(context).firstOrNull { it.id == id }

  fun create(context: Context, name: String = "Overlay ${load(context).size + 1}"): OverlayProject {
    val project = OverlayProject(id = newId(), name = name, config = defaultConfig(context))
    val projects = load(context) + project
    saveAll(context, projects, activeId(context))
    return project
  }

  fun save(context: Context, project: OverlayProject) {
    val projects = load(context).toMutableList()
    val updated = project.copy(updatedAt = System.currentTimeMillis())
    val index = projects.indexOfFirst { it.id == project.id }
    if (index >= 0) projects[index] = updated else projects.add(updated)
    val active = activeId(context)
    saveAll(context, projects, active)
    if (active == project.id) OverlayManager.save(context, updated.config)
  }

  fun activate(context: Context, id: String) {
    val project = get(context, id) ?: return
    saveAll(context, load(context), id)
    OverlayManager.save(context, project.config)
  }

  fun delete(context: Context, id: String) {
    val remaining = load(context).filterNot { it.id == id }.toMutableList()
    if (remaining.isEmpty()) remaining += OverlayProject(id = newId(), name = "My Overlay", config = defaultConfig(context))
    val active = if (activeId(context) == id) remaining.first().id else activeId(context)
    saveAll(context, remaining, active)
    get(context, active)?.let { OverlayManager.save(context, it.config) }
  }

  private fun saveAll(context: Context, projects: List<OverlayProject>, activeId: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
      .putString(KEY_PROJECTS, gson.toJson(projects))
      .putString(KEY_ACTIVE, activeId)
      .apply()
  }

  private fun defaultConfig(context: Context): OverlayConfig {
    val current = OverlayManager.load(context)
    return current.copy(enabled = true, layers = emptyList())
  }

  private fun newId(): String = "overlay_${System.currentTimeMillis()}_${(100..999).random()}"
}
