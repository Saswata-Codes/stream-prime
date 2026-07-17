package com.stream.prime.overlay

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayConfigSerializationTest {

  @Test
  fun oldProjectWithoutFramePresetDefaultsToPortrait() {
    val config = Gson().fromJson(
      """{"enabled":true,"layers":[]}""",
      OverlayConfig::class.java
    )

    assertEquals(ScreenPreset.PORTRAIT, config.screenPreset)
  }

  @Test
  fun selectedLandscapeFrameSurvivesProjectJsonRoundTrip() {
    val gson = Gson()
    val restored = gson.fromJson(
      gson.toJson(OverlayConfig(screenPreset = ScreenPreset.LANDSCAPE)),
      OverlayConfig::class.java
    )

    assertEquals(ScreenPreset.LANDSCAPE, restored.screenPreset)
  }

  @Test
  fun animatedGifFlagSurvivesProjectJsonRoundTrip() {
    val gson = Gson()
    val restored = gson.fromJson(
      gson.toJson(OverlayConfig(layers = listOf(OverlayLayer(id = "gif", animated = true)))),
      OverlayConfig::class.java
    )

    assertTrue(restored.layers.single().animated)
  }

  @Test
  fun oldImageLayerWithoutAnimatedFlagRemainsStatic() {
    val restored = Gson().fromJson(
      """{"layers":[{"id":"old","imageUri":"content://image"}]}""",
      OverlayConfig::class.java
    )

    assertFalse(restored.layers.single().animated)
  }
}
