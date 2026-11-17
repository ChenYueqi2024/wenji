package org.wenji.study

import java.util.Locale

internal enum class ModelCapabilityMatchKind { OFFICIAL_ID, VERSION_CANDIDATE, ALIAS_CANDIDATE }

/**
 * Offline, display-only vendor specifications checked on the date below.
 * These are not measurements of the configured endpoint, nor token budgets for
 * this app. Never use the catalogue to change credentials, routing or settings.
 */
internal data class ModelCapabilities(
    val modelId: String,
    val provider: String,
    val contextTokens: Int,
    val maxOutputTokens: Int,
    val imageInput: Boolean,
    val sourceUrl: String,
    val verifiedDate: String = "2026-11-17",
    val contextLabel: String = "上下文窗口",
    val notes: String = "",
    val matchKind: ModelCapabilityMatchKind = ModelCapabilityMatchKind.OFFICIAL_ID,
)

private val gpt41 = ModelCapabilities(
    "gpt-4.1", "OpenAI", 1_047_576, 32_768, true,
    "https://developers.openai.com/api/docs/models/gpt-4.1",
    notes = "官方已标记弃用；此处保留规格参考，不保证仍可调用。",
)
private val gpt4o = ModelCapabilities(
    "gpt-4o", "OpenAI", 128_000, 16_384, true,
    "https://developers.openai.com/api/docs/models/gpt-4o",
    notes = "官方已标记弃用；此处保留规格参考，不保证仍可调用。",
)
private val gemini35FlashLite = ModelCapabilities(
    "gemini-3.5-flash-lite", "Google", 1_048_576, 65_536, true,
    "https://aistudio.google.com/docs/models/gemini-3.5-flash-lite",
    contextLabel = "输入上限",
    notes = "官方同时列出音频、视频和 PDF 输入；这里的数值是输入上限，并非本应用已启用的输入能力。",
)
private val gemini25Flash = ModelCapabilities(
    "gemini-2.5-flash", "Google", 1_048_576, 65_536, true,
    "https://aistudio.google.com/docs/models/gemini-2.5-flash",
    contextLabel = "输入上限",
    notes = "官方已标记弃用，并公告 2026-12-01 起请求改路由至 3.5 Flash-Lite；本表不会自动跟随别名更新。官方另支持音频、视频输入。",
)
private val claudeSonnet55 = ModelCapabilities(
    "claude-sonnet-5-5", "Anthropic", 1_000_000, 128_000, true,
    "https://platform.claude.com/docs/en/models/sonnet-5-5/overview",
    notes = "官方标称 1M 上下文与 128K 最大输出；未采用需额外条件的批处理 beta 上限。",
)
private val deepseekFlash = ModelCapabilities(
    "deepseek-flash", "DeepSeek", 1_048_576, 393_216, true,
    "https://api-docs.deepseek.com/api/list-models/",
    notes = "官方服务当前对应 DeepSeek-V4.1-Flash-1017；第三方服务的同名映射和实际限额须另行核对。",
)
private val deepseekPro = ModelCapabilities(
    "deepseek-v4-pro", "DeepSeek", 1_048_576, 393_216, false,
    "https://api-docs.deepseek.com/api/list-models/",
    notes = "官方模型列表声明仅文本输入；第三方服务实际配置须另行核对。",
)

// Only explicitly documented API IDs and individually checked snapshots.
// Do not infer from prefixes, family resemblance, deployment paths or dates.
private val capabilityCatalogue = mapOf(
    "gpt-4.1" to gpt41,
    "gpt-4.1-2025-04-14" to gpt41,
    "gpt-4o" to gpt4o,
    "gpt-4o-2024-08-06" to gpt4o,
    "gpt-4o-2024-11-20" to gpt4o,
    "gemini-3.5-flash-lite" to gemini35FlashLite,
    "gemini-2.5-flash" to gemini25Flash,
    "claude-sonnet-5-5" to claudeSonnet55,
    "deepseek-flash" to deepseekFlash,
    "deepseek-v4-pro" to deepseekPro,
)

private data class AliasTarget(val identifier: String, val capabilities: ModelCapabilities)

// Match complete documented identifiers separated into tokens. A vendor or
// deployment prefix/suffix is allowed, but a partial family name is never enough.
private fun capabilityNameTokens(name: String): List<String> =
    name.lowercase(Locale.ROOT).split(Regex("[\\s_./:@-]+")).filter(String::isNotEmpty)

private val aliasTargets = (capabilityCatalogue.map { (id, capability) -> AliasTarget(id, capability) } +
    // This full display name is explicitly present in DeepSeek's model metadata.
    AliasTarget("DeepSeek-V4.1-Flash", deepseekFlash) +
    AliasTarget("DeepSeek-V4.1-Flash-1017", deepseekFlash))
    .sortedByDescending { capabilityNameTokens(it.identifier).size }

// An extra variant token cannot inherit a base model's capabilities. These
// tokens inside the selected complete official identifier are already verified.
private val unverifiedVariantTokens = setOf(
    "mini", "nano", "pro", "flash", "lite", "sonnet", "haiku", "opus", "fable",
    "vision", "audio", "realtime", "reasoner", "reasoning", "thinking", "nonthinking",
    "distill", "distilled", "instruct", "coder", "chat", "fast", "turbo",
)

private fun aliasCapabilities(name: String): ModelCapabilities? {
    // A model-ID hint is not a URL parser. Avoid matching credentials, URLs or
    // arbitrary pasted configuration as though they were an API deployment ID.
    if (name.length > 200 || name.contains("://") || name.any { it.isISOControl() }) return null
    val tokens = capabilityNameTokens(name)
    data class Match(val target: AliasTarget, val start: Int, val size: Int)
    val matches = aliasTargets.mapNotNull { target ->
        val wanted = capabilityNameTokens(target.identifier)
        if (tokens.size < wanted.size) return@mapNotNull null
        val start = (0..tokens.size - wanted.size).firstOrNull { at ->
            tokens.subList(at, at + wanted.size) == wanted
        } ?: return@mapNotNull null
        Match(target, start, wanted.size)
    }
    // Two different model identities are ambiguous, even if one name is longer.
    if (matches.map { it.target.capabilities.modelId }.distinct().size != 1) return null
    val match = matches.firstOrNull() ?: return null // Longest complete ID wins.
    val outside = tokens.take(match.start) + tokens.drop(match.start + match.size)
    if (outside.any { it in unverifiedVariantTokens }) return null
    return match.target.capabilities.copy(
        matchKind = ModelCapabilityMatchKind.ALIAS_CANDIDATE,
        notes = "名称包含官方标识 ${match.target.identifier}，仅推荐其参数作近似参考。第三方别名的实际上下文、输出上限和图像能力未核验，不自动更改参数。" +
            match.target.capabilities.notes,
    )
}

internal fun modelCapabilities(modelName: String): ModelCapabilities? {
    val name = modelName.trim()
    capabilityCatalogue[name]?.let { return it }
    // A documented version name is not proof that a third-party API ID is
    // mapped to that model. Return a separate candidate kind, never OFFICIAL_ID.
    if (name.lowercase(Locale.ROOT) == "deepseek-v4.1-flash-1017") {
        return deepseekFlash.copy(
            sourceUrl = "https://api-docs.deepseek.com/quick_start/pricing/",
            matchKind = ModelCapabilityMatchKind.VERSION_CANDIDATE,
            notes = "仅匹配官方版本名称；第三方别名的实际部署上限与模态未核验，不自动更改参数。官方 API 名称是 deepseek-flash。",
        )
    }
    return aliasCapabilities(name)
}
