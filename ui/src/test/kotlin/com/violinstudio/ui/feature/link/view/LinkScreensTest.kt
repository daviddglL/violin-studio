package com.violinstudio.ui.feature.link.view

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.link.model.TeacherLink
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.ui.R
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.link.viewmodel.MyTeacherIntent
import com.violinstudio.ui.feature.link.viewmodel.MyTeacherState
import com.violinstudio.ui.feature.link.viewmodel.RedeemCodeIntent
import com.violinstudio.ui.feature.link.viewmodel.RedeemCodeState
import com.violinstudio.ui.feature.link.viewmodel.RedeemError
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class LinkScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val redeemIntents = mutableListOf<RedeemCodeIntent>()
    private val teacherIntents = mutableListOf<MyTeacherIntent>()
    private val link =
        TeacherLink("t1", "s1", "Marta", "Ana", Instrument.VIOLIN, 1, Instant.parse("2026-10-01T12:00:00Z"))

    private fun showRedeem(state: RedeemCodeState) = compose.setContent {
        ViolinStudioTheme { RedeemCodeScreen(state, { redeemIntents += it }, onBack = {}) }
    }

    private fun showTeacher(state: MyTeacherState) = compose.setContent {
        ViolinStudioTheme { MyTeacherScreen(state, { teacherIntents += it }, onBack = {}) }
    }

    @Test
    fun `el aviso es visible y el boton esta bloqueado hasta aceptarlo`() {
        showRedeem(RedeemCodeState(code = "ABCD2345"))
        compose.onNodeWithTag(REDEEM_NOTICE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(REDEEM_SUBMIT_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `aceptado y con codigo el boton envia`() {
        showRedeem(RedeemCodeState(code = "ABCD2345", accepted = true))
        compose.onNodeWithTag(REDEEM_SUBMIT_TAG).performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf<RedeemCodeIntent>(RedeemCodeIntent.Submit), redeemIntents)
    }

    @Test
    fun `marcar la casilla emite el intent`() {
        showRedeem(RedeemCodeState(code = "ABCD2345"))
        compose.onNodeWithTag(REDEEM_CHECKBOX_TAG).performScrollTo().performClick()
        assertEquals(listOf<RedeemCodeIntent>(RedeemCodeIntent.AcceptedChanged(true)), redeemIntents)
    }

    @Test
    fun `rate limited muestra los minutos de espera`() {
        showRedeem(RedeemCodeState(code = "ABCD2345", accepted = true, error = RedeemError.RateLimited(7)))
        compose.onNodeWithTag(REDEEM_ERROR_TAG).performScrollTo()
            .assertTextContains(context.getString(R.string.link_error_rate_limited, 7))
    }

    @Test
    fun `cancelar el dialogo de desvincular no confirma`() {
        showTeacher(MyTeacherState(isLoading = false, teacher = link, confirmingUnlink = true))
        compose.onNodeWithTag(UNLINK_DIALOG_TAG).assertIsDisplayed()
        compose.onNodeWithTag(UNLINK_CANCEL_TAG).performClick()
        assertEquals(listOf<MyTeacherIntent>(MyTeacherIntent.CancelUnlink), teacherIntents)
    }

    @Test
    fun `confirmar el dialogo emite la confirmacion`() {
        showTeacher(MyTeacherState(isLoading = false, teacher = link, confirmingUnlink = true))
        compose.onNodeWithTag(UNLINK_CONFIRM_TAG).performClick()
        assertEquals(listOf<MyTeacherIntent>(MyTeacherIntent.ConfirmUnlink), teacherIntents)
    }

    @Test
    fun `desvincular pide confirmacion en vez de actuar`() {
        showTeacher(MyTeacherState(isLoading = false, teacher = link))
        compose.onNodeWithTag(MY_TEACHER_UNLINK_TAG).performClick()
        assertEquals(listOf<MyTeacherIntent>(MyTeacherIntent.RequestUnlink), teacherIntents)
    }

    @Test
    fun `mientras carga no afirma que no haya profesor`() {
        showTeacher(MyTeacherState())
        compose.onNodeWithTag(MY_TEACHER_LOADING_TAG).assertIsDisplayed()
        compose.onNodeWithTag(MY_TEACHER_NONE_TAG).assertDoesNotExist()
    }
}
