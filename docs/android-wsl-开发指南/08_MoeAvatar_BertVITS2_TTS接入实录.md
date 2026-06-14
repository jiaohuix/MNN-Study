# 08 MoeAvatar：把 Bert-VITS2-MNN TTS 接到一个新 App 里

> 读者画像：会 Python，刚在 07 文档里看完 MnnLlmChat 拆解，想做一个**自己的二次元 App**。
> 目标：复现把 **Bert-VITS2-MNN（端侧动漫角色 TTS）** 接进 `apps/Android/MoeAvatar/` 的整个过程，看清每个卡点和怎么绕过。

---

## 0. 这次干了什么

- 起了一个新工程 `apps/Android/MoeAvatar/`，定位是**比 MoeChat 更全的二次元 App**（计划 LLM 对话 + TTS + ASR + Live2D）
- 先做 **TTS-only 验证**：装 APK → 输入文字 → 听到甘雨说中文 ✅
- 验证下来 RTF ≈ 1.0（15 字 → 4 秒音频，合成耗时 ~4 秒）

> 为什么先 TTS：声音的"听感"最难提前预判，硬件兼容性、模型质量都要真机才能验证。LLM 已经在 MoeChat 跑过、Live2D 是纯渲染，所以风险最高的环节先打掉。

---

## 1. 整体架构（关键：6 个 module + 主仓 MNN 头）

```
MoeAvatar/                                ← 一个 Android 工程，6 个 library module + 1 个 app
├── settings.gradle                       ← include 7 个 module（app + 6 library）
├── app/                                  ← 应用 module，UI/业务在这里
│   └── src/main/java/com/moeavatar/
│       └── TtsActivity.kt                ← 整个 demo 唯一一个 Activity
│
├── bertvits2-jni/                        ← C++ 推理 + JNI 封装
│   ├── src/main/cpp/                     ← 6 个 .mnn 模型怎么 load + run
│   ├── src/main/jniLibs/arm64-v8a/       ← 预编译的 libMNN.so / libMNN_Express.so（关键！）
│   └── src/main/assets/                  ← 模型权重（zh 完整、jp/en/mix 是 LFS 指针，被移走了）
├── bertvits2-infer-wrapper/              ← Kotlin 高层封装：BertVITS2SimpleInferImpl
├── text-preprocess/                      ← 文本预处理（jieba 分词、拼音、tone sandhi、英文 cmudict、日文 openjtalk）
├── cppjieba/                             ← jieba 分词 JNI 包装
├── cpptokenizer/                         ← Bert tokenizer JNI（用预编译的 Rust 静态库 .a）
└── openjtalk/                            ← 日文 G2P（自带完整源码，2MB+ 的 sys.dic 字典）
│
└── third_party/                          ← 三个 header-only 第三方库（最大卡点）
    ├── MNN/include → 软链到主仓 include/
    ├── cppjieba/                         ← git clone yanyiwu/cppjieba
    └── tokenizers-cpp/                   ← git clone mlc-ai/tokenizers-cpp
```

调用链（点合成按钮发生了什么）：

```
[Kotlin] TtsActivity.synthOrStop()
   → BertVITS2SimpleInferImpl.infer(text, spkName)
       → text-preprocess 模块: 分词/G2P → phone+tone+language 三件套
           → cppjieba JNI / cpptokenizer JNI / openjtalk JNI
       → bertvits2-jni: BertVITS2JNI.startAudioInfer(...)
           → C++ 调 libMNN.so 跑 6 个 mnn 模型（enc/dec/flow/dp/sdp/emb）
       → 返回 FloatArray（PCM 浮点）
   → AudioTrack.write(...)（PCM_FLOAT、44.1kHz、单声道、MODE_STATIC）
```

---

## 2. 起步：怎么拿到这 6 个 module 的源码

> ⚠️ 这 6 个 module **不是**从 `apps/Android/ChatWaifu_Mobile-main/` 来的（那个仓里没有 cpp 部分），是**另一个独立的 Bert-VITS2-MNN 工程**。在动手前，要确认你手边的 `MoeAvatar/` 已经有这 6 个 module 的代码（kt + cpp + CMakeLists）。

