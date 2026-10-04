package com.violinstudio.ui.commons.auth

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.violinstudio.domain.feature.auth.model.GoogleIdToken

sealed interface GoogleIdTokenResult {
    data class Token(val token: GoogleIdToken) : GoogleIdTokenResult
    data object Cancelled : GoogleIdTokenResult
    data object ProviderUnavailable : GoogleIdTokenResult
}

/** Pide un ID token de Google; necesita un contexto de Activity para mostrar la hoja de Credential Manager. */
interface GoogleIdTokenRequester {
    suspend fun request(context: Context): GoogleIdTokenResult
}

/** Valor por defecto del `CompositionLocal`: sin proveedor Google no falla, simplemente no esta disponible. */
object UnavailableGoogleIdTokenRequester : GoogleIdTokenRequester {
    override suspend fun request(context: Context): GoogleIdTokenResult = GoogleIdTokenResult.ProviderUnavailable
}

val LocalGoogleIdTokenRequester = staticCompositionLocalOf<GoogleIdTokenRequester> { UnavailableGoogleIdTokenRequester }
