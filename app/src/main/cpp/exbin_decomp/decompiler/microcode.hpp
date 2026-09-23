#pragma once
// microcode.hpp — Microcode IR data structures (对标 Hex-Rays mba_t/mblock_t/minsn_t/mop_t)
// Phase 1 core: defines the intermediate representation between ARM64 assembly and CTree.

#include <cstdint>
#include <string>
#include <vector>
#include <set>
#include <map>
#include <unordered_map>
#include <memory>
#include <algorithm>

namespace mc {

// ── Micro-op codes (对标 mcode_t, subset of Hex-Rays 72 opcodes) ──
enum MicroOp {
    OP_NOP,
    OP_MOV,        // d = l
    OP_LDC,        // d = imm
    // Arithmetic
    OP_ADD, OP_SUB, OP_MUL,
    OP_UDIV, OP_SDIV, OP_UMOD, OP_SMOD,
    // Bitwise
    OP_AND, OP_OR, OP_XOR, OP_NEG, OP_NOT,
    OP_SHL, OP_SHR, OP_SAR,
    // Comparisons (produce 0/1)
    OP_SETZ, OP_SETNZ, OP_SETB, OP_SETAE, OP_SETA, OP_SETBE,
    OP_SETG, OP_SETGE, OP_SETL, OP_SETLE, OP_SETS, OP_SETO,
    // Load/Store
    OP_LOAD,       // d = mem[base + offset]  (integer load)
    OP_STORE,      // mem[base + offset] = l  (integer store)
    OP_FLOAD,      // d = mem[base + offset]  (floating-point load, 对标 Ghidra FLOAT_LOAD)
    OP_FSTORE,     // mem[base + offset] = l  (floating-point store, 对标 Ghidra FLOAT_STORE)
    // Type conversion
    OP_XDU,        // zero-extend
    OP_XDS,        // sign-extend
    OP_LOW,        // take low half
    OP_HIGH,       // take high half
    // Control flow
    OP_GOTO,       // unconditional jump
    OP_CBRANCH,    // conditional jump
    OP_CALL,       // direct call
    OP_ICALL,      // indirect call
    OP_RET,        // return
    OP_JTBL,       // jump table (switch)
    // Carry/overflow
    OP_CFADD, OP_OFADD,
    // Floating point
    OP_F2I, OP_I2F, OP_F2F, OP_FADD, OP_FSUB, OP_FMUL, OP_FDIV,
    // SSA
    OP_PHI,        // phi node
    // Helper
    OP_HELPER      // auxiliary function call
};

// ── Operand types (对标 mopt_t) ──
enum MopType {
    MOP_NONE,
    MOP_REG,       // micro-register
    MOP_IMM,       // immediate
    MOP_MEM,       // memory reference (base + offset)
    MOP_INSN,      // nested micro-instruction result
    MOP_STKVAR,    // stack variable
    MOP_GLOBAL,    // global variable
    MOP_STR,       // string constant
    MOP_CALLINFO,  // function call info
    MOP_PHI_LIST,  // phi operand list
    MOP_FP_CONST   // floating-point constant
};

// ── Condition codes (ARM64 condition flags) ──
enum CondCode {
    CC_NONE,
    CC_EQ, CC_NE,
    CC_HS, CC_LO,   // unsigned >= / <
    CC_HI, CC_LS,   // unsigned > / <=
    CC_GE, CC_LT,   // signed >= / <
    CC_GT, CC_LE,   // signed > / <=
    CC_MI, CC_PL,   // negative / positive or zero
    CC_VS, CC_VC,   // overflow / no overflow
    CC_AL            // always
};

// ── Instruction properties (bitmask) ──
enum InsnProp {
    IPROP_NONE     = 0,
    IPROP_DEAD     = 1 << 0,   // dead code
    IPROP_VOLATILE = 1 << 1,   // has side effects
    IPROP_RET      = 1 << 2,   // return
    IPROP_CALL     = 1 << 3,   // call
    IPROP_STORE    = 1 << 4,   // store
    IPROP_LOAD     = 1 << 5,   // load
    IPROP_ADRP     = 1 << 6,   // adrp result
    IPROP_BRANCHED = 1 << 7,   // causes branch
    IPROP_ATOMIC   = 1 << 8,   // v3.13: atomic operation (ldrex/strex)
    IPROP_SWITCH   = 1 << 9,   // v9.1: switch table dispatch (tbb/tbh/jump table)
};

// ── Call info for function calls ──
struct CallInfo {
    uint64_t target_addr = 0;       // direct call target
    std::string target_name;        // resolved name (e.g. "FindClass", "memcpy")
    bool is_indirect = false;       // indirect call (blr)
    bool is_known = false;          // known library function
    std::string return_type;        // return type
    std::vector<std::string> param_types;
    std::vector<std::string> param_names;
    int arg_count = -1;             // v4.0: -1 = unknown, 0 = explicit zero-arg, >0 = known count
    bool has_return = true;         // does this call produce a return value?
    // v4.5: Resolved string arguments (arg_index → string value)
    // Populated by handleCall when reg_state_ shows a RESOLVED_SYM string.
    std::map<int, std::string> string_args;
};

// ── Operand (对标 mop_t) ──
struct Mop {
    MopType type = MOP_NONE;
    int width = 8;           // byte width (1, 2, 4, 8, 16)

