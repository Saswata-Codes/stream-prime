package com.stream.prime.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemBarInsetsTest {

  @Test
  fun `adds portrait status and navigation insets to existing padding`() {
    val base = EdgePadding(left = 4, top = 8, right = 4, bottom = 8)
    val bars = EdgePadding(left = 0, top = 72, right = 0, bottom = 96)

    assertEquals(
      EdgePadding(left = 4, top = 80, right = 4, bottom = 104),
      base.plus(bars)
    )
  }

  @Test
  fun `adds landscape navigation and cutout insets on physical sides`() {
    val base = EdgePadding(left = 0, top = 0, right = 0, bottom = 0)
    val bars = EdgePadding(left = 84, top = 0, right = 48, bottom = 0)

    assertEquals(EdgePadding(84, 0, 48, 0), base.plus(bars))
  }
}
