package com.violinstudio.domain.feature.recording.repository

import com.violinstudio.domain.feature.recording.model.RecordingSummary
import kotlinx.coroutines.flow.Flow

/** Grabaciones del propio usuario en Firestore (las escribe solo `finalizeRecording`; el dueno lee y borra, A12). */
interface RecordingLibrary {
    /** Mas recientes primero. El flujo falla si el listener falla. */
    fun observe(uid: String): Flow<List<RecordingSummary>>

    /** Borra el doc; el servidor borra el objeto, las revisiones y devuelve la plaza del cupo. */
    suspend fun delete(uid: String, id: String): Result<Unit>
}

/** Callables del consentimiento de grabacion. Los fallos llegan como `Result.failure(ConsentFailure)`. */
interface RecordingConsentRepository {
    /** Solo 14+ (los menores, por su tutor). */
    suspend fun grant(version: Int): Result<Unit>

    /** Revoca solo la grabacion; el servidor borra todo lo grabado (REQ-REC-09). */
    suspend fun revoke(): Result<Unit>

    /** Pide el permiso al tutor por correo (`requestGuardianConsent` con `recording_sharing`); el email enmascarado. */
    suspend fun requestGuardian(guardianEmail: String): Result<String>
}