    // Register
    int mreg = -1;           // micro-register number
    int ssa_ver = 0;         // SSA version (0 = undefined)

    // Immediate
    int64_t imm = 0;

    // Memory
    int mem_base = -1;       // base mreg
    int mem_base_ssa_ver = 0; // v8.5: SSA version of memory base register
    int64_t mem_offset = 0;
    bool mem_signed = false; // signed load
    int mem_index = -1;      // v10.6: index mreg (for [base + index * scale])
    int mem_scale = 1;       // v10.6: index scale factor (1, 2, 4, 8)

    // Stack variable
    int64_t stk_offset = 0;
    int stk_size = 0;

    // Global/string address
    uint64_t global_addr = 0;
    std::string str_val;     // string content (for MOP_STR)

    // Nested instruction
    struct MicroInsn* insn = nullptr;

    // Call info
    std::shared_ptr<CallInfo> call;

    // Phi sources (list of (mreg, ssa_ver) pairs)
    std::vector<std::pair<int,int>> phi_srcs;

    // Floating-point constant
    double fp_val = 0.0;

    Mop() = default;

    // Factory methods
    static Mop reg(int r, int w = 8) {
        Mop m;
        m.type = MOP_REG;
        m.mreg = r;
        m.width = w;
        return m;
    }
    static Mop imm64(int64_t v, int w = 8) {
        Mop m;
        m.type = MOP_IMM;
        m.imm = v;
        m.width = w;
        return m;
    }
    static Mop mem(int base, int64_t off, int w = 8, bool sign = false) {
        Mop m;
        m.type = MOP_MEM;
        m.mem_base = base;
        m.mem_offset = off;
        m.width = w;
        m.mem_signed = sign;
        return m;
    }
    // v10.6: Indexed memory: [base + index * scale + offset]
    static Mop memIndexed(int base, int index, int scale, int64_t off, int w = 8, bool sign = false) {
        Mop m;
        m.type = MOP_MEM;
        m.mem_base = base;
        m.mem_index = index;
        m.mem_scale = scale;
        m.mem_offset = off;
        m.width = w;
        m.mem_signed = sign;
        return m;
    }
    static Mop stkvar(int64_t off, int sz) {
        Mop m;
        m.type = MOP_STKVAR;
        m.stk_offset = off;
        m.stk_size = sz;
        m.width = sz;
        return m;
    }
    static Mop global(uint64_t addr) {
        Mop m;
        m.type = MOP_GLOBAL;
        m.global_addr = addr;
        m.width = 8;
        return m;
    }
    static Mop str(uint64_t addr, const std::string& s) {
        Mop m;
        m.type = MOP_STR;
        m.global_addr = addr;
        m.str_val = s;
        m.width = 8;
        return m;
    }
    static Mop fp(double v, int w = 8) {
        Mop m;
        m.type = MOP_FP_CONST;
        m.fp_val = v;
        m.width = w;
        return m;
    }

