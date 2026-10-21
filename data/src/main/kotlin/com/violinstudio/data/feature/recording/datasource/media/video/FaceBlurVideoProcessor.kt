package com.violinstudio.data.feature.recording.datasource.media.video

import com.violinstudio.domain.feature.recording.failure.RecordingFailure
import com.violinstudio.domain.feature.recording.model.BlurredVideo
import com.violinstudio.domain.feature.recording.model.FaceBlurPolicy
import com.violinstudio.domain.feature.recording.model.RecordingLimits
import com.violinstudio.domain.feature.recording.repository.VideoBlurrer
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Difuminado del video (4c) con la propiedad del fichero de la cola de audio: la exportacion escribe `{output}.part`
 * y solo un resultado completo y dentro de [RecordingLimits.MAX_VIDEO_BYTES] se renombra a [outputPath]. Cualquier
 * fallo o cancelacion borra el parcial. El original no se toca: lo borra quien llama, cuando ya tiene el difuminado.
 */
class FaceBlurVideoProcessor @Inject constructor(
    private val transcoder: BlurTranscoder
) : VideoBlurrer {
    override suspend fun blur(sourcePath: String, outputPath: String): Result<BlurredVideo> {
        val source = File(sourcePath)
        val output = File(outputPath)
        val part = File("$outputPath.part")
        if (!source.isFile || source.canonicalPath == output.canonicalPath) {
            return Result.failure(RecordingFailure.BlurFailed)
        }
        part.delete()
        output.delete()
        val durationMs = try {
            transcoder.export(source, part)
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } catch (e: Exception) {
            part.delete()
            return Result.failure(RecordingFailure.BlurFailed)
        }
        val size = part.length()
        val failure = when {
            !part.isFile || size <= 0L || durationMs <= 0L -> RecordingFailure.BlurFailed
            size > RecordingLimits.MAX_VIDEO_BYTES -> RecordingFailure.TooLarge
            !part.renameTo(output) -> RecordingFailure.BlurFailed
            else -> null
        }
        if (failure != null) {
            part.delete()
            return Result.failure(failure)
        }
        return Result.success(BlurredVideo(output.path, durationMs, size, FaceBlurPolicy.VERSION))
    }
}
