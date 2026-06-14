# 12 MnnAsrTest 本轮改动与 mic 闪退待查

> 上下文：到上一篇 [11_sherpa-mnn-jni加载坑案.md](11_sherpa-mnn-jni加载坑案.md) 为止，已经把"模型 dlopen 失败"的根因定位到 `libsherpa-mnn-jni.so` 缺 `libMNN_Express.so` 的 NEEDED。
>
> 本篇专门记录**最新这一次改动到底动了哪 4 个文件**——区别于之前几轮 `dlopen RTLD_GLOBAL` / `patchelf --add-needed` 的失败尝试。
> 改完后："加载 ASR" 按钮可以正常返回 `ASR 就绪`，但**按住"按住说话"会闪退**。这一篇也把闪退的待查方向列出来。

---

## 1. 这一轮到底改了什么（4 处）

### 1.1 `app/src/main/cpp/CMakeLists.txt`：从 dlopen 方案切到 target_link_libraries

**之前的写法**（依赖 `__attribute__((constructor))` 里 `dlopen("libMNN_Express.so", RTLD_GLOBAL)`）：能跑但脆弱。

**这一轮**：把 3 个预编译 .so 全部声明为 `IMPORTED`，让 `asrtest_shim` 通过 `target_link_libraries` 直接 link 它们：

```cmake
add_library(mnn         SHARED IMPORTED)
add_library(mnn_express SHARED IMPORTED)
add_library(sherpa_mnn  SHARED IMPORTED)
set_target_properties(mnn         PROPERTIES IMPORTED_LOCATION "${JNILIBS_DIR}/libMNN.so")
set_target_properties(mnn_express PROPERTIES IMPORTED_LOCATION "${JNILIBS_DIR}/libMNN_Express.so")
set_target_properties(sherpa_mnn  PROPERTIES IMPORTED_LOCATION "${JNILIBS_DIR}/libsherpa-mnn-jni.so")

add_library(asrtest_shim SHARED asrtest_shim.cpp)
target_link_libraries(asrtest_shim log mnn mnn_express sherpa_mnn)
```

效果：`libasrtest_shim.so` 的 `DT_NEEDED` 里同时出现 `libMNN.so` / `libMNN_Express.so` / `libsherpa-mnn-jni.so` / `libc++_shared.so`，linker 在 dlopen `asrtest_shim` 时沿 NEEDED 链一次性加载，符号在主命名空间里全部可解析。

### 1.2 `OnlineRecognizer.kt` / `OnlineStream.kt`：companion init 显式 4 步加载

```kotlin
companion object {
    init {
        System.loadLibrary("MNN")
        System.loadLibrary("MNN_Express")
        System.loadLibrary("asrtest_shim")    // ★ NEEDED 链把 sherpa 一并拖进来
        System.loadLibrary("sherpa-mnn-jni")
    }
}
```

两个类都改成同一份顺序，避免哪边先被 class-init 都能稳。

### 1.3 `asrtest_shim.cpp`：保留 `libc++_shared` 兜底 dlopen

虽然现在 `-DANDROID_STL=c++_shared` 已经把 `libc++_shared.so` 打进 APK 并通过 NEEDED 拉进来，构造函数里再 `dlopen("libc++_shared.so", RTLD_NOW | RTLD_GLOBAL)` 当 belt-and-suspenders——一行打印 `AsrShim: dlopen libc++_shared.so RTLD_GLOBAL ok` 也方便日志确认 shim 真被装载。

### 1.4 `AndroidManifest.xml`：`extractNativeLibs="true"`

显式声明把 .so 解压到 `/data/app/.../lib/` 下，避免某些 Android 版本/启动器从 APK 内部走 zip 路径加载导致的奇怪问题。

---

## 2. 当前状态

| 阶段 | 现象 |
|------|------|
| 启动 App | OK |
| 点 "加载 ASR" | OK，状态栏变 `ASR 就绪 · 按住下方按钮说话` |
| **按住 "按住说话"** | ❌ **闪退** |

也就是说：**3 个 .so dlopen + 模型构造（`OnlineRecognizer(cfg)` → `newFromFile`）全部通过了**。崩溃发生在录音/解码循环里，是另一类问题，跟 NEEDED/namespace 已经没关系。

