# 05 国内网络环境踩坑与可复现 Playbook

> 把"在 WSL 里 0 → 1 编出 MnnLlmChat APK"过程中遇到的所有坑和最终解法记录下来，下次照抄即可复现。

---

## 0. 背景知识：Gradle 是什么

一句话：**Java/Android 世界的 `make` + `pip` + `setup.py` 三合一**。

| Python 类比 | Gradle 对应 |
|---|---|
| `setup.py` / `pyproject.toml` | `build.gradle` / `settings.gradle` |
| `pip install xxx` | `dependencies { implementation 'com.xxx:yyy:1.0' }` |
| `pypi.org` | Maven 仓库（mavenCentral / google / jitpack / aliyun…） |
| `make` | `./gradlew assembleDebug`（任务名） |
| `python -m venv` | **Gradle Wrapper**（`./gradlew`，每个项目自带一个固定版本） |

**Wrapper 机制**：项目根目录里的 `gradlew` 脚本会查 `gradle/wrapper/gradle-wrapper.properties` 中的 `distributionUrl`，按需把对应版本的 Gradle 下载到 `~/.gradle/wrapper/dists/` 缓存起来。**第一次跑会下 130MB 的 zip，国内是主要卡点之一**。

`./gradlew assembleStandardDebug` 完整链路：
```
gradlew 启动 → 下/解压 gradle-8.9 → 读 settings.gradle/build.gradle
       → 从 Maven 拉几百个依赖 → 调 NDK+CMake 编 C++（生成 .so）
       → javac/kotlinc 编 Java/Kotlin → 打包 → 签名 → APK
```

> 国内卡点 ①：下 Gradle 本体；② 下 Maven 依赖。05 文档解决的就是这两件事。

---

## 1. 关于 `installDebug.sh` 的真相

```bash
# apps/Android/MnnLlmChat/installDebug.sh
./gradlew assembleStandardDebug
adb install app/build/outputs/apk/standard/debug/app-standard-debug.apk
```

**两条独立命令、不是 `&&` 串联**。所以：

- 第一行跑完后 APK 一定会生成（只要编译成功）
- 第二行单独执行；没设备只是第二行报错，APK 仍在 `app/build/outputs/apk/standard/debug/` 下
- "`adb: no devices/emulators found`" 是预期行为，不影响 APK 产物

**用法决策**：
- 在 WSL 里编、通过 Windows/手机装 → **直接用 `./gradlew assembleStandardDebug`**，绕开 adb
- 在 WSL 里编+装一条龙 → 配好无线调试，用 `installDebug.sh`

---

## 2. 一次性可复现的完整流程

> 假设：JDK17 / cmdline-tools / NDK 26.1 / CMake 3.22 已装好（见 02 文档），**还没编过任何东西**。

### Step 1: 准备 Gradle 本体（避开下载超时）

```bash
# 用 wget -c 断点续传（比 wrapper 自己下要稳得多）
mkdir -p ~/gradle-dist
cd ~/gradle-dist
wget -c --tries=20 --timeout=60 \
     https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip
# 备用源：https://mirrors.aliyun.com/macports/distfiles/gradle/gradle-8.9-bin.zip
ls -lh gradle-8.9-bin.zip   # 应为 ~130MB
```

> **注意**：腾讯云镜像并非 100% 稳定，但 `wget -c` 的断点续传在断了之后能续上；`./gradlew` 自带的下载器没有续传，断 = 重头来。

### Step 2: 让 wrapper 用本地 zip

```bash
sed -i "s|distributionUrl=.*|distributionUrl=file\\\\:///home/jhx/gradle-dist/gradle-8.9-bin.zip|" \
    /home/jhx/Projects/nlp/MNN/apps/Android/MnnLlmChat/gradle/wrapper/gradle-wrapper.properties
```

验证：
```bash
grep distributionUrl /home/jhx/Projects/nlp/MNN/apps/Android/MnnLlmChat/gradle/wrapper/gradle-wrapper.properties
# 期望：distributionUrl=file\:///home/jhx/gradle-dist/gradle-8.9-bin.zip
```

> 反斜杠转义在 sed 命令里要写两次（`\\\\:`），最终落到文件里是 `\:`，这是 .properties 格式对 `:` 的转义要求。

