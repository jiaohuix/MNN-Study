# 11 sherpa-mnn-jni 加载坑案：Android 7+ namespace 隔离下的 DT_NEEDED 修复

> **场景**：把 `sherpa-mnn` 的预编译 `libsherpa-mnn-jni.so` 塞进一个新 Android 项目（如 `apps/Android/MnnAsrTest/`），运行时反复崩在 `OnlineRecognizer.<clinit>`。
>
> 这篇是 [10_MoeAvatarPro项目拆解.md](10_MoeAvatarPro项目拆解.md) 的姊妹篇——专门讲为什么相同的 .so 在 MnnLlmChat 里能跑、复制到一个全新项目就崩，以及最终怎么修。

---

## 0. TL;DR

**问题**：`libsherpa-mnn-jni.so` 在新项目里 dlopen 失败，`UnsatisfiedLinkError`。

**根因 3 件套**（同时存在）：
1. `libsherpa-mnn-jni.so` 用了 `MNN::Express::Module::load`，符号在 **`libMNN_Express.so`** 里
2. 它自己的 `DT_NEEDED` 列表 **只声明了 `libMNN.so`，没声明 `libMNN_Express.so`**
3. Android 7+ linker 是 **RTLD_LOCAL + namespace 隔离**，单独 `System.loadLibrary("MNN_Express")` 不会让 sherpa 看到符号

**最终修法（推荐）**：在工程里加一个空壳 CMake 模块 `asrtest_shim`，通过 `target_link_libraries` 让它 link 上 `libMNN.so` / `libMNN_Express.so` / `libsherpa-mnn-jni.so`。Kotlin 里先 `loadLibrary("asrtest_shim")`，**linker 会沿着 NEEDED 链把所有依赖一次性拉进主命名空间**。

**踩过但放弃的方案**：
- ❌ Kotlin 里 `loadLibrary("MNN_Express")` 然后再 load sherpa：namespace 隔离，没用
- ❌ `patchelf --add-needed libMNN_Express.so libsherpa-mnn-jni.so`：patchelf 0.13 在 ARM64 上写坏 `GNU_HASH`，linker 直接拒绝加载
- ❌ LIEF Python 库改 ELF：直接 segfault
- ❌ shim 里 `dlopen("libMNN_Express.so", RTLD_GLOBAL)`：能跑但比 NEEDED 方案脆弱

---

## 1. 现象：三段不同的崩溃栈

按"修一次报一个新错"的顺序，出现过三种崩法：

### 1.1 第一阶段 — `regex_error` 符号缺失

```
java.lang.UnsatisfiedLinkError: dlopen failed:
  cannot locate symbol "_ZNSt6__ndk111regex_errorD1Ev"
  referenced by ".../libsherpa-mnn-jni.so"
```

`c++filt` 解出来是 `std::__ndk1::regex_error::~regex_error()`。
**原因**：sherpa 用了 `<regex>`（C++ 异常类），需要 `libc++_shared.so`，但它的 `DT_NEEDED` 没写。

### 1.2 第二阶段 — `MNN::Express::Module::load` 符号缺失

```
java.lang.UnsatisfiedLinkError: dlopen failed:
  cannot locate symbol "_ZN3MNN7Express6Module4loadE..."
  referenced by ".../libsherpa-mnn-jni.so"
```

`c++filt`：

```cpp
MNN::Express::Module::load(
    const std::vector<std::string>&,
    const std::vector<std::string>&,
    const unsigned char*, size_t,
    std::shared_ptr<MNN::Express::Executor::RuntimeManager>,
    const MNN::Express::Module::Config*)
```

`nm -D libMNN_Express.so` 验证：**符号确实在那里面**。但 sherpa `DT_NEEDED` 没声明它。

### 1.3 第三阶段 — `GNU_HASH` 损坏（patchelf 0.13 的锅）

```
java.lang.UnsatisfiedLinkError: dlopen failed:
  empty/missing DT_HASH/DT_GNU_HASH in ".../libsherpa-mnn-jni.so"
  (new hash type from the future?)
```

这是我用 `patchelf --add-needed libMNN_Express.so libsherpa-mnn-jni.so` 想给 ELF 补 NEEDED 时写坏的。已知 patchelf 0.13 在 ARM64 上有这个 bug。

---

## 2. 根因解剖

### 2.1 为什么 sherpa 用了 Express 但没 NEEDED 它？

看 `readelf -d libsherpa-mnn-jni.so`：

```
NEEDED: libandroid.so
NEEDED: liblog.so
NEEDED: libMNN.so       ← 只有 MNN 主库
NEEDED: libm.so
NEEDED: libdl.so
NEEDED: libc.so
```

