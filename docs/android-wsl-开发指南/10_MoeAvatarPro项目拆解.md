# 10 MoeAvatarPro 项目拆解（新手向）

> 读者画像：会 Python，**没碰过 Android/Kotlin**。
> 目标：30 分钟看懂 `apps/Android/MoeAvatarPro/` 是怎么把 **LLM 对话 + BertVITS2 语音 + Live2D 角色 + 流式 ASR** 拼成一个能跑的桌宠 App 的。
>
> 配套阅读：
> - 编 / 装 APK：[09_MoeAvatarPro编译部署技能_给AI用.md](09_MoeAvatarPro编译部署技能_给AI用.md)
> - TTS 接入历史：[08_MoeAvatar_BertVITS2_TTS接入实录.md](08_MoeAvatar_BertVITS2_TTS接入实录.md)
> - 旁参：[07_MnnLlmChat项目拆解.md](07_MnnLlmChat项目拆解.md)（Pro 是从那个项目分出来的，UI 简化版）

---

## 0. 一张图先建心智模型

```
                ┌───────────────── LauncherActivity（首页 4 大按钮）─────────────────┐
                │  ① 聊天      ② 单测 TTS      ③ 单测 Live2D      ④ 单测 ASR         │
                └────────────┬───────────────┬──────────────────┬───────────────────┘
                             ▼                 ▼                  ▼
              ┌──────────────────────┐  ┌──────────────┐   ┌─────────────────────┐
              │ LlmChatActivity ★主页 │  │ TtsActivity  │   │ AsrActivity         │
              │ Live2D + LLM + TTS   │  │              │   │ Live2DActivity      │
              └──────────┬───────────┘  └──────────────┘   └─────────────────────┘
                         │
        ┌────────────────┼─────────────────┬────────────────────────┐
        ▼                ▼                 ▼                        ▼
   LlmBackend       SpeechQueue      Live2DController          (未来) Asr 输入
   (LLM 抽象)        (TTS 流水线)     (GLSurfaceView+JNI)
        │                │                 │
        │                │                 └─→ Cubism .moc3 / .model3.json (assets)
        │                └─→ BertVITS2 .so + cppjieba/openjtalk/...（synth 出 PCM）
        │                                       │
        │                                       └→ 旁路 PCM → Live2D 嘴型 (RMS)
        │
        ├─→ LocalLlmBackend  → libMNN.so + libllm.so + libmoeavatar_llm.so（端侧）
        └─→ OpenAiLlmBackend → OkHttp SSE → /v1/chat/completions（远端）
```

**Python 类比**（沿用文档 07）：
- Activity ≈ Flask 一个路由的 view 函数
- Module（gradle 子工程）≈ Python 子包，独立编译，互相 `import`
- `external fun` JNI ≈ Python 的 `ctypes` / `pybind11`
- `Flow<String>` ≈ Python 的 `async generator`，`collect` 就是 `async for`

---

## 1. 项目定位 & 与 MnnLlmChat 的区别

| 维度 | MnnLlmChat | **MoeAvatarPro** |
|------|------------|------------------|
| applicationId | `com.alibaba.mnnllm.android` | `com.moeavatar.pro`（可同机共存） |
| 主打 | 模型市场 + 端侧 LLM 通用聊天 | "桌宠"：角色形象 + 语音 + 端侧/远端 LLM 二选一 |
| LLM 后端 | 只有本地 MNN | **本地 MNN ＋ OpenAI 兼容 API** 双后端，UI 设置里切 |
| TTS | 没有 | **BertVITS2-MNN**，流式合成 |
| 角色显示 | 没有 | **Live2D Cubism**，全屏背景 + TTS 嘴型同步 |
| ASR | 没有 | **sherpa-mnn 流式 zipformer**（独立页，暂未接进聊天输入） |
| 代码体量 | 数十个 Activity / Fragment | 5 个 Activity，单兵突击 |
| 适合谁读 | 想做"模型管理 + 多模型聊天"的产品 | 想做"虚拟伙伴 / VTuber / 单人 chatbot" 的玩家 |

