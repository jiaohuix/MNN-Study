package com.moeavatar.tts

/**
 * 去掉所有括号内容的流式过滤器，专给 TTS 用。
 *
 * 角色扮演 LLM 喜欢用 `(突然跳起来)喵～` / `（拍拍头）真乖`
 * 之类的动作描述。这部分应该显示给用户但不该被语音合成。
 *
 * 因为 LLM 一个 token 一个 token 流式过来，左括号 `(` 和右括号 `)`
 * 可能跨 chunk，所以维护一个 [depth] 状态：进入括号 depth++，
 * 出来 depth--，depth>0 期间所有字符丢弃。
 *
 * 全角 `（）` 和半角 `()` 都过滤；中括号 `[]【】` 暂不过滤
 * （这些通常是真正想念出来的内容比如代码）。
 *
 * Splitter 那边吐出来的"完整句"也得过一遍这个 → 句子里嵌套的括号会被拿掉，
 * 全是括号的句子会变成空串（被 [enqueue] 拒绝）。
 */
class TtsTextFilter {
    private var depth: Int = 0

    fun reset() { depth = 0 }

    /** 流式：吃一段文字，返回去掉括号内容后的部分。 */
    fun feed(chunk: String): String {
        if (chunk.isEmpty()) return ""
        val sb = StringBuilder(chunk.length)
        for (ch in chunk) {
            when (ch) {
                '(', '（' -> depth++
                ')', '）' -> if (depth > 0) depth--
                else -> if (depth == 0) sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** 一次性：直接处理整个字符串（用于已切好的单句）。 */
    fun stripAll(s: String): String {
        // 单句调用：用本地 depth 不污染流式状态
        var d = 0
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                '(', '（' -> d++
                ')', '）' -> if (d > 0) d--
                else -> if (d == 0) sb.append(ch)
            }
        }
        return sb.toString()
    }
}
