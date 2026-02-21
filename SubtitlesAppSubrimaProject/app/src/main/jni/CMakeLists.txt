cmake_minimum_required(VERSION 3.10)

# -----------------------------------------------------------------------------
# Project Definition
# -----------------------------------------------------------------------------
# Defines the main project for native transcription functionality.
# This project is responsible for building native libraries for audio
# transcription on Android, including integration with whisper.cpp and
# SentencePiece submodules.
project(native_transcription)

# -----------------------------------------------------------------------------
# C++ Standard and Position-Independent Code
# -----------------------------------------------------------------------------
# Sets the C++ standard to C++17 for all targets.
# Enables position-independent code (PIC) for building shared libraries
# compatible with Android.
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# -----------------------------------------------------------------------------
# Android NDK Toolchain Configuration
# -----------------------------------------------------------------------------
# Specifies the CMake toolchain file for cross-compilation targeting Android.
# Sets the minimum Android platform API version required (android-29).
# Defines the Android ABI for compilation; defaults to arm64-v8a if not defined.
set(CMAKE_TOOLCHAIN_FILE "${ANDROID_NDK}/build/cmake/android.toolchain.cmake")
set(ANDROID_PLATFORM android-29)
if(NOT DEFINED ANDROID_ABI)
    set(ANDROID_ABI arm64-v8a)
endif()

# -----------------------------------------------------------------------------
# Subproject Inclusion
# -----------------------------------------------------------------------------
# Adds subdirectories for building the whisper.cpp and sentencepiece libraries.
# These subprojects are treated as part of the overall build and will be
# compiled along with the native_transcription project.
add_subdirectory(whisper)
add_subdirectory(sentencepiece)
