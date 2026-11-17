package org.wenji.study

import org.junit.Assert.*
import org.junit.Test

class ModelCapabilitiesTest {
    @Test fun exactSnapshotHasOutputLimitSeparateFromContext() {
        val info = modelCapabilities("gpt-4.1-2025-04-14")!!
        assertEquals(1_047_576, info.contextTokens)
        assertEquals(32_768, info.maxOutputTokens)
        assertEquals(ModelCapabilityMatchKind.OFFICIAL_ID, info.matchKind)
        assertTrue(info.imageInput)
        assertTrue(info.notes.contains("弃用"))
        assertEquals(modelCapabilities("gpt-4.1"), info)
    }

    @Test fun BrandOnlyNamesPartialFamiliesAndUnverifiedVariantsStayUnknown() {
        listOf("", "gpt-4", "DeepSeek", "某某AI", "OpenAI", "gpt-4.1-mini",
            "vendor/gpt-4.1-nano-20261117", "my-gpt-4.1-pro", "gpt-4.10", "gpt-4o-audio-preview",
            "deepseek-v4.1-flash-1017-fast", "deepseek-flash-lite", "gemini-3.5",
            "claude-sonnet-5-5-thinking", "deepseek-v4.2-flash-1017", "https://provider.invalid/gpt-4.1").forEach {
            assertNull("Unknown deployment ID must stay unknown: $it", modelCapabilities(it))
        }
        assertEquals(modelCapabilities("deepseek-flash"), modelCapabilities("  deepseek-flash  "))
    }

    @Test fun normalizedVendorAliasesSuggestButNeverVerifyADeployment() {
        listOf("openai/gpt-4.1", "vendor:GPT_4.1_custom", "gpt-4.1-2026-11-17",
            "GPT-4.1").forEach { name ->
            val candidate = modelCapabilities(name)!!
            assertEquals(ModelCapabilityMatchKind.ALIAS_CANDIDATE, candidate.matchKind)
            assertEquals("gpt-4.1", candidate.modelId)
            assertEquals(1_047_576, candidate.contextTokens)
            assertTrue(candidate.notes.contains("近似参考"))
            assertTrue(candidate.notes.contains("未核验"))
            assertTrue(candidate.notes.contains("不自动更改参数"))
        }
        assertEquals("gemini-3.5-flash-lite", modelCapabilities("models/GEMINI_3.5_FLASH_LITE_20261117")!!.modelId)
        assertEquals("claude-sonnet-5-5", modelCapabilities("anthropic/claude-sonnet-5-5-latest")!!.modelId)
    }

    @Test fun longestOfficialIdentityWinsWithoutConfusingModelVariants() {
        val snapshot = modelCapabilities("azure/gpt-4.1-2025-04-14-deployment")!!
        assertTrue(snapshot.notes.contains("gpt-4.1-2025-04-14"))
        val flash = modelCapabilities("provider/DeepSeek-V4.1-Flash-1018")!!
        assertEquals(ModelCapabilityMatchKind.ALIAS_CANDIDATE, flash.matchKind)
        assertEquals("deepseek-flash", flash.modelId)
        assertTrue(flash.imageInput)
        assertFalse(modelCapabilities("provider/deepseek-v4-pro-0813")!!.imageInput)
        listOf("gpt-4.1-and-gpt-4o", "gemini-3.5-flash-lite-and-deepseek-flash",
            "vendor/deepseek-v4-pro-vision", "gpt-4.1-mini-2026-11-17").forEach {
            assertNull("Ambiguous or additional variant must stay unknown: $it", modelCapabilities(it))
        }
    }

    @Test fun documentedVersionOnlyGetsAnUnverifiedCandidateMatch() {
        val candidate = modelCapabilities("DeepSeek-V4.1-Flash-1017")!!
        assertEquals(ModelCapabilityMatchKind.VERSION_CANDIDATE, candidate.matchKind)
        assertEquals("deepseek-flash", candidate.modelId)
        assertTrue(candidate.notes.contains("未核验"))
        assertTrue(candidate.notes.contains("不自动更改参数"))
        assertEquals(candidate, modelCapabilities("deepseek-v4.1-flash-1017"))
        assertEquals(ModelCapabilityMatchKind.OFFICIAL_ID, modelCapabilities("deepseek-flash")!!.matchKind)
    }

    @Test fun textOnlyModelIsNotUpgradedToVisionAndInputLimitIsNamedAccurately() {
        assertFalse(modelCapabilities("deepseek-v4-pro")!!.imageInput)
        val gemini = modelCapabilities("gemini-3.5-flash-lite")!!
        assertTrue(gemini.imageInput)
        assertEquals("输入上限", gemini.contextLabel)
        assertEquals(1_048_576, gemini.contextTokens)
        assertEquals(65_536, gemini.maxOutputTokens)
    }

    @Test fun catalogueEntriesKeepOfficialSourcesAndVerificationDate() {
        val hosts = listOf("https://developers.openai.com/", "https://aistudio.google.com/",
            "https://platform.claude.com/", "https://api-docs.deepseek.com/")
        listOf("gpt-4.1", "gpt-4o", "gemini-3.5-flash-lite", "gemini-2.5-flash",
            "claude-sonnet-5-5", "deepseek-flash", "deepseek-v4-pro").forEach { id ->
            val info = modelCapabilities(id)!!
            assertTrue(hosts.any { info.sourceUrl.startsWith(it) })
            assertEquals("2026-11-17", info.verifiedDate)
            assertTrue(info.contextTokens > info.maxOutputTokens)
            assertTrue(info.maxOutputTokens > 0)
        }
    }
}
