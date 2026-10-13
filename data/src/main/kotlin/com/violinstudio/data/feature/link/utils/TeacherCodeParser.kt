package com.violinstudio.data.feature.link.utils

import com.violinstudio.data.feature.health.utils.MalformedResponseException
import com.violinstudio.domain.feature.link.model.TeacherCode
import java.time.Instant

/** Respuestas de los callables de vinculo: lo que no es un objeto o una lista es malformado; un elemento ilegible se omite. */
object TeacherCodeParser {
    fun parseCreated(data: Any?): TeacherCode = element(data as? Map<*, *>, withCode = true)
        ?: throw MalformedResponseException("createTeacherCode: respuesta ilegible")

    fun parseList(data: Any?): List<TeacherCode> {
        val codes = (data as? Map<*, *>)?.get("codes") as? List<*>
            ?: throw MalformedResponseException("listTeacherCodes: falta la lista")
        return codes.mapNotNull { element(it as? Map<*, *>, withCode = false) }
    }

    fun parseRedeemed(data: Any?): String {
        val map = data as? Map<*, *>
        return map?.takeIf { it["linked"] == true }?.get("teacherUid") as? String
            ?: throw MalformedResponseException("redeemTeacherCode: respuesta ilegible")
    }

    private fun element(map: Map<*, *>?, withCode: Boolean): TeacherCode? {
        map ?: return null
        val id = map["id"] as? String ?: return null
        val hint = map["codeHint"] as? String ?: return null
        val millis = (map["expiresAt"] as? Number)?.toDouble()?.takeIf { it % 1.0 == 0.0 } ?: return null
        val code = if (withCode) map["code"] as? String ?: return null else null
        return TeacherCode(id, code, hint, Instant.ofEpochMilli(millis.toLong()))
    }
}
