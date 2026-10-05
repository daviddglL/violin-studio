package com.violinstudio.ui.feature.tuner.view

import kotlin.math.abs
import kotlin.math.roundToInt

/** Posición de la aguja en -1..1; el estado conserva los cents sin acotar y aquí se fija en el borde. */
fun needleFraction(cents: Double, maxCents: Int): Float = (cents / maxCents).coerceIn(-1.0, 1.0).toFloat()

/** Cents con signo y unidad ("+87 ¢"), sin depender del locale. */
fun centsLabel(cents: Double): String {
    val rounded = cents.roundToInt()
    return (if (rounded > 0) "+" else "") + "$rounded ¢"
}

/** Etiqueta de desborde: `null` si la lectura cabe en la escala. */
fun overflowLabel(cents: Double, maxCents: Int): String? = if (abs(cents) <= maxCents) null else centsLabel(cents)

private val TICK_STEPS = listOf(5, 10, 25, 50)

/** Marcas de la rueda: múltiplos de un paso legible (<= 5 por lado) y siempre el 0 y ±[maxCents]. */
fun tickValues(maxCents: Int): List<Int> {
    val step = TICK_STEPS.firstOrNull { maxCents / it <= 5 } ?: TICK_STEPS.last()
    val positive = (step..maxCents step step).toSet() + maxCents
    return (positive.map { -it } + 0 + positive).sorted()
}
