package com.violinstudio.data.feature.recording.datasource.media

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/**
 * `noBackupFilesDir/recordings/{uid}`: fuera de las copias de seguridad y de la transferencia entre dispositivos
 * (REQ-REC-03). Nunca `filesDir`, `cacheDir` ni almacenamiento externo.
 */
class NoBackupRecordingsDirectory @Inject constructor(
    @param:ApplicationContext private val context: Context
) : RecordingsDirectory {
    override fun forUid(uid: String): File = File(File(context.noBackupFilesDir, ROOT), uid)

    private companion object {
        const val ROOT = "recordings"
    }
}
