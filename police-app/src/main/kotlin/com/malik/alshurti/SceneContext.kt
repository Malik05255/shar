package com.malik.alshurti

/**
 * Conversation context used only to constrain cinematic scene selection.
 * It never generates dialogue, audio, images, video or motion.
 */
enum class ConversationDomain {
    SAFETY,
    CONFLICT,
    EMOTIONAL,
    SCHOOL,
    FAMILY,
    BEHAVIOR,
    PLAYFUL,
    GENERAL
}

enum class ExplicitSceneCue {
    NONE,
    PAPER,
    PHONE,
    DOOR,
    APPROACH
}

data class SceneContext(
    val domain: ConversationDomain = ConversationDomain.GENERAL,
    val explicitCue: ExplicitSceneCue = ExplicitSceneCue.NONE,
    val suppressMajorEvents: Boolean = false,
    val suppressApproach: Boolean = false
)

/**
 * Session-local semantic gate for the cinematic director.
 *
 * The direct cue is one-shot: e.g. if the child talks about a ringing phone, PHONE may be chosen
 * for that moment but the cue is consumed and cannot leak into later unrelated turns.
 * The broader domain remains until the next recognized utterance updates it.
 */
object SceneContextRegistry {
    @Volatile
    private var currentContext: SceneContext = SceneContext()

    @Synchronized
    fun observe(rawText: String) {
        val text = normalize(rawText)
        val domain = when {
            containsAny(
                text,
                "سكين", "سلاح", "حريق", "ينزف", "نزيف", "ما يتنفس", "اختناق",
                "خطف", "يهددني", "حادث", "ضايع"
            ) -> ConversationDomain.SAFETY
            containsAny(text, "ضربني", "يضربني", "تهاوش", "تهاوشت", "مشكله", "مشكلة") ->
                ConversationDomain.CONFLICT
            containsAny(text, "خايف", "خوف", "زعلان", "حزين", "ابكي", "بكيت") ->
                ConversationDomain.EMOTIONAL
            containsAny(text, "مدرسه", "مدرسة", "واجب", "معلم", "معلمه", "اختبار", "دفتر") ->
                ConversationDomain.SCHOOL
            containsAny(text, "ماما", "امي", "أمي", "بابا", "ابوي", "أبوي", "اخوي", "اختي") ->
                ConversationDomain.FAMILY
            containsAny(text, "كسرت", "خربت", "ارمي", "اصرخ", "كذبت", "اكذب", "شغب") ->
                ConversationDomain.BEHAVIOR
            containsAny(text, "هههه", "مضحك", "نكته", "نكتة", "لعب", "نلعب") ->
                ConversationDomain.PLAYFUL
            else -> ConversationDomain.GENERAL
        }

        val explicitCue = when {
            containsAny(text, "تلفون", "جوال", "الهاتف", "اتصال", "اتصل", "يرن", "رن الجوال") ->
                ExplicitSceneCue.PHONE
            containsAny(text, "الباب", "باب", "دخل", "يدق الباب", "احد عند الباب", "أحد عند الباب") ->
                ExplicitSceneCue.DOOR
            containsAny(text, "ملف", "تقرير", "ورقه", "ورقة", "دفتر", "واجب") ->
                ExplicitSceneCue.PAPER
            !containsAny(text, "لا تقرب", "لا تجي", "ابتعد") &&
                containsAny(text, "تعال", "قرب", "اقترب") -> ExplicitSceneCue.APPROACH
            else -> ExplicitSceneCue.NONE
        }

        val highFocus = domain in setOf(
            ConversationDomain.SAFETY,
            ConversationDomain.CONFLICT,
            ConversationDomain.EMOTIONAL
        )
        val mediumFocus = domain in setOf(
            ConversationDomain.FAMILY,
            ConversationDomain.BEHAVIOR,
            ConversationDomain.SCHOOL
        )

        currentContext = SceneContext(
            domain = domain,
            explicitCue = explicitCue,
            suppressMajorEvents = highFocus || mediumFocus,
            suppressApproach = highFocus || mediumFocus
        )
    }

    fun snapshot(): SceneContext = currentContext

    @Synchronized
    fun consumeExplicitCue(): ExplicitSceneCue {
        val cue = currentContext.explicitCue
        if (cue != ExplicitSceneCue.NONE) {
            currentContext = currentContext.copy(explicitCue = ExplicitSceneCue.NONE)
        }
        return cue
    }

    @Synchronized
    fun reset() {
        currentContext = SceneContext()
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
