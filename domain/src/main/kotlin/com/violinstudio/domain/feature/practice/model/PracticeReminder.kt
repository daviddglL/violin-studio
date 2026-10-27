package com.violinstudio.domain.feature.practice.model

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Recordatorio diario de practica: vive solo en el dispositivo (por usuario) y lo pinta el propio movil, sin servidor.
 * Apagado por defecto. Solo avisa si ese dia aun no hay practica registrada.
 */
data class PracticeReminder(val enabled: Boolean = false, val time: LocalTime = DEFAULT_TIME) {
    companion object {
        val DEFAULT_TIME: LocalTime = LocalTime.of(18, 0)

        /**
         * Espera desde [now] hasta la siguiente [time] en [zone] que caiga al menos [margin] despues: el trabajo que
         * acaba de saltar no se reprograma para el mismo minuto. Usa la hora local, asi que sigue los cambios de hora.
         */
        fun delayUntilNext(now: Instant, zone: ZoneId, time: LocalTime, margin: Duration = Duration.ZERO): Duration {
            val earliest = now.plus(margin).atZone(zone)
            val sameDay = earliest.with(time)
            val next = if (sameDay.isAfter(earliest)) sameDay else earliest.plusDays(1).with(time)
            return Duration.between(now, next.toInstant())
        }
    }
}
