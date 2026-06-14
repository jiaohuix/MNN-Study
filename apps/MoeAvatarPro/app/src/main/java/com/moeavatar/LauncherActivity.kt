package com.moeavatar

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.button.MaterialButton
import com.moeavatar.asr.AsrActivity
import com.moeavatar.chat.LlmChatActivity
import com.moeavatar.live2d.Live2DActivity

class LauncherActivity : AppCompatActivity() {

    private val legacyPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* no-op */ }

    private val micPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)
        findViewById<MaterialButton>(R.id.btn_chat).setOnClickListener {
            startActivity(Intent(this, LlmChatActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btn_live2d).setOnClickListener {
            startActivity(Intent(this, Live2DActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btn_asr).setOnClickListener {
            ensureMicPermission()
            startActivity(Intent(this, AsrActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btn_tts).setOnClickListener {
            startActivity(Intent(this, TtsActivity::class.java))
        }
        ensureStoragePermission()
    }

    /** /sdcard/Download/MoeAvatar/ 下的模型文件需要 MANAGE_EXTERNAL_STORAGE 才能读到（普通媒体权限不行）。 */
    private fun ensureStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) return
            AlertDialog.Builder(this)
                .setTitle("需要文件访问权限")
                .setMessage(
                    "MoeAvatarPro 需要读取 /sdcard/Download/MoeAvatar/ 下的模型 / 语音文件。\n\n" +
                            "点击\"前往设置\"开启\"所有文件访问权限\"，然后返回 App。"
                )
                .setCancelable(false)
                .setPositiveButton("前往设置") { _, _ ->
                    runCatching {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:$packageName")
                            }
                        )
                    }.onFailure {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
                .setNegativeButton("以后再说", null)
                .show()
        } else {
            val needed = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            )
            val missing = needed.filter {
                ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) legacyPermLauncher.launch(missing.toTypedArray())
        }
    }

    private fun ensureMicPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()
        ) return
    }
}
