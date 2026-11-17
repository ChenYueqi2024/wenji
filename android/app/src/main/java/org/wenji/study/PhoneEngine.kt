package org.wenji.study

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.File

/** In-process backend. Call from Dispatchers.IO, never the UI thread. */
internal class PhoneEngine(
    context: Context,
    configJson: String? = null,
    secretsJson: String = "{}",
    dbName: String = "phone-engine.sqlite3",
) : AutoCloseable {
    private val bridge: PyObject

    init {
        require(dbName.matches(Regex("[A-Za-z0-9._-]+")) && dbName != "." && dbName != "..") {
            "手机数据库名称无效"
        }
        bridge = try {
            val appContext = context.applicationContext
            val python = synchronized(startLock) {
                if (!Python.isStarted()) Python.start(AndroidPlatform(appContext))
                Python.getInstance()
            }
            python.getModule("server.mobile_bridge").get("MobileEngine")!!.call(
                File(appContext.filesDir, dbName).absolutePath, configJson, secretsJson,
            )
        } catch (_: Exception) {
            // A PyException may contain private input in its message or cause.
            // Do not attach it, print it, or propagate it to UI error handlers.
            throw IllegalStateException("手机内置服务启动失败，请检查模型配置并重试。")
        }
    }

    fun request(path: String, body: JSONObject? = null): JSONObject =
        invoke("dispatch", path, body?.toString())

    fun configure(configJson: String? = null, secretsJson: String = "{}"): JSONObject =
        invoke("configure", configJson, secretsJson)

    private fun invoke(method: String, vararg args: Any?): JSONObject {
        try {
            val envelope = JSONObject(bridge.callAttr(method, *args).toString())
            val status = envelope.getInt("status")
            val body = envelope.getJSONObject("body")
            if (status !in 200..299) {
                throw ApiFailure(status, body.optJSONObject("error")?.optString("message")
                    ?.takeIf { it.isNotBlank() } ?: "手机内置服务未能完成操作")
            }
            return body
        } catch (failure: ApiFailure) {
            // Only the bridge's sanitized error envelope reaches callers.
            throw failure
        } catch (_: Exception) {
            throw IllegalStateException("手机内置服务暂时不可用，已保存内容仍保留。")
        }
    }

    override fun close() {
        try {
            bridge.callAttr("close")
        } catch (_: Exception) {
            throw IllegalStateException("手机内置服务未能完成关闭。")
        }
    }

    companion object {
        private val startLock = Any()
        @Volatile private var instance: PhoneEngine? = null

        /** Configuration is read by the caller before the first database access. */
        fun get(
            context: Context,
            initialConfigJson: String? = null,
            initialSecretsJson: String = "{}",
        ): PhoneEngine = instance ?: synchronized(startLock) {
            instance ?: PhoneEngine(context, initialConfigJson, initialSecretsJson).also { instance = it }
        }
    }
}
