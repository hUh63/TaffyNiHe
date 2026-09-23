#pragma once
// microcode_emitter.hpp — ARM64 instruction decomposer
// Translates Capstone-disassembled ARM64 instructions into microcode IR.
// 对标 Hex-Rays MMAT_GENERATED stage.

#include "microcode.hpp"
#include <string>
#include <vector>
#include <set>
#include <unordered_map>
#include <map>

namespace mc {

// v10.0: Inline function detection info
struct InlineFunctionInfo {
    uint64_t entry_addr = 0;    // address of the first instruction in the inline pattern
    std::string name;          // detected function name (e.g. "memcpy", "memset", "strlen")
    int size = 0;              // number of instructions in the pattern
    bool is_inline = true;      // true if detected as an inlined pattern
};

// Decoded instruction from Capstone (simplified representation)
struct AsmInsn {
    uint64_t addr = 0;
    std::string mnemonic;
    std::string op_str;
    std::vector<std::string> operands;
    bool is_branch = false;
    bool is_call = false;
    bool is_ret = false;
    size_t bytes_size = 4;
};

// ADRP+ADD/LDR fusion state
struct AdrpState {
    bool valid = false;
    uint64_t page_addr = 0;
    int mreg = -1;
};

// Register state tracking (for ADRP fusion and mov tracking)
struct RegState {
    enum Kind { UNKNOWN, ADRP, IMM_CONST, REG_COPY, LITERAL_POOL, GOT_ADDR, RESOLVED_SYM, VTABLE_PTR, VTFUNC_PTR };
    Kind kind = UNKNOWN;
    uint64_t value = 0;      // for IMM_CONST, LITERAL_POOL: the loaded value
    int src_mreg = -1;       // for REG_COPY
    uint64_t lit_addr = 0;   // for LITERAL_POOL: the literal pool address
    std::string sym_name;    // for RESOLVED_SYM: resolved symbol name
    int vtbl_base_mreg = -1; // for VTABLE_PTR: the register that holds the vtable base
};

// The emitter: converts assembly to microcode
class MicrocodeEmitter {
public:
    // Main entry: convert a list of assembly instructions to microcode
    MicrocodeBlockArray emit(const std::vector<AsmInsn>& insns, uint64_t entry_addr = 0);

    // Set architecture: true=AArch64, false=ARM32/Thumb
    void setIsAArch64(bool is64) { is_aarch64_ = is64; }
    bool isAArch64() const { return is_aarch64_; }
    // v9.13: Set Thumb mode (ARM32 only). When true, PC = insn.addr + 4
    // instead of insn.addr + 8 (ARM mode pipeline offset).
    void setIsThumb(bool isThumb) { is_thumb_ = isThumb; }
    bool isThumb() const { return is_thumb_; }

    // v3.10: Preset ELF intelligence (string refs, global names)
    // Must be called BEFORE emit() so PC-relative loads can be resolved.
    void setStringRefs(const std::map<uint64_t, std::string>& refs) {
        for (auto& [addr, s] : refs) mba_.string_refs[addr] = s;
    }
    void setGlobalNames(const std::map<uint64_t, std::string>& names) {
        for (auto& [addr, name] : names) mba_.global_names[addr] = name;
    }

    // v10.1: Preset string length + UTF-16 string metadata.
    // These mirror ElfSymbolMap.stringLengths / utf16Strings so that
    // the emitter can mark RESOLVED_SYM with string content when a
    // PC-relative load resolves to a known string address.
    void setStringLengths(const std::map<uint64_t, size_t>& lengths) {
        for (auto& [addr, len] : lengths) mba_.string_lengths[addr] = len;
    }
    void setUtf16Strings(const std::map<uint64_t, std::u16string>& strs) {
        for (auto& [addr, s] : strs) mba_.utf16_strings[addr] = s;
    }

    // v3.19: Set ELF raw data for literal pool reading.
    // ARM32 LDR [pc, #off] loads a 4-byte value from the literal pool.
    // Without raw bytes, we cannot resolve the two-step pattern:
    //   ldr r1, [pc, #0x5ec]  → r1 = offset (from literal pool)
    //   add r1, pc, r1         → r1 = GOT address
    //   ldr r1, [r1]           → r1 = *GOT = symbol address
    // With ELF data, we can read the literal pool and fuse this pattern.
    void setElfData(const uint8_t* data, size_t size, uint64_t base_addr) {
        elf_data_ = data;
        elf_data_size_ = size;
        elf_base_addr_ = base_addr;
    }
    // v3.19: Set GOT entry → symbol name mapping (from .rel.plt)
    void setGotToName(const std::map<uint64_t, std::string>& got_map) {
        got_to_name_ = got_map;
    }

