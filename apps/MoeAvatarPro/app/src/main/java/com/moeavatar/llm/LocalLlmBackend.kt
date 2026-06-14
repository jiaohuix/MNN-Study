package com.moeavatar.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 端侧 MNN-LLM 后端。给定一个 config.json 路径，load 后即可 chat。
 *
 * 历史拼接策略：用 ChatML 风格的简单拼接（user/assistant 各占一行 + role 前缀），
 * 末尾拼一个 "<|assistant|>\n" 让模型续写。MNN 的 Llm::response 会负责 KV 缓存。
 *
 * @property configPath 形如 /sdcard/.../<model_dir>/config.json
 */
class LocalLlmBackend(private val configPath: String) : LlmBackend {

    override val displayName: String = "本地 · ${File(configPath).parentFile?.name ?: "?"}"

    private val nativePtr = AtomicLong(0L)
    @Volatile private var prepared = false

    override val ready: Boolean
        get() = prepared && nativePtr.get() != 0L

    override suspend fun prepare(): Boolean {
        if (ready) return true
        if (!LocalLlmBridge.nativeAvailable) {
            Log.w(TAG, "native lib not available, local backend disabled")
            return false
        }
        val ptr = try {
            LocalLlmBridge.initNative(configPath)
        } catch (t: Throwable) {
            Log.e(TAG, "initNative throw", t)
            0L
        }
        if (ptr == 0L) {
            Log.e(TAG, "initNative returned 0 for $configPath")
            return false
        }
        nativePtr.set(ptr)
        prepared = true
        return true
    }

    override fun chat(history: List<ChatTurn>): Flow<String> = callbackFlow<String> {
        val ptr = nativePtr.get()
        if (ptr == 0L) {
            close(IllegalStateException("local llm not prepared"))
            return@callbackFlow
        }
        val prompt = composePrompt(history)

        val listener = object : LocalLlmBridge.TokenListener {
            override fun onToken(token: String): Boolean {
                if (token == "<eop>") return true
                // trySend 失败（下游已取消）时返回 stop=true，让 native 端尽快收尾
                val ok = trySend(token).isSuccess
                return !ok
            }
        }

        // submitNative 是阻塞的——必须放到 IO，但 callbackFlow 本身就在协程里，
        // 我们靠 flowOn(Dispatchers.IO) 切线程。
        try {
            LocalLlmBridge.submitNative(ptr, prompt, listener)
        } catch (t: Throwable) {
            Log.e(TAG, "submitNative throw", t)
            close(t)
            return@callbackFlow
        }

        close()  // 模型说完，正常结束 Flow
        awaitClose {
            // 上游取消时通知 native 停止
            LocalLlmBridge.stopNative(ptr)
        }
    }.flowOn(Dispatchers.IO)

    override fun stop() {
        val ptr = nativePtr.get()
        if (ptr != 0L) LocalLlmBridge.stopNative(ptr)
    }

    override fun resetSession() {
        val ptr = nativePtr.get()
        if (ptr != 0L) LocalLlmBridge.resetNative(ptr)
    }

    override fun release() {
        val ptr = nativePtr.getAndSet(0L)
        if (ptr != 0L) {
            try { LocalLlmBridge.stopNative(ptr) } catch (_: Throwable) {}
            try { LocalLlmBridge.releaseNative(ptr) } catch (_: Throwable) {}
        }
        prepared = false
    }

    /** 把对话历史拼成单条 prompt。MNN 的 Llm::response 内部会处理 chat template。 */
    private fun composePrompt(history: List<ChatTurn>): String {
        // 取最后一条 user 作为本轮输入，前面的 history 当上下文（让 native 内部处理）
        // 这个简单实现：直接把最后一条 user 内容传过去；KV cache 由 native 维护。
        val last = history.lastOrNull { it.role == ChatTurn.Role.USER }
        return last?.content ?: ""
    }

    companion object {
        private const val TAG = "LocalLlmBackend"
    }
}
