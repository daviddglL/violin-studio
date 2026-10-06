package com.violinstudio

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import com.violinstudio.ui.feature.account.view.DELETE_ACCOUNT_BUTTON_TAG
import com.violinstudio.ui.feature.account.view.DELETE_ACCOUNT_CONFIRM_TAG
import com.violinstudio.ui.feature.auth.view.AUTH_EMAIL_TAG
import com.violinstudio.ui.feature.auth.view.AUTH_MESSAGE_TAG
import com.violinstudio.ui.feature.auth.view.AUTH_PASSWORD_TAG
import com.violinstudio.ui.feature.auth.view.AUTH_SUBMIT_TAG
import com.violinstudio.ui.feature.auth.view.LOGIN_TAG
import com.violinstudio.ui.feature.auth.view.REGISTER_TAG
import com.violinstudio.ui.feature.auth.view.VERIFY_EMAIL_TAG
import com.violinstudio.ui.feature.consent.view.CONSENT_CHECKBOX_TAG
import com.violinstudio.ui.feature.consent.view.CONSENT_TAG
import com.violinstudio.ui.feature.onboarding.view.ONBOARDING_DAY_TAG
import com.violinstudio.ui.feature.onboarding.view.ONBOARDING_MONTH_TAG
import com.violinstudio.ui.feature.onboarding.view.ONBOARDING_NAME_TAG
import com.violinstudio.ui.feature.onboarding.view.ONBOARDING_TAG
import com.violinstudio.ui.feature.onboarding.view.ONBOARDING_YEAR_TAG
import com.violinstudio.ui.feature.onboarding.view.onboardingInstrumentTag
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.UUID
import org.json.JSONObject

const val E2E_TIMEOUT_MS = 45_000L
const val FIRST_SCREEN_TIMEOUT_MS = 90_000L
const val REGISTER_RETRIES = 2
const val E2E_PASSWORD = "Violin-E2e-2026!x"

/**
 * Cliente REST de los emuladores (10.0.2.2 desde el emulador Android) con el token de administrador `owner`.
 * Se usa para lo que el usuario haria fuera de la app (verificar el email, abrir el enlace del tutor) y para comprobar
 * el backend tras un borrado.
 */
object Emulators {
    const val PROJECT = "violin-app-dev-f0b55"
    private val host = BuildConfig.EMULATOR_HOST
    private const val REGION = "europe-west1"
    private const val DOCS = "databases/(default)/documents"

    class Response(val code: Int, val body: String)

    fun request(method: String, url: String, body: String? = null, form: Boolean = false): Response {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Authorization", "Bearer owner")
            if (body != null) {
                conn.doOutput = true
                val type = if (form) "application/x-www-form-urlencoded" else "application/json"
                conn.setRequestProperty("Content-Type", type)
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            return Response(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            conn.disconnect()
        }
    }

    private fun authBase() = "http://$host:9099/identitytoolkit.googleapis.com/v1/projects/$PROJECT"
    private fun docsUrl(path: String) = "http://$host:8080/v1/projects/$PROJECT/$DOCS/$path"

    /** Cuenta de Auth por email (`localId`, `emailVerified`) o `null` si no existe. */
    fun authUser(email: String): JSONObject? {
        val body = request("POST", "${authBase()}/accounts:lookup", """{"email":["$email"]}""").body
        return JSONObject(body).optJSONArray("users")?.optJSONObject(0)
    }

    /** Equivale a abrir el enlace del correo de verificacion. */
    fun markEmailVerified(email: String) {
        val uid = checkNotNull(authUser(email)) { "no existe la cuenta $email" }.getString("localId")
        val response = request("POST", "${authBase()}/accounts:update", """{"localId":"$uid","emailVerified":true}""")
        check(response.code == 200) { "accounts:update fallo: ${response.code}" }
    }

    /** Valor de un campo string de un documento (p. ej. `consentStatus`), o `null` si no existe. */
    fun stringField(path: String, field: String): String? {
        val r = request("GET", docsUrl(path))
        if (r.code != 200) return null
        return JSONObject(r.body).optJSONObject("fields")?.optJSONObject(field)?.optString("stringValue")
    }

    fun consentCount(uid: String): Int = docs("users/$uid/consents").size

    fun docExists(path: String): Boolean = request("GET", docsUrl(path)).code == 200

    /** Documentos de `users/{uid}/practiceSessions` (vacia -> lista vacia). */
    fun practiceSessions(uid: String): List<JSONObject> = docs("users/$uid/practiceSessions")

    /** Id (ultimo segmento de la ruta) de un documento devuelto por [docs]. */
    fun docId(doc: JSONObject): String = doc.getString("name").substringAfterLast('/')

    /** Ids de documentos de una coleccion (vacia -> lista vacia). */
    fun docs(collectionPath: String, pageSize: Int = 300): List<JSONObject> {
        val body = request("GET", docsUrl(collectionPath) + "?pageSize=$pageSize").body
        val arr = JSONObject(body).optJSONArray("documents")
        return (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it) }
    }

