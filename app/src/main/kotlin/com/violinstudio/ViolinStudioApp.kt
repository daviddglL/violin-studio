package com.violinstudio

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.google.firebase.Firebase
import com.google.firebase.crashlytics.crashlytics
import com.violinstudio.ui.commons.AuthDiagnostics
import com.violinstudio.ui.feature.session.ForegroundSessionRefresh
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ViolinStudioApp :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var foregroundSessionRefresh: ForegroundSessionRefresh

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    // WorkManager lo pide al primer uso (ya inyectado); el inicializador por defecto esta quitado del manifest.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        AuthDiagnostics.verbose = BuildConfig.DEBUG
        installAppCheck()
        // super.onCreate() de Hilt ya inyecto el campo: ON_START del proceso pide reevaluar la sesion.
        foregroundSessionRefresh.install()
        Firebase.crashlytics.isCrashlyticsCollectionEnabled = BuildConfig.CRASHLYTICS_ENABLED
    }
}
