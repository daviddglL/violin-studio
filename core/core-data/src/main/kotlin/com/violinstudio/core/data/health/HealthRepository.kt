package com.violinstudio.core.data.health

import com.violinstudio.core.model.HealthInfo
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

class ServerUnavailableException(val status: String) :
    IllegalStateException("El servidor respondió con estado '$status'")

class HealthRepository @Inject constructor(private val remote: HealthRemoteSource) {
    suspend fun check(): Result<HealthInfo> {
        val info = try {
            remote.fetchHealth()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        }
        return if (info.isOk) Result.success(info) else Result.failure(ServerUnavailableException(info.status))
    }
}
