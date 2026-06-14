package com.moeavatar.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * OpenAI 兼容后端：HTTP POST /v1/chat/completions，stream=true，自己解 SSE。
 *
 * baseUrl 可填官方（https://api.openai.com）或自建网关，结尾有没有 /v1 都行——我们补齐。
 */
class OpenAiLlmBackend(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val systemPrompt: String? = null,
) : LlmBackend {

    override val displayName: String = "API · $model"

    @Volatile private var prepared = false
    private val currentCall = AtomicReference<Call?>(null)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)   // SSE 不超时
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    override val ready: Boolean
        get() = prepared

    override suspend fun prepare(): Boolean {
        prepared = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
        return prepared
    }

    override fun chat(history: List<ChatTurn>): Flow<String> = callbackFlow<String> {
        if (!ready) {
            close(IllegalStateException("openai backend not configured"))
            return@callbackFlow
        }

        val url = buildChatUrl(baseUrl)
        val body = buildBody(history).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(body)
            .build()

        val call = client.newCall(req)
        currentCall.set(call)

        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val msg = "HTTP ${resp.code}: ${resp.body?.string()?.take(500)}"
                    Log.e(TAG, msg)
                    close(RuntimeException(msg))
                    return@callbackFlow
                }
                val source = resp.body?.source() ?: run {
                    close(RuntimeException("empty response body"))
                    return@callbackFlow
                }
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isEmpty()) continue
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    val token = parseToken(payload) ?: continue
                    val ok = trySend(token).isSuccess
                    if (!ok) break
                }
            }
            close()
        } catch (t: Throwable) {
            if (call.isCanceled()) {
                close()
            } else {
                Log.e(TAG, "openai stream error", t)
                close(t)
            }
        } finally {
            currentCall.compareAndSet(call, null)
        }

        awaitClose {
            currentCall.get()?.cancel()
        }
    }.flowOn(Dispatchers.IO)

    override fun stop() {
        currentCall.getAndSet(null)?.cancel()
    }

    override fun resetSession() {
        // 无状态：每轮 chat 都把 history 整体发过去
        stop()
    }

    override fun release() {
        stop()
        prepared = false
    }

    private fun buildBody(history: List<ChatTurn>): JSONObject {
        val messages = JSONArray()
        if (!systemPrompt.isNullOrBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        }
        for (turn in history) {
            val role = when (turn.role) {
                ChatTurn.Role.USER -> "user"
                ChatTurn.Role.ASSISTANT -> "assistant"
                ChatTurn.Role.SYSTEM -> "system"
            }
            messages.put(JSONObject().put("role", role).put("content", turn.content))
        }
        return JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("messages", messages)
    }

    private fun parseToken(payload: String): String? {
        return try {
            val obj = JSONObject(payload)
            val choices = obj.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val delta = choices.getJSONObject(0).optJSONObject("delta") ?: return null
            val content = delta.optString("content", "")
            if (content.isEmpty()) null else content
        } catch (t: Throwable) {
            Log.w(TAG, "bad SSE payload: $payload", t)
            null
        }
    }

    private fun buildChatUrl(base: String): String {
        val trimmed = base.trimEnd('/')
        return when {
            trimmed.endsWith("/chat/completions") -> trimmed
            trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
            else -> "$trimmed/v1/chat/completions"
        }
    }

    companion object {
        private const val TAG = "OpenAiLlmBackend"
    }
}
