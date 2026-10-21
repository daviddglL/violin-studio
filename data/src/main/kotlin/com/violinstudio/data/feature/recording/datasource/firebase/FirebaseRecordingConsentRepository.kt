package com.violinstudio.data.feature.recording.datasource.firebase

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.violinstudio.data.commons.firebase.FunctionsCallException
import com.violinstudio.data.commons.firebase.FunctionsErrorMapper
import com.violinstudio.data.commons.utils.resultOf
import com.violinstudio.data.feature.consent.utils.ConsentResponseParser
import com.violinstudio.domain.feature.recording.repository.RecordingConsentRepository
import javax.inject.Inject
import kotlinx.coroutines.tasks.await

/**
 * Callables `grantRecordingConsent` / `revokeRecordingConsent` y `requestGuardianConsent` con la finalidad
 * `recording_sharing`; los fallos se traducen con `toConsentFailure`.
 */
class FirebaseRecordingConsentRepository @Inject constructor(private val functions: FirebaseFunctions) :
    RecordingConsentRepository {
    override suspend fun grant(version: Int): Result<Unit> = resultOf(FunctionsErrorMapper::toConsentFailure) {
        call("grantRecordingConsent", mapOf("version" to version))
    }

    override suspend fun revoke(): Result<Unit> = resultOf(FunctionsErrorMapper::toConsentFailure) {
        call("revokeRecordingConsent")
    }

    override suspend fun requestGuardian(guardianEmail: String): Result<String> =
        resultOf(FunctionsErrorMapper::toConsentFailure) {
            ConsentResponseParser.parseReceipt(
                call("requestGuardianConsent", mapOf("guardianEmail" to guardianEmail, "purpose" to PURPOSE))
            ).emailMasked
        }

    private suspend fun call(name: String, data: Any? = null): Any? {
        try {
            return functions.getHttpsCallable(name).call(data).await().data
        } catch (e: FirebaseFunctionsException) {
            throw FunctionsCallException(e.code.name, e.details, e)
        }
    }

    private companion object {
        const val PURPOSE = "recording_sharing"
    }
}
