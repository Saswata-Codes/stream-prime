package com.stream.prime.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayProjectSelectionTest {

  @Test
  fun enablingProjectDisablesEveryOtherProjectAndMakesItActive() {
    val projects = listOf(
      project("first", enabled = true, updatedAt = 1L),
      project("second", enabled = false, updatedAt = 2L),
      project("third", enabled = true, updatedAt = 3L)
    )

    val result = enableOnly(projects, "second")

    assertEquals("second", result.activeId)
    assertTrue(result.projects.single { it.id == "second" }.config.enabled)
    assertFalse(result.projects.single { it.id == "first" }.config.enabled)
    assertFalse(result.projects.single { it.id == "third" }.config.enabled)
    assertEquals(1, result.projects.count { it.config.enabled })
  }

  @Test
  fun legacyMultiEnabledStateKeepsTheAlreadyActiveProject() {
    val projects = listOf(
      project("first", enabled = true, updatedAt = 1L),
      project("second", enabled = true, updatedAt = 2L)
    )

    val result = reconcileSelection(projects, "first")

    assertEquals("first", result.activeId)
    assertTrue(result.projects.single { it.id == "first" }.config.enabled)
    assertFalse(result.projects.single { it.id == "second" }.config.enabled)
  }

  @Test
  fun legacyEnabledProjectBecomesActiveWhenStoredActiveProjectIsOff() {
    val projects = listOf(
      project("first", enabled = false, updatedAt = 1L),
      project("second", enabled = true, updatedAt = 2L)
    )

    val result = reconcileSelection(projects, "first")

    assertEquals("second", result.activeId)
    assertTrue(result.projects.single { it.id == "second" }.config.enabled)
  }

  private fun project(id: String, enabled: Boolean, updatedAt: Long) = OverlayProject(
    id = id,
    name = id,
    config = OverlayConfig(enabled = enabled),
    updatedAt = updatedAt
  )
}
