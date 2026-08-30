package com.malik.alshurti.remote

import android.content.Context
import com.malik.alshurti.DogMood
import com.malik.alshurti.PoliceBrain
import com.malik.alshurti.PoliceReply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Online brain: Qwen runs on the self-hosted backend, never inside the phone. */
class RemotePoliceBrain(context: Context) : PoliceBrain {
    private val locator = BackendLocator(context)
    private val history = ArrayDeque<Turn>()

    override suspend fun reply(userText: String): PoliceReply = withContext(Dispatchers.IO) {
        val clean = userText.trim()
        require(clean.isNotBlank()) { "ما سمعت كلام واضح." }

        val baseUrl = locator.resolve()
        val payload = JSONObject().apply {
            put("text", clean)
            put("history", JSONArray().apply {
                history.forEach { turn ->
                    put(JSONObject().apply {
                        put("role", turn.role)
                        put("content", turn.content)
                    })
                }
            })
        }

        val connection = (URL("$baseUrl/v1/chat").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 50_000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()

            if (code !in 200..299) {
                val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull()
                error(detail?.takeIf { it.isNotBlank() } ?: "خادم المحادثة رجع خطأ $code")
            }

            val json = JSONObject(body)
            val reply = json.optString("reply").trim()
            check(reply.isNotBlank()) { "خادم المحادثة رجع رد فارغ." }
            val mood = when (json.optString("mood")) {
                "smile" -> DogMood.SMILE
                "serious" -> DogMood.SERIOUS
                "calm" -> DogMood.CALM
                else -> DogMood.TALKING
            }

            history.addLast(Turn("user", clean))
            history.addLast(Turn("assistant", reply))
            while (history.size > MAX_HISTORY_ITEMS) history.removeFirst()

            PoliceReply(reply, mood)
        } catch (t: Throwable) {
            locator.invalidate()
            throw t
        } finally {
            connection.disconnect()
        }
    }

    override fun release() {
        history.clear()
        locator.invalidate()
    }

    private data class Turn(val role: String, val content: String)

    private companion object {
        const val MAX_HISTORY_ITEMS = 10
    }
}