### Step 3: 给 settings.gradle 加阿里云 Maven 镜像

MnnLlmChat 的 `settings.gradle` 用了 `RepositoriesMode.FAIL_ON_PROJECT_REPOS`，这意味着**项目里所有 repo 都必须在 settings.gradle 里声明**——所以加镜像必须改这个文件，不能改 `~/.gradle/init.gradle`。

把 `apps/Android/MnnLlmChat/settings.gradle` 顶部两个 `repositories { ... }` 块都加上阿里云镜像（保留原有 mavenCentral/google）：

```groovy
pluginManagement {
    repositories {
        maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }
        maven { url 'https://maven.aliyun.com/repository/public' }
        maven { url 'https://maven.aliyun.com/repository/google' }
        mavenCentral()
        google()
        gradlePluginPortal()
        maven { url 'https://jitpack.io' }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url 'https://maven.aliyun.com/repository/public' }
        maven { url 'https://maven.aliyun.com/repository/google' }
        maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }
        mavenCentral()
        google()
        maven { url 'https://jitpack.io' }
    }
}
```

> ❌ 不要改 `~/.gradle/init.gradle.kts`：本项目用 Groovy DSL，Kotlin DSL 写法在初始化阶段会报 `Unresolved reference: repositoriesMode`。
> ✅ 改项目自身的 `settings.gradle`（Groovy）最稳。

### Step 4: 先编 MNN 核心库

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_NDK=$ANDROID_HOME/ndk/26.1.10909125

cd ~/Projects/nlp/MNN/project/android
mkdir -p build_64 && cd build_64
../build_64.sh "-DMNN_LOW_MEMORY=true \
                -DMNN_CPU_WEIGHT_DEQUANT_GEMM=true \
                -DMNN_BUILD_LLM=true \
                -DMNN_SUPPORT_TRANSFORMER_FUSE=true \
                -DMNN_ARM82=true \
                -DMNN_USE_LOGCAT=true \
                -DMNN_OPENCL=true \
                -DLLM_SUPPORT_VISION=true \
                -DMNN_BUILD_OPENCV=true \
                -DMNN_IMGCODECS=true \
                -DLLM_SUPPORT_AUDIO=true \
                -DMNN_BUILD_AUDIO=true \
                -DMNN_BUILD_DIFFUSION=ON \
                -DMNN_SEP_BUILD=OFF \
                -DCMAKE_SHARED_LINKER_FLAGS='-Wl,-z,max-page-size=16384' \
                -DCMAKE_INSTALL_PREFIX=."
make -j$(nproc) install   # ★ install 必须执行
```

> ⚠️ **不能省 `make install`**。MnnLlmChat 的 `app/src/main/cpp/CMakeLists.txt` 是从 `project/android/build_64` 找头文件和 `libMNN.so` 的；只 `make` 不 `install`，APK 编译会因为找不到产物失败。

### Step 5: 编 APK

```bash
cd ~/Projects/nlp/MNN/apps/Android/MnnLlmChat
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export ANDROID_NDK=$ANDROID_HOME/ndk/26.1.10909125

./gradlew assembleStandardDebug --no-daemon
```

成功标志：
```
BUILD SUCCESSFUL in 12m XXs
```

产物：`app/build/outputs/apk/standard/debug/app-standard-debug.apk`（约 42MB）

### Step 6: 拷到 Windows 下载目录

```bash
cp app/build/outputs/apk/standard/debug/app-standard-debug.apk \
   /mnt/c/Users/<你的Windows用户名>/Downloads/
