package com.violinstudio.domain.feature.recording.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.consent.repository.ConsentRepository
import com.violinstudio.domain.feature.recording.failure.RecordingFailure
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
        val uid = auth.authUser.first()?.uid ?: return fail(RecordingFailure.NoSession)
        val config = consent.identityConfig().getOrElse { return fail(RecordingFailure.Unavailable) }
        if (!config.recordingsEnabled) return fail(RecordingFailure.Disabled)
        val account = accounts.current(uid).getOrElse { return fail(RecordingFailure.Unavailable) }
        if (account.isMinor && !config.minorRecordingEnabled) return fail(RecordingFailure.Disabled)
        val consented = account.baseConsentGranted && account.recordingConsentGranted &&
            account.recordingConsentVersion >= config.recordingConsentVersion
        if (!consented) return fail(RecordingFailure.ConsentRequired)
        if (account.liveCount + pending.count(uid) >= RecordingLimits.MAX_RECORDINGS_PER_OWNER) {
            return fail(RecordingFailure.QuotaReached)
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

    private fun fail(failure: RecordingFailure): Result<RecordingHandle> = Result.failure(failure)
}
