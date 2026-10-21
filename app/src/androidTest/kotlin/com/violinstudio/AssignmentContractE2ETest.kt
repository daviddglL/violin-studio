package com.violinstudio

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.firestore.FirebaseFirestore
import com.violinstudio.domain.feature.assignment.model.AssignmentDraft
import com.violinstudio.domain.feature.assignment.model.AssignmentStatus
import com.violinstudio.domain.feature.assignment.repository.AssignmentRepository
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [REQ-ASG-02] Contrato del repositorio de tareas contra el emulador de Firestore con los tipos REALES del SDK
 * (`Timestamp`, `serverTimestamp`, `FieldValue.delete()`) y las reglas desplegadas: el mapa que escribe
 * `AssignmentRepositoryImpl` pasa `validAssignment` al crear y al editar, y una escritura sin vinculo se rechaza.
 * El usuario de la app hace de profesor; el alumno y el vinculo (que solo escribe el backend) se siembran por REST.
 * Las escrituras del repositorio no esperan al servidor, asi que todo se comprueba contra el backend (REST).
 * La parte del alumno (`doneFields`) la cubren los rules-tests con el mismo mapa: requiere sesion como alumno.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AssignmentContractE2ETest : E2eTest() {
    @Inject
    lateinit var assignments: AssignmentRepository

    @Inject
    lateinit var db: FirebaseFirestore

    private fun str(value: String) = JSONObject().put("stringValue", value)

    private fun path(student: String, id: String) = "users/$student/assignments/$id"

    private fun draft(id: String, title: String, description: String?, dueAt: Instant?) =
        AssignmentDraft.create(id, title, description, dueAt).getOrThrow()

    @Test
    fun createUpdateDeleteAndUnlinkedWriteRejected() = runBlocking {
        val email = uniqueEmail("assignment")
        journey.registerUpToHome(email)
        val teacher = checkNotNull(Emulators.authUser(email)).getString("localId")
        val student = "alumno-" + UUID.randomUUID().toString().take(8)
        // Lo que normalmente crean las Functions: alumno activo y vinculo profesor-alumno.
        Emulators.seedDoc("users/$student", JSONObject().put("consentStatus", str("granted")))
        Emulators.seedDoc(
            "teacherLinks/${teacher}_$student",
            JSONObject().put("teacherUid", str(teacher)).put("studentUid", str(student))
        )
        val id = UUID.randomUUID().toString()
        val due = Instant.parse("2027-01-15T10:00:00Z")

        // Crear con todos los campos: tipos reales, createdAt/updatedAt = request.time, sin doneAt.
        assertTrue(assignments.create(student, teacher, draft(id, "Escalas", "Do mayor", due)).isSuccess)
        awaitBackend("la tarea creada") { Emulators.docFields(path(student, id)) != null }
        val created = checkNotNull(Emulators.docFields(path(student, id)))
        assertEquals(teacher, created.getJSONObject("teacherUid").getString("stringValue"))
        assertEquals("Escalas", created.getJSONObject("title").getString("stringValue"))
        assertEquals("Do mayor", created.getJSONObject("description").getString("stringValue"))
        assertTrue(created.getJSONObject("dueAt").has("timestampValue"))
        assertEquals("open", created.getJSONObject("status").getString("stringValue"))
        assertTrue(created.getJSONObject("createdAt").has("timestampValue"))
        assertTrue(created.getJSONObject("updatedAt").has("timestampValue"))
        assertFalse(created.has("doneAt"))

        // El profesor la ve parseada con los tipos del SDK y confirmada por el servidor.
        val synced = withTimeout(E2E_TIMEOUT_MS) {
            assignments.observeByTeacher(teacher, student).first { l -> l.any { it.id == id && !it.pendingSync } }
        }.first { it.id == id }
        assertEquals(AssignmentStatus.OPEN, synced.status)
        assertEquals(due, synced.dueAt)
        assertNotNull(synced.createdAt)

        // Editar: cambia el titulo y QUITA descripcion y dueAt con FieldValue.delete() (null no pasa las reglas).
        assertTrue(assignments.update(student, draft(id, "Arpegios", null, null)).isSuccess)
        awaitBackend("tarea editada") {
            val f = Emulators.docFields(path(student, id))
            f?.optJSONObject("title")?.optString("stringValue") == "Arpegios" &&
                !f.has("description") && !f.has("dueAt")
        }
        val edited = checkNotNull(Emulators.docFields(path(student, id)))
        assertEquals("open", edited.getJSONObject("status").getString("stringValue"))
        assertEquals(teacher, edited.getJSONObject("teacherUid").getString("stringValue"))

        // Escritura sin vinculo (otro alumno): las reglas la rechazan y no llega al servidor; la valida posterior si.
        val stranger = "ajeno-" + UUID.randomUUID().toString().take(8)
        Emulators.seedDoc("users/$stranger", JSONObject().put("consentStatus", str("granted")))
        val rejectedId = UUID.randomUUID().toString()
        assertTrue(assignments.create(stranger, teacher, draft(rejectedId, "Sin vinculo", null, null)).isSuccess)
        val okId = UUID.randomUUID().toString()
        assertTrue(assignments.create(student, teacher, draft(okId, "Valida", null, null)).isSuccess)
        awaitBackend("la tarea valida posterior") { Emulators.docFields(path(student, okId)) != null }
        assertFalse(Emulators.docExists(path(stranger, rejectedId)))

        // Borrar las propias.
        assertTrue(assignments.delete(student, id).isSuccess)
        awaitBackend("la tarea borrada") { !Emulators.docExists(path(student, id)) }
        assertTrue(assignments.delete(student, okId).isSuccess)
        awaitBackend("todas borradas") { Emulators.docs("users/$student/assignments").isEmpty() }
    }
}
