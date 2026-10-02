package com.violinstudio.data.feature.auth.utils

import com.violinstudio.domain.feature.auth.failure.AuthFailure

/** Contexto de la operación: el mismo error de Firebase se traduce distinto según lo que no debe revelar. */
enum class AuthOperation { SIGN_IN, SIGN_UP, GOOGLE_SIGN_IN, PASSWORD_RESET, OTHER }

object AuthErrorMapper {
    fun map(error: Throwable, operation: AuthOperation = AuthOperation.OTHER): AuthFailure = TODO()
}
