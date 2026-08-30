package com.malik.alshurti

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.codeshipping.llamakotlin.LlamaModel
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class QwenPoliceBrain(context: Context) : PoliceBrain {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val modelLoadMutex = Mutex()
    private val historyLock = Any()
    private val history = ArrayDeque<Turn>()

    @Volatile private var loadedModel: LlamaModel? = null
    @Volatile private var allowNetworkDownloads = false

    override fun prepare(allowDownload: Boolean) {
        allowNetworkDownloads = allowDownload
        if (loadedModel != null) return
        scope.launch { runCatching { modelOrLoad() } }
    }

    override suspend fun reply(userText: String): PoliceReply {
        val cleanUser = userText.trim()
        require(cleanUser.isNotBlank()) { "ما سمعت كلاماً واضحاً." }

        emergencyReply(cleanUser)?.let { return it }

        return withContext(Dispatchers.IO) {
            val model = modelOrLoad()
            val generated = sanitize(model.generate(buildPrompt(cleanUser)))
            check(generated.isNotBlank()) { "تعذر تكوين رد واضح." }

            synchronized(historyLock) {
                history.addLast(Turn(cleanUser, generated))
                while (history.size > MAX_HISTORY_TURNS) history.removeFirst()
            }

            PoliceReply(generated, inferMood(cleanUser, generated))
        }
    }

    override fun release() {
        scope.cancel()
        val model = loadedModel
        loadedModel = null
        runCatching { model?.cancelGeneration() }
        runCatching { model?.close() }
    }

    private suspend fun modelOrLoad(): LlamaModel {
        loadedModel?.let { return it }

        modelLoadMutex.lock()
        try {
            loadedModel?.let { return it }

            val modelFile = ensureModel(allowNetworkDownloads)
            val cpuCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
            val model = LlamaModel.load(modelFile.absolutePath) {
                contextSize = 1792
                batchSize = 192
                threads = (cpuCount - 1).coerceIn(2, 6)
                temperature = 0.58f
                topP = 0.88f
                topK = 30
                repeatPenalty = 1.08f
                maxTokens = 72
                useMmap = true
                useMlock = false
                gpuLayers = 0
                seed = -1
            }
            loadedModel = model
            return model
        } finally {
            modelLoadMutex.unlock()
        }
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
            .replace(Regex("(?i)^assistant\\s*[:：-]?\\s*"), "")
            .replace(Regex("[*_#`]+"), "")
            .replace(Regex("\\s+([،؟!.])"), "$1")
            .replace(Regex("\\s+"), " ")
            .trim()

        val arabicFirst = cleaned
            .replace(Regex("\\b[A-Za-z][A-Za-z0-9_-]*\\b"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (arabicFirst.length <= MAX_REPLY_CHARS) return arabicFirst

        val window = arabicFirst.take(MAX_REPLY_CHARS)
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
            "روح الحين لشخص كبير تثق فيه وخله يكون معك. وإذا الخطر موجود الآن، خله يتصل بالطوارئ الحقيقية.",
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
            error("عقل المحادثة غير مثبت. اختر وضع الإنترنت مرة واحدة لتجهيزه.")
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
                error("تعذر تنزيل عقل المحادثة (${connection.responseCode}).")
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
            error("تنزيل عقل المحادثة لم يكتمل.")
        }
        if (target.exists()) target.delete()
        check(partial.renameTo(target)) { "تعذر تثبيت عقل المحادثة." }
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
        const val MAX_HISTORY_TURNS = 4
        const val MAX_REPLY_CHARS = 175
        const val MIN_NATURAL_BOUNDARY = 55
        const val MODEL_FILE_NAME = "Qwen3-0.6B-Q4_K_M.gguf"
        const val MIN_MODEL_BYTES = 430_000_000L
        const val MODEL_URL = "https://huggingface.co/bartowski/Qwen_Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf?download=true"
    }
}
