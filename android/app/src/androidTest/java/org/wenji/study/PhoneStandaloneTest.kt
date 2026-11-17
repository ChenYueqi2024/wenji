@file:Suppress("DEPRECATION")

package org.wenji.study

import android.content.Context
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Real Android/Python/SQLite/PDF/Keystore tests. Every file, alias, and credential is a disposable fixture. */
@RunWith(AndroidJUnit4::class)
class PhoneStandaloneTest {
    private lateinit var appContext: Context
    private lateinit var dbName: String
    private lateinit var storageName: String
    private lateinit var keyAlias: String
    private lateinit var settings: StandaloneSettings
    private var engine: PhoneEngine? = null
    private var socketModule: PyObject? = null
    private var originalSocket: PyObject? = null
    private var socketGuard: PyObject? = null

    @Before
    fun setUp() {
        appContext = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val suffix = UUID.randomUUID().toString()
        dbName = "instrument-$suffix.sqlite3"
        storageName = "instrument_settings_$suffix"
        keyAlias = "instrument_key_$suffix"
        settings = StandaloneSettings(appContext, storageName, keyAlias)
        if (!Python.isStarted()) Python.start(AndroidPlatform(appContext))
        val python = Python.getInstance()
        // ssl declares a socket subclass at import time; load that declaration before replacing the constructor.
        python.getModule("ssl")
        val builtins = python.getModule("builtins")
        socketGuard = builtins.callAttr("dict")
        builtins.callAttr("exec", """
            attempts = 0
            def deny_socket(*args, **kwargs):
                global attempts
                attempts += 1
                raise AssertionError("Network is forbidden in offline phone instrumentation")
        """.trimIndent(), socketGuard)
        socketModule = python.getModule("socket")
        originalSocket = socketModule!!.get("socket")
        socketModule!!.callAttr("__setattr__", "socket", socketGuard!!.callAttr("__getitem__", "deny_socket"))
    }

    @After
    fun tearDown() {
        var cleanupFailed = false
        try {
            try { engine?.close() } catch (_: Exception) { cleanupFailed = true }
            engine = null
            // Cleanup targets are names generated in setUp; production preferences/keys/database are untouched.
            try {
                settings.clear()
                appContext.deleteSharedPreferences(storageName)
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
                listOf(dbName, "$dbName-journal", "$dbName-wal", "$dbName-shm").forEach { name ->
                    val target = File(appContext.filesDir, name)
                    check(target.canonicalFile.parentFile == appContext.filesDir.canonicalFile)
                    if (target.exists() && !target.delete()) cleanupFailed = true
                }
            } catch (_: Exception) { cleanupFailed = true }
            assertEquals("No socket may be opened by the offline phone flow", 0,
                socketGuard?.callAttr("__getitem__", "attempts")?.toInt() ?: 0)
            assertFalse("Disposable test storage cleanup failed", cleanupFailed)
        } finally {
            if (originalSocket != null) socketModule?.callAttr("__setattr__", "socket", originalSocket)
        }
    }

