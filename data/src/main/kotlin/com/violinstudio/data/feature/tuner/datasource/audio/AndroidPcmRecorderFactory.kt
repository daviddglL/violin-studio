package com.violinstudio.data.feature.tuner.datasource.audio

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import com.violinstudio.data.feature.tuner.utils.AudioErrorMapper
import com.violinstudio.data.feature.tuner.utils.AudioRecordConfig
import com.violinstudio.data.feature.tuner.utils.CaptureSource
import javax.inject.Inject

/** Adaptador fino sobre `AudioRecord`: sin logica propia salvo trasladar estados al [AudioErrorMapper]. */
class AndroidPcmRecorderFactory @Inject constructor(private val audioManager: AudioManager) : PcmRecorderFactory {
    @Suppress("MissingPermission")
    override fun create(chunkSize: Int): PcmRecorder {
        val unprocessed = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = when (AudioRecordConfig.sourceFor(unprocessed)) {
            CaptureSource.UNPROCESSED -> MediaRecorder.AudioSource.UNPROCESSED
            CaptureSource.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            AudioRecordConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        AudioErrorMapper.fromMinBuffer(minBuffer)?.let { throw it }
        val record = AudioRecord(
            source,
            AudioRecordConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            AudioRecordConfig.bufferBytes(minBuffer, chunkSize)
        )
        AudioErrorMapper.requireInitialized(record.state) { record.release() }
        return object : PcmRecorder {
            override fun start() {
                record.startRecording()
                AudioErrorMapper.requireRecording(record.recordingState)
            }

            override fun read(buffer: ShortArray): Int = record.read(buffer, 0, buffer.size)

            override fun stop() = record.stop()

            override fun release() = record.release()
        }
    }
}
