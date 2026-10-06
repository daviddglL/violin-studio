package com.violinstudio

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.violinstudio.domain.feature.practice.model.PracticeDraft
import com.violinstudio.domain.feature.practice.repository.PracticeLogRepository
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.ui.feature.settings.view.SETTINGS_TAG
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [Cascada completa de datos][Borrado desde el cliente] Borrar la cuenta con sesiones de practica deja el backend sin
 * `users/{uid}/practiceSessions` y marca la purga de la cache offline de Firestore para el siguiente arranque.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AccountErasureE2ETest : E2eTest() {
    @Inject
    lateinit var practiceLog: PracticeLogRepository

    private fun purgeRequested(): Boolean = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences("local_erasure", Context.MODE_PRIVATE).getBoolean("purge_firestore_cache", false)

    private fun clearPurgeFlag() {
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("local_erasure", Context.MODE_PRIVATE)
        check(prefs.edit().remove("purge_firestore_cache").commit())
    }

    @Test
    fun deletingTheAccountRemovesPracticeSessionsAndSchedulesCachePurge() {
        val email = uniqueEmail("erase-practice")
        journey.registerUpToHome(email)
        val uid = checkNotNull(Emulators.authUser(email)).getString("localId")
        val start = Instant.now().minusSeconds(120)
        repeat(2) { n ->
            val id = UUID.randomUUID().toString()
            val draft = PracticeDraft.create(id, start, 60 + n, Instrument.VIOLIN, "nota $n", Instant.now())
                .getOrThrow()
            assertTrue(runBlocking { practiceLog.create(uid, draft) }.isSuccess)
        }
        awaitBackend("2 sesiones en users/$uid/practiceSessions") { Emulators.practiceSessions(uid).size == 2 }

        // La marca persiste entre tests del proceso: se baja antes para que la comprobacion final no sea vacua.
        clearPurgeFlag()
        assertFalse(purgeRequested())

        journey.click("home_settings")
        journey.waitForTag(SETTINGS_TAG)
        journey.deleteAccount()

        awaitBackend("practiceSessions y users/$uid borrados") {
            Emulators.practiceSessions(uid).isEmpty() && !Emulators.docExists("users/$uid")
        }
        // El siguiente arranque purga la cache offline (el borrado deja la marca; FirestoreCachePurge la consume).
        assertTrue("falta la marca de purga de la cache", purgeRequested())
        assertFalse(Emulators.docExists("users/$uid"))
    }
}
