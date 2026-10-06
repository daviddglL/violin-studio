package com.violinstudio

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.violinstudio.domain.feature.practice.model.PracticeDraft
import com.violinstudio.domain.feature.practice.repository.PracticeLogRepository
import com.violinstudio.domain.feature.profile.model.Instrument
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [REQ-PRA-05] Contrato del repositorio de practica contra el emulador de Firestore con los tipos REALES del SDK
 * (Int -> long, `Timestamp`, `serverTimestamp`) y las reglas desplegadas: crear, actualizar notas, borrar y una escritura
 * rechazada. Las escrituras del repositorio no esperan al servidor, asi que todo se comprueba contra el backend (REST).
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PracticeLogContractE2ETest : E2eTest() {
    @Inject
    lateinit var practiceLog: PracticeLogRepository

    @Inject
    lateinit var db: FirebaseFirestore

    private fun fields(uid: String, id: String): JSONObject? =
        Emulators.practiceSessions(uid).firstOrNull { Emulators.docId(it) == id }?.getJSONObject("fields")

    private fun draft(id: String, notes: String?) =
        PracticeDraft.create(id, Instant.now().minusSeconds(300), 125, Instrument.CELLO, notes, Instant.now())
            .getOrThrow()

    @Test
    fun createUpdateNotesDeleteAndRejectedWrite() = runBlocking {
        val email = uniqueEmail("contract")
        journey.registerUpToHome(email)
        val uid = checkNotNull(Emulators.authUser(email)).getString("localId")
        val id = UUID.randomUUID().toString()

        // Crear: Int -> integerValue, startedAt/createdAt -> timestampValue (createdAt lo pone el servidor).
        assertTrue(practiceLog.create(uid, draft(id, "primera")).isSuccess)
        awaitBackend("la sesion creada") { fields(uid, id) != null }
        val created = checkNotNull(fields(uid, id))
        assertEquals("125", created.getJSONObject("durationSec").getString("integerValue"))
        assertEquals("cello", created.getJSONObject("instrument").getString("stringValue"))
        assertEquals("primera", created.getJSONObject("notes").getString("stringValue"))
        assertTrue(created.getJSONObject("startedAt").has("timestampValue"))
        assertTrue(created.getJSONObject("createdAt").has("timestampValue"))

        // El historial parsea los tipos reales del SDK y la escritura acaba confirmada por el servidor.
        val synced = withTimeout(E2E_TIMEOUT_MS) {
            practiceLog.observeHistory(uid).first { list -> list.any { it.id == id && !it.pendingSync } }
        }.first { it.id == id }
        assertEquals(125, synced.durationSec)
        assertEquals(Instrument.CELLO, synced.instrument)

        // Actualizar notas, y quitarlas con null.
        assertTrue(practiceLog.updateNotes(uid, id, "editada").isSuccess)
        awaitBackend("notas editadas") {
            fields(uid, id)?.optJSONObject("notes")?.optString("stringValue") == "editada"
        }
        assertTrue(practiceLog.updateNotes(uid, id, null).isSuccess)
        awaitBackend("notas borradas") { fields(uid, id)?.has("notes") == false }

        // Escritura rechazada por las reglas (doc de otro uid): la Task de la escritura falla con PERMISSION_DENIED
        // (el repositorio no espera al servidor, asi que se escribe con el SDK directamente) y no llega al servidor.
        val otherUid = "otro-" + UUID.randomUUID().toString().take(8)
        val rejectedId = UUID.randomUUID().toString()
        val rejected = db.collection("users").document(otherUid).collection("practiceSessions").document(rejectedId)
            .set(mapOf("durationSec" to 60, "instrument" to "violin"))
        val denied = try {
            Tasks.await(rejected, E2E_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            null
        } catch (e: ExecutionException) {
            e.cause as? FirebaseFirestoreException
        }
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, denied?.code)
        val okId = UUID.randomUUID().toString()
        assertTrue(practiceLog.create(uid, draft(okId, null)).isSuccess)
        awaitBackend("la sesion valida posterior") { fields(uid, okId) != null }
        assertFalse(Emulators.docExists("users/$otherUid/practiceSessions/$rejectedId"))

        // Borrar.
        assertTrue(practiceLog.delete(uid, id).isSuccess)
        awaitBackend("la sesion borrada") { fields(uid, id) == null }
        assertTrue(practiceLog.delete(uid, okId).isSuccess)
        awaitBackend("todas borradas") { Emulators.practiceSessions(uid).isEmpty() }
    }
}
