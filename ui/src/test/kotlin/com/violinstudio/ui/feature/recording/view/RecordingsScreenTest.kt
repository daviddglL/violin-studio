package com.violinstudio.ui.feature.recording.view

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.recording.model.FailReason
import com.violinstudio.domain.feature.recording.model.PlaybackState
import com.violinstudio.domain.feature.recording.model.QueueState
import com.violinstudio.domain.feature.recording.model.QueuedRecording
import com.violinstudio.domain.feature.recording.model.RecordedTake
import com.violinstudio.domain.feature.recording.model.RecordingAccess
import com.violinstudio.domain.feature.recording.model.RecordingSummary
import com.violinstudio.domain.feature.recording.model.Review
import com.violinstudio.domain.feature.recording.model.Verdict
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.recording.viewmodel.MicPrompt
import com.violinstudio.ui.feature.recording.viewmodel.RecordingsIntent
import com.violinstudio.ui.feature.recording.viewmodel.RecordingsMessage
import com.violinstudio.ui.feature.recording.viewmodel.RecordingsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "es")
class RecordingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<RecordingsIntent>()
    private var records = 0
    private var back = 0
    private val allowed = RecordingAccess.Allowed(60_000, quotaFull = false)

    private fun show(state: RecordingsState) {
        compose.setContent {
            ViolinStudioTheme { RecordingsScreen(state, { sent += it }, { records++ }, { back++ }) }
        }
    }

    @Test
    fun `con acceso se puede grabar y volver`() {
        show(RecordingsState(access = allowed))
        compose.onNodeWithText("Puedes grabar hasta 1:00 por toma. Mantén la pantalla abierta mientras grabas.")
            .assertIsDisplayed()
        compose.onNodeWithTag(RECORDINGS_RECORD_TAG).performClick()
        compose.onNodeWithText("Aún no tienes grabaciones.").assertIsDisplayed()
        compose.onNodeWithTag(RECORDINGS_BACK_TAG).performScrollTo().performClick()
        assertEquals(1, records)
        assertEquals(1, back)
    }

    @Test
    fun `grabando muestra el tiempo y parar`() {
        show(RecordingsState(access = allowed, elapsedMs = 65_000))
        compose.onNodeWithText("Grabando 1:05 de 1:00").assertIsDisplayed()
        compose.onNodeWithTag(RECORDINGS_STOP_TAG).performClick()
        assertEquals(listOf<RecordingsIntent>(RecordingsIntent.Stop), sent)
    }

    @Test
    fun `sin consentimiento se pide, y a un menor se le remite al tutor`() {
        show(RecordingsState(access = RecordingAccess.NeedsConsent(selfGrant = true, version = 1)))
        compose.onNodeWithTag(RECORDINGS_GRANT_TAG).performClick()
        assertEquals(listOf<RecordingsIntent>(RecordingsIntent.GrantConsent), sent)
    }

    @Test
    fun `un menor sin consentimiento no ve el boton de aceptar`() {
        show(RecordingsState(access = RecordingAccess.NeedsConsent(selfGrant = false, version = 1)))
        compose.onNodeWithText(
            "Para grabar necesitas la autorización de tu madre, padre o tutor. Pídeles que la concedan."
        ).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTagCount(RECORDINGS_GRANT_TAG) == 0)
    }

    @Test
    fun `desactivado lo explica`() {
        show(RecordingsState(access = RecordingAccess.Disabled))
        compose.onNodeWithText("Las grabaciones aún no están disponibles en tu cuenta.").assertIsDisplayed()
    }

    @Test
    fun `fallo al comprobar ofrece reintentar`() {
        show(RecordingsState(accessFailed = true))
        compose.onNodeWithText("Reintentar").performClick()
        assertEquals(listOf<RecordingsIntent>(RecordingsIntent.Refresh), sent)
    }

    @Test
    fun `con el cupo lleno no hay boton de grabar`() {
        show(RecordingsState(access = allowed.copy(quotaFull = true)))
        compose.onNodeWithText("Has llegado al máximo de 20 grabaciones. Borra alguna para grabar otra.")
            .assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTagCount(RECORDINGS_RECORD_TAG) == 0)
    }

    @Test
    fun `una pendiente fallida se reintenta o descarta y una grabacion se borra`() {
        val failed = QueuedRecording(
            RecordedTake("q1", "u1", "/x/q1.m4a", 5_000, 1, 0),
            state = QueueState.FAILED,
            lastError = FailReason.QUOTA
        )
        val ready = RecordingSummary("r1", null, 12_000, ready = true, isDemo = false)
        show(RecordingsState(access = allowed, pending = listOf(failed), recordings = listOf(ready)))
        compose.onNodeWithText("0:05 · No se pudo subir: máximo de grabaciones alcanzado").assertIsDisplayed()
        compose.onNodeWithText("Reintentar").performClick()
        compose.onNodeWithText("Descartar").performClick()
        compose.onNodeWithText("0:12 · Lista").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Borrar").performScrollTo().performClick()
        compose.onNodeWithTag(RECORDINGS_REVOKE_TAG).performScrollTo().performClick()
        assertEquals(
            listOf(
                RecordingsIntent.Retry("q1"),
                RecordingsIntent.Discard("q1"),
                RecordingsIntent.AskDelete("r1"),
                RecordingsIntent.AskRevoke
            ),
            sent
        )
    }

    @Test
    fun `los dialogos confirman o cancelan`() {
        show(RecordingsState(access = allowed, confirmRevoke = true))
        compose.onNodeWithText("Retirar y borrar").performClick()
        compose.onNodeWithText("Cancelar").performClick()
        assertEquals(listOf(RecordingsIntent.ConfirmRevoke, RecordingsIntent.CancelRevoke), sent)
    }

    @Test
    fun `permiso del micro y avisos`() {
        show(RecordingsState(access = allowed, mic = MicPrompt.BLOCKED, message = RecordingsMessage.TOO_SHORT))
        compose.onNodeWithText("Abrir ajustes").performClick()
        compose.onNodeWithText("La grabación es demasiado corta y no se ha guardado.").assertIsDisplayed()
        compose.onNodeWithText("Cerrar").performClick()
        assertEquals(listOf(RecordingsIntent.OpenAppSettings, RecordingsIntent.DismissMessage), sent)
    }

    @Test
    fun `el reloj usa minutos y segundos`() {
        assertEquals("0:00", clock(0))
        assertEquals("0:59", clock(59_999))
        assertEquals("2:00", clock(120_000))
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    @Test
    fun `una grabacion lista se escucha y muestra sus valoraciones`() {
        val ready = RecordingSummary("r0", null, 5_000, ready = true, isDemo = false, objectPath = "p")
        val review = Review(Verdict.NEEDS_WORK, "Cuida la afinación", 1L)
        show(
            RecordingsState(
                access = allowed,
                recordings = listOf(ready),
                playback = PlaybackState.Failed("r0"),
                reviewsFor = "r0",
                reviews = listOf(review, Review(Verdict.APPROVED, "", null, fromTeacher = false))
            )
        )
        compose.onNodeWithText("No se pudo reproducir. Revisa la conexión.").assertIsDisplayed()
        compose.onNodeWithText("Tu profesor: Hay que trabajarla").assertIsDisplayed()
        compose.onNodeWithText("Cuida la afinación").assertIsDisplayed()
        compose.onNodeWithText("Un antiguo profesor: Bien hecho").assertIsDisplayed()
        compose.onNodeWithTag(playTag("r0")).performClick()
        compose.onNodeWithTag(reviewsToggleTag("r0")).performClick()
        assertEquals(listOf(RecordingsIntent.Play("r0"), RecordingsIntent.ToggleReviews("r0")), sent)
    }

    @Test
    fun `sonando se puede parar y sin valoraciones se dice`() {
        val ready = RecordingSummary("r0", null, 5_000, ready = true, isDemo = false, objectPath = "p")
        show(
            RecordingsState(
                access = allowed,
                recordings = listOf(ready),
                playback = PlaybackState.Playing("r0"),
                reviewsFor = "r0",
                reviews = emptyList()
            )
        )
        compose.onNodeWithText("Tu profesor aún no la ha valorado.").assertIsDisplayed()
        compose.onNodeWithTag(stopPlaybackTag("r0")).performClick()
        assertEquals(listOf<RecordingsIntent>(RecordingsIntent.StopPlayback), sent)
    }
}
