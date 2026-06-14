package com.moeavatar.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.example.bertvits2_infer_wrapper.interfaces.IBertVITS2SimpleInfer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * 流水线 TTS：合成线程 ‖ 播放线程，中间一个有界 Channel 缓冲合成结果。
 *
 *   句1 → [synth worker] → ─┐    ┌─→ [audio worker] → 句1 播
 *   句2 → [synth worker] → ─┼ Ch ┼─→ [audio worker] → 句2 播
 *
 * 关键：play worker 维护**单个共享 AudioTrack**（MODE_STREAM），所有句子的
 * PCM 顺序 write 进同一个环形缓冲。WRITE_BLOCKING 提供天然背压，缓冲区满了
 * 自动等下一帧空出来，缓冲区有数据就连续放音 —— 句与句之间没有任何
 * stop/start/sleep gap，听起来才是连贯的一句话而不是"嘶……嘶……"。
 *
 * 旧版每句都 build → write → sleep(approxMs+50) → stop → release，仅
 * sleep + 重建就够撕出一个清晰可闻的停顿；现在彻底干掉这套循环。
 */
class SpeechQueue(
    private val infer: IBertVITS2SimpleInfer,
    private val speakerProvider: () -> String,
    /** 播放线程在每次 write 同样的 PCM 之前回调一次，给 lip-sync 用。 */
    private var pcmListener: ((FloatArray, Int) -> Unit)? = null,
) {
    /** 后续 Activity 创建好 Live2D 控制器再注入 listener，简化构造时序。 */
    fun setPcmListener(l: ((FloatArray, Int) -> Unit)?) { pcmListener = l }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var textCh: Channel<String> = Channel(Channel.UNLIMITED)
    private var pcmCh: Channel<Pcm> = Channel(capacity = 2)

    private var synthJob: Job? = null
    private var playJob: Job? = null

    // 共享 AudioTrack：相同采样率的连续句子复用，跨采样率时才重建
    @Volatile private var sharedTrack: AudioTrack? = null
    @Volatile private var sharedSr: Int = 0

    private data class Pcm(val samples: FloatArray, val sr: Int, val text: String)

    fun start() {
        if (synthJob?.isActive == true && playJob?.isActive == true) return
        textCh = Channel(Channel.UNLIMITED)
        pcmCh = Channel(capacity = 2)

        synthJob = scope.launch {
            for (text in textCh) {
                val pcm = runCatching { synth(text) }
                    .onFailure { Log.e(TAG, "synth failed: $text", it) }
                    .getOrNull() ?: continue
                pcmCh.send(pcm)
            }
            pcmCh.close()
        }

        playJob = scope.launch {
            for (pcm in pcmCh) {
                runCatching { writeToShared(pcm.samples, pcm.sr) }
                    .onFailure { Log.e(TAG, "play failed: ${pcm.text}", it) }
            }
        }
    }

    fun enqueue(sentence: String) {
        if (sentence.isBlank()) return
        textCh.trySend(sentence)
    }

    /** 用户打断 / 切换 backend：丢掉队列里的合成任务 + 当前 track 缓冲。 */
    fun clear() {
        // 清缓冲（已写但未播完的 PCM 直接扔）。track 本身留着下次还能用。
        sharedTrack?.let {
            try { it.pause() } catch (_: Throwable) {}
            try { it.flush() } catch (_: Throwable) {}
            try { it.play() } catch (_: Throwable) {}
        }
        textCh.close()
        pcmCh.close()
        synthJob?.cancel()
        playJob?.cancel()
        synthJob = null
        playJob = null
        start()
    }

    fun release() {
        textCh.close()
        pcmCh.close()
        synthJob?.cancel()
        playJob?.cancel()
        synthJob = null
        playJob = null
        sharedTrack?.run {
            try { stop() } catch (_: Throwable) {}
            try { release() } catch (_: Throwable) {}
        }
        sharedTrack = null
        sharedSr = 0
        scope.cancel()
    }

    private suspend fun synth(text: String): Pcm? {
        val spk = speakerProvider()
        val t0 = System.currentTimeMillis()
        val pair = try {
            infer.infer(text, spk)
        } catch (t: Throwable) {
            Log.e(TAG, "infer throw on: $text", t); return null
        } ?: return null
        val samples = pair.first
        val sr = pair.second
        if (samples == null || samples.isEmpty() || sr <= 0) return null
        val cost = System.currentTimeMillis() - t0
        Log.i(TAG, "synth ok len=${samples.size} sr=$sr cost=${cost}ms text=${text.take(20)}")
        return Pcm(samples, sr, text)
    }

    /**
     * 写到共享 track。同采样率直接 write（WRITE_BLOCKING 自动背压），
     * 不同采样率才重建。**不 sleep、不 stop**，连续两句之间无缝。
     */
    private fun writeToShared(samples: FloatArray, sampleRate: Int) {
        var track = sharedTrack
        if (track == null || sharedSr != sampleRate) {
            // 采样率变了 / 第一次：销毁旧的、建新的
            track?.let {
                try { it.stop() } catch (_: Throwable) {}
                try { it.release() } catch (_: Throwable) {}
            }
            track = buildTrack(sampleRate)
            sharedTrack = track
            sharedSr = sampleRate
            track.play()
        }
        // lip-sync 喂数据：在真正写入 AudioTrack 之前调用一次，
        // 这样 Live2D 嘴型曲线和耳朵听到的声音几乎对齐（差不多在 buffer 长度内）。
        runCatching { pcmListener?.invoke(samples, sampleRate) }
        var written = 0
        while (written < samples.size) {
            val w = track.write(
                samples,
                written,
                samples.size - written,
                AudioTrack.WRITE_BLOCKING
            )
            if (w <= 0) break
            written += w
        }
        // 不等播完，下一句的 PCM 直接接到环形缓冲后面 —— 这就是"连贯"的关键
    }

    private fun buildTrack(sampleRate: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        // 给大点的 buffer（约 1s 余量），合成稍慢也不至于断流
        val bufBytes = maxOf(minBuf, sampleRate * 4 /*float*/ * 1)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    companion object {
        private const val TAG = "SpeechQueue"
    }
}
