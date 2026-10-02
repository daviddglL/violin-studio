package com.violinstudio.di

import android.content.Context
import com.violinstudio.BuildConfig
import com.violinstudio.data.commons.firebase.EmulatorConfig
import com.violinstudio.ui.commons.auth.GoogleSignInConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object AppConfigModule {
    @Provides
    fun provideEmulatorConfig(): EmulatorConfig =
        EmulatorConfig(enabled = BuildConfig.USE_EMULATORS, host = BuildConfig.EMULATOR_HOST)

    /**
     * `default_web_client_id` lo genera el plugin de google-services por flavor, y solo si el `google-services.json`
     * trae un cliente OAuth web. Se busca por nombre en tiempo de ejecución: si falta (build de CI o proyecto sin
     * Google habilitado) no rompe la compilación; el botón de Google queda como proveedor no disponible.
     */
    @Provides
    fun provideGoogleSignInConfig(@ApplicationContext context: Context): GoogleSignInConfig {
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        return GoogleSignInConfig(serverClientId = if (id == 0) null else context.getString(id))
    }
}
