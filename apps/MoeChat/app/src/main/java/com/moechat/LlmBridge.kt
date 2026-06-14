package com.moechat

object LlmBridge {
    init { System.loadLibrary("moechat") }

    interface TokenListener {
        // return true to request stop
        fun onToken(token: String): Boolean
    }

    external fun initNative(configPath: String): Long
    external fun submitNative(ptr: Long, prompt: String, listener: TokenListener): String
    external fun resetNative(ptr: Long)
    external fun stopNative(ptr: Long)
    external fun releaseNative(ptr: Long)
}
