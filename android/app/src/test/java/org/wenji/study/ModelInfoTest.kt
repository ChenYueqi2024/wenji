package org.wenji.study

import org.junit.Assert.*
import org.junit.Test

class ModelInfoTest {
    private val remote = ModelInfo("llm", "openai-compatible", "course-model", "https://models.example/v1", "课程模型", "configuration-a", true)

    @Test fun cachedConfigurationCannotStartOrConfirmExplanation() {
        assertEquals(ExplainAction.BLOCKED, explainAction(remote, false))
        assertEquals(ExplainAction.BLOCKED, explainAction(ModelInfo(mode = "demo"), false))
        assertFalse(canConfirmExplanation(remote, remote, false))
        assertTrue(remote.statusSummary(false).contains("缓存"))
    }

    @Test fun everyLlmRequestNeedsConsentEvenWithInconsistentFlag() {
        assertEquals(ExplainAction.CONFIRM_REMOTE, explainAction(remote, true))
        assertEquals(ExplainAction.CONFIRM_REMOTE, explainAction(remote.copy(requiresConsent = false), true))
        assertTrue(canConfirmExplanation(remote, remote, true))
    }

    @Test fun changedConfigurationInvalidatesOpenConfirmation() {
        assertFalse(canConfirmExplanation(remote, remote.copy(fingerprint = "configuration-b"), true))
        assertFalse(canConfirmExplanation(remote, remote.copy(endpoint = "https://other.example/v1"), true))
        assertFalse(canConfirmExplanation(remote, remote.copy(model = "another-model"), true))
        assertFalse(canConfirmExplanation(remote, ModelInfo(mode = "demo"), true))
    }

    @Test fun missingConfigurationCannotSkipConsent() {
        assertEquals(ExplainAction.BLOCKED, explainAction(ModelInfo(), true))
        assertEquals(ExplainAction.BLOCKED, explainAction(remote.copy(fingerprint = ""), true))
        assertEquals(ExplainAction.BLOCKED, explainAction(remote.copy(endpoint = ""), true))
        assertEquals(ExplainAction.BLOCKED, explainAction(remote.copy(model = ""), true))
    }

    @Test fun changedOrMissingQuestionInvalidatesConsent() {
        assertTrue(consentQuestionMatches(1, 1))
        assertFalse(consentQuestionMatches(1, 2))
        assertFalse(consentQuestionMatches(1, null))
        assertFalse(consentQuestionMatches(0, 0))
    }

    @Test fun explicitDemoContinuesWithoutRemoteConsent() {
        val demo = ModelInfo(mode = "demo", label = "固定案例规则演示")
        assertEquals(ExplainAction.DEMO, explainAction(demo, true))
        assertTrue(demo.description.contains("合成教学材料"))
        assertTrue(demo.statusSummary(true).contains("未调用大模型"))
        assertEquals("模型已配置", remote.badge)
        assertTrue(remote.description.contains("配置不代表连接或调用已验证"))
    }

    @Test fun answerLabelsReflectStoredProviderRatherThanCurrentConfiguration() {
        assertEquals("固定案例规则演示 · 未调用大模型", answerProviderLabel("deterministic-extractive-v1"))
        assertEquals("llm:course-model", answerProviderLabel("llm:course-model"))
        assertEquals("提供方未记录", answerProviderLabel(""))
    }
}
