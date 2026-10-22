package com.violinstudio.domain.feature.recording.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.consent.repository.ConsentRepository
import com.violinstudio.domain.feature.recording.failure.RecordingFailure
import com.violinstudio.domain.feature.recording.model.MediaKind
import com.violinstudio.domain.feature.recording.model.QueuedRecording
import com.violinstudio.domain.feature.recording.model.RecordedTake
import com.violinstudio.domain.feature.recording.model.RecordingLimits
import com.violinstudio.domain.feature.recording.model.VideoSpec
import com.violinstudio.domain.feature.recording.repository.PendingUploads
import com.violinstudio.domain.feature.recording.repository.RecordingAccountSource
import com.violinstudio.domain.feature.recording.repository.RecordingQueue
import com.violinstudio.domain.feature.recording.repository.VideoBlurrer
import com.violinstudio.domain.feature.recording.repository.VideoFiles
import java.util.UUID
import javax.inject.Inject

/**
 * Prepara una toma de video (4c) con las mismas comprobaciones que el audio mas `videoEnabled`. No abre la camara: la
 * pantalla graba en [VideoSpec.rawPath] y para en [VideoSpec.autoStopMs]. Antes borra los originales huerfanos.
 */
class StartVideoUseCase(
    private val auth: AuthRepository,
    private val consent: ConsentRepository,
    private val accounts: RecordingAccountSource,
    private val pending: PendingUploads,
    private val files: VideoFiles,
    private val newId: () -> String
) {
    @Inject
    constructor(
        auth: AuthRepository,
        consent: ConsentRepository,
        accounts: RecordingAccountSource,
        pending: PendingUploads,
        files: VideoFiles
    ) : this(auth, consent, accounts, pending, files, { UUID.randomUUID().toString() })

    suspend operator fun invoke(): Result<VideoSpec> {
        val (uid, account) = checkRecordingAllowed(auth, consent, accounts, pending, video = true).getOrElse {
            return Result.failure(it)
        }
        files.sweepRaw(uid)
        val id = newId()
        return Result.success(
            VideoSpec(
                id = id,
                uid = uid,
                rawPath = files.rawPath(uid, id),
                outputPath = files.outputPath(uid, id),
                maxDurationMs = RecordingLimits.maxDurationMs(account.role),
                autoStopMs = RecordingLimits.autoStopMs(account.role),
                consentEpoch = account.consentEpoch
            )
        )
    }
}

/**
 * Cierra una toma de video (4c): difumina el original, lo borra SIEMPRE (haya ido bien o mal) y encola solo el
 * difuminado. Una toma demasiado corta o que no se pudo encolar se borra entera: no queda nada sin cola en disco.
 */
class FinishVideoUseCase @Inject constructor(
    private val blurrer: VideoBlurrer,
    private val files: VideoFiles,
    private val queue: RecordingQueue
) {
    suspend operator fun invoke(
        spec: VideoSpec,
        assignmentId: String? = null,
        sharedWith: String? = null
    ): Result<Unit> {
        val blurred = try {
            blurrer.blur(spec.rawPath, spec.outputPath)
        } finally {
            files.delete(spec.uid, spec.rawPath)
        }
        val video = blurred.getOrElse {
            files.delete(spec.uid, spec.outputPath)
            return Result.failure(it)
        }
        if (video.durationMs < RecordingLimits.MIN_DURATION_MS) {
            files.delete(spec.uid, spec.outputPath)
            return Result.failure(RecordingFailure.TooShort)
        }
        val take = RecordedTake(
            id = spec.id,
            uid = spec.uid,
            path = video.path,
            durationMs = video.durationMs.coerceAtMost(spec.maxDurationMs),
            sizeBytes = video.sizeBytes,
            consentEpoch = spec.consentEpoch,
            kind = MediaKind.VIDEO,
            blurPolicyVersion = video.policyVersion
        )
        return queue.enqueue(QueuedRecording(take, assignmentId = assignmentId, sharedWith = sharedWith))
            .onFailure { files.delete(spec.uid, spec.outputPath) }
    }
}

/** Abandona una toma de video (salir de la pantalla, error de camara): borra el original y el difuminado si existe. */
class DiscardVideoUseCase @Inject constructor(private val files: VideoFiles) {
    suspend operator fun invoke(spec: VideoSpec) {
        files.delete(spec.uid, spec.rawPath)
        files.delete(spec.uid, spec.outputPath)
    }
}