    bool isReg() const { return type == MOP_REG; }
    bool isImm() const { return type == MOP_IMM; }
    bool isMem() const { return type == MOP_MEM; }
    bool isStr() const { return type == MOP_STR; }
    bool isGlobal() const { return type == MOP_GLOBAL; }
    bool isStkVar() const { return type == MOP_STKVAR; }

    // Convert operand to C expression string
    std::string toCExpr() const;
};

// ── Micro-instruction (对标 minsn_t) ──
struct MicroInsn {
    MicroOp opcode = OP_NOP;
    int iprops = IPROP_NONE;
    uint64_t ea = 0;        // source instruction address
    Mop l, r, d;            // left, right, destination operands
    MicroInsn* next = nullptr;
    MicroInsn* prev = nullptr;

    // SSA version info
    int ssa_version = 0;    // version of the mreg defined by this instruction
    int def_mreg = -1;      // which mreg this instruction defines (-1 = none)

    // Memory SSA version (对标 Ghidra heritage.cc)
    // For STORE: the memory version this store creates
    // For LOAD: the memory version this load reads from (0 = unknown)
    int mem_version = 0;

    // Control flow
    int target_block = -1;  // goto/cbranch target block id (resolved later)
    uint64_t target_addr = 0; // raw jump target address (before block resolution)
    CondCode cond = CC_NONE; // condition for cbranch
    bool cond_is_imm = false; // condition compares with immediate
    int64_t cond_imm = 0;   // immediate value for condition comparison

    // Source assembly (for debugging)
    std::string src_asm;

    // Call info (for OP_CALL / OP_ICALL)
    std::shared_ptr<CallInfo> call_info;

    // v9.1: Switch table info (for TBB/TBH/jump table)
    std::vector<uint64_t> case_values;  // case values extracted from jump table
    uint64_t switch_table_addr = 0;     // address of the jump table
    // v10.6: Actual target addresses for each case (computed from jump table entries).
    // Populated by handleAarch64JumpTable/handleArm32JumpTable.
    // Used by edge resolution to create CFG edges from the switch block to case blocks.
    std::vector<uint64_t> case_targets; // target address for each case entry

    MicroInsn() = default;
    MicroInsn(MicroOp op, uint64_t addr) : opcode(op), ea(addr) {}

    bool isDead() const { return iprops & IPROP_DEAD; }
    bool isCall() const { return opcode == OP_CALL || opcode == OP_ICALL; }
    bool isStore() const { return opcode == OP_STORE; }
    bool isLoad() const { return opcode == OP_LOAD || opcode == OP_FLOAD; }
    bool isFLoad() const { return opcode == OP_FLOAD; }
    bool isFStore() const { return opcode == OP_FSTORE; }
    bool isBranch() const { return opcode == OP_GOTO || opcode == OP_CBRANCH; }
    bool isJumpTable() const { return opcode == OP_JTBL; }
    bool isRet() const { return opcode == OP_RET; }
    bool hasSideEffect() const {
        return iprops & (IPROP_VOLATILE | IPROP_CALL | IPROP_STORE | IPROP_RET);
    }

    // Get all mregs used (read) by this instruction
    std::vector<int> getUseMregs() const;
    // Get the mreg defined (written) by this instruction
    int getDefMreg() const { return def_mreg; }
};

// ── Register info ──
struct RegInfo {
    std::string name;       // "x0", "w0", "sp", etc.
    int width = 8;          // byte width
    bool is_phys = true;    // physical register
    int parent_mreg = -1;   // parent register (w0 -> x0)
    bool is_zero = false;   // zero register (xzr/wzr)
    bool is_sp = false;     // stack pointer
    bool is_pc = false;     // program counter
    bool is_lr = false;     // link register
    bool is_fp = false;     // frame pointer
};

// ── Micro-block (对标 mblock_t) ──
struct MicroBlock {
    int block_id = 0;
    uint64_t start_addr = 0;
    uint64_t end_addr = 0;
    MicroInsn* head = nullptr;
    MicroInsn* tail = nullptr;
    std::vector<int> predecessors;
    std::vector<int> successors;

    // Dominator tree
    int dom_parent = -1;
    std::vector<int> dom_children;
    std::vector<int> dom_frontier;

    // Post-dominator tree
    int pdom_parent = -1;
    std::vector<int> pdom_children;
    std::set<int> pdom;  // set of post-dominators (for data-flow analysis)

