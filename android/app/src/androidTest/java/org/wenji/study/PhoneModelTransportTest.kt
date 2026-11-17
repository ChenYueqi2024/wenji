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

    @Test
    fun testPhoneGeneralReferenceIsExplicitQuestionOnlyAndSeparateFromEvidence() {
        val port = fixture!!.callAttr("__getitem__", "port").toInt()
        val profile = JSONObject()
            .put("protocol", "openai_chat")
            .put("base_url", "http://127.0.0.1:$port/v1")
            .put("model", "phone-synthetic-chat-fixture")
            .put("auth_mode", "none")
            .put("label", "手机本机合成协议测试")
            .put("timeout_seconds", 10)
        engine = PhoneEngine(appContext, dbName = dbName)
        val info = engine!!.configure(profile.toString()).getJSONObject("provider_info")
        val questionId = mutate("/questions", JSONObject().put("text", "什么是强化学习？"))
            .getJSONObject("result").getString("question_id")
        fun consent() = JSONObject()
            .put("allow_remote", true)
            .put("provider_fingerprint", info.getString("fingerprint"))
            .put("question_revision", 1)

        // Having a configured model must not silently change evidence mode into general chat.
        val evidenceState = mutate("/questions/$questionId/explain", consent()).getJSONObject("state")
        val evidenceAnswer = evidenceState.getJSONArray("questions").getJSONObject(0).getJSONObject("answer")
        assertEquals("needs_material", evidenceAnswer.getString("status"))
        assertEquals(0, evidenceAnswer.getJSONArray("claims").length())
        assertEquals("Missing evidence must not spend a model request", 0, hits())

        val refused = listOf(
            consent(),
            consent().put("purpose", "evidence_answer"),
            consent().put("purpose", "general_reference").put("allow_remote", false),
            consent().put("purpose", "general_reference").put("provider_fingerprint", "stale-fingerprint"),
            consent().put("purpose", "general_reference").put("question_revision", 2),
        )
        refused.forEach { payload ->
            try {
                mutate("/questions/$questionId/reference", payload)
                fail("General reference must require its own purpose, consent and current context")
            } catch (failure: ApiFailure) { assertEquals(409, failure.status) }
        }
        assertEquals("Rejected confirmations must not send a model request", 0, hits())

        // Even when a document exists, the general-reference wire may contain only the question.
        val before = mutate("/sources", JSONObject().put("title", "不应外发的合成材料")
            .put("text", "LOCAL_ONLY_MATERIAL_SENTINEL：缓存记录仅用于本地合成测试。"))
            .getJSONObject("state")
        val beforeAnswer = before.getJSONArray("questions").getJSONObject(0).getJSONObject("answer")
        val request = consent().put("purpose", "general_reference")
            .put("request_id", UUID.randomUUID().toString())
        val response = engine!!.request("/questions/$questionId/reference", request)
        assertEquals("One explicit reference produces exactly one local synthetic call", 1, hits())
        assertFalse("Reference wire must contain no source text or evidence field",
            fixture!!.callAttr("__getitem__", "server").get("failed")!!.toBoolean())
        val state = response.getJSONObject("state")
        val question = state.getJSONArray("questions").getJSONObject(0)
        val reference = question.getJSONObject("reference")
        assertEquals("general_knowledge", reference.getString("kind"))
        assertEquals("unverified", reference.getString("status"))
        assertEquals("合成参考：强化学习通过与环境交互，根据奖励反馈调整策略。", reference.getString("text"))
        assertEquals(1, reference.getInt("question_revision"))
        assertTrue(reference.getString("notice").isNotBlank())
        assertFalse("General reference must not acquire evidence fields", reference.has("claims"))
        assertFalse("General reference must not acquire evidence fields", reference.has("evidence"))
        assertEquals(beforeAnswer.getString("id"), question.getJSONObject("answer").getString("id"))
        assertEquals(beforeAnswer.getString("status"), question.getJSONObject("answer").getString("status"))
        assertEquals("General knowledge has its own explicitly labelled review card", 1, state.getJSONArray("cards").length())
        val card = state.getJSONArray("cards").getJSONObject(0)
        val cardId = card.getString("id")
        assertEquals(response.getJSONObject("result").getString("card_id"), cardId)
        assertEquals(reference.getString("card_id"), cardId)
        assertEquals("general_reference", card.getString("basis"))
        assertEquals(reference.getString("id"), card.getString("reference_id"))
        assertTrue(card.getString("basis_notice").contains("尚无课程依据"))
        assertEquals("awaiting_attempt", card.getString("status"))
        assertFalse("The reference must stay hidden until the learner submits an answer", card.has("rubric"))
        assertEquals(0, question.getJSONObject("answer").getJSONArray("claims").length())

        val learnerAnswer = "先与环境交互，再根据奖励反馈调整选择。"
        val attempted = mutate("/cards/$cardId/attempts", JSONObject().put("answer", learnerAnswer))
        val attemptId = attempted.getJSONObject("result").getString("attempt_id")
        val attempt = attempted.getJSONObject("state").getJSONArray("attempts").getJSONObject(0)
        val feedback = attempt.getJSONObject("feedback")
        assertEquals(learnerAnswer, attempt.getString("answer"))
        assertEquals("general_reference", attempt.getString("basis"))
        assertEquals("general-reference-comparison", feedback.getString("mode"))
        assertEquals(reference.getString("text"), feedback.getJSONArray("reference_points").getString(0))
        assertTrue(feedback.isNull("automatic_grade"))
        val confirmed = mutate("/cards/$cardId/confirm", JSONObject()
            .put("attempt_id", attemptId).put("self_rating", "understood")).getJSONObject("state")
        assertEquals("reviewing", confirmed.getJSONArray("cards").getJSONObject(0).getString("status"))
        assertTrue(confirmed.getJSONArray("attempts").getJSONObject(0).getBoolean("confirmed"))
        assertEquals("understood", confirmed.getJSONArray("attempts").getJSONObject(0).getString("self_rating"))
        assertEquals("General-reference self assessment must not assert course mastery",
            question.getString("state"), confirmed.getJSONArray("questions").getJSONObject(0).getString("state"))
        assertEquals("Saving an answer and self assessment remain entirely local", 1, hits())

        val replay = engine!!.request("/questions/$questionId/reference", request).getJSONObject("result")
        assertTrue(replay.getBoolean("replayed"))
        assertEquals(response.getJSONObject("result").getString("reference_id"), replay.getString("reference_id"))
        assertEquals("Replay must not create another review card", cardId, replay.getString("card_id"))
        assertEquals("Replay must not spend another model call", 1, hits())

        val revised = mutate("/questions/$questionId", JSONObject().put("base_revision", 1)
            .put("text", "强化学习中的奖励是什么？")).getJSONObject("state")
        val staleReference = revised.getJSONArray("questions").getJSONObject(0).getJSONObject("reference")
        assertEquals("needs_review", staleReference.getString("status"))
        assertEquals(reference.getString("id"), staleReference.getString("id"))
        assertEquals(reference.getString("text"), staleReference.getString("text"))
        assertEquals("needs_review", revised.getJSONArray("cards").getJSONObject(0).getString("status"))
        assertEquals(learnerAnswer, revised.getJSONArray("attempts").getJSONObject(0).getString("answer"))
        assertEquals("Question edits must not automatically regenerate references", 1, hits())
        try {
            mutate("/questions/$questionId/reference", consent().put("purpose", "general_reference"))
            fail("A confirmation for the previous question revision must be rejected")
        } catch (failure: ApiFailure) { assertEquals(409, failure.status) }
        assertEquals(1, hits())

        // Reopening without model configuration still permits offline viewing of saved references.
        engine!!.close()
        engine = PhoneEngine(appContext, dbName = dbName)
        val restored = engine!!.request("/state?course_id=os-default")
        val restoredReference = restored.getJSONArray("questions").getJSONObject(0).getJSONObject("reference")
        assertEquals(reference.getString("id"), restoredReference.getString("id"))
        assertEquals("needs_review", restoredReference.getString("status"))
        assertEquals(1, restored.getJSONArray("cards").length())
        assertEquals("general_reference", restored.getJSONArray("cards").getJSONObject(0).getString("basis"))
        assertEquals(attemptId, restored.getJSONArray("attempts").getJSONObject(0).getString("id"))
        assertEquals(learnerAnswer, restored.getJSONArray("attempts").getJSONObject(0).getString("answer"))
        assertEquals("Offline reads must not send model requests", 1, hits())
    }

    @Test
    fun testPhoneCourseAnswerSupersedesGeneralReviewButPreservesPriorWork() {
        val port = fixture!!.callAttr("__getitem__", "port").toInt()
        val profile = JSONObject()
            .put("protocol", "openai_chat")
            .put("base_url", "http://127.0.0.1:$port/v1")
            .put("model", "phone-synthetic-chat-fixture")
            .put("auth_mode", "none")
            .put("label", "手机本机合成协议测试")
            .put("timeout_seconds", 10)
        engine = PhoneEngine(appContext, dbName = dbName)
        val info = engine!!.configure(profile.toString()).getJSONObject("provider_info")
        val questionId = mutate("/questions", JSONObject().put("text", "什么是强化学习？"))
            .getJSONObject("result").getString("question_id")
        fun consent() = JSONObject()
            .put("allow_remote", true)
            .put("provider_fingerprint", info.getString("fingerprint"))
            .put("question_revision", 1)

        val general = mutate("/questions/$questionId/reference", consent().put("purpose", "general_reference"))
        val generalCardId = general.getJSONObject("result").getString("card_id")
        val originalReference = general.getJSONObject("state").getJSONArray("questions").getJSONObject(0)
            .getJSONObject("reference")
        assertTrue("A general reference must not masquerade as a course answer",
            general.getJSONObject("state").getJSONArray("questions").getJSONObject(0).isNull("answer"))
        val learnerAnswer = "通过奖励反馈不断调整策略，但还需用课程材料核对定义。"
        val attempted = mutate("/cards/$generalCardId/attempts", JSONObject().put("answer", learnerAnswer))
        val attemptId = attempted.getJSONObject("result").getString("attempt_id")
        val originalFeedback = attempted.getJSONObject("state").getJSONArray("attempts").getJSONObject(0)
            .getJSONObject("feedback")
        mutate("/cards/$generalCardId/confirm", JSONObject().put("attempt_id", attemptId).put("self_rating", "again"))
        assertEquals("General explanation is the only network request so far", 1, hits())

        val courseText = "强化学习是一种通过与环境交互，根据奖励信号学习决策策略的方法。"
        val imported = mutate("/sources", JSONObject().put("title", "合成强化学习课程材料").put("text", courseText))
        val sourceId = imported.getJSONObject("result").getString("source_id")
        val importedState = imported.getJSONObject("state")
        assertEquals("Import must never silently call a model", 1, hits())
        assertTrue(importedState.getJSONArray("questions").getJSONObject(0).isNull("answer"))
        assertEquals("unverified", importedState.getJSONArray("questions").getJSONObject(0)
            .getJSONObject("reference").getString("status"))
        assertEquals("reviewing", importedState.objects("cards").single { it.getString("id") == generalCardId }.getString("status"))
        try {
            mutate("/questions/$questionId/explain", JSONObject())
            fail("Course upgrade requires a new explicit network confirmation")
        } catch (failure: ApiFailure) { assertEquals(409, failure.status) }
        assertEquals(1, hits())

        val courseRequest = consent().put("request_id", UUID.randomUUID().toString())
        val upgraded = engine!!.request("/questions/$questionId/explain", courseRequest)
        assertEquals("One separate course request after confirmation", 2, hits())
        assertFalse(fixture!!.callAttr("__getitem__", "server").get("failed")!!.toBoolean())
        val upgradedState = upgraded.getJSONObject("state")
        val question = upgradedState.getJSONArray("questions").getJSONObject(0)
        val citedAnswer = question.getJSONObject("answer")
        val answerId = citedAnswer.getString("id")
        assertEquals("ready", citedAnswer.getString("status"))
        val evidence = citedAnswer.getJSONArray("claims").getJSONObject(0).getJSONArray("evidence").getJSONObject(0)
        assertEquals(sourceId, evidence.getString("source_id"))
        assertEquals(courseText, evidence.getString("quote"))
        assertEquals(1, evidence.getInt("revision"))
        assertEquals(1, evidence.getInt("page"))
        val retainedReference = question.getJSONObject("reference")
        assertEquals(originalReference.getString("id"), retainedReference.getString("id"))
        assertEquals(originalReference.getString("text"), retainedReference.getString("text"))
        assertEquals("superseded", retainedReference.getString("status"))
        assertEquals(answerId, retainedReference.getString("superseded_by_answer_id"))
        assertEquals(2, upgradedState.getJSONArray("cards").length())
        val oldCard = upgradedState.objects("cards").single { it.getString("id") == generalCardId }
        assertEquals("general_reference", oldCard.getString("basis"))
        assertEquals("needs_review", oldCard.getString("status"))
        assertTrue(oldCard.isNull("due_at"))
        assertEquals(answerId, oldCard.getString("superseded_by_answer_id"))
        val activeCard = upgradedState.objects("cards").single { it.getString("status") != "needs_review" }
        assertEquals("course_evidence", activeCard.getString("basis"))
        assertEquals(answerId, activeCard.getString("answer_id"))
        assertEquals(upgraded.getJSONObject("result").getString("card_id"), activeCard.getString("id"))
        assertEquals("awaiting_attempt", activeCard.getString("status"))
        val oldAttempt = upgradedState.getJSONArray("attempts").getJSONObject(0)
        assertEquals(attemptId, oldAttempt.getString("id"))
        assertEquals(learnerAnswer, oldAttempt.getString("answer"))
        assertEquals("general_reference", oldAttempt.getString("basis"))
        assertTrue(oldAttempt.getBoolean("confirmed"))
        assertEquals("again", oldAttempt.getString("self_rating"))
        assertEquals(originalFeedback.toString(), oldAttempt.getJSONObject("feedback").toString())
        assertEquals(answerId, oldAttempt.getString("superseded_by_answer_id"))
        try {
            mutate("/cards/$generalCardId/attempts", JSONObject().put("answer", "旧卡不应继续接收新作答"))
            fail("The superseded general card must no longer accept active review")
        } catch (failure: ApiFailure) { assertEquals(409, failure.status) }
        val replay = engine!!.request("/questions/$questionId/explain", courseRequest)
        assertTrue(replay.getJSONObject("result").getBoolean("replayed"))
        assertEquals(2, replay.getJSONObject("state").getJSONArray("cards").length())
        assertEquals("Import, study, state reads and replay must not add model calls", 2, hits())

        engine!!.close()
        engine = PhoneEngine(appContext, dbName = dbName)
        val restored = engine!!.request("/state?course_id=os-default")
        assertEquals(answerId, restored.getJSONArray("questions").getJSONObject(0).getJSONObject("answer").getString("id"))
        assertEquals(originalReference.getString("text"), restored.getJSONArray("questions").getJSONObject(0)
            .getJSONObject("reference").getString("text"))
        assertEquals(learnerAnswer, restored.getJSONArray("attempts").getJSONObject(0).getString("answer"))
        assertEquals(2, restored.getJSONArray("cards").length())
        assertEquals(2, hits())
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
                        if prompt['question'] == '什么是强化学习？' and 'evidence' not in prompt:
                            assert set(prompt) == {'question'}
                            assert 'LOCAL_ONLY_MATERIAL_SENTINEL' not in json.dumps(body, ensure_ascii=False)
                            answer = {'text': '合成参考：强化学习通过与环境交互，根据奖励反馈调整策略。'}
                        else:
                            assert prompt['question'] in ('队列的处理顺序是什么？', '什么是强化学习？')
                            evidence = prompt['evidence'][0]
                            expected = ('强化学习是一种通过与环境交互，根据奖励信号学习决策策略的方法。'
                                        if prompt['question'] == '什么是强化学习？' else '队列按照先进先出的顺序处理元素。')
                            assert evidence['text'] == expected
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
