package com.violinstudio.domain.feature.recording.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.consent.repository.ConsentRepository
import com.violinstudio.domain.feature.recording.failure.RecordingFailure
import com.violinstudio.domain.feature.recording.model.RecordingAccount
import com.violinstudio.domain.feature.recording.model.RecordingLimits
import com.violinstudio.domain.feature.recording.model.RecordingSpec
import com.violinstudio.domain.feature.recording.repository.PendingUploads
import com.violinstudio.domain.feature.recording.repository.Recorder
import com.violinstudio.domain.feature.recording.repository.RecordingAccountSource
import com.violinstudio.domain.feature.recording.repository.RecordingHandle
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Abre el micro solo si todo es vigente (REQ-REC-02, REQ-REC-14), fallando cerrado ante cualquier duda: sesion, flags
 * del servidor, consentimiento base + de grabacion en la version vigente y cupo (vivas + en cola < 20). Captura la
 * `recordingConsentEpoch` actual del usuario en la [RecordingSpec]; la subida la reenvia siempre como metadato.
 * El servidor repite cada comprobacion (reglas de Storage y `finalizeRecording`).
 */
class StartRecordingUseCase(
    private val auth: AuthRepository,
    private val consent: ConsentRepository,
    private val accounts: RecordingAccountSource,
    private val pending: PendingUploads,
    private val recorder: Recorder,
    private val newId: () -> String
) {
    @Inject
    constructor(
        auth: AuthRepository,
        consent: ConsentRepository,
        accounts: RecordingAccountSource,
        pending: PendingUploads,
        recorder: Recorder
    ) : this(auth, consent, accounts, pending, recorder, { UUID.randomUUID().toString() })

    suspend operator fun invoke(): Result<RecordingHandle> {
        val (uid, account) = checkRecordingAllowed(auth, consent, accounts, pending).getOrElse {
            return Result.failure(it)
        }
        return recorder.start(
            RecordingSpec(
                id = newId(),
                uid = uid,
                maxDurationMs = RecordingLimits.maxDurationMs(account.role),
                autoStopMs = RecordingLimits.autoStopMs(account.role),
                consentEpoch = account.consentEpoch
            )
        )
    }
}

/** Quien graba y su estado de cuenta, ya comprobados por [checkRecordingAllowed]. */
internal data class AllowedRecorder(val uid: String, val account: RecordingAccount)

/**
 * Comprobaciones comunes a audio y video (REQ-REC-02, REQ-REC-14), fallando cerrado: sesion, flags del servidor (con
 * [video] tambien `videoEnabled`), consentimiento base + de grabacion en la version vigente y cupo (vivas + en cola).
 */
internal suspend fun checkRecordingAllowed(
    auth: AuthRepository,
    consent: ConsentRepository,
    accounts: RecordingAccountSource,
    pending: PendingUploads,
    video: Boolean = false
): Result<AllowedRecorder> {
    val uid = auth.authUser.first()?.uid ?: return Result.failure(RecordingFailure.NoSession)
    val config = consent.identityConfig().getOrElse { return Result.failure(RecordingFailure.Unavailable) }
    if (!config.recordingsEnabled || (video && !config.videoEnabled)) {
        return Result.failure(RecordingFailure.Disabled)
    }
    val account = accounts.current(uid).getOrElse { return Result.failure(RecordingFailure.Unavailable) }
    if (account.isMinor && !config.minorRecordingEnabled) return Result.failure(RecordingFailure.Disabled)
    val consented = account.baseConsentGranted && account.recordingConsentGranted &&
        account.recordingConsentVersion >= config.recordingConsentVersion
    if (!consented) return Result.failure(RecordingFailure.ConsentRequired)
    if (account.liveCount + pending.count(uid) >= RecordingLimits.MAX_RECORDINGS_PER_OWNER) {
        return Result.failure(RecordingFailure.QuotaReached)
    }
    return Result.success(AllowedRecorder(uid, account))
}
