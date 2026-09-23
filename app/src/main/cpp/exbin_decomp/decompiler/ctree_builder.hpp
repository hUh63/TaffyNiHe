#pragma once
// ctree_builder.hpp — Region → CTree conversion
// 对标 Hex-Rays ctree generation from microcode

#include "ctree.hpp"
#include "cfg_structure.hpp"
#include "microcode.hpp"
#include "calling_convention.hpp"
#include "type_inference.hpp"
#include "known_symbols.hpp"
#include "indirect_call_resolver.hpp"
#include <climits>
#include <unordered_map>

namespace ctree {

// Builder: converts structured CFG (Region tree) into CTree AST
class CTreeBuilder {
public:
    // Main entry: convert region tree to CTree statement
    StmtPtr build(const cfg::Region& region, mc::MicrocodeBlockArray& mba);

    // Set parameter name mapping: mreg -> param name
    // e.g., 100 -> "a", 101 -> "b"
    void setParamNames(const std::vector<std::pair<int, std::string>>& params) {
        param_names_.clear();
        for (auto& [mreg, name] : params)
            param_names_[mreg] = name;
    }

    // Set local variable name mapping: mreg -> var name
    void setLocalVarNames(const std::unordered_map<int, std::string>& locals) {
        local_var_names_ = locals;
    }

    // Set signature map: funcName → C signature string
    void setSignatureMap(const std::map<std::string, std::string>& sigs) {
        sig_map_ = sigs;
    }

    // Phase 3: Set known symbols database for call return type inference
    void setKnownSymbolsDB(const symdb::KnownSymbolsDB* db) { db_ = db; }

    // Set whether this is AArch64 (true) or ARM32 (false)
    void setIsAArch64(bool is64) { is_aarch64_ = is64; }

    // ── v3.5: Stack variable analysis ──
    // Analyze all sp-relative load/store in the function and assign
    // local variable names (var_N) to unique sp+offset slots.
    void analyzeStackSlots();

    // v3.6: Track stack address registers (reg = sp + imm patterns)
    void trackStackAddrRegs();

    // Get the stack variable name for a given sp offset, or empty string
    std::string getStackVarName(int offset) const;

    // Get all detected stack slots (offset -> name), for CPrinter declaration
    const std::map<int, std::pair<std::string, CType>>& getStackSlots() const {
        return stack_slots_;
    }

    // v3.8: Check if instruction should produce a statement (public for testing)
    bool shouldEmitStmt(const mc::MicroInsn* insn) const;

    // v3.8: Trim parameter list based on actual usage in function body
    // Scans the microcode to find which arg registers are actually read
    // before being written (i.e., are real parameters from the caller).
    // Removes unused entries from param_names_.
    void trimParameterList();

    // v3.8: Get actual parameter count (after trimming)
    int getActualParamCount() const { return actual_param_count_; }

    // v10.5: Check if a parameter mreg is still active (not trimmed).
    // After trimParameterList(), some parameter registers may have been
    // removed from param_names_ because they were never read at SSA version 0
    // (i.e., they were redefined before any use, so they're not real parameters).
    // Use this to filter the function signature.
    bool isParamActive(int mreg) const {
        return param_names_.find(mreg) != param_names_.end();
    }
    // v5.8: Set function name for r2-style param count inference
    void setFuncName(const std::string& name) { func_name_ = name; }
    // v8.5: Set whether this is a C++ member function (has implicit 'this' param)
    void setIsMemberFunction(bool b) { is_member_func_ = b; }

    // v3.8: Get the set of actual parameter mregs
    std::set<int> getActualParamMregs() const { return actual_param_mregs_; }

    // v3.9: Get inferred type for a variable (mreg + ssa version)
    CType getInferredType(int mreg, int ssa_ver) const {
        return type_inference_.getType(mreg, ssa_ver);
    }

    // v3.9: Get inferred type for a stack slot
    CType getInferredStackType(int sp_offset) const {
        return type_inference_.getStackSlotType(sp_offset);
    }

    // v3.9: Get inferred type for a parameter
    CType getParamType(int paramIdx) const;

    // Phase 5: Set secondary symbol names for call target resolution
    // (injected from outside — e.g., known function names not in ELF symtab)
    void setSecondaryNames(const std::map<uint64_t, std::string>* names) {
        secondary_names_ = names;
    }

    // v9.0: Set indirect call resolver for virtual function call resolution
    void setIndirectCallResolver(const mc::IndirectCallResolver* resolver) {
        icall_resolver_ = resolver;
    }

private:
    mc::MicrocodeBlockArray* mba_ = nullptr;
    // Map from mreg+ssa_ver to variable name
    std::unordered_map<std::string, std::string> var_name_map_;
    // Set of declared variables
    std::set<std::string> declared_vars_;
    // Parameter register -> name mapping (mreg 100=x0 -> "a", etc.)
    std::unordered_map<int, std::string> param_names_;
    // Local variable register -> name mapping
    std::unordered_map<int, std::string> local_var_names_;
    // Signature map: funcName → C signature string
    std::map<std::string, std::string> sig_map_;
    // Phase 5: Secondary symbol names for call target resolution
    const std::map<uint64_t, std::string>* secondary_names_ = nullptr;
    // v9.0: Indirect call resolver for virtual function call resolution
    const mc::IndirectCallResolver* icall_resolver_ = nullptr;
    // Current block being processed (for liveness analysis)
    int current_block_id_ = -1;
    // Is AArch64 (true) or ARM32 (false)
    bool is_aarch64_ = true;

