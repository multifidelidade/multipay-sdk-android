# MultiPay SDK — Android / Kotlin

Dois artefatos:

| Módulo | O que é | Testado |
|---|---|---|
| `multipay-core` | Kotlin/JVM puro: cliente HTTP da API v2, parser do retorno do checkout, validador de URL, verificador de webhook (backends Kotlin) | ✅ 20 testes (`./gradlew :multipay-core:test`) |
| `multipay-android` | `MultiPayCheckout.launch(...)` (Chrome Custom Tabs) + `parseReturn(Uri)` | ⚠️ **não compilado** (a máquina de build não tinha Android SDK). Abra no Android Studio e rode um build antes de publicar |

## Princípio de segurança

**A chave `mp_live_` nunca vai para o app.** O app mostra o checkout; quem cria o pagamento é o **seu backend**.

```
App ──"quero pagar o pedido 123"──► Seu backend ──POST /v2/payments (mp_live_)──► MultiPay
App ◄────────── paymentUrl ──────── Seu backend
App ── abre paymentUrl (Custom Tab) ──► checkout (PIX/cartão) ──► deep link meuapp://pagamento/retorno?referenceId=123&status=paid
App ──"e o pedido 123, pagou?"──► Seu backend (confirma por webhook/GET) ──► App
```

`MultiPayClient.forApp(...)` **recusa** `mp_live_` (lança `IllegalArgumentException`). Em app use só `mp_test_` (demo/sandbox).

## Uso no app

```kotlin
// build.gradle.kts
// settings.gradle.kts: repositories { maven("https://jitpack.io") }
dependencies { implementation("com.github.multifidelidade.multipay-sdk-android:multipay-android:0.1.0") }
```

```kotlin
// 1) abrir o checkout (paymentUrl veio do SEU backend)
MultiPayCheckout.launch(this, paymentUrl)          // valida https + host MultiPay antes de abrir

// 2) receber o retorno (Activity com o intent-filter do deep link, launchMode singleTop)
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    val result = MultiPayCheckout.parseReturn(intent.data) ?: return
    when (result.status) {
        CheckoutStatus.PAID -> viewModel.confirmarNoBackend(result.referenceId)   // status da URL é só dica!
        CheckoutStatus.CANCELED, CheckoutStatus.EXPIRED -> mostrarErro()
        else -> Unit
    }
}
```

Manifest da Activity de retorno:

```xml
<activity android:name=".PedidoActivity" android:launchMode="singleTop" android:exported="true">
  <intent-filter>
    <action android:name="android.intent.action.VIEW"/>
    <category android:name="android.intent.category.DEFAULT"/>
    <category android:name="android.intent.category.BROWSABLE"/>
    <data android:scheme="meuapp" android:host="pagamento"/>
  </intent-filter>
</activity>
```

No backend, crie o pagamento com `returnUrl = "meuapp://pagamento/retorno"`.

## Uso em backend Kotlin/JVM (Ktor, Spring)

```kotlin
val client = MultiPayClient.forBackend(System.getenv("MULTIPAY_API_KEY"))   // aceita mp_live_

val created = client.createPayment(CreatePaymentRequest(
    referenceId = "PED-1001", amountCents = 15000, cpf = "52998224725",
    callbackUrl = "https://sualoja.com.br/webhooks/multipay", returnUrl = "meuapp://pagamento/retorno"))
// created.replayed == true se o mesmo referenceId já existia (idempotente)
respondWith(created.payment.paymentUrl)

// webhook (corpo BRUTO!)
when (WebhookVerifier.verify(secret, call.request.header(WebhookVerifier.HEADER), rawBody)) {
    is WebhookVerifier.Result.Valid -> processar(rawBody)
    is WebhookVerifier.Result.Invalid -> call.respond(HttpStatusCode.BadRequest)
}
```

Chamadas são bloqueantes: use `Dispatchers.IO`.

## Testando sem pagar

Com uma chave `mp_test_` o `paymentUrl` abre um checkout fake. Para concluir sem tocar no navegador:

```kotlin
client.simulatePayment("PED-1001", "paid")   // dispara o webhook payment.paid
```
