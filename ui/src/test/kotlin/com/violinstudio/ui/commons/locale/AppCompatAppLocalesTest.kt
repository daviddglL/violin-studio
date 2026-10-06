package com.violinstudio.ui.commons.locale

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
// Antes de Android 13 AppCompat guarda la lista por su cuenta; en 13+ exige una actividad AppCompat registrada.
@Config(application = Application::class, sdk = [32])
class AppCompatAppLocalesTest {
    private val locales = AppCompatAppLocales()

    @After
    fun restore() = locales.set(emptyList())

    @Test
    fun `lo aplicado se lee de vuelta y la lista vacia devuelve el idioma del sistema`() {
        assertEquals(emptyList<String>(), locales.current())
        locales.set(listOf("en"))
        assertEquals(listOf("en"), locales.current())
        locales.set(listOf("es"))
        assertEquals(listOf("es"), locales.current())
        locales.set(emptyList())
        assertEquals(emptyList<String>(), locales.current())
    }
}