    /** Documentos de `mail`/`guardianRequests` cuyo campo `uid` es [uid]. */
    fun docsOwnedBy(collection: String, uid: String): List<JSONObject> =
        docs(collection).filter { it.optJSONObject("fields")?.optJSONObject("uid")?.optString("stringValue") == uid }

    /** `(requestId, token)` del enlace que el backend metio en el correo al tutor (`mail/`, campo `message.text`). */
    fun guardianLink(uid: String): Pair<String, String> {
        val text = docsOwnedBy("mail", uid)
            .map { it.getJSONObject("fields") }
            .first { it.getJSONObject("kind").getString("stringValue") == "guardian_consent" }
            .getJSONObject("message").getJSONObject("mapValue").getJSONObject("fields")
            .getJSONObject("text").getString("stringValue")
        val pattern = Regex("""[?&]r=([A-Za-z0-9]{20})#t=([A-Za-z0-9_-]+)""")
        val match = checkNotNull(pattern.find(text)) { "enlace no encontrado" }
        return match.groupValues[1] to match.groupValues[2]
    }

    /** POST de la pagina del tutor (lo que hace su navegador al pulsar "Aceptar" con la declaracion marcada). */
    fun confirmGuardian(requestId: String, token: String): Int {
        val url = "http://$host:5001/$PROJECT/$REGION/guardianConsent"
        return request("POST", url, "r=$requestId&t=$token&action=accept&declaration=on", form = true).code
    }
}

/** Espera sondeando el backend (fuera de Compose): verdadero antes del plazo o falla con [what]. */
fun awaitBackend(what: String, timeoutMs: Long = E2E_TIMEOUT_MS, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    var last: Throwable? = null
    while (System.currentTimeMillis() < deadline) {
        try {
            if (condition()) return
            last = null
        } catch (e: Exception) {
            last = e
        }
        Thread.sleep(500)
    }
    throw AssertionError("Plazo agotado esperando: $what" + (last?.let { " (ultimo error: $it)" } ?: ""), last)
}

fun uniqueEmail(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(12)}@example.test"

/** Pasos de interfaz compartidos por los recorridos E2E. Los textos son los de `strings.xml` (solo hay locale base). */
@OptIn(ExperimentalTestApi::class)
class Journey(private val compose: ComposeTestRule) {
    /** Si vence el plazo, el error lleva el arbol semantico visible para saber en que pantalla se quedo. */
    private fun waitOrDump(what: String, timeoutMs: Long, matcher: SemanticsMatcher) {
        try {
            compose.waitUntilExactlyOneExists(matcher, timeoutMs)
        } catch (e: ComposeTimeoutException) {
            val tree = runCatching { compose.onRoot().printToString(maxDepth = 6) }.getOrDefault("(sin arbol)")
            throw AssertionError("Esperando $what. Pantalla actual: $tree logcat ViolinAuth: ${authLog()}", e)
        }
    }

    private fun authLog(): String = runCatching {
        Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-s", "ViolinAuth:W")).inputStream.bufferedReader().readText()
    }.getOrDefault("(sin logcat)")

