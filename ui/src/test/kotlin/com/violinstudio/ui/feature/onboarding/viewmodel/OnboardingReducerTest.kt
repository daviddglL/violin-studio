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
        val failed = reduce(
            filled.copy(isLoading = true),
            OnboardingMutation.Failed(OnboardingError.UNDERAGE_NOT_ALLOWED)
        )
        assertEquals(OnboardingError.UNDERAGE_NOT_ALLOWED, failed.error)
        assertFalse(failed.isLoading)
        val ok = reduce(filled.copy(isLoading = true), OnboardingMutation.Succeeded)
        assertTrue(ok.succeeded)
        assertFalse(ok.canSubmit)
    }

    @Test
    fun `toString leaves out the name and the birth date`() {
        val text = filled.toString()
        assertFalse(text.contains("Ana"))
        assertFalse(text.contains("1990"))
    }

    private val underage = filled.copy(ageHint = true, error = OnboardingError.UNDERAGE_NOT_ALLOWED)

    @Test
    fun `the underage verdict hides the hint and blocks continue`() {
        assertFalse(underage.showAgeHint)
        assertFalse(underage.canSubmit)
        assertTrue(filled.copy(ageHint = true).showAgeHint)
    }

    @Test
    fun `the underage verdict survives unrelated edits and goes away when the birth date changes`() {
        val renamed = reduce(underage, OnboardingMutation.DisplayNameChanged("B"))
        assertEquals(OnboardingError.UNDERAGE_NOT_ALLOWED, renamed.error)
        assertEquals(
            OnboardingError.UNDERAGE_NOT_ALLOWED,
            reduce(underage, OnboardingMutation.InstrumentSelected(Instrument.CELLO)).error
        )
        val dated = reduce(underage, OnboardingMutation.BirthDateChanged("1", "1", "1990"))
        assertNull(dated.error)
        assertTrue(dated.canSubmit)
        val network = filled.copy(error = OnboardingError.NETWORK)
        assertNull(reduce(network, OnboardingMutation.DisplayNameChanged("B")).error)
    }

    @Test
    fun `editing after success keeps submit blocked until the session swaps the screen`() {
        val ok = reduce(filled.copy(isLoading = true), OnboardingMutation.Succeeded)
        assertFalse(reduce(ok, OnboardingMutation.DisplayNameChanged("B")).canSubmit)
        assertFalse(reduce(ok, OnboardingMutation.BirthDateChanged("1", "1", "1990")).canSubmit)
    }

    @Test
    fun `a future date is a local field error and not a minor hint`() {
        val future = reduce(filled, OnboardingMutation.BirthDateChanged("1", "1", "2030"))
        assertTrue(future.birthDateInFuture)
        assertFalse(future.ageHint)
        val submitted = reduce(future, OnboardingMutation.SubmitRequested)
        assertEquals(setOf(ProfileField.BIRTH_DATE), submitted.fieldErrors)
        assertFalse(submitted.isLoading)
        assertFalse(reduce(future, OnboardingMutation.BirthDateChanged("1", "1", "1990")).birthDateInFuture)
        assertFalse(reduce(filled, OnboardingMutation.BirthDateChanged("1", "1", "")).birthDateInFuture)
    }

    @Test
    fun `an implausible year is built as a date and left to the server`() {
        for (year in listOf("0000", "0001")) {
            val s = reduce(filled, OnboardingMutation.BirthDateChanged("15", "6", year))
            assertEquals(LocalDate.of(year.toInt(), 6, 15), s.birthDate)
            assertFalse(s.ageHint)
            assertFalse(s.birthDateInFuture)
            assertTrue(reduce(s, OnboardingMutation.SubmitRequested).isLoading)
        }
    }

    @Test
    fun `names follow the registration rules`() {
        val emoji = String(Character.toChars(0x1F3BB))
        fun errors(name: String): Set<ProfileField> {
            val s = filled.copy(displayName = name)
            return reduce(s, OnboardingMutation.SubmitRequested).fieldErrors
        }
        assertTrue(errors("x".repeat(40)).isEmpty())
        assertEquals(setOf(ProfileField.DISPLAY_NAME), errors("x".repeat(41)))
        assertTrue(errors(emoji.repeat(40)).isEmpty())
        assertEquals(setOf(ProfileField.DISPLAY_NAME), errors(emoji.repeat(41)))
        assertEquals(setOf(ProfileField.DISPLAY_NAME), errors(" \t "))
        assertTrue(errors("Ana $emoji").isEmpty())
    }

    @Test
    fun `legacy and script locales still give a tag the server accepts`() {
        val valid = Regex("^[a-z]{2}(-[A-Z]{2})?$")
        for (locale in listOf(Locale("in"), Locale("iw"), Locale.forLanguageTag("zh-Hant-TW"), Locale("in", "ID"))) {
            assertTrue(valid.matches(localeTagOf(locale)), localeTagOf(locale))
        }
        assertEquals("zh-TW", localeTagOf(Locale.forLanguageTag("zh-Hant-TW")))
    }
}