```

之后在手机上装即可（微信传 / USB+adb / 文件传输）。

---

## 3. 踩过的坑（按出现顺序）

| # | 现象 | 根因 | 解法 |
|---|------|------|------|
| 1 | `Could not find toolchain file: /build/cmake/android.toolchain.cmake` | `$ANDROID_NDK` 未导出 | `export ANDROID_NDK=$HOME/Android/Sdk/ndk/26.1.10909125` 后清掉 `CMakeCache.txt` 重编 |
| 2 | `gradle-wrapper` 下载 `gradle-8.9-bin.zip` `Read timed out` | 默认源 `services.gradle.org` 国内不通 | 先 `wget -c` 下到本地，然后用 `file://` 路径写到 `gradle-wrapper.properties` |
| 3 | 镜像改成腾讯云后下到 70% `Connection reset` | 镜像本身被掐 | 同上，`wget -c` 续传 |
| 4 | `Unresolved reference: repositoriesMode` | 我把镜像配置写成了 `~/.gradle/init.gradle.kts`，但项目是 Groovy DSL | 删掉 init 脚本，直接改项目 `settings.gradle`（Groovy 写法） |
| 5 | 即使 `init.gradle` 加了 repo，仍然提示 "Build was configured to prefer settings repositories" | 项目 `settings.gradle` 用了 `RepositoriesMode.FAIL_ON_PROJECT_REPOS`，强制只从 settings.gradle 里读 repo | 必须改 `settings.gradle` 自己（不能用全局 init） |
| 6 | `installDebug.sh` 报 `adb: no devices/emulators found` | 脚本最后一行装 APK 时没设备 | **可以忽略**，APK 已经编出来；或改用 `./gradlew assembleStandardDebug` 跳过 install |

---

## 4. 复现核对清单（下次只需走这一遍）

```bash
# ========= 一次性环境 =========
[ ] 装 JDK17：sudo apt install openjdk-17-jdk
[ ] 下 cmdline-tools 并解压到 ~/Android/Sdk/cmdline-tools/latest/
[ ] sdkmanager 装 platform-tools / platforms;android-34 / build-tools;34.0.0 / ndk;26.1.10909125 / cmake;3.22.1
[ ] ~/.bashrc 加：
        export ANDROID_HOME=$HOME/Android/Sdk
        export ANDROID_NDK=$ANDROID_HOME/ndk/26.1.10909125
        export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

# ========= 国内网络一次性配置 =========
[ ] wget -c 下好 gradle-8.9-bin.zip 到 ~/gradle-dist/
[ ] sed 改 gradle-wrapper.properties 指向 file:// 本地 zip
[ ] settings.gradle 加阿里云 Maven 镜像（pluginManagement + dependencyResolutionManagement 两块都要加）

# ========= 编译 =========
[ ] cd project/android/build_64 && ../build_64.sh "<flags>" && make install   # 出 libMNN.so
[ ] cd apps/Android/MnnLlmChat && ./gradlew assembleStandardDebug --no-daemon   # 出 APK
[ ] cp APK 到 /mnt/c/Users/<user>/Downloads/
```

---

## 5. 一键脚本（可选）

把核对清单的核心步骤打包，存为 `~/bin/mnn-build-apk.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail

export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export ANDROID_NDK=$ANDROID_HOME/ndk/26.1.10909125

REPO=$HOME/Projects/nlp/MNN
WIN_USER=jiaohuix   # ← 改成你的 Windows 用户名

# 1. 编核心库（如已编过会增量）
cd $REPO/project/android
mkdir -p build_64 && cd build_64
[ -f Makefile ] || ../build_64.sh "-DMNN_LOW_MEMORY=true \
    -DMNN_CPU_WEIGHT_DEQUANT_GEMM=true -DMNN_BUILD_LLM=true \
    -DMNN_SUPPORT_TRANSFORMER_FUSE=true -DMNN_ARM82=true \
    -DMNN_USE_LOGCAT=true -DMNN_OPENCL=true -DLLM_SUPPORT_VISION=true \
    -DMNN_BUILD_OPENCV=true -DMNN_IMGCODECS=true -DLLM_SUPPORT_AUDIO=true \
    -DMNN_BUILD_AUDIO=true -DMNN_BUILD_DIFFUSION=ON -DMNN_SEP_BUILD=OFF \
    -DCMAKE_SHARED_LINKER_FLAGS='-Wl,-z,max-page-size=16384' \
    -DCMAKE_INSTALL_PREFIX=."
make -j$(nproc) install

# 2. 编 APK
cd $REPO/apps/Android/MnnLlmChat
./gradlew assembleStandardDebug --no-daemon

# 3. 导出到 Windows
APK=$(ls app/build/outputs/apk/standard/debug/*.apk | head -1)
DEST=/mnt/c/Users/$WIN_USER/Downloads/
cp "$APK" "$DEST"
echo "✓ APK 已导出: $DEST$(basename "$APK")"
```

```bash
chmod +x ~/bin/mnn-build-apk.sh
mnn-build-apk.sh
```
