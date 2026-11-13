package com.multifidelidade.multipay.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.multifidelidade.multipay.CheckoutResult
import com.multifidelidade.multipay.CheckoutReturn
import com.multifidelidade.multipay.CheckoutUrl

/**
 * Abre o checkout MultiPay num Chrome Custom Tab e interpreta o retorno (deep link).
 *
 * Fluxo:
 *  1. Seu backend cria o pagamento (chave mp_live_ so la) e devolve o `paymentUrl` ao app.
 *  2. `MultiPayCheckout.launch(context, paymentUrl)`
 *  3. O checkout redireciona para a `returnUrl` (deep link do app), p.ex. `meuapp://pagamento/retorno?referenceId=..&status=paid`.
 *  4. Sua Activity recebe o intent e chama `MultiPayCheckout.parseReturn(intent.data)`.
 *  5. Confirme o status REAL no seu backend (webhook/GET). O status da URL e so dica de UI.
 *
 * Manifest (na Activity que recebe o retorno, `launchMode="singleTop"` ou `singleTask`):
 * ```xml
 * <intent-filter>
 *   <action android:name="android.intent.action.VIEW"/>
 *   <category android:name="android.intent.category.DEFAULT"/>
 *   <category android:name="android.intent.category.BROWSABLE"/>
 *   <data android:scheme="meuapp" android:host="pagamento"/>
 * </intent-filter>
 * ```
 */
object MultiPayCheckout {

    class UnsafeCheckoutUrlException(url: String) :
        IllegalArgumentException("URL de checkout recusada (precisa ser https e de um host MultiPay): $url")

    /**
     * @param extraHosts hosts adicionais autorizados (ex.: ambiente de testes proprio).
     * @throws UnsafeCheckoutUrlException se a URL nao for https/host permitido.
     */
    @JvmStatic
    @JvmOverloads
    fun launch(context: Context, paymentUrl: String, extraHosts: Set<String> = emptySet()) {
        if (!CheckoutUrl.isSafe(paymentUrl, extraHosts)) throw UnsafeCheckoutUrlException(paymentUrl)

        val uri = Uri.parse(paymentUrl)
        val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
        // Context sem Activity (ex.: Application/Service) exige NEW_TASK.
        if (context !is android.app.Activity) tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            tab.launchUrl(context, uri)
        } catch (e: android.content.ActivityNotFoundException) {
            // sem navegador com Custom Tabs: cai para qualquer navegador
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Interpreta o deep link de retorno. Null se `uri` for nulo. Nunca lanca excecao. */
    @JvmStatic
    fun parseReturn(uri: Uri?): CheckoutResult? = CheckoutReturn.parse(uri?.toString())
}
