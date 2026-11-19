package com.maekom.apirunner.engine

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.maekom.apirunner.model.HttpExecutionResponse
import com.maekom.apirunner.model.HttpRequestSnapshot
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Thread-safe OkHttp execution wrapper with:
 * - 10s connect timeout, 30s read/write timeout
 * - Strict SSL verification by default; opt-in ignoreSsl for local self-signed certs
 * - Immediate cancellation via Call.cancel()
 * - Safe non-JSON response handling (HTML / Plain Text fallback without crashing)
 */
class HttpExecutor {

    private val activeCall = AtomicReference<Call?>(null)

    private val prettyMapper: ObjectMapper = ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)

    private val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val insecureClient: OkHttpClient by lazy {
        val trustAllManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun execute(
        snapshot: HttpRequestSnapshot,
        ignoreSsl: Boolean = false
    ): HttpExecutionResponse {
        val startNanos = System.nanoTime()
        val client = if (ignoreSsl) insecureClient else defaultClient

        val request = try {
            buildRequest(snapshot)
        } catch (e: Exception) {
            val elapsedMs = elapsedMillis(startNanos)
            return HttpExecutionResponse(
                statusCode = null,
                statusMessage = "Invalid Request",
                durationMs = elapsedMs,
                responseHeaders = emptyMap(),
                rawBody = "",
                formattedBody = "",
                isJson = false,
                errorMessage = "요청 생성 실패: ${e.message ?: e.javaClass.simpleName}"
            )
        }

        val call = client.newCall(request)
        activeCall.set(call)

        return try {
            call.execute().use { response ->
                val elapsedMs = elapsedMillis(startNanos)
                val responseHeaders = linkedMapOf<String, String>()
                for (i in 0 until response.headers.size) {
                    val name = response.headers.name(i)
                    val value = response.headers.value(i)
                    val prev = responseHeaders[name]
                    responseHeaders[name] = if (prev == null) value else "$prev, $value"
                }

                val rawBody = try {
                    response.body?.string().orEmpty()
                } catch (e: Exception) {
                    "[응답 본문 읽기 실패: ${e.message}]"
                }

                val contentType = response.header("Content-Type").orEmpty()
                val (formatted, isJson) = safeFormatBody(rawBody, contentType)

                HttpExecutionResponse(
                    statusCode = response.code,
                    statusMessage = response.message,
                    durationMs = elapsedMs,
                    responseHeaders = responseHeaders,
                    rawBody = rawBody,
                    formattedBody = formatted,
                    isJson = isJson
                )
            }
        } catch (e: IOException) {
            val elapsedMs = elapsedMillis(startNanos)
            val wasCancelled = call.isCanceled()
            HttpExecutionResponse(
                statusCode = null,
                statusMessage = if (wasCancelled) "Cancelled" else "Network Error",
                durationMs = elapsedMs,
                responseHeaders = emptyMap(),
                rawBody = "",
                formattedBody = "",
                isJson = false,
                errorMessage = if (wasCancelled) {
                    "사용자에 의해 요청이 중단되었습니다."
                } else {
                    "네트워크 오류 (${e.javaClass.simpleName}): ${e.message ?: "연결 실패"}"
                },
                cancelled = wasCancelled
            )
        } catch (e: Exception) {
            val elapsedMs = elapsedMillis(startNanos)
            HttpExecutionResponse(
                statusCode = null,
                statusMessage = "Execution Error",
                durationMs = elapsedMs,
                responseHeaders = emptyMap(),
                rawBody = "",
                formattedBody = "",
                isJson = false,
                errorMessage = "예기치 않은 오류 (${e.javaClass.simpleName}): ${e.message ?: "알 수 없는 오류"}"
            )
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    fun cancelCurrent() {
        activeCall.get()?.cancel()
    }

    private fun buildRequest(snapshot: HttpRequestSnapshot): Request {
        val method = snapshot.method.trim().uppercase().ifEmpty { "GET" }
        val builder = Request.Builder().url(snapshot.url)

        var contentTypeHeader: String? = null
        for ((key, value) in snapshot.headers) {
            val trimmedKey = key.trim()
            if (trimmedKey.isNotEmpty()) {
                if (trimmedKey.equals("Content-Type", ignoreCase = true)) {
                    contentTypeHeader = value
                }
                builder.header(trimmedKey, value)
            }
        }

        val requestBody: RequestBody? = when (method) {
            "GET", "HEAD" -> null
            "POST", "PUT", "PATCH" -> {
                val mediaType = (contentTypeHeader ?: "application/json; charset=utf-8").toMediaTypeOrNull()
                snapshot.body.toRequestBody(mediaType)
            }
            "DELETE", "OPTIONS" -> {
                if (snapshot.body.isBlank()) {
                    null
                } else {
                    val mediaType = (contentTypeHeader ?: "application/json; charset=utf-8").toMediaTypeOrNull()
                    snapshot.body.toRequestBody(mediaType)
                }
            }
            else -> {
                if (snapshot.body.isBlank()) null
                else snapshot.body.toRequestBody((contentTypeHeader ?: "application/json; charset=utf-8").toMediaTypeOrNull())
            }
        }

        builder.method(method, requestBody)
        return builder.build()
    }

    fun safeFormatBody(rawBody: String, contentType: String = ""): Pair<String, Boolean> {
        val trimmed = rawBody.trim()
        if (trimmed.isEmpty()) {
            return "" to false
        }
        val looksLikeJson = (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]")) ||
            contentType.contains("json", ignoreCase = true)

        if (!looksLikeJson) {
            return rawBody to false
        }

        return try {
            val tree = prettyMapper.readTree(trimmed)
            if (tree != null && (tree.isObject || tree.isArray)) {
                prettyMapper.writeValueAsString(tree) to true
            } else {
                rawBody to false
            }
        } catch (_: Exception) {
            // Non-JSON response (e.g., Spring Whitelabel HTML error page or plain text) - never crash
            rawBody to false
        }
    }

    private fun elapsedMillis(startNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
}
