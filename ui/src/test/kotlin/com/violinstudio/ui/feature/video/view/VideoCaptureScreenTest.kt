package com.violinstudio.ui.feature.video.view

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.video.viewmodel.CameraPrompt
import com.violinstudio.ui.feature.video.viewmodel.VideoCaptureIntent
import com.violinstudio.ui.feature.video.viewmodel.VideoCaptureState
import com.violinstudio.ui.feature.video.viewmodel.VideoMessage
import com.violinstudio.ui.feature.video.viewmodel.VideoPhase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "es")
class VideoCaptureScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<VideoCaptureIntent>()
    private var records = 0
    private var back = 0

    private fun show(state: VideoCaptureState) {
        compose.setContent {
            ViolinStudioTheme { VideoCaptureScreen(state, { sent += it }, { records++ }, { back++ }) }
        }
    }

    @Test
    fun `en reposo explica el difuminado y deja grabar y volver`() {
        show(VideoCaptureState())
        compose.onNodeWithText("Las caras se difuminan en tu móvil", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(VIDEO_RECORD_TAG).performClick()
        compose.onNodeWithTag(VIDEO_BACK_TAG).performScrollTo().performClick()
        assertEquals(1, records)
        assertEquals(1, back)
    }

    @Test
    fun `grabando muestra el tiempo, parar y no deja volver`() {
        show(VideoCaptureState(phase = VideoPhase.RECORDING, elapsedMs = 65_000, maxDurationMs = 120_000))
        compose.onNodeWithText("Grabando 1:05 de 2:00").assertIsDisplayed()
        compose.onNodeWithTag(VIDEO_STOP_TAG).performClick()
        compose.onNodeWithTag(VIDEO_BACK_TAG).performScrollTo().assertIsNotEnabled()
        assertEquals(listOf<VideoCaptureIntent>(VideoCaptureIntent.Stop), sent)
    }

    @Test
    fun `difuminando lo dice y no ofrece grabar`() {
        show(VideoCaptureState(phase = VideoPhase.BLURRING))
        compose.onNodeWithTag(VIDEO_BLURRING_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(VIDEO_RECORD_TAG).assertDoesNotExist()
    }

    @Test
    fun `en modo demo cambia el titulo`() {
        show(VideoCaptureState(demo = true))
        compose.onNodeWithText("Vídeo de demostración").assertIsDisplayed()
    }

    @Test
    fun `los permisos bloqueados mandan a Ajustes y los avisos se cierran`() {
        show(VideoCaptureState(prompt = CameraPrompt.BLOCKED, message = VideoMessage.BLUR_FAILED))
        compose.onNodeWithText("Abrir ajustes").performScrollTo().performClick()
        compose.onNodeWithText("No se pudieron difuminar las caras", substring = true).performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Cerrar").performScrollTo().performClick()
        assertEquals(
            listOf<VideoCaptureIntent>(VideoCaptureIntent.OpenAppSettings, VideoCaptureIntent.DismissMessage),
            sent
        )
    }

    @Test
    fun `la explicacion de permisos vuelve a pedirlos`() {
        show(VideoCaptureState(prompt = CameraPrompt.RATIONALE))
        compose.onNodeWithText("Permitir").performScrollTo().performClick()
        assertEquals(listOf<VideoCaptureIntent>(VideoCaptureIntent.ConfirmRationale), sent)
    }
}
