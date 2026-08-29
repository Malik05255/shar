package com.almi.ai.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.almi.ai.data.network.NetworkClient
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class OpenRouterOAuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkClient: NetworkClient,
) {
    suspend fun connect(): Result<OpenRouterOAuthResult> = withContext(Dispatchers.IO) {
        runCatching {
            val verifier = createVerifier()
            val challenge = createChallenge(verifier)

            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = CALLBACK_TIMEOUT_MS
                val callbackUrl = "http://127.0.0.1:${server.localPort}/callback"
                val authUrl = Uri.parse(AUTH_URL).buildUpon()
                    .appendQueryParameter("callback_url", callbackUrl)
                    .appendQueryParameter("code_challenge", challenge)
                    .appendQueryParameter("code_challenge_method", "S256")
                    .build()

                withContext(Dispatchers.Main) {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, authUrl).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }

                val code = server.accept().use { socket ->
                    socket.soTimeout = CALLBACK_TIMEOUT_MS
                    val reader = socket.getInputStream().bufferedReader()
                    val requestLine = reader.readLine().orEmpty()
                    val target = requestLine.split(' ').getOrNull(1).orEmpty()
                    val requestUri = Uri.parse("http://127.0.0.1$target")
                    val error = requestUri.getQueryParameter("error")
                    val authorizationCode = requestUri.getQueryParameter("code")

                    val responseBody = if (!authorizationCode.isNullOrBlank()) {
                        "ALMI_AI connected successfully. You can return to the app."
                    } else {
                        "ALMI_AI connection was not completed. You can return to the app."
                    }
                    val response = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/plain; charset=utf-8\r\n" +
                        "Connection: close\r\n" +
                        "Content-Length: ${responseBody.toByteArray().size}\r\n\r\n" +
                        responseBody
                    socket.getOutputStream().use { output ->
                        output.write(response.toByteArray())
                        output.flush()
                    }

                    if (!error.isNullOrBlank()) throw IllegalStateException("oauth_$error")
                    authorizationCode ?: throw IllegalStateException("oauth_code_missing")
                }

                exchangeCode(code, verifier)
            }
        }
    }

    private suspend fun exchangeCode(code: String, verifier: String): OpenRouterOAuthResult {
        val payload = JSONObject()
            .put("code", code)
            .put("code_verifier", verifier)
            .put("code_challenge_method", "S256")

        val response = networkClient().post(EXCHANGE_URL) {
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException("oauth_exchange_http_${response.status.value}")
        }
        val root = JSONObject(body)
        val key = root.optString("key").trim()
        if (key.isBlank()) throw IllegalStateException("oauth_key_missing")
        return OpenRouterOAuthResult(
            apiKey = key,
            userId = root.optString("user_id").takeIf(String::isNotBlank),
        )
    }

    private fun createVerifier(): String {
        val bytes = ByteArray(48)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun createChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    companion object {
        private const val AUTH_URL = "https://openrouter.ai/auth"
        private const val EXCHANGE_URL = "https://openrouter.ai/api/v1/auth/keys"
        private const val CALLBACK_TIMEOUT_MS = 5 * 60 * 1000
    }
}

data class OpenRouterOAuthResult(
    val apiKey: String,
    val userId: String?,
)
