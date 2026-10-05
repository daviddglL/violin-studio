package com.violinstudio.ui.feature.tuner.view

import kotlin.math.abs
import kotlin.math.roundToInt

/** Posición de la aguja en -1..1; el estado conserva los cents sin acotar y aquí se fija en el borde. */
fun needleFraction(cents: Double, maxCents: Int): Float =
    if (cents.isFinite()) (cents / maxCents).coerceIn(-1.0, 1.0).toFloat() else 0f

/** Cents con signo y unidad ("+87 ¢"), sin depender del locale; "—" si no es un número. */
fun centsLabel(cents: Double): String {
    if (!cents.isFinite()) return "—"
    val rounded = cents.roundToInt()
    return (if (rounded > 0) "+" else "") + "$rounded ¢"
}

/** Fuera de escala según los cents redondeados que se muestran (50,4 con tope 50 no lo está). */
fun isOffScale(cents: Double, maxCents: Int): Boolean = cents.isFinite() && abs(cents.roundToInt()) > maxCents

/** Etiqueta de desborde: `null` si la lectura cabe en la escala. */
fun overflowLabel(cents: Double, maxCents: Int): String? = if (isOffScale(cents, maxCents)) centsLabel(cents) else null

private val TICK_STEPS = listOf(5, 10, 25, 50)

/** Marcas de la rueda: múltiplos de un paso legible (<= 5 por lado) y siempre el 0 y ±[maxCents]. */
fun tickValues(maxCents: Int): List<Int> {
    val step = TICK_STEPS.firstOrNull { maxCents / it <= 5 } ?: TICK_STEPS.last()
    val positive = (step..maxCents step step).toSet() + maxCents
    return (positive.map { -it } + 0 + positive).sorted()
}
