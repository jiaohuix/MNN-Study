# 03 MNN 核心库与 MnnLlmChat 编译部署流程

> 命令行测试成功，只是万里长征的第一步。
> 接下来把端侧 LLM 完整地移植到手机 APP 上。本文聚焦 **Android** 部分。

前置条件：[02_WSL环境搭建与设备连接.md](02_WSL环境搭建与设备连接.md) 已完成（JDK / SDK / NDK / CMake / adb 全部就绪）。

---

## 第 1 步：编译 MNN Android 核心库

为 Android 平台编译 MNN 引擎的动态库（`.so` 文件），这是 APP 运行的核心。

```bash
# 1. 进入 MNN 的 Android 工程目录
cd MNN/project/android

# 2. 创建编译目录
mkdir build_64 && cd build_64

# 3. 执行编译脚本（包含 LLM、VISION、OPENCL 等关键模块）
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

# 4. 安装编译产物（.so 会被拷到 install 目录里）
make install
```

### 编译开关说明（重点）

| 开关 | 作用 |
|------|------|
| `MNN_LOW_MEMORY=true` | 启用低内存模式（手机端必需） |
| `MNN_CPU_WEIGHT_DEQUANT_GEMM=true` | CPU 上权重反量化 GEMM，量化模型更快 |
| `MNN_BUILD_LLM=true` | **构建 LLM 引擎**（不开就没法跑大模型） |
| `MNN_SUPPORT_TRANSFORMER_FUSE=true` | Transformer 算子融合，性能关键 |
| `MNN_ARM82=true` | 启用 ARMv8.2 指令（半精度 fp16），新手机必开 |
| `MNN_OPENCL=true` | 启用 OpenCL GPU 后端 |
| `LLM_SUPPORT_VISION=true` | 多模态视觉支持 |
| `LLM_SUPPORT_AUDIO=true` | 多模态音频支持 |
| `MNN_BUILD_DIFFUSION=ON` | 文生图（Stable Diffusion）支持 |
| `MNN_USE_LOGCAT=true` | 日志走 Android logcat（方便 `adb logcat` 抓） |
| `max-page-size=16384` | 兼容部分新机型的 16KB 页大小 |

第一次编会比较慢（几分钟到十几分钟），耐心等。

---

## 第 2 步：编译并安装 MNN Chat APP

核心库准备好后，编译作为示例的 MnnLlmChat 应用：

```bash
# 1. 进入 MnnLlmChat 工程目录
cd MNN/apps/Android/MnnLlmChat

# 2. 执行安装脚本（会自动编译 + adb install 到手机）
./installDebug.sh
```

### 前提检查清单

- [ ] Android SDK / NDK 已正确配置（见 [02 文档](02_WSL环境搭建与设备连接.md)）
- [ ] 第 1 步的核心库已成功 `make install`
- [ ] 手机已连接，`adb devices` 能看到设备
- [ ] 手机已开启"开发者选项"和"USB 调试"（或"无线调试"）

`installDebug.sh` 内部做的事（**两行独立命令，不是 `&&`**）：
1. `./gradlew assembleStandardDebug` 编 APK
2. `adb install app/build/outputs/apk/standard/debug/app-standard-debug.apk` 装到手机

> 因为是独立命令，**第一行编译成功后 APK 一定会生成**。第二行报 `adb: no devices/emulators found` 不影响 APK 产物，可以从 `app/build/outputs/apk/standard/debug/` 直接取。

如果脚本失败 / 你只想编不装，可以手工分步：

```bash
./gradlew assembleStandardDebug                                                    # 只编不装
adb install -r app/build/outputs/apk/standard/debug/app-standard-debug.apk         # 单独装
```

> 关于国内网络踩坑、镜像配置、`gradlew` 卡在下载、`make install` 漏做等问题，见 [05 国内网络踩坑与可复现Playbook](05_国内网络踩坑与可复现Playbook.md)。

---

## 第 3 步：使用 App

1. 在手机的应用列表里找到 **MNN Chat**
2. 首次启动会列出支持的模型，挑一个下载（建议先试体积较小的，如 Qwen2-1.5B）
3. 下载完成后即可对话

可选：把模型下载到电脑、再 `adb push` 到手机指定目录，避免在手机上下大文件。

> **本仓默认本地 LLM**：MoeAvatarPro / MnnLlmChat 实测用我自己微调的猫娘 0.8B —— [jiaohui/qwen35_08b_nekoneko](https://modelscope.cn/models/jiaohui/qwen35_08b_nekoneko)（Qwen3-0.8B 基座，已转 MNN）。下载后 `adb push` 到 App 扫描目录即可；详细说明见 doc 10 §10。
>
> ```bash
> pip install modelscope
> modelscope download --model jiaohui/qwen35_08b_nekoneko --local_dir /tmp/qwen35_08b_nekoneko
> adb push /tmp/qwen35_08b_nekoneko /sdcard/Download/MoeAvatar/models/   # MoeAvatarPro
> # 或 MnnLlmChat: adb push 到 /sdcard/Android/data/com.alibaba.mnnllm.android/files/...
> ```

---

## 常见问题

| 现象 | 原因 / 解决 |
|------|------|
| `gradlew: Permission denied` | `chmod +x gradlew` |
| CMake 报找不到 NDK | 检查 `local.properties`：`ndk.dir=$HOME/Android/Sdk/ndk/<version>` |
| `adb install` 报 `INSTALL_FAILED_USER_RESTRICTED` | 手机上确认安装弹窗；小米/OPPO 需关掉 MIUI/ColorOS 的额外限制 |
| App 启动崩溃，logcat 报找不到 `libMNN.so` | 第 1 步未编完 / `MNN_SEP_BUILD=OFF` 没传，导致 .so 未被打包 |
| 模型推理很慢 | 确认 `MNN_ARM82` 和 `MNN_OPENCL` 开了；可在 App 设置里切换 GPU 后端 |

---

## iOS 部分

本套文档只覆盖 Android。iOS 编译流程参考 `apps/iOS/` 下的 README（需要 macOS + Xcode 环境）。
