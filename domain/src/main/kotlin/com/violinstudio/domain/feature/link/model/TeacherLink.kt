package com.violinstudio.domain.feature.link.model

import com.violinstudio.domain.feature.profile.model.Instrument
import java.time.Instant

/** Vínculo `teacherLinks/{t}_{s}`: existe ⇔ activo. Los nombres e instrumento son copias del servidor (REQ-PRF-T04). */
data class TeacherLink(
    val teacherUid: String,
    val studentUid: String,
    val teacherDisplayName: String,
    val studentDisplayName: String,
    val studentInstrument: Instrument?,
    val ackVersion: Int,
    val createdAt: Instant
) {
    override fun toString(): String = "TeacherLink(ackVersion=$ackVersion, createdAt=$createdAt)"
}
