package com.moeavatar

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.bertvits2_infer_wrapper.impl.BertVITS2SimpleInferImpl
import com.example.bertvits2_infer_wrapper.interfaces.IBertVITS2SimpleInfer
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TtsActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var spinnerSpeaker: Spinner
    private lateinit var seekSpeed: SeekBar
    private lateinit var tvSpeed: TextView
    private lateinit var editText: EditText
    private lateinit var btnInit: MaterialButton
    private lateinit var btnSynth: MaterialButton
    private lateinit var tvLog: TextView

    private val infer: IBertVITS2SimpleInfer by lazy { BertVITS2SimpleInferImpl(applicationContext) }
    private var ready = false
    private var currentSpkName: String? = null
    private var synthJob: Job? = null
    private var audioTrack: AudioTrack? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tts)
        toolbar = findViewById(R.id.toolbar)
        spinnerSpeaker = findViewById(R.id.spinner_speaker)
        seekSpeed = findViewById(R.id.seek_speed)
        tvSpeed = findViewById(R.id.tv_speed_value)
        editText = findViewById(R.id.edit_text)
        btnInit = findViewById(R.id.btn_init)
        btnSynth = findViewById(R.id.btn_synth)
        tvLog = findViewById(R.id.tv_log)
        setSupportActionBar(toolbar)

        editText.setText("你好，我是甘雨，很高兴见到你～")

        seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val scale = lengthScaleFromProgress(p)
                tvSpeed.text = "length_scale = ${"%.2f".format(scale)}"
                if (ready) infer.setAudioLengthScale(scale)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        tvSpeed.text = "length_scale = ${"%.2f".format(lengthScaleFromProgress(seekSpeed.progress))}"

        spinnerSpeaker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                currentSpkName = p?.getItemAtPosition(pos) as? String
                appendLog("音色 = $currentSpkName")
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        btnInit.setOnClickListener { initEngine() }
        btnSynth.setOnClickListener { synthOrStop() }
    }

    private fun lengthScaleFromProgress(p: Int): Float {
        // SeekBar 0..200 -> length_scale 0.5..2.0, 100 -> 1.0
        return 0.5f + (p / 200f) * 1.5f
    }

    private fun initEngine() {
        if (synthJob?.isActive == true) return
        btnInit.isEnabled = false
        toolbar.subtitle = getString(R.string.tts_status_init)
        appendLog("开始初始化（首次需要从 assets 拷贝模型，可能耗时…）")

        synthJob = lifecycleScope.launch(Dispatchers.IO) {
            val ok = try {
                infer.init()
            } catch (t: Throwable) {
                Log.e(TAG, "init throw", t)
                false
            }
            withContext(Dispatchers.Main) {
                if (!ok) {
                    toolbar.subtitle = getString(R.string.tts_status_init_failed)
                    appendLog("初始化失败 ✗")
                    btnInit.isEnabled = true
                    return@withContext
                }
                ready = true
                infer.setAudioLengthScale(lengthScaleFromProgress(seekSpeed.progress))
                val names = infer.getSpkNameList()
                appendLog("初始化成功，音色: ${names.joinToString()}")
                spinnerSpeaker.adapter = ArrayAdapter(
                    this@TtsActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    names
                )
                if (names.isNotEmpty()) {
                    val ganyuIdx = names.indexOfFirst { it.contains("甘雨") }
                    spinnerSpeaker.setSelection(if (ganyuIdx >= 0) ganyuIdx else 0)
                }
                toolbar.subtitle = getString(R.string.tts_status_ready)
                btnSynth.isEnabled = true
                btnInit.isEnabled = false
                btnInit.text = "✓ 已就绪"
            }
        }
    }

    private fun synthOrStop() {
        if (synthJob?.isActive == true) {
            stopPlayback()
            synthJob?.cancel()
            return
        }
        if (!ready) {
            appendLog(getString(R.string.tts_err_not_ready)); return
        }
        val text = editText.text.toString().trim()
        if (text.isEmpty()) {
            appendLog(getString(R.string.tts_err_empty)); return
        }
        val spkName = currentSpkName ?: run {
            appendLog("未选择音色"); return
        }

        toolbar.subtitle = getString(R.string.tts_status_synth)
        btnSynth.text = getString(R.string.tts_btn_stop)
        btnSynth.setBackgroundColor(0xFFAAAAAA.toInt())
        appendLog("合成: spk=$spkName, len=${text.length}")
        val tStart = System.currentTimeMillis()

        synthJob = lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                infer.infer(text, spkName)
            } catch (t: Throwable) {
                Log.e(TAG, "infer throw", t)
                null
            }
            val (samples, sr) = result ?: (null to 0)
            withContext(Dispatchers.Main) {
                btnSynth.text = getString(R.string.tts_btn_synth)
                btnSynth.setBackgroundColor(0xFFFF8FB1.toInt())
                val cost = System.currentTimeMillis() - tStart
                if (samples == null || samples.isEmpty()) {
                    toolbar.subtitle = getString(R.string.tts_status_idle)
                    appendLog(getString(R.string.tts_err_synth, "samples=null/empty"))
                    return@withContext
                }
                appendLog("合成完成 ${samples.size} samples @ ${sr}Hz, ${cost}ms (RTF=${"%.2f".format(cost / 1000f / (samples.size.toFloat() / sr))})")
                playPcm(samples, sr)
            }
        }
    }

    private fun playPcm(samples: FloatArray, sampleRate: Int) {
        stopPlayback()
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        val bufSize = maxOf(minBuf, samples.size * 4)
        val track = AudioTrack.Builder()
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
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        track.play()
        audioTrack = track
        toolbar.subtitle = getString(R.string.tts_status_play)
        appendLog("▶ 播放中…")

        track.setNotificationMarkerPosition(samples.size)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack?) {
                runOnUiThread {
                    toolbar.subtitle = getString(R.string.tts_status_ready)
                    appendLog("⏹ 播放完成")
                }
                stopPlayback()
            }
            override fun onPeriodicNotification(t: AudioTrack?) {}
        })
    }

    private fun stopPlayback() {
        audioTrack?.let {
            try {
                it.stop()
            } catch (_: Throwable) {}
            it.release()
        }
        audioTrack = null
    }

    private fun appendLog(msg: String) {
        Log.i(TAG, msg)
        runOnUiThread {
            val cur = tvLog.text.toString()
            val next = if (cur == "日志会显示在这里…") msg else "$cur\n$msg"
            tvLog.text = next
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
        if (ready) {
            try { infer.release() } catch (_: Throwable) {}
        }
    }

    companion object {
        private const val TAG = "TtsActivity"
    }
}
