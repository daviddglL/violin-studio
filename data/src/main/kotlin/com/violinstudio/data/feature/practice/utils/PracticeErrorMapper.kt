package com.violinstudio.data.feature.practice.utils

import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreException.Code
import com.violinstudio.domain.feature.practice.failure.PracticeFailure

object PracticeErrorMapper {
    fun map(error: Throwable): PracticeFailure = when {
        error is PracticeFailure -> error
        error is FirebaseFirestoreException && error.code == Code.PERMISSION_DENIED -> PracticeFailure.PermissionDenied
        else -> PracticeFailure.Unknown
    }
}
