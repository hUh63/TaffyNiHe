# ═══════════════════════════════════════════════════════════════════════════
# Exbin 自研 microcode + SSA 反编译器（独立 native 库 libexbin_decomp.so）
#
# 独立于 rizin/ghidra 的第六条伪 C 路径：
#   AsmInsn → MicrocodeEmitter → SSA → Optimize → CFGStructure → CTree → Beautify → CPrinter
#
# 完全自包含：仅依赖 C++17 标准库 + JNI；不需要 capstone/keystone
# （Exbin 仓库里 decompiler/ndk_decompile.cpp 那个旧入口才用 capstone，
#  本项目用的是 jni 根目录的正式入口 ndk_decompile.cpp，零第三方依赖）。
#
# JNI 入口：Java_com_exbin_app_nativebridge_NativeBridge_nativeDecompileFunction
# ═══════════════════════════════════════════════════════════════════════════

cmake_minimum_required(VERSION 3.22.1)
project(exbin_decomp LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(EXBIN_DECOMP_DIR "${CMAKE_CURRENT_LIST_DIR}")

add_library(exbin_decomp SHARED
    ndk_decompile.cpp
    decompiler/microcode_emitter.cpp
    decompiler/ssa.cpp
    decompiler/cfg_structure.cpp
    decompiler/ctree_impl.cpp
    decompiler/c_printer.cpp
    decompiler/symbols.cpp
    decompiler/var_types.cpp
    decompiler/elf_symbol_resolver.cpp
    decompiler/calling_convention.cpp
    decompiler/branch_simplify.cpp
    decompiler/type_inference.cpp
    decompiler/microcode_opt.cpp
    decompiler/ir_optimizer.cpp
    decompiler/high_variable.cpp
)

target_include_directories(exbin_decomp PRIVATE
    "${EXBIN_DECOMP_DIR}"
    "${EXBIN_DECOMP_DIR}/decompiler"
)

# 大文件 + 深层递归（ctree/cfg_structure），开 O2；异常必须开启（源码用 try/catch）
target_compile_options(exbin_decomp PRIVATE
    -O2
    -fexceptions
    -fvisibility=hidden
    -fvisibility-inlines-hidden
)

target_link_libraries(exbin_decomp PRIVATE log)
