cmake_minimum_required(VERSION 3.15 FATAL_ERROR)

# opt in to the new IN_LIST semantics (policy CMP0057)
if(POLICY CMP0057)
    cmake_policy(SET CMP0057 NEW)
endif()

set(CMAKE_TOOLCHAIN_FILE "${ANDROID_NDK}/build/cmake/android.toolchain.cmake")
if(NOT DEFINED ANDROID_ABI)
    set(ANDROID_ABI arm64-v8a)
endif()
message(STATUS "ANDROID_ABI = ${ANDROID_ABI}")
set(ANDROID_PLATFORM android-29)

project(sentencepiece-jni)

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include(ExternalProject)

set(sentencepiece_STATIC_LIBRARIES ${CMAKE_CURRENT_BINARY_DIR}/sentencepiece/src/sentencepiece-build/src/libsentencepiece.a)

ExternalProject_Add(sentencepiece
        PREFIX    ${CMAKE_CURRENT_BINARY_DIR}/sentencepiece
        GIT_REPOSITORY https://github.com/google/sentencepiece.git
        GIT_TAG         v0.2.0
        INSTALL_COMMAND ""
        BUILD_BYPRODUCTS ${sentencepiece_STATIC_LIBRARIES}

        CMAKE_ARGS
        -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
        -DANDROID_ABI=${ANDROID_ABI}
        -DANDROID_PLATFORM=${ANDROID_PLATFORM}
        -DCMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE}
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON

        # —— HERE ARE THE MAGIC FLAGS ——
        -DCMAKE_POLICY_DEFAULT_CMP0057=NEW
        -DBUILD_SHARED_LIBS=OFF
        -DSENTENCEPIECE_BUILD_SHARED=OFF
        -DSENTENCEPIECE_BUILD_STATIC=ON
        -DSENTENCEPIECE_BUILD_TRAINER=OFF
)


add_library(sentencepiece_jni SHARED sentencepiece_jni.cc)
add_dependencies(sentencepiece_jni sentencepiece)

target_include_directories(sentencepiece_jni PUBLIC
        ${ANDROID_NDK}/sysroot/usr/include
        ${ANDROID_NDK}/sysroot/usr/include/android
        ${ANDROID_NDK}/sysroot/usr/include/jni
        ${CMAKE_CURRENT_BINARY_DIR}/sentencepiece/src/sentencepiece/src
)

target_link_libraries(sentencepiece_jni
        ${sentencepiece_STATIC_LIBRARIES}
        log
)