    // v3.5: Stack variable slots: sp_offset -> (var_name, var_type)
    // Populated by analyzeStackSlots()
    std::map<int, std::pair<std::string, CType>> stack_slots_;

    // v3.6: Stack address register tracking
    // When we see "reg = sp + imm", track reg -> imm so that
    // later [reg + imm2] can be resolved to var_<imm+imm2>
    // Key: (mreg, ssa_version) -> stack_offset
    std::map<std::pair<int,int>, int> stack_addr_regs_;

    // v3.8: Actual parameter info (set by trimParameterList)
    int actual_param_count_ = -1;  // -1 = not yet computed
    std::string func_name_;  // v5.8: function name for r2-style param inference
    bool is_member_func_ = false;  // v8.5: C++ member function flag
    std::set<int> actual_param_mregs_;

    // v3.9: Type inference pass
    TypeInferencePass type_inference_;

    // v5.6: Inferred parameter types from known callees (对标 r2 get_reg_type)
    std::map<int, mc::InferredParamType> inferred_param_types_;

    // Phase 3: Known symbols DB for call return type inference
    const symdb::KnownSymbolsDB* db_ = nullptr;

    // v4.12: Register-to-variable-name mapping for AArch64.
    // When a register has no param/local name, assign a proper name like
    // "var_1", "var_2" instead of leaking raw register names ("x8", "x19").
    // v8.6: Key is (mreg, ssa_ver) to distinguish different SSA versions
    // of the same register. Previously only mreg was used, causing all
    // SSA versions to share the same name (e.g., uVar1 reused across
    // multiple definitions).
    std::map<std::pair<int,int>, std::string> mreg_to_varname_;
    int var_counter_ = 0;

    // v5.0: Two-phase variable merging (对标 Ghidra varcode.cc)
    // Phase 1: COPY merging — OP_MOV dst, src unions dst and src into
    //          the same "high variable" (like Ghidra's HighVariable).
    // Phase 2: Type-based merging — variables with compatible types that
    //          don't interfere can share a name.
    // v9.11: Replaced union-find on mregs with copy-chain map keyed by
    //        (mreg, ssa_ver) → (src_mreg, src_ssa_ver). This avoids the
    //        fundamental collision between different SSA versions of the
    //        same merged mreg.
    std::map<std::pair<int,int>, std::pair<int,int>> copy_chain_;  // (dst_mreg, dst_ver) → (src_mreg, src_ver)
    void buildCopyMergeMap();  // scan OP_MOV chains and build copy-chain map
    std::pair<int,int> resolveCopyChain(int mreg, int ssa_ver);  // follow chain to source
    ExprPtr makeVarRef(int mreg, int ssa_ver);
    ExprPtr makeVarRef(const std::string& name, const CType& type = CType::unknown());

    // v3.6: Check if a register version holds a stack address
    // Returns the stack offset, or INT_MIN if not a stack address
    int getStackAddrOffset(int mreg, int ssa_ver) const {
        auto it = stack_addr_regs_.find({mreg, ssa_ver});
        if (it != stack_addr_regs_.end()) return it->second;
        return INT_MIN;
    }

    // v3.6: sp/lr/pc mreg for current architecture
    int spMreg() const { return is_aarch64_ ? 131 : 113; }
    int lrMreg() const { return is_aarch64_ ? 130 : 114; }
    int pcMreg() const { return is_aarch64_ ? 133 : 115; }
    int fpMreg() const { return is_aarch64_ ? 129 : 111; }

    // v3.8: Check if a mreg is a special register (sp/lr/pc/fp)
    bool isSpecialReg(int mreg) const {
        return mreg == spMreg() || mreg == lrMreg() ||
               mreg == pcMreg() || mreg == fpMreg();
    }

    // v3.5: Check if a memory operand is sp-relative
    bool isSpRelative(const mc::Mop& mop) const {
        return mop.mem_base == spMreg();
    }

    // v3.23: Loop context for break/continue generation
    struct LoopContext {
        int header_block;  // loop header (continue target)
        int exit_block;    // loop exit (break target)
    };
    std::vector<LoopContext> loop_stack_;

    // Convert region to statement
    StmtPtr regionToStmt(const cfg::Region& region);
    StmtPtr blockToStmt(const cfg::Region& region);
    StmtPtr sequenceToStmt(const cfg::Region& region);
    StmtPtr ifToStmt(const cfg::Region& region);
    StmtPtr whileToStmt(const cfg::Region& region);
    StmtPtr doWhileToStmt(const cfg::Region& region);
    StmtPtr forToStmt(const cfg::Region& region);
    StmtPtr switchToStmt(const cfg::Region& region);
    StmtPtr gotoToStmt(const cfg::Region& region);

    // Convert micro-instruction to expression
    ExprPtr microToExpr(const mc::MicroInsn* insn);
    ExprPtr mopToExpr(const mc::Mop& mop);
    ExprPtr buildLoadExpr(const mc::MicroInsn* insn);
    ExprPtr buildStoreExpr(const mc::MicroInsn* insn);
    ExprPtr buildCallExpr(const mc::MicroInsn* insn);

    // Convert block instructions to statements
    std::vector<StmtPtr> blockInsnsToStmts(int block_id);

    // Helper: get variable name for mreg
    std::string getVarName(int mreg, int ssa_ver = 0);
    std::string getRegName(int mreg);
};

} // namespace ctree
