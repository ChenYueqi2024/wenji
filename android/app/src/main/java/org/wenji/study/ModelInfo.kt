package org.wenji.study

/** Display-only configuration. Secret material is isolated in the encrypted phone settings store. */
internal data class ModelInfo(
    val mode: String = "unknown",
    val protocol: String = "",
    val model: String = "",
    val endpoint: String = "",
    val label: String = "",
    val fingerprint: String = "",
    val requiresConsent: Boolean = false,
) {
    val badge: String get() = when (mode) {
        "demo" -> "规则演示"
        "llm" -> "模型已配置"
        else -> "配置待同步"
    }
    val displayLabel: String get() = label.ifBlank { badge }
    val description: String get() = when (mode) {
        "demo" -> "当前使用合成教学材料中的死锁案例与固定摘录规则，未调用大模型。此模式在手机本地处理，不需要电脑或网络。"
        "llm" -> "手机已配置模型；配置不代表连接或调用已验证。每次查找依据前都会单独确认，确认后手机联网发送本次问题及检索出的课程资料片段（含来源定位），不会上传整份 PDF。"
        else -> "尚未取得可核对的服务配置。请同步后查看解释模式与数据发送范围。"
    }
    fun statusSummary(fresh: Boolean): String = if (!fresh) "缓存配置 · 待同步核验" else when (mode) {
        "demo" -> "固定案例规则演示，未调用大模型"
        "llm" -> "模型已配置，调用前需单次确认"
        else -> "解释配置不可用，请检查服务"
    }
}

internal enum class ExplainAction { DEMO, CONFIRM_REMOTE, BLOCKED }

internal fun explainAction(info: ModelInfo, fresh: Boolean): ExplainAction = when {
    !fresh -> ExplainAction.BLOCKED
    info.mode == "demo" -> ExplainAction.DEMO
    // LLM mode always requires explicit consent, even if an inconsistent server flag says otherwise.
    info.mode == "llm" && info.endpoint.isNotBlank() && info.model.isNotBlank() && info.fingerprint.isNotBlank() -> ExplainAction.CONFIRM_REMOTE
    else -> ExplainAction.BLOCKED
}

internal fun canConfirmExplanation(confirmed: ModelInfo, current: ModelInfo, fresh: Boolean): Boolean =
    explainAction(current, fresh) == ExplainAction.CONFIRM_REMOTE && confirmed == current

internal fun consentQuestionMatches(confirmedRevision: Int, currentRevision: Int?): Boolean =
    confirmedRevision > 0 && confirmedRevision == currentRevision

internal fun answerProviderLabel(provider: String): String = when (provider) {
    "deterministic-extractive-v1" -> "固定案例规则演示 · 未调用大模型"
    "", "null" -> "提供方未记录"
    else -> provider
}
