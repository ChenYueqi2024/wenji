package org.wenji.study

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator

/** Isolated local fixtures only. No API clients, production preferences, or real credentials. */
@RunWith(AndroidJUnit4::class)
class ProviderCatalogStorageTest {
    private lateinit var context: Context
    private lateinit var storageName: String
    private lateinit var keyAlias: String
    private lateinit var settings: StandaloneSettings
    private val keyA = "fixture-provider-a-placeholder"
    private val keyB = "fixture-provider-b-placeholder"

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val suffix = UUID.randomUUID().toString()
        storageName = "catalog_fixture_$suffix"
        keyAlias = "catalog_fixture_key_$suffix"
        settings = StandaloneSettings(context, storageName, keyAlias)
    }

    @After fun tearDown() {
        context.deleteSharedPreferences(storageName)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
    }

    private fun rejects(action: () -> Unit) {
        try { action(); fail("Expected safe storage failure") }
        catch (error: ModelSettingsException) {
            assertTrue(error.message?.isNotBlank() == true)
            assertFalse(error.message.orEmpty().contains(keyA))
            assertFalse(error.message.orEmpty().contains(keyB))
        }
    }

    private fun provider(name: String, endpoint: String, key: String): String {
        val staged = settings.prepareUpsertProvider(ProviderEditForm(name = name, baseUrl = endpoint, apiKey = key))
        val id = staged.catalog.providers.last().id
        settings.commitPrepared(staged)
        return id
    }

    private fun model(providerId: String, name: String, tokens: Int = 2048): String {
        val staged = settings.prepareUpsertModel(ProviderModelForm(providerId, model = name, maxOutputTokens = tokens))
        val id = staged.catalog.providers.first { it.id == providerId }.models.last().id
        settings.commitPrepared(staged)
        return id
    }

    private fun select(providerId: String, modelId: String) = settings.commitPrepared(settings.prepareSelect(providerId, modelId))

    private fun activeKeyMatches(expected: String): Boolean {
        val secrets = JSONObject(settings.load().secretsJson)
        return secrets.length() == 1 && secrets.getString("WENJI_PHONE_KEY") == expected
    }

    @Test fun multipleSuppliersModelsRestartAndCredentialIsolation() {
        val a = provider("课程 A", "https://a.invalid/v1", keyA)
        assertNull(settings.load().configJson) // Adding a key never opts into a supplier automatically.
        val a1 = model(a, "a-small", 1024)
        val a2 = model(a, "a-large", 4096)
        val b = provider("课程 B", "https://b.invalid/v1", keyB)
        val b1 = model(b, "b-model", 3072)
        assertNull(settings.load().configJson)
        select(a, a1)
        assertTrue(activeKeyMatches(keyA))
        assertEquals("课程 A", JSONObject(settings.load().configJson!!).getString("label"))
        select(b, b1)
        assertTrue(activeKeyMatches(keyB))
        assertEquals(3072, JSONObject(settings.load().configJson!!).getJSONObject("generation").getInt("max_output_tokens"))
        select(a, a2)
        settings = StandaloneSettings(context, storageName, keyAlias)
        assertTrue(activeKeyMatches(keyA))
        assertEquals("a-large", settings.loadForm().model)
        assertEquals(4096, settings.loadForm().maxOutputTokens)
        assertEquals("", settings.loadProviderForm(a).apiKey)
        assertEquals("", settings.loadForm().apiKey)
        val publicText = settings.loadCatalog().toString()
        val prefs = context.getSharedPreferences(storageName, Context.MODE_PRIVATE)
        val disk = File(context.applicationInfo.dataDir, "shared_prefs/$storageName.xml").readText()
        listOf(keyA, keyB).forEach { key ->
            assertFalse(publicText.contains(key))
            assertFalse(prefs.all.values.any { it.toString().contains(key) })
            assertFalse(disk.contains(key))
        }
    }

    @Test fun supplierEditsKeepOnlyTheirOwnKeyAndRequireKeyForChangedEndpoint() {
        val a = provider("A", "https://a.invalid/v1", keyA)
        val a1 = model(a, "a-model")
        val b = provider("B", "https://b.invalid/v1", keyB)
        val b1 = model(b, "b-model")
        select(a, a1)
        settings.commitPrepared(settings.prepareUpsertProvider(ProviderEditForm(b, "B renamed", baseUrl = "https://b.invalid/v1/")))
        assertTrue(activeKeyMatches(keyA))
        select(b, b1)
        assertTrue(activeKeyMatches(keyB))
        rejects { settings.prepareUpsertProvider(ProviderEditForm(b, "B", baseUrl = "https://other.invalid/v1")) }
        rejects { settings.prepareUpsertProvider(ProviderEditForm(name = "New", baseUrl = "https://new.invalid/v1")) }
        assertEquals("https://b.invalid/v1", settings.loadProviderForm(b).baseUrl)
        val replacement = "fixture-replacement-placeholder"
        settings.commitPrepared(settings.prepareUpsertProvider(ProviderEditForm(b, "B moved", baseUrl = "https://other.invalid/v1", apiKey = replacement)))
        assertTrue(activeKeyMatches(replacement))
        select(a, a1)
        assertTrue(activeKeyMatches(keyA))
    }

    @Test fun offlineAndDeletionNeverFallBackToAnotherSupplier() {
        val a = provider("A", "https://a.invalid/v1", keyA)
        val a1 = model(a, "a-first")
        val a2 = model(a, "a-second")
        val b = provider("B", "https://b.invalid/v1", keyB)
        val b1 = model(b, "b-first")
        select(a, a1)
        settings.commitPrepared(settings.prepareDemo())
        assertNull(settings.load().configJson)
        assertEquals(2, settings.loadCatalog().providers.size)
        select(b, b1)
        assertTrue(activeKeyMatches(keyB))
        settings.commitPrepared(settings.prepareRemoveProvider(b))
        assertNull(settings.load().configJson)
        assertEquals(1, settings.loadCatalog().providers.size)
        select(a, a1)
        settings.commitPrepared(settings.prepareRemoveModel(a, a1))
        assertNull(settings.load().configJson)
        assertEquals(listOf(a2), settings.loadCatalog().providers.single().models.map { it.id })
        select(a, a2)
        assertTrue(activeKeyMatches(keyA))
    }

    @Test fun cancelledAndStalePreparedChangesCannotOverwriteCommittedSettings() {
        val a = provider("A", "https://a.invalid/v1", keyA)
        val a1 = model(a, "a-first")
        select(a, a1)
        val cancelled = settings.prepareDemo()
        assertNull(cancelled.activeProfile.configJson)
        assertTrue(activeKeyMatches(keyA)) // No commit means cancellation leaves durable settings untouched.
        val stale = settings.prepareUpsertProvider(ProviderEditForm(a, "Stale name", baseUrl = "https://a.invalid/v1"))
        val secondInstance = StandaloneSettings(context, storageName, keyAlias)
        secondInstance.commitPrepared(secondInstance.prepareUpsertProvider(ProviderEditForm(a, "Current name", baseUrl = "https://a.invalid/v1")))
        rejects { settings.commitPrepared(stale) }
        rejects { settings.commitPrepared(cancelled) }
        assertEquals("Current name", settings.loadCatalog().providers.single().name)
        assertTrue(activeKeyMatches(keyA))
        rejects { settings.prepareUpsertModel(ProviderModelForm(a, model = "a-first")) }
        rejects { settings.prepareUpsertModel(ProviderModelForm(a, id = "deleted-model", model = "other")) }
        assertEquals(1, settings.loadCatalog().providers.single().models.size)
    }

    @Test fun authenticLegacySingleProfileMigratesWithoutChangingKeyOrModel() {
        writeLegacyFixture()
        val prefs = context.getSharedPreferences(storageName, Context.MODE_PRIVATE)
        val oldIv = prefs.getString("encryption_iv", null)
        assertTrue(activeKeyMatches(keyA))
        val catalog = settings.loadCatalog()
        assertEquals(1, catalog.providers.size)
        assertEquals("legacy-model", catalog.activeModelId)
        assertEquals("legacy-fixture-model", settings.loadForm().model)
        assertEquals(3072, settings.loadForm().maxOutputTokens)
        assertEquals("https://legacy.invalid/v1", settings.loadForm().baseUrl)
        assertEquals(2, JSONObject(prefs.getString("config", null)!!).getInt("version"))
        val migratedIv = prefs.getString("encryption_iv", null)
        assertNotEquals(oldIv, migratedIv)
        settings = StandaloneSettings(context, storageName, keyAlias)
        assertTrue(activeKeyMatches(keyA))
        assertEquals(migratedIv, prefs.getString("encryption_iv", null)) // Migration runs once.
    }

    @Test fun failedPreferencesCommitRestoresPriorCatalogAndEncryptedCredential() {
        val a = provider("A", "https://a.invalid/v1", keyA)
        val a1 = model(a, "a-first")
        select(a, a1)
        val original = settings.loadCatalog()
        val prefs = context.getSharedPreferences(storageName, Context.MODE_PRIVATE)
        val oldIv = prefs.getString("encryption_iv", null)
        val failNext = AtomicBoolean(false)
        val injected = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val delegate = prefs.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun clear(): SharedPreferences.Editor { delegate.clear(); return this }
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        delegate.putString(key, value); return this
                    }
                    override fun commit(): Boolean {
                        val result = delegate.commit()
                        // SharedPreferences can change its memory snapshot before reporting disk failure.
                        return if (failNext.getAndSet(false)) false else result
                    }
                }
            }
        }
        val testContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                check(name == storageName)
                return injected
            }
        }
        val faultingSettings = StandaloneSettings(testContext, storageName, keyAlias)
        val staged = faultingSettings.prepareUpsertProvider(ProviderEditForm(a, "Must not persist", baseUrl = "https://a.invalid/v1", apiKey = keyB))
        failNext.set(true)
        rejects { faultingSettings.commitPrepared(staged) }
        assertEquals(original, settings.loadCatalog())
        assertEquals(oldIv, prefs.getString("encryption_iv", null))
        assertTrue(activeKeyMatches(keyA))
    }

    @Test fun tamperedLegacyOrCatalogNeverSilentlyBecomesOfflineMode() {
        writeLegacyFixture()
        val prefs = context.getSharedPreferences(storageName, Context.MODE_PRIVATE)
        val original = prefs.getString("config", null)!!
        assertTrue(prefs.edit().putString("config", JSONObject(original).put("model", "tampered").toString()).commit())
        rejects { settings.load() }
        rejects { settings.loadCatalog() }
        assertTrue(prefs.edit().putString("config", original).commit())
        assertTrue(activeKeyMatches(keyA))
        val migrated = JSONObject(prefs.getString("config", null)!!)
        migrated.getJSONArray("providers").getJSONObject(0).put("base_url", "https://changed.invalid/v1")
        assertTrue(prefs.edit().putString("config", migrated.toString()).commit())
        rejects { settings.load() }
        rejects { settings.loadCatalog() }
        rejects { settings.prepareDemo() }
    }

    private fun writeLegacyFixture() {
        val config = JSONObject().put("protocol", "openai_chat").put("base_url", "https://legacy.invalid/v1")
            .put("model", "legacy-fixture-model").put("label", "legacy fixture").put("api_key_env", "WENJI_PHONE_KEY")
            .put("timeout_seconds", 60).put("generation", JSONObject().put("max_output_tokens", 3072)).toString()
        val key = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(config.toByteArray(Charsets.UTF_8))
        val plain = JSONObject().put("WENJI_PHONE_KEY", keyA).toString().toByteArray(Charsets.UTF_8)
        val encrypted = try { cipher.doFinal(plain) } finally { plain.fill(0) }
        assertTrue(context.getSharedPreferences(storageName, Context.MODE_PRIVATE).edit()
            .putString("config", config).putString("encrypted_secrets", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("encryption_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit())
    }
}
