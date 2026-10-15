package com.violinstudio

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.assignment.failure.AssignmentFailure
import com.violinstudio.domain.feature.assignment.model.AssignmentDraft
import com.violinstudio.domain.feature.assignment.model.AssignmentStatus
import com.violinstudio.domain.feature.assignment.repository.AssignmentRepository
import com.violinstudio.domain.feature.link.model.LinkNotice
import com.violinstudio.domain.feature.link.repository.LinkRepository
import com.violinstudio.domain.feature.practice.failure.PracticeFailure
import com.violinstudio.domain.feature.practice.repository.PracticeLogRepository
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
 * Recorrido del vinculo de punta a punta contra los emuladores (Auth, Firestore, Functions con las reglas reales)
 * [REQ-LNK-03][REQ-TVW-02][REQ-ASG-03][REQ-LNK-08]: el profesor crea un codigo, el alumno lo canjea (con el aviso),
 * el profesor ve una sesion con notas, crea una tarea, el alumno la marca, se desvincula y el profesor pierde el
 * acceso (con los oyentes activos terminando en `PermissionDenied`, sin cierre de la app).
 *
 * El usuario de la app (registrado por la interfaz) hace de profesor: se le da el rol en su documento (lo que hace
 * `admin:grant-teacher`) y actua por los repositorios reales. El alumno es una cuenta del emulador a la que se llama
 * como lo haria su cliente (callables y escrituras de Firestore con su ID token, sujetas a las reglas); su sesion
 * de practica se siembra con el token de administrador.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TeacherStudentFlowE2ETest : E2eTest() {
    @Inject
    lateinit var links: LinkRepository

    @Inject
    lateinit var assignments: AssignmentRepository

    @Inject
    lateinit var practice: PracticeLogRepository

    private fun int(value: Int) = JSONObject().put("integerValue", value.toString())

    private fun ts(value: Instant) = JSONObject().put("timestampValue", value.toString())

    private fun draft(id: String, title: String) = AssignmentDraft.create(id, title, null, null).getOrThrow()

    @Test
    fun codeRedeemSessionAssignmentDoneUnlinkAndRevokedAccess() = runBlocking {
        // Profesor: alta por la interfaz y rol de profesor en su documento.
        val teacherEmail = uniqueEmail("teacher")
        journey.registerUpToHome(teacherEmail)
        val teacher = checkNotNull(Emulators.authUser(teacherEmail)).getString("localId")
        Emulators.patchField("users/$teacher", "role", Emulators.stringValue("teacher"))
        val policyVersion = checkNotNull(Emulators.docFields("users/$teacher"))
            .getJSONObject("policyVersion").getString("integerValue").toInt()

        // [REQ-LNK-03] El profesor crea un codigo (en claro solo en esta respuesta).
        val code = checkNotNull(links.createCode().getOrThrow().code)

        // Alumno: cuenta verificada con perfil y consentimiento vigentes (lo que dejan registerProfile/recordConsent).
        val studentEmail = uniqueEmail("student")
        val student = Emulators.signUp(studentEmail)
        Emulators.markEmailVerified(studentEmail)
        Emulators.seedDoc(
            "users/$student",
            JSONObject()
                .put("role", Emulators.stringValue("independent"))
                .put("isMinor", JSONObject().put("booleanValue", false))
                .put("birthDate", Emulators.stringValue("1990-01-01"))
                .put("displayName", Emulators.stringValue("Alumno E2E"))
                .put("consentStatus", Emulators.stringValue("granted"))
                .put("policyVersion", int(policyVersion))
        )
        Emulators.setCustomClaims(student, """{"role":"independent","consentOk":true}""")
        var studentToken = Emulators.idToken(studentEmail)

        // El aviso es obligatorio: con una version antigua no se canjea y el codigo sigue valido.
        val stale = Emulators.callAs(
            studentToken,
            "redeemTeacherCode",
            JSONObject().put("code", code).put("ackVersion", LinkNotice.VERSION - 1)
        )
        assertEquals("NOTICE_OUTDATED rechazado", 400, stale.code)
        assertTrue("estado FAILED_PRECONDITION: ${stale.body}", stale.body.contains("FAILED_PRECONDITION"))
        assertTrue("motivo NOTICE_OUTDATED: ${stale.body}", stale.body.contains("NOTICE_OUTDATED"))
        assertFalse(Emulators.docExists("teacherLinks/${teacher}_$student"))

        val redeemed = Emulators.callAs(
            studentToken,
            "redeemTeacherCode",
            JSONObject().put("code", code).put("ackVersion", LinkNotice.VERSION)
        )
        assertEquals("canje: ${redeemed.body}", 200, redeemed.code)
        assertTrue(Emulators.docExists("teacherLinks/${teacher}_$student"))
        assertEquals("student", Emulators.stringField("users/$student", "role"))
        studentToken = Emulators.idToken(studentEmail) // claims nuevos tras el canje

        // [REQ-TVW-02] El profesor ve la sesion del alumno con sus notas (reglas de lectura del profesor vinculado).
        val sessionId = UUID.randomUUID().toString()
        val started = Instant.now().minusSeconds(900)
        Emulators.seedDoc(
            "users/$student/practiceSessions/$sessionId",
            JSONObject()
                .put("startedAt", ts(started))
                .put("durationSec", int(600))
                .put("instrument", Emulators.stringValue("violin"))
                .put("notes", Emulators.stringValue("Escalas lentas"))
                .put("createdAt", ts(Instant.now()))
        )
        val seen = withTimeout(E2E_TIMEOUT_MS) {
            practice.observeHistory(student).first { list -> list.any { it.id == sessionId } }
        }.first { it.id == sessionId }
        assertEquals("Escalas lentas", seen.notes)

        // [REQ-ASG-03] El profesor crea una tarea abierta; el alumno la marca como hecha con sus reglas.
        val doneId = UUID.randomUUID().toString()
        val openId = UUID.randomUUID().toString()
        assertTrue(assignments.create(student, teacher, draft(doneId, "Escalas")).isSuccess)
        assertTrue(assignments.create(student, teacher, draft(openId, "Arpegios")).isSuccess)
        awaitBackend("las tareas creadas") { Emulators.docs("users/$student/assignments").size == 2 }
        val marked = Emulators.setAssignmentDoneAs(studentToken, student, doneId)
        assertEquals("marcar hecha: ${marked.body}", 200, marked.code)
        val doneForTeacher = withTimeout(E2E_TIMEOUT_MS) {
            assignments.observeByTeacher(teacher, student).first { l ->
                l.any { it.id == doneId && it.status == AssignmentStatus.DONE && !it.pendingSync }
            }
        }.first { it.id == doneId }
        assertNotNull(doneForTeacher.doneAt)

        // [REQ-LNK-08] Desvincular con los oyentes del profesor activos: terminan en PermissionDenied (A7b S2).
        val historyEnds = async(Dispatchers.IO) { runCatching { practice.observeHistory(student).collect { } } }
        val assignmentsEnd = async(Dispatchers.IO) {
            runCatching { assignments.observeByTeacher(teacher, student).collect { } }
        }
        delay(2_000)
        assertTrue(links.unlink(teacher, student).isSuccess)
        // Firestore solo revalida las reglas de un oyente activo cuando cambia un doc de su resultado (no al borrar el
        // vinculo que consultan con get()): se toca un doc escuchado de cada coleccion para forzar la revalidacion.
        Emulators.patchField(
            "users/$student/practiceSessions/$sessionId",
            "notes",
            Emulators.stringValue("Escalas lentas (editada)")
        )
        Emulators.patchField("users/$student/assignments/$doneId", "title", Emulators.stringValue("Escalas (editada)"))
        val historyError = withTimeout(E2E_TIMEOUT_MS) { historyEnds.await() }.exceptionOrNull()
        assertTrue("historial: $historyError", historyError is PracticeFailure.PermissionDenied)
        val listError = withTimeout(E2E_TIMEOUT_MS) { assignmentsEnd.await() }.exceptionOrNull()
        assertTrue("tareas: $listError", listError is AssignmentFailure.PermissionDenied)

        // El vinculo desaparece, el alumno vuelve a independiente, las tareas abiertas se borran y las hechas se conservan.
        awaitBackend("vinculo borrado") { !Emulators.docExists("teacherLinks/${teacher}_$student") }
        awaitBackend("alumno independiente") { Emulators.stringField("users/$student", "role") == "independent" }
        awaitBackend("tarea abierta borrada") { !Emulators.docExists("users/$student/assignments/$openId") }
        assertTrue(Emulators.docExists("users/$student/assignments/$doneId"))

        // Una lectura nueva del profesor ya no se permite.
        val failure = runCatching { practice.observeHistory(student).first() }.exceptionOrNull()
        assertTrue("lectura tras desvincular: $failure", failure is PracticeFailure.PermissionDenied)
        val assignmentsFailure = runCatching {
            assignments.observeByTeacher(teacher, student).first()
        }.exceptionOrNull()
        assertTrue(assignmentsFailure is AssignmentFailure.PermissionDenied)
    }
}
