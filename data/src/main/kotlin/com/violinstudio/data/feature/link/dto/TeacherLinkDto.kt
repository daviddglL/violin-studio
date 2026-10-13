package com.violinstudio.data.feature.link.dto

import java.time.Instant

/** Documento `teacherLinks/{t}_{s}` tolerante: cada campo es nulo si faltaba o tenia otro tipo. */
data class TeacherLinkDto(
    val teacherUid: String?,
    val studentUid: String?,
    val teacherDisplayName: String?,
    val studentDisplayName: String?,
    val studentInstrument: String?,
    val ackVersion: Int?,
    val createdAt: Instant?
) {
    override fun toString(): String = "TeacherLinkDto(ackVersion=$ackVersion, createdAt=$createdAt)"
}
