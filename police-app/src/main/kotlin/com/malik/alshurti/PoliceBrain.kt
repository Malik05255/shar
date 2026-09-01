package com.malik.alshurti

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque

interface PoliceBrain {
    suspend fun reply(userText: String): PoliceReply
}

data class PoliceReply(
    val text: String,
    val mood: DogMood = DogMood.TALKING
)

object PoliceCharacterContract {
    val systemPrompt: String = """
        أنت شخصية خيالية اسمها «الشرطي»، كلب شرطة لطيف يتحدث مع طفل.
        تكلم بعربية سعودية طبيعية وواضحة وبجمل قصيرة مناسبة لعمر الطفل.
        كن هادئاً وودوداً وحازماً عند الحاجة، ولا تستخدم لغة مخيفة أو رسمية بشكل مبالغ.
        التزم بمواضيع الطفل اليومية: السلوك، المدرسة، الوالدين، الإخوة، النوم، النظافة، السلامة، الصدق، اللعب والطعام.
        لا تهدد بالسجن، ولا تدّعي إرسال دورية أو معرفة مكان الطفل، ولا تطلب عنواناً أو رقم هاتف أو أي بيانات شخصية.
        إذا ذكر الطفل خطراً حقيقياً أو إصابة أو شخصاً يهدده، اطلب منه فوراً الذهاب لشخص بالغ موثوق، وإذا كانت حالة طارئة فليتولى البالغ الاتصال بخدمات الطوارئ الحقيقية.
        إذا خرج الحديث عن النطاق، أعده بلطف إلى موضوع مناسب للطفل.
        لا تقل إنك شرطي حقيقي؛ أنت شخصية داخل التطبيق.
        تذكر سياق الحديث القريب ولا تكرر نفس الجملة بلا داعٍ. اسأل سؤال متابعة واحداً فقط عندما يفيد استمرار الحوار.
        اجعل الرد في الغالب من جملة إلى ثلاث جمل قصيرة.
    """.trimIndent()
}

/** Gemini-first production brain with deterministic local failover. */
class HybridPoliceBrain(
    private val cloud: PoliceBrain = GeminiPoliceBrain(),
    private val fallback: PoliceBrain = DeterministicPoliceBrain()
) : PoliceBrain {
    override suspend fun reply(userText: String): PoliceReply {
        val normalized = userText.trim()
        SceneContextRegistry.observe(normalized)
        urgentSafetyReply(normalized)?.let { return it }
        return runCatching { cloud.reply(normalized) }
            .getOrElse { fallback.reply(normalized) }
    }

    private fun urgentSafetyReply(text: String): PoliceReply? {
        val normalized = normalizeArabic(text)
        val emergencyWords = listOf(
            "دم", "ينزف", "نزيف", "سكين", "سلاح", "حريق", "اختنق", "ما يتنفس",
            "خطف", "يهددني", "تهديد", "ضايع", "حادث", "اصابه", "مصاب"
        )
        if (emergencyWords.none(normalized::contains)) return null
        return PoliceReply(
            "هذا مهم. روح الآن عند ماما أو بابا أو أي شخص بالغ تثق فيه وخله يساعدك. وإذا فيه خطر مباشر، خله يتصل بالطوارئ الحقيقية فوراً.",
            DogMood.SERIOUS
        )
    }
}

/**
 * Compatibility entry point used by the existing ViewModel. It is no longer a scripted brain:
 * every normal online turn routes through HybridPoliceBrain.
 */
class LocalPoliceBrain : PoliceBrain {
    private val delegate = HybridPoliceBrain()
    override suspend fun reply(userText: String): PoliceReply = delegate.reply(userText)
}

