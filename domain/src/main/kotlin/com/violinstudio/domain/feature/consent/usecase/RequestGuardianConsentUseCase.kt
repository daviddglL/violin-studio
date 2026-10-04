package com.violinstudio.domain.feature.consent.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.auth.usecase.isPlausibleEmail
import com.violinstudio.domain.feature.consent.PendingGuardianEmail
import com.violinstudio.domain.feature.consent.failure.ConsentFailure
import com.violinstudio.domain.feature.consent.model.GuardianRequestReceipt
import com.violinstudio.domain.feature.consent.repository.ConsentRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/** Un formato de email inválido ni sale del dispositivo. `RateLimited(retryAfterSeconds)` se propaga tal cual. */
class RequestGuardianConsentUseCase @Inject constructor(
    private val consent: ConsentRepository,
    private val pendingEmail: PendingGuardianEmail,
    private val auth: AuthRepository
) {
    suspend operator fun invoke(guardianEmail: String): Result<GuardianRequestReceipt> {
        val email = guardianEmail.trim()
        if (!isPlausibleEmail(email)) return Result.failure(ConsentFailure.GuardianEmailInvalid)
        val uid = auth.authUser.first()?.uid
        return consent.requestGuardianConsent(email).onSuccess {
            // Solo si sigue siendo el mismo usuario: una peticion que acaba tras cerrar sesion o cambiar de cuenta
            // no deja el email de un tutor atado a otra sesion.
            if (uid != null && auth.authUser.first()?.uid == uid) pendingEmail.remember(uid, email)
        }
    }
}
