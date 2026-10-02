package com.violinstudio.data.feature.consent.repository

import com.violinstudio.data.commons.firebase.FunctionsErrorMapper
import com.violinstudio.data.commons.utils.resultOf
import com.violinstudio.data.feature.consent.utils.ConsentResponseParser
import com.violinstudio.data.feature.profile.datasource.IdentityFunctionsDataSource
import com.violinstudio.domain.feature.consent.model.GuardianRequestReceipt
import com.violinstudio.domain.feature.consent.model.IdentityConfig
import com.violinstudio.domain.feature.consent.repository.ConsentRepository
import javax.inject.Inject

class ConsentRepositoryImpl @Inject constructor(private val functions: IdentityFunctionsDataSource) :
    ConsentRepository {
    // Una respuesta malformada lanza MalformedResponseException, que el mapper convierte en Unknown.
    override suspend fun identityConfig(): Result<IdentityConfig> =
        resultOf(FunctionsErrorMapper::toConsentFailure) { ConsentResponseParser.parseConfig(functions.identityConfig()) }

    // La respuesta `{consentStatus}` no se usa: el estado llega por el perfil (observe) y los claims.
    override suspend fun recordConsent(policyVersion: Int): Result<Unit> =
        resultOf(FunctionsErrorMapper::toConsentFailure) {
            functions.recordConsent(policyVersion)
            Unit
        }

    override suspend fun revokeConsent(): Result<Unit> = resultOf(FunctionsErrorMapper::toConsentFailure) {
        functions.revokeConsent()
        Unit
    }

    override suspend fun requestGuardianConsent(guardianEmail: String): Result<GuardianRequestReceipt> =
        resultOf(FunctionsErrorMapper::toConsentFailure) {
            ConsentResponseParser.parseReceipt(functions.requestGuardianConsent(guardianEmail))
        }
}
