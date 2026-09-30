package com.violinstudio.data.feature.health.utils

import com.violinstudio.data.feature.health.dto.HealthDto

class MalformedResponseException(message: String) : IllegalStateException(message)

object HealthResponseParser {
    fun parse(data: Any?): HealthDto {
        val map = data as? Map<*, *> ?: throw MalformedResponseException("health: se esperaba un objeto y llegó $data")
        return HealthDto(status = map.string("status"), version = map.string("version"))
    }

    private fun Map<*, *>.string(key: String): String =
        this[key] as? String ?: throw MalformedResponseException("health: falta '$key' o no es texto")
}
