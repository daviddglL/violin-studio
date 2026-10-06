package com.violinstudio.ui.feature.practice.view

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** REQ-PRA-11: la duracion y la fecha respetan el locale; el cronometro es neutro (H:MM:SS). */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class PracticeFormatTest {
    private val resources get() = ApplicationProvider.getApplicationContext<Application>().resources
    private val moment = Instant.parse("2026-01-05T15:30:00Z")

    @Test
    @Config(qualifiers = "es")
    fun `3725 s en espanol se escribe con h y min`() {
        assertEquals("1 h 2 min", resources.practiceDuration(3725))
    }

    @Test
    @Config(qualifiers = "en")
    fun `3725 s en ingles se escribe con hr y min`() {
        assertEquals("1 hr 2 min", resources.practiceDuration(3725))
    }

    @Test
    @Config(qualifiers = "es")
    fun `las duraciones cortas usan minutos y segundos`() {
        assertEquals("5 min", resources.practiceDuration(300))
        assertEquals("45 s", resources.practiceDuration(45))
        assertEquals("2 h 0 min", resources.practiceDuration(7200))
    }

    @Test
    fun `el cronometro usa H MM SS sin depender del locale`() {
        assertEquals("0:00", formatClock(0))
        assertEquals("2:00", formatClock(120))
        assertEquals("1:02:05", formatClock(3725))
        assertEquals("0:00", formatClock(-5))
    }

    @Test
    fun `la fecha cambia de formato segun el locale y respeta la zona`() {
        val es = formatPracticeDate(moment, Locale.forLanguageTag("es"), ZoneOffset.UTC)
        val en = formatPracticeDate(moment, Locale.forLanguageTag("en"), ZoneOffset.UTC)
        assertNotEquals(es, en)
        assertTrue(es, es.contains("15:30") && es.contains("2026"))
        assertTrue(en, en.contains("3:30") && en.contains("2026"))
        val shifted = formatPracticeDate(moment, Locale.forLanguageTag("es"), ZoneOffset.ofHours(2))
        assertTrue(shifted, shifted.contains("17:30"))
    }
}
