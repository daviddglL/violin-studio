package com.violinstudio.data.feature.recording.datasource.media.video

import java.io.File

/**
 * Exporta [source] a [output] pasando cada fotograma por el difuminado de caras. Adaptador fino sobre Media3
 * ([Media3BlurTranscoder]); devuelve la duracion exportada en ms y lanza si la exportacion falla.
 */
interface BlurTranscoder {
    suspend fun export(source: File, output: File): Long
}
