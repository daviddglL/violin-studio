package com.violinstudio.data.feature.recording.datasource.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/**
 * Adaptador fino sobre `MediaRecorder` (REQ-REC-03): AAC mono 44,1 kHz ~96 kbps en MP4. Sin logica propia ni logs;
 * no se prueba en JVM (necesita micro): verificacion en dispositivo pendiente (B12/C10).
 */
class AndroidMediaRecorderFactory @Inject constructor(
    @param:ApplicationContext private val context: Context
) : MediaRecorderFactory {
    override fun create(): MediaRecorderSession = RealSession(newRecorder())

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()

    private class RealSession(private val recorder: MediaRecorder) : MediaRecorderSession {
        override fun start(output: File, maxDurationMs: Long, listener: MediaRecorderSession.Listener) {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioChannels(CHANNELS)
            recorder.setAudioSamplingRate(SAMPLE_RATE_HZ)
            recorder.setAudioEncodingBitRate(BIT_RATE_BPS)
            recorder.setOutputFile(output.path)
            recorder.setMaxDuration(maxDurationMs.toInt())
            recorder.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) listener.onMaxDurationReached()
            }
            recorder.setOnErrorListener { _, _, _ -> listener.onError() }
            recorder.prepare()
            recorder.start()
        }

        override fun stop() = recorder.stop()

        override fun release() = recorder.release()
    }

    private companion object {
        const val CHANNELS = 1
        const val SAMPLE_RATE_HZ = 44_100
        const val BIT_RATE_BPS = 96_000
    }
}
