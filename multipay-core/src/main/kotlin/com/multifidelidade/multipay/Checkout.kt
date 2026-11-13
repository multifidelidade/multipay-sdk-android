package com.multifidelidade.multipay

import java.net.URI
import java.net.URLDecoder

/**
 * Situacao de um pagamento.
 *
 * IMPORTANTE: o status que volta pelo deep link/retorno do checkout e so uma DICA para a interface
 * (o usuario pode editar a URL). Quem libera o pedido e o SEU backend, apos o webhook assinado ou um GET.
 */
enum class CheckoutStatus {
    PAID, PENDING, EXPIRED, CANCELED, UNKNOWN;

    companion object {
        fun from(value: String?): CheckoutStatus = when (value?.lowercase()) {
            "paid" -> PAID
            "pending" -> PENDING
            "expired" -> EXPIRED
            "canceled", "cancelled" -> CANCELED
            else -> UNKNOWN
        }
    }
}

/** Retorno do checkout (a `returnUrl` recebe `?referenceId=...&status=...`). */
data class CheckoutResult(
    val referenceId: String?,
    val status: CheckoutStatus,
    /** URL completa recebida, para depuracao. */
    val rawUrl: String,
)

object CheckoutReturn {
    /** Interpreta a URL de retorno. Devolve null se nao for uma URL valida. Nunca lanca excecao. */
    fun parse(url: String?): CheckoutResult? {
        if (url.isNullOrBlank()) return null
        val query = runCatching { URI(url).rawQuery }.getOrNull() ?: return CheckoutResult(null, CheckoutStatus.UNKNOWN, url)
        val params = query.split('&').filter { it.isNotEmpty() }.associate {
            val kv = it.split('=', limit = 2)
            decode(kv[0]) to decode(kv.getOrElse(1) { "" })
        }
        return CheckoutResult(params["referenceId"], CheckoutStatus.from(params["status"]), url)
    }

    private fun decode(s: String) = runCatching { URLDecoder.decode(s, Charsets.UTF_8) }.getOrDefault(s)
}

/**
 * Valida a URL do checkout ANTES de abri-la. Evita que um backend comprometido (ou erro de integracao)
 * mande o app abrir uma pagina qualquer: so https e so hosts da MultiPay (ou os que voce autorizar).
 */
object CheckoutUrl {
    private val DEFAULT_HOSTS = setOf("multifidelidade.app")

    /** @param extraHosts hosts adicionais (ex.: o dominio do seu ambiente de testes). Subdominios sao aceitos. */
    fun isSafe(url: String?, extraHosts: Set<String> = emptySet()): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() != "https") return false
        if (uri.userInfo != null) return false
        val host = uri.host?.lowercase() ?: return false
        return (DEFAULT_HOSTS + extraHosts.map { it.lowercase() }).any { host == it || host.endsWith(".$it") }
    }
}
