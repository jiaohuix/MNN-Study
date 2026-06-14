# 07 MnnLlmChat 项目拆解（新手向）

> 读者画像：会 Python，但 **没碰过 Android/Kotlin**。
> 目标：5 分钟看懂 `apps/Android/MnnLlmChat` 是怎么把"端侧大模型"拼成一个聊天 App 的。

---

## 0. 一张图先建心智模型

```
用户在 UI 输入文字
        │
        ▼
[Kotlin 层]  ChatActivity (聊天页)
              ├─ ChatInputComponent     收输入框
              ├─ ChatListComponent      管聊天 RecyclerView（消息列表）
              └─ ChatPresenter          业务编排（线程、历史、状态）
                     │
                     ▼
              LlmSession.kt             外观对象（持 nativePtr）
                     │  ↓ JNI（external fun submitNative）
                     ▼
[C++ 层]      llm_mnn_jni.cpp           JNI 胶水
                     │
                     ▼
              mls::LlmSession           调 MNN 引擎做推理
                     │
                     ▼
              libMNN.so / libMNN_CL.so  ←★ 你之前编出的核心库
```

**Python 类比**：
- Activity ≈ 一个 Flask 路由处理一个页面
- Fragment ≈ 一个可复用的 UI 组件（有自己生命周期，挂在 Activity 里）
- ViewBinding ≈ Jinja 模板里的变量绑定（XML 写好的控件自动变成 Kotlin 字段）
- JNI（`external fun`）≈ Python 的 `ctypes` / `pybind11`

---

## 1. 入口与启动顺序

`AndroidManifest.xml` 里有两个 `<activity>` 标了 `LAUNCHER`：

```xml
<activity android:name=".main.MainActivity">                  <!-- 主页 -->
<activity android:name="com.alibaba.mnnllm.android.chat.ChatActivity">  <!-- 聊天页 -->
```

实际启动流程：
1. **MnnLlmApplication**（`android:name=".MnnLlmApplication"`）启动，初始化全局单例
2. **MainActivity**：模型市场 / 本地模型 / Benchmark 三个 Tab，左侧抽屉是历史会话
3. 用户点某个模型 → `RouterUtils.startActivity` 跳到 **ChatActivity**，把 `modelId / modelName / configFilePath` 通过 Intent 传过去
4. ChatActivity 加载 `libMNN.so` + 该模型的权重，进入聊天界面

**Python 类比**：MainActivity 像 "首页路由"，ChatActivity 像 "/chat/<model_id>" 详情路由，Intent 就是 URL query 参数。

---

## 2. 模块全景（按职责）

```
app/src/main/java/com/alibaba/mnnllm/android/
├── main/                ← 主入口三大 Tab + Toolbar + 抽屉
│   ├── MainActivity.kt          611 行，主框架
│   ├── MainFragmentsManager.kt  Fragment 切换逻辑（参考下一节）
│   └── FilterComponent.kt       搜索/筛选条
├── modelist/            ← 「本地模型」Tab：已下载模型列表
├── modelmarket/         ← 「模型市场」Tab：远端模型 + 一键下载
├── benchmark/           ← 「跑分」Tab
├── history/             ← 历史会话（左侧抽屉里那个）
├── chat/                ← ★ 聊天页主战场
│   ├── ChatActivity.kt          1054 行，UI 容器
│   ├── ChatPresenter.kt         537 行，业务编排
│   ├── chatlist/                聊天消息列表 RecyclerView 相关
│   ├── input/                   输入框组件（文字/图片/语音）
│   ├── voice/                   语音对话模式
│   └── model/                   ChatDataItem 等数据类
├── llm/                 ← ★ Kotlin 层的 MNN 包装
│   ├── ChatSession.kt           interface（统一抽象）
│   ├── LlmSession.kt            ★ 标准 LLM session，持 nativePtr
│   ├── DiffusionSession.kt      文生图
│   ├── SanaSession.kt           Sana 文生图变体
│   └── ChatService.kt           session 的工厂 + 缓存
├── modelsettings/       ← 推理参数设置（max tokens, system prompt 等）
├── audio/  asr/         ← 麦克风录音 + 语音识别
├── download/  mainsettings/  privacy/  update/  widgets/  utils/
└── api/openai/          ← 内置一个 OpenAI 兼容的 HTTP server（手机能当 API 用！）

cpp/                     ← C++ 层
├── llm_mnn_jni.cpp      ★ Java↔C++ 桥（initNative / submitNative / releaseNative）
├── llm_session.cpp/.h   ★ 实际调 MNN-LLM 引擎
├── diffusion_jni.cpp + diffusion_session.cpp/.h    文生图链路
├── sana_jni.cpp + sana_session.cpp/.h              Sana 文生图
└── CMakeLists.txt       告诉 gradle 怎么编上面这些
```

