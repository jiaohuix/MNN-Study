package com.moeavatar.tts

/**
 * 流式文本切句器：把 LLM 一段段吐出来的 token 拼起来，按规则切成"可送进 TTS"的整句。
 *
 * 策略（按优先级）：
 *  1. 一级标点 [。？！.?!\n] —— 立刻切，最自然。
 *  2. 累积 ≥ MIN_FALLBACK 字 → 找最近的二级标点 [，；,;:：—、] 切。
 *  3. 累积 ≥ MAX_HARD 字仍没标点 → 硬切（避免无标点长文卡住首句延迟）。
 *
 * 用法：
 *   val sp = SentenceSplitter()
 *   while (token in flow) for (s in sp.feed(token)) speechQueue.enqueue(s)
 *   for (s in sp.flush()) speechQueue.enqueue(s)
 */
class SentenceSplitter(
    private val minFallback: Int = 12,
    private val maxHard: Int = 40,
) {
    private val buf = StringBuilder()

    /** 喂入一段新 token，返回这次能切出来的若干完整句子（保留标点）。 */
    fun feed(chunk: String): List<String> {
        if (chunk.isEmpty()) return emptyList()
        buf.append(chunk)
        val out = mutableListOf<String>()
        while (true) {
            val cut = findCut() ?: break
            val s = buf.substring(0, cut).trim()
            buf.delete(0, cut)
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    /** 流结束时调用：把残留 buffer 当作最后一句吐出来。 */
    fun flush(): List<String> {
        val s = buf.toString().trim()
        buf.setLength(0)
        return if (s.isEmpty()) emptyList() else listOf(s)
    }

    /** 返回 buf 里第一个切点的"右边界"（exclusive），找不到返回 null。 */
    private fun findCut(): Int? {
        // 1. 一级标点：扫到第一个就切
        for (i in 0 until buf.length) {
            if (PRIMARY.contains(buf[i])) return i + 1
        }
        // 2. 累积超过 minFallback：找二级标点
        if (buf.length >= minFallback) {
            for (i in (buf.length - 1) downTo (minFallback - 1)) {
                if (SECONDARY.contains(buf[i])) return i + 1
            }
        }
        // 3. 累积超过 maxHard：硬切
        if (buf.length >= maxHard) return maxHard
        return null
    }

    companion object {
        private val PRIMARY = setOf('。', '？', '！', '.', '?', '!', '\n')
        private val SECONDARY = setOf('，', '；', '、', ',', ';', ':', '：', '—')
    }
}
