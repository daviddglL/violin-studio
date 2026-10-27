package com.violinstudio.ui.navigation

import com.violinstudio.domain.feature.profile.model.Role
import com.violinstudio.domain.feature.session.SessionState
import kotlinx.serialization.Serializable

@Serializable
data object SplashDestination

@Serializable
data object AuthDestination

@Serializable
data object VerifyEmailDestination

@Serializable
data object OnboardingDestination

@Serializable
data object ConsentDestination

@Serializable
data object GuardianWaitDestination

/** La version instalada es anterior a la minima del servidor: solo actualizar o cerrar sesion. */
@Serializable
data object UpdateRequiredDestination

/** Sin red y reintentando: no es terminal, la sesión lo sustituye sola cuando vuelve a resolverse. */
@Serializable
data object OfflineDestination

@Serializable
data object HomeDestination

/** Ruta de negocio dentro del grafo de Ready: se mantiene solo con Ready y cae a la raíz del estado si cambia. */
@Serializable
data object SettingsDestination

@Serializable
data object TunerDestination

@Serializable
data object MetronomeDestination

@Serializable
data object PracticeLogDestination

/** Estadisticas de practica: comun a todos los roles. */
@Serializable
data object StatsDestination

/** Curriculo de lecciones por niveles: comun a todos los roles. */
@Serializable
data object CurriculumDestination

/** Ejercicio de afinacion [exerciseId] (de una leccion del curriculo): comun a todos los roles. */
@Serializable
data class IntonationDestination(val exerciseId: String)

/** Grabaciones de práctica (consentimiento, grabar, cola de subida): común a todos los roles. */
@Serializable
data object RecordingsDestination

/**
 * Grabar un video (4c): común a todos los roles. Con [studentUid] y [assignmentId] es una demostración del profesor
 * para ese alumno y esa tarea (se llega desde `TeacherDemoDestination`).
 */
@Serializable
data class VideoCaptureDestination(val studentUid: String? = null, val assignmentId: String? = null) {
    override fun toString(): String = "VideoCaptureDestination"
}

/** Canjear código: solo `independent`. */
@Serializable
data object RedeemCodeDestination

/** Mi profesor: solo `student`. */
@Serializable
data object MyTeacherDestination

/** Alumnos del profesor: solo `teacher`. */
@Serializable
data object StudentsDestination

/** Registro de solo lectura de un alumno vinculado ([studentUid]): solo `teacher`. */
@Serializable
data class StudentDetailDestination(val studentUid: String) {
    // El uid del alumno no debe acabar en logs ni volcados de la pila.
    override fun toString(): String = "StudentDetailDestination"
}

/** Códigos de vínculo del profesor: solo `teacher`. */
@Serializable
data object TeacherCodesDestination

/** Tareas del alumno (marcar hecha / reabrir): solo `student`. */
@Serializable
data object AssignmentsDestination

/** Tareas que el profesor creó para el alumno [studentUid]: solo `teacher`. */
@Serializable
data class TeacherAssignmentsDestination(val studentUid: String) {
    // El uid del alumno no debe acabar en logs ni volcados de la pila.
    override fun toString(): String = "TeacherAssignmentsDestination"
}

/** Grabaciones `ready` del alumno [studentUid] para escuchar y valorar: solo `teacher`. */
@Serializable
data class StudentRecordingsDestination(val studentUid: String) {
    // El uid del alumno no debe acabar en logs ni volcados de la pila.
    override fun toString(): String = "StudentRecordingsDestination"
}

/** El profesor graba demostraciones de la tarea [assignmentId] para el alumno [studentUid]: solo `teacher`. */
@Serializable
data class TeacherDemoDestination(val studentUid: String, val assignmentId: String) {
    override fun toString(): String = "TeacherDemoDestination"
}

