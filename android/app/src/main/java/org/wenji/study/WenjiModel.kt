package org.wenji.study

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class FailureNotice(val sequence: Long, val summary: String)
internal data class ExplainConsent(val questionId: String, val question: String, val questionRevision: Int, val provider: ModelInfo, val done: () -> Unit)
class WenjiModel(app: Application) : AndroidViewModel(app) {
    private val db = LocalStore.create(app)
    internal val editors = EditorSession()
    private var failureSequence = 0L
    var failure by mutableStateOf<FailureNotice?>(null); private set
    var state by mutableStateOf(JSONObject()); private set
    var pending by mutableStateOf(emptyList<PendingQuestion>()); private set
    var writes by mutableStateOf(emptyList<PendingWrite>()); private set
    var busy by mutableStateOf(false); private set
    var connected by mutableStateOf(false); private set
    var message by mutableStateOf("正在读取本地记录"); private set
    var fetchedAt by mutableLongStateOf(0); private set
    var searchResult by mutableStateOf(JSONObject()); private set
    private var providerInfoFresh by mutableStateOf(false)
    internal var explainConsent by mutableStateOf<ExplainConsent?>(null); private set
    internal val providerInfo: ModelInfo get() = state.optJSONObject("provider_info")?.let {
        ModelInfo(it.optString("mode"), it.optString("protocol"), it.optString("model"), it.optString("endpoint"),
            it.optString("label"), it.optString("fingerprint"), it.optBoolean("requires_consent"))
    } ?: ModelInfo()
    internal val providerConfigurationFresh: Boolean get() = connected && providerInfoFresh
    init { viewModelScope.launch { loadLocal(); refresh() } }

