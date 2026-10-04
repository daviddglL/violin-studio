package com.violinstudio

import android.app.Application
import com.google.firebase.Firebase
import com.google.firebase.crashlytics.crashlytics
import com.violinstudio.ui.feature.session.ForegroundSessionRefresh
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ViolinStudioApp : Application() {
    @Inject
    lateinit var foregroundSessionRefresh: ForegroundSessionRefresh

    override fun onCreate() {
        super.onCreate()
        installAppCheck()
        // super.onCreate() de Hilt ya inyecto el campo: ON_START del proceso pide reevaluar la sesion.
        foregroundSessionRefresh.install()
        Firebase.crashlytics.isCrashlyticsCollectionEnabled = BuildConfig.CRASHLYTICS_ENABLED
    }
}
