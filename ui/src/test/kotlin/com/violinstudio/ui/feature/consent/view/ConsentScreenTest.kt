package com.violinstudio.ui.feature.consent.view

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.consent.model.IdentityConfig
import com.violinstudio.domain.feature.session.ConsentReason
import com.violinstudio.ui.R
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.account.view.FAKE_DELETE_TAG
import com.violinstudio.ui.feature.account.view.LocalDeleteAccount
import com.violinstudio.ui.feature.account.view.fakeDeleteScope
import com.violinstudio.ui.feature.auth.view.AUTH_MESSAGE_TAG
import com.violinstudio.ui.feature.auth.view.AUTH_SUBMIT_TAG
import com.violinstudio.ui.feature.consent.viewmodel.ConsentError
import com.violinstudio.ui.feature.consent.viewmodel.ConsentIntent
import com.violinstudio.ui.feature.consent.viewmodel.ConsentState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ConsentScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)
    private val intents = mutableListOf<ConsentIntent>()
    private val v1 = IdentityConfig(1, "https://example.test/policy/1", 14, true)
    private val v2 = IdentityConfig(2, "https://example.test/policy/2", 14, true)

    private fun show(state: ConsentState, deleteActive: Boolean = false) = compose.setContent {
        ViolinStudioTheme {
            CompositionLocalProvider(LocalDeleteAccount provides fakeDeleteScope(deleteActive)) {
                ConsentScreen(state) { intents += it }
            }
        }
    }

    private fun loaded(config: IdentityConfig = v1, reason: ConsentReason = ConsentReason.FIRST) =
        ConsentState(config = config, reason = reason)

    @Test
    fun `first consent shows the intro, the policy version and the three actions`() {
        show(loaded())
        compose.onNodeWithText(text(R.string.consent_title_first)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.consent_intro_first)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.consent_read_policy, 1)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.consent_checkbox, 1)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(FAKE_DELETE_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(CONSENT_SIGN_OUT_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a policy update says it was updated and shows the new version`() {
        show(loaded(v2, ConsentReason.POLICY_UPDATED))
        compose.onNodeWithText(text(R.string.consent_title_updated)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.consent_intro_updated, 2)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.consent_checkbox, 2)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.consent_intro_first)).assertDoesNotExist()
    }

    @Test
    fun `a revoked consent says it was withdrawn and offers to accept again or delete`() {
        show(loaded(v2, ConsentReason.REVOKED))
        compose.onNodeWithText(text(R.string.consent_title_revoked)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.consent_intro_revoked, 2)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.consent_intro_updated, 2)).assertDoesNotExist()
        compose.onNodeWithTag(FAKE_DELETE_TAG).performScrollTo().assertIsEnabled()
    }

    @Test
    fun `accept is disabled until the checkbox is ticked`() {
        show(loaded())
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(CONSENT_CHECKBOX_TAG).performScrollTo().performClick()
        assertEquals(listOf<ConsentIntent>(ConsentIntent.CheckedChanged(true)), intents)
    }

    @Test
    fun `with the checkbox ticked accept sends its intent and the box can be unticked`() {
        show(loaded().copy(checked = true))
        compose.onNodeWithTag(CONSENT_CHECKBOX_TAG).performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .performClick()
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf(ConsentIntent.CheckedChanged(false), ConsentIntent.Accept), intents)
    }

    @Test
    fun `the policy link sends open policy`() {
        show(loaded())
        compose.onNodeWithTag(CONSENT_POLICY_LINK_TAG).performScrollTo().performClick()
        assertEquals(listOf<ConsentIntent>(ConsentIntent.OpenPolicy), intents)
    }

    @Test
    fun `while loading everything that changes the outcome is blocked`() {
        show(loaded().copy(checked = true, isLoading = true))
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.consent_accepting)).assertIsDisplayed()
        compose.onNodeWithTag(FAKE_DELETE_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(CONSENT_CHECKBOX_TAG).performScrollTo().performClick()
        assertEquals(emptyList<ConsentIntent>(), intents)
    }

    @Test
    fun `after success accept stays disabled`() {
        show(loaded().copy(checked = true, succeeded = true))
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `before the policy is known nothing can be accepted`() {
        show(ConsentState())
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(CONSENT_SIGN_OUT_TAG).performScrollTo().assertIsDisplayed()
    }

    private fun assertAssertive(message: String, state: ConsentState) {
        show(state)
        compose.onNode(hasText(message)).performScrollTo().assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
    }

    @Test
    fun `a network error is assertive`() =
        assertAssertive(text(R.string.consent_error_network), loaded().copy(error = ConsentError.NETWORK))

    @Test
    fun `a policy changed notice names the new version and is assertive`() {
        assertAssertive(
            text(R.string.consent_error_policy_changed, 2),
            loaded(v2).copy(error = ConsentError.POLICY_CHANGED)
        )
    }

    @Test
    fun `an unavailable policy is assertive`() {
        assertAssertive(
            text(R.string.consent_error_policy_unavailable),
            loaded().copy(error = ConsentError.POLICY_UNAVAILABLE)
        )
    }

    @Test
    fun `a guardian required answer is assertive`() =
        assertAssertive(text(R.string.consent_error_guardian), loaded().copy(error = ConsentError.GUARDIAN_REQUIRED))

    @Test
    fun `an unknown error is assertive and uses the shared message node`() {
        assertAssertive(text(R.string.consent_error_unknown), loaded().copy(error = ConsentError.UNKNOWN))
        compose.onNodeWithTag(AUTH_MESSAGE_TAG).assertExists()
    }

    @Test
    fun `a policy link that cannot be opened is explained`() =
        assertAssertive(text(R.string.consent_link_failed), loaded().copy(policyLinkFailed = true))

    @Test
    fun `sign out sends its intent and delete is the shared entry`() {
        show(loaded(reason = ConsentReason.REVOKED))
        compose.onNodeWithTag(FAKE_DELETE_TAG).performScrollTo().assertIsEnabled()
        compose.onNodeWithTag(CONSENT_SIGN_OUT_TAG).performScrollTo().performClick()
        assertEquals(listOf<ConsentIntent>(ConsentIntent.SignOut), intents)
    }

    @Test
    fun `while the shared delete flow is active accept, the checkbox and sign out are blocked`() {
        show(loaded().copy(checked = true), deleteActive = true)
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(CONSENT_SIGN_OUT_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(CONSENT_CHECKBOX_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `while accepting the delete entry is not offered to start`() {
        show(loaded().copy(checked = true, isLoading = true))
        compose.onNodeWithTag(FAKE_DELETE_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `the title is a heading`() {
        show(loaded())
        compose.onNode(
            hasText(text(R.string.consent_title_first)) and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        ).assertIsDisplayed()
    }
}
