package com.stream.prime.overlay

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.stream.prime.settings.SettingsManager
import kotlin.math.abs

/** Named overlay compositions. The active project is mirrored into OverlayManager for live rendering. */
object OverlayProjectManager {
  private const val PREFS = "overlay_projects"
  private const val KEY_PROJECTS = "projects_json"
  private const val KEY_ACTIVE = "active_project_id"
  private const val KEY_DEFAULT_LAYOUT_VERSION = "default_layout_version"
  private const val DEFAULT_LAYOUT_VERSION = 2
  private val gson = Gson()

  fun load(context: Context): List<OverlayProject> {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val json = prefs.getString(KEY_PROJECTS, null)
    if (!json.isNullOrBlank()) {
      runCatching {
        val type = object : TypeToken<List<OverlayProject>>() {}.type
        val storedProjects: List<OverlayProject> = gson.fromJson(json, type)
        if (storedProjects.isNotEmpty()) {
          val needsDefaultRepair = prefs.getInt(KEY_DEFAULT_LAYOUT_VERSION, 0) < DEFAULT_LAYOUT_VERSION
          val repairedProjects = if (needsDefaultRepair) {
            repairRestoredBlankDefaultProject(context, storedProjects)
          } else {
            storedProjects
          }
          val projects = alignDefaultLayoutsToCurrentCanvas(context, repairedProjects)
          val storedActiveId = prefs.getString(KEY_ACTIVE, null)
          val selection = reconcileSelection(projects, storedActiveId)
          if (
            needsDefaultRepair ||
            selection.projects != storedProjects ||
            selection.activeId != storedActiveId
          ) {
            saveAll(context, selection.projects, selection.activeId)
            selection.projects.firstOrNull { it.id == selection.activeId }
              ?.let { OverlayManager.save(context, it.config) }
          }
          return selection.projects
        }
      }
    }
    val current = OverlayManager.load(context)
    val migrated = OverlayProject(
      id = newId(),
      name = "My Overlay",
      config = if (current.layers.isEmpty()) defaultConfig(context, current) else current
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
    val previousActive = activeId(context)
    val selection = if (updated.config.enabled) {
      enableOnly(projects, updated.id)
    } else {
      OverlayProjectSelection(projects, previousActive)
    }
    saveAll(context, selection.projects, selection.activeId)
    selection.projects.firstOrNull { it.id == selection.activeId }
      ?.let { OverlayManager.save(context, it.config) }
  }

  fun activate(context: Context, id: String) {
    setEnabled(context, id, true)
  }

  /** Enabling a project makes it live and atomically disables every other project. */
  fun setEnabled(context: Context, id: String, enabled: Boolean) {
    val projects = load(context)
    val target = projects.firstOrNull { it.id == id } ?: return
    val now = System.currentTimeMillis()
    val selection = if (enabled) {
      enableOnly(
        projects.map { project ->
          if (project.id == id) {
            project.copy(config = project.config.copy(enabled = true), updatedAt = now)
          } else {
            project
          }
        },
        id
      )
    } else {
      OverlayProjectSelection(
        projects.map { project ->
          if (project.id == id) {
            project.copy(config = project.config.copy(enabled = false), updatedAt = now)
          } else {
            project
          }
        },
        activeId(context)
      )
    }
    saveAll(context, selection.projects, selection.activeId)
    val live = selection.projects.firstOrNull { it.id == selection.activeId }
      ?: target.copy(config = target.config.copy(enabled = false))
    OverlayManager.save(context, live.config)
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
      .putInt(KEY_DEFAULT_LAYOUT_VERSION, DEFAULT_LAYOUT_VERSION)
      .apply()
  }

  private fun defaultConfig(
    context: Context,
    current: OverlayConfig = OverlayManager.load(context)
  ): OverlayConfig {
    return freshOverlayConfig(
      current,
      OverlayManager.defaultScreenLayout(
        context,
        ScreenPreset.PORTRAIT,
        current.portraitScreenFitMode
      ),
      OverlayManager.defaultScreenLayout(
        context,
        ScreenPreset.LANDSCAPE,
        current.landscapeScreenFitMode
      )
    )
  }

  private fun repairRestoredBlankDefaultProject(
    context: Context,
    projects: List<OverlayProject>
  ): List<OverlayProject> {
    val project = projects.singleOrNull() ?: return projects
    if (project.name != "My Overlay" || project.config.layers.isNotEmpty()) return projects
    return listOf(
      project.copy(
        config = resetScreenLayouts(
          project.config,
          OverlayManager.defaultScreenLayout(
            context,
            ScreenPreset.PORTRAIT,
            project.config.portraitScreenFitMode
          ),
          OverlayManager.defaultScreenLayout(
            context,
            ScreenPreset.LANDSCAPE,
            project.config.landscapeScreenFitMode
          )
        )
      )
    )
  }

  /**
   * A reset/default layout is relative to the output canvas aspect. If the streaming mode
   * changes, carry that default intent to the new canvas while preserving every layout the
   * user has actually moved, zoomed or rotated.
   */
  private fun alignDefaultLayoutsToCurrentCanvas(
    context: Context,
    projects: List<OverlayProject>
  ): List<OverlayProject> {
    val verticalAspect = SettingsManager.getVerticalWidth(context).coerceAtLeast(1).toFloat() /
      SettingsManager.getVerticalHeight(context).coerceAtLeast(1).toFloat()
    val landscapeAspect = SettingsManager.getLandscapeWidth(context).coerceAtLeast(1).toFloat() /
      SettingsManager.getLandscapeHeight(context).coerceAtLeast(1).toFloat()
    val targetAspect = if (SettingsManager.getStreamingMode(context) == "Vertical") {
      verticalAspect
    } else {
      landscapeAspect
    }
    val captureLandscapeAspect = CaptureDisplayAspect.landscapeAspect(context)

    return projects.map { project ->
      val config = project.config
      val knownPortraitDefaults = listOf(verticalAspect, landscapeAspect).map { canvasAspect ->
        CaptureOrientation.defaultLayout(
          canvasAspect,
          ScreenPreset.PORTRAIT,
          config.portraitScreenFitMode,
          captureLandscapeAspect
        )
      }
      val knownLandscapeDefaults = listOf(verticalAspect, landscapeAspect).map { canvasAspect ->
        CaptureOrientation.defaultLayout(
          canvasAspect,
          ScreenPreset.LANDSCAPE,
          config.landscapeScreenFitMode,
          captureLandscapeAspect
        )
      }
      val alignedConfig = recenterLayoutsStillAtDefaults(
        current = config,
        knownPortraitDefaults = knownPortraitDefaults,
        knownLandscapeDefaults = knownLandscapeDefaults,
        targetPortraitDefault = CaptureOrientation.defaultLayout(
          targetAspect,
          ScreenPreset.PORTRAIT,
          config.portraitScreenFitMode,
          captureLandscapeAspect
        ),
        targetLandscapeDefault = CaptureOrientation.defaultLayout(
          targetAspect,
          ScreenPreset.LANDSCAPE,
          config.landscapeScreenFitMode,
          captureLandscapeAspect
        )
      )
      if (alignedConfig == config) project else project.copy(config = alignedConfig)
    }
  }

  private fun newId(): String = "overlay_${System.currentTimeMillis()}_${(100..999).random()}"
}

internal data class OverlayProjectSelection(
  val projects: List<OverlayProject>,
  val activeId: String
)

/** Repairs legacy multi-enabled state, preferring the already-live project when possible. */
internal fun reconcileSelection(
  projects: List<OverlayProject>,
  preferredActiveId: String?
): OverlayProjectSelection {
  require(projects.isNotEmpty())
  val validPreferredId = preferredActiveId?.takeIf { id -> projects.any { it.id == id } }
  val enabledProjects = projects.filter { it.config.enabled }
  if (enabledProjects.isEmpty()) {
    return OverlayProjectSelection(projects, validPreferredId ?: projects.first().id)
  }

  val winner = enabledProjects.firstOrNull { it.id == validPreferredId }
    ?: enabledProjects.maxBy { it.updatedAt }
  return enableOnly(projects, winner.id)
}

internal fun enableOnly(projects: List<OverlayProject>, enabledId: String): OverlayProjectSelection {
  require(projects.any { it.id == enabledId })
  return OverlayProjectSelection(
    projects = projects.map { project ->
      val shouldEnable = project.id == enabledId
      if (project.config.enabled == shouldEnable) project
      else project.copy(config = project.config.copy(enabled = shouldEnable))
    },
    activeId = enabledId
  )
}

internal fun freshOverlayConfig(
  current: OverlayConfig,
  portraitResetLayout: ScreenLayout,
  landscapeResetLayout: ScreenLayout
): OverlayConfig = resetScreenLayouts(
  current,
  portraitResetLayout,
  landscapeResetLayout
).copy(
  enabled = false,
  layers = emptyList(),
  portraitScreenLocked = false,
  landscapeScreenLocked = false,
  screenLayerPosition = 0
)

internal fun resetScreenLayouts(
  current: OverlayConfig,
  portraitResetLayout: ScreenLayout,
  landscapeResetLayout: ScreenLayout
): OverlayConfig = current.copy(
  screenLayout = portraitResetLayout,
  landscapeScreenLayout = landscapeResetLayout
)

internal fun recenterLayoutsStillAtDefaults(
  current: OverlayConfig,
  knownPortraitDefaults: List<ScreenLayout>,
  knownLandscapeDefaults: List<ScreenLayout>,
  targetPortraitDefault: ScreenLayout,
  targetLandscapeDefault: ScreenLayout
): OverlayConfig = current.copy(
  screenLayout = if (knownPortraitDefaults.any { current.screenLayout.nearlyEquals(it) }) {
    targetPortraitDefault
  } else {
    current.screenLayout
  },
  landscapeScreenLayout = if (knownLandscapeDefaults.any {
      current.landscapeScreenLayout.nearlyEquals(it)
    }) {
    targetLandscapeDefault
  } else {
    current.landscapeScreenLayout
  }
)

private fun ScreenLayout.nearlyEquals(other: ScreenLayout, tolerance: Float = 0.05f): Boolean =
  abs(positionXPct - other.positionXPct) <= tolerance &&
    abs(positionYPct - other.positionYPct) <= tolerance &&
    abs(scalePct - other.scalePct) <= tolerance &&
    abs(rotationDegrees - other.rotationDegrees) <= tolerance
