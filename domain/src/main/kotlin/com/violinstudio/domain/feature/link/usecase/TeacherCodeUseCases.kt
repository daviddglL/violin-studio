package com.violinstudio.domain.feature.link.usecase

import com.violinstudio.domain.feature.link.model.TeacherCode
import com.violinstudio.domain.feature.link.repository.LinkRepository
import javax.inject.Inject

/** El código en claro solo está en este resultado: la UI no debe persistirlo. */
class CreateTeacherCodeUseCase @Inject constructor(private val repo: LinkRepository) {
    suspend operator fun invoke(): Result<TeacherCode> = repo.createCode()
}

class ListTeacherCodesUseCase @Inject constructor(private val repo: LinkRepository) {
    suspend operator fun invoke(): Result<List<TeacherCode>> = repo.listCodes()
}

class RevokeTeacherCodeUseCase @Inject constructor(private val repo: LinkRepository) {
    suspend operator fun invoke(id: String): Result<Unit> = repo.revokeCode(id)
}
