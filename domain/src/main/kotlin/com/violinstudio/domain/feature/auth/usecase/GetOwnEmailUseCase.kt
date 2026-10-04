package com.violinstudio.domain.feature.auth.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/** Email del usuario con sesión, o `null` sin sesión o sin email (p. ej. proveedor que no lo da). */
class GetOwnEmailUseCase @Inject constructor(private val auth: AuthRepository) {
    suspend operator fun invoke(): String? = auth.authUser.first()?.email
}
