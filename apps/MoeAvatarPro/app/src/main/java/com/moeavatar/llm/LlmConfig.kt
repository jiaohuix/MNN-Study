package com.moeavatar.llm

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 全局可配置项。SharedPreferences 持久化。
 *
 * 这里只放跟 LLM/TTS 选择相关的轻量配置，模型扫描结果不持久化。
 */
class LlmConfig(ctx: Context) {

    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    enum class BackendKind { LOCAL, OPENAI }

    var backendKind: BackendKind
        get() = runCatching { BackendKind.valueOf(sp.getString(K_BACKEND, BackendKind.LOCAL.name)!!) }
            .getOrDefault(BackendKind.LOCAL)
        set(v) = sp.edit { putString(K_BACKEND, v.name) }

    /** 本地模型扫描根目录，默认 /sdcard/Download/MoeAvatar/models */
    var localModelDir: String
        get() = sp.getString(K_LOCAL_DIR, DEFAULT_LOCAL_DIR) ?: DEFAULT_LOCAL_DIR
        set(v) = sp.edit { putString(K_LOCAL_DIR, v) }

    /** 当前选中的本地模型子目录名（不是绝对路径） */
    var localModelName: String?
        get() = sp.getString(K_LOCAL_NAME, null)
        set(v) = sp.edit { putString(K_LOCAL_NAME, v) }

    var openAiBaseUrl: String
        get() = sp.getString(K_OAI_BASE, "https://api.openai.com") ?: "https://api.openai.com"
        set(v) = sp.edit { putString(K_OAI_BASE, v) }

    var openAiApiKey: String
        get() = sp.getString(K_OAI_KEY, "") ?: ""
        set(v) = sp.edit { putString(K_OAI_KEY, v) }

    var openAiModel: String
        get() = sp.getString(K_OAI_MODEL, "gpt-4o-mini") ?: "gpt-4o-mini"
        set(v) = sp.edit { putString(K_OAI_MODEL, v) }

    var systemPrompt: String
        get() = sp.getString(K_SYS_PROMPT, DEFAULT_SYS_PROMPT) ?: DEFAULT_SYS_PROMPT
        set(v) = sp.edit { putString(K_SYS_PROMPT, v) }

    /** TTS 当前音色 id（沿用 BertVITS2 的 speaker 名字） */
    var ttsSpeaker: String
        get() = sp.getString(K_TTS_SPK, "甘雨_ZH") ?: "甘雨_ZH"
        set(v) = sp.edit { putString(K_TTS_SPK, v) }

    /** 当前 Live2D 角色名（对应 Live2DController.PRESETS 的 key） */
    var live2dModel: String
        get() = sp.getString(K_LIVE2D, "ATRI") ?: "ATRI"
        set(v) = sp.edit { putString(K_LIVE2D, v) }

    companion object {
        private const val PREF_NAME = "moeavatar_llm"
        private const val K_BACKEND = "backend"
        private const val K_LOCAL_DIR = "local_dir"
        private const val K_LOCAL_NAME = "local_name"
        private const val K_OAI_BASE = "oai_base"
        private const val K_OAI_KEY = "oai_key"
        private const val K_OAI_MODEL = "oai_model"
        private const val K_SYS_PROMPT = "sys_prompt"
        private const val K_TTS_SPK = "tts_spk"
        private const val K_LIVE2D = "live2d_model"

        const val DEFAULT_LOCAL_DIR = "/sdcard/Download/MoeAvatar/models"
        const val DEFAULT_SYS_PROMPT =
            "你是一个二次元 AI 助手，回答简洁可爱，用中文聊天，不要使用 markdown。"
    }
}
