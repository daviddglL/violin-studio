package com.violinstudio.domain.feature.recording.repository

import com.violinstudio.domain.feature.recording.model.BlurredVideo

/**
 * Difumina en el movil un video ya grabado (4c) segun `FaceBlurPolicy`. Escribe [outputPath] solo si termina bien
 * (nunca deja un parcial) y no toca [sourcePath]: borrar el video sin difuminar es cosa de quien lo llama. Los fallos
 * llegan como `Result.failure(RecordingFailure.BlurFailed)`.
 */
interface VideoBlurrer {
    suspend fun blur(sourcePath: String, outputPath: String): Result<BlurredVideo>
}
