package com.violinstudio

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.google.firebase.Firebase
import com.google.firebase.crashlytics.crashlytics
import com.violinstudio.ui.commons.AuthDiagnostics
import com.violinstudio.ui.feature.session.ForegroundSessionRefresh
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ViolinStudioApp : Application() {
    @Inject
    lateinit var foregroundSessionRefresh: ForegroundSessionRefresh

    @Inject
    lateinit var uploadQueueStarter: UploadQueueStarter

    override fun onCreate() {
        super.onCreate()
        AuthDiagnostics.verbose = BuildConfig.DEBUG
        installAppCheck()
        // super.onCreate() de Hilt ya inyecto el campo: ON_START del proceso pide reevaluar la sesion.
        foregroundSessionRefresh.install()
        uploadQueueStarter.install()
        createNotificationChannel()
        Firebase.crashlytics.isCrashlyticsCollectionEnabled = BuildConfig.CRASHLYTICS_ENABLED
    }

    /** Canal de los avisos push (chat, revisiones, tareas); crearlo no pide permisos ni genera ningun token. */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            ACTIVITY_CHANNEL_ID,
            getString(com.violinstudio.ui.R.string.push_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = getString(com.violinstudio.ui.R.string.push_channel_description) }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
}