---

## 3. 主页 UI：一个 XML 看懂结构

`res/layout/activity_main.xml`（109 行，已极简化）：

```xml
<FullScreenDrawerLayout>                      <!-- ① 总容器：可侧滑出抽屉 -->
    <CoordinatorLayout>                       <!-- 主区域 -->
        <AppBarLayout>
            <MaterialToolbar>                 <!-- ② 顶部标题栏 -->
                <ModelSwitcherView/>          <!--    显示当前模型，可下拉切换 -->
            </MaterialToolbar>
        </AppBarLayout>

        <FrameLayout id="main_fragment_container"/>   <!-- ③ 三 Tab 内容区 -->

        <BottomTabBar id="bottom_navigation"/>        <!-- ④ 底部 Tab：本地/市场/Benchmark -->

        <ExpandableFabLayout>                          <!-- ⑤ 右下角浮动按钮：GitHub/反馈/添加本地模型 -->
            <FabOption android:onClick="onStarProject"/>
            <FabOption android:onClick="onReportIssue"/>
            <FabOption android:onClick="addLocalModels"/>
        </ExpandableFabLayout>
    </CoordinatorLayout>

    <NavigationView>                          <!-- ⑥ 抽屉：历史会话 -->
        <FrameLayout id="history_fragment_container"/>
    </NavigationView>
</FullScreenDrawerLayout>
```

可以直接对应到截图：
- ② 顶部那个能下拉的"模型名" → `ModelSwitcherView`
- ③ 中间内容 → 由 `MainFragmentsManager` 切换的 3 个 Fragment
- ④ 底部 3 个 Tab 图标 → `BottomTabBar`
- ⑥ 从屏幕左边缘划出来 → `NavigationView`，里面塞 `ChatHistoryFragment`

> **Android 布局 = HTML**。XML 里的标签是 View 树，`android:id="@+id/xxx"` 就是给元素起 id，Kotlin 里通过 `binding.xxx` 访问，类似 JS `document.getElementById`。

---

## 4. 三 Tab 怎么切换：`MainFragmentsManager.kt`

新手最容易困惑：**三个 Tab 之间怎么共存？是销毁重建吗？**
答案：**全部 add 进同一个容器，用 hide/show 切换（保留状态，性能好）**。

```kotlin
// 简化版 (MainFragmentsManager.kt:44-54)
fun initialize(savedInstanceState: Bundle?) {
    if (savedInstanceState == null) {
        modelListFragment   = ModelListFragment()
        modelMarketFragment = ModelMarketFragment()
        benchmarkFragment   = BenchmarkFragment()

        activity.supportFragmentManager.beginTransaction()
            .add(containerId, benchmarkFragment!!,   TAG_BENCHMARK).hide(benchmarkFragment!!)
            .add(containerId, modelMarketFragment!!, TAG_MARKET   ).hide(modelMarketFragment!!)
            .add(containerId, modelListFragment!!,   TAG_LIST     )   // 默认显示
            .commit()

        activeFragment = modelListFragment
    }
}

private fun switchFragment(targetFragment: Fragment) {
    activity.supportFragmentManager.beginTransaction()
        .hide(activeFragment!!)
        .show(targetFragment)
        .commitNow()
    activeFragment = targetFragment
}
```

**Python 类比**：像 Streamlit 用 `st.tabs()`，但只有当前 tab 渲染。
**Kotlin 这里更像** Vue 的 `<keep-alive>`：组件没卸载，只是隐藏，state 全在。

---

## 5. 聊天页：`ChatActivity` + 三个 Component

### 5.1 布局（极简 `activity_chat.xml`）

```xml
<CoordinatorLayout>
    <AppBarLayout><MaterialToolbar><ModelSwitcherView/></MaterialToolbar></AppBarLayout>

    <RecyclerView id="recyclerView"/>             <!-- 聊天消息列表（核心） -->
    <include id="empty_view" layout="@layout/chat_layout_empty_view"/>

    <LinearLayout id="layout_bottom_container">   <!-- 底部输入区 -->
        <MaterialButton id="btn_toggle_audio_output"/>
        <MaterialCardView id="input_card_container">
            <RecyclerView id="image_preview_recycler"/>  <!-- 图片预览 -->
            <EditText/>                                  <!-- 文字输入 -->
            <ImageButton/>                               <!-- 发送/录音/停止 -->
        </MaterialCardView>
    </LinearLayout>
</CoordinatorLayout>
```

