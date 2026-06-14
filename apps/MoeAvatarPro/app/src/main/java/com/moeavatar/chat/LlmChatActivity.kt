package com.moeavatar.chat

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.bertvits2_infer_wrapper.impl.BertVITS2SimpleInferImpl
import com.example.bertvits2_infer_wrapper.interfaces.IBertVITS2SimpleInfer
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.moeavatar.R
import com.moeavatar.live2d.Live2DController
import com.moeavatar.llm.ChatTurn
import com.moeavatar.llm.LlmBackend
import com.moeavatar.llm.LlmConfig
import com.moeavatar.llm.LocalLlmBackend
import com.moeavatar.llm.ModelScanner
import com.moeavatar.llm.OpenAiLlmBackend
import com.moeavatar.tts.SentenceSplitter
import com.moeavatar.tts.SpeechQueue
import com.moeavatar.tts.TtsTextFilter
import android.widget.FrameLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LlmChatActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvStatus: TextView
    private lateinit var rv: RecyclerView
    private lateinit var input: EditText
    private lateinit var btnSend: MaterialButton

    private val adapter = ChatAdapter()
    private lateinit var config: LlmConfig

    private val infer: IBertVITS2SimpleInfer by lazy { BertVITS2SimpleInferImpl(applicationContext) }
    private var ttsReady = false
    private var ttsSpeakers: List<String> = emptyList()

    private lateinit var speechQueue: SpeechQueue
    private var backend: LlmBackend? = null
    private var chatJob: Job? = null

    private lateinit var live2d: Live2DController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // MIUI/HyperOS 上 adjustResize 经常失效 → 自己用 IME insets 顶起来
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_llm_chat)

        toolbar = findViewById(R.id.toolbar)
        tvStatus = findViewById(R.id.tv_status)
        rv = findViewById(R.id.rv_messages)
        input = findViewById(R.id.edit_input)
        btnSend = findViewById(R.id.btn_send)
        setSupportActionBar(toolbar)

        // 单点处理：只给 root 装监听器，把状态栏 + 导航栏 + IME 全部转成 root 的 padding
        // 这样 LinearLayout 自然布局后输入条永远贴在"非键盘+导航"的下边缘
        val root = findViewById<View>(R.id.root_container)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            // 键盘弹起时取 ime 高度（已包含导航栏）；否则只让出导航栏
            val bottom = if (ime > 0) ime else sysBars.bottom
            v.setPadding(sysBars.left, sysBars.top, sysBars.right, bottom)
            WindowInsetsCompat.CONSUMED
        }

        config = LlmConfig(this)

        rv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        rv.adapter = adapter

        speechQueue = SpeechQueue(infer, speakerProvider = { config.ttsSpeaker })
        speechQueue.start()

        // 把 Live2D GLSurfaceView 塞进背景容器；TTS 播放时回调 PCM 驱动嘴型
        val live2dContainer = findViewById<FrameLayout>(R.id.live2d_container)
        live2d = Live2DController(this, live2dContainer)
        live2d.onCreate(presetName = config.live2dModel)
        speechQueue.setPcmListener { samples, sr -> live2d.feedAudioForLipSync(samples, sr) }

        btnSend.setOnClickListener {
            if (chatJob?.isActive == true) {
                stopGeneration()
            } else {
                sendCurrentInput()
            }
        }

        bootstrap()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.chat_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> { showSettings(); true }
            R.id.action_clear -> { clearChat(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /** 启动时：异步初始化 TTS + 选定的 LLM backend，状态栏更新 */
    private fun bootstrap() {
        setStatus("初始化 TTS…")
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = runCatching { infer.init() }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                if (ok) {
                    ttsReady = true
                    ttsSpeakers = infer.getSpkNameList()
                    if (config.ttsSpeaker !in ttsSpeakers && ttsSpeakers.isNotEmpty()) {
                        config.ttsSpeaker = ttsSpeakers.firstOrNull { it.contains("甘雨") }
                            ?: ttsSpeakers[0]
                    }
                    setStatus("TTS 就绪 · 准备 LLM…")
                } else {
                    setStatus("TTS 初始化失败 — 仍可文本聊天")
                }
            }
            prepareBackend()
        }
    }

    private suspend fun prepareBackend() {
        // 切 backend 时旧的释放
        backend?.release()
        backend = null

        val newBackend: LlmBackend? = when (config.backendKind) {
            LlmConfig.BackendKind.LOCAL -> {
                val models = ModelScanner.scan(config.localModelDir)
                if (models.isEmpty()) {
                    withContext(Dispatchers.Main) { setStatus("本地模型目录为空: ${config.localModelDir}") }
                    null
                } else {
                    val pick = models.firstOrNull { it.name == config.localModelName } ?: models[0]
                    config.localModelName = pick.name
                    LocalLlmBackend(pick.configPath)
                }
            }
            LlmConfig.BackendKind.OPENAI -> {
                if (config.openAiApiKey.isBlank()) {
                    withContext(Dispatchers.Main) { setStatus("OpenAI key 未配置 · 打开设置") }
                    null
                } else {
                    OpenAiLlmBackend(
                        baseUrl = config.openAiBaseUrl,
                        apiKey = config.openAiApiKey,
                        model = config.openAiModel,
                        systemPrompt = config.systemPrompt,
                    )
                }
            }
        }
        val pickedBackend: LlmBackend = newBackend ?: return

        withContext(Dispatchers.Main) { setStatus("加载 ${pickedBackend.displayName} …") }
        val ok = runCatching { pickedBackend.prepare() }.getOrDefault(false)
        withContext(Dispatchers.Main) {
            if (ok) {
                backend = pickedBackend
                setStatus("就绪 · ${pickedBackend.displayName}")
            } else {
                setStatus("LLM 加载失败 · ${pickedBackend.displayName}")
            }
        }
    }

    private fun sendCurrentInput() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val be = backend ?: run {
            Toast.makeText(this, "LLM 未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        input.setText("")
        adapter.appendUser(text)
        scrollToEnd()
        val asstIdx = adapter.appendAssistantPlaceholder()
        scrollToEnd()
        speechQueue.clear()
        btnSend.text = "停止"

        val splitter = SentenceSplitter()
        val ttsFilter = TtsTextFilter()
        val accum = StringBuilder()
        val history = adapter.snapshot().filter { it.content.isNotEmpty() || it.role == ChatTurn.Role.USER }

        chatJob = lifecycleScope.launch {
            try {
                be.chat(history)
                    .onCompletion {
                        for (s in splitter.flush()) {
                            val clean = ttsFilter.stripAll(s)
                            if (clean.isNotBlank()) speechQueue.enqueue(clean)
                        }
                    }
                    .collect { token ->
                        accum.append(token)
                        adapter.updateAt(asstIdx, accum.toString())
                        scrollToEnd()
                        // 显示路径保留括号内容；TTS 路径过滤掉括号里的动作描述
                        val ttsChunk = ttsFilter.feed(token)
                        if (ttsChunk.isNotEmpty()) {
                            for (s in splitter.feed(ttsChunk)) speechQueue.enqueue(s)
                        }
                    }
            } catch (t: Throwable) {
                Log.e(TAG, "chat error", t)
                accum.append("\n[错误: ${t.message}]")
                adapter.updateAt(asstIdx, accum.toString())
            } finally {
                btnSend.text = "发送"
            }
        }
    }

    private fun stopGeneration() {
        chatJob?.cancel()
        backend?.stop()
        speechQueue.clear()
        btnSend.text = "发送"
    }

    private fun clearChat() {
        stopGeneration()
        adapter.clear()
        backend?.resetSession()
    }

    private fun scrollToEnd() {
        rv.post { rv.scrollToPosition(adapter.itemCount - 1) }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
    }

    private fun setStatus(msg: String) {
        tvStatus.text = msg
        Log.i(TAG, msg)
    }

    private fun showSettings() {
        val view = layoutInflater.inflate(R.layout.dialog_chat_settings, null)
        val rg = view.findViewById<RadioGroup>(R.id.rg_backend)
        val etDir = view.findViewById<EditText>(R.id.et_local_dir)
        val spLocal = view.findViewById<Spinner>(R.id.sp_local_model)
        val btnRescan = view.findViewById<MaterialButton>(R.id.btn_rescan)
        val etBase = view.findViewById<EditText>(R.id.et_oai_base)
        val etKey = view.findViewById<EditText>(R.id.et_oai_key)
        val etModel = view.findViewById<EditText>(R.id.et_oai_model)
        val etSys = view.findViewById<EditText>(R.id.et_sys_prompt)
        val spSpeaker = view.findViewById<Spinner>(R.id.sp_tts_speaker)

        rg.check(if (config.backendKind == LlmConfig.BackendKind.LOCAL) R.id.rb_local else R.id.rb_openai)
        etDir.setText(config.localModelDir)
        etBase.setText(config.openAiBaseUrl)
        etKey.setText(config.openAiApiKey)
        etModel.setText(config.openAiModel)
        etSys.setText(config.systemPrompt)

        var localModels = ModelScanner.scan(etDir.text.toString())
        var pickedLocal: String? = config.localModelName
        fun refreshLocalSpinner() {
            val names = localModels.map { it.name }
            spLocal.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item, names
            )
            val idx = names.indexOf(pickedLocal).let { if (it < 0) 0 else it }
            if (names.isNotEmpty()) spLocal.setSelection(idx)
        }
        refreshLocalSpinner()
        spLocal.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                pickedLocal = localModels.getOrNull(pos)?.name
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        btnRescan.setOnClickListener {
            localModels = ModelScanner.scan(etDir.text.toString())
            Toast.makeText(this, "扫到 ${localModels.size} 个模型", Toast.LENGTH_SHORT).show()
            refreshLocalSpinner()
        }

        val speakers = if (ttsSpeakers.isEmpty()) listOf(config.ttsSpeaker) else ttsSpeakers
        spSpeaker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, speakers)
        val spkIdx = speakers.indexOf(config.ttsSpeaker).let { if (it < 0) 0 else it }
        spSpeaker.setSelection(spkIdx)

        AlertDialog.Builder(this)
            .setTitle("设置")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                val newKind = if (rg.checkedRadioButtonId == R.id.rb_openai)
                    LlmConfig.BackendKind.OPENAI else LlmConfig.BackendKind.LOCAL
                config.backendKind = newKind
                config.localModelDir = etDir.text.toString().trim()
                config.localModelName = pickedLocal
                config.openAiBaseUrl = etBase.text.toString().trim()
                config.openAiApiKey = etKey.text.toString().trim()
                config.openAiModel = etModel.text.toString().trim().ifEmpty { "gpt-4o-mini" }
                config.systemPrompt = etSys.text.toString()
                config.ttsSpeaker = spSpeaker.selectedItem?.toString() ?: config.ttsSpeaker
                stopGeneration()
                lifecycleScope.launch(Dispatchers.IO) { prepareBackend() }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (::live2d.isInitialized) live2d.onResume()
    }

    override fun onPause() {
        if (::live2d.isInitialized) live2d.onPause()
        super.onPause()
    }

    override fun onStop() {
        if (::live2d.isInitialized) live2d.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopGeneration()
        speechQueue.release()
        backend?.release()
        if (ttsReady) runCatching { infer.release() }
        if (::live2d.isInitialized) live2d.onDestroy()
    }

    companion object {
        private const val TAG = "LlmChatActivity"
    }
}
