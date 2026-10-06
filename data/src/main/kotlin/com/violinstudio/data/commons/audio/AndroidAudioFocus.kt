package com.violinstudio.data.commons.audio

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import javax.inject.Inject

/**
 * Foco de audio con `AudioFocusRequest` (minSdk 26). Pide `AUDIOFOCUS_GAIN_TRANSIENT` con los mismos atributos que
 * la pista (`USAGE_MEDIA`/`CONTENT_TYPE_MUSIC`) y NO acepta foco diferido: si no se concede ya, no suena.
 */
class AndroidAudioFocus @Inject constructor(private val audioManager: AudioManager) : AudioFocus {
    override fun request(onLoss: () -> Unit): AudioFocusLease? {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { change -> if (endsPlayback(change)) onLoss() }
            .build()
        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return null
        return AudioFocusLease { audioManager.abandonAudioFocusRequest(request) }
    }

    companion object {
        /**
         * `LOSS` y `LOSS_TRANSIENT` paran (sin reanudar al recuperarlo). `LOSS_TRANSIENT_CAN_DUCK` no: a partir de
         * la API 26 el sistema atenua solo el audio y se sigue sonando. `GAIN` no hace nada.
         */
        internal fun endsPlayback(focusChange: Int): Boolean =
            focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
    }
}
