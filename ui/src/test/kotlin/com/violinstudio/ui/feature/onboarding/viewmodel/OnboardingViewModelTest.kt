package com.violinstudio.ui.feature.onboarding.viewmodel

import com.violinstudio.domain.feature.auth.usecase.SignOutUseCase
import com.violinstudio.domain.feature.profile.failure.ProfileFailure
import com.violinstudio.domain.feature.profile.failure.ProfileField
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.profile.model.ProfileRegistration
import com.violinstudio.domain.feature.profile.usecase.AgeGate
import com.violinstudio.domain.feature.profile.usecase.RegisterProfileUseCase
import com.violinstudio.ui.commons.testing.MainDispatcherExtension
import com.violinstudio.ui.commons.testing.MviScenario
import com.violinstudio.ui.commons.testing.testMvi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MainDispatcherExtension::class)
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val register = mockk<RegisterProfileUseCase>()
    private val signOut = mockk<SignOutUseCase>(relaxed = true)
    private val gate = AgeGate(14, Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC))

    private fun viewModel() = OnboardingViewModel(register, signOut, gate)

    private fun registerAnswers(result: Result<Unit>) {
        coEvery { register(any()) } coAnswers {
            delay(100)
            result
        }
    }

    private suspend fun MviScenario<OnboardingState, OnboardingIntent, OnboardingEffect>.fill(year: String = "1990") {
        intent(OnboardingIntent.DisplayNameChanged("  Ana  "))
        assertState { it.displayName == "  Ana  " }
        intent(OnboardingIntent.InstrumentSelected(Instrument.VIOLA))
        assertState { it.instrument == Instrument.VIOLA }
        intent(OnboardingIntent.BirthDateChanged("15", "6", year))
        assertState { it.year == year }
    }

    @Test
    fun `submit registers the trimmed profile and success blocks a second submit`() = runTest {
        val sent = slot<ProfileRegistration>()
        coEvery { register(capture(sent)) } coAnswers {
            delay(100)
            Result.success(Unit)
        }
        viewModel().testMvi {
            fill()
            intent(OnboardingIntent.Submit)
            assertState { it.isLoading }
            assertState { it.succeeded && !it.canSubmit && it.error == null }
            assertNoEffects()
        }
        assertEquals("Ana", sent.captured.displayName)
        assertEquals(Instrument.VIOLA, sent.captured.instrument)
        assertEquals(LocalDate.of(1990, 6, 15), sent.captured.birthDate)
        assertTrue(Regex("^[a-z]{2}(-[A-Z]{2})?$").matches(sent.captured.locale))
        coVerify(exactly = 1) { register(any()) }
    }

    @Test
    fun `an invalid form never reaches the use case`() = runTest {
        viewModel().testMvi {
            intent(OnboardingIntent.Submit)
            assertState { it.fieldErrors.size == 3 && !it.isLoading }
        }
        coVerify(exactly = 0) { register(any()) }
    }

    @Test
    fun `the server prevails over the age hint in both directions`() = runTest {
        // Pista de menor, pero el servidor lo acepta: se avanza (el cliente no bloquea).
        registerAnswers(Result.success(Unit))
        viewModel().testMvi {
            fill(year = "2020")
            intent(OnboardingIntent.Submit)
            assertState { it.isLoading && it.ageHint }
            assertState { it.succeeded }
        }
        // Sin pista de menor, pero el servidor lo rechaza: se muestra su veredicto.
        registerAnswers(Result.failure(ProfileFailure.UnderageNotAllowed))
        viewModel().testMvi {
            fill(year = "1990")
            intent(OnboardingIntent.Submit)
            assertState { it.isLoading }
            assertState { it.error == OnboardingError.UNDERAGE_NOT_ALLOWED && !it.ageHint && !it.succeeded }
        }
    }

    @Test
    fun `server field errors land on their field`() = runTest {
        val cases = listOf(
            ProfileFailure.InvalidBirthDate to ProfileField.BIRTH_DATE,
            ProfileFailure.InvalidInput(ProfileField.DISPLAY_NAME) to ProfileField.DISPLAY_NAME
        )
        for ((failure, field) in cases) {
            registerAnswers(Result.failure(failure))
            viewModel().testMvi {
                fill()
                intent(OnboardingIntent.Submit)
                assertState { it.isLoading }
                assertState { it.fieldErrors == setOf(field) && it.error == null && !it.isLoading }
            }
        }
    }

    @Test
    fun `network failure shows its message and a retry calls the use case again without advancing`() = runTest {
        registerAnswers(Result.failure(ProfileFailure.Network))
        viewModel().testMvi {
            fill()
            intent(OnboardingIntent.Submit)
            assertState { it.isLoading }
            assertState { it.error == OnboardingError.NETWORK && !it.succeeded && it.canSubmit }
            registerAnswers(Result.success(Unit))
            intent(OnboardingIntent.Submit)
            assertState { it.isLoading && it.error == null }
            assertState { it.succeeded }
        }
        coVerify(exactly = 2) { register(any()) }
    }

    @Test
    fun `unexpected failures and exceptions become a generic error and keep the loop alive`() = runTest {
        coEvery { register(any()) } throws IllegalStateException("boom")
        viewModel().testMvi {
            fill()
            intent(OnboardingIntent.Submit)
            assertState { it.isLoading }
            assertState { it.error == OnboardingError.UNKNOWN && !it.isLoading }
            registerAnswers(Result.failure(ProfileFailure.Unknown()))
            intent(OnboardingIntent.Submit)
            assertState { it.isLoading }
            assertState { it.error == OnboardingError.UNKNOWN && !it.isLoading }
        }
    }

    @Test
    fun `a second quick submit is dropped`() = runTest {
        registerAnswers(Result.success(Unit))
        viewModel().testMvi {
            fill()
            intent(OnboardingIntent.Submit)
            intent(OnboardingIntent.Submit)
            assertState { it.isLoading }
            assertState { it.succeeded }
        }
        coVerify(exactly = 1) { register(any()) }
    }

    @Test
    fun `sign out calls the use case and survives its failure`() = runTest {
        coEvery { signOut() } throws IllegalStateException("boom") andThen Unit
        viewModel().testMvi {
            intent(OnboardingIntent.SignOut)
            intent(OnboardingIntent.SignOut)
            intent(OnboardingIntent.DisplayNameChanged("x"))
            assertState { it.displayName == "x" }
        }
        coVerify(exactly = 2) { signOut() }
    }

    @Test
    fun `an implausible year is sent as typed and the server answer lands on the date field`() = runTest {
        val sent = slot<ProfileRegistration>()
        coEvery { register(capture(sent)) } coAnswers {
            delay(100)
            Result.failure(ProfileFailure.InvalidBirthDate)
        }
        viewModel().testMvi {
            fill(year = "0001")
            intent(OnboardingIntent.Submit)
            assertState { it.isLoading }
            assertState { it.fieldErrors == setOf(ProfileField.BIRTH_DATE) && !it.isLoading && it.error == null }
        }
        assertEquals(LocalDate.of(1, 6, 15), sent.captured.birthDate)
    }

    @Test
    fun `a future date never reaches the use case`() = runTest {
        viewModel().testMvi {
            fill(year = "2030")
            intent(OnboardingIntent.Submit)
            assertState { it.fieldErrors == setOf(ProfileField.BIRTH_DATE) && !it.isLoading }
        }
        coVerify(exactly = 0) { register(any()) }
    }

    @Test
    fun `while the shared delete flow is active submit and edits are dropped`() = runTest {
        registerAnswers(Result.success(Unit))
        viewModel().testMvi {
            fill()
            intent(OnboardingIntent.DeleteActiveChanged(true))
            assertState { it.deleteActive && !it.canSubmit }
            intent(OnboardingIntent.Submit)
            intent(OnboardingIntent.DisplayNameChanged("Otra"))
            intent(OnboardingIntent.DeleteActiveChanged(false))
            assertState { !it.deleteActive && it.canSubmit && it.displayName == "  Ana  " && !it.isLoading }
        }
        coVerify(exactly = 0) { register(any()) }
    }
}