    // SSA
    std::vector<MicroInsn*> phi_nodes;

    // Loop info
    bool is_loop_header = false;
    int loop_back_edge_from = -1;

    // Region info (filled by cfg_structure)
    bool is_structured = false;

    ~MicroBlock() {
        MicroInsn* cur = head;
        while (cur) {
            MicroInsn* nxt = cur->next;
            delete cur;
            cur = nxt;
        }
    }

    // Append instruction to this block
    void appendInsn(MicroInsn* insn) {
        if (!head) {
            head = tail = insn;
        } else {
            tail->next = insn;
            insn->prev = tail;
            tail = insn;
        }
        insn->next = nullptr;
    }

    // Get all instructions in order
    std::vector<MicroInsn*> getInsns() const {
        std::vector<MicroInsn*> result;
        for (MicroInsn* i = head; i; i = i->next)
            result.push_back(i);
        return result;
    }
};

// ── MMAT maturity levels ──
enum MMAT {
    MMAT_ZERO = 0,
    MMAT_GENERATED,      // raw from assembly
    MMAT_PREOPTIMIZED,   // simple const/reg propagation
    MMAT_LOCOPT,         // local optimization per block
    MMAT_CALLS,          // call analysis
    MMAT_GLBOPT1,        // global opt phase 1
    MMAT_GLBOPT2,        // global opt phase 2
    MMAT_GLBOPT3,        // global opt done
    MMAT_LVARS           // local variables allocated
};

// ── Microcode Block Array (对标 mba_t) ──
struct MicrocodeBlockArray {
    std::vector<std::unique_ptr<MicroBlock>> blocks;
    int entry_block = 0;
    int maturity = MMAT_ZERO;

    // Register mapping: "x0" -> mreg 100, "w0" -> mreg 100 (low 32)
    std::unordered_map<std::string, int> phys_to_mreg;
    std::vector<RegInfo> mreg_info;

    // Function info
    uint64_t entry_addr = 0;
    std::string func_name;
    bool is_aarch64 = true;

    // String References: addr -> string content
    std::map<uint64_t, std::string> string_refs;
    // Global variable names: addr -> name
    std::map<uint64_t, std::string> global_names;

    // v10.1: 字符串长度信息: addr -> length (bytes, excluding NUL)
    // 用于 CPrinter 输出字符串字面量时附加长度注释
    std::map<uint64_t, size_t> string_lengths;
    // v10.1: UTF-16 字符串: addr -> u16string content
    // 当常量池地址命中此映射时，CPrinter 输出 L"..." 宽字符串
    std::map<uint64_t, std::u16string> utf16_strings;

    // v9.12: Section ranges for literal pool address validation.
    // When a literal pool load reads a value that falls within a known
    // data section, we can resolve it to a global reference even if
    // the address is not in global_names (e.g., unnamed data symbols).
    std::vector<std::pair<uint64_t, uint64_t>> data_section_ranges;
    std::vector<std::pair<uint64_t, uint64_t>> code_section_ranges;

    bool isInDataSection(uint64_t addr) const {
        for (auto& [start, end] : data_section_ranges) {
            if (addr >= start && addr < end) return true;
        }
        return false;
    }
    bool isInCodeSection(uint64_t addr) const {
        for (auto& [start, end] : code_section_ranges) {
            if (addr >= start && addr < end) return true;
        }
        return false;
    }

    // Unique mreg counter (for temporaries)
    int next_unique_mreg = 1000;

    MicrocodeBlockArray() {
        initRegMapAArch64();
    }

    // Movable but not copyable (contains unique_ptr)
    MicrocodeBlockArray(const MicrocodeBlockArray&) = delete;
    MicrocodeBlockArray& operator=(const MicrocodeBlockArray&) = delete;
    MicrocodeBlockArray(MicrocodeBlockArray&&) = default;
    MicrocodeBlockArray& operator=(MicrocodeBlockArray&&) = default;

