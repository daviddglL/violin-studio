package com.violinstudio.data.feature.recording.datasource.media

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adaptador fino sobre `MediaPlayer` alimentado desde memoria ([ByteArrayDataSource]): la grabacion nunca toca el
 * disco al reproducirse. Un solo clip a la vez. Sin logs. Verificacion en dispositivo pendiente.
 */
@Singleton
class AndroidAudioClipOutput @Inject constructor() : AudioClipOutput {
    private var player: MediaPlayer? = null

    @Synchronized
    override fun play(bytes: ByteArray, onDone: () -> Unit, onError: () -> Unit) {
        release()
        val current = MediaPlayer()
        player = current
        current.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        current.setOnCompletionListener {
            releaseIf(current)
            onDone()
        }
        current.setOnErrorListener { _, _, _ ->
            releaseIf(current)
            onError()
            true
        }
        try {
            current.setDataSource(ByteArrayDataSource(bytes))
            current.prepare()
            current.start()
        } catch (e: Exception) {
            releaseIf(current)
            throw e
        }
    }

    @Synchronized
    override fun stop() = release()

    @Synchronized
    private fun releaseIf(target: MediaPlayer) {
        if (player === target) release()
    }

    private fun release() {
        player?.release()
        player = null
    }
}

/** `MediaDataSource` sobre un array en memoria (API 23+). */
internal class ByteArrayDataSource(private val data: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0 || position >= data.size) return END_OF_STREAM
        val count = minOf(size.toLong(), data.size - position).toInt()
        System.arraycopy(data, position.toInt(), buffer, offset, count)
        return count
    }

    override fun getSize(): Long = data.size.toLong()

    override fun close() = Unit

    private companion object {
        const val END_OF_STREAM = -1
    }
}
