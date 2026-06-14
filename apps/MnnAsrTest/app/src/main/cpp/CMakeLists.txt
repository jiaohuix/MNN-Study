cmake_minimum_required(VERSION 3.22.1)
project("asrtest_native")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(JNILIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

# 三个预编译 .so 都声明为 IMPORTED，然后让 asrtest_shim 全部 link 上：
# 这样 asrtest_shim.so 的 DT_NEEDED 里就会有 libMNN_Express.so / libMNN.so / libsherpa-mnn-jni.so，
# Android linker 在 dlopen("asrtest_shim") 时会沿 NEEDED 链把它们全部拉进主命名空间。
#
# 必须这么干的原因：libsherpa-mnn-jni.so 自己 DT_NEEDED 只有 libMNN.so，但实际用到了
# MNN::Express::Module::load —— 这个符号在 libMNN_Express.so 里。Android 7+ 的 linker
# 是 RTLD_LOCAL + namespace 隔离，单独 System.loadLibrary("MNN_Express") 不会让 sherpa
# 看到符号；patchelf --add-needed 改 sherpa 那个 .so 又会破坏 GNU_HASH。让 shim 显式
# NEEDED 是当前最稳的方案。
add_library(mnn SHARED IMPORTED)
set_target_properties(mnn PROPERTIES IMPORTED_LOCATION "${JNILIBS_DIR}/libMNN.so")

add_library(mnn_express SHARED IMPORTED)
set_target_properties(mnn_express PROPERTIES IMPORTED_LOCATION "${JNILIBS_DIR}/libMNN_Express.so")

add_library(sherpa_mnn SHARED IMPORTED)
set_target_properties(sherpa_mnn PROPERTIES IMPORTED_LOCATION "${JNILIBS_DIR}/libsherpa-mnn-jni.so")

add_library(asrtest_shim SHARED asrtest_shim.cpp)
target_link_libraries(asrtest_shim log mnn mnn_express sherpa_mnn)
