package com.violinstudio.data.feature.assignment.utils

import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreException.Code
import com.violinstudio.domain.feature.assignment.failure.AssignmentFailure

object AssignmentErrorMapper {
    fun map(error: Throwable): AssignmentFailure = when {
        error is AssignmentFailure -> error
        error is FirebaseFirestoreException && error.code == Code.PERMISSION_DENIED ->
            AssignmentFailure.PermissionDenied
        else -> AssignmentFailure.Unknown
    }
}
