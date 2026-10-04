package com.violinstudio

import android.content.Context
import android.util.Base64
import com.violinstudio.di.GoogleSignInModule
import com.violinstudio.domain.feature.auth.model.GoogleIdToken
import com.violinstudio.ui.commons.auth.GoogleIdTokenRequester
import com.violinstudio.ui.commons.auth.GoogleIdTokenResult
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn

/**
 * Credential Manager no se puede automatizar en un test: se entrega un ID token JSON SIN FIRMAR (`alg: none`) que
 * solo acepta el emulador de Auth.
 */
class FakeGoogleIdTokenRequester(private val email: String = "google.user@example.test") : GoogleIdTokenRequester {
    override suspend fun request(context: Context): GoogleIdTokenResult {
        val flags = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        fun encode(json: String) = Base64.encodeToString(json.toByteArray(), flags)
        val header = encode("""{"alg":"none","typ":"JWT"}""")
        val payload = encode(
            """{"iss":"https://accounts.google.com","sub":"fake-google-user","email":"$email","email_verified":true}"""
        )
        return GoogleIdTokenResult.Token(GoogleIdToken("$header.$payload."))
    }
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [GoogleSignInModule::class])
object FakeGoogleSignInModule {
    @Provides
    fun provideGoogleIdTokenRequester(): GoogleIdTokenRequester = FakeGoogleIdTokenRequester()
}
