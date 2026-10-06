package com.violinstudio

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.ui.feature.metronome.view.METRONOME_BACK_TAG
import com.violinstudio.ui.feature.metronome.view.METRONOME_TAG
import com.violinstudio.ui.feature.practice.view.PRACTICE_BACK_TAG
import com.violinstudio.ui.feature.practice.view.PRACTICE_DELETE_CONFIRM_TAG
import com.violinstudio.ui.feature.practice.view.PRACTICE_EMPTY_TAG
import com.violinstudio.ui.feature.practice.view.PRACTICE_NOTES_FIELD_TAG
import com.violinstudio.ui.feature.practice.view.PRACTICE_SAVE_DIALOG_TAG
import com.violinstudio.ui.feature.practice.view.PRACTICE_SAVE_TAG
import com.violinstudio.ui.feature.practice.view.PRACTICE_START_TAG
import com.violinstudio.ui.feature.practice.view.PRACTICE_STOP_TAG
import com.violinstudio.ui.feature.practice.view.PRACTICE_TAG
import com.violinstudio.ui.feature.practice.view.practiceItemDeleteTag
import com.violinstudio.ui.feature.tuner.view.TUNER_BACK_TAG
import com.violinstudio.ui.feature.tuner.view.TUNER_TAG
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [REQ-PRA-12][REQ-NAV-P01] Usuario `Ready` con consentimiento contra los emuladores: Home -> Practica -> iniciar/parar
 * -> guardar con notas -> aparece en el historial y en Firestore -> borrar -> el doc desaparece; y navegacion a afinador
 * y metronomo. No se toca el micro: el afinador solo se abre y se cierra.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PracticeFlowE2ETest : E2eTest() {
    private val notes = "Escala de re mayor E2E"

    @Test
    fun practiceSessionIsSavedListedAndDeleted() {
        val email = uniqueEmail("practice")
        journey.registerUpToHome(email)
        val uid = checkNotNull(Emulators.authUser(email)).getString("localId")

        journey.click("home_practice")
        journey.waitForTag(PRACTICE_TAG)
        journey.waitForTag(PRACTICE_EMPTY_TAG)

        journey.click(PRACTICE_START_TAG)
        journey.waitForTag(PRACTICE_STOP_TAG)
        Thread.sleep(2_500) // la duracion minima guardable es 1 s
        journey.click(PRACTICE_STOP_TAG)
        journey.waitForTag(PRACTICE_SAVE_DIALOG_TAG)
        journey.type(PRACTICE_NOTES_FIELD_TAG, notes)
        journey.click(PRACTICE_SAVE_TAG)

        awaitBackend("la sesion en users/$uid/practiceSessions") { Emulators.practiceSessions(uid).size == 1 }
        val doc = Emulators.practiceSessions(uid).single()
        assertEquals(notes, doc.getJSONObject("fields").getJSONObject("notes").getString("stringValue"))
        journey.waitForText(notes)

        journey.click(practiceItemDeleteTag(Emulators.docId(doc)))
        journey.click(PRACTICE_DELETE_CONFIRM_TAG)
        awaitBackend("la sesion borrada de Firestore") { Emulators.practiceSessions(uid).isEmpty() }
        journey.waitForTag(PRACTICE_EMPTY_TAG)
    }

    @Test
    fun homeNavigatesToTunerAndMetronome() {
        journey.registerUpToHome(uniqueEmail("nav"))

        // Sin conceder el micro: solo se comprueba que la pantalla del afinador abre (no se arranca ninguna captura).
        journey.click("home_tuner")
        journey.waitForTag(TUNER_TAG)
        journey.click(TUNER_BACK_TAG)

        journey.waitForTag("home_metronome")
        journey.click("home_metronome")
        journey.waitForTag(METRONOME_TAG)
        journey.click(METRONOME_BACK_TAG)

        journey.waitForTag("home_practice")
        journey.click("home_practice")
        journey.waitForTag(PRACTICE_TAG)
        journey.click(PRACTICE_BACK_TAG)
        journey.waitForTag("home_settings")
    }
}
