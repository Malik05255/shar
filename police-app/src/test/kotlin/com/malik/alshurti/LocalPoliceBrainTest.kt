package com.malik.alshurti

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoliceConversationContractTest {
    @Test
    fun promptRequiresNaturalSaudiConversationInsteadOfCannedReplies() {
        val prompt = PoliceCharacterContract.systemPrompt
        assertTrue(prompt.contains("اللهجة السعودية"))
        assertTrue(prompt.contains("مكالمة حقيقية"))
        assertTrue(prompt.contains("لا تبدأ كل رد"))
        assertTrue(prompt.contains("سياق الكلام السابق"))
    }

    @Test
    fun promptKeepsChildSafetyBoundaries() {
        val prompt = PoliceCharacterContract.systemPrompt
        assertTrue(prompt.contains("لا تهدد بالسجن"))
        assertTrue(prompt.contains("لا تطلب عنواناً"))
        assertTrue(prompt.contains("شخص بالغ موثوق"))
        assertTrue(prompt.contains("الطوارئ الحقيقية"))
    }

    @Test
    fun promptDoesNotPretendToBeRealPolice() {
        val prompt = PoliceCharacterContract.systemPrompt
        assertTrue(prompt.contains("شخصية خيالية"))
        assertFalse(prompt.contains("سأرسل دورية"))
    }
}
