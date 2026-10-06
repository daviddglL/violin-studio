package com.violinstudio.ui.feature.consent.view

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.violinstudio.domain.feature.consent.model.IdentityConfig
import com.violinstudio.domain.feature.session.ConsentReason
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.account.view.LocalDeleteAccount
import com.violinstudio.ui.feature.account.view.idleDeleteScope
import com.violinstudio.ui.feature.consent.viewmodel.ConsentError
import com.violinstudio.ui.feature.consent.viewmodel.ConsentState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "es-rES-w411dp-h1200dp-xxhdpi")
class ConsentScreenScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val v1 = IdentityConfig(1, "https://example.test/policy/1", 14, true)
    private val v2 = IdentityConfig(2, "https://example.test/policy/2", 14, true)

    private fun capture(state: ConsentState, name: String) {
        compose.setContent {
            ViolinStudioTheme {
                CompositionLocalProvider(LocalDeleteAccount provides idleDeleteScope()) {
                    ConsentScreen(state, onIntent = {})
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test fun first() = capture(ConsentState(config = v1), "consent_first")

    @Test fun checked() = capture(ConsentState(config = v1, checked = true), "consent_checked")

    @Test fun policyUpdated() =
        capture(ConsentState(config = v2, reason = ConsentReason.POLICY_UPDATED), "consent_policy_updated")

    @Test fun revoked() = capture(ConsentState(config = v2, reason = ConsentReason.REVOKED), "consent_revoked")

    @Test fun policyChanged() = capture(
        ConsentState(config = v2, error = ConsentError.POLICY_CHANGED),
        "consent_policy_changed"
    )

    @Test fun policyChangedUpdated() = capture(
        ConsentState(config = v2, reason = ConsentReason.POLICY_UPDATED, error = ConsentError.POLICY_CHANGED),
        "consent_policy_changed_updated"
    )

    @Test fun retry() = capture(
        ConsentState(config = v1, checked = true, error = ConsentError.NETWORK),
        "consent_retry"
    )
}
