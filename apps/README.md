# Android Demo 归档（纯代码版）

仅保留源码、Gradle 配置、AndroidManifest、layout/res，能让人一眼看清"做了什么、怎么对接 MNN"。
**不含**：编译产物、模型文件、第三方依赖源码、jniLibs `.so`。下次要编译需要按下面"补料清单"把对应内容拉回。

| Demo | 用途 | 归档大小 | 原始大小 |
|---|---|---|---|
| `MoeChat/` | 最早的 MNN LLM 聊天 demo（备份基线） | ~256K | 72M |
| `MoeAvatarPro/` | Live2D 数字人版：LLM + Bert-VITS2 TTS + sherpa-mnn ASR + Live2D | ~524K | 1.3G |
| `MnnAsrTest/` | sherpa-mnn 流式 ASR 单 Activity 测试 App（zipformer 中英双语） | ~220K | 78M |

## 共同剔除项

```
build/        .gradle/      .cxx/        .idea/      *.iml      local.properties
```

## 各项目额外剔除项

### MoeChat
无（本来就没有 assets/jniLibs）。

### MoeAvatarPro（剔得最多）
- `app/src/main/assets/`（127M：bert + bv2_model TTS 模型）
- `app/src/main/jniLibs/`（5M：编译出来的 .so）
- 顶层第三方依赖目录：`cppjieba/  cpptokenizer/  live2d/  openjtalk/  sherpa/  text-preprocess/  third_party/`（合计 ~460M，CMake 通过相对路径引用，编译时必须存在）
- `.lfs_pointers/`

### MnnAsrTest
- `app/src/main/jniLibs/`（含 libsherpa-mnn-jni.so / libMNN.so / libMNN_Express.so / libc++_shared.so）

## 模型来源（ModelScope）

下面两个就是这套 demo 实测在用的模型，均托管在 ModelScope，国内直连无需翻墙。

| 用途 | 模型 | 链接 |
|---|---|---|
| **流式 ASR**（MoeAvatarPro / MnnAsrTest 都用它） | sherpa-mnn 流式 zipformer 中英双语 | [MNN/sherpa-mnn-streaming-zipformer-bilingual-zh-en-2023-02-20](https://www.modelscope.cn/models/MNN/sherpa-mnn-streaming-zipformer-bilingual-zh-en-2023-02-20) |
| **本地 LLM**（MoeChat / MoeAvatarPro 默认本地后端） | 自己微调的猫娘 0.8B（Qwen3-0.8B 基座 → MNN 格式） | [jiaohui/qwen35_08b_nekoneko](https://modelscope.cn/models/jiaohui/qwen35_08b_nekoneko) |

下载并落到设备：

```bash
pip install modelscope

# 1) ASR —— 推到 /data/local/tmp/asr（必须；/sdcard/Download 在 Android 11+ 有读权限坑）
modelscope download --model MNN/sherpa-mnn-streaming-zipformer-bilingual-zh-en-2023-02-20 \
    --local_dir /tmp/asr_zipformer
adb shell mkdir -p /data/local/tmp/asr
adb push /tmp/asr_zipformer /data/local/tmp/asr/sherpa-mnn-streaming-zipformer-bilingual-zh-en-2023-02-20
adb shell chmod -R 755 /data/local/tmp/asr

# 2) LLM 猫娘 —— 推到 App 扫描的模型目录
modelscope download --model jiaohui/qwen35_08b_nekoneko --local_dir /tmp/qwen35_08b_nekoneko
adb push /tmp/qwen35_08b_nekoneko /sdcard/Download/MoeAvatar/models/
```

ASR 模型路径需与 `AsrTestActivity.DEFAULT_MODEL_DIR` / MoeAvatarPro 的 `AsrActivity` 默认值匹配（详见 docs §13 §10）。
LLM 启动后会在 App 设置里出现，选中即可；`sys_prompt` 用预设角色扮演 prompt。

---

## 补料清单（想要重新编译时）

### MoeAvatarPro
```bash
# 1. 第三方依赖（从原工作树拉回，或重新 git submodule init/update / git lfs pull）
cp -r /home/jhx/Projects/nlp/MNN/apps/Android/MoeAvatarPro/{cppjieba,cpptokenizer,live2d,openjtalk,sherpa,text-preprocess,third_party} .

# 2. TTS 模型 assets（128M）
cp -r /home/jhx/Projects/nlp/MNN/apps/Android/MoeAvatarPro/app/src/main/assets app/src/main/

# 3. ASR 模型 push 到设备（不打包进 APK，下载与 push 见上面"模型来源"）
```

### MnnAsrTest
`jniLibs/arm64-v8a/` 下需要：
```
libMNN.so  libMNN_Express.so  libsherpa-mnn-jni.so  libasrtest_shim.so  libc++_shared.so
```
- `libMNN.so` / `libMNN_Express.so`：MNN Android 核心库编译产物
- `libsherpa-mnn-jni.so`：来自 [k2-fsa/sherpa-mnn](https://github.com/k2-fsa/sherpa-mnn) Android 编译
- `libasrtest_shim.so`：CMake 在本项目内编译（cpp/asrtest_shim.cpp）
- `libc++_shared.so`：NDK 自带，需要随包

完整 step-by-step 参考 `docs/13_Android集成sherpa-mnn-ASR完整指南.md`。
ASR 模型同样推到 `/data/local/tmp/asr/`，不要放 `/sdcard/Download`（Android 11+ scoped storage 拦读权限）。

## 关键文档（已在 docs/ 同步）

- `09_MoeAvatarPro编译部署技能_给AI用.md` — MoeAvatarPro 一键编译
- `10_MoeAvatarPro项目拆解.md` — Live2D 数字人模块结构
- `11_sherpa-mnn-jni加载坑案.md` — DT_NEEDED / namespace 问题
- `12_MnnAsrTest本轮改动与mic闪退待查.md` — 4 处改动总结
- `13_Android集成sherpa-mnn-ASR完整指南.md` — sherpa ASR 集成 8 步清单（终结篇）
