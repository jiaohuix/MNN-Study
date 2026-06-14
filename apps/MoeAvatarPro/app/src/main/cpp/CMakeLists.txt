cmake_minimum_required(VERSION 3.22.1)
project("moeavatar_native")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Repo root: app/src/main/cpp -> 7 hops up reaches /home/jhx/Projects/nlp/MNN
set(MNN_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../../")
set(LIB_PATH "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

include_directories("${MNN_REPO_ROOT}/include")
include_directories("${MNN_REPO_ROOT}/transformers/llm/engine/include")

# Single libMNN.so / libMNN_Express.so (NDK 28 build) shared by all sub-projects.
add_library(MNN SHARED IMPORTED)
set_target_properties(MNN PROPERTIES IMPORTED_LOCATION "${LIB_PATH}/libMNN.so")

add_library(MNN_Express SHARED IMPORTED)
set_target_properties(MNN_Express PROPERTIES IMPORTED_LOCATION "${LIB_PATH}/libMNN_Express.so")

# Prebuilt libllm.so (MNN::Transformer::Llm) — built together with libMNN.so via
# .tmp_mnn_build_llm_sep with MNN_BUILD_LLM=ON + MNN_SEP_BUILD=ON.
add_library(llm SHARED IMPORTED)
set_target_properties(llm PROPERTIES IMPORTED_LOCATION "${LIB_PATH}/libllm.so")

# bertvits2 TTS native bridge -> libbertvits2.so
add_subdirectory(bertvits2)

# Local LLM JNI bridge -> libmoeavatar_llm.so (links libllm.so for createLLM).
option(MOE_ENABLE_LOCAL_LLM "Build the local LLM JNI bridge" ON)
if(MOE_ENABLE_LOCAL_LLM)
    add_library(moeavatar_llm SHARED moeavatar_llm_jni.cpp)
    target_link_options(moeavatar_llm PRIVATE
            "-Wl,-z,max-page-size=16384"
            "-Wl,-z,common-page-size=16384")
    target_link_libraries(moeavatar_llm android log llm MNN MNN_Express)
endif()
