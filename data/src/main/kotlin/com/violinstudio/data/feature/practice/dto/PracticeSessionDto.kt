package com.violinstudio.data.feature.practice.dto

import java.time.Instant

/** Documento `practiceSessions/{id}` tolerante: cada campo es nulo si faltaba o tenía otro tipo. */
data class PracticeSessionDto(
    val id: String,
    val startedAt: Instant?,
    val durationSec: Int?,
    val instrument: String?,
    val notes: String?,
    val pendingSync: Boolean
) {
    override fun toString(): String = "PracticeSessionDto(durationSec=$durationSec, instrument=$instrument)"
}
