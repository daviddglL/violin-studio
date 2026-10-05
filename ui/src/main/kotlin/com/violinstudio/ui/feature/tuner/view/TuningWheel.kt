package com.violinstudio.ui.feature.tuner.view

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.cos
import kotlin.math.sin

private const val SWEEP_DEGREES = 80.0
val InTuneGreen = Color(0xFF2E7D32)

/**
 * Rueda semicircular: ticks según [maxCents] y aguja en [cents] (`null` = sin lectura), fijada en el borde cuando
 * se desborda. El texto accesible lo aporta [description]; el dibujo en sí no se anuncia.
 */
@Composable
fun TuningWheel(cents: Double?, maxCents: Int, inTune: Boolean, description: String, modifier: Modifier = Modifier) {
    val scale = MaterialTheme.colorScheme.outline
    val needle = if (inTune) InTuneGreen else MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxWidth().aspectRatio(2f).semantics { contentDescription = description }) {
        val radius = size.width / 2f * 0.9f
        val center = Offset(size.width / 2f, size.height * 0.95f)
        fun point(fraction: Float, r: Float): Offset {
            val angle = Math.toRadians(fraction * SWEEP_DEGREES)
            return Offset(center.x + (r * sin(angle)).toFloat(), center.y - (r * cos(angle)).toFloat())
        }
        for (tick in tickValues(maxCents)) {
            val fraction = tick / maxCents.toFloat()
            val length = if (tick == 0) radius * 0.22f else radius * 0.12f
            val color = if (tick == 0) InTuneGreen else scale
            drawLine(color, point(fraction, radius), point(fraction, radius - length), strokeWidth = 4f)
        }
        if (cents != null) {
            drawLine(needle, center, point(needleFraction(cents, maxCents), radius * 0.95f), 8f, StrokeCap.Round)
        }
    }
}