    // Initialize ARM64 (AArch64) physical register mapping
    void initRegMapAArch64() {
        is_aarch64 = true;
        // x0-x30 -> mreg 100-130 (64-bit)
        for (int i = 0; i <= 30; i++) {
            std::string name = "x" + std::to_string(i);
            int mreg = 100 + i;
            phys_to_mreg[name] = mreg;
            if ((int)mreg_info.size() <= mreg)
                mreg_info.resize(mreg + 1);
            mreg_info[mreg] = {name, 8, true, -1, false, false, false, false, false};
            if (i == 29) mreg_info[mreg].is_fp = true;
            if (i == 30) mreg_info[mreg].is_lr = true;
        }
        // w0-w30 -> mreg 100-130 (32-bit view, parent = xN)
        for (int i = 0; i <= 30; i++) {
            std::string name = "w" + std::to_string(i);
            int mreg = 100 + i;  // same mreg as xN, width distinguishes
            phys_to_mreg[name] = mreg;
        }
        // sp (x31) -> mreg 131
        phys_to_mreg["sp"] = 131;
        if ((int)mreg_info.size() <= 131)
            mreg_info.resize(132);
        mreg_info[131] = {"sp", 8, true, -1, false, true, false, false, false};

        // xzr/wzr -> mreg 132 (zero register)
        phys_to_mreg["xzr"] = 132;
        phys_to_mreg["wzr"] = 132;
        if ((int)mreg_info.size() <= 132)
            mreg_info.resize(133);
        mreg_info[132] = {"xzr", 8, true, -1, true, false, false, false, false};

        // pc -> mreg 133
        phys_to_mreg["pc"] = 133;
        if ((int)mreg_info.size() <= 133)
            mreg_info.resize(134);
        mreg_info[133] = {"pc", 8, true, -1, false, false, true, false, false};

        // SIMD: v0-v31 / q0-q31 -> mreg 200-231 (128-bit)
        for (int i = 0; i <= 31; i++) {
            int mreg = 200 + i;
            std::string vname = "v" + std::to_string(i);
            std::string qname = "q" + std::to_string(i);
            phys_to_mreg[vname] = mreg;
            phys_to_mreg[qname] = mreg;
            if ((int)mreg_info.size() <= mreg)
                mreg_info.resize(mreg + 1);
            mreg_info[mreg] = {qname, 16, true, -1, false, false, false, false, false};
        }
        // d0-d31 -> mreg 200-231 (64-bit float, parent = vN)
        for (int i = 0; i <= 31; i++) {
            std::string name = "d" + std::to_string(i);
            phys_to_mreg[name] = 200 + i;
        }
        // s0-s31 -> mreg 200-231 (32-bit float)
        for (int i = 0; i <= 31; i++) {
            std::string name = "s" + std::to_string(i);
            phys_to_mreg[name] = 200 + i;
        }
    }

    // Initialize ARM32 (AArch32) physical register mapping
    // ARM AAPCS: r0-r3=args, r4-r11=callee-saved, r12=ip, r13=sp, r14=lr, r15=pc
    void initRegMapARM32() {
        is_aarch64 = false;
        // r0-r15 -> mreg 100-115 (32-bit)
        for (int i = 0; i <= 15; i++) {
            std::string name = "r" + std::to_string(i);
            int mreg = 100 + i;
            phys_to_mreg[name] = mreg;
            if ((int)mreg_info.size() <= mreg)
                mreg_info.resize(mreg + 1);
            mreg_info[mreg] = {name, 4, true, -1, false, false, false, false, false};
            if (i == 11) mreg_info[mreg].is_fp = true;   // r11 = fp
            if (i == 13) mreg_info[mreg].is_sp = true;   // r13 = sp
            if (i == 14) mreg_info[mreg].is_lr = true;   // r14 = lr
            if (i == 15) mreg_info[mreg].is_pc = true;   // r15 = pc
        }
        // Aliases
        phys_to_mreg["sp"] = 113;   // r13
        phys_to_mreg["lr"] = 114;   // r14
        phys_to_mreg["pc"] = 115;   // r15
        phys_to_mreg["fp"] = 111;   // r11
        phys_to_mreg["ip"] = 112;   // r12

        // SIMD (VFPv3): d0-d31, s0-s31, q0-q15
        for (int i = 0; i <= 31; i++) {
            int mreg = 200 + i;
            std::string dname = "d" + std::to_string(i);
            phys_to_mreg[dname] = mreg;
            if ((int)mreg_info.size() <= mreg)
                mreg_info.resize(mreg + 1);
            mreg_info[mreg] = {dname, 8, true, -1, false, false, false, false, false};
        }
        for (int i = 0; i <= 31; i++) {
            std::string sname = "s" + std::to_string(i);
            phys_to_mreg[sname] = 200 + (i / 2);  // s0/s1 -> d0, etc.
        }
    }

