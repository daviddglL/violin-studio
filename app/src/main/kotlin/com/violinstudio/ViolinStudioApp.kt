package com.violinstudio

import android.app.Application
import com.google.firebase.Firebase
import com.google.firebase.crashlytics.crashlytics
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ViolinStudioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        installAppCheck()
        Firebase.crashlytics.isCrashlyticsCollectionEnabled = BuildConfig.CRASHLYTICS_ENABLED
    }
}
