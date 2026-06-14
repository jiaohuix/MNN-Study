cmake_minimum_required(VERSION 3.22.1)
project("bertvits2")
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# MNN headers + IMPORTED targets MNN / MNN_Express are declared by the parent
# CMakeLists.txt. We only need to add our own sources and link.

add_library(${PROJECT_NAME} SHARED
        bertvits2_jni.cpp
        bert_vits2_v23_loader.cpp
)

target_link_options(${PROJECT_NAME} PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384")

target_link_libraries(${PROJECT_NAME}
        android
        log
        MNN
        MNN_Express
)