class GeminiPoliceBrain(
    private val apiKey: String = BuildConfig.GEMINI_API_KEY.trim(),
    private val model: String = MODEL
) : PoliceBrain {
    private data class Exchange(val child: String, val police: String)

    private val history = ArrayDeque<Exchange>()

    override suspend fun reply(userText: String): PoliceReply = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Gemini conversation is not configured." }
        val text = userText.trim()
        require(text.isNotBlank()) { "Empty utterance." }

        val historySnapshot = synchronized(history) { history.toList() }
        val connection = (URL("$BASE_URL/$model:generateContent").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("x-goog-api-key", apiKey)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "AlShorti-Conversation/${BuildConfig.VERSION_NAME}")
        }

        try {
            val request = JSONObject()
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", buildPrompt(historySnapshot, text))))
                    )
                )
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("maxOutputTokens", MAX_OUTPUT_TOKENS)
                        .put("responseMimeType", "application/json")
                )

            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(request.toString()) }
            val status = connection.responseCode
            val body = if (status in 200..299) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            }
            check(status in 200..299) { "Gemini conversation HTTP $status" }

            val reply = parseReply(body)
            synchronized(history) {
                history.addLast(Exchange(text.take(MAX_HISTORY_TEXT_CHARS), reply.text.take(MAX_HISTORY_TEXT_CHARS)))
                while (history.size > MAX_HISTORY_TURNS) history.removeFirst()
            }
            reply
        } finally {
            connection.disconnect()
        }
    }

    private fun buildPrompt(historySnapshot: List<Exchange>, current: String): String = buildString {
        appendLine(PoliceCharacterContract.systemPrompt)
        appendLine()
        appendLine("أخرج JSON فقط بهذا الشكل:")
        appendLine("{\"text\":\"الرد العربي\",\"mood\":\"CALM|SMILE|SERIOUS|TALKING\"}")
        appendLine("لا تضف أي مفاتيح أخرى. لا تكتب Markdown.")
        if (historySnapshot.isNotEmpty()) {
            appendLine()
            appendLine("السياق القريب:")
            historySnapshot.forEach { exchange ->
                appendLine("الطفل: ${exchange.child}")
                appendLine("الشرطي: ${exchange.police}")
            }
        }
        appendLine()
        appendLine("رسالة الطفل الحالية:")
        append(current.take(MAX_INPUT_CHARS))
    }

    private fun parseReply(response: String): PoliceReply {
        val root = JSONObject(response)
        val candidates = root.optJSONArray("candidates") ?: error("Gemini returned no candidates.")
        val parts = candidates.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: error("Gemini returned no content.")

        val jsonText = buildString {
            for (index in 0 until parts.length()) {
                parts.optJSONObject(index)?.optString("text")
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::append)
            }
        }.trim()
        check(jsonText.isNotBlank()) { "Gemini returned an empty response." }

        val payload = JSONObject(jsonText)
        val spoken = payload.optString("text").replace(Regex("\\s+"), " ").trim()
        check(spoken.isNotBlank()) { "Gemini returned empty dialogue." }
        val mood = when (payload.optString("mood").trim().uppercase()) {
            "CALM" -> DogMood.CALM
            "SMILE" -> DogMood.SMILE
            "SERIOUS" -> DogMood.SERIOUS
            else -> DogMood.TALKING
        }
        return PoliceReply(spoken.take(MAX_REPLY_CHARS), mood)
    }

    private companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        const val MODEL = "gemini-3.5-flash-lite"
        const val CONNECT_TIMEOUT_MS = 6_000
        const val READ_TIMEOUT_MS = 14_000
        const val MAX_OUTPUT_TOKENS = 220
        const val MAX_HISTORY_TURNS = 6
        const val MAX_HISTORY_TEXT_CHARS = 360
        const val MAX_INPUT_CHARS = 800
        const val MAX_REPLY_CHARS = 480
    }
}

/** Deterministic offline-only safety/failure fallback. */
class DeterministicPoliceBrain : PoliceBrain {
    private var previousTopic: String = ""

