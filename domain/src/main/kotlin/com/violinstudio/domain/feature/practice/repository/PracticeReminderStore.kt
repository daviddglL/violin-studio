package com.violinstudio.domain.feature.practice.repository

import com.violinstudio.domain.feature.practice.model.PracticeReminder
import java.time.Duration
import kotlinx.coroutines.flow.Flow

/** Ajuste del recordatorio por uid, solo en el dispositivo. Leer nunca falla: sin valor es el de por defecto. */
interface PracticeReminderStore {
    fun observe(uid: String): Flow<PracticeReminder>

    suspend fun set(uid: String, reminder: PracticeReminder)
}

/**
 * Programa el unico aviso del dispositivo para dentro de [delay] o lo anula. Sustituye al programado; con
 * [afterCurrent] (lo pide el propio aviso al saltar) se encadena tras el que esta corriendo, sin cortarlo.
 */
interface PracticeReminderScheduler {
    fun schedule(delay: Duration, afterCurrent: Boolean = false)

    fun cancel()
}