如果你是从零开始而不是接手现有目录：从 [Voine/Bert-VITS2-MNN](https://github.com/Voine/Bert-VITS2-MNN) 之类的 Bert-VITS2 端侧推理工程里把这 6 个 module 拷过来。

---

## 3. 编译卡点全记录（按踩坑顺序）

### 卡点 ①：openjtalk/build.gradle 用了 version catalog 写法

```
build file 'openjtalk/build.gradle': 2: only alias(libs.plugins.someAlias) plugin identifiers
where `libs` is a valid version catalog
@ line 2, column 5.
    alias(libs.plugins.android.library)
```

原因：原 module 来自一个用了 `gradle/libs.versions.toml` 的工程，引用 `libs.plugins.xxx`。MoeAvatar 没建这个 toml。

**修法**：把 6 个 module 的 `build.gradle` 顶部统一成传统写法：

```gradle
plugins {
    id 'com.android.library'
    id 'org.jetbrains.kotlin.android'
}
apply from: '../app_version.gradle'
```

dependencies 区也要去掉 `libs.androidx.xxx`，换回 `'androidx.core:core-ktx:1.13.1'` 这种字符串坐标。

> Python 类比：version catalog 像 `requirements.in` + 版本约束文件，传统写法像直接在每个 `setup.py` 里写死版本号。两个混用就报错。

---

### 卡点 ②：第三方头文件目录是空壳

`bertvits2-jni / cppjieba / cpptokenizer` 三个模块的 `CMakeLists.txt` 都引用 `third_party/<lib>/include`，但 `third_party/` 三个子目录都是空的：

```
fatal error: 'MNN/expr/Module.hpp' file not found
```

**修法**：

| 库 | 怎么补 |
|---|---|
| `third_party/MNN/include` | 软链到主仓 `include/`（MNN 头跟当前主仓版本绑定） |
| `third_party/cppjieba/` | `git clone --depth 1 https://github.com/yanyiwu/cppjieba.git` |
| `third_party/tokenizers-cpp/` | `git clone --depth 1 https://github.com/mlc-ai/tokenizers-cpp.git` |

**软链命令**（注意 `..` 层数，从 `MoeAvatar/third_party/MNN/include` 到主仓 `MNN/include` 是 5 层）：

```bash
cd apps/Android/MoeAvatar/third_party/MNN
ln -sfn ../../../../../include include
ls include/MNN/expr/Module.hpp        # 应当能 ls 到，否则层数错了
```

> ⚠️ 关于 cppjieba 的 limonp 子模块：**新版本 cppjieba 已经把 limonp 内联进 `include/cppjieba/`** 了，不再需要 `deps/limonp/include`。CMakeLists 里写的 `include_directories(${CPPJIEBA_ROOT}/deps/limonp/include)` 即使指向不存在的目录，cmake 也不会报错（只是相当于 `-I` 一个空路径）。如果你拉到的是老版本 cppjieba（带子模块），就 `git submodule update --init --recursive`，或者单独 clone limonp 进去。

---

### 卡点 ③：assets 里的 .mnn 模型是 git-lfs 指针文件

启动 App 后，`BertVITS2SimpleInferImpl.init()` 会做两件事：
1. 把 `assets/bert/` 和 `assets/bv2_model/` 整个目录拷到 `filesDir`
2. **walk 整个 `bv2_model/`，对每个 `config.json` 都用 gson 解析**

如果某个 `bv2_model/<lang>/config.json` 是 LFS 指针（128 字节，内容是 `version https://git-lfs.github.com/spec/v1\noid sha256:...`），gson 解析直接抛 `JsonSyntaxException`，整个 init 挂掉。

**怎么发现一个文件是 LFS 指针**：

```bash
for f in bertvits2-jni/src/main/assets/bv2_model/*/config.json; do
  size=$(stat -c %s "$f")
  head -c 50 "$f" | grep -q "git-lfs" && echo "LFS  ($size): $f" || echo "REAL ($size): $f"
done
```

LFS 指针文件特征：**100~200 字节**、开头是 `version https://git-lfs.github.com/...`。

**修法**（先跑通中文再说）：把不完整的 jp/en/mix 三个目录移走，避免 init 路径走到那里：

```bash
mkdir -p .lfs_pointers/bv2_model .lfs_pointers/bert
mv bertvits2-jni/src/main/assets/bv2_model/jp .lfs_pointers/bv2_model/
mv bertvits2-jni/src/main/assets/bv2_model/en .lfs_pointers/bv2_model/
mv bertvits2-jni/src/main/assets/bv2_model/mix .lfs_pointers/bv2_model/
mv bertvits2-jni/src/main/assets/bert/jp .lfs_pointers/bert/
mv bertvits2-jni/src/main/assets/bert/en .lfs_pointers/bert/
```

之后 `assets/bv2_model/` 只剩 `zh/`，App 起来就只能选中文音色（陈/珐露珊/甘雨）。

---

### 卡点 ④：`app/cpp/` 的 LLM 部分会牵连构建

最初 `MoeAvatar/app/src/main/cpp/` 里就放了个 LLM JNI（拷 MoeChat 来的），引用 `<llm/llm.hpp>` 和 `libMNN.so`。但当时主仓的 LLM 静态库 / libMNN_Transformers 不一定都编了，会报链接失败。

**先 TTS-only 的策略**：把 `app/cpp/` 整个删掉，`app/build.gradle` 里的 `externalNativeBuild { cmake { ... } }` 块删掉（顶层和 `defaultConfig` 里都有），LlmBridge.kt / MainActivity.kt 等也一并删，把 LAUNCHER 切到 TtsActivity。

```bash
rm -rf app/src/main/cpp
rm app/src/main/java/com/moeavatar/{MainActivity.kt,LlmBridge.kt,ChatMessage.kt,MessageAdapter.kt}
rm app/src/main/res/layout/{activity_main.xml,item_message_user.xml,item_message_assistant.xml}
```

> `libMNN.so` / `libMNN_Express.so` 由 `bertvits2-jni/src/main/jniLibs/arm64-v8a/` 自带，APK 打包时会被合进去，不依赖主仓重新编。

---

## 4. 完整复现命令（下次照抄）

假设你已经拿到 `apps/Android/MoeAvatar/` 这个目录骨架（6 个 module + app）：

```bash
cd /home/<you>/Projects/nlp/MNN/apps/Android/MoeAvatar

# === 1. settings.gradle 把 6 个 module include 进来 ===
cat > settings.gradle << 'EOF'
pluginManagement {
    repositories {
        maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }
        maven { url 'https://maven.aliyun.com/repository/public' }
        maven { url 'https://maven.aliyun.com/repository/google' }
        mavenCentral()
        google()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url 'https://maven.aliyun.com/repository/public' }
        maven { url 'https://maven.aliyun.com/repository/google' }
        mavenCentral()
        google()
    }
}

rootProject.name = "MoeAvatar"
include ':app'
include ':cppjieba'
include ':cpptokenizer'
include ':openjtalk'
include ':text-preprocess'
include ':bertvits2-jni'
include ':bertvits2-infer-wrapper'
EOF

# === 2. app/build.gradle 加 wrapper 依赖 ===
# 在 dependencies { ... } 里加一行：implementation project(':bertvits2-infer-wrapper')

# === 3. 补 third_party ===
cd third_party
ln -sfn ../../../../../include MNN/include
git clone --depth 1 https://github.com/yanyiwu/cppjieba.git
git clone --depth 1 https://github.com/mlc-ai/tokenizers-cpp.git
ls cppjieba/include/cppjieba/Jieba.hpp tokenizers-cpp/include/tokenizers_cpp.h
cd ..

# === 4. 把 LFS 指针的不完整模型移走（只留 zh）===
mkdir -p .lfs_pointers/bv2_model .lfs_pointers/bert
mv bertvits2-jni/src/main/assets/bv2_model/{jp,en,mix} .lfs_pointers/bv2_model/ 2>/dev/null
mv bertvits2-jni/src/main/assets/bert/{jp,en} .lfs_pointers/bert/ 2>/dev/null

# === 5. 把 openjtalk/build.gradle 的 alias(libs.xxx) 改成传统写法 ===
# （手改，参考 cppjieba/build.gradle）

# === 6. 编 ===
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_NDK=$ANDROID_HOME/ndk/26.1.10909125
./gradlew :app:assembleDebug --no-daemon
# 产物：app/build/outputs/apk/debug/app-debug.apk（约 167MB）
```

---

## 5. 怎么换音色 / 加新角色

**音色（speaker）** 在 `bertvits2-jni/src/main/assets/bv2_model/<lang>/config.json` 里：

```json
{
    "data": {
        "sampling_rate": 44100,
        "spk2id": {
            "陈_ZH": 0,
            "珐露珊_ZH": 1,
            "甘雨_ZH": 2
        }
    },
    "version": "2.3"
}
```

`spk2id` 的 key 是显示名，**后缀必须是 `_ZH` / `_EN` / `_JP` / `_MIX`**（`BertVITS2SimpleInferImpl.getLanguageTypeFromSpkName()` 靠后缀分流到对应预处理流水线）。

### 5.1 加一个**同语言**的新角色

举例：在 zh 那套 6 个 mnn 模型里训了一个新角色"千织"，speaker id = 3：

```json
{
    "data": {
        "sampling_rate": 44100,
        "spk2id": {
            "陈_ZH": 0, "珐露珊_ZH": 1, "甘雨_ZH": 2, "千织_ZH": 3
        }
    },
    "version": "2.3"
}
```

不需要换 mnn 文件，重编 APK 即可——TtsActivity 的 spinner 会自动多一项。

### 5.2 换一套**不同的训练模型**（不同的训练版本）

替换 `bv2_model/zh/` 下的 6 个 `.mnn` 文件（`*_enc.mnn`、`*_dec.mnn`、`*_flow.mnn`、`*_dp.mnn`、`*_sdp.mnn`、`*_emb.mnn`），同时换 `config.json`。文件名前缀（如 `bert_vits23_genshin_arknights_*`）只要 6 个文件保持一致就行——`BertVITS2SimpleInferImpl.setInternalModelPath()` 用 `endsWith("_enc.mnn")` 等后缀去匹配。

### 5.3 加一个**新语言**

需要：
- `bv2_model/<lang>/` 下的 6 个 mnn + config.json
- `bert/<lang>/` 下对应的 bert 模型（zh: chinese-roberta + tokenizer.json；jp: deberta + vocab.txt；en: deberta + spm.model）
- `text-preprocess/src/main/assets/preprocess/<lang>/` 对应词典（en 的 cmudict.rep 等）

如果只是加一种已经有预处理代码的语言（jp/en/mix），把对应模型放回 assets 即可。如果是全新语言，要在 `text-preprocess/` 加新的 `XXBV2Impl.kt` 并在 `BertVITS2PreprocessFactoryImpl` 的 when 里多一个 case，工作量大。

---

## 6. RTF 与性能观察

测试机：（待补充具体型号）  
测试条件：甘雨_ZH，length_scale=1.0，文本"你好，我是甘雨，很高兴见到你～"（15 字）

| 指标 | 数值 |
|---|---|
| 输出 PCM | 172032 samples @ 44100 Hz ≈ 3.9 秒 |
| 合成耗时 | 3842 ms（首次）/ 4299 ms（第二次） |
| RTF | ≈ 0.98 / 1.10 |

**结论**：RTF 接近 1.0，整段合完再播没问题，但要做"边出边播"得**分句合成**（按标点切，每句单独跑一次 infer 再 enqueue 到 AudioTrack 的 stream 模式）。这是后面接 LLM 时要解决的事。

调速度：`infer.setAudioLengthScale(scale)`，scale ∈ [0.5, 2.0]，1.0 默认；TtsActivity 里有个 SeekBar 接到这个参数。

---

## 7. 下一步路线

1. ~~验证 TTS 能跑~~ ✅（这一步）
2. 接 LLM 流式回复：把 MoeChat 的 LlmBridge / MainActivity 弄回来，加一个"LLM 出一句话 → 喂给 TTS → 播放"的桥；关键在**分句**避免 RTF=1 的延迟堆积
3. 接 ASR：单独开发一个 ASR 小 APK 打包权重做一次性测试，验证完合并进 MoeAvatar
4. ATRI Live2D 渲染 + 嘴型 sync
5. OpenAI 兼容 API 接入

---

## 8. 用到的关键命令速查

```bash
# 看哪个文件是 LFS 指针
find <dir> -type f | while read f; do
  head -c 50 "$f" | grep -q "git-lfs" && echo "LFS: $f"
done

# 看 APK 里都打包了什么 .so
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep "\.so"

# 抓 TTS 合成日志
adb logcat -s TtsActivity BertVITS2SimpleInferImpl BertVITS2FullInferImpl BertVITS2PreprocessImplFactory copyAssets2Local

# 拷 APK 到 Windows
cp app/build/outputs/apk/debug/app-debug.apk /mnt/c/Users/<you>/Downloads/MoeAvatar-debug.apk
```