> 一句话：MnnLlmChat 是平台，MoeAvatarPro 是 demo —— 但 demo 把 LLM/TTS/Live2D/ASR 都串通了，这点反而比平台更适合"复制改造一个自己的 App"。

---

## 2. Gradle 工程结构（顶层）

```
MoeAvatarPro/
├── app/                       ★ 主 App（5 个 Activity 都在这）
├── live2d/                    ← Cubism SDK 包装（GLSurfaceView + JniBridge）
├── sherpa/                    ← sherpa-mnn 流式 ASR（zipformer JNI）
├── cppjieba/                  ← BertVITS2 文本前端：中文分词
├── cpptokenizer/              ← BertVITS2 文本前端：通用 tokenizer
├── openjtalk/                 ← BertVITS2 文本前端：日语注音
├── text-preprocess/           ← BertVITS2 文本前端：高层 wrapper
├── third_party/               ← 预编译 .so（libMNN/libllm/libbertvits2/libc++_shared 等）
├── settings.gradle            ← 已配阿里云 Gradle/Maven 镜像
├── gradle.properties
├── build.gradle               ← 顶层 build 脚本
└── README.md
```

每个子目录都是一个 **Gradle module**（约等于"Python 子包 + 各自的 setup.py"），
`settings.gradle` 通过 `include ':live2d'` 这种语法把它们组装成一个 multi-project。

`app/build.gradle` 里通过 `implementation project(':live2d')` 引用兄弟模块 ——
**只有 app 是 application（出 APK），其余都是 library（出 .aar）**。

### 2.1 模块职责一句话

| Module | 干什么 | 关键产物 |
|--------|--------|---------|
| `app` | UI + 编排，把另外几个胶起来 | APK |
| `live2d` | Cubism SDK 的 native + Kotlin 封装 + 模型 assets | `libnative-lib.so` + `assets/Live2DModels/{ATRI,Yuuka,Amadeus}` |
| `sherpa` | k2-fsa sherpa-mnn 的 JNI 封装 | `libsherpa-mnn-jni.so`（已被 patchelf 加 `DT_NEEDED libc++_shared.so`） |
| `cppjieba`/`cpptokenizer`/`openjtalk`/`text-preprocess` | BertVITS2 文本前端链 | 各自 `.so` + 词典 |
| `third_party` | 不归 gradle 管，纯文件挂入 | MNN/llm/bertvits2 等 `.so` |

### 2.2 app 模块依赖树

```
app
├── :live2d
├── :sherpa
└── :text-preprocess  → :cppjieba / :cpptokenizer / :openjtalk
```

---

## 3. `app/` 的包结构（按 4 大功能切）

```
app/src/main/java/com/moeavatar/
├── LauncherActivity.kt        ← 首页 4 大按钮入口
├── TtsActivity.kt             ← 单独验证 TTS 的页面（"嗓子测试"）
│
├── chat/                      ★ 主舞台
│   ├── LlmChatActivity.kt     ★★ Live2D + LLM + TTS 的合体页面
│   └── ChatAdapter.kt         ← RecyclerView 气泡列表
│
├── llm/                       ← LLM 抽象与两种实现
│   ├── ChatTurn.kt            ← 数据类：role + content
│   ├── LlmBackend.kt          ★ interface：prepare/chat/stop/...
│   ├── LocalLlmBackend.kt     ← 本地 MNN-LLM（包 LocalLlmBridge）
│   ├── LocalLlmBridge.kt      ← JNI external 函数 + System.loadLibrary
│   ├── OpenAiLlmBackend.kt    ← OkHttp SSE 调远端 /v1/chat/completions
│   ├── ModelScanner.kt        ← 扫 /sdcard/.../models/<name>/config.json
│   └── LlmConfig.kt           ← SharedPreferences 存配置（含 live2dModel 字段）
│
├── tts/                       ← 流式 TTS 三件套
│   ├── SpeechQueue.kt         ★ 合成 ‖ 播放 流水线，单 AudioTrack 共享
│   ├── SentenceSplitter.kt    ← 流式 token → 完整句
│   └── TtsTextFilter.kt       ← 过滤 (...)（不送 TTS）
│
├── live2d/
│   ├── Live2DController.kt    ★ GLSurfaceView 封装 + 角色预设 + 嘴型同步
│   └── Live2DActivity.kt      ← 单独看模型用的调试页
│
└── asr/
    └── AsrActivity.kt         ← sherpa 流式听写（独立页，暂未接进 chat 输入框）
```

