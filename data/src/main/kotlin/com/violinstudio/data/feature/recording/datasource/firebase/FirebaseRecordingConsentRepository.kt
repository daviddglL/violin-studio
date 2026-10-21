package com.violinstudio.data.feature.recording.datasource.firebase

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.violinstudio.data.commons.firebase.FunctionsCallException
import com.violinstudio.data.commons.firebase.FunctionsErrorMapper
import com.violinstudio.data.commons.utils.resultOf
import com.violinstudio.domain.feature.recording.repository.RecordingConsentRepository
import javax.inject.Inject
import kotlinx.coroutines.tasks.await

/** Callables `grantRecordingConsent` / `revokeRecordingConsent`; los fallos se traducen con `toConsentFailure`. */
class FirebaseRecordingConsentRepository @Inject constructor(private val functions: FirebaseFunctions) :
    RecordingConsentRepository {
    override suspend fun grant(version: Int): Result<Unit> = resultOf(FunctionsErrorMapper::toConsentFailure) {
        call("grantRecordingConsent", mapOf("version" to version))
    }

    override suspend fun revoke(): Result<Unit> = resultOf(FunctionsErrorMapper::toConsentFailure) {
        call("revokeRecordingConsent")
    }

    private suspend fun call(name: String, data: Any? = null) {
        try {
            functions.getHttpsCallable(name).call(data).await()
        } catch (e: FirebaseFunctionsException) {
            throw FunctionsCallException(e.code.name, e.details, e)
        }
    }
}
