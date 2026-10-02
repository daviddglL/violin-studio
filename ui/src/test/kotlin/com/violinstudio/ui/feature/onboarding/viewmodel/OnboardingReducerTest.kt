package com.violinstudio.ui.feature.onboarding.viewmodel

import com.violinstudio.domain.feature.profile.failure.ProfileField
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.profile.usecase.AgeGate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OnboardingReducerTest {
    private val gate = AgeGate(14, Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC))

    private fun reduce(s: OnboardingState, m: OnboardingMutation) = OnboardingReducer.reduce(s, m, gate)

    private val filled = OnboardingState(
        displayName = "Ana",
        instrument = Instrument.VIOLIN,
        locale = "es-ES",
        day = "15",
        month = "6",
        year = "1990"
    )

    @Test
    fun `typing a field stores it and clears that field error and the general error`() {
        val s = OnboardingState(
            fieldErrors = setOf(ProfileField.DISPLAY_NAME, ProfileField.INSTRUMENT),
            error = OnboardingError.NETWORK
        )
        val named = reduce(s, OnboardingMutation.DisplayNameChanged("Ana"))
        assertEquals("Ana", named.displayName)
        assertEquals(setOf(ProfileField.INSTRUMENT), named.fieldErrors)
        assertNull(named.error)
        val picked = reduce(named, OnboardingMutation.InstrumentSelected(Instrument.CELLO))
        assertEquals(Instrument.CELLO, picked.instrument)
        assertTrue(picked.fieldErrors.isEmpty())
    }

    @Test
    fun `the system locale is turned into a tag the server accepts`() {
        assertEquals("es-ES", localeTagOf(Locale("es", "ES")))
        assertEquals("en", localeTagOf(Locale("en")))
        assertEquals("pt-BR", localeTagOf(Locale("pt", "BR")))
        assertEquals(FALLBACK_LOCALE, localeTagOf(Locale("fil", "PH")))
        assertEquals(FALLBACK_LOCALE, localeTagOf(Locale.ROOT))
        assertEquals("zh", localeTagOf(Locale("zh", "019")))
    }

    @Test
    fun `a complete real calendar date is exposed and an impossible one is not`() {
        assertEquals(LocalDate.of(1990, 6, 15), filled.birthDate)
        assertNull(filled.copy(day = "31", month = "2").birthDate)
        assertNull(filled.copy(year = "90").birthDate)
        assertNull(filled.copy(day = "").birthDate)
        assertEquals(LocalDate.of(2024, 2, 29), filled.copy(day = "29", month = "2", year = "2024").birthDate)
    }

    @Test
    fun `the age hint follows the soft gate only for a complete date and never blocks anything`() {
        val minor = reduce(filled, OnboardingMutation.BirthDateChanged("1", "1", "2020"))
        assertTrue(minor.ageHint)
        assertTrue(minor.canSubmit)
        assertFalse(reduce(minor, OnboardingMutation.BirthDateChanged("1", "1", "1990")).ageHint)
        assertFalse(reduce(minor, OnboardingMutation.BirthDateChanged("1", "1", "")).ageHint)
        assertFalse(reduce(minor, OnboardingMutation.BirthDateChanged("40", "1", "2020")).ageHint)
    }

    @Test
    fun `submit flags every missing or malformed field without loading`() {
        val s = reduce(OnboardingState(), OnboardingMutation.SubmitRequested)
        assertEquals(
            setOf(ProfileField.DISPLAY_NAME, ProfileField.INSTRUMENT, ProfileField.BIRTH_DATE),
            s.fieldErrors
        )
        assertFalse(s.isLoading)
        val badName = reduce(filled.copy(displayName = "   "), OnboardingMutation.SubmitRequested)
        assertEquals(setOf(ProfileField.DISPLAY_NAME), badName.fieldErrors)
        val badLocale = reduce(filled.copy(locale = "english"), OnboardingMutation.SubmitRequested)
        assertEquals(setOf(ProfileField.LOCALE), badLocale.fieldErrors)
    }

    @Test
    fun `submit with valid values starts loading and does not decide legality from the hint`() {
        val s = reduce(
            filled.copy(ageHint = true, error = OnboardingError.UNKNOWN),
            OnboardingMutation.SubmitRequested
        )
        assertTrue(s.isLoading)
        assertTrue(s.fieldErrors.isEmpty())
        assertNull(s.error)
    }

    @Test
    fun `a field rejected by the server is shown on that field and stops loading`() {
        val s = reduce(filled.copy(isLoading = true), OnboardingMutation.FieldRejected(ProfileField.BIRTH_DATE))
        assertEquals(setOf(ProfileField.BIRTH_DATE), s.fieldErrors)
        assertFalse(s.isLoading)
        assertEquals("1990", s.year)
    }

    @Test
    fun `failures stop loading and success blocks submit`() {
        val failed = reduce(filled.copy(isLoading = true), OnboardingMutation.Failed(OnboardingError.UNDERAGE_NOT_ALLOWED))
        assertEquals(OnboardingError.UNDERAGE_NOT_ALLOWED, failed.error)
        assertFalse(failed.isLoading)
        val ok = reduce(filled.copy(isLoading = true), OnboardingMutation.Succeeded)
        assertTrue(ok.succeeded)
        assertFalse(ok.canSubmit)
    }

    @Test
    fun `deleting blocks submit, and a failed deletion keeps the account on screen with its reason`() {
        val deleting = reduce(filled, OnboardingMutation.DeleteStarted)
        assertTrue(deleting.isDeleting)
        assertFalse(deleting.canSubmit)
        val failed = reduce(deleting, OnboardingMutation.DeleteFailed(OnboardingDeleteError.REAUTH_REQUIRED))
        assertFalse(failed.isDeleting)
        assertEquals(OnboardingDeleteError.REAUTH_REQUIRED, failed.deleteError)
        assertNull(reduce(failed, OnboardingMutation.DeleteStarted).deleteError)
    }

    @Test
    fun `toString leaves out the name and the birth date`() {
        val text = filled.toString()
        assertFalse(text.contains("Ana"))
        assertFalse(text.contains("1990"))
    }
}
