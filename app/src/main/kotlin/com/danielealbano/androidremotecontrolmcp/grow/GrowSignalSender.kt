package com.danielealbano.androidremotecontrolmcp.grow

import android.util.Log
import java.net.URL
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSession

/**
 * Posts a lightweight state "signal" (醒了 / 吃了早饭 / 吃药了 …) from the home-screen
 * widget straight to the Life Log, so she can record state without opening the app.
 *
 * Security note (mirrors [GrowActivity]'s WebView TLS handling): the server is reached
 * by IP but presents a CA-valid Let's Encrypt certificate issued for [EXPECTED_CERT_CN].
 * The default [HttpsURLConnection] still validates the full CA chain — we only relax the
 * *hostname* check, and only when the validated leaf certificate's CN is our known domain.
 * A real MITM (different cert / untrusted CA / expired) still fails the handshake closed.
 */
internal object GrowSignalSender {

    private const val TAG = "GrowSignalSender"
    private const val LOG_ENDPOINT = "https://8.209.196.82/log/api/log"
    private const val EXPECTED_CERT_CN = "helloqing.xyz"
    private const val TIMEOUT_MS = 8000

    private val expectedCnRegex = Regex("""CN=helloqing\.xyz($|,)""")

    private val cnHostnameVerifier =
        HostnameVerifier { _, session -> leafCertCnMatches(session) }

    /**
     * Blocking POST of a `[信号]` progress entry. Call OFF the main thread.
     * Returns true on a 2xx response, false on any failure (caller stays silent-safe).
     */
    @Suppress("TooGenericExceptionCaught")
    fun postSignal(category: String, label: String): Boolean {
        val body = """{"type":"progress","category":"$category","note":"[by:yuyu][信号] $label"}"""
        var conn: HttpsURLConnection? = null
        return try {
            conn =
                (URL(LOG_ENDPOINT).openConnection() as HttpsURLConnection).apply {
                    hostnameVerifier = cnHostnameVerifier
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    doOutput = true
                }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            Log.w(TAG, "signal post failed: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun leafCertCnMatches(session: SSLSession): Boolean =
        try {
            val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
            val dn = leaf?.subjectX500Principal?.name ?: return false
            expectedCnRegex.containsMatchIn(dn)
        } catch (e: Exception) {
            Log.w(TAG, "cert check failed: ${e.message}")
            false
        }
}
