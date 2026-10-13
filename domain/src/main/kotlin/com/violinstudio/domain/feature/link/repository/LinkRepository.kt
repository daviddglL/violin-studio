package com.violinstudio.domain.feature.link.repository

/** Los fallos llegan como `Result.failure(LinkFailure)`. */
interface LinkRepository {
    /** Canjea [code] (ya normalizado) aceptando el aviso [ackVersion]; devuelve el uid del profesor. */
    suspend fun redeem(code: String, ackVersion: Int): Result<String>
}
