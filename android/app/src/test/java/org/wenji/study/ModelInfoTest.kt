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
        assertEquals("模型 · course-model", answerProviderLabel("llm:course-model"))
        assertEquals("提供方未记录", answerProviderLabel(""))
    }

    @Test fun historicDemoRecoveryUsesCurrentVerifiedConfiguration() {
        val demoProvider = "deterministic-extractive-v1"
        assertEquals(DemoRecoveryAction.CONFIGURE_MODEL, demoRecoveryAction(demoProvider,"needs_material",ModelInfo(mode="demo"),true))
        assertEquals(DemoRecoveryAction.CONFIRM_CURRENT_MODEL, demoRecoveryAction(demoProvider,"needs_material",remote,true))
        assertEquals(DemoRecoveryAction.REFRESH_CONFIG, demoRecoveryAction(demoProvider,"needs_material",remote,false))
        assertEquals(DemoRecoveryAction.REFRESH_CONFIG, demoRecoveryAction(demoProvider,"needs_material",remote.copy(fingerprint=""),true))
        assertEquals(DemoRecoveryAction.NONE, demoRecoveryAction(demoProvider,"ready",remote,true))
        assertEquals(DemoRecoveryAction.NONE, demoRecoveryAction("llm:course-model","needs_material",remote,true))
    }

    @Test fun generalReferenceCanNeverUseDemoAndAlwaysNeedsRemoteConfirmation() {
        assertEquals(ExplainAction.BLOCKED,modelRequestAction(ModelInfo(mode="demo"),true,ModelRequestKind.GENERAL_REFERENCE))
        assertEquals(ExplainAction.CONFIRM_REMOTE,modelRequestAction(remote,true,ModelRequestKind.GENERAL_REFERENCE))
        assertEquals(ExplainAction.BLOCKED,modelRequestAction(remote,false,ModelRequestKind.GENERAL_REFERENCE))
        assertEquals(ExplainAction.DEMO,modelRequestAction(ModelInfo(mode="demo"),true,ModelRequestKind.COURSE_EVIDENCE))
        assertNotEquals(ModelRequestKind.GENERAL_REFERENCE.pathSuffix,ModelRequestKind.COURSE_EVIDENCE.pathSuffix)
    }

    @Test fun ordinaryHistoryLabelsDoNotExposeStoredPrivateEndpoint() {
        assertEquals("模型 · openai_chat · course-model",answerProviderLabel("llm:openai_chat:course-model @ https://private.example/v1"))
        assertEquals("提供方未记录",answerProviderLabel("https://private.example/v1"))
        assertEquals("提供方未记录",answerProviderLabel("llm:https://private.example/v1"))
    }

    @Test fun firstDemoIntroductionOnlyUsesVerifiedUnconfiguredState() {
        val demo = ModelInfo(mode="demo")
        assertTrue(shouldShowDemoIntro(demo,true,false))
        assertFalse(shouldShowDemoIntro(remote,true,false))
        assertFalse(shouldShowDemoIntro(demo,false,false))
        assertFalse(shouldShowDemoIntro(ModelInfo(),true,false))
        // Both dismissal and "configure" persist this acknowledgement; later refreshes do not reopen it.
        assertFalse(shouldShowDemoIntro(demo,true,true))
        assertFalse(shouldShowDemoIntro(demo,false,true))
    }

    @Test fun currentCourseReviewLeadsWithoutHidingGeneralOrHistoricalWork() {
        val cards = listOf("general" to reviewDisplayPriority("general_reference","awaiting_attempt"),
            "old-general" to reviewDisplayPriority("general_reference","needs_review"),
            "course" to reviewDisplayPriority("course_evidence","awaiting_attempt"),
            "old-course" to reviewDisplayPriority("course_evidence","needs_review"))
        assertEquals(listOf("course","general","old-general","old-course"),cards.sortedBy { it.second }.map { it.first })
    }

}
