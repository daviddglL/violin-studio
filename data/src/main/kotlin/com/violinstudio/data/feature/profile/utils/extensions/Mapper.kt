package com.violinstudio.data.feature.profile.utils.extensions

import com.violinstudio.data.feature.profile.dto.UserProfileDto
import com.violinstudio.domain.feature.profile.model.ConsentStatus
import com.violinstudio.domain.feature.profile.model.GuardianSummary
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.profile.model.Role
import com.violinstudio.domain.feature.profile.model.UserProfile

private const val DEFAULT_LOCALE = "es"

/** Fail-closed: estado de consentimiento desconocido -> `PENDING`; `isMinor` ausente -> `true`. */
fun UserProfileDto.toDomain(uid: String): UserProfile = UserProfile(
    uid = uid,
    displayName = displayName.orEmpty(),
    instrument = Instrument.fromWire(instrument) ?: Instrument.OTHER,
    locale = locale ?: DEFAULT_LOCALE,
    role = Role.fromWire(role) ?: Role.INDEPENDENT,
    isMinor = UserProfile.minorOrTrue(isMinor),
    consentStatus = ConsentStatus.fromWireOrPending(consentStatus),
    policyVersion = policyVersion,
    guardian = guardianEmailMasked?.let { GuardianSummary(it, guardianSends) },
    deletionInProgress = deletionInProgress
)
