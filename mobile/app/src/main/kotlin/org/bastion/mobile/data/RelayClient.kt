package org.bastion.mobile.data

import android.annotation.SuppressLint
import java.io.IOException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.TlsVersion
import org.bastion.core.crypto.Bytes
import org.bastion.core.crypto.RequestAuth
import org.bastion.core.crypto.SigningKeypair
import org.bastion.core.crypto.Sodium
import org.bastion.protocol.v1.EnrollRequest
import org.bastion.protocol.v1.EnrollResponse
import org.bastion.protocol.v1.MailboxAck
import org.bastion.protocol.v1.MailboxBatch

/** Relay failures, mapped from HTTP and TLS errors. */
sealed class RelayException(message: String) : IOException(message) {
    /** The relay no longer knows this device (revoked or unpaired by the controller). */
    class Unauthorized : RelayException("unauthorized")

    class Refused(val status: Int) : RelayException("refused: $status")

    class PinMismatch : RelayException("relay key does not match the pin")

    class Unreachable(cause: IOException) : RelayException("unreachable: ${chain(cause)}") {
        init {
            initCause(cause)
        }
    }
}

/** Exception class names along the cause chain (never messages, which may contain data). */
internal fun chain(error: Throwable): String =
    generateSequence(error) { it.cause }.take(MAX_CHAIN).joinToString(" < ") {
        // Messages only in debug builds: they may contain host names.
        val name = it.javaClass.simpleName
        if (org.bastion.mobile.BuildConfig.DEBUG) "$name(${it.message})" else name
    }

private const val MAX_CHAIN = 5

/**
 * Accepts exactly the relay key pinned in the pairing QR code (ADR-0009). No certificate
 * authority is trusted; the TLS handshake still proves possession of the pinned key.
 */
// Deliberate (ADR-0009): the relay is self-signed and authenticated by its pinned key only.
// checkServerTrusted always throws unless the leaf key matches the 32-byte pin.
@SuppressLint("CustomX509TrustManager")
class SpkiPinTrustManager(private val pin: ByteArray) : X509TrustManager {
    fun matches(certificate: X509Certificate): Boolean =
        MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded), pin)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("empty chain")
        if (!matches(leaf)) throw CertificateException("pin mismatch")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?): Unit =
        throw CertificateException("client certificates are not accepted")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Signed relay API client (PROTOCOL.md §8), TLS 1.3 only, SPKI-pinned. */
class RelayClient(baseUrl: String, pin: ByteArray, private val sodium: Sodium, private val identitySeed: ByteArray) {
    private val base = baseUrl.trimEnd('/')
    private val http: OkHttpClient

    init {
        require(base.startsWith("https://")) { "relay must use HTTPS" }
        require(pin.size == PIN_BYTES) { "pin must be 32 bytes" }
        val trust = SpkiPinTrustManager(pin.copyOf())
        val tls = SSLContext.getInstance("TLSv1.3").apply { init(null, arrayOf(trust), null) }
        http = OkHttpClient.Builder()
            .sslSocketFactory(tls.socketFactory, trust)
            // The certificate names carry no trust; the session is accepted only if its leaf
            // certificate matches the pin (checked again here as defence in depth).
            .hostnameVerifier { _, session ->
                runCatching { (session.peerCertificates.first() as X509Certificate).let(trust::matches) }
                    .getOrDefault(false)
            }
            .connectionSpecs(
                listOf(ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS).tlsVersions(TlsVersion.TLS_1_3).build()),
            )
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Suppress("CyclomaticComplexMethod")
    private suspend fun call(method: String, path: String, body: ByteArray, signed: Boolean): ByteArray =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(base + path)
            if (signed) {
                val auth = SigningKeypair(sodium, identitySeed).use { ik ->
                    RequestAuth.sign(sodium, ik, method, path, System.currentTimeMillis(), body)
                }
                builder.header(RequestAuth.HEADER, auth)
            }
            val payload = if (method == "GET" || method == "DELETE") null else body.toRequestBody(PROTOBUF)
            val request = builder.method(method, payload).build()
            try {
                http.newCall(request).execute().use { response ->
                    when {
                        response.code == HTTP_UNAUTHORIZED -> throw RelayException.Unauthorized()
                        !response.isSuccessful -> throw RelayException.Refused(response.code)
                        else -> response.body.bytes()
                    }
                }
            } catch (e: SSLPeerUnverifiedException) {
                throw RelayException.PinMismatch().apply { initCause(e) }
            } catch (e: javax.net.ssl.SSLHandshakeException) {
                if (e.cause is CertificateException) throw RelayException.PinMismatch().apply { initCause(e) }
                throw RelayException.Unreachable(e)
            } catch (e: RelayException) {
                throw e
            } catch (e: IOException) {
                throw RelayException.Unreachable(e)
            }
        }

    suspend fun enroll(request: EnrollRequest): EnrollResponse =
        EnrollResponse.parseFrom(call("POST", "/v1/enroll", request.toByteArray(), signed = false))

    suspend fun fetch(waitSeconds: Int): MailboxBatch {
        val path = if (waitSeconds > 0) "/v1/mailbox?wait=$waitSeconds" else "/v1/mailbox"
        return MailboxBatch.parseFrom(call("GET", path, ByteArray(0), signed = true))
    }

    suspend fun ack(ids: List<Long>) {
        if (ids.isEmpty()) return
        call("POST", "/v1/mailbox/ack", MailboxAck.newBuilder().addAllIds(ids).build().toByteArray(), signed = true)
    }

    suspend fun send(recipient: ByteArray, envelope: ByteArray) {
        call("PUT", "/v1/mailbox/${Bytes.toHex(recipient)}", envelope, signed = true)
    }

    suspend fun revoke(device: ByteArray) {
        call("DELETE", "/v1/peers/${Bytes.toHex(device)}", ByteArray(0), signed = true)
    }

    private companion object {
        val PROTOBUF = "application/x-protobuf".toMediaType()
        const val PIN_BYTES = 32
        const val CONNECT_TIMEOUT_S = 15L
        const val READ_TIMEOUT_S = 45L
        const val HTTP_UNAUTHORIZED = 401
    }
}
