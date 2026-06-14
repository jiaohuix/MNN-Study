# MoeAvatarPro

> MoeAvatar 的"全家桶"扩展版：在 LLM + TTS 的基础上加了 **Live2D 角色** 和 **流式 ASR 语音输入**。
> 用 `applicationId com.moeavatar.pro` 与原版分别安装，互不冲突。
>
> 状态：**所有结构 / Gradle / 源码均已就位，但尚未在 Android 设备上跑过验证**。本 README 列出明天起来要按顺序做的几件事。

---

## 0. 跟原版（`MoeAvatar/`）的关系

* **结构性变化**: 原版本来有 `bertvits2-jni` + `bertvits2-infer-wrapper` 两个 module 各带一份 `libMNN.so`，导致 AGP 打包冲突 → 本目录已经把它们**合并进 `app/` module**。同名的 `libMNN.so` 现在全工程只有一份。
* **新增 module**:
  * `:live2d` —— 来自 ChatWaifu Mobile，含 Cubism SDK + JNI + ATRI/Amadeus/Yuuka 模型 assets，编出 `libchatwaifu-live2d.so`。
  * `:sherpa` —— 来自 MnnLlmChat / MnnTaoAvatar，sherpa-mnn 的 Kotlin API + 预编译 `libsherpa-mnn-jni.so`（直接 ship，不用从 CDN 下）。
* **applicationId** 改为 `com.moeavatar.pro`，可与原版同机共存。
* **package name** 维持 `com.moeavatar.*`，所以 Kotlin 代码 import 跟原版一致。

## 1. 顶层模块图

```
MoeAvatarPro/
├── app/                            # 唯一的 application module
│   ├── src/main/
│   │   ├── cpp/                    # 合并后的 native 入口
│   │   │   ├── CMakeLists.txt      # 父 CMake：声明唯一一份 IMPORTED MNN/MNN_Express
│   │   │   ├── bertvits2/          # 来自旧 bertvits2-jni/cpp
│   │   │   │   └── CMakeLists.txt  # 子 CMake，编出 libbertvits2.so
│   │   │   └── moeavatar_llm_jni.cpp  # 本地 LLM JNI（关闭中，见 §3）
│   │   ├── jniLibs/arm64-v8a/      # 全工程唯一的 libMNN.so / libMNN_Express.so
│   │   ├── java/com/example/bertvits2/             # 旧 bertvits2-jni Kotlin
│   │   ├── java/com/example/bertvits2_infer_wrapper/  # 旧 wrapper Kotlin
│   │   ├── java/com/moeavatar/asr/AsrActivity.kt   # 新增
│   │   ├── java/com/moeavatar/live2d/Live2DActivity.kt  # 新增
│   │   └── ... (LauncherActivity 加了 Live2D / ASR 入口按钮)
│   └── build.gradle                # externalNativeBuild 指向 cpp/CMakeLists.txt
├── live2d/                         # Cubism SDK + JNI + Live2D 模型 assets (~23MB)
├── sherpa/                         # sherpa-mnn Kotlin + libsherpa-mnn-jni.so
├── cppjieba/ cpptokenizer/ openjtalk/ text-preprocess/   # 沿用原版
└── settings.gradle                 # rootProject.name = "MoeAvatarPro"
```

## 2. 明早第一件事 —— 编一次试试

```bash
cd /home/jhx/Projects/nlp/MNN/apps/Android/MoeAvatarPro
export ANDROID_HOME=/home/jhx/Android/Sdk
./gradlew :app:assembleDebug
```

预期可能踩坑：
1. **Live2D `Framework` 子项目编译失败** — 大概率是 SDKRoot/Framework 路径问题。如果报错，先看 `apps/Android/ChatWaifu_Mobile-main/Live2D/.cxx/` 里它当年是怎么编出来的，把缺的 include / 库补回来。
2. **sherpa kotlin 字段对不上** — `AsrActivity.kt` 里 `OnlineRecognizerConfig(...)` 用了具名参数，已对照 `sherpa/.../OnlineRecognizer.kt` 的 data class 签名校对过；但如果版本不一致，按 IDE 提示改顺序即可。
3. **`AsrModelConfig` 找不到 `getLmConfigFromDirectory`** — 该函数在 `AsrModelConfig.kt` 里有，已一起拷贝过来。
4. **R class 找不到 R.id.btn_live2d / R.id.btn_asr** — layout 已经写好，clean rebuild 应该就有。

## 3. 本地 LLM 当前为什么还跑不了

`app/src/main/jniLibs/arm64-v8a/libMNN.so` 是 bertvits2 当年用 NDK 28.2 编的，**没开 `MNN_BUILD_LLM=ON`**——里面没有 `MNN::Transformer::Llm::createLLM`。所以 `app/src/main/cpp/CMakeLists.txt` 里加了：

```cmake
option(MOE_ENABLE_LOCAL_LLM "Build the local LLM JNI bridge" OFF)
```

默认 OFF，APK 里就没有 `libmoeavatar_llm.so`，`LocalLlmBridge.nativeAvailable` 自动 fallback，UI 走 OpenAI 通道。

要打开本地 LLM：

