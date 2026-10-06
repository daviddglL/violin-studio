package com.violinstudio.ui.feature.settings.viewmodel

import com.violinstudio.domain.feature.consent.failure.ConsentFailure
import com.violinstudio.domain.feature.consent.usecase.RevokeConsentUseCase
import com.violinstudio.domain.feature.profile.failure.ProfileFailure
import com.violinstudio.domain.feature.profile.failure.ProfileField
import com.violinstudio.domain.feature.profile.model.ConsentStatus
import com.violinstudio.domain.feature.profile.model.EditableProfile
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.profile.model.Role
import com.violinstudio.domain.feature.profile.model.UserProfile
import com.violinstudio.domain.feature.profile.usecase.ObserveProfileUseCase
import com.violinstudio.domain.feature.profile.usecase.UpdateProfileUseCase
import com.violinstudio.domain.feature.session.SessionRefreshTrigger
import com.violinstudio.ui.commons.locale.AppLanguage
import com.violinstudio.ui.commons.locale.FakeAppLocales
import com.violinstudio.ui.commons.testing.MainDispatcherExtension
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MainDispatcherExtension::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val profile = MutableStateFlow<UserProfile?>(
        UserProfile(
            "u1", "Ana", Instrument.VIOLIN, "es", Role.INDEPENDENT, false, ConsentStatus.GRANTED, 1, null, false
        )
    )
    private val observe = mockk<ObserveProfileUseCase> { every { this@mockk() } returns profile }
    private val update = mockk<UpdateProfileUseCase>()
    private val revoke = mockk<RevokeConsentUseCase>()
    private val trigger = SessionRefreshTrigger()
    private val locales = FakeAppLocales()

    private fun viewModel(appLocales: FakeAppLocales = locales) =
        SettingsViewModel(observe, update, revoke, trigger, appLocales)

    private fun TestScope.refreshCount(): () -> Int {
        var count = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { trigger.refreshes.collect { count++ } }
        return { count }
    }

    private suspend fun TestScope.confirmRevoke(vm: SettingsViewModel) {
        vm.onIntent(SettingsIntent.RevokeConsent)
        vm.onIntent(SettingsIntent.ConfirmRevoke)
        // Sin avanzar el reloj virtual: el aviso de "la sesion no se actualizo" tiene su propio plazo.
        runCurrent()
    }

    @Test
    fun `the stored profile seeds the editable fields`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(SettingsFields("Ana", Instrument.VIOLIN, "es"), vm.state.value.fields)
        assertTrue(vm.state.value.loaded)
    }

    @Test
    fun `a valid edit is saved trimmed and shown as saved`() = runTest {
        val sent = slot<EditableProfile>()
        coEvery { update(capture(sent)) } returns Result.success(Unit)
        val vm = viewModel()
        advanceUntilIdle()
        vm.onIntent(SettingsIntent.DisplayNameChanged("  Bea  "))
        vm.onIntent(SettingsIntent.InstrumentSelected(Instrument.CELLO))
        vm.onIntent(SettingsIntent.LocaleChanged("en-GB"))
        vm.onIntent(SettingsIntent.Save)
        advanceUntilIdle()
        assertEquals(EditableProfile.create("Bea", Instrument.CELLO, "en-GB").getOrThrow(), sent.captured)
        assertTrue(vm.state.value.saved)
        assertEquals("Bea", vm.state.value.fields.displayName)
        assertFalse(vm.state.value.dirty)
    }

    @Test
    fun `an invalid edit never reaches the use case`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onIntent(SettingsIntent.DisplayNameChanged("   "))
        vm.onIntent(SettingsIntent.Save)
        advanceUntilIdle()
        assertEquals(setOf(ProfileField.DISPLAY_NAME), vm.state.value.fieldErrors)
        coVerify(exactly = 0) { update(any()) }
    }

    @Test
    fun `a rules rejection is a recoverable error, asks for a refresh and a retry can succeed`() = runTest {
        val refreshes = refreshCount()
        coEvery { update(any()) } returnsMany listOf(Result.failure(ProfileFailure.NotAllowed), Result.success(Unit))
        val vm = viewModel()
        advanceUntilIdle()
        vm.onIntent(SettingsIntent.LocaleChanged("en"))
        vm.onIntent(SettingsIntent.Save)
        advanceUntilIdle()
        assertEquals(SettingsError.NOT_ALLOWED, vm.state.value.error)
        assertEquals("en", vm.state.value.fields.locale)
        assertTrue(vm.state.value.canSave)
        assertEquals(1, refreshes())
        vm.onIntent(SettingsIntent.Save)
        advanceUntilIdle()
        assertTrue(vm.state.value.saved)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `every save failure maps to its own outcome`() = runTest {
        val cases = listOf(
            ProfileFailure.Network to SettingsError.NETWORK,
            ProfileFailure.NoProfile to SettingsError.UNAVAILABLE,
            ProfileFailure.EmailNotVerified to SettingsError.UNAVAILABLE,
            ProfileFailure.UnderageNotAllowed to SettingsError.UNKNOWN,
            ProfileFailure.Unknown() to SettingsError.UNKNOWN,
            ProfileFailure.InvalidInput(null) to SettingsError.UNKNOWN
        )
        for ((failure, expected) in cases) {
            coEvery { update(any()) } returns Result.failure(failure)
            val vm = viewModel()
            advanceUntilIdle()
            vm.onIntent(SettingsIntent.LocaleChanged("en"))
            vm.onIntent(SettingsIntent.Save)
            advanceUntilIdle()
            assertEquals(expected, vm.state.value.error, "for $failure")
        }
    }

    @Test
    fun `a server field rejection and a thrown exception are handled`() = runTest {
        coEvery { update(any()) } returns Result.failure(ProfileFailure.InvalidInput(ProfileField.LOCALE))
        val vm = viewModel()
        advanceUntilIdle()
        vm.onIntent(SettingsIntent.LocaleChanged("en"))
        vm.onIntent(SettingsIntent.Save)
        advanceUntilIdle()
        assertEquals(setOf(ProfileField.LOCALE), vm.state.value.fieldErrors)
        coEvery { update(any()) } throws IllegalStateException("boom")
        vm.onIntent(SettingsIntent.LocaleChanged("fr"))
        vm.onIntent(SettingsIntent.Save)
        advanceUntilIdle()
        assertEquals(SettingsError.UNKNOWN, vm.state.value.error)
    }

    @Test
    fun `revoking asks for confirmation first and cancelling never calls the use case`() = runTest {
        val refreshes = refreshCount()
        val vm = viewModel()
        advanceUntilIdle()
        vm.onIntent(SettingsIntent.RevokeConsent)
        advanceUntilIdle()
        assertTrue(vm.state.value.confirmingRevoke)
        vm.onIntent(SettingsIntent.CancelRevoke)
        advanceUntilIdle()
        assertFalse(vm.state.value.confirmingRevoke)
        vm.onIntent(SettingsIntent.ConfirmRevoke)
        advanceUntilIdle()
        coVerify(exactly = 0) { revoke() }
        assertEquals(0, refreshes())
    }

    @Test
    fun `a confirmed revoke calls the use case once, locks the form and requests one refresh`() = runTest {
        val refreshes = refreshCount()
        val gate = CompletableDeferred<Unit>()
        coEvery { revoke() } coAnswers {
            gate.await()
            Result.success(Unit)
        }
        val vm = viewModel()
        advanceUntilIdle()
        vm.onIntent(SettingsIntent.RevokeConsent)
        vm.onIntent(SettingsIntent.ConfirmRevoke)
        vm.onIntent(SettingsIntent.ConfirmRevoke)
        advanceUntilIdle()
        assertTrue(vm.state.value.isRevoking)
        vm.onIntent(SettingsIntent.DisplayNameChanged("Otra"))
        vm.onIntent(SettingsIntent.Save)
        advanceUntilIdle()
        coVerify(exactly = 0) { update(any()) }
        gate.complete(Unit)
        runCurrent()
        assertTrue(vm.state.value.revoked)
        coVerify(exactly = 1) { revoke() }
        assertEquals(1, refreshes())
    }

    @Test
    fun `a failed revoke requests no refresh and can be retried`() = runTest {
        val refreshes = refreshCount()
        coEvery { revoke() } returnsMany listOf(Result.failure(ConsentFailure.Network), Result.success(Unit))
        val vm = viewModel()
        advanceUntilIdle()
        confirmRevoke(vm)
        assertEquals(RevokeError.NETWORK, vm.state.value.revokeError)
        assertEquals(0, refreshes())
        confirmRevoke(vm)
        assertTrue(vm.state.value.revoked)
        assertEquals(1, refreshes())
    }

    @Test
    fun `every revoke failure maps explicitly`() = runTest {
        val cases = listOf(
            ConsentFailure.NoProfile to RevokeError.UNAVAILABLE,
            ConsentFailure.EmailNotVerified to RevokeError.UNAVAILABLE,
            ConsentFailure.RateLimited(30) to RevokeError.UNKNOWN,
            ConsentFailure.Unknown() to RevokeError.UNKNOWN
        )
        for ((failure, expected) in cases) {
            coEvery { revoke() } returns Result.failure(failure)
            val vm = viewModel()
            advanceUntilIdle()
            confirmRevoke(vm)
            assertEquals(expected, vm.state.value.revokeError, "for $failure")
        }
        coEvery { revoke() } throws IllegalStateException("boom")
        val thrower = viewModel()
        advanceUntilIdle()
        confirmRevoke(thrower)
        assertEquals(RevokeError.UNKNOWN, thrower.state.value.revokeError)
    }

    @Test
    fun `no active consent means it is already revoked, so the session is refreshed`() = runTest {
        val refreshes = refreshCount()
        coEvery { revoke() } returns Result.failure(ConsentFailure.NoActiveConsent)
        val vm = viewModel()
        advanceUntilIdle()
        confirmRevoke(vm)
        assertTrue(vm.state.value.revoked)
        assertEquals(1, refreshes())
    }

    @Test
    fun `if the session does not leave Ready in time the revoke is stalled and retrying refreshes again`() = runTest {
        val refreshes = refreshCount()
        coEvery { revoke() } returns Result.success(Unit)
        val vm = viewModel()
        advanceUntilIdle()
        confirmRevoke(vm)
        assertTrue(vm.state.value.revoked)
        advanceTimeBy(SettingsViewModel.REVOKE_REFRESH_TIMEOUT_MS - 1)
        assertTrue(vm.state.value.revoked)
        advanceTimeBy(2)
        assertTrue(vm.state.value.revokeStalled)
        assertFalse(vm.state.value.revoked)
        assertFalse(vm.state.value.busy)
        assertEquals(1, refreshes())
        vm.onIntent(SettingsIntent.RetryRefresh)
        runCurrent()
        assertEquals(2, refreshes())
        assertTrue(vm.state.value.revoked)
        advanceTimeBy(SettingsViewModel.REVOKE_REFRESH_TIMEOUT_MS + 1)
        assertTrue(vm.state.value.revokeStalled)
    }

    @Test
    fun `a retry without a stalled revoke does nothing`() = runTest {
        val refreshes = refreshCount()
        val vm = viewModel()
        advanceUntilIdle()
        vm.onIntent(SettingsIntent.RetryRefresh)
        advanceUntilIdle()
        assertEquals(0, refreshes())
    }

    @Test
    fun `sin idioma aplicado la seleccion inicial es el idioma del sistema`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(AppLanguage.SYSTEM, vm.state.value.language)
    }

    @Test
    fun `el idioma ya aplicado se muestra como seleccionado al abrir ajustes`() = runTest {
        val vm = viewModel(FakeAppLocales(listOf("en-GB")))
        advanceUntilIdle()
        assertEquals(AppLanguage.ENGLISH, vm.state.value.language)
    }

    @Test
    fun `elegir espanol aplica la lista es y lo marca como seleccionado`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onIntent(SettingsIntent.LanguageSelected(AppLanguage.SPANISH))
        advanceUntilIdle()
        assertEquals(listOf(listOf("es")), locales.applied)
        assertEquals(AppLanguage.SPANISH, vm.state.value.language)
    }

    @Test
    fun `elegir ingles aplica la lista en`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onIntent(SettingsIntent.LanguageSelected(AppLanguage.ENGLISH))
        advanceUntilIdle()
        assertEquals(listOf(listOf("en")), locales.applied)
        assertEquals(AppLanguage.ENGLISH, vm.state.value.language)
    }

    @Test
    fun `elegir idioma del sistema aplica la lista vacia`() = runTest {
        val vm = viewModel(FakeAppLocales(listOf("en")))
        advanceUntilIdle()
        assertEquals(AppLanguage.ENGLISH, vm.state.value.language)
        vm.onIntent(SettingsIntent.LanguageSelected(AppLanguage.SYSTEM))
        advanceUntilIdle()
        assertEquals(AppLanguage.SYSTEM, vm.state.value.language)
    }

    @Test
    fun `volver al idioma del sistema envia exactamente la lista vacia`() = runTest {
        val spy = FakeAppLocales(listOf("en"))
        val vm = viewModel(spy)
        advanceUntilIdle()
        vm.onIntent(SettingsIntent.LanguageSelected(AppLanguage.SYSTEM))
        advanceUntilIdle()
        assertEquals(listOf(emptyList<String>()), spy.applied)
    }

    @Test
    fun `al volver a la pantalla se relee el idioma cambiado desde los ajustes del sistema`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(AppLanguage.SYSTEM, vm.state.value.language)
        locales.changeExternally(listOf("en"))
        vm.onIntent(SettingsIntent.RefreshLanguage)
        advanceUntilIdle()
        assertEquals(AppLanguage.ENGLISH, vm.state.value.language)
        assertEquals(emptyList<List<String>>(), locales.applied)
    }

    @Test
    fun `cambiar de idioma no toca el formulario ni guarda el perfil`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onIntent(SettingsIntent.LanguageSelected(AppLanguage.ENGLISH))
        advanceUntilIdle()
        assertFalse(vm.state.value.dirty)
        coVerify(exactly = 0) { update(any()) }
    }
}
