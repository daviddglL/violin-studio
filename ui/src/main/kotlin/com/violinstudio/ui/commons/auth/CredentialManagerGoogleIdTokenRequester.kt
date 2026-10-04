package com.violinstudio.ui.commons.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.violinstudio.domain.feature.auth.model.GoogleIdToken
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CancellationException

/**
 * `serverClientId` es el `default_web_client_id` del proyecto Firebase; `null` si el build no lo trae (por ejemplo
 * un `google-services.json` sin cliente OAuth): Google queda entonces como proveedor no disponible, sin crashear.
 */
data class GoogleSignInConfig(val serverClientId: String?)

/**
 * Parámetros de la petición, puros. [nonce] es el SHA-256 hexadecimal del nonce crudo: lo que Google incrusta en el
 * ID token (el `GetGoogleIdOption` real se construye en el adaptador).
 */
data class GoogleIdRequestSpec(
    val serverClientId: String,
    val nonce: String,
    val filterByAuthorizedAccounts: Boolean,
    val autoSelectEnabled: Boolean
)

/**
 * Diagnóstico de fallos de Google. Solo recibe un motivo sin datos personales: el nombre de la clase de la excepción,
 * nunca su mensaje (puede llevar el email o el token).
 */
fun interface GoogleSignInDiagnostics {
    fun report(reason: String)
}

/** Costura fina sobre Credential Manager: devuelve el ID token de Google o lanza la excepción de Credential Manager. */
fun interface GoogleCredentialSource {
    suspend fun fetch(context: Context, spec: GoogleIdRequestSpec): String
}

object GoogleIdOptionFactory {
    private const val NONCE_BYTES = 32
    private val random = SecureRandom()

    /**
     * Acción explícita del usuario (botón): se ofrecen todas las cuentas de Google del dispositivo, no solo las ya
     * autorizadas, y sin selección automática.
     */
    fun create(serverClientId: String, rawNonce: String) = GoogleIdRequestSpec(
        serverClientId = serverClientId,
        nonce = sha256Hex(rawNonce),
        filterByAuthorizedAccounts = false,
        autoSelectEnabled = false
    )

    fun sha256Hex(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    /**
     * Nonce crudo nuevo e impredecible por petición. A Google se envía su hash; el crudo viaja con el token hasta
     * Firebase, que comprueba que el hash del crudo coincide con el `nonce` del ID token (anti-replay).
     */
    fun newNonce(): String {
        val bytes = ByteArray(NONCE_BYTES).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

class CredentialManagerGoogleIdTokenRequester(
    private val config: GoogleSignInConfig,
    private val source: GoogleCredentialSource = CredentialManagerSource,
    private val diagnostics: GoogleSignInDiagnostics = GoogleSignInDiagnostics {}
) : GoogleIdTokenRequester {
    override suspend fun request(context: Context): GoogleIdTokenResult {
        val serverClientId = config.serverClientId?.takeIf { it.isNotBlank() }
        if (serverClientId == null) {
            diagnostics.report("serverClientId missing")
            return GoogleIdTokenResult.ProviderUnavailable
        }
        return try {
            val rawNonce = GoogleIdOptionFactory.newNonce()
            val spec = GoogleIdOptionFactory.create(serverClientId, rawNonce)
            val idToken = source.fetch(context, spec)
            if (idToken.isBlank()) {
                GoogleIdTokenResult.ProviderUnavailable
            } else {
                GoogleIdTokenResult.Token(GoogleIdToken(idToken, rawNonce))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: GetCredentialCancellationException) {
            GoogleIdTokenResult.Cancelled
        } catch (e: Throwable) {
            // NoCredential, interrupciones y desconocidos: el usuario ve lo mismo; el diagnóstico solo lleva la clase.
            diagnostics.report(e.javaClass.simpleName)
            GoogleIdTokenResult.ProviderUnavailable
        }
    }
}

/** Adaptador real: no se prueba en JVM (necesita Google Play Services y una Activity). */
private object CredentialManagerSource : GoogleCredentialSource {
    override suspend fun fetch(context: Context, spec: GoogleIdRequestSpec): String {
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(spec.serverClientId)
            .setNonce(spec.nonce)
            .setFilterByAuthorizedAccounts(spec.filterByAuthorizedAccounts)
            .setAutoSelectEnabled(spec.autoSelectEnabled)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = CredentialManager.create(context).getCredential(context, request).credential
        val isGoogleId = credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        check(isGoogleId) { "Credencial inesperada" }
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }
}