    override suspend fun reply(userText: String): PoliceReply {
        delay(30)
        val text = normalizeArabic(userText)
        if (text.isBlank()) return PoliceReply("أنا سامعك يا بطل، قل لي وش صار؟", DogMood.SMILE)

        val emergencyWords = listOf(
            "دم", "ينزف", "نزيف", "سكين", "سلاح", "حريق", "اختنق", "ما يتنفس",
            "خطف", "يهددني", "تهديد", "ضايع", "حادث", "اصابه", "مصاب"
        )
        if (emergencyWords.any(text::contains)) {
            previousTopic = "safety"
            return PoliceReply(
                "هذا مهم. روح الآن عند ماما أو بابا أو أي شخص بالغ تثق فيه وخله يساعدك. وإذا فيه خطر مباشر، خله يتصل بالطوارئ الحقيقية فوراً.",
                DogMood.SERIOUS
            )
        }

        return when {
            containsAny(text, "ضربني", "يضربني", "اخوي ضرب", "تهاوش", "تهاوشت") -> {
                previousTopic = "siblings"
                PoliceReply("أول شيء ابتعد عن الضرب وخلك هادي. قل لشخص كبير وش صار، وبعدها تكلموا بدون ضرب. تقدر تقول لي مين بدأ المشكلة؟", DogMood.SERIOUS)
            }
            containsAny(text, "بابا اخذ", "ابوي اخذ", "ماما اخذت", "امي اخذت", "اخذ كرتي", "اخذ لعبتي") -> {
                previousTopic = "parents"
                PoliceReply("أعرف إنك ممكن تزعل. اسأله بهدوء ليش أخذها، واسمع السبب أول. وبعدها قل له وش اللي زعلك بدون صراخ.", DogMood.CALM)
            }
            containsAny(text, "ما بنام", "ما ابغى انام", "ما ابي انام", "نوم", "انام") -> {
                previousTopic = "sleep"
                PoliceReply("طيب يا بطل، جسمك يحتاج نوم عشان تصحى قوي ومروق. خلنا نسوي اتفاق: ترتب أغراضك، تغسل أسنانك، وبعدها سرير بدون جدال.", DogMood.SMILE)
            }
            containsAny(text, "ما بروح المدرسه", "ما ابي المدرسه", "مدرسه", "واجب", "معلم") -> {
                previousTopic = "school"
                PoliceReply("إذا في شيء مضايقك بالمدرسة قل لي عنه. الهروب ما يحل المشكلة، الأفضل نحكي لماما أو بابا ونحل السبب مع بعض.", DogMood.CALM)
            }
            containsAny(text, "كذبت", "اكذب", "كذب") -> {
                previousTopic = "honesty"
                PoliceReply("الشجاع مو اللي ما يغلط؛ الشجاع اللي يقول الصدق ويصلح غلطه. قل الحقيقة بهدوء واعتذر إذا احتجت.", DogMood.SMILE)
            }
            containsAny(text, "شغب", "كسرت", "خربت", "ارمي", "اصرخ") -> {
                previousTopic = "behavior"
                PoliceReply("نوقف هنا يا بطل. لا نكسر ولا نرمي ولا نؤذي أحد. إذا أنت معصب، ابتعد شوي وقل بالكلام وش اللي مزعلك.", DogMood.SERIOUS)
            }
            containsAny(text, "زعلان", "حزين", "ابكي", "خايف") -> {
                previousTopic = "feelings"
                PoliceReply("أنا سامعك. قل لي وش اللي خلاك تحس كذا؟ وإذا الشيء يخوفك فعلاً، خلك قريب من شخص كبير تثق فيه.", DogMood.CALM)
            }
            containsAny(text, "هههه", "مضحك", "نكته", "ضحك") -> {
                previousTopic = "play"
                PoliceReply("هههه، واضح إنك اليوم جاي تضحكني! بس الشرطي يحب المزح المؤدب اللي ما يزعل أحد.", DogMood.SMILE)
            }
            previousTopic == "parents" -> PoliceReply("طيب، وش قال لك بابا أو ماما لما سألتهم بهدوء؟", DogMood.CALM)
            previousTopic == "siblings" -> PoliceReply("المهم ما نرجع للضرب. تقدر تقول لأخوك: أنا زعلت من اللي صار، خلنا نتفاهم.", DogMood.CALM)
            previousTopic == "school" -> PoliceReply("قل لي بالضبط وش أكثر شيء مضايقك في المدرسة، ونفكر بحل بسيط.", DogMood.CALM)
            else -> {
                previousTopic = "general"
                PoliceReply("تمام يا بطل، فهمتك. احكِ لي أكثر: وش صار بالضبط؟", DogMood.SMILE)
            }
        }
    }

    private fun containsAny(text: String, vararg terms: String): Boolean = terms.any(text::contains)
}

private fun normalizeArabic(value: String): String = value
    .trim()
    .lowercase()
    .replace('إ', 'ا')
    .replace('أ', 'ا')
    .replace('آ', 'ا')
    .replace('ة', 'ه')
