// var_types.cpp — Variable allocation + type propagation + naming
// Implements LVarAllocator, TypePropagator, VarNamer

#include "lvar_allocator.hpp"
#include "type_propagator.hpp"
#include "var_namer.hpp"
#include "known_symbols.hpp"
#include "calling_convention.hpp"
#include "type_inference.hpp"  // v9.4: for getReturnTypeForCallee
#include <algorithm>
#include <set>
#include <map>

namespace mc {

// ── VarType::toCString() ──
std::string VarType::toCString() const {
    switch (category) {
        case TC_VOID:    return "void";
        case TC_BOOL:    return "bool";
        case TC_INT8:    return "int8_t";
        case TC_UINT8:   return "uint8_t";
        case TC_INT16:   return "int16_t";
        case TC_UINT16:  return "uint16_t";
        case TC_INT32:   return "int32_t";
        case TC_UINT32:  return "uint32_t";
        case TC_INT64:   return "int64_t";
        case TC_UINT64:  return "uint64_t";
        case TC_FLOAT:   return "float";
        case TC_DOUBLE:  return "double";
        case TC_POINTER: return "void*";
        case TC_STRING:  return "const char*";
        case TC_FUNC_PTR:return "void*";
        case TC_STRUCT_PTR: return struct_name + "*";
        default:         return "uint64_t";
    }
}

// ════════════════════════════════════════════════════════════════════
// LVarAllocator implementation
// ════════════════════════════════════════════════════════════════════

void LVarAllocator::allocate(MicrocodeBlockArray& mba) {
    mba_ = &mba;
    variables_.clear();
    ssa_to_var_.clear();

    // Step 1: Identify parameters (x0-x7 for AArch64)
    identifyParams();

    // Step 2: Identify stack variables
    identifyStackVars();

    // Step 3: Identify callee-saved registers (x19-x28)
    identifyCalleeSaved();

    // Step 4: Create variables for all other SSA versions
    computeLiveRanges();
    mergeVariables();

    // Step 5: Assign names
    assignNames();

    mba.maturity = MMAT_LVARS;
}

void LVarAllocator::identifyParams() {
    if (!mba_) return;

    // For AArch64: x0-x7 are argument registers
    // Create a variable for each that is used
    std::set<int> used_arg_regs;
    for (int b = 0; b < mba_->numBlocks(); b++) {
        for (auto* insn = mba_->blocks[b]->head; insn; insn = insn->next) {
            auto uses = insn->getUseMregs();
            for (int m : uses) {
                if (m >= 100 && m <= 107) {  // x0-x7
                    used_arg_regs.insert(m);
                }
            }
        }
    }

    for (int mreg : used_arg_regs) {
        LocalVar var;
        var.id = (int)variables_.size();
        var.reg_mreg = mreg;
        var.type = VarType::u64();
        var.width = 8;
        var.is_param = true;
        var.param_idx = mreg - 100;
        var.name = "a" + std::to_string(mreg - 99);  // a1, a2, ...
        var.needs_decl = true;

        // Map all SSA versions of this mreg to this variable
        ssa_to_var_[{mreg, 0}] = var.id;  // version 0 (entry value)
        variables_.push_back(var);
    }
}

void LVarAllocator::identifyStackVars() {
    if (!mba_) return;

    // Find all stack variable accesses: mem(sp/fp, offset) or stkvar(offset)
    std::set<int64_t> stack_offsets;
    for (int b = 0; b < mba_->numBlocks(); b++) {
        for (auto* insn = mba_->blocks[b]->head; insn; insn = insn->next) {
            if (insn->l.type == MOP_MEM && (insn->l.mem_base == 131 || insn->l.mem_base == 129)) {
                stack_offsets.insert(insn->l.mem_offset);
            }
            if (insn->d.type == MOP_MEM && (insn->d.mem_base == 131 || insn->d.mem_base == 129)) {
                stack_offsets.insert(insn->d.mem_offset);
            }
            if (insn->l.type == MOP_STKVAR) {
                stack_offsets.insert(insn->l.stk_offset);
            }
            if (insn->d.type == MOP_STKVAR) {
                stack_offsets.insert(insn->d.stk_offset);
            }
        }
    }

    for (int64_t off : stack_offsets) {
        LocalVar var;
        var.id = (int)variables_.size();
        var.is_stack = true;
        var.stack_offset = (int)off;
        var.type = VarType::u64();
        var.width = 8;
        char buf[32];
        snprintf(buf, sizeof(buf), "var_%x", (unsigned)(off & 0xFFFFFF));
        var.name = buf;
        var.needs_decl = true;
        variables_.push_back(var);
    }
}

void LVarAllocator::identifyCalleeSaved() {
    if (!mba_) return;

    // x19-x28 are callee-saved (mreg 119-128)
    std::set<int> used_saved;
    for (int b = 0; b < mba_->numBlocks(); b++) {
        for (auto* insn = mba_->blocks[b]->head; insn; insn = insn->next) {
            auto uses = insn->getUseMregs();
            for (int m : uses) {
                if (m >= 119 && m <= 128) {
                    used_saved.insert(m);
                }
            }
            if (insn->def_mreg >= 119 && insn->def_mreg <= 128) {
                used_saved.insert(insn->def_mreg);
            }
        }
    }

    // Also check x30 (lr, mreg 130)
    for (int b = 0; b < mba_->numBlocks(); b++) {
        for (auto* insn = mba_->blocks[b]->head; insn; insn = insn->next) {
            auto uses = insn->getUseMregs();
            for (int m : uses) {
                if (m == 130) used_saved.insert(m);
            }
        }
    }

    for (int mreg : used_saved) {
        LocalVar var;
        var.id = (int)variables_.size();
        var.reg_mreg = mreg;
        var.is_callee_saved = true;
        var.type = VarType::u64();
        var.width = 8;

        if (mreg == 130) {
            var.name = "saved_lr";
        } else {
            var.name = "saved_" + mba_->getRegName(mreg);
        }
        var.needs_decl = true;
        ssa_to_var_[{mreg, 0}] = var.id;
        variables_.push_back(var);
    }
}

void LVarAllocator::computeLiveRanges() {
    if (!mba_) return;

    // For each SSA version, find where it's defined and used
    std::map<std::pair<int,int>, LiveRange> ranges;

    for (int b = 0; b < mba_->numBlocks(); b++) {
        int idx = 0;
        for (auto* insn = mba_->blocks[b]->head; insn; insn = insn->next, idx++) {
            // Definition
            if (insn->def_mreg >= 0 && insn->ssa_version > 0) {
                auto key = std::make_pair(insn->def_mreg, insn->ssa_version);
                ranges[key].mreg = insn->def_mreg;
                ranges[key].ssa_version = insn->ssa_version;
                ranges[key].def_block = b;
                ranges[key].def_idx = idx;
            }

            // Uses
            auto uses = insn->getUseMregs();
            for (int m __attribute__((unused)) : uses) {
                // For SSA, we need the version — but getUseMregs doesn't return versions
                // Simplified: just track the block
            }
        }
    }
}

void LVarAllocator::mergeVariables() {
    if (!mba_) return;

    // Simplified: create one variable per mreg (not per SSA version)
    // This avoids the complexity of Cover-based merging
    std::set<int> all_def_mregs;
    std::set<int> all_use_mregs;

    for (int b = 0; b < mba_->numBlocks(); b++) {
        for (auto* insn = mba_->blocks[b]->head; insn; insn = insn->next) {
            if (insn->def_mreg >= 0 && insn->def_mreg < 1000 &&
                insn->def_mreg != 132 && insn->def_mreg != 131) {
                // Skip already-allocated registers (params, saved)
                bool already = false;
                for (auto& v : variables_) {
                    if (v.reg_mreg == insn->def_mreg) {
                        already = true;
                        // Map this SSA version to existing var
                        ssa_to_var_[{insn->def_mreg, insn->ssa_version}] = v.id;
                        break;
                    }
                }
                if (!already) {
                    all_def_mregs.insert(insn->def_mreg);
                }
            }
            auto uses = insn->getUseMregs();
            for (int m : uses) {
                if (m >= 1000) all_use_mregs.insert(m);
            }
        }
    }

    // Create variables for temp registers (mreg >= 1000)
    for (int mreg : all_use_mregs) {
        // Check if already allocated
        bool found = false;
        for (auto& v : variables_) {
            if (v.reg_mreg == mreg) { found = true; break; }
        }
        if (found) continue;

        LocalVar var;
        var.id = (int)variables_.size();
        var.reg_mreg = mreg;
        var.type = VarType::u64();
        var.width = mba_->getRegWidth(mreg);
        var.name = "v" + std::to_string(var.id);
        var.needs_decl = true;
        ssa_to_var_[{mreg, 0}] = var.id;
        variables_.push_back(var);
    }
}

void LVarAllocator::assignNames() {
    // Names already assigned in identify* and merge*
    // Could add semantic naming here
}

std::string LVarAllocator::getVarName(int mreg, int ssa_ver) const {
    auto it = ssa_to_var_.find({mreg, ssa_ver});
    if (it != ssa_to_var_.end() && it->second < (int)variables_.size()) {
        return variables_[it->second].name;
    }
    // Fallback: try version 0
    it = ssa_to_var_.find({mreg, 0});
    if (it != ssa_to_var_.end() && it->second < (int)variables_.size()) {
        return variables_[it->second].name;
    }
    // Fallback: use register name
    if (mba_) return mba_->getRegName(mreg);
    return "r" + std::to_string(mreg);
}

const LocalVar* LVarAllocator::findVar(const std::string& name) const {
    for (auto& v : variables_) {
        if (v.name == name) return &v;
    }
    return nullptr;
}

bool LVarAllocator::isDeclared(const std::string& name) const {
    const LocalVar* v = findVar(name);
    return v && v->needs_decl;
}

// ════════════════════════════════════════════════════════════════════
// TypePropagator implementation
// ════════════════════════════════════════════════════════════════════

void TypePropagator::propagate(MicrocodeBlockArray& mba, LVarAllocator& allocator) {
    mba_ = &mba;
    allocator_ = &allocator;

    inferFromCallingConvention();
    inferFromStrings();
    inferFromMemAccess();
    inferFromComparisons();
    inferFromKnownCalls();
    inferFromPointerArith();
    inferCrossFunction();
}

void TypePropagator::inferFromCallingConvention() {
    // x0-x7 are parameters; x0 is return value
    for (auto& var : allocator_->getVariables()) {
        if (var.is_param) {
            // Default: uint64_t (may be refined later)
            var.type = VarType::u64();
        }
    }
}

void TypePropagator::inferFromStrings() {
    if (!mba_) return;

    // Find LDC instructions that load string references
    for (int b = 0; b < mba_->numBlocks(); b++) {
        for (auto* insn = mba_->blocks[b]->head; insn; insn = insn->next) {
            if (insn->opcode == OP_LDC && insn->l.isStr()) {
                // The defined register holds a string pointer
                int mreg = insn->def_mreg;
                if (mreg >= 0) {
                    for (auto& var : allocator_->getVariables()) {
                        if (var.reg_mreg == mreg) {
                            var.type = VarType::str();
                            break;
                        }
                    }
                }
            }
        }
    }
}

void TypePropagator::inferFromMemAccess() {
    if (!mba_) return;

    // Infer type from load/store width
    for (int b = 0; b < mba_->numBlocks(); b++) {
        for (auto* insn = mba_->blocks[b]->head; insn; insn = insn->next) {
            if (insn->opcode == OP_LOAD && insn->def_mreg >= 0) {
                int width = insn->l.width;
                bool is_signed = insn->l.mem_signed;
                for (auto& var : allocator_->getVariables()) {
                    if (var.reg_mreg == insn->def_mreg) {
                        switch (width) {
                            case 1: var.type = is_signed ? VarType::i32() : VarType::u32(); break;
                            case 2: var.type = is_signed ? VarType::i32() : VarType::u32(); break;
                            case 4: var.type = is_signed ? VarType::i32() : VarType::u32(); break;
                            case 8: var.type = VarType::u64(); break;
                        }
                        break;
                    }
                }
            }
        }
    }
}

void TypePropagator::inferFromComparisons() {
    if (!mba_) return;

    // b.hs/b.lo → unsigned; b.ge/b.lt → signed
    for (int b = 0; b < mba_->numBlocks(); b++) {
        for (auto* insn = mba_->blocks[b]->head; insn; insn = insn->next) {
            if (insn->opcode == OP_CBRANCH) {
                bool is_unsigned = (insn->cond == CC_HS || insn->cond == CC_LO ||
                                    insn->cond == CC_HI || insn->cond == CC_LS);
                bool is_signed = (insn->cond == CC_GE || insn->cond == CC_LT ||
                                  insn->cond == CC_GT || insn->cond == CC_LE);
                if (is_signed || is_unsigned) {
                    // Mark the compared register as signed/unsigned
                    if (insn->l.isReg()) {
                        int mreg = insn->l.mreg;
                        for (auto& var : allocator_->getVariables()) {
                            if (var.reg_mreg == mreg) {
                                if (is_signed && var.type.category == VarType::TC_UINT64)
                                    var.type = VarType::i64();
                                else if (is_signed && var.type.category == VarType::TC_UINT32)
                                    var.type = VarType::i32();
                                break;
                            }
                        }
                    }
                }
            }
        }
    }
}

void TypePropagator::inferFromKnownCalls() {
    // 对标 Ghidra typeop.cc: 从已知函数签名传播参数类型
    // 扫描所有 CALL 指令，查找已知 callee 的签名，将返回类型和参数类型
    // 传播到对应的 LVarAllocator 变量
    if (!mba_ || !allocator_) return;

    for (int b = 0; b < mba_->numBlocks(); b++) {
        for (auto* insn = mba_->blocks[b]->head; insn; insn = insn->next) {
            if (insn->opcode != OP_CALL && insn->opcode != OP_ICALL) continue;

            // 查找 callee 名称
            std::string calleeName;
            if (insn->call_info && !insn->call_info->target_name.empty()) {
                calleeName = insn->call_info->target_name;
            } else if (insn->target_addr != 0) {
                auto nameIt = mba_->global_names.find(insn->target_addr);
                if (nameIt != mba_->global_names.end())
                    calleeName = nameIt->second;
            }
            if (calleeName.empty()) continue;

            // 传播返回类型到 def 寄存器
            std::string retType = ctree::TypeInferencePass::getReturnTypeForCallee(calleeName);
            if (!retType.empty() && insn->def_mreg >= 0) {
                VarType vt;
                if (retType.find('*') != std::string::npos) {
                    vt = VarType::ptr();
                } else if (retType == "int" || retType == "long" || retType == "size_t") {
                    vt = VarType::u64();
                } else if (retType == "void") {
                    vt = VarType::unknown();
                }
                if (vt.category != VarType::TC_UNKNOWN || retType == "void") {
                    setVarType(insn->def_mreg, insn->ssa_version, vt);
                }
            }

            // 传播参数类型到参数寄存器
            auto paramTypes = CallingConvention::getParamTypesForCallee(calleeName);
            int maxArgs = sizeof(ARG_REGS_ARM32) / sizeof(int);
            const int* argRegs = ARG_REGS_ARM32;
            // 通过特征检测 AArch64 (简化: 看 mreg 范围)
            bool likelyAArch64 = false;
            for (auto& var : allocator_->getVariables()) {
                if (var.reg_mreg >= 108) { likelyAArch64 = true; break; }
            }
            if (likelyAArch64) {
                maxArgs = sizeof(ARG_REGS_AARCH64) / sizeof(int);
                argRegs = ARG_REGS_AARCH64;
            }

            for (int i = 0; i < (int)paramTypes.size() && i < maxArgs; i++) {
                if (paramTypes[i] == "...") continue;
                int argMreg = argRegs[i];
                // 向前扫描找到该参数寄存器的最新 SSA 版本
                int foundSsaVer = -1;
                for (auto* prev = insn->prev; prev; prev = prev->prev) {
                    if (prev->def_mreg == argMreg) {
                        foundSsaVer = prev->ssa_version;
                        break;
                    }
                }
                if (foundSsaVer < 0) {
                    if (insn->l.isReg() && insn->l.mreg == argMreg)
                        foundSsaVer = insn->l.ssa_ver;
                    else if (insn->r.isReg() && insn->r.mreg == argMreg)
                        foundSsaVer = insn->r.ssa_ver;
                }
                if (foundSsaVer < 0) continue;

                VarType vt;
                const std::string& pt = paramTypes[i];
                if (pt.find('*') != std::string::npos) {
                    vt = VarType::ptr();
                } else if (pt == "int") {
                    vt = VarType::i32();
                } else if (pt == "size_t" || pt == "unsigned long") {
                    vt = VarType::u64();
                }
                if (vt.category != VarType::TC_UNKNOWN) {
                    setVarType(argMreg, foundSsaVer, vt);
                }
            }
        }
    }
}

void TypePropagator::inferFromPointerArith() {
    // 对标 Ghidra typeop.cc: 指针算术检测
    // 1. 扫描 LOAD/STORE 中非 SP 的基址寄存器 → 标记为指针
    // 2. 检测同一基址寄存器的多个不同偏移 → 结构体指针
    // 3. ADD/SUB 涉及指针寄存器 → 传播指针类型
    if (!mba_ || !allocator_) return;

    static const int SP_MREG = 131;  // 栈指针

    // Pass 1: 收集所有用作内存基址的寄存器（非 SP）
    std::set<int> memBaseRegs;
    std::map<int, std::set<int>> baseOffsets;  // reg → {offset set}
    for (int b = 0; b < mba_->numBlocks(); b++) {
        for (auto* insn = mba_->blocks[b]->head; insn; insn = insn->next) {
            if (insn->opcode == OP_LOAD && insn->l.isMem() && insn->l.mem_base >= 0) {
                if (insn->l.mem_base != SP_MREG) {
                    memBaseRegs.insert(insn->l.mem_base);
                    baseOffsets[insn->l.mem_base].insert((int)insn->l.mem_offset);
                }
            }
            if (insn->opcode == OP_STORE && insn->d.isMem() && insn->d.mem_base >= 0) {
                if (insn->d.mem_base != SP_MREG) {
                    memBaseRegs.insert(insn->d.mem_base);
                    baseOffsets[insn->d.mem_base].insert((int)insn->d.mem_offset);
                }
            }
        }
    }

    // Pass 2: 标记内存基址寄存器为指针
    for (int baseReg : memBaseRegs) {
        for (auto& var : allocator_->getVariables()) {
            if (var.reg_mreg == baseReg) {
                var.type = VarType::ptr();
                break;
            }
        }
    }

    // Pass 3: 结构体检测 — 同一基址 ≥3 个不同偏移
    for (auto& [baseReg, offsets] : baseOffsets) {
        if ((int)offsets.size() >= 3) {
            for (auto& var : allocator_->getVariables()) {
                if (var.reg_mreg == baseReg && var.type.category != VarType::TC_POINTER) {
                    var.type = VarType::ptr();
                    break;
                }
            }
        }
    }

    // Pass 4: ADD/SUB 涉及内存基址寄存器 → 结果也是指针
    for (int b = 0; b < mba_->numBlocks(); b++) {
        for (auto* insn = mba_->blocks[b]->head; insn; insn = insn->next) {
            if (insn->opcode != OP_ADD && insn->opcode != OP_SUB) continue;
            if (insn->def_mreg < 0) continue;

            bool lhsIsBase = insn->l.isReg() && memBaseRegs.count(insn->l.mreg);
            bool rhsIsBase = insn->r.isReg() && memBaseRegs.count(insn->r.mreg);
            if (lhsIsBase || rhsIsBase) {
                for (auto& var : allocator_->getVariables()) {
                    if (var.reg_mreg == insn->def_mreg) {
                        var.type = VarType::ptr();
                        break;
                    }
                }
            }
        }
    }
}

void TypePropagator::inferCrossFunction() {
    // 对标 Ghidra typeop.cc: 跨函数类型传播
    // 如果一个参数被用作已知函数的参数，且已知函数对该参数有类型要求，
    // 则将该类型传播回参数变量。
    // 例如: 如果某参数被传给 strlen，则该参数是 const char*
    if (!mba_ || !allocator_) return;

    for (int b = 0; b < mba_->numBlocks(); b++) {
        for (auto* insn = mba_->blocks[b]->head; insn; insn = insn->next) {
            if (insn->opcode != OP_CALL && insn->opcode != OP_ICALL) continue;

            // 查找 callee 名称
            std::string calleeName;
            if (insn->call_info && !insn->call_info->target_name.empty()) {
                calleeName = insn->call_info->target_name;
            } else if (insn->target_addr != 0) {
                auto nameIt = mba_->global_names.find(insn->target_addr);
                if (nameIt != mba_->global_names.end())
                    calleeName = nameIt->second;
            }
            if (calleeName.empty()) continue;

            auto paramTypes = CallingConvention::getParamTypesForCallee(calleeName);
            if (paramTypes.empty()) continue;

            // 检测架构
            int maxArgs = sizeof(ARG_REGS_ARM32) / sizeof(int);
            const int* argRegs = ARG_REGS_ARM32;
            bool likelyAArch64 = false;
            for (auto& var : allocator_->getVariables()) {
                if (var.reg_mreg >= 108) { likelyAArch64 = true; break; }
            }
            if (likelyAArch64) {
                maxArgs = sizeof(ARG_REGS_AARCH64) / sizeof(int);
                argRegs = ARG_REGS_AARCH64;
            }

            for (int i = 0; i < (int)paramTypes.size() && i < maxArgs; i++) {
                if (paramTypes[i] == "...") continue;
                int argMreg = argRegs[i];

                // 向前扫描找该参数寄存器的 SSA 版本
                int foundSsaVer = -1;
                for (auto* prev = insn->prev; prev; prev = prev->prev) {
                    if (prev->def_mreg == argMreg) {
                        foundSsaVer = prev->ssa_version;
                        break;
                    }
                }
                if (foundSsaVer < 0) {
                    if (insn->l.isReg() && insn->l.mreg == argMreg)
                        foundSsaVer = insn->l.ssa_ver;
                    else if (insn->r.isReg() && insn->r.mreg == argMreg)
                        foundSsaVer = insn->r.ssa_ver;
                }
                if (foundSsaVer < 0) continue;

                const std::string& pt = paramTypes[i];
                VarType vt;
                if (pt == "const char*") {
                    vt = VarType::str();
                } else if (pt.find('*') != std::string::npos) {
                    vt = VarType::ptr();
                } else if (pt == "int") {
                    vt = VarType::i32();
                } else if (pt == "size_t" || pt == "unsigned long") {
                    vt = VarType::u64();
                }
                if (vt.category == VarType::TC_UNKNOWN) continue;

                // 跨函数传播: 如果这个参数寄存器是函数的参数变量，
                // 则更新该变量的类型
                for (auto& var : allocator_->getVariables()) {
                    if (var.reg_mreg == argMreg && var.is_param) {
                        if (var.type.category == VarType::TC_UNKNOWN ||
                            var.type.category == VarType::TC_UINT64 ||
                            var.type.category == VarType::TC_UINT32) {
                            var.type = vt;
                        }
                        break;
                    }
                }
            }
        }
    }
}

void TypePropagator::setVarType(int mreg, int ssa_ver, const VarType& type) {
    (void)ssa_ver;
    if (!allocator_) return;
    for (auto& var : allocator_->getVariables()) {
        if (var.reg_mreg == mreg) {
            var.type = type;
            break;
        }
    }
}

VarType TypePropagator::getVarType(int mreg, int ssa_ver) const {
    (void)ssa_ver;
    if (!allocator_) return VarType::unknown();
    for (auto& var : allocator_->getVariables()) {
        if (var.reg_mreg == mreg) return var.type;
    }
    return VarType::unknown();
}

// ════════════════════════════════════════════════════════════════════
// VarNamer implementation
// ════════════════════════════════════════════════════════════════════

void VarNamer::name(MicrocodeBlockArray& mba, LVarAllocator& allocator) {
    mba_ = &mba;
    allocator_ = &allocator;

    for (auto& var : allocator.getVariables()) {
        std::string semantic = nameVariable(var);
        if (!semantic.empty()) {
            var.name = semantic;
        }
    }
}

std::string VarNamer::nameVariable(const LocalVar& var) {
    // Priority: semantic name > default name
    std::string name = inferSemanticName(var);
    if (!name.empty()) return name;
    return var.defaultName();
}

std::string VarNamer::inferSemanticName(const LocalVar& var) {
    std::string name;
    // v9.0: Priority: this > env > str > result > size > loop counter
    if (!(name = checkThisPointer(var)).empty()) return name;
    if (!(name = checkEnvPointer(var)).empty()) return name;
    if (!(name = checkStringPointer(var)).empty()) return name;
    if (!(name = checkReturnValue(var)).empty()) return name;
    if (!(name = checkSizeLength(var)).empty()) return name;
    if (!(name = checkLoopCounter(var)).empty()) return name;
    return "";
}

std::string VarNamer::checkEnvPointer(const LocalVar& var) {
    // Check if first param and used for JNI calls
    if (!var.is_param || var.param_idx != 0) return "";
    if (!mba_) return "";

    // Check if this register is dereferenced and the result is called
    bool found_jni_pattern = false;
    for (int b = 0; b < mba_->numBlocks(); b++) {
        for (auto* insn = mba_->blocks[b]->head; insn; insn = insn->next) {
            if (insn->opcode == OP_LOAD && insn->l.type == MOP_MEM &&
                insn->l.mem_base == var.reg_mreg) {
                // Loading from [env + offset]
                found_jni_pattern = true;
                break;
            }
        }
    }
    return found_jni_pattern ? "env" : "";
}

std::string VarNamer::checkStringPointer(const LocalVar& var) {
    if (var.type.category == VarType::TC_STRING) return "str";
    return "";
}

std::string VarNamer::checkReturnValue(const LocalVar& var) {
    // x0 is return value — but don't rename if it's a param
    if (var.reg_mreg == 100 && !var.is_param) return "result";
    return "";
}

std::string VarNamer::checkSizeLength(const LocalVar& var) {
    // v9.0: Detect if used as size/length argument to known functions
    // Patterns: memcpy(..., n), malloc(n), strlen(...), etc.
    if (!mba_) return "";

    bool used_as_size = false;
    for (int b = 0; b < mba_->numBlocks(); b++) {
        for (auto* insn = mba_->blocks[b]->head; insn; insn = insn->next) {
            if (!insn->isCall() || !insn->call_info) continue;
            
            const auto& ci = *insn->call_info;
            const std::string& callee = ci.target_name;
            
            // Check if this variable is used as a size/count argument
            // memcpy/memmove/memset: 3rd arg is size
            // malloc/calloc: 1st arg is size
            // strlen: return value is length
            // fread/fwrite: 2nd arg is size, 3rd is count
            static const std::set<std::string> size_funcs = {
                "memcpy", "memmove", "memset", "memcmp", "memchr",
                "malloc", "calloc", "realloc",
                "fread", "fwrite",
                "__aeabi_memcpy", "__aeabi_memcpy4", "__aeabi_memcpy8",
                "__aeabi_memset", "__aeabi_memset4", "__aeabi_memset8",
                "__aeabi_memmove", "__aeabi_memmove4", "__aeabi_memmove8",
                "__aeabi_memclr", "__aeabi_memclr4", "__aeabi_memclr8",
            };

            if (size_funcs.count(callee)) {
                // Check if the variable matches the size argument register
                int size_arg_reg = -1;
                if (callee.find("memcpy") != std::string::npos ||
                    callee.find("memmove") != std::string::npos ||
                    callee.find("memset") != std::string::npos ||
                    callee.find("memcmp") != std::string::npos) {
                    size_arg_reg = 102;  // x2/r2 (3rd arg)
                } else if (callee == "malloc" || callee == "realloc") {
                    size_arg_reg = 100;  // x0/r0 (1st arg)
                } else if (callee == "calloc") {
                    size_arg_reg = 100;  // x0/r0 or x1/r1
                }

                if (size_arg_reg >= 0) {
                    // Check if the variable is used as the size argument
                    auto uses = insn->getUseMregs();
                    if (std::find(uses.begin(), uses.end(), size_arg_reg) != uses.end()) {
                        // Check if this variable maps to the size register
                        if (var.reg_mreg == size_arg_reg) {
                            used_as_size = true;
                            break;
                        }
                    }
                }
            }
        }
        if (used_as_size) break;
    }

    return used_as_size ? "size" : "";
}

std::string VarNamer::checkThisPointer(const LocalVar& var) {
    // v9.0: Detect C++ this pointer
    // Pattern: first parameter (x0), loaded for vtable access
    // ldr xN, [x0] or ldr xN, [x0, #vtable_offset]
    if (!var.is_param || var.param_idx != 0) return "";
    if (!mba_) return "";

    bool found_this_pattern = false;
    for (int b = 0; b < mba_->numBlocks(); b++) {
        for (auto* insn = mba_->blocks[b]->head; insn; insn = insn->next) {
            // Look for load from [this + offset] where offset looks like vtable/field offset
            if (insn->opcode == OP_LOAD && insn->l.type == MOP_MEM &&
                insn->l.mem_base == var.reg_mreg) {
                // Loading from this pointer — check if it's a vtable access
                // (loading function pointer from [this]) or field access
                int64_t offset = insn->l.mem_offset;
                if (offset >= 0 && offset < 0x1000) {
                    // Check if the loaded value is used as a call target
                    // (function pointer from vtable)
                    int loaded_mreg = insn->def_mreg;
                    if (loaded_mreg >= 0) {
                        // Scan for indirect call using this loaded value
                        for (auto* next = insn->next; next; next = next->next) {
                            if (next->opcode == OP_ICALL) {
                                auto uses = next->getUseMregs();
                                if (std::find(uses.begin(), uses.end(), loaded_mreg) != uses.end()) {
                                    found_this_pattern = true;
                                    break;
                                }
                            }
                            if (next->opcode == OP_CALL || next->opcode == OP_RET) break;
                        }
                    }
                    if (!found_this_pattern) {
                        // Also check for field access pattern (store to [this + offset])
                        for (auto* next = insn->next; next; next = next->next) {
                            if (next->opcode == OP_STORE && next->l.type == MOP_MEM &&
                                next->l.mem_base == var.reg_mreg) {
                                found_this_pattern = true;
                                break;
                            }
                            if (next->opcode == OP_CALL || next->opcode == OP_RET) break;
                        }
                    }
                }
                if (found_this_pattern) break;
            }
        }
        if (found_this_pattern) break;
    }

    return found_this_pattern ? "this" : "";
}

std::string VarNamer::checkLoopCounter(const LocalVar& var) {
    // v9.0: Detect loop counter pattern
    // Pattern: var = 0 or other constant, then var < N comparison, then var++
    if (!mba_) return "";

    bool has_init = false, has_compare = false, has_increment = false;
    
    for (int b = 0; b < mba_->numBlocks(); b++) {
        for (auto* insn = mba_->blocks[b]->head; insn; insn = insn->next) {
            // Check for initialization: MOV var, #const or LDC var, #const
            if (insn->def_mreg == var.reg_mreg) {
                if (insn->opcode == OP_LDC || insn->opcode == OP_MOV) {
                    if (insn->l.isImm() && insn->l.imm >= 0 && insn->l.imm <= 100) {
                        has_init = true;
                    }
                }
            }

            // Check for comparison: var < N or var != N
            if (insn->opcode == OP_CBRANCH) {
                if (insn->l.isReg() && insn->l.mreg == var.reg_mreg) {
                    has_compare = true;
                }
                if (insn->r.isReg() && insn->r.mreg == var.reg_mreg) {
                    has_compare = true;
                }
            }

            // Check for increment: ADD var, var, #1 or similar
            if (insn->def_mreg == var.reg_mreg) {
                if (insn->opcode == OP_ADD || insn->opcode == OP_SUB) {
                    // var = var + const or var = var + reg
                    if ((insn->l.isReg() && insn->l.mreg == var.reg_mreg) ||
                        (insn->r.isReg() && insn->r.mreg == var.reg_mreg)) {
                        has_increment = true;
                    }
                }
            }
        }
    }

    if (has_init && has_compare && has_increment) return "i";
    if (has_init && has_compare) return "cnt";
    return "";
}

} // namespace mc
