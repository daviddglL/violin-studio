package com.violinstudio.data.commons.firebase

import java.util.concurrent.ConcurrentHashMap

/**
 * `useEmulator` solo puede llamarse una vez por instancia del SDK, y esas instancias son globales del proceso. Hilt
 * puede recrear el grafo Singleton (un test instrumentado por grafo), asi que la configuracion se guarda por proceso.
 */
class EmulatorOnce {
    private val done = ConcurrentHashMap.newKeySet<String>()

    fun apply(service: String, configure: () -> Unit) {
        if (done.add(service)) configure()
    }

    companion object {
        val process = EmulatorOnce()
    }
}
