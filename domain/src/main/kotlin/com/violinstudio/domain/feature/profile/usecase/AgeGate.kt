package com.violinstudio.domain.feature.profile.usecase

import java.time.Clock
import java.time.LocalDate
import java.time.Period
import java.time.ZoneOffset
import javax.inject.Inject

/**
 * Solo feedback de formulario: el servidor decide si es menor (`isMinor`) y es lo que manda.
 * Calcula la fecha de hoy en UTC con el reloj inyectado, igual que el servidor (`ageOn` en UTC). Quien nace un
 * 29 de febrero cumple el 1 de marzo en años no bisiestos (también igual que el servidor).
 */
class AgeGate(private val threshold: Int, private val clock: Clock) {
    @Inject
    constructor(clock: Clock) : this(DEFAULT_THRESHOLD, clock)

    fun ageOf(birthDate: LocalDate): Int = Period.between(
        birthDate,
        LocalDate.now(clock.withZone(ZoneOffset.UTC))
    ).years

    /** Una fecha futura cuenta como menor (fail-closed, igual que el servidor). */
    fun isBelowThreshold(birthDate: LocalDate): Boolean = ageOf(birthDate) < threshold

    companion object {
        const val DEFAULT_THRESHOLD = 14
    }
}
