package com.violinstudio.domain.feature.health.model

/** Respuesta de la Function `health`. */
data class HealthInfo(val status: String, val version: String) {
    val isOk: Boolean get() = status == STATUS_OK

    companion object {
        const val STATUS_OK = "ok"
    }
}
