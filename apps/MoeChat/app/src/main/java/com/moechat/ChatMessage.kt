package com.moechat

data class ChatMessage(
    val role: Role,
    var content: String,
    var isStreaming: Boolean = false
) {
    enum class Role { USER, ASSISTANT, SYSTEM }
}
