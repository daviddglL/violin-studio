package com.violinstudio.ui.feature.onboarding.viewmodel

import com.violinstudio.domain.feature.profile.failure.ProfileField
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState
import java.time.DateTimeException
import java.time.LocalDate
import java.util.Locale

/** Locale que se envía si el del sistema no cumple `^[a-z]{2}(-[A-Z]{2})?$` (p. ej. `fil` o vacío). */
const val FALLBACK_LOCALE = "es"

/** Etiqueta de idioma del sistema en el formato que acepta el servidor. */
fun localeTagOf(locale: Locale): String {
    val language = locale.language
    if (language.length != 2 || !language.all { it in 'a'..'z' }) return FALLBACK_LOCALE
    val country = locale.country
    return if (country.length == 2 && country.all { it in 'A'..'Z' }) "$language-$country" else language
}

/** Fallos generales del envío. El servidor decide la legalidad: [UNDERAGE_NOT_ALLOWED] es su respuesta, no del cliente. */
enum class OnboardingError { UNDERAGE_NOT_ALLOWED, NETWORK, UNKNOWN }

/** Resultado de un borrado de cuenta que NO se completó; un borrado correcto no tiene mensaje (la sesión cambia). */
data class OnboardingState(
    val displayName: String = "",
    val instrument: Instrument? = null,
    val locale: String = FALLBACK_LOCALE,
    val day: String = "",
    val month: String = "",
    val year: String = "",
    /** Pista suave del [com.violinstudio.domain.feature.profile.usecase.AgeGate]; nunca bloquea ni se muestra como edad legal. */
    val ageHint: Boolean = false,
    val fieldErrors: Set<ProfileField> = emptySet(),
    val isLoading: Boolean = false,
    val error: OnboardingError? = null,
    val succeeded: Boolean = false,
    /** La fecha tecleada es posterior a hoy: error local de campo, no una decision legal. */
    val birthDateInFuture: Boolean = false
) : UiState {
    /** Fecha completa y válida en el calendario, o `null`. No valida plausibilidad: eso es del servidor. */
    val birthDate: LocalDate?
        get() {
            val y = year.toIntOrNull() ?: return null
            val m = month.toIntOrNull() ?: return null
            val d = day.toIntOrNull() ?: return null
            if (year.length != YEAR_DIGITS) return null
            return try {
                LocalDate.of(y, m, d)
            } catch (_: DateTimeException) {
                null
            }
        }

    /** Tras un registro correcto el envío sigue bloqueado: la sesión sustituirá la pantalla. */
    val canSubmit: Boolean
        get() = !isLoading && !succeeded && error != OnboardingError.UNDERAGE_NOT_ALLOWED

    /** La pista solo se ve mientras no haya veredicto del servidor ni fecha futura. */
    val showAgeHint: Boolean get() = ageHint && error != OnboardingError.UNDERAGE_NOT_ALLOWED

    // Sin nombre ni fecha de nacimiento.
    override fun toString(): String =
        "OnboardingState(instrument=$instrument, locale=$locale, isLoading=$isLoading, error=$error)"

    private companion object {
        const val YEAR_DIGITS = 4
    }
}

sealed interface OnboardingIntent : UiIntent {
    data class DisplayNameChanged(val value: String) : OnboardingIntent
    data class InstrumentSelected(val value: Instrument) : OnboardingIntent
    data class BirthDateChanged(val day: String, val month: String, val year: String) : OnboardingIntent
    data object Submit : OnboardingIntent
    data object SignOut : OnboardingIntent
}

/** Sin efectos: tras el alta, los claims se refrescan y la sesión lleva a la pantalla siguiente. */
sealed interface OnboardingEffect : UiEffect

sealed interface OnboardingMutation {
    data class DisplayNameChanged(val value: String) : OnboardingMutation
    data class InstrumentSelected(val value: Instrument) : OnboardingMutation
    data class BirthDateChanged(val day: String, val month: String, val year: String) : OnboardingMutation
    data object SubmitRequested : OnboardingMutation
    data object Succeeded : OnboardingMutation
    data class FieldRejected(val field: ProfileField) : OnboardingMutation
    data class Failed(val error: OnboardingError) : OnboardingMutation
}