剩下还有 native 入口 `com.example.bertvits2*`（TTS 推理库的 Kotlin wrapper），
那是从 `com.example.bertvits2_infer` 直接搬过来的，不是 MoeAvatarPro 自己写的。

---

## 4. 入口与启动顺序

### `AndroidManifest.xml` 节选

```xml
<application android:label="@string/app_name" ...>
  <activity android:name=".LauncherActivity"  android:exported="true">
      <intent-filter>
          <action android:name="android.intent.action.MAIN" />
          <category android:name="android.intent.category.LAUNCHER" />
      </intent-filter>
  </activity>
  <activity android:name=".TtsActivity"            android:windowSoftInputMode="adjustResize" />
  <activity android:name=".chat.LlmChatActivity"   android:windowSoftInputMode="adjustNothing" />
  <activity android:name=".live2d.Live2DActivity"  android:configChanges="..." />
  <activity android:name=".asr.AsrActivity"        android:windowSoftInputMode="adjustResize" />
</application>
```

启动流程：

1. 系统加载 `LauncherActivity`（**唯一**的 `LAUNCHER`）。
2. 用户在 Launcher 上按 4 个按钮之一 → `Intent` 跳到对应 Activity。
   - 实际产品里 90% 时间在 `LlmChatActivity`。其它三个是开发用的"单元测试页"。
3. `LlmChatActivity.onCreate()` 干这几件大事（顺序很重要）：
   - 找 `R.id.live2d_container` / `R.id.chat_container`，把 Live2D 塞进背景
   - **异步**初始化 BertVITS2 推理器（耗时 ~3s）
   - **异步**根据配置 `prepareBackend()` 加载 LLM（本地或远端）
   - 注册按钮、菜单（设置/清空）、IME inset 监听器（让输入框躲键盘）

---

## 5. 主舞台 `LlmChatActivity` 串起来的数据流

`app/src/main/java/com/moeavatar/chat/LlmChatActivity.kt:199` 的 `sendCurrentInput()`
是整个 App 的"心脏"：

```
用户在 EditText 按发送
        │
        ▼
sendCurrentInput()                       LlmChatActivity.kt:199
  │  ├─ adapter.appendUser(text)         （UI 立刻显示气泡）
  │  ├─ adapter.appendAssistantPlaceholder()
  │  └─ chatJob = launch { ... }          ← 一个 coroutine 跑下面
  │
  ▼
backend.chat(history) : Flow<String>     LlmBackend.kt
  │
  ├─ LocalLlmBackend：JNI 调 libmoeavatar_llm.so → MNN 推理 → 每 token 回调 emit
  └─ OpenAiLlmBackend：OkHttp SSE → 解析 data: {...} → 每 chunk emit
  │
  ▼ collect { token ->
        accum.append(token)
        adapter.updateAt(asstIdx, accum)         ← UI 路径：原文（保留括号）
        scrollToEnd()

        ttsChunk = TtsTextFilter.feed(token)     ← TTS 路径：去掉 (动作描述)
        for (s in SentenceSplitter.feed(ttsChunk)) {
            speechQueue.enqueue(s)               ← 切句送 TTS 队列
        }
    }
        │
        ▼
SpeechQueue（合成 ‖ 播放 双线程）          tts/SpeechQueue.kt
  │
  ├─ synth worker: 句子 → BertVITS2 → samples: FloatArray
  │       └→ Channel<PCM> 缓冲（背压）
  │
  └─ play worker: 取 PCM → AudioTrack.write（MODE_STREAM 共享 track，无 gap）
                          └→ pcmListener?.invoke(samples, sr)   ← 旁路给 Live2D
                                  │
                                  ▼
            Live2DController.feedAudioForLipSync()      live2d/Live2DController.kt:115
                ├─ 每 50ms 一帧算 RMS（声音能量）
                ├─ rms → mouthOpen ∈ [0, 1]（gain=6, noise floor=0.01）
                └─ glView.queueEvent {
                       JniBridgeJava.nativeProjectMouthOpenY(mouthOpen)
                   }                              ← 嘴跟着说话起伏开合
```

