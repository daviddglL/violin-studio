package com.violinstudio.ui.feature.settings.viewmodel

import com.violinstudio.domain.feature.profile.failure.ProfileFailure
import com.violinstudio.domain.feature.profile.failure.ProfileField
import com.violinstudio.domain.feature.profile.model.EditableProfile

object SettingsReducer {
    fun reduce(state: SettingsState, mutation: SettingsMutation): SettingsState = when (mutation) {
        is SettingsMutation.ProfileLoaded -> {
            // Un perfil nuevo solo pisa lo que se ve si el usuario no ha tocado nada.
            val untouched = !state.loaded || state.fields == state.baseline
            state.copy(baseline = mutation.fields, fields = if (untouched) mutation.fields else state.fields)
        }
        is SettingsMutation.DisplayNameChanged ->
            state.edited(ProfileField.DISPLAY_NAME) { it.copy(displayName = mutation.value) }
        is SettingsMutation.InstrumentSelected ->
            state.edited(ProfileField.INSTRUMENT) { it.copy(instrument = mutation.value) }
        is SettingsMutation.LocaleChanged -> state.edited(ProfileField.LOCALE) { it.copy(locale = mutation.value) }
        SettingsMutation.SaveRequested -> {
            val errors = validate(state.fields)
            state.copy(fieldErrors = errors, error = null, saved = false, isSaving = errors.isEmpty())
        }
        is SettingsMutation.Saved ->
            state.copy(isSaving = false, saved = true, baseline = mutation.fields, fields = mutation.fields)
        is SettingsMutation.FieldRejected ->
            state.copy(isSaving = false, fieldErrors = state.fieldErrors + mutation.field)
        is SettingsMutation.SaveFailed -> state.copy(isSaving = false, error = mutation.error)
        SettingsMutation.RevokeAsked -> state.copy(confirmingRevoke = state.canRevoke, saved = false)
        SettingsMutation.RevokeCancelled -> state.copy(confirmingRevoke = false)
        SettingsMutation.RevokeStarted -> state.copy(confirmingRevoke = false, isRevoking = true, revokeError = null)
        SettingsMutation.RevokeSucceeded -> state.copy(isRevoking = false, revoked = true)
        is SettingsMutation.RevokeFailed -> state.copy(isRevoking = false, revokeError = mutation.error)
    }

    private fun SettingsState.edited(field: ProfileField, change: (SettingsFields) -> SettingsFields): SettingsState {
        if (busy) return this
        return copy(fields = change(fields), fieldErrors = fieldErrors - field, error = null, saved = false)
    }

    /** La misma regla de nombre y locale que aplica el dominio al crear el [EditableProfile]. */
    private fun validate(fields: SettingsFields): Set<ProfileField> {
        val failure = EditableProfile.create(fields.displayName, fields.instrument, fields.locale).exceptionOrNull()
        return (failure as? ProfileFailure.InvalidInput)?.field?.let { setOf(it) } ?: emptySet()
    }
}
