package com.violinstudio

import com.google.firebase.messaging.FirebaseMessagingService
import com.violinstudio.domain.feature.notification.usecase.RefreshPushTokenUseCase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Solo atiende el cambio de token. Los avisos llegan como notificacion de FCM con claves de texto (`push_*`): con la
 * app en segundo plano los pinta el sistema en el canal [ACTIVITY_CHANNEL_ID]; en primer plano no se muestran (ya se
 * esta dentro de la app). Sin `onMessageReceived` propio: ningun dato del aviso se procesa ni se guarda.
 */
class PushMessagingService : FirebaseMessagingService() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun refreshPushToken(): RefreshPushTokenUseCase
    }

    override fun onNewToken(token: String) {
        val refresh = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
            .refreshPushToken()
        // El servicio puede terminar antes: es lo mejor posible y el siguiente arranque con sesion vuelve a registrar.
        scope.launch { refresh(token) }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/** Debe coincidir con `ANDROID_CHANNEL_ID` de functions (`notifications/notify.ts`). */
const val ACTIVITY_CHANNEL_ID = "activity"
