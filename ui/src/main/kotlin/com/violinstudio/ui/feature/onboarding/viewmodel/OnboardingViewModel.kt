package com.violinstudio.ui.feature.onboarding.viewmodel

import com.violinstudio.domain.feature.auth.usecase.SignOutUseCase
import com.violinstudio.domain.feature.profile.failure.ProfileFailure
import com.violinstudio.domain.feature.profile.failure.ProfileField
import com.violinstudio.domain.feature.profile.model.ProfileRegistration
import com.violinstudio.domain.feature.profile.usecase.AgeGate
import com.violinstudio.domain.feature.profile.usecase.RegisterProfileUseCase
import com.violinstudio.ui.commons.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/**
 * No navega: tras el alta el caso de uso refresca los claims y la sesión sustituye la pantalla. El cliente nunca
 * decide la legalidad: el [AgeGate] es solo una pista visual y el veredicto (`UnderageNotAllowed`, fecha inválida)
 * es siempre el del servidor. Sin registros de datos personales.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val registerProfile: RegisterProfileUseCase,
    private val signOut: SignOutUseCase,
    private val ageGate: AgeGate
) : MviViewModel<OnboardingState, OnboardingIntent, OnboardingEffect>(
    OnboardingState(locale = localeTagOf(Locale.getDefault()))
) {
    private var submitPending = false

    override fun onIntent(intent: OnboardingIntent) {
        when (intent) {
            OnboardingIntent.Submit -> {
                if (submitPending) return
                submitPending = true
            }
            else -> Unit
        }
        super.onIntent(intent)
    }

    override suspend fun handleIntent(intent: OnboardingIntent) = when (intent) {
        is OnboardingIntent.DisplayNameChanged -> reduce(OnboardingMutation.DisplayNameChanged(intent.value))
        is OnboardingIntent.InstrumentSelected -> reduce(OnboardingMutation.InstrumentSelected(intent.value))
        is OnboardingIntent.BirthDateChanged ->
            reduce(OnboardingMutation.BirthDateChanged(intent.day, intent.month, intent.year))
        OnboardingIntent.Submit -> onSubmit()
        OnboardingIntent.SignOut -> onSignOut()
    }

    private suspend fun onSubmit() {
        try {
            reduce(OnboardingMutation.SubmitRequested)
            val current = state.value
            if (!current.isLoading) return
            val registration = ProfileRegistration.create(
                birthDate = checkNotNull(current.birthDate),
                displayName = current.displayName,
                instrument = checkNotNull(current.instrument),
                locale = current.locale
            ).getOrElse {
                reduce(it.toMutation())
                return
            }
            val result = try {
                registerProfile(registration)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                Result.failure(ProfileFailure.Unknown())
            }
            result.fold(
                onSuccess = { reduce(OnboardingMutation.Succeeded) },
                onFailure = { reduce(it.toMutation()) }
            )
        } finally {
            submitPending = false
        }
    }

    private suspend fun onSignOut() {
        try {
            signOut()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // El estado de sesión sigue siendo la fuente de verdad; un fallo no debe matar el bucle de intents.
        }
    }

    private fun Throwable.toMutation(): OnboardingMutation = when (this) {
        ProfileFailure.UnderageNotAllowed -> OnboardingMutation.Failed(OnboardingError.UNDERAGE_NOT_ALLOWED)
        ProfileFailure.InvalidBirthDate -> OnboardingMutation.FieldRejected(ProfileField.BIRTH_DATE)
        is ProfileFailure.InvalidInput -> field?.let { OnboardingMutation.FieldRejected(it) }
            ?: OnboardingMutation.Failed(OnboardingError.UNKNOWN)
        ProfileFailure.Network -> OnboardingMutation.Failed(OnboardingError.NETWORK)
        else -> OnboardingMutation.Failed(OnboardingError.UNKNOWN)
    }

    private fun reduce(mutation: OnboardingMutation) = setState { OnboardingReducer.reduce(this, mutation, ageGate) }
}