---

## 3. 闪退待查方向（按概率排序）

`AsrTestActivity.kt` 中按下按钮触发 `startRecording()` → 起 `recordThread`，里面调用顺序：

```
rec.createStream("")  ──▶ JNI: createStream(ptr, hotwords)
audioRecord.read(...)
stream.acceptWaveform(samples, SR)  ──▶ JNI
rec.isReady(stream) / rec.decode(stream) / rec.getResult / rec.isEndpoint / rec.reset
```

任何一步 JNI 都可能 native crash，需要 logcat 才能定位。可能性：

1. **`createStream` 在 worker 线程里第一次解析 JNI 符号时再次触发 dlopen** —— 比如 sherpa JNI 里 lazy 调用 Express 的另一个符号（不是 `Module::load`），shim 没把它顶上来。**最有可能**。
2. **音频权限未授时直接走到 `audioRecord.startRecording()`** —— `requestAudioPermission` 是异步的，但 `startRecording()` 已经在 UI 线程里继续执行了。读到的是 0/-1 大小，不一定崩，但要确认。
3. **`AudioRecord` 初始化失败**（比如 minBuf 计算错或硬件占用），`startRecording()` 后 state 不是 `RECORDSTATE_RECORDING`，部分机型会 native crash。
4. **`stream.acceptWaveform` 输入约束** —— sherpa zipformer 对采样率/帧长有要求，100ms 切片在 16k 上是 1600 sample，正常应该 OK，但仍然要看 logcat。
5. **模型路径或 tokens 未真正读到**（`/sdcard/Download/MoeAvatar/asr/...`）—— `newFromFile` 能成功未必所有文件都开了，某些 lazy init 可能延迟到 `createStream` 才报错。

---

## 4. 抓闪退栈的最小命令

```bash
adb logcat -c
# 触发"按住说话"，复现闪退后：
adb logcat -d -s AsrTest:* AsrShim:* libc:* DEBUG:* AndroidRuntime:E | tail -200
```

重点看：
- `DEBUG` tag 里的 `signal 11 (SIGSEGV)` / `Abort message` / `backtrace`
- `AndroidRuntime: E  AndroidRuntime: FATAL EXCEPTION` 后面那一段 Java/Kotlin 栈
- `AsrShim` 是不是又重新 dlopen 了什么

如果是 native crash，把 `backtrace` 第一个 frame 的 `pc` + `libsherpa-mnn-jni.so` 行喂给：

```bash
$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-addr2line \
    -C -f -e app/src/main/jniLibs/arm64-v8a/libsherpa-mnn-jni.so 0x<pc>
```

注意：sherpa 是 release strip 过的，可能只能拿到符号名+偏移，未必行号。但符号名通常足够定位是哪个 sherpa C++ 类。

---

## 5. 下一步建议（不要再改 .so 了）

1. 接 USB 让 `adb devices` 能看到设备 → 复现 mic 闪退 → 抓 logcat
2. 拿到栈以后再决定是 sherpa 配置问题还是 AudioRecord 时序问题
3. 如果是 sherpa 内部 lazy dlopen 别的符号导致：在 `asrtest_shim.cpp` 里再 `dlopen` 一下报错那个 .so 的对应 RTLD_GLOBAL，或检查 sherpa 是否还需要别的 MNN 子库
4. **不要再去改 sherpa .so 自身的 ELF**——上一篇已经写明 patchelf 0.13 在 ARM64 上会写坏 GNU_HASH

---

## 6. 给 AI 的一句提示词

> 用户报"加载成功但按住说话闪退"，并且 .so 加载链已经按 [11_sherpa-mnn-jni加载坑案.md](11_sherpa-mnn-jni加载坑案.md) 的"3. 最终方案"修过——
> **不要再动 CMake / loadLibrary 顺序 / .so**，改成先抓 `adb logcat -s AsrTest:* AsrShim:* DEBUG:* AndroidRuntime:E`，根据栈定位 `createStream` / `acceptWaveform` / `AudioRecord` 哪一步崩。
