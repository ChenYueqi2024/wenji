@file:Suppress("DEPRECATION")

package org.wenji.study

import android.content.Context
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
import java.util.UUID

/** Synthetic localhost transport only. This checks no real provider, public network, credentials, or TLS. */
@RunWith(AndroidJUnit4::class)
class PhoneModelTransportTest {
    private lateinit var appContext: Context
    private lateinit var dbName: String
    private var engine: PhoneEngine? = null
    private var fixture: PyObject? = null

    @Before
    fun setUp() {
        appContext = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        dbName = "instrument-transport-${UUID.randomUUID()}.sqlite3"
        if (!Python.isStarted()) Python.start(AndroidPlatform(appContext))
        val builtins = Python.getInstance().getModule("builtins")
        fixture = builtins.callAttr("dict")
        // Test code lives in the instrumentation APK, never in the production Python asset bundle.
        builtins.callAttr("exec", FIXTURE, fixture)
    }

    @After
    fun tearDown() {
        var cleanupFailed = false
        try { engine?.close() } catch (_: Exception) { cleanupFailed = true }
        engine = null
        try {
            fixture?.callAttr("__getitem__", "stop_fixture")?.call()?.let {
                if (!it.toBoolean()) cleanupFailed = true
            }
        } catch (_: Exception) { cleanupFailed = true }
        try {
            listOf(dbName, "$dbName-journal", "$dbName-wal", "$dbName-shm").forEach { name ->
                val target = File(appContext.filesDir, name)
                check(target.canonicalFile.parentFile == appContext.filesDir.canonicalFile)
                if (target.exists() && !target.delete()) cleanupFailed = true
            }
        } catch (_: Exception) { cleanupFailed = true }
        assertFalse("Disposable localhost fixture cleanup failed", cleanupFailed)
    }

    @Test
    fun testPhoneChatTransportRequiresConsentReplaysAndInvalidatesEvidence() {
        val port = fixture!!.callAttr("__getitem__", "port").toInt()
        assertTrue(port in 1..65535)
        val profile = JSONObject()
            .put("protocol", "openai_chat")
            .put("base_url", "http://127.0.0.1:$port/v1")
            .put("model", "phone-synthetic-chat-fixture")
            .put("auth_mode", "none")
            .put("label", "手机本机合成协议测试")
            .put("timeout_seconds", 10)
        engine = PhoneEngine(appContext, dbName = dbName)
        val info = engine!!.configure(profile.toString()).getJSONObject("provider_info")
        assertEquals("llm", info.getString("mode"))
        assertEquals("openai_chat", info.getString("protocol"))
        assertEquals(0, hits())

        val sourceText = "队列按照先进先出的顺序处理元素。"
        val sourceId = mutate("/sources", JSONObject().put("title", "原创合成协议测试材料").put("text", sourceText))
            .getJSONObject("result").getString("source_id")
        val questionId = mutate("/questions", JSONObject().put("text", "队列的处理顺序是什么？"))
            .getJSONObject("result").getString("question_id")
        try {
            mutate("/questions/$questionId/explain", JSONObject())
            fail("A configured model must require explicit consent before transport")
        } catch (failure: ApiFailure) { assertEquals(409, failure.status) }
        assertEquals("No request before consent", 0, hits())

        val consent = JSONObject()
            .put("request_id", UUID.randomUUID().toString())
            .put("allow_remote", true)
            .put("provider_fingerprint", info.getString("fingerprint"))
            .put("question_revision", 1)
        val response = engine!!.request("/questions/$questionId/explain", consent)
        assertEquals("Exactly one local synthetic HTTP request", 1, hits())
        assertFalse("Synthetic server must accept the request wire shape", fixture!!.callAttr("__getitem__", "server")
            .get("failed")!!.toBoolean())
        val state = response.getJSONObject("state")
        val answer = state.getJSONArray("questions").getJSONObject(0).getJSONObject("answer")
        assertEquals("ready", answer.getString("status"))
        val claim = answer.getJSONArray("claims").getJSONObject(0)
        assertEquals(sourceText, claim.getString("text"))
        val evidence = claim.getJSONArray("evidence").getJSONObject(0)
        assertEquals(sourceId, evidence.getString("source_id"))
        assertEquals(1, evidence.getInt("revision"))
        assertEquals(1, evidence.getInt("page"))
        assertEquals(sourceText, evidence.getString("quote"))
        assertFalse("Internal assessment rubric must remain private", state.getJSONArray("cards").getJSONObject(0).has("rubric"))

        val replay = engine!!.request("/questions/$questionId/explain", consent).getJSONObject("result")
        assertTrue(replay.getBoolean("replayed"))
        assertEquals(response.getJSONObject("result").getString("answer_id"), replay.getString("answer_id"))
        assertEquals("Replaying the same authorized request must not spend another call", 1, hits())

        val revised = mutate("/sources/$sourceId/revisions", JSONObject().put("base_revision", 1)
            .put("text", "队列采用先进先出，栈采用后进先出。"))
            .getJSONObject("state")
        assertEquals("needs_review", revised.getJSONArray("questions").getJSONObject(0).getJSONObject("answer").getString("status"))
        assertEquals("needs_review", revised.getJSONArray("cards").getJSONObject(0).getString("status"))
        assertEquals("Source revision must not trigger another model request", 1, hits())
    }

