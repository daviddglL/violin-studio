package com.violinstudio.ui.feature.account.view

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.violinstudio.domain.feature.auth.usecase.ReauthMethod
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.account.viewmodel.DeleteAccountError
import com.violinstudio.ui.feature.account.viewmodel.DeleteAccountState
import com.violinstudio.ui.feature.account.viewmodel.DeleteStep
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h800dp-xxhdpi")
class DeleteAccountEntryScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val confirming = DeleteAccountState(step = DeleteStep.CONFIRMING)
    private val reauthPassword = DeleteAccountState(step = DeleteStep.REAUTH, method = ReauthMethod.PASSWORD)

    private fun capture(state: DeleteAccountState, name: String) {
        compose.setContent {
            ViolinStudioTheme {
                Surface {
                    Column(Modifier.padding(24.dp)) { DeleteAccountEntry(state, onIntent = {}, enabled = true) }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test fun idle() = capture(DeleteAccountState(), "delete_account_idle")

    @Test fun confirm() = capture(confirming, "delete_account_confirm")

    @Test fun deleting() = capture(confirming.copy(isWorking = true), "delete_account_deleting")

    @Test fun retryAfterNetworkError() = capture(
        confirming.copy(error = DeleteAccountError.NETWORK),
        "delete_account_retry"
    )

    @Test fun reauthPassword() = capture(reauthPassword.copy(password = "secreta"), "delete_account_reauth_password")

    @Test fun wrongPassword() = capture(
        reauthPassword.copy(error = DeleteAccountError.WRONG_PASSWORD),
        "delete_account_wrong_password"
    )

    @Test fun reauthGoogle() = capture(
        DeleteAccountState(step = DeleteStep.REAUTH, method = ReauthMethod.GOOGLE),
        "delete_account_reauth_google"
    )

    @Test fun noProviderToReauthenticate() = capture(
        confirming.copy(error = DeleteAccountError.REAUTH_UNAVAILABLE),
        "delete_account_reauth_unavailable"
    )

    @Test fun done() = capture(DeleteAccountState(deleted = true), "delete_account_done")
}