> 关键设计点：
> - **UI 文本**和 **TTS 文本**两条路径独立 —— 用户能看到 `(笑)` 但 TTS 不会念出来。
> - **TTS 推 PCM 给 Live2D 是"旁路"**，不是 Live2D 主动拉。这样换 TTS 引擎只需保持
>   "出 FloatArray + sampleRate"接口，Live2D 那边零修改。
> - **Job 取消**：`btnSend` 二次按下触发 `chatJob.cancel()`，Flow 自动级联清掉
>   生成线程、网络连接、TTS 队列。

---

## 6. LLM 后端抽象：`LlmBackend`

`llm/LlmBackend.kt` 是个极简接口：

```kotlin
interface LlmBackend {
    val displayName: String
    val ready: Boolean
    suspend fun prepare(): Boolean
    fun chat(history: List<ChatTurn>): Flow<String>   // 流式
    fun stop()
    fun resetSession()
    fun release()
}
```

两种实现：

| 实现 | 入口 | 适用 |
|------|------|------|
| `LocalLlmBackend(configPath)` | `nativePtr = LocalLlmBridge.initNative(configPath)` | 端侧推理；依赖 jniLibs/ 下 `libMNN.so + libMNN_Express.so + libllm.so + libmoeavatar_llm.so` 全部加载成功 |
| `OpenAiLlmBackend(baseUrl, apiKey, model, systemPrompt)` | `OkHttpClient` + 自己解 SSE | 开发联调或显存吃紧时；`baseUrl` 兼容 OpenAI / vLLM / Ollama / 自建网关 |

**怎么切**：`LlmConfig.backendKind = LOCAL or OPENAI`，菜单 ⚙ 里改完保存触发
`prepareBackend()` 重新加载（自动 release 旧的）。

**怎么加新后端**：例如想加 Anthropic / Gemini，写一个 `class ClaudeBackend : LlmBackend`
在 `chat(history)` 里实现 SSE 解码、`emit(token)` 即可，UI 完全不需要改。

---

## 7. TTS 流水线：为什么句子之间不会"嘶——嘶——"

`tts/SpeechQueue.kt` 用了**两个 worker + 一个共享 AudioTrack** 的经典设计：

```
LLM 流  ──切句──┐
                ▼
         input Channel<String>
                │
   [synth worker]   ←  BertVITS2 合成（CPU 密集）
                │
         pcm Channel<FloatArray>   ← 有界，自动背压
                │
   [audio worker]   ←  AudioTrack(MODE_STREAM).write(...)（顺序写一个 track）
                │
                ▼
              扬声器
```

为啥要这么搞？

- 如果"合成完一句就 stop track，再 start track 播下一句"，每次 `start` 有几十 ms 缓冲填充延迟，听起来像断句卡顿。
- 现在所有句子的 PCM 顺序写到**同一个 track 的环形缓冲**里，`WRITE_BLOCKING` 模式下缓冲满了自然等下一帧空出来，缓冲有数据就持续放音 —— 句与句之间是同一段连续 PCM，0 gap。

这一套是踩了 [文档 08](08_MoeAvatar_BertVITS2_TTS接入实录.md) 里那些坑之后定下来的。

---

## 8. Live2D 渲染与角色切换

### 8.1 Live2DController 的职责

`live2d/Live2DController.kt` 把 Cubism 那一套（`GLSurfaceView` + `GLRenderer` + `JniBridgeJava`）全部包起来，对外只暴露 6 个方法：