    @Test
    fun testLocalPdfStudyFlowPersistsAndInvalidatesReferences() {
        engine = PhoneEngine(appContext, dbName = dbName)
        val health = engine!!.request("/health")
        assertEquals("android-in-process", health.getString("runtime"))
        assertEquals("android-app-private", health.getString("authentication"))
        assertEquals("local-demo", health.getString("mode"))

        val pdf = appContext.assets.open("fixtures/resource_graph_v1.pdf").use { it.readBytes() }
        val imported = mutate("/sources", JSONObject().put("title", "手机离线 PDF 测试")
            .put("pdf_base64", Base64.encodeToString(pdf, Base64.NO_WRAP)))
        val sourceId = imported.getJSONObject("result").getString("source_id")
        val pages = imported.getJSONObject("state").getJSONArray("sources").getJSONObject(0).getJSONArray("pages")
        assertEquals("Bundled copyable PDF must retain both pages", 2, pages.length())
        assertTrue(engine!!.request("/search?q=%E6%AD%BB%E9%94%81").getJSONArray("candidates").length() > 0)

        val questionId = mutate("/questions", JSONObject().put("text", "资源分配图有环，是否一定死锁？"))
            .getJSONObject("result").getString("question_id")
        val explainRequest = JSONObject().put("request_id", UUID.randomUUID().toString())
        val explained = engine!!.request("/questions/$questionId/explain", explainRequest)
        val result = explained.getJSONObject("result")
        val answer = explained.getJSONObject("state").getJSONArray("questions").getJSONObject(0).getJSONObject("answer")
        assertEquals("ready", answer.getString("status"))
        assertEquals("deterministic-extractive-v1", answer.getString("provider"))
        val citedPages = mutableSetOf<Int>()
        val claims = answer.getJSONArray("claims")
        assertTrue(claims.length() > 0)
        for (index in 0 until claims.length()) {
            val evidence = claims.getJSONObject(index).getJSONArray("evidence").getJSONObject(0)
            val page = evidence.getInt("page")
            citedPages += page
            val sourceText = pages.getJSONObject(page - 1).getString("text")
            // Python offsets count code points; use the same unit when verifying Android strings.
            val start = sourceText.offsetByCodePoints(0, evidence.getInt("start"))
            val end = sourceText.offsetByCodePoints(0, evidence.getInt("end"))
            assertEquals(evidence.getString("quote"), sourceText.substring(start, end))
        }
        assertEquals(setOf(1, 2), citedPages)
        val replay = engine!!.request("/questions/$questionId/explain", explainRequest).getJSONObject("result")
        assertTrue(replay.getBoolean("replayed"))
        assertEquals(result.getString("answer_id"), replay.getString("answer_id"))

        val cardId = result.getString("card_id")
        val attemptId = mutate("/cards/$cardId/attempts", JSONObject().put("answer", "多实例资源有环仍可能继续，需要核对实例数量。"))
            .getJSONObject("result").getString("attempt_id")
        val confirmed = mutate("/cards/$cardId/confirm", JSONObject().put("attempt_id", attemptId).put("self_rating", "understood"))
        assertTrue(confirmed.getJSONObject("state").getJSONArray("attempts").getJSONObject(0).getBoolean("confirmed"))
        reopenEngine()
        val reopened = engine!!.request("/state")
        assertEquals(attemptId, reopened.getJSONArray("attempts").getJSONObject(0).getString("id"))
        assertEquals(result.getString("answer_id"), reopened.getJSONArray("questions").getJSONObject(0).getJSONObject("answer").getString("id"))
        assertEquals(1, reopened.getJSONArray("cards").length())

        val revisedText = appContext.assets.open("fixtures/resource_graph_v2.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val revised = mutate("/sources/$sourceId/revisions", JSONObject().put("base_revision", 1).put("text", revisedText))
            .getJSONObject("state")
        assertEquals("needs_review", revised.getJSONArray("cards").getJSONObject(0).getString("status"))
        assertEquals("needs_review", revised.getJSONArray("questions").getJSONObject(0).getJSONObject("answer").getString("status"))
        assertFalse(revised.getJSONArray("attempts").getJSONObject(0).isNull("source_warning"))
        reopenEngine()
        val finalState = engine!!.request("/state")
        assertEquals(2, finalState.getJSONArray("sources").getJSONObject(0).getInt("revision"))
        assertEquals("needs_review", finalState.getJSONArray("cards").getJSONObject(0).getString("status"))
        assertEquals(attemptId, finalState.getJSONArray("attempts").getJSONObject(0).getString("id"))
    }

    @Test
    fun testKeystoreRoundTripDestinationIsolationAndDemoKeepsLearningData() {
        val fakeKey = "phone-fixture-key-${UUID.randomUUID()}"
        assertNull(settings.load().configJson)
        assertFalse(settings.loadForm().hasSavedKey)
        engine = PhoneEngine(appContext, dbName = dbName)
        val questionId = mutate("/questions", JSONObject().put("text", "保留这条本机疑问"))
            .getJSONObject("result").getString("question_id")

        // Configure validates locally and makes no model call. The socket guard also enforces this.
        val prepared = settings.prepare(ModelSettingsForm(baseUrl = "https://provider.invalid/v1", model = "phone-fixture-model", apiKey = fakeKey))
        assertEquals("llm", engine!!.configure(prepared.configJson, prepared.secretsJson).getJSONObject("provider_info").getString("mode"))
        settings.save(prepared)
        val restoredSettings = StandaloneSettings(appContext, storageName, keyAlias)
        val restored = restoredSettings.load()
        assertTrue("Encrypted credential must round-trip", JSONObject(restored.secretsJson).getString("WENJI_PHONE_KEY") == fakeKey)
        assertFalse(restored.toString().contains(fakeKey))
        val form = restoredSettings.loadForm()
        assertTrue(form.hasSavedKey)
        assertEquals("", form.apiKey)
        assertEquals("https://provider.invalid/v1", form.baseUrl)
        val prefs = appContext.getSharedPreferences(storageName, Context.MODE_PRIVATE)
        val ivBefore = prefs.getString("encryption_iv", null)
        assertFalse("Preferences must not contain plaintext credential", prefs.all.values.any { it.toString().contains(fakeKey) })
        val diskPreferences = File(appContext.applicationInfo.dataDir, "shared_prefs/$storageName.xml")
        assertTrue("Encrypted preferences must be committed to disk", diskPreferences.isFile)
        assertFalse("On-disk preferences must not contain plaintext credential", diskPreferences.readText().contains(fakeKey))

        val sameDestination = restoredSettings.prepare(ModelSettingsForm(baseUrl = form.baseUrl, model = "phone-fixture-model-v2"))
        assertTrue("Blank field keeps the key only at the same destination", JSONObject(sameDestination.secretsJson).getString("WENJI_PHONE_KEY") == fakeKey)
        restoredSettings.save(sameDestination)
        assertTrue("Each encryption must use a fresh IV", prefs.getString("encryption_iv", null) != ivBefore)
        assertRejected { restoredSettings.prepare(ModelSettingsForm(baseUrl = "https://another.invalid/v1", model = form.model)) }
        assertTrue("Rejected edit must retain old destination", restoredSettings.loadForm().baseUrl == form.baseUrl)
        val replacementKey = "phone-fixture-replacement-${UUID.randomUUID()}"
        val changed = restoredSettings.prepare(ModelSettingsForm(baseUrl = "https://another.invalid/v1", model = form.model, apiKey = replacementKey))
        assertTrue(JSONObject(changed.secretsJson).getString("WENJI_PHONE_KEY") == replacementKey)

        val publicState = engine!!.request("/state").toString()
        assertFalse("Public state must redact credentials", publicState.contains(fakeKey))
        assertEquals("demo", engine!!.configure().getJSONObject("provider_info").getString("mode"))
        restoredSettings.clear()
        assertNull(restoredSettings.load().configJson)
        assertFalse(restoredSettings.loadForm().hasSavedKey)
        assertTrue(prefs.all.isEmpty())
        reopenEngine()
        val afterClear = engine!!.request("/state")
        assertEquals("demo", afterClear.getJSONObject("provider_info").getString("mode"))
        assertEquals(questionId, afterClear.getJSONArray("questions").getJSONObject(0).getString("id"))
        assertFalse("Learning SQLite must never store model credentials", File(appContext.filesDir, dbName).readBytes()
            .toString(Charsets.ISO_8859_1).contains(fakeKey))
    }

    @Test
    fun testTamperedEncryptedConfigurationFailsClosed() {
        val prepared = settings.prepare(ModelSettingsForm(baseUrl = "https://provider.invalid/v1", model = "phone-fixture-model",
            apiKey = "phone-fixture-key-${UUID.randomUUID()}"))
        settings.save(prepared)
        val prefs = appContext.getSharedPreferences(storageName, Context.MODE_PRIVATE)
        val alteredConfig = JSONObject(prefs.getString("config", null)!!).put("base_url", "https://changed.invalid/v1")
        assertTrue(prefs.edit().putString("config", alteredConfig.toString()).commit())
        // Config is AES/GCM associated data: even changing public fields invalidates the encrypted record.
        assertRejected { settings.load() }
        assertRejected { settings.loadForm() }
        settings.clear()
        assertNull(settings.load().configJson)
    }

    private fun mutate(path: String, payload: JSONObject): JSONObject = engine!!.request(path,
        payload.put("request_id", UUID.randomUUID().toString()))

    private fun reopenEngine() {
        engine!!.close()
        engine = null
        engine = PhoneEngine(appContext, dbName = dbName)
    }

    private fun assertRejected(action: () -> Any?) {
        try { action(); fail("Expected configuration rejection") }
        catch (error: ModelSettingsException) {
            assertTrue("Failure must have a safe message", error.message?.isNotBlank() == true)
            assertFalse("Failure must not expose fixture credential", error.message!!.contains("phone-fixture-key-"))
        }
    }
}
