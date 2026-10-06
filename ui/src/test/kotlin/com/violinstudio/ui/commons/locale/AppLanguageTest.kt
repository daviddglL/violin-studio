package com.violinstudio.ui.commons.locale

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppLanguageTest {
    @Test
    fun `el idioma del sistema se aplica como lista vacia y los demas como su etiqueta`() {
        assertEquals(emptyList<String>(), AppLanguage.SYSTEM.localeTags)
        assertEquals(listOf("es"), AppLanguage.SPANISH.localeTags)
        assertEquals(listOf("en"), AppLanguage.ENGLISH.localeTags)
    }

    @Test
    fun `sin locales aplicados se sigue el idioma del sistema`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTags(emptyList()))
    }

    @Test
    fun `una etiqueta con region se reconoce por su idioma`() {
        assertEquals(AppLanguage.SPANISH, AppLanguage.fromTags(listOf("es-ES")))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTags(listOf("en-GB")))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTags(listOf("EN")))
    }

    @Test
    fun `un idioma que la app no ofrece cuenta como idioma del sistema`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTags(listOf("fr-FR")))
    }

    @Test
    fun `manda la primera etiqueta de la lista`() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTags(listOf("en", "es")))
    }

    @Test
    fun `aplicar y leer es una ida y vuelta para cada opcion`() {
        for (language in AppLanguage.entries) {
            assertEquals(language, AppLanguage.fromTags(language.localeTags))
        }
    }
}