    // v7.0: Set vtable consensus map: offset → most common function name.
    // When the emitter sees ldr xM, [vtbl, #offset], it resolves to the consensus name.
    void setVtableConsensus(const std::map<int, std::string>& consensus) {
        vtable_consensus_ = consensus;
    }

    // v9.0: Set known vtable addresses — enables VTABLE_PTR detection for
    // vtable loads from any register, not just from "this" (mreg 100).
    // When the emitter sees a load that resolves to a known vtable address,
    // it marks the destination as VTABLE_PTR.
    void setVtableAddresses(const std::set<uint64_t>& vtable_addrs) {
        vtable_addresses_ = vtable_addrs;
    }

    // v3.13: Post-emit peephole pass — fold ldrex+sub/add+strex into
    // __sync_fetch_and_sub/add. Call after emit(), before SSA.
    void foldAtomicCas();

    // v3.14: Ghidra-inspired special register elimination pass.
    // Marks all sp/pc/lr/fp operations as IPROP_DEAD at the IR level,
    // so they never reach CTree building. This mirrors Ghidra's Heritage
    // mechanism which eliminates stack-pointer bookkeeping before printing.
    // Runs after foldAtomicCas() in emit().
    void eliminateSpecialRegisters();

    // v10.0: Inline function detection — scan emitted microcode for
    // known patterns (memset, memcpy, strlen) that compilers inline,
    // mark original instructions as IPROP_DEAD and replace with OP_CALL.
    // 对标 Ghidra: detect embedded known function patterns in function bodies.
    void detectInlineFunctions();

    // v10.0: Access detected inline functions (for CTree builder, etc.)
    const std::vector<InlineFunctionInfo>& detectedInlines() const {
        return detected_inlines_;
    }

private:
    MicrocodeBlockArray mba_;
    bool is_aarch64_ = true;  // default AArch64
    bool is_thumb_ = false;   // v9.13: true = Thumb mode (ARM32), PC = addr + 4
    std::unordered_map<int, AdrpState> adrp_state_;  // mreg -> adrp state
    std::unordered_map<int, RegState> reg_state_;     // mreg -> tracked state

    // ARM32 movw/movt state (for PC-relative address loading)
    struct MovwState {
        bool valid = false;
        uint16_t imm16 = 0;
        int mreg = -1;
    };
    std::unordered_map<int, MovwState> movw_state_;  // ARM32 movw+movt fusion

    // v9.23: AArch64 movz+movk fusion for 64-bit constant construction
    struct MovzState {
        bool valid = false;
        uint64_t accumulated = 0;
        int mreg = -1;
        MicroInsn* last_insn = nullptr;  // the OP_MOV to update with full value
    };
    std::unordered_map<int, MovzState> movz_state_;

    // v3.19: ELF raw data for literal pool reading
    const uint8_t* elf_data_ = nullptr;
    size_t elf_data_size_ = 0;
    uint64_t elf_base_addr_ = 0;
    // v3.19: GOT entry address → import symbol name
    std::map<uint64_t, std::string> got_to_name_;

    // v7.0: vtable consensus: offset → function name
    std::map<int, std::string> vtable_consensus_;

    // v9.0: Known vtable addresses for VTABLE_PTR detection
    std::set<uint64_t> vtable_addresses_;

    // v10.0: Detected inline functions
    std::vector<InlineFunctionInfo> detected_inlines_;

    // v3.19: Read 4 bytes from ELF at given virtual address (for literal pool)
    // Returns false if address is outside ELF data range
    bool readElfU32(uint64_t vaddr, uint32_t& out) const;

    // v3.11: ARM32 LDREX state (for atomic pattern recognition)
    // v3.13: 简化为只记录最后一条 ldrex 指令，合并逻辑移到 foldAtomicCas()
    MicroInsn* last_ldrex_insn_ = nullptr;

    // CMP state (for feeding conditional branches)
    int cmp_a_mreg_ = -1;
    int cmp_b_mreg_ = -1;
    int64_t cmp_b_imm_ = 0;
    bool cmp_b_is_imm_ = false;

    // v9.17: Tail call detection — when lr is restored via pop,
    // the next unconditional branch is a tail call.
    bool lr_restored_ = false;

    // Current block being emitted
    int cur_block_ = -1;

    // Block boundary map: addr -> block_id
    std::unordered_map<uint64_t, int> block_at_;

    // Operand parsing (public for parseMemOp access)
    int parseReg(const std::string& s);
    int64_t parseImm(const std::string& s);
    bool isReg(const std::string& s);
    bool isImm(const std::string& s);
    std::string stripImm(const std::string& s);

