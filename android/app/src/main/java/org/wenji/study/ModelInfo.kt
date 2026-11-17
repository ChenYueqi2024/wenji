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
        "llm" -> "手机已配置模型；配置不代表连接或调用已验证。带引用的解释发送问题及检索出的课程片段（含来源定位）；通用答案与复习卡仅发送问题生成，并标注尚无课程依据。两种请求都需单独确认，不会上传整份 PDF。"
        else -> "尚未取得可核对的服务配置。请同步后查看解释模式与数据发送范围。"
    }
    fun statusSummary(fresh: Boolean): String = if (!fresh) "缓存配置 · 待同步核验" else when (mode) {
        "demo" -> "固定案例规则演示，未调用大模型"
        "llm" -> "模型已配置，调用前需单次确认"
        else -> "解释配置不可用，请检查服务"
    }
}

internal enum class ExplainAction { DEMO, CONFIRM_REMOTE, BLOCKED }

internal fun shouldShowDemoIntro(info: ModelInfo, fresh: Boolean, acknowledged: Boolean): Boolean =
    !acknowledged && fresh && info.mode == "demo"

internal enum class ModelRequestKind(val pathSuffix: String) {
    COURSE_EVIDENCE("explain"), GENERAL_REFERENCE("reference")
}

internal enum class DemoRecoveryAction { NONE, CONFIGURE_MODEL, CONFIRM_CURRENT_MODEL, REFRESH_CONFIG }

/** The stored answer's origin and today's configuration are separate facts. */
internal fun demoRecoveryAction(provider: String, answerStatus: String, current: ModelInfo, fresh: Boolean): DemoRecoveryAction {
    if (provider != "deterministic-extractive-v1" || answerStatus != "needs_material") return DemoRecoveryAction.NONE
    return when (explainAction(current, fresh)) {
        ExplainAction.DEMO -> DemoRecoveryAction.CONFIGURE_MODEL
        ExplainAction.CONFIRM_REMOTE -> DemoRecoveryAction.CONFIRM_CURRENT_MODEL
        ExplainAction.BLOCKED -> DemoRecoveryAction.REFRESH_CONFIG
    }
}

internal fun explainAction(info: ModelInfo, fresh: Boolean): ExplainAction = when {
    !fresh -> ExplainAction.BLOCKED
    info.mode == "demo" -> ExplainAction.DEMO
    // LLM mode always requires explicit consent, even if an inconsistent server flag says otherwise.
    info.mode == "llm" && info.endpoint.isNotBlank() && info.model.isNotBlank() && info.fingerprint.isNotBlank() -> ExplainAction.CONFIRM_REMOTE
    else -> ExplainAction.BLOCKED
}

internal fun modelRequestAction(info: ModelInfo, fresh: Boolean, kind: ModelRequestKind): ExplainAction {
    val action = explainAction(info, fresh)
    return if (kind == ModelRequestKind.GENERAL_REFERENCE && action == ExplainAction.DEMO) ExplainAction.BLOCKED else action
}

internal fun canConfirmExplanation(confirmed: ModelInfo, current: ModelInfo, fresh: Boolean): Boolean =
    explainAction(current, fresh) == ExplainAction.CONFIRM_REMOTE && confirmed == current

internal fun consentQuestionMatches(confirmedRevision: Int, currentRevision: Int?): Boolean =
    confirmedRevision > 0 && confirmedRevision == currentRevision

internal fun answerProviderLabel(provider: String): String = when (provider) {
    "deterministic-extractive-v1" -> "固定案例规则演示 · 未调用大模型"
    "", "null" -> "提供方未记录"
    else -> {
        // Older records include " @ endpoint". Keep endpoint details inside settings/consent.
        val details = provider.removePrefix("llm:").substringBefore(" @ ")
        if (!provider.startsWith("llm:") || details.contains("://") || details.contains('@')) "提供方未记录"
        else "模型 · ${details.replaceFirst(":", " · ")}"
    }
}

internal fun answerCallLabel(remoteCalled: Boolean?): String = when(remoteCalled) {
    true -> "本次已调用模型；结论仍需核对原文。"
    false -> "本次未调用大模型。"
    null -> "此记录未记载是否调用模型；处理方式仅表示当时配置。"
}

/** Current course cards lead; historical/stale cards remain reachable without taking priority. */
internal fun reviewDisplayPriority(basis:String,status:String):Int = when {
    status=="needs_review" -> 2
    basis=="general_reference" -> 1
    else -> 0
}
