package com.violinstudio.ui.commons.auth

import android.content.Context

class FakeGoogleIdTokenRequester(private val result: GoogleIdTokenResult) : GoogleIdTokenRequester {
    var calls = 0
        private set

    override suspend fun request(context: Context): GoogleIdTokenResult {
        calls++
        return result
    }
}
