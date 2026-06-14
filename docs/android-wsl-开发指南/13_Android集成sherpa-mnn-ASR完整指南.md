# 13 Android 集成 sherpa-mnn ASR 完整指南（可复用模块）

> 复盘自 MnnAsrTest 项目（`apps/Android/MnnAsrTest/`）。从 0 到能跑通流式 ASR + 实时 partial text + 性能指标显示，**全程踩了 6 个坑**。本篇把这些坑组织成"按照这份清单复制就能跑"的可复用模板。
>
> 想直接抄答案：跳到 [§3 一键集成清单](#3-一键集成清单)。
>
> 想知道为什么这么写：每条都有 [§4 坑案速查表](#4-坑案速查表) 对应。

---

## 0. TL;DR

| 关键决策 | 一句话原因 |
|----------|-----------|
| 用 CMake shim + `target_link_libraries` 拉链 | sherpa 的 `.so` `DT_NEEDED` 缺 `libMNN_Express.so`，Android 7+ namespace 隔离让 `loadLibrary` 不互通；shim 让 NEEDED 链一次拉齐 |
| `-DANDROID_STL=c++_shared` | sherpa 用了 `<regex>` 但二进制没 NEEDED `libc++_shared.so`，Gradle 这一行让 NDK 把它打进 APK 并 NEEDED |
| 模型放 `/data/local/tmp/` | Android 11+ scoped storage 让 App 读不到别 App 的 `/sdcard/Download/`，sherpa 静默失败拿到 `impl_=nullptr`，下一步 `createStream` SIGSEGV |
| Kotlin 端构造时校验 ptr | sherpa `newFromFile` 失败返回 0，外壳对象照样存活——必须在 init 里检查 |
| `tv_text` 用 `wrap_content + minHeight` | 用 `0dp + 上下约束夹` 在某些机型上塌成 0 高度，文字写进去看不见 |
| 显式 `textColor="#FF000000"` | 默认主题字色跟背景对比度可能不够，深色模式 / 浅色背景会吃掉文字 |
| Endpoint 后先固定文本再 reset | sherpa `reset(stream)` 会让下一次 `getResult` 返回空字符串，UI 会被覆盖 |
| 录音线程自己跑完 tail flush | 别在 `stopRecording()` 里 `thread.join()` 阻塞 UI；让 thread 自己跑 tail + UI 更新再退出 |

---

## 1. 模块依赖关系全景

```
你的 App Activity (Kotlin)
      │
      ▼
com.k2fsa.sherpa.mnn.OnlineRecognizer / OnlineStream  (Kotlin 包装)
      │  System.loadLibrary("MNN") / ("MNN_Express") / ("<your>_shim") / ("sherpa-mnn-jni")
      ▼
libsherpa-mnn-jni.so  (预编译 .so，DT_NEEDED 列表不全)
      │  依赖 ↓
libMNN.so + libMNN_Express.so + libc++_shared.so   (其中 Express 和 c++_shared 必须靠 shim 拖进来)
```

**核心逻辑**：sherpa 那个 .so 的 DT_NEEDED 不全，但 Android 7+ linker 严格按 NEEDED 链解析符号。我们写一个空壳 shim `.so`，通过 `target_link_libraries` 把缺的 .so 都 NEEDED 上，dlopen shim 时 linker 会沿链一次性把所有依赖拉进同一个 namespace。

---

## 2. 项目目录结构（参考 MnnAsrTest）

```
your-app/
├── settings.gradle              # rootProject 聚合
├── build.gradle                 # 根 gradle
├── gradle.properties            # Android Studio Iguana+ 兼容字段
└── app/
    ├── build.gradle             # ★ externalNativeBuild + ANDROID_STL=c++_shared
    └── src/main/
        ├── AndroidManifest.xml  # ★ extractNativeLibs="true"
        ├── cpp/
        │   ├── CMakeLists.txt   # ★ 把 3 个 .so 用 IMPORTED+target_link_libraries 串起来
        │   └── <project>_shim.cpp  # ★ 构造函数兜底 dlopen libc++_shared
        ├── jniLibs/arm64-v8a/   # ★ 4 个预编译 .so
        │   ├── libMNN.so
        │   ├── libMNN_Express.so
        │   ├── libsherpa-mnn-jni.so
        │   └── (libc++_shared.so 由 NDK 自动打入,不要手动放)
        ├── java/com/k2fsa/sherpa/mnn/
        │   ├── OnlineRecognizer.kt   # ★ 4 步 loadLibrary + ptr=0 校验
        │   ├── OnlineStream.kt
        │   └── FeatureConfig.kt
        ├── java/<your-package>/
        │   └── XxxActivity.kt        # 调用方
        └── res/layout/activity_xxx.xml  # ★ 显式 textColor + wrap_content/minHeight
```

打 ★ 的 8 个文件是必须项,缺一个就坑。

---

## 3. 一键集成清单

> 假设你新建了一个空 Android 项目,按下面步骤照抄。**8 步全部完成才能跑通**,跳过任何一步都会出现下面 §4 列的某种坑。

### 步骤 1: `app/build.gradle` 启用 CMake + STL

在 `android { defaultConfig { ... } }` 里加:

```groovy
defaultConfig {
    ndk { abiFilters "arm64-v8a" }
    externalNativeBuild {
        cmake {
            cppFlags "-std=c++17"
            arguments "-DANDROID_STL=c++_shared"   // ★ 必须,否则 std::regex 符号缺失
        }
    }
}

externalNativeBuild {
    cmake {
        path file('src/main/cpp/CMakeLists.txt')
        version '3.22.1'
    }
}
```

### 步骤 2: `app/src/main/AndroidManifest.xml`

```xml
<application
    ...
    android:extractNativeLibs="true">   <!-- ★ 强制把 .so 解压到 /data/app/.../lib/ -->
```

### 步骤 3: 把 4 个预编译 .so 放到 `app/src/main/jniLibs/arm64-v8a/`

```
libMNN.so
libMNN_Express.so
libsherpa-mnn-jni.so
```

> **不要**手动放 `libc++_shared.so`,`-DANDROID_STL=c++_shared` 会自动注入,手动放容易和 NDK 内部那份冲突。
>
> **不要**用 patchelf / LIEF 修改这 3 个 .so 的 ELF——patchelf 0.13 在 ARM64 上会写坏 GNU_HASH,linker 直接拒载。

### 步骤 4: `app/src/main/cpp/CMakeLists.txt`

```cmake
cmake_minimum_required(VERSION 3.22.1)
project("yourasr_native")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(JNILIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

# 三个预编译 .so 都声明为 IMPORTED,让 shim 全部 link 上:
# 这样 yourasr_shim.so 的 DT_NEEDED 里就会有 libMNN_Express.so / libMNN.so / libsherpa-mnn-jni.so,
# Android linker 在 dlopen("yourasr_shim") 时会沿 NEEDED 链把它们全部拉进主命名空间。
add_library(mnn         SHARED IMPORTED)
add_library(mnn_express SHARED IMPORTED)
add_library(sherpa_mnn  SHARED IMPORTED)
set_target_properties(mnn         PROPERTIES IMPORTED_LOCATION "${JNILIBS_DIR}/libMNN.so")
set_target_properties(mnn_express PROPERTIES IMPORTED_LOCATION "${JNILIBS_DIR}/libMNN_Express.so")
set_target_properties(sherpa_mnn  PROPERTIES IMPORTED_LOCATION "${JNILIBS_DIR}/libsherpa-mnn-jni.so")

add_library(yourasr_shim SHARED yourasr_shim.cpp)
target_link_libraries(yourasr_shim log mnn mnn_express sherpa_mnn)
```

### 步骤 5: `app/src/main/cpp/yourasr_shim.cpp`

```cpp
#include <dlfcn.h>
#include <android/log.h>
#include <string>

// 兜底:即便 NEEDED 已经把 libc++_shared 拉进来了,这里再 RTLD_GLOBAL 一遍保险,
// 同时打条日志方便确认 shim 真被装载。
__attribute__((constructor))
static void preload_cxx_shared() {
    void* h = dlopen("libc++_shared.so", RTLD_NOW | RTLD_GLOBAL);
    __android_log_print(h ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
        "AsrShim", h ? "dlopen libc++_shared.so RTLD_GLOBAL ok" : "dlopen libc++_shared.so failed: %s",
        h ? "" : dlerror());
}

extern "C" int yourasr_shim_keep_alive() {
    std::string s = "yourasr"; return static_cast<int>(s.size());
}
```

### 步骤 6: Kotlin 端 `OnlineRecognizer.kt` / `OnlineStream.kt`

完整内容直接从 MnnAsrTest 抄: `apps/Android/MnnAsrTest/app/src/main/java/com/k2fsa/sherpa/mnn/`

**两个关键点必须保留**:

```kotlin
// 1) companion init 里按依赖顺序 4 步加载
companion object {
    init {
        System.loadLibrary("MNN")
        System.loadLibrary("MNN_Express")
        System.loadLibrary("yourasr_shim")    // ★ NEEDED 链拉齐 sherpa
        System.loadLibrary("sherpa-mnn-jni")
    }
}

// 2) 构造时校验 ptr,防 newFromFile 静默返回 0
private var ptr: Long = newFromFile(config).also {
    if (it == 0L) error("OnlineRecognizer.newFromFile returned 0 — 检查模型路径/文件名/读权限")
}
```

### 步骤 7: 模型存放位置——必须用 App 可读路径

**测试期**: 推到 `/data/local/tmp/asr/`,所有 App 都能读:

```bash
adb shell "mkdir -p /data/local/tmp/asr && cp -r /sdcard/Download/MoeAvatar/asr/<模型目录> /data/local/tmp/asr/ && chmod -R 755 /data/local/tmp/asr"
```

**生产期**: 改成 App 私有目录(`context.filesDir`),首次启动从 assets 或下载源拷贝过去。**不要**用 `/sdcard/Download` 跨 App 共享(scoped storage 会拦)。

### 步骤 8: Activity 调用 + UI 必须遵守

完整模板见 `apps/Android/MnnAsrTest/app/src/main/java/com/mnn/asrtest/AsrTestActivity.kt`。

**3 个 UI 雷区**:

```xml
<!-- 1) tv_text 必须 wrap_content+minHeight,不要用 0dp 上下约束夹 -->
<TextView
    android:layout_height="wrap_content"
    android:minHeight="180dp"
    android:textColor="#FF000000"           <!-- 2) 显式黑字,别靠主题 -->
    android:textSize="32sp"
    android:textStyle="bold"
    .../>
```

```kotlin
// 3) Endpoint 处理必须先固定再 reset,否则 UI 会被空字符串覆盖
if (isEp) {
    if (text.isNotEmpty()) {
        emittedSegments += text
        runOnUiThread { tvText.text = emittedSegments.joinToString("") }
    }
    rec.reset(stream)
    lastEmitted = ""
}
```

```kotlin
// 4) stopRecording 不要 join thread,让 thread 自己跑完 tail flush + UI 更新
private fun stopRecording() {
    if (!recording.getAndSet(false)) return
    runCatching { audioRecord?.stop(); audioRecord?.release() }
    audioRecord = null
    btnMic.text = "按住说话"
    // 注意:不要 recordThread?.join() 在主线程,会卡 UI
}
```

---

## 4. 坑案速查表

> 按 `症状 → 根因 → 修法` 组织,出问题查这张表。

### 4.1 `UnsatisfiedLinkError: cannot locate symbol "_ZN3MNN7Express6Module4loadE..."`

| 项 | 内容 |
|----|------|
| 发生时机 | 第一次 `System.loadLibrary("sherpa-mnn-jni")` 或 OnlineRecognizer 构造 |
| 根因 | `libsherpa-mnn-jni.so` DT_NEEDED 只列了 `libMNN.so`,但代码用了 `MNN::Express::Module::load`(符号在 `libMNN_Express.so`)。Android 7+ 是 RTLD_LOCAL + namespace,单独 loadLibrary("MNN_Express") 不让 sherpa 看到符号 |
| 修法 | CMake shim + `target_link_libraries` (步骤 4) |
| 详细 | [11_sherpa-mnn-jni加载坑案.md](11_sherpa-mnn-jni加载坑案.md) |

### 4.2 `UnsatisfiedLinkError: cannot locate symbol "_ZNSt6__ndk111regex_errorD1Ev"`

| 项 | 内容 |
|----|------|
| 根因 | sherpa 用了 `<regex>`,需要 `libc++_shared.so`,但 sherpa 二进制没 NEEDED 它 |
| 修法 | `app/build.gradle` 加 `arguments "-DANDROID_STL=c++_shared"` (步骤 1)。NDK 会自动把 libc++_shared 打进 APK 并被 shim NEEDED |

### 4.3 `dlopen failed: empty/missing DT_HASH/DT_GNU_HASH`

| 项 | 内容 |
|----|------|
| 根因 | 你用 `patchelf --add-needed` 改过 sherpa 那个 .so,patchelf 0.13 在 ARM64 上会写坏 `GNU_HASH` |
| 修法 | 把 .so 还原成原版(从仓库或备份里重新拷),改用 CMake shim 方案。**永远别 patchelf sherpa 的 .so** |

### 4.4 加载阶段没崩,按麦克按钮 SIGSEGV(`createStream+136`,fault addr 0x0)

| 项 | 内容 |
|----|------|
| 根因 | sherpa `OnlineRecognizerImpl::Create` 失败返回 nullptr,但**外壳对象指针非 0**,Kotlin 看到 ptr 不等于 0 以为成功;调 createStream 时 deref 内部 `impl_` 空指针 |
| 触发条件 | 模型文件 fopen 失败(权限/路径错),或 modelType 不匹配,或 tokens.txt 读不到 |
| 排查 | `adb shell run-as <你的包名> ls /sdcard/Download/.../`——看 App 自己能不能列出来 |
| 修法 | 模型放 `/data/local/tmp/asr/`(步骤 7);Kotlin 构造时 `if (ptr == 0L) error(...)` 显式抛错(步骤 6) |

### 4.5 模型加载成功,识别也跑通(logcat 有结果),但屏幕 TextView 不显示

| 项 | 内容 |
|----|------|
| 根因 1 | `tv_text` 用 `layout_height="0dp"` + 上下约束夹,某些机型(MIUI 测过)会塌成 0 高度 |
| 根因 2 | `textColor` 没显式指定,跟背景对比度太低(白底白字 / 灰底浅灰字) |
| 修法 | `wrap_content` + `minHeight="180dp"` + `textColor="#FF000000"`;布局外面套 ScrollView 防被键盘挤(步骤 8) |
| 验证 | 同时弹 Toast 浮层 ——Toast 一定不会被布局问题影响,出来了证明数据流转 OK,只是 TextView 视觉问题 |

### 4.6 partial text 实时跳出来了,但 endpoint 后变空

| 项 | 内容 |
|----|------|
| 根因 | sherpa `reset(stream)` 调用后,下一帧 `getResult` 返回空串,UI 被空字符串覆盖 |
| 修法 | reset 之前先把当前 text 拼到 `emittedSegments`,UI 只显示拼接后的全文(步骤 8) |

### 4.7 PowerShell 里 `adb logcat` 中文显示乱码 `浠婂ぉ澶╂皵`

| 项 | 内容 |
|----|------|
| 根因 | logcat 是 UTF-8,PowerShell 默认 GBK 解读 |
| 修法 | 跑一次 `chcp 65001` 切到 UTF-8;或在 WSL 里 `adb logcat` |
| 注意 | 这只是终端显示问题,**手机 App 上的 TextView 显示是正常中文**——别因为 logcat 乱码就以为 App 有 bug |

### 4.8 `adb install` 后改动没生效

| 项 | 内容 |
|----|------|
| 根因 | 增量安装在某些机型上有缓存,新 APK 没真正生效 |
| 修法 | `adb uninstall <package>` 后再 `adb install`;或装前比对 md5: `md5sum app-debug.apk` vs `adb shell md5sum $(adb shell pm path <package>)` |

---

## 5. 常用 ASR 性能指标

`AsrTestActivity` 实测打的指标(可直接抄):

```
音频 4.12s  解码 877ms  RTF 0.213
首字延时 1068ms  录音启动 133ms  decode 次数 14
```

| 指标 | 计算 | 意义 |
|------|------|------|
| **音频时长** | `totalSamples / sampleRate` | 用户实际说话时间 |
| **解码总耗时** | 累加每次 `decode()` 的纳秒 | 模型纯计算耗时 |
| **RTF** (Real Time Factor) | `decodeMs / 1000 / audioSec` | < 1 才能跟上实时,越低越好。zipformer-int8 在普通 ARM 上典型 0.1~0.3 |
| **首字延时** | 第一帧 PCM 入帐 → 第一次 `getResult().text` 非空 | 体感"反应快不快"的核心指标。期望 < 1.5s |
| **录音启动延时** | 用户按下 → AudioRecord 第一帧 | Android + 麦克风冷启动,典型 100~300ms |
| **decode 次数** | sherpa 内部 `isReady` 触发的 decode 调用数 | 辅助看负载,跟音频时长成正比 |

---

## 6. 验证 checklist

按顺序跑,任何一步通过不了就停下来查对应的坑:

```bash
# 1. .so 都打进 APK 了吗?
unzip -l app-debug.apk | grep "\.so$"
# 期望:libMNN.so / libMNN_Express.so / libsherpa-mnn-jni.so / libc++_shared.so / libyourasr_shim.so

# 2. shim 的 NEEDED 链对吗?
unzip -p app-debug.apk lib/arm64-v8a/libyourasr_shim.so > /tmp/shim.so
readelf -d /tmp/shim.so | grep NEEDED
# 期望:NEEDED libMNN.so / libMNN_Express.so / libsherpa-mnn-jni.so / libc++_shared.so

# 3. sherpa .so GNU_HASH 完好(没被 patchelf 改坏)?
unzip -p app-debug.apk lib/arm64-v8a/libsherpa-mnn-jni.so > /tmp/sh.so
readelf -S /tmp/sh.so | grep -i hash
# 期望:.gnu.hash 和 .hash 都有

# 4. App 能读到模型吗?
adb shell run-as <package> ls /data/local/tmp/asr/<模型目录>/
# 期望:能列出 encoder/decoder/joiner/tokens

# 5. App 启动时 shim 加载成功?
adb logcat -d -s AsrShim:V | head -2
# 期望:I AsrShim: dlopen libc++_shared.so RTLD_GLOBAL ok

# 6. 跑一次完整识别,看 metrics
adb logcat -d -s AsrTest:V | grep "ASR done" -A 5
# 期望:audio=X.XXs / RTF=0.XXX / firstTokenDelay=XXXms / text=...
```

---

## 7. 快速复用到新项目

把这 6 个文件从 MnnAsrTest 复制过去,改一下包名/shim 名即可:

```
app/build.gradle                          → 改 namespace, applicationId
app/src/main/AndroidManifest.xml          → 改 activity 全限定名
app/src/main/cpp/CMakeLists.txt           → 改 project() 和 add_library() 名字
app/src/main/cpp/<新名>_shim.cpp           → 改函数名后缀
app/src/main/jniLibs/arm64-v8a/*.so       → 直接拷
app/src/main/java/com/k2fsa/sherpa/mnn/*  → 整目录拷,companion init 里改 shim 名
```

**Kotlin Activity 完全可以照抄 `AsrTestActivity`**,只换 `R.layout.xxx` 和 `R.id.xxx`。

---

## 8. 给 AI 的快速复用提示词

> 用户要在新 Android 项目里集成 sherpa-mnn ASR。直接读 [docs/android-wsl-开发指南/13_Android集成sherpa-mnn-ASR完整指南.md](13_Android集成sherpa-mnn-ASR完整指南.md) 的"§3 一键集成清单",按 8 步走完。
>
> **关键纪律**:
> 1. 不要 patchelf 改 sherpa .so 的 ELF——会写坏 GNU_HASH
> 2. 不要把模型留在 `/sdcard/Download` 跨 App 共享——scoped storage 会拦,sherpa 静默失败
> 3. 不要用 `layout_height="0dp"` + 上下约束夹给 tv_text——某些机型会塌成 0 高度
> 4. 不要在 `stopRecording` 里 join 录音线程——会卡 UI
> 5. 不要相信 `newFromFile` 的返回值就是成功标志——sherpa 失败时返回 0,Kotlin 必须显式校验
>
> 出现 `cannot locate symbol "_ZN3MNN7Express..."` → 看 §4.1
> 出现 `createStream+136 SIGSEGV fault addr 0x0` → 看 §4.4
> 文字识别成功但屏幕看不到 → 看 §4.5
