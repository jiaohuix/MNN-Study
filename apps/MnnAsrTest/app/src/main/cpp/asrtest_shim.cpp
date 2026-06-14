#include <dlfcn.h>
#include <android/log.h>
#include <string>

// 在 asrtest_shim 被 dlopen 时自动执行：
// libsherpa-mnn-jni.so 用了 std::regex 但 DT_NEEDED 没写 libc++_shared。
// Android linker 只解析 DT_NEEDED 链上的符号，不会自动搜索命名空间内所有 .so。
// 这里用 RTLD_GLOBAL 把 libc++_shared 的符号设为全局可见，后续 sherpa-mnn-jni 就能找到。
__attribute__((constructor))
static void preload_cxx_shared() {
    void* h = dlopen("libc++_shared.so", RTLD_NOW | RTLD_GLOBAL);
    if (h) {
        __android_log_print(ANDROID_LOG_INFO, "AsrShim", "dlopen libc++_shared.so RTLD_GLOBAL ok");
    } else {
        __android_log_print(ANDROID_LOG_ERROR, "AsrShim", "dlopen libc++_shared.so failed: %s", dlerror());
    }
}

extern "C" int asrtest_shim_keep_alive() {
    std::string s = "asrtest";
    return static_cast<int>(s.size());
}
