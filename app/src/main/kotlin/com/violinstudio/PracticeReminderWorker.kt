package com.violinstudio

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.violinstudio.domain.feature.practice.usecase.PracticeReminderDueUseCase
import com.violinstudio.ui.MainActivity
import com.violinstudio.ui.navigation.PUSH_KIND_EXTRA
import com.violinstudio.ui.navigation.PUSH_KIND_PRACTICE
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Recordatorio diario de practica: lo programa [WorkManagerPracticeReminderScheduler] y, al saltar, deja programado el
 * del dia siguiente y avisa solo si hoy no hay practica. El aviso es local (no pasa por FCM ni por el servidor) y su
 * texto es fijo. Sin permiso de avisos (Android 13+) o con los avisos apagados no se pinta nada.
 */
class PracticeReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun practiceReminderDue(): PracticeReminderDueUseCase
    }

    override suspend fun doWork(): Result {
        val due = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
            .practiceReminderDue()
        if (due()) notify(applicationContext)
        return Result.success()
    }

    companion object {
        const val NOTIFICATION_ID = 1_001

        fun notify(context: Context) {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            val open = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(PUSH_KIND_EXTRA, PUSH_KIND_PRACTICE)
            val tap = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_practice)
                .setContentTitle(context.getString(com.violinstudio.ui.R.string.reminder_notification_title))
                .setContentText(context.getString(com.violinstudio.ui.R.string.reminder_notification_text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            try {
                manager.notify(NOTIFICATION_ID, notification)
            } catch (_: SecurityException) {
                // El permiso se retiro entre la comprobacion y el aviso.
            }
        }
    }
}

/** Canal del recordatorio diario, aparte del de actividad para que se pueda silenciar por separado. */
const val REMINDER_CHANNEL_ID = "practice_reminder"
