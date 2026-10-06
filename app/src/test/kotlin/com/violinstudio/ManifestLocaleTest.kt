package com.violinstudio

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.appcompat.app.AppLocalesMetadataHolderService
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Idioma por app: en Android 13+ lo gestiona el sistema (`localeConfig`, generado por AGP); antes, AppCompat guarda
 * el idioma con su servicio de metadatos y `autoStoreLocales`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ManifestLocaleTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun `AppCompat guarda el idioma elegido antes de Android 13`() {
        val service = context.packageManager.getServiceInfo(
            ComponentName(context, AppLocalesMetadataHolderService::class.java),
            PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS
        )
        assertTrue(service.metaData.getBoolean("autoStoreLocales"))
        assertEquals(false, service.exported)
    }

    @Test
    fun `la ventana usa el fondo oscuro de la app para no destellar en blanco`() {
        val theme = context.resources.newTheme()
        theme.applyStyle(R.style.Theme_ViolinStudio, true)
        val value = android.util.TypedValue()
        assertTrue(theme.resolveAttribute(android.R.attr.windowBackground, value, true))
        assertEquals(0xFF1C1B1F.toInt(), value.data)
    }
}
