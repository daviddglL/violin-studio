package com.violinstudio.data.commons.erasure

/** Marca persistente "purgar la cache offline de Firestore en el proximo arranque". */
interface CachePurgeFlag {
    fun request()

    fun isRequested(): Boolean

    fun clear()
}
