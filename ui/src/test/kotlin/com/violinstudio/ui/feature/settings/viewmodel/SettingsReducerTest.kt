package com.violinstudio.ui.feature.settings.viewmodel

import com.violinstudio.domain.feature.profile.failure.ProfileField
import com.violinstudio.domain.feature.profile.model.Instrument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingsReducerTest {
    private val ana = SettingsFields("Ana", Instrument.VIOLIN, "es-ES")
    private val loaded = SettingsReducer.reduce(SettingsState(), SettingsMutation.ProfileLoaded(ana))

    private fun reduce(s: SettingsState, m: SettingsMutation) = SettingsReducer.reduce(s, m)

    @Test
    fun `the first profile seeds the three editable fields and marks the form loaded`() {
        assertFalse(SettingsState().loaded)
        assertTrue(loaded.loaded)
        assertEquals(ana, loaded.fields)
        assertFalse(loaded.dirty)
        assertFalse(loaded.canSave)
    }

    @Test
    fun `the editable state exposes only displayName, instrument and locale`() {
        val names = SettingsFields::class.java.declaredFields.map { it.name }
            .filterNot { it.startsWith("$") || it == "Companion" }
            .toSet()
        assertEquals(setOf("displayName", "instrument", "locale"), names)
    }

    @Test
    fun `editing each field changes it, makes the form dirty and clears errors and the saved notice`() {
        val noisy = loaded.copy(
            fieldErrors = setOf(ProfileField.DISPLAY_NAME, ProfileField.LOCALE),
            saved = true,
            error = SettingsError.NETWORK
        )
        val named = reduce(noisy, SettingsMutation.DisplayNameChanged("Ana Maria"))
        assertEquals("Ana Maria", named.fields.displayName)
        assertEquals(setOf(ProfileField.LOCALE), named.fieldErrors)
        assertFalse(named.saved)
        assertNull(named.error)
        assertTrue(named.canSave)
        val cello = reduce(loaded, SettingsMutation.InstrumentSelected(Instrument.CELLO))
        assertEquals(Instrument.CELLO, cello.fields.instrument)
        assertEquals("en", reduce(loaded, SettingsMutation.LocaleChanged("en")).fields.locale)
    }

    @Test
    fun `going back to the stored values is not dirty`() {
        val edited = reduce(loaded, SettingsMutation.LocaleChanged("en"))
        assertFalse(reduce(edited, SettingsMutation.LocaleChanged("es-ES")).canSave)
    }

    @Test
    fun `a later profile follows remote changes only while the form is untouched`() {
        val remote = ana.copy(instrument = Instrument.CELLO)
        val followed = reduce(loaded, SettingsMutation.ProfileLoaded(remote))
        assertEquals(remote, followed.fields)
        assertEquals(remote, followed.baseline)
        val editing = reduce(loaded, SettingsMutation.DisplayNameChanged("Bea"))
        val kept = reduce(editing, SettingsMutation.ProfileLoaded(remote))
        assertEquals("Bea", kept.fields.displayName)
        assertEquals(remote, kept.baseline)
    }

    @Test
    fun `saving validates locally and an invalid name or locale never starts the request`() {
        val badName = reduce(loaded, SettingsMutation.DisplayNameChanged("   "))
        val rejectedName = reduce(badName, SettingsMutation.SaveRequested)
        assertEquals(setOf(ProfileField.DISPLAY_NAME), rejectedName.fieldErrors)
        assertFalse(rejectedName.isSaving)
        val badLocale = reduce(loaded, SettingsMutation.LocaleChanged("espanol"))
        val rejectedLocale = reduce(badLocale, SettingsMutation.SaveRequested)
        assertEquals(setOf(ProfileField.LOCALE), rejectedLocale.fieldErrors)
        assertFalse(rejectedLocale.isSaving)
    }

    @Test
    fun `a valid save starts loading, success moves the baseline and shows the notice`() {
        val edited = reduce(loaded, SettingsMutation.DisplayNameChanged("Bea"))
        val saving = reduce(edited, SettingsMutation.SaveRequested)
        assertTrue(saving.isSaving)
        assertTrue(saving.busy)
        assertTrue(saving.fieldErrors.isEmpty())
        val done = reduce(saving, SettingsMutation.Saved(edited.fields.copy(displayName = "Bea")))
        assertFalse(done.isSaving)
        assertTrue(done.saved)
        assertEquals("Bea", done.baseline?.displayName)
        assertFalse(done.dirty)
    }

    @Test
    fun `failures keep the edits and unlock the form, except the terminal one`() {
        val saving = reduce(reduce(loaded, SettingsMutation.LocaleChanged("en")), SettingsMutation.SaveRequested)
        val failed = reduce(saving, SettingsMutation.SaveFailed(SettingsError.NOT_ALLOWED))
        assertEquals(SettingsError.NOT_ALLOWED, failed.error)
        assertEquals("en", failed.fields.locale)
        assertTrue(failed.canSave)
        assertFalse(reduce(saving, SettingsMutation.SaveFailed(SettingsError.UNAVAILABLE)).canSave)
        val rejected = reduce(saving, SettingsMutation.FieldRejected(ProfileField.LOCALE))
        assertEquals(setOf(ProfileField.LOCALE), rejected.fieldErrors)
        assertFalse(rejected.isSaving)
    }

    @Test
    fun `revoking needs a confirmation step that can be cancelled`() {
        val asked = reduce(loaded, SettingsMutation.RevokeAsked)
        assertTrue(asked.confirmingRevoke)
        assertFalse(reduce(asked, SettingsMutation.RevokeCancelled).confirmingRevoke)
        assertFalse(reduce(SettingsState(), SettingsMutation.RevokeAsked).confirmingRevoke)
    }

    @Test
    fun `revocation locks everything until the session changes`() {
        val revoking = reduce(reduce(loaded, SettingsMutation.RevokeAsked), SettingsMutation.RevokeStarted)
        assertTrue(revoking.isRevoking)
        assertFalse(revoking.confirmingRevoke)
        assertFalse(revoking.canRevoke)
        val done = reduce(revoking, SettingsMutation.RevokeSucceeded)
        assertTrue(done.revoked)
        assertFalse(done.isRevoking)
        assertTrue(done.busy)
        assertFalse(reduce(done, SettingsMutation.DisplayNameChanged("x")).canSave)
    }

    @Test
    fun `a retryable revoke failure allows another try and the terminal one does not`() {
        val revoking = reduce(loaded, SettingsMutation.RevokeStarted)
        val net = reduce(revoking, SettingsMutation.RevokeFailed(RevokeError.NETWORK))
        assertEquals(RevokeError.NETWORK, net.revokeError)
        assertTrue(net.canRevoke)
        assertFalse(reduce(revoking, SettingsMutation.RevokeFailed(RevokeError.UNAVAILABLE)).canRevoke)
    }

    @Test
    fun `toString redacts the display name`() {
        assertFalse(loaded.toString().contains("Ana"))
        assertFalse(ana.toString().contains("Ana"))
    }
}
