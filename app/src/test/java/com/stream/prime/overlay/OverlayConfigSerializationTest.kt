package com.stream.prime.overlay

import com.google.gson.Gson
import org.junit.Assert.assertEquals
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
}
