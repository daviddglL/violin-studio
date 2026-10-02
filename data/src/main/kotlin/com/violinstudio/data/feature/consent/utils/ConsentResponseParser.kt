package com.violinstudio.data.feature.consent.utils

import com.violinstudio.data.feature.health.utils.MalformedResponseException
import com.violinstudio.domain.feature.consent.model.GuardianRequestReceipt
import com.violinstudio.domain.feature.consent.model.IdentityConfig

/** Parser estricto (como `HealthResponseParser`): un campo ausente o de otro tipo es una respuesta malformada. */
object ConsentResponseParser {
    fun parseConfig(data: Any?): IdentityConfig {
        val map = data.asMap("identityConfig")
        return IdentityConfig(
            policyVersion = map.int("identityConfig", "policyVersion"),
            policyUrl = map.string("identityConfig", "policyUrl"),
            digitalConsentAge = map.int("identityConfig", "digitalConsentAge"),
            // Ausente = sin flujo de tutor (valor seguro); un tipo erróneo sí es respuesta malformada.
            guardianFlowEnabled = map.guardianFlow()
        )
    }

    fun parseReceipt(data: Any?): GuardianRequestReceipt =
        GuardianRequestReceipt(data.asMap("requestGuardianConsent").string("requestGuardianConsent", "emailMasked"))

    private fun Any?.asMap(op: String): Map<*, *> =
        this as? Map<*, *> ?: throw MalformedResponseException("$op: se esperaba un objeto y llegó $this")

    private fun Map<*, *>.string(op: String, key: String): String =
        this[key] as? String ?: throw MalformedResponseException("$op: falta '$key' o no es texto")

    // Functions entrega los números como Int/Long/Double según la ruta; se aceptan solo los enteros exactos.
    private fun Map<*, *>.int(op: String, key: String): Int {
        val number = this[key] as? Number
        val range = Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()
        val whole = number?.toDouble()?.takeIf { it % 1.0 == 0.0 && it in range }
        return whole?.toInt() ?: throw MalformedResponseException("$op: falta '$key' o no es un entero")
    }

    private fun Map<*, *>.guardianFlow(): Boolean {
        if ("guardianFlowEnabled" !in this) return false
        return this["guardianFlowEnabled"] as? Boolean
            ?: throw MalformedResponseException("identityConfig: 'guardianFlowEnabled' no es booleano")
    }
}
