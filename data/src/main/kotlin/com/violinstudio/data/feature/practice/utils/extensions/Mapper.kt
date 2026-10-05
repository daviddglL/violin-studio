package com.violinstudio.data.feature.practice.utils.extensions

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.violinstudio.data.feature.practice.dto.PracticeSessionDto
import com.violinstudio.domain.feature.practice.model.PracticeDraft
import com.violinstudio.domain.feature.practice.model.PracticeRules
import com.violinstudio.domain.feature.practice.model.PracticeSession
import com.violinstudio.domain.feature.profile.model.Instrument

/** Solo las claves que permiten las reglas; `notes` solo si hay y `createdAt` lo pone el servidor. */
fun PracticeDraft.toFields(): Map<String, Any> = buildMap {
    put("startedAt", Timestamp(startedAt.epochSecond, startedAt.nano))
    put("durationSec", durationSec)
    put("instrument", instrument.wire)
    notes?.let { put("notes", it) }
    put("createdAt", FieldValue.serverTimestamp())
}

/** Actualización de solo `notes`: vacío o nulo borra el campo. */
fun notesUpdate(notes: String?): Map<String, Any> =
    mapOf("notes" to (notes?.trim()?.ifEmpty { null } ?: FieldValue.delete()))

/** `null` si falta algo o está fuera de rango: el doc se omite en vez de romper el historial. */
fun PracticeSessionDto.toDomain(): PracticeSession? {
    val started = startedAt ?: return null
    val duration = durationSec?.takeIf { it in PracticeRules.MIN_DURATION_SEC..PracticeRules.MAX_DURATION_SEC }
        ?: return null
    val instrument = Instrument.fromWire(instrument) ?: return null
    return PracticeSession(id, started, duration, instrument, notes, pendingSync)
}
