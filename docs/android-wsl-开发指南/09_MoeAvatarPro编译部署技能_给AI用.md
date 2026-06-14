# MoeAvatarPro 编译部署技能（给 AI 用）

> 本文是一份"操作脚本式"文档，目标读者：**别的 AI 助手**。
> 它们不懂 Android Gradle / NDK / APK，但只要严格按照本文档照搬命令，就能把
> `apps/Android/MoeAvatarPro/` 编出 APK，并落到 Windows 主机的 Downloads 目录。
>
> 触发条件：用户说"编一下 MoeAvatarPro"、"打个 APK"、"重新出包"、"装到手机上看看"等。

---

## TL;DR — 一条命令出包到指定路径

```bash
cd /home/jhx/Projects/nlp/MNN/apps/Android/MoeAvatarPro && \
  ./gradlew :app:assembleDebug && \
  cp app/build/outputs/apk/debug/app-debug.apk \
     /mnt/c/Users/jiaohuix/Downloads/MoeAvatarPro-debug.apk && \
  ls -lh /mnt/c/Users/jiaohuix/Downloads/MoeAvatarPro-debug.apk
```

成功的特征：
- Gradle 末尾打印 `BUILD SUCCESSFUL in <时间>`
- `ls -lh` 输出 ~190–210MB 的 APK，时间戳是当前时刻

之后告诉用户在 Windows PowerShell / CMD 里执行：

```cmd
adb install -r C:\Users\jiaohuix\Downloads\MoeAvatarPro-debug.apk
```

> 装机命令必须由用户在 **Windows 宿主**执行，因为 WSL 里的 adb server 和
> Windows 那边的 adb server 容易抢端口；让用户用 Windows 自己的 adb 最稳。

---

## 一、必须先确认的前提（执行前用 bash 检查）

| 检查项 | 检查命令 | 期望 |
|--------|---------|------|
| 工作目录存在 | `ls /home/jhx/Projects/nlp/MNN/apps/Android/MoeAvatarPro/gradlew` | 文件存在且可执行 |
| Java 17 | `java -version 2>&1 \| head -1` | `openjdk version "17"` |
| NDK 26.1 | `ls /home/jhx/Android/Sdk/ndk/26.1.10909125` | 目录存在 |
| Android SDK | `ls /home/jhx/Android/Sdk/platforms/android-35` | 目录存在 |
| 输出目录可写 | `ls /mnt/c/Users/jiaohuix/Downloads/` | WSL 能访问 Windows 盘 |

如果用户机器上路径不一样：**停下来问用户**，不要自己改路径或新装 SDK。

---

## 二、标准编译流程

### Step 1 — 进入工程目录

```bash
cd /home/jhx/Projects/nlp/MNN/apps/Android/MoeAvatarPro
```

> 注意：本项目 **不是** `apps/Android/MnnLlmChat`。MoeAvatarPro 是 fork 出来的
> 独立子工程，`applicationId = com.moeavatar.pro`，可以和 MnnLlmChat 共存安装。

### Step 2 — 跑 gradle assembleDebug

```bash
./gradlew :app:assembleDebug
```

首次执行会下依赖（aliyun 镜像），耗时 1–3 分钟；增量编译通常 10–30 秒。

**预期结尾输出**：

```
BUILD SUCCESSFUL in 14s
35 actionable tasks: 5 executed, 30 up-to-date
```

### Step 3 — 拷贝 APK 到 Windows Downloads

```bash
cp /home/jhx/Projects/nlp/MNN/apps/Android/MoeAvatarPro/app/build/outputs/apk/debug/app-debug.apk \
   /mnt/c/Users/jiaohuix/Downloads/MoeAvatarPro-debug.apk
```

> 文件名固定叫 `MoeAvatarPro-debug.apk`，**不要改**。用户已经习惯这个路径，
> 改名只会让人困惑。

### Step 4 — 验证产物

```bash
ls -lh /mnt/c/Users/jiaohuix/Downloads/MoeAvatarPro-debug.apk
```

正常 APK 体积约 **190–210 MB**（包含 Live2D 模型 + sherpa-mnn-jni + bertvits2 .so 等）。
如果 < 50MB，说明 jniLibs 没打进去，回到 Step 2 加 `--rerun-tasks` 重新打。

### Step 5 — 告诉用户安装命令

用 **中文**告诉用户在 Windows 终端执行：