```kotlin
class Live2DController(context: Context, container: ViewGroup) {
    fun onCreate(presetName: String = "ATRI")
    fun switchModel(presetName: String)              // 切角色
    fun feedAudioForLipSync(samples: FloatArray, sr: Int)  // 喂 PCM 做嘴型
    fun closeMouth()
    fun onResume() / onPause() / onStop() / onDestroy()
}
```

### 8.2 角色预设

```kotlin
data class ModelPreset(
    val dirAsset: String,        // "Live2DModels/ATRI/"
    val modelJson: String,       // "ATRI.model3.json"
    val translateX: Float = 0f,
    val translateY: Float = 0f,
    val scale:      Float = 1f,
)

val PRESETS = mapOf(
    "ATRI"    to ModelPreset("Live2DModels/ATRI/",    "ATRI.model3.json",    0f, -0.6f, 3f),
    "Yuuka"   to ModelPreset("Live2DModels/Yuuka/",   "Yuuka.model3.json",   0f, -0.6f, 4f),
    "Amadeus" to ModelPreset("Live2DModels/Amadeus/", "Amadeus.model3.json", 0f,  0f,   2f),
)
```

加载完模型后 `onLoadDone` 回调会把 translateX/Y/scale 应用到 native，画面才不会是"模型缩在屏幕中央一小块"。

### 8.3 嘴型同步算法

简单暴力但够用：

```
samples (FloatArray) → 每 50ms 切一帧
                    → 帧内算 RMS = sqrt( Σx² / N )
                    → mouthOpen = clamp((rms - 0.01) * 6, 0, 1)
                    → nativeProjectMouthOpenY(mouthOpen)
```

**没**做 phoneme 对齐 / viseme（"啊嘴 / 哦嘴 / 闭嘴"），只看声音能量起伏 ——
说话时嘴张大，停顿时合上，效果在桌宠场景已经够用。要更真实就得做 phoneme alignment。

### 8.4 换角色操作清单

详见之前给你的回答，简版：

1. 模型 assets 拷到 `live2d/src/main/assets/Live2DModels/<名字>/`
2. `Live2DController.PRESETS` 加一行（注意 `scale` / `translateY` 调到画面合适）
3. （可选）改默认：`LlmConfig.kt` 里 `K_LIVE2D` 默认值
4. 重编：`./gradlew :app:assembleDebug`

---

## 9. ASR：sherpa-mnn 流式 zipformer

当前状态：**独立页可用，未接进聊天输入框**（任务 #46 待做）。

- Module：`sherpa/`，Kotlin 封装在 `com.k2fsa.sherpa.mnn.*`
- Native：`libsherpa-mnn-jni.so` 已 patchelf 加 `DT_NEEDED libc++_shared.so`，
  `SherpaNative.ensureLoaded()` 是唯一的 native 加载入口
