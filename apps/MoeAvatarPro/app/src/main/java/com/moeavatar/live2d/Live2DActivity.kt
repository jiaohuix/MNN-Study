package com.moeavatar.live2d

import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import com.chatwaifu.live2d.GLRenderer
import com.chatwaifu.live2d.JniBridgeJava

/**
 * Live2D 角色展示。第一阶段只把 ATRI 模型加载并渲染出来；后续 TTS 播放时
 * 可以从外部调用 [setMouthOpen] 做 lip-sync。
 *
 * 模型文件由 :live2d module 的 assets/Live2DModels/ 提供。
 */
class Live2DActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Live2DActivity"
        const val EXTRA_MODEL_DIR = "model_dir"        // assets 目录: Live2DModels/ATRI/
        const val EXTRA_MODEL_JSON = "model_json"      // ATRI.model3.json
        private const val DEFAULT_MODEL_DIR = "Live2DModels/ATRI/"
        private const val DEFAULT_MODEL_JSON = "ATRI.model3.json"
    }

    private lateinit var glView: GLSurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        JniBridgeJava.SetContext(applicationContext)
        JniBridgeJava.SetActivityInstance(this)

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(GLRenderer())
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> JniBridgeJava.nativeOnTouchesBegan(e.x, e.y)
                    MotionEvent.ACTION_MOVE -> JniBridgeJava.nativeOnTouchesMoved(e.x, e.y)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        JniBridgeJava.nativeOnTouchesEnded(e.x, e.y)
                }
                true
            }
        }
        setContentView(glView)

        JniBridgeJava.nativeOnStart()
        JniBridgeJava.setLive2DLoadInterface(object : JniBridgeJava.Live2DLoadInterface {
            override fun onLoadError() { Log.e(TAG, "live2d onLoadError") }
            override fun onLoadDone() { Log.i(TAG, "live2d onLoadDone") }
            override fun onLoadOneMotion(group: String, index: Int, name: String) {}
            override fun onLoadOneExpression(name: String, index: Int) {}
        })

        val dir = intent.getStringExtra(EXTRA_MODEL_DIR) ?: DEFAULT_MODEL_DIR
        val json = intent.getStringExtra(EXTRA_MODEL_JSON) ?: DEFAULT_MODEL_JSON
        glView.queueEvent {
            JniBridgeJava.nativeProjectChangeTo(dir, json)
            JniBridgeJava.nativeAutoBlinkEyes(true)
        }
    }

    /** 0~1，可由 TTS RMS 驱动 */
    fun setMouthOpen(value: Float) {
        glView.queueEvent { JniBridgeJava.nativeProjectMouthOpenY(value) }
    }

    override fun onResume() { super.onResume(); glView.onResume() }

    override fun onPause() {
        glView.onPause()
        JniBridgeJava.nativeOnPause()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        JniBridgeJava.nativeOnStop()
    }

    override fun onDestroy() {
        JniBridgeJava.nativeOnDestroy()
        super.onDestroy()
    }
}
