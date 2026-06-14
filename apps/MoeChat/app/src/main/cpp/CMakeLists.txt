cmake_minimum_required(VERSION 3.22.1)
project("moechat")

# Path to MNN repo: apps/Android/MoeChat/app/src/main/cpp -> repo root is 7 levels up
set(MNN_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../../")
set(MNN_INSTALL_ROOT "${MNN_REPO_ROOT}/project/android/build_64")
set(MNN_LIB_DIR "${MNN_INSTALL_ROOT}/lib")

message(STATUS "MoeChat MNN_REPO_ROOT=${MNN_REPO_ROOT}")
message(STATUS "MoeChat MNN_LIB_DIR=${MNN_LIB_DIR}")

include_directories("${MNN_REPO_ROOT}/include")
include_directories("${MNN_REPO_ROOT}/transformers/llm/engine/include")

# Imported prebuilt libMNN.so from project/android/build_64/lib
add_library(MNN SHARED IMPORTED)
set_target_properties(MNN PROPERTIES IMPORTED_LOCATION "${MNN_LIB_DIR}/libMNN.so")

add_library(${CMAKE_PROJECT_NAME} SHARED moechat_jni.cpp)

target_link_options(${CMAKE_PROJECT_NAME} PRIVATE "-Wl,-z,max-page-size=16384")

target_link_libraries(${CMAKE_PROJECT_NAME}
    android
    log
    MNN
)
