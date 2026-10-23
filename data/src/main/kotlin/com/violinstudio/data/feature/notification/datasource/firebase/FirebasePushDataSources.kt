package com.violinstudio.data.feature.notification.datasource.firebase

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.messaging.FirebaseMessaging
import com.violinstudio.data.commons.firebase.FunctionsCallException
import com.violinstudio.data.feature.notification.datasource.DeviceFunctionsDataSource
import com.violinstudio.data.feature.notification.datasource.PushTokenSource
import javax.inject.Inject
import kotlinx.coroutines.tasks.await

/** Adaptadores finos sobre FCM y los callables; se prueban en el E2E y en dispositivo. */
class FirebasePushTokenSource @Inject constructor() : PushTokenSource {
    override suspend fun token(): String = FirebaseMessaging.getInstance().token.await()

    override suspend fun delete() {
        FirebaseMessaging.getInstance().deleteToken().await()
    }
}

class FirebaseDeviceFunctionsDataSource @Inject constructor(private val functions: FirebaseFunctions) :
    DeviceFunctionsDataSource {
    override suspend fun register(token: String) {
        call("registerDevice", token)
    }

    override suspend fun unregister(token: String) {
        call("unregisterDevice", token)
    }

    private suspend fun call(name: String, token: String) {
        try {
            functions.getHttpsCallable(name).call(mapOf("token" to token)).await()
        } catch (e: FirebaseFunctionsException) {
            throw FunctionsCallException(e.code.name, e.details, e)
        }
    }
}
