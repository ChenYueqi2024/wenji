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
import java.util.UUID

data class FailureNotice(val sequence: Long, val summary: String)
internal data class ExplainConsent(val questionId: String, val question: String, val questionRevision: Int, val provider: ModelInfo, val done: () -> Unit)
class WenjiModel(app: Application) : AndroidViewModel(app) {
    private val db = LocalStore.create(app)
    private val settings = StandaloneSettings(app)
    private var engine: PhoneEngine? = null
    internal val hasDesktopRecords = app.getDatabasePath("wenji.db").exists()
    internal var settingsForm by mutableStateOf<ModelSettingsForm?>(null); private set
    internal var settingsError by mutableStateOf<String?>(null); private set
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
    private fun localEngine(): PhoneEngine {
        engine?.let { return it }
        val profile = settings.load()
        val candidate = PhoneEngine.get(getApplication(), profile.configJson, profile.secretsJson)
        // Reapply persisted settings if a previous save/rollback failed in this process.
        candidate.configure(profile.configJson, profile.secretsJson)
        engine = candidate
        return candidate
    }
    private suspend fun request(path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        localEngine().request(path, body)
    }
    fun openSettings() = operation {
        settingsError = null
        settingsForm = try { withContext(Dispatchers.IO) { settings.loadForm() } }
        catch (_: Exception) {
            settingsError = "保存的模型配置无法读取。请先清除配置恢复规则演示，再重新填写；学习资料仍保留。"
            ModelSettingsForm()
        }
    }
    fun closeSettings() { if (!busy) { settingsForm = null; settingsError = null } }
    internal fun saveSettings(form: ModelSettingsForm) = operation {
        try {
            val newState = withContext(Dispatchers.IO) {
                val previous = settings.load()
                val prepared = settings.prepare(form)
                val current = localEngine()
                val response = current.configure(prepared.configJson, prepared.secretsJson)
                try { settings.save(prepared) }
                catch (failure: Exception) {
                    engine = null
                    try { current.configure(previous.configJson, previous.secretsJson) } catch (_: Exception) { }
                    throw IllegalStateException("模型配置未能安全保存，请重试。")
                }
                response.getJSONObject("state")
            }
            cache(newState); connected = true; explainConsent = null
            settingsForm = null; settingsError = null
            message = "模型配置已保存到手机；尚未调用模型，生成前仍需单次确认"
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            providerInfoFresh = false
            settingsError = if (e is IllegalArgumentException || e is ApiFailure || e is IllegalStateException) e.message else "模型配置未能安全保存，请检查输入后重试。"
        }
    }
    fun useDemo() = operation {
        val newState = withContext(Dispatchers.IO) {
            val current = PhoneEngine.get(getApplication())
            val response = current.configure(null, "{}")
            try { settings.clear() } catch (_: Exception) {
                engine = null
                throw IllegalStateException("模型配置未能清除，请重试。")
            }
            engine = current
            response.getJSONObject("state")
        }
        cache(newState); connected = true; explainConsent = null; settingsForm = null; settingsError = null
        message = "已清除手机模型配置，使用本地规则演示；学习资料保留"
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
                        reportFailure("操作失败，已保存内容仍保留。请刷新或检查手机模型设置。")
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
        var explanationNeedsReview = false
        val pendingFailure = syncPendingAndRefresh(
            syncPending = {
                for (write in db.dao().writes()) {
                    if (!mayAutoRetry(write.path)) { explanationNeedsReview = true; break }
                    sendWrite(write)
                }
                if (explanationNeedsReview) return@syncPendingAndRefresh
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
        if (explanationNeedsReview) {
            reportFailure("已更新本机状态；上次解释请求仍待核对。请在本机与模型说明中停止旧重试，核对结果后再决定是否重新生成。")
        } else if (pendingFailure == null) {
            message = "本机记录已更新 · ${providerInfo.statusSummary(providerConfigurationFresh)}"
        } else {
            reportFailure("已更新状态，待同步内容仍保留：${pendingFailure.message}。请检查待同步列表。")
        }
    }
    fun saveQuestion(text: String, saved: () -> Unit) = operation {
        require(text.isNotBlank()) { "疑问不能为空" }
        require(text.length <= 2000) { "疑问最多2000字符，请拆分记录" }
        db.dao().insert(PendingQuestion(UUID.randomUUID().toString(), text.trim(), System.currentTimeMillis()))
        message = "疑问已写入本地"; saved()
        // This only saves questions to the embedded engine; never replay a pending model call here.
        for (q in db.dao().pending()) {
            val res = request("/questions", JSONObject().put("request_id", q.requestId).put("text", q.text))
            val newState = res.getJSONObject("state")
            db.withTransaction { persistCache(newState, System.currentTimeMillis()); db.dao().removePending(q.requestId) }
        }
        cache(request("/state?course_id=os-default")); connected = true
        message = "疑问已写入本地；不需要网络或电脑"
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
    fun importDemoMaterial() = operation {
        val title = "合成教材：资源分配图与死锁"
        if (state.objects("sources").any { it.optString("title") == title && !it.optBoolean("deleted") }) {
            message = "合成教材已在资料列表中，可记录演示疑问并查找依据"
            return@operation
        }
        val bytes = withContext(Dispatchers.IO) {
            getApplication<Application>().assets.open("fixtures/resource_graph_v1.pdf").use { it.readBytes() }
        }
        performMutation("/sources", JSONObject().put("title", title)
            .put("pdf_base64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)))
    }
    private fun submitMutation(path: String, body: JSONObject = JSONObject(), done: () -> Unit = {}) = operation {
        performMutation(path, body, done)
    }
    private suspend fun performMutation(path: String, body: JSONObject, done: () -> Unit = {}) {
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
