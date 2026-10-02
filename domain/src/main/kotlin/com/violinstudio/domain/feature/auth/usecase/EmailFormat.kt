package com.violinstudio.domain.feature.auth.usecase

private val EMAIL = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""")

/** Comprobación mínima de formato (feedback local); la validez real la decide el servidor. */
internal fun isPlausibleEmail(value: String): Boolean = EMAIL.matches(value)
