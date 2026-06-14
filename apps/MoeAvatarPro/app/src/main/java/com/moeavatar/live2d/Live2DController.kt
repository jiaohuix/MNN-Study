package com.moeavatar.live2d

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import com.chatwaifu.live2d.GLRenderer
import com.chatwaifu.live2d.JniBridgeJava

/**
 * Live2D 渲染 + 口型 + 触控的统一封装。Activity 只需要：
 *
 *   ```kotlin
 *   val live2d = Live2DController(this, container)
 *   live2d.onCreate(modelDir = "Live2DModels/ATRI/", modelJson = "ATRI.model3.json")
 *   ...
 *   live2d.feedAudioForLipSync(samples, sampleRate)   // TTS PCM 喂进来做嘴型
 *   ...
 *   live2d.onResume()/onPause()/onStop()/onDestroy()  // 跟 Activity 生命周期
 *   ```
 *
 * 缩放 / 平移：在加载完模型后，按预置 [ModelPreset] 调一次 transform/scale，
 * 这样 ATRI 不再"缩在画面正中"，而是像 ChatWaifu 那样头肩贴满屏幕。
 *
 * 口型同步：基于 PCM 包络（每 ~50ms 一帧的 RMS）调用 nativeProjectMouthOpenY。
 * 简单但够用 —— 真正的 viseme 对齐需要 phoneme alignment，超过当前阶段范围。
 */
class Live2DController(
    private val context: Context,
    private val container: ViewGroup,
) {
    /** 每个角色的默认 (translateX, translateY, scale) —— 来自 ChatWaifu_Mobile. */
    data class ModelPreset(
        val dirAsset: String,        // 例如 "Live2DModels/ATRI/"
        val modelJson: String,       // 例如 "ATRI.model3.json"
        val translateX: Float = 0f,
        val translateY: Float = 0f,
        val scale: Float = 1f,
    )

    private lateinit var glView: GLSurfaceView
    private var currentPreset: ModelPreset = PRESETS[DEFAULT_NAME]!!
    private var loaded = false

    fun onCreate(presetName: String = DEFAULT_NAME) {
        currentPreset = PRESETS[presetName] ?: PRESETS[DEFAULT_NAME]!!
        JniBridgeJava.SetContext(context.applicationContext)
        // 不调 SetActivityInstance — 这里 context 不是 Activity 也能跑
        glView = GLSurfaceView(context).apply {
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
        container.addView(
            glView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        )
        JniBridgeJava.nativeOnStart()
        JniBridgeJava.setLive2DLoadInterface(object : JniBridgeJava.Live2DLoadInterface {
            override fun onLoadError() { Log.e(TAG, "live2d onLoadError") }
            override fun onLoadDone() {
                Log.i(TAG, "live2d onLoadDone, applying preset ${currentPreset.dirAsset}")
                glView.queueEvent {
                    JniBridgeJava.nativeProjectTransformX(currentPreset.translateX)
                    JniBridgeJava.nativeProjectTransformY(currentPreset.translateY)
                    JniBridgeJava.nativeProjectScale(currentPreset.scale)
                }
            }
            override fun onLoadOneMotion(group: String, index: Int, name: String) {}
            override fun onLoadOneExpression(name: String, index: Int) {}
        })
        glView.queueEvent {
            JniBridgeJava.nativeProjectChangeTo(currentPreset.dirAsset, currentPreset.modelJson)
            JniBridgeJava.nativeAutoBlinkEyes(true)
        }
        loaded = true
    }

    /** 切换模型（设置里选了别的角色时调）。安全在主线程调用。 */
    fun switchModel(presetName: String) {
        val preset = PRESETS[presetName] ?: return
        currentPreset = preset
        glView.queueEvent {
            JniBridgeJava.nativeProjectChangeTo(preset.dirAsset, preset.modelJson)
        }
    }

    fun onResume() { if (loaded) glView.onResume() }
    fun onPause() { if (loaded) { glView.onPause(); JniBridgeJava.nativeOnPause() } }
    fun onStop() { if (loaded) JniBridgeJava.nativeOnStop() }
    fun onDestroy() { if (loaded) JniBridgeJava.nativeOnDestroy(); loaded = false }

    // --- lip sync ---------------------------------------------------------

    /**
     * TTS 播放线程喂入 PCM 数据，控制器自己按 ~50ms 帧切片，算 RMS → mouthOpen。
     *
     * RMS 阈值/增益按经验值调。简单但 OK：嘴型跟着说话能量起伏，没说话的时候闭嘴。
     */
    @Volatile private var lastFeedMs = 0L
    fun feedAudioForLipSync(samples: FloatArray, sampleRate: Int) {
        if (!loaded || samples.isEmpty()) return
        val frame = ((sampleRate * LIP_SYNC_FRAME_MS / 1000).toInt()).coerceAtLeast(64)
        var i = 0
        while (i < samples.size) {
            val end = (i + frame).coerceAtMost(samples.size)
            var sum = 0f
            for (j in i until end) sum += samples[j] * samples[j]
            val rms = kotlin.math.sqrt(sum / (end - i))
            val mouth = ((rms - LIP_SYNC_NOISE_FLOOR) * LIP_SYNC_GAIN)
                .coerceIn(0f, 1f)
            // 节流：连续帧间隔 LIP_SYNC_FRAME_MS 推送一次到 native
            val now = SystemClock.uptimeMillis()
            if (now - lastFeedMs >= LIP_SYNC_FRAME_MS) {
                lastFeedMs = now
                glView.queueEvent { JniBridgeJava.nativeProjectMouthOpenY(mouth) }
            }
            i = end
        }
    }

    /** 没声音时回到闭嘴。 */
    fun closeMouth() {
        if (!loaded) return
        glView.queueEvent { JniBridgeJava.nativeProjectMouthOpenY(0f) }
    }

    companion object {
        private const val TAG = "Live2DController"

        private const val LIP_SYNC_FRAME_MS = 50L
        private const val LIP_SYNC_NOISE_FLOOR = 0.01f
        private const val LIP_SYNC_GAIN = 6f

        const val DEFAULT_NAME = "ATRI"

        // 预设来自 ChatWaifu_Mobile LocalModelManager#getDefaultModelPosition
        // ATRI / Yuuka 模型本身画布很大，scale=1 时只占屏幕中央一小块；这里给到 3/4
        // 让头肩贴满屏幕。translateY=-0.6 把人物往下沉一点，露出头顶留点天空。
        val PRESETS: Map<String, ModelPreset> = mapOf(
            "ATRI" to ModelPreset(
                dirAsset = "Live2DModels/ATRI/",
                modelJson = "ATRI.model3.json",
                translateX = 0f,
                translateY = -0.6f,
                scale = 3f,
            ),
            "Yuuka" to ModelPreset(
                dirAsset = "Live2DModels/Yuuka/",
                modelJson = "Yuuka.model3.json",
                translateX = 0f,
                translateY = -0.6f,
                scale = 4f,
            ),
            "Amadeus" to ModelPreset(
                dirAsset = "Live2DModels/Amadeus/",
                modelJson = "Amadeus.model3.json",
                translateX = 0f,
                translateY = 0f,
                scale = 2f,
            ),
        )
    }
}
