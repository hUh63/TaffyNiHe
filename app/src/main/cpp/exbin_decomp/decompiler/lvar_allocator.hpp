#pragma once
// lvar_allocator.hpp — Local variable allocation
// 对标 Hex-Rays MMAT_LVARS + Ghidra Merge + Cover

#include "microcode.hpp"
#include <string>
#include <set>
#include <map>
#include <vector>

namespace mc {

// Live range for an SSA version
struct LiveRange {
    int mreg = -1;
    int ssa_version = 0;
    int def_block = -1;
    int def_idx = 0;
    std::set<int> use_blocks;
    // Cover: block_id → (start_idx, stop_idx)
    std::map<int, std::pair<int,int>> cover;
};

// Type info for variables
struct VarType {
    enum Category {
        TC_UNKNOWN, TC_VOID, TC_BOOL,
        TC_INT8, TC_UINT8, TC_INT16, TC_UINT16,
        TC_INT32, TC_UINT32, TC_INT64, TC_UINT64,
        TC_FLOAT, TC_DOUBLE,
        TC_POINTER, TC_STRING, TC_FUNC_PTR, TC_STRUCT_PTR
    } category = TC_UNKNOWN;
    int width = 8;
    bool is_signed = false;
    std::string struct_name;
    int struct_offset = 0;

    std::string toCString() const;
    bool isPointer() const { return category == TC_POINTER || category == TC_STRING ||
                                    category == TC_FUNC_PTR || category == TC_STRUCT_PTR; }
    bool isInteger() const { return category >= TC_INT8 && category <= TC_UINT64; }

    static VarType unknown() { return {}; }
    static VarType u64() { VarType t; t.category = TC_UINT64; t.width = 8; return t; }
    static VarType u32() { VarType t; t.category = TC_UINT32; t.width = 4; return t; }
    static VarType i64() { VarType t; t.category = TC_INT64; t.width = 8; t.is_signed = true; return t; }
    static VarType i32() { VarType t; t.category = TC_INT32; t.width = 4; t.is_signed = true; return t; }
    static VarType ptr() { VarType t; t.category = TC_POINTER; t.width = 8; return t; }
    static VarType str() { VarType t; t.category = TC_STRING; t.width = 8; return t; }
};

// Local variable (对标 lvar_t / HighVariable)
struct LocalVar {
    int id = -1;
    std::string name;
    VarType type;
    int width = 8;
    bool is_stack = false;
    int stack_offset = 0;
    int reg_mreg = -1;
    bool is_param = false;
    int param_idx = -1;
    bool is_callee_saved = false;
    std::vector<LiveRange> ranges;
    bool is_used = true;
    bool needs_decl = true;

    // Default name
    std::string defaultName() const {
        if (is_param) return "a" + std::to_string(param_idx + 1);
        if (is_stack) {
            char buf[32];
            snprintf(buf, sizeof(buf), "var_%x", stack_offset);
            return buf;
        }
        return "v" + std::to_string(id);
    }
};

// Variable allocator
class LVarAllocator {
public:
    // Main entry: allocate local variables from SSA
    void allocate(MicrocodeBlockArray& mba);

    // Get allocated variables
    const std::vector<LocalVar>& getVariables() const { return variables_; }
    std::vector<LocalVar>& getVariables() { return variables_; }

    // Get variable name for mreg + ssa_ver
    std::string getVarName(int mreg, int ssa_ver) const;

    // Get variable by name
    const LocalVar* findVar(const std::string& name) const;

    // Check if variable is declared
    bool isDeclared(const std::string& name) const;

private:
    MicrocodeBlockArray* mba_ = nullptr;
    std::vector<LocalVar> variables_;
    std::map<std::pair<int,int>, int> ssa_to_var_;  // (mreg, ssa_ver) → var index

    // Compute live ranges for all SSA versions
    void computeLiveRanges();

    // Merge SSA versions into local variables (Cover-based)
    void mergeVariables();

    // Identify parameters (x0-x7 for AArch64)
    void identifyParams();

    // Identify stack variables (sp/fp + offset)
    void identifyStackVars();

    // Identify callee-saved registers (x19-x28)
    void identifyCalleeSaved();

    // Assign names to variables
    void assignNames();
};

} // namespace mc
