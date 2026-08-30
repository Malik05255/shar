package com.malik.alshurti

import kotlinx.coroutines.delay

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
        تكلم بعربية طبيعية وواضحة وبجمل قصيرة مناسبة لعمر الطفل.
        كن هادئاً وودوداً وحازماً عند الحاجة، ولا تستخدم لغة مخيفة.
        التزم بمواضيع الطفل اليومية: السلوك، المدرسة، الوالدين، الإخوة، النوم، النظافة، السلامة، الصدق، اللعب والطعام.
        لا تهدد بالسجن، ولا تدّعي إرسال دورية أو معرفة مكان الطفل، ولا تطلب عنواناً أو رقم هاتف أو أي بيانات شخصية.
        إذا ذكر الطفل خطراً حقيقياً أو إصابة أو شخصاً يهدده، اطلب منه فوراً الذهاب لشخص بالغ موثوق، وإذا كانت حالة طارئة فليتولى البالغ الاتصال بخدمات الطوارئ الحقيقية.
        إذا خرج الحديث عن النطاق، أعده بلطف إلى موضوع مناسب للطفل.
        لا تقل إنك شرطي حقيقي؛ أنت شخصية داخل التطبيق.
    """.trimIndent()
}

/**
 * Baseline local brain used before the GGUF/Qwen adapter is bundled.
 * It keeps the app usable without a server and preserves the exact safety
 * contract that the neural brain will receive as its system prompt.
 */
class LocalPoliceBrain : PoliceBrain {
    private var previousTopic: String = ""

    override suspend fun reply(userText: String): PoliceReply {
        // Update the cinematic semantic gate from the same recognized utterance before any scene
        // can be scheduled. This layer never changes the dialogue; it only constrains later visuals.
        SceneContextRegistry.observe(userText)

        delay(70)
        val text = normalize(userText)
        if (text.isBlank()) return PoliceReply("أنا سامعك يا بطل، قل لي وش صار؟", DogMood.SMILE)

        val emergencyWords = listOf(
            "دم", "ينزف", "سكين", "سلاح", "حريق", "اختنق", "ما يتنفس",
            "خطف", "يهددني", "ضايع", "حادث"
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

    private fun normalize(value: String): String = value
        .trim()
        .lowercase()
        .replace('إ', 'ا')
        .replace('أ', 'ا')
        .replace('آ', 'ا')
        .replace('ة', 'ه')
}
