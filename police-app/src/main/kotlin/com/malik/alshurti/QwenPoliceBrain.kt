package com.malik.alshurti

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.codeshipping.llamakotlin.LlamaModel
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * On-device conversational brain.
 *
 * Normal turns are generated from the child's current sentence + recent context.
 * There is deliberately no table of canned behaviour replies. A deterministic
 * emergency guard remains before the model because child safety should not depend
 * solely on a small local LLM.
 */
class QwenPoliceBrain(context: Context) : PoliceBrain {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadLock = Any()
    private val historyLock = Any()
    private val history = ArrayDeque<Turn>()

    @Volatile private var loadedModel: LlamaModel? = null
    @Volatile private var allowNetworkDownloads = true

    override fun prepare(allowDownload: Boolean) {
        allowNetworkDownloads = allowDownload
        if (loadedModel != null) return
        scope.launch {
            runCatching { modelOrLoad() }
            // Warm-up errors are not made permanent: switching from Offline to Online
            // later can still provision the model without restarting the app.
        }
    }

    override suspend fun reply(userText: String): PoliceReply {
        val cleanUser = userText.trim()
        require(cleanUser.isNotBlank()) { "لا يوجد كلام واضح للرد عليه." }

        emergencyReply(cleanUser)?.let { return it }

        return withContext(Dispatchers.IO) {
            val model = modelOrLoad()
            val prompt = buildPrompt(cleanUser)
            val generated = sanitize(model.generate(prompt))
            check(generated.isNotBlank()) { "النموذج لم يولد رداً صالحاً. حاول مرة ثانية." }

            synchronized(historyLock) {
                history.addLast(Turn(cleanUser, generated))
                while (history.size > MAX_HISTORY_TURNS) history.removeFirst()
            }

            PoliceReply(generated, inferMood(cleanUser, generated))
        }
    }

    override fun release() {
        scope.cancel()
        synchronized(loadLock) {
            runCatching { loadedModel?.cancelGeneration() }
            runCatching { loadedModel?.close() }
            loadedModel = null
        }
    }

    private fun modelOrLoad(): LlamaModel = synchronized(loadLock) {
        loadedModel?.let { return@synchronized it }

        val modelFile = ensureModel(allowNetworkDownloads)
        val cpuCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        val model = LlamaModel.load(modelFile.absolutePath) {
            contextSize = 2048
            batchSize = 256
            threads = (cpuCount - 1).coerceIn(2, 6)
            temperature = 0.78f
            topP = 0.92f
            topK = 40
            repeatPenalty = 1.12f
            maxTokens = 120
            useMmap = true
            useMlock = false
            gpuLayers = 0
            seed = -1
        }
        loadedModel = model
        model
    }

    private fun buildPrompt(currentUserText: String): String = buildString {
        append("<|im_start|>system\n")
        append(PoliceCharacterContract.systemPrompt)
        append("\n/no_think")
        append("<|im_end|>\n")

        synchronized(historyLock) {
            history.forEach { turn ->
                append("<|im_start|>user\n")
                append(turn.user)
                append("<|im_end|>\n")
                append("<|im_start|>assistant\n")
                append(turn.assistant)
                append("<|im_end|>\n")
            }
        }

        append("<|im_start|>user\n")
        append(currentUserText)
        append(" /no_think")
        append("<|im_end|>\n")
        append("<|im_start|>assistant\n")
    }

    private fun sanitize(raw: String): String {
        val cleaned = raw
            .replace(Regex("(?s)<think>.*?</think>"), "")
            .replace("<|im_end|>", "")
            .replace("<|endoftext|>", "")
            .replace("<|im_start|>", "")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (cleaned.length <= MAX_REPLY_CHARS) return cleaned

        // Keep a phone-call response short at a natural sentence boundary rather than
        // chopping an Arabic word mid-sentence. Nothing is substituted with canned text.
        val window = cleaned.take(MAX_REPLY_CHARS)
        val boundary = window.indexOfLast { it == '.' || it == '؟' || it == '!' || it == '،' }
        return if (boundary >= MIN_NATURAL_BOUNDARY) {
            window.substring(0, boundary + 1).trim()
        } else {
            window.substringBeforeLast(' ', window).trim()
        }
    }

    private fun emergencyReply(text: String): PoliceReply? {
        val normalized = normalize(text)
        val danger = listOf(
            "سكين", "سلاح", "حريق", "ينزف", "دم كثير", "ما يتنفس", "اختناق",
            "خطف", "يهددني", "بيضربني", "يضربني الحين", "حادث قوي", "ضايع لحالي"
        )
        if (danger.none(normalized::contains)) return null
        return PoliceReply(
            "اسمعني الحين: روح فوراً لشخص كبير تثق فيه وخله يكون معك. وإذا الخطر موجود الآن، خله يتصل بالطوارئ الحقيقية.",
            DogMood.SERIOUS
        )
    }

    private fun inferMood(user: String, reply: String): DogMood {
        val value = normalize("$user $reply")
        return when {
            listOf("خايف", "حزين", "زعلان", "يبكي", "مشكله").any(value::contains) -> DogMood.CALM
            listOf("ضرب", "خطر", "سلاح", "حريق", "لا تسوي", "ممنوع").any(value::contains) -> DogMood.SERIOUS
            listOf("هههه", "ضحك", "مضحك", "حلو", "ممتاز").any(value::contains) -> DogMood.SMILE
            else -> DogMood.TALKING
        }
    }

    private fun ensureModel(allowDownload: Boolean): File {
        val dir = File(appContext.filesDir, "conversation-models").apply { mkdirs() }
        val target = File(dir, MODEL_FILE_NAME)
        if (target.isFile && target.length() >= MIN_MODEL_BYTES) return target

        if (!allowDownload) {
            error("نموذج المحادثة غير مثبت. شغّل وضع الإنترنت مرة واحدة لتنزيله، وبعدها يعمل محلياً.")
        }

        val partial = File(dir, "$MODEL_FILE_NAME.part")
        if (partial.exists()) partial.delete()

        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AlShorti-Android")
        }

        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                error("تعذر تنزيل نموذج المحادثة (${connection.responseCode}).")
            }
            BufferedInputStream(connection.inputStream, 256 * 1024).use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }

        if (partial.length() < MIN_MODEL_BYTES) {
            partial.delete()
            error("اكتمل تنزيل غير صالح لنموذج المحادثة.")
        }
        if (target.exists()) target.delete()
        check(partial.renameTo(target)) { "تعذر تثبيت نموذج المحادثة المحلي." }
        return target
    }

    private fun normalize(value: String): String = value
        .trim()
        .lowercase()
        .replace('إ', 'ا')
        .replace('أ', 'ا')
        .replace('آ', 'ا')
        .replace('ة', 'ه')

    private data class Turn(val user: String, val assistant: String)

    private companion object {
        const val MAX_HISTORY_TURNS = 5
        const val MAX_REPLY_CHARS = 240
        const val MIN_NATURAL_BOUNDARY = 90
        const val MODEL_FILE_NAME = "Qwen3-0.6B-Q4_K_M.gguf"
        const val MIN_MODEL_BYTES = 430_000_000L
        const val MODEL_URL = "https://huggingface.co/bartowski/Qwen_Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf?download=true"
    }
}
