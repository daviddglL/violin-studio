package com.violinstudio.ui.feature.onboarding.viewmodel

import com.violinstudio.domain.feature.profile.failure.ProfileFailure
import com.violinstudio.domain.feature.profile.failure.ProfileField
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.profile.model.ProfileRegistration
import com.violinstudio.domain.feature.profile.usecase.AgeGate
import java.time.LocalDate

object OnboardingReducer {
    fun reduce(state: OnboardingState, mutation: OnboardingMutation, ageGate: AgeGate): OnboardingState =
        when (mutation) {
            is OnboardingMutation.DisplayNameChanged -> state.edited(ProfileField.DISPLAY_NAME)
                .copy(displayName = mutation.value)
            is OnboardingMutation.InstrumentSelected -> state.edited(ProfileField.INSTRUMENT)
                .copy(instrument = mutation.value)
            is OnboardingMutation.BirthDateChanged -> {
                val typed = state.edited(ProfileField.BIRTH_DATE)
                    .copy(day = mutation.day, month = mutation.month, year = mutation.year)
                // Pista suave: solo con una fecha completa y real; el servidor decide siempre.
                val date = typed.birthDate
                val future = date != null && ageGate.isInFuture(date)
                typed.copy(
                    ageHint = date != null && !future && ageGate.isBelowThreshold(date),
                    birthDateInFuture = future
                )
            }
            OnboardingMutation.SubmitRequested -> {
                val errors = validate(state)
                state.copy(fieldErrors = errors, error = null, isLoading = errors.isEmpty())
            }
            OnboardingMutation.Succeeded -> state.copy(isLoading = false, succeeded = true)
            is OnboardingMutation.FieldRejected ->
                state.copy(isLoading = false, fieldErrors = state.fieldErrors + mutation.field)
            is OnboardingMutation.Failed -> state.copy(isLoading = false, error = mutation.error)
        }

    private fun OnboardingState.edited(field: ProfileField): OnboardingState {
        // El veredicto de menor solo se retira al cambiar la fecha; tras el exito el envio sigue bloqueado.
        val keepVerdict = error == OnboardingError.UNDERAGE_NOT_ALLOWED && field != ProfileField.BIRTH_DATE
        return copy(fieldErrors = fieldErrors - field, error = if (keepVerdict) error else null)
    }

    /** Campos que no pasan la validación local (la misma regla de nombre/locale que usa el dominio al crear). */
    private fun validate(state: OnboardingState): Set<ProfileField> {
        val errors = mutableSetOf<ProfileField>()
        if (state.birthDate == null || state.birthDateInFuture) errors += ProfileField.BIRTH_DATE
        if (state.instrument == null) errors += ProfileField.INSTRUMENT
        val registration = ProfileRegistration.create(
            birthDate = state.birthDate ?: LocalDate.of(1970, 1, 1),
            displayName = state.displayName,
            instrument = state.instrument ?: Instrument.OTHER,
            locale = state.locale
        )
        (registration.exceptionOrNull() as? ProfileFailure.InvalidInput)?.field?.let { errors += it }
        return errors
    }
}