/** El alumno escucha las demostraciones de su profesor [teacherUid] para la tarea [assignmentId]: solo `student`. */
@Serializable
data class AssignmentDemosDestination(val teacherUid: String, val assignmentId: String) {
    override fun toString(): String = "AssignmentDemosDestination"
}

/** Chat del par profesor [teacherUid] y alumno [studentUid]: solo `teacher` y `student` (uno de los dos es el usuario). */
@Serializable
data class ChatDestination(val teacherUid: String, val studentUid: String) {
    override fun toString(): String = "ChatDestination"
}

/** Crear (sin [assignmentId]) o editar una tarea del alumno [studentUid]: solo `teacher`. */
@Serializable
data class AssignmentEditDestination(val studentUid: String, val assignmentId: String? = null) {
    override fun toString(): String = "AssignmentEditDestination"
}

/** Solicitud de alta de profesor: solo `independent` (y adulto, que lo decide la navegación con el perfil). */
@Serializable
data object TeacherRequestDestination

/** Rutas de negocio: solo se mantienen con Ready; con otro estado SessionRedirect las lleva a la raíz. */
val BUSINESS_ROUTES: List<kotlin.reflect.KClass<*>> =
    listOf(
        SettingsDestination::class,
        TunerDestination::class,
        MetronomeDestination::class,
        PracticeLogDestination::class,
        StatsDestination::class,
        CurriculumDestination::class,
        IntonationDestination::class,
        RecordingsDestination::class,
        VideoCaptureDestination::class,
        RedeemCodeDestination::class,
        TeacherRequestDestination::class,
        MyTeacherDestination::class,
        StudentsDestination::class,
        StudentDetailDestination::class,
        TeacherCodesDestination::class,
        AssignmentsDestination::class,
        TeacherAssignmentsDestination::class,
        AssignmentEditDestination::class,
        StudentRecordingsDestination::class,
        TeacherDemoDestination::class,
        AssignmentDemosDestination::class,
        ChatDestination::class
    )

/** Roles que pueden abrir cada ruta por rol; las ausentes son comunes a todos. Solo UX: la seguridad está en reglas. */
private val ROLE_ROUTES: Map<kotlin.reflect.KClass<*>, Set<Role>> = mapOf(
    RedeemCodeDestination::class to setOf(Role.INDEPENDENT),
    TeacherRequestDestination::class to setOf(Role.INDEPENDENT),
    MyTeacherDestination::class to setOf(Role.STUDENT),
    StudentsDestination::class to setOf(Role.TEACHER),
    StudentDetailDestination::class to setOf(Role.TEACHER),
    TeacherCodesDestination::class to setOf(Role.TEACHER),
    AssignmentsDestination::class to setOf(Role.STUDENT),
    TeacherAssignmentsDestination::class to setOf(Role.TEACHER),
    AssignmentEditDestination::class to setOf(Role.TEACHER),
    StudentRecordingsDestination::class to setOf(Role.TEACHER),
    TeacherDemoDestination::class to setOf(Role.TEACHER),
    AssignmentDemosDestination::class to setOf(Role.STUDENT),
    ChatDestination::class to setOf(Role.TEACHER, Role.STUDENT)
)

fun Role.allows(route: kotlin.reflect.KClass<*>): Boolean = ROLE_ROUTES[route]?.contains(this) ?: true

/** Ruta raíz que corresponde a cada estado de sesión: solo `Ready` llega a rutas de negocio. */
fun SessionState.rootRoute(): Any = when (this) {
    SessionState.Loading -> SplashDestination
    SessionState.Unavailable -> OfflineDestination
    SessionState.LoggedOut -> AuthDestination
    is SessionState.EmailUnverified -> VerifyEmailDestination
    SessionState.NeedsProfile -> OnboardingDestination
    SessionState.UpdateRequired -> UpdateRequiredDestination
    is SessionState.ConsentPending -> ConsentDestination
    is SessionState.ParentalPending -> GuardianWaitDestination
    is SessionState.Ready -> HomeDestination
}
