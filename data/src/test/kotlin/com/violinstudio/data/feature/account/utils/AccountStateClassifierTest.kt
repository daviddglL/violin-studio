package com.violinstudio.data.feature.account.utils

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthException
import java.io.IOException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AccountStateClassifierTest {
    private fun auth(code: String) = FirebaseAuthException(code, "detalle")

    @Test
    fun `un reload correcto significa que la cuenta existe`() {
        assertEquals(AccountState.EXISTS, AccountStateClassifier.classify(null))
    }

    @Test
    fun `usuario inexistente o deshabilitado significan cuenta desaparecida`() {
        listOf("ERROR_USER_NOT_FOUND", "ERROR_USER_DISABLED").forEach {
            assertEquals(AccountState.GONE, AccountStateClassifier.classify(auth(it)), it)
        }
    }

    @Test
    fun `red, sin sesion y otros errores no prueban nada`() {
        assertEquals(AccountState.UNKNOWN, AccountStateClassifier.classify(FirebaseNetworkException("net")))
        assertEquals(AccountState.UNKNOWN, AccountStateClassifier.classify(IOException("net")))
        assertEquals(AccountState.UNKNOWN, AccountStateClassifier.classify(IllegalStateException("Sin sesión")))
        assertEquals(AccountState.UNKNOWN, AccountStateClassifier.classify(auth("ERROR_INTERNAL_ERROR")))
        // Token caducado tambien ocurre al cambiar la contrasena en otro dispositivo: la cuenta existe.
        assertEquals(AccountState.UNKNOWN, AccountStateClassifier.classify(auth("ERROR_USER_TOKEN_EXPIRED")))
    }
}
