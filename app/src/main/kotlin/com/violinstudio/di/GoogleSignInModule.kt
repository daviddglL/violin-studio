package com.violinstudio.di

import android.util.Log
import com.violinstudio.BuildConfig
import com.violinstudio.ui.commons.auth.CredentialManagerGoogleIdTokenRequester
import com.violinstudio.ui.commons.auth.GoogleIdTokenRequester
import com.violinstudio.ui.commons.auth.GoogleSignInConfig
import com.violinstudio.ui.commons.auth.GoogleSignInDiagnostics
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Módulo propio y aislado para poder sustituirlo con `@TestInstallIn` en las pruebas instrumentadas. */
@Module
@InstallIn(SingletonComponent::class)
object GoogleSignInModule {
    private const val TAG = "GoogleSignIn"

    /** Solo en debug y solo el motivo (clase de la excepción): nunca mensajes, tokens ni emails. */
    @Provides
    fun provideGoogleSignInDiagnostics(): GoogleSignInDiagnostics = GoogleSignInDiagnostics { reason ->
        if (BuildConfig.DEBUG) Log.w(TAG, "Google sign-in unavailable: $reason")
    }

    @Provides
    fun provideGoogleIdTokenRequester(
        config: GoogleSignInConfig,
        diagnostics: GoogleSignInDiagnostics
    ): GoogleIdTokenRequester = CredentialManagerGoogleIdTokenRequester(config, diagnostics = diagnostics)
}
