package com.violinstudio.core.firebase

data class EmulatorEndpoint(val host: String, val port: Int)

/**
 * Si [enabled], los SDKs de Firebase apuntan a los emuladores locales en [host]
 * (10.0.2.2 desde el emulador Android; la IP del PC desde un móvil físico).
 */
data class EmulatorConfig(val enabled: Boolean, val host: String) {
    init {
        require(!enabled || host.isNotBlank()) { "EmulatorConfig: host vacío con los emuladores activados" }
    }

    fun functions(): EmulatorEndpoint? = if (enabled) EmulatorEndpoint(host, FUNCTIONS_PORT) else null

    companion object {
        const val FUNCTIONS_PORT = 5001
    }
}