### 5.2 ChatActivity = "粘合剂"

`ChatActivity.kt:111-` 的 `onCreate`：
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    binding = ActivityChatBinding.inflate(layoutInflater)   // 读 XML 出 binding
    setContentView(binding.root)
    setSupportActionBar(binding.toolbar)

    this.modelName = intent.getStringExtra("modelName") ?: ""
    this.modelId   = intent.getStringExtra("modelId")
    // ...
    chatPresenter = ChatPresenter(this, modelName, modelId!!)
    chatListComponent  = ChatListComponent(this, ...)
    chatInputModule    = ChatInputComponent(this, ...)
    chatPresenter.createSession()
}
```

它不直接做业务，只把三个 Component 串起来：
| 组件 | 文件 | 干啥 |
|------|------|------|
| ChatListComponent | `chat/chatlist/ChatListComponent.kt` | 聊天列表的 RecyclerView 适配器、滚动到底、空状态 |
| ChatInputComponent | `chat/input/...` | 输入框、附件上传、录音按钮 |
| ChatPresenter | `chat/ChatPresenter.kt` | 业务编排：建 session、调推理、流式更新、写入数据库 |

> 这是 MVP 模式的简化版：**View（Activity+Component）** 只负责 UI，**Presenter** 负责逻辑。和 React 里 component / hook 的分层类似。

### 5.3 ChatPresenter 的关键路径

```kotlin
// ChatPresenter.kt:88+
fun createSession(): ChatSession {
    val sessionId   = intent.getStringExtra("chatSessionId")           // 老会话 or null
    val historyList = chatDataManager?.getChatDataBySession(sessionId) // 从 SQLite 拉历史
    val configPath  = intent.getStringExtra("configFilePath")          // 模型 config.json

    chatSession = if (isDiffusion) {
        ChatService.provide().createSession(...)         // 文生图分支
    } else {
        ServiceLocator.getLlmRuntimeController().ensureSession(...)   // 标准 LLM 分支
    }
    return chatSession
}
```

提交一次推理（伪代码）：
```kotlin
presenterScope.launch(Dispatchers.IO) {           // 协程：在 IO 线程跑，避免卡 UI
    val result = chatSession.generate(prompt, params, listener)
    withContext(Dispatchers.Main) {
        chatActivity.chatListComponent.updateAssistantResponse(...)
    }
}
```

**Python 类比**：`Dispatchers.IO` ≈ asyncio 的执行池；`withContext(Main)` ≈ 把结果切回主线程更新 UI。

---

## 6. ★ 最关键的桥：Kotlin → C++ → MNN 引擎

### 6.1 Kotlin 端：声明 native 函数

`llm/LlmSession.kt:227-245`：
```kotlin
class LlmSession(...) : ChatSession {
    private var nativePtr: Long = 0     // C++ 对象的指针（伪装成 Long 跨 JNI）

    override fun load() {
        nativePtr = initNative(configPath, history, mergedConfig, configJson)
    }

    override fun generate(prompt: String, params, listener): HashMap<String, Any> {
        return submitNative(nativePtr, prompt, keepHistory, listener)
    }

    override fun release() { releaseNative(nativePtr); nativePtr = 0 }

