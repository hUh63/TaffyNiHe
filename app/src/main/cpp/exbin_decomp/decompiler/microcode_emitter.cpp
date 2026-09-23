// microcode_emitter.cpp — ARM64 instruction decomposer implementation
// Translates Capstone-disassembled ARM64 instructions into microcode IR.

#include "microcode_emitter.hpp"
#include <algorithm>
#include <cctype>
#include <cstring>
#include <iostream>
#include <sstream>
#include <set>

// Debug control
#ifndef DBG_CFG_VERBOSE
#define DBG_CFG_VERBOSE 0
#endif
#if DBG_CFG_VERBOSE
#define DBG_PRINT(...) fprintf(stderr, __VA_ARGS__)
#else
#define DBG_PRINT(...) ((void)0)
#endif

namespace mc {

// ── v3.19: Little-endian readers for ELF parsing (no alignment requirement) ──
static uint16_t leU16(const uint8_t* p) { return (uint16_t)p[0] | ((uint16_t)p[1] << 8); }
static uint32_t leU32(const uint8_t* p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
           ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}
static uint64_t leU64(const uint8_t* p) {
    return (uint64_t)leU32(p) | ((uint64_t)leU32(p + 4) << 32);
}

// ── v3.19: Read 4 bytes from ELF at given virtual address ──
// Parses ELF program headers (PT_LOAD segments) to convert vaddr → file offset.
// This is needed for ARM32 LDR [pc, #off] literal pool resolution.
// 对标 Ghidra LoadImage::loadFill: translate virtual address to raw bytes.
bool MicrocodeEmitter::readElfU32(uint64_t vaddr, uint32_t& out) const {
    if (!elf_data_ || elf_data_size_ < 16) return false;

    // Verify ELF magic
    if (elf_data_[0] != 0x7f || elf_data_[1] != 'E' ||
        elf_data_[2] != 'L' || elf_data_[3] != 'F') return false;

    bool is64 = (elf_data_[4] == 2);  // ELFCLASS64

    // Parse ELF header for program header table location
    uint64_t e_phoff;
    uint16_t e_phentsize, e_phnum;

    if (is64) {
        if (elf_data_size_ < 64) return false;
        e_phoff     = leU64(elf_data_ + 32);
        e_phentsize = leU16(elf_data_ + 54);
        e_phnum     = leU16(elf_data_ + 56);
    } else {
        if (elf_data_size_ < 52) return false;
        e_phoff     = leU32(elf_data_ + 28);
        e_phentsize = leU16(elf_data_ + 42);
        e_phnum     = leU16(elf_data_ + 44);
    }

    if (e_phoff == 0 || e_phnum == 0 || e_phentsize == 0) return false;
    if (e_phoff + (size_t)e_phnum * e_phentsize > elf_data_size_) return false;

    // PT_LOAD = 1
    const uint32_t PT_LOAD = 1;

    // Find the PT_LOAD segment containing vaddr
    for (uint16_t i = 0; i < e_phnum; i++) {
        const uint8_t* ph = elf_data_ + e_phoff + (size_t)i * e_phentsize;
        uint32_t p_type = leU32(ph);
        if (p_type != PT_LOAD) continue;

        uint64_t p_offset, p_vaddr, p_filesz;
        if (is64) {
            p_offset = leU64(ph + 8);
            p_vaddr  = leU64(ph + 16);
            p_filesz = leU64(ph + 32);
        } else {
            p_offset = leU32(ph + 4);
            p_vaddr  = leU32(ph + 8);
            p_filesz = leU32(ph + 16);
        }

        if (vaddr >= p_vaddr && vaddr + 4 <= p_vaddr + p_filesz) {
            uint64_t file_off = vaddr - p_vaddr + p_offset;
            if (file_off + 4 > elf_data_size_) return false;
            out = leU32(elf_data_ + file_off);
            return true;
        }
    }

    return false;
}

// ── Operand splitting: split by comma at bracket depth 0 ──
// Tracks both [] (memory operands) and {} (register lists) to avoid
// splitting commas inside them.
std::vector<std::string> MicrocodeEmitter::splitOps(const std::string& op_str) {
    std::vector<std::string> result;
    std::string cur;
    int bracket = 0;
    int brace = 0;
    for (char c : op_str) {
        if (c == '[') bracket++;
        else if (c == ']') bracket--;
        else if (c == '{') brace++;
        else if (c == '}') brace--;
        if (c == ',' && bracket == 0 && brace == 0) {
            // trim
            size_t a = cur.find_first_not_of(" \t");
            size_t b = cur.find_last_not_of(" \t");
            if (a != std::string::npos)
                result.push_back(cur.substr(a, b - a + 1));
            else
                result.push_back("");
            cur.clear();
        } else {
            cur += c;
        }
    }
    // last operand
    size_t a = cur.find_first_not_of(" \t");
    size_t b = cur.find_last_not_of(" \t");
    if (a != std::string::npos)
        result.push_back(cur.substr(a, b - a + 1));
    return result;
}

// ── Check if string is a register name ──
bool MicrocodeEmitter::isReg(const std::string& s) {
    if (s.empty()) return false;
    char c = s[0];
    // AArch64: x0-x30, w0-w30, d0-d31, s0-s31, q0-q31, v0-v31
    if (c == 'x' || c == 'w' || c == 'd' || c == 's' || c == 'q' || c == 'v') {
        if (s.length() < 2) return false;
        for (size_t i = 1; i < s.length(); i++) {
            if (!std::isdigit((unsigned char)s[i])) return false;
        }
        return true;
    }
    // ARM32: r0-r15
    if (c == 'r') {
        if (s.length() < 2) return false;
        for (size_t i = 1; i < s.length(); i++) {
            if (!std::isdigit((unsigned char)s[i])) return false;
        }
        return true;
    }
    return s == "sp" || s == "lr" || s == "fp" || s == "pc" ||
           s == "xzr" || s == "wzr" || s == "ip" || s == "sb" || s == "sl";
}

// ── Check if string is an immediate ──
bool MicrocodeEmitter::isImm(const std::string& s) {
    if (s.empty()) return false;
    std::string t = s;
    if (t[0] == '#') t = t.substr(1);
    if (t.empty()) return false;
    if (t[0] == '-') {
        if (t.length() == 1) return false;
        t = t.substr(1);
    }
    if (t.substr(0, 2) == "0x" || t.substr(0, 2) == "0X") {
        for (size_t i = 2; i < t.length(); i++) {
            if (!std::isxdigit((unsigned char)t[i])) return false;
        }
        return t.length() > 2;
    }
    for (char c : t) {
        if (!std::isdigit((unsigned char)c)) return false;
    }
    return true;
}

// ── Strip leading # from immediate ──
std::string MicrocodeEmitter::stripImm(const std::string& s) {
    if (!s.empty() && s[0] == '#') return s.substr(1);
    return s;
}

// ── Parse register name to mreg ──
int MicrocodeEmitter::parseReg(const std::string& s) {
    return mba_.getMreg(s);
}

// ── Parse immediate value ──
int64_t MicrocodeEmitter::parseImm(const std::string& s) {
    std::string t = stripImm(s);
    if (t.empty()) return 0;
    try {
        if (t[0] == '-') {
            return -(int64_t)std::stoull(t.substr(1), nullptr, 0);
        }
        return (int64_t)std::stoull(t, nullptr, 0);
    } catch (const std::exception&) {
        return 0;
    }
}

// ── Parse memory operand: [base, #offset] or [base, index, lsl #n] ──
MicrocodeEmitter::MemOperand MicrocodeEmitter::parseMemOp(const std::string& s) {
    MemOperand mo;
    std::string t = s;
    bool pre_index = (t.back() == '!');
    if (pre_index) {
        mo.pre_indexed = true;
        t = t.substr(0, t.length() - 1);
    }
    // Remove brackets
    if (t.front() == '[') t = t.substr(1);
    if (t.back() == ']') t = t.substr(0, t.length() - 1);

    // Split by comma inside brackets
    std::vector<std::string> parts;
    std::string cur;
    for (char c : t) {
        if (c == ',') {
            size_t a = cur.find_first_not_of(" \t");
            size_t b = cur.find_last_not_of(" \t");
            if (a != std::string::npos)
                parts.push_back(cur.substr(a, b - a + 1));
            cur.clear();
        } else {
            cur += c;
        }
    }
    size_t a = cur.find_first_not_of(" \t");
    size_t b = cur.find_last_not_of(" \t");
    if (a != std::string::npos)
        parts.push_back(cur.substr(a, b - a + 1));

    if (parts.empty()) return mo;

    // First part is always base register
    mo.base_mreg = parseReg(parts[0]);

    if (parts.size() >= 2) {
        // Check if second part is immediate or register
        if (parts[1][0] == '#') {
            mo.offset = parseImm(parts[1]);
        } else if (isReg(parts[1])) {
            mo.has_index = true;
            mo.index_mreg = parseReg(parts[1]);
        }
        // Check for shift: "lsl #2"
        if (parts.size() >= 3) {
            if (parts[2].substr(0, 3) == "lsl") {
                std::string shiftStr = parts[2].substr(3);
                shiftStr.erase(0, shiftStr.find_first_not_of(" \t"));
                if (!shiftStr.empty() && shiftStr[0] == '#')
                    shiftStr = shiftStr.substr(1);
                try {
                    mo.shift = (int)std::stoull(shiftStr, nullptr, 0);
                } catch (const std::exception&) {
                    mo.shift = 0;
                }
            }
        }
    }
    return mo;
}

// ── Map b.cond mnemonic to CondCode ──
static CondCode mapCond(const std::string& suffix) {
    if (suffix == "eq") return CC_EQ;
    if (suffix == "ne") return CC_NE;
    if (suffix == "hs" || suffix == "cs") return CC_HS;
    if (suffix == "lo" || suffix == "cc") return CC_LO;
    if (suffix == "hi") return CC_HI;
    if (suffix == "ls") return CC_LS;
    if (suffix == "ge") return CC_GE;
    if (suffix == "lt") return CC_LT;
    if (suffix == "gt") return CC_GT;
    if (suffix == "le") return CC_LE;
    if (suffix == "mi") return CC_MI;
    if (suffix == "pl") return CC_PL;
    if (suffix == "vs") return CC_VS;
    if (suffix == "vc") return CC_VC;
    if (suffix == "al") return CC_AL;
    return CC_NONE;
}

// ── ADRP+ADD fusion check ──
bool MicrocodeEmitter::tryAdrpFusion(int dst_mreg, int64_t add_imm) {
    auto it = adrp_state_.find(dst_mreg);
    if (it == adrp_state_.end() || !it->second.valid) {
        return false;
    }

    uint64_t full_addr = it->second.page_addr + (uint64_t)add_imm;

    adrp_state_.erase(it);
    reg_state_[dst_mreg] = RegState(); reg_state_[dst_mreg].kind = RegState::IMM_CONST; reg_state_[dst_mreg].value = full_addr;

    // Check if this address is a known string
    auto str_it = mba_.string_refs.find(full_addr);
    if (str_it != mba_.string_refs.end()) {
        return true;  // caller will emit MOP_STR
    }
    // v10.1: Also check UTF-16 strings — caller will handle UTF-16 emit
    if (mba_.utf16_strings.find(full_addr) != mba_.utf16_strings.end()) {
        return true;
    }
    // Check if this address is a known global
    auto glob_it = mba_.global_names.find(full_addr);
    if (glob_it != mba_.global_names.end()) {
        return true;
    }
    return true;  // address resolved regardless
}

// ════════════════════════════════════════════════════════════════════
// v10.1: resolveStringRef — resolve a .rodata/.data address to string
// content. Checks ASCII string_refs first, then UTF-16 utf16_strings.
// On a UTF-16 hit, the content is converted to a UTF-8 best-effort
// representation (ASCII-range chars passed through; non-ASCII emitted
// as \uXXXX) so it can be stored in sym_name (a std::string).
// Returns true on any string hit.
// ════════════════════════════════════════════════════════════════════
bool MicrocodeEmitter::resolveStringRef(uint64_t addr, std::string& out_str,
                                        bool& is_utf16, size_t& out_len) const {
    is_utf16 = false;
    out_len = 0;

    // (1) ASCII string reference
    auto str_it = mba_.string_refs.find(addr);
    if (str_it != mba_.string_refs.end()) {
        // v3.20: __const__ prefix entries are constant identifiers, not strings
        if (str_it->second.size() > 9 &&
            str_it->second.substr(0, 9) == "__const__") {
            return false;
        }
        out_str = str_it->second;
        is_utf16 = false;
        // Look up recorded length (fallback to content length)
        auto len_it = mba_.string_lengths.find(addr);
        out_len = (len_it != mba_.string_lengths.end()) ? len_it->second
                                                        : out_str.size();
        return true;
    }

    // (2) UTF-16 string reference
    auto u16_it = mba_.utf16_strings.find(addr);
    if (u16_it != mba_.utf16_strings.end()) {
        // Best-effort UTF-16 → UTF-8/ASCII conversion for sym_name storage.
        // ASCII-range code units pass through; others become \uXXXX escapes
        // so the CPrinter can render a readable L"..." literal later.
        std::string conv;
        conv.reserve(u16_it->second.size());
        for (char16_t ch : u16_it->second) {
            if (ch >= 0x20 && ch < 0x7F) {
                conv.push_back((char)ch);
            } else {
                char buf[8];
                snprintf(buf, sizeof(buf), "\\u%04x", (unsigned)ch);
                conv += buf;
            }
        }
        out_str = conv;
        is_utf16 = true;
        auto len_it = mba_.string_lengths.find(addr);
        out_len = (len_it != mba_.string_lengths.end())
                      ? len_it->second
                      : u16_it->second.size() * 2;
        return true;
    }

    return false;
}

// ── Start a new basic block ──
void MicrocodeEmitter::startBlock(uint64_t addr) {
    // Check if a block already starts at this address
    auto it = block_at_.find(addr);
    if (it != block_at_.end()) {
        cur_block_ = it->second;
        return;
    }
    cur_block_ = mba_.newBlock(addr);
    block_at_[addr] = cur_block_;
}

// ── Emit an instruction into the current block ──
void MicrocodeEmitter::emitInsn(MicroInsn* insn) {
    if (cur_block_ < 0) {
        cur_block_ = mba_.newBlock(0);
    }
    mba_.getBlock(cur_block_)->appendInsn(insn);
}

// v3.11: Remove the last instruction from a block (for peephole optimization)
// Frees the instruction memory and updates head/tail pointers.
void MicrocodeEmitter::removeLastInsn(MicroBlock* blk) {
    if (!blk || !blk->tail) return;
    MicroInsn* last = blk->tail;
    if (blk->head == last) {
        // Only instruction in block
        blk->head = blk->tail = nullptr;
    } else {
        blk->tail = last->prev;
        if (blk->tail) blk->tail->next = nullptr;
    }
    delete last;
}

// ════════════════════════════════════════════════════════════════════
// v3.13: foldAtomicCas — Post-emit peephole pass
// 识别 ldrex + [可选无关] + sub/add + strex + ldc(status=0) 模式
// 合并为 __sync_fetch_and_sub/add(ptr, imm)
// 参考 Ghidra: LDREX Rd,[Rn] → Rd=*Rn; STREX Rd,Rm,[Rn] → 独占存储
// Ghidra 不做这个合并（用 CALLOTHER），但我们需要以减少输出冗余
// ════════════════════════════════════════════════════════════════════
void MicrocodeEmitter::foldAtomicCas() {
    // v3.16: Enhanced atomic pattern matching (Ghidra-inspired).
    // Key improvements:
    // 1. Skip __sync_synchronize barriers (don't count as "unrelated")
    // 2. Handle teq/cmp + bne retry pattern after strex
    // 3. Mark dmb barriers as dead when merging
    // 4. Handle ldrex+strex (no arithmetic) as __sync_val_compare_and_swap
    //
    // v4.6: Track atomic call adjustments.
    // __sync_fetch_and_sub returns the OLD value (before subtract).
    // The original ARM code did "sub r0, r0, #K; cmp r0, #0" which checks
    // if "old - K == 0". After folding, we need "cmp r0, #K" instead.
    // We record (def_mreg, operand) pairs and fix comparison immediates
    // in a cross-block post-pass.
    std::vector<std::pair<int, int64_t>> atomic_adjust;  // (def_mreg, sub_operand)
    for (auto& blk_up : mba_.blocks) {
        if (!blk_up) continue;
        auto* blk = blk_up.get();

        MicroInsn* insn = blk->head;
        while (insn) {
            if (insn->opcode == OP_LOAD && (insn->iprops & IPROP_ATOMIC)) {
                int ldrexDst = insn->def_mreg;
                int ldrexBase = insn->l.mem_base;
                int ldrexWidth = insn->l.width;
                uint64_t ldrexAddr = insn->ea;

                // Scan forward for arith + strex, skipping barriers
                MicroInsn* scan = insn->next;
                MicroInsn* arithInsn = nullptr;
                MicroInsn* strexInsn = nullptr;
                MicroInsn* statusInsn = nullptr;
                std::vector<MicroInsn*> barriers;  // dmb barriers to remove
                int unrelated = 0;
                int arithDst = -1;  // destination of the arithmetic instruction

                while (scan && unrelated < 10) {
                    if (scan->opcode == OP_CALL && scan->call_info &&
                        scan->call_info->target_name == "__sync_synchronize") {
                        // Memory barrier — skip without counting as unrelated
                        barriers.push_back(scan);
                    } else if (scan->opcode == OP_SUB || scan->opcode == OP_ADD) {
                        // v3.16: Handle two patterns:
                        // Pattern A: sub r2, r2, #1 (modifies ldrex target in-place)
                        // Pattern B: sub r3, r2, #1 (writes to new register)
                        // For Pattern A: def_mreg == ldrexDst and l.mreg == ldrexDst
                        // For Pattern B: l.mreg == ldrexDst (reads ldrex result)
                        bool readsLdrexDst = false;
                        if (scan->l.isReg() && scan->l.mreg == ldrexDst) readsLdrexDst = true;
                        if (scan->r.isReg() && scan->r.mreg == ldrexDst) readsLdrexDst = true;

                        if (readsLdrexDst && !arithInsn) {
                            arithInsn = scan;
                            arithDst = scan->def_mreg;  // could be ldrexDst or a new reg
                        } else {
                            unrelated++;
                        }
                    } else if (scan->opcode == OP_STORE && (scan->iprops & IPROP_ATOMIC)) {
                        // v3.16: strex source can be either ldrexDst (Pattern A)
                        // or arithDst (Pattern B)
                        int strexSrc = scan->l.mreg;
                        bool srcMatches = (strexSrc == ldrexDst) ||
                                          (arithInsn && strexSrc == arithDst);
                        if (srcMatches && scan->d.mem_base == ldrexBase) {
                            strexInsn = scan;
                            break;
                        } else {
                            unrelated++;
                        }
                    } else if (scan->opcode == OP_LDC) {
                        // Could be strex status=0 or other constant load
                        unrelated++;
                    } else {
                        unrelated++;
                    }
                    scan = scan->next;
                }

                if (strexInsn) {
                    // Found ldrex [→ arith] → strex pattern
                    // Look for status=0 LDC after strex
                    MicroInsn* afterStrex = strexInsn->next;
                    if (afterStrex && afterStrex->opcode == OP_LDC &&
                        afterStrex->r.isImm() && afterStrex->r.imm == 0) {
                        statusInsn = afterStrex;
                    }

                    // Also look for teq/cmp + bne retry pattern after strex
                    // teq r6, #0 → OP_SUB with def_mreg=-1; bne retry → OP_CBRANCH
                    // cbnz w4, addr → OP_CBRANCH (direct branch on reg != 0, ARM64)
                    // These should be marked dead since we're merging to atomic call
                    MicroInsn* teqInsn = nullptr;
                    MicroInsn* bneInsn = nullptr;
                    MicroInsn* afterStatus = statusInsn ? statusInsn->next : strexInsn->next;
                    MicroInsn* retryScan = afterStatus;
                    int retryCount = 0;
                    while (retryScan && retryCount < 4) {
                        if ((retryScan->opcode == OP_SUB || retryScan->opcode == OP_ADD) &&
                            retryScan->def_mreg == -1) {
                            // Comparison instruction (teq/cmp)
                            teqInsn = retryScan;
                        } else if (retryScan->opcode == OP_CBRANCH) {
                            // v3.16: Accept CBRANCH with or without preceding teq
                            // ARM64 cbnz is a direct conditional branch (no teq needed)
                            bneInsn = retryScan;
                            break;
                        } else if (retryScan->opcode == OP_CALL && retryScan->call_info &&
                                   retryScan->call_info->target_name == "__sync_synchronize") {
                            barriers.push_back(retryScan);
                        }
                        retryScan = retryScan->next;
                        retryCount++;
                    }

                    if (arithInsn) {
                        // ldrex + arith + strex → __sync_fetch_and_sub/add
                        bool isAdd = (arithInsn->opcode == OP_ADD);
                        int64_t operand = 0;
                        if (arithInsn->r.isImm()) {
                            operand = arithInsn->r.imm;
                        }

                        std::string funcName = isAdd ? "__sync_fetch_and_add" : "__sync_fetch_and_sub";
                        auto* atomicCall = new MicroInsn(OP_CALL, ldrexAddr);
                        atomicCall->src_asm = funcName;
                        atomicCall->def_mreg = ldrexDst;
                        atomicCall->d = Mop::reg(ldrexDst, ldrexWidth);
                        atomicCall->call_info = std::make_shared<CallInfo>();
                        atomicCall->call_info->is_indirect = false;
                        atomicCall->call_info->target_name = funcName;
                        atomicCall->call_info->arg_count = 2;
                        atomicCall->call_info->has_return = true;
                        atomicCall->iprops = IPROP_VOLATILE | IPROP_CALL | IPROP_ATOMIC;
                        atomicCall->l = Mop::reg(ldrexBase, ldrexWidth);
                        atomicCall->r = Mop::imm64(operand, ldrexWidth);

                        // v4.6: Record that __sync_fetch_and_sub result is OLD value.
                        // The subsequent cmp r0, #0 should be cmp r0, #operand.
                        if (!isAdd) {
                            atomic_adjust.push_back({ldrexDst, operand});
                        } else {
                            // For fetch_and_add, cmp r0, #0 should be cmp r0, #-operand
                            atomic_adjust.push_back({ldrexDst, -operand});
                        }

                        // Mark dead instructions
                        insn->iprops |= IPROP_DEAD;
                        arithInsn->iprops |= IPROP_DEAD;
                        strexInsn->iprops |= IPROP_DEAD;
                        if (statusInsn) statusInsn->iprops |= IPROP_DEAD;
                        if (teqInsn) teqInsn->iprops |= IPROP_DEAD;
                        if (bneInsn) bneInsn->iprops |= IPROP_DEAD;
                        for (auto* b : barriers) b->iprops |= IPROP_DEAD;

                        // Insert atomic call before ldrex
                        if (insn->prev) {
                            insn->prev->next = atomicCall;
                            atomicCall->prev = insn->prev;
                            atomicCall->next = insn;
                            insn->prev = atomicCall;
                        } else {
                            blk->head = atomicCall;
                            atomicCall->next = insn;
                            insn->prev = atomicCall;
                        }
                    } else {
                        // ldrex + strex (no arithmetic) → mark as dead
                        // The strex status is already set to 0, so this is a successful store
                        // We can mark ldrex, strex, status as dead since they're no-ops
                        // But only if there's no arith between them
                        // Actually, keep the ldrex+strex as a regular load+store
                        // Don't merge — let the normal pipeline handle it
                    }
                }
            }
            insn = insn->next;
        }

        // Second pass: remove dead instructions
        MicroInsn* cur = blk->head;
        while (cur) {
            MicroInsn* next = cur->next;
            if (cur->iprops & IPROP_DEAD) {
                if (cur->prev) cur->prev->next = cur->next;
                else blk->head = cur->next;
                if (cur->next) cur->next->prev = cur->prev;
                else blk->tail = cur->prev;
                delete cur;
            }
            cur = next;
        }
    }

    // v4.6: Cross-block post-pass — fix comparison immediates for
    // __sync_fetch_and_sub/add results.
    // __sync_fetch_and_sub(ptr, K) returns the OLD value (before K was subtracted).
    // The original ARM code did "sub r0, r0, #K; cmp r0, #0" which checks if
    // "old - K == 0". After folding, r0 is the OLD value, so we need
    // "cmp r0, #K" instead of "cmp r0, #0".
    // We must fix BOTH the comparison instruction (OP_SUB def=-1) AND the
    // CBRANCH instruction (which has its own l/r copied from tracked cmp state).
    if (!atomic_adjust.empty()) {
        std::map<int, int64_t> adjust_map;
        for (auto& [mreg, adj] : atomic_adjust) {
            adjust_map[mreg] = adj;
        }
        for (auto& blk_up : mba_.blocks) {
            if (!blk_up) continue;
            for (auto* insn = blk_up->head; insn; insn = insn->next) {
                // Fix 1: Comparison instruction (OP_SUB with def_mreg == -1)
                if (insn->opcode == OP_SUB && insn->def_mreg == -1 &&
                    insn->l.isReg() && insn->r.isImm()) {
                    auto it = adjust_map.find(insn->l.mreg);
                    if (it != adjust_map.end() && insn->r.imm == 0) {
                        insn->r = Mop::imm64(it->second, insn->r.width);
                    }
                }
                // Fix 2: CBRANCH instruction (has its own l/r from tracked cmp state)
                if (insn->opcode == OP_CBRANCH &&
                    insn->l.isReg() && insn->r.isImm()) {
                    auto it = adjust_map.find(insn->l.mreg);
                    if (it != adjust_map.end() && insn->r.imm == 0) {
                        insn->r = Mop::imm64(it->second, insn->r.width);
                        insn->cond_imm = it->second;
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════
// v3.14: eliminateSpecialRegisters — Ghidra-inspired IR-level elimination
// of sp/pc/lr/fp bookkeeping. Marks all stack-pointer adjustments,
// prologue saves, epilogue restores, and frame-pointer setup as IPROP_DEAD
// so they never reach CTree building.
//
// This mirrors Ghidra's Heritage mechanism (heritage.cc) which rewrites
// stack-space Varnodes into local-variable Varnodes at the IR level,
// eliminating raw register references before the C printer runs.
// ════════════════════════════════════════════════════════════════════
void MicrocodeEmitter::eliminateSpecialRegisters() {
    // Special register mregs for current architecture
    int spMreg = is_aarch64_ ? 131 : 113;
    int lrMreg = is_aarch64_ ? 130 : 114;
    int pcMreg = is_aarch64_ ? 133 : 115;
    int fpMreg = is_aarch64_ ? 129 : 111;

    auto isSpecial = [&](int mreg) {
        return mreg == spMreg || mreg == lrMreg ||
               mreg == pcMreg || mreg == fpMreg;
    };

    for (auto& blk_up : mba_.blocks) {
        if (!blk_up) continue;
        for (auto* insn = blk_up->head; insn; insn = insn->next) {
            if (insn->isDead()) continue;

            // ── Never touch control flow instructions ──
            // OP_RET, OP_CALL, OP_ICALL are real control flow and must survive.
            // OP_GOTO, OP_CBRANCH are handled by CFG structuring.
            if (insn->opcode == OP_RET || insn->opcode == OP_CALL ||
                insn->opcode == OP_ICALL || insn->opcode == OP_GOTO ||
                insn->opcode == OP_CBRANCH || insn->opcode == OP_JTBL) {
                continue;
            }

            // ── Rule 1: Any instruction that DEFINES sp/lr/pc/fp is bookkeeping ──
            // - sp = sp + imm   (prologue/epilogue stack adjustment)
            // - sp = sp - imm
            // - lr = x0         (lr save before nested call)
            // - lr = [sp+off]   (epilogue restore)
            // - fp = sp         (prologue frame setup)
            // - fp = [sp+off]   (epilogue restore)
            // - pc = x0         (computed jump, rare — OP_RET already excluded)
            if (insn->def_mreg >= 0 && isSpecial(insn->def_mreg)) {
                insn->iprops |= IPROP_DEAD;
                continue;
            }

            // ── Rule 2: OP_STORE of lr/fp to anywhere is prologue save ──
            // - str lr, [sp, #-4]!   (push {lr})
            // - str x29, [sp, #-16]! (stp x29, x30, ...)
            // - str fp, [sp, #4]     (save fp to stack)
            if (insn->opcode == OP_STORE && insn->l.isReg()) {
                if (insn->l.mreg == lrMreg || insn->l.mreg == fpMreg) {
                    insn->iprops |= IPROP_DEAD;
                    continue;
                }
            }

            // ── Rule 3: OP_ADD/OP_SUB involving sp is stack address computation ──
            // - x9 = sp + 16   (stack address for local variable access)
            // - x9 = sp - 8
            // These are tracked by CTreeBuilder::trackStackAddrRegs() which
            // does NOT check isDead(), so marking them dead here is safe.
            if (insn->opcode == OP_ADD || insn->opcode == OP_SUB) {
                bool lIsSp = insn->l.isReg() && insn->l.mreg == spMreg;
                bool rIsSp = insn->r.isReg() && insn->r.mreg == spMreg;
                if (lIsSp || rIsSp) {
                    insn->iprops |= IPROP_DEAD;
                    continue;
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════
// v10.0: Inline function detection
// 对标 Ghidra: detect embedded known function patterns in function bodies.
// Scan emitted microcode for sequences that represent inlined calls to
// well-known functions (memset, memcpy, strlen), mark the original
// instructions as IPROP_DEAD and insert a synthetic OP_CALL.
// ════════════════════════════════════════════════════════════════════
void MicrocodeEmitter::detectInlineFunctions() {
    for (int bi = 0; bi < mba_.numBlocks(); bi++) {
        auto* blk = mba_.getBlock(bi);
        if (!blk || !blk->head) continue;

        // Count live instructions
        int liveCount = 0;
        for (auto* insn = blk->head; insn; insn = insn->next) {
            if (!insn->isDead()) liveCount++;
        }
        if (liveCount < 3) continue;

        // Collect live instructions into a vector for windowed scanning
        std::vector<MicroInsn*> live;
        for (auto* insn = blk->head; insn; insn = insn->next) {
            if (!insn->isDead()) live.push_back(insn);
        }

        // ── Pattern 1: memset(dst, 0, count) ──
        // AArch64:   mov  wZR/0, wN      ; strb wZR/0, [base, idx]
        //            or:    stp xZR, xZR, [dst]  (zero 16 bytes)
        // ARM32:     mov  rN, #0         ; strb rN, [base, idx]
        // Generalized: a store of zero to consecutive addresses in a loop-like
        // pattern (same base register, ascending offset, zero source).
        // Simplified: detect a sequence of STORE(0) to the same base with
        // consecutive offsets (manual unrolled memset).
        {
            // Look for a sequence of stores with zero source and ascending offsets
            int seqStart = -1;
            int seqLen = 0;
            int baseMreg = -1;
            int64_t baseOff = 0;
            int64_t stride = 0;

            for (int j = 0; j < (int)live.size(); j++) {
                auto* insn = live[j];
                bool isZeroStore = false;
                int curBase = -1;
                int64_t curOff = 0;

                if (insn->opcode == OP_STORE && insn->l.isReg()) {
                    // Check if the stored value is zero
                    if (insn->l.isImm() && insn->l.imm == 0) {
                        isZeroStore = true;
                    } else if (insn->l.isReg()) {
                        // Check if the register is known zero (xzr/wzr/r0=0)
                        int mreg = insn->l.mreg;
                        // xzr=131, wzr=131 (same mreg, different width in AArch64)
                        // For ARM32, check if the register was set to 0 by a prior mov
                        if (is_aarch64_ && mreg == 131) {
                            isZeroStore = true;
                        }
                        // Look backwards for a preceding OP_MOV that sets this reg to 0
                        if (!isZeroStore && j > 0) {
                            for (int k = j - 1; k >= std::max(0, j - 5); k--) {
                                auto* prev = live[k];
                                if (prev->opcode == OP_MOV && prev->def_mreg == mreg) {
                                    if (prev->l.isImm() && prev->l.imm == 0) {
                                        isZeroStore = true;
                                    }
                                    break;
                                }
                            }
                        }
                    }

                    if (isZeroStore) {
                        // Extract base and offset from destination
                        if (insn->d.isMem()) {
                            curBase = insn->d.mem_base;
                            curOff = insn->d.mem_offset;
                        } else if (insn->d.isReg()) {
                            // Store to register — skip, not a memset pattern
                        }
                    }
                }

                if (isZeroStore && curBase >= 0) {
                    if (seqStart < 0) {
                        seqStart = j;
                        seqLen = 1;
                        baseMreg = curBase;
                        baseOff = curOff;
                        stride = 1; // will be refined
                    } else if (curBase == baseMreg) {
                        int64_t newStride = curOff - baseOff;
                        if (seqLen == 1) stride = newStride;
                        if (newStride == stride * seqLen) {
                            seqLen++;
                        } else {
                            seqStart = j;
                            seqLen = 1;
                            baseMreg = curBase;
                            baseOff = curOff;
                            stride = 1;
                        }
                    } else {
                        seqStart = -1;
                        seqLen = 0;
                    }

                    // Emit memset if we have >= 3 consecutive zero stores
                    // (at least 3 bytes being zeroed)
                    if (seqLen >= 3) {
                        InlineFunctionInfo info;
                        info.entry_addr = live[seqStart]->ea;
                        info.name = "memset";
                        info.size = seqLen;
                        info.is_inline = true;
                        detected_inlines_.push_back(info);

                        // Mark all instructions in the sequence as dead
                        for (int k = seqStart; k < seqStart + seqLen && k < (int)live.size(); k++) {
                            live[k]->iprops |= IPROP_DEAD;
                        }

                        // Insert a synthetic OP_CALL to memset before the first dead insn
                        auto* callMicro = new MicroInsn(OP_CALL, info.entry_addr);
                        callMicro->src_asm = "// inlined memset";
                        callMicro->iprops = IPROP_CALL | IPROP_VOLATILE;
                        auto call_info = std::make_shared<CallInfo>();
                        call_info->target_name = "memset";
                        call_info->is_known = true;
                        call_info->has_return = false;
                        call_info->arg_count = 3;
                        callMicro->call_info = call_info;
                        callMicro->def_mreg = -1;  // memset returns void (actually ptr, but simplified)

                        // Insert into the block's instruction list
                        MicroInsn* firstInsn = live[seqStart];
                        if (firstInsn->prev) {
                            callMicro->prev = firstInsn->prev;
                            callMicro->next = firstInsn;
                            firstInsn->prev->next = callMicro;
                            firstInsn->prev = callMicro;
                        } else {
                            // firstInsn is the block head
                            callMicro->next = firstInsn;
                            firstInsn->prev = callMicro;
                            blk->head = callMicro;
                        }

                        // Reset scan state
                        seqStart = -1;
                        seqLen = 0;
                    }
                } else {
                    seqStart = -1;
                    seqLen = 0;
                }
            }
        }

        // ── Pattern 2: memcpy(dst, src, count) ──
        // Detect a sequence of LOAD+STORE pairs where:
        //   - LOAD reads from [src_base + offset]
        //   - STORE writes to [dst_base + offset]
        //   - offsets are ascending (consecutive)
        // This is the compiler-unrolled memcpy pattern.
        {
            int seqStart = -1;
            int seqLen = 0;
            int srcBase = -1;
            int dstBase = -1;
            int64_t srcBaseOff = 0;
            int64_t dstBaseOff = 0;
            int64_t stride = 0;

            for (int j = 1; j < (int)live.size(); j++) {
                auto* prevInsn = live[j - 1];
                auto* curInsn = live[j];

                // Pattern: prev = LOAD [src_base + off], cur = STORE [dst_base + off]
                bool isLoadStore = false;
                if (prevInsn->opcode == OP_LOAD && curInsn->opcode == OP_STORE &&
                    prevInsn->def_mreg >= 0 && curInsn->l.isReg() &&
                    curInsn->l.mreg == prevInsn->def_mreg) {
                    // The loaded value is stored — potential memcpy
                    isLoadStore = true;
                }

                if (isLoadStore) {
                    int curSrcBase = -1, curDstBase = -1;
                    int64_t curSrcOff = 0, curDstOff = 0;

                    if (prevInsn->l.isMem()) {
                        curSrcBase = prevInsn->l.mem_base;
                        curSrcOff = prevInsn->l.mem_offset;
                    }
                    if (curInsn->d.isMem()) {
                        curDstBase = curInsn->d.mem_base;
                        curDstOff = curInsn->d.mem_offset;
                    }

                    if (curSrcBase >= 0 && curDstBase >= 0 &&
                        curSrcBase != curDstBase) {
                        if (seqStart < 0) {
                            seqStart = j - 1;
                            seqLen = 1;
                            srcBase = curSrcBase;
                            dstBase = curDstBase;
                            srcBaseOff = curSrcOff;
                            dstBaseOff = curDstOff;
                            stride = 0; // first pair
                        } else if (curSrcBase == srcBase && curDstBase == dstBase) {
                            int64_t srcDiff = curSrcOff - srcBaseOff;
                            int64_t dstDiff = curDstOff - dstBaseOff;

                            if (srcDiff == dstDiff && srcDiff > 0) {
                                if (seqLen == 1) stride = srcDiff;
                                if (srcDiff == stride * seqLen) {
                                    seqLen++;
                                } else {
                                    seqStart = j - 1;
                                    seqLen = 1;
                                    srcBase = curSrcBase;
                                    dstBase = curDstBase;
                                    srcBaseOff = curSrcOff;
                                    dstBaseOff = curDstOff;
                                    stride = 0;
                                }
                            } else {
                                seqStart = j - 1;
                                seqLen = 1;
                                srcBase = curSrcBase;
                                dstBase = curDstBase;
                                srcBaseOff = curSrcOff;
                                dstBaseOff = curDstOff;
                                stride = 0;
                            }
                        } else {
                            seqStart = j - 1;
                            seqLen = 1;
                            srcBase = curSrcBase;
                            dstBase = curDstBase;
                            srcBaseOff = curSrcOff;
                            dstBaseOff = curDstOff;
                            stride = 0;
                        }

                        // Emit memcpy if we have >= 3 pairs (6 instructions)
                        if (seqLen >= 3) {
                            InlineFunctionInfo info;
                            info.entry_addr = live[seqStart]->ea;
                            info.name = "memcpy";
                            info.size = seqLen * 2; // each pair = 2 instructions
                            info.is_inline = true;
                            detected_inlines_.push_back(info);

                            // Mark all instructions in the sequence as dead
                            for (int k = seqStart; k < seqStart + seqLen * 2 && k < (int)live.size(); k++) {
                                live[k]->iprops |= IPROP_DEAD;
                            }

                            // Insert a synthetic OP_CALL to memcpy
                            auto* callMicro = new MicroInsn(OP_CALL, info.entry_addr);
                            callMicro->src_asm = "// inlined memcpy";
                            callMicro->iprops = IPROP_CALL | IPROP_VOLATILE;
                            auto call_info = std::make_shared<CallInfo>();
                            call_info->target_name = "memcpy";
                            call_info->is_known = true;
                            call_info->has_return = false;
                            call_info->arg_count = 3;
                            callMicro->call_info = call_info;
                            callMicro->def_mreg = -1;

                            // Insert into block
                            MicroInsn* firstInsn = live[seqStart];
                            if (firstInsn->prev) {
                                callMicro->prev = firstInsn->prev;
                                callMicro->next = firstInsn;
                                firstInsn->prev->next = callMicro;
                                firstInsn->prev = callMicro;
                            } else {
                                callMicro->next = firstInsn;
                                firstInsn->prev = callMicro;
                                blk->head = callMicro;
                            }

                            seqStart = -1;
                            seqLen = 0;
                        }
                    } else {
                        seqStart = -1;
                        seqLen = 0;
                    }
                } else {
                    seqStart = -1;
                    seqLen = 0;
                }
            }
        }

        // ── Pattern 3: strlen(str) ──
        // Detect a loop-like pattern: LOAD byte from [base + offset],
        // followed by a conditional branch (CBRANCH) back to the load.
        // The load byte checks for zero terminator.
        // In unrolled form: multiple LOAD byte from [base], [base+1], [base+2]...
        // Comparison with zero is done via OP_SETZ/OP_SETNZ (set flag on zero)
        // which we treat as a terminator-check instruction.
        {
            int seqStart = -1;
            int seqLen = 0;
            int strBase = -1;
            int64_t strBaseOff = 0;

            for (int j = 0; j < (int)live.size(); j++) {
                auto* insn = live[j];
                bool isByteLoad = false;
                int curBase = -1;
                int64_t curOff = 0;

                if (insn->opcode == OP_LOAD && insn->l.isMem() && insn->d.width == 1) {
                    isByteLoad = true;
                    curBase = insn->l.mem_base;
                    curOff = insn->l.mem_offset;
                }

                // Also check for zero-test after a byte load (OP_SETZ / OP_SETNZ)
                bool isZeroTest = (insn->opcode == OP_SETZ || insn->opcode == OP_SETNZ ||
                                   insn->opcode == OP_SETB  || insn->opcode == OP_SETAE);
                if (!isByteLoad && isZeroTest && seqStart >= 0) {
                    // This is the terminator check — extend the sequence
                    seqLen++;
                    continue;
                }

                if (isByteLoad && curBase >= 0) {
                    if (seqStart < 0) {
                        seqStart = j;
                        seqLen = 1;
                        strBase = curBase;
                        strBaseOff = curOff;
                    } else if (curBase == strBase && curOff == strBaseOff + seqLen) {
                        seqLen++;
                    } else {
                        seqStart = j;
                        seqLen = 1;
                        strBase = curBase;
                        strBaseOff = curOff;
                    }

                    // Emit strlen if we have >= 3 consecutive byte loads
                    if (seqLen >= 3) {
                        InlineFunctionInfo info;
                        info.entry_addr = live[seqStart]->ea;
                        info.name = "strlen";
                        info.size = seqLen;
                        info.is_inline = true;
                        detected_inlines_.push_back(info);

                        for (int k = seqStart; k < seqStart + seqLen && k < (int)live.size(); k++) {
                            live[k]->iprops |= IPROP_DEAD;
                        }

                        // Insert a synthetic OP_CALL to strlen
                        auto* callMicro = new MicroInsn(OP_CALL, info.entry_addr);
                        callMicro->src_asm = "// inlined strlen";
                        callMicro->iprops = IPROP_CALL | IPROP_VOLATILE;
                        auto call_info = std::make_shared<CallInfo>();
                        call_info->target_name = "strlen";
                        call_info->is_known = true;
                        call_info->has_return = true;
                        call_info->arg_count = 1;
                        call_info->return_type = "size_t";
                        callMicro->call_info = call_info;
                        callMicro->def_mreg = live[seqStart]->def_mreg;
                        callMicro->d = Mop::reg(callMicro->def_mreg, 8);

                        MicroInsn* firstInsn = live[seqStart];
                        if (firstInsn->prev) {
                            callMicro->prev = firstInsn->prev;
                            callMicro->next = firstInsn;
                            firstInsn->prev->next = callMicro;
                            firstInsn->prev = callMicro;
                        } else {
                            callMicro->next = firstInsn;
                            firstInsn->prev = callMicro;
                            blk->head = callMicro;
                        }

                        seqStart = -1;
                        seqLen = 0;
                    }
                } else if (!isZeroTest) {
                    seqStart = -1;
                    seqLen = 0;
                }
            }
        }
    }
}

// ── Handle mov/movz/movn ──
void MicrocodeEmitter::handleMov(const AsmInsn& insn) {
    auto ops = splitOps(insn.op_str);
    if (ops.size() < 2) return;
    int dst = parseReg(ops[0]);
    std::string src = ops[1];

    // v8.6: mov pc, lr = return (ARM32 equivalent of bx lr)
    if (ops[0] == "pc" || ops[0] == "r15") {
        if (ops[1] == "lr" || ops[1] == "r14") {
            handleRet(insn);
            return;
        }
        // mov pc, rN = indirect jump (unusual but possible)
        auto* micro = new MicroInsn(OP_GOTO, insn.addr);
        micro->src_asm = insn.mnemonic + " " + insn.op_str;
        micro->iprops = IPROP_BRANCHED | IPROP_VOLATILE;
        emitInsn(micro);
        return;
    }

    // Suppress prologue: mov x29, sp
    if (ops[0] == "x29" || ops[0] == "fp") {
        if (src == "sp") return;
    }
    if (ops[0] == "sp" && (src == "x29" || src == "fp")) return;

    auto* micro = new MicroInsn(OP_MOV, insn.addr);
    micro->src_asm = insn.mnemonic + " " + insn.op_str;
    micro->def_mreg = dst;
    int width = (ops[0][0] == 'w') ? 4 : (is_aarch64_ ? 8 : 4);
    micro->d = Mop::reg(dst, width);

    if (isImm(src)) {
        int64_t val = parseImm(src);
        micro->l = Mop::imm64(val, width);
        // Track for ADRP fusion and constant propagation
        reg_state_[dst] = RegState(); reg_state_[dst].kind = RegState::IMM_CONST; reg_state_[dst].value = (uint64_t)val;

        // v9.23: Track movz for movk fusion
        if (is_aarch64_ && insn.mnemonic == "movz") {
            // Extract shift amount from op_str, e.g. "x0, #0x1234, lsl #0"
            int shift = 0;
            auto ops = splitOps(insn.op_str);
            if (ops.size() >= 3) {
                for (size_t i = 2; i < ops.size(); i++) {
                    // "lsl #16" may be a single element after comma-split
                    if (ops[i].find("lsl") != std::string::npos) {
                        // Split by space: "lsl #16" → ["lsl", "#16"]
                        size_t sp = ops[i].find(' ');
                        if (sp != std::string::npos) {
                            std::string immStr = ops[i].substr(sp + 1);
                            shift = (int)parseImm(immStr);
                        }
                        break;
                    }
                }
            }
            MovzState st;
            st.valid = true;
            st.accumulated = (uint64_t)val << shift;
            st.mreg = dst;
            st.last_insn = micro;
            movz_state_[dst] = st;
        }
    } else {
        int src_mreg = parseReg(src);
        micro->l = Mop::reg(src_mreg, width);
        // Track register copy
        if (src_mreg >= 0) {
            reg_state_[dst] = RegState();
            reg_state_[dst].kind = RegState::REG_COPY;
            reg_state_[dst].src_mreg = src_mreg;
        } else {
            reg_state_.erase(dst);
        }
    }
    emitInsn(micro);
}

// ── Handle add ──
void MicrocodeEmitter::handleAdd(const AsmInsn& insn) {
    auto ops = splitOps(insn.op_str);
    if (ops.size() < 2) return;
    int dst = parseReg(ops[0]);
    int width = (ops[0][0] == 'w') ? 4 : (is_aarch64_ ? 8 : 4);

    // Check ADRP+ADD fusion: add x0, x0, #imm
    if (ops.size() == 3 && ops[1] == ops[0] && isImm(ops[2])) {
        int64_t add_imm = parseImm(ops[2]);
        if (tryAdrpFusion(dst, add_imm)) {
            uint64_t full_addr = reg_state_[dst].value;
            // v10.1: Unified string reference resolution (ASCII + UTF-16).
            // When ADRP+ADD resolves to a .rodata/.data address that holds a
            // known string, mark the register as RESOLVED_SYM and store the
            // string content so handleCall can pass it as a literal argument.
            std::string strContent;
            bool isUtf16 = false;
            size_t strLen = 0;
            if (resolveStringRef(full_addr, strContent, isUtf16, strLen)) {
                auto* micro = new MicroInsn(OP_LDC, insn.addr);
                micro->src_asm = insn.mnemonic + " " + insn.op_str;
                micro->def_mreg = dst;
                micro->d = Mop::reg(dst, width);
                // For ASCII strings emit MOP_STR directly. For UTF-16 strings
                // there is no native u16string operand, so emit MOP_STR with
                // the converted content and rely on CPrinter to render L"...".
                micro->l = Mop::str(full_addr, strContent);
                emitInsn(micro);
                // v4.5/v10.1: Set RESOLVED_SYM so handleCall can pass the string
                // as a literal argument (e.g. sub_15f0("_ZN15MinecraftClient4initEv"))
                reg_state_[dst].kind = RegState::RESOLVED_SYM;
                reg_state_[dst].sym_name = strContent;
                reg_state_[dst].value = full_addr;
                return;
            }
            // Global or resolved address
            auto* micro = new MicroInsn(OP_LDC, insn.addr);
            micro->src_asm = insn.mnemonic + " " + insn.op_str;
            micro->def_mreg = dst;
            micro->d = Mop::reg(dst, width);
            micro->l = Mop::global(full_addr);
            emitInsn(micro);
            return;
        }
    }

    // v3.13: ARM32 PC-relative add — add Rd, pc, Rn / add Rd, Rn, pc
    // v9.9: Also handle Thumb "add Rd, pc" (2-operand, implicit +0)
    // 参考 Ghidra ARM.sinc addrmode2: reloff = inst_start + offset
    // ARM mode: PC = insn.addr + 8; Thumb mode: PC = insn.addr + 4
    // PC 不应作为变量出现在 IR 中，直接替换为常量
    int pcMreg = mba_.phys_to_mreg.count("pc") ? mba_.phys_to_mreg["pc"] : 133;
    if (!is_aarch64_ && ops.size() >= 2) {
        int lReg = parseReg(ops[1]);
        int rReg = -1;
        bool hasThird = (ops.size() >= 3);
        std::string third = hasThird ? ops[2] : "";
        // 检查第三个操作数是否是寄存器（非立即数、非移位）
        if (hasThird && !isImm(third) && third.find("lsl") == std::string::npos &&
            third.find("lsr") == std::string::npos &&
            third.find("asr") == std::string::npos) {
            // 去除可能的逗号
            std::string cleanThird = third;
            cleanThird.erase(cleanThird.find_last_not_of(" \t,") + 1);
            rReg = parseReg(cleanThird);
        }

        // add Rd, pc, Rn  或  add Rd, Rn, pc
        // v9.9: Also match "add Rd, pc" (Thumb 2-operand, implicit +0)
        bool lIsPc = (lReg == pcMreg);
        bool rIsPc = (rReg == pcMreg);
        if (lIsPc || rIsPc) {
            // v3.19: LDR-literal + ADD-PC fusion (抄 Ghidra ARM p-code constant pool)
            // Pattern:
            //   ldr r0, [pc, #off]   ; r0 = offset (LITERAL_POOL state from handleLoad)
            //   add r0, pc, r0        ; r0 = pc + offset = resolved address
            // If the non-PC register has LITERAL_POOL state, compute the final
            // address and resolve against GOT/string/global maps.
            // v9.13: For Thumb 2-operand "add Rd, pc", rReg is -1; the other
            // register is the destination register (Rd), which is also the source.
            int otherReg = lIsPc ? (rReg >= 0 ? rReg : dst) : lReg;
            auto rsIt = reg_state_.find(otherReg);
            if (rsIt != reg_state_.end() &&
                rsIt->second.kind == RegState::LITERAL_POOL) {
                uint64_t resolvedAddr = pcValue(insn.addr) + rsIt->second.value;

                // Check GOT entry first (GOT entries are also in global_names
                // via byAddr, so must check got_to_name_ first to set GOT_ADDR
                // state for the subsequent LDR dereference).
                auto gotIt = got_to_name_.find(resolvedAddr);
                if (gotIt != got_to_name_.end()) {
                    // GOT entry address — mark as GOT_ADDR.
                    // The subsequent ldr rN, [rN] will dereference this to
                    // get the function pointer, which we resolve to RESOLVED_SYM.
                    reg_state_[dst].kind = RegState::GOT_ADDR;
                    reg_state_[dst].value = resolvedAddr;
                    reg_state_[dst].sym_name = gotIt->second;
                    auto* micro = new MicroInsn(OP_LDC, insn.addr);
                    micro->src_asm = insn.mnemonic + " " + insn.op_str;
                    micro->def_mreg = dst;
                    micro->d = Mop::reg(dst, width);
                    micro->l = Mop::global(resolvedAddr);
                    emitInsn(micro);
                    return;
                }

                // Check string reference (v10.1: ASCII + UTF-16)
                std::string strContent2;
                bool isUtf16_2 = false;
                size_t strLen2 = 0;
                if (resolveStringRef(resolvedAddr, strContent2, isUtf16_2, strLen2)) {
                    auto* micro = new MicroInsn(OP_LDC, insn.addr);
                    micro->src_asm = insn.mnemonic + " " + insn.op_str;
                    micro->def_mreg = dst;
                    micro->d = Mop::reg(dst, width);
                    micro->l = Mop::str(resolvedAddr, strContent2);
                    emitInsn(micro);
                    // v4.5/v10.1: Set RESOLVED_SYM so handleCall can pass the string
                    // as a literal argument (e.g. sub_15f0("_ZN15MinecraftClient4initEv"))
                    reg_state_[dst].kind = RegState::RESOLVED_SYM;
                    reg_state_[dst].sym_name = strContent2;
                    reg_state_[dst].value = resolvedAddr;
                    return;
                }

                // Check global variable
                auto nameIt = mba_.global_names.find(resolvedAddr);
                if (nameIt != mba_.global_names.end()) {
                    auto* micro = new MicroInsn(OP_LDC, insn.addr);
                    micro->src_asm = insn.mnemonic + " " + insn.op_str;
                    micro->def_mreg = dst;
                    micro->d = Mop::reg(dst, width);
                    micro->l = Mop::global(resolvedAddr);
                    emitInsn(micro);
                    reg_state_.erase(dst);
                    return;
                }

                // v9.12: Check if resolved address is in a data section.
                // Even unnamed data symbols should be treated as global
                // references so the CPrinter can resolve them properly.
                if (mba_.isInDataSection(resolvedAddr)) {
                    auto* micro = new MicroInsn(OP_LDC, insn.addr);
                    micro->src_asm = insn.mnemonic + " " + insn.op_str;
                    micro->def_mreg = dst;
                    micro->d = Mop::reg(dst, width);
                    micro->l = Mop::global(resolvedAddr);
                    emitInsn(micro);
                    reg_state_.erase(dst);
                    return;
                }

                // Resolved to a constant address — track as IMM_CONST
                auto* micro = new MicroInsn(OP_LDC, insn.addr);
                micro->src_asm = insn.mnemonic + " " + insn.op_str;
                micro->def_mreg = dst;
                micro->d = Mop::reg(dst, width);
                micro->l = Mop::imm64((int64_t)resolvedAddr, width);
                emitInsn(micro);
                reg_state_[dst].kind = RegState::IMM_CONST;
                reg_state_[dst].value = resolvedAddr;
                return;
            }

            // No LITERAL_POOL state — fallback to PC constant + ADD
            // v9.13: ARM mode: PC = insn.addr + 8; Thumb: PC = insn.addr + 4
            uint64_t pcVal = pcValue(insn.addr);
            // 生成一条 LDC 把 pc 常量载入临时寄存器
            int pcTmp = mba_.allocUnique(width);
            auto* pcLoad = new MicroInsn(OP_LDC, insn.addr);
            pcLoad->src_asm = insn.mnemonic + " " + insn.op_str;
            pcLoad->def_mreg = pcTmp;
            pcLoad->d = Mop::reg(pcTmp, width);
            pcLoad->l = Mop::imm64(pcVal, width);
            emitInsn(pcLoad);

            // 生成 ADD，用 pcTmp 替换 pc
            auto* micro = new MicroInsn(OP_ADD, insn.addr);
            micro->src_asm = insn.mnemonic + " " + insn.op_str;
            micro->def_mreg = dst;
            micro->d = Mop::reg(dst, width);
            if (lIsPc) {
                micro->l = Mop::reg(pcTmp, width);
                // v9.9: rReg may be -1 for Thumb "add Rd, pc" (2-operand) or
                // when the third operand is an immediate — use imm64(0)
                micro->r = (rReg >= 0) ? Mop::reg(rReg, width) : Mop::imm64(0, width);
            } else {
                micro->l = Mop::reg(lReg, width);
                micro->r = Mop::reg(pcTmp, width);
            }
            // 尝试折叠：如果另一个寄存器是已知的常量偏移（来自 .got 加载）
            // 则直接解析为全局符号/字符串
            // 这里不做折叠，让 SSA 优化阶段的常量传播处理
            reg_state_.erase(dst);
            emitInsn(micro);
            return;
        }
    }

    // Normal add
    auto* micro = new MicroInsn(OP_ADD, insn.addr);
    micro->src_asm = insn.mnemonic + " " + insn.op_str;
    micro->def_mreg = dst;
    micro->d = Mop::reg(dst, width);

    // v9.10: Handle 2-operand Thumb adds (adds Rd, #imm or adds Rd, Rm)
    // In Thumb mode, 2-operand adds use Rd as both dst and first src.
    if (ops.size() == 2) {
        // add Rd, #imm → Rd = Rd + imm
        // add Rd, Rm  → Rd = Rd + Rm
        if (isImm(ops[1])) {
            micro->l = Mop::reg(dst, width);
            micro->r = Mop::imm64(parseImm(ops[1]), width);
        } else {
            micro->l = Mop::reg(dst, width);
            micro->r = Mop::reg(parseReg(ops[1]), width);
        }
    } else if (ops.size() >= 3) {
        micro->l = Mop::reg(parseReg(ops[1]), width);
        if (isImm(ops[2])) {
            micro->r = Mop::imm64(parseImm(ops[2]), width);
        } else {
            // Check for shift: "x2, lsl #2"
            std::string third = ops[2];
            if (third.find("lsl") != std::string::npos) {
                // shifted register
                size_t pos = third.find("lsl");
                std::string regPart = third.substr(0, pos);
                regPart.erase(regPart.find_last_not_of(" \t,") + 1);
                std::string shiftPart = third.substr(pos + 3);
                shiftPart.erase(0, shiftPart.find_first_not_of(" \t#"));
                int shift = std::stoi(shiftPart);
                int idx_mreg = parseReg(regPart);
                // Emit shift first
                int tmp = mba_.allocUnique(width);
                auto* shf = new MicroInsn(OP_SHL, insn.addr);
                shf->def_mreg = tmp;
                shf->d = Mop::reg(tmp, width);
                shf->l = Mop::reg(idx_mreg, width);
                shf->r = Mop::imm64(shift, width);
                emitInsn(shf);
                micro->r = Mop::reg(tmp, width);
            } else {
                micro->r = Mop::reg(parseReg(ops[2]), width);
            }
        }
    }
    reg_state_.erase(dst);
    emitInsn(micro);
}

// ── Handle sub ──
void MicrocodeEmitter::handleSub(const AsmInsn& insn) {
    auto ops = splitOps(insn.op_str);
    if (ops.size() < 2) return;
    int dst = parseReg(ops[0]);
    int width = (ops[0][0] == 'w') ? 4 : (is_aarch64_ ? 8 : 4);

    // v9.22: When destination is xzr/wzr (mreg 132), subs is a comparison.
    // Track the operands so subsequent conditional branches have correct
    // condition operands instead of "unknown == unknown".
    if (dst == 132 && ops.size() >= 3) {
        cmp_a_mreg_ = parseReg(ops[1]);
        if (isImm(ops[2])) {
            cmp_b_is_imm_ = true;
            cmp_b_imm_ = parseImm(ops[2]);
            cmp_b_mreg_ = -1;
        } else {
            cmp_b_is_imm_ = false;
            cmp_b_mreg_ = parseReg(ops[2]);
        }
    }

    auto* micro = new MicroInsn(OP_SUB, insn.addr);
    micro->src_asm = insn.mnemonic + " " + insn.op_str;
    micro->def_mreg = dst;
    micro->d = Mop::reg(dst, width);
    micro->l = Mop::reg(parseReg(ops[1]), width);

    if (ops.size() >= 3) {
        if (isImm(ops[2]))
            micro->r = Mop::imm64(parseImm(ops[2]), width);
        else
            micro->r = Mop::reg(parseReg(ops[2]), width);
    }
    reg_state_.erase(dst);
    emitInsn(micro);
}

// ── Handle mul ──
void MicrocodeEmitter::handleMul(const AsmInsn& insn) {
    auto ops = splitOps(insn.op_str);
    if (ops.size() < 2) return;
    int dst = parseReg(ops[0]);
    int width = (ops[0][0] == 'w') ? 4 : (is_aarch64_ ? 8 : 4);

    auto* micro = new MicroInsn(OP_MUL, insn.addr);
    micro->src_asm = insn.mnemonic + " " + insn.op_str;
    micro->def_mreg = dst;
    micro->d = Mop::reg(dst, width);
    micro->l = Mop::reg(parseReg(ops[1]), width);
    if (ops.size() >= 3) {
        if (isImm(ops[2]))
            micro->r = Mop::imm64(parseImm(ops[2]), width);
        else
            micro->r = Mop::reg(parseReg(ops[2]), width);
    }
    reg_state_.erase(dst);
    emitInsn(micro);
}

// ── Handle logical ops (and, orr, eor) ──
void MicrocodeEmitter::handleLogical(const AsmInsn& insn, MicroOp op) {
    auto ops = splitOps(insn.op_str);
    if (ops.size() < 2) return;
    int dst = parseReg(ops[0]);
    int width = (ops[0][0] == 'w') ? 4 : (is_aarch64_ ? 8 : 4);

    // orr x0, x1 with 2 operands is a mov alias
    if (op == OP_OR && ops.size() == 2) {
        auto* micro = new MicroInsn(OP_MOV, insn.addr);
        micro->src_asm = insn.mnemonic + " " + insn.op_str;
        micro->def_mreg = dst;
        micro->d = Mop::reg(dst, width);
        micro->l = Mop::reg(parseReg(ops[1]), width);
        emitInsn(micro);
        return;
    }

    auto* micro = new MicroInsn(op, insn.addr);
    micro->src_asm = insn.mnemonic + " " + insn.op_str;
    micro->def_mreg = dst;
    micro->d = Mop::reg(dst, width);
    micro->l = Mop::reg(parseReg(ops[1]), width);
    if (ops.size() >= 3) {
        if (isImm(ops[2]))
            micro->r = Mop::imm64(parseImm(ops[2]), width);
        else
            micro->r = Mop::reg(parseReg(ops[2]), width);
    }
    reg_state_.erase(dst);
    emitInsn(micro);
}

// ── Handle shifts (lsl, lsr, asr) ──
void MicrocodeEmitter::handleShift(const AsmInsn& insn, MicroOp op) {
    auto ops = splitOps(insn.op_str);
    if (ops.size() < 3) return;
    int dst = parseReg(ops[0]);
    int width = (ops[0][0] == 'w') ? 4 : (is_aarch64_ ? 8 : 4);

    auto* micro = new MicroInsn(op, insn.addr);
    micro->src_asm = insn.mnemonic + " " + insn.op_str;
    micro->def_mreg = dst;
    micro->d = Mop::reg(dst, width);
    micro->l = Mop::reg(parseReg(ops[1]), width);
    if (isImm(ops[2]))
        micro->r = Mop::imm64(parseImm(ops[2]), width);
    else
        micro->r = Mop::reg(parseReg(ops[2]), width);
    reg_state_.erase(dst);
    emitInsn(micro);
}

// ── Handle cmp ──
void MicrocodeEmitter::handleCmp(const AsmInsn& insn) {
    auto ops = splitOps(insn.op_str);
    if (ops.size() < 2) return;
    int width = (ops[0][0] == 'w') ? 4 : (is_aarch64_ ? 8 : 4);
    cmp_a_mreg_ = parseReg(ops[0]);
    if (isImm(ops[1])) {
        cmp_b_is_imm_ = true;
        cmp_b_imm_ = parseImm(ops[1]);
        cmp_b_mreg_ = -1;
    } else {
        cmp_b_is_imm_ = false;
        cmp_b_mreg_ = parseReg(ops[1]);
    }
    // Emit a comparison micro-op (result discarded, sets condition codes)
    auto* micro = new MicroInsn(OP_SUB, insn.addr);
    micro->src_asm = insn.mnemonic + " " + insn.op_str;
    micro->def_mreg = -1;  // no register defined; sets condition codes
    micro->iprops = IPROP_VOLATILE;  // don't optimize away
    micro->l = Mop::reg(cmp_a_mreg_, width);
    if (cmp_b_is_imm_)
        micro->r = Mop::imm64(cmp_b_imm_, width);
    else
        micro->r = Mop::reg(cmp_b_mreg_, width);
    emitInsn(micro);
}

// ── Handle load (ldr, ldrb, ldrh, ldrsb, ldrsh, ldrsw) ──
void MicrocodeEmitter::handleLoad(const AsmInsn& insn, int width, bool is_signed) {
    auto ops = splitOps(insn.op_str);
    if (ops.size() < 2) return;
    int dst = parseReg(ops[0]);
    int dst_width = (ops[0][0] == 'w') ? 4 : (is_aarch64_ ? 8 : 4);  // v8.1: ARM32 regs are 4 bytes

    // v24.0: AArch64 ldr s0/d0/q0, [xN] → floating-point load (OP_FLOAD)
    // ARM64 uses the same "ldr" mnemonic for both integer and floating-point
    // loads. If the destination register is a VFP/NEON register (mreg >= 200),
    // emit OP_FLOAD instead of OP_LOAD so type inference assigns FLOAT type.
    // 对标 Ghidra FLOAT_LOAD TypeOp: floating-point loads produce floating-point
    // variables, not integers.
    if (is_aarch64_ && dst >= 200) {
        // Determine the correct memory width from the register name
        int fp_width = width;
        if (ops[0][0] == 's') fp_width = 4;
        else if (ops[0][0] == 'd') fp_width = 8;
        else if (ops[0][0] == 'q' || ops[0][0] == 'v') fp_width = 16;
        handleFLoad(insn, fp_width);
        return;
    }

    // v3.10: ARM32 LDR literal — ldr rN, [pc, #offset]
    // PC = current insn addr + 8 (ARM32 pipeline)
    // Resolve to string/global/absolute address
    int pcMreg = mba_.phys_to_mreg.count("pc") ? mba_.phys_to_mreg["pc"] : 133;
    {
        MemOperand mo = parseMemOp(ops[1]);
        // v4.4: Skip PC-relative literal pool resolution when there's an index
        // register (e.g. "ldr r4, [pc, r4]"). The effective address is pc+index,
        // not pc+offset, so we cannot resolve it statically. Treat as normal load.
        if (mo.base_mreg == pcMreg && !mo.has_index) {
            // v9.13: AArch64: PC = insn.addr; ARM: PC = insn.addr + 8; Thumb: PC = insn.addr + 4
            uint64_t targetAddr = pcValue(insn.addr) + mo.offset;
            auto* micro = new MicroInsn(OP_LOAD, insn.addr);
            micro->src_asm = insn.mnemonic + " " + insn.op_str;
            micro->def_mreg = dst;
            micro->d = Mop::reg(dst, dst_width);
            micro->iprops = IPROP_LOAD;
            // Check string reference first (v10.1: ASCII + UTF-16)
            std::string strContent3;
            bool isUtf16_3 = false;
            size_t strLen3 = 0;
            if (resolveStringRef(targetAddr, strContent3, isUtf16_3, strLen3)) {
                // ldr rN, [pc, #off] pointing to a string → load string address
                micro->l = Mop::str(targetAddr, strContent3);
                micro->opcode = OP_LDC;  // treat as load constant (string addr)
            } else {
                auto nameIt = mba_.global_names.find(targetAddr);
                if (nameIt != mba_.global_names.end()) {
                    // ldr rN, [pc, #off] pointing to a global → load global address
                    micro->l = Mop::global(targetAddr);
                    micro->opcode = OP_LDC;
                } else {
                    // v3.19: Try reading literal pool value from ELF data.
                    // ARM32 LDR [pc, #off] loads a 4-byte value from the literal
                    // pool. In PIC code this is a PC-relative offset that gets
                    // fused with a subsequent ADD PC. In non-PIC code it may be
                    // an absolute address to a string/global.
                    // 对标 Ghidra ARM.sinc: LDR literal calculates
                    //   reloff = inst_start + offset; then reads mem[reloff]
                    uint32_t litValue = 0;
                    if (readElfU32(targetAddr, litValue)) {
                        // v10.1: Check if literal value is a direct string
                        // reference (non-PIC) — includes UTF-16 strings.
                        std::string strContent4;
                        bool isUtf16_4 = false;
                        size_t strLen4 = 0;
                        if (resolveStringRef(litValue, strContent4,
                                             isUtf16_4, strLen4)) {
                            micro->l = Mop::str(litValue, strContent4);
                            micro->opcode = OP_LDC;
                            // v10.1: Mark RESOLVED_SYM so handleCall can pass
                            // the string as a literal argument.
                            reg_state_[dst].kind = RegState::RESOLVED_SYM;
                            reg_state_[dst].sym_name = strContent4;
                            reg_state_[dst].value = litValue;
                            emitInsn(micro);
                            return;
                        }
                        // Check if literal value is a direct global reference (non-PIC)
                        auto nameIt2 = mba_.global_names.find(litValue);
                        if (nameIt2 != mba_.global_names.end()) {
                            micro->l = Mop::global(litValue);
                            micro->opcode = OP_LDC;
                            reg_state_.erase(dst);
                            emitInsn(micro);
                            return;
                        }
                        // v9.12: Check if literal value is a data section address.
                        // If the value falls within a known data section (.rodata,
                        // .data, etc.), treat it as a global reference even if the
                        // address is not in global_names. This prevents raw hex
                        // addresses like 0x29ed96 from appearing in output when
                        // they should be recognized as data pointers.
                        if (mba_.isInDataSection(litValue)) {
                            micro->l = Mop::global(litValue);
                            micro->opcode = OP_LDC;
                            reg_state_.erase(dst);
                            emitInsn(micro);
                            return;
                        }
                        // PC-relative offset (PIC code) — track LITERAL_POOL state
                        // for subsequent ADD-PC fusion.
                        // Emit LDC with raw value so SSA sees a definition.
                        micro->opcode = OP_LDC;
                        micro->l = Mop::imm64((int32_t)litValue, dst_width);
                        reg_state_[dst].kind = RegState::LITERAL_POOL;
                        reg_state_[dst].value = litValue;
                        reg_state_[dst].lit_addr = targetAddr;
                        emitInsn(micro);
                        return;
                    }

                    // No ELF data available — fallback to absolute address load
                    // Use a synthetic unique mreg as base to avoid "pc" appearing
                    int tmpBase = mba_.allocUnique(8);
                    micro->l = Mop::mem(tmpBase, 0, width, is_signed);
                    // Emit a preceding LDC to set tmpBase = targetAddr
                    auto* addrLoad = new MicroInsn(OP_LDC, insn.addr);
                    addrLoad->def_mreg = tmpBase;
                    addrLoad->d = Mop::reg(tmpBase, 8);
                    addrLoad->l = Mop::global(targetAddr);
                    emitInsn(addrLoad);
                }
            }
            reg_state_.erase(dst);
            emitInsn(micro);
            return;
        }
    }

    // Parse memory operand
    // ops[1] might be "[x1, #0x18]" or "[x1]"
    // For post-indexed: ops[1]="[x1]" and ops[2]="#0x10"
    MemOperand mo;
    if (ops.size() >= 2) {
        mo = parseMemOp(ops[1]);
        // Post-indexed: [base], #imm
        if (ops.size() >= 3 && !mo.has_index && mo.offset == 0 &&
            ops[1].find(']') != std::string::npos &&
            ops[1].back() == ']' && isImm(ops[2])) {
            mo.post_indexed = true;
            mo.offset = parseImm(ops[2]);
        }
    }

    // v3.19: GOT dereference — ldr rN, [rM] where rM has GOT_ADDR state
    // Pattern (ARM32 PIC import call):
    //   ldr r0, [pc, #off]   ; r0 = offset (LITERAL_POOL)
    //   add r0, pc, r0        ; r0 = GOT entry address (GOT_ADDR)
    //   ldr r0, [r0]          ; r0 = *GOT = function pointer (RESOLVED_SYM)
    //   blr r0                ; call import → handleCall resolves via RESOLVED_SYM
    if (!mo.has_index && mo.offset == 0 && mo.base_mreg >= 0) {
        auto rsIt = reg_state_.find(mo.base_mreg);
        if (rsIt != reg_state_.end() &&
            rsIt->second.kind == RegState::GOT_ADDR) {
            // Emit LOAD for SSA correctness (rN = mem[GOT_entry])
            auto* micro = new MicroInsn(OP_LOAD, insn.addr);
            micro->src_asm = insn.mnemonic + " " + insn.op_str;
            micro->def_mreg = dst;
            micro->d = Mop::reg(dst, dst_width);
            micro->iprops = IPROP_LOAD;
            micro->l = Mop::mem(mo.base_mreg, 0, width, is_signed);
            emitInsn(micro);
            // Track RESOLVED_SYM so handleCall can set target_name
            reg_state_[dst].kind = RegState::RESOLVED_SYM;
            reg_state_[dst].sym_name = rsIt->second.sym_name;
            return;
        }
    }

    // v4.5: ARM32 global variable load — ldr rN, [rM] where rM has IMM_CONST
    // state from a prior ADD-PC resolution (e.g. add r0, pc, r0 resolved to
    // 0x17458). Emit as MOP_GLOBAL so CTree builder uses the named global
    // symbol instead of *(0 + 0x17458).
    if (!mo.has_index && mo.offset == 0 && mo.base_mreg >= 0) {
        auto rsIt = reg_state_.find(mo.base_mreg);
        if (rsIt != reg_state_.end() &&
            rsIt->second.kind == RegState::IMM_CONST &&
            rsIt->second.value != 0) {
            uint64_t globalAddr = rsIt->second.value;
            auto* micro = new MicroInsn(OP_LOAD, insn.addr);
            micro->src_asm = insn.mnemonic + " " + insn.op_str;
            micro->def_mreg = dst;
            micro->d = Mop::reg(dst, dst_width);
            micro->iprops = IPROP_LOAD;
            micro->l = Mop::global(globalAddr);
            // Register the global address for CTree naming
            if (mba_.global_names.find(globalAddr) == mba_.global_names.end()) {
                char buf[32];
                snprintf(buf, sizeof(buf), "g_%llx", (unsigned long long)globalAddr);
                mba_.global_names[globalAddr] = buf;
            }
            emitInsn(micro);
            reg_state_.erase(dst);
            return;
        }
    }

    // v4.5: ARM32 indexed PC-relative load — ldr rN, [pc, rM]
    // Pattern: ldr r1, [pc, #off]   → r1 = offset (LITERAL_POOL, handled above)
    //          ldr r1, [pc, r1]     → r1 = *(pc + r1) = global variable
    // Used for global variable access in ARM32 PIC code.
    // add r0, pc, r0   → r0 = IMM_CONST (0x17458)
    // ldr r1, [pc, r1] → r1 = *(pc + literal_offset) → needs index register state
    if (mo.has_index && mo.base_mreg == pcMreg && mo.index_mreg >= 0) {
        auto rsIt = reg_state_.find(mo.index_mreg);
        if (rsIt != reg_state_.end() &&
            rsIt->second.kind == RegState::LITERAL_POOL) {
            uint64_t resolvedAddr = pcValue(insn.addr) + rsIt->second.value;
            auto* micro = new MicroInsn(OP_LOAD, insn.addr);
            micro->src_asm = insn.mnemonic + " " + insn.op_str;
            micro->def_mreg = dst;
            micro->d = Mop::reg(dst, dst_width);
            micro->iprops = IPROP_LOAD;
            micro->l = Mop::global(resolvedAddr);
            if (mba_.global_names.find(resolvedAddr) == mba_.global_names.end()) {
                char buf[32];
                snprintf(buf, sizeof(buf), "g_%llx", (unsigned long long)resolvedAddr);
                mba_.global_names[resolvedAddr] = buf;
            }
            emitInsn(micro);
            reg_state_.erase(dst);
            return;
        }
    }

    // v3.20: ADRP+LDR fusion — ldr rN, [rM, #offset] where rM has ADRP state
    // Pattern (AArch64 PIC global variable access):
    //   adrp x0, page         ; x0 = page address
    //   ldr  x0, [x0, #off]   ; x0 = *(page + off) = GOT entry → global var / import
    // Resolve to known global/string/import symbol
    if (!mo.has_index && mo.base_mreg >= 0) {
        auto rsIt = reg_state_.find(mo.base_mreg);
        // v4.10: Also check IMM_CONST (from adrp+add fusion) — when add has
        // already resolved the full address, ldr xN,[xN,#off] should still
        // be treated as a global load, not a raw memory access.
        if (rsIt != reg_state_.end() && is_aarch64_ &&
            (rsIt->second.kind == RegState::ADRP ||
             (rsIt->second.kind == RegState::IMM_CONST && rsIt->second.value != 0))) {
            uint64_t full_addr = rsIt->second.value + (uint64_t)mo.offset;
            // Check GOT-to-name mapping first (covers GLOB_DAT and JUMP_SLOT entries)
            auto gotIt = got_to_name_.find(full_addr);
            if (gotIt != got_to_name_.end()) {
                // GOT entry resolved to import/global name
                auto* micro = new MicroInsn(OP_LDC, insn.addr);
                micro->src_asm = insn.mnemonic + " " + insn.op_str;
                micro->def_mreg = dst;
                micro->d = Mop::reg(dst, dst_width);
                micro->l = Mop::global(full_addr);
                micro->iprops = IPROP_LOAD;
                emitInsn(micro);
                // Track as GOT_ADDR so subsequent ldr on this reg resolves correctly
                reg_state_[dst].kind = RegState::GOT_ADDR;
                reg_state_[dst].value = full_addr;
                reg_state_[dst].sym_name = gotIt->second;
                // Also register in global_names for CTree resolution
                if (mba_.global_names.find(full_addr) == mba_.global_names.end())
                    mba_.global_names[full_addr] = gotIt->second;
                return;
            }
            // Check string references (v10.1: ASCII + UTF-16)
            std::string strContent5;
            bool isUtf16_5 = false;
            size_t strLen5 = 0;
            if (resolveStringRef(full_addr, strContent5, isUtf16_5, strLen5)) {
                auto* micro = new MicroInsn(OP_LDC, insn.addr);
                micro->src_asm = insn.mnemonic + " " + insn.op_str;
                micro->def_mreg = dst;
                micro->d = Mop::reg(dst, dst_width);
                micro->l = Mop::str(full_addr, strContent5);
                micro->iprops = IPROP_LOAD;
                emitInsn(micro);
                // v10.1: Mark RESOLVED_SYM so handleCall can pass the string
                // (including UTF-16 best-effort content) as a literal argument.
                reg_state_[dst].kind = RegState::RESOLVED_SYM;
                reg_state_[dst].sym_name = strContent5;
                reg_state_[dst].value = full_addr;
                return;
            }
            // Check global names
            auto globIt = mba_.global_names.find(full_addr);
            if (globIt != mba_.global_names.end()) {
                auto* micro = new MicroInsn(OP_LDC, insn.addr);
                micro->src_asm = insn.mnemonic + " " + insn.op_str;
                micro->def_mreg = dst;
                micro->d = Mop::reg(dst, dst_width);
                micro->l = Mop::global(full_addr);
                micro->iprops = IPROP_LOAD;
                emitInsn(micro);
                reg_state_[dst].kind = RegState::GOT_ADDR;
                reg_state_[dst].value = full_addr;
                reg_state_[dst].sym_name = globIt->second;
                return;
            }
            // Address resolved but unknown — emit as GLOBAL anyway for CTree
            auto* micro = new MicroInsn(OP_LDC, insn.addr);
            micro->src_asm = insn.mnemonic + " " + insn.op_str;
            micro->def_mreg = dst;
            micro->d = Mop::reg(dst, dst_width);
            micro->l = Mop::global(full_addr);
            micro->iprops = IPROP_LOAD;
            emitInsn(micro);
            reg_state_[dst].kind = RegState::GOT_ADDR;
            reg_state_[dst].value = full_addr;
            return;
        }
    }

    // v8.1: Vtable dispatch resolution (ARM32 + AArch64)
    // Pattern: ldr xM, [xN, #offset] where xN is VTABLE_PTR
    // (loaded from this->vtable = *(this)).
    // If the consensus map has a function name for this offset, resolve it.
    // Otherwise, emit a generic vfunc_N name.
    if (!mo.has_index && mo.base_mreg >= 0 && mo.offset > 0) {
        auto rsIt = reg_state_.find(mo.base_mreg);
        if (rsIt != reg_state_.end() && rsIt->second.kind == RegState::VTABLE_PTR) {
            int off = (int)mo.offset;
            int entrySize = is_aarch64_ ? 8 : 4;
            auto consIt = vtable_consensus_.find(off);
            if (consIt != vtable_consensus_.end()) {
                auto* vmicro = new MicroInsn(OP_LOAD, insn.addr);
                vmicro->src_asm = insn.mnemonic + " " + insn.op_str;
                vmicro->def_mreg = dst;
                vmicro->d = Mop::reg(dst, dst_width);
                vmicro->iprops = IPROP_LOAD;
                vmicro->l = Mop::mem(mo.base_mreg, mo.offset, width, is_signed);
                emitInsn(vmicro);
                reg_state_[dst].kind = RegState::RESOLVED_SYM;
                reg_state_[dst].sym_name = consIt->second;
                return;
            }
            // Even if no consensus, mark as resolved with a generic name
            int entryIdx = off / entrySize;
            char vname[32];
            snprintf(vname, sizeof(vname), "vfunc_%d", entryIdx);
            auto* vmicro = new MicroInsn(OP_LOAD, insn.addr);
            vmicro->src_asm = insn.mnemonic + " " + insn.op_str;
            vmicro->def_mreg = dst;
            vmicro->d = Mop::reg(dst, dst_width);
            vmicro->iprops = IPROP_LOAD;
            vmicro->l = Mop::mem(mo.base_mreg, mo.offset, width, is_signed);
            emitInsn(vmicro);
            reg_state_[dst].kind = RegState::RESOLVED_SYM;
            reg_state_[dst].sym_name = vname;
            return;
        }
    }

    auto* micro = new MicroInsn(OP_LOAD, insn.addr);
    micro->src_asm = insn.mnemonic + " " + insn.op_str;
    micro->def_mreg = dst;
    micro->d = Mop::reg(dst, dst_width);
    micro->iprops = IPROP_LOAD;

    if (mo.has_index) {
        // [base, index, lsl #shift] → add tmp, base, (index << shift); load from tmp
        int tmp = mba_.allocUnique(8);
        auto* addr_calc = new MicroInsn(OP_ADD, insn.addr);
        addr_calc->def_mreg = tmp;
        addr_calc->d = Mop::reg(tmp, 8);
        addr_calc->l = Mop::reg(mo.base_mreg, 8);
        if (mo.shift > 0) {
            int tmp2 = mba_.allocUnique(8);
            auto* shf = new MicroInsn(OP_SHL, insn.addr);
            shf->def_mreg = tmp2;
            shf->d = Mop::reg(tmp2, 8);
            shf->l = Mop::reg(mo.index_mreg, 8);
            shf->r = Mop::imm64(mo.shift, 8);
            emitInsn(shf);
            addr_calc->r = Mop::reg(tmp2, 8);
        } else {
            addr_calc->r = Mop::reg(mo.index_mreg, 8);
        }
        emitInsn(addr_calc);
        micro->l = Mop::mem(tmp, 0, width, is_signed);
    } else {
        micro->l = Mop::mem(mo.base_mreg, mo.offset, width, is_signed);
    }
    reg_state_.erase(dst);
    emitInsn(micro);

    // v8.2: Vtable pointer load detection (ARM32 + AArch64)
    // Patterns:
    //   AArch64: ldr x0, [x0] — self-referencing from "this" (base == dst == 100)
    //   ARM32:  ldr rN, [r0] — load from "this" pointer (base == 100, offset == 0)
    // Both load the vtable pointer from the object.
    // v9.0: Also detect when the loaded value is a known vtable address
    // (from ELF symbol table), enabling VTABLE_PTR for any register.
    // Also detect cascading: if base is already VTABLE_PTR and offset > 0,
    // loading from vtable is a virtual function pointer load.
    if (!mo.has_index && mo.offset == 0 && mo.base_mreg == 100) {
        reg_state_[dst].kind = RegState::VTABLE_PTR;
        reg_state_[dst].vtbl_base_mreg = dst;
    } else if (!vtable_addresses_.empty()) {
        // v9.0: Check if the loaded address is a known vtable address
        // The loaded value is the vtable address itself
        // We can't check the value directly at emit time, but we can check
        // if loading from a known vtable global variable
        // For now, also detect cascading: base is VTABLE_PTR and offset is
        // a vtable entry offset (multiple of pointer size)
        int entrySize = is_aarch64_ ? 8 : 4;
        auto baseIt = reg_state_.find(mo.base_mreg);
        if (baseIt != reg_state_.end() && baseIt->second.kind == RegState::VTABLE_PTR) {
            if (!mo.has_index && mo.offset > 0 && mo.offset % entrySize == 0) {
                // Loading from vtable at known offset — this is a virtual function pointer
                // The consensus lookup will happen in the vtable offset handling code
                // Mark destination as potentially a vtable entry
                reg_state_[dst].kind = RegState::VTFUNC_PTR;
                reg_state_[dst].vtbl_base_mreg = baseIt->second.vtbl_base_mreg;
            }
        }
    }

    // Handle writeback for pre/post indexed
    if (mo.pre_indexed || mo.post_indexed) {
        int sp_mreg = mba_.phys_to_mreg.count("sp") ? mba_.phys_to_mreg["sp"] : 131;
        if (mo.base_mreg == sp_mreg) {
            // sp adjustment (prologue/epilogue) — suppress
        } else {
            auto* wb = new MicroInsn(OP_ADD, insn.addr);
            wb->def_mreg = mo.base_mreg;
            wb->d = Mop::reg(mo.base_mreg, 8);
            wb->l = Mop::reg(mo.base_mreg, 8);
            wb->r = Mop::imm64(mo.offset, 8);
            emitInsn(wb);
        }
    }
}

// ── Handle store (str, strb, strh) ──
void MicrocodeEmitter::handleStore(const AsmInsn& insn, int width) {
    auto ops = splitOps(insn.op_str);
    if (ops.size() < 2) return;
    int src = parseReg(ops[0]);
    int src_width = (ops[0][0] == 'w') ? 4 : (is_aarch64_ ? 8 : 4);

    // v24.0: AArch64 str s0/d0/q0, [xN] → floating-point store (OP_FSTORE)
    // ARM64 uses the same "str" mnemonic for both integer and floating-point
    // stores. If the source register is a VFP/NEON register (mreg >= 200),
    // emit OP_FSTORE instead of OP_STORE so type inference assigns FLOAT type.
    // 对标 Ghidra FLOAT_STORE TypeOp: floating-point stores consume floating-point
    // values.
    if (is_aarch64_ && src >= 200) {
        // Determine the correct width from the register name
        int fp_width = width;
        if (ops[0][0] == 's') fp_width = 4;
        else if (ops[0][0] == 'd') fp_width = 8;
        else if (ops[0][0] == 'q' || ops[0][0] == 'v') fp_width = 16;
        handleFStore(insn, fp_width);
        return;
    }

    MemOperand mo = parseMemOp(ops[1]);
    if (ops.size() >= 3 && !mo.has_index && mo.offset == 0 &&
        ops[1].back() == ']' && isImm(ops[2])) {
        mo.post_indexed = true;
        mo.offset = parseImm(ops[2]);
    }

    auto* micro = new MicroInsn(OP_STORE, insn.addr);
    micro->src_asm = insn.mnemonic + " " + insn.op_str;
    micro->def_mreg = -1;  // store doesn't define a register
    micro->iprops = IPROP_STORE | IPROP_VOLATILE;
    micro->l = Mop::reg(src, src_width);

    if (mo.has_index) {
        int tmp = mba_.allocUnique(8);
        auto* addr_calc = new MicroInsn(OP_ADD, insn.addr);
        addr_calc->def_mreg = tmp;
        addr_calc->d = Mop::reg(tmp, 8);
        addr_calc->l = Mop::reg(mo.base_mreg, 8);
        if (mo.shift > 0) {
            int tmp2 = mba_.allocUnique(8);
            auto* shf = new MicroInsn(OP_SHL, insn.addr);
            shf->def_mreg = tmp2;
            shf->d = Mop::reg(tmp2, 8);
            shf->l = Mop::reg(mo.index_mreg, 8);
            shf->r = Mop::imm64(mo.shift, 8);
            emitInsn(shf);
            addr_calc->r = Mop::reg(tmp2, 8);
        } else {
            addr_calc->r = Mop::reg(mo.index_mreg, 8);
        }
        emitInsn(addr_calc);
        micro->d = Mop::mem(tmp, 0, width);
    } else {
        micro->d = Mop::mem(mo.base_mreg, mo.offset, width);
    }
    emitInsn(micro);

    // Handle writeback
    if (mo.pre_indexed || mo.post_indexed) {
        int sp_mreg = mba_.phys_to_mreg.count("sp") ? mba_.phys_to_mreg["sp"] : 131;
        if (mo.base_mreg != sp_mreg) {
            auto* wb = new MicroInsn(OP_ADD, insn.addr);
            wb->def_mreg = mo.base_mreg;
            wb->d = Mop::reg(mo.base_mreg, 8);
            wb->l = Mop::reg(mo.base_mreg, 8);
            wb->r = Mop::imm64(mo.offset, 8);
            emitInsn(wb);
        }
    }
}

// ── Handle VFP/NEON floating-point load (OP_FLOAD) ──
// 对标 Ghidra FLOAT_LOAD TypeOp: creates a floating-point typed variable.
// Uses the same memory operand parsing as handleLoad but emits OP_FLOAD
// so type inference can assign FLOAT type instead of UNSIGNED/SIGNED.
void MicrocodeEmitter::handleFLoad(const AsmInsn& insn, int width) {
    auto ops = splitOps(insn.op_str);
    if (ops.size() < 2) return;
    int dst = parseReg(ops[0]);
    int dst_width = (ops[0][0] == 's') ? 4 : (ops[0][0] == 'd') ? 8 : 4;

    // v24.0: For ARM64, determine the memory width from the register name.
    // ARM64 ldr s0/d0/q0 uses the same mnemonic, so the caller's width
    // parameter may be wrong (e.g., 8 for integer default). Override based
    // on the register name to ensure the memory operand width matches.
    // 对标 Ghidra FLOAT_LOAD: memory access width = register width.
    if (is_aarch64_) {
        if (ops[0][0] == 's') width = 4;
        else if (ops[0][0] == 'd') width = 8;
        else if (ops[0][0] == 'q' || ops[0][0] == 'v') width = 16;
    }

    // Parse memory operand (same as handleLoad)
    MemOperand mo;
    if (ops.size() >= 2) {
        mo = parseMemOp(ops[1]);
        if (ops.size() >= 3 && !mo.has_index && mo.offset == 0 &&
            ops[1].find(']') != std::string::npos &&
            ops[1].back() == ']' && isImm(ops[2])) {
            mo.post_indexed = true;
            mo.offset = parseImm(ops[2]);
        }
    }

    auto* micro = new MicroInsn(OP_FLOAD, insn.addr);
    micro->src_asm = insn.mnemonic + " " + insn.op_str;
    micro->def_mreg = dst;
    micro->d = Mop::reg(dst, dst_width);
    micro->iprops = IPROP_LOAD;

    if (mo.has_index) {
        int tmp = mba_.allocUnique(8);
        auto* addr_calc = new MicroInsn(OP_ADD, insn.addr);
        addr_calc->def_mreg = tmp;
        addr_calc->d = Mop::reg(tmp, 8);
        addr_calc->l = Mop::reg(mo.base_mreg, 8);
        if (mo.shift > 0) {
            int tmp2 = mba_.allocUnique(8);
            auto* shf = new MicroInsn(OP_SHL, insn.addr);
            shf->def_mreg = tmp2;
            shf->d = Mop::reg(tmp2, 8);
            shf->l = Mop::reg(mo.index_mreg, 8);
            shf->r = Mop::imm64(mo.shift, 8);
            emitInsn(shf);
            addr_calc->r = Mop::reg(tmp2, 8);
        } else {
            addr_calc->r = Mop::reg(mo.index_mreg, 8);
        }
        emitInsn(addr_calc);
        micro->l = Mop::mem(tmp, 0, width, false);
    } else {
        micro->l = Mop::mem(mo.base_mreg, mo.offset, width, false);
    }
    emitInsn(micro);

    // Handle writeback
    if (mo.pre_indexed || mo.post_indexed) {
        int sp_mreg = mba_.phys_to_mreg.count("sp") ? mba_.phys_to_mreg["sp"] : 131;
        if (mo.base_mreg != sp_mreg) {
            auto* wb = new MicroInsn(OP_ADD, insn.addr);
            wb->def_mreg = mo.base_mreg;
            wb->d = Mop::reg(mo.base_mreg, 8);
            wb->l = Mop::reg(mo.base_mreg, 8);
            wb->r = Mop::imm64(mo.offset, 8);
            emitInsn(wb);
        }
    }
}

// ── Handle VFP/NEON floating-point store (OP_FSTORE) ──
// 对标 Ghidra FLOAT_STORE TypeOp: stores a floating-point typed value.
void MicrocodeEmitter::handleFStore(const AsmInsn& insn, int width) {
    auto ops = splitOps(insn.op_str);
    if (ops.size() < 2) return;
    int src = parseReg(ops[0]);

    // v24.0: For ARM64, determine the width from the register name.
    // ARM64 str s0/d0/q0 uses the same mnemonic, so the caller's width
    // parameter may be wrong (e.g., 8 for integer default). Override based
    // on the register name to ensure both the register and memory operand
    // widths match the actual floating-point register size.
    // 对标 Ghidra FLOAT_STORE: memory access width = register width.
    if (is_aarch64_) {
        if (ops[0][0] == 's') width = 4;
        else if (ops[0][0] == 'd') width = 8;
        else if (ops[0][0] == 'q' || ops[0][0] == 'v') width = 16;
    }

    MemOperand mo = parseMemOp(ops[1]);
    if (ops.size() >= 3 && !mo.has_index && mo.offset == 0 &&
        ops[1].back() == ']' && isImm(ops[2])) {
        mo.post_indexed = true;
        mo.offset = parseImm(ops[2]);
    }

    auto* micro = new MicroInsn(OP_FSTORE, insn.addr);
    micro->src_asm = insn.mnemonic + " " + insn.op_str;
    micro->def_mreg = -1;
    micro->iprops = IPROP_STORE | IPROP_VOLATILE;
    micro->l = Mop::reg(src, width);

    if (mo.has_index) {
        int tmp = mba_.allocUnique(8);
        auto* addr_calc = new MicroInsn(OP_ADD, insn.addr);
        addr_calc->def_mreg = tmp;
        addr_calc->d = Mop::reg(tmp, 8);
        addr_calc->l = Mop::reg(mo.base_mreg, 8);
        if (mo.shift > 0) {
            int tmp2 = mba_.allocUnique(8);
            auto* shf = new MicroInsn(OP_SHL, insn.addr);
            shf->def_mreg = tmp2;
            shf->d = Mop::reg(tmp2, 8);
            shf->l = Mop::reg(mo.index_mreg, 8);
            shf->r = Mop::imm64(mo.shift, 8);
            emitInsn(shf);
            addr_calc->r = Mop::reg(tmp2, 8);
        } else {
            addr_calc->r = Mop::reg(mo.index_mreg, 8);
        }
        emitInsn(addr_calc);
        micro->d = Mop::mem(tmp, 0, width);
    } else {
        micro->d = Mop::mem(mo.base_mreg, mo.offset, width);
    }
    emitInsn(micro);

    // Handle writeback
    if (mo.pre_indexed || mo.post_indexed) {
        int sp_mreg = mba_.phys_to_mreg.count("sp") ? mba_.phys_to_mreg["sp"] : 131;
        if (mo.base_mreg != sp_mreg) {
            auto* wb = new MicroInsn(OP_ADD, insn.addr);
            wb->def_mreg = mo.base_mreg;
            wb->d = Mop::reg(mo.base_mreg, 8);
            wb->l = Mop::reg(mo.base_mreg, 8);
            wb->r = Mop::imm64(mo.offset, 8);
            emitInsn(wb);
        }
    }
}

// ── Handle adrp ──
void MicrocodeEmitter::handleAdrp(const AsmInsn& insn) {
    auto ops = splitOps(insn.op_str);
    if (ops.size() < 2) return;
    int dst = parseReg(ops[0]);
    int64_t page = parseImm(ops[1]);

    adrp_state_[dst] = {true, (uint64_t)page, dst};
    reg_state_[dst] = RegState(); reg_state_[dst].kind = RegState::ADRP; reg_state_[dst].value = (uint64_t)page;

    auto* micro = new MicroInsn(OP_LDC, insn.addr);
    micro->src_asm = insn.mnemonic + " " + insn.op_str;
    micro->def_mreg = dst;
    micro->d = Mop::reg(dst, 8);
    micro->l = Mop::imm64(page, 8);
    micro->iprops = IPROP_ADRP;
    emitInsn(micro);
}

// ── Handle branch ──
void MicrocodeEmitter::handleBranch(const AsmInsn& insn, bool is_cond, CondCode cc) {
    uint64_t target = (uint64_t)parseImm(insn.op_str);

    if (is_cond) {
        auto* micro = new MicroInsn(OP_CBRANCH, insn.addr);
        micro->src_asm = insn.mnemonic + " " + insn.op_str;
        micro->iprops = IPROP_BRANCHED | IPROP_VOLATILE;
        micro->target_addr = target;
        micro->cond = cc;
        // Set condition operands from tracked cmp state
        int width = is_aarch64_ ? 8 : 4;
        if (cmp_a_mreg_ >= 0) {
            micro->l = Mop::reg(cmp_a_mreg_, width);
        }
        if (cmp_b_is_imm_) {
            micro->r = Mop::imm64(cmp_b_imm_, width);
            micro->cond_is_imm = true;
            micro->cond_imm = cmp_b_imm_;
        } else if (cmp_b_mreg_ >= 0) {
            micro->r = Mop::reg(cmp_b_mreg_, width);
        }
        emitInsn(micro);
    } else {
        // v9.17: Tail call detection.
        // When lr was just restored (via pop {lr}) and we see an
        // unconditional branch, the branch is a tail call.
        // Emit OP_CALL + OP_RET instead of OP_GOTO so the decompiler
        // produces "return target_func(args)" instead of a bare goto.
        if (lr_restored_) {
            lr_restored_ = false;

            // Emit a tail call: CALL target, then RET
            auto* call = new MicroInsn(OP_CALL, insn.addr);
            call->src_asm = insn.mnemonic + " " + insn.op_str;
            call->iprops = IPROP_CALL | IPROP_VOLATILE;
            call->target_addr = target;
            call->d = Mop::reg(100, is_aarch64_ ? 8 : 4);  // return value in r0
            auto ci = std::make_shared<CallInfo>();
            ci->target_addr = target;
            ci->is_indirect = false;
            call->call_info = ci;
            emitInsn(call);

            auto* ret = new MicroInsn(OP_RET, insn.addr);
            ret->src_asm = insn.mnemonic + " " + insn.op_str;
            ret->iprops = IPROP_RET;
            ret->l = Mop::reg(100, is_aarch64_ ? 8 : 4);  // return r0
            emitInsn(ret);
        } else {
            auto* micro = new MicroInsn(OP_GOTO, insn.addr);
            micro->src_asm = insn.mnemonic + " " + insn.op_str;
            micro->iprops = IPROP_BRANCHED | IPROP_VOLATILE;
            micro->target_addr = target;
            emitInsn(micro);
        }
    }
}

// ── Handle call (bl/blr) ──
void MicrocodeEmitter::handleCall(const AsmInsn& insn, bool is_indirect) {
    auto* micro = new MicroInsn(is_indirect ? OP_ICALL : OP_CALL, insn.addr);
    micro->src_asm = insn.mnemonic + " " + insn.op_str;
    micro->iprops = IPROP_CALL | IPROP_VOLATILE;
    auto call_info = std::make_shared<CallInfo>();
    call_info->is_indirect = is_indirect;
    int call_target_reg = -1;  // v8.5: for indirect call, the target register

    if (!is_indirect) {
        // bl target_addr
        uint64_t target = (uint64_t)parseImm(insn.op_str);
        call_info->target_addr = target;
        micro->target_addr = target;

        // v4.11: Resolve target name from global_names at emit time.
        // Previously, name resolution was deferred to buildCallExpr in
        // CTree builder, but that only had access to mba_->global_names
        // which might not contain all symbols. Now we resolve immediately
        // so the call emits the demangled C++ name instead of sub_XXXX.
        // Also try target & ~1 for ARM32 Thumb mode (bit 0 is mode flag).
        uint64_t targets[] = {target, target & ~1ULL, target | 1ULL};
        for (uint64_t t : targets) {
            auto nameIt = mba_.global_names.find(t);
            if (nameIt != mba_.global_names.end() && !nameIt->second.empty()) {
                call_info->target_name = nameIt->second;
                break;
            }
        }
    } else {
        // blr xN
        auto ops = splitOps(insn.op_str);
        if (!ops.empty()) {
            call_target_reg = parseReg(ops[0]);
            micro->l = Mop::reg(call_target_reg, 8);

            // v3.19: If the register has RESOLVED_SYM state (from GOT
            // dereference: ldr-literal + add-pc + ldr-got), set target_name
            // so buildCallExpr uses the real import name (e.g. "pthread_create")
            // instead of (*var_XX).
            // 对标 Ghidra action.cc: convert indirect call to direct when
            // the call target can be resolved.
            auto rsIt = reg_state_.find(call_target_reg);
            if (rsIt != reg_state_.end() &&
                rsIt->second.kind == RegState::RESOLVED_SYM) {
                call_info->target_name = rsIt->second.sym_name;
            }
        }
    }

    micro->d = Mop::reg(100, 8);  // return value in x0
    micro->def_mreg = 100;
    micro->call_info = call_info;

    // v4.5: Resolve string arguments from register state.
    // For ARM32: r0-r3 (mreg 100-103). For AArch64: x0-x7 (mreg 100-107).
    // When a call like "bl 0x15f0" is preceded by "add r0, pc, r0" that
    // resolves to a string constant, record it so CTree builder can emit:
    //   sub_15f0("_ZN15MinecraftClient4initEv")
    int numArgRegs = is_aarch64_ ? 8 : 4;
    for (int ai = 0; ai < numArgRegs; ai++) {
        int argMreg = 100 + ai;
        // v8.5: For indirect calls, the call target register is NOT an argument.
        // e.g., "blx r3" — r3 is the call target, arguments are r0,r1,r2.
        if (is_indirect && argMreg == call_target_reg) continue;
        auto it = reg_state_.find(argMreg);
        if (it != reg_state_.end() && it->second.kind == RegState::RESOLVED_SYM) {
            call_info->string_args[ai] = it->second.sym_name;
        }
    }

    emitInsn(micro);
    // v8.5: Clear ALL arg register states after a call. A call clobbers r0-r3
    // (ARM32) or x0-x7 (AArch64), so any tracked state is stale. This prevents
    // callee names from leaking as string arguments to the next call.
    for (int ai = 0; ai < numArgRegs; ai++) {
        reg_state_.erase(100 + ai);
    }
}

// ── Handle ret ──
void MicrocodeEmitter::handleRet(const AsmInsn& insn) {
    auto* micro = new MicroInsn(OP_RET, insn.addr);
    micro->src_asm = insn.mnemonic + " " + insn.op_str;
    micro->iprops = IPROP_RET | IPROP_VOLATILE;
    // Return value is in x0 (AArch64) or r0 (ARM32)
    micro->l = Mop::reg(100, is_aarch64_ ? 8 : 4);
    emitInsn(micro);
}

// ── Handle stp (store pair) ──
void MicrocodeEmitter::handleStp(const AsmInsn& insn) {
    auto ops = splitOps(insn.op_str);
    if (ops.size() < 3) return;
    int reg1 = parseReg(ops[0]);
    int reg2 = parseReg(ops[1]);
    int width = (ops[0][0] == 'w') ? 4 : (is_aarch64_ ? 8 : 4);

    MemOperand mo = parseMemOp(ops[2]);

    // store reg1 at [base + offset]
    auto* s1 = new MicroInsn(OP_STORE, insn.addr);
    s1->src_asm = insn.mnemonic + " " + ops[0] + ", " + ops[2];
    s1->iprops = IPROP_STORE | IPROP_VOLATILE;
    s1->l = Mop::reg(reg1, width);
    s1->d = Mop::mem(mo.base_mreg, mo.offset, width);
    emitInsn(s1);

    // store reg2 at [base + offset + width]
    auto* s2 = new MicroInsn(OP_STORE, insn.addr);
    s2->src_asm = insn.mnemonic + " " + ops[1] + ", " + ops[2];
    s2->iprops = IPROP_STORE | IPROP_VOLATILE;
    s2->l = Mop::reg(reg2, width);
    s2->d = Mop::mem(mo.base_mreg, mo.offset + width, width);
    emitInsn(s2);

    // Handle writeback (pre/post indexed)
    if (mo.pre_indexed || mo.post_indexed) {
        auto* wb = new MicroInsn(OP_ADD, insn.addr);
        wb->def_mreg = mo.base_mreg;
        wb->d = Mop::reg(mo.base_mreg, 8);
        wb->l = Mop::reg(mo.base_mreg, 8);
        wb->r = Mop::imm64(mo.offset, 8);
        emitInsn(wb);
    }
}

// ── Handle strd (store register dual, ARM32) ──
// v9.15: strd r2, r3, [r4, #0x20] → emit two OP_STORE:
//   store r2 at [r4+0x20]; store r3 at [r4+0x24]
void MicrocodeEmitter::handleStrd(const AsmInsn& insn) {
    auto ops = splitOps(insn.op_str);
    if (ops.size() < 3) return;
    int reg1 = parseReg(ops[0]);
    int reg2 = parseReg(ops[1]);
    // ARM32 strd operates on 32-bit registers (4 bytes each)
    int width = 4;

    MemOperand mo = parseMemOp(ops[2]);

    // store reg1 at [base + offset]
    auto* s1 = new MicroInsn(OP_STORE, insn.addr);
    s1->src_asm = insn.mnemonic + " " + ops[0] + ", " + ops[2];
    s1->iprops = IPROP_STORE | IPROP_VOLATILE;
    s1->l = Mop::reg(reg1, width);
    s1->d = Mop::mem(mo.base_mreg, mo.offset, width);
    emitInsn(s1);

    // store reg2 at [base + offset + 4]
    auto* s2 = new MicroInsn(OP_STORE, insn.addr);
    s2->src_asm = insn.mnemonic + " " + ops[1] + ", " + ops[2];
    s2->iprops = IPROP_STORE | IPROP_VOLATILE;
    s2->l = Mop::reg(reg2, width);
    s2->d = Mop::mem(mo.base_mreg, mo.offset + 4, width);
    emitInsn(s2);
}

// ── Handle ldp (load pair) ──
void MicrocodeEmitter::handleLdp(const AsmInsn& insn) {
    auto ops = splitOps(insn.op_str);
    if (ops.size() < 3) return;
    int reg1 = parseReg(ops[0]);
    int reg2 = parseReg(ops[1]);
    int width = (ops[0][0] == 'w') ? 4 : (is_aarch64_ ? 8 : 4);

    MemOperand mo = parseMemOp(ops[2]);

    // load reg1 from [base + offset]
    auto* l1 = new MicroInsn(OP_LOAD, insn.addr);
    l1->src_asm = insn.mnemonic + " " + ops[0] + ", " + ops[2];
    l1->iprops = IPROP_LOAD;
    l1->def_mreg = reg1;
    l1->d = Mop::reg(reg1, width);
    l1->l = Mop::mem(mo.base_mreg, mo.offset, width);
    emitInsn(l1);

    // load reg2 from [base + offset + width]
    auto* l2 = new MicroInsn(OP_LOAD, insn.addr);
    l2->src_asm = insn.mnemonic + " " + ops[1] + ", " + ops[2];
    l2->iprops = IPROP_LOAD;
    l2->def_mreg = reg2;
    l2->d = Mop::reg(reg2, width);
    l2->l = Mop::mem(mo.base_mreg, mo.offset + width, width);
    emitInsn(l2);

    // Handle writeback
    if (mo.pre_indexed || mo.post_indexed) {
        auto* wb = new MicroInsn(OP_ADD, insn.addr);
        wb->def_mreg = mo.base_mreg;
        wb->d = Mop::reg(mo.base_mreg, 8);
        wb->l = Mop::reg(mo.base_mreg, 8);
        wb->r = Mop::imm64(mo.offset, 8);
        emitInsn(wb);
    }
    reg_state_.erase(reg1);
    reg_state_.erase(reg2);
}

// ── Handle cbz/cbnz ──
void MicrocodeEmitter::handleCbz(const AsmInsn& insn, bool is_nz) {
    auto ops = splitOps(insn.op_str);
    if (ops.size() < 2) return;
    int reg = parseReg(ops[0]);
    int width = (ops[0][0] == 'w') ? 4 : (is_aarch64_ ? 8 : 4);
    uint64_t target = (uint64_t)parseImm(ops[1]);

    auto* micro = new MicroInsn(OP_CBRANCH, insn.addr);
    micro->src_asm = insn.mnemonic + " " + insn.op_str;
    micro->iprops = IPROP_BRANCHED | IPROP_VOLATILE;
    micro->target_addr = target;
    micro->cond = is_nz ? CC_NE : CC_EQ;
    micro->l = Mop::reg(reg, width);
    micro->r = Mop::imm64(0, width);
    micro->cond_is_imm = true;
    micro->cond_imm = 0;
    emitInsn(micro);
}

// ── Handle tbz/tbnz ──
void MicrocodeEmitter::handleTbz(const AsmInsn& insn, bool is_nz) {
    auto ops = splitOps(insn.op_str);
    if (ops.size() < 3) return;
    int reg = parseReg(ops[0]);
    int width = (ops[0][0] == 'w') ? 4 : (is_aarch64_ ? 8 : 4);
    int64_t bit = parseImm(ops[1]);
    uint64_t target = (uint64_t)parseImm(ops[2]);

    // tbz: branch if (reg & (1<<bit)) == 0
    // tbnz: branch if (reg & (1<<bit)) != 0
    auto* micro = new MicroInsn(OP_CBRANCH, insn.addr);
    micro->src_asm = insn.mnemonic + " " + insn.op_str;
    micro->iprops = IPROP_BRANCHED | IPROP_VOLATILE;
    micro->target_addr = target;
    micro->cond = is_nz ? CC_NE : CC_EQ;

    // Emit: l = reg & (1 << bit), r = 0
    int tmp = mba_.allocUnique(width);
    auto* and_insn = new MicroInsn(OP_AND, insn.addr);
    and_insn->def_mreg = tmp;
    and_insn->d = Mop::reg(tmp, width);
    and_insn->l = Mop::reg(reg, width);
    and_insn->r = Mop::imm64(1LL << bit, width);
    emitInsn(and_insn);

    micro->l = Mop::reg(tmp, width);
    micro->r = Mop::imm64(0, width);
    micro->cond_is_imm = true;
    micro->cond_imm = 0;
    emitInsn(micro);
}

// ── Handle madd (a = b * c + d) ──
void MicrocodeEmitter::handleMadd(const AsmInsn& insn) {
    auto ops = splitOps(insn.op_str);
    if (ops.size() < 4) return;
    int dst = parseReg(ops[0]);
    int width = (ops[0][0] == 'w') ? 4 : (is_aarch64_ ? 8 : 4);

    // mul tmp, ops[1], ops[2]
    int tmp = mba_.allocUnique(width);
    auto* mul = new MicroInsn(OP_MUL, insn.addr);
    mul->def_mreg = tmp;
    mul->d = Mop::reg(tmp, width);
    mul->l = Mop::reg(parseReg(ops[1]), width);
    mul->r = Mop::reg(parseReg(ops[2]), width);
    emitInsn(mul);

    // add dst, tmp, ops[3]
    auto* add = new MicroInsn(OP_ADD, insn.addr);
    add->def_mreg = dst;
    add->d = Mop::reg(dst, width);
    add->l = Mop::reg(tmp, width);
    add->r = Mop::reg(parseReg(ops[3]), width);
    add->src_asm = insn.mnemonic + " " + insn.op_str;
    emitInsn(add);
    reg_state_.erase(dst);
}

// ── Handle msub (a = b * c - d) ──
void MicrocodeEmitter::handleMsub(const AsmInsn& insn) {
    auto ops = splitOps(insn.op_str);
    if (ops.size() < 4) return;
    int dst = parseReg(ops[0]);
    int width = (ops[0][0] == 'w') ? 4 : (is_aarch64_ ? 8 : 4);

    int tmp = mba_.allocUnique(width);
    auto* mul = new MicroInsn(OP_MUL, insn.addr);
    mul->def_mreg = tmp;
    mul->d = Mop::reg(tmp, width);
    mul->l = Mop::reg(parseReg(ops[1]), width);
    mul->r = Mop::reg(parseReg(ops[2]), width);
    emitInsn(mul);

    auto* sub = new MicroInsn(OP_SUB, insn.addr);
    sub->def_mreg = dst;
    sub->d = Mop::reg(dst, width);
    sub->l = Mop::reg(tmp, width);
    sub->r = Mop::reg(parseReg(ops[3]), width);
    sub->src_asm = insn.mnemonic + " " + insn.op_str;
    emitInsn(sub);
    reg_state_.erase(dst);
}

// ── Handle csel (conditional select) ──
void MicrocodeEmitter::handleCsel(const AsmInsn& insn) {
    auto ops = splitOps(insn.op_str);
    if (ops.size() < 4) return;
    int dst = parseReg(ops[0]);
    int width = (ops[0][0] == 'w') ? 4 : (is_aarch64_ ? 8 : 4);
    CondCode cc = mapCond(ops[3]);

    // csel x0, x1, x2, eq → modeled as: if (cc) x0 = x1; else x0 = x2
    // For now, emit as a helper call
    auto* micro = new MicroInsn(OP_HELPER, insn.addr);
    micro->src_asm = insn.mnemonic + " " + insn.op_str;
    micro->def_mreg = dst;
    micro->d = Mop::reg(dst, width);
    micro->l = Mop::reg(parseReg(ops[1]), width);
    micro->r = Mop::reg(parseReg(ops[2]), width);
    micro->cond = cc;
    emitInsn(micro);
    reg_state_.erase(dst);
}

// ── Handle div ──
void MicrocodeEmitter::handleDiv(const AsmInsn& insn, bool is_signed) {
    auto ops = splitOps(insn.op_str);
    if (ops.size() < 3) return;
    int dst = parseReg(ops[0]);
    int width = (ops[0][0] == 'w') ? 4 : (is_aarch64_ ? 8 : 4);

    auto* micro = new MicroInsn(is_signed ? OP_SDIV : OP_UDIV, insn.addr);
    micro->src_asm = insn.mnemonic + " " + insn.op_str;
    micro->def_mreg = dst;
    micro->d = Mop::reg(dst, width);
    micro->l = Mop::reg(parseReg(ops[1]), width);
    micro->r = Mop::reg(parseReg(ops[2]), width);
    emitInsn(micro);
    reg_state_.erase(dst);
}

// ── Main emit function ──
MicrocodeBlockArray MicrocodeEmitter::emit(const std::vector<AsmInsn>& insns, uint64_t entry_addr) {
    mba_.entry_addr = entry_addr;
    // Initialize register map based on architecture
    if (is_aarch64_) {
        mba_.initRegMapAArch64();
    } else {
        mba_.initRegMapARM32();
    }

    if (insns.empty()) return std::move(mba_);

    // v9.17: Reset tail call detection state
    lr_restored_ = false;

    // v3.18: Normalize mnemonics and operands to lowercase.
    // Disassemblers may output uppercase (BEQ, LDR, R0, SP, FP) but all
    // emitter matching logic and register maps use lowercase. Without this,
    // ALL ARM32 conditional branches are silently ignored (BEQ != "b"/"beq"),
    // causing complete loss of control flow.
    std::vector<AsmInsn> normInsns;
    normInsns.reserve(insns.size());
    for (const auto& origInsn : insns) {
        AsmInsn ni = origInsn;
        for (char& c : ni.mnemonic) c = (char)std::tolower((unsigned char)c);
        for (char& c : ni.op_str) c = (char)std::tolower((unsigned char)c);
        normInsns.push_back(std::move(ni));
    }

    // ── Pass 1: Collect all branch/jump targets ──
    // v3.21: 修复 ARM32 条件分支 (ble, bne, beq 等) 未被收集为 jump target
    // 旧代码只检查 AArch64 格式 (b, b.eq, cbz 等)，导致 ARM32 的 ble/bne 等
    // 条件分支目标不被识别为块边界，整个控制流结构化失败。
    std::set<uint64_t> jump_targets;
    for (const auto& insn : normInsns) {
        const std::string& mn = insn.mnemonic;
        bool isBranch = false;
        if (is_aarch64_) {
            // AArch64: b, b.eq, bl, cbz, cbnz, tbz, tbnz
            isBranch = (mn == "b" || mn == "bl" || mn.substr(0, 2) == "b." ||
                        mn == "cbz" || mn == "cbnz" || mn == "tbz" || mn == "tbnz");
        } else {
            // ARM32: b, bl, ble, bne, beq, bhs, blo, bhi, bls, bge, blt, bgt, ble, etc.
            // Also bx, blx (but these are register branches, not immediate targets)
            if (mn == "b" || mn == "bl") {
                isBranch = true;
            } else if (mn.length() > 1 && mn[0] == 'b') {
                std::string suffix = mn.substr(1);
                if (suffix == "eq" || suffix == "ne" || suffix == "cs" || suffix == "hs" ||
                    suffix == "cc" || suffix == "lo" || suffix == "mi" || suffix == "pl" ||
                    suffix == "vs" || suffix == "vc" || suffix == "hi" || suffix == "ls" ||
                    suffix == "ge" || suffix == "lt" || suffix == "gt" || suffix == "le") {
                    isBranch = true;
                }
            }
        }
        if (isBranch) {
            auto ops = splitOps(insn.op_str);
            // Target is last operand for branches
            if (!ops.empty()) {
                std::string target_str = ops.back();
                if (isImm(target_str) || target_str[0] == '#') {
                    uint64_t target = (uint64_t)parseImm(target_str);
                    jump_targets.insert(target);
                }
            }
        }
    }

    // ── Pass 2: Create blocks and emit instructions ──
    startBlock(normInsns[0].addr);

    for (size_t i = 0; i < normInsns.size(); i++) {
        const auto& insn = normInsns[i];
        std::string mn = insn.mnemonic;

        // ── ARM32: Strip condition suffix ──
        // ARM32 instructions can have condition suffixes: addeq, movne, bne, etc.
        // We strip the suffix and handle the base instruction.
        std::string condSuffix;
        if (!is_aarch64_) {
            condSuffix = extractCondSuffix(mn);
            if (!condSuffix.empty() && condSuffix != "al") {
                mn = stripCondSuffix(mn);
            }
            // v8.2: Strip Thumb wide/narrow encoding suffix (.w / .n)
            // Capstone outputs ldr.w, str.w, etc. for Thumb wide encodings.
            // These must be dispatched to the same handlers as ldr, str, etc.
            if (mn.size() > 2 && mn[mn.size() - 2] == '.' &&
                (mn.back() == 'w' || mn.back() == 'n')) {
                mn = mn.substr(0, mn.size() - 2);
            }

            // v9.18: Handle conditional non-branch instructions.
            // ARM32 supports conditional execution of most instructions
            // (movgt, addne, ldreq, etc.). For non-branch, non-call, non-ret
            // instructions with a condition suffix, emit a CBRANCH with the
            // inverted condition to skip the instruction when the condition
            // is NOT met. This mirrors the ARM predication model.
            if (!condSuffix.empty() && condSuffix != "al" &&
                mn != "b" && mn != "bl" && mn != "bx" && mn != "blx" &&
                mn != "pop" && mn != "push" && mn != "tbb" && mn != "tbh" &&
                mn != "ldm" && mn != "stm" && mn != "ldmia" && mn != "stmia" &&
                mn != "ldmdb" && mn != "stmdb" &&
                cmp_a_mreg_ >= 0) {

                CondCode cc = mapCond(condSuffix);
                CondCode invCC = invertCond(cc);

                uint64_t nextAddr = (i + 1 < normInsns.size())
                    ? normInsns[i + 1].addr
                    : insn.addr + insn.bytes_size;
                jump_targets.insert(nextAddr);

                auto* cbranch = new MicroInsn(OP_CBRANCH, insn.addr);
                cbranch->src_asm = insn.mnemonic + " " + insn.op_str;
                cbranch->iprops = IPROP_BRANCHED | IPROP_VOLATILE;
                cbranch->target_addr = nextAddr;
                cbranch->cond = invCC;
                int cw = is_aarch64_ ? 8 : 4;
                if (cmp_a_mreg_ >= 0) {
                    cbranch->l = Mop::reg(cmp_a_mreg_, cw);
                }
                if (cmp_b_is_imm_) {
                    cbranch->r = Mop::imm64(cmp_b_imm_, cw);
                    cbranch->cond_is_imm = true;
                    cbranch->cond_imm = cmp_b_imm_;
                } else if (cmp_b_mreg_ >= 0) {
                    cbranch->r = Mop::reg(cmp_b_mreg_, cw);
                }
                emitInsn(cbranch);
            }
        }

        // Check if we need to start a new block at this address
        if (jump_targets.count(insn.addr)) {
            auto* cur_blk = mba_.getBlock(cur_block_);
            if (cur_blk && cur_blk->head != nullptr) {
                startBlock(insn.addr);
            } else if (cur_blk && cur_blk->head == nullptr) {
                // v4.0: Previous block is empty (e.g. suppressed instruction
                // at a jump target). Start a fresh block at this address so
                // the empty block doesn't swallow subsequent instructions.
                startBlock(insn.addr);
            }
        }

        bool handled = false;

        // ── ARM32-specific branch instructions ──
        // ARM32 conditional branches: beq, bne, bgt, etc.
        // (AArch64 uses b.eq, b.ne, etc.)
        // v15.0: Use condSuffix instead of mn because mn has been stripped
        // of the condition suffix (line 2562). For bne, mn becomes "b" and
        // condSuffix is "ne". The old check `mn.length() > 1 && mn[0] == 'b'`
        // failed because mn.length() was 1 after stripping.
        if (!is_aarch64_ && mn == "b" && !condSuffix.empty() && condSuffix != "al") {
            CondCode cc = mapCond(condSuffix);
            handleBranch(insn, true, cc);
            handled = true;
        }
        // v16.0: Handle 3-char ARM32 conditional branch mnemonics that
        // extractCondSuffix missed (length < 4 check at line 4016).
        // e.g., "bne", "beq", "bgt", "blt", "bge", "ble", "bhi", "bls",
        // "bhs", "blo", "bmi", "bpl", "bvs", "bvc", "bcc", "bcs"
        if (!is_aarch64_ && !handled && mn.length() == 3 && mn[0] == 'b') {
            std::string suffix = mn.substr(1);
            CondCode cc = mapCond(suffix);
            if (cc != CC_NONE) {
                handleBranch(insn, true, cc);
                handled = true;
            }
        }

        // ── ARM32-specific instructions ──
        if (!is_aarch64_ && !handled) {
            if (mn == "bx") {
                handleBxBlx(insn, false);
                handled = true;
            } else if (mn == "blx") {
                handleBxBlx(insn, true);
                handled = true;
            } else if (mn == "tbb" || mn == "tbh") {
                // v9.1: TBB/TBH — ARM Thumb switch table branch
                handleTbbTbh(insn, mn == "tbh");
                handled = true;
            } else if (mn == "push") {
                handlePush(insn);
                handled = true;
            } else if (mn == "pop") {
                handlePop(insn);
                handled = true;
            } else if (mn == "movw") {
                handleMovwMovt(insn, false);
                handled = true;
            } else if (mn == "movt") {
                handleMovwMovt(insn, true);
                handled = true;
            } else if (mn == "rsb") {
                handleRsb(insn);
                handled = true;
            } else if (mn == "mvn" || mn == "mvns") {
                handleMvn(insn);
                handled = true;
            } else if (mn == "adr") {
                handleAdr(insn);
                handled = true;
            } else if (mn.substr(0, 3) == "ldm") {
                handleLdmStm(insn, true);
                handled = true;
            } else if (mn.substr(0, 3) == "stm") {
                handleLdmStm(insn, false);
                handled = true;
            } else if (mn == "bic" || mn == "bics") {
                // bic: dst = src1 & ~src2
                handleLogical(insn, OP_AND);  // simplified
                handled = true;
            } else if (mn == "mla") {
                handleMadd(insn);  // similar to AArch64 madd
                handled = true;
            } else if (mn == "tst" || mn == "tsts") {
                handleCmpArm32(insn, false);
                handled = true;
            } else if (mn == "teq") {
                handleCmpArm32(insn, false);
                handled = true;
            } else if (mn == "cmp" || mn == "cmps") {
                handleCmpArm32(insn, false);
                handled = true;
            } else if (mn == "cmn" || mn == "cmns") {
                handleCmpArm32(insn, true);
                handled = true;
            }
        }

        // ── Common dispatch (both AArch64 and ARM32) ──
        // Dispatch to handler
        if (!handled) {
        if (mn == "mov" || mn == "movs" || mn == "movz" || mn == "fmov") {
            handleMov(insn);
        } else if (mn == "movn") {
            // movn: dst = ~src
            handleLogical(insn, OP_NOT);
        } else if (mn == "movk") {
            // v9.23: movk — fuse with previous movz to build 64-bit constant
            auto ops = splitOps(insn.op_str);
            if (ops.size() >= 2) {
                int dst = parseReg(ops[0]);
                int64_t imm = parseImm(ops[1]);
                int shift = 0;
                // Extract shift amount, e.g. "lsl #16"
                // The operand may be split by comma ("lsl", "#16") or
                // be a single token ("lsl #16"). Handle both cases.
                for (size_t i = 2; i < ops.size(); i++) {
                    if (ops[i] == "lsl" && i + 1 < ops.size()) {
                        shift = (int)parseImm(ops[i + 1]);
                        break;
                    }
                    if (ops[i].find("lsl") != std::string::npos) {
                        // "lsl #16" → split by space
                        size_t sp = ops[i].find(' ');
                        if (sp != std::string::npos) {
                            std::string immStr = ops[i].substr(sp + 1);
                            shift = (int)parseImm(immStr);
                        }
                        break;
                    }
                }

                auto it = movz_state_.find(dst);
                if (it != movz_state_.end() && it->second.valid) {
                    // Fuse with previous movz/movk
                    uint64_t fullVal = it->second.accumulated | ((uint64_t)imm << shift);
                    it->second.accumulated = fullVal;

                    // Update the previous movz instruction with the full value
                    if (it->second.last_insn) {
                        it->second.last_insn->l = Mop::imm64(fullVal, 8);
                        it->second.last_insn->src_asm += " | " + insn.mnemonic + " " + insn.op_str;
                    }
                    // Don't emit a new instruction — movk is consumed by fusion
                } else {
                    // No previous movz — emit as helper (fallback)
                    auto* micro = new MicroInsn(OP_HELPER, insn.addr);
                    micro->src_asm = mn + " " + insn.op_str;
                    micro->iprops = IPROP_VOLATILE;
                    emitInsn(micro);
                }
            }
        } else if (mn == "add" || mn == "adds") {
            handleAdd(insn);
        } else if (mn == "sub" || mn == "subs") {
            handleSub(insn);
        } else if (mn == "mul" || mn == "madd") {
            if (mn == "madd") handleMadd(insn);
            else handleMul(insn);
        } else if (mn == "msub") {
            handleMsub(insn);
        } else if (mn == "udiv") {
            handleDiv(insn, false);
        } else if (mn == "sdiv") {
            handleDiv(insn, true);
        } else if (mn == "and" || mn == "ands") {
            handleLogical(insn, OP_AND);
        } else if (mn == "orr") {
            handleLogical(insn, OP_OR);
        } else if (mn == "eor" || mn == "eors") {
            handleLogical(insn, OP_XOR);
        } else if (mn == "lsl") {
            handleShift(insn, OP_SHL);
        } else if (mn == "lsr") {
            handleShift(insn, OP_SHR);
        } else if (mn == "asr") {
            handleShift(insn, OP_SAR);
        } else if (mn == "cmp" || mn == "cmn") {
            handleCmp(insn);
        } else if (mn == "ldr") {
            handleLoad(insn, is_aarch64_ ? 8 : 4, false);
        } else if (mn == "ldur") {
            handleLoad(insn, is_aarch64_ ? 8 : 4, false);
        } else if (mn == "ldrb") {
            handleLoad(insn, 1, false);
        } else if (mn == "ldrh") {
            handleLoad(insn, 2, false);
        } else if (mn == "ldrsw") {
            handleLoad(insn, 4, true);
        } else if (mn == "ldrsb") {
            handleLoad(insn, 1, true);
        } else if (mn == "ldrsh") {
            handleLoad(insn, 2, true);
        } else if (mn == "str") {
            handleStore(insn, 8);
        } else if (mn == "stur") {
            handleStore(insn, 8);
        } else if (mn == "strb") {
            handleStore(insn, 1);
        } else if (mn == "strh") {
            handleStore(insn, 2);
        } else if (mn == "strd") {
            // v9.15: ARM32 strd (store register dual) — emit two OP_STORE
            // e.g., strd r2, r3, [r4, #0x20] → store r2 at [r4+0x20]; store r3 at [r4+0x24]
            handleStrd(insn);
        } else if (mn == "stp") {
            handleStp(insn);
        } else if (mn == "ldp") {
            handleLdp(insn);
        } else if (mn == "adrp") {
            handleAdrp(insn);
        } else if (mn == "adr") {
            // adr: PC-relative address (small range)
            handleAdrp(insn);  // similar handling
        } else if (mn == "b") {
            // v9.14: "b lr" is a return (ARM32 unconditional branch to link register).
            // Capstone may decode "bx lr" as "b lr" in some ARM modes.
            // v9.21: AArch64 uses "ret" (alias for "br x30"), not "b x30".
            // "b x30" is not valid AArch64 assembly, so only check ARM32
            // link register names (lr, r14).
            auto ops = splitOps(insn.op_str);
            if (!ops.empty() && (ops[0] == "lr" || ops[0] == "r14")) {
                handleRet(insn);
            } else {
                handleBranch(insn, false);
            }
        } else if (mn.substr(0, 2) == "b." && mn.length() > 2) {
            CondCode cc = mapCond(mn.substr(2));
            handleBranch(insn, true, cc);
        } else if (mn == "cbz") {
            handleCbz(insn, false);
        } else if (mn == "cbnz") {
            handleCbz(insn, true);
        } else if (mn == "tbz") {
            handleTbz(insn, false);
        } else if (mn == "tbnz") {
            handleTbz(insn, true);
        } else if (mn == "bl") {
            handleCall(insn, false);
        } else if (mn == "blr") {
            handleCall(insn, true);
        } else if (mn == "ret") {
            handleRet(insn);
        } else if (mn == "br") {
            // br x30 = return; br xN = indirect jump or tail call
            auto ops = splitOps(insn.op_str);
            if (!ops.empty() && (ops[0] == "x30" || ops[0] == "lr")) {
                handleRet(insn);
            } else if (!ops.empty() && i == normInsns.size() - 1) {
                // v9.25: br xN as the last instruction is a tail call.
                // The callee will return directly to our caller, so we
                // emit OP_ICALL (indirect call) + OP_RET to produce a
                // proper return statement instead of failing with "empty
                // function body".
                int reg = parseReg(ops[0]);
                int width = (ops[0][0] == 'w') ? 4 : (is_aarch64_ ? 8 : 4);
                auto* callMicro = new MicroInsn(OP_ICALL, insn.addr);
                callMicro->src_asm = mn + " " + insn.op_str;
                callMicro->iprops = IPROP_CALL | IPROP_VOLATILE;
                callMicro->l = Mop::reg(reg, width);
                callMicro->d = Mop::reg(100, width);
                auto call_info = std::make_shared<CallInfo>();
                call_info->is_indirect = true;
                callMicro->call_info = call_info;
                emitInsn(callMicro);
                // Emit return with x0 as the return value
                auto* retMicro = new MicroInsn(OP_RET, insn.addr);
                retMicro->src_asm = mn + " " + insn.op_str;
                retMicro->iprops = IPROP_VOLATILE;
                retMicro->l = Mop::reg(100, width);
                emitInsn(retMicro);
            } else if (is_aarch64_ && i > 0) {
                // v10.0: AArch64 jump table detection.
                // Pattern: ldr xN, [xM, xK, lsl #3]  ;  br xN
                // The ldr loads a 64-bit offset from the jump table, and
                // br jumps to (table_base + offset). Check if the previous
                // instruction is such a table load.
                const AsmInsn& prevInsn = normInsns[i - 1];
                bool isJumpTableLoad = false;
                if (prevInsn.mnemonic == "ldr") {
                    auto prevOps = splitOps(prevInsn.op_str);
                    // prevOps: [xN, xM, xK, "lsl", "#3"]  (after stripping brackets)
                    // We need: ldr xN, [xM, xK, lsl #3]
                    if (prevOps.size() >= 2 && !ops.empty()) {
                        int ldrDst = parseReg(prevOps[0]);
                        int brReg = parseReg(ops[0]);
                        if (ldrDst == brReg && ldrDst >= 0) {
                            isJumpTableLoad = true;
                        }
                    }
                }

                if (isJumpTableLoad) {
                    // v10.6: Extract the index register from the ldr instruction.
                    // ldr xN, [xM, xK, lsl #3] → xK is the switch variable
                    int switchIdxReg = -1;
                    if (prevInsn.mnemonic == "ldr" || prevInsn.mnemonic == "ldrsw") {
                        auto prevOps2 = splitOps(prevInsn.op_str);
                        // prevOps2: [xN, xM, xK, "lsl", "#3"] or [xN, xM, xK]
                        for (size_t oi = 2; oi < prevOps2.size(); oi++) {
                            if (prevOps2[oi] == "lsl" || prevOps2[oi] == "lsr" ||
                                prevOps2[oi] == "asr" || prevOps2[oi] == "ror") break;
                            if (!prevOps2[oi].empty() && prevOps2[oi][0] == '#') continue;
                            if (prevOps2[oi] == "xzr" || prevOps2[oi] == "wzr") continue;
                            int r = parseReg(prevOps2[oi]);
                            if (r >= 0) {
                                switchIdxReg = r;
                                break;
                            }
                        }
                    }
                    handleAarch64JumpTable(insn, switchIdxReg);
                } else {
                    // Indirect jump — emit as goto
                    auto* micro = new MicroInsn(OP_GOTO, insn.addr);
                    micro->src_asm = mn + " " + insn.op_str;
                    micro->iprops = IPROP_BRANCHED | IPROP_VOLATILE;
                    emitInsn(micro);
                }
            } else {
                // Indirect jump — emit as goto
                auto* micro = new MicroInsn(OP_GOTO, insn.addr);
                micro->src_asm = mn + " " + insn.op_str;
                micro->iprops = IPROP_BRANCHED | IPROP_VOLATILE;
                emitInsn(micro);
            }
        } else if (mn.substr(0,4) == "csel") {
            handleCsel(insn);
        } else if (mn == "nop") {
            // Skip nops
        } else if (mn == "svc") {
            // System call — emit as helper
            auto* micro = new MicroInsn(OP_HELPER, insn.addr);
            micro->src_asm = mn + " " + insn.op_str;
            micro->iprops = IPROP_VOLATILE;
            emitInsn(micro);
        } else if (mn == "neg") {
            handleLogical(insn, OP_NEG);
        } else if (mn == "mvn") {
            handleLogical(insn, OP_NOT);
        } else if (mn == "tst") {
            // tst is like cmp but with AND
            handleCmp(insn);  // simplified
        } else if (mn == "lsl" || mn == "lslv") {
            handleShift(insn, OP_SHL);
        } else if (mn == "lsr" || mn == "lsrv") {
            handleShift(insn, OP_SHR);
        } else if (mn == "asr" || mn == "asrv") {
            handleShift(insn, OP_SAR);
        } else if (mn == "ror") {
            auto* micro = new MicroInsn(OP_HELPER, insn.addr);
            micro->src_asm = mn + " " + insn.op_str;
            micro->iprops = IPROP_VOLATILE;
            emitInsn(micro);
        } else if (mn == "uxtB" || mn == "uxtb") {
            // Zero-extend byte
            auto ops2 = splitOps(insn.op_str);
            if (ops2.size() >= 2) {
                int dst = parseReg(ops2[0]);
                int width = (ops2[0][0] == 'w') ? 4 : 8;
                auto* micro = new MicroInsn(OP_XDU, insn.addr);
                micro->def_mreg = dst;
                micro->d = Mop::reg(dst, width);
                micro->l = Mop::reg(parseReg(ops2[1]), 1);
                emitInsn(micro);
            }
        } else if (mn == "uxth" || mn == "uxtH") {
            auto ops2 = splitOps(insn.op_str);
            if (ops2.size() >= 2) {
                int dst = parseReg(ops2[0]);
                int width = (ops2[0][0] == 'w') ? 4 : 8;
                auto* micro = new MicroInsn(OP_XDU, insn.addr);
                micro->def_mreg = dst;
                micro->d = Mop::reg(dst, width);
                micro->l = Mop::reg(parseReg(ops2[1]), 2);
                emitInsn(micro);
            }
        } else if (mn == "sxtb") {
            auto ops2 = splitOps(insn.op_str);
            if (ops2.size() >= 2) {
                int dst = parseReg(ops2[0]);
                int width = (ops2[0][0] == 'w') ? 4 : 8;
                auto* micro = new MicroInsn(OP_XDS, insn.addr);
                micro->def_mreg = dst;
                micro->d = Mop::reg(dst, width);
                micro->l = Mop::reg(parseReg(ops2[1]), 1);
                emitInsn(micro);
            }
        } else if (mn == "sxth") {
            auto ops2 = splitOps(insn.op_str);
            if (ops2.size() >= 2) {
                int dst = parseReg(ops2[0]);
                int width = (ops2[0][0] == 'w') ? 4 : 8;
                auto* micro = new MicroInsn(OP_XDS, insn.addr);
                micro->def_mreg = dst;
                micro->d = Mop::reg(dst, width);
                micro->l = Mop::reg(parseReg(ops2[1]), 2);
                emitInsn(micro);
            }
        } else if (mn == "sxtw") {
            auto ops2 = splitOps(insn.op_str);
            if (ops2.size() >= 2) {
                int dst = parseReg(ops2[0]);
                // v9.24: Match other extend handlers — use destination register
                // width (w=4, x=8) instead of hardcoding 8.
                int width = (ops2[0][0] == 'w') ? 4 : 8;
                auto* micro = new MicroInsn(OP_XDS, insn.addr);
                micro->def_mreg = dst;
                micro->d = Mop::reg(dst, width);
                micro->l = Mop::reg(parseReg(ops2[1]), 4);
                // v9.24: Clear reg_state_ so constant-propagation doesn't
                // use stale state for the sign-extended register.
                reg_state_.erase(dst);
                emitInsn(micro);
            }
        }
        // ── v3.6: Atomic operation built-ins ──
        // Map ARM atomic instructions to __sync_* / __atomic_* built-in calls
        // instead of emitting them as comments
        else if (mn == "clrex") {
            // clrex: clear exclusive monitor → __builtin_clrex()
            // v4.0: MUST emit a micro-op, otherwise the block at this
            // jump target is empty → next jump target merges into it
            auto* micro = new MicroInsn(OP_CALL, insn.addr);
            micro->target_addr = 0;
            micro->call_info = std::make_shared<CallInfo>();
            micro->call_info->is_indirect = false;
            micro->call_info->target_name = "__builtin_clrex";
            micro->call_info->arg_count = 0;
            micro->call_info->has_return = false;
            micro->iprops = IPROP_VOLATILE;
            emitInsn(micro);
        } else if (mn == "dmb" || mn == "dsb" || mn == "isb") {
            // Memory barriers → __sync_synchronize()  [no args, no return]
            auto* micro = new MicroInsn(OP_CALL, insn.addr);
            micro->target_addr = 0;
            micro->call_info = std::make_shared<CallInfo>();
            micro->call_info->is_indirect = false;
            micro->call_info->target_name = "__sync_synchronize";
            micro->call_info->arg_count = 0;      // v3.15: barrier takes no args
            micro->call_info->has_return = false;  // v3.15: barrier returns void
            micro->iprops = IPROP_VOLATILE;
            emitInsn(micro);
        } else if (mn == "ldxr" || mn == "ldaxr") {
            // ldxr x0, [x1] → exclusive load (v3.16: mark as ATOMIC)
            auto ops = splitOps(insn.op_str);
            if (ops.size() >= 2) {
                int dst = parseReg(ops[0]);
                std::string addr_str = ops[1];
                if (!addr_str.empty() && addr_str[0] == '[') {
                    addr_str = addr_str.substr(1);
                    if (!addr_str.empty() && addr_str.back() == ']')
                        addr_str = addr_str.substr(0, addr_str.length() - 1);
                }
                int base = parseReg(addr_str);
                auto* micro = new MicroInsn(OP_LOAD, insn.addr);
                micro->src_asm = mn + " " + insn.op_str;
                micro->def_mreg = dst;
                micro->d = Mop::reg(dst, is_aarch64_ ? 8 : 4);
                micro->l = Mop::mem(base, 0, is_aarch64_ ? 8 : 4);
                micro->iprops = IPROP_ATOMIC;  // v3.16: mark as atomic
                emitInsn(micro);
            }
        } else if (mn == "stxr" || mn == "stlxr") {
            // stxr w1, x0, [x2] → exclusive store (v3.16: mark as ATOMIC)
            // Format: stxr wStatus, wSrc, [xAddr]
            auto ops = splitOps(insn.op_str);
            if (ops.size() >= 3) {
                int statusReg = parseReg(ops[0]);  // w1 = status
                int src = parseReg(ops[1]);
                std::string addr_str = ops[2];
                if (!addr_str.empty() && addr_str[0] == '[') {
                    addr_str = addr_str.substr(1);
                    if (!addr_str.empty() && addr_str.back() == ']')
                        addr_str = addr_str.substr(0, addr_str.length() - 1);
                }
                int base = parseReg(addr_str);
                auto* micro = new MicroInsn(OP_STORE, insn.addr);
                micro->src_asm = mn + " " + insn.op_str;
                micro->l = Mop::reg(src, is_aarch64_ ? 8 : 4);
                micro->d = Mop::mem(base, 0, is_aarch64_ ? 8 : 4);
                micro->iprops = IPROP_ATOMIC | IPROP_STORE;  // v3.16: mark as atomic
                emitInsn(micro);
                // v3.16: Emit status=0 (success) like ARM32 strex
                auto* statusMicro = new MicroInsn(OP_LDC, insn.addr);
                statusMicro->def_mreg = statusReg;
                statusMicro->d = Mop::reg(statusReg, 4);
                statusMicro->r = Mop::imm64(0, 4);
                statusMicro->iprops = IPROP_ATOMIC;  // mark as part of atomic sequence
                emitInsn(statusMicro);
            }
        } else if (mn == "cas" || mn == "casa" || mn == "casl" || mn == "casal") {
            // CAS: compare-and-swap → __sync_val_compare_and_swap
            auto ops = splitOps(insn.op_str);
            if (ops.size() >= 3) {
                int dst = parseReg(ops[0]);
                auto* micro = new MicroInsn(OP_CALL, insn.addr);
                micro->def_mreg = dst;
                micro->d = Mop::reg(dst, 8);
                micro->target_addr = 0;
                micro->call_info = std::make_shared<CallInfo>();
                micro->call_info->is_indirect = false;
                micro->call_info->target_name = "__sync_val_compare_and_swap";
                emitInsn(micro);
            }
        } else if (mn == "brk") {
            // brk #1 → __builtin_trap()
            auto* micro = new MicroInsn(OP_CALL, insn.addr);
            micro->target_addr = 0;
            micro->call_info = std::make_shared<CallInfo>();
            micro->call_info->is_indirect = false;
            micro->call_info->target_name = "__builtin_trap";
            micro->call_info->arg_count = 0;
            micro->call_info->has_return = false;
            micro->iprops = IPROP_VOLATILE;
            emitInsn(micro);
        }
        // ARM32 atomic instructions
        // v3.13: emit 阶段只标记，合并逻辑移到 foldAtomicCas() 后处理 pass
        // 参考 Ghidra: LDREX Rd,[Rn] → Rd = *Rn (普通 LOAD, 无原子标记)
        //              STREX Rd,Rm,[Rn] → 独占检查 + 存储 + Rd=status
        // 我们用 IPROP_ATOMIC 标记 ldrex/strex，让 foldAtomicCas 识别
        else if (!is_aarch64_ && (mn == "ldrex" || mn == "ldrexb" || mn == "ldrexh" || mn == "ldrexd")) {
            auto ops = splitOps(insn.op_str);
            if (ops.size() >= 2) {
                int dst = parseReg(ops[0]);
                std::string addr_str = ops[1];
                if (!addr_str.empty() && addr_str[0] == '[') {
                    addr_str = addr_str.substr(1);
                    if (!addr_str.empty() && addr_str.back() == ']')
                        addr_str = addr_str.substr(0, addr_str.length() - 1);
                }
                int base = parseReg(addr_str);
                int width = (mn == "ldrexb") ? 1 : (mn == "ldrexh") ? 2 : (mn == "ldrexd") ? 8 : 4;

                auto* micro = new MicroInsn(OP_LOAD, insn.addr);
                micro->src_asm = mn + " " + insn.op_str;
                micro->def_mreg = dst;
                micro->d = Mop::reg(dst, width);
                micro->l = Mop::mem(base, 0, width);
                micro->iprops = IPROP_ATOMIC;  // v3.13: 标记为原子操作
                emitInsn(micro);
                // 记录 ldrex 信息供 foldAtomicCas 使用
                last_ldrex_insn_ = micro;
            }
        } else if (!is_aarch64_ && (mn == "strex" || mn == "strexb" || mn == "strexh" || mn == "strexd")) {
            auto ops = splitOps(insn.op_str);
            if (ops.size() >= 3) {
                int statusReg = parseReg(ops[0]);
                int src = parseReg(ops[1]);
                std::string addr_str = ops[2];
                if (!addr_str.empty() && addr_str[0] == '[') {
                    addr_str = addr_str.substr(1);
                    if (!addr_str.empty() && addr_str.back() == ']')
                        addr_str = addr_str.substr(0, addr_str.length() - 1);
                }
                int base = parseReg(addr_str);
                int width = (mn == "strexb") ? 1 : (mn == "strexh") ? 2 : (mn == "strexd") ? 8 : 4;

                auto* micro = new MicroInsn(OP_STORE, insn.addr);
                micro->src_asm = mn + " " + insn.op_str;
                micro->l = Mop::reg(src, width);
                micro->d = Mop::mem(base, 0, width);
                micro->iprops = IPROP_ATOMIC | IPROP_STORE;
                emitInsn(micro);
                // strex status reg = 0 (success)
                auto* statusMicro = new MicroInsn(OP_LDC, insn.addr);
                statusMicro->src_asm = mn + " " + insn.op_str;
                statusMicro->def_mreg = statusReg;
                statusMicro->d = Mop::reg(statusReg, 4);
                statusMicro->l = Mop::imm64(0, 4);
                emitInsn(statusMicro);
            }
        } else if (!is_aarch64_ && mn == "clrex") {
            // clrex → __builtin_clrex() (must emit to avoid empty block)
            auto* micro = new MicroInsn(OP_CALL, insn.addr);
            micro->src_asm = mn + " " + insn.op_str;
            micro->target_addr = 0;
            micro->call_info = std::make_shared<CallInfo>();
            micro->call_info->is_indirect = false;
            micro->call_info->target_name = "__builtin_clrex";
            micro->call_info->arg_count = 0;
            micro->call_info->has_return = false;
            micro->iprops = IPROP_VOLATILE | IPROP_CALL;
            emitInsn(micro);
        } else if (mn == "dmb" || mn == "dsb" || mn == "isb") {
            // Memory barrier → __sync_synchronize()
            auto* micro = new MicroInsn(OP_CALL, insn.addr);
            micro->src_asm = mn + " " + insn.op_str;
            micro->target_addr = 0;
            micro->call_info = std::make_shared<CallInfo>();
            micro->call_info->is_indirect = false;
            micro->call_info->target_name = "__sync_synchronize";
            micro->call_info->arg_count = 0;
            micro->call_info->has_return = false;
            micro->iprops = IPROP_VOLATILE | IPROP_CALL;
            emitInsn(micro);
        } else if (mn == "wfe" || mn == "wfi" || mn == "sev") {
            // v3.13: wfe/wfi/sev → __builtin_wfe() 等, 不生成死循环
            auto* micro = new MicroInsn(OP_CALL, insn.addr);
            micro->src_asm = mn + " " + insn.op_str;
            micro->target_addr = 0;
            micro->call_info = std::make_shared<CallInfo>();
            micro->call_info->is_indirect = false;
            micro->call_info->target_name = (mn == "wfe") ? "__builtin_wfe" :
                                            (mn == "wfi") ? "__builtin_wfi" : "__builtin_sev";
            micro->call_info->arg_count = 0;
            micro->call_info->has_return = false;
            micro->iprops = IPROP_VOLATILE | IPROP_CALL;
            emitInsn(micro);
        }
        // ── v5.3: ARM64 additional instructions ──
        else if (is_aarch64_ && mn == "cset") {
            // cset dst, cond → dst = (cond) ? 1 : 0
            // This is equivalent to a SET opcode
            auto ops = splitOps(insn.op_str);
            if (ops.size() >= 2) {
                int dst = parseReg(ops[0]);
                int width = (ops[0][0] == 'w') ? 4 : (is_aarch64_ ? 8 : 4);
                // Parse condition from second operand
                std::string cond = ops[1];
                MicroOp setOp = OP_SETZ;
                if (cond == "eq") setOp = OP_SETZ;
                else if (cond == "ne") setOp = OP_SETNZ;
                else if (cond == "cs" || cond == "hs") setOp = OP_SETB;
                else if (cond == "cc" || cond == "lo") setOp = OP_SETAE;
                else if (cond == "hi") setOp = OP_SETA;
                else if (cond == "ls") setOp = OP_SETBE;
                else if (cond == "ge") setOp = OP_SETGE;
                else if (cond == "lt") setOp = OP_SETL;
                else if (cond == "gt") setOp = OP_SETG;
                else if (cond == "le") setOp = OP_SETLE;
                auto* micro = new MicroInsn(setOp, insn.addr);
                micro->src_asm = mn + " " + insn.op_str;
                micro->def_mreg = dst;
                micro->d = Mop::reg(dst, width);
                emitInsn(micro);
            }
        } else if (is_aarch64_ && mn == "csetm") {
            // csetm dst, cond → dst = (cond) ? -1 : 0
            auto ops = splitOps(insn.op_str);
            if (ops.size() >= 2) {
                int dst = parseReg(ops[0]);
                int width = (ops[0][0] == 'w') ? 4 : (is_aarch64_ ? 8 : 4);
                auto* micro = new MicroInsn(OP_SETZ, insn.addr);
                micro->src_asm = mn + " " + insn.op_str;
                micro->def_mreg = dst;
                micro->d = Mop::reg(dst, width);
                emitInsn(micro);
            }
        } else if (is_aarch64_ && (mn == "clz" || mn == "cls")) {
            // clz: count leading zeros, cls: count leading sign bits
            auto ops = splitOps(insn.op_str);
            if (ops.size() >= 2) {
                int dst = parseReg(ops[0]);
                int width = (ops[0][0] == 'w') ? 4 : (is_aarch64_ ? 8 : 4);
                auto* micro = new MicroInsn(OP_HELPER, insn.addr);
                micro->src_asm = mn + " " + insn.op_str;
                micro->def_mreg = dst;
                micro->d = Mop::reg(dst, width);
                micro->l = Mop::reg(parseReg(ops[1]), width);
                micro->call_info = std::make_shared<CallInfo>();
                micro->call_info->is_indirect = false;
                micro->call_info->target_name = (mn == "clz") ? "__builtin_clz" : "__builtin_cls";
                micro->call_info->arg_count = 1;
                micro->call_info->has_return = true;
                micro->iprops = IPROP_CALL;
                emitInsn(micro);
            }
        } else if (is_aarch64_ && (mn == "rev" || mn == "rev16" || mn == "rev32" || mn == "rbit")) {
            // rev: byte reverse, rbit: bit reverse
            auto ops = splitOps(insn.op_str);
            if (ops.size() >= 2) {
                int dst = parseReg(ops[0]);
                int width = (ops[0][0] == 'w') ? 4 : (is_aarch64_ ? 8 : 4);
                std::string funcName;
                if (mn == "rev") funcName = (width == 8) ? "__builtin_bswap64" : "__builtin_bswap32";
                else if (mn == "rev16") funcName = "__builtin_bswap16";
                else if (mn == "rev32") funcName = "__builtin_bswap32";
                else funcName = "__builtin_rbit";
                auto* micro = new MicroInsn(OP_HELPER, insn.addr);
                micro->src_asm = mn + " " + insn.op_str;
                micro->def_mreg = dst;
                micro->d = Mop::reg(dst, width);
                micro->l = Mop::reg(parseReg(ops[1]), width);
                micro->call_info = std::make_shared<CallInfo>();
                micro->call_info->is_indirect = false;
                micro->call_info->target_name = funcName;
                micro->call_info->arg_count = 1;
                micro->call_info->has_return = true;
                micro->iprops = IPROP_CALL;
                emitInsn(micro);
            }
        } else if (is_aarch64_ && (mn == "smull" || mn == "umull")) {
            // smull/umull xDst, wSrc1, wSrc2 → 64-bit = 32-bit * 32-bit
            auto ops = splitOps(insn.op_str);
            if (ops.size() >= 3) {
                int dst = parseReg(ops[0]);
                int src1 = parseReg(ops[1]);
                int src2 = parseReg(ops[2]);
                auto* micro = new MicroInsn(OP_MUL, insn.addr);
                micro->src_asm = mn + " " + insn.op_str;
                micro->def_mreg = dst;
                micro->d = Mop::reg(dst, 8);
                micro->l = Mop::reg(src1, 4);
                micro->r = Mop::reg(src2, 4);
                emitInsn(micro);
            }
        } else if (is_aarch64_ && mn == "mneg") {
            // mneg xDst, xSrc1, xSrc2 → xDst = -(xSrc1 * xSrc2)
            auto ops = splitOps(insn.op_str);
            if (ops.size() >= 3) {
                int dst = parseReg(ops[0]);
                int src1 = parseReg(ops[1]);
                int src2 = parseReg(ops[2]);
                int width = (ops[0][0] == 'w') ? 4 : (is_aarch64_ ? 8 : 4);
                // mul then neg
                auto* mul = new MicroInsn(OP_MUL, insn.addr);
                mul->src_asm = mn + " " + insn.op_str;
                mul->def_mreg = dst;
                mul->d = Mop::reg(dst, width);
                mul->l = Mop::reg(src1, width);
                mul->r = Mop::reg(src2, width);
                emitInsn(mul);
                auto* neg = new MicroInsn(OP_NEG, insn.addr);
                neg->src_asm = mn + " " + insn.op_str;
                neg->def_mreg = dst;
                neg->d = Mop::reg(dst, width);
                neg->l = Mop::reg(dst, width);
                emitInsn(neg);
            }
        } else if (is_aarch64_ && (mn == "bfm" || mn == "bfi" || mn == "bfxil" ||
                                    mn == "sbfm" || mn == "sbfx" || mn == "ubfm" || mn == "ubfx")) {
            // Bit field move: emit as helper but capture all operands so
            // CTree builder can at least generate a meaningful assignment.
            auto ops = splitOps(insn.op_str);
            if (ops.size() >= 4) {
                int dst = parseReg(ops[0]);
                int width = (ops[0][0] == 'w') ? 4 : (is_aarch64_ ? 8 : 4);
                auto* micro = new MicroInsn(OP_HELPER, insn.addr);
                micro->src_asm = mn + " " + insn.op_str;
                micro->def_mreg = dst;
                micro->d = Mop::reg(dst, width);
                micro->l = Mop::reg(parseReg(ops[1]), width);
                // v9.24: Capture bitfield immediate parameters so the
                // instruction isn't completely lost in CTree output.
                if (ops.size() >= 3) {
                    if (isImm(ops[2]))
                        micro->r = Mop::imm64(parseImm(ops[2]), width);
                    else
                        micro->r = Mop::reg(parseReg(ops[2]), width);
                }
                micro->iprops = IPROP_VOLATILE;
                emitInsn(micro);
            }
        } else if (is_aarch64_ && mn == "extr") {
            // extr dst, src1, src2, #lsb → extract bits
            auto ops = splitOps(insn.op_str);
            if (ops.size() >= 4) {
                int dst = parseReg(ops[0]);
                int width = (ops[0][0] == 'w') ? 4 : (is_aarch64_ ? 8 : 4);
                auto* micro = new MicroInsn(OP_HELPER, insn.addr);
                micro->src_asm = mn + " " + insn.op_str;
                micro->def_mreg = dst;
                micro->d = Mop::reg(dst, width);
                micro->l = Mop::reg(parseReg(ops[1]), width);
                // v9.24: Capture src2 (second source register) so the
                // instruction is properly represented.
                micro->r = Mop::reg(parseReg(ops[2]), width);
                micro->iprops = IPROP_VOLATILE;
                emitInsn(micro);
            }
        }
        // ── v5.3: ARM64 floating-point instructions ──
        else if (is_aarch64_ && (mn == "fadd" || mn == "fsub" || mn == "fmul" || mn == "fdiv")) {
            auto ops = splitOps(insn.op_str);
            if (ops.size() >= 3) {
                int dst = parseReg(ops[0]);
                int src1 = parseReg(ops[1]);
                int src2 = parseReg(ops[2]);
                // Determine width from register name: d=8 bytes (double), s=4 bytes (float)
                int width = (ops[0][0] == 'd') ? 8 : 4;
                MicroOp op = OP_FADD;
                if (mn == "fsub") op = OP_FSUB;
                else if (mn == "fmul") op = OP_FMUL;
                else if (mn == "fdiv") op = OP_FDIV;
                auto* micro = new MicroInsn(op, insn.addr);
                micro->src_asm = mn + " " + insn.op_str;
                micro->def_mreg = dst;
                micro->d = Mop::reg(dst, width);
                micro->l = Mop::reg(src1, width);
                micro->r = Mop::reg(src2, width);
                emitInsn(micro);
            }
        } else if (is_aarch64_ && (mn == "fneg" || mn == "fabs" || mn == "fsqrt")) {
            auto ops = splitOps(insn.op_str);
            if (ops.size() >= 2) {
                int dst = parseReg(ops[0]);
                int src = parseReg(ops[1]);
                int width = (ops[0][0] == 'd') ? 8 : 4;
                // Emit as helper call
                std::string funcName;
                if (mn == "fneg") funcName = (width == 8) ? "__builtin_fabs" : "__builtin_fabsf";
                else if (mn == "fabs") funcName = (width == 8) ? "__builtin_fabs" : "__builtin_fabsf";
                else funcName = (width == 8) ? "sqrt" : "sqrtf";
                auto* micro = new MicroInsn(OP_HELPER, insn.addr);
                micro->src_asm = mn + " " + insn.op_str;
                micro->def_mreg = dst;
                micro->d = Mop::reg(dst, width);
                micro->l = Mop::reg(src, width);
                micro->call_info = std::make_shared<CallInfo>();
                micro->call_info->is_indirect = false;
                micro->call_info->target_name = funcName;
                micro->call_info->arg_count = 1;
                micro->call_info->has_return = true;
                micro->iprops = IPROP_CALL;
                emitInsn(micro);
            }
        } else if (is_aarch64_ && mn == "fmov") {
            // fmov can be: fmov dst, src (register to register)
            //              fmov dst, #imm (immediate)
            handleMov(insn);
        } else if (is_aarch64_ && (mn == "fcmp" || mn == "fcmpe")) {
            // fcmp: floating-point compare, sets flags
            auto ops = splitOps(insn.op_str);
            if (ops.size() >= 2) {
                int src1 = parseReg(ops[0]);
                int src2 = parseReg(ops[1]);
                int width = (ops[0][0] == 'd') ? 8 : 4;
                auto* micro = new MicroInsn(OP_SETZ, insn.addr);
                micro->src_asm = mn + " " + insn.op_str;
                micro->l = Mop::reg(src1, width);
                micro->r = Mop::reg(src2, width);
                emitInsn(micro);
            }
        } else if (is_aarch64_ && (mn == "scvtf" || mn == "ucvtf")) {
            // scvtf: signed integer to float, ucvtf: unsigned integer to float
            auto ops = splitOps(insn.op_str);
            if (ops.size() >= 2) {
                int dst = parseReg(ops[0]);
                int src = parseReg(ops[1]);
                int dstWidth = (ops[0][0] == 'd') ? 8 : 4;
                int srcWidth = (ops[1][0] == 'w') ? 4 : 8;
                auto* micro = new MicroInsn(OP_I2F, insn.addr);
                micro->src_asm = mn + " " + insn.op_str;
                micro->def_mreg = dst;
                micro->d = Mop::reg(dst, dstWidth);
                micro->l = Mop::reg(src, srcWidth);
                emitInsn(micro);
            }
        } else if (is_aarch64_ && (mn == "fcvtzs" || mn == "fcvtzu")) {
            // fcvtzs: float to signed integer, fcvtzu: float to unsigned integer
            auto ops = splitOps(insn.op_str);
            if (ops.size() >= 2) {
                int dst = parseReg(ops[0]);
                int src = parseReg(ops[1]);
                int dstWidth = (ops[0][0] == 'w') ? 4 : (is_aarch64_ ? 8 : 4);
                int srcWidth = (ops[1][0] == 'd') ? 8 : 4;
                auto* micro = new MicroInsn(OP_F2I, insn.addr);
                micro->src_asm = mn + " " + insn.op_str;
                micro->def_mreg = dst;
                micro->d = Mop::reg(dst, dstWidth);
                micro->l = Mop::reg(src, srcWidth);
                emitInsn(micro);
            }
        } else if (is_aarch64_ && mn == "fcvt") {
            // fcvt: convert between float and double
            // fcvt sDst, dSrc or fcvt dDst, sSrc
            auto ops = splitOps(insn.op_str);
            if (ops.size() >= 2) {
                int dst = parseReg(ops[0]);
                int src = parseReg(ops[1]);
                int dstWidth = (ops[0][0] == 'd') ? 8 : 4;
                int srcWidth = (ops[1][0] == 'd') ? 8 : 4;
                auto* micro = new MicroInsn(OP_F2F, insn.addr);
                micro->src_asm = mn + " " + insn.op_str;
                micro->def_mreg = dst;
                micro->d = Mop::reg(dst, dstWidth);
                micro->l = Mop::reg(src, srcWidth);
                emitInsn(micro);
            }
        } else if (is_aarch64_ && (mn == "fmin" || mn == "fmax" || mn == "fminnm" || mn == "fmaxnm")) {
            // Floating-point min/max
            auto ops = splitOps(insn.op_str);
            if (ops.size() >= 3) {
                int dst = parseReg(ops[0]);
                int width = (ops[0][0] == 'd') ? 8 : 4;
                std::string funcName;
                if (mn == "fmin" || mn == "fminnm") funcName = (width == 8) ? "fmin" : "fminf";
                else funcName = (width == 8) ? "fmax" : "fmaxf";
                auto* micro = new MicroInsn(OP_CALL, insn.addr);
                micro->src_asm = mn + " " + insn.op_str;
                micro->def_mreg = dst;
                micro->d = Mop::reg(dst, width);
                micro->target_addr = 0;
                micro->call_info = std::make_shared<CallInfo>();
                micro->call_info->is_indirect = false;
                micro->call_info->target_name = funcName;
                micro->call_info->arg_count = 2;
                micro->call_info->has_return = true;
                micro->iprops = IPROP_CALL;
                emitInsn(micro);
            }
        }
        // ── v5.3: ARM32 additional instructions ──
        else if (!is_aarch64_ && (mn == "smull" || mn == "umull")) {
            // smull/umull rDstLo, rDstHi, rSrc1, rSrc2
            // 32-bit × 32-bit → 64-bit (two 32-bit registers)
            auto ops = splitOps(insn.op_str);
            if (ops.size() >= 4) {
                int dstLo = parseReg(ops[0]);
                int dstHi = parseReg(ops[1]);
                int src1 = parseReg(ops[2]);
                int src2 = parseReg(ops[3]);
                // Emit mul to dstLo
                auto* mul = new MicroInsn(OP_MUL, insn.addr);
                mul->src_asm = mn + " " + insn.op_str;
                mul->def_mreg = dstLo;
                mul->d = Mop::reg(dstLo, 4);
                mul->l = Mop::reg(src1, 4);
                mul->r = Mop::reg(src2, 4);
                emitInsn(mul);
                // Emit high half as helper (upper 32 bits)
                auto* hi = new MicroInsn(OP_HIGH, insn.addr);
                hi->src_asm = mn + " " + insn.op_str;
                hi->def_mreg = dstHi;
                hi->d = Mop::reg(dstHi, 4);
                hi->l = Mop::reg(src1, 4);
                hi->r = Mop::reg(src2, 4);
                emitInsn(hi);
            }
        } else if (!is_aarch64_ && (mn == "smlal" || mn == "umlal")) {
             // smlal/umlal rDstLo, rDstHi, rSrc1, rSrc2: accumulate multiply
             auto ops = splitOps(insn.op_str);
             if (ops.size() >= 4) {
                 int dstLo = parseReg(ops[0]);
                 int src1 = parseReg(ops[2]);
                 int src2 = parseReg(ops[3]);
                // mul then add to existing value
                auto* mul = new MicroInsn(OP_MUL, insn.addr);
                mul->src_asm = mn + " " + insn.op_str;
                mul->def_mreg = dstLo;
                mul->d = Mop::reg(dstLo, 4);
                mul->l = Mop::reg(src1, 4);
                mul->r = Mop::reg(src2, 4);
                emitInsn(mul);
            }
        } else if (!is_aarch64_ && mn == "mls") {
            // mls rDst, rSrc1, rSrc2, rAcc: rDst = rAcc - (rSrc1 * rSrc2)
            auto ops = splitOps(insn.op_str);
            if (ops.size() >= 4) {
                int dst = parseReg(ops[0]);
                int src1 = parseReg(ops[1]);
                int src2 = parseReg(ops[2]);
                int acc = parseReg(ops[3]);
                // mul tmp, src1, src2
                auto* mul = new MicroInsn(OP_MUL, insn.addr);
                mul->src_asm = mn + " " + insn.op_str;
                mul->def_mreg = dst;
                mul->d = Mop::reg(dst, 4);
                mul->l = Mop::reg(src1, 4);
                mul->r = Mop::reg(src2, 4);
                emitInsn(mul);
                // sub dst, acc, dst
                auto* sub = new MicroInsn(OP_SUB, insn.addr);
                sub->src_asm = mn + " " + insn.op_str;
                sub->def_mreg = dst;
                sub->d = Mop::reg(dst, 4);
                sub->l = Mop::reg(acc, 4);
                sub->r = Mop::reg(dst, 4);
                emitInsn(sub);
            }
        } else if (!is_aarch64_ && mn == "clz") {
            auto ops = splitOps(insn.op_str);
            if (ops.size() >= 2) {
                int dst = parseReg(ops[0]);
                auto* micro = new MicroInsn(OP_HELPER, insn.addr);
                micro->src_asm = mn + " " + insn.op_str;
                micro->def_mreg = dst;
                micro->d = Mop::reg(dst, 4);
                micro->l = Mop::reg(parseReg(ops[1]), 4);
                micro->call_info = std::make_shared<CallInfo>();
                micro->call_info->is_indirect = false;
                micro->call_info->target_name = "__builtin_clz";
                micro->call_info->arg_count = 1;
                micro->call_info->has_return = true;
                micro->iprops = IPROP_CALL;
                emitInsn(micro);
            }
        } else if (!is_aarch64_ && (mn == "rev" || mn == "rev16" || mn == "revsh")) {
            auto ops = splitOps(insn.op_str);
            if (ops.size() >= 2) {
                int dst = parseReg(ops[0]);
                std::string funcName;
                if (mn == "rev") funcName = "__builtin_bswap32";
                else if (mn == "rev16") funcName = "__builtin_bswap16";
                else funcName = "__builtin_bswap16";
                auto* micro = new MicroInsn(OP_HELPER, insn.addr);
                micro->src_asm = mn + " " + insn.op_str;
                micro->def_mreg = dst;
                micro->d = Mop::reg(dst, 4);
                micro->l = Mop::reg(parseReg(ops[1]), 4);
                micro->call_info = std::make_shared<CallInfo>();
                micro->call_info->is_indirect = false;
                micro->call_info->target_name = funcName;
                micro->call_info->arg_count = 1;
                micro->call_info->has_return = true;
                micro->iprops = IPROP_CALL;
                emitInsn(micro);
            }
        } else if (!is_aarch64_ && (mn == "ssat" || mn == "usat")) {
            // Saturating arithmetic — emit as helper
            auto ops = splitOps(insn.op_str);
            if (ops.size() >= 3) {
                int dst = parseReg(ops[0]);
                auto* micro = new MicroInsn(OP_HELPER, insn.addr);
                micro->src_asm = mn + " " + insn.op_str;
                micro->def_mreg = dst;
                micro->d = Mop::reg(dst, 4);
                micro->iprops = IPROP_VOLATILE;
                emitInsn(micro);
            }
        } else if (!is_aarch64_ && (mn == "qadd" || mn == "qsub" || mn == "qdadd" || mn == "qdsub")) {
            // Saturating add/sub
            auto ops = splitOps(insn.op_str);
            if (ops.size() >= 3) {
                int dst = parseReg(ops[0]);
                auto* micro = new MicroInsn(OP_ADD, insn.addr);
                micro->src_asm = mn + " " + insn.op_str;
                micro->def_mreg = dst;
                micro->d = Mop::reg(dst, 4);
                micro->l = Mop::reg(parseReg(ops[1]), 4);
                micro->r = Mop::reg(parseReg(ops[2]), 4);
                emitInsn(micro);
            }
        } else if (!is_aarch64_ && (mn == "mrs" || mn == "msr")) {
            // System register access — emit as helper
            auto* micro = new MicroInsn(OP_HELPER, insn.addr);
            micro->src_asm = mn + " " + insn.op_str;
            micro->iprops = IPROP_VOLATILE;
            emitInsn(micro);
        }
        // v10.0: ARM32 jump table detection — ldr pc, [pc, rN, lsl #2]
        // This is the ARM32 switch table pattern. Detect before the generic
        // ldr handler so we can populate case_values from the ELF data.
        else if (!is_aarch64_ && mn == "ldr") {
            auto ops = splitOps(insn.op_str);
            bool isPcLoad = false;
            if (!ops.empty()) {
                // Destination is pc or r15
                if (ops[0] == "pc" || ops[0] == "r15") {
                    isPcLoad = true;
                }
            }
            if (isPcLoad) {
                handleArm32JumpTable(insn);
            } else {
                handleLoad(insn, 4, false);
            }
        }
        // ── v5.3: ARM32 VFP (Vector Floating Point) ──
        else if (!is_aarch64_ && (mn == "vadd" || mn == "vsub" || mn == "vmul" || mn == "vdiv" ||
                                   mn == "vnmul" || mn == "vnmla" || mn == "vnmls")) {
            // VFP arithmetic: vadd.f32 s0, s1, s2 etc.
            auto ops = splitOps(insn.op_str);
            // Skip the .f32/.f64 suffix operand if present
            std::vector<std::string> regs;
            for (auto& op : ops) {
                if (op.substr(0, 2) == ".f" || op.substr(0, 2) == ".d") continue;
                regs.push_back(op);
            }
            if (regs.size() >= 3) {
                int dst = parseReg(regs[0]);
                int src1 = parseReg(regs[1]);
                int src2 = parseReg(regs[2]);
                MicroOp op = OP_FADD;
                if (mn == "vsub") op = OP_FSUB;
                else if (mn == "vmul") op = OP_FMUL;
                else if (mn == "vdiv") op = OP_FDIV;
                auto* micro = new MicroInsn(op, insn.addr);
                micro->src_asm = mn + " " + insn.op_str;
                micro->def_mreg = dst;
                micro->d = Mop::reg(dst, 4);
                micro->l = Mop::reg(src1, 4);
                micro->r = Mop::reg(src2, 4);
                emitInsn(micro);
            }
        } else if (!is_aarch64_ && (mn == "vmov" || mn == "vmovs" || mn == "vmovd")) {
            // vmov: move between VFP register and ARM register, or between VFP registers
            handleMov(insn);
        } else if (!is_aarch64_ && (mn == "vcmp" || mn == "vcmpe")) {
            // VFP compare
            auto ops = splitOps(insn.op_str);
            std::vector<std::string> regs;
            for (auto& op : ops) {
                if (op.substr(0, 2) == ".f" || op.substr(0, 2) == ".d") continue;
                regs.push_back(op);
            }
            if (regs.size() >= 2) {
                auto* micro = new MicroInsn(OP_SETZ, insn.addr);
                micro->src_asm = mn + " " + insn.op_str;
                micro->l = Mop::reg(parseReg(regs[0]), 4);
                micro->r = Mop::reg(parseReg(regs[1]), 4);
                emitInsn(micro);
            }
        } else if (!is_aarch64_ && (mn == "vldr" || mn == "vldrb")) {
            // VFP load — use OP_FLOAD so type inference assigns FLOAT type
            // 对标 Ghidra FLOAT_LOAD TypeOp: floating-point loads produce
            // floating-point variables, not integers.
            handleFLoad(insn, 4);
        } else if (!is_aarch64_ && (mn == "vstr" || mn == "vstrb")) {
            // VFP store — use OP_FSTORE so type inference assigns FLOAT type
            // 对标 Ghidra FLOAT_STORE TypeOp: floating-point stores consume
            // floating-point values.
            handleFStore(insn, 4);
        } else if (!is_aarch64_ && (mn == "vneg" || mn == "vabs" || mn == "vsqrt")) {
            // VFP unary operations
            auto ops = splitOps(insn.op_str);
            std::vector<std::string> regs;
            for (auto& op : ops) {
                if (op.substr(0, 2) == ".f" || op.substr(0, 2) == ".d") continue;
                regs.push_back(op);
            }
            if (regs.size() >= 2) {
                int dst = parseReg(regs[0]);
                int src = parseReg(regs[1]);
                std::string funcName;
                if (mn == "vneg") funcName = "fnegf";
                else if (mn == "vabs") funcName = "fabsf";
                else funcName = "sqrtf";
                auto* micro = new MicroInsn(OP_HELPER, insn.addr);
                micro->src_asm = mn + " " + insn.op_str;
                micro->def_mreg = dst;
                micro->d = Mop::reg(dst, 4);
                micro->l = Mop::reg(src, 4);
                micro->call_info = std::make_shared<CallInfo>();
                micro->call_info->is_indirect = false;
                micro->call_info->target_name = funcName;
                micro->call_info->arg_count = 1;
                micro->call_info->has_return = true;
                micro->iprops = IPROP_CALL;
                emitInsn(micro);
            }
        } else if (!is_aarch64_ && (mn == "vcvt" || mn == "v cvt")) {
            // VFP convert between int/float
            auto ops = splitOps(insn.op_str);
            std::vector<std::string> regs;
            for (auto& op : ops) {
                if (op.substr(0, 2) == ".f" || op.substr(0, 2) == ".d" ||
                    op.substr(0, 2) == ".u" || op.substr(0, 2) == ".s") continue;
                regs.push_back(op);
            }
            if (regs.size() >= 2) {
                int dst = parseReg(regs[0]);
                int src = parseReg(regs[1]);
                auto* micro = new MicroInsn(OP_I2F, insn.addr);
                micro->src_asm = mn + " " + insn.op_str;
                micro->def_mreg = dst;
                micro->d = Mop::reg(dst, 4);
                micro->l = Mop::reg(src, 4);
                emitInsn(micro);
            }
        } else if (mn == "nop" || mn == "nop.w" || mn == "nopv" || mn == "yield") {
            // Skip nops and hints
        } else if (mn == "msr" || mn == "mrs") {
            // ARM64 system register access — emit as helper
            auto* micro = new MicroInsn(OP_HELPER, insn.addr);
            micro->src_asm = mn + " " + insn.op_str;
            micro->iprops = IPROP_VOLATILE;
            emitInsn(micro);
        } else if (mn == "hlt" || mn == "hvc" || mn == "smc") {
            // Hypervisor/secure monitor calls
            auto* micro = new MicroInsn(OP_CALL, insn.addr);
            micro->target_addr = 0;
            micro->call_info = std::make_shared<CallInfo>();
            micro->call_info->is_indirect = false;
            micro->call_info->target_name = "__builtin_trap";
            micro->call_info->arg_count = 0;
            micro->call_info->has_return = false;
            micro->iprops = IPROP_VOLATILE;
            emitInsn(micro);
        } else {
            // Unknown instruction — emit as helper/comment
            auto* micro = new MicroInsn(OP_HELPER, insn.addr);
            micro->src_asm = mn + " " + insn.op_str;
            micro->iprops = IPROP_VOLATILE;
            emitInsn(micro);
        }
        } // end if (!handled)

        // After a branch or return, start a new block
        // AArch64: b, b.eq, ret, cbz, cbnz, tbz, tbnz, br, brk, svc, hvc
        // ARM32: b, beq/bne/..., bx lr, blx, pop {...,pc}
        if (mn == "b" || mn == "ret" || mn.substr(0, 2) == "b." ||
            mn == "cbz" || mn == "cbnz" || mn == "tbz" || mn == "tbnz" ||
            mn == "brk" || mn == "svc" || mn == "hvc" || mn == "hlt" ||
            (mn == "br" && i < normInsns.size() - 1) ||
            // ARM32 branch terminators
            (!is_aarch64_ && (mn == "bx" || mn == "blx" || mn == "pop" ||
                              mn.substr(0, 3) == "ldm")) ||
            (!is_aarch64_ && mn.length() > 1 && mn[0] == 'b' &&
             (mn[1] == 'e' || mn[1] == 'n' || mn[1] == 'c' || mn[1] == 'h' ||
              mn[1] == 'l' || mn[1] == 'm' || mn[1] == 'p' || mn[1] == 'v' ||
              mn[1] == 'g' || mn[1] == 'l'))) {
            uint64_t next_addr __attribute__((unused)) = insn.addr + insn.bytes_size;
            if (i + 1 < normInsns.size()) {
                startBlock(normInsns[i + 1].addr);
            }
        }
    }

    // v4.3: CRITICAL FIX — foldAtomicCas and eliminateSpecialRegisters MUST run BEFORE
    // edge resolution. When they remove CBRANCH instructions (atomic retry branches),
    // the block's tail changes. If edges are already resolved, stale successors remain
    // and the CFG structurer creates false if-then-else branches.
    //
    // v3.13: Post-emit peephole — fold ldrex+sub/add+strex into __sync_fetch_and_*
    foldAtomicCas();

    // v3.14: Ghidra-inspired special register elimination.
    // Mark all sp/pc/lr/fp bookkeeping as IPROP_DEAD at IR level.
    eliminateSpecialRegisters();

    // v10.0: Inline function detection — scan for memset/memcpy/strlen patterns
    // and replace with synthetic OP_CALL instructions.
    detectInlineFunctions();

    // ── Pass 2.5: Split blocks at branch targets ──
    // v8.4: When a branch (CBRANCH/GOTO) targets an address inside a block
    // (not at the block start), split the target block so the CFG structurer
    // sees distinct successors for the TRUE and FALSE branches.
    // Example: cbz r0, #0x4bec12 where block starts at 0x4bec10
    // → split block at 0x4bec12 into two blocks
    {
        std::vector<std::pair<int, uint64_t>> splits; // (block_id, split_addr)
        for (int bi = 0; bi < mba_.numBlocks(); bi++) {
            auto* blk = mba_.getBlock(bi);
            if (!blk || !blk->tail) continue;
            uint64_t target = 0;
            if (blk->tail->opcode == OP_CBRANCH || blk->tail->opcode == OP_GOTO) {
                target = blk->tail->target_addr;
            }
            if (target == 0) continue;
            // Check if target is inside another block (not at block start)
            if (block_at_.count(target)) continue; // exact match, no split needed
            // Find the containing block
             bool found = false;
             for (int bj = 0; bj < mba_.numBlocks(); bj++) {
                 auto* tgtBlk = mba_.getBlock(bj);
                 if (!tgtBlk || tgtBlk->start_addr >= target) continue;
                 // Check if target is inside this block by scanning instructions
                 for (auto* insn = tgtBlk->head; insn; insn = insn->next) {
                     if (insn->ea == target) {
                         splits.push_back({bj, target});
                         found = true;
                         break;
                     }
                 }
                 if (found) break;
             }
        }
        // Apply splits (in reverse order to avoid invalidating block indices)
        for (auto& sp : splits) {
            int blkId = sp.first;
            uint64_t splitAddr = sp.second;
            auto* blk = mba_.getBlock(blkId);
            if (!blk) continue;

            // Find the split point: instruction before the split
            MicroInsn* splitBefore = nullptr;
            for (auto* insn = blk->head; insn; insn = insn->next) {
                if (insn->ea == splitAddr) break;
                splitBefore = insn;
            }
            if (!splitBefore || !splitBefore->next) continue;

            // Create new block at split address
            int newId = mba_.newBlock(splitAddr);
            auto* newBlk = mba_.getBlock(newId);
            block_at_[splitAddr] = newId;

            // Move instructions from split point to new block
            newBlk->head = splitBefore->next;
            newBlk->tail = blk->tail;
            newBlk->head->prev = nullptr;

            // Update old block
            blk->tail = splitBefore;
            splitBefore->next = nullptr;
        }
    }

    // ── Pass 3: Resolve block edges ──
    // v4.3: also clean up successors for blocks whose tail was changed by foldAtomicCas
    // v8.4: Helper to find the block containing a given address.
    // Branches may target an address inside a block (not just the block start).
    auto findBlockContaining = [&](uint64_t addr) -> int {
        // Exact match first
        auto it = block_at_.find(addr);
        if (it != block_at_.end()) return it->second;
        // Search for the block with the largest start_addr <= addr
        int best = -1;
        uint64_t bestStart = 0;
        for (int bi = 0; bi < mba_.numBlocks(); bi++) {
            auto* blk2 = mba_.getBlock(bi);
            if (!blk2) continue;
            if (blk2->start_addr <= addr && blk2->start_addr > bestStart) {
                best = bi;
                bestStart = blk2->start_addr;
            }
        }
        return best;
    };

    mba_.clearAllEdges();
    for (int b = 0; b < mba_.numBlocks(); b++) {
        auto* blk = mba_.getBlock(b);
        if (!blk || !blk->tail) continue;
        bool hasCbranch = false;
        bool hasGoto = false;

        // v9.18: Scan all instructions for branch targets, not just the tail.
        // ARM32 conditional instructions emit CBRANCH in the middle of blocks.
        for (auto* insn = blk->head; insn; insn = insn->next) {
            if (insn->opcode == OP_GOTO) {
                hasGoto = true;
                int target = findBlockContaining(insn->target_addr);
                if (target >= 0) {
                    insn->target_block = target;
                    mba_.addEdge(b, target);
                }
            } else if (insn->opcode == OP_CBRANCH) {
                hasCbranch = true;
                int target = findBlockContaining(insn->target_addr);
                if (target >= 0) {
                    insn->target_block = target;
                    mba_.addEdge(b, target);
                }
            }
        }

        if (b == 19) {
            DBG_PRINT("[DBG_EDGE] b19: tail_op=%d hasCbranch=%d hasGoto=%d succs=[", 
                    (int)blk->tail->opcode, hasCbranch, hasGoto);
            for (int s : blk->successors) fprintf(stderr, " b%d", s);
            fprintf(stderr, " ]\n");
            DBG_PRINT("[DBG_EDGE] b19 instructions:\n");
            for (auto* insn = blk->head; insn; insn = insn->next) {
                fprintf(stderr, "  [%d] ea=0x%lx op=%d src=%s\n", 
                        insn->opcode, (unsigned long)insn->ea, insn->opcode, insn->src_asm.c_str());
            }
        }

        // Handle tail instruction for fall-through
        if (blk->tail->opcode == OP_GOTO) {
            // Already handled above, no fall-through
        } else if (blk->tail->opcode == OP_CBRANCH) {
            // False branch: fall-through to next block
            if (b + 1 < mba_.numBlocks()) {
                mba_.addEdge(b, b + 1);
            }
        } else if (blk->tail->opcode == OP_RET) {
            // No successors
        } else if (blk->tail->opcode == OP_CALL && blk->tail->call_info &&
                   !blk->tail->call_info->has_return) {
            // v9.8: noreturn call (abort, __builtin_trap, __cxa_throw, etc.)
            // No fall-through — code after this is unreachable (literal pool, padding)
        } else if (blk->tail->opcode == OP_JTBL) {
            // v10.6: Jump table (switch) — create edges to all case target blocks.
            // Without this, the CFG is broken: the switch block has no successors
            // and case blocks are unreachable, causing the structurer to generate
            // duplicated code blocks (Bug4: 代码块重复5次以上).
            //
            // For each case_targets entry, find the corresponding block and add
            // an edge. Also add a fall-through edge as the "default" case path
            // (the block after the jump table region is typically the merge point
            // or the default case target).
            auto* jtbl = blk->tail;
            for (uint64_t targetAddr : jtbl->case_targets) {
                int target = findBlockContaining(targetAddr);
                if (target >= 0 && target != b) {
                    mba_.addEdge(b, target);
                }
            }
            // If no case_targets were resolved, add fall-through as fallback
            if (jtbl->case_targets.empty()) {
                if (b + 1 < mba_.numBlocks()) {
                    mba_.addEdge(b, b + 1);
                }
            }
        } else {
            // Fall-through to next block
            if (b + 1 < mba_.numBlocks()) {
                mba_.addEdge(b, b + 1);
            }
        }
    }

    mba_.maturity = MMAT_GENERATED;
    return std::move(mba_);
}

// ════════════════════════════════════════════════════════════════════
// ARM32-specific instruction handlers
// ════════════════════════════════════════════════════════════════════

// ── ARM32 condition suffix table ──
static const char* ARM_CONDS[] = {
    "eq", "ne", "cs", "cc", "mi", "pl", "vs", "vc",
    "hi", "ls", "ge", "lt", "gt", "le", "al", "nv"
};

std::string MicrocodeEmitter::extractCondSuffix(const std::string& mnem) {
    // ARM32 mnemonics can have condition suffixes:
    // addeq, movne, bne, bxeq, ldmeq, etc.
    // Also "s" flag for setting flags: adds, movs
    if (mnem.length() < 4) return "";

    // Check 2-character condition suffixes
    for (const char* cond : ARM_CONDS) {
        std::string condStr(cond);
        if (mnem.length() > condStr.length()) {
            size_t pos = mnem.length() - condStr.length();
            if (mnem.substr(pos) == condStr) {
                // Make sure it's not a base instruction (e.g. "b" + "eq")
                // by checking the prefix is a known instruction
                return condStr;
            }
        }
    }
    return "";
}

std::string MicrocodeEmitter::stripCondSuffix(const std::string& mnem) {
    for (const char* cond : ARM_CONDS) {
        std::string condStr(cond);
        if (mnem.length() > condStr.length() &&
            mnem.substr(mnem.length() - condStr.length()) == condStr) {
            return mnem.substr(0, mnem.length() - condStr.length());
        }
    }
    return mnem;
}

// ── movw/movt: PC-relative address loading (ARM32) ──
// movw rN, #imm16    → rN = imm16 (zero-extended)
// movt rN, #imm16    → rN = (rN & 0xFFFF) | (imm16 << 16)
// Together: movw+movt loads a 32-bit address
void MicrocodeEmitter::handleMovwMovt(const AsmInsn& insn, bool is_movt) {
    auto ops = splitOps(insn.op_str);
    if (ops.size() < 2) return;

    int dst = parseReg(ops[0]);
    int64_t imm = parseImm(ops[1]);

    if (!is_movt) {
        // movw: rN = imm16 (and record state for movt fusion)
        movw_state_[dst] = {true, (uint16_t)imm, dst};

        auto* micro = new MicroInsn(OP_LDC, insn.addr);
        micro->def_mreg = dst;
        micro->d = Mop::reg(dst, 4);
        micro->l = Mop::imm64(imm);
        emitInsn(micro);
    } else {
        // movt: rN = (rN & 0xFFFF) | (imm16 << 16)
        // Check if we can fuse with previous movw
        auto it = movw_state_.find(dst);
        if (it != movw_state_.end() && it->second.valid) {
            // Fuse: rN = (movt_imm << 16) | movw_imm
            uint32_t fullAddr = ((uint32_t)imm << 16) | it->second.imm16;
            it->second.valid = false;  // consume

            // Check if this is a known symbol address
            auto nameIt = mba_.global_names.find(fullAddr);
            if (nameIt != mba_.global_names.end()) {
                auto* micro = new MicroInsn(OP_LDC, insn.addr);
                micro->def_mreg = dst;
                micro->d = Mop::reg(dst, 4);
                micro->l = Mop::global(fullAddr);
                emitInsn(micro);
            } else {
                // v10.1: Check if it's a string reference (ASCII + UTF-16)
                std::string strContent6;
                bool isUtf16_6 = false;
                size_t strLen6 = 0;
                if (resolveStringRef(fullAddr, strContent6, isUtf16_6, strLen6)) {
                    auto* micro = new MicroInsn(OP_LDC, insn.addr);
                    micro->def_mreg = dst;
                    micro->d = Mop::reg(dst, 4);
                    micro->l = Mop::str(fullAddr, strContent6);
                    emitInsn(micro);
                    // v10.1: Mark RESOLVED_SYM so handleCall can pass the
                    // string (incl. UTF-16 best-effort) as a literal argument.
                    reg_state_[dst].kind = RegState::RESOLVED_SYM;
                    reg_state_[dst].sym_name = strContent6;
                    reg_state_[dst].value = fullAddr;
                } else {
                    // Just load the full address as constant
                    auto* micro = new MicroInsn(OP_LDC, insn.addr);
                    micro->def_mreg = dst;
                    micro->d = Mop::reg(dst, 4);
                    micro->l = Mop::imm64(fullAddr);
                    emitInsn(micro);
                }
            }
        } else {
            // No matching movw, just do the or
            auto* micro = new MicroInsn(OP_OR, insn.addr);
            micro->def_mreg = dst;
            micro->d = Mop::reg(dst, 4);
            micro->l = Mop::reg(dst, 4);
            micro->r = Mop::imm64(imm << 16);
            emitInsn(micro);
        }
    }
}

// ── push {r4, r5, lr} → str lr, [sp, #-12]!; str r5, [sp, #4]; str r4, [sp, #8] ──
void MicrocodeEmitter::handlePush(const AsmInsn& insn) {
    // Parse register list: {r4, r5, lr}
    std::string ops = insn.op_str;
    // Remove braces
    ops.erase(std::remove(ops.begin(), ops.end(), '{'), ops.end());
    ops.erase(std::remove(ops.begin(), ops.end(), '}'), ops.end());
    ops.erase(std::remove(ops.begin(), ops.end(), ' '), ops.end());

    auto regs = splitOps(ops);
    int spMreg = mba_.phys_to_mreg.count("sp") ? mba_.phys_to_mreg["sp"] : 113;

    // v3.10: 处理寄存器范围 "r4-r11"
    std::vector<int> regListExpanded;
    for (auto& r : regs) {
        size_t dashPos = r.find('-');
        if (dashPos != std::string::npos && dashPos > 0 && dashPos < r.length() - 1) {
            std::string first = r.substr(0, dashPos);
            std::string last = r.substr(dashPos + 1);
            if (isReg(first) && isReg(last)) {
                int firstReg = parseReg(first);
                int lastReg = parseReg(last);
                if (firstReg >= 100 && lastReg >= firstReg && lastReg < firstReg + 32) {
                    for (int r = firstReg; r <= lastReg; r++)
                        regListExpanded.push_back(r);
                    continue;
                }
            }
        }
        regListExpanded.push_back(parseReg(r));
    }

    // push = STMDB: 先减后存。起始偏移 = -4*count
    int count = (int)regListExpanded.size();
    int startOffset = -(count * 4);

    // Each register is pushed (stored to stack)
    for (int i = 0; i < count; i++) {
        int r = regListExpanded[i];
        int slotOffset = startOffset + i * 4;
        auto* micro = new MicroInsn(OP_STORE, insn.addr);
        micro->l = Mop::reg(r, 4);
        // v3.10: 使用 MEM 操作数而非 REG
        micro->d = Mop::mem(spMreg, slotOffset, 4);
        micro->src_asm = insn.mnemonic + " " + insn.op_str;
        micro->iprops = IPROP_STORE;
        emitInsn(micro);
    }
    // Update SP
    auto* spMicro = new MicroInsn(OP_ADD, insn.addr);
    spMicro->def_mreg = spMreg;
    spMicro->d = Mop::reg(spMreg, 4);
    spMicro->l = Mop::reg(spMreg, 4);
    spMicro->r = Mop::imm64(startOffset);
    spMicro->src_asm = insn.mnemonic + " " + insn.op_str;
    emitInsn(spMicro);
}

// ── pop {r4, r5, pc} → ldr r4, [sp, #0]; ldr r5, [sp, #4]; ldr pc, [sp, #8]; add sp, sp, #12 ──
void MicrocodeEmitter::handlePop(const AsmInsn& insn) {
    std::string ops = insn.op_str;
    ops.erase(std::remove(ops.begin(), ops.end(), '{'), ops.end());
    ops.erase(std::remove(ops.begin(), ops.end(), '}'), ops.end());
    ops.erase(std::remove(ops.begin(), ops.end(), ' '), ops.end());

    auto regs = splitOps(ops);
    int spMreg = mba_.phys_to_mreg.count("sp") ? mba_.phys_to_mreg["sp"] : 113;
    int pcMreg = mba_.phys_to_mreg.count("pc") ? mba_.phys_to_mreg["pc"] : 115;

    // v3.10: 处理寄存器范围 "r4-r11"
    std::vector<int> regListExpanded;
    for (auto& r : regs) {
        size_t dashPos = r.find('-');
        if (dashPos != std::string::npos && dashPos > 0 && dashPos < r.length() - 1) {
            std::string first = r.substr(0, dashPos);
            std::string last = r.substr(dashPos + 1);
            if (isReg(first) && isReg(last)) {
                int firstReg = parseReg(first);
                int lastReg = parseReg(last);
                if (firstReg >= 100 && lastReg >= firstReg && lastReg < firstReg + 32) {
                    for (int r = firstReg; r <= lastReg; r++)
                        regListExpanded.push_back(r);
                    continue;
                }
            }
        }
        regListExpanded.push_back(parseReg(r));
    }

    // pop = LDMIA: 先存后增。起始偏移 = 0
    for (size_t i = 0; i < regListExpanded.size(); i++) {
        int r = regListExpanded[i];
        int slotOffset = (int)(i * 4);
        if (r == pcMreg) {
            // pop {pc} = return
            auto* micro = new MicroInsn(OP_RET, insn.addr);
            micro->l = Mop::reg(100, 4);  // return r0, not lr
            micro->src_asm = insn.mnemonic + " " + insn.op_str;
            micro->iprops = IPROP_RET;  // v9.2: was missing — DCE needs this
            emitInsn(micro);
        } else {
            auto* micro = new MicroInsn(OP_LOAD, insn.addr);
            micro->def_mreg = r;
            micro->d = Mop::reg(r, 4);
            // v3.10: 使用 MEM 操作数而非 REG
            micro->l = Mop::mem(spMreg, slotOffset, 4, false);
            micro->src_asm = insn.mnemonic + " " + insn.op_str;
            emitInsn(micro);

            // v9.17: Track lr restoration for tail call detection.
            // When pop {..., lr} is followed by an unconditional branch,
            // the branch is a tail call.
            if (r == 114) {  // ARM32 lr = r14 = mreg 114
                lr_restored_ = true;
            }
        }
    }
    // Update SP
    int totalSize = (int)(regListExpanded.size() * 4);
    auto* spMicro = new MicroInsn(OP_ADD, insn.addr);
    spMicro->def_mreg = spMreg;
    spMicro->d = Mop::reg(spMreg, 4);
    spMicro->l = Mop::reg(spMreg, 4);
    spMicro->r = Mop::imm64(totalSize);
    spMicro->src_asm = insn.mnemonic + " " + insn.op_str;
    emitInsn(spMicro);
}

// ── ldm/stm: block load/store ──
// ARM32 多寄存器加载/存储指令。
// 关键变体：
//   STMDB SP!, {r4-r11, lr}  = push {r4-r11, lr}  (先减后存, writeback)
//   LDMIA SP!, {r4-r11, pc}  = pop  {r4-r11, pc}  (先存后增, writeback)
// STMDB 的地址计算: base -= 4*count, 然后从新 base 开始向上存
// LDMIA 的地址计算: 从 base 开始向上加载, 然后 base += 4*count
void MicrocodeEmitter::handleLdmStm(const AsmInsn& insn, bool is_load) {
    auto ops = splitOps(insn.op_str);
    if (ops.empty()) return;

    int pcMreg = mba_.phys_to_mreg.count("pc") ? mba_.phys_to_mreg["pc"] : 115;
    int spMreg = mba_.phys_to_mreg.count("sp") ? mba_.phys_to_mreg["sp"] : 113;

    // v3.10: 健壮地解析 base 寄存器（处理 "sp!" 形式）
    // ops[0] 可能是 "sp", "sp!", "r4!", "[sp]" 等
    int baseReg = -1;
    std::string baseName = ops[0];
    // 去除方括号
    if (!baseName.empty() && baseName.front() == '[') baseName = baseName.substr(1);
    if (!baseName.empty() && baseName.back() == ']') baseName = baseName.substr(0, baseName.length() - 1);
    // 去除感叹号（pre-indexed writeback 标记）
    bool has_writeback = false;
    if (!baseName.empty() && baseName.back() == '!') {
        has_writeback = true;
        baseName = baseName.substr(0, baseName.length() - 1);
    }
    baseReg = parseReg(baseName);
    if (baseReg < 0) return;  // 无法解析 base 寄存器

    // 判断指令变体: STMDB (decrement before) vs STMIA (increment after)
    // ARM stack addressing modes:
    //   FD (Full Descending): STMFD=STMDB (push), LDMFD=LDMIA (pop)
    //   FA (Full Ascending):  STMFA=STMIB, LDMFA=LDMDA
    //   ED (Empty Descending): STMED=STMDA, LDMED=LDMIB
    //   EA (Empty Ascending):  STMEA=STMIA, LDMEA=LDMDB
    // v9.19: Fix fd/fa mapping — for LDM, fd=ia (not db), fa=da (not ia).
    std::string mn = insn.mnemonic;
    bool is_db = false, is_ia = false;
    if (is_load) {
        // LDM variants: fd=ia, ea=db, fa=da, ed=ib
        is_ia = (mn.find("ia") != std::string::npos || mn.find("fd") != std::string::npos ||
                 mn.find("pop") != std::string::npos);
        is_db = (mn.find("db") != std::string::npos || mn.find("ea") != std::string::npos);
    } else {
        // STM variants: fd=db, ea=ia, fa=ib, ed=da
        is_db = (mn.find("db") != std::string::npos || mn.find("fd") != std::string::npos ||
                 mn.find("push") != std::string::npos);
        is_ia = (mn.find("ia") != std::string::npos || mn.find("ea") != std::string::npos);
    }
    // 默认: stm = stmia, ldm = ldmia
    if (!is_db && !is_ia) {
        is_ia = true;  // 默认按 IA 处理
    }
    // 检查 writeback（操作数中有 '!'）
    if (insn.op_str.find('!') != std::string::npos) has_writeback = true;

    // 解析寄存器列表
    std::string regList;
    for (size_t i = 1; i < ops.size(); i++) regList += ops[i];
    regList.erase(std::remove(regList.begin(), regList.end(), '{'), regList.end());
    regList.erase(std::remove(regList.begin(), regList.end(), '}'), regList.end());
    regList.erase(std::remove(regList.begin(), regList.end(), ' '), regList.end());

    auto regs = splitOps(regList);

    // v3.10: 处理寄存器范围 "r4-r11" → 展开为 r4,r5,...,r11
    std::vector<int> regListExpanded;
    for (auto& r : regs) {
        // 检查是否是范围 "r4-r11"
        size_t dashPos = r.find('-');
        if (dashPos != std::string::npos && dashPos > 0 && dashPos < r.length() - 1) {
            std::string first = r.substr(0, dashPos);
            std::string last = r.substr(dashPos + 1);
            // 确保都是寄存器名
            if (isReg(first) && isReg(last)) {
                int firstReg = parseReg(first);
                int lastReg = parseReg(last);
                // 只处理数字编号连续的寄存器 (r4-r11)
                if (firstReg >= 100 && lastReg >= firstReg && lastReg < firstReg + 32) {
                    for (int r = firstReg; r <= lastReg; r++) {
                        regListExpanded.push_back(r);
                    }
                    continue;
                }
            }
        }
        regListExpanded.push_back(parseReg(r));
    }

    int count = (int)regListExpanded.size();
    if (count == 0) return;

    // STMDB: 先减后存。最终起始地址 = base - 4*count
    // LDMIA: 先存后增。起始地址 = base
    int startOffset = is_db ? -(count * 4) : 0;

    // 生成逐寄存器的 load/store，使用正确的 MEM 操作数
    for (int i = 0; i < count; i++) {
        int r = regListExpanded[i];
        int slotOffset = startOffset + i * 4;

        if (is_load) {
            // v3.8: ldm with pc in list = return (like pop {pc})
            if (r == pcMreg) {
                auto* micro = new MicroInsn(OP_RET, insn.addr);
                micro->l = Mop::reg(100, 4);  // return r0
                micro->src_asm = insn.mnemonic + " " + insn.op_str;
                micro->iprops = IPROP_RET;  // v9.2: was missing — DCE needs this
                emitInsn(micro);
            } else {
                auto* micro = new MicroInsn(OP_LOAD, insn.addr);
                micro->def_mreg = r;
                micro->d = Mop::reg(r, 4);
                // 使用 MEM 操作数: [base + slotOffset]
                micro->l = Mop::mem(baseReg, slotOffset, 4, false);
                micro->src_asm = insn.mnemonic + " " + insn.op_str;
                emitInsn(micro);
            }
        } else {
            // v3.10: 跳过 LR/PC/FP 的压栈（伪 C 不需要手动保存寄存器）
            // shouldEmitStmt 会过滤这些，但生成它们也没意义
            int lrMreg = mba_.phys_to_mreg.count("lr") ? mba_.phys_to_mreg["lr"] : 114;
            int fpMreg = mba_.phys_to_mreg.count("fp") ? mba_.phys_to_mreg["fp"] :
                         (is_aarch64_ ? 129 : 111);
            if (r == lrMreg || r == pcMreg || r == fpMreg) {
                // 仍然生成 store，但 shouldEmitStmt 会过滤
                auto* micro = new MicroInsn(OP_STORE, insn.addr);
                micro->l = Mop::reg(r, 4);
                micro->d = Mop::mem(baseReg, slotOffset, 4);
                micro->src_asm = insn.mnemonic + " " + insn.op_str;
                micro->iprops = IPROP_STORE;
                emitInsn(micro);
            } else {
                auto* micro = new MicroInsn(OP_STORE, insn.addr);
                micro->l = Mop::reg(r, 4);
                // 使用 MEM 操作数: [base + slotOffset]
                micro->d = Mop::mem(baseReg, slotOffset, 4);
                micro->src_asm = insn.mnemonic + " " + insn.op_str;
                micro->iprops = IPROP_STORE;
                emitInsn(micro);
            }
        }
    }

    // v3.10: 处理 writeback（更新 base 寄存器）
    // STMDB SP!, {...}: sp = sp - 4*count (但伪 C 中 sp 不应出现)
    // LDMIA SP!, {...}: sp = sp + 4*count
    // 只在 base 不是 sp 时生成 writeback（sp 的调整由 shouldEmitStmt 过滤）
    if (has_writeback && baseReg != spMreg) {
        int delta = is_db ? -(count * 4) : (count * 4);
        auto* wb = new MicroInsn(OP_ADD, insn.addr);
        wb->def_mreg = baseReg;
        wb->d = Mop::reg(baseReg, 4);
        wb->l = Mop::reg(baseReg, 4);
        wb->r = Mop::imm64(delta);
        wb->src_asm = insn.mnemonic + " " + insn.op_str;
        emitInsn(wb);
    }
    // SP writeback: 生成但让 shouldEmitStmt 过滤
    if (has_writeback && baseReg == spMreg) {
        int delta = is_db ? -(count * 4) : (count * 4);
        auto* wb = new MicroInsn(OP_ADD, insn.addr);
        wb->def_mreg = spMreg;
        wb->d = Mop::reg(spMreg, 4);
        wb->l = Mop::reg(spMreg, 4);
        wb->r = Mop::imm64(delta);
        wb->src_asm = insn.mnemonic + " " + insn.op_str;
        emitInsn(wb);
    }
}

// ── bx/blx: branch exchange ──
// bx lr → return
// bx rN → indirect branch
// blx rN → indirect call
// blx #addr → direct call
void MicrocodeEmitter::handleBxBlx(const AsmInsn& insn, bool is_blx) {
    auto ops = splitOps(insn.op_str);
    if (ops.empty()) return;

    // Check if it's a register or immediate
    if (isReg(ops[0])) {
        int r = parseReg(ops[0]);
        int lrMreg = is_aarch64_ ? 130 : 114;  // x30 or r14

        if (r == lrMreg && !is_blx) {
            // bx lr = return
            auto* micro = new MicroInsn(OP_RET, insn.addr);
            micro->l = Mop::reg(100, 4);  // return r0, not lr
            micro->iprops = IPROP_RET;
            emitInsn(micro);
        } else if (is_blx) {
            // blx rN = indirect call
            // v9.0: Use OP_ICALL (consistent with AArch64 blr) for proper
            // indirect call handling in CTreeBuilder and CFG structuring
            auto* micro = new MicroInsn(OP_ICALL, insn.addr);
            micro->iprops = IPROP_CALL | IPROP_VOLATILE;  // v9.2: was missing — DCE needs this
            micro->l = Mop::reg(r, 4);
            micro->d = Mop::reg(100, 4);  // v8.5: return value in r0
            micro->def_mreg = 100;
            micro->call_info = std::make_shared<CallInfo>();
            micro->call_info->is_indirect = true;
            micro->call_info->target_name = "";
            // v8.2: Look up RESOLVED_SYM state (from vtable dispatch or GOT)
            auto rsIt = reg_state_.find(r);
            if (rsIt != reg_state_.end() &&
                rsIt->second.kind == RegState::RESOLVED_SYM) {
                micro->call_info->target_name = rsIt->second.sym_name;
            }
            // v8.5: Populate string_args from reg_state_ (same as handleCall)
            int numArgRegs = is_aarch64_ ? 8 : 4;
            for (int ai = 0; ai < numArgRegs; ai++) {
                int argMreg = 100 + ai;
                if (argMreg == r) continue;  // skip call target register
                auto it = reg_state_.find(argMreg);
                if (it != reg_state_.end() && it->second.kind == RegState::RESOLVED_SYM) {
                    micro->call_info->string_args[ai] = it->second.sym_name;
                }
            }
            emitInsn(micro);
            // v8.5: Clear arg register states after call
            for (int ai = 0; ai < numArgRegs; ai++) {
                reg_state_.erase(100 + ai);
            }
        } else {
            // bx rN = indirect branch (switch table, etc.)
            auto* micro = new MicroInsn(OP_GOTO, insn.addr);
            micro->l = Mop::reg(r, 4);
            micro->iprops = IPROP_VOLATILE;  // mark as indirect
            emitInsn(micro);
        }
    } else {
        // Immediate: blx #addr = direct call
        int64_t target = parseImm(ops[0]);
        auto* micro = new MicroInsn(OP_CALL, insn.addr);
        micro->iprops = IPROP_CALL | IPROP_VOLATILE;  // v9.2: was missing — DCE needs this
        micro->target_addr = (uint64_t)target;
        micro->d = Mop::reg(100, 4);  // v8.6: return value in r0
        micro->def_mreg = 100;
        micro->call_info = std::make_shared<CallInfo>();
        micro->call_info->target_addr = (uint64_t)target;
        micro->call_info->is_indirect = false;
        // v8.6: Resolve target name from global_names (same as handleCall)
        uint64_t targets[] = {(uint64_t)target, (uint64_t)target & ~1ULL, (uint64_t)target | 1ULL};
        for (uint64_t t : targets) {
            auto nameIt = mba_.global_names.find(t);
            if (nameIt != mba_.global_names.end() && !nameIt->second.empty()) {
                micro->call_info->target_name = nameIt->second;
                break;
            }
        }
        // v8.5: Populate string_args and clear reg_state_ (same as handleCall)
        int numArgRegs = is_aarch64_ ? 8 : 4;
        for (int ai = 0; ai < numArgRegs; ai++) {
            auto it = reg_state_.find(100 + ai);
            if (it != reg_state_.end() && it->second.kind == RegState::RESOLVED_SYM) {
                micro->call_info->string_args[ai] = it->second.sym_name;
            }
        }
        emitInsn(micro);
        // v8.5: Clear arg register states after call
        for (int ai = 0; ai < numArgRegs; ai++) {
            reg_state_.erase(100 + ai);
        }
    }
}

// ── tbb/tbh: ARM Thumb switch table branch v9.1 ──
//   tbb [pc, rN]      : byte  table at (pc + rN)  → branch via index
//   tbh [pc, rN, lsl #1] : halfword table at (pc + rN*2) → branch via index
// TBB/TBH 是 ARM Thumb 中 switch 的实现方式，等价于 Ghidra 的 switch table
void MicrocodeEmitter::handleTbbTbh(const AsmInsn& insn, bool is_tbh) {
    auto ops = splitOps(insn.op_str);
    if (ops.size() < 2) return;

    // 解析基址寄存器（通常是 pc/r15）和索引寄存器
    // 格式: "tbb [pc, rN]" 或 "tbh [pc, rN, lsl #1]"
    std::string baseReg = ops[0];
    std::string idxReg = ops[1];

    int baseMreg = parseReg(baseReg);
    int idxMreg = parseReg(idxReg);

    // v10.0: Compute the switch table address.
    // For TBB/TBH, the table is at PC + offset (PC = insn.addr + 4 in Thumb,
    // aligned to 4). The table base is where the entries start.
    // TBB: byte entries; TBH: halfword entries.
    uint64_t tableAddr = pcValue(insn.addr);

    // v10.6: Emit OP_JTBL (jump table) instruction instead of OP_LOAD.
    // This allows the edge resolver to create CFG edges to case blocks.
    auto* micro = new MicroInsn(OP_JTBL, insn.addr);
    micro->src_asm = insn.mnemonic + " " + insn.op_str;
    micro->iprops = IPROP_BRANCHED | IPROP_VOLATILE | IPROP_SWITCH;

    // Build memory operand: [base + idx * scale]
    // v10.6: Use memIndexed to properly store the index register and scale.
    micro->l = Mop::memIndexed(baseMreg, idxMreg, is_tbh ? 2 : 1, 0, is_tbh ? 2 : 1);
    // Also store index register in r for switch expression extraction
    if (idxMreg >= 0) {
        micro->r = Mop::reg(idxMreg, 4);
    }

    // v10.6: Don't set def_mreg — OP_JTBL is control flow, not a value definition.
    micro->def_mreg = -1;
    micro->switch_table_addr = tableAddr;

    // v10.0: Populate case_values from the ELF jump table data.
    // TBB entries are bytes, TBH entries are halfwords. Each entry is a
    // relative offset (target - branch_addr) / 2, so target = insn.addr + entryVal * 2.
    // v10.6: Also populate case_targets with actual target addresses.
    // We read up to 256 entries (max for TBB) or until we hit a zero entry
    // or run out of ELF data.
    if (elf_data_) {
        int maxEntries = is_tbh ? 65536 : 256;  // theoretical max
        int entrySize = is_tbh ? 2 : 1;
        for (int ci = 0; ci < maxEntries; ci++) {
            uint64_t entryAddr = tableAddr + (uint64_t)ci * entrySize;
            uint32_t entryVal = 0;
            bool ok = false;
            if (entrySize == 1) {
                uint32_t word = 0;
                if (readElfU32(entryAddr, word)) {
                    entryVal = word & 0xFF;
                    ok = true;
                }
            } else {
                uint32_t word = 0;
                if (readElfU32(entryAddr, word)) {
                    entryVal = word & 0xFFFF;
                    ok = true;
                }
            }
            if (!ok) break;
            // Heuristic: stop if the offset is implausibly large (table end).
            // TBB/TBH offsets are (target - branch_addr) / 2, so a value
            // larger than ~0x10000 (128KB reach) signals end of table.
            if (entryVal > 0x10000) break;
            micro->case_values.push_back((uint64_t)ci);
            // Compute target: insn.addr + entryVal * 2
            uint64_t target = insn.addr + (uint64_t)entryVal * 2;
            micro->case_targets.push_back(target);
        }
    }

    emitInsn(micro);
}

// ════════════════════════════════════════════════════════════════════
// v10.0: AArch64 jump table handler
// Pattern: ldr xN, [xM, xK, lsl #3]  ;  br xN
// The ldr loads a 64-bit value (absolute address or relative offset) from
// the jump table indexed by xK, and br jumps to it.
// We read the jump table entries from ELF data and populate case_values.
// ════════════════════════════════════════════════════════════════════
void MicrocodeEmitter::handleAarch64JumpTable(const AsmInsn& insn, int switchIdxReg) {
    // insn is the "br xN" instruction. The preceding ldr loaded the target.
    // We emit an OP_JTBL instruction marked with IPROP_SWITCH.
    auto ops = splitOps(insn.op_str);
    if (ops.empty()) return;
    int brReg = parseReg(ops[0]);
    if (brReg < 0) return;

    // v10.0: Try to determine the jump table base address from reg_state_.
    // The ldr's base register (xM) typically holds the table base, which
    // may have been set up by an ADRP+ADD sequence. We look it up here.
    // If we cannot resolve the table base, we still emit the OP_JTBL but
    // without case_values (the CFG structurer will use sequential indices).
    uint64_t tableAddr = 0;
    bool hasTableAddr = false;

    // The br register (xN) was loaded by the preceding ldr. Its base register
    // (xM) is the table base. We check reg_state_ for xN's source, but since
    // handleLoad already ran, the state may have been updated. As a fallback,
    // we scan reg_state_ for any ADRP/RESOLVED_SYM that looks like a table base.
    // For robustness, we attempt to read the table from the br register's
    // loaded value if it was resolved.
    auto rsIt = reg_state_.find(brReg);
    if (rsIt != reg_state_.end()) {
        if (rsIt->second.kind == RegState::IMM_CONST ||
            rsIt->second.kind == RegState::RESOLVED_SYM) {
            tableAddr = rsIt->second.value;
            hasTableAddr = (tableAddr != 0);
        }
    }

    // Emit OP_JTBL (jump table) instruction
    auto* micro = new MicroInsn(OP_JTBL, insn.addr);
    micro->src_asm = insn.mnemonic + " " + insn.op_str;
    micro->iprops = IPROP_BRANCHED | IPROP_VOLATILE | IPROP_SWITCH;
    micro->l = Mop::reg(brReg, 8);
    // v10.6: Don't set def_mreg — OP_JTBL is control flow, not a value definition.
    // Setting def_mreg would cause SSA to create unnecessary PHI nodes.
    micro->def_mreg = -1;
    micro->switch_table_addr = tableAddr;
    // v10.6: Store the switch index register (the variable being switched on).
    // This is used by the CTree builder to generate the switch expression.
    if (switchIdxReg >= 0) {
        micro->r = Mop::reg(switchIdxReg, 4);
    }

    // v10.0: Populate case_values from the ELF jump table data.
    // AArch64 jump tables typically contain 32-bit relative offsets (signed)
    // or 64-bit absolute addresses. We try reading 32-bit entries first;
    // if they look like small relative offsets, we use them. Otherwise we
    // try 64-bit absolute addresses.
    if (hasTableAddr && elf_data_) {
        // v10.6: Read 32-bit entries and compute actual target addresses.
        // AArch64 jump tables contain 32-bit signed relative offsets.
        // The offset is relative to the table base address (the ADR label).
        // target = tableAddr + (int32_t)entryVal
        //
        // Some compilers use offsets relative to the br instruction itself.
        // We try both: tableAddr + offset first, then insn.addr + offset.
        bool used32Bit = false;
        for (int ci = 0; ci < 256; ci++) {
            uint64_t entryAddr = tableAddr + (uint64_t)ci * 4;
            uint32_t entryVal = 0;
            if (!readElfU32(entryAddr, entryVal)) break;

            int32_t relOffset = (int32_t)entryVal;
            if (entryVal == 0) {
                // Could be case 0 with target == table base, keep it.
                micro->case_values.push_back((uint64_t)ci);
                micro->case_targets.push_back(tableAddr);
                used32Bit = true;
                continue;
            }
            // Stop if the offset is implausibly large (negative huge or > 1MB)
            if (relOffset < -0x100000 || relOffset > 0x100000) break;

            // Compute target: tableAddr + offset
            uint64_t target = tableAddr + (int64_t)relOffset;
            // Validate: target should be in code range
            if (target >= elf_base_addr_ && target < elf_base_addr_ + elf_data_size_) {
                micro->case_values.push_back((uint64_t)ci);
                micro->case_targets.push_back(target);
                used32Bit = true;
            } else {
                // Try: insn.addr + offset (offset relative to br instruction)
                target = insn.addr + (int64_t)relOffset;
                if (target >= elf_base_addr_ && target < elf_base_addr_ + elf_data_size_) {
                    micro->case_values.push_back((uint64_t)ci);
                    micro->case_targets.push_back(target);
                    used32Bit = true;
                } else {
                    break;
                }
            }
        }

        // If 32-bit didn't work, try 64-bit absolute addresses.
        if (!used32Bit || micro->case_values.size() < 2) {
            micro->case_values.clear();
            micro->case_targets.clear();
            for (int ci = 0; ci < 256; ci++) {
                uint64_t entryAddr = tableAddr + (uint64_t)ci * 8;
                uint32_t lo = 0, hi = 0;
                if (!readElfU32(entryAddr, lo)) break;
                if (!readElfU32(entryAddr + 4, hi)) break;
                uint64_t absAddr = ((uint64_t)hi << 32) | lo;
                if (absAddr == 0) break;
                // Stop if the address is outside a plausible code range
                if (absAddr < elf_base_addr_ ||
                    absAddr > elf_base_addr_ + elf_data_size_) break;
                micro->case_values.push_back((uint64_t)ci);
                micro->case_targets.push_back(absAddr);
            }
        }
    }

    emitInsn(micro);
}

// ════════════════════════════════════════════════════════════════════
// v10.0: ARM32 jump table handler
// Pattern: ldr pc, [pc, rN, lsl #2]
// The ldr loads a 32-bit absolute address from the jump table at
// (pc + rN*4) and branches to it. PC is the ARM pipeline value
// (insn.addr + 8 in ARM mode, insn.addr + 4 in Thumb).
// We read the jump table entries from ELF data and populate case_values.
// ════════════════════════════════════════════════════════════════════
void MicrocodeEmitter::handleArm32JumpTable(const AsmInsn& insn) {
    auto ops = splitOps(insn.op_str);
    // Format: "ldr pc, [pc, rN, lsl #2]" → ops after split:
    //   ["pc", "pc", "rN", "lsl", "#2"]  (brackets stripped by splitOps)
    // or  ["pc", "pc, rN, lsl #2"]       (depends on bracket handling)
    // The index register rN and shift are operands 2+.

    // Find the index register (the register operand after the base "pc")
    int idxMreg = -1;
    for (size_t oi = 1; oi < ops.size(); oi++) {
        // Skip "pc" (base) and "lsl" keyword and shift amount
        if (ops[oi] == "pc" || ops[oi] == "r15") continue;
        if (ops[oi] == "lsl") continue;
        if (!ops[oi].empty() && ops[oi][0] == '#') continue;
        // This should be the index register
        int r = parseReg(ops[oi]);
        if (r >= 0) {
            idxMreg = r;
            break;
        }
    }

    // Compute the jump table base address.
    // ARM mode: PC = insn.addr + 8; Thumb: PC = insn.addr + 4 (aligned).
    // The table starts at PC (the ldr pc instruction reads from [pc + idx*4]).
    uint64_t tableAddr = pcValue(insn.addr);

    // Emit OP_JTBL (jump table) instruction
    auto* micro = new MicroInsn(OP_JTBL, insn.addr);
    micro->src_asm = insn.mnemonic + " " + insn.op_str;
    micro->iprops = IPROP_BRANCHED | IPROP_VOLATILE | IPROP_SWITCH;

    if (idxMreg >= 0) {
        // v10.6: Store index register in both l and r for switch expression.
        // l is used for ARM32 pattern (register operand), r for switch expression.
        micro->l = Mop::reg(idxMreg, 4);
        micro->r = Mop::reg(idxMreg, 4);
        // v10.6: Don't set def_mreg — OP_JTBL is control flow, not a value definition.
        micro->def_mreg = -1;
    }
    micro->switch_table_addr = tableAddr;

    // v10.0: Populate case_values from the ELF jump table data.
    // ARM32 jump tables contain 32-bit absolute addresses. Each entry is
    // the target address of a case. We read entries until we hit an
    // address outside the plausible code range or run out of ELF data.
    // v10.6: Also populate case_targets with the actual target addresses.
    if (elf_data_) {
        for (int ci = 0; ci < 1024; ci++) {  // generous upper bound
            uint64_t entryAddr = tableAddr + (uint64_t)ci * 4;
            uint32_t entryVal = 0;
            if (!readElfU32(entryAddr, entryVal)) break;

            // ARM32 addresses: Thumb has bit 0 set for Thumb mode.
            // Strip the Thumb bit for range checking.
            uint32_t targetAddr = entryVal & ~1u;

            // Stop if the entry is 0 (end of table marker) or outside
            // the plausible code range.
            if (targetAddr == 0) break;
            if (elf_data_size_ > 0) {
                uint64_t codeStart = elf_base_addr_;
                uint64_t codeEnd = elf_base_addr_ + elf_data_size_;
                if (targetAddr < codeStart || targetAddr >= codeEnd) break;
            }
            micro->case_values.push_back((uint64_t)ci);
            micro->case_targets.push_back(targetAddr);
        }
    }

    emitInsn(micro);
}

// ── rsb: reverse subtract (dst = src2 - src1) ──
void MicrocodeEmitter::handleRsb(const AsmInsn& insn) {
    auto ops = splitOps(insn.op_str);
    if (ops.size() < 3) return;

    int dst = parseReg(ops[0]);
    int src1 = parseReg(ops[1]);

    auto* micro = new MicroInsn(OP_SUB, insn.addr);
    micro->def_mreg = dst;
    micro->d = Mop::reg(dst, 4);
    micro->l = Mop::reg(src1, 4);

    if (isReg(ops[2])) {
        micro->r = Mop::reg(parseReg(ops[2]), 4);
    } else {
        micro->r = Mop::imm64(parseImm(ops[2]));
    }
    // Swap l and r for reverse subtract
    auto tmp = micro->l;
    micro->l = micro->r;
    micro->r = tmp;
    emitInsn(micro);
}

// ── mvn: move NOT (dst = ~src) ──
void MicrocodeEmitter::handleMvn(const AsmInsn& insn) {
    auto ops = splitOps(insn.op_str);
    if (ops.size() < 2) return;

    int dst = parseReg(ops[0]);

    auto* micro = new MicroInsn(OP_XOR, insn.addr);  // ~x = x ^ -1
    micro->def_mreg = dst;
    micro->d = Mop::reg(dst, 4);
    if (isReg(ops[1])) {
        micro->l = Mop::reg(parseReg(ops[1]), 4);
    } else {
        micro->l = Mop::imm64(parseImm(ops[1]));
    }
    micro->r = Mop::imm64(-1);
    emitInsn(micro);
}

// ── adr: PC-relative address loading ──
void MicrocodeEmitter::handleAdr(const AsmInsn& insn) {
    auto ops = splitOps(insn.op_str);
    if (ops.size() < 2) return;

    int dst = parseReg(ops[0]);
    int64_t offset = parseImm(ops[1]);
    // v9.13: ARM mode: PC = insn.addr + 8; Thumb: PC = insn.addr + 4
    uint64_t targetAddr = pcValue(insn.addr) + offset;

    auto* micro = new MicroInsn(OP_LDC, insn.addr);
    micro->def_mreg = dst;
    micro->d = Mop::reg(dst, 4);

    // v10.1: Check if target is a string reference (ASCII + UTF-16)
    std::string strContent7;
    bool isUtf16_7 = false;
    size_t strLen7 = 0;
    if (resolveStringRef(targetAddr, strContent7, isUtf16_7, strLen7)) {
        micro->l = Mop::str(targetAddr, strContent7);
        // v10.1: Mark RESOLVED_SYM so handleCall can pass the string as a
        // literal argument.
        reg_state_[dst].kind = RegState::RESOLVED_SYM;
        reg_state_[dst].sym_name = strContent7;
        reg_state_[dst].value = targetAddr;
    } else {
        auto nameIt = mba_.global_names.find(targetAddr);
        if (nameIt != mba_.global_names.end()) {
            micro->l = Mop::global(targetAddr);
        } else {
            micro->l = Mop::imm64(targetAddr);
        }
    }
    emitInsn(micro);
}

// ── cmp/cmn for ARM32 ──
void MicrocodeEmitter::handleCmpArm32(const AsmInsn& insn, bool is_cmn) {
    auto ops = splitOps(insn.op_str);
    if (ops.size() < 2) return;

    int src1 = parseReg(ops[0]);

    // Record comparison state for subsequent conditional branch
    // This is the same mechanism used by AArch64 handleCmp
    cmp_a_mreg_ = src1;
    if (isImm(ops[1])) {
        cmp_b_is_imm_ = true;
        cmp_b_imm_ = parseImm(ops[1]);
        cmp_b_mreg_ = -1;
    } else {
        cmp_b_is_imm_ = false;
        cmp_b_mreg_ = parseReg(ops[1]);
    }

    auto* micro = new MicroInsn(is_cmn ? OP_ADD : OP_SUB, insn.addr);
    micro->src_asm = insn.mnemonic + " " + insn.op_str;
    micro->def_mreg = -1;  // cmp doesn't write to a register
    micro->l = Mop::reg(src1, 4);
    if (isReg(ops[1])) {
        micro->r = Mop::reg(parseReg(ops[1]), 4);
    } else {
        micro->r = Mop::imm64(parseImm(ops[1]));
    }
    emitInsn(micro);
}

} // namespace mc