    private suspend fun loadLocal() {
        try {
        pending = db.dao().pending()
        writes = db.dao().writes()
        val chunks = db.dao().cache()
        if (chunks.isNotEmpty()) { state = JSONObject(chunks.joinToString("") { it.json }); fetchedAt = chunks.first().fetchedAt }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            connected = false
            reportFailure("本地记录读取失败，请保留应用数据以便排查。", "本地记录读取失败：${e.message}。请保留应用数据以便排查。")
        }
    }
    private suspend fun request(path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val c = URL("http://127.0.0.1:8765$path").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 4000; c.readTimeout = if (path.endsWith("/explain")) 75000 else 12000
            if (body != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = c.responseCode
            val data = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = if (data.isEmpty()) JSONObject() else JSONObject(data)
            if (status !in 200..299) throw ApiFailure(status, json.optJSONObject("error")?.optString("message") ?: "HTTP $status")
            json
        } finally { c.disconnect() }
    }
    private suspend fun cache(json: JSONObject) {
        val at = System.currentTimeMillis()
        db.withTransaction { persistCache(json, at) }
        state = json; fetchedAt = at
        providerInfoFresh = json.optJSONObject("provider_info") != null
        searchResult = JSONObject()
    }
    private suspend fun persistCache(json: JSONObject, at: Long) {
        // Small TEXT rows avoid Android CursorWindow's single-row limit.
        db.dao().clearCache()
        db.dao().saveCache(storageChunks(json.toString()).mapIndexed { index, chunk -> CachedState(index, chunk, at) })
    }
    private fun operation(block: suspend () -> Unit) {
        if (busy) { reportFailure("正在处理上一项操作，请稍后再试。"); return }
        busy = true
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                when (e) {
                    is ApiFailure -> {
                        providerInfoFresh = false
                        reportFailure("服务拒绝：${e.message}。请核对后重试。")
                    }
                    is IllegalArgumentException -> reportFailure("输入无效：${e.message ?: "请检查输入内容"}")
                    is IllegalStateException -> reportFailure("操作未完成：${e.message ?: "请检查当前状态"}")
                    else -> {
                        connected = false
                        providerInfoFresh = false
                        reportFailure("操作失败，已保存内容仍保留。请检查连接并重试。", "操作失败：${e.message ?: "未知错误"}。已保存的疑问与缓存仍保留。")
                    }
                }
            } finally { busy = false; loadLocal() }
        }
    }
    fun reportFailure(summary: String, detail: String = summary) {
        message = detail
        // A new sequence also surfaces consecutive failures with identical messages.
        failure = FailureNotice(++failureSequence, summary)
    }
    fun acknowledgeFailure(sequence: Long) {
        if (failure?.sequence == sequence) failure = null
    }
    fun refresh() = operation {
        val pendingFailure = syncPendingAndRefresh(
            syncPending = {
                for (write in db.dao().writes()) sendWrite(write)
                for (q in db.dao().pending()) {
                    val res = request("/questions", JSONObject().put("request_id", q.requestId).put("text", q.text))
                    val newState = res.getJSONObject("state")
                    db.withTransaction {
                        persistCache(newState, System.currentTimeMillis())
                        db.dao().removePending(q.requestId)
                    }
                }
            },
            refreshState = {
                cache(request("/state?course_id=os-default"))
                connected = true
            }
        )
        if (pendingFailure == null) {
            message = "已同步 · ${providerInfo.statusSummary(providerConfigurationFresh)}"
        } else {
            reportFailure("已更新状态，待同步内容仍保留：${pendingFailure.message}。请检查待同步列表。")
        }
    }
    fun saveQuestion(text: String, saved: () -> Unit) = operation {
        require(text.isNotBlank()) { "疑问不能为空" }
        require(text.length <= 2000) { "疑问最多2000字符，请拆分记录" }
        db.dao().insert(PendingQuestion(UUID.randomUUID().toString(), text.trim(), System.currentTimeMillis()))
        message = "疑问已写入本地；点击同步后送往本机服务"; saved()
    }
    fun removePendingQuestion(id: String) = operation { db.dao().removePending(id); message = "已移除本机待同步疑问" }
    fun search(query: String) = operation {
        searchResult = request("/search?course_id=os-default&q=" + java.net.URLEncoder.encode(query, "UTF-8"))
        connected = true; message = "已检索当前资料；候选片段不代表答案正确"
    }
    fun requestExplain(questionId: String, done: () -> Unit = {}) {
        if (!canWrite()) { reportFailure("请先同步或处理待同步操作，再查找依据。"); return }
        val question = state.objects("questions").find { it.optString("id") == questionId }
        if (question == null) { reportFailure("疑问已变化，请同步后重试。"); return }
        val info = providerInfo
        when (explainAction(info, providerConfigurationFresh)) {
            ExplainAction.DEMO -> submitMutation("/questions/$questionId/explain", done = done)
            ExplainAction.CONFIRM_REMOTE -> {
                val revision = question.optInt("revision")
                if (revision <= 0) { reportFailure("疑问版本不可用，请同步后重试。"); return }
                explainConsent = ExplainConsent(questionId, question.optString("text"), revision, info, done)
            }
            ExplainAction.BLOCKED -> reportFailure("模型配置仅为缓存或不完整，请先同步核对，再查找依据。")
        }
    }
    fun cancelExplain() { explainConsent = null }
    fun confirmExplain() {
        val consent = explainConsent ?: return
        explainConsent = null
        val revision = state.objects("questions").find { it.optString("id") == consent.questionId }?.optInt("revision")
        if (!canWrite() || !canConfirmExplanation(consent.provider, providerInfo, providerConfigurationFresh)
            || !consentQuestionMatches(consent.questionRevision, revision)) {
            reportFailure("连接、疑问或模型配置已变化，请同步后重新查找依据并确认。"); return
        }
        submitMutation("/questions/${consent.questionId}/explain", JSONObject()
            .put("allow_remote", true).put("provider_fingerprint", consent.provider.fingerprint)
            .put("question_revision", consent.questionRevision), consent.done)
    }
    fun mutate(path: String, body: JSONObject = JSONObject(), done: () -> Unit = {}) {
        // All new explanation requests must pass through the mode check and consent flow above.
        if (path.endsWith("/explain")) { reportFailure("请从查找依据入口确认本次请求。"); return }
        submitMutation(path, body, done)
    }
    private fun submitMutation(path: String, body: JSONObject = JSONObject(), done: () -> Unit = {}) = operation {
        check(db.dao().writes().isEmpty()) { "已有待同步操作，请先同步或撤销本地重试" }
        val id = UUID.randomUUID().toString()
        body.put("request_id", id).put("course_id", "os-default")
        val write = PendingWrite(id, path, System.currentTimeMillis())
        db.withTransaction {
            db.dao().insertWrite(write)
            db.dao().insertChunks(storageChunks(body.toString()).mapIndexed { i, chunk -> PendingWriteChunk(id, i, chunk) })
        }
        sendWrite(write)
        connected = true; message = "操作成功 · 请核对原文与状态"; done()
    }
    private suspend fun sendWrite(write: PendingWrite) {
        val body = db.dao().writeChunks(write.requestId).joinToString("") { it.body }
        val res = request(write.path, JSONObject(body))
        val newState = res.getJSONObject("state")
        val at = System.currentTimeMillis()
        db.withTransaction {
            persistCache(newState, at)
            db.dao().removeWrite(write.requestId)
            db.dao().removeChunks(write.requestId)
        }
        state = newState; fetchedAt = at
        providerInfoFresh = newState.optJSONObject("provider_info") != null
        searchResult = JSONObject()
    }
    fun discardRetry(id: String) = operation {
        db.withTransaction { db.dao().removeWrite(id); db.dao().removeChunks(id) }
        // This only abandons a client retry. The server may already have committed the operation.
        cache(request("/state?course_id=os-default")); connected = true
        message = "已停止本地重试，并重新获取服务状态；已生效的服务操作不会撤销"
    }
}

fun JSONObject.objects(key: String): List<JSONObject> {
    val a = optJSONArray(key) ?: return emptyList()
    return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}
fun JSONObject.strings(key: String): List<String> {
    val a = optJSONArray(key) ?: return emptyList()
    return (0 until a.length()).map { a.optString(it) }
}
