package com.violinstudio.ui.feature.settings.viewmodel

import com.violinstudio.domain.feature.profile.failure.ProfileField
import com.violinstudio.domain.feature.profile.model.EditableProfile
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.profile.model.UserProfile
import com.violinstudio.ui.commons.locale.AppLanguage
import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState

/**
 * Los únicos tres campos editables. El rol, la fecha de nacimiento, el consentimiento y el tutor no existen aquí:
 * los controla el servidor y la UI no tiene forma de cambiarlos.
 */
data class SettingsFields(val displayName: String, val instrument: Instrument, val locale: String) {
    // Sin el nombre del usuario.
    override fun toString() = "SettingsFields(instrument=$instrument, locale=$locale)"

    companion object {
        fun of(profile: UserProfile) = SettingsFields(profile.displayName, profile.instrument, profile.locale)

        fun of(edit: EditableProfile) = SettingsFields(edit.displayName, edit.instrument, edit.locale)
    }
}

/** Fallos del guardado. [UNAVAILABLE] es terminal (no hay perfil o falta verificar el email): no se reintenta. */
enum class SettingsError { NETWORK, NOT_ALLOWED, UNAVAILABLE, UNKNOWN }

/** Fallos de la revocación. [UNAVAILABLE] es terminal; el resto se puede reintentar. */
enum class RevokeError { NETWORK, UNAVAILABLE, UNKNOWN }

data class SettingsState(
    val fields: SettingsFields = SettingsFields("", Instrument.OTHER, "es"),
    /** Último perfil conocido; `null` mientras no ha llegado (la pantalla muestra carga, no un formulario vacío). */
    val baseline: SettingsFields? = null,
    val fieldErrors: Set<ProfileField> = emptySet(),
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    val error: SettingsError? = null,
    val confirmingRevoke: Boolean = false,
    val isRevoking: Boolean = false,
    val revokeError: RevokeError? = null,
    /** Revocación hecha: la sesión cambia sola a re-consentir; hasta entonces todo queda bloqueado. */
    val revoked: Boolean = false,
    /** La revocacion se hizo pero la sesion no salio de Ready a tiempo: se ofrece reintentar el refresco. */
    val revokeStalled: Boolean = false,

    /** Solo la pantalla lo fija: el flujo compartido de borrar la cuenta esta abierto o terminado. */
    val deleteActive: Boolean = false,

    /** Idioma de la app elegido (local al dispositivo; no forma parte del perfil). */
    val language: AppLanguage = AppLanguage.SYSTEM
) : UiState {
    val loaded: Boolean get() = baseline != null
    val busy: Boolean get() = isSaving || isRevoking || revoked || confirmingRevoke || deleteActive
    val dirty: Boolean get() = loaded && fields != baseline
    val canSave: Boolean get() = dirty && !busy && error != SettingsError.UNAVAILABLE
    val canRevoke: Boolean
        get() = loaded && !busy && !revokeStalled && revokeError != RevokeError.UNAVAILABLE

    override fun toString() =
        "SettingsState(loaded=$loaded, isSaving=$isSaving, error=$error, isRevoking=$isRevoking, revoked=$revoked)"
}

sealed interface SettingsIntent : UiIntent {
    data class DisplayNameChanged(val value: String) : SettingsIntent
    data class InstrumentSelected(val value: Instrument) : SettingsIntent
    data class LocaleChanged(val value: String) : SettingsIntent
    data object Save : SettingsIntent
    data object RevokeConsent : SettingsIntent
    data object ConfirmRevoke : SettingsIntent
    data object CancelRevoke : SettingsIntent
    data class LanguageSelected(val value: AppLanguage) : SettingsIntent

    /** Al volver a la pantalla: el idioma pudo cambiarse desde los ajustes del sistema (Android 13+). */
    data object RefreshLanguage : SettingsIntent

    /** Tras una revocacion que la sesion no refleja: vuelve a pedir el refresco. */
    data object RetryRefresh : SettingsIntent
}

sealed interface SettingsEffect : UiEffect

sealed interface SettingsMutation {
    data class ProfileLoaded(val fields: SettingsFields) : SettingsMutation
    data class DisplayNameChanged(val value: String) : SettingsMutation
    data class InstrumentSelected(val value: Instrument) : SettingsMutation
    data class LocaleChanged(val value: String) : SettingsMutation
    data object SaveRequested : SettingsMutation
    data class Saved(val fields: SettingsFields) : SettingsMutation
    data class FieldRejected(val field: ProfileField) : SettingsMutation
    data class SaveFailed(val error: SettingsError) : SettingsMutation
    data object RevokeAsked : SettingsMutation
    data object RevokeCancelled : SettingsMutation
    data object RevokeStarted : SettingsMutation
    data object RevokeSucceeded : SettingsMutation
    data class RevokeFailed(val error: RevokeError) : SettingsMutation
    data object RevokeStalled : SettingsMutation
    data object RefreshRetried : SettingsMutation
    data class LanguageSelected(val value: AppLanguage) : SettingsMutation
}
