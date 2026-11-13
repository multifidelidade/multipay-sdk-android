package app.multifidelidade.multipaydemo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.multifidelidade.multipay.CreatePaymentRequest
import com.multifidelidade.multipay.MultiPayClient
import com.multifidelidade.multipay.MultiPayException
import com.multifidelidade.multipay.android.MultiPayCheckout
import kotlin.concurrent.thread

/** App de teste do SDK: use SOMENTE chave mp_test_ (o SDK recusa mp_live_). */
class MainActivity : Activity() {
    private lateinit var key: EditText
    private lateinit var base: EditText
    private lateinit var log: TextView
    private lateinit var btnWho: Button
    private lateinit var btnPay: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        key = EditText(this).apply { hint = "Chave mp_test_..."; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        base = EditText(this).apply { setText("https://api.multifidelidade.app/integracoes"); inputType = InputType.TYPE_TEXT_VARIATION_URI }
        btnWho = Button(this).apply { text = "Verificar chave" }
        btnPay = Button(this).apply { text = "Criar e abrir checkout" }
        log = TextView(this).apply { textSize = 13f }
        listOf(key, base, btnWho, btnPay).forEach(root::addView)
        root.addView(ScrollView(this).apply { addView(log) })
        setContentView(root)

        btnWho.setOnClickListener { run(btnWho) { c -> val m = c.whoami(); "whoami: ${m.merchantName} (livemode=${m.livemode})" } }
        btnPay.setOnClickListener {
            run(btnPay) { c ->
                val ref = "ANDROID-" + System.currentTimeMillis()
                val created = c.createPayment(
                    CreatePaymentRequest(
                        referenceId = ref, amountCents = 1500, cpf = "52998224725",
                        callbackUrl = "https://example.com/webhooks/multipay",
                        returnUrl = "multipaydemo://pagamento/retorno",
                    )
                )
                val url = created.payment.paymentUrl
                runOnUiThread { MultiPayCheckout.launch(this, url) }
                "criado $ref status=${created.payment.status}\nabrindo $url"
            }
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    private fun handle(i: Intent?) {
        val r = MultiPayCheckout.parseReturn(i?.data) ?: return
        append("retorno: $r")
    }

    private fun append(s: String) = runOnUiThread { log.append(s + "\n\n") }

    /** Reabilita o botao em TODOS os caminhos (sucesso e erro). */
    private fun run(btn: Button, block: (MultiPayClient) -> String) {
        btn.isEnabled = false
        thread {
            try {
                val c = MultiPayClient.forApp(key.text.toString().trim(), base.text.toString().trim())
                append(block(c))
            } catch (e: MultiPayException) {
                append("erro API: ${e.message} (${e.code}) traceId=${e.traceId}")
            } catch (e: Exception) {
                append("erro: ${e::class.simpleName}: ${e.message}")
            } finally {
                runOnUiThread { btn.isEnabled = true }
            }
        }
    }
}
