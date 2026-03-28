cmake_minimum_required(VERSION 3.10)

# -----------------------------------------------------------------------------
# Project Definition
# -----------------------------------------------------------------------------
# Defines the project name and prepares CMake for compiling C++17 code.
# This project is responsible for building the whisper.cpp library and
# integrating the custom JNI/C bindings for Android.
project(whisper.cpp)

# Set C++ standard to C++17 for all targets
set(CMAKE_CXX_STANDARD 17)

# Find Git once so native dependency checkout can happen during configure.
find_package(Git QUIET)

# -----------------------------------------------------------------------------
# Whisper.cpp Source Resolution
# -----------------------------------------------------------------------------
# The native build supports two source locations for whisper.cpp:
#
# 1. Preferred local override:
#    ../../../../../whisper_cpp
#    This keeps compatibility with an existing manual checkout.
#
# 2. Automatic fallback:
#    If the local checkout does not exist, CMake performs a shallow clone of a
#    pinned upstream whisper.cpp tag into a short cache path under Local AppData.
#
# This removes the need for developers to clone whisper.cpp by hand before
# opening or building the Android project.
set(WHISPER_LOCAL_DIR "${CMAKE_SOURCE_DIR}/../../../../../whisper_cpp")
set(WHISPER_FETCH_TAG "v1.8.4")
set(WHISPER_FETCH_REPOSITORY "https://github.com/ggerganov/whisper.cpp.git")

if (DEFINED ENV{LOCALAPPDATA} AND NOT "$ENV{LOCALAPPDATA}" STREQUAL "")
    file(TO_CMAKE_PATH "$ENV{LOCALAPPDATA}" WHISPER_CACHE_BASE)
else()
    set(WHISPER_CACHE_BASE "${CMAKE_SOURCE_DIR}/.native_deps")
endif()

set(WHISPER_CACHE_ROOT "${WHISPER_CACHE_BASE}/SubrimaNativeDeps")
set(WHISPER_AUTO_DIR "${WHISPER_CACHE_ROOT}/whispercpp")

# -----------------------------------------------------------------------------
# Function: ensure_whisper_checkout
# -----------------------------------------------------------------------------
# Guarantees that a usable whisper.cpp checkout exists in a short cache path.
#
# Why a custom checkout flow instead of FetchContent:
# - Android Studio configures native builds inside deep .cxx directories.
# - FetchContent adds additional nested _deps paths.
# - On Windows this can exceed the 260-character legacy path limit.
#
# By cloning into Local AppData, the dependency path stays short and stable
# across ABI builds, avoiding the failing Ninja stamp-file path.
function(ensure_whisper_checkout repository tag destination)
    if (NOT GIT_FOUND)
        message(FATAL_ERROR
                "Git was not found. Install Git or place a local whisper.cpp checkout at ${WHISPER_LOCAL_DIR}")
    endif()

    get_filename_component(destination_parent "${destination}" DIRECTORY)
    file(MAKE_DIRECTORY "${destination_parent}")

    # Serialize clone/update work so parallel ABI configuration does not race.
    file(LOCK "${destination_parent}/whispercpp.lock" GUARD FUNCTION TIMEOUT 300)

    if (EXISTS "${destination}/src/whisper.cpp" AND EXISTS "${destination}/CMakeLists.txt")
        message(STATUS "Using cached whisper.cpp checkout: ${destination}")
        return()
    endif()

    if (EXISTS "${destination}")
        execute_process(
                COMMAND "${CMAKE_COMMAND}" -E rm -rf "${destination}"
                RESULT_VARIABLE remove_result
        )
        if (NOT remove_result EQUAL 0)
            message(FATAL_ERROR "Failed to remove incomplete whisper.cpp cache at: ${destination}")
        endif()
    endif()

    message(STATUS "Cloning whisper.cpp ${tag} into cache: ${destination}")

    execute_process(
            COMMAND "${GIT_EXECUTABLE}" -c core.longpaths=true clone --depth 1 --branch "${tag}" "${repository}" "${destination}"
            RESULT_VARIABLE clone_result
            OUTPUT_VARIABLE clone_output
            ERROR_VARIABLE clone_error
    )

    if (NOT clone_result EQUAL 0)
        message(FATAL_ERROR
                "Failed to clone whisper.cpp ${tag}.\n"
                "Repository: ${repository}\n"
                "Destination: ${destination}\n"
                "Git error: ${clone_error}")
    endif()
endfunction()

if (EXISTS "${WHISPER_LOCAL_DIR}/CMakeLists.txt")
    set(WHISPER_LIB_DIR "${WHISPER_LOCAL_DIR}")
    message(STATUS "Using local whisper.cpp checkout: ${WHISPER_LIB_DIR}")
else()
    message(STATUS "Local whisper.cpp checkout not found. Downloading ${WHISPER_FETCH_TAG} automatically.")
    ensure_whisper_checkout("${WHISPER_FETCH_REPOSITORY}" "${WHISPER_FETCH_TAG}" "${WHISPER_AUTO_DIR}")
    set(WHISPER_LIB_DIR "${WHISPER_AUTO_DIR}")
    message(STATUS "Using cached whisper.cpp sources: ${WHISPER_LIB_DIR}")
