package com.violinstudio.domain.feature.consent.usecase

import com.violinstudio.domain.feature.consent.failure.ConsentFailure
import com.violinstudio.domain.feature.consent.model.GuardianRequestReceipt
import com.violinstudio.domain.feature.consent.repository.ConsentRepository
import javax.inject.Inject

/** Un formato de email inválido ni sale del dispositivo. `RateLimited(retryAfterSeconds)` se propaga tal cual. */
class RequestGuardianConsentUseCase @Inject constructor(private val consent: ConsentRepository) {
    suspend operator fun invoke(guardianEmail: String): Result<GuardianRequestReceipt> {
        val email = guardianEmail.trim()
        if (!EMAIL.matches(email)) return Result.failure(ConsentFailure.GuardianEmailInvalid)
        return consent.requestGuardianConsent(email)
    }

    private companion object {
        val EMAIL = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""")
    }
}
