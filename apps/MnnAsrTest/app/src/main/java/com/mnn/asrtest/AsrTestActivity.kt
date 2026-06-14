package com.mnn.asrtest

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.k2fsa.sherpa.mnn.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class AsrTestActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AsrTest"
        private const val SR = 16000
        private const val REQ_AUDIO = 100

        // 默认模型路径 —— 用 /data/local/tmp 而不是 /sdcard/Download，
        // 因为 Android 11+ scoped storage 下，/sdcard/Download 是写入 App 私有的，
        // 别的 App 读会 Permission denied，sherpa 内部 fopen 失败但不抛异常，
        // 一路骗到 createStream 才 SIGSEGV。/data/local/tmp 是 adb shell 的目录，所有 App 都能读。
        private const val DEFAULT_MODEL_DIR =
            "/data/local/tmp/asr/sherpa-mnn-streaming-zipformer-bilingual-zh-en"
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvText: TextView
    private lateinit var tvMetrics: TextView
    private lateinit var etDir: TextInputEditText
    private lateinit var btnLoad: MaterialButton
    private lateinit var btnMic: MaterialButton

    @Volatile private var recognizer: OnlineRecognizer? = null
    private val recording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_asr)

        tvStatus = findViewById(R.id.tv_status)
        tvText = findViewById(R.id.tv_text)
        tvMetrics = findViewById(R.id.tv_metrics)
        etDir = findViewById(R.id.et_model_dir)
        btnLoad = findViewById(R.id.btn_load)
        btnMic = findViewById(R.id.btn_mic)

        etDir.setText(DEFAULT_MODEL_DIR)

        btnLoad.setOnClickListener { loadModel() }

        btnMic.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (hasAudioPermission()) startRecording() else requestAudioPermission()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopRecording(); true
                }
                else -> false
            }
        }
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestAudioPermission() =
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        }
    }

    private fun loadModel() {
        val modelDir = etDir.text.toString().trim()
        if (modelDir.isEmpty()) {
            tvStatus.text = "请填写模型目录路径"
            return
        }
        tvStatus.text = "加载中: $modelDir"

        lifecycleScope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val result = runCatching {
                val cfg = buildConfig(modelDir)
                recognizer = OnlineRecognizer(cfg)
            }
            val loadMs = System.currentTimeMillis() - t0
            val errMsg = result.exceptionOrNull()?.message
            if (result.isFailure) Log.e(TAG, "load asr failed", result.exceptionOrNull())
            withContext(Dispatchers.Main) {
                tvStatus.text = when {
                    result.isSuccess -> "ASR 就绪 · 按住下方按钮说话 (load=${loadMs}ms)"
                    else -> "ASR 加载失败：${errMsg ?: "看 logcat"}"
                }
            }
        }
    }

    /** 直接用硬编码文件名构造 config（不读 json，避免 sdcard 权限问题） */
    private fun buildConfig(modelDir: String): OnlineRecognizerConfig {
        return OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SR, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$modelDir/encoder-epoch-99-avg-1.int8.mnn",
                    decoder = "$modelDir/decoder-epoch-99-avg-1.int8.mnn",
                    joiner = "$modelDir/joiner-epoch-99-avg-1.int8.mnn",
                ),
                tokens = "$modelDir/tokens.txt",
                modelType = "zipformer",
                debug = false,
            ),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0.0f),
                rule2 = EndpointRule(true, 1.4f, 0.0f),
                rule3 = EndpointRule(false, 0.0f, 20.0f),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
            maxActivePaths = 4,
        )
    }

    private fun startRecording() {
        val rec = recognizer ?: run {
            Toast.makeText(this, "请先加载 ASR 模型", Toast.LENGTH_SHORT).show()
            return
        }
        if (recording.getAndSet(true)) return

        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, SR,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
        )
        audioRecord?.startRecording()
        tvStatus.text = "录音中…"
        tvText.text = ""
        tvMetrics.text = ""
        btnMic.text = "松开停止"

        recordThread = Thread {
            val stream = rec.createStream("")
            val buffer = ShortArray((0.1 * SR).toInt()) // 100ms 分片

            // —— 性能指标 ——
            val recStartMs = System.currentTimeMillis()  // 用户按下的时刻
            var firstAudioMs = 0L                        // 第一帧 PCM 入帐的时刻
            var firstTokenMs = 0L                        // 模型吐出第一个非空 text 的时刻（首字延时）
            var totalSamples = 0L                        // 累计入帧的样本数
            var decodeNanos = 0L                         // 解码累计纯耗时（纳秒）
            var decodeCalls = 0                          // 调过多少次 decode()
            var emittedSegments = mutableListOf<String>()
            var lastEmitted = ""

            try {
                while (recording.get()) {
                    val n = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (n > 0) {
                        if (firstAudioMs == 0L) firstAudioMs = System.currentTimeMillis()
                        totalSamples += n

                        val samples = FloatArray(n) { i -> buffer[i] / 32768.0f }
                        stream.acceptWaveform(samples, SR)

                        val tDec0 = System.nanoTime()
                        while (rec.isReady(stream)) {
                            rec.decode(stream); decodeCalls++
                        }
                        decodeNanos += System.nanoTime() - tDec0

                        val text = rec.getResult(stream).text
                        val isEp = rec.isEndpoint(stream)

                        if (text.isNotEmpty() && firstTokenMs == 0L) {
                            firstTokenMs = System.currentTimeMillis()
                        }

                        // 实时把 partial text 推到 UI
                        if (text != lastEmitted) {
                            lastEmitted = text
                            val displayText = (emittedSegments + text).joinToString("")
                            runOnUiThread { tvText.text = displayText }
                        }

                        if (isEp) {
                            if (text.isNotEmpty()) {
                                emittedSegments += text
                                val full = emittedSegments.joinToString("")
                                runOnUiThread { tvText.text = full }
                            }
                            rec.reset(stream)
                            lastEmitted = ""
                        }
                    }
                }

                // 录音结束 —— tail flush 让最后没说完的 chunk 解出来
                val tail = FloatArray((0.5 * SR).toInt())
                stream.acceptWaveform(tail, SR)
                val tDec0 = System.nanoTime()
                while (rec.isReady(stream)) {
                    rec.decode(stream); decodeCalls++
                }
                decodeNanos += System.nanoTime() - tDec0
                val finalText = rec.getResult(stream).text
                if (finalText.isNotEmpty()) emittedSegments += finalText

                // —— 算指标 ——
                val recEndMs = System.currentTimeMillis()
                val audioSec = totalSamples / SR.toDouble()
                val recordMs = recEndMs - recStartMs
                val firstByteMs = if (firstAudioMs > 0) firstAudioMs - recStartMs else -1
                val firstTokenLatMs = if (firstTokenMs > 0 && firstAudioMs > 0) firstTokenMs - firstAudioMs else -1
                val decodeMs = decodeNanos / 1_000_000.0
                val rtf = if (audioSec > 0) decodeMs / 1000.0 / audioSec else 0.0
                val full = emittedSegments.joinToString("")

                Log.i(TAG, "==== ASR done ====")
                Log.i(TAG, "audio=${"%.2f".format(audioSec)}s  record=${recordMs}ms")
                Log.i(TAG, "firstAudioDelay=${firstByteMs}ms  firstTokenDelay=${firstTokenLatMs}ms")
                Log.i(TAG, "decodeTotal=${"%.0f".format(decodeMs)}ms  decodeCalls=$decodeCalls  RTF=${"%.3f".format(rtf)}")
                Log.i(TAG, "text=$full")

                runOnUiThread {
                    tvText.text = full.ifEmpty { "(无识别结果)" }
                    tvMetrics.text = buildString {
                        append("音频 ").append("%.2f".format(audioSec)).append("s")
                        append("  解码 ").append("%.0f".format(decodeMs)).append("ms")
                        append("  RTF ").append("%.3f".format(rtf)).append('\n')
                        append("首字延时 ")
                        append(if (firstTokenLatMs >= 0) "${firstTokenLatMs}ms" else "—")
                        append("  录音启动 ")
                        append(if (firstByteMs >= 0) "${firstByteMs}ms" else "—")
                        append("  decode 次数 ").append(decodeCalls)
                    }
                    // 同时弹 Toast,绕开任何 TextView 渲染问题
                    Toast.makeText(
                        this@AsrTestActivity,
                        "结果: ${full.ifEmpty { "(空)" }}\n" +
                        "音频 ${"%.2f".format(audioSec)}s | RTF ${"%.3f".format(rtf)} | 首字 ${firstTokenLatMs}ms",
                        Toast.LENGTH_LONG
                    ).show()
                    tvStatus.text = "已停止 · ${full.take(20)}"
                }
            } catch (t: Throwable) {
                Log.e(TAG, "asr loop error", t)
                runOnUiThread { tvStatus.text = "ASR 异常：${t.message}" }
            } finally {
                stream.release()
            }
        }.also { it.start() }
    }

    private fun stopRecording() {
        if (!recording.getAndSet(false)) return
        runCatching { audioRecord?.stop(); audioRecord?.release() }
        audioRecord = null
        // 不在这里 join —— 让 record thread 自己跑完 tail flush + UI 更新
        btnMic.text = "按住说话"
    }

    override fun onDestroy() {
        recording.set(false)
        runCatching { audioRecord?.stop(); audioRecord?.release() }
        audioRecord = null
        recordThread?.join(1000)
        recordThread = null
        recognizer?.release()
        recognizer = null
        super.onDestroy()
    }
}
