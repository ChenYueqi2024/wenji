package org.wenji.study

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModelSettingsFormTest {
    private fun form(
        protocol: String = "openai_chat", url: String = "https://api.example.com/v1",
        model: String = "model-name", key: String = "", tokens: Int = 2048,
    ) = ModelSettingsForm(protocol, url, model, key, tokens)

    private fun rejects(action: () -> Unit) {
        try { action(); fail("Expected safe validation failure") }
        catch (error: ModelSettingsException) { assertTrue(error.message?.isNotBlank() == true) }
    }

    @Test fun supportsAllFourGatewayProtocols() {
        modelProtocols.keys.forEach { validateModelSettings(form(protocol = it)) }
        rejects { validateModelSettings(form(protocol = "unrecognized")) }
    }

    @Test fun requiresSecureRemoteDestinationAndNoUrlCredentials() {
        listOf("http://api.example.com/v1", "https://user:password@example.com", "https://api.example.com?key=value",
            "https://api.example.com?", "https://api.example.com/#", "https://api.example.com:0",
            "https://api.example.com:65536", "https://api.example.com/\npath", "https://例子.com/v1",
            "https://api.example.com\\path", "relative/path", "https:///v1").forEach { url ->
            rejects { validateModelSettings(form(url = url)) }
        }
        listOf("http://localhost:8080/v1", "http://127.0.0.1:8000/v1", "http://[::1]:8000/v1",
            "https://api.example.com/nested/v1").forEach { validateModelSettings(form(url = it)) }
    }

    @Test fun rejectsMalformedModelsAndOutOfRangeTokenLimits() {
        listOf("", "one two", "bad\nmodel", "x".repeat(201), "\uD800", "\u200B", "\u2800").forEach {
            rejects { validateModelSettings(form(model = it)) }
        }
        listOf(0, -1, 16385).forEach { rejects { validateModelSettings(form(tokens = it)) } }
        validateModelSettings(form(model = "模型-1", tokens = 1))
        validateModelSettings(form(tokens = 16384))
    }

    @Test fun newCredentialsMustBePrintableAndCannotBeSilentlyTrimmed() {
        listOf("", " leading", "trailing ", "line\nbreak", "nonascii密钥", "x".repeat(8193)).forEach {
            rejects { validateModelKey(it) }
        }
        validateModelKey("test-only-key")
        validateModelSettings(form()) // A blank form key is resolved from encrypted storage by prepare.
        assertFalse(form(key = "test-only-key").toString().contains("test-only-key"))
    }

    @Test fun savedKeyIsNeverReusedAtAnotherDestination() {
        validateSavedKeyDestination(" https://api.example.com/v1/ ", "https://api.example.com/v1")
        listOf("https://other.example.com/v1", "https://api.example.com/v2", "http://api.example.com/v1",
            "https://api.example.com:8443/v1").forEach {
            rejects { validateSavedKeyDestination(it, "https://api.example.com/v1") }
        }
    }
}
