package com.violinstudio.domain.feature.profile.model

internal object ProfileRules {
    const val DISPLAY_NAME_MAX = 40
    private val LOCALE = Regex("^[a-z]{2}(-[A-Z]{2})?$")
    private val NAME_WHITELIST = Regex("^[\\p{L}\\p{M}\\p{N}\\p{P}\\p{S} ]+$")

    /** Con qué se mide el máximo: puntos de código (servidor) o unidades UTF-16 (reglas de Firestore). */
    enum class Unit { CODE_POINTS, UTF16_UNITS }

    /**
     * Igual que `String.prototype.trim` de JavaScript (lo que hace el servidor): espacio, tabuladores, saltos,
     * NBSP, U+FEFF, U+2028/9 y la categoría Zs. No usa `isWhitespace` de Kotlin, que además recorta U+001C-001F.
     */
    private val JS_WHITESPACE =
        intArrayOf(0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF)

    private fun isJsWhitespace(c: Char): Boolean = c.code in JS_WHITESPACE || c.code in 0x2000..0x200A

    /** Devuelve el nombre recortado, o `null` si no cumple las reglas. */
    fun displayName(raw: String, unit: Unit): String? {
        val trimmed = raw.trim(::isJsWhitespace)
        val size = if (unit == Unit.CODE_POINTS) trimmed.codePointCount(0, trimmed.length) else trimmed.length
        return trimmed.takeIf { size in 1..DISPLAY_NAME_MAX && NAME_WHITELIST.matches(it) }
    }

    fun isValidLocale(value: String): Boolean = LOCALE.matches(value)
}
