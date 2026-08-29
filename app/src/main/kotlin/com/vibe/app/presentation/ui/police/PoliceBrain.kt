package com.vibe.app.presentation.ui.police

import kotlinx.coroutines.delay

interface PoliceBrain {
    suspend fun reply(userText: String): PoliceReply
}

data class PoliceReply(
    val text: String,
    val mood: DogMood = DogMood.TALKING
)

/**
 * System contract shared by future Qwen/llama.cpp and server-backed brains.
 * Keeping it in code makes the character rules independent from any provider.
 */
object PoliceCharacterContract {
    val systemPrompt: String = """
        أنت شخصية خيالية اسمها «الشرطي»، كلب شرطة لطيف يتحدث مع طفل.
        تكلم بعربية طبيعية وواضحة بجمل قصيرة تناسب الطفل.
        كن هادئاً وودوداً وحازماً عند الحاجة، ولا تستخدم لغة مخيفة.
        ناقش فقط: السلوك، المدرسة، الوالدين، الإخوة، النوم، النظافة، السلامة، الصدق، اللعب، والطعام.
        لا تهدد بالسجن، ولا تدّعي إرسال دورية أو معرفة مكان الطفل، ولا تطلب عنواناً أو رقم هاتف أو بيانات شخصية.
        إذا ذكر الطفل خطراً حقيقياً أو إصابة أو شخصاً يهدده، اطلب منه فوراً الذهاب لشخص بالغ موثوق، وإذا كانت حالة طارئة فليتولى البالغ الاتصال بخدمات الطوارئ الحقيقية.
        إذا طلب موضوعاً خارج النطاق، أعد الحديث بلطف إلى موضوع مناسب للطفل.
        لا تقل إنك شرطي حقيقي؛ أنت شخصية داخل التطبيق.
    """.trimIndent()
}

/**
 * Fast, completely local fallback. It is intentionally lightweight so the first
 * build remains usable while Qwen/llama.cpp is plugged into the same interface.
 */
class LocalPoliceBrain : PoliceBrain {
    private var previousTopic: String = ""

    override suspend fun reply(userText: String): PoliceReply {
        // Tiny pause keeps the animation natural without adding noticeable latency.
        delay(90)
        val text = normalize(userText)

        if (text.isBlank()) {
            return PoliceReply("أنا سامعك يا بطل، قل لي وش صار؟", DogMood.SMILE)
        }

        val emergencyWords = listOf("دم", "ينزف", "سكين", "سلاح", "حريق", "اختنق", "ما يتنفس", "خطف", "يهددني", "ضايع")
        if (emergencyWords.any(text::contains)) {
            previousTopic = "safety"
            return PoliceReply(
                "هذا مهم. روح الآن عند ماما أو بابا أو أي شخص بالغ تثق فيه وخله يساعدك. وإذا فيه خطر مباشر، خله يتصل بالطوارئ الحقيقية فوراً.",
                DogMood.SERIOUS
            )
        }

        return when {
            containsAny(text, "ضربني", "يضربني", "اخوي ضرب", "أخوي ضرب", "تهاوش", "تهاوشت") -> {
                previousTopic = "siblings"
                PoliceReply("أول شيء ابتعد عن الضرب وخلك هادي. قل لشخص كبير وش صار، وبعدها تكلموا بدون ضرب. تقدر تقول لي مين بدأ المشكلة؟", DogMood.SERIOUS)
            }

            containsAny(text, "بابا أخذ", "ابوي أخذ", "أبوي أخذ", "ماما أخذت", "امي أخذت", "أمي أخذت", "اخذ كرتي", "أخذ كرتي") -> {
                previousTopic = "parents"
                PoliceReply("أعرف إنك ممكن تزعل. اسأله بهدوء: ليش أخذتها؟ وإذا قال لك السبب اسمعه أول، وبعدها قل له وش اللي زعلك بدون صراخ.", DogMood.CALM)
            }

            containsAny(text, "ما بنام", "ما ابغى انام", "ما أبي أنام", "نوم", "أنام") -> {
                previousTopic = "sleep"
                PoliceReply("طيب يا بطل، جسمك يحتاج نوم عشان تصحى قوي ومروق. خلنا نسوي اتفاق: ترتب أغراضك، تغسل أسنانك، وبعدها سرير بدون جدال.", DogMood.SMILE)
            }

            containsAny(text, "ما بروح المدرسة", "ما ابي المدرسة", "ما أبي المدرسة", "مدرسة", "واجب", "معلم") -> {
                previousTopic = "school"
                PoliceReply("إذا في شيء مضايقك بالمدرسة قل لي عنه. بس الهروب من المدرسة ما يحل المشكلة، الأفضل نحكي لماما أو بابا ونحل السبب مع بعض.", DogMood.CALM)
            }

            containsAny(text, "كذبت", "اكذب", "أكذب", "كذب") -> {
                previousTopic = "honesty"
                PoliceReply("الشجاع مو اللي ما يغلط، الشجاع اللي يقول الصدق ويصلح غلطه. قل الحقيقة بهدوء واعتذر إذا احتجت، وأنا معك.", DogMood.SMILE)
            }

            containsAny(text, "شغب", "كسرت", "خربت", "ارمي", "أرمي", "أصرخ", "اصرخ") -> {
                previousTopic = "behavior"
                PoliceReply("نوقف هنا يا بطل. لا نكسر ولا نرمي ولا نؤذي أحد. إذا أنت معصب، ابتعد شوي وقل بالكلام وش اللي مزعلك.", DogMood.SERIOUS)
            }

            containsAny(text, "زعلان", "حزين", "ابكي", "أبكي", "خايف", "خائف") -> {
                previousTopic = "feelings"
                PoliceReply("أنا سامعك. قل لي وش اللي خلاك تحس كذا؟ وإذا الشيء يخوفك فعلاً، خلك قريب من شخص كبير تثق فيه.", DogMood.CALM)
            }

            containsAny(text, "هههه", "مضحك", "نكتة", "ضحك") -> {
                previousTopic = "play"
                PoliceReply("هههه، واضح إنك اليوم جاي تضحكني! بس لا تنسى: الشرطي يحب المزح المؤدب اللي ما يزعل أحد.", DogMood.SMILE)
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

    private fun containsAny(text: String, vararg terms: String): Boolean = terms.any { text.contains(it) }

    private fun normalize(value: String): String = value
        .trim()
        .lowercase()
        .replace('إ', 'ا')
        .replace('أ', 'ا')
        .replace('آ', 'ا')
}
