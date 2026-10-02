package com.violinstudio.data.feature.profile.utils

import com.violinstudio.data.feature.profile.dto.UserProfileDto

/** Lee `users/{uid}` de forma tolerante (como `HealthResponseParser`, pero sin lanzar): lo ilegible queda en nulo. */
object UserProfileParser {
    fun parse(data: Map<*, *>): UserProfileDto {
        val guardian = data["guardian"] as? Map<*, *>
        return UserProfileDto(
            displayName = data["displayName"] as? String,
            instrument = data["instrument"] as? String,
            locale = data["locale"] as? String,
            role = data["role"] as? String,
            isMinor = data["isMinor"] as? Boolean,
            consentStatus = data["consentStatus"] as? String,
            policyVersion = (data["policyVersion"] as? Number)?.wholeInt(),
            guardianEmailMasked = guardian?.get("emailMasked") as? String,
            guardianSends = guardian?.sends() ?: 0,
            deletionInProgress = data["deletion"] != null
        )
    }

    /** El servidor guarda `guardian.sends` como lista de marcas de tiempo; se tolera también un contador. */
    private fun Map<*, *>.sends(): Int = when (val raw = this["sends"]) {
        is List<*> -> raw.size
        is Number -> raw.wholeInt() ?: 0
        else -> 0
    }

    private fun Number.wholeInt(): Int? = toDouble().takeIf { it % 1.0 == 0.0 && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() }?.toInt()
}
