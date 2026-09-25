cmake_minimum_required(VERSION 3.22)

# Rizin + LIEF + Unidbg toolchain — Capstone/Keystone fully removed.
#
# Native backends:
#  1. Rizin (librz): disasm/asm/analysis/xrefs. Cross-compiled by meson per ABI.
#  2. LIEF (libLIEF): ELF parsing + section header reconstruction (xAnSo).
#     Cross-compiled by CMake per ABI into a static archive.
# Both are linked into a single librz_native.so that Kotlin loads.

set(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY CACHE STRING "" FORCE)
project(rz_native_core LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Map Android ABI to subdirectory names.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(RZ_ABI_DIR "arm64-v8a")
    set(LIEF_ABI_DIR "arm64-v8a")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(RZ_ABI_DIR "armeabi-v7a")
    set(LIEF_ABI_DIR "armeabi-v7a")
elseif(ANDROID_ABI STREQUAL "x86")
    set(RZ_ABI_DIR "x86")
    set(LIEF_ABI_DIR "x86")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(RZ_ABI_DIR "x86_64")
    set(LIEF_ABI_DIR "x86_64")
else()
    set(RZ_ABI_DIR "")
    set(LIEF_ABI_DIR "")
endif()

# --- Rizin build dir ---
set(RIZIN_BUILD_ROOT "" CACHE PATH "Root containing per-ABI Rizin meson build dirs")
if(NOT RIZIN_BUILD_ROOT)
    set(RIZIN_BUILD_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../rizin-build")
endif()
set(RIZIN_BUILD_DIR "${RIZIN_BUILD_ROOT}/${RZ_ABI_DIR}")
set(RIZIN_SHARED_SDK "${CMAKE_CURRENT_LIST_DIR}/../../../../rizin-sdk/${RZ_ABI_DIR}-wsl")

# --- LIEF build dir ---
set(LIEF_BUILD_ROOT "" CACHE PATH "Root containing per-ABI LIEF cmake build dirs")
if(NOT LIEF_BUILD_ROOT)
    set(LIEF_BUILD_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/lief-build")
endif()
set(LIEF_BUILD_DIR "${LIEF_BUILD_ROOT}/${LIEF_ABI_DIR}")
set(LIEF_LIB "${LIEF_BUILD_DIR}/lib/libLIEF.a")

# Check which backends are available for this ABI.
set(RIZIN_AVAILABLE FALSE)
set(LIEF_AVAILABLE FALSE)

if(EXISTS "${RIZIN_SHARED_SDK}/lib/librz_core.so")
    set(RIZIN_AVAILABLE TRUE)
elseif(EXISTS "${RIZIN_BUILD_DIR}/librz/util/librz_util.a")
    set(RIZIN_AVAILABLE TRUE)
endif()

if(EXISTS "${LIEF_LIB}")
    set(LIEF_AVAILABLE TRUE)
endif()

# Source files: always include rizin_core.cpp (or stub), plus lief_elf.cpp if LIEF is available.
set(RZ_SOURCES "")
if(RIZIN_AVAILABLE)
    list(APPEND RZ_SOURCES rizin_core.cpp)
else()
    list(APPEND RZ_SOURCES rizin_stub.cpp)
endif()

if(LIEF_AVAILABLE)
    list(APPEND RZ_SOURCES lief_elf.cpp)
endif()

add_library(rz_native SHARED ${RZ_SOURCES})

# ─── 构建期生成 key_generated.h（对齐上游 SOMCP v1.0.21 / PR #130）───────────
# pin（发布签名证书 SHA-256 + applicationId）与轮转 XOR 密钥都写在这份头文件里；
# 密钥来自构建环境 $TM（16 hex = 8 字节），不再依赖仓库内硬编码的 fallback 走发布路径。
# 上报 API Key（$LRP_API_KEY）按 SHA-256-CTR 密钥流加密后注入（解码器 reporting_key.h，用后擦除）。
set(KEY_GENERATED "${CMAKE_CURRENT_BINARY_DIR}/key_generated.h")
set(GEN_SCRIPT "${CMAKE_CURRENT_LIST_DIR}/../../../../app/generate_header.py")
if(DEFINED ENV{TM} AND NOT "$ENV{TM}" STREQUAL "")
    set(XOR_KEY "$ENV{TM}")
    message(STATUS "[xor-key] generating key_generated.h from TM secret")
elseif(REQUIRE_NATIVE_BACKENDS)
    message(FATAL_ERROR "[xor-key] TM environment variable not set.")
else()
    set(XOR_KEY "0000000000000000")
    message(WARNING "[xor-key] TM not set; using public fallback key (NOT for release builds)")
endif()
if(DEFINED ENV{LRP_API_KEY} AND NOT "$ENV{LRP_API_KEY}" STREQUAL "")
    message(STATUS "[reporting-key] LRP_API_KEY present; injecting into native key_generated.h")
else()
    message(STATUS "[reporting-key] LRP_API_KEY not set; reports will be sent without an API key")
endif()
find_program(PY3 NAMES python3 python REQUIRED)
execute_process(
    COMMAND "${PY3}" "${GEN_SCRIPT}" --key "${XOR_KEY}" --dst "${KEY_GENERATED}"
    WORKING_DIRECTORY "${CMAKE_CURRENT_LIST_DIR}/../../../.."
    RESULT_VARIABLE GEN_RESULT
)
if(NOT GEN_RESULT EQUAL 0)
    message(FATAL_ERROR "[xor-key] generation failed. Check generate_header.py output.")
endif()
message(STATUS "[xor-key] using header: ${KEY_GENERATED}")

# 逆核 v1.3.54: native 级防篡改。native_probe.cpp 直接读 APK 文件系统的
# META-INF PKCS7 证书（绕过可被 kstools/ApkSignatureKiller hook 的 PackageManager
# Binder），并做 v2/v3 真实验签 + apksig 1MiB 分块内容摘要重算。
# 需要 zlib(inflate) + android log；pin 值来自上面构建期生成的 key_generated.h。
target_include_directories(rz_native PRIVATE
    "${CMAKE_CURRENT_BINARY_DIR}"
    "${CMAKE_CURRENT_SOURCE_DIR}"
)
target_sources(rz_native PRIVATE zstd_compat_stub.cpp native_probe.cpp)

add_library(blutter_bridge SHARED blutter_bridge.cpp)
target_link_libraries(blutter_bridge PRIVATE dl log)

# 逆核: xAnSo(ELF section 重建) native 库。third_party/xAnSo-src 由 CI 拉取;
# 源码缺失时(如只跑引擎流水线)自动跳过, 不影响其他目标。
set(XANSO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/xAnSo-src")
if(EXISTS "${XANSO_ROOT}/log.cpp" AND EXISTS "${XANSO_ROOT}/util/util.cpp")
  # 逆核: 启用 xAnSo(ELF section 重建) native 库 —— third_party/xAnSo-src 由 CI 拉取。
  # 库缺失/编译失败时 XAnSoEngine 用 runCatching 优雅降级, 不影响 App 启动。
  set(XANSO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/xAnSo-src")

  # Compatibility header for Windows-specific includes in xAnSo.
  set(XANSO_COMPAT_DIR "${CMAKE_CURRENT_BINARY_DIR}/xanso_compat")
  file(WRITE "${XANSO_COMPAT_DIR}/strsafe.h" "#include <stdio.h>\n")

  # Patch log.cpp: fix invalid va_list dereference.
  set(XANSO_PATCHED_DIR "${CMAKE_CURRENT_BINARY_DIR}/xanso_patched")
  file(READ "${XANSO_ROOT}/log.cpp" XANSO_LOG_SRC)
  string(REPLACE "printf(log_format, *var_arg);" "vprintf(log_format, var_arg);"
         XANSO_LOG_SRC_PATCHED "${XANSO_LOG_SRC}")
  file(WRITE "${XANSO_PATCHED_DIR}/log.cpp" "${XANSO_LOG_SRC_PATCHED}")

  # Patch util.cpp: replace Windows-only ::_itoa_s() with standard snprintf.
  file(READ "${XANSO_ROOT}/util/util.cpp" XANSO_UTIL_SRC)
  string(REPLACE "::_itoa_s(i, _rs, rdx)"
         "snprintf(_rs, sizeof(_rs), (rdx==16)?\"%x\":(rdx==8)?\"%o\":\"%d\", i)"
         XANSO_UTIL_SRC_PATCHED "${XANSO_UTIL_SRC}")
  file(WRITE "${XANSO_PATCHED_DIR}/util.cpp" "${XANSO_UTIL_SRC_PATCHED}")

  add_library(xanso_native SHARED
      xanso_jni.cpp
      ${XANSO_PATCHED_DIR}/log.cpp
      ${XANSO_PATCHED_DIR}/util.cpp
      ${XANSO_ROOT}/Core/dyn_item.cpp
      ${XANSO_ROOT}/Core/dyn_section.cpp
      ${XANSO_ROOT}/Core/elf_header.cpp
      ${XANSO_ROOT}/Core/elf_section.cpp
      ${XANSO_ROOT}/Core/elf_segment.cpp
      ${XANSO_ROOT}/fix/section_fix.cpp
  )
  target_include_directories(xanso_native PRIVATE
      "${XANSO_COMPAT_DIR}"
      "${XANSO_ROOT}/util"
  )
  target_compile_options(xanso_native PRIVATE "-iquote${XANSO_ROOT}")
  target_link_libraries(xanso_native PRIVATE log)
else()
  message(WARNING "xAnSo source not found at ${XANSO_ROOT}; skipping xanso_native (XAnSoEngine degrades gracefully).")
endif()

# --- LIEF include dirs and link ---
if(LIEF_AVAILABLE)
    message(STATUS "LIEF: using ${LIEF_LIB}")
    target_include_directories(rz_native PRIVATE "${LIEF_BUILD_DIR}/include")
    target_link_libraries(rz_native PRIVATE ${LIEF_LIB})
else()
    message(WARNING "LIEF not found at '${LIEF_LIB}'. ELF parsing will use Kotlin fallback (ElfParser).")
endif()

# --- Rizin archives ---
if(RIZIN_AVAILABLE)
    set(RIZIN_SRC "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/rizin-src" CACHE PATH "Rizin source tree")
    if(EXISTS "${RIZIN_SHARED_SDK}/lib/librz_core.so")
        message(STATUS "Rizin: linking shared SDK from ${RIZIN_SHARED_SDK}")
        target_include_directories(rz_native PRIVATE
            ${RIZIN_SHARED_SDK}/include/librz
            ${RIZIN_SHARED_SDK}/include/librz/sdb
        )
        file(GLOB RZ_SHARED_LIBS "${RIZIN_SHARED_SDK}/lib/librz_*.so")
        target_link_libraries(rz_native PRIVATE ${RZ_SHARED_LIBS})
    else()
        file(GLOB_RECURSE RZ_ALL_ARCHIVES_RAW
            "${RIZIN_BUILD_DIR}/librz/*.a"
            "${RIZIN_BUILD_DIR}/subprojects/*.a"
        )
        set(RZ_LINK_ARCHIVES "")
        foreach(archive ${RZ_ALL_ARCHIVES_RAW})
            get_filename_component(fname "${archive}" NAME)
            if(fname MATCHES "_native\.a$" OR fname MATCHES "_cross_native")
                continue()
            endif()
            list(APPEND RZ_LINK_ARCHIVES "${archive}")
        endforeach()

        message(STATUS "Rizin: linking ${RZ_LINK_ARCHIVES} from ${RIZIN_BUILD_DIR}")
        target_include_directories(rz_native PRIVATE
            ${RIZIN_SRC}/librz/include
            ${RIZIN_SRC}/librz/analysis
            ${RIZIN_SRC}/librz/util
            ${RIZIN_SRC}/librz/util/sdb/src
            ${RIZIN_SRC}
            ${RIZIN_SRC}/subprojects/sdb/include
            ${RIZIN_SRC}/subprojects/sdb/include/sdb
            ${RIZIN_SRC}/subprojects/capstone-next/include
            ${RIZIN_SRC}/subprojects/capstone-next/include/capstone
            ${RIZIN_SRC}/subprojects/tree-sitter/lib/include
            ${RIZIN_SRC}/subprojects/tree-sitter/lib/src
            ${RIZIN_SRC}/subprojects/libdemangle/include
            ${RIZIN_BUILD_DIR}
            ${RIZIN_BUILD_DIR}/librz/include
            ${RIZIN_BUILD_DIR}/subprojects/sdb/include
            ${RIZIN_BUILD_DIR}/subprojects
        )
        foreach(archive ${RZ_LINK_ARCHIVES})
            target_link_libraries(rz_native PRIVATE "-Wl,--whole-archive" ${archive} "-Wl,--no-whole-archive")
        endforeach()
    endif()

    target_link_options(rz_native PRIVATE -Wl,--allow-multiple-definition)
    target_link_libraries(rz_native PRIVATE log m z dl c)
else()
    message(WARNING "Rizin static libs not found under '${RIZIN_BUILD_DIR}'. "
                    "Rizin backend will be unavailable at runtime. "
                    "Run the meson cross-compile first.")
    # z: signature_verify.cpp 的 inflate() 需要（无 Rizin 时也要能编译该文件）
    target_link_libraries(rz_native PRIVATE log z)
endif()

if(CMAKE_BUILD_TYPE STREQUAL "Release" OR NOT CMAKE_BUILD_TYPE)
    # 加固开关（对齐上游 SOMCP v1.0.21）：
    #   -fstack-protector-all  所有函数插入栈 canary，防栈溢出覆盖返回地址
    #   -D_FORTIFY_SOURCE=2    在 -O2 下启用运行期缓冲区溢出检查（先 -U 消除与 NDK 默认宏的重定义）
    #   -fno-strict-overflow   禁止基于有符号溢出的 UB 优化（避免安全检查被优化裁剪）
    # 说明：PRIVATE，仅作用于本 target 的胶水源码；rizin/LIEF 静态库为独立交叉编译产物，不受影响。
    target_compile_options(rz_native PRIVATE
        -O2
        -ffunction-sections
        -fdata-sections
        -fvisibility=hidden
        -fstack-protector-all
        -U_FORTIFY_SOURCE
        -D_FORTIFY_SOURCE=2
        -fno-strict-overflow
    )
    # 链接期加固：-z now + -z relro 在加载时立即绑定 PLT 并整段只读重定位（阻断运行时 PLT/GOT hook）；
    # --as-needed 收缩依赖面。故意不使用 --strip-all：保留符号表以便引擎自检 / map / 诊断。
    target_link_options(rz_native PRIVATE
        -Wl,--gc-sections
        -Wl,--icf=safe
        -Wl,--exclude-libs,ALL
        -Wl,-z,relro
        -Wl,-z,now
        -Wl,--as-needed
    )
endif()

# ── 逆核: Exbin 自研 microcode + SSA 反编译器（独立 libexbin_decomp.so）──
# 独立于 rizin/ghidra 的第六条伪 C 路径；纯 C++17 + JNI，无第三方依赖。
# JNI 入口: Java_com_exbin_app_nativebridge_NativeBridge_nativeDecompileFunction
add_subdirectory(exbin_decomp)
