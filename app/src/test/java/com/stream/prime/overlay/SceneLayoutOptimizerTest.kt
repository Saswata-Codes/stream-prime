package com.stream.prime.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SceneLayoutOptimizerTest {

  @Test
  fun `rotated bounds preserve center and swap dimensions at 90 degrees`() {
    val rotated = SceneLayoutOptimizer.rotatedBounds(SceneBounds(10f, 20f, 50f, 40f), 90f)

    assertEquals(30f, rotated.centerX, 0.001f)
    assertEquals(30f, rotated.centerY, 0.001f)
    assertEquals(20f, rotated.width, 0.001f)
    assertEquals(40f, rotated.height, 0.001f)
  }

  @Test
  fun `fit centers scene and preserves uniform scale`() {
    val bounds = SceneBounds(20f, 10f, 80f, 90f)
    val result = SceneLayoutOptimizer.fit(bounds, safeMarginPct = 2f)
    assertNotNull(result)
    val transform = result!!

    assertEquals(1.2f, transform.scale, 0.001f)
    assertEquals(50f, transform.mapX(bounds.centerX), 0.001f)
    assertEquals(50f, transform.mapY(bounds.centerY), 0.001f)
    assertEquals(2f, transform.mapY(bounds.top), 0.001f)
    assertEquals(98f, transform.mapY(bounds.bottom), 0.001f)
  }

  @Test
  fun `fit recenters an offset scene and honors scale cap`() {
    val bounds = SceneBounds(10f, 20f, 30f, 40f)
    val result = SceneLayoutOptimizer.fit(bounds, safeMarginPct = 3f, maxScale = 2f)
    assertNotNull(result)
    val transform = result!!

    assertEquals(2f, transform.scale, 0.001f)
    assertEquals(30f, transform.mapX(bounds.left), 0.001f)
    assertEquals(70f, transform.mapX(bounds.right), 0.001f)
    assertEquals(30f, transform.mapY(bounds.top), 0.001f)
    assertEquals(70f, transform.mapY(bounds.bottom), 0.001f)
  }
}
