package org.wenji.study

import org.junit.Assert.*
import org.junit.Test

class ProviderCatalogTest {
    private val a = ProviderView("a", "供应商 A", "openai_chat", "https://a.invalid/v1", true,
        listOf(ProviderModelView("a1", "model-a"), ProviderModelView("a2", "model-a-large", 4096)))
    private val b = ProviderView("b", "供应商 B", "anthropic_messages", "https://b.invalid", true,
        listOf(ProviderModelView("b1", "model-b")))
    private val active = ProviderCatalogView(listOf(a, b), "a", "a1")

    private fun rejects(action: () -> Unit) {
        try { action(); fail("Expected safe validation failure") }
        catch (error: ModelSettingsException) { assertTrue(error.message?.isNotBlank() == true) }
    }

    @Test fun selectionRequiresExistingModelAndSavedCredential() {
        val selected = providerAfterSelection(active, "b", "b1")
        assertEquals("b", selected.activeProviderId)
        assertEquals("b1", selected.activeModelId)
        rejects { providerAfterSelection(active, "missing", "b1") }
        rejects { providerAfterSelection(active, "b", "a1") }
        rejects { providerAfterSelection(active.copy(providers = listOf(b.copy(hasSavedKey = false))), "b", "b1") }
    }

    @Test fun deletingActiveProviderNeverSelectsAnotherCompany() {
        val removed = providerAfterRemoval(active, "a")
        assertEquals(listOf(b), removed.providers)
        assertNull(removed.activeProviderId)
        assertNull(removed.activeModelId)
        assertEquals("a", providerAfterRemoval(active, "b").activeProviderId)
        rejects { providerAfterRemoval(active, "missing") }
    }

    @Test fun deletingActiveModelPreservesOtherModelsButDisablesCalls() {
        val removed = modelAfterRemoval(active, "a", "a1")
        assertEquals(listOf(a.models[1]), removed.providers.first().models)
        assertNull(removed.activeProviderId)
        assertNull(removed.activeModelId)
        assertEquals("a1", modelAfterRemoval(active, "a", "a2").activeModelId)
        assertEquals("a1", modelAfterRemoval(active, "b", "b1").activeModelId)
        rejects { modelAfterRemoval(active, "a", "b1") }
    }

    @Test fun validatesSupplierNamesEndpointsAndKeepsCredentialOutOfToString() {
        validateProviderForm(ProviderEditForm(name = "课程模型 🔬", baseUrl = a.baseUrl))
        listOf("", "  ", "x".repeat(61), "bad\nname", "\uD800", "\u200B").forEach { name ->
            rejects { validateProviderForm(ProviderEditForm(name = name, baseUrl = a.baseUrl)) }
        }
        rejects { validateProviderForm(ProviderEditForm(name = "A", baseUrl = "http://remote.invalid")) }
        rejects { validateProviderForm(ProviderEditForm(name = "A", baseUrl = a.baseUrl, apiKey = " leading")) }
        val secret = "test-only-placeholder-key"
        assertFalse(ProviderEditForm(name = "A", baseUrl = a.baseUrl, apiKey = secret).toString().contains(secret))
    }

    @Test fun modelValidationUsesItsOwnSupplierAndIndependentOutputBudget() {
        validateProviderModelForm(ProviderModelForm("a", model = "model-2", maxOutputTokens = 16384), a)
        rejects { validateProviderModelForm(ProviderModelForm("b", model = "model-2"), a) }
        rejects { validateProviderModelForm(ProviderModelForm("a", model = "bad model"), a) }
        rejects { validateProviderModelForm(ProviderModelForm("a", model = "model-2", maxOutputTokens = 0), a) }
    }
}
