package com.violinstudio.core.firebase.di

import com.google.firebase.Firebase
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.functions
import com.violinstudio.core.firebase.EmulatorConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

const val FUNCTIONS_REGION = "europe-west1"

@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {
    @Provides
    @Singleton
    fun provideFunctions(config: EmulatorConfig): FirebaseFunctions = Firebase.functions(FUNCTIONS_REGION).apply {
        config.functions()?.let { useEmulator(it.host, it.port) }
    }
}
