package com.violinstudio.data.commons.di

import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.firestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.functions
import com.violinstudio.data.commons.erasure.CachePurgeFlag
import com.violinstudio.data.commons.erasure.FirestoreCachePurge
import com.violinstudio.data.commons.firebase.EmulatorConfig
import com.violinstudio.data.commons.firebase.EmulatorOnce
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton
import kotlinx.coroutines.tasks.await

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

    /**
     * Si un borrado de cuenta dejo la marca, la cache offline se purga aqui, antes de cualquier uso. El bloqueo es breve
     * (borrado de ficheros locales, acotado a 3 s) y solo ocurre en el primer arranque tras un borrado.
     * Si hay un usuario autenticado no se purga y la marca se conserva (issue #47): evita perder escrituras offline
     * pendientes de otra cuenta; se purgara en un arranque posterior sin sesion.
     */
    @Provides
    @Singleton
    fun provideFirestore(config: EmulatorConfig, purgeFlag: CachePurgeFlag, auth: FirebaseAuth): FirebaseFirestore =
        Firebase.firestore.apply {
            FirestoreCachePurge.runIfRequested(purgeFlag, isSignedIn = { auth.currentUser != null }) {
                clearPersistence().await()
            }
            config.firestore()?.let { e -> EmulatorOnce.process.apply("firestore") { useEmulator(e.host, e.port) } }
        }

    /** Para `AgeGate`; los tests usan relojes fijos. */
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()
}
