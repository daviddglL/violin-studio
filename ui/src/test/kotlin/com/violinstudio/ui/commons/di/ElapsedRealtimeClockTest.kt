package com.violinstudio.ui.commons.di

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowSystemClock

@RunWith(AndroidJUnit4::class)
class ElapsedRealtimeClockTest {
    @Test
    fun `el reloj sigue el tiempo desde el arranque y avanza con el`() {
        val clock = ElapsedRealtimeClock()
        val before = clock.millis()
        assertEquals(SystemClock.elapsedRealtime(), before)
        ShadowSystemClock.advanceBy(Duration.ofMillis(250))
        assertEquals(before + 250, clock.millis())
    }
}
