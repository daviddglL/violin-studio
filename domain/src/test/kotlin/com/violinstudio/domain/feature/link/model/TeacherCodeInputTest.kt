package com.violinstudio.domain.feature.link.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TeacherCodeInputTest {
    @Test
    fun `normaliza a mayusculas y quita espacios y guiones`() {
        assertEquals("ABCD2345", TeacherCodeInput.normalize(" abcd-2345 "))
        assertEquals("ABCD2345", TeacherCodeInput.normalize("ab cd\t23-45"))
    }

    @Test
    fun `rechaza longitud distinta de 8`() {
        assertNull(TeacherCodeInput.normalize("ABCD234"))
        assertNull(TeacherCodeInput.normalize("ABCD23456"))
        assertNull(TeacherCodeInput.normalize(""))
    }

    @Test
    fun `rechaza simbolos fuera del alfabeto 0 O 1 I L y no ASCII`() {
        for (bad in listOf("0BCD2345", "OBCD2345", "1BCD2345", "IBCD2345", "LBCD2345", "ABCD234!", "ABCD234Ñ")) {
            assertNull(TeacherCodeInput.normalize(bad), bad)
        }
    }

    @Test
    fun `entrada cruda de mas de 32 caracteres se rechaza aunque normalice a 8`() {
        assertNull(TeacherCodeInput.normalize("ABCD2345" + " ".repeat(25)))
        assertEquals("ABCD2345", TeacherCodeInput.normalize("ABCD2345" + " ".repeat(24)))
    }
}
