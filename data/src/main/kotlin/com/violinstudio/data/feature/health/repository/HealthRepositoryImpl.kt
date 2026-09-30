package com.violinstudio.data.feature.health.repository

import com.violinstudio.data.feature.health.datasource.HealthRemoteDataSource
import com.violinstudio.data.feature.health.utils.extensions.toDomain
import com.violinstudio.domain.feature.health.failure.ServerUnavailableException
import com.violinstudio.domain.feature.health.model.HealthInfo
import com.violinstudio.domain.feature.health.repository.HealthRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

class HealthRepositoryImpl @Inject constructor(private val remote: HealthRemoteDataSource) : HealthRepository {
    override suspend fun check(): Result<HealthInfo> {
        val info = try {
            remote.fetchHealth().toDomain()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        }
        return if (info.isOk) Result.success(info) else Result.failure(ServerUnavailableException(info.status))
    }
}
