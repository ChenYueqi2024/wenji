package org.wenji.study

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Plaintext exists only in process memory when configuring the local engine. Never log this object. */
internal class StandaloneProfile(val configJson: String?, val secretsJson: String = "{}") {
    override fun toString(): String = "StandaloneProfile(credentials=redacted)"
}

/** App-private preferences contain public config and AES/GCM ciphertext; the key stays in Android Keystore. */
internal class StandaloneSettings(
    context: Context,
    storageName: String = "phone_model_settings_v1",
    private val keyAlias: String = "wenji_phone_model_aes_v1",
) {
    init {
        require(storageName.matches(Regex("[A-Za-z0-9_-]+"))) { "模型设置存储名称无效" }
        require(keyAlias.matches(Regex("[A-Za-z0-9_-]+"))) { "模型设置密钥名称无效" }
    }
    private val preferences = context.applicationContext.getSharedPreferences(storageName, Context.MODE_PRIVATE)

    @Synchronized
    fun load(): StandaloneProfile = secureOperation {
        val config = preferences.getString(CONFIG, null)
        val ciphertext = preferences.getString(CIPHERTEXT, null)
        val iv = preferences.getString(IV, null)
        if (config == null && ciphertext == null && iv == null) return@secureOperation StandaloneProfile(null)
        if (config == null || ciphertext == null || iv == null) throw IllegalStateException()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, readKey() ?: throw IllegalStateException(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        cipher.updateAAD(config.toByteArray(Charsets.UTF_8))
        val plaintext = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
        try {
            val secrets = plaintext.toString(Charsets.UTF_8)
            validateModelKey(JSONObject(secrets).getString(KEY_ENV))
            StandaloneProfile(config, secrets)
        } finally { plaintext.fill(0) }
    }

    /** UI state never includes the saved credential, including when the dialog is reopened. */
    @Synchronized
    fun loadForm(): ModelSettingsForm = secureOperation {
        val profile = load()
        val config = profile.configJson?.let(::JSONObject) ?: return@secureOperation ModelSettingsForm()
        ModelSettingsForm(
            protocol = config.getString("protocol"), baseUrl = config.getString("base_url"),
            model = config.getString("model"),
            maxOutputTokens = config.getJSONObject("generation").getInt("max_output_tokens"),
            hasSavedKey = true,
        ).also(::validateModelSettings)
    }

    /** Does not persist. Caller must also ask the embedded gateway to validate/configure before save. */
    @Synchronized
    fun prepare(form: ModelSettingsForm): StandaloneProfile {
        validateModelSettings(form)
        val key = if (form.apiKey.isNotEmpty()) form.apiKey else secureOperation {
            val previous = load()
            if (previous.configJson != null) {
                validateSavedKeyDestination(form.baseUrl, JSONObject(previous.configJson).getString("base_url"))
            }
            JSONObject(previous.secretsJson).optString(KEY_ENV, "")
        }
        validateModelKey(key)
        val config = JSONObject()
            .put("protocol", form.protocol)
            .put("base_url", form.baseUrl.trim().trimEnd('/'))
            .put("model", form.model.trim())
            .put("api_key_env", KEY_ENV)
            .put("timeout_seconds", 60)
            .put("generation", JSONObject().put("max_output_tokens", form.maxOutputTokens))
        return StandaloneProfile(config.toString(), JSONObject().put(KEY_ENV, key).toString())
    }

    /** Encrypt before touching preferences. Failed encryption/commit is never reported as success. */
    @Synchronized
    fun save(profile: StandaloneProfile): Unit = secureOperation {
        val config = profile.configJson ?: return@secureOperation clear()
        validateModelKey(JSONObject(profile.secretsJson).getString(KEY_ENV))
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, readKey() ?: createKey())
        cipher.updateAAD(config.toByteArray(Charsets.UTF_8))
        val plaintext = profile.secretsJson.toByteArray(Charsets.UTF_8)
        val ciphertext = try { cipher.doFinal(plaintext) } finally { plaintext.fill(0) }
        commit(mapOf(
            CONFIG to config,
            CIPHERTEXT to Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            IV to Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
        ))
    }

    /** Explicit demo selection removes the saved profile and encrypted API key together. */
    @Synchronized
    fun clear(): Unit = secureOperation { commit(emptyMap()) }

    private fun commit(values: Map<String, String>) {
        val previous = listOf(CONFIG, CIPHERTEXT, IV).associateWith { preferences.getString(it, null) }
        try {
            val edit = preferences.edit().clear()
            values.forEach { (name, value) -> edit.putString(name, value) }
            if (!edit.commit()) throw IllegalStateException()
        } catch (_: Exception) {
            // SharedPreferences changes memory even if disk commit fails. Restore both before surfacing failure.
            val restore = preferences.edit().clear()
            previous.forEach { (name, value) -> if (value != null) restore.putString(name, value) }
            try { restore.commit() } catch (_: Exception) { /* The caller still receives a failure. */ }
            throw ModelSettingsException("模型设置未能保存，请重试；当前操作未报告成功。")
        }
    }

    private fun readKey(): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(keyAlias, null) as? SecretKey
    }

    private fun createKey(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
        init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        generateKey()
    }

    private inline fun <T> secureOperation(action: () -> T): T = try { action() }
    catch (error: ModelSettingsException) { throw error }
    catch (_: Exception) { throw ModelSettingsException("无法读取或保存加密模型设置，请重试或清除设置后重新配置。") }

    private companion object {
        const val KEY_ENV = "WENJI_PHONE_KEY"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val CONFIG = "config"
        const val CIPHERTEXT = "encrypted_secrets"
        const val IV = "encryption_iv"
    }
}