    private fun mutate(path: String, payload: JSONObject): JSONObject = engine!!.request(path,
        payload.put("request_id", UUID.randomUUID().toString()))

    private fun hits(): Int = fixture!!.callAttr("__getitem__", "server").get("calls")!!.toInt()

    private companion object {
        val FIXTURE = """
            import json
            import threading
            from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

            class SyntheticChatHandler(BaseHTTPRequestHandler):
                def log_message(self, *args):
                    pass

                def do_POST(self):
                    self.server.calls += 1
                    try:
                        assert self.path == '/v1/chat/completions'
                        assert self.headers.get('Authorization') is None
                        size = int(self.headers.get('Content-Length', '0'))
                        assert 0 < size <= 65536
                        body = json.loads(self.rfile.read(size))
                        assert body['model'] == 'phone-synthetic-chat-fixture'
                        assert body['stream'] is False
                        assert body['max_tokens'] == 2048
                        assert body['response_format'] == {'type': 'json_object'}
                        assert body['messages'][-1]['role'] == 'user'
                        prompt = json.loads(body['messages'][-1]['content'])
                        assert prompt['question'] == '队列的处理顺序是什么？'
                        evidence = prompt['evidence'][0]
                        assert evidence['text'] == '队列按照先进先出的顺序处理元素。'
                        assert evidence['evidence_id'].startswith('ev_')
                        answer = {'status': 'ready', 'claims': [{'text': evidence['text'],
                                  'evidence_ids': [evidence['evidence_id']]}], 'missing_information': []}
                        wire = {'choices': [{'finish_reason': 'stop',
                                'message': {'content': json.dumps(answer, ensure_ascii=False)}}]}
                        encoded = json.dumps(wire, ensure_ascii=False).encode('utf-8')
                        self.send_response(200)
                        self.send_header('Content-Type', 'application/json')
                        self.send_header('Content-Length', str(len(encoded)))
                        self.end_headers()
                        self.wfile.write(encoded)
                    except Exception:
                        self.server.failed = True
                        self.send_error(500, 'Synthetic fixture rejected request')

            server = ThreadingHTTPServer(('127.0.0.1', 0), SyntheticChatHandler)
            server.calls = 0
            server.failed = False
            port = server.server_port
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()

            def stop_fixture():
                server.shutdown()
                server.server_close()
                worker.join(5)
                return not worker.is_alive()
        """.trimIndent()
    }
}
