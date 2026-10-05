package com.violinstudio.data.commons.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.violinstudio.domain.feature.tuner.audio.PcmFormat
import javax.inject.Inject

/** Adaptador fino sobre `AudioTrack` (PCM float mono, stream, baja latencia, `USAGE_MEDIA`/sonificacion). */
class AndroidPcmTrackFactory @Inject constructor() : PcmTrackFactory {
    override fun create(blockSize: Int): PcmTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(PcmFormat.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBytes = AudioTrack.getMinBufferSize(
            PcmFormat.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        check(minBytes > 0) { "AudioTrack min buffer unavailable" }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(maxOf(minBytes, blockSize * Float.SIZE_BYTES * 2))
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            error("AudioTrack not initialized")
        }
        return object : PcmTrack {
            override fun play() = track.play()

            override fun write(buffer: FloatArray, size: Int): Int =
                track.write(buffer, 0, size, AudioTrack.WRITE_BLOCKING)

            override fun playbackHeadPosition(): Int = track.playbackHeadPosition

            override fun pause() = track.pause()

            override fun flush() = track.flush()

            override fun release() = track.release()
        }
    }
}
