package com.violinstudio.data.commons.erasure

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

/** `commit()` (no `apply()`): la marca debe estar en disco aunque el proceso muera justo despues del borrado. */
@Singleton
class SharedPrefsCachePurgeFlag @Inject constructor(
    @ApplicationContext context: Context
) : CachePurgeFlag {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun request() {
        check(prefs.edit().putBoolean(KEY, true).commit()) { "no se pudo guardar la marca de purga" }
    }

    override fun isRequested(): Boolean = prefs.getBoolean(KEY, false)

    override fun clear() {
        if (!prefs.edit().remove(KEY).commit()) {
            Logger.getLogger("CachePurgeFlag").warning("no se pudo bajar la marca de purga")
        }
    }

    private companion object {
        const val FILE = "local_erasure"
        const val KEY = "purge_firestore_cache"
    }
}
