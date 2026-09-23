// elf_symbol_resolver.hpp — C++ ELF 符号解析器
// 直接解析 ELF 文件获取 PLT/导入表/符号表，不依赖 Java 层传参。
//
// 能力：
//   - 解析 .dynsym + .dynstr（动态符号表）
//   - 解析 .symtab + .strtab（完整符号表）
//   - 解析 .rel.plt + .rela.plt（PLT 重定位表）
//   - 解析 .rel.dyn + .rela.dyn（数据重定位表）
//   - 构建 addr→name 映射（函数符号 + PLT 桩地址 + GOT 入口地址）
//   - 支持 32/64 bit, ARM/AArch64

#pragma once

#include <cstdint>
#include <string>
#include <map>
#include <vector>
// v10.1: std::u16string 已由 <string> 提供，无需额外头文件

namespace mc {

// 符号类型
enum class SymType : uint8_t {
    UNKNOWN = 0,
    FUNC_LOCAL,       // 本地函数（.symtab 中的 STT_FUNC）
    FUNC_GLOBAL,      // 全局函数（.symtab/.dynsym 中的 STT_FUNC + STB_GLOBAL）
    FUNC_IMPORT,      // 导入函数（SHN_UNDEF + STT_FUNC）
    DATA_GLOBAL,      // 全局数据符号
    PLT_STUB,         // PLT 桩地址
    GOT_ENTRY,        // GOT 入口地址
    VTABLE,           // v4.10: C++ 虚表（_ZTV 符号）
};

// 解析出的符号条目
struct ResolvedSym {
    uint64_t addr;          // 符号地址（函数入口 / PLT 桩 / GOT 入口）
    uint64_t size;          // st_size from ELF symbol table
    std::string name;       // 符号名（已 demangle 的 C++ 名）
    SymType type;           // 符号类型
    std::string module;     // 来源模块（导入符号的 DSO 名，通常为空）
};

// v4.10: C++ 虚表信息
struct VTableInfo {
    uint64_t addr;                      // 虚表地址
    std::string className;              // 类名（从 _ZTV 符号名 demangle）
    std::vector<uint64_t> entries;      // 虚函数指针数组
    std::vector<std::string> entryNames; // 解析后的虚函数名
    uint64_t parentVTable;              // 父类虚表地址（0 = 无父类/未知）
};

// v4.11: C++ 结构体/类信息
struct StructInfo {
    std::string name;                   // demangled 类名
    std::string mangledName;            // mangled 类名
    uint64_t vtableAddr;                // 虚表地址（0 = 无虚表）
    uint64_t typeinfoAddr;              // _ZTI 地址
    std::string typeinfoName;           // 从 _ZTS 获取的类名
    std::vector<std::string> baseClasses; // 父类名列表
    std::vector<std::string> vfuncNames;  // 虚函数名列表
    bool isPolymorphic;                 // 是否有多态（有虚表）
};

// ELF 符号解析结果
struct ElfSymbolMap {
    // addr → 符号信息
    std::map<uint64_t, ResolvedSym> byAddr;

    // name → addr（反向查找，用于签名匹配）
    std::map<std::string, uint64_t> byName;

    // PLT 桩地址列表（按地址排序）
    std::vector<uint64_t> pltStubs;

    // 导入函数名列表
    std::vector<std::string> importNames;

    // v3.3: .rodata 字符串常量: addr → string content
    // 用于把 adrp+add 加载的地址替换为 "hello" 字符串字面量
    std::map<uint64_t, std::string> stringRefs;

    // v10.1: 字符串长度信息 (addr → length)
    // 记录每个字符串引用的实际字节长度（不含结尾的 NUL）。
    // 用于 CPrinter 在输出字符串字面量时附加长度注释。
    std::map<uint64_t, size_t> stringLengths;

    // v10.1: UTF-16 字符串 (addr → u16string content)
    // 检测到的 UTF-16LE 编码的宽字符串。当 ADRP+ADD/LDR 解析到的
    // 地址命中此映射时，CPrinter 将输出 L"..." 宽字符串字面量。
    std::map<uint64_t, std::u16string> utf16Strings;

    // v3.3: 全局变量: addr → variable name
    // 从 .data/.bss 段的符号表中提取
    std::map<uint64_t, std::string> globalVars;

    // v3.3: 全局变量大小: addr → size (bytes)
    std::map<uint64_t, uint64_t> globalVarSizes;

    // v3.19: GOT entry address → import symbol name (from .rel.plt / .rela.plt)
    // Used by MicrocodeEmitter to resolve the ARM32 LDR-literal + ADD-PC + LDR
    // GOT indirection pattern:
    //   ldr r0, [pc, #off]   ; r0 = offset (from literal pool)
    //   add r0, pc, r0        ; r0 = GOT entry address
    //   ldr r0, [r0]          ; r0 = *GOT = function pointer
    //   blr r0                ; call import
    // With gotToName, the emitter can resolve the GOT address to the import name.
    std::map<uint64_t, std::string> gotToName;