```
请在 Windows PowerShell 或 CMD 里跑：
    adb install -r C:\Users\jiaohuix\Downloads\MoeAvatarPro-debug.apk

如果提示 INSTALL_FAILED_UPDATE_INCOMPATIBLE，先卸载旧版：
    adb uninstall com.moeavatar.pro
    adb install C:\Users\jiaohuix\Downloads\MoeAvatarPro-debug.apk
```

---

## 三、常见报错与处理

### 1. `SDK location not found`

**原因**：缺 `local.properties`。

**修复**：

```bash
cat > /home/jhx/Projects/nlp/MNN/apps/Android/MoeAvatarPro/local.properties <<'EOF'
sdk.dir=/home/jhx/Android/Sdk
ndk.dir=/home/jhx/Android/Sdk/ndk/26.1.10909125
EOF
```

### 2. `Could not resolve com.android.tools.build:gradle:...`（拉依赖失败）

**原因**：网络抽风。本项目 `settings.gradle` 已经配了 aliyun 镜像，正常应该能拉。

**自检**：

```bash
grep -A2 'aliyun' /home/jhx/Projects/nlp/MNN/apps/Android/MoeAvatarPro/settings.gradle | head
```

如果输出为空，**停下来问用户**，不要乱改 settings.gradle。

### 3. `cannot find -lc++_shared` / `_ZNSt6__ndk111regex_error...`（运行时崩）

这是 **运行时**问题不是编译问题。本工程的 `sherpa/src/main/jniLibs/arm64-v8a/libsherpa-mnn-jni.so`
已经被 patchelf 加过 `DT_NEEDED libc++_shared.so`，不要重新替换该文件。
如果用户报这个错，先看 `sherpa/src/main/java/com/k2fsa/sherpa/mnn/SherpaNative.kt` 是否完整。

### 4. `Execution failed for task ':app:processDebugResources'`

**通常**是 XML 资源写错了。打开 Gradle 报错指向的具体 res 文件，找不到就停下来问人。

### 5. `BUILD SUCCESSFUL` 但 APK 不存在

**原因**：跑的是 `:assemble` 而不是 `:app:assembleDebug`，子模块（live2d/sherpa/...）
也产出 .aar 但 app 没编。**严格用 `:app:assembleDebug`**。

### 6. 编译卡死 > 5 分钟

可能在下 Gradle 本身或大依赖。让用户看看：

```bash
ls -lh ~/.gradle/caches/modules-2/files-2.1/ 2>/dev/null | head
```

如果在拉东西，等。如果完全没动静，`Ctrl-C` 后重跑一次。

---

## 四、清理 / 重打（用户说"完全重新编"时）

```bash
cd /home/jhx/Projects/nlp/MNN/apps/Android/MoeAvatarPro
./gradlew clean
./gradlew :app:assembleDebug
```

**不要**手动 `rm -rf build/`，gradle clean 更稳（保留 native build 缓存，下次更快）。

---

## 五、不要做的事

- ❌ 不要去改 `settings.gradle`、`build.gradle` 的版本号 / repo 列表，除非用户明确要求
- ❌ 不要去重新编 MNN 核心库（`project/android/build_64.sh`）—— MoeAvatarPro 用的是
  随仓库提交进来的预编译 `.so`，重编核心库 90% 概率会让 APK 反而起不来
- ❌ 不要替换 `sherpa/src/main/jniLibs/arm64-v8a/libsherpa-mnn-jni.so`
- ❌ 不要在 WSL 里跑 `adb install`，让用户用 Windows 那边的 adb
- ❌ 不要把 APK 拷到别的位置然后告诉用户"我放在 X 了"——固定就是
  `/mnt/c/Users/jiaohuix/Downloads/MoeAvatarPro-debug.apk`

---

## 六、对接其他 AI 的"提示词模板"

如果用户把这个任务转给另一个 AI，让它直接读本文。最短能跑通的提示词：

```
请按 /home/jhx/Projects/nlp/MNN/docs/MoeAvatarPro_编译部署技能.md 里的
"TL;DR" 一段命令编出 APK 并落到 /mnt/c/Users/jiaohuix/Downloads/MoeAvatarPro-debug.apk，
完成后告诉我 Windows 端的 adb install 命令。遇到报错按文档"常见报错"小节处理，
解决不了就停下来问我。
```
