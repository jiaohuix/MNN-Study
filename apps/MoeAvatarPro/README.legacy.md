# MoeChat ✨ — 抽离版二次元 MNN 聊天 App

> 极简版 MNN-LLM Chat：抽离 [MnnLlmChat](../MnnLlmChat) 的"对话核心"，去掉模型市场、下载器、TTS、ASR、Diffusion、HTTP API、历史会话等所有外围模块。
> 只保留：**加载本地 MNN LLM 模型 → 聊天框 → 流式回复**。

---

## 体积对比

| 项目 | APK 大小 | Java/Kotlin 文件数 | 主要依赖 |
|------|---------|-----|------|
| MnnLlmChat | ~42 MB | 200+ | Material3 + Compose + Ktor + Koin + Markwon + CameraX + Firebase + Sherpa + … |
| **MoeChat**     | **~8.7 MB** | **4 个 kt 文件** | Material3 + RecyclerView + Coroutines |

## 工程结构

```
apps/Android/MoeChat/
├── settings.gradle / build.gradle / gradle.properties
├── gradlew / gradle/wrapper/                    ← 共用 gradle-8.9 本地包
└── app/
    ├── build.gradle                             ← 单 Activity，只链 libMNN.so
    └── src/main/
        ├── AndroidManifest.xml
        ├── cpp/
        │   ├── CMakeLists.txt                    ← include MNN 头 + 引 libMNN.so
        │   └── moechat_jni.cpp                  ← initNative/submitNative/stop/release
        ├── java/com/moechat/
        │   ├── LlmBridge.kt                      ← external fun，加载 libmoechat.so
        │   ├── ChatMessage.kt                    ← user/assistant 消息数据类
        │   ├── MessageAdapter.kt                 ← RecyclerView 适配器
        │   └── MainActivity.kt                   ← 单 Activity：列表 + 输入栏
        └── res/
            ├── layout/
            │   ├── activity_main.xml             ← 渐变背景 + Toolbar + RV + 输入栏
            │   ├── item_message_user.xml         ← 粉紫渐变气泡
            │   └── item_message_assistant.xml    ← 白底圆角气泡 + 🌸 头像
            ├── drawable/                          ← 渐变 / 气泡 / 头像 / 启动图
            ├── mipmap-anydpi-v26/                 ← Adaptive icon
            └── values/                            ← 主题、文案
```

## 核心调用链

```
EditText → MainActivity.sendOrStop()
        → lifecycleScope.launch(Dispatchers.IO)
        → LlmBridge.submitNative(ptr, prompt, listener)   ← Kotlin external fun
        → moechat_jni.cpp Java_com_moechat_LlmBridge_submitNative
        → MNN::Transformer::Llm::response(prompt, &os)    ← libMNN.so
        ↓ 每个 token 写入 streambuf
        → cb(chunk) → listener.onToken(token)             ← JNI 回调 Kotlin
        → runOnUiThread { adapter.updateLast(全文) }
        → RecyclerView 最后一项追加显示（流式打字效果）
```

## 用法

1. 把 APK 装到手机：`/mnt/c/Users/jiaohuix/Downloads/MoeChat-debug.apk`
2. 把已经导出的 MNN 模型 `adb push` 到手机：
   ```
   adb push Qwen2-1.5B-Instruct-mnn /sdcard/Download/
   ```
   目录里要有 `config.json`（以及 `llm.mnn` / `tokenizer.txt` 等）。
3. 打开 App → 点左下角 📂 → 输入 config.json 绝对路径，例如：
   ```
   /sdcard/Download/Qwen2-1.5B-Instruct-mnn/config.json
   ```
4. 等弹出"模型加载成功 ♡" → 输入框打字 → 发送 → 等流式回复。

## 依赖前提

编译前需要：
- `project/android/build_64/lib/libMNN.so` 已经 `make install` 出来（CMakeLists.txt 直接引这个目录）
- `~/gradle-dist/gradle-8.9-bin.zip`（gradle-wrapper.properties 指向这里）
- JDK17、Android SDK 35、NDK 26.1、CMake 3.22.1

编译命令：

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_NDK=$ANDROID_HOME/ndk/26.1.10909125
cd apps/Android/MoeChat
./gradlew assembleDebug --no-daemon
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## 与 MnnLlmChat 的对应关系

| MoeChat 文件 | MnnLlmChat 对应 | 抽离掉的部分 |
|---|---|---|
| `LlmBridge.kt` | `llm/LlmSession.kt` | history / multimodal / config 热切换 / lora |
| `moechat_jni.cpp` | `cpp/llm_mnn_jni.cpp` + `llm_session.cpp` | crash report / firebase / 多步 stepping / video / metrics |
| `MainActivity.kt` | `chat/ChatActivity.kt` + `ChatPresenter.kt` + `ChatListComponent` | 模型市场 / 历史会话 / TTS / ASR / 图片附件 / 数据库 |
| `activity_main.xml` | `activity_chat.xml` | Drawer / FAB / Bottom Tab / Voice 模式 |