——大概率是 sherpa 编译时是把 `libMNN_Express.so` 当 `IMPORTED INTERFACE` 而非真正 link 进 NEEDED 链。这种产物在**符号从 libMNN.so 拆出来之前**能跑，一旦 MNN 把 Express 拆成独立 .so（这个 fork 干的事），sherpa 旧产物就废了。

### 2.2 为什么 `System.loadLibrary("MNN_Express")` 不管用？

Android 7（Nougat）开始，每个 APK 默认运行在自己的 **linker namespace** 里，且每次 dlopen 默认是 `RTLD_LOCAL`：
- 你 `System.loadLibrary("MNN_Express")` 时，linker 把它放进 namespace
- 但**它的符号不会自动对其他 .so 可见**
- 后续 `dlopen("libsherpa-mnn-jni.so")` 解析符号时**只看 sherpa 自己的 DT_NEEDED 链**

参考：[Android Linker Namespace 设计文档](https://source.android.com/docs/core/architecture/vndk/linker-namespace)

简单说：**Android 不是 Linux**。Linux 的 `LD_LIBRARY_PATH` + 全局符号表那套思维在这里行不通。

### 2.3 为什么 NEEDED 链管用？

linker 在 dlopen 一个 .so 时，**会递归地加载它 DT_NEEDED 列出的所有 .so，并在该 .so 的链上做符号解析**。所以：

```
asrtest_shim.so          (你用 CMake 编的空壳)
  └─ NEEDED: libMNN.so
  └─ NEEDED: libMNN_Express.so
  └─ NEEDED: libsherpa-mnn-jni.so
            └─ NEEDED: libMNN.so   (它自己的 NEEDED)
```

dlopen `asrtest_shim` 时，linker 把 MNN/Express/sherpa 全拉到同一个命名空间里，sherpa 解析 `MNN::Express::Module::load` 时能看到 Express 已经加载，符号成功解析。

---

## 3. 最终方案（操作清单）

### 3.1 创建 CMake shim 模块

`app/src/main/cpp/CMakeLists.txt`：

```cmake
cmake_minimum_required(VERSION 3.22.1)
project("asrtest_native")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(JNILIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

# 让 asrtest_shim 把所有需要的 .so 都拉进 NEEDED 链：
add_library(mnn         SHARED IMPORTED)
add_library(mnn_express SHARED IMPORTED)
add_library(sherpa_mnn  SHARED IMPORTED)
set_target_properties(mnn         PROPERTIES IMPORTED_LOCATION "${JNILIBS_DIR}/libMNN.so")
set_target_properties(mnn_express PROPERTIES IMPORTED_LOCATION "${JNILIBS_DIR}/libMNN_Express.so")
set_target_properties(sherpa_mnn  PROPERTIES IMPORTED_LOCATION "${JNILIBS_DIR}/libsherpa-mnn-jni.so")

add_library(asrtest_shim SHARED asrtest_shim.cpp)
target_link_libraries(asrtest_shim log mnn mnn_express sherpa_mnn)
```

`app/src/main/cpp/asrtest_shim.cpp`：随便写点内容让它非空，常用是带个 `__attribute__((constructor))` 顺手 `dlopen("libc++_shared.so", RTLD_GLOBAL)` 解决 `std::regex` 那一个坑（有 `c++_shared` 也就稳了）：

```cpp
#include <dlfcn.h>
#include <android/log.h>
#include <string>

__attribute__((constructor))
static void preload_cxx_shared() {
    void* h = dlopen("libc++_shared.so", RTLD_NOW | RTLD_GLOBAL);
    __android_log_print(h ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
        "AsrShim", h ? "libc++_shared loaded" : "libc++_shared FAILED: %s",
        h ? "" : dlerror());
}

extern "C" int asrtest_shim_keep_alive() {
    std::string s = "asrtest"; return (int)s.size();
}
```

### 3.2 `app/build.gradle` 启用 CMake

```groovy
android {
    defaultConfig {
        externalNativeBuild {
            cmake {
                cppFlags "-std=c++17"
                arguments "-DANDROID_STL=c++_shared"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path file('src/main/cpp/CMakeLists.txt')
            version '3.22.1'
        }
    }
}
```

`-DANDROID_STL=c++_shared` 这行是重点：让 NDK 把 `libc++_shared.so` 也打进 APK。

### 3.3 把 4 个预编译 .so 放到 jniLibs

```
app/src/main/jniLibs/arm64-v8a/
├── libMNN.so
├── libMNN_Express.so
└── libsherpa-mnn-jni.so       ← 必须是原版未被 patchelf 改过的（md5 7db639fa...）
```

`libc++_shared.so` 由 `-DANDROID_STL=c++_shared` 自动注入，不要手动放（手动放容易和 NDK 内部那份冲突）。

### 3.4 Kotlin 加载顺序

`OnlineRecognizer.kt` / `OnlineStream.kt` 的 companion init：

```kotlin
companion object {
    init {
        System.loadLibrary("MNN")
        System.loadLibrary("MNN_Express")
        System.loadLibrary("asrtest_shim")    // ← 它的 NEEDED 链把 sherpa 一起拖进来
        System.loadLibrary("sherpa-mnn-jni")
    }
}
```

最后一行其实可以不写（shim 已经把它 NEEDED 进来了），但显式 load 一次保险。

### 3.5 验证

```bash
# 验证 shim NEEDED 完整
unzip -p app-debug.apk lib/arm64-v8a/libasrtest_shim.so > /tmp/shim.so
readelf -d /tmp/shim.so | grep NEEDED

# 期望看到（顺序无所谓）：
#   NEEDED libMNN.so
#   NEEDED libMNN_Express.so
#   NEEDED libsherpa-mnn-jni.so
#   NEEDED libc++_shared.so

# 验证 sherpa .so GNU_HASH 完好（不能被 patchelf 动过）
unzip -p app-debug.apk lib/arm64-v8a/libsherpa-mnn-jni.so > /tmp/sh.so
readelf -S /tmp/sh.so | grep -i hash
# 期望看到：
#   .gnu.hash    GNU_HASH
#   .hash        HASH
```

---

## 4. 走过的弯路（按时间顺序）

| 尝试 | 失败原因 | 教训 |
|------|----------|------|
| `loadLibrary("c++_shared")` 再 load sherpa | namespace 隔离，sherpa 看不见 c++_shared 的符号 | Android 不是 Linux，全局加载思维错 |
| 在 Kotlin 里多 load 几个 .so | 同上 | linker 解析符号只看自己的 NEEDED 链 |
| `patchelf --add-needed libc++_shared.so` | patchelf 0.13 在 ARM64 写坏 GNU_HASH | 别用 patchelf 0.13 改 ARM64 ELF |
| `patchelf --add-needed libMNN_Express.so` | 同上 | 同上 |
| LIEF (`lief.parse + add_library + write`) | LIEF 0.11 + 这个 .so 直接 segfault | LIEF 也不是万能的 |
| 写空壳 `.so` 在它里面 `dlopen("...", RTLD_GLOBAL)` | 能跑，但只解决 c++_shared 不解决 Express 符号（还得加 NEEDED） | 原理对但不彻底 |
| **CMake shim + target_link_libraries**（最终） | ✅ | linker 沿 NEEDED 链递归加载，符号解析自然贯通 |

---

## 5. 把这套思路套到别的项目

对于 **MoeAvatarPro / 任何用 sherpa-mnn 的新项目**，复刻流程：

1. 拷贝同一份 md5 的 `libsherpa-mnn-jni.so`（原版，不要被 patchelf 动过）
2. 拷贝匹配版本的 `libMNN.so` + `libMNN_Express.so`
3. 在 `app/src/main/cpp/` 加两个文件：`CMakeLists.txt` + `<project>_shim.cpp`
4. `app/build.gradle` 加 `externalNativeBuild` 块 + `-DANDROID_STL=c++_shared`
5. Kotlin `OnlineRecognizer` / `OnlineStream` 的 companion init 改成显式 4 步加载

注意 shim 库名要与 Kotlin 里 `loadLibrary("xxx_shim")` 一致。

---

## 6. 调试 cheatsheet

```bash
# 看 .so 的 NEEDED 链
readelf -d <lib.so> | grep NEEDED

# 查符号在哪个 .so 里（c++ 名字）
nm -D --defined-only <libMNN_Express.so> | c++filt | grep "Module::load"

# 反查 mangled name
echo "_ZN3MNN..." | c++filt

# 验证 ELF 没被改坏
readelf -S <lib.so> | grep -i hash
# .gnu.hash 和 .hash 都应该存在

# 看 APK 里实际打进的 .so
unzip -l app-debug.apk | grep "\.so$"
```

logcat 时只盯关键 tag，别看 `AndroidRuntime` 全量噪音：

```cmd
adb logcat -c
adb logcat -s <YourTag>:* AsrShim:* AndroidRuntime:E
```

---

## 7. 给 AI 的快速复用提示词

> 用户报 `UnsatisfiedLinkError: cannot locate symbol "_ZN3MNN7Express6Module4loadE..."` 时，
> 直接读 [docs/android-wsl-开发指南/11_sherpa-mnn-jni加载坑案.md](11_sherpa-mnn-jni加载坑案.md) 的"3. 最终方案"，
> 按 5 步操作即可。**不要尝试 patchelf 改 sherpa .so 的 ELF**——会写坏 GNU_HASH。
