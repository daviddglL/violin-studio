package com.violinstudio.data.feature.assignment.dto

import java.time.Instant

/** Documento `assignments/{id}` tolerante: cada campo es nulo si faltaba o tenia otro tipo. */
data class AssignmentDto(
    val id: String,
    val teacherUid: String?,
    val title: String?,
    val description: String?,
    val dueAt: Instant?,
    val status: String?,
    val doneAt: Instant?,
    val createdAt: Instant?,
    val pendingSync: Boolean
) {
    override fun toString(): String = "AssignmentDto(status=$status, pendingSync=$pendingSync)"
}