```bash
# 1. 用 NDK 28.2 重新编一份 libMNN.so，参数对齐 bertvits2 + 加上 LLM
cd /home/jhx/Projects/nlp/MNN
mkdir -p apps/Android/MoeAvatarPro/.tmp_mnn_build && cd $_
ANDROID_NDK=/home/jhx/Android/Sdk/ndk/28.2.13676358 \
cmake ../../../../ \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DCMAKE_BUILD_TYPE=Release \
  -DANDROID_ABI="arm64-v8a" \
  -DANDROID_STL=c++_static \
  -DANDROID_NATIVE_API_LEVEL=android-28 \
  -DMNN_USE_LOGCAT=true \
  -DMNN_BUILD_FOR_ANDROID_COMMAND=true \
  -DMNN_LOW_MEMORY=true \
  -DMNN_CPU_WEIGHT_DEQUANT_GEMM=true \
  -DMNN_SUPPORT_TRANSFORMER_FUSE=true \
  -DMNN_ARM82=true \
  -DMNN_BUILD_LLM=ON \
  -DMNN_OPENCL=false \
  -DMNN_VULKAN=false \
  -DMNN_SEP_BUILD=ON
make -j$(nproc)
cp libMNN.so libMNN_Express.so ../app/src/main/jniLibs/arm64-v8a/

# 2. 打开 MOE_ENABLE_LOCAL_LLM
# 编辑 app/src/main/cpp/CMakeLists.txt，把 OFF 改 ON
```

然后 `assembleDebug` 重打包，本地 LLM 才会出现在 backend 列表里。

## 4. 模型放哪

放 `/sdcard/Download/MoeAvatar/` 下：

```
/sdcard/Download/MoeAvatar/
├── models/                         # 本地 LLM (扫一级子目录里的 config.json)
│   └── qwen35_08b_nekoneko-MNN/
│       ├── config.json
│       └── ...
└── asr/                            # ASR (扫一级子目录里的 config.json，由 AsrConfigManager 解析)
    └── sherpa-mnn-streaming-zipformer-bilingual-zh-en/
        ├── config.json
        ├── encoder-epoch-99-avg-1.int8.mnn
        ├── decoder-epoch-99-avg-1.int8.mnn
        ├── joiner-epoch-99-avg-1.int8.mnn
        └── tokens.txt
```

TTS 模型还是 bertvits2 那套，老地方没动。

Live2D 模型已经打到 `live2d/src/main/assets/Live2DModels/`，无需手动准备。默认加载 `Live2DModels/ATRI/ATRI.model3.json`，可以在 `Live2DActivity.EXTRA_MODEL_DIR` / `EXTRA_MODEL_JSON` 里通过 Intent 切角色。

## 5. 三个新入口怎么用

* **Live2D 角色** —— 主页 `Live2D 角色` 按钮 → 单 Activity，GLSurfaceView 渲染 ATRI，触屏拖拽。
  TTS lip sync 还没接上，Activity 里留了 `setMouthOpen(value: Float)` 钩子，下一步可以从 SpeechQueue 的 RMS 计算驱动。
* **语音输入 (ASR)** —— 主页 `语音输入 (ASR)` 按钮 → 填模型目录 → 点 `加载 ASR 模型` → 长按 `按住说话` → 松手停止 → `送到聊天` 把识别文本带回 LlmChatActivity 输入框。第一次会请求麦克风权限。
* **聊天** —— 行为跟原版一致，只是现在能从 ASR 那边接 Intent extras `asr_text` 自动回填。

## 6. 还没做 / 已知 TODO

- [ ] **真机验证**（最重要）。WSL 没 Android 设备，没法跑 logcat / GL 渲染。
- [ ] **本地 LLM**: 等决定是否花 30min 重编 libMNN.so（见 §3）。
- [ ] **TTS → Live2D lip sync**: 钩子已留，逻辑没接。需要在 SpeechQueue 播 PCM 时算 RMS，回调 `Live2DActivity.setMouthOpen`。
- [ ] **ASR + Chat 联动闭环**: 当前是 ASR 完一句 → 跳 Chat 输入框 → 用户手动点发送。理想的"全语音"是 ASR endpoint 触发后自动发 + LLM 回复进入 SpeechQueue 自动 TTS + Live2D 张嘴。
- [ ] Live2D 切换角色 UI（目前只支持 Intent extras 传入路径）。

## 7. 旧目录的备份

为了避免误删，原来的 `bertvits2-jni/` 和 `bertvits2-infer-wrapper/` 在主项目（`MoeAvatar/`）里被改名为 `.bertvits2-jni.merged_into_app/` / `.bertvits2-infer-wrapper.merged_into_app/`（隐藏目录）。如果合并阶段出问题：
```bash
cd /home/jhx/Projects/nlp/MNN/apps/Android/MoeAvatar
mv .bertvits2-jni.merged_into_app bertvits2-jni
mv .bertvits2-infer-wrapper.merged_into_app bertvits2-infer-wrapper
# 然后把 settings.gradle / app/build.gradle 改回去
```

## 8. 给我看的快速 sanity 清单

明天最先看这几个：

```bash
# 1. 主项目（MoeAvatar）TTS + 聊天能编 + 装 + 跑：
cd /home/jhx/Projects/nlp/MNN/apps/Android/MoeAvatar
./gradlew :app:assembleDebug

# 2. Pro 版能编（只编 app，不要 :live2d :sherpa 也能验证 Phase A 没退化）
cd ../MoeAvatarPro
./gradlew :app:assembleDebug

# 3. 全量 Pro：
./gradlew assembleDebug
```

Live2D / ASR 真机第一次跑大概率会报缺这缺那（GLES 版本、麦克权限、模型路径），到时候按 logcat 改即可。
