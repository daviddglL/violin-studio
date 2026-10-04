package com.violinstudio.domain.feature.auth.usecase

import com.violinstudio.domain.feature.auth.model.AuthProvider
import com.violinstudio.domain.feature.auth.repository.AuthRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first

enum class ReauthMethod { PASSWORD, GOOGLE, NONE }

/** Como puede reautenticarse el usuario actual (la contrasena gana si hay ambos proveedores).. */
class GetReauthMethodUseCase @Inject constructor(private val auth: AuthRepository) {
    suspend operator fun invoke(): ReauthMethod {
        val user = auth.authUser.first() ?: return ReauthMethod.NONE
        return when {
            user.usesPassword -> ReauthMethod.PASSWORD
            AuthProvider.GOOGLE in user.providers -> ReauthMethod.GOOGLE
            else -> ReauthMethod.NONE
        }
    }
}