    // ↓ external 表示"这函数在 .so 里"
    private external fun initNative(configPath: String?, history: List<String>?, ...): Long
    private external fun submitNative(instanceId: Long, input: String, keepHistory: Boolean,
                                      listener: GenerateProgressListener): HashMap<String, Any>
    private external fun resetNative(instanceId: Long)
    private external fun releaseNative(instanceId: Long)
}
```

### 6.2 C++ 端：函数名按规则匹配

`cpp/llm_mnn_jni.cpp:144-`：
```cpp
// JVM 自动按 Java_<package_with_underscores>_<className>_<methodName> 找符号
JNIEXPORT jobject JNICALL
Java_com_alibaba_mnnllm_android_llm_LlmSession_submitNative(
        JNIEnv *env, jobject thiz,
        jlong llmPtr, jstring inputStr, jboolean keepHistory, jobject progressListener) {

    auto *llm = reinterpret_cast<mls::LlmSession *>(llmPtr);   // ① Long 还原成 C++ 指针
    const char *input = env->GetStringUTFChars(inputStr, nullptr);

    // ② 注册"流式回调"：每生成一个 token 就回调 Kotlin 的 onProgress
    auto *context = llm->Response(input,
        [&](const std::string &response, bool is_eop) {
            jstring js = is_eop ? nullptr : env->NewStringUTF(response.c_str());
            jboolean stop = env->CallBooleanMethod(progressListener, onProgressMethod, js);
            return (bool) stop;
        });

    // ③ 把 prompt_len / decode_len / prefill_us 等指标塞进 HashMap 返回 Kotlin
    jobject hashMap = env->NewObject(hashMapClass, hashMapInit);
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("prompt_len"), ...);
    return hashMap;
}
```

**理解要点**：
1. `nativePtr` 是 **Kotlin 端持有 C++ 对象**的方式：C++ 创建对象后把指针转成 `jlong` 返回，之后 Kotlin 每次调 native 都把这个 long 传回去，C++ `reinterpret_cast` 还原成对象指针。这是 JNI 通用模式。
2. **流式输出**：C++ 不是"算完整段返回"，而是每生成一个 token 就调一次 Kotlin 的 `onProgress(text)`。Kotlin 里这个回调把文本追加到 RecyclerView 最后一条消息。
3. **谁加载 .so**：`MnnLlmApplication.onCreate` 或 `LlmSession` 的伴生对象里 `System.loadLibrary("mnnllmapp")`，最终从 `app/build/.../jniLibs/arm64-v8a/libmnnllmapp.so` 加载。`libMNN.so` 也在那里。

### 6.3 .so 是哪来的？

回到[03 文档](03_MNN核心库与MnnLlmChat编译部署流程.md)的第 1 步——`make install` 把 `libMNN.so` 装到 `project/android/build_64/install/lib/`。
然后 `app/src/main/cpp/CMakeLists.txt` 用 `-L<那个目录>` 把它链接进 `libmnnllmapp.so`。
最后 gradle 把这两个 `.so` 一起打进 APK。

**少了 `make install` → APK 编不出来的根因**就是这条链断了。

---

## 7. 一次完整对话的时序

```
[用户] 在输入框敲字 → 点发送
        ↓
ChatInputComponent.onSendClick(text)
        ↓
ChatActivity.sendMessage(text)
        ↓
ChatPresenter.requestGenerate(text)        // 协程 launch(Dispatchers.IO)
        ↓
LlmSession.generate(prompt, params, listener)
        ↓ JNI
submitNative(nativePtr, prompt, ..., listener)
        ↓ C++
mls::LlmSession::Response(input, callback)
        ↓ MNN 引擎逐 token 推理
每生成一个 token：
   C++ callback(token, false)
        ↓ JNI 回调
   Kotlin GenerateProgressListener.onProgress(token)
        ↓
   ChatListComponent.updateAssistantResponse(stream_text)
        ↓
   RecyclerView 最后一项的 TextView 文本追加 → 屏幕看到流式打字效果

推理结束：
   C++ callback("", true)            // is_eop = true
   submitNative 返回 HashMap{prompt_len, decode_len, prefill_us, decode_us}
        ↓
   Kotlin 把指标显示在消息底部（"X tok/s, prefill Yms"）
   写入 SQLite 历史
```

如果你能在脑子里把这条链跑通，**这个 App 你已经懂 80% 了**。

---

## 8. 新手按"想动哪儿就读哪儿"导航

| 你想干什么 | 改 / 看哪个文件 |
|----|----|
| 改聊天页底部输入框样式 | `res/layout/activity_chat.xml` 的 `input_card_container` |
| 改"发送"按钮点击逻辑 | `chat/input/ChatInputComponent.kt` |
| 改消息气泡样式 | `res/layout/item_chat_*.xml` + `chat/chatlist/ChatViewHolders.kt` |
| 改主页 Tab 数量 | `widgets/BottomTabBar.kt` + `main/MainFragmentsManager.kt` |
| 加一个新模型类型（非 LLM/Diffusion） | 实现 `llm/ChatSession.kt` 接口 + 注册到 `ChatService.kt` |
| 改推理参数（temperature 等） | `modelsettings/ModelConfig.kt` + 设置面板的 XML |
| 改 JNI 接口 | Kotlin 加 `external fun xxx`，C++ 写 `Java_..._xxx`，重编 `app:configureCMakeDebug` |
| 加内置 HTTP API | `api/openai/` 整个模块（自带 OpenAI 兼容接口！） |

---

## 9. 一句话总结

MnnLlmChat 是一个 **"标准 Android Material 三件套（Toolbar + DrawerLayout + BottomNav） + 聊天 RecyclerView + JNI 调 MNN 引擎"** 的应用。
所有"端侧大模型"的魔法都在 `LlmSession.kt` ↔ `llm_mnn_jni.cpp` ↔ `libMNN.so` 这条链上；
其余 90% 的代码都是**普通 Android UI**——用 Python 工程师视角看：模板（XML）+ 控制器（Activity）+ 业务（Presenter）+ 服务（Session/JNI）。
