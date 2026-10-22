package com.violinstudio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.chat.failure.ChatFailure
import com.violinstudio.domain.feature.chat.model.ChatRules
import com.violinstudio.domain.feature.chat.repository.ChatRepository
import com.violinstudio.domain.feature.link.repository.LinkRepository
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Chat profesor-alumno (fase 4c) de punta a punta contra los emuladores con las reglas reales: el profesor (usuario de
 * la app) escribe por el repositorio real, el alumno escribe con su ID token por la API REST de Firestore, el profesor
 * ve los dos mensajes en orden, un tercero no puede escribir en la conversacion y al desvincular se borran los mensajes.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ChatE2ETest : E2eTest() {
    @Inject
    lateinit var links: LinkRepository

    @Inject
    lateinit var chat: ChatRepository

    @Test
    fun teacherAndStudentExchangeMessagesAndUnlinkDeletesThem() = runBlocking {
        val pair = linkTeacherAndStudent(journey, links)
        val teacher = pair.teacher
        val student = pair.student

        val fromTeacher = UUID.randomUUID().toString()
        val expireAt = Instant.now().plus(Duration.ofDays(ChatRules.RETENTION_DAYS))
        assertTrue(chat.send(teacher, student, teacher, fromTeacher, "Hola, ¿qué tal las escalas?", expireAt).isSuccess)
        awaitBackend("mensaje del profesor en el servidor") { Emulators.docExists("chatMessages/$fromTeacher") }

        val fromStudent = UUID.randomUUID().toString()
        val sent = Emulators.sendChatAs(pair.studentToken(), fromStudent, teacher, student, student, "Bien, gracias")
        assertEquals("mensaje del alumno: ${sent.body}", 200, sent.code)

        val thread = withTimeout(E2E_TIMEOUT_MS) {
            chat.observe(teacher, student).first { list ->
                list.count { !it.pendingSync && it.id in setOf(fromTeacher, fromStudent) } == 2
            }
        }
        assertEquals(listOf(fromTeacher, fromStudent), thread.map { it.id })
        assertEquals(student, thread.last().senderUid)

        // Un tercero sin vinculo no puede colarse en la conversacion (reglas: isChatParty).
        val outsiderEmail = uniqueEmail("outsider")
        val outsider = Emulators.signUp(outsiderEmail)
        Emulators.markEmailVerified(outsiderEmail)
        val intruder = Emulators.sendChatAs(
            Emulators.idToken(outsiderEmail),
            UUID.randomUUID().toString(),
            teacher,
            student,
            outsider,
            "Hola"
        )
        assertEquals("intruso: ${intruder.body}", 403, intruder.code)

        // Desvincular borra los mensajes del par y el profesor deja de poder leerlos.
        assertTrue(links.unlink(teacher, student).isSuccess)
        awaitBackend("mensajes borrados al desvincular") {
            !Emulators.docExists("chatMessages/$fromTeacher") && !Emulators.docExists("chatMessages/$fromStudent")
        }
        val failure = withTimeout(E2E_TIMEOUT_MS) {
            runCatching { chat.observe(teacher, student).collect { } }.exceptionOrNull()
        }
        assertTrue("lectura tras desvincular: $failure", failure is ChatFailure.PermissionDenied)
    }
}
