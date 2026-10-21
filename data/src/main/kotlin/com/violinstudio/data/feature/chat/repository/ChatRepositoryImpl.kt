package com.violinstudio.data.feature.chat.repository

import com.violinstudio.data.feature.chat.datasource.ChatRemoteDataSource
import com.violinstudio.data.feature.chat.utils.ChatErrorMapper
import com.violinstudio.data.feature.chat.utils.ChatMessageParser
import com.violinstudio.domain.feature.chat.model.ChatMessage
import com.violinstudio.domain.feature.chat.repository.ChatRepository
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class ChatRepositoryImpl @Inject constructor(private val remote: ChatRemoteDataSource) : ChatRepository {
    // Decodifica por elemento: un doc ilegible se omite sin tirar la lista.
    override fun observe(teacherUid: String, studentUid: String): Flow<List<ChatMessage>> =
        remote.observe(teacherUid, studentUid)
            .map { docs -> docs.mapNotNull(ChatMessageParser::parse) }
            .distinctUntilChanged()
            .catch { throw ChatErrorMapper.map(it) }

    override suspend fun send(
        teacherUid: String,
        studentUid: String,
        senderUid: String,
        id: String,
        text: String,
        expireAt: Instant
    ): Result<Unit> = write {
        remote.create(id, ChatMessageParser.createFields(teacherUid, studentUid, senderUid, text, expireAt))
    }

    override suspend fun delete(id: String): Result<Unit> = write { remote.delete(id) }

    private inline fun write(block: () -> Unit): Result<Unit> = try {
        block()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(ChatErrorMapper.map(e))
    }
}