    // Memory operand parsing
    struct MemOperand {
        int base_mreg = -1;
        int64_t offset = 0;
        bool has_index = false;
        int index_mreg = -1;
        int shift = 0;
        bool pre_indexed = false;
        bool post_indexed = false;
    };
    MemOperand parseMemOp(const std::string& s);

private:
    // Helper methods
    void startBlock(uint64_t addr);
    void emitInsn(MicroInsn* insn);
    void splitBlock(uint64_t addr);
    // v3.11: Remove the last instruction from a block (for peephole optimization)
    void removeLastInsn(MicroBlock* blk);

    // Instruction handlers
    void handleMov(const AsmInsn& insn);
    void handleAdd(const AsmInsn& insn);
    void handleSub(const AsmInsn& insn);
    void handleMul(const AsmInsn& insn);
    void handleDiv(const AsmInsn& insn, bool is_signed);
    void handleLogical(const AsmInsn& insn, MicroOp op);
    void handleShift(const AsmInsn& insn, MicroOp op);
    void handleCmp(const AsmInsn& insn);
    void handleLoad(const AsmInsn& insn, int width, bool is_signed);
    void handleStore(const AsmInsn& insn, int width);
    void handleFLoad(const AsmInsn& insn, int width);
    void handleFStore(const AsmInsn& insn, int width);
    void handleAdrp(const AsmInsn& insn);
    void handleBranch(const AsmInsn& insn, bool is_cond, CondCode cc = CC_NONE);
    void handleCall(const AsmInsn& insn, bool is_indirect);
    void handleRet(const AsmInsn& insn);
    void handleStp(const AsmInsn& insn);
    void handleStrd(const AsmInsn& insn);  // v9.15: ARM32 store register dual
    void handleLdp(const AsmInsn& insn);
    void handleCsel(const AsmInsn& insn);
    void handleCbz(const AsmInsn& insn, bool is_nz);
    void handleTbz(const AsmInsn& insn, bool is_nz);
    void handleMadd(const AsmInsn& insn);
    void handleMsub(const AsmInsn& insn);

    // ARM32-specific instruction handlers
    void handleMovwMovt(const AsmInsn& insn, bool is_movt);
    void handlePush(const AsmInsn& insn);   // ARM32 push {r4, r5, lr}
    void handlePop(const AsmInsn& insn);    // ARM32 pop {r4, r5, pc}
    void handleLdmStm(const AsmInsn& insn, bool is_load);  // ldm/stm
    void handleBxBlx(const AsmInsn& insn, bool is_blx);    // bx/blx
    void handleTbbTbh(const AsmInsn& insn, bool is_tbh);  // tbb/tbh switch table
    void handleAarch64JumpTable(const AsmInsn& insn, int switchIdxReg = -1);  // v10.0: AArch64 jump table (ldr+br pattern)
    void handleArm32JumpTable(const AsmInsn& insn);         // v10.0: ARM32 jump table (ldr pc, [pc, rN, lsl #2])
    void handleRsb(const AsmInsn& insn);    // rsb (reverse subtract)
    void handleMvn(const AsmInsn& insn);    // mvn (move not)
    void handleAdr(const AsmInsn& insn);    // adr (PC-relative address)
    void handleCmpArm32(const AsmInsn& insn, bool is_cmn); // cmp/cmn for ARM32

    // Strip ARM32 condition suffix from mnemonic
    // e.g. "addeq" → "add", "movne" → "mov"
    std::string stripCondSuffix(const std::string& mnem);
    std::string extractCondSuffix(const std::string& mnem);

    // v9.13: Compute effective PC value for a given instruction address.
    // AArch64: PC = insn.addr
    // ARM mode: PC = insn.addr + 8
    // Thumb: PC = Align(insn.addr + 4, 4) — word-aligned per ARM ARM
    uint64_t pcValue(uint64_t insnAddr) const {
        if (is_aarch64_) return insnAddr;
        if (is_thumb_) return (insnAddr + 4) & ~3ULL;
        return insnAddr + 8;
    }

    // ADRP+ADD fusion check
    bool tryAdrpFusion(int dst_mreg, int64_t add_imm);
    bool tryAdrpLoad(int dst_mreg, int64_t ldr_offset);

    // v10.1: Resolve a .rodata/.data address to string content.
    // Checks string_refs (ASCII), utf16_strings (UTF-16) and string_lengths.
    // On hit, returns true and fills out_str (ASCII content or UTF-16 → UTF-8
    // best-effort) plus is_utf16 flag and byte length. This is used by the
    // ADRP+ADD/LDR fusion and ARM32 literal pool paths to mark RESOLVED_SYM.
    bool resolveStringRef(uint64_t addr, std::string& out_str,
                          bool& is_utf16, size_t& out_len) const;

    // Split operands
    std::vector<std::string> splitOps(const std::string& op_str);
};

} // namespace mc
