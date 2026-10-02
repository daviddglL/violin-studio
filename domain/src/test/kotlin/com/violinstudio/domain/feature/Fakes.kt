package com.violinstudio.domain.feature

import com.violinstudio.domain.feature.account.repository.AccountRepository
import com.violinstudio.domain.feature.auth.model.AuthProvider
import com.violinstudio.domain.feature.auth.model.AuthUser
import com.violinstudio.domain.feature.auth.model.GoogleIdToken
import com.violinstudio.domain.feature.auth.model.SessionClaims
import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.consent.model.GuardianRequestReceipt
import com.violinstudio.domain.feature.consent.model.IdentityConfig
import com.violinstudio.domain.feature.consent.repository.ConsentRepository
import com.violinstudio.domain.feature.profile.model.ConsentStatus
import com.violinstudio.domain.feature.profile.model.EditableProfile
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.profile.model.ProfileRegistration
import com.violinstudio.domain.feature.profile.model.Role
import com.violinstudio.domain.feature.profile.model.UserProfile
import com.violinstudio.domain.feature.profile.repository.ProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

val passwordUser = AuthUser("u1", "a@b.com", emailVerified = false, providers = setOf(AuthProvider.PASSWORD))
val verifiedUser = passwordUser.copy(emailVerified = true)
val config =
    IdentityConfig(
        policyVersion = 2,
        policyUrl = "https://x/privacy",
        digitalConsentAge = 14,
        guardianFlowEnabled = true
    )

fun userProfile(
    status: ConsentStatus = ConsentStatus.GRANTED,
    policyVersion: Int? = 2,
    isMinor: Boolean = false,
    deletion: Boolean = false
) = UserProfile(
    uid = "u1",
    displayName = "Ana",
    instrument = Instrument.VIOLIN,
    locale = "es",
    role = Role.INDEPENDENT,
    isMinor = isMinor,
    consentStatus = status,
    policyVersion = policyVersion,
    guardian = null,
    deletionInProgress = deletion
)

/** Fake de [AuthRepository]: registra las llamadas en [calls] y devuelve lo configurado en cada `*Result`. */
class FakeAuthRepository : AuthRepository {
    private val shared = MutableSharedFlow<AuthUser?>(replay = 1).also { it.tryEmit(null) }

    /** Cada asignacion emite, tambien si el valor es igual al anterior (como un `reload` de Firebase). */
    var user: AuthUser? = null
        set(value) {
            field = value
            shared.tryEmit(value)
        }
    val calls = mutableListOf<String>()
    var signInResult: Result<AuthUser> = Result.success(verifiedUser)
    var signUpResult: Result<AuthUser> = Result.success(passwordUser)
    var googleResult: Result<AuthUser> = Result.success(verifiedUser)
    var sendVerificationResult: Result<Unit> = Result.success(Unit)
    var reloadResult: Result<AuthUser> = Result.success(verifiedUser)
    var resetResult: Result<Unit> = Result.success(Unit)
    var reauthResult: Result<Unit> = Result.success(Unit)

    /** Cola de resultados de `claims`; el último se repite. */
    var claimsResults: MutableList<Result<SessionClaims>> =
        mutableListOf(Result.success(SessionClaims(Role.INDEPENDENT, true)))

    override val authUser: Flow<AuthUser?> get() = shared

    override suspend fun signInWithEmail(email: String, password: String): Result<AuthUser> {
        calls += "signInWithEmail:$email"
        return signInResult
    }

    override suspend fun signUpWithEmail(email: String, password: String): Result<AuthUser> {
        calls += "signUpWithEmail:$email"
        return signUpResult
    }

    override suspend fun signInWithGoogle(token: GoogleIdToken): Result<AuthUser> {
        calls += "signInWithGoogle"
        return googleResult
    }

    override suspend fun sendEmailVerification(): Result<Unit> {
        calls += "sendEmailVerification"
        return sendVerificationResult
    }

    override suspend fun reloadAndRefreshToken(): Result<AuthUser> {
        calls += "reloadAndRefreshToken"
        return reloadResult
    }

    override suspend fun sendPasswordReset(email: String): Result<Unit> {
        calls += "sendPasswordReset:$email"
        return resetResult
    }

    override suspend fun claims(forceRefresh: Boolean): Result<SessionClaims> {
        calls += "claims:$forceRefresh"
        return if (claimsResults.size > 1) claimsResults.removeAt(0) else claimsResults.first()
    }

    override suspend fun reauthenticateWithPassword(password: String): Result<Unit> {
        calls += "reauthPassword"
        return reauthResult
    }

    override suspend fun reauthenticateWithGoogle(token: GoogleIdToken): Result<Unit> {
        calls += "reauthGoogle"
        return reauthResult
    }

    override suspend fun signOut() {
        calls += "signOut"
    }
}

class FakeProfileRepository : ProfileRepository {
    val profile = MutableStateFlow<UserProfile?>(null)
    val calls = mutableListOf<String>()
    var registerResult: Result<Unit> = Result.success(Unit)
    var updateResult: Result<Unit> = Result.success(Unit)
    var lastRegistration: ProfileRegistration? = null
    var lastUpdate: EditableProfile? = null

    /** Los fallos se consumen uno por llamada a `observe`: el flujo devuelto lanza el fallo (listener roto). */
    val observeFailures = mutableListOf<Throwable>()

    override fun observe(uid: String): Flow<UserProfile?> {
        calls += "observe:$uid"
        if (observeFailures.isNotEmpty()) {
            val failure = observeFailures.removeAt(0)
            return flow { throw failure }
        }
        return profile
    }

    override suspend fun register(registration: ProfileRegistration): Result<Unit> {
        calls += "register"
        lastRegistration = registration
        return registerResult
    }

    override suspend fun update(uid: String, profile: EditableProfile): Result<Unit> {
        calls += "update:$uid"
        lastUpdate = profile
        return updateResult
    }
}

class FakeConsentRepository : ConsentRepository {
    val calls = mutableListOf<String>()
    var configResult: Result<IdentityConfig> = Result.success(config)
    /** Resultados de `identityConfig` consumidos uno por llamada antes de caer en [configResult]. */
    val configQueue = mutableListOf<Result<IdentityConfig>>()
    var recordResult: Result<Unit> = Result.success(Unit)
    var revokeResult: Result<Unit> = Result.success(Unit)
    var guardianResult: Result<GuardianRequestReceipt> = Result.success(GuardianRequestReceipt("t***@x.com"))

    override suspend fun identityConfig(): Result<IdentityConfig> {
        calls += "identityConfig"
        return if (configQueue.isNotEmpty()) configQueue.removeAt(0) else configResult
    }

    override suspend fun recordConsent(policyVersion: Int): Result<Unit> {
        calls += "recordConsent:$policyVersion"
        return recordResult
    }

    override suspend fun revokeConsent(): Result<Unit> {
        calls += "revokeConsent"
        return revokeResult
    }

    override suspend fun requestGuardianConsent(guardianEmail: String): Result<GuardianRequestReceipt> {
        calls += "requestGuardianConsent:$guardianEmail"
        return guardianResult
    }
}

class FakeAccountRepository : AccountRepository {
    var result: Result<Unit> = Result.success(Unit)
    var calls = 0

    override suspend fun deleteAccount(): Result<Unit> {
        calls++
        return result
    }
}