    // Lookup mreg by register name
    // v9.20: Normalize register names with leading zeros (e.g., "r04" → "r4",
    // "x00" → "x0"). ARM32 disassembly often uses zero-padded names like
    // "r04" in push/pop/ldm/stm register lists.
    int getMreg(const std::string& name) const {
        auto it = phys_to_mreg.find(name);
        if (it != phys_to_mreg.end())
            return it->second;
        // v9.20: Handle leading zeros in register names.
        if (name.size() >= 2 && (name[0] == 'r' || name[0] == 'x' || name[0] == 'w' ||
                                  name[0] == 'd' || name[0] == 's' || name[0] == 'q')) {
            std::string normalized = name;
            // Strip leading zeros from the numeric suffix (e.g., "r04" → "r4")
            size_t numStart = 1;
            while (numStart < normalized.size() && normalized[numStart] == '0'
                   && numStart + 1 < normalized.size()) {
                numStart++;
            }
            if (numStart > 1) {
                normalized = normalized[0] + normalized.substr(numStart);
                auto it2 = phys_to_mreg.find(normalized);
                if (it2 != phys_to_mreg.end())
                    return it2->second;
            }
        }
        return -1;
    }

    // Get register name from mreg
    std::string getRegName(int mreg) const {
        if (mreg >= 0 && mreg < (int)mreg_info.size())
            return mreg_info[mreg].name;
        if (mreg >= 1000)
            return "t" + std::to_string(mreg - 1000);
        return "0";  // v3.15: was "?" — invalid mreg should never appear as a variable
    }

    // Get register width
    int getRegWidth(int mreg) const {
        if (mreg >= 0 && mreg < (int)mreg_info.size())
            return mreg_info[mreg].width;
        return 8;
    }

    // Is this mreg a zero register?
    bool isZeroReg(int mreg) const {
        return mreg == 132;
    }

    // Is this mreg the stack pointer?
    bool isStackPtr(int mreg) const {
        return mreg == 131;
    }

    // Allocate a unique temporary mreg
    int allocUnique(int width = 8) {
        int mreg = next_unique_mreg++;
        if ((int)mreg_info.size() <= mreg)
            mreg_info.resize(mreg + 1);
        mreg_info[mreg] = {"t" + std::to_string(mreg - 1000), width, false, -1, false, false, false, false, false};
        return mreg;
    }

    // Create new block
    int newBlock(uint64_t start = 0) {
        int id = blocks.size();
        auto blk = std::make_unique<MicroBlock>();
        blk->block_id = id;
        blk->start_addr = start;
        blocks.push_back(std::move(blk));
        return id;
    }

    MicroBlock* getBlock(int id) {
        if (id >= 0 && id < (int)blocks.size())
            return blocks[id].get();
        return nullptr;
    }

    // Add edge between blocks
    void addEdge(int from, int to) {
        if (from < 0 || from >= (int)blocks.size()) return;
        if (to < 0 || to >= (int)blocks.size()) return;
        auto& succ = blocks[from]->successors;
        if (std::find(succ.begin(), succ.end(), to) == succ.end())
            succ.push_back(to);
        auto& pred = blocks[to]->predecessors;
        if (std::find(pred.begin(), pred.end(), from) == pred.end())
            pred.push_back(from);
    }

    // v4.3: Clear all edges (before re-resolving after foldAtomicCas)
    void clearAllEdges() {
        for (auto& blk : blocks) {
            if (blk) {
                blk->successors.clear();
                blk->predecessors.clear();
            }
        }
    }

