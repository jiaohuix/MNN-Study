package com.moeavatar.asr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.k2fsa.sherpa.mnn.AsrConfigManager
import com.k2fsa.sherpa.mnn.EndpointConfig
import com.k2fsa.sherpa.mnn.EndpointRule
import com.k2fsa.sherpa.mnn.FeatureConfig
import com.k2fsa.sherpa.mnn.OnlineCtcFstDecoderConfig
import com.k2fsa.sherpa.mnn.OnlineRecognizer
import com.k2fsa.sherpa.mnn.OnlineRecognizerConfig
import com.moeavatar.R
import com.moeavatar.chat.LlmChatActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 流式 ASR：长按 [按住说话] 录音，松手停止；endpoint 触发或松手时把文本回填到结果框。
 *
 * 模型路径默认 /sdcard/Download/MoeAvatar/asr/<model_dir>，目录需含 config.json。
 * config.json 由 [AsrConfigManager] 解析（来自 MnnLlmChat），可以不带 LM。
 */
class AsrActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AsrActivity"
        private const val SR = 16000
        private const val REQ_AUDIO = 0xA51
        const val EXTRA_RECOGNIZED_TEXT = "asr_text"
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvText: TextView
    private lateinit var etDir: EditText
    private lateinit var btnLoad: MaterialButton
    private lateinit var btnMic: MaterialButton
    private lateinit var btnSendToChat: MaterialButton

    @Volatile private var recognizer: OnlineRecognizer? = null
    private val recording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_asr)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

        tvStatus = findViewById(R.id.tv_status)
        tvText = findViewById(R.id.tv_text)
        etDir = findViewById(R.id.et_model_dir)
        btnLoad = findViewById(R.id.btn_load)
        btnMic = findViewById(R.id.btn_mic)
        btnSendToChat = findViewById(R.id.btn_send_to_chat)

        btnLoad.setOnClickListener { loadModel(etDir.text.toString().trim()) }

        btnMic.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (ensurePermission()) startRecording()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopRecording(); true
                }
                else -> false
            }
        }

        btnSendToChat.setOnClickListener {
            val text = tvText.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "还没识别到内容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 通过 Intent extras 把识别文本带回 LlmChatActivity
            val i = Intent(this, LlmChatActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                putExtra(EXTRA_RECOGNIZED_TEXT, text)
            }
            startActivity(i)
        }
    }

    private fun ensurePermission(): Boolean {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return false
        }
        return true
    }

    private fun loadModel(modelDir: String) {
        if (modelDir.isEmpty()) {
            tvStatus.text = "请填模型目录"
            return
        }
        tvStatus.text = "加载中: $modelDir"
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val modelConfig = AsrConfigManager.getModelConfigFromDirectory(modelDir)
                    ?: error("config.json missing or invalid in $modelDir")
                val lmConfig = AsrConfigManager.getLmConfigFromDirectory(modelDir)
                val cfg = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SR, featureDim = 80),
                    modelConfig = modelConfig,
                    lmConfig = lmConfig,
                    ctcFstDecoderConfig = OnlineCtcFstDecoderConfig("", 3000),
                    endpointConfig = EndpointConfig(
                        rule1 = EndpointRule(false, 2.4f, 0.0f),
                        rule2 = EndpointRule(true, 1.4f, 0.0f),
                        rule3 = EndpointRule(false, 0.0f, 20.0f),
                    ),
                    enableEndpoint = true,
                    decodingMethod = "greedy_search",
                    maxActivePaths = 4,
                    hotwordsFile = "",
                    hotwordsScore = 1.5f,
                    ruleFsts = "",
                    ruleFars = "",
                )
                recognizer = OnlineRecognizer(null, cfg)
                true
            }.getOrElse {
                Log.e(TAG, "load asr failed", it)
                false
            }
            withContext(Dispatchers.Main) {
                tvStatus.text = if (ok) "ASR 就绪 · 按住下面按钮说话" else "ASR 加载失败，看 logcat"
            }
        }
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
        recordThread = Thread {
            val stream = rec.createStream("")
            val buffer = ShortArray((0.1 * SR).toInt())
            try {
                while (recording.get()) {
                    val n = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (n > 0) {
                        val samples = FloatArray(n) { i -> buffer[i] / 32768.0f }
                        stream.acceptWaveform(samples, SR)
                        while (rec.isReady(stream)) rec.decode(stream)
                        val text = rec.getResult(stream).text
                        runOnUiThread { tvText.text = text }
                        if (rec.isEndpoint(stream)) rec.reset(stream)
                    }
                }
                // tail flush
                val tail = FloatArray((0.5 * SR).toInt())
                stream.acceptWaveform(tail, SR)
                while (rec.isReady(stream)) rec.decode(stream)
                val finalText = rec.getResult(stream).text
                runOnUiThread {
                    if (finalText.isNotEmpty()) tvText.text = finalText
                    tvStatus.text = "已停止"
                }
            } catch (t: Throwable) {
                Log.e(TAG, "asr loop error", t)
            } finally {
                stream.release()
            }
        }.also { it.start() }
    }

    private fun stopRecording() {
        if (!recording.getAndSet(false)) return
        runCatching { audioRecord?.stop(); audioRecord?.release() }
        audioRecord = null
        recordThread?.join(500)
        recordThread = null
    }

    override fun onDestroy() {
        stopRecording()
        recognizer?.release()
        recognizer = null
        super.onDestroy()
    }
}
