package com.violinstudio.ui.feature.onboarding.view

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.profile.failure.ProfileField
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.ui.R
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.account.view.FAKE_DELETE_TAG
import com.violinstudio.ui.feature.account.view.LocalDeleteAccount
import com.violinstudio.ui.feature.account.view.fakeDeleteScope
import com.violinstudio.ui.feature.auth.view.AUTH_MESSAGE_TAG
import com.violinstudio.ui.feature.auth.view.AUTH_SUBMIT_TAG
import com.violinstudio.ui.feature.onboarding.viewmodel.OnboardingError
import com.violinstudio.ui.feature.onboarding.viewmodel.OnboardingIntent
import com.violinstudio.ui.feature.onboarding.viewmodel.OnboardingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "es")
class OnboardingScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private fun text(id: Int) = context.getString(id)
    private val intents = mutableListOf<OnboardingIntent>()

    private fun show(state: OnboardingState = OnboardingState(), deleteActive: Boolean = false) = compose.setContent {
        ViolinStudioTheme {
            CompositionLocalProvider(LocalDeleteAccount provides fakeDeleteScope(deleteActive)) {
                OnboardingScreen(state) { intents += it }
            }
        }
    }

    @Test
    fun `typing the name and picking an instrument send intents`() {
        show()
        compose.onNodeWithTag(ONBOARDING_NAME_TAG).performTextInput("Ana")
        compose.onNodeWithTag(onboardingInstrumentTag("cello")).performScrollTo().performClick()
        assertEquals(
            listOf(OnboardingIntent.DisplayNameChanged("Ana"), OnboardingIntent.InstrumentSelected(Instrument.CELLO)),
            intents
        )
    }

    @Test
    fun `every instrument is offered with its label`() {
        show()
        for (id in listOf(
            R.string.onboarding_instrument_violin,
            R.string.onboarding_instrument_viola,
            R.string.onboarding_instrument_cello,
            R.string.onboarding_instrument_double_bass,
            R.string.onboarding_instrument_other
        )) compose.onNodeWithText(text(id)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `date fields keep digits only and report the whole typed date`() {
        show(OnboardingState(day = "5", month = "6", year = ""))
        compose.onNodeWithTag(ONBOARDING_YEAR_TAG).performScrollTo().performTextInput("19a9b0")
        assertEquals(listOf(OnboardingIntent.BirthDateChanged("5", "6", "1990")), intents)
    }

    @Test
    fun `submit sends the intent and is disabled while loading or after success`() {
        show()
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).performScrollTo().performClick()
        assertEquals(listOf<OnboardingIntent>(OnboardingIntent.Submit), intents)
    }

    @Test
    fun `submit is disabled while loading`() {
        show(OnboardingState(isLoading = true))
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.onboarding_loading)).assertIsDisplayed()
    }

    @Test
    fun `the age hint is soft with no age and no verdict and the form stays usable`() {
        show(OnboardingState(ageHint = true))
        compose.onNodeWithTag(ONBOARDING_AGE_HINT_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.onboarding_age_hint)).assertIsDisplayed()
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).performScrollTo().performClick()
        assertEquals(listOf<OnboardingIntent>(OnboardingIntent.Submit), intents)
        val numberText = SemanticsMatcher("shows a number") {
            SemanticsProperties.Text in it.config &&
                it.config[SemanticsProperties.Text].any { t -> t.text.any(Char::isDigit) && t.text.length < 40 }
        }
        val notAField = SemanticsMatcher("is not a field") { SemanticsProperties.EditableText !in it.config }
        compose.onNode(numberText.and(notAField)).assertDoesNotExist()
    }

    @Test
    fun `without a hint no hint text exists`() {
        show(OnboardingState(ageHint = false))
        compose.onNodeWithTag(ONBOARDING_AGE_HINT_TAG).assertDoesNotExist()
    }

    @Test
    fun `field errors are shown next to their field`() {
        show(OnboardingState(fieldErrors = setOf(ProfileField.DISPLAY_NAME, ProfileField.BIRTH_DATE)))
        compose.onNodeWithText(text(R.string.onboarding_field_name)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.onboarding_field_birth)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `an underage verdict offers the shared delete entry and sign out and nothing else`() {
        show(OnboardingState(error = OnboardingError.UNDERAGE_NOT_ALLOWED))
        compose.onNodeWithText(text(R.string.onboarding_error_underage)).performScrollTo().assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
        compose.onNodeWithTag(FAKE_DELETE_TAG).performScrollTo().assertIsEnabled()
        compose.onNodeWithTag(ONBOARDING_SIGN_OUT_TAG).performScrollTo().performClick()
        assertEquals(listOf<OnboardingIntent>(OnboardingIntent.SignOut), intents)
    }

    @Test
    fun `account deletion is offered in every state, not only after the underage verdict`() {
        show(OnboardingState(error = OnboardingError.NETWORK))
        compose.onNodeWithTag(FAKE_DELETE_TAG).performScrollTo().assertIsEnabled()
        compose.onNodeWithTag(ONBOARDING_SIGN_OUT_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `while the shared delete flow is active submit and sign out are blocked`() {
        val filled = OnboardingState(
            displayName = "Ana",
            day = "1",
            month = "1",
            year = "2000",
            instrument = Instrument.VIOLIN
        )
        show(filled, deleteActive = true)
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(ONBOARDING_SIGN_OUT_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `general errors are announced assertively`() {
        show(OnboardingState(error = OnboardingError.NETWORK))
        compose.onNodeWithTag(AUTH_MESSAGE_TAG).performScrollTo().assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
    }

    @Test
    fun `the underage verdict hides the hint and disables continue`() {
        show(OnboardingState(ageHint = true, error = OnboardingError.UNDERAGE_NOT_ALLOWED))
        compose.onNodeWithTag(ONBOARDING_AGE_HINT_TAG).assertDoesNotExist()
        compose.onNodeWithTag(AUTH_SUBMIT_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(FAKE_DELETE_TAG).performScrollTo().assertIsEnabled()
    }

    @Test
    fun `the hint promises nothing concrete`() {
        val hint = text(R.string.onboarding_age_hint)
        assertTrue(hint.startsWith("Si eres menor de edad"))
        assertFalse(hint.contains("confirmaremos"))
    }

    @Test
    fun `deleting the account is blocked while a registration is loading`() {
        show(OnboardingState(error = OnboardingError.UNDERAGE_NOT_ALLOWED, isLoading = true))
        compose.onNodeWithTag(FAKE_DELETE_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `a future date shows its own local message`() {
        show(OnboardingState(birthDateInFuture = true, fieldErrors = setOf(ProfileField.BIRTH_DATE)))
        compose.onNodeWithText(text(R.string.onboarding_field_birth_future)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.onboarding_field_birth)).assertDoesNotExist()
    }

    @Test
    fun `the date group has a heading and each field says which part it is`() {
        show()
        compose.onNode(
            hasText(text(R.string.onboarding_birth_label)) and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        ).performScrollTo().assertIsDisplayed()
        for (id in listOf(
            R.string.onboarding_birth_day_desc,
            R.string.onboarding_birth_month_desc,
            R.string.onboarding_birth_year_desc
        )) compose.onNodeWithContentDescription(text(id)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the date error is the supporting text of the year field and is announced`() {
        show(OnboardingState(fieldErrors = setOf(ProfileField.BIRTH_DATE)))
        val error = compose.onNode(
            hasText(text(R.string.onboarding_field_birth)) and hasAnyAncestor(hasTestTag(ONBOARDING_YEAR_TAG)),
            useUnmergedTree = true
        )
        error.performScrollTo().assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onAllNodesWithText(text(R.string.onboarding_field_birth), useUnmergedTree = true).assertCountEquals(1)
    }
}
