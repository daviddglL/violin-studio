package com.violinstudio

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.ui.feature.auth.view.AUTH_EMAIL_TAG
import com.violinstudio.ui.feature.auth.view.AUTH_SUBMIT_TAG
import com.violinstudio.ui.feature.auth.view.VERIFY_EMAIL_TAG
import com.violinstudio.ui.feature.guardian.view.GUARDIAN_REQUEST_TAG
import com.violinstudio.ui.feature.guardian.view.GUARDIAN_WAIT_TAG
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** [G6][G3][AD6][AD1] Recorridos del menor (13 anos) y borrado desde los estados previos a Ready. */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MinorJourneyTest : E2eTest() {
    // Nacido el 1 de enero hace 13 anos: tiene 13 en cualquier fecha del ano, siempre por debajo de los 14.
    private val minorBirthYear = LocalDate.now().year - 13

    /** Registro -> verificacion -> onboarding de menor -> email del tutor -> espera del tutor. */
    private fun reachGuardianWait(email: String, guardianEmail: String) {
        journey.register(email)
        journey.verifyEmail(email)
        journey.onboard(minorBirthYear)
        journey.waitForTag(GUARDIAN_REQUEST_TAG)
        journey.type(AUTH_EMAIL_TAG, guardianEmail)
        journey.click(AUTH_SUBMIT_TAG)
        journey.waitForTag(GUARDIAN_WAIT_TAG)
    }

    @Test
    fun minorWaitsForTheGuardianAndBecomesReadyWhenTheGuardianConfirms() {
        val email = uniqueEmail("minor")
        reachGuardianWait(email, uniqueEmail("tutor"))
        val uid = checkNotNull(Emulators.authUser(email)).getString("localId")

        // El tutor abre el enlace del correo (guardado en `mail/` por el emulador) y acepta.
        var link: Pair<String, String>? = null
        awaitBackend("correo al tutor en mail/") {
            link = Emulators.guardianLink(uid)
            true
        }
        val (requestId, token) = checkNotNull(link)
        assertEquals(200, Emulators.confirmGuardian(requestId, token))

        // La sesion cambia sola (listener del perfil) y la espera desaparece.
        compose.waitUntilExactlyOneExists(hasTestTag("home_settings"), E2E_TIMEOUT_MS)
        awaitBackend("consentStatus=granted y un consentimiento tras la confirmacion del tutor") {
            Emulators.stringField("users/$uid", "consentStatus") == "granted" && Emulators.consentCount(uid) >= 1
        }
    }

    @Test
    fun accountCanBeDeletedFromEmailUnverified() {
        val email = uniqueEmail("unverified")
        journey.register(email)
        val uid = checkNotNull(Emulators.authUser(email)).getString("localId")
        journey.waitForTag(VERIFY_EMAIL_TAG)

        journey.deleteAccount()

        awaitBackend("cuenta de Auth borrada") { Emulators.authUser(email) == null }
        assertNull(Emulators.authUser(email))
        awaitBackend("users/$uid inexistente") { !Emulators.docExists("users/$uid") }
    }

    @Test
    fun accountCanBeDeletedFromParentalPendingAndTheGuardianLinkDies() {
        val email = uniqueEmail("pending")
        reachGuardianWait(email, uniqueEmail("tutor"))
        val uid = checkNotNull(Emulators.authUser(email)).getString("localId")
        awaitBackend("correo al tutor en mail/") { Emulators.docsOwnedBy("mail", uid).isNotEmpty() }
        val (requestId, token) = Emulators.guardianLink(uid)

        journey.deleteAccount()

        awaitBackend("cuenta, perfil, correo y solicitud borrados") {
            Emulators.authUser(email) == null &&
                !Emulators.docExists("users/$uid") &&
                Emulators.docsOwnedBy("mail", uid).isEmpty() &&
                Emulators.docsOwnedBy("guardianRequests", uid).isEmpty()
        }
        // El enlace que el tutor ya tenia ya no sirve (404 generico).
        assertEquals(404, Emulators.confirmGuardian(requestId, token))
    }
}