- 模型：`/data/local/tmp/asr/sherpa-mnn-streaming-zipformer-bilingual-zh-en-2023-02-20/`（来自 ModelScope:[MNN/sherpa-mnn-streaming-zipformer-bilingual-zh-en-2023-02-20](https://www.modelscope.cn/models/MNN/sherpa-mnn-streaming-zipformer-bilingual-zh-en-2023-02-20)，下载与 push 命令见 doc 13 §7。**不要**留在 `/sdcard/Download` 下，scoped storage 会拦读权限）
- 入口：`AsrActivity.kt`，按住按钮录音 + 实时出文字

要把它接进 chat：在 `LlmChatActivity` 输入栏左边加个 🎤 按钮，
长按用 `MediaRecorder.AudioSource.MIC` 喂给 sherpa 的 `OnlineRecognizer`，
弹起把 `recognizer.text` 写到 `input.setText(...)` 即可。

---

## 10. 配置文件 `LlmConfig`

`SharedPreferences` 当 KV 存的 7 个字段：

| Key | 含义 | 默认 |
|-----|------|------|
| `backend_kind` | 用本地还是 OpenAI | `LOCAL` |
| `local_dir` | 模型扫描根目录 | `/sdcard/Download/MoeAvatar/models` |
| `local_name` | 当前选中的模型子目录名 | null |
| `oai_base` | OpenAI 兼容端点 | `https://api.openai.com` |
| `oai_key` | API key | `""` |
| `oai_model` | 模型名 | `gpt-4o-mini` |
| `sys_prompt` | system 提示 | （角色扮演风预设） |
| `tts_speaker` | BertVITS2 说话人 | 自动选含"甘雨"的或第一个 |
| `live2d_model` | 启动时角色 | `ATRI` |

> **本仓默认 LLM**：本地端用我自己微调的猫娘 0.8B —— [jiaohui/qwen35_08b_nekoneko](https://modelscope.cn/models/jiaohui/qwen35_08b_nekoneko)（基于 Qwen3-0.8B，已转 MNN 格式）。下载后放 `local_dir` 指向的目录，启动时会被 `ModelScanner` 扫到，在设置里能选；`sys_prompt` 走预设的角色扮演 prompt 即可。
>
> ```bash
> pip install modelscope
> modelscope download --model jiaohui/qwen35_08b_nekoneko --local_dir /tmp/qwen35_08b_nekoneko
> adb push /tmp/qwen35_08b_nekoneko /sdcard/Download/MoeAvatar/models/
> ```

设置弹窗在 `LlmChatActivity.showSettings()`，UI 在 `res/layout/dialog_chat_settings.xml`。

---

## 11. 新手最容易看错的几件事

| 误解 | 实际 |
|------|------|
| "改了代码 APK 就重新编了" | 不会，必须显式 `./gradlew :app:assembleDebug` |
| "Live2D 嘴型抖说明 RMS 算错了" | 多半是 `LIP_SYNC_GAIN`/`LIP_SYNC_NOISE_FLOOR` 跟你的 TTS 音量不匹配，调常量即可 |
| "TTS 不出声 = LLM 没出 token" | 经常是 `TtsTextFilter` 把整句吃成空串（句子全在括号里），看 logcat 即可确认 |
| "切 Live2D 角色要改 native" | 不需要，改 `PRESETS` map 就够了；Cubism 引擎本身只看 model3.json |
| "本地模型加载失败 = .so 没编对" | 大概率是 `LocalLlmBridge.nativeAvailable=false`，看 logcat 哪一个 `System.loadLibrary` 抛了 UnsatisfiedLinkError |
| "ASR 报 `regex_error` 是 sherpa 的 bug" | 是 Android linker namespace 隔离问题，`libsherpa-mnn-jni.so` 必须有 `DT_NEEDED libc++_shared.so`（已 patchelf 处理） |

---

## 12. 快速索引：我要做 X，应该改哪？

| 想做的事 | 改哪 |
|---------|------|
| 看主流程怎么串的 | `chat/LlmChatActivity.kt` |
| 加 LLM 后端（Claude/Gemini/...） | 实现 `llm/LlmBackend.kt`，参考 `OpenAiLlmBackend.kt` |
| 调嘴型灵敏度 | `live2d/Live2DController.kt` 的 `LIP_SYNC_GAIN`/`LIP_SYNC_NOISE_FLOOR` |
| 加/换 Live2D 角色 | `live2d/Live2DController.kt` 的 `PRESETS` + `live2d/src/main/assets/Live2DModels/` |
| 改默认角色 / 默认 TTS 说话人 | `llm/LlmConfig.kt` 默认值 |
| 加 TTS 后端 | 实现 `IBertVITS2SimpleInfer` 的同形接口，替换 `LlmChatActivity.infer` 即可 |
| 改"括号不念出来"规则 | `tts/TtsTextFilter.kt` |
| 改切句策略（句子太长/太短） | `tts/SentenceSplitter.kt` 的 `minFallback` / `maxHard` |
| 把 ASR 接进输入框 | `chat/LlmChatActivity.kt` + `asr/AsrActivity.kt`（参考 sherpa OnlineRecognizer 用法） |
| 编 APK / 装手机 | [09_MoeAvatarPro编译部署技能_给AI用.md](09_MoeAvatarPro编译部署技能_给AI用.md) |
