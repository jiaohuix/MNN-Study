# 02 WSL 环境搭建与设备连接

> 目标：在 WSL（Linux）里**不装 Android Studio**，纯命令行完成安卓开发，并通过 adb 把 APK 装到手机。

Android Studio 本质上就是个带 GUI 的 gradle + SDK 包装器；命令行下完全可以替代它。

---

## 1. 安装 JDK 17

Gradle 8.x 需要 JDK 17：

```bash
sudo apt update
sudo apt install openjdk-17-jdk
java -version   # 应输出 openjdk 17.x
```

---

## 2. 下载 Android command-line tools

Google 官方提供的纯命令行工具包（替代 Android Studio）：

```bash
# 下载最新版（链接来自 https://developer.android.com/studio 页面底部 "Command line tools only"）
wget https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip

# 解压到约定路径
mkdir -p ~/Android/Sdk/cmdline-tools
unzip commandlinetools-linux-13114758_latest.zip -d ~/Android/Sdk/cmdline-tools

# 重要：Google 要求把目录名改成 latest
mv ~/Android/Sdk/cmdline-tools/cmdline-tools ~/Android/Sdk/cmdline-tools/latest
```

> 如果链接失效，去 https://developer.android.com/studio 页面底部，复制最新的 `commandlinetools-linux-XXXXXXXX_latest.zip` 链接替换即可。

---

## 3. 配置环境变量

把下面几行加到 `~/.bashrc`（或 `~/.zshrc`）末尾：

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin
export PATH=$PATH:$ANDROID_HOME/platform-tools
export PATH=$PATH:$ANDROID_HOME/emulator
```

然后 `source ~/.bashrc` 让其生效。

---

## 4. 用 sdkmanager 安装组件

```bash
# 接受所有 license（连按 y 即可）
sdkmanager --licenses

# 安装 MNN 安卓项目所需的全部组件
sdkmanager "platform-tools" \
           "platforms;android-34" \
           "build-tools;34.0.0" \
           "ndk;26.1.10909125" \
           "cmake;3.22.1"
```

各组件作用：
- **platform-tools**：提供 `adb`、`fastboot`
- **platforms;android-34**：API Level 34 的 SDK
- **build-tools;34.0.0**：aapt、dx 等构建工具
- **ndk**：必须，因为 MNN 需要把 C++ 源码编成 `.so`
- **cmake**：MNN 用 CMake 组织 C++ 构建

> NDK 版本号可能随时间变化，可以用 `sdkmanager --list | grep ndk` 查看当前最新版。

---

## 5. WSL 连手机的三种方案

**关键问题：WSL2 默认看不到 Windows 上插的 USB 设备**，需要解决这一步。

### 方案 A：用 Windows 上的 adb（最省事）

- Windows 装个 adb：`scoop install adb`，或下载 platform-tools 包
- 把 APK 从 WSL 复制到 Windows 路径，在 Windows PowerShell 跑：
  ```powershell
  adb install app-debug.apk
  ```
- WSL 文件路径在 Windows 里是 `\\wsl$\Ubuntu\home\jhx\...`，可直接访问

### 方案 B：usbipd-win 把 USB 透传给 WSL

```powershell
# Windows PowerShell（管理员权限）
winget install dorssel.usbipd-win
usbipd list                    # 找到手机的 BUSID（形如 2-3）
usbipd bind --busid <X-Y>      # 仅首次需要
usbipd attach --wsl --busid <X-Y>
```

完成后在 WSL 里：

```bash
adb devices    # 应能看到手机序列号
```

### 方案 C：adb over WiFi（★ 推荐）

最简单、最稳定，**完全绕开 USB 麻烦**。前提：手机和 WSL 在同一 WiFi 局域网。

1. Android 11+ 在"开发者选项"里打开"无线调试"
2. 点击"使用配对码配对设备"，会显示 IP:Port 和 6 位配对码
3. WSL 里：
   ```bash
   adb pair <ip>:<pair_port>      # 输入配对码（仅首次）
   adb connect <ip>:<port>        # 连接（端口和 pair 端口不同）
   adb devices                    # 验证
   ```

---

## 6. 验证环境

```bash
java -version              # OpenJDK 17
sdkmanager --version       # 命令行工具版本
adb --version              # platform-tools
ls $ANDROID_HOME/ndk/      # 至少有一个版本目录
ls $ANDROID_HOME/cmake/    # 至少有一个版本目录
```

环境就绪后，进入 [03_MNN核心库与MnnLlmChat编译部署流程.md](03_MNN核心库与MnnLlmChat编译部署流程.md) 开始编译。
