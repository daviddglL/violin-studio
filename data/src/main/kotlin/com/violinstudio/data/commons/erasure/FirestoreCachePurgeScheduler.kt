package com.violinstudio.data.commons.erasure

import javax.inject.Inject

/** `terminate()` inutilizaria el singleton de Firestore: la purga real ocurre en el siguiente arranque. */
class FirestoreCachePurgeScheduler @Inject constructor(
    private val flag: CachePurgeFlag
) : LocalUserDataEraser {
    override suspend fun erase(uid: String) = flag.request()
}
