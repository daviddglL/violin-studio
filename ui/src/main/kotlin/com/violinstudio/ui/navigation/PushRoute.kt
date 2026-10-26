package com.violinstudio.ui.navigation

import com.violinstudio.domain.feature.profile.model.Role
import com.violinstudio.domain.feature.session.SessionState

/** Clave del dato `kind` que manda functions en cada aviso (`notifications/fcm.ts`); llega como extra del intent. */
const val PUSH_KIND_EXTRA = "kind"

/**
 * Pantalla que abre tocar un aviso de tipo [kind] (`chat`, `review`, `assignment`). El aviso no lleva ids (decisión
 * de privacidad de los avisos): se abre la lista donde está lo nuevo, a un toque del detalle. Nulo si el tipo es
 * desconocido o la ruta no está disponible para el rol o con su interruptor apagado: se queda en Home.
 */
fun SessionState.Ready.pushDestination(kind: String?): Any? {
    val role = profile.role
    val route: Any = when (kind) {
        "chat" -> (if (role == Role.STUDENT) MyTeacherDestination else StudentsDestination).takeIf { features.chat }
        "review" -> RecordingsDestination.takeIf { features.recordings }
        "assignment" -> AssignmentsDestination
        else -> null
    } ?: return null
    return route.takeIf { role.allows(it::class) }
}
