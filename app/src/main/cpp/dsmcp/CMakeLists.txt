cmake_minimum_required(VERSION 3.22.1)

project(dsmcp_native LANGUAGES CXX)

# C++ 源文件
add_library(dsmcp_native SHARED
    native-lib.cpp
    elf_bridge.cpp
    pe_bridge.cpp
    dotnet_bridge.cpp
)

# C++ 标准和特性
set_target_properties(dsmcp_native PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
    POSITION_INDEPENDENT_CODE ON
)

# 编译选项
target_compile_options(dsmcp_native PRIVATE
    -fexceptions
    -frtti
    -O2
)

# 链接 Android log 库
target_link_libraries(dsmcp_native
    log
)
