package com.stream.prime.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayLayerOrderingTest {
  @Test
  fun topFirstPanelOrderBecomesNormalizedRenderOrder() {
    val layers = listOf(
      OverlayLayer(id = "a", zIndex = 0),
      OverlayLayer(id = "b", zIndex = 1),
      OverlayLayer(id = "c", zIndex = 2)
    )

    val reordered = OverlayLayerOrdering.fromTopFirst(layers, listOf("a", "c", "b"))

    assertEquals(listOf("b", "c", "a"), reordered.sortedBy { it.zIndex }.map { it.id })
    assertEquals(listOf(0, 1, 2), reordered.sortedBy { it.zIndex }.map { it.zIndex })
  }

  @Test
  fun reorderingPreservesLayerLock() {
    val layers = listOf(OverlayLayer(id = "locked", locked = true), OverlayLayer(id = "free"))

    val reordered = OverlayLayerOrdering.fromTopFirst(layers, listOf("free", "locked"))

    assertTrue(reordered.first { it.id == "locked" }.locked)
  }

  @Test
  fun screenParticipatesInTopFirstStackOrder() {
    val layers = listOf(
      OverlayLayer(id = "bottom", zIndex = 0),
      OverlayLayer(id = "middle", zIndex = 1),
      OverlayLayer(id = "top", zIndex = 2)
    )

    val ids = OverlayLayerOrdering.topFirstWithScreen(layers, screenPosition = 1)

    assertEquals(listOf("top", "middle", OverlayLayerOrdering.SCREEN_ID, "bottom"), ids)
  }

  @Test
  fun unifiedStackOrderRoundTripsScreenPosition() {
    val layers = listOf(OverlayLayer(id = "a"), OverlayLayer(id = "b"), OverlayLayer(id = "c"))
    val topFirst = listOf("c", OverlayLayerOrdering.SCREEN_ID, "a", "b")

    val stack = OverlayLayerOrdering.fromTopFirstWithScreen(layers, topFirst)!!

    assertEquals(2, stack.screenPosition)
    assertEquals(topFirst, OverlayLayerOrdering.topFirstWithScreen(stack.layers, stack.screenPosition))
  }

  @Test
  fun splitAtScreenReturnsBackgroundAndForegroundLayers() {
    val layers = listOf(
      OverlayLayer(id = "a", zIndex = 0),
      OverlayLayer(id = "b", zIndex = 1),
      OverlayLayer(id = "c", zIndex = 2)
    )

    val (below, above) = OverlayLayerOrdering.splitAtScreen(layers, 2)

    assertEquals(listOf("a", "b"), below.map { it.id })
    assertEquals(listOf("c"), above.map { it.id })
  }
}