    fun waitForTag(tag: String, timeoutMs: Long = E2E_TIMEOUT_MS) = waitOrDump("tag $tag", timeoutMs, hasTestTag(tag))

    fun waitForText(text: String, timeoutMs: Long = E2E_TIMEOUT_MS) =
        waitOrDump("texto '$text'", timeoutMs, hasText(text))

    // Home no es desplazable: `performScrollTo` falla sin un ancestro con scroll, asi que solo se pide si lo hay.
    private fun SemanticsNodeInteraction.scrolledIfNeeded(): SemanticsNodeInteraction {
        runCatching { performScrollTo() }
        return this
    }

    fun click(tag: String) {
        compose.onNodeWithTag(tag).scrolledIfNeeded().performClick()
    }

    fun clickText(text: String) {
        compose.onNodeWithText(text).scrolledIfNeeded().performClick()
    }

    fun type(tag: String, text: String) {
        compose.onNodeWithTag(tag).scrolledIfNeeded().performTextInput(text)
    }

    fun register(email: String) {
        waitForTag(LOGIN_TAG, FIRST_SCREEN_TIMEOUT_MS)
        clickText("Crear cuenta")
        waitForTag(REGISTER_TAG)
        type(AUTH_EMAIL_TAG, email)
        type(AUTH_PASSWORD_TAG, E2E_PASSWORD)
        submitRegistration()
    }

    /** Como un usuario: si el alta falla de forma transitoria (error generico o de red) se vuelve a pulsar, 2 veces como mucho. */
    private fun submitRegistration() {
        repeat(REGISTER_RETRIES + 1) { attempt ->
            click(AUTH_SUBMIT_TAG)
            compose.waitUntil(E2E_TIMEOUT_MS) {
                compose.onAllNodes(hasTestTag(VERIFY_EMAIL_TAG)).fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodes(hasTestTag(AUTH_MESSAGE_TAG)).fetchSemanticsNodes().isNotEmpty()
            }
            if (compose.onAllNodes(hasTestTag(VERIFY_EMAIL_TAG)).fetchSemanticsNodes().isNotEmpty()) return
            println("E2E: el alta fallo (intento ${attempt + 1}); se reintenta. logcat: ${authLog()}")
            Thread.sleep(2_000)
        }
        waitForTag(VERIFY_EMAIL_TAG)
    }

    /** Verifica en el emulador (Admin REST) y pulsa "Ya lo he verificado": la sesion pasa a onboarding. */
    fun verifyEmail(email: String) {
        Emulators.markEmailVerified(email)
        clickText("Ya lo he verificado")
        waitForTag(ONBOARDING_TAG)
    }

    fun onboard(year: Int, name: String = "E2E Violinista") {
        type(ONBOARDING_NAME_TAG, name)
        click(onboardingInstrumentTag("violin"))
        type(ONBOARDING_DAY_TAG, "01")
        type(ONBOARDING_MONTH_TAG, "01")
        type(ONBOARDING_YEAR_TAG, year.toString())
        click(AUTH_SUBMIT_TAG)
    }

    /** Acepta la politica en la pantalla de consentimiento (adulto con sesion hasta consentimiento). */
    fun acceptPolicy() {
        waitForTag(CONSENT_TAG)
        click(CONSENT_CHECKBOX_TAG)
        click(AUTH_SUBMIT_TAG)
    }

    /** Alta, verificacion, onboarding de un adulto y consentimiento: termina en Home (usuario `Ready`). */
    fun registerUpToHome(email: String, adultBirthYear: Int = LocalDate.now().year - 30) {
        register(email)
        verifyEmail(email)
        onboard(adultBirthYear)
        acceptPolicy()
        waitForTag("home_settings")
    }

    /** Abre el flujo compartido de borrado de la pantalla actual y lo confirma (la cuenta es recien creada: sin reautenticar). */
    fun deleteAccount() {
        click(DELETE_ACCOUNT_BUTTON_TAG)
        waitForTag(DELETE_ACCOUNT_CONFIRM_TAG)
        click(DELETE_ACCOUNT_CONFIRM_TAG)
        waitForTag(LOGIN_TAG)
    }
}
