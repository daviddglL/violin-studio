package com.violinstudio

import com.violinstudio.domain.feature.link.model.LinkNotice
import com.violinstudio.domain.feature.link.repository.LinkRepository
import org.json.JSONObject
import org.junit.Assert.assertEquals

/** Profesor (usuario de la app) y alumno (cuenta del emulador) ya vinculados por un codigo canjeado. */
class LinkedPair(val teacher: String, val student: String, val studentEmail: String) {
    /** ID token fresco del alumno (los claims cambian con el canje). */
    fun studentToken(): String = Emulators.idToken(studentEmail)
}

/**
 * Registra al usuario de la app por la interfaz, le da el rol de profesor (lo que hace `admin:grant-teacher`), crea
 * una cuenta de alumno con perfil y consentimiento vigentes y la vincula canjeando un codigo del profesor, como en
 * `TeacherStudentFlowE2ETest`.
 */
suspend fun linkTeacherAndStudent(journey: Journey, links: LinkRepository): LinkedPair {
    val teacherEmail = uniqueEmail("teacher")
    journey.registerUpToHome(teacherEmail)
    val teacher = checkNotNull(Emulators.authUser(teacherEmail)).getString("localId")
    Emulators.patchField("users/$teacher", "role", Emulators.stringValue("teacher"))
    val policyVersion = checkNotNull(Emulators.docFields("users/$teacher"))
        .getJSONObject("policyVersion").getString("integerValue").toInt()
    val code = checkNotNull(links.createCode().getOrThrow().code)

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
            .put("policyVersion", JSONObject().put("integerValue", policyVersion.toString()))
    )
    Emulators.setCustomClaims(student, """{"role":"independent","consentOk":true}""")
    val redeemed = Emulators.callAs(
        Emulators.idToken(studentEmail),
        "redeemTeacherCode",
        JSONObject().put("code", code).put("ackVersion", LinkNotice.VERSION)
    )
    assertEquals("canje: ${redeemed.body}", 200, redeemed.code)
    return LinkedPair(teacher, student, studentEmail)
}
