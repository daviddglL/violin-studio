package com.violinstudio

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.ui.R
import com.violinstudio.ui.feature.auth.view.AUTH_EMAIL_TAG
import com.violinstudio.ui.feature.auth.view.AUTH_PASSWORD_TAG
import com.violinstudio.ui.feature.auth.view.AUTH_SUBMIT_TAG
import com.violinstudio.ui.feature.auth.view.LOGIN_TAG
import com.violinstudio.ui.feature.settings.view.SETTINGS_REVOKE_CONFIRM_TAG
import com.violinstudio.ui.feature.settings.view.SETTINGS_REVOKE_TAG
import com.violinstudio.ui.feature.settings.view.SETTINGS_TAG
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** [A1][A2][AD6][C5][AD2] Recorrido completo de un adulto contra los emuladores, sin simular ninguna capa de la app. */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AdultJourneyTest : E2eTest() {
    private fun awaitConsent(uid: String, status: String, atLeast: Int) =
        awaitBackend("users/$uid con consentStatus=$status y >= $atLeast consentimientos") {
            Emulators.stringField("users/$uid", "consentStatus") == status && Emulators.consentCount(uid) >= atLeast
        }

    private fun openSettings() {
        journey.click("home_settings")
        journey.waitForTag(SETTINGS_TAG)
    }

    @Test
    fun adultRegistersConsentsRevokesReconsentsAndDeletesTheAccount() {
        val email = uniqueEmail("adult")
        journey.registerUpToHome(email)
        val uid = checkNotNull(Emulators.authUser(email)).getString("localId")
        awaitConsent(uid, "granted", atLeast = 1)

        // Home real tras la sesion (sustituye al antiguo test de salud, que ya no llegaba a Home sin sesion).
        journey.clickText(journey.string(R.string.home_check_health))
        compose.waitUntilExactlyOneExists(hasTestTag("health_ok"), E2E_TIMEOUT_MS)

        openSettings()
        journey.click(SETTINGS_REVOKE_TAG)
        journey.click(SETTINGS_REVOKE_CONFIRM_TAG)
        // Revocar devuelve a la pantalla de consentimiento con el motivo REVOKED.
        journey.waitForText(journey.string(R.string.consent_title_revoked))
        awaitConsent(uid, "revoked", atLeast = 1)
        val before = Emulators.consentCount(uid)
        journey.acceptPolicy()
        journey.waitForTag("home_settings")
        awaitConsent(uid, "granted", atLeast = before + 1)

        openSettings()
        journey.deleteAccount()

        awaitBackend("la cuenta de Auth borrada") { Emulators.authUser(email) == null }
        awaitBackend("users/$uid borrado") { !Emulators.docExists("users/$uid") }

        // No puede volver a entrar con las mismas credenciales.
        journey.type(AUTH_EMAIL_TAG, email)
        journey.type(AUTH_PASSWORD_TAG, E2E_PASSWORD)
        journey.click(AUTH_SUBMIT_TAG)
        journey.waitForText(journey.string(R.string.login_error_invalid))
        journey.waitForTag(LOGIN_TAG)
        assertNull(Emulators.authUser(email))
    }

    @Test
    fun deletingTheAccountLeavesNothingInTheBackend() {
        val email = uniqueEmail("erase")
        journey.registerUpToHome(email)
        val uid = checkNotNull(Emulators.authUser(email)).getString("localId")
        // Precondiciones: hay datos que borrar (perfil y al menos un consentimiento).
        assertTrue(Emulators.docExists("users/$uid"))
        assertTrue(Emulators.docs("users/$uid/consents").isNotEmpty())

        openSettings()
        journey.deleteAccount()

        awaitBackend("Auth, perfil y consentimientos borrados") {
            Emulators.authUser(email) == null &&
                !Emulators.docExists("users/$uid") &&
                Emulators.docs("users/$uid/consents").isEmpty()
        }
        assertNull(Emulators.authUser(email))
        assertEquals(0, Emulators.docsOwnedBy("mail", uid).size)
        assertEquals(0, Emulators.docsOwnedBy("guardianRequests", uid).size)
    }
}
