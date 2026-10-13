package com.violinstudio.data.feature.link.utils.extensions

import com.violinstudio.data.feature.link.dto.TeacherLinkDto
import com.violinstudio.domain.feature.link.model.TeacherLink
import com.violinstudio.domain.feature.profile.model.Instrument

/** `null` si falta la identidad, la version del aviso o la fecha; nombres ausentes quedan vacios y un instrumento desconocido en `null`. */
fun TeacherLinkDto.toDomain(): TeacherLink? {
    val teacher = teacherUid ?: return null
    val student = studentUid ?: return null
    return TeacherLink(
        teacherUid = teacher,
        studentUid = student,
        teacherDisplayName = teacherDisplayName.orEmpty(),
        studentDisplayName = studentDisplayName.orEmpty(),
        studentInstrument = Instrument.fromWire(studentInstrument),
        ackVersion = ackVersion ?: return null,
        createdAt = createdAt ?: return null
    )
}
