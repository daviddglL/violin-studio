package com.violinstudio.ui.commons.locale

/** Doble de [AppLocales]: guarda las etiquetas aplicadas y recuerda cada llamada a [set]. */
class FakeAppLocales(initial: List<String> = emptyList()) : AppLocales {
    var tags: List<String> = initial
        private set
    val applied = mutableListOf<List<String>>()

    /** Cambio hecho fuera de la app (ajustes del sistema): no pasa por [set]. */
    fun changeExternally(tags: List<String>) {
        this.tags = tags
    }

    override fun current(): List<String> = tags

    override fun set(tags: List<String>) {
        this.tags = tags
        applied += tags
    }
}
