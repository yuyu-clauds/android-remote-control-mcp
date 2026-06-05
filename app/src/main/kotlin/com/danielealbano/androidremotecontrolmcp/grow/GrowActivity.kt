package com.danielealbano.androidremotecontrolmcp.grow

import android.annotation.SuppressLint
import android.net.http.SslError
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/**
 * Fullscreen WebView host for the "room" page served at [GROW_URL].
 *
 * Deliberately isolated from the MCP transport code: this is a thin shell whose
 * only job is to load the existing web page, so the home-screen widget has
 * somewhere to open. No Hilt, no MCP wiring.
 *
 * Transport note: the page is reached over HTTPS by IP, but the server presents
 * a CA-valid Let's Encrypt certificate issued for [EXPECTED_CERT_CN] (the IP has
 * no certificate of its own, and that domain currently has no DNS). So the only
 * expected TLS error is a hostname mismatch. [GrowWebViewClient] accepts that one
 * narrow case for our known host and rejects everything else — the page stays
 * encrypted (it shows personal status, so cleartext is not acceptable) while a
 * real MITM, an expired cert, or an untrusted CA still fails closed.
 */
class GrowActivity : ComponentActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView =
            WebView(this).apply {
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = GrowWebViewClient()
                loadUrl(GROW_URL)
            }
        setContentView(webView)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    /**
     * Accepts the server's CA-valid certificate despite the hostname mismatch that
     * comes from reaching it by IP, and only for our known host. Fails closed on
     * any other TLS problem.
     */
    private class GrowWebViewClient : WebViewClient() {
        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler,
            error: SslError,
        ) {
            val onlyHostnameMismatch =
                error.primaryError == SslError.SSL_IDMISMATCH &&
                    !error.hasError(SslError.SSL_UNTRUSTED) &&
                    !error.hasError(SslError.SSL_EXPIRED) &&
                    !error.hasError(SslError.SSL_NOTYETVALID) &&
                    !error.hasError(SslError.SSL_INVALID) &&
                    !error.hasError(SslError.SSL_DATE_INVALID)
            val isKnownHost = error.certificate.issuedTo?.cName == EXPECTED_CERT_CN
            if (onlyHostnameMismatch && isKnownHost) {
                handler.proceed()
            } else {
                handler.cancel()
            }
        }
    }

    companion object {
        /** The room page. Served over HTTPS; see the TLS note above. */
        const val GROW_URL = "https://8.209.196.82/grow/"

        /** Common name on the server's certificate (issued for the domain, not the IP). */
        const val EXPECTED_CERT_CN = "helloqing.xyz"
    }
}