endif()

if (NOT EXISTS "${WHISPER_LIB_DIR}/src/whisper.cpp")
    message(FATAL_ERROR "whisper.cpp sources were not found at: ${WHISPER_LIB_DIR}")
endif()

# -----------------------------------------------------------------------------
# Whisper.cpp Version Detection
# -----------------------------------------------------------------------------
# Reads the main CMakeLists.txt of whisper.cpp to extract the library version.
# This version is then made available to dependent targets via PARENT_SCOPE.
file(READ "${WHISPER_LIB_DIR}/CMakeLists.txt" MAIN_CMAKE_CONTENT)
string(REGEX MATCH "project\\(\"whisper\\.cpp\" VERSION ([0-9]+\\.[0-9]+\\.[0-9]+)\\)" VERSION_MATCH "${MAIN_CMAKE_CONTENT}")
if(CMAKE_MATCH_1)
    set(WHISPER_VERSION ${CMAKE_MATCH_1} PARENT_SCOPE)
else()
    set(WHISPER_VERSION "unknown" PARENT_SCOPE)
endif()

message(STATUS " Whisper version: ${WHISPER_VERSION}")

# -----------------------------------------------------------------------------
# Source Files
# -----------------------------------------------------------------------------
# Defines the source files to be compiled for the library, including
# the main whisper.cpp source and JNI/C interface files.
set(SOURCE_FILES
    ${WHISPER_LIB_DIR}/src/whisper.cpp
    ${CMAKE_SOURCE_DIR}/whisper/jni.c
    )

# Locate Android log library for linking
find_library(LOG_LIB log)

# -----------------------------------------------------------------------------
# GGML Dependency
# -----------------------------------------------------------------------------
# ggml is consumed directly from the resolved whisper.cpp source tree.
# This avoids FetchContent subbuild path expansion on Windows.
if (NOT TARGET ggml)
    add_subdirectory("${WHISPER_LIB_DIR}/ggml" "${CMAKE_BINARY_DIR}/ggml-build")
endif()

# -----------------------------------------------------------------------------
# Function: build_library
# -----------------------------------------------------------------------------
# Encapsulates the steps required to build a shared library target for whisper.
# - Adds the library target
# - Includes ggml as a dependency using FetchContent
# - Links to Android system libraries and log
# - Defines preprocessor macros and compile options
# - Sets architecture-specific compiler flags
# - Applies optimization and visibility flags for non-Debug builds
function(build_library target_name)
    # Create shared library target
    add_library(
        ${target_name}
        SHARED
        ${SOURCE_FILES}
    )

    # Link libraries required for Android and whisper
    target_link_libraries(${target_name} ${LOG_LIB} android ggml)
    # Define preprocessor macros
    target_compile_definitions(${target_name} PUBLIC GGML_USE_CPU)
    target_compile_definitions(${target_name} PRIVATE WHISPER_VERSION="${WHISPER_VERSION}")

    # Set architecture-specific compiler options
    if (${target_name} STREQUAL "whisper_v8fp16_va")
        target_compile_options(${target_name} PRIVATE -march=armv8.2-a+fp16)
    elseif (${target_name} STREQUAL "whisper_vfpv4")
        target_compile_options(${target_name} PRIVATE -mfpu=neon-vfpv4)
    endif ()

    # Apply optimizations and hidden visibility flags for Release builds
    if (NOT ${CMAKE_BUILD_TYPE} STREQUAL "Debug")

        target_compile_options(${target_name} PRIVATE -O3)
        target_compile_options(${target_name} PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
        target_compile_options(${target_name} PRIVATE -ffunction-sections -fdata-sections)

        #target_link_options(${target_name} PRIVATE -Wl,--gc-sections)
        #target_link_options(${target_name} PRIVATE -Wl,--exclude-libs,ALL)
        #target_link_options(${target_name} PRIVATE -flto)
    endif ()
endfunction()

# -----------------------------------------------------------------------------
# Build Targets
# -----------------------------------------------------------------------------
# Build the default whisper library
build_library("whisper") # Default target

# Build architecture-specific optimized versions for Android ABIs
if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    build_library("whisper_v8fp16_va")
elseif (${ANDROID_ABI} STREQUAL "armeabi-v7a")
    build_library("whisper_vfpv4")
endif ()

# -----------------------------------------------------------------------------
# Include Directories
# -----------------------------------------------------------------------------
# Specifies directories to be searched for header files during compilation
# Includes whisper.cpp sources, ggml, and internal include paths.
include_directories(${WHISPER_LIB_DIR})
include_directories(${WHISPER_LIB_DIR}/src)
include_directories(${WHISPER_LIB_DIR}/include)
include_directories(${WHISPER_LIB_DIR}/ggml/include)
include_directories(${WHISPER_LIB_DIR}/ggml/src)
include_directories(${WHISPER_LIB_DIR}/ggml/src/ggml-cpu)
