package com.multifidelidade.multipay

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Verificador de webhooks para backends Kotlin/JVM (Ktor, Spring...). Nao use em app.
 * Header: `MultiPay-Signature: t=<unix>,v1=<hex>`; v1 = HMAC-SHA256(segredo, "<t>." + corpoBruto).
 */
object WebhookVerifier {
    const val HEADER = "MultiPay-Signature"
    const val DEFAULT_TOLERANCE_SECONDS = 300L

    enum class Failure { MISSING_HEADER, MALFORMED_HEADER, TIMESTAMP_OUT_OF_TOLERANCE, SIGNATURE_MISMATCH }

    sealed interface Result {
        data object Valid : Result
        data class Invalid(val reason: Failure) : Result
    }

    fun sign(secret: String, timestamp: Long, rawBody: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal("$timestamp.$rawBody".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun verify(
        secret: String,
        header: String?,
        rawBody: String,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
        toleranceSeconds: Long = DEFAULT_TOLERANCE_SECONDS,
    ): Result {
        if (header.isNullOrBlank()) return Result.Invalid(Failure.MISSING_HEADER)

        var t: Long? = null
        var v1: String? = null
        for (part in header.split(',')) {
            val kv = part.split('=', limit = 2).map { it.trim() }
            if (kv.size != 2) continue
            when (kv[0]) {
                "t" -> t = kv[1].toLongOrNull()
                "v1" -> v1 = kv[1]
            }
        }
        if (t == null || v1 == null) return Result.Invalid(Failure.MALFORMED_HEADER)
        if (kotlin.math.abs(nowSeconds - t) > toleranceSeconds) return Result.Invalid(Failure.TIMESTAMP_OUT_OF_TOLERANCE)

        val expected = sign(secret, t, rawBody).toByteArray(Charsets.UTF_8)
        // MessageDigest.isEqual compara em tempo constante
        return if (MessageDigest.isEqual(expected, v1.toByteArray(Charsets.UTF_8))) Result.Valid
        else Result.Invalid(Failure.SIGNATURE_MISMATCH)
    }
}
