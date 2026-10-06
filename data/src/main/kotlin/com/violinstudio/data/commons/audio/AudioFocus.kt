package com.violinstudio.data.commons.audio

/**
 * Costura fina sobre el foco de audio de Android, para probar [AudioTrackOutput] sin framework. Pide foco
 * transitorio; [request] devuelve `null` si se deniega (p. ej. durante una llamada). El foco de una reproduccion
 * se pide ANTES de crear la pista y se abandona al terminar, con [AudioFocusLease.abandon].
 */
fun interface AudioFocus {
    /**
     * [onLoss] se llama (desde cualquier hilo) cuando se pierde el foco de forma que hay que parar. Con "duck" no
     * se llama: el sistema atenua solo el volumen y se sigue sonando.
     */
    fun request(onLoss: () -> Unit): AudioFocusLease?
}

/** Foco concedido a una reproduccion. */
fun interface AudioFocusLease {
    /** Devuelve el foco. La salida lo llama exactamente una vez por reproduccion. */
    fun abandon()
}
