package com.violinstudio.domain.feature.link.model

import java.time.Instant

/**
 * Código de vínculo del profesor. [code] (en claro) solo viene al crearlo; el servidor no lo guarda, así que el
 * listado solo trae [hint] (últimos 2 caracteres). [id] es el HMAC que identifica el doc para revocarlo.
 */
data class TeacherCode(val id: String, val code: String?, val hint: String, val expiresAt: Instant) {
    override fun toString(): String = "TeacherCode(hint=$hint, expiresAt=$expiresAt)"
}

/** Espeja `parseCodeInput` de `functions/src/teacher/codes.ts`. */
object TeacherCodeInput {
    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val LENGTH = 8
    private const val MAX_RAW_LENGTH = 32

    /** Como `\s` de JS (el servidor): espacios Unicode y BOM, más el guion. */
    private fun isSeparator(c: Char) = c == '-' || c == '﻿' || Character.isWhitespace(c) || Character.isSpaceChar(c)

    /** Mayúsculas sin espacios ni guiones; `null` si no es un código posible (longitud 8, solo [ALPHABET]). */
    fun normalize(raw: String): String? {
        if (raw.length > MAX_RAW_LENGTH) return null
        val code = raw.uppercase().filterNot(::isSeparator)
        return code.takeIf { it.length == LENGTH && it.all { c -> c in ALPHABET } }
    }
}
