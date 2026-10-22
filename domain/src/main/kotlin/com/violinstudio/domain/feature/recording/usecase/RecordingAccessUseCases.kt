package com.violinstudio.domain.feature.recording.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.auth.usecase.isPlausibleEmail
import com.violinstudio.domain.feature.consent.failure.ConsentFailure
import com.violinstudio.domain.feature.consent.repository.ConsentRepository
import com.violinstudio.domain.feature.recording.failure.RecordingFailure
import com.violinstudio.domain.feature.recording.model.RecordingAccess
import com.violinstudio.domain.feature.recording.model.RecordingLimits
import com.violinstudio.domain.feature.recording.repository.PendingUploads
import com.violinstudio.domain.feature.recording.repository.RecordingAccountSource
import com.violinstudio.domain.feature.recording.repository.RecordingConsentRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Decide que ofrece la pantalla de grabaciones con las mismas reglas que `StartRecordingUseCase` (que las repite al
 * pulsar grabar, y el servidor despues). Falla cerrado: sin sesion, config o cuenta legibles devuelve el fallo.
 */
class GetRecordingAccessUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val consent: ConsentRepository,
    private val accounts: RecordingAccountSource,
    private val pending: PendingUploads
) {
    suspend operator fun invoke(): Result<RecordingAccess> {
        val uid = auth.authUser.first()?.uid ?: return Result.failure(RecordingFailure.NoSession)
        val config = consent.identityConfig().getOrElse { return Result.failure(RecordingFailure.Unavailable) }
        if (!config.recordingsEnabled) return Result.success(RecordingAccess.Disabled)
        val account = accounts.current(uid).getOrElse { return Result.failure(RecordingFailure.Unavailable) }
        if (account.isMinor && !config.minorRecordingEnabled) return Result.success(RecordingAccess.Disabled)
        val consented = account.baseConsentGranted && account.recordingConsentGranted &&
            account.recordingConsentVersion >= config.recordingConsentVersion
        if (!consented) {
            return Result.success(
                RecordingAccess.NeedsConsent(
                    selfGrant = !account.isMinor,
                    version = config.recordingConsentVersion,
                    guardianPending = account.isMinor && account.guardianPending
                )
            )
        }
        val used = account.liveCount + pending.count(uid)
        return Result.success(
            RecordingAccess.Allowed(
                maxDurationMs = RecordingLimits.maxDurationMs(account.role),
                quotaFull = used >= RecordingLimits.MAX_RECORDINGS_PER_OWNER,
                videoEnabled = config.videoEnabled
            )
        )
    }
}

/**
 * Un menor pide a su tutor el permiso de grabacion (finalidad `recording_sharing`): debe ser el mismo tutor que acepto
 * la politica, lo comprueba el servidor. Un email con mal formato ni sale del dispositivo. Devuelve el email enmascarado.
 */
class RequestRecordingGuardianUseCase @Inject constructor(private val repository: RecordingConsentRepository) {
    suspend operator fun invoke(guardianEmail: String): Result<String> {
        val email = guardianEmail.trim()
        if (!isPlausibleEmail(email)) return Result.failure(ConsentFailure.GuardianEmailInvalid)
        return repository.requestGuardian(email)
    }
}

class GrantRecordingConsentUseCase @Inject constructor(private val repository: RecordingConsentRepository) {
    suspend operator fun invoke(version: Int): Result<Unit> = repository.grant(version)
}

/**
 * Revoca la grabacion (REQ-REC-09): el servidor borra lo subido y aqui se vacia la cola con sus ficheros. Si la
 * revocacion falla no se toca nada local; si falla vaciar la cola, la cola sigue y el motor la borrara al recibir el 403
 * (R1), asi que la revocacion se da por hecha igualmente.
 */
class RevokeRecordingConsentUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val repository: RecordingConsentRepository,
    private val clearQueue: ClearRecordingQueueUseCase
) {
    suspend operator fun invoke(): Result<Unit> = repository.revoke().onSuccess {
        auth.authUser.first()?.uid?.let { clearQueue(it) }
    }
}
