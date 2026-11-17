package org.wenji.study

import java.net.URI

/** Deliberately not a data class: credentials must never appear in generated toString/copy output. */
internal class ModelSettingsForm(
    val protocol: String = "openai_chat",
    val baseUrl: String = "",
    val model: String = "",
    val apiKey: String = "",
    val maxOutputTokens: Int = 2048,
    val hasSavedKey: Boolean = false,
) {
    override fun toString(): String = "ModelSettingsForm(credentials=redacted)"
}

internal val modelProtocols = linkedMapOf(
    "openai_chat" to "OpenAI Chat Completions",
    "openai_responses" to "OpenAI Responses",
    "anthropic_messages" to "Anthropic Messages",
    "gemini_generate_content" to "Gemini generateContent",
)

internal class ModelSettingsException(message: String) : IllegalArgumentException(message)

/** Early form feedback only. The embedded Python gateway remains the final configuration validator. */
internal fun validateModelSettings(form: ModelSettingsForm) {
    if (form.protocol !in modelProtocols) throw ModelSettingsException("请选择支持的模型协议。")
    val baseUrl = form.baseUrl.trim()
    val endpoint = try { URI(baseUrl) } catch (_: Exception) { null }
    val host = endpoint?.host?.removePrefix("[")?.removeSuffix("]")
    val local = host in setOf("localhost", "127.0.0.1", "::1")
    if (baseUrl.isEmpty() || baseUrl.length > 2048 || baseUrl.any { it.code !in 33..126 || it == '\\' } ||
        endpoint == null || host.isNullOrBlank() || endpoint.rawUserInfo != null ||
        endpoint.rawQuery != null || endpoint.rawFragment != null ||
        endpoint.scheme !in setOf("http", "https") || (endpoint.scheme == "http" && !local) ||
        (endpoint.port != -1 && endpoint.port !in 1..65535)) {
        throw ModelSettingsException("请输入 HTTPS API 基址，不含账号、查询参数或片段；HTTP 仅限手机本机。")
    }
    val model = form.model.trim()
    val hasVisibleContent = model.codePoints().anyMatch { code ->
        Character.getType(code) in setOf(
            Character.UPPERCASE_LETTER.toInt(), Character.LOWERCASE_LETTER.toInt(),
            Character.TITLECASE_LETTER.toInt(), Character.MODIFIER_LETTER.toInt(),
            Character.OTHER_LETTER.toInt(), Character.DECIMAL_DIGIT_NUMBER.toInt(),
            Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt(),
            Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(), Character.MATH_SYMBOL.toInt(),
            Character.CURRENCY_SYMBOL.toInt(), Character.MODIFIER_SYMBOL.toInt(), Character.OTHER_SYMBOL.toInt(),
        ) && code !in setOf(0x115f, 0x1160, 0x3164, 0xffa0, 0x2800)
    }
    if (model.isEmpty() || model.codePointCount(0, model.length) > 200 || !hasVisibleContent ||
        model.any { it.isWhitespace() || it.isISOControl() } ||
        model.codePoints().anyMatch { it in 0xd800..0xdfff }) {
        throw ModelSettingsException("请输入不含空白字符的模型名称（最多 200 个字符）。")
    }
    if (form.maxOutputTokens !in 1..16384) {
        throw ModelSettingsException("最大输出 Token 必须为 1 至 16384 的整数。")
    }
    // A blank entry keeps the encrypted saved key. Never trim or otherwise modify a new key.
    if (form.apiKey.isNotEmpty()) validateModelKey(form.apiKey)
}

internal fun validateModelKey(key: String) {
    if (key.isEmpty() || key.length > 8192 || key.trim() != key || key.any { it.code !in 32..126 }) {
        throw ModelSettingsException("请输入有效的 API Key；不能含换行、首尾空格或非 ASCII 字符。")
    }
}

/** Never carry a saved provider credential to a newly entered destination without explicit re-entry. */
internal fun validateSavedKeyDestination(baseUrl: String, savedBaseUrl: String) {
    if (baseUrl.trim().trimEnd('/') != savedBaseUrl.trim().trimEnd('/')) {
        throw ModelSettingsException("更换 API 地址后请重新输入密钥。")
    }
}