    // Get number of blocks
    int numBlocks() const { return (int)blocks.size(); }
};

// ── Helper: convert condition code to C operator ──
inline const char* condToC(CondCode cc) {
    switch (cc) {
        case CC_EQ: return "==";
        case CC_NE: return "!=";
        case CC_HS: return ">=";  // unsigned
        case CC_LO: return "<";   // unsigned
        case CC_HI: return ">";
        case CC_LS: return "<=";
        case CC_GE: return ">=";  // signed
        case CC_LT: return "<";   // signed
        case CC_GT: return ">";
        case CC_LE: return "<=";
        case CC_MI: return "< 0";
        case CC_PL: return ">= 0";
        default: return "!= 0";
    }
}

// ── Helper: invert condition code ──
inline CondCode invertCond(CondCode cc) {
    switch (cc) {
        case CC_EQ: return CC_NE;
        case CC_NE: return CC_EQ;
        case CC_HS: return CC_LO;
        case CC_LO: return CC_HS;
        case CC_HI: return CC_LS;
        case CC_LS: return CC_HI;
        case CC_GE: return CC_LT;
        case CC_LT: return CC_GE;
        case CC_GT: return CC_LE;
        case CC_LE: return CC_GT;
        case CC_MI: return CC_PL;
        case CC_PL: return CC_MI;
        case CC_VS: return CC_VC;
        case CC_VC: return CC_VS;
        default: return cc;
    }
}

// ── Operand to C expression string ──
inline std::string Mop::toCExpr() const {
    switch (type) {
        case MOP_REG:
            return "r" + std::to_string(mreg) + (ssa_ver > 0 ? "_" + std::to_string(ssa_ver) : "");
        case MOP_IMM:
            return "0x" + [&]() -> std::string {
                if (imm >= 0) {
                    char buf[32];
                    snprintf(buf, sizeof(buf), "%llx", (unsigned long long)imm);
                    return buf;
                }
                char buf[32];
                snprintf(buf, sizeof(buf), "%llx", (unsigned long long)imm);
                return buf;
            }();
        case MOP_MEM: {
            std::string base = "r" + std::to_string(mem_base);
            if (mem_offset == 0)
                return "*(uint" + std::to_string(width * 8) + "_t*)" + base;
            char off[32];
            if (mem_offset >= 0)
                snprintf(off, sizeof(off), "0x%llx", (unsigned long long)mem_offset);
            else
                snprintf(off, sizeof(off), "-0x%llx", (unsigned long long)(-mem_offset));
            return "*(uint" + std::to_string(width * 8) + "_t*)(r" +
                   std::to_string(mem_base) + " + " + off + ")";
        }
        case MOP_STKVAR:
            return "local_" + [&]() -> std::string {
                char buf[32];
                snprintf(buf, sizeof(buf), "%x", (unsigned)(stk_offset & 0xFFFFFF));
                return buf;
            }();
        case MOP_GLOBAL:
            return "g_" + [&]() -> std::string {
                char buf[32];
                snprintf(buf, sizeof(buf), "%llx", (unsigned long long)global_addr);
                return buf;
            }();
        case MOP_STR:
            return "\"" + str_val + "\"";
        case MOP_FP_CONST: {
            char buf[64];
            snprintf(buf, sizeof(buf), "%g", fp_val);
            return buf;
        }
        case MOP_INSN:
            return insn ? insn->d.toCExpr() : "/* insn */";
        default:
            return "/* unknown */";
    }
}

// ── Get use mregs from an instruction ──
inline std::vector<int> MicroInsn::getUseMregs() const {
    std::vector<int> uses;
    auto addMop = [&](const Mop& m) {
        if (m.type == MOP_REG && m.mreg >= 0)
            uses.push_back(m.mreg);
        else if (m.type == MOP_MEM && m.mem_base >= 0)
            uses.push_back(m.mem_base);
    };
    // For most instructions, l and r are uses
    if (opcode != OP_PHI && opcode != OP_STORE) {
        addMop(l);
        addMop(r);
    } else if (opcode == OP_STORE) {
        // store: l is the value to store, mem address is in d
        addMop(l);
        if (d.type == MOP_MEM && d.mem_base >= 0)
            uses.push_back(d.mem_base);
    }
    // For phi, uses come from phi_srcs
    if (opcode == OP_PHI) {
        for (auto& [mr, ver] : l.phi_srcs)
            uses.push_back(mr);
    }
    return uses;
}

} // namespace mc
