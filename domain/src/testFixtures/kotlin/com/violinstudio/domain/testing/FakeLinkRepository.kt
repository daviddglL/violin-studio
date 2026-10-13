package com.violinstudio.domain.testing

import com.violinstudio.domain.feature.link.repository.LinkRepository

/** `calls` registra cada interaccion; los resultados son configurables. */
class FakeLinkRepository : LinkRepository {
    val calls = mutableListOf<String>()
    var redeemResult: Result<String> = Result.success("teacher")

    override suspend fun redeem(code: String, ackVersion: Int): Result<String> {
        calls += "redeem:$code:$ackVersion"
        return redeemResult
    }
}
