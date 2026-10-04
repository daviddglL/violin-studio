package com.violinstudio.domain.feature.auth.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/** Uid del usuario con sesion, o `null` sin sesion. */
class GetOwnUidUseCase @Inject constructor(private val auth: AuthRepository) {
    suspend operator fun invoke(): String? = auth.authUser.first()?.uid
}
