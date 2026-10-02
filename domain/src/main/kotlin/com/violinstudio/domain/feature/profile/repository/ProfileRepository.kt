package com.violinstudio.domain.feature.profile.repository

import com.violinstudio.domain.feature.profile.model.EditableProfile
import com.violinstudio.domain.feature.profile.model.ProfileRegistration
import com.violinstudio.domain.feature.profile.model.UserProfile
import kotlinx.coroutines.flow.Flow

/** Los fallos llegan como `Result.failure(ProfileFailure)`. */
interface ProfileRepository {
    /** `null` mientras no exista `users/{uid}`. */
    fun observe(uid: String): Flow<UserProfile?>

    /** Idempotente en el servidor: reenviar devuelve el perfil existente sin modificarlo. */
    suspend fun register(registration: ProfileRegistration): Result<Unit>

    suspend fun update(uid: String, profile: EditableProfile): Result<Unit>
}
