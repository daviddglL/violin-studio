package com.violinstudio.ui.commons

/** Diagnostico solo de depuracion: la app lo activa con `BuildConfig.DEBUG` (y los E2E). Nunca registra email ni contrasena. */
object AuthDiagnostics {
    @Volatile
    var verbose: Boolean = false
}
