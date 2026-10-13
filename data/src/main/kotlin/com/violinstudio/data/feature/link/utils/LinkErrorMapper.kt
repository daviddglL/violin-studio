package com.violinstudio.data.feature.link.utils

import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreException.Code
import com.violinstudio.domain.feature.link.failure.LinkFailure
import java.io.IOException

/** Errores de los listeners de `teacherLinks`; los de los callables van por `FunctionsErrorMapper.toLinkFailure`. */
object LinkErrorMapper {
    fun map(error: Throwable): LinkFailure = when {
        error is LinkFailure -> error
        error is FirebaseFirestoreException && error.code == Code.PERMISSION_DENIED -> LinkFailure.PermissionDenied
        error is IOException -> LinkFailure.Offline
        else -> LinkFailure.Unknown
    }
}
