package com.violinstudio.data.feature.link.datasource

/**
 * Frontera con los callables de vinculo (region `europe-west1`). Devuelve el `data` crudo y lanza
 * `FunctionsCallException` (ver `FunctionsErrorMapper.toLinkFailure`) o la excepcion de transporte.
 */
interface TeacherFunctionsDataSource {
    suspend fun createCode(): Any?

    suspend fun listCodes(): Any?

    suspend fun revokeCode(id: String): Any?

    suspend fun redeem(code: String, ackVersion: Int): Any?

    suspend fun unlink(teacherUid: String, studentUid: String): Any?
}
