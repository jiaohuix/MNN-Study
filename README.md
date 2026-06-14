# MNN-Study

学习 [alibaba/MNN](https://github.com/alibaba/MNN) 端侧推理引擎的实操记录。
不是 MNN 的 fork，而是"在 WSL 里从零把 MNN LLM / TTS / ASR / Live2D 数字人跑到安卓手机上"过程中沉淀下来的**文档 + 可参考的 Android demo 代码**。

读者画像：**熟 Python，不熟 Android/Java/NDK**，但要在 Linux/WSL 命令行下完成端侧 AI App 的开发与部署。

## 这里有什么

```
MNN-Study/
├── docs/android-wsl-开发指南/   # 一套从 0 到 1 的中文实操文档（13 篇）
└── apps/                        # 三个 Android demo 的纯代码归档（无 build/无模型/无 .so）
    ├── MoeChat/                 # MNN LLM 聊天 baseline
    ├── MoeAvatarPro/            # Live2D 数字人：LLM + Bert-VITS2 TTS + sherpa-mnn ASR
    └── MnnAsrTest/              # sherpa-mnn 流式 ASR 单 Activity 测试 App
```

## 文档导览

完整索引见 [docs/android-wsl-开发指南/00_总览_这套文档讲什么.md](docs/android-wsl-开发指南/00_总览_这套文档讲什么.md)。
关键篇目：

| # | 主题 |
|---|---|
| 02 | WSL 不装 Android Studio，纯命令行装 SDK/NDK，连手机的三种方式 |
| 03 | 编 MNN 核心库 → 编 MnnLlmChat App → adb 装机的完整命令 |
| 05 | 国内网络下 Gradle / 镜像 / 一次性复现脚本 |
| 07 | MnnLlmChat 项目结构、UI 布局、JNI 桥接全景 |
| 08 | 把 Bert-VITS2-MNN TTS 接进新 App 的踩坑实录 |
| 10 | MoeAvatarPro 模块结构 + LLM/TTS/Live2D/ASR 数据流 |
| 11 | Android 7+ namespace 隔离下 sherpa-mnn-jni dlopen 失败的根因 + CMake shim |
| 13 | ★ 终结篇：sherpa-mnn ASR 集成 8 步清单（DT_NEEDED / 模型路径 / UI 坑全包） |

## Demo 归档

`apps/` 下三个 demo 是**纯代码版**：保留源码、Gradle、AndroidManifest、res，剔除 build/、第三方源码、模型文件、jniLibs 的 `.so`。
要重新编译需按 [apps/README.md](apps/README.md) 的"补料清单"把对应内容拉回。归档总大小 ~1MB。

| Demo | 一句话 | 配套文档 |
|---|---|---|
| MoeChat | 最早能跑通的 MNN LLM 聊天界面 | 03 / 07 |
| MoeAvatarPro | Live2D 数字人：LLM 驱动对话 + TTS 出声 + ASR 听语音 | 09 / 10 |
| MnnAsrTest | 单 Activity 验证 sherpa-mnn 流式 ASR，含 RTF / 首字延时指标 | 11 / 12 / 13 |

## 实测使用的模型（ModelScope）

| 用途 | 模型 |
|---|---|
| 流式 ASR（MnnAsrTest / MoeAvatarPro） | [MNN/sherpa-mnn-streaming-zipformer-bilingual-zh-en-2023-02-20](https://www.modelscope.cn/models/MNN/sherpa-mnn-streaming-zipformer-bilingual-zh-en-2023-02-20) — sherpa-mnn 中英双语流式 zipformer |
| 本地 LLM（MoeChat / MoeAvatarPro 默认） | [jiaohui/qwen35_08b_nekoneko](https://modelscope.cn/models/jiaohui/qwen35_08b_nekoneko) — 我自己微调的猫娘 0.8B（Qwen3-0.8B → MNN） |

下载与 push 命令见 [apps/README.md §模型来源](apps/README.md)。

## 这套记录的目的

- 把"端侧大模型上 Android"这条路上踩过的坑（namespace、scoped storage、Gradle 镜像、UI 不显示…）一次性写清楚，下次直接复用
- 给同样不熟 Android 的同行一份"小白也能照抄"的 step-by-step
- 配合 demo 代码当作模板，从中拷出 CMake / Manifest / Activity 直接拼新 App

不涉及 MNN 引擎本身的内部实现细节——那部分在上游 [alibaba/MNN](https://github.com/alibaba/MNN) 仓库。