    // v3.3: .rodata 段范围 (用于判断地址是否在只读数据段)
    uint64_t rodataStart = 0;
    uint64_t rodataEnd = 0;

    // v3.3: .data 段范围
    uint64_t dataStart = 0;
    uint64_t dataEnd = 0;

    // v3.3: .bss 段范围
    uint64_t bssStart = 0;
    uint64_t bssEnd = 0;

    // v9.4: 代码段/数据段范围 (SHF_EXECINSTR 判断)
    // 用于区分可执行代码与数据段，过滤 .ARM.exidx 等被误判为函数的符号
    std::vector<std::pair<uint64_t, uint64_t>> codeSectionRanges;
    std::vector<std::pair<uint64_t, uint64_t>> dataSectionRanges;

    // v9.4: ARM 映射符号 ($a/$t/$d/$x) 标记的代码区域
    // 用于细化代码段内部的数据区域（如 literal pool，跳转表）
    std::vector<std::pair<uint64_t, uint64_t>> mappingCodeRanges;
    std::vector<std::pair<uint64_t, uint64_t>> mappingDataRanges;

    // 是否有效
    bool valid = false;

    // v4.10: 虚表映射: addr → VTableInfo
    std::map<uint64_t, VTableInfo> vtableMap;
    // v4.10: 类名 → 虚表地址（反向查找）
    std::map<std::string, uint64_t> classNameToVTable;

    // v4.11: 结构体/类信息: name → StructInfo
    std::map<std::string, StructInfo> structMap;

    // 查找地址对应的符号名，找不到返回空字符串
    std::string lookup(uint64_t addr) const {
        auto it = byAddr.find(addr);
        if (it != byAddr.end()) return it->second.name;
        return "";
    }

    // 查找地址对应的字符串常量，找不到返回空字符串
    std::string lookupString(uint64_t addr) const {
        auto it = stringRefs.find(addr);
        if (it != stringRefs.end()) return it->second;
        return "";
    }

    // v10.1: 查找地址对应的字符串长度，找不到返回 0
    size_t lookupStringLength(uint64_t addr) const {
        auto it = stringLengths.find(addr);
        if (it != stringLengths.end()) return it->second;
        return 0;
    }

    // v10.1: 查找地址对应的 UTF-16 字符串，找不到返回空 u16string
    std::u16string lookupUtf16String(uint64_t addr) const {
        auto it = utf16Strings.find(addr);
        if (it != utf16Strings.end()) return it->second;
        return std::u16string();
    }

    // 查找地址对应的全局变量名，找不到返回空字符串
    std::string lookupGlobalVar(uint64_t addr) const {
        auto it = globalVars.find(addr);
        if (it != globalVars.end()) return it->second;
        return "";
    }

    // 判断地址是否在 .rodata 段内
    bool isInRodata(uint64_t addr) const {
        return addr >= rodataStart && addr < rodataEnd;
    }

    // 判断地址是否在 .data 段内
    bool isInData(uint64_t addr) const {
        return addr >= dataStart && addr < dataEnd;
    }

    // 判断地址是否在 .bss 段内
    bool isInBss(uint64_t addr) const {
        return addr >= bssStart && addr < bssEnd;
    }

    // v9.4: 判断地址是否在可执行代码段中
    bool isInCodeSection(uint64_t addr) const {
        for (auto& [start, end] : codeSectionRanges) {
            if (addr >= start && addr < end) return true;
        }
        return false;
    }

    // v9.4: 判断地址是否在已知数据段中（包括 .ARM.exidx 等）
    bool isInDataSection(uint64_t addr) const {
        for (auto& [start, end] : dataSectionRanges) {
            if (addr >= start && addr < end) return true;
        }
        return false;
    }

    // v9.4: 判断地址是否在 ARM 映射符号标记的数据区域内
    bool isInMappingData(uint64_t addr) const {
        for (auto& [start, end] : mappingDataRanges) {
            if (addr >= start && addr <= end) return true;
        }
        return false;
    }

    // 查找最近的 PLT 桩（地址在 [base, base+range) 范围内）
    // 用于 bl 指令跳转到 PLT 区域的情况
    uint64_t findNearestPlt(uint64_t addr, uint64_t range = 32) const {
        for (uint64_t stub : pltStubs) {
            if (addr >= stub && addr < stub + range)
                return stub;
        }
        return 0;
    }
};

// ELF 符号解析器
class ElfSymbolResolver {
public:
    // 从文件路径解析
    // path: SO 文件路径
    // 返回符号映射，失败时 valid=false
    static ElfSymbolMap resolve(const std::string& path);

    // 从内存数据解析
    // data: ELF 文件数据指针
    // size: 数据大小
    static ElfSymbolMap resolve(const uint8_t* data, size_t size);

private:
    // 核心解析逻辑
    static ElfSymbolMap resolveInternal(const uint8_t* data, size_t size);

    // C++ 名字 demangle (Itanium ABI)
    static std::string demangle(const std::string& name);
};

} // namespace mc
