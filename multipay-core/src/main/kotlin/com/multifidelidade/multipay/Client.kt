package com.multifidelidade.multipay

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

@Serializable
data class FieldError(val field: String, val message: String)

/** Erro devolvido pela API (`{ error: { code, message, details, traceId } }`) ou falha de rede (status 0). */
class MultiPayException(
    val status: Int,
    val code: String,
    override val message: String,
    val details: List<FieldError> = emptyList(),
    val traceId: String? = null,
) : Exception(message)

@Serializable
data class Customer(val name: String? = null, val email: String? = null, val phone: String? = null)

@Serializable
data class CreatePaymentRequest(
    val referenceId: String,
    /** Centavos. R$ 150,00 = 15000. */
    val amountCents: Long,
    val cpf: String,
    val callbackUrl: String,
    val returnUrl: String,
    val message: String? = null,
    val customer: Customer? = null,
    val expiresAt: String? = null,
    val expiresInMinutes: Int? = null,
)

@Serializable
data class Payment(
    val referenceId: String,
    val paymentUrl: String,
    /** pending | paid | expired | canceled */
    val status: String,
    val amountCents: Long,
    val currency: String = "BRL",
    val livemode: Boolean = false,
    val createdAt: String? = null,
    val expiresAt: String? = null,
    val paidAt: String? = null,
    val canceledAt: String? = null,
    val transactionId: String? = null,
) {
    val checkoutStatus: CheckoutStatus get() = CheckoutStatus.from(status)
}

data class CreatedPayment(val payment: Payment, val replayed: Boolean)

@Serializable
data class Me(val merchantId: Int, val merchantName: String, val livemode: Boolean)

@Serializable
private data class ErrorEnvelope(val error: ErrorBody? = null)

@Serializable
private data class ErrorBody(
    val code: String? = null,
    val message: String? = null,
    val details: List<FieldError>? = null,
    val traceId: String? = null,
)

@Serializable
private data class PaymentList(val data: List<Payment> = emptyList(), val nextCursor: String? = null)

data class PaymentPage(val data: List<Payment>, val nextCursor: String?)

/**
 * Cliente HTTP da MultiPay API v2 (bloqueante: chame de uma thread de background / Dispatchers.IO).
 *
 * SEGURANCA: chaves de producao (`mp_live_`) sao recusadas por padrao. Elas so podem existir em backend.
 * Em app use apenas `mp_test_` (demo/sandbox); em producao o app fala com o SEU backend, que cria o pagamento.
 * Backends Kotlin/JVM criam o cliente com [forBackend].
 */
class MultiPayClient private constructor(
    private val apiKey: String,
    private val baseUrl: String,
    private val timeoutMs: Int,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.multifidelidade.app/integracoes"
        private val KEY_REGEX = Regex("^mp_(live|test)_[A-Za-z0-9_-]{16,}$")
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        /** Para APPS: aceita somente chave de teste (`mp_test_`). */
        fun forApp(testApiKey: String, baseUrl: String = DEFAULT_BASE_URL, timeoutMs: Int = 15_000): MultiPayClient {
            require(testApiKey.startsWith("mp_test_")) {
                "Chave de producao nao pode ficar em app: qualquer pessoa a extrai do binario. " +
                    "Use mp_test_ no app e crie os pagamentos reais no seu backend."
            }
            return create(testApiKey, baseUrl, timeoutMs)
        }

        /** Para BACKENDS Kotlin/JVM (aceita mp_live_ e mp_test_). Nunca use dentro de um app. */
        fun forBackend(apiKey: String, baseUrl: String = DEFAULT_BASE_URL, timeoutMs: Int = 15_000): MultiPayClient =
            create(apiKey, baseUrl, timeoutMs)

        private fun create(apiKey: String, baseUrl: String, timeoutMs: Int): MultiPayClient {
            require(KEY_REGEX.matches(apiKey)) { "API key invalida: deve comecar com mp_live_ ou mp_test_." }
            val uri = URI(baseUrl)
            val local = uri.host in setOf("localhost", "127.0.0.1", "[::1]")
            require(uri.scheme == "https" || local) { "baseUrl precisa ser https (a chave trafega no header)." }
            return MultiPayClient(apiKey, baseUrl.trimEnd('/'), timeoutMs)
        }
    }

    fun whoami(): Me = json.decodeFromString(Me.serializer(), call("GET", "/v2/me", null).first)

    /** Idempotente por `referenceId`: repetir a mesma requisicao devolve o mesmo link ([CreatedPayment.replayed] = true). */
    fun createPayment(request: CreatePaymentRequest): CreatedPayment {
        val (body, headers) = call("POST", "/v2/payments", json.encodeToString(CreatePaymentRequest.serializer(), request))
        val replayed = headers["idempotent-replayed"]?.firstOrNull() == "true"
        return CreatedPayment(json.decodeFromString(Payment.serializer(), body), replayed)
    }

    fun getPayment(referenceId: String): Payment =
        json.decodeFromString(Payment.serializer(), call("GET", "/v2/payments/${enc(referenceId)}", null).first)

    fun listPayments(limit: Int = 20, cursor: String? = null): PaymentPage {
        val q = "limit=$limit" + (cursor?.let { "&cursor=${enc(it)}" } ?: "")
        val page = json.decodeFromString(PaymentList.serializer(), call("GET", "/v2/payments?$q", null).first)
        return PaymentPage(page.data, page.nextCursor)
    }

    fun cancelPayment(referenceId: String): Payment =
        json.decodeFromString(Payment.serializer(), call("POST", "/v2/payments/${enc(referenceId)}/cancel", null).first)

    /** Somente chave de teste: marca o pagamento de teste como "paid" ou "expired". */
    fun simulatePayment(referenceId: String, outcome: String): Payment {
        require(apiKey.startsWith("mp_test_")) { "simulate so existe no sandbox (mp_test_)." }
        require(outcome == "paid" || outcome == "expired") { "outcome deve ser 'paid' ou 'expired'." }
        val body = call("POST", "/v2/sandbox/payments/${enc(referenceId)}/simulate", """{"outcome":"$outcome"}""").first
        return json.decodeFromString(Payment.serializer(), body)
    }

    // ---------------------------------------------------------------------------------------

    private fun call(method: String, path: String, body: String?): Pair<String, Map<String, List<String>>> {
        val conn = URI(baseUrl + path).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = false          // nunca seguir redirect com a chave no header
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "multipay-sdk-kotlin/0.1")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val headers = conn.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }

            if (status in 200..299) return text to headers
            throw toException(status, text)
        } catch (e: MultiPayException) {
            throw e
        } catch (e: java.io.IOException) {
            throw MultiPayException(0, "network_error", "Falha de rede ao chamar a MultiPay API: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    private fun toException(status: Int, text: String): MultiPayException {
        val err = runCatching { json.decodeFromString(ErrorEnvelope.serializer(), text).error }.getOrNull()
        return MultiPayException(
            status = status,
            code = err?.code ?: "http_$status",
            message = err?.message ?: "Resposta inesperada da API (HTTP $status).",
            details = err?.details.orEmpty(),
            traceId = err?.traceId,
        )
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
}
