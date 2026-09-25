package com.violinstudio.core.data.remote

import com.violinstudio.core.model.HealthInfo

class MalformedResponseException(message: String) : IllegalStateException(message)

object HealthResponseParser {
    fun parse(data: Any?): HealthInfo {
        val map = data as? Map<*, *> ?: throw MalformedResponseException("health: se esperaba un objeto y llegó $data")
        return HealthInfo(status = map.string("status"), version = map.string("version"))
    }

    private fun Map<*, *>.string(key: String): String =
        this[key] as? String ?: throw MalformedResponseException("health: falta '$key' o no es texto")
}
