package com.violinstudio.data.commons.di

import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.firestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.functions
import com.violinstudio.data.commons.firebase.EmulatorConfig
import com.violinstudio.data.commons.firebase.EmulatorOnce
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

const val FUNCTIONS_REGION = "europe-west1"

/** `useEmulator` se aplica al crear cada singleton, antes de que nadie lo use. */
@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {
    @Provides
    @Singleton
    fun provideFunctions(config: EmulatorConfig): FirebaseFunctions = Firebase.functions(FUNCTIONS_REGION).apply {
        config.functions()?.let { e -> EmulatorOnce.process.apply("functions") { useEmulator(e.host, e.port) } }
    }

    @Provides
    @Singleton
    fun provideAuth(config: EmulatorConfig): FirebaseAuth = Firebase.auth.apply {
        config.auth()?.let { e -> EmulatorOnce.process.apply("auth") { useEmulator(e.host, e.port) } }
    }

    @Provides
    @Singleton
    fun provideFirestore(config: EmulatorConfig): FirebaseFirestore = Firebase.firestore.apply {
        config.firestore()?.let { e -> EmulatorOnce.process.apply("firestore") { useEmulator(e.host, e.port) } }
    }

    /** Para `AgeGate`; los tests usan relojes fijos. */
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()
}
