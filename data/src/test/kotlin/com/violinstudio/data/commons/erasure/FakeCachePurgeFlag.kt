package com.violinstudio.data.commons.erasure

/** Flag en memoria; `fail` simula un almacén que no puede escribir. */
class FakeCachePurgeFlag(var pending: Boolean = false) : CachePurgeFlag {
    var fail: Exception? = null

    override fun request() {
        fail?.let { throw it }
        pending = true
    }

    override fun isRequested(): Boolean = pending

    override fun clear() {
        pending = false
    }
}
