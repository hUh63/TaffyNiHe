#pragma once
// indirect_call_resolver.hpp — 间接调用目标解析器 v9.0
// 对标 Ghidra 的 indirect call resolution：
//   1. 虚函数调用 → 通过 vtable offset 映射到函数名
//   2. 函数指针调用 → 通过 GOT 或全局变量追踪
//   3. 回调函数 → 通过参数类型推断

#include "elf_symbol_resolver.hpp"
#include "microcode.hpp"
#include <string>
#include <map>
#include <set>
#include <cstdint>

namespace mc {

// 间接调用解析结果
struct ResolvedCall {
    std::string target_name;    // 解析后的函数名（如 "Actor::tick()"）
    std::string class_name;     // 所属类名（虚函数调用时）
    uint64_t vtable_offset = 0; // 虚表偏移
    bool is_virtual = false;    // 是否为虚函数调用
    bool is_resolved = false;   // 是否解析成功
};

// 间接调用解析器
class IndirectCallResolver {
public:
    IndirectCallResolver(const ElfSymbolMap& elfSyms, bool isAArch64);

    // 通过虚表偏移解析调用目标
    // offset: 虚表条目偏移（字节）
    // 返回解析后的函数名，失败返回空字符串
    ResolvedCall resolveVtableCall(uint64_t offset) const;

    // 通过目标地址解析（在虚表条目中查找）
    // target_addr: 调用的目标地址
    ResolvedCall resolveByAddress(uint64_t target_addr) const;

    // 通过寄存器加载模式解析
    // 当 emitter 看到 ldr rN, [vtable_ptr, #offset]; blx rN 时
    // 可以通过 offset 和 base 信息来解析
    ResolvedCall resolveByOffset(uint64_t offset, uint64_t vtable_base) const;

    // 获取虚表共识映射（offset → 函数名）
    const std::map<int, std::string>& getVtableConsensus() const { return consensus_; }

    // 获取类名（通过虚表地址）
    std::string getClassName(uint64_t vtable_addr) const;

private:
    const ElfSymbolMap& elf_syms_;
    std::map<int, std::string> consensus_;  // offset → best function name
    int entry_size_;

    void buildConsensus();
};

// ──── 实现 ────

inline IndirectCallResolver::IndirectCallResolver(const ElfSymbolMap& elfSyms, bool isAArch64)
    : elf_syms_(elfSyms)
{
    entry_size_ = isAArch64 ? 8 : 4;
    buildConsensus();
}

inline void IndirectCallResolver::buildConsensus() {
    // 统计所有虚表中相同 offset 对应的函数名，取出现次数最多的
    std::map<int, std::map<std::string, int>> offsetCounts;

    for (auto& [vaddr, vt] : elf_syms_.vtableMap) {
        for (size_t i = 0; i < vt.entries.size() && i < vt.entryNames.size(); i++) {
            if (vt.entries[i] == 0) continue;
            if (vt.entryNames[i].empty() || vt.entryNames[i] == "<null>") continue;
            int offset = (int)(i * entry_size_);
            offsetCounts[offset][vt.entryNames[i]]++;
        }
    }

    for (auto& [offset, nameCounts] : offsetCounts) {
        std::string bestName;
        int bestCount = 0;
        for (auto& [name, count] : nameCounts) {
            if (count > bestCount) {
                bestCount = count;
                bestName = name;
            }
        }
        if (!bestName.empty()) {
            consensus_[offset] = bestName;
        }
    }
}

inline ResolvedCall IndirectCallResolver::resolveVtableCall(uint64_t offset) const {
    ResolvedCall result;
    int off = (int)offset;
    auto it = consensus_.find(off);
    if (it != consensus_.end()) {
        result.target_name = it->second;
        result.vtable_offset = offset;
        result.is_virtual = true;
        result.is_resolved = true;
    }
    return result;
}

inline ResolvedCall IndirectCallResolver::resolveByAddress(uint64_t target_addr) const {
    ResolvedCall result;

    // v9.26: Skip null targets — target_addr=0 is common for indirect calls
    // and would incorrectly match vtable null entries named "<null>".
    if (target_addr == 0) return result;

    // 在虚表条目中查找目标地址
    for (auto& [vaddr, vt] : elf_syms_.vtableMap) {
        for (size_t i = 0; i < vt.entries.size() && i < vt.entryNames.size(); i++) {
            if (vt.entries[i] == target_addr) {
                // v9.26: Skip null entries
                if (vt.entryNames[i].empty() || vt.entryNames[i] == "<null>") continue;
                result.target_name = vt.entryNames[i];
                result.class_name = vt.className;
                result.vtable_offset = i * entry_size_;
                result.is_virtual = true;
                result.is_resolved = true;
                return result;
            }
        }
    }

    return result;
}

inline ResolvedCall IndirectCallResolver::resolveByOffset(uint64_t offset, uint64_t vtable_base) const {
    ResolvedCall result;

    // 先尝试通过共识映射解析
    int off = (int)offset;
    auto consIt = consensus_.find(off);
    if (consIt != consensus_.end()) {
        result.target_name = consIt->second;
        result.vtable_offset = offset;
        result.is_virtual = true;
        result.is_resolved = true;

        // 查找类名
        for (auto& [vaddr, vt] : elf_syms_.vtableMap) {
            if (vaddr == vtable_base) {
                result.class_name = vt.className;
                break;
            }
        }
        return result;
    }

    // 在虚表条目中查找
    for (auto& [vaddr, vt] : elf_syms_.vtableMap) {
        if (vtable_base != 0 && vaddr != vtable_base) continue;
        for (size_t i = 0; i < vt.entries.size() && i < vt.entryNames.size(); i++) {
            if ((uint64_t)(i * entry_size_) == offset) {
                // v9.26: Skip null entries
                if (vt.entryNames[i].empty() || vt.entryNames[i] == "<null>") continue;
                result.target_name = vt.entryNames[i];
                result.class_name = vt.className;
                result.vtable_offset = offset;
                result.is_virtual = true;
                result.is_resolved = true;
                return result;
            }
        }
    }

    return result;
}

inline std::string IndirectCallResolver::getClassName(uint64_t vtable_addr) const {
    auto it = elf_syms_.vtableMap.find(vtable_addr);
    if (it != elf_syms_.vtableMap.end()) {
        return it->second.className;
    }
    return "";
}

} // namespace mc