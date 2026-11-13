package com.multifidelidade.multipay

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TEST_KEY = "mp_test_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
private const val LIVE_KEY = "mp_live_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

class CheckoutReturnTest {
    @Test fun `interpreta referenceId e status`() {
        val r = assertNotNull(CheckoutReturn.parse("meuapp://pagamento/retorno?referenceId=PED%201&status=paid"))
        assertEquals("PED 1", r.referenceId)
        assertEquals(CheckoutStatus.PAID, r.status)
    }

    @Test fun `status desconhecido ou ausente vira UNKNOWN`() {
        assertEquals(CheckoutStatus.UNKNOWN, CheckoutReturn.parse("meuapp://x?status=banana")!!.status)
        assertEquals(CheckoutStatus.UNKNOWN, CheckoutReturn.parse("meuapp://x")!!.status)
    }

    @Test fun `entrada invalida nao lanca`() {
        assertNull(CheckoutReturn.parse(null))
        assertNull(CheckoutReturn.parse(""))
        assertNotNull(CheckoutReturn.parse("::::não é url::::"))
    }

    @Test fun `todos os status`() {
        mapOf("pending" to CheckoutStatus.PENDING, "expired" to CheckoutStatus.EXPIRED, "canceled" to CheckoutStatus.CANCELED, "PAID" to CheckoutStatus.PAID)
            .forEach { (s, e) -> assertEquals(e, CheckoutReturn.parse("a://b?status=$s")!!.status) }
    }
}

class CheckoutUrlTest {
    @Test fun `aceita https do dominio e subdominios`() {
        assertTrue(CheckoutUrl.isSafe("https://multifidelidade.app/site/pay/checkout?idP=abc"))
        assertTrue(CheckoutUrl.isSafe("https://api.multifidelidade.app/v2/sandbox/checkout/00000000-0000-0000-0000-000000000000"))
    }

    @Test fun `recusa o resto`() {
        assertFalse(CheckoutUrl.isSafe("http://multifidelidade.app/x"))                 // sem TLS
        assertFalse(CheckoutUrl.isSafe("https://evil.com/?multifidelidade.app"))
        assertFalse(CheckoutUrl.isSafe("https://multifidelidade.app.evil.com/x"))       // sufixo falso
        assertFalse(CheckoutUrl.isSafe("https://user:pass@multifidelidade.app/x"))
        assertFalse(CheckoutUrl.isSafe("javascript:alert(1)"))
        assertFalse(CheckoutUrl.isSafe(null))
    }

    @Test fun `host extra autorizado`() {
        assertTrue(CheckoutUrl.isSafe("https://pay.minhaloja.com/x", setOf("minhaloja.com")))
    }
}

class WebhookVerifierTest {
    private val secret = "whsec_vetor_de_teste"
    private val body = """{"id":"evt_test_1_paid","type":"payment.paid"}"""

    @Test fun `vetor identico ao do servidor (dotnet) e do MCP (node)`() {
        assertEquals("6935acef1b3f9ab0883c8079b3cad5aefd921ac25a6142e463ce823b6ee75b19", WebhookVerifier.sign(secret, 1731323700, body))
    }

    @Test fun `valida e rejeita`() {
        val t = 1731323700L
        val h = "t=$t,v1=${WebhookVerifier.sign(secret, t, body)}"
        assertEquals(WebhookVerifier.Result.Valid, WebhookVerifier.verify(secret, h, body, t + 10))
        assertEquals(WebhookVerifier.Result.Invalid(WebhookVerifier.Failure.SIGNATURE_MISMATCH), WebhookVerifier.verify(secret, h, "$body ", t))
        assertEquals(WebhookVerifier.Result.Invalid(WebhookVerifier.Failure.SIGNATURE_MISMATCH), WebhookVerifier.verify("outro", h, body, t))
        assertEquals(WebhookVerifier.Result.Invalid(WebhookVerifier.Failure.TIMESTAMP_OUT_OF_TOLERANCE), WebhookVerifier.verify(secret, h, body, t + 3600))
        assertEquals(WebhookVerifier.Result.Invalid(WebhookVerifier.Failure.MISSING_HEADER), WebhookVerifier.verify(secret, null, body))
        assertEquals(WebhookVerifier.Result.Invalid(WebhookVerifier.Failure.MALFORMED_HEADER), WebhookVerifier.verify(secret, "lixo", body))
    }
}

class ClientTest {
    private lateinit var server: HttpServer
    private var base = ""
    private val seen = mutableMapOf<String, Long>()
    private val calls = mutableListOf<Triple<String, String, String?>>() // metodo, path, Authorization

    @BeforeTest fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex -> handle(ex) }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @AfterTest fun stop() = server.stop(0)

    private fun payment(ref: String, status: String = "pending", amount: Long = 1500) =
        """{"referenceId":"$ref","paymentUrl":"https://multifidelidade.app/c/$ref","status":"$status","amountCents":$amount,"currency":"BRL","livemode":false,"createdAt":"2026-11-11T10:00:00-03:00","expiresAt":"2026-11-13T23:59:59-03:00","paidAt":null,"canceledAt":null,"transactionId":null,"campoNovoQueOSdkNaoConhece":1}"""

    private fun reply(ex: HttpExchange, status: Int, json: String, headers: Map<String, String> = emptyMap()) {
        val bytes = json.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.rawPath
        val body = ex.requestBody.readBytes().decodeToString()
        calls += Triple(ex.requestMethod, ex.requestURI.toString(), ex.requestHeaders.getFirst("Authorization"))

        if (ex.requestMethod == "GET" && path == "/v2/me") return reply(ex, 200, """{"merchantId":100,"merchantName":"Loja","livemode":false}""")
        if (ex.requestMethod == "POST" && path == "/v2/payments") {
            val ref = Regex("\"referenceId\":\"([^\"]+)\"").find(body)!!.groupValues[1]
            if (body.contains("11111111111")) return reply(ex, 422, """{"error":{"code":"validation_error","message":"Um ou mais campos sao invalidos.","details":[{"field":"cpf","message":"CPF invalido."}],"traceId":"t-1"}}""")
            val amount = Regex("\"amountCents\":(\\d+)").find(body)!!.groupValues[1].toLong()
            val prev = seen[ref]
            return when {
                prev == null -> { seen[ref] = amount; reply(ex, 201, payment(ref, amount = amount)) }
                prev == amount -> reply(ex, 200, payment(ref, amount = amount), mapOf("Idempotent-Replayed" to "true"))
                else -> reply(ex, 409, """{"error":{"code":"reference_id_conflict","message":"dados diferentes","details":null,"traceId":null}}""")
            }
        }
        val m = Regex("^/v2/payments/([^/]+)(/cancel)?$").find(path)
        if (m != null && ex.requestMethod == "GET") {
            val ref = java.net.URLDecoder.decode(m.groupValues[1], Charsets.UTF_8)
            return if (ref in seen) reply(ex, 200, payment(ref)) else reply(ex, 404, """{"error":{"code":"not_found","message":"Pagamento nao encontrado.","details":null,"traceId":null}}""")
        }
        if (m != null && m.groupValues[2].isNotEmpty() && ex.requestMethod == "POST") return reply(ex, 200, payment(m.groupValues[1], "canceled"))
        if (path == "/v2/payments") return reply(ex, 200, """{"data":[${payment("A")}],"nextCursor":"7"}""")
        val sim = Regex("^/v2/sandbox/payments/([^/]+)/simulate$").find(path)
        if (sim != null) return reply(ex, 200, payment(sim.groupValues[1], "paid"))
        if (path == "/redir") { ex.responseHeaders.add("Location", "https://evil.example/"); ex.sendResponseHeaders(302, -1); ex.close(); return }
        reply(ex, 404, "{}")
    }

    private fun req(ref: String = "PED-1", amount: Long = 1500, cpf: String = "52998224725") = CreatePaymentRequest(
        referenceId = ref, amountCents = amount, cpf = cpf,
        callbackUrl = "https://loja.test/hook", returnUrl = "meuapp://pagamento/retorno",
    )

    @Test fun `envia Bearer, cria e repete com replayed`() {
        val c = MultiPayClient.forApp(TEST_KEY, base)
        val first = c.createPayment(req())
        assertFalse(first.replayed)
        assertEquals("pending", first.payment.status)
        assertEquals(CheckoutStatus.PENDING, first.payment.checkoutStatus)
        assertEquals("Bearer $TEST_KEY", calls.first().third)
        assertTrue(c.createPayment(req()).replayed)
    }

    @Test fun `ignora campos novos da API`() {
        // o JSON de teste traz "campoNovoQueOSdkNaoConhece": nao pode quebrar clientes antigos
        assertEquals("X", MultiPayClient.forApp(TEST_KEY, base).createPayment(req("X")).payment.referenceId)
    }

    @Test fun `erro de validacao traz campos e traceId`() {
        val e = assertFailsWith<MultiPayException> { MultiPayClient.forApp(TEST_KEY, base).createPayment(req(cpf = "11111111111")) }
        assertEquals(422, e.status)
        assertEquals("validation_error", e.code)
        assertEquals("cpf", e.details.single().field)
        assertEquals("t-1", e.traceId)
    }

    @Test fun `409 e 404`() {
        val c = MultiPayClient.forApp(TEST_KEY, base)
        c.createPayment(req())
        assertEquals(409, assertFailsWith<MultiPayException> { c.createPayment(req(amount = 999)) }.status)
        assertEquals("not_found", assertFailsWith<MultiPayException> { c.getPayment("NAO-EXISTE") }.code)
    }

    @Test fun `cancela, lista, simula e whoami`() {
        val c = MultiPayClient.forApp(TEST_KEY, base)
        c.createPayment(req())
        assertEquals("canceled", c.cancelPayment("PED-1").status)
        assertEquals("7", c.listPayments(limit = 1).nextCursor)
        assertEquals("paid", c.simulatePayment("PED-1", "paid").status)
        assertFalse(c.whoami().livemode)
    }

    @Test fun `referenceId e codificado na URL`() {
        MultiPayClient.forApp(TEST_KEY, base).runCatching { getPayment("a/b c") }
        assertEquals("/v2/payments/a%2Fb%20c", calls.last().second)
    }

    @Test fun `app recusa chave de producao`() {
        val e = assertFailsWith<IllegalArgumentException> { MultiPayClient.forApp(LIVE_KEY, base) }
        assertTrue(e.message!!.contains("producao", ignoreCase = true))
    }

    @Test fun `backend aceita producao mas simulate fica so no sandbox`() {
        val c = MultiPayClient.forBackend(LIVE_KEY, base)
        assertFailsWith<IllegalArgumentException> { c.simulatePayment("X", "paid") }
    }

    @Test fun `exige https fora de localhost e chave valida`() {
        assertFailsWith<IllegalArgumentException> { MultiPayClient.forApp(TEST_KEY, "http://api.exemplo.com") }
        assertFailsWith<IllegalArgumentException> { MultiPayClient.forBackend("abc", base) }
    }

    @Test fun `nao segue redirect com a chave no header`() {
        val c = MultiPayClient.forApp(TEST_KEY, base)
        val e = assertFailsWith<MultiPayException> { c.getPayment("../../redir") }
        assertTrue(e.status == 404 || e.status == 302 || e.status == 0)
        assertTrue(calls.none { it.second.contains("evil.example") })
    }

    @Test fun `falha de rede vira MultiPayException`() {
        val c = MultiPayClient.forApp(TEST_KEY, "http://127.0.0.1:1", timeoutMs = 500)
        assertEquals("network_error", assertFailsWith<MultiPayException> { c.whoami() }.code)
    }
}
