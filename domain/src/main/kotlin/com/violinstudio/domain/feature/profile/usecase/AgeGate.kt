package com.violinstudio.domain.feature.profile.usecase

import java.time.Clock
import java.time.LocalDate
import java.time.Period
import javax.inject.Inject

/**
 * Solo feedback de formulario: el servidor decide si es menor (`isMinor`) y es lo que manda.
 * Calcula con el reloj inyectado.
 */
class AgeGate(private val threshold: Int, private val clock: Clock) {
    @Inject
    constructor(clock: Clock) : this(DEFAULT_THRESHOLD, clock)

    fun ageOf(birthDate: LocalDate): Int = Period.between(birthDate, LocalDate.now(clock)).years

    /** Una fecha futura cuenta como menor (fail-closed, igual que el servidor). */
    fun isBelowThreshold(birthDate: LocalDate): Boolean = ageOf(birthDate) < threshold

    companion object {
        const val DEFAULT_THRESHOLD = 14
    }
}
