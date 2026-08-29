package com.almi.ai.data.preferences

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class GoogleAiStudioStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<GoogleAiStudioSettings> = _settings.asStateFlow()

    fun saveApiKey(value: String) {
        val normalized = value.trim()
        preferences.edit().apply {
            if (normalized.isBlank()) remove(KEY_API_KEY)
            else putString(KEY_API_KEY, encrypt(normalized))
        }.apply()
        _settings.value = read()
    }

    fun selectFreeModel(modelId: String) {
        preferences.edit().putString(KEY_FREE_MODEL, modelId.trim()).apply()
        _settings.value = read()
    }

    fun selectPaidModel(modelId: String) {
        preferences.edit().putString(KEY_PAID_MODEL, modelId.trim()).apply()
        _settings.value = read()
    }

    fun selectTextModel(modelId: String, paid: Boolean) {
        preferences.edit()
            .putString(KEY_TEXT_MODEL, modelId.trim())
            .putBoolean(KEY_TEXT_PAID, paid)
            .putBoolean(KEY_ACTIVE, true)
            .apply()
        _settings.value = read()
    }

    fun selectImageModel(modelId: String, paid: Boolean) {
        preferences.edit()
            .putString(KEY_IMAGE_MODEL, modelId.trim())
            .putBoolean(KEY_IMAGE_PAID, paid)
            .putBoolean(KEY_ACTIVE, true)
            .apply()
        _settings.value = read()
    }

    fun selectVideoModel(modelId: String, paid: Boolean) {
        preferences.edit()
            .putString(KEY_VIDEO_MODEL, modelId.trim())
            .putBoolean(KEY_VIDEO_PAID, paid)
            .putBoolean(KEY_ACTIVE, true)
            .apply()
        _settings.value = read()
    }

    fun setActive(active: Boolean) {
        preferences.edit().putBoolean(KEY_ACTIVE, active).apply()
        _settings.value = read()
    }

    fun setConnected(connected: Boolean) {
        preferences.edit().putBoolean(KEY_CONNECTED, connected).apply()
        _settings.value = read()
    }

    fun clearConnection() {
        preferences.edit()
            .remove(KEY_API_KEY)
            .putBoolean(KEY_CONNECTED, false)
            .putBoolean(KEY_ACTIVE, false)
            .apply()
        _settings.value = read()
    }

    fun apiKey(): String = decryptStoredKey()

    private fun read(): GoogleAiStudioSettings = GoogleAiStudioSettings(
        hasApiKey = decryptStoredKey().isNotBlank(),
        connected = preferences.getBoolean(KEY_CONNECTED, false) && decryptStoredKey().isNotBlank(),
        active = preferences.getBoolean(KEY_ACTIVE, false) && decryptStoredKey().isNotBlank(),
        freeModelId = preferences.getString(KEY_FREE_MODEL, "").orEmpty(),
        paidModelId = preferences.getString(KEY_PAID_MODEL, "").orEmpty(),
        textModelId = preferences.getString(KEY_TEXT_MODEL, "").orEmpty(),
        imageModelId = preferences.getString(KEY_IMAGE_MODEL, "").orEmpty(),
        videoModelId = preferences.getString(KEY_VIDEO_MODEL, "").orEmpty(),
        textPaid = preferences.getBoolean(KEY_TEXT_PAID, false),
        imagePaid = preferences.getBoolean(KEY_IMAGE_PAID, true),
        videoPaid = preferences.getBoolean(KEY_VIDEO_PAID, true),
    )

    private fun decryptStoredKey(): String {
        val encrypted = preferences.getString(KEY_API_KEY, null) ?: return ""
        return runCatching { decrypt(encrypted) }.getOrDefault("")
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val parts = value.split('.', limit = 2)
        require(parts.size == 2)
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFS = "almi_google_ai_studio"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_CONNECTED = "connected"
        private const val KEY_ACTIVE = "active"
        private const val KEY_FREE_MODEL = "free_model"
        private const val KEY_PAID_MODEL = "paid_model"
        private const val KEY_TEXT_MODEL = "text_model"
        private const val KEY_IMAGE_MODEL = "image_model"
        private const val KEY_VIDEO_MODEL = "video_model"
        private const val KEY_TEXT_PAID = "text_paid"
        private const val KEY_IMAGE_PAID = "image_paid"
        private const val KEY_VIDEO_PAID = "video_paid"
        private const val KEY_ALIAS = "almi_google_ai_studio_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

data class GoogleAiStudioSettings(
    val hasApiKey: Boolean = false,
    val connected: Boolean = false,
    val active: Boolean = false,
    val freeModelId: String = "",
    val paidModelId: String = "",
    val textModelId: String = "",
    val imageModelId: String = "",
    val videoModelId: String = "",
    val textPaid: Boolean = false,
    val imagePaid: Boolean = true,
    val videoPaid: Boolean = true,
)
