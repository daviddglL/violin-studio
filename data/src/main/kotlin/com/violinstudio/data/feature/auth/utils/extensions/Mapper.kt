package com.violinstudio.data.feature.auth.utils.extensions

import com.violinstudio.data.feature.auth.dto.AuthUserDto
import com.violinstudio.data.feature.auth.dto.ClaimsDto
import com.violinstudio.domain.feature.auth.model.AuthUser
import com.violinstudio.domain.feature.auth.model.SessionClaims

fun AuthUserDto.toDomain(): AuthUser = AuthUser(uid = uid, email = email, emailVerified = TODO(), providers = TODO())

fun ClaimsDto.toDomain(): SessionClaims = TODO()
