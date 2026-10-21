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

/** Grabaciones de práctica (consentimiento, grabar, cola de subida): común a todos los roles. */
@Serializable
data object RecordingsDestination

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

/** Crear (sin [assignmentId]) o editar una tarea del alumno [studentUid]: solo `teacher`. */
@Serializable
data class AssignmentEditDestination(val studentUid: String, val assignmentId: String? = null) {
    override fun toString(): String = "AssignmentEditDestination"
}

/** Rutas de negocio: solo se mantienen con Ready; con otro estado SessionRedirect las lleva a la raíz. */
val BUSINESS_ROUTES: List<kotlin.reflect.KClass<*>> =
    listOf(
        SettingsDestination::class,
        TunerDestination::class,
        MetronomeDestination::class,
        PracticeLogDestination::class,
        StatsDestination::class,
        CurriculumDestination::class,
        RecordingsDestination::class,
        RedeemCodeDestination::class,
        MyTeacherDestination::class,
        StudentsDestination::class,
        StudentDetailDestination::class,
        TeacherCodesDestination::class,
        AssignmentsDestination::class,
        TeacherAssignmentsDestination::class,
        AssignmentEditDestination::class,
        StudentRecordingsDestination::class
    )

/** Roles que pueden abrir cada ruta por rol; las ausentes son comunes a todos. Solo UX: la seguridad está en reglas. */
private val ROLE_ROUTES: Map<kotlin.reflect.KClass<*>, Set<Role>> = mapOf(
    RedeemCodeDestination::class to setOf(Role.INDEPENDENT),
    MyTeacherDestination::class to setOf(Role.STUDENT),
    StudentsDestination::class to setOf(Role.TEACHER),
    StudentDetailDestination::class to setOf(Role.TEACHER),
    TeacherCodesDestination::class to setOf(Role.TEACHER),
    AssignmentsDestination::class to setOf(Role.STUDENT),
    TeacherAssignmentsDestination::class to setOf(Role.TEACHER),
    AssignmentEditDestination::class to setOf(Role.TEACHER),
    StudentRecordingsDestination::class to setOf(Role.TEACHER)
)

fun Role.allows(route: kotlin.reflect.KClass<*>): Boolean = ROLE_ROUTES[route]?.contains(this) ?: true

/** Ruta raíz que corresponde a cada estado de sesión: solo `Ready` llega a rutas de negocio. */
fun SessionState.rootRoute(): Any = when (this) {
    SessionState.Loading -> SplashDestination
    SessionState.Unavailable -> OfflineDestination
    SessionState.LoggedOut -> AuthDestination
    is SessionState.EmailUnverified -> VerifyEmailDestination
    SessionState.NeedsProfile -> OnboardingDestination
    is SessionState.ConsentPending -> ConsentDestination
    is SessionState.ParentalPending -> GuardianWaitDestination
    is SessionState.Ready -> HomeDestination
}
