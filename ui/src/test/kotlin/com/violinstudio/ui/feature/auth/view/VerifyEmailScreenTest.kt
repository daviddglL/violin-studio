package com.violinstudio.ui.feature.auth.view

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.ui.R
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.auth.viewmodel.VerifyEmailIntent
import com.violinstudio.ui.feature.auth.viewmodel.VerifyEmailMessage
import com.violinstudio.ui.feature.auth.viewmodel.VerifyEmailState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class VerifyEmailScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val sent = mutableListOf<VerifyEmailIntent>()

    private fun show(state: VerifyEmailState = VerifyEmailState(), email: String? = "ana@example.test") {
        sent.clear()
        compose.setContent {
            ViolinStudioTheme { VerifyEmailScreen(email = email, state = state, onIntent = { sent += it }) }
        }
    }

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    @Test
    fun showsTheOwnEmailAndExactlyThreeActions() {
        show()
        compose.onNodeWithText(text(R.string.verify_email_message, "ana@example.test"), substring = true)
            .assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(3)
        compose.onNodeWithText(text(R.string.verify_email_check)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.verify_email_resend)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.session_sign_out)).assertIsDisplayed()
    }

    @Test
    fun withoutEmailItStillExplainsWhatToDo() {
        show(email = null)
        compose.onNodeWithText(text(R.string.verify_email_message_no_email)).assertIsDisplayed()
    }

    @Test
    fun eachActionSendsItsIntent() {
        show()
        compose.onNodeWithText(text(R.string.verify_email_check)).performClick()
        compose.onNodeWithText(text(R.string.verify_email_resend)).performClick()
        compose.onNodeWithText(text(R.string.session_sign_out)).performClick()
        assertEquals(
            listOf(VerifyEmailIntent.CheckNow, VerifyEmailIntent.Resend, VerifyEmailIntent.SignOut),
            sent
        )
    }

    @Test
    fun resendIsDisabledDuringTheCooldownAndShowsTheRemainingSeconds() {
        show(VerifyEmailState(resendCooldownSeconds = 42))
        compose.onNodeWithText(text(R.string.verify_email_resend_wait, 42)).assertIsNotEnabled()
    }

    @Test
    fun checkingDisablesTheCheckButtonButNotSignOut() {
        show(VerifyEmailState(checking = true))
        compose.onNodeWithText(text(R.string.verify_email_checking)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.session_sign_out)).assertIsEnabled()
    }

    @Test
    fun everyMessageHasItsOwnText() {
        val expected = mapOf(
            VerifyEmailMessage.NOT_VERIFIED_YET to R.string.verify_email_not_verified_yet,
            VerifyEmailMessage.RESEND_SENT to R.string.verify_email_resend_sent,
            VerifyEmailMessage.WAIT_TOO_MANY_REQUESTS to R.string.verify_email_wait_too_many,
            VerifyEmailMessage.NETWORK to R.string.verify_email_network,
            VerifyEmailMessage.UNKNOWN to R.string.verify_email_unknown
        )
        assertEquals(VerifyEmailMessage.entries.toSet(), expected.keys)
        val message = mutableStateOf<VerifyEmailMessage?>(null)
        sent.clear()
        compose.setContent {
            ViolinStudioTheme {
                VerifyEmailScreen("ana@example.test", VerifyEmailState(message = message.value), {})
            }
        }
        expected.forEach { (m, id) ->
            message.value = m
            compose.onNodeWithText(text(id)).assertIsDisplayed()
        }
    }
}
