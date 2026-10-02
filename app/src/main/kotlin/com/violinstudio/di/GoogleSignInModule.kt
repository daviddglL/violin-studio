package com.violinstudio.di

import com.violinstudio.ui.commons.auth.CredentialManagerGoogleIdTokenRequester
import com.violinstudio.ui.commons.auth.GoogleIdTokenRequester
import com.violinstudio.ui.commons.auth.GoogleSignInConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Módulo propio y aislado para poder sustituirlo con `@TestInstallIn` en las pruebas instrumentadas. */
@Module
@InstallIn(SingletonComponent::class)
object GoogleSignInModule {
    @Provides
    fun provideGoogleIdTokenRequester(config: GoogleSignInConfig): GoogleIdTokenRequester =
        CredentialManagerGoogleIdTokenRequester(config)
}
