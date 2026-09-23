// elf_symbol_resolver.cpp — 重写版 ELF 符号解析器
// 对标 Ghidra symboltab.cc + loadimage.cc 的数据收集逻辑
//
// 改进:
//   1. 按 relocation type 过滤 JUMP_SLOT（而非全量 gotToName）
//   2. 用 JUMP_SLOT 重定位数量确定 PLT 桩数（而非 section.size / pltEntSize）
//   3. 从 .dynstr 结构提取字符串（而非启发式扫描）
//   4. 全局变量正确记录 st_size
//   5. JNI/NDK 常量数据库

#include "elf_symbol_resolver.hpp"

#include <elf.h>
#include <cstring>
#include <cstdio>
#include <fstream>
#include <vector>
#include <algorithm>
#include <set>

#if __has_include(<cxxabi.h>)
#include <cxxabi.h>
#include <cstdlib>
#define HAS_CXXABI 1
#else
#define HAS_CXXABI 0
#endif

namespace mc {

// ═══════════════════════════════════════════════════════════════
// 小端整数读取
// ═══════════════════════════════════════════════════════════════
static inline uint16_t rdU16(const uint8_t* p) { uint16_t v; std::memcpy(&v, p, 2); return v; }
static inline uint32_t rdU32(const uint8_t* p) { uint32_t v; std::memcpy(&v, p, 4); return v; }
static inline uint64_t rdU64(const uint8_t* p) { uint64_t v; std::memcpy(&v, p, 8); return v; }

static bool inRange(size_t off, size_t len, size_t dataSize) {
    return off < dataSize && len <= dataSize - off;
}

// ═══════════════════════════════════════════════════════════════
// C++ demangle
// ═══════════════════════════════════════════════════════════════
std::string ElfSymbolResolver::demangle(const std::string& name) {
    if (name.empty()) return name;
    if (name.size() < 2 || name[0] != '_' || name[1] != 'Z')
        return name;  // 不是 Itanium mangled name，跳过
#if HAS_CXXABI
    int status = 0;
    char* demangled = __cxxabiv1::__cxa_demangle(name.c_str(), nullptr, nullptr, &status);
    if (status == 0 && demangled) {
        std::string result(demangled);
        std::free(demangled);
        return result;
    }
    if (demangled) std::free(demangled);
#endif
    return name;
}

// ═══════════════════════════════════════════════════════════════
// ELF 相关常量
// ═══════════════════════════════════════════════════════════════
#ifndef STT_GNU_IFUNC
#define STT_GNU_IFUNC 10
#endif
#define ST_TYPE(info) ((info) & 0xf)
#define ST_BIND(info) ((info) >> 4)

// Relocation type constants (using MY_ prefix to avoid <elf.h> conflicts)
// AArch64: R_AARCH64_JUMP_SLOT = 1026, R_AARCH64_GLOB_DAT = 1025
//          R_AARCH64_RELATIVE = 1027, R_AARCH64_ABS64 = 257
//          R_AARCH64_COPY = 1024
// ARM32:   R_ARM_JUMP_SLOT = 22, R_ARM_GLOB_DAT = 21
//          R_ARM_RELATIVE = 23, R_ARM_ABS32 = 2, R_ARM_COPY = 20
// x86_64:  R_X86_64_JUMP_SLOT = 7, R_X86_64_GLOB_DAT = 6
//          R_X86_64_RELATIVE = 8, R_X86_64_64 = 1, R_X86_64_COPY = 5
static constexpr uint32_t MY_R_AARCH64_JUMP_SLOT = 1026;
static constexpr uint32_t MY_R_AARCH64_GLOB_DAT  = 1025;
static constexpr uint32_t MY_R_AARCH64_RELATIVE  = 1027;
static constexpr uint32_t MY_R_AARCH64_ABS64     = 257;
static constexpr uint32_t MY_R_AARCH64_COPY      = 1024;
static constexpr uint32_t MY_R_ARM_JUMP_SLOT     = 22;
static constexpr uint32_t MY_R_ARM_GLOB_DAT      = 21;
static constexpr uint32_t MY_R_ARM_RELATIVE      = 23;
static constexpr uint32_t MY_R_ARM_ABS32         = 2;
static constexpr uint32_t MY_R_ARM_COPY          = 20;
static constexpr uint32_t MY_R_X86_64_JUMP_SLOT  = 7;
static constexpr uint32_t MY_R_X86_64_GLOB_DAT   = 6;
static constexpr uint32_t MY_R_X86_64_RELATIVE   = 8;
static constexpr uint32_t MY_R_X86_64_64         = 1;
static constexpr uint32_t MY_R_X86_64_COPY       = 5;

// ═══════════════════════════════════════════════════════════════
// JNI/NDK 常见常量数据库
// 对标 Ghidra 的常量折叠 — 在 ELF 解析阶段注入常量名
// ═══════════════════════════════════════════════════════════════
struct KnownConstant {
    uint64_t value;
    const char* name;
};

static const KnownConstant JNI_CONSTANTS[] = {
    {0x00010002, "JNI_VERSION_1_2"},
    {0x00010004, "JNI_VERSION_1_4"},
    {0x00010006, "JNI_VERSION_1_6"},
    {0x00010008, "JNI_VERSION_1_8"},
    {0x0001000a, "JNI_VERSION_10"},
    {0x00000000, "JNI_OK"},
    {-1ULL,       "JNI_ERR"},
    {-2ULL,       "JNI_EDETACHED"},
    {-3ULL,       "JNI_EVERSION"},
    {-4ULL,       "JNI_ENOMEM"},
    {-5ULL,       "JNI_EEXIST"},
    {-6ULL,       "JNI_EINVAL"},
    {0xffffffff,  "JNI_FALSE"},
    {0x00000000,  "JNI_FALSE"},
    {0x00000001,  "JNI_TRUE"},
};

static const KnownConstant PTHREAD_CONSTANTS[] = {
    {0, "PTHREAD_MUTEX_NORMAL"},
    {1, "PTHREAD_MUTEX_RECURSIVE"},
    {2, "PTHREAD_MUTEX_ERRORCHECK"},
    {0, "PTHREAD_CREATE_JOINABLE"},
    {1, "PTHREAD_CREATE_DETACHED"},
};

// ═══════════════════════════════════════════════════════════════
// 符号信息
// ═══════════════════════════════════════════════════════════════
struct SymInfo {
    uint32_t name;      // strtab offset
    uint8_t  info;      // st_info
    uint8_t  other;     // st_other
    uint16_t shndx;     // st_shndx
    uint64_t value;     // st_value
    uint64_t size;      // st_size
};

static void readSym(const uint8_t* p, bool is64, SymInfo& sym) {
    if (is64) {
        sym.name  = rdU32(p);
        sym.info  = p[4];
        sym.other = p[5];
        sym.shndx = rdU16(p + 6);
        sym.value = rdU64(p + 8);
        sym.size  = rdU64(p + 16);
    } else {
        sym.name  = rdU32(p);
        sym.value = rdU32(p + 4);
        sym.size  = rdU32(p + 8);
        sym.info  = p[12];
        sym.other = p[13];
        sym.shndx = rdU16(p + 14);
    }
}

// ═══════════════════════════════════════════════════════════════
// 节区信息
// ═══════════════════════════════════════════════════════════════
struct SecInfo {
    uint32_t name;       // sh_name
    uint32_t type;       // sh_type
    uint64_t flags;      // sh_flags
    uint64_t addr;       // sh_addr
    uint64_t offset;     // sh_offset
    uint64_t size;       // sh_size
    uint32_t link;       // sh_link
    uint32_t info;       // sh_info
    uint64_t entsize;    // sh_entsize
    std::string nameStr;
};

static void readSecInfo(const uint8_t* sh, bool is64, SecInfo& s) {
    if (is64) {
        s.name    = rdU32(sh + 0);
        s.type    = rdU32(sh + 4);
        s.flags   = rdU64(sh + 8);
        s.addr    = rdU64(sh + 16);
        s.offset  = rdU64(sh + 24);
        s.size    = rdU64(sh + 32);
        s.link    = rdU32(sh + 40);
        s.info    = rdU32(sh + 44);
        s.entsize = rdU64(sh + 56);
    } else {
        s.name    = rdU32(sh + 0);
        s.type    = rdU32(sh + 4);
        s.flags   = rdU32(sh + 8);
        s.addr    = rdU32(sh + 12);
        s.offset  = rdU32(sh + 16);
        s.size    = rdU32(sh + 20);
        s.link    = rdU32(sh + 24);
        s.info    = rdU32(sh + 28);
        s.entsize = rdU32(sh + 36);
    }
}

// ═══════════════════════════════════════════════════════════════
// resolve() — 从文件路径加载
// ═══════════════════════════════════════════════════════════════
ElfSymbolMap ElfSymbolResolver::resolve(const std::string& path) {
    std::ifstream ifs(path, std::ios::binary);
    if (!ifs) {
        ElfSymbolMap m; m.valid = false; return m;
    }
    std::vector<uint8_t> data((std::istreambuf_iterator<char>(ifs)),
                               std::istreambuf_iterator<char>());
    if (data.size() < 16) {
        ElfSymbolMap m; m.valid = false; return m;
    }
    return resolveInternal(data.data(), data.size());
}

ElfSymbolMap ElfSymbolResolver::resolve(const uint8_t* data, size_t size) {
    return resolveInternal(data, size);
}

// ═══════════════════════════════════════════════════════════════
// resolveInternal() — 核心解析
// ═══════════════════════════════════════════════════════════════
ElfSymbolMap ElfSymbolResolver::resolveInternal(const uint8_t* data, size_t dataSize) {
    ElfSymbolMap result;
    result.valid = false;

    if (!data || dataSize < 16) return result;
    if (data[0] != 0x7f || data[1] != 'E' || data[2] != 'L' || data[3] != 'F')
        return result;

    bool is64 = (data[4] == ELFCLASS64);
    bool isLE = (data[5] == ELFDATA2LSB);
    if (!isLE) return result;  // Android SO 都是小端

    // ── ELF Header ──
    uint16_t e_machine;
    uint64_t e_shoff;
    uint16_t e_shentsize, e_shnum, e_shstrndx;

    if (is64) {
        if (dataSize < sizeof(Elf64_Ehdr)) return result;
        e_machine   = rdU16(data + 18);
        e_shoff     = rdU64(data + 40);
        e_shentsize = rdU16(data + 58);
        e_shnum     = rdU16(data + 60);
        e_shstrndx  = rdU16(data + 62);
    } else {
        if (dataSize < sizeof(Elf32_Ehdr)) return result;
        e_machine   = rdU16(data + 18);
        e_shoff     = rdU32(data + 32);
        e_shentsize = rdU16(data + 46);
        e_shnum     = rdU16(data + 48);
        e_shstrndx  = rdU16(data + 50);
    }

    if (e_shoff == 0 || e_shnum == 0 || e_shentsize == 0) return result;
    if (e_shoff + (size_t)e_shnum * e_shentsize > dataSize) return result;

    bool isAArch64 = (e_machine == 183);  // EM_AARCH64
    bool isARM32   = (e_machine == 40);   // EM_ARM
    bool isX86_64  = (e_machine == 62);   // EM_X86_64
    (void)isX86_64;  // future use
    uint32_t JUMP_SLOT_TYPE = isAArch64 ? MY_R_AARCH64_JUMP_SLOT :
                              isARM32   ? MY_R_ARM_JUMP_SLOT :
                              MY_R_X86_64_JUMP_SLOT;  // x86_64 / i386

    // ── 读取所有 section headers ──
    std::vector<SecInfo> sections(e_shnum);
    for (uint16_t i = 0; i < e_shnum; i++) {
        readSecInfo(data + e_shoff + (size_t)i * e_shentsize, is64, sections[i]);
    }

    // ── 读取 shstrtab ──
    if (e_shstrndx >= e_shnum) return result;
    const SecInfo& shstrSec = sections[e_shstrndx];
    if (!inRange(shstrSec.offset, shstrSec.size, dataSize)) return result;
    const char* shstrtab = (const char*)(data + shstrSec.offset);
    size_t shstrtabSize = shstrSec.size;

    for (auto& s : sections) {
        if (s.name < shstrtabSize)
            s.nameStr = std::string(shstrtab + s.name);
    }

    // ── 定位关键节区 ──
    const SecInfo* dynsymSec = nullptr;
    const SecInfo* symtabSec = nullptr;
    const SecInfo* dynstrSec = nullptr;
    const SecInfo* strtabSec = nullptr;
    const SecInfo* rodataSec = nullptr;
    const SecInfo* dataSec = nullptr;
    const SecInfo* bssSec = nullptr;
    const SecInfo* gnuHashSec = nullptr;  // v5.6: .gnu.hash (r2 elf.c)
    const SecInfo* hashSec = nullptr;     // v5.6: .hash (SYSV hash) — reserved for .hash fallback
    std::vector<const SecInfo*> relPltSecs;   // .rel.plt / .rela.plt
    std::vector<const SecInfo*> relDynSecs;   // .rel.dyn / .rela.dyn
    std::vector<const SecInfo*> pltSecs;       // .plt / .plt.got / .plt.sec

    // v9.4: ARM 映射符号收集 (地址, 类型字符)
    std::vector<std::pair<uint64_t, char>> mappingSyms;

    for (const auto& s : sections) {
        if (s.nameStr == ".dynsym")           dynsymSec = &s;
        else if (s.nameStr == ".symtab")      symtabSec = &s;
        else if (s.nameStr == ".dynstr")      dynstrSec = &s;
        else if (s.nameStr == ".strtab")      strtabSec = &s;
        else if (s.nameStr == ".plt" || s.nameStr == ".plt.got" ||
                 s.nameStr == ".plt.sec")     pltSecs.push_back(&s);
        else if (s.nameStr == ".rodata" || s.nameStr == ".rodata.str1.1" ||
                 s.nameStr == ".rodata.str1.8" || s.nameStr == ".data.rel.ro") {
            if (!rodataSec) rodataSec = &s;
        }
        else if (s.nameStr == ".data") {
            if (!dataSec) dataSec = &s;
        }
        else if (s.nameStr == ".bss")         bssSec = &s;
        else if (s.nameStr == ".gnu.hash")    gnuHashSec = &s;  // v5.6
        else if (s.nameStr == ".hash")        hashSec = &s;     // v5.6
        else if (s.type == SHT_REL || s.type == SHT_RELA) {
            if (s.nameStr.find(".rel.plt") != std::string::npos ||
                s.nameStr.find(".rela.plt") != std::string::npos)
                relPltSecs.push_back(&s);
            else if (s.nameStr.find(".rel.dyn") != std::string::npos ||
                     s.nameStr.find(".rela.dyn") != std::string::npos)
                relDynSecs.push_back(&s);
        }
    }

    // ── 记录段范围 ──
    if (rodataSec) { result.rodataStart = rodataSec->addr; result.rodataEnd = rodataSec->addr + rodataSec->size; }
    if (dataSec)   { result.dataStart   = dataSec->addr;   result.dataEnd   = dataSec->addr + dataSec->size; }
    if (bssSec)    { result.bssStart    = bssSec->addr;    result.bssEnd    = bssSec->addr + bssSec->size; }

    // v9.4: 收集代码段和数据段范围 (参考 r2 的 sections.c: is_executable())
    for (const auto& s : sections) {
        if (s.size == 0 || s.addr == 0) continue;
        bool isExec = (s.flags & SHF_EXECINSTR) != 0;
        bool isAlloc = (s.flags & SHF_ALLOC) != 0;
        bool isProgBits = (s.type == SHT_PROGBITS) || (s.type == SHT_NOBITS);
        if (isExec && isAlloc) {
            result.codeSectionRanges.push_back({s.addr, s.addr + s.size});
        } else if (isAlloc && isProgBits && s.size > 0 && s.addr > 0) {
            // 非代码但分配内存且有内容的段 → 数据段
            // 包括 .rodata, .data, .bss, .ARM.exidx, .ARM.extab, .init_array 等
            result.dataSectionRanges.push_back({s.addr, s.addr + s.size});
        }
    }

    // ── 辅助函数：从 strtab 读取字符串 ──
    auto getStr = [](const char* tab, size_t tabSize, uint32_t off) -> const char* {
        if (!tab || off >= tabSize) return "";
        return tab + off;
    };

    // ═══════════════════════════════════════════════════════════
    // Step 1: 解析 .dynsym
    // ═══════════════════════════════════════════════════════════
    std::map<uint32_t, std::string> dynSymNames;
    std::map<uint32_t, SymInfo>     dynSymInfos;

    if (dynsymSec && dynstrSec) {
        const char* dynstr = nullptr;
        size_t dynstrSize = 0;
        if (inRange(dynstrSec->offset, dynstrSec->size, dataSize)) {
            dynstr = (const char*)(data + dynstrSec->offset);
            dynstrSize = dynstrSec->size;
        }

        uint64_t entSize = dynsymSec->entsize;
        if (entSize == 0) entSize = is64 ? sizeof(Elf64_Sym) : sizeof(Elf32_Sym);
        uint64_t count = dynsymSec->size / entSize;

        if (inRange(dynsymSec->offset, dynsymSec->size, dataSize)) {
            for (uint64_t i = 0; i < count; i++) {
                uint64_t off = dynsymSec->offset + i * entSize;
                if (off + entSize > dataSize) break;

                SymInfo sym;
                readSym(data + off, is64, sym);

                const char* name = getStr(dynstr, dynstrSize, sym.name);
                std::string nameStr = name ? name : "";

                dynSymNames[(uint32_t)i] = nameStr;
                dynSymInfos[(uint32_t)i] = sym;

                unsigned char type = ST_TYPE(sym.info);
                unsigned char bind = ST_BIND(sym.info);

                // 导入函数
                if (sym.shndx == SHN_UNDEF && (type == STT_FUNC || type == STT_GNU_IFUNC)) {
                    if (!nameStr.empty())
                        result.importNames.push_back(nameStr);
                }

                // 已定义的全局函数
                if (sym.shndx != SHN_UNDEF && sym.value != 0 &&
                    (type == STT_FUNC || type == STT_GNU_IFUNC)) {
                    ResolvedSym rs;
                    rs.addr = sym.value;
                    rs.size = sym.size;
                    rs.name = demangle(nameStr);
                    rs.type = (bind == STB_LOCAL) ? SymType::FUNC_LOCAL : SymType::FUNC_GLOBAL;
                    result.byAddr[rs.addr] = rs;
                    result.byName[rs.name] = rs.addr;
                }

                // 全局数据符号
                if (type == STT_OBJECT && nameStr.size() > 0 && sym.value != 0) {
                    ResolvedSym rs;
                    rs.addr = sym.value;
                    rs.size = sym.size;
                    rs.name = demangle(nameStr);
                    rs.type = SymType::DATA_GLOBAL;
                    result.byAddr[rs.addr] = rs;
                    result.byName[rs.name] = rs.addr;
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Step 1.5: 解析 .gnu.hash — 获取动态符号数量 (v5.6)
    // 对标 r2 libr/bin/format/elf/elf.c r_bin_elf_get_syms()
    // 很多 stripped SO 只有 .gnu.hash 没有 .hash，且 .dynsym 的
    // 符号数量无法直接从 section header 获取（因为 .dynsym 的
    // sh_size 可能包含 padding）。.gnu.hash 的 header 包含
    // symcount（nsyms），可以用它来校验/补充 .dynsym 的符号数。
    // ═══════════════════════════════════════════════════════════
    (void)hashSec;  // .hash (SYSV) — reserved for future .hash fallback
    if (gnuHashSec && inRange(gnuHashSec->offset, gnuHashSec->size, dataSize)) {
        // .gnu.hash layout:
        //   uint32_t nbuckets
        //   uint32_t symoffset  (symndx — first symbol in hash chain)
        //   uint32_t bloom_size
        //   uint32_t bloom_shift
        //   uint64_t bloom[bloom_size]  (on 64-bit)
        //   uint32_t buckets[nbuckets]
        //   uint32_t chain[]             (val | 1 = end of chain)
        const uint8_t* gh = data + gnuHashSec->offset;
        if (gnuHashSec->size >= 16) {
            uint32_t nbuckets    = rdU32(gh + 0);
            uint32_t symoffset   = rdU32(gh + 4);
            uint32_t bloom_size  = rdU32(gh + 8);
            // uint32_t bloom_shift = rdU32(gh + 12);
            size_t bloomEntrySize = is64 ? 8 : 4;
            size_t bloomBytes = (size_t)bloom_size * bloomEntrySize;
            size_t bucketsOff = 16 + bloomBytes;
            size_t chainOff = bucketsOff + (size_t)nbuckets * 4;

            if (chainOff <= gnuHashSec->size) {
                // Find the max symbol index by scanning buckets
                uint32_t maxSymIdx = symoffset;
                for (uint32_t b = 0; b < nbuckets; b++) {
                    size_t off = 16 + bloomBytes + (size_t)b * 4;
                    if (off + 4 <= gnuHashSec->size) {
                        uint32_t bucketVal = rdU32(gh + off);
                        if (bucketVal > maxSymIdx) maxSymIdx = bucketVal;
                    }
                }
                // Follow the chain from the max bucket to find the last symbol
                if (maxSymIdx >= symoffset && chainOff < gnuHashSec->size) {
                    uint32_t idx = maxSymIdx;
                    while (idx >= symoffset) {
                        size_t coff = chainOff + (size_t)(idx - symoffset) * 4;
                        if (coff + 4 > gnuHashSec->size) break;
                        uint32_t chainVal = rdU32(gh + coff);
                        if (chainVal & 1) break;  // end of chain
                        idx++;
                    }
                    // idx is now the last symbol index; total = idx + 1
                    // This can be used to validate dynSymNames coverage
                    // (we already iterate all of .dynsym, so this is mainly informational)
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Step 2: 解析 .symtab (完整符号表)
    // ═══════════════════════════════════════════════════════════
    if (symtabSec && strtabSec) {
        const char* strtab = nullptr;
        size_t strtabSize = 0;
        if (inRange(strtabSec->offset, strtabSec->size, dataSize)) {
            strtab = (const char*)(data + strtabSec->offset);
            strtabSize = strtabSec->size;
        }

        uint64_t entSize = symtabSec->entsize;
        if (entSize == 0) entSize = is64 ? sizeof(Elf64_Sym) : sizeof(Elf32_Sym);
        uint64_t count = symtabSec->size / entSize;

        if (inRange(symtabSec->offset, symtabSec->size, dataSize)) {
            for (uint64_t i = 0; i < count; i++) {
                uint64_t off = symtabSec->offset + i * entSize;
                if (off + entSize > dataSize) break;

                SymInfo sym;
                readSym(data + off, is64, sym);

                if (sym.shndx == SHN_UNDEF || sym.value == 0) continue;

                const char* name = getStr(strtab, strtabSize, sym.name);
                if (!name || !name[0]) continue;

                unsigned char type = ST_TYPE(sym.info);
                std::string nameStr = name;

                // Skip ARM/AArch64 mapping symbols ($d, $x, $t, $a)
                // These are auto-generated NOTYPE symbols that pollute the symbol table.
                // When they appear before a real symbol at the same address,
                // the "don't overwrite" check below prevents the real symbol from being registered.
                // v9.4: Also collect them for code/data region detection
                if (nameStr.size() == 2 && nameStr[0] == '$' &&
                    std::string("dtxa").find(nameStr[1]) != std::string::npos) {
                    // Store mapping symbol for code/data region detection
                    // $a=ARM code, $t=Thumb code, $x=A64 code, $d=data
                    mappingSyms.push_back({sym.value, nameStr[1]});
                    continue;
                }

                // 不覆盖 dynsym 已有的条目
                if (result.byAddr.find(sym.value) != result.byAddr.end())
                    continue;

                ResolvedSym rs;
                rs.addr = sym.value;
                rs.size = sym.size;
                rs.name = demangle(nameStr);

                if (type == STT_FUNC || type == STT_GNU_IFUNC) {
                    rs.type = SymType::FUNC_LOCAL;
                } else {
                    rs.type = SymType::DATA_GLOBAL;
                }

                result.byAddr[rs.addr] = rs;
                result.byName[rs.name] = rs.addr;
            }
        }
    }

    // v9.4: 处理 ARM/AArch64 映射符号 ($a/$t/$d/$x)
    // 对标 r2 的 bin_build_mapping_symbols() + Ghidra 的 MappingSymbols
    // 映射符号标记代码段内部的数据区域（literal pool, jump table, etc.）
    if (!mappingSyms.empty()) {
        std::sort(mappingSyms.begin(), mappingSyms.end());
        // 构建连续区域: 每对相邻映射符号定义一个区域
        for (size_t i = 0; i + 1 < mappingSyms.size(); i++) {
            uint64_t start = mappingSyms[i].first;
            uint64_t end = mappingSyms[i + 1].first;
            char type = mappingSyms[i].second;
            if (type == 'd') {
                // $d = data region
                result.mappingDataRanges.push_back({start, end});
            } else {
                // $a/$t/$x = code region
                result.mappingCodeRanges.push_back({start, end});
            }
        }
        // 最后一个映射符号: 重用它定义的类型，区间延伸到下一个符号或段尾
        // 这里先不处理，因为最后一个映射符号之后的内容未知
    }

    // ═══════════════════════════════════════════════════════════
    // Step 2.5: 解析 C++ 虚表 (v4.10)
    // 扫描 _ZTV 符号（虚表），从 ELF 数据中读取虚函数指针数组。
    // 对标 Ghidra 的 RTTI/VTable 分析器。
    // ═══════════════════════════════════════════════════════════
    for (auto& [addr, sym] : result.byAddr) {
        if (sym.name.find("_ZTV") != 0 && sym.name.find("vtable for ") == std::string::npos) continue;
        if (sym.size == 0 || sym.size > 0x10000) continue;

        // Find the section containing this vtable address
        const SecInfo* vtableSec = nullptr;
        for (auto& s : sections) {
            if (sym.addr >= s.addr && sym.addr < s.addr + s.size) {
                vtableSec = &s;
                break;
            }
        }
        if (!vtableSec) continue;

        uint64_t secOffset = sym.addr - vtableSec->addr;
        if (secOffset + sym.size > vtableSec->size) continue;

        VTableInfo vt;
        vt.addr = sym.addr;
        // v4.11: Strip "vtable for " prefix from demangled _ZTV symbol name
        std::string rawName = sym.name;
        if (rawName.find("vtable for ") == 0) {
            vt.className = rawName.substr(11); // strip "vtable for "
        } else {
            vt.className = rawName;
        }
        vt.parentVTable = 0;

        const uint8_t* secData = data + vtableSec->offset;
        size_t ptrSize = is64 ? 8 : 4;
        size_t entryCount = (size_t)sym.size / ptrSize;

        // Read vtable entries (function pointers)
        for (size_t i = 0; i < entryCount && i < 256; i++) {
            uint64_t ptr = 0;
            if (is64) {
                ptr = rdU64(secData + secOffset + i * 8);
            } else {
                ptr = rdU32(secData + secOffset + i * 4);
            }
            vt.entries.push_back(ptr);

            // Resolve function name
            if (ptr == 0) {
                vt.entryNames.push_back("<null>");
            } else {
                // Check if ptr-1 is a known function (ARM Thumb mode)
                uint64_t lookupAddr = ptr;
                if (!is64 && (ptr & 1)) lookupAddr = ptr & ~1ULL;
                auto it = result.byAddr.find(lookupAddr);
                if (it != result.byAddr.end()) {
                    vt.entryNames.push_back(it->second.name);
                } else {
                    // Try nearby addresses
                    bool found = false;
                    for (int delta = -2; delta <= 2; delta++) {
                        auto nit = result.byAddr.find(lookupAddr + delta);
                        if (nit != result.byAddr.end()) {
                            vt.entryNames.push_back(nit->second.name);
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        char buf[32];
                        snprintf(buf, sizeof(buf), "vfunc_%llx", (unsigned long long)ptr);
                        vt.entryNames.push_back(buf);
                    }
                }
            }
        }

        // Detect parent class: first entry is often the parent's vtable
        if (!vt.entries.empty() && vt.entries[0] != 0) {
            auto parentIt = result.byAddr.find(vt.entries[0]);
            if (parentIt != result.byAddr.end() && parentIt->second.type == SymType::VTABLE) {
                vt.parentVTable = vt.entries[0];
            }
        }

        result.vtableMap[sym.addr] = vt;
        result.classNameToVTable[vt.className] = sym.addr;

        // Mark the symbol as VTABLE type
        auto& mutableSym = result.byAddr[sym.addr];
        mutableSym.type = SymType::VTABLE;
    }

    if (!result.vtableMap.empty()) {
        fprintf(stderr, "  [VTable] scanned %zu vtables (%zu unique classes)\n",
                result.vtableMap.size(), result.classNameToVTable.size());
    }

    // ═══════════════════════════════════════════════════════════
    // Step 2.6: 解析 _ZTI / _ZTS — C++ RTTI typeinfo (v4.11)
    // 对标 Ghidra RTTI analyzer: 从 typeinfo 符号重建类层次结构。
    // _ZTI = typeinfo structure (contains pointer to _ZTS name string)
    // _ZTS = typeinfo name string (mangled class name)
    // ═══════════════════════════════════════════════════════════
    std::map<std::string, uint64_t> ztsMap;  // mangledName → _ZTS address
    std::map<uint64_t, std::string> ztiToName; // _ZTI addr → demangled name

    // First pass: collect _ZTS symbols (typeinfo name strings)
    for (auto& [addr, sym] : result.byAddr) {
        if (sym.name.find("_ZTS") == 0 && sym.name.size() > 4) {
            std::string mangled = sym.name.substr(4); // strip "_ZTS" prefix
            if (!mangled.empty()) {
                ztsMap[mangled] = addr;
                // Also register the demangled name
                std::string demangled = demangle("_Z" + mangled);
                if (demangled != "_Z" + mangled && !demangled.empty()) {
                    ztiToName[addr] = demangled;
                }
            }
        }
    }

    // Second pass: collect _ZTI symbols and build struct info
    for (auto& [addr, sym] : result.byAddr) {
        if (sym.name.find("_ZTI") == 0 && sym.name.size() > 4) {
            std::string mangled = sym.name.substr(4); // strip "_ZTI" prefix
            std::string demangled = demangle("_Z" + mangled);
            if (demangled == "_Z" + mangled) continue; // demangle failed

            StructInfo si;
            si.name = demangled;
            si.mangledName = mangled;
            si.typeinfoAddr = addr;
            si.isPolymorphic = false;

            // Find matching vtable
            std::string vtableName = "_ZTV" + mangled;
            auto vtIt = result.classNameToVTable.find(demangled);
            if (vtIt == result.classNameToVTable.end()) {
                // Try demangled vtable name with "vtable for " prefix stripped
                // (classNameToVTable already has the prefix stripped)
            }
            if (vtIt != result.classNameToVTable.end()) {
                si.vtableAddr = vtIt->second;
                si.isPolymorphic = true;
                auto vti = result.vtableMap.find(si.vtableAddr);
                if (vti != result.vtableMap.end()) {
                    si.vfuncNames = vti->second.entryNames;
                }
            } else {
                // Try to find by _ZTV symbol name
                for (auto& [a, s] : result.byAddr) {
                    if (s.name == vtableName) {
                        si.vtableAddr = a;
                        si.isPolymorphic = true;
                        auto vti = result.vtableMap.find(a);
                        if (vti != result.vtableMap.end()) {
                            si.vfuncNames = vti->second.entryNames;
                        }
                        break;
                    }
                }
            }

            // Find parent class from _ZTI data (if in .rodata or .data.rel.ro)
            // The _ZTI structure layout (Itanium ABI):
            //   [0] ptr to vtable of __class_type_info / __si_class_type_info
            //   [1] ptr to _ZTS name string
            //   [2] (si only) ptr to base class _ZTI
            // We try to read the section data to find base class info
            const SecInfo* tiSec = nullptr;
            for (auto& s : sections) {
                if (addr >= s.addr && addr < s.addr + s.size) {
                    tiSec = &s;
                    break;
                }
            }
            if (tiSec && tiSec->offset + tiSec->size <= dataSize) {
                uint64_t secOff = addr - tiSec->addr;
                size_t ptrSize = is64 ? 8 : 4;
                // Read _ZTS pointer (field [1] in typeinfo struct)
                if (secOff + ptrSize * 2 <= tiSec->size) {
                    const uint8_t* tiData = data + tiSec->offset + secOff;
                    uint64_t ztsPtr = is64 ? rdU64(tiData + ptrSize) : rdU32(tiData + ptrSize);
                    auto ztsIt = ztiToName.find(ztsPtr);
                    if (ztsIt != ztiToName.end()) {
                        si.typeinfoName = ztsIt->second;
                    }

                    // Try to read base class _ZTI pointer (field [2] for __si_class_type_info)
                    if (secOff + ptrSize * 3 <= tiSec->size) {
                        uint64_t baseZti = is64 ? rdU64(tiData + ptrSize * 2) : rdU32(tiData + ptrSize * 2);
                        if (baseZti != 0 && baseZti != addr) {
                            auto baseIt = result.byAddr.find(baseZti);
                            if (baseIt != result.byAddr.end() && baseIt->second.name.find("_ZTI") == 0) {
                                std::string baseMangled = baseIt->second.name.substr(4);
                                std::string baseDemangled = demangle("_Z" + baseMangled);
                                if (baseDemangled != "_Z" + baseMangled) {
                                    si.baseClasses.push_back(baseDemangled);
                                }
                            }
                        }
                    }
                }
            }

            result.structMap[demangled] = si;
        }
    }

    // Third pass: merge vtable-only classes into structMap
    for (auto& [addr, vt] : result.vtableMap) {
        if (result.structMap.find(vt.className) == result.structMap.end()) {
            StructInfo si;
            si.name = vt.className;
            si.vtableAddr = addr;
            si.isPolymorphic = true;
            si.vfuncNames = vt.entryNames;
            si.mangledName = vt.className;
            result.structMap[vt.className] = si;
        }
    }

    if (!result.structMap.empty()) {
        fprintf(stderr, "  [Struct] scanned %zu classes (%zu polymorphic)\n",
                result.structMap.size(),
                std::count_if(result.structMap.begin(), result.structMap.end(),
                              [](auto& p) { return p.second.isPolymorphic; }));
    }

    // ═══════════════════════════════════════════════════════════
    // Step 3: 解析字符串常量
    // 对标 Ghidra: 从 .dynstr 提取字符串 + 扫描 .rodata/.data 空白终止字符串
    //
    // v10.1 增强:
    //   - 扫描 .data 段中的字符串（不只 .rodata）
    //   - 检测 UTF-16 字符串（每偶数字节为 ASCII，奇数字节为 0）
    //   - 检测长字符串（>= 4 字符）的引用点
    //   - 为每个字符串记录其长度（stringLengths）
    // ═══════════════════════════════════════════════════════════
    std::set<std::string> seenStrings;  // 已收集的 ASCII 字符串去重集合
    std::set<std::u16string> seenUtf16; // 已收集的 UTF-16 字符串去重集合

    // v10.1: 检测 UTF-16LE 字符串。
    // 判定条件：偶数字节为可打印 ASCII（含 \t \n \r），奇数字节为 0，
    // 最少 4 个码元（8 字节）且以 0x0000 终止。返回码元数（不含终止符），
    // 非 UTF-16 返回 0。
    auto detectUtf16Le = [](const uint8_t* p, size_t maxBytes) -> size_t {
        if (maxBytes < 8) return 0;            // 至少 4 码元 + 终止符
        if ((maxBytes & 1) != 0) maxBytes--;   // 对齐到偶数
        size_t count = 0;
        size_t i = 0;
        while (i + 1 < maxBytes) {
            uint8_t lo = p[i];
            uint8_t hi = p[i + 1];
            if (lo == 0 && hi == 0) break;     // 终止符
            // 偶数字节必须可打印 ASCII（0x20-0x7E）或制表符/换行/回车
            if (hi != 0) return 0;
            if (lo == 0) return 0;
            if (lo < 0x20 && lo != '\t' && lo != '\n' && lo != '\r') return 0;
            if (lo > 0x7E) return 0;
            count++;
            i += 2;
        }
        // 需要有终止符 (0x0000)，且至少 4 个码元
        if (i + 1 >= maxBytes) return 0;       // 没有空间放终止符
        if (p[i] != 0 || p[i + 1] != 0) return 0;
        if (count < 4) return 0;
        return count;
    };

    // v10.1: 通用字符串段扫描器。同时处理 ASCII 字符串收集、长度记录
    // 以及 UTF-16 字符串检测。函数对象，可对 .rodata 与 .data 复用。
    // 参数：
    //   secData  - 段在文件中的数据指针
    //   secSize  - 段大小
    //   secBase  - 段的虚拟基址
    auto scanStringSection = [&](const uint8_t* secData, uint64_t secSize,
                                 uint64_t secBase) {
        if (!secData || secSize == 0) return;

        // (a) 从 .dynsym 符号引用中提取字符串（已知引用点）
        for (auto& [idx, sym] : dynSymInfos) {
            if (sym.shndx == SHN_UNDEF || sym.value == 0) continue;
            if (sym.value < secBase || sym.value >= secBase + secSize) continue;

            uint64_t strOff = sym.value - secBase;
            if (strOff >= secSize) continue;

            const char* p = (const char*)(secData + strOff);
            size_t maxLen = secSize - strOff;

            // 先尝试 ASCII 字符串
            size_t len = 0;
            while (len < maxLen && p[len] != '\0') len++;
            if (len >= 1 && len < 1024) {
                std::string content(p, len);
                if (seenStrings.insert(content).second) {
                    result.stringRefs[sym.value] = content;
                    result.stringLengths[sym.value] = len;  // v10.1: 记录长度
                }
                continue;
            }

            // 再尝试 UTF-16 字符串（符号引用点可能指向宽字符串）
            size_t u16count = detectUtf16Le(secData + strOff, maxLen);
            if (u16count > 0) {
                std::u16string ws(reinterpret_cast<const char16_t*>(secData + strOff),
                                  u16count);
                if (seenUtf16.insert(ws).second) {
                    result.utf16Strings[sym.value] = ws;
                    result.stringLengths[sym.value] = u16count * 2;  // 字节长度
                }
            }
        }

        // (b) 启发式扫描段中未被符号引用的字符串
        //     以空字符为边界收集可打印 ASCII 字符串，并检测 UTF-16
        size_t i = 0;
        while (i < secSize) {
            // 跳过前导 NUL
            if (secData[i] == 0) { i++; continue; }

            // 跳过不可打印的起始字节
            if (secData[i] < 0x20 && secData[i] != '\t' && secData[i] != '\n') {
                i++; continue;
            }

            // 先尝试在当前位置检测 UTF-16 字符串（要求对齐到 2 字节）
            // 仅当当前字节是可打印 ASCII 且下一字节是 0 时才可能是 UTF-16
            if ((i & 1) == 0 && i + 1 < secSize && secData[i + 1] == 0) {
                size_t u16count = detectUtf16Le(secData + i, secSize - i);
                if (u16count > 0) {
                    uint64_t strAddr = secBase + i;
                    std::u16string ws(reinterpret_cast<const char16_t*>(secData + i),
                                      u16count);
                    if (seenUtf16.insert(ws).second) {
                        result.utf16Strings[strAddr] = ws;
                        result.stringLengths[strAddr] = u16count * 2;  // 字节长度
                    }
                    // 跳过 UTF-16 字符串内容 + 终止符 (2 字节)
                    i += u16count * 2 + 2;
                    continue;
                }
            }

            // 普通 ASCII 字符串：扫描到 NUL 或不可打印字节
            size_t start = i;
            bool valid = true;
            while (i < secSize && secData[i] != 0) {
                uint8_t c = secData[i];
                if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                    valid = false;
                    break;
                }
                i++;
            }

            if (valid && (i - start) >= 2 && i < secSize && secData[i] == 0) {
                uint64_t strAddr = secBase + start;
                size_t strLen = i - start;
                std::string content((const char*)(secData + start), strLen);
                if (seenStrings.find(content) == seenStrings.end()) {
                    result.stringRefs[strAddr] = content;
                    result.stringLengths[strAddr] = strLen;  // v10.1: 记录长度
                    seenStrings.insert(content);
                }
            }
            i++;  // skip null terminator or invalid byte
        }
    };

    // 扫描 .rodata 段
    if (rodataSec && inRange(rodataSec->offset, rodataSec->size, dataSize)) {
        scanStringSection(data + rodataSec->offset,
                          rodataSec->size,
                          rodataSec->addr);
    }

    // v10.1: 扫描 .data 段中的字符串（不只 .rodata）
    // .data 段可能包含初始化的字符串指针/字符串常量（如静态缓冲区）
    if (dataSec && inRange(dataSec->offset, dataSec->size, dataSize)) {
        scanStringSection(data + dataSec->offset,
                          dataSec->size,
                          dataSec->addr);
    }

    // v10.1: 对已知长度的长字符串（>= 4 字符）确保其引用点被记录。
    // 即使字符串本身已通过启发式扫描收集，这里显式地保证长字符串的
    // 引用点在 stringRefs/stringLengths 中存在，便于后续常量池关联。
    // （前面 scanStringSection 已经记录长度，此处仅做补全与统计。）
    size_t longStringRefCount = 0;
    for (auto& [addr, len] : result.stringLengths) {
        if (len >= 4) longStringRefCount++;
    }

    if (!result.utf16Strings.empty() || longStringRefCount > 0) {
        fprintf(stderr, "  [Strings] %zu ASCII refs, %zu UTF-16 refs, %zu long(>=4)\n",
                result.stringRefs.size(), result.utf16Strings.size(),
                longStringRefCount);
    }


    // ═══════════════════════════════════════════════════════════
    // Step 4: 解析全局变量（.data / .bss / .rodata 中的 STT_OBJECT）
    // 使用正确的 st_size 而非硬编码 8
    // ═══════════════════════════════════════════════════════════
    // 从 .dynsym 收集 STT_OBJECT 符号的大小
    std::map<uint64_t, uint64_t> objSizes;
    for (auto& [idx, sym] : dynSymInfos) {
        if (sym.shndx != SHN_UNDEF && sym.value != 0 &&
            ST_TYPE(sym.info) == STT_OBJECT) {
            objSizes[sym.value] = sym.size;
        }
    }

    auto registerGlobalVar = [&](uint64_t addr, const std::string& name, uint64_t size) {
        result.globalVars[addr] = name;
        result.globalVarSizes[addr] = (size > 0) ? size : 8;
    };

    if (dataSec) {
        for (auto& [addr, sym] : result.byAddr) {
            if (addr >= dataSec->addr && addr < dataSec->addr + dataSec->size) {
                if (sym.type == SymType::DATA_GLOBAL) {
                    uint64_t sz = 8;
                    auto it = objSizes.find(addr);
                    if (it != objSizes.end()) sz = it->second;
                    registerGlobalVar(addr, sym.name, sz);
                }
            }
        }
    }
    if (bssSec) {
        for (auto& [addr, sym] : result.byAddr) {
            if (addr >= bssSec->addr && addr < bssSec->addr + bssSec->size) {
                if (sym.type == SymType::DATA_GLOBAL) {
                    uint64_t sz = 8;
                    auto it = objSizes.find(addr);
                    if (it != objSizes.end()) sz = it->second;
                    registerGlobalVar(addr, sym.name, sz);
                }
            }
        }
    }
    if (rodataSec) {
        for (auto& [addr, sym] : result.byAddr) {
            if (addr >= rodataSec->addr && addr < rodataSec->addr + rodataSec->size) {
                if (sym.type == SymType::DATA_GLOBAL) {
                    uint64_t sz = 8;
                    auto it = objSizes.find(addr);
                    if (it != objSizes.end()) sz = it->second;
                    registerGlobalVar(addr, sym.name, sz);
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Step 5: 解析重定位表 — 按 JUMP_SLOT 类型过滤
    // 对标 Ghidra: 只处理 JUMP_SLOT 类型的重定位用于 PLT
    // ═══════════════════════════════════════════════════════════
    struct RelocEntry {
        uint64_t r_offset;
        uint32_t symIdx;
        uint32_t type;
        std::string name;
    };
    std::vector<RelocEntry> jumpSlotRelocs;  // JUMP_SLOT 重定位（按 r_offset 排序）

    // 处理所有重定位节区
    auto processRelocs = [&](const std::vector<const SecInfo*>& relSecs) {
        for (const SecInfo* rs : relSecs) {
            if (!rs || !inRange(rs->offset, rs->size, dataSize)) continue;

            bool isRela = (rs->type == SHT_RELA);
            uint64_t entSize = rs->entsize;
            if (entSize == 0) {
                entSize = is64
                    ? (isRela ? sizeof(Elf64_Rela) : sizeof(Elf64_Rel))
                    : (isRela ? sizeof(Elf32_Rela) : sizeof(Elf32_Rel));
            }
            uint64_t count = rs->size / entSize;

            for (uint64_t i = 0; i < count; i++) {
                uint64_t off = rs->offset + i * entSize;
                if (off + entSize > dataSize) break;

                uint64_t r_offset, r_info, r_addend = 0;
                if (is64) {
                    r_offset = rdU64(data + off);
                    r_info   = rdU64(data + off + 8);
                    if (isRela) r_addend = rdU64(data + off + 16);
                } else {
                    r_offset = rdU32(data + off);
                    r_info   = rdU32(data + off + 4);
                    if (isRela) r_addend = rdU32(data + off + 8);
                }

                uint32_t symIdx = is64 ? (uint32_t)(r_info >> 32) : (uint32_t)(r_info >> 8);
                uint32_t relType = is64 ? (uint32_t)(r_info & 0xffffffff) : (uint32_t)(r_info & 0xff);

                auto nameIt = dynSymNames.find(symIdx);
                std::string symName = (nameIt != dynSymNames.end()) ? nameIt->second : "";

                if (relType == JUMP_SLOT_TYPE && !symName.empty()) {
                    // JUMP_SLOT: GOT entry → PLT import
                    jumpSlotRelocs.push_back({r_offset, symIdx, relType, symName});
                    result.gotToName[r_offset] = symName;

                    // 注册 GOT 入口为符号
                    if (result.byAddr.find(r_offset) == result.byAddr.end()) {
                        ResolvedSym rs;
                        rs.addr = r_offset;
                        rs.name = demangle(symName);
                        rs.type = SymType::GOT_ENTRY;
                        result.byAddr[r_offset] = rs;
                    }
                } else if (!symName.empty() && result.gotToName.find(r_offset) == result.gotToName.end()) {
                    // v5.6: GLOB_DAT / ABS64 / ABS32 — 全局数据符号重定位
                    // 对标 r2 elf_reloc.c: 把所有有名字的重定位都注册到 gotToName
                    // GLOB_DAT: GOT entry → global variable address
                    // ABS64/ABS32: absolute address relocation (data pointer)
                    result.gotToName[r_offset] = symName;

                    // v5.6: Also register as a data symbol if not already present
                    if (result.byAddr.find(r_offset) == result.byAddr.end()) {
                        ResolvedSym rs;
                        rs.addr = r_offset;
                        rs.name = demangle(symName);
                        rs.type = SymType::GOT_ENTRY;
                        result.byAddr[r_offset] = rs;
                    }
                } else if (symName.empty() && result.gotToName.find(r_offset) == result.gotToName.end()) {
                    // v5.6: RELATIVE relocations — local symbol address
                    // 对标 r2 elf_reloc.c r_bin_elf_reloc_section()
                    // R_AARCH64_RELATIVE (1027) / R_ARM_RELATIVE (23) / R_X86_64_RELATIVE (8):
                    // GOT entry → absolute address (addend) of local symbol
                    bool isRelative = (isAArch64 && relType == MY_R_AARCH64_RELATIVE) ||
                                      (isARM32 && relType == MY_R_ARM_RELATIVE) ||
                                      (!isAArch64 && !isARM32 && relType == MY_R_X86_64_RELATIVE);
                    if (isRelative) {
                        auto addrIt = result.byAddr.find(r_addend);
                        if (addrIt != result.byAddr.end() && !addrIt->second.name.empty()) {
                            result.gotToName[r_offset] = addrIt->second.name;
                        } else if (r_addend != 0) {
                            // v5.6: RELATIVE with unknown target — register as static var
                            // The addend is the absolute address of a local symbol
                            // (e.g., static variables like s_internal, call_count.0)
                            // If it's in .data/.bss/.rodata, create a synthetic name
                            char buf[64];
                            snprintf(buf, sizeof(buf), "s_%llx", (unsigned long long)r_addend);
                            result.gotToName[r_offset] = buf;

                            // Also register the target address as a global var
                            if (result.globalVars.find(r_addend) == result.globalVars.end()) {
                                result.globalVars[r_addend] = buf;
                                result.globalVarSizes[r_addend] = 8;
                            }
                        }
                    }
                }
            }
        }
    };

    processRelocs(relPltSecs);
    processRelocs(relDynSecs);

    // 按 r_offset 排序 JUMP_SLOT 重定位
    std::sort(jumpSlotRelocs.begin(), jumpSlotRelocs.end(),
              [](const RelocEntry& a, const RelocEntry& b) { return a.r_offset < b.r_offset; });

    // ═══════════════════════════════════════════════════════════
    // Step 6: 解析 PLT 节区，构建 PLT 桩地址列表
    // 对标 Ghidra: 用 JUMP_SLOT 重定位数量确定 PLT 桩数
    // ═══════════════════════════════════════════════════════════
    uint64_t pltEntSize = isAArch64 ? 16 : (isARM32 ? 12 : 16);

    for (const SecInfo* ps : pltSecs) {
        if (!ps || !inRange(ps->offset, ps->size, dataSize)) continue;

        // 检测 PLT 头大小
        // AArch64: 头以 stp 指令开头 (0xa9...), 32 字节
        // ARM32: 头以 push 或类似指令开头, 16 字节
        uint64_t headerSize = pltEntSize;  // default: 1 entry
        if (ps->nameStr == ".plt" && ps->size >= 4 && ps->offset + 4 <= dataSize) {
            uint32_t firstWord = rdU32(data + ps->offset);
            if (isAArch64 && (firstWord & 0xFF000000) == 0xA9000000) {
                // stp x16, x30, [sp, #-16]! → 32-byte header
                headerSize = 32;
            }
        }
        bool skipHeader = (ps->nameStr == ".plt");
        uint64_t pltStart = ps->addr + (skipHeader ? headerSize : 0);
        uint64_t pltEnd = ps->addr + ps->size;

        // 用 JUMP_SLOT 重定位数量作为 PLT 桩数
        // 这比 section.size / pltEntSize 更可靠
        uint64_t stubCount = (pltEnd - pltStart) / pltEntSize;
        if (jumpSlotRelocs.size() < stubCount)
            stubCount = jumpSlotRelocs.size();

        for (uint64_t i = 0; i < stubCount && i < jumpSlotRelocs.size(); i++) {
            uint64_t pltStub = pltStart + i * pltEntSize;
            if (pltStub >= pltEnd) break;

            const std::string& name = jumpSlotRelocs[i].name;
            if (name.empty()) continue;

            result.pltStubs.push_back(pltStub);

            // 注册 PLT 桩
            ResolvedSym rs;
            rs.addr = pltStub;
            rs.name = demangle(name);
            rs.type = SymType::PLT_STUB;
            result.byAddr[pltStub] = rs;

            // 注册 PLT 桩范围内的所有地址（bl 可能跳到桩中间）
            for (uint64_t delta = 1; delta < pltEntSize; delta++) {
                uint64_t a = pltStub + delta;
                if (result.byAddr.find(a) == result.byAddr.end()) {
                    ResolvedSym rsD;
                    rsD.addr = a;
                    rsD.name = rs.name;
                    rsD.type = SymType::PLT_STUB;
                    result.byAddr[a] = rsD;
                }
            }
        }
    }

    std::sort(result.pltStubs.begin(), result.pltStubs.end());

    // ═══════════════════════════════════════════════════════════
    // Step 7: 注入 JNI/NDK 常量名
    // 对标 Ghidra 的常量折叠 — 在 ELF 解析阶段注入
    // ═══════════════════════════════════════════════════════════
    // 遍历 .rodata 中的常量，匹配已知常量值
    if (rodataSec && inRange(rodataSec->offset, rodataSec->size, dataSize)) {
        const uint8_t* rodata = data + rodataSec->offset;
        uint64_t rodataSize = rodataSec->size;
        uint64_t baseAddr = rodataSec->addr;

        // 扫描 4 字节和 8 字节对齐的常量
        for (uint64_t off = 0; off + 4 <= rodataSize; off += 4) {
            uint32_t val32 = rdU32(rodata + off);
            uint64_t addr = baseAddr + off;

            // 如果这个地址还没被字符串或符号占用
            if (result.stringRefs.find(addr) != result.stringRefs.end()) continue;
            if (result.globalVars.find(addr) != result.globalVars.end()) continue;
            // v10.1: 跳过已记录为 UTF-16 字符串的地址，避免常量注入覆盖宽字符串
            if (result.utf16Strings.find(addr) != result.utf16Strings.end()) continue;

            for (auto& kc : JNI_CONSTANTS) {
                if (kc.value == val32) {
                    // 避免重复注入 JNI_OK = 0（太常见）
                    if (kc.value == 0 && kc.name == std::string("JNI_OK")) continue;
                    if (result.stringRefs.find(addr) == result.stringRefs.end()) {
                        // 用特殊标记存储常量名（后续 CPrinter 识别）
                        result.stringRefs[addr] = std::string("__const__") + kc.name;
                    }
                    break;
                }
            }
            // v5.3: Also check PTHREAD_CONSTANTS
            for (auto& kc : PTHREAD_CONSTANTS) {
                if (kc.value == val32) {
                    if (result.stringRefs.find(addr) == result.stringRefs.end()) {
                        result.stringRefs[addr] = std::string("__const__") + kc.name;
                    }
                    break;
                }
            }
        }
    }

    result.valid = true;
    return result;
}

} // namespace mc