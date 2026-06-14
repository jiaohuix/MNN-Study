package com.moechat

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.moechat.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: MessageAdapter

    private var nativePtr: Long = 0L
    private var loaded = false
    private var generateJob: Job? = null

    private val prefs by lazy { getSharedPreferences("moechat", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        adapter = MessageAdapter(messages)
        binding.recyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.recyclerView.adapter = adapter

        binding.btnLoad.setOnClickListener { promptForConfig() }
        binding.btnSend.setOnClickListener { sendOrStop() }
        binding.btnReset.setOnClickListener { resetSession() }

        binding.editInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { updateSendButton() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        // Auto-load if previous config saved
        prefs.getString("configPath", null)?.let { saved ->
            if (File(saved).exists()) loadModel(saved)
        }

        showSystemHint()
        updateSendButton()
        ensureAllFilesAccess()
    }

    private fun ensureAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()) {
            AlertDialog.Builder(this)
                .setTitle("需要文件访问权限")
                .setMessage("MoeChat 需要读取手机里的 MNN 模型文件。点确定去打开「所有文件访问」权限。")
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        intent.data = Uri.parse("package:$packageName")
                        startActivity(intent)
                    } catch (e: Exception) {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun showSystemHint() {
        if (messages.isEmpty()) {
            messages.add(ChatMessage(
                ChatMessage.Role.ASSISTANT,
                getString(R.string.welcome_hint)
            ))
            adapter.notifyItemInserted(0)
        }
    }

    private fun updateSendButton() {
        val hasText = binding.editInput.text?.isNotBlank() == true
        val streaming = generateJob?.isActive == true
        binding.btnSend.isEnabled = (hasText && loaded) || streaming
        binding.btnSend.text = if (streaming) getString(R.string.btn_stop) else getString(R.string.btn_send)
    }

    private fun promptForConfig() {
        val input = EditText(this).apply {
            hint = "/sdcard/Download/MODEL/config.json"
            setText(prefs.getString("configPath", "") ?: "")
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_load_title)
            .setMessage(R.string.dialog_load_msg)
            .setView(input)
            .setPositiveButton(R.string.btn_load) { _, _ ->
                val path = input.text.toString().trim()
                if (path.isNotEmpty() && File(path).exists()) {
                    prefs.edit().putString("configPath", path).apply()
                    loadModel(path)
                } else {
                    Toast.makeText(this, R.string.err_path, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun loadModel(configPath: String) {
        if (loaded) {
            releaseNative()
        }
        binding.toolbar.subtitle = getString(R.string.status_loading)
        binding.btnLoad.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            val ptr = try {
                LlmBridge.initNative(configPath)
            } catch (t: Throwable) {
                0L
            }
            withContext(Dispatchers.Main) {
                binding.btnLoad.isEnabled = true
                if (ptr == 0L) {
                    binding.toolbar.subtitle = getString(R.string.status_load_failed)
                    Toast.makeText(this@MainActivity, R.string.err_load, Toast.LENGTH_LONG).show()
                } else {
                    nativePtr = ptr
                    loaded = true
                    binding.toolbar.subtitle = File(configPath).parentFile?.name ?: "loaded"
                    Toast.makeText(this@MainActivity, R.string.toast_loaded, Toast.LENGTH_SHORT).show()
                    updateSendButton()
                }
            }
        }
    }

    private fun resetSession() {
        if (loaded) {
            lifecycleScope.launch(Dispatchers.IO) { LlmBridge.resetNative(nativePtr) }
        }
        messages.clear()
        showSystemHint()
        adapter.notifyDataSetChanged()
    }

    private fun sendOrStop() {
        if (generateJob?.isActive == true) {
            if (loaded) LlmBridge.stopNative(nativePtr)
            return
        }
        val text = binding.editInput.text.toString().trim()
        if (text.isEmpty()) return
        if (!loaded) {
            Toast.makeText(this, R.string.err_no_model, Toast.LENGTH_SHORT).show()
            return
        }
        binding.editInput.setText("")
        hideKeyboard(binding.editInput)
        adapter.appendMessage(ChatMessage(ChatMessage.Role.USER, text))
        scrollToBottom()

        val replyMsg = ChatMessage(ChatMessage.Role.ASSISTANT, "", isStreaming = true)
        adapter.appendMessage(replyMsg)
        scrollToBottom()
        updateSendButton()

        val sb = StringBuilder()
        val listener = object : LlmBridge.TokenListener {
            override fun onToken(token: String): Boolean {
                if (token == "<eop>") return false
                sb.append(token)
                runOnUiThread {
                    adapter.updateLast(sb.toString(), streaming = true)
                    scrollToBottom()
                }
                return false
            }
        }

        generateJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                LlmBridge.submitNative(nativePtr, text, listener)
            } catch (t: Throwable) {
                t.printStackTrace()
            }
            withContext(Dispatchers.Main) {
                adapter.updateLast(sb.toString().ifEmpty { "(empty)" }, streaming = false)
                scrollToBottom()
                updateSendButton()
            }
        }
    }

    private fun scrollToBottom() {
        binding.recyclerView.post {
            if (adapter.itemCount > 0) {
                binding.recyclerView.smoothScrollToPosition(adapter.itemCount - 1)
            }
        }
    }

    private fun hideKeyboard(view: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun releaseNative() {
        generateJob?.cancel()
        if (loaded) {
            LlmBridge.stopNative(nativePtr)
            LlmBridge.releaseNative(nativePtr)
            nativePtr = 0
            loaded = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseNative()
    }
}
