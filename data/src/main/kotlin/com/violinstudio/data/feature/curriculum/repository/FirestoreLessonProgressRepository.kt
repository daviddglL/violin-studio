package com.violinstudio.data.feature.curriculum.repository

import com.violinstudio.data.feature.curriculum.datasource.CurriculumProgressRemoteDataSource
import com.violinstudio.domain.feature.curriculum.failure.CurriculumFailure
import com.violinstudio.domain.feature.curriculum.repository.LessonProgressRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Lecciones hechas en Firestore (`users/{uid}/progress/curriculum`); la cache del SDK lo hace funcionar sin conexion. Al
 * observar un uid se sube, una sola vez, lo que hubiera en el dispositivo de versiones anteriores (union con la nube) y
 * se borra de alli cuando el servidor lo confirma; si no llega a confirmarse, se reintenta en la proxima observacion
 * (la union es idempotente). Leer nunca falla: un error (p. ej. reglas que deniegan) es "nada hecho" y el flujo
 * termina. Escribir traduce el rechazo local del SDK a [CurriculumFailure.Storage].
 */
@Singleton
class FirestoreLessonProgressRepository @Inject constructor(
    private val remote: CurriculumProgressRemoteDataSource,
    private val legacy: LegacyLessonProgressStore
) : LessonProgressRepository {
    override fun observeCompleted(uid: String): Flow<Set<String>> = channelFlow {
        launch { migrate(uid) }
        remote.observe(uid).collect { send(it.toSet()) }
    }
        .catch { if (it is CancellationException) throw it else emit(emptySet()) }
        .distinctUntilChanged()

    override suspend fun setCompleted(uid: String, lessonId: String, done: Boolean) {
        try {
            if (done) remote.add(uid, listOf(lessonId)) else remote.remove(uid, lessonId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw CurriculumFailure.Storage
        }
    }

    private suspend fun migrate(uid: String) {
        try {
            val local = legacy.read(uid)
            if (local.isEmpty()) return
            remote.addAndAwaitServer(uid, local)
            legacy.clear(uid)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Sin consentimiento, rechazo del servidor o E-S: lo local se queda y se reintenta la proxima vez.
        }
    }
}
