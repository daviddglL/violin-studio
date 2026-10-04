package com.violinstudio.ui.feature.settings.viewmodel

import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.consent.failure.ConsentFailure
import com.violinstudio.domain.feature.consent.usecase.RevokeConsentUseCase
import com.violinstudio.domain.feature.profile.failure.ProfileFailure
import com.violinstudio.domain.feature.profile.model.EditableProfile
import com.violinstudio.domain.feature.profile.usecase.ObserveProfileUseCase
import com.violinstudio.domain.feature.profile.usecase.UpdateProfileUseCase
import com.violinstudio.domain.feature.session.SessionRefreshTrigger
import com.violinstudio.ui.commons.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * No navega: tras revocar, la sesión pasa sola a re-consentir (perfil en caliente + petición de refresco). Guardar y
 * revocar se excluyen mutuamente. Sin registros de datos personales.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    observeProfile: ObserveProfileUseCase,
    private val updateProfile: UpdateProfileUseCase,
    private val revokeConsent: RevokeConsentUseCase,
    private val refreshTrigger: SessionRefreshTrigger
) : MviViewModel<SettingsState, SettingsIntent, SettingsEffect>(SettingsState()) {
    private var savePending = false
    private var revokePending = false
    private var staleJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                observeProfile().collect { profile ->
                    profile?.let { reduce(SettingsMutation.ProfileLoaded(SettingsFields.of(it))) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Sin perfil el formulario sigue en carga; la sesión es la fuente de verdad.
            }
        }
    }

    override fun onIntent(intent: SettingsIntent) {
        when (intent) {
            SettingsIntent.Save -> {
                if (savePending || revokePending) return
                savePending = true
            }
            SettingsIntent.ConfirmRevoke -> {
                if (revokePending || savePending) return
                revokePending = true
            }
            else -> Unit
        }
        super.onIntent(intent)
    }

    override suspend fun handleIntent(intent: SettingsIntent) = when (intent) {
        is SettingsIntent.DisplayNameChanged -> reduce(SettingsMutation.DisplayNameChanged(intent.value))
        is SettingsIntent.InstrumentSelected -> reduce(SettingsMutation.InstrumentSelected(intent.value))
        is SettingsIntent.LocaleChanged -> reduce(SettingsMutation.LocaleChanged(intent.value))
        SettingsIntent.Save -> onSave()
        SettingsIntent.RevokeConsent -> reduce(SettingsMutation.RevokeAsked)
        SettingsIntent.CancelRevoke -> reduce(SettingsMutation.RevokeCancelled)
        SettingsIntent.ConfirmRevoke -> onRevoke()
        SettingsIntent.RetryRefresh -> onRetryRefresh()
    }

    private suspend fun onSave() {
        try {
            if (state.value.busy) return
            reduce(SettingsMutation.SaveRequested)
            val current = state.value
            if (!current.isSaving) return
            val edit = EditableProfile.create(
                current.fields.displayName,
                current.fields.instrument,
                current.fields.locale
            ).getOrElse {
                reduce(SettingsMutation.SaveFailed(SettingsError.UNKNOWN))
                return
            }
            val result = try {
                updateProfile(edit)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                Result.failure(ProfileFailure.Unknown())
            }
            result.fold(
                onSuccess = { reduce(SettingsMutation.Saved(SettingsFields.of(edit))) },
                onFailure = { reduce(it.toSaveMutation()) }
            )
        } finally {
            savePending = false
        }
    }

    private suspend fun onRevoke() {
        try {
            // Solo tras la confirmación explícita (los intents se procesan en orden: el estado ya la refleja).
            if (!state.value.confirmingRevoke) return
            reduce(SettingsMutation.RevokeStarted)
            val result = try {
                revokeConsent()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                Result.failure(ConsentFailure.Unknown())
            }
            result.fold(
                onSuccess = { onRevoked() },
                onFailure = { failure ->
                    // Sin consentimiento activo ya está revocado: se refresca la sesión para que lo refleje.
                    if (failure == ConsentFailure.NoActiveConsent) onRevoked() else reduce(failure.toRevokeMutation())
                }
            )
        } finally {
            revokePending = false
        }
    }

    private fun onRevoked() {
        reduce(SettingsMutation.RevokeSucceeded)
        refreshTrigger.requestRefresh()
        watchSessionCatchUp()
    }

    private fun onRetryRefresh() {
        if (!state.value.revokeStalled) return
        reduce(SettingsMutation.RefreshRetried)
        refreshTrigger.requestRefresh()
        watchSessionCatchUp()
    }

    /**
     * Si la sesion sale de Ready, la pantalla desaparece y el ViewModel se cancela con ella. Si sigue aqui pasado el
     * plazo, la revocacion no se ha reflejado: se desbloquea y se ofrece reintentar el refresco (sin callejon).
     */
    private fun watchSessionCatchUp() {
        staleJob?.cancel()
        staleJob = viewModelScope.launch {
            delay(REVOKE_REFRESH_TIMEOUT_MS)
            reduce(SettingsMutation.RevokeStalled)
        }
    }

    private fun Throwable.toSaveMutation(): SettingsMutation = when (this) {
        is ProfileFailure.InvalidInput -> field?.let { SettingsMutation.FieldRejected(it) }
            ?: SettingsMutation.SaveFailed(SettingsError.UNKNOWN)
        ProfileFailure.NotAllowed -> {
            // Claim o consentimiento obsoletos: que la sesión se reevalúe; el formulario sigue editable.
            refreshTrigger.requestRefresh()
            SettingsMutation.SaveFailed(SettingsError.NOT_ALLOWED)
        }
        ProfileFailure.Network -> SettingsMutation.SaveFailed(SettingsError.NETWORK)
        ProfileFailure.NoProfile, ProfileFailure.EmailNotVerified ->
            SettingsMutation.SaveFailed(SettingsError.UNAVAILABLE)
        ProfileFailure.UnderageNotAllowed, ProfileFailure.InvalidBirthDate, is ProfileFailure.Unknown ->
            SettingsMutation.SaveFailed(SettingsError.UNKNOWN)
        else -> SettingsMutation.SaveFailed(SettingsError.UNKNOWN)
    }

    private fun Throwable.toRevokeMutation(): SettingsMutation = when (this) {
        ConsentFailure.Network -> SettingsMutation.RevokeFailed(RevokeError.NETWORK)
        ConsentFailure.NoProfile, ConsentFailure.EmailNotVerified ->
            SettingsMutation.RevokeFailed(RevokeError.UNAVAILABLE)
        else -> SettingsMutation.RevokeFailed(RevokeError.UNKNOWN)
    }

    private fun reduce(mutation: SettingsMutation) = setState { SettingsReducer.reduce(this, mutation) }

    companion object {
        /** Tiempo que se espera a que la sesion refleje una revocacion antes de avisar y ofrecer reintentar. */
        const val REVOKE_REFRESH_TIMEOUT_MS = 10_000L
    }
}
