# SPDX-License-Identifier: GPL-3.0-only
#
# TaffyNiHe - tools/unidbg-unicorn-bridge/CMakeLists.txt
# Copyright (C) 2026 TaffyNiHe authors
#
# 把 unidbg 的 unicorn2 **JNI 桥** 和（静态构建的）unicorn 引擎链接成一个
# libunicorn.so —— 这是 APK 里唯一合法的 libunicorn.so 形态。
#
# 背景（上游 issue #91 同坑）：unidbg 的 com.github.unidbg.arm.backend.unicorn.Unicorn
# 是纯 JNI 绑定，入口符号形如 Java_com_github_unidbg_arm_backend_unicorn_Unicorn_*。
# 只把裸引擎当 libunicorn.so 打包 → System.loadLibrary 成功、后端看起来"可用"，
# 但 session_open 时绑定失败（BackendFactory 吞异常后回退 legacy UnicornBackend →
# NoClassDefFoundError: unicorn.Unicorn）。
#
# 为什么用 CMake 把两者放一起而不是手工 clang：
#   unicorn 的 STATIC 目标只含通用对象（uc.c/qemu/*），各架构后端在独立的
#   lib<arch>-softmmu.a 里，靠 INTERFACE_LINK_LIBRARIES 传递（含 armeb/aarch64eb 的
#   正确链接顺序）。手工拼接很容易漏库或重复符号，交给 CMake 解析依赖最稳。
#
# 用法（由 scripts/build-unidbg-native.sh 调用）：
#   cmake -S tools/unidbg-unicorn-bridge -B <build> \
#         -DCMAKE_TOOLCHAIN_FILE=<ndk>/build/cmake/android.toolchain.cmake \
#         -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
#         -DUNICORN_SRC=<repo>/third_party/unicorn-zhkl0228
#   产物：<build>/libunicorn.so
cmake_minimum_required(VERSION 3.22)
project(unidbg_unicorn2_bridge C)

# unicorn 的 CMakeLists 仍写着 cmake_minimum_required(VERSION 3.1)，CMake >= 4.0 会直接
# 报 "Compatibility with CMake < 3.5 has been removed"。在 add_subdirectory 之前把这个
# 策略下限塞进 cache，本地用 CMake 4（pip 装的）也能一条命令跑通。
if(CMAKE_VERSION VERSION_GREATER_EQUAL 4.0)
    set(CMAKE_POLICY_VERSION_MINIMUM 3.5 CACHE STRING "" FORCE)
endif()

if(NOT UNICORN_SRC)
    message(FATAL_ERROR "必须指定 -DUNICORN_SRC=<path to zhkl0228/unicorn source tree>")
endif()
if(NOT EXISTS "${UNICORN_SRC}/CMakeLists.txt")
    message(FATAL_ERROR "UNICORN_SRC 里没有 CMakeLists.txt: ${UNICORN_SRC}")
endif()

if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/unicorn.c")
    message(FATAL_ERROR
        "缺少 unidbg 的 JNI 桥源码 unicorn.c —— 由 scripts/build-unidbg-native.sh 从 "
        "third_party/unidbg-src/backend/unicorn2/src/main/native/ 复制过来")
endif()

# 引擎侧：静态库 + 只编 arm/aarch64（注意列表分隔符是分号/空格，逗号不是分隔符，
# 之前的 -DUNICORN_ARCH=arm,aarch64 会被当成单一 arch 名而什么都不编）。
# zhkl0228/unicorn 有两条分支：
#   unicorn2 —— unidbg unicorn2 后端对应分支，含 uc_ctl_set_cpu_model /
#               uc_ctl_remove_cache / UC_CPU_ARM64_MAX（unidbg 0.9.9 的 JNI 桥要用），
#               CMake 用 BUILD_SHARED_LIBS 控制静态/共享；
#   master   —— 1.0.x 通用版，**已移除**上述 uc_ctl_* API，桥编译不过（别用）。
# 两边都强制成静态库，避免出现"同名 libunicorn.so"混淆。
set(UNICORN_ARCH "arm;aarch64" CACHE STRING "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(UNICORN_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(UNICORN_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(UNICORN_BUILD_SAMPLES OFF CACHE BOOL "" FORCE)
set(UNICORN_INSTALL OFF CACHE BOOL "" FORCE)
set(UNICORN_TRACER OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_subdirectory("${UNICORN_SRC}" "${CMAKE_BINARY_DIR}/unicorn" EXCLUDE_FROM_ALL)

add_library(unicorn_jni SHARED
    "${CMAKE_CURRENT_SOURCE_DIR}/unicorn.c"
    "${CMAKE_CURRENT_SOURCE_DIR}/sample_arm.c"
    "${CMAKE_CURRENT_SOURCE_DIR}/sample_arm64.c"
)
set_target_properties(unicorn_jni PROPERTIES OUTPUT_NAME unicorn)
target_include_directories(unicorn_jni PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"          # jni 桥自带头 + khash.h
    "${UNICORN_SRC}/include"               # <unicorn/unicorn.h>
)
# JNI 头：Android 下在 NDK sysroot 里（自动可见）；桌面/本地验证时靠 JAVA_HOME 找。
find_package(JNI QUIET)
if(JNI_INCLUDE_DIRS)
    target_include_directories(unicorn_jni PRIVATE ${JNI_INCLUDE_DIRS})
elseif(NOT ANDROID)
    message(WARNING "没找到 JNI 头（设置 JAVA_HOME 或 -DJAVA_HOME），jni.h 可能解析失败")
endif()

if(ANDROID)
    target_compile_definitions(unicorn_jni PRIVATE __ANDROID__)
    target_link_libraries(unicorn_jni PRIVATE log)
endif()
target_link_libraries(unicorn_jni PRIVATE unicorn m)
set_target_properties(unicorn_jni PROPERTIES LINK_FLAGS "-Wl,-z,now")
