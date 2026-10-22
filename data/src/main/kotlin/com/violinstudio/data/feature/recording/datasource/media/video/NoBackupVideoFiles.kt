package com.violinstudio.data.feature.recording.datasource.media.video

import com.violinstudio.data.feature.recording.datasource.media.RecordingNames
import com.violinstudio.data.feature.recording.datasource.media.RecordingsDirectory
import com.violinstudio.data.feature.recording.di.RecordingDispatcher
import com.violinstudio.domain.feature.recording.repository.VideoFiles
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Ficheros de video (4c) junto al audio, en `noBackupFilesDir/recordings/{uid}`: original sin difuminar
 * `{id}.raw.mp4` y difuminado `{id}.mp4`. Solo borra nombres de toma validos dentro del directorio del uid.
 */
class NoBackupVideoFiles @Inject constructor(
    private val directories: RecordingsDirectory,
    @param:RecordingDispatcher private val io: CoroutineDispatcher
) : VideoFiles {
    override fun rawPath(uid: String, id: String): String = File(directories.forUid(uid), "$id$RAW_SUFFIX").path

    override fun outputPath(uid: String, id: String): String = File(directories.forUid(uid), "$id$MP4").path

    override suspend fun delete(uid: String, path: String) = withContext(io) {
        val file = File(path)
        if (!RecordingNames.UID.matches(uid) || file.parentFile != directories.forUid(uid) || !isVideoName(file.name)) {
            return@withContext
        }
        file.delete()
        File("${file.path}.part").delete()
        Unit
    }

    override suspend fun sweepRaw(uid: String) = withContext(io) {
        if (!RecordingNames.UID.matches(uid)) return@withContext
        val dir = directories.forUid(uid).apply { mkdirs() }
        dir.listFiles { f -> f.isFile && f.name.endsWith(RAW_SUFFIX) }.orEmpty().forEach { it.delete() }
    }

    private fun isVideoName(name: String): Boolean {
        val id = when {
            name.endsWith(RAW_SUFFIX) -> name.removeSuffix(RAW_SUFFIX)
            name.endsWith(MP4) -> name.removeSuffix(MP4)
            else -> return false
        }
        return RecordingNames.ID.matches(id)
    }

    private companion object {
        const val RAW_SUFFIX = ".raw.mp4"
        const val MP4 = ".mp4"
    }
}
