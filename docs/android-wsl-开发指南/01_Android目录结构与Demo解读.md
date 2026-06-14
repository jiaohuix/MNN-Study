# 01 Android 目录结构与 Demo 解读

> 读者背景：熟悉 Python，对 Android/Java 不熟。本文用 Python 类比帮你建立心智模型。

## 1. `project/android/` 这个目录到底是什么

```
project/android/
├── src/main/AndroidManifest.xml    # MNN 作为 Android 库（aar）发布时用
├── build.gradle / nativepub.gradle # 把 MNN C++ 编成 .so 并打包发布的 gradle 配置
├── build_*.sh                      # 直接用 NDK 命令行编 .so 的脚本（不走 AS）
├── demo/                           # ★ 真正的可运行示例 App
└── apps/MnnLlmApp/README*.md       # 一个"指路牌"，告诉你 LLM App 已搬到 apps/Android/MnnLlmChat
```

它的角色 = "把 MNN 这个 C++ 推理引擎包装成给安卓用的库 + 一个 Demo"。
真正的功能型 App（聊天、3D Avatar、TaoAvatar）已经移到了仓库根目录的 `apps/Android/` 下。

## 2. demo App 在做什么

`demo/app/` 是一个标准 Android Studio 项目，跑起来后会出现 4 个入口 Activity（在 `AndroidManifest.xml` 里注册）：

| Activity | 功能 | 用的模型 |
|---|---|---|
| `VideoActivity` | 摄像头实时分类 | MobileNet |
| `ImageActivity` | 单张图片分类（有只猫的测试图） | MobileNet v2 |
| `PortraitActivity` | 人像分割（背景虚化效果） | DeepLab v3 |
| `OpenGLTestActivity` | OpenGL 纹理 → MNN 推理的零拷贝示例 | — |

主线流程：**摄像头/图片 → 转成 Tensor → MNN 推理 → 把结果显示在屏幕上**。

## 3. 用 Python 类比理解调用流程

把 `ImageActivity.java` 里的核心代码翻译成 Python 思维：

```python
# 你大概会这样写：
net = mnn.load("mobilenet_v2.mnn")          # 1. 加载模型
session = net.create_session(threads=4)     # 2. 建 session
input_t  = session.get_input()              # 3. 拿输入 tensor
input_t.from_bitmap(img, mean=..., std=...) # 4. 预处理
session.run()                               # 5. 推理
output = session.get_output().numpy()       # 6. 取结果
```

Java 版本（节选自 `ImageActivity.java`）几乎一模一样，只是名字驼峰化：

```java
mNetInstance = MNNNetInstance.createFromFile(modelPath);   // 1
mSession     = mNetInstance.createSession(config);          // 2
mInputTensor = mSession.getInput(null);                     // 3
MNNImageProcess.convertBitmap(mBitmap, mInputTensor, ...);  // 4
mSession.run();                                             // 5
float[] result = mSession.getOutput(null).getFloatData();   // 6
```

关键的"魔法"在 `MNNNetNative.java`：里面全是 `native` 方法，靠 **JNI** 把 Java 调用桥接到 `libMNN.so`（C++ 实现）。和 Python 的 `pybind11` 思路一样，安卓世界叫 JNI。

```java
static { System.loadLibrary("MNN"); System.loadLibrary("mnncore"); }
protected static native long nativeCreateNetFromFile(String modelName);
```

> `mnncore` 是 demo 自己写的胶水 .so，`MNN` 是引擎本体。

## 4. 不同组件的角色一句话总结

| 文件/目录 | 角色（Python 类比） |
|---|---|
| `MNNNetNative.java` | `ctypes`/JNI 绑定层 — 声明 native 方法 |
| `MNNNetInstance.java` | 高层 Python 包装类 — `Net / Session / Tensor` 三件套 |
| `MNNImageProcess.java` | `torchvision.transforms` — 图像归一化、缩放、颜色转换 |
| `MNNForwardType.java` | 枚举：CPU / OpenCL / Vulkan 后端选择 |
| `mnndemo/*Activity.java` | 主程序 = 4 个 UI 页面（每个 Activity 类比一个页面/窗口） |
| `opengl/*` | 用 OpenGL 直接读摄像头纹理、避免 CPU 拷贝 |
| `demo/app/build.gradle` | 类比 `setup.py` + `Makefile`，描述如何编 + 打包 |
| `AndroidManifest.xml` | 应用清单：声明 4 个入口、相机/存储权限 |
| `build_64.sh` 等脚本 | 不走 AS，直接命令行 NDK 编 .so 的便捷脚本 |

## 5. 学习建议

- 如果你的目标是**理解 SDK 用法（Java↔C++ 怎么对接）** → 看 `project/android/demo/`。
- 如果你的目标是**把端侧大模型跑起来** → 直接去 `apps/Android/MnnLlmChat/`，那才是项目当前主推的 Android 应用。编译流程见 [03_MNN核心库与MnnLlmChat编译部署流程.md](03_MNN核心库与MnnLlmChat编译部署流程.md)。
