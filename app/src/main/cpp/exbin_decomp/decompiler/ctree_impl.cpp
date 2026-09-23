// ctree_impl.cpp — CTree builder + beautification passes implementation
// Converts structured CFG (Region tree) into CTree AST, then beautifies.

#include "ctree_builder.hpp"
#include "ctree_beautify.hpp"
#include "lvar_allocator.hpp"
#include "indirect_call_resolver.hpp"
#include "high_variable.hpp"  // v9.6: HighVariable support
#include <algorithm>
#include <cctype>
#include <functional>
#include <iostream>
#include <cstdio>
#include <set>

namespace ctree {

// v3.16: Forward declarations for helper functions defined later
ExprPtr cloneExpr(const Expr* e);
bool exprHasSideEffects(const Expr* expr);

// ════════════════════════════════════════════════════════════════════
// toString() implementations for CTree nodes
// ════════════════════════════════════════════════════════════════════

std::string Block::toString() const {
    std::string s = "{\n";
    for (auto& st : statements)
        s += "  " + st->toString() + "\n";
    s += "}";
    return s;
}

std::string If::toString() const {
    std::string s = "if (" + (condition ? condition->toString() : "0") + ") ";
    s += then_branch ? then_branch->toString() : "{}";
    if (else_branch) s += " else " + else_branch->toString();
    return s;
}

std::string While::toString() const {
    std::string s = "while (" + (condition ? condition->toString() : "1") + ") ";
    s += body ? body->toString() : "{}";
    return s;
}

std::string DoWhile::toString() const {
    std::string s = "do ";
    s += body ? body->toString() : "{}";
    s += " while (" + (condition ? condition->toString() : "1") + ");";
    return s;
}

std::string For::toString() const {
    return "for (;;) " + (body ? body->toString() : "{}");
}

std::string Switch::toString() const {
    std::string s = "switch (" + (expr ? expr->toString() : "0") + ") {\n";
    for (auto& [val, body] : cases) {
        s += "case " + (val ? val->toString() : "0") + ":\n";
        s += body ? body->toString() : ";";
        s += "\n";
    }
    if (default_body) s += "default:\n" + default_body->toString() + "\n";
    s += "}";
    return s;
}

std::string Return::toString() const {
    if (value) return "return " + value->toString() + ";";
    return "return;";
}

std::string ExprStmt::toString() const {
    return expr ? expr->toString() + ";" : ";";
}

std::string VarDecl::toString() const {
    std::string s = var_type.toCString() + " " + var_name;
    if (init_expr) s += " = " + init_expr->toString();
    s += ";";
    return s;
}

std::string BinaryOp::toString() const {
    return "(" + (left ? left->toString() : "0") + " " + op + " " +
           (right ? right->toString() : "0") + ")";
}

std::string UnaryOp::toString() const {
    if (is_prefix)
        return op + (operand ? operand->toString() : "");
    return (operand ? operand->toString() : "") + op;
}

std::string Assign::toString() const {
    return (target ? target->toString() : "?") + " " + op + " " +
           (value ? value->toString() : "?");
}

std::string Call::toString() const {
    std::string s = callee_name.empty() ? (callee ? callee->toString() : "?") : callee_name;
    s += "(";
    for (size_t i = 0; i < args.size(); i++) {
        if (i > 0) s += ", ";
        s += args[i] ? args[i]->toString() : "0";
    }
    s += ")";
    return s;
}

std::string Const::toString() const {
    if (is_fp) {
        char buf[64];
        snprintf(buf, sizeof(buf), "%g", fp_val);
        return buf;
    }
    char buf[32];
    if (int_val >= 0 && int_val <= 0xFFFF)
        snprintf(buf, sizeof(buf), "%lld", (long long)int_val);
    else
        snprintf(buf, sizeof(buf), "0x%llx", (unsigned long long)int_val);
    return buf;
}

std::string StringConst::toString() const {
    std::string s = "\"";
    for (char c : value) {
        switch (c) {
            case '"': s += "\\\""; break;
            case '\\': s += "\\\\"; break;
            case '\n': s += "\\n"; break;
            case '\t': s += "\\t"; break;
            case '\r': s += "\\r"; break;
            default:
                if ((unsigned char)c >= 0x20 && (unsigned char)c < 0x7f)
                    s += c;
                else {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\x%02x", (unsigned char)c);
                    s += buf;
                }
        }
    }
    s += "\"";
    return s;
}

std::string Cast::toString() const {
    return "(" + target_type.toCString() + ")" + (expr ? expr->toString() : "0");
}

std::string MemberAccess::toString() const {
    std::string s = base ? base->toString() : "?";
    s += is_pointer ? "->" : ".";
    s += field_name;
    return s;
}

std::string Index::toString() const {
    return (array ? array->toString() : "?") + "[" +
           (index ? index->toString() : "0") + "]";
}

std::string Ternary::toString() const {
    return "(" + (condition ? condition->toString() : "0") + " ? " +
           (true_expr ? true_expr->toString() : "0") + " : " +
           (false_expr ? false_expr->toString() : "0") + ")";
}

// v10.0: New AST node toString() implementations
// 对标 Ghidra printc.cc 各节点输出格式

std::string AsmStmt::toString() const {
    std::string s = "asm(";
    if (is_volatile) s = "__asm__ __volatile__ (";
    s += "\"" + asm_string + "\"";
    if (!outputs.empty() || !inputs.empty()) {
        s += " : ";
        for (size_t i = 0; i < outputs.size(); i++) {
            if (i > 0) s += ", ";
            s += outputs[i] ? outputs[i]->toString() : "?";
        }
        s += " : ";
        for (size_t i = 0; i < inputs.size(); i++) {
            if (i > 0) s += ", ";
            s += inputs[i] ? inputs[i]->toString() : "?";
        }
    }
    s += ")";
    return s;
}

std::string Deref::toString() const {
    return "*(" + (operand ? operand->toString() : "0") + ")";
}

std::string AddressOf::toString() const {
    return "&(" + (operand ? operand->toString() : "0") + ")";
}

std::string SizeOf::toString() const {
    if (is_type) return "sizeof(" + type_arg.toCString() + ")";
    return "sizeof(" + (expr_arg ? expr_arg->toString() : "0") + ")";
}

std::string CommaExpr::toString() const {
    std::string s = "(";
    for (size_t i = 0; i < items.size(); i++) {
        if (i > 0) s += ", ";
        s += items[i] ? items[i]->toString() : "0";
    }
    s += ")";
    return s;
}

std::string EnumConstRef::toString() const {
    return enum_name.empty() ? std::to_string(value) : enum_name;
}

std::string GlobalVarRef::toString() const {
    return name.empty() ? "g_0x" + std::to_string(address) : name;
}

std::string BitFieldAccess::toString() const {
    std::string s = base ? base->toString() : "?";
    s += "." + std::to_string(offset) + ":" + std::to_string(width);
    return s;
}

std::string NewExpr::toString() const {
    std::string s = "new " + alloc_type.toCString();
    if (is_array && size_expr) {
        s += "[" + size_expr->toString() + "]";
    }
    if (!ctor_args.empty()) {
        s += "(";
        for (size_t i = 0; i < ctor_args.size(); i++) {
            if (i > 0) s += ", ";
            s += ctor_args[i] ? ctor_args[i]->toString() : "0";
        }
        s += ")";
    }
    return s;
}

std::string DeleteExpr::toString() const {
    if (is_array) return "delete[] (" + (operand ? operand->toString() : "0") + ")";
    return "delete (" + (operand ? operand->toString() : "0") + ")";
}

std::string ThrowExpr::toString() const {
    return "throw " + (operand ? operand->toString() : "0");
}

std::string InitList::toString() const {
    std::string s = "{";
    for (size_t i = 0; i < items.size(); i++) {
        if (i > 0) s += ", ";
        s += items[i] ? items[i]->toString() : "0";
    }
    s += "}";
    return s;
}

// ════════════════════════════════════════════════════════════════════
// CTreeBuilder implementation
// ════════════════════════════════════════════════════════════════════

// ════════════════════════════════════════════════════════════════════
// v3.9: Type inference integration
// ════════════════════════════════════════════════════════════════════
CType CTreeBuilder::getParamType(int paramIdx) const {
    if (paramIdx < 0) return CType::u64();
    int mreg = 100 + paramIdx;
    // Phase 5: Semantic pointer names — vm, env, this are always pointers
    auto pit = param_names_.find(mreg);
    if (pit != param_names_.end()) {
        const std::string& name = pit->second;
        if (name == "vm" || name == "env" || name == "this") {
            CType t;
            // v8.5: For 'this', extract class name from the demangled function name
            // e.g., "Minecraft::getGameMode" → "Minecraft"
            if (name == "this" && !func_name_.empty()) {
                size_t colon = func_name_.find("::");
                if (colon != std::string::npos) {
                    t.category = CType::TC_STRUCT_PTR;
                    t.struct_name = func_name_.substr(0, colon);
                    t.width = is_aarch64_ ? 8 : 4;
                    return t;
                }
            }
            // v9.26: For JNI functions (Java_*), env is JNIEnv*
            if (name == "env" && !func_name_.empty() &&
                func_name_.compare(0, 5, "Java_") == 0) {
                t.category = CType::TC_STRUCT_PTR;
                t.struct_name = "JNIEnv";
                t.width = is_aarch64_ ? 8 : 4;
                return t;
            }
            // v50.0: JNI_OnLoad vm parameter is JavaVM*
            if (name == "vm") {
                t.category = CType::TC_STRUCT_PTR;
                t.struct_name = "JavaVM";
                t.width = is_aarch64_ ? 8 : 4;
                return t;
            }
            t.category = CType::TC_POINTER;
            t.width = is_aarch64_ ? 8 : 4;
            return t;
        }
    }

    // v5.6: Use callee-inferred types (对标 r2 get_reg_type)
    // If this parameter register is used as an argument to a known function
    // (e.g., malloc, memcpy), use the inferred type.
    auto iptIt = inferred_param_types_.find(mreg);
    if (iptIt != inferred_param_types_.end()) {
        const std::string& ctype = iptIt->second.ctype;
        CType t;
        if (ctype.find('*') != std::string::npos) {
            t.category = CType::TC_POINTER;
            t.width = is_aarch64_ ? 8 : 4;
        } else if (ctype == "int" || ctype == "long" || ctype == "size_t") {
            t.category = is_aarch64_ ? CType::TC_UINT64 : CType::TC_INT32;
            t.width = is_aarch64_ ? 8 : 4;
        } else if (ctype == "void") {
            t.category = CType::TC_VOID;
            t.width = 0;
        } else {
            t.category = is_aarch64_ ? CType::TC_UINT64 : CType::TC_INT32;
            t.width = is_aarch64_ ? 8 : 4;
        }
        return t;
    }

    // Use type inference result (ssa_ver 0 = parameter value from caller)
    CType t = type_inference_.getType(mreg, 0);
    if (t.category != CType::TC_UNKNOWN) return t;
    // Default based on architecture
    return is_aarch64_ ? CType::u64() : CType::u32();
}

StmtPtr CTreeBuilder::build(const cfg::Region& region, mc::MicrocodeBlockArray& mba) {
    mba_ = &mba;
    // v4.12: Reset variable naming state for each function
    mreg_to_varname_.clear();
    var_counter_ = 0;

    // v5.0: Build COPY merge map for two-phase variable merging
    // (对标 Ghidra varcode.cc — merge OP_MOV chains into HighVariables)
    buildCopyMergeMap();

    type_inference_.setIsAArch64(is_aarch64_);  // v3.9: type inference first
    type_inference_.analyze(mba);

    // v7.0: Infer parameter types from demangled C++ name (Priority 0)
    // 对标 r2 r_type_func_guess: extract types from demangled signature
    // Example: "Class::method(int, char*, long)" types arg0=int, arg1=char*, arg2=long
    if (!func_name_.empty()) {
        auto demangledTypes = mc::CallingConvention::inferParamTypesFromDemangled(
            func_name_, is_aarch64_);
        if (!demangledTypes.empty()) {
            inferred_param_types_ = demangledTypes;
        }
    }

    // v5.6: Infer parameter types from known callee names (Priority 1)
    // Scans all CALL instructions, looks up callee in known signature DB,
    // and types the argument registers at those call sites.
    // Only fills in types not already set by demangled name inference.
    auto calleeInferred = mc::CallingConvention::inferParamTypesFromCallees(mba, is_aarch64_);
    for (auto& [mreg, ipt] : calleeInferred) {
        if (inferred_param_types_.find(mreg) == inferred_param_types_.end()) {
            inferred_param_types_[mreg] = ipt;
        }
    }

    analyzeStackSlots();  // v3.5: detect local stack variables (uses inferred types)
    trackStackAddrRegs(); // v3.6: track reg = sp + imm
    trimParameterList();  // v3.8: trim params based on actual usage
    return regionToStmt(region);
}

// ════════════════════════════════════════════════════════════════════
// v9.5: Parameter list trimming (对标 r2 afr + Ghidra ParameterAnalyzer)
//
// 多级参数裁剪:
//   1. 取所有推断来源的最大参数数量
//   2. 从参数列表中删除未被实际使用的寄存器
//   3. 如参数寄存器仅在 CALL 中作为参数传递，且是直接传递（ssa_ver==0），
//      则保留为参数（这是"透传参数"模式）
// ════════════════════════════════════════════════════════════════════
void CTreeBuilder::trimParameterList() {
    if (!mba_) return;

    actual_param_mregs_ = mc::CallingConvention::getFunctionParams(*mba_, is_aarch64_);
    actual_param_count_ = mc::CallingConvention::getFunctionParamCount(
        *mba_, func_name_, is_aarch64_);

    // v8.5: For C++ member functions, the demangled signature only counts
    // explicit params (not 'this'). Adjust to include 'this' (mreg 100).
    if (is_member_func_ && actual_param_count_ == 0) {
        actual_param_count_ = 1;
    }

    int maxArgs = is_aarch64_ ? mc::MAX_ARGS_AARCH64 : mc::MAX_ARGS_ARM32;

    // v9.5: 收集所有被实际读取的参数寄存器 (对标 r2 is_arg_used)
    // 如果参数寄存器在这个函数体内从未被读取（只被写入），则不是真正的参数。
    std::set<int> readParamRegs;
    // v9.7: 预先收集所有被定义的寄存器（用于穿透参数检测）
    std::set<int> definedRegs;
    for (auto& blk : mba_->blocks) {
        if (!blk) continue;
        for (mc::MicroInsn* insn = blk->head; insn; insn = insn->next) {
            if (insn->def_mreg >= 0) definedRegs.insert(insn->def_mreg);
        }
    }
    for (auto& blk : mba_->blocks) {
        if (!blk) continue;
        for (mc::MicroInsn* insn = blk->head; insn; insn = insn->next) {
            auto checkRead = [&](const mc::Mop& op) {
                if (op.isReg() && op.mreg >= 100 && op.mreg < (100 + maxArgs)) {
                    // v10.5: Only count reads of SSA version 0 (the original
                    // parameter value). If ssa_ver > 0, the register was
                    // redefined before this use, so this is NOT a read of
                    // the parameter — it's a read of a local variable that
                    // happens to use the same physical register.
                    // This mirrors Ghidra's HighVariable: each SSA version
                    // of a parameter register is a distinct variable, and
                    // only version 0 is the parameter.
                    if (op.ssa_ver == 0) {
                        readParamRegs.insert(op.mreg);
                    }
                }
                if (op.isMem() && op.mem_base >= 100 && op.mem_base < (100 + maxArgs)) {
                    if (op.mem_base_ssa_ver == 0) {
                        readParamRegs.insert(op.mem_base);
                    }
                }
            };
            // 检查所有操作数（除了 def_mreg，那是写入）
            // v9.7: 修复: STORE的l操作数是读取的值，不应跳过。
            // 只有def_mreg是写入，但STORE没有def_mreg。
            checkRead(insn->l);
            checkRead(insn->r);
            if (insn->opcode == mc::OP_STORE) {
                checkRead(insn->d);  // STORE的d是目标地址(读取)
            }
            if (insn->opcode == mc::OP_CALL || insn->opcode == mc::OP_ICALL) {
                // v9.7: 穿透参数检测。CALL的参数寄存器是隐式读取。
                // 如果一个参数寄存器在函数体内从未被定义(definedRegs)，
                // 但被用作CALL的参数，它是穿透参数(pass-through)。
                int callArgCount = maxArgs;
                if (insn->call_info && insn->call_info->arg_count > 0) {
                    callArgCount = insn->call_info->arg_count;
                }
                if (callArgCount > maxArgs) callArgCount = maxArgs;
                for (int a = 0; a < callArgCount; a++) {
                    int argMreg = 100 + a;
                    if (!definedRegs.count(argMreg)) {
                        readParamRegs.insert(argMreg);
                    }
                }
            }
        }
    }

    // v9.5: 删除未被实际读取的参数寄存器 (对标 r2 afr: remove unused args)
    // 但保留 ssa_ver==0 的寄存器（它们可能在函数入口处被使用但未在 scan 中捕获）
    for (int i = 0; i < actual_param_count_; i++) {
        int mreg = 100 + i;
        if (readParamRegs.find(mreg) == readParamRegs.end()) {
            // 该参数寄存器从未被读取 → 可能不是真正的参数
            // 但保留 this 指针 (mreg 100) 如果它是成员函数
            if (is_member_func_ && i == 0) continue;
            param_names_.erase(mreg);
        }
    }

    // v8.6: Remove param names for ALL arg registers beyond actual_param_count_.
    for (int i = actual_param_count_; i < maxArgs; i++) {
        int mreg = 100 + i;
        param_names_.erase(mreg);
    }
}

// ════════════════════════════════════════════════════════════════════
// v3.6: Track stack address registers
// Scans all instructions for "reg = sp + imm" patterns and records
// the mapping (mreg, ssa_ver) -> stack_offset
// ════════════════════════════════════════════════════════════════════
void CTreeBuilder::trackStackAddrRegs() {
    if (!mba_) return;
    stack_addr_regs_.clear();
    int sp = spMreg();

    for (auto& blk_up : mba_->blocks) {
        auto* blk = blk_up.get();
        if (!blk) continue;
        for (mc::MicroInsn* insn = blk->head; insn; insn = insn->next) {
            if (insn->opcode != mc::OP_ADD && insn->opcode != mc::OP_SUB) continue;
            if (insn->def_mreg < 0 || insn->def_mreg == sp) continue;

            bool lIsSp = insn->l.isReg() && insn->l.mreg == sp;
            bool rIsSp = insn->r.isReg() && insn->r.mreg == sp;
            if (!lIsSp && !rIsSp) continue;

            // Compute the offset
            int64_t offset = 0;
            if (lIsSp && insn->r.isImm()) {
                offset = insn->r.imm;
            } else if (rIsSp && insn->l.isImm()) {
                offset = insn->l.imm;
            } else {
                continue;  // sp + reg, can't track statically
            }
            if (insn->opcode == mc::OP_SUB && lIsSp) {
                // sp - imm: offset is negative
                offset = -offset;
            }

            stack_addr_regs_[{insn->def_mreg, insn->ssa_version}] = (int)offset;
        }
    }
}

// ════════════════════════════════════════════════════════════════════
// v3.5: Stack variable analysis
// Scans all blocks for sp-relative load/store, assigns var_N names
// ════════════════════════════════════════════════════════════════════
void CTreeBuilder::analyzeStackSlots() {
    if (!mba_) return;
    stack_slots_.clear();

    int sp = spMreg();

    for (auto& blk_up : mba_->blocks) {
        auto* blk = blk_up.get();
        if (!blk) continue;
        for (mc::MicroInsn* insn = blk->head; insn; insn = insn->next) {
            // Check load: l = [sp + offset]
            if (insn->opcode == mc::OP_LOAD && insn->l.mem_base == sp) {
                int off = insn->l.mem_offset;
                if (stack_slots_.find(off) == stack_slots_.end()) {
                    char buf[32];
                    snprintf(buf, sizeof(buf), "local_%x", (unsigned)std::abs(off));
                    std::string name = buf;
                    // v3.9: Try inferred type first
                    CType t = type_inference_.getStackSlotType(off);
                    if (t.category == CType::TC_UNKNOWN) {
                        // Fallback: width-based type
                        switch (insn->l.width * 8) {
                            case 32: t = CType::u32(); break;
                            case 64: t = CType::u64(); break;
                            default: t = CType::u64(); break;
                        }
                    }
                    stack_slots_[off] = {name, t};
                }
            }
            // Check store: d = [sp + offset]
            if (insn->opcode == mc::OP_STORE && insn->d.mem_base == sp) {
                int off = insn->d.mem_offset;
                if (stack_slots_.find(off) == stack_slots_.end()) {
                    char buf[32];
                    snprintf(buf, sizeof(buf), "local_%x", (unsigned)std::abs(off));
                    std::string name = buf;
                    // v3.9: Try inferred type first
                    CType t = type_inference_.getStackSlotType(off);
                    if (t.category == CType::TC_UNKNOWN) {
                        // Fallback: width-based type
                        switch (insn->d.width * 8) {
                            case 32: t = CType::u32(); break;
                            case 64: t = CType::u64(); break;
                            default: t = CType::u64(); break;
                        }
                    }
                    stack_slots_[off] = {name, t};
                }
            }
        }
    }
}

std::string CTreeBuilder::getStackVarName(int offset) const {
    auto it = stack_slots_.find(offset);
    if (it != stack_slots_.end()) return it->second.first;
    return "";
}

StmtPtr CTreeBuilder::regionToStmt(const cfg::Region& region) {
    // v4.12: Recursion depth limit to prevent infinite loops in deeply nested regions
    static int depth = 0;
    struct DepthGuard { DepthGuard() { depth++; } ~DepthGuard() { depth--; } };
    DepthGuard guard;
    if (depth > 500) {
        // v9.19: Instead of silently dropping deeply nested regions,
        // emit a comment block so the user knows something was skipped.
        auto block = std::make_unique<Block>();
        auto comment = std::make_unique<ExprStmt>();
        comment->comment = "/* WARNING: region nesting too deep (>500), structure omitted */";
        block->statements.push_back(std::move(comment));
        return block;
    }

    switch (region.type) {
        case cfg::REGION_BASIC_BLOCK: return blockToStmt(region);
        case cfg::REGION_SEQUENCE:    return sequenceToStmt(region);
        case cfg::REGION_IF_THEN:     // v4.2: fix — if-then (no else) was missing
        case cfg::REGION_IF_THEN_ELSE:return ifToStmt(region);
        case cfg::REGION_WHILE:       return whileToStmt(region);
        case cfg::REGION_DO_WHILE:    return doWhileToStmt(region);
        case cfg::REGION_FOR:         return forToStmt(region);
        case cfg::REGION_SWITCH:      return switchToStmt(region);
        case cfg::REGION_GOTO:        return gotoToStmt(region);
        case cfg::REGION_BREAK:       return std::make_unique<Break>();
        case cfg::REGION_CONTINUE:    return std::make_unique<Continue>();
        default: return nullptr;
    }
}

StmtPtr CTreeBuilder::blockToStmt(const cfg::Region& region) {
    auto block = std::make_unique<Block>();
    auto stmts = blockInsnsToStmts(region.block_id);
    for (auto& s : stmts)
        block->statements.push_back(std::move(s));
    return block;
}

StmtPtr CTreeBuilder::sequenceToStmt(const cfg::Region& region) {
    auto block = std::make_unique<Block>();
    // v24.4: 对标 Ghidra CollapseStructure::ActionBlockStructure
    // SEQUENCE 的子节点可能按 BFS/node-ID 顺序排列，而不是控制流顺序。
    // 这会导致 RETURN 块出现在 IF_ELSE 块之前（"return before code"）。
    // 修复：先收集所有子节点，然后将纯 RETURN 块移到末尾。
    // Ghidra 在 BlockGraph 排序阶段使用结构化区域图的拓扑排序，
    // 天然将 terminal RETURN 块放在序列末尾。我们的 fixpoint 规则
    // 运行后的 BFS 顺序可能违反这个排序。
    //
    // 重要：只移动纯 RETURN 的基本块（没有其他语句），不移动 if-else
    // 内部的 RETURN 或带有条件判断的 RETURN。
    struct CollectedStmt {
        StmtPtr stmt;
        bool isTerminalReturn;  // true if this is a bare return (no other statements)
    };
    std::vector<CollectedStmt> collected;
    for (auto& child : region.children) {
        auto stmt = regionToStmt(*child);
        if (stmt) {
            // Flatten nested blocks
            if (stmt->type == NT_BLOCK) {
                auto* inner = static_cast<Block*>(stmt.get());
                for (auto& s : inner->statements) {
                    bool isRet = (s && s->type == NT_RETURN);
                    collected.push_back({std::move(s), isRet});
                }
            } else {
                bool isRet = (stmt->type == NT_RETURN);
                collected.push_back({std::move(stmt), isRet});
            }
        }
    }
    
    // v24.4: 重新排序 — 非 RETURN 语句在前，纯 RETURN 语句在后
    // 但只在最外层（不在循环内部）执行此操作，因为循环体内的
    // RETURN 可能是条件性的（如 do-while 体中的 if(cond) return;）
    if (loop_stack_.empty()) {
        std::vector<CollectedStmt> nonReturn, returnStmts;
        for (auto& cs : collected) {
            if (cs.isTerminalReturn) {
                returnStmts.push_back(std::move(cs));
            } else {
                nonReturn.push_back(std::move(cs));
            }
        }
        // 将 RETURN 语句移到末尾
        for (auto& cs : returnStmts)
            nonReturn.push_back(std::move(cs));
        collected = std::move(nonReturn);
    }
    
    // 将收集的语句添加到 block
    for (auto& cs : collected) {
        if (cs.stmt)
            block->statements.push_back(std::move(cs.stmt));
    }
    
    // v6.4: Stop after an if-else where ALL paths return.
    // 参考 Ghidra ActionUnreachable: if both branches of an if-else
    // end with return/break/continue, code after the if-else is dead.
    if (!block->statements.empty()) {
        auto* last = block->statements.back().get();
        if (last->type == NT_IF) {
            auto* ifn = static_cast<const If*>(last);
            if (ifn->then_branch && ifn->else_branch) {
                // Recursively check if both branches always return
                std::function<bool(const Stmt*)> alwaysReturns =
                    [&](const Stmt* s) -> bool {
                    if (!s) return false;
                    switch (s->type) {
                        case NT_RETURN: case NT_BREAK: case NT_CONTINUE:
                            return true;
                        case NT_BLOCK: {
                            auto* b = static_cast<const Block*>(s);
                            if (b->statements.empty()) return false;
                            return alwaysReturns(b->statements.back().get());
                        }
                        case NT_IF: {
                            auto* i = static_cast<const If*>(s);
                            if (!i->then_branch || !i->else_branch) return false;
                            return alwaysReturns(i->then_branch.get()) &&
                                   alwaysReturns(i->else_branch.get());
                        }
                        default: return false;
                    }
                };
                if (alwaysReturns(ifn->then_branch.get()) &&
                    alwaysReturns(ifn->else_branch.get())) {
                    // Dead code after if-else that always returns — cut it
                    while (block->statements.size() > 1) {
                        // Keep only the if-else, remove any trailing dead code
                        auto& secondLast = block->statements[block->statements.size() - 2];
                        if (secondLast->type == NT_RETURN ||
                            secondLast->type == NT_IF) {
                            // Check if it's also an if-else that always returns
                            break;
                        }
                        // Remove the last statement (it's dead after the if-else)
                        block->statements.pop_back();
                    }
                }
            }
        }
    }
    return block;
}

StmtPtr CTreeBuilder::ifToStmt(const cfg::Region& region) {
    auto ifNode = std::make_unique<If>();

    // Build condition expression from the cbranch instruction
    if (region.condition) {
        const auto* insn = region.condition;
        // Build comparison: l cond r
        auto lhs = mopToExpr(insn->l);
        ExprPtr rhs = mopToExpr(insn->r);

        const char* op = nullptr;
        switch (insn->cond) {
            case mc::CC_EQ: op = "=="; break;
            case mc::CC_NE: op = "!="; break;
            case mc::CC_HS: op = ">="; break;
            case mc::CC_LO: op = "<"; break;
            case mc::CC_HI: op = ">"; break;
            case mc::CC_LS: op = "<="; break;
            case mc::CC_GE: op = ">="; break;
            case mc::CC_LT: op = "<"; break;
            case mc::CC_GT: op = ">"; break;
            case mc::CC_LE: op = "<="; break;
            default: op = "!="; break;
        }

        if (region.condition_negated) {
            // Negate: use inverted comparison
            switch (insn->cond) {
                case mc::CC_EQ: op = "!="; break;
                case mc::CC_NE: op = "=="; break;
                case mc::CC_HS: op = "<"; break;
                case mc::CC_LO: op = ">="; break;
                case mc::CC_HI: op = "<="; break;
                case mc::CC_LS: op = ">"; break;
                case mc::CC_GE: op = "<"; break;
                case mc::CC_LT: op = ">="; break;
                case mc::CC_GT: op = "<="; break;
                case mc::CC_LE: op = ">"; break;
                default: break;
            }
        }

        ifNode->condition = std::make_unique<BinaryOp>(op, std::move(lhs), std::move(rhs));
    }

    if (region.then_region)
        ifNode->then_branch = regionToStmt(*region.then_region);
    if (region.else_region)
        ifNode->else_branch = regionToStmt(*region.else_region);

    // v6.0: Fix break/continue generation.
    // target_block is the CBRANCH's TRUE branch target (branch taken when cond is true).
    // Fall-through (successors[1]) is the FALSE branch target.
    // When condition_negated is false: THEN = TRUE = target_block, ELSE = FALSE = fall-through
    // When condition_negated is true:  THEN = FALSE = fall-through, ELSE = TRUE = target_block
    if (region.condition && !loop_stack_.empty()) {
        const auto* cbr = region.condition;
        int target_blk = cbr->target_block;  // TRUE branch target
        const auto& lc = loop_stack_.back();

        // Get the FALSE branch (fall-through) target
        int fall_through_blk = -1;
        if (region.block_id >= 0) {
            auto* bblk = mba_->getBlock(region.block_id);
            if (bblk && bblk->successors.size() >= 2) {
                fall_through_blk = bblk->successors[1];
            }
        }

        // v6.0: When condition is NOT negated:
        // THEN = TRUE branch (target_blk), ELSE = FALSE branch (fall_through_blk)
        if (!region.condition_negated) {
            if (!ifNode->then_branch && target_blk >= 0) {
                if (target_blk == lc.exit_block) {
                    ifNode->then_branch = std::make_unique<Break>();
                } else if (target_blk == lc.header_block) {
                    ifNode->then_branch = std::make_unique<Continue>();
                }
            }
            if (!ifNode->else_branch && fall_through_blk >= 0) {
                if (fall_through_blk == lc.exit_block) {
                    ifNode->else_branch = std::make_unique<Break>();
                } else if (fall_through_blk == lc.header_block) {
                    ifNode->else_branch = std::make_unique<Continue>();
                }
            }
        } else {
            // When condition IS negated:
            // THEN = FALSE branch (fall_through_blk), ELSE = TRUE branch (target_blk)
            if (!ifNode->then_branch && fall_through_blk >= 0) {
                if (fall_through_blk == lc.exit_block) {
                    ifNode->then_branch = std::make_unique<Break>();
                } else if (fall_through_blk == lc.header_block) {
                    ifNode->then_branch = std::make_unique<Continue>();
                }
            }
            if (!ifNode->else_branch && target_blk >= 0) {
                if (target_blk == lc.exit_block) {
                    ifNode->else_branch = std::make_unique<Break>();
                } else if (target_blk == lc.header_block) {
                    ifNode->else_branch = std::make_unique<Continue>();
                }
            }
        }
        
        // v5.8: If BOTH branches are continue, the if is redundant — skip it
        if (ifNode->then_branch && ifNode->then_branch->type == NT_CONTINUE &&
            ifNode->else_branch && ifNode->else_branch->type == NT_CONTINUE) {
            return std::make_unique<Block>();
        }
        // v5.8: If BOTH branches are break, keep only the THEN break
        if (ifNode->then_branch && ifNode->then_branch->type == NT_BREAK &&
            ifNode->else_branch && ifNode->else_branch->type == NT_BREAK) {
            ifNode->else_branch = nullptr;
        }
    }

    // v4.0: Emit branch block's non-CBRANCH instructions before the IF.
    // This fixes the bug where stlxr/ldar in the branch block are dropped
    // because the IF only structures the CBRANCH and its successors.
    if (region.block_id >= 0) {
        auto preStmts = blockInsnsToStmts(region.block_id);
        if (!preStmts.empty()) {
            auto block = std::make_unique<Block>();
            for (auto& s : preStmts)
                block->statements.push_back(std::move(s));
            block->statements.push_back(std::move(ifNode));
            return block;
        }
    }

    return ifNode;
}

StmtPtr CTreeBuilder::whileToStmt(const cfg::Region& region) {
    auto whileNode = std::make_unique<While>();

    if (region.loop_condition) {
        const auto* insn = region.loop_condition;
        auto lhs = mopToExpr(insn->l);
        auto rhs = mopToExpr(insn->r);
        const char* op = "!=";
        switch (insn->cond) {
            case mc::CC_EQ: op = "=="; break;
            case mc::CC_NE: op = "!="; break;
            case mc::CC_HS: op = ">="; break;
            case mc::CC_LO: op = "<"; break;
            case mc::CC_HI: op = ">"; break;
            case mc::CC_LS: op = "<="; break;
            case mc::CC_GE: op = ">="; break;
            case mc::CC_LT: op = "<"; break;
            case mc::CC_GT: op = ">"; break;
            case mc::CC_LE: op = "<="; break;
            default: break;
        }

        // Determine if the branch target is the loop exit or loop body.
        // If the branch goes to the exit, we need to negate the condition
        // because the while loop continues while the condition is true.
        bool need_negate = false;
        if (insn->target_block >= 0 && mba_) {
            // Check if target_block is in the loop body
            bool target_in_body = false;
            if (region.loop_body) {
                // Recursively check if target_block's block_id appears in the body
                std::function<bool(const cfg::Region&)> checkBody = [&](const cfg::Region& r) -> bool {
                    if (r.type == cfg::REGION_BASIC_BLOCK && r.block_id == insn->target_block)
                        return true;
                    for (auto& child : r.children)
                        if (checkBody(*child)) return true;
                    return false;
                };
                if (region.loop_body)
                    target_in_body = checkBody(*region.loop_body);
            }
            // If target is NOT in the loop body, it's the exit → negate
            need_negate = !target_in_body;
        }

        if (need_negate) {
            // Invert the comparison operator
            if (op == std::string("==")) op = "!=";
            else if (op == std::string("!=")) op = "==";
            else if (op == std::string("<")) op = ">=";
            else if (op == std::string("<=")) op = ">";
            else if (op == std::string(">")) op = "<=";
            else if (op == std::string(">=")) op = "<";
            // For unsigned variants
            else if (op == std::string(">=u")) op = "<u";
            else if (op == std::string("<u")) op = ">=u";
            else if (op == std::string(">u")) op = "<=u";
            else if (op == std::string("<=u")) op = ">u";
        }

        whileNode->condition = std::make_unique<BinaryOp>(op, std::move(lhs), std::move(rhs));
    } else {
        // v47.0: No loop condition → while(true) loop.
        // Generate `while (1)` instead of leaving condition null
        // (which would print as `while (0)`).
        auto one = std::make_unique<Const>(1);
        whileNode->condition = std::move(one);
    }

    // v3.23: Build loop body, including header block's non-CBRANCH instructions
    // The header block (e.g., ldaxr x10, [x8]; cbnz x10, exit) has its
    // CBRANCH used as the while condition, but the other instructions
    // (ldaxr) must be emitted at the top of the loop body.
    auto bodyBlock = std::make_unique<Block>();
    if (region.header_block >= 0) {
        auto headerStmts = blockInsnsToStmts(region.header_block);
        for (auto& s : headerStmts)
            bodyBlock->statements.push_back(std::move(s));
    }

    if (region.loop_body) {
        // v3.23: Push loop context for break/continue generation
        LoopContext lc;
        lc.header_block = region.header_block;
        lc.exit_block = region.exit_block;
        loop_stack_.push_back(lc);
        auto bodyStmt = regionToStmt(*region.loop_body);
        loop_stack_.pop_back();
        if (bodyStmt) {
            if (bodyStmt->type == NT_BLOCK) {
                auto* inner = static_cast<Block*>(bodyStmt.get());
                for (auto& s : inner->statements)
                    bodyBlock->statements.push_back(std::move(s));
            } else {
                bodyBlock->statements.push_back(std::move(bodyStmt));
            }
        }
    }
    whileNode->body = std::move(bodyBlock);

    return whileNode;
}

StmtPtr CTreeBuilder::doWhileToStmt(const cfg::Region& region) {
    auto doNode = std::make_unique<DoWhile>();

    if (region.loop_condition) {
        const auto* insn = region.loop_condition;
        auto lhs = mopToExpr(insn->l);
        auto rhs = mopToExpr(insn->r);
        const char* op = "!=";
        switch (insn->cond) {
            case mc::CC_EQ: op = "=="; break;
            case mc::CC_NE: op = "!="; break;
            case mc::CC_HS: op = ">="; break;
            case mc::CC_LO: op = "<"; break;
            case mc::CC_GE: op = ">="; break;
            case mc::CC_LT: op = "<"; break;
            case mc::CC_GT: op = ">"; break;
            case mc::CC_LE: op = "<="; break;
            default: break;
        }

        // v5.9: Determine if the CBRANCH branches back to the header (continue)
        // or to the exit (break). For a do-while, the condition should be the
        // "continue" condition. If the branch target is the exit, negate.
        bool need_negate = false;
        if (insn->target_block >= 0) {
            // If target is the header, it's the "continue" branch — keep as-is.
            // If target is NOT the header (i.e., it's the exit), negate.
            need_negate = (insn->target_block != region.header_block);
        }

        if (need_negate) {
            if (op == std::string("==")) op = "!=";
            else if (op == std::string("!=")) op = "==";
            else if (op == std::string("<")) op = ">=";
            else if (op == std::string("<=")) op = ">";
            else if (op == std::string(">")) op = "<=";
            else if (op == std::string(">=")) op = "<";
        }

        doNode->condition = std::make_unique<BinaryOp>(op, std::move(lhs), std::move(rhs));
    } else {
        // v47.0: No loop condition found → default to while(1).
        // This is defensive; properly classified do-while loops should
        // always have a condition from the CBRANCH back edge.
        auto one = std::make_unique<Const>(1);
        doNode->condition = std::move(one);
    }

    // v3.23: Build loop body
    // v47.0: The loop body region ALREADY includes the header block
    // (tryDoWhileLoop includes the header in bodyRemaining per v6.1).
    // So we do NOT emit the header block separately here — it would
    // cause double emission of the header's instructions.
    // The whileToStmt function emits the header separately because
    // tryWhileLoop EXCLUDES the header from the body.
    auto bodyBlock = std::make_unique<Block>();

    if (region.loop_body) {
        fprintf(stderr, "[DBG_DW] doWhile body region: type=%d children=%zu\n",
                region.loop_body->type, region.loop_body->children.size());
        // v3.23: Push loop context for break/continue generation
        LoopContext lc;
        lc.header_block = region.header_block;
        lc.exit_block = region.exit_block;
        loop_stack_.push_back(lc);
        auto bodyStmt = regionToStmt(*region.loop_body);
        loop_stack_.pop_back();
        if (bodyStmt) {
            if (bodyStmt->type == NT_BLOCK) {
                auto* inner = static_cast<Block*>(bodyStmt.get());
                fprintf(stderr, "[DBG_DW] doWhile body: %zu stmts (inner type=%d)\n",
                        inner->statements.size(), bodyStmt->type);
                for (auto& s : inner->statements)
                    bodyBlock->statements.push_back(std::move(s));
            } else {
                fprintf(stderr, "[DBG_DW] doWhile body: single stmt type=%d\n", bodyStmt->type);
                bodyBlock->statements.push_back(std::move(bodyStmt));
            }
        } else {
            fprintf(stderr, "[DBG_DW] doWhile body: NULL bodyStmt\n");
        }
    }
    doNode->body = std::move(bodyBlock);

    // DEBUG: print inner loop body statements
    if (doNode->body && doNode->body->type == NT_BLOCK) {
        auto* b = static_cast<Block*>(doNode->body.get());
        fprintf(stderr, "[DBG_DW_DETAIL] doWhile bodyBlock has %zu stmts:\n", b->statements.size());
        for (size_t di = 0; di < b->statements.size() && di < 30; di++) {
            auto& s = b->statements[di];
            fprintf(stderr, "  [%zu] type=%d text=\"%s\"\n", di, s->type,
                    s->toString().substr(0, 80).c_str());
        }
    }

    return doNode;
}

StmtPtr CTreeBuilder::forToStmt(const cfg::Region& region) {
    // v9.0: Implement for-loop conversion from Region
    // A for-loop has: init, condition, increment, body
    auto forNode = std::make_unique<For>();

    // Push loop context for break/continue in body
    int exitBlock = region.exit_block;
    int headerBlock = region.header_block;
    loop_stack_.push_back({headerBlock, exitBlock});

    // Build init statement (if present)
    if (region.for_init) {
        const auto* insn = region.for_init;
        auto tl = mopToExpr(insn->l);
        auto tr = mopToExpr(insn->r);
        if (tl && tr) {
            auto assign = std::make_unique<Assign>(std::move(tl), std::move(tr));
            auto stmt = std::make_unique<ExprStmt>();
            stmt->expr = std::move(assign);
            forNode->init = std::move(stmt);
        }
    }

    // Build condition from loop_condition
    if (region.loop_condition) {
        const auto* insn = region.loop_condition;
        auto lhs = mopToExpr(insn->l);
        auto rhs = mopToExpr(insn->r);
        const char* op = "!=";
        switch (insn->cond) {
            case mc::CC_EQ: op = "=="; break;
            case mc::CC_NE: op = "!="; break;
            case mc::CC_HS: op = ">="; break;
            case mc::CC_LO: op = "<"; break;
            case mc::CC_HI: op = ">"; break;
            case mc::CC_LS: op = "<="; break;
            case mc::CC_GE: op = ">="; break;
            case mc::CC_LT: op = "<"; break;
            case mc::CC_GT: op = ">"; break;
            case mc::CC_LE: op = "<="; break;
            default: break;
        }

        // Check if branch target is exit (negate) or body (keep)
        bool need_negate = false;
        if (insn->target_block >= 0 && mba_) {
            std::function<bool(const cfg::Region&)> checkBody = [&](const cfg::Region& r) -> bool {
                if (r.type == cfg::REGION_BASIC_BLOCK && r.block_id == insn->target_block)
                    return true;
                for (auto& child : r.children)
                    if (checkBody(*child)) return true;
                return false;
            };
            if (region.loop_body) need_negate = !checkBody(*region.loop_body);
        }

        if (need_negate) {
            if (op == std::string("==")) op = "!=";
            else if (op == std::string("!=")) op = "==";
            else if (op == std::string("<")) op = ">=";
            else if (op == std::string("<=")) op = ">";
            else if (op == std::string(">")) op = "<=";
            else if (op == std::string(">=")) op = "<";
        }

        // v9.19: If either operand is null, fall back to a constant true
        // to avoid generating a malformed condition expression.
        if (!lhs || !rhs) {
            forNode->condition = std::make_unique<Const>(1);
        } else {
            forNode->condition = std::make_unique<BinaryOp>(op, std::move(lhs), std::move(rhs));
        }
    }

    // Build increment expression (if present)
    if (region.for_increment) {
        const auto* insn = region.for_increment;
        auto tl = mopToExpr(insn->l);
        auto tr = mopToExpr(insn->r);
        if (tl && tr) {
            forNode->increment = std::make_unique<Assign>(std::move(tl), std::move(tr));
        }
    }

    // Build body
    if (region.loop_body) {
        forNode->body = regionToStmt(*region.loop_body);
    }

    loop_stack_.pop_back();
    return forNode;
}

StmtPtr CTreeBuilder::switchToStmt(const cfg::Region& region) {
    // v8.7: Implement switch-case from region
    auto switchNode = std::make_unique<Switch>();

    // Build switch expression from microcode
    if (region.switch_expr) {
        switchNode->expr = microToExpr(region.switch_expr);
    } else {
        // Fallback: use a dummy expression
        switchNode->expr = std::make_unique<VarRef>("var_switch");
    }

    // Build case bodies
    for (auto& [caseVal, caseRegion] : region.cases) {
        auto caseExpr = std::make_unique<Const>(caseVal);
        caseExpr->result_type = CType::u64();
        StmtPtr caseBody = regionToStmt(*caseRegion);
        if (!caseBody) {
            caseBody = std::make_unique<Block>();
        }
        switchNode->cases.push_back({std::move(caseExpr), std::move(caseBody)});
    }

    // Build default body
    if (region.default_region) {
        switchNode->default_body = regionToStmt(*region.default_region);
    }

    return switchNode;
}

StmtPtr CTreeBuilder::gotoToStmt(const cfg::Region& region) {
    // v9.0: Convert goto to break/continue when in loop context
    // This matches Ghidra's behavior of recovering structured control flow
    int target = region.goto_target;

    // Check if we're inside a loop and the goto target matches loop boundaries
    for (auto it = loop_stack_.rbegin(); it != loop_stack_.rend(); ++it) {
        if (target == it->exit_block) {
            return std::make_unique<Break>();
        }
        if (target == it->header_block) {
            return std::make_unique<Continue>();
        }
    }

    // Fallback: raw goto
    auto gotoNode = std::make_unique<Goto>();
    gotoNode->label = "label_" + std::to_string(target);
    return gotoNode;
}

// ── Convert block instructions to statements ──
std::vector<StmtPtr> CTreeBuilder::blockInsnsToStmts(int block_id) {
    std::vector<StmtPtr> stmts;
    if (!mba_) return stmts;
    auto* blk = mba_->getBlock(block_id);
    if (!blk) {
        fprintf(stderr, "[DBG_BLK] blockInsnsToStmts: block %d not found\n", block_id);
        return stmts;
    }
    
    // v23.0 debug: count instructions in this block
    int insnCount = 0;
    for (auto* insn = blk->head; insn; insn = insn->next) {
        if (!(insn->iprops & mc::IPROP_DEAD)) insnCount++;
    }
    fprintf(stderr, "[DBG_BLK] block %d: %d live instructions, phi_nodes=%zu\n",
            block_id, insnCount, blk->phi_nodes.size());

    // DEBUG: print all non-dead instructions in this block
    for (auto* insn = blk->head; insn; insn = insn->next) {
        if (insn->iprops & mc::IPROP_DEAD) continue;
        fprintf(stderr, "[DBG_BLK_INS] block %d: op=%d def_mreg=%d\n", block_id, insn->opcode, insn->def_mreg);
    }

    current_block_id_ = block_id;  // for liveness analysis in buildCallExpr

    // v6.1: Return value folding (参考 Ghidra ActionReturn)
    // When a block's last data instruction defines the return register (mreg 100)
    // and is followed by OP_RET or a branch to a return-only block, fold the
    // assignment into a return statement. This converts:
    //   mov w0, #0; b ret_block   →   return 0
    //   ldr x0, [x0]; ret          →   return *(uint64_t*)p
    // Without this, the assignment generates "x = 0" (using the parameter name)
    // and the return generates "return x", which is semantically correct but
    // doesn't match Ghidra's output and causes type mismatch warnings.
    auto isReturnOnlyBlock = [this](int blockId) -> bool {
        auto* b = mba_->getBlock(blockId);
        if (!b || !b->head) return false;
        // Block must contain only OP_RET (no other data instructions)
        return b->head->opcode == mc::OP_RET && b->head->next == nullptr;
    };

    auto shouldFoldReturn = [&](const mc::MicroInsn* insn) -> bool {
        if (insn->def_mreg != 100) return false;  // not the return register
        // Case 1: Next instruction is OP_RET (same block)
        if (insn->next && insn->next->opcode == mc::OP_RET) return true;
        // Case 2: This is the last instruction in the block (insn->next is null)
        // and the block has a single successor that is a return-only block.
        // This handles fall-through to a return block.
        if (!insn->next && blk->successors.size() == 1) {
            return isReturnOnlyBlock(blk->successors[0]);
        }
        // Case 3: Next instruction is OP_GOTO (branch to another block)
        // and the block has a single successor that is a return-only block
        if (insn->next && insn->next->opcode == mc::OP_GOTO &&
            blk->successors.size() == 1) {
            return isReturnOnlyBlock(blk->successors[0]);
        }
        return false;
    };

    bool generated_return = false;

    // v10.5: Process PHI nodes from the phi_nodes vector (not the instruction list).
    // PHI nodes are stored separately in MicroBlock::phi_nodes and are never
    // linked into the head→tail instruction chain. Without this loop, PHI
    // assignments are never emitted, causing variables that receive their
    // value through a PHI merge to appear as "/* UNINIT */" in the output.
    //
    // The copy-chain map (built by buildCopyMergeMap) already handles the
    // common case: when all PHI sources and the destination resolve to the
    // same name, no assignment is needed. This loop only emits an explicit
    // assignment when the copy chain doesn't fully unify the names — i.e.,
    // when a source comes from a different register that couldn't be merged.
    //
    // This mirrors Ghidra's StructureDSA::actionBlock which processes phi
    // nodes at block entry before emitting the block's body statements.
    for (auto* phi : blk->phi_nodes) {
        if (!phi || phi->isDead()) {
            fprintf(stderr, "[DBG_PHI_EX] block %d: phi is null/dead, skipping\n", block_id);
            continue;
        }
        if (phi->def_mreg < 0) {
            fprintf(stderr, "[DBG_PHI_EX] block %d: phi def_mreg=%d, skipping\n", block_id, phi->def_mreg);
            continue;
        }
        fprintf(stderr, "[DBG_PHI_EX] block %d: phi opcode=%d def_mreg=%d ssa_ver=%d\n",
                block_id, phi->opcode, phi->def_mreg, phi->ssa_version);

        // Skip special registers (sp, lr, pc, fp, xzr)
        if (isSpecialReg(phi->def_mreg)) continue;
        if (is_aarch64_ && phi->def_mreg == 132) continue;

        // v10.5: Handle two cases:
        // 1. OP_PHI: emit assignment when copy chain doesn't unify names
        // 2. OP_MOV (simplified PHI): phiSimplification converts PHI nodes
        //    with all-same sources to OP_MOV, but leaves them in phi_nodes
        //    (not in the instruction list). These need to be processed here
        //    as regular MOV assignments, otherwise the destination variable
        //    is never assigned and appears as "/* UNINIT */".
        if (phi->opcode == mc::OP_MOV && phi->l.isReg()) {
            fprintf(stderr, "[DBG_PHI_EX] block %d: phi is simplified MOV dst_mreg=%d src_mreg=%d\n", block_id, phi->def_mreg, phi->l.mreg);
            // Simplified PHI node (was OP_PHI, now OP_MOV)
            std::string dstName = getVarName(phi->def_mreg, phi->ssa_version);
            std::string srcName = getVarName(phi->l.mreg, phi->l.ssa_ver);
            if (srcName != dstName && !srcName.empty() && srcName != "0") {
                auto lhs = makeVarRef(phi->def_mreg, phi->ssa_version);
                auto rhs = std::make_unique<VarRef>(srcName);
                CType srcType = getInferredType(phi->l.mreg, phi->l.ssa_ver);
                if (srcType.category != CType::TC_UNKNOWN) {
                    rhs->result_type = srcType;
                }
                auto stmt = std::make_unique<ExprStmt>();
                stmt->expr = std::make_unique<Assign>(std::move(lhs), std::move(rhs));
                stmts.push_back(std::move(stmt));
            }
            continue;
        }

        // Original OP_PHI handling
        if (phi->l.phi_srcs.empty()) continue;

        std::string dstName = getVarName(phi->def_mreg, phi->ssa_version);

        // Find the first source that has a different name.
        // If all sources share the same name as the destination,
        // the copy chain has already unified them — no assignment needed.
        bool needsAssign = false;
        std::string srcName;
        for (auto& [srcMreg, srcVer] : phi->l.phi_srcs) {
            if (srcVer < 0) continue;  // v9.21: skip uninitialized placeholders
            if (isSpecialReg(srcMreg)) continue;
            if (is_aarch64_ && srcMreg == 132) continue;

            std::string name = getVarName(srcMreg, srcVer);
            if (name != dstName) {
                needsAssign = true;
                srcName = name;
                break;
            }
        }

        if (needsAssign && !srcName.empty()) {
            fprintf(stderr, "[DBG_PHI_EX] block %d: phi generates ASSIGN dst=%s src=%s\n", block_id, dstName.c_str(), srcName.c_str());
            auto lhs = makeVarRef(phi->def_mreg, phi->ssa_version);
            auto rhs = std::make_unique<VarRef>(srcName);
            // Preserve type info on the RHS if available
            CType srcType = getInferredType(phi->def_mreg, phi->ssa_version);
            if (srcType.category != CType::TC_UNKNOWN) {
                rhs->result_type = srcType;
            }
            auto stmt = std::make_unique<ExprStmt>();
            stmt->expr = std::make_unique<Assign>(std::move(lhs), std::move(rhs));
            stmts.push_back(std::move(stmt));
        }
    }

    for (auto* insn = blk->head; insn; insn = insn->next) {
        // v9.18: Handle CBRANCH in the middle of a block (ARM32 conditional
        // instructions). This must be checked before shouldEmitStmt because
        // CBRANCH is filtered out by shouldEmitStmt.
        if (insn->opcode == mc::OP_CBRANCH && insn->next &&
            insn->next->opcode != mc::OP_GOTO &&
            insn->next->opcode != mc::OP_CBRANCH &&
            insn->next->opcode != mc::OP_RET &&
            insn->next->opcode != mc::OP_PHI) {

            auto ifStmt = std::make_unique<If>();

            // Build condition: CBRANCH has inverted condition, so
            // we invert it back to get the original predicate.
            // e.g. CBRANCH LE → if (GT) { ... }
            auto lhs = mopToExpr(insn->l);
            auto rhs = mopToExpr(insn->r);
            const char* op = nullptr;
            mc::CondCode origCC = invertCond(insn->cond);
            switch (origCC) {
                case mc::CC_EQ: op = "=="; break;
                case mc::CC_NE: op = "!="; break;
                case mc::CC_HS: op = ">="; break;
                case mc::CC_LO: op = "<"; break;
                case mc::CC_HI: op = ">"; break;
                case mc::CC_LS: op = "<="; break;
                case mc::CC_GE: op = ">="; break;
                case mc::CC_LT: op = "<"; break;
                case mc::CC_GT: op = ">"; break;
                case mc::CC_LE: op = "<="; break;
                default: op = "!="; break;
            }
            ifStmt->condition = std::make_unique<BinaryOp>(op, std::move(lhs), std::move(rhs));

            // Collect the "then" body: instructions after CBRANCH
            // until the next branch or end of block
            std::vector<StmtPtr> thenBody;
            for (auto* next = insn->next; next; next = next->next) {
                if (next->opcode == mc::OP_GOTO ||
                    next->opcode == mc::OP_CBRANCH ||
                    next->opcode == mc::OP_RET) {
                    break;
                }
                if (!shouldEmitStmt(next)) continue;
                if (next->def_mreg >= 0) {
                    auto lhs2 = makeVarRef(next->def_mreg, next->ssa_version);
                    auto rhs2 = microToExpr(next);
                    if (rhs2->type != NT_NULL) {
                        auto stmt = std::make_unique<ExprStmt>();
                        stmt->expr = std::make_unique<Assign>(std::move(lhs2), std::move(rhs2));
                        thenBody.push_back(std::move(stmt));
                    }
                }
            }

            auto thenBlock = std::make_unique<Block>();
            thenBlock->statements = std::move(thenBody);
            ifStmt->then_branch = std::move(thenBlock);
            ifStmt->else_branch = nullptr;

            stmts.push_back(std::move(ifStmt));
            return stmts;  // Stop processing — control flow handled
        }

        bool emit = shouldEmitStmt(insn);
        if (!emit) {
            continue;
        }

        // v3.15: Stop generating statements after OP_RET.
        // Code after return in the same block is unreachable (dead code).
        // This mirrors Ghidra's ActionUnreachable which removes unreachable blocks.
        if (insn->opcode == mc::OP_RET) {
            auto ret = std::make_unique<Return>();
            // v9.7: 使用推断的返回类型进行正确的返回语句生成
            // 对标 Ghidra actiontype.cc: 当函数返回类型为 void 时,
            // 生成 "return;" 而非 "return x;"
            //
            // 判断逻辑:
            //   1. 如果 TypeInferencePass 推断返回类型为 void → return;
            //   2. 否则,使用 mopToExpr 生成返回值表达式
            //      并根据推断的返回类型进行类型转换
            bool returnIsVoid = type_inference_.isInferredReturnVoid();

            if (returnIsVoid) {
                // 对标 Ghidra: void 函数的 return 不带值
                ret->value = nullptr;  // 生成 "return;"
            } else {
                // v9.16: Always use mopToExpr to handle all operand types.
                // After constant propagation, the optimizer may replace a
                // register-based return with a constant (MOP_IMM). Previously
                // the isReg() check caused constant returns to fall through
                // to makeVarRef(100,0) which created "uVar1 /* UNINIT */".
                // mopToExpr already handles MOP_IMM → Const, MOP_REG → VarRef,
                // MOP_STR → StringConst, MOP_GLOBAL → VarRef, etc.
                ret->value = mopToExpr(insn->l);

                // v9.7: 如果返回值表达式为空且推断返回类型为 void,
                // 确保生成 "return;"
                if (!ret->value) {
                    // mopToExpr 返回空表达式 — 保持 void return
                }
            }
            stmts.push_back(std::move(ret));
            break;  // Stop processing this block — remaining code is unreachable
        }

        switch (insn->opcode) {
            case mc::OP_MOV:
            case mc::OP_LDC:
            case mc::OP_ADD:
            case mc::OP_SUB:
            case mc::OP_MUL:
            case mc::OP_UDIV:
            case mc::OP_SDIV:
            case mc::OP_AND:
            case mc::OP_OR:
            case mc::OP_XOR:
            case mc::OP_SHL:
            case mc::OP_SHR:
            case mc::OP_SAR:
            case mc::OP_NEG:
            case mc::OP_NOT:
            case mc::OP_XDU:
            case mc::OP_XDS:
            // 对标 Ghidra P-Code comparison ops
            case mc::OP_SETZ:
            case mc::OP_SETNZ:
            case mc::OP_SETB:
            case mc::OP_SETAE:
            case mc::OP_SETA:
            case mc::OP_SETBE:
            case mc::OP_SETG:
            case mc::OP_SETGE:
            case mc::OP_SETL:
            case mc::OP_SETLE:
            case mc::OP_SETS:
            case mc::OP_SETO:
            // v5.0: 对标 Ghidra P-Code INT_REM/INT_SREM
            case mc::OP_UMOD:
            case mc::OP_SMOD:
            // v5.0: 对标 Ghidra P-Code INT_FLOAT2FLOAT/FLOAT_INT2FLOAT
            case mc::OP_F2I:
            case mc::OP_I2F:
            case mc::OP_F2F:
            // v5.0: 对标 Ghidra P-Code FLOAT_ADD/SUB/MUL/DIV
            case mc::OP_FADD:
            case mc::OP_FSUB:
            case mc::OP_FMUL:
            case mc::OP_FDIV: {
                if (insn->def_mreg < 0) break;
                // v8.6: Skip OP_MOV when source and destination are the same
                // register with the same SSA version (dead assignment).
                // This prevents "tmp0 = tmp0" from appearing in output.
                if (insn->opcode == mc::OP_MOV && insn->l.isReg() &&
                    insn->l.mreg == insn->def_mreg &&
                    insn->l.ssa_ver == insn->ssa_version) {
                    break;
                }
                // v9.11: Skip OP_MOV when source and destination resolve to
                // the same copy-chain key. This prevents self-assignments
                // like "uVar1 = uVar1" that arise from copy chain merging.
                if (insn->opcode == mc::OP_MOV && insn->l.isReg()) {
                    auto srcKey = resolveCopyChain(insn->l.mreg, insn->l.ssa_ver);
                    auto dstKey = resolveCopyChain(insn->def_mreg, insn->ssa_version);
                    if (srcKey == dstKey) break;
                }
                // v9.23: Skip OP_XDS/OP_XDU when source and destination resolve
                // to the same variable name. This prevents self-assignments
                // like "uVar1 = uVar1" from sxtw/uxtw when the same register
                // is both source and destination (same mreg, different SSA
                // version but resolves to the same variable name).
                // Note: compare getVarName() rather than resolveCopyChain()
                // because v10.5 makes getVarName() return distinct names per
                // SSA version for reused parameter registers.
                if ((insn->opcode == mc::OP_XDS || insn->opcode == mc::OP_XDU) &&
                    insn->l.isReg()) {
                    std::string srcName = getVarName(insn->l.mreg, insn->l.ssa_ver);
                    std::string dstName = getVarName(insn->def_mreg, insn->ssa_version);
                    if (srcName == dstName) break;
                }
                // v6.1: Return value folding — if this instruction defines
                // the return register and is the last data instruction before
                // OP_RET or a branch to a return-only block, fold it into
                // a return statement (参考 Ghidra ActionReturn).
                // v9.7: 当推断返回类型为 void 时不进行 folding,
                // 让该指令生成普通赋值语句,后续 OP_RET 会生成 "return;"
                // 对标 Ghidra: void 函数的返回值赋值应保留为独立语句
                if (shouldFoldReturn(insn) && !type_inference_.isInferredReturnVoid()) {
                    auto rhs = microToExpr(insn);
                    if (rhs->type != NT_NULL) {
                        fprintf(stderr, "[DBG_FOLD_RET] block %d: folding MOV into return (shouldFoldReturn)\n", block_id);
                        auto ret = std::make_unique<Return>();
                        ret->value = std::move(rhs);
                        stmts.push_back(std::move(ret));
                        generated_return = true;
                        break;  // break switch
                    }
                }
                auto lhs = makeVarRef(insn->def_mreg, insn->ssa_version);
                auto rhs = microToExpr(insn);
                // v4.12: Skip if rhs is NullExpr (unknown opcode fallback)
                if (rhs->type == NT_NULL) break;
                auto stmt = std::make_unique<ExprStmt>();
                stmt->expr = std::make_unique<Assign>(std::move(lhs), std::move(rhs));
                stmts.push_back(std::move(stmt));
                break;
            }
            case mc::OP_LOAD:
            case mc::OP_FLOAD: {
                if (insn->def_mreg < 0) break;
                // v6.1: Return value folding for LOAD (e.g., ldr x0, [x0]; ret)
                // v9.7: 当推断返回类型为 void 时不进行 folding
                if (shouldFoldReturn(insn) && !type_inference_.isInferredReturnVoid()) {
                    auto rhs = buildLoadExpr(insn);
                    if (rhs->type != NT_NULL) {
                        fprintf(stderr, "[DBG_FOLD_RET] block %d: folding LOAD into return (shouldFoldReturn)\n", block_id);
                        auto ret = std::make_unique<Return>();
                        ret->value = std::move(rhs);
                        stmts.push_back(std::move(ret));
                        generated_return = true;
                        break;  // break switch
                    }
                }
                auto lhs = makeVarRef(insn->def_mreg, insn->ssa_version);
                auto rhs = buildLoadExpr(insn);
                // fprintf(stderr, "  [OP_LOAD] rhs=%d\n", rhs->type);
                // v4.12: Skip if rhs is NullExpr
                if (rhs->type == NT_NULL) break;
                auto stmt = std::make_unique<ExprStmt>();
                stmt->expr = std::make_unique<Assign>(std::move(lhs), std::move(rhs));
                stmts.push_back(std::move(stmt));
                break;
            }
            case mc::OP_STORE:
            case mc::OP_FSTORE: {
                auto lhs = buildStoreExpr(insn);
                // v25.0: 跳过向特殊寄存器(SP/LR/PC/FP)的存储。
                // buildStoreExpr 在遇到特殊寄存器基址时返回 Const(0)，
                // 这会导致 "0 = value" 的无效 C 语句。
                // 对标 Ghidra: eliminateSpecialRegisters 在 IR 层面
                // 就消除了所有特殊寄存器的定义和使用，不会产生 C 语句。
                if (lhs && lhs->type == NT_CONST) {
                    break;
                }
                auto rhs = mopToExpr(insn->l);
                auto stmt = std::make_unique<ExprStmt>();
                stmt->expr = std::make_unique<Assign>(std::move(lhs), std::move(rhs));
                stmts.push_back(std::move(stmt));
                break;
            }
            case mc::OP_CALL:
            case mc::OP_ICALL: {
                // v4.5: Skip __sync_synchronize barriers — memory barriers
                // with no meaningful C representation
                if (insn->call_info && insn->call_info->target_name == "__sync_synchronize") {
                    break;
                }
                auto expr = buildCallExpr(insn);
                if (expr) {
                    auto stmt = std::make_unique<ExprStmt>();
                    stmt->expr = std::move(expr);
                    stmts.push_back(std::move(stmt));
                }
                // v9.8: noreturn call (abort, __builtin_trap, exit, etc.) —
                // terminates execution, skip all remaining instructions
                if (insn->call_info && !insn->call_info->has_return) {
                    return stmts;
                }
                break;
            }
            case mc::OP_GOTO:
                // v9.8: Stop processing after a goto.
                // Instructions after a goto are unreachable and would generate
                // garbage statements with undefined variables.
                return stmts;
            case mc::OP_JTBL:
                // v10.6: Jump table (switch) dispatch — skip.
                // The switch structure is handled by switchToStmt() via the
                // REGION_SWITCH region. The OP_JTBL instruction itself is
                // the switch dispatch and should not generate any C statement.
                // Instructions after OP_JTBL are unreachable (literal pool, etc.)
                return stmts;
            case mc::OP_CBRANCH: {
                // v9.18: If this CBRANCH is followed by non-branch instructions,
                // it's a conditional skip (from ARM32 predicated instructions).
                // Generate an if statement with the inverted condition.
                // The instructions after the CBRANCH form the "then" body.
                if (insn->next && insn->next->opcode != mc::OP_GOTO &&
                    insn->next->opcode != mc::OP_CBRANCH &&
                    insn->next->opcode != mc::OP_RET &&
                    insn->next->opcode != mc::OP_PHI) {

                    auto ifStmt = std::make_unique<If>();

                    // Build condition: CBRANCH has inverted condition, so
                    // we invert it back to get the original predicate.
                    // e.g. CBRANCH LE → if (GT) { ... }
                    auto lhs = mopToExpr(insn->l);
                    auto rhs = mopToExpr(insn->r);
                    const char* op = nullptr;
                    mc::CondCode origCC = invertCond(insn->cond);
                    switch (origCC) {
                        case mc::CC_EQ: op = "=="; break;
                        case mc::CC_NE: op = "!="; break;
                        case mc::CC_HS: op = ">="; break;
                        case mc::CC_LO: op = "<"; break;
                        case mc::CC_HI: op = ">"; break;
                        case mc::CC_LS: op = "<="; break;
                        case mc::CC_GE: op = ">="; break;
                        case mc::CC_LT: op = "<"; break;
                        case mc::CC_GT: op = ">"; break;
                        case mc::CC_LE: op = "<="; break;
                        default: op = "!="; break;
                    }
                    ifStmt->condition = std::make_unique<BinaryOp>(op, std::move(lhs), std::move(rhs));

                    // Collect the "then" body: instructions after CBRANCH
                    // until the next branch or end of block
                    std::vector<StmtPtr> thenBody;
                    for (auto* next = insn->next; next; next = next->next) {
                        if (next->opcode == mc::OP_GOTO ||
                            next->opcode == mc::OP_CBRANCH ||
                            next->opcode == mc::OP_RET) {
                            break;
                        }
                        if (!shouldEmitStmt(next)) continue;
                        // Build assignment from the instruction
                        if (next->def_mreg >= 0) {
                            auto lhs2 = makeVarRef(next->def_mreg, next->ssa_version);
                            auto rhs2 = microToExpr(next);
                            if (rhs2->type != NT_NULL) {
                                auto stmt = std::make_unique<ExprStmt>();
                                stmt->expr = std::make_unique<Assign>(std::move(lhs2), std::move(rhs2));
                                thenBody.push_back(std::move(stmt));
                            }
                        }
                    }

                    auto thenBlock = std::make_unique<Block>();
                    thenBlock->statements = std::move(thenBody);
                    ifStmt->then_branch = std::move(thenBlock);
                    ifStmt->else_branch = nullptr;

                    stmts.push_back(std::move(ifStmt));
                }
                // Always stop processing after a CBRANCH.
                // Control flow is handled by the structurer.
                return stmts;
            }
            case mc::OP_PHI:
                // v10.5: PHI nodes in the instruction list are handled above
                // by the phi_nodes vector loop. This case should never be
                // reached in normal operation because PHI nodes are stored
                // in MicroBlock::phi_nodes, not in the head→tail chain.
                // Keep as safety net: no-op if somehow reached.
                break;
            case mc::OP_HELPER: {
                // v9.22: Handle csel (conditional select) as a ternary expression.
                if (insn->cond != mc::CC_NONE && insn->d.isReg() &&
                    insn->l.isReg() && insn->r.isReg() && insn->def_mreg >= 0) {
                    // The comparison operands are in the previous instruction
                    // (cmp/subs/adds). Even if the comparison is marked dead
                    // by DCE, its operands are still valid for csel.
                    mc::MicroInsn* cmpInsn = insn->prev;
                    if (cmpInsn && (cmpInsn->opcode == mc::OP_SUB ||
                                    cmpInsn->opcode == mc::OP_ADD)) {
                        ExprPtr cmpL = mopToExpr(cmpInsn->l);
                        ExprPtr cmpR = mopToExpr(cmpInsn->r);
                        const char* condOp = mc::condToC(insn->cond);
                        ExprPtr condExpr = std::make_unique<BinaryOp>(
                            condOp, std::move(cmpL), std::move(cmpR));
                        ExprPtr trueVal = mopToExpr(insn->l);
                        ExprPtr falseVal = mopToExpr(insn->r);
                        auto ternary = std::make_unique<Ternary>(
                            std::move(condExpr), std::move(trueVal), std::move(falseVal));

                        auto lhs = makeVarRef(insn->def_mreg, insn->ssa_version);
                        auto stmt = std::make_unique<ExprStmt>();
                        stmt->expr = std::make_unique<Assign>(
                            std::move(lhs), std::move(ternary));
                        stmts.push_back(std::move(stmt));
                        break;
                    }
                }
                // v9.15: OP_HELPER is used for several purposes:
                // - Instructions we don't have a proper microcode for (e.g. ror, msr)
                // - Now that strd is handled natively, OP_HELPER should not
                //   terminate the block. Simply skip generating a statement
                //   and continue to the next instruction.
                // Previously this returned immediately, which caused OP_RET
                // after a strd (emitted as OP_HELPER) to be skipped.
                break;
            }
            default:
                break;
        }
        // v6.1: If return value folding generated a return, stop processing
        if (generated_return) {
            fprintf(stderr, "[DBG_BLK_STMTS] block %d: generated return, %zu stmts total\n", block_id, stmts.size());
            break;
        }
    }
    fprintf(stderr, "[DBG_BLK_STMTS] block %d: final %zu stmts", block_id, stmts.size());
    if (!stmts.empty()) {
        fprintf(stderr, " first_type=%d first_text=\"%s\"", stmts[0]->type,
                stmts[0]->toString().substr(0, 60).c_str());
    }
    fprintf(stderr, "\n");
    return stmts;
}

// ── Convert micro-instruction to expression ──
ExprPtr CTreeBuilder::microToExpr(const mc::MicroInsn* insn) {
    if (!insn) return std::make_unique<NullExpr>();

    auto makeBinOp = [](const std::string& op, ExprPtr l, ExprPtr r) {
        auto b = std::make_unique<BinaryOp>(op, std::move(l), std::move(r));
        // Phase 3: propagate result_type from left operand
        if (b->left && b->left->result_type.category != CType::TC_UNKNOWN)
            b->result_type = b->left->result_type;
        return b;
    };

    switch (insn->opcode) {
        case mc::OP_MOV:
        case mc::OP_LDC:
            return mopToExpr(insn->l);
        case mc::OP_ADD:
            return makeBinOp("+", mopToExpr(insn->l), mopToExpr(insn->r));
        case mc::OP_SUB:
            return makeBinOp("-", mopToExpr(insn->l), mopToExpr(insn->r));
        case mc::OP_MUL:
            return makeBinOp("*", mopToExpr(insn->l), mopToExpr(insn->r));
        case mc::OP_UDIV:
            return makeBinOp("/", mopToExpr(insn->l), mopToExpr(insn->r));
        case mc::OP_SDIV:
            return makeBinOp("/", mopToExpr(insn->l), mopToExpr(insn->r));
        case mc::OP_AND:
            return makeBinOp("&", mopToExpr(insn->l), mopToExpr(insn->r));
        case mc::OP_OR:
            return makeBinOp("|", mopToExpr(insn->l), mopToExpr(insn->r));
        case mc::OP_XOR:
            return makeBinOp("^", mopToExpr(insn->l), mopToExpr(insn->r));
        case mc::OP_SHL:
            return makeBinOp("<<", mopToExpr(insn->l), mopToExpr(insn->r));
        case mc::OP_SHR:
            return makeBinOp(">>", mopToExpr(insn->l), mopToExpr(insn->r));
        case mc::OP_SAR:
            return makeBinOp(">>", mopToExpr(insn->l), mopToExpr(insn->r));
        case mc::OP_NEG: {
            auto u = std::make_unique<UnaryOp>("-", mopToExpr(insn->l), true);
            if (u->operand && u->operand->result_type.category != CType::TC_UNKNOWN)
                u->result_type = u->operand->result_type;
            return u;
        }
        case mc::OP_NOT: {
            auto u = std::make_unique<UnaryOp>("~", mopToExpr(insn->l), true);
            if (u->operand && u->operand->result_type.category != CType::TC_UNKNOWN)
                u->result_type = u->operand->result_type;
            return u;
        }
        // 对标 Ghidra P-Code INT_EQUAL, INT_NOTEQUAL, INT_LESS, etc.
        // 比较指令 → 生成比较表达式 (==, !=, <, <=, >, >=)
        case mc::OP_SETZ:   // == (zero flag set)
            return makeBinOp("==", mopToExpr(insn->l), mopToExpr(insn->r));
        case mc::OP_SETNZ:  // != (zero flag not set)
            return makeBinOp("!=", mopToExpr(insn->l), mopToExpr(insn->r));
        case mc::OP_SETB:   // < (unsigned below)
            return makeBinOp("<", mopToExpr(insn->l), mopToExpr(insn->r));
        case mc::OP_SETAE:  // >= (unsigned above or equal)
            return makeBinOp(">=", mopToExpr(insn->l), mopToExpr(insn->r));
        case mc::OP_SETA:   // > (unsigned above)
            return makeBinOp(">", mopToExpr(insn->l), mopToExpr(insn->r));
        case mc::OP_SETBE:  // <= (unsigned below or equal)
            return makeBinOp("<=", mopToExpr(insn->l), mopToExpr(insn->r));
        case mc::OP_SETG:   // > (signed greater)
            return makeBinOp(">", mopToExpr(insn->l), mopToExpr(insn->r));
        case mc::OP_SETGE:  // >= (signed greater or equal)
            return makeBinOp(">=", mopToExpr(insn->l), mopToExpr(insn->r));
        case mc::OP_SETL:   // < (signed less)
            return makeBinOp("<", mopToExpr(insn->l), mopToExpr(insn->r));
        case mc::OP_SETLE:  // <= (signed less or equal)
            return makeBinOp("<=", mopToExpr(insn->l), mopToExpr(insn->r));
        case mc::OP_SETS:   // < 0 (sign flag set) → check if negative
            return makeBinOp("<", mopToExpr(insn->l),
                             std::make_unique<Const>(0));
        case mc::OP_SETO:   // overflow flag set → fallback to 0
            return std::make_unique<Const>(0);
        // v5.0: 对标 Ghidra P-Code INT_REM/INT_SREM
        case mc::OP_UMOD:
            return makeBinOp("%", mopToExpr(insn->l), mopToExpr(insn->r));
        case mc::OP_SMOD:
            return makeBinOp("%", mopToExpr(insn->l), mopToExpr(insn->r));
        // v5.0: 对标 Ghidra P-Code FLOAT conversions
        case mc::OP_F2I: {
            auto inner = mopToExpr(insn->l);
            if (inner) {
                auto cast = std::make_unique<Cast>(CType::i64(), std::move(inner));
                cast->result_type = CType::i64();
                return cast;
            }
            return std::make_unique<NullExpr>();
        }
        case mc::OP_I2F: {
            auto inner = mopToExpr(insn->l);
            if (inner) {
                auto cast = std::make_unique<Cast>(CType::f64(), std::move(inner));
                cast->result_type = CType::f64();
                return cast;
            }
            return std::make_unique<NullExpr>();
        }
        case mc::OP_F2F: {
            // float to float (width change) → just pass through
            return mopToExpr(insn->l);
        }
        // v5.0: 对标 Ghidra P-Code FLOAT_ADD/SUB/MUL/DIV
        case mc::OP_FADD:
            return makeBinOp("+", mopToExpr(insn->l), mopToExpr(insn->r));
        case mc::OP_FSUB:
            return makeBinOp("-", mopToExpr(insn->l), mopToExpr(insn->r));
        case mc::OP_FMUL:
            return makeBinOp("*", mopToExpr(insn->l), mopToExpr(insn->r));
        case mc::OP_FDIV:
            return makeBinOp("/", mopToExpr(insn->l), mopToExpr(insn->r));
        // 对标 Ghidra P-Code INT_ZEXT, INT_SEXT
        // 零扩展/符号扩展 → 生成 Cast 表达式
        case mc::OP_XDU: {
            // Zero-extend: cast to wider unsigned type
            auto inner = mopToExpr(insn->l);
            if (inner) {
                auto cast = std::make_unique<Cast>(CType::u64(), std::move(inner));
                cast->result_type = CType::u64();
                return cast;
            }
            return std::make_unique<NullExpr>();
        }
        case mc::OP_XDS: {
            // Sign-extend: cast to wider signed type
            auto inner = mopToExpr(insn->l);
            if (inner) {
                auto cast = std::make_unique<Cast>(CType::i64(), std::move(inner));
                cast->result_type = CType::i64();
                return cast;
            }
            return std::make_unique<NullExpr>();
        }
        case mc::OP_LOAD:
        case mc::OP_FLOAD:
            return buildLoadExpr(insn);
        case mc::OP_JTBL: {
            // v10.6: Jump table (switch) — extract the switch expression.
            // The switch index register is stored in the `r` operand
            // (populated by handleAarch64JumpTable).
            // For ARM32 (ldr pc, [pc, rN, lsl #2]), it's in `l` (idxMreg).
            // For TBB/TBH, it's in the memory operand's index register.
            if (insn->r.isReg() && insn->r.mreg >= 0) {
                return mopToExpr(insn->r);
            }
            if (insn->l.isReg() && insn->l.mreg >= 0) {
                return mopToExpr(insn->l);
            }
            if (insn->l.isMem() && insn->l.mem_index >= 0) {
                return mopToExpr(mc::Mop::reg(insn->l.mem_index, insn->l.width));
            }
            // Fallback: use a dummy variable
            return std::make_unique<VarRef>("var_switch");
        }
        default:
            return std::make_unique<NullExpr>();
    }
}

// ── Convert operand to expression ──
ExprPtr CTreeBuilder::mopToExpr(const mc::Mop& mop) {
    switch (mop.type) {
        case mc::MOP_REG: {
            // v5.2: Return Const(0) for special registers (sp/pc/lr/fp) and
            // the AArch64 zero register (xzr/wzr, mreg==132). Previously these
            // produced VarRef("0") via getVarName(), which prevented
            // eliminateConstantConditions from recognizing "0 == 0" as a
            // constant condition — causing dead "if (0 == 0)" patterns.
            if (isSpecialReg(mop.mreg) || (is_aarch64_ && mop.mreg == 132)) {
                auto c = std::make_unique<Const>();
                c->int_val = 0;
                return c;
            }
            return makeVarRef(mop.mreg, mop.ssa_ver);
        }
        case mc::MOP_IMM: {
            auto c = std::make_unique<Const>();
            c->int_val = mop.imm;
            return c;
        }
        case mc::MOP_STR:
            return std::make_unique<StringConst>(mop.str_val);
        case mc::MOP_GLOBAL: {
            // v54.0: Return GlobalVarRef instead of VarRef so that
            // the printer handles it as a global symbol (avoids being
            // declared as a local variable and enables symbol resolution).
            auto gv = std::make_unique<GlobalVarRef>();
            gv->address = (uint64_t)mop.global_addr;
            if (mba_) {
                auto it = mba_->global_names.find(mop.global_addr);
                if (it != mba_->global_names.end() && !it->second.empty()) {
                    std::string name = it->second;
                    if (name.size() > 6 && name.substr(0, 6) == "got_s_") {
                        char buf[48];
                        snprintf(buf, sizeof(buf), "got_0x%llx",
                                 (unsigned long long)mop.global_addr);
                        gv->name = buf;
                    } else {
                        gv->name = name;
                    }
                }
            }
            if (gv->name.empty()) {
                char buf[32];
                snprintf(buf, sizeof(buf), "g_%llx", (unsigned long long)mop.global_addr);
                gv->name = buf;
            }
            return gv;
        }
        case mc::MOP_STKVAR: {
            char buf[32];
            snprintf(buf, sizeof(buf), "local_%x", (unsigned)(mop.stk_offset & 0xFFFFFF));
            return std::make_unique<VarRef>(buf);
        }
        case mc::MOP_MEM: {
            // v9.12: Handle absolute address case (no base register)
            // v55.0: Check if the absolute address is a known global variable.
            // If so, emit a GlobalVarRef instead of *(addr) to produce
            // readable symbol names like "g_pthread_create" instead of
            // "*0x1234" or "*0" which is meaningless.
            if (mop.mem_base < 0) {
                uint64_t abs_addr = (uint64_t)mop.mem_offset;
                // v58.0: If the absolute address is 0, return Const(0) instead
                // of *0. A null pointer dereference is never valid decompiler
                // output — it indicates an unresolved memory operand.
                if (abs_addr == 0) {
                    fprintf(stderr, "[DBG_0] mopToExpr MOP_MEM base<0 off=0 -> Const(0)\n");
                    return std::make_unique<Const>(0);
                }
                if (mba_ && mba_->global_names.count(abs_addr)) {
                    auto gv = std::make_unique<GlobalVarRef>();
                    gv->address = abs_addr;
                    gv->name = mba_->global_names[abs_addr];
                    return gv;
                }
                auto addr2 = std::make_unique<Const>(mop.mem_offset);
                fprintf(stderr, "[DBG_STAR] SRC1 mopToExpr MOP_MEM base<0 off=%lld -> *Const(%lld)\n",
                        (long long)mop.mem_offset, (long long)mop.mem_offset);
                return std::make_unique<UnaryOp>("*", std::move(addr2), true);
            }
            // v54.0: If sp-relative and we have a stack slot, use local_xx name
            if (mop.mem_base == spMreg() || mop.mem_base == fpMreg()) {
                std::string sname = getStackVarName(mop.mem_offset);
                if (!sname.empty()) {
                    return std::make_unique<VarRef>(sname);
                }
            }
            // v56.0: Special register (pc/lr) as base — this means the
            // PC-relative load was not properly resolved. Return Const(0)
            // to avoid generating nonsensical *0 or *(0 + -20) in output.
            if (isSpecialReg(mop.mem_base)) {
                fprintf(stderr, "[DBG_0] mopToExpr MOP_MEM special reg %d -> Const(0)\n", mop.mem_base);
                return std::make_unique<Const>(0);
            }
            auto base = makeVarRef(mop.mem_base, mop.mem_base_ssa_ver);
            // DEBUG: trace *0 generation
            if (base && base->type == ctree::NT_VAR_REF) {
                auto* vr = static_cast<const ctree::VarRef*>(base.get());
                if (vr->name == "0") {
                    fprintf(stderr, "[DBG_0] mopToExpr MOP_MEM fallback base=%d off=%lld -> VarRef(\"0\")\n",
                            mop.mem_base, (long long)mop.mem_offset);
                }
            }
            // DEBUG: trace *Const(0) generation
            if (base && base->type == ctree::NT_CONST) {
                auto* c = static_cast<const ctree::Const*>(base.get());
                if (c->int_val == 0 && !c->is_fp) {
                    fprintf(stderr, "[DBG_0] mopToExpr MOP_MEM fallback base=%d off=%lld -> Const(0)\n",
                            mop.mem_base, (long long)mop.mem_offset);
                }
            }
            if (mop.mem_offset != 0) {
                auto off = std::make_unique<Const>();
                off->int_val = mop.mem_offset;
                fprintf(stderr, "[DBG_STAR] SRC2 mopToExpr MOP_MEM base=%d off=%lld -> *(base + %lld)\n",
                        mop.mem_base, (long long)mop.mem_offset, (long long)mop.mem_offset);
                return std::make_unique<UnaryOp>("*",
                    std::make_unique<BinaryOp>("+", std::move(base), std::move(off)), true);
            }
            fprintf(stderr, "[DBG_STAR] SRC3 mopToExpr MOP_MEM base=%d off=0 -> *base\n", mop.mem_base);
            return std::make_unique<UnaryOp>("*", std::move(base), true);
        }
        default:
            return std::make_unique<Const>(0);
    }
}

// ── Build load expression: *(type*)(base + offset) ──
ExprPtr CTreeBuilder::buildLoadExpr(const mc::MicroInsn* insn) {
    const auto& mem = insn->l;

    // v3.5: If sp-relative, use stack variable name
    if (isSpRelative(mem)) {
        std::string varName = getStackVarName(mem.mem_offset);
        if (!varName.empty()) {
            return std::make_unique<VarRef>(varName);
        }
    }

    // v3.6: If base register is a tracked stack address register,
    // compute total offset = tracked_offset + mem_offset and use var_N
    // v4.0: Skip atomic ops — they access shared memory, not local stack vars
    if (mem.mem_base >= 0 && !(insn->iprops & mc::IPROP_ATOMIC)) {
        int baseOff = getStackAddrOffset(mem.mem_base, mem.mem_base_ssa_ver);
        // Also try the SSA version from the instruction if available
        if (baseOff == INT_MIN) {
            // Try without version (conservative)
            for (auto& [key, off] : stack_addr_regs_) {
                if (key.first == mem.mem_base) {
                    baseOff = off;
                    break;
                }
            }
        }
        if (baseOff != INT_MIN) {
            int totalOff = baseOff + mem.mem_offset;
            std::string varName = getStackVarName(totalOff);
            if (!varName.empty()) {
                return std::make_unique<VarRef>(varName);
            }
            // v3.15: Only create new slot if this offset doesn't already exist.
            // Don't overwrite existing slots (prevents type conflicts / duplicate decls).
            char buf[32];
            snprintf(buf, sizeof(buf), "local_%x", (unsigned)std::abs(totalOff));
            std::string name = buf;
            if (stack_slots_.find(totalOff) == stack_slots_.end()) {
                stack_slots_[totalOff] = {name, CType::u64()};
            }
            return std::make_unique<VarRef>(name);
        }
    }

    // v58.0: Special register (pc/lr) as base — the PC-relative load was
    // not properly resolved. Return Const(0) to avoid *0 or *(0 + -20).
    if (mem.mem_base >= 0 && isSpecialReg(mem.mem_base)) {
        fprintf(stderr, "[DBG_0] buildLoadExpr mreg=%d -> Const(0)\n", mem.mem_base);
        return std::make_unique<Const>(0);
    }

    // v4.5: MOP_GLOBAL in load instruction means loading from a global variable.
    // Return the variable reference directly (e.g. "global_18fdc" not "*global_18fdc").
    if (mem.type == mc::MOP_GLOBAL) {
        return mopToExpr(mem);
    }

    // Build address expression
    // v9.12: When mem_base < 0 (no base register), the address is the
    // absolute address stored in mem_offset. Previously we incorrectly
    // generated Const(0), producing spurious "*(type*)(0)" null-pointer
    // dereferences for absolute-address memory accesses.
    ExprPtr addr;
    if (mem.mem_base >= 0) {
        auto base = makeVarRef(mem.mem_base, mem.mem_base_ssa_ver);
        if (mem.mem_offset != 0) {
            auto off = std::make_unique<Const>();
            off->int_val = mem.mem_offset;
            addr = std::make_unique<BinaryOp>("+", std::move(base), std::move(off));
        } else {
            addr = std::move(base);
        }
    } else {
        // Absolute address: use mem_offset directly.
        // The CPrinter's resolveConstAddr() will resolve known addresses
        // to global variable names or string references.
        addr = std::make_unique<Const>(mem.mem_offset);
        if (mem.mem_offset == 0) {
            fprintf(stderr, "[DBG_0] buildLoadExpr abs addr 0 -> *Const(0)\n");
        }
    }

    // Build: *(uintN_t*)(addr)
    int bits = mem.width * 8;
    CType load_type;
    switch (bits) {
        // v9.24: Use precise types for sub-word loads instead of
        // always using i32/u32, which loses type information for
        // ldrb/ldrh/ldrsb/ldrsh.
        case 8:  load_type = mem.mem_signed ? CType::i8() : CType::u8(); break;
        case 16: load_type = mem.mem_signed ? CType::i16() : CType::u16(); break;
        case 32: load_type = mem.mem_signed ? CType::i32() : CType::u32(); break;
        case 64: load_type = CType::u64(); break;
        default: load_type = CType::u64(); break;
    }

    // Cast address to typed pointer, then dereference
    auto cast = std::make_unique<Cast>(CType::ptrTo(load_type), std::move(addr));
    fprintf(stderr, "[DBG_STAR] SRC4 buildLoadExpr\n");
    return std::make_unique<UnaryOp>("*", std::move(cast), true);
}

// ── Build store expression: *(type*)(base + offset) ──
ExprPtr CTreeBuilder::buildStoreExpr(const mc::MicroInsn* insn) {
    const auto& mem = insn->d;

    // v3.5: If sp-relative, use stack variable name
    if (isSpRelative(mem)) {
        std::string varName = getStackVarName(mem.mem_offset);
        if (!varName.empty()) {
            return std::make_unique<VarRef>(varName);
        }
    }

    // v3.6: If base register is a tracked stack address register
    // v4.0: Skip atomic ops — they access shared memory, not local stack vars
    if (mem.mem_base >= 0 && !(insn->iprops & mc::IPROP_ATOMIC)) {
        int baseOff = getStackAddrOffset(mem.mem_base, 0);
        if (baseOff == INT_MIN) {
            for (auto& [key, off] : stack_addr_regs_) {
                if (key.first == mem.mem_base) {
                    baseOff = off;
                    break;
                }
            }
        }
        if (baseOff != INT_MIN) {
            int totalOff = baseOff + mem.mem_offset;
            std::string varName = getStackVarName(totalOff);
            if (!varName.empty()) {
                return std::make_unique<VarRef>(varName);
            }
            // v3.15: Only create new slot if this offset doesn't already exist.
            char buf2[32];
            snprintf(buf2, sizeof(buf2), "local_%x", (unsigned)std::abs(totalOff));
            std::string name = buf2;
            if (stack_slots_.find(totalOff) == stack_slots_.end()) {
                stack_slots_[totalOff] = {name, CType::u64()};
            }
            return std::make_unique<VarRef>(name);
        }
    }

    // v58.0: Special register (pc/lr) as base — the PC-relative store was
    // not properly resolved. Return Const(0) to avoid *0 or *(0 + -20).
    if (mem.mem_base >= 0 && isSpecialReg(mem.mem_base)) {
        return std::make_unique<Const>(0);
    }

    // v9.12: Same fix as buildLoadExpr — when mem_base < 0, use
    // mem_offset as the absolute address instead of Const(0).
    // v9.24: Use mem_base_ssa_ver (set during SSA renaming) instead of
    // hardcoding 0, to match buildLoadExpr and mopToExpr behavior.
    ExprPtr addr;
    if (mem.mem_base >= 0) {
        auto base = makeVarRef(mem.mem_base, mem.mem_base_ssa_ver);
        if (mem.mem_offset != 0) {
            auto off = std::make_unique<Const>();
            off->int_val = mem.mem_offset;
            addr = std::make_unique<BinaryOp>("+", std::move(base), std::move(off));
        } else {
            addr = std::move(base);
        }
    } else {
        addr = std::make_unique<Const>(mem.mem_offset);
    }

    // Determine store type based on width
    int sbits = mem.width * 8;
    CType store_type;
    switch (sbits) {
        case 8:  store_type = mem.mem_signed ? CType::i32() : CType::u32(); break;
        case 16: store_type = mem.mem_signed ? CType::i32() : CType::u32(); break;
        case 32: store_type = mem.mem_signed ? CType::i32() : CType::u32(); break;
        case 64: store_type = CType::u64(); break;
        default: store_type = CType::u64(); break;
    }

    auto cast = std::make_unique<Cast>(CType::ptrTo(store_type), std::move(addr));
    fprintf(stderr, "[DBG_STAR] SRC5 buildStoreExpr\n");
    return std::make_unique<UnaryOp>("*", std::move(cast), true);
}

// ── Build call expression ──
ExprPtr CTreeBuilder::buildCallExpr(const mc::MicroInsn* insn) {
    // Helper lambda: resolve a target address to a function name
    // Priority: 1) call_info->target_name, 2) global_names, 3) secondary_names, 4) sub_XXXX
    auto resolveCallTarget = [this](uint64_t target_addr) -> std::string {
        // Check global_names first (ELF symbols, PLT stubs)
        if (mba_ && target_addr != 0) {
            auto it = mba_->global_names.find(target_addr);
            if (it != mba_->global_names.end() && !it->second.empty())
                return it->second;
        }
        // Check secondary names (externally injected symbol map)
        if (secondary_names_ && target_addr != 0) {
            auto it = secondary_names_->find(target_addr);
            if (it != secondary_names_->end() && !it->second.empty())
                return it->second;
        }
        char buf[32];
        snprintf(buf, sizeof(buf), "func_%llx", (unsigned long long)target_addr);
        return buf;
    };

    auto call = std::make_unique<Call>();

    if (insn->call_info) {
        if (!insn->call_info->target_name.empty()) {
            call->callee_name = insn->call_info->target_name;
        } else if (insn->call_info->is_indirect) {
            // v9.0: Try to resolve indirect call via vtable consensus
            // The emitter may have resolved the target via reg_state_ RESOLVED_SYM
            // If not, try the indirect call resolver
            bool resolved = false;
            if (icall_resolver_ && insn->l.isReg()) {
                // Check if this register was loaded from a vtable offset
                // The emitter already marks vtable function loads as RESOLVED_SYM
                // so this is a fallback for cases where the emitter didn't resolve
                auto resolved_call = icall_resolver_->resolveByAddress(insn->target_addr);
                if (resolved_call.is_resolved) {
                    call->callee_name = resolved_call.target_name;
                    resolved = true;
                }
            }
            if (!resolved) {
                // Indirect call: blr xN / blx rN
                call->callee_name = "(*" + getVarName(insn->l.mreg, insn->l.ssa_ver) + ")";
            }
        } else {
            // Direct call: resolve target address
            call->callee_name = resolveCallTarget(insn->target_addr);
        }
    } else {
        call->callee_name = resolveCallTarget(insn->target_addr);
    }

    // Arguments: use calling convention analysis to determine count
    // Priority: call_info->arg_count > signature map > liveness analysis > default(4)
    std::string calleeName = call->callee_name;
    int argCount = 4;  // default
    bool returnsVoid = false;

    // v4.0: If call_info explicitly specifies arg_count (>=0), use it directly.
    // This handles barriers (__sync_synchronize) and other no-arg builtins.
    if (insn->call_info && insn->call_info->arg_count >= 0) {
        argCount = insn->call_info->arg_count;
        returnsVoid = !insn->call_info->has_return;
    }
    // v39.0: Normalize callee name for signature map lookup.
    // The callee_name is the demangled name (e.g.,
    // "std::basic_string<char,...>::basic_string(char const*,...)")
    // but the signature map may use a simplified name.
    // We try multiple variants:
    //   1. Full demangled name (as-is)
    //   2. Name without parameter list (strip from first '(')
    //   3. Name with std::string replacing std::basic_string<char,...>
    auto normalizeSigName = [](const std::string& name) -> std::vector<std::string> {
        std::vector<std::string> variants;
        variants.push_back(name);  // 1. Full name

        // 2. Strip parameter list
        int angleDepth = 0;
        for (size_t i = 0; i < name.size(); i++) {
            if (name[i] == '<') angleDepth++;
            else if (name[i] == '>') angleDepth--;
            else if (name[i] == '(' && angleDepth == 0) {
                variants.push_back(name.substr(0, i));
                break;
            }
        }

        // 3. Replace std::basic_string<char,...> with std::string
        // The demangled name contains "std::basic_string<char, std::char_traits<char>, std::allocator<char> >"
        // Try replacing with "std::string"
        {
            std::string s = name;
            // Try various patterns
            std::string patterns[] = {
                "std::basic_string<char, std::char_traits<char>, std::allocator<char> >",
                "std::__cxx11::basic_string<char, std::char_traits<char>, std::allocator<char> >"
            };
            for (auto& pat : patterns) {
                size_t pos = s.find(pat);
                if (pos != std::string::npos) {
                    std::string replaced = s;
                    replaced.replace(pos, pat.size(), "std::string");
                    variants.push_back(replaced);
                    // Also strip parameter list from the replaced version
                    int ad2 = 0;
                    for (size_t j = 0; j < replaced.size(); j++) {
                        if (replaced[j] == '<') ad2++;
                        else if (replaced[j] == '>') ad2--;
                        else if (replaced[j] == '(' && ad2 == 0) {
                            variants.push_back(replaced.substr(0, j));
                            break;
                        }
                    }
                    break;
                }
            }
        }

        // 4. Also try the mangled name pattern for std::string functions
        // The demangled name contains "basic_string" which demangles from "_ZNSs"
        if (name.find("basic_string") != std::string::npos) {
            // Try a simplified short name
            std::string shortName = name;
            // Strip everything after the first '('
            for (size_t i = 0; i < shortName.size(); i++) {
                if (shortName[i] == '(') {
                    shortName = shortName.substr(0, i);
                    break;
                }
            }
            // Also try with "std::string" prefix
            std::string sname = "std::string" + shortName.substr(shortName.rfind("::"));
            variants.push_back(sname);
        }

        return variants;
    };

    // Check signature map for known functions
    if (!calleeName.empty() && !sig_map_.empty()) {
        auto nameVariants = normalizeSigName(calleeName);
        for (auto& variant : nameVariants) {
            auto sigIt = sig_map_.find(variant);
            if (sigIt != sig_map_.end()) {
                auto parsed = mc::CallingConvention::parseSignature(sigIt->second);
                if (parsed.valid) {
                    argCount = parsed.argCount();
                    returnsVoid = parsed.is_void_return;
                    // For variadic functions, add extra args from liveness
                    if (parsed.is_variadic) {
                        auto* blk = (current_block_id_ >= 0) ? mba_->getBlock(current_block_id_) : nullptr;
                        int liveCount = mc::CallingConvention::countArgsFromLiveness(insn, blk, is_aarch64_);
                        if (liveCount > argCount) argCount = liveCount;
                    }
                    break;
                }
            }
        }
    }

    // If no signature found and no explicit arg_count, use liveness analysis
    if (argCount == 4 && (!insn->call_info || insn->call_info->arg_count < 0)) {
        // Check if any signature map variant matched
        bool sigFound = false;
        if (!calleeName.empty() && !sig_map_.empty()) {
            auto nameVariants = normalizeSigName(calleeName);
            for (auto& variant : nameVariants) {
                if (sig_map_.find(variant) != sig_map_.end()) {
                    sigFound = true;
                    break;
                }
            }
        }
        if (!sigFound) {
            auto* blk = (current_block_id_ >= 0) ? mba_->getBlock(current_block_id_) : nullptr;
            int liveCount = mc::CallingConvention::countArgsFromLiveness(insn, blk, is_aarch64_);
            // v9.23: When liveness returns 0, use 0 args instead of the default 4.
            // This prevents UNINIT variables for unknown calls with no args set up.
            if (liveCount >= 0) argCount = liveCount;
        }
    }

    // v9.8: noreturn calls (abort, exit, etc.) take 0 arguments.
    // countArgsFromLiveness may return 0 for these, but the default argCount=4
    // would override it. Explicitly set argCount=0 for noreturn calls.
    if (insn->call_info && !insn->call_info->has_return) {
        argCount = 0;
        returnsVoid = true;
    }

    // v8.6: Fallback void return check — use KnownSymbolsDB for well-known
    // void functions (free, delete, memset, etc.) even if not in sig_map_.
    if (!returnsVoid && !calleeName.empty()) {
        if (mc::CallingConvention::returnsVoid(calleeName)) {
            returnsVoid = true;
        } else if (db_) {
            const symdb::KnownSymbol* sym = db_->lookupByName(calleeName);
            if (sym && sym->has_return && sym->return_type == "void") {
                returnsVoid = true;
            }
        }
    }

    int maxArgs = is_aarch64_ ? mc::MAX_ARGS_AARCH64 : mc::MAX_ARGS_ARM32;
    if (argCount > maxArgs) argCount = maxArgs;
    if (argCount < 0) argCount = 0;

    // Only pass the actual arguments
    // v4.5: Check call_info->string_args for resolved string constants.
    // When handleCall detected a string constant in an argument register
    // (e.g. "add r0, pc, r0" resolved to "MinecraftClient::init"),
    // use the string literal directly instead of the variable name.
    //
    // v8.6: Scan backwards from the call instruction to find the correct
    // SSA version for each argument register. Previously we always used
    // ssa_ver=0, which was wrong when parameters were modified before the call.
    // Now we find the most recent definition of each arg register.
    std::vector<int> argSsaVers(argCount, 0);  // default: ssa_ver=0
    auto* blk = (current_block_id_ >= 0) ? mba_->getBlock(current_block_id_) : nullptr;
    if (blk) {
        // Scan backwards from the call to find the most recent definition
        // of each argument register
        for (auto* scan = insn->prev; scan; scan = scan->prev) {
            for (int i = 0; i < argCount; i++) {
                int argMreg = 100 + i;
                if (argSsaVers[i] == 0 && scan->def_mreg == argMreg) {
                    argSsaVers[i] = scan->ssa_version;
                }
            }
            // Stop if all versions are found
            bool allFound = true;
            for (int i = 0; i < argCount; i++) {
                if (argSsaVers[i] == 0) { allFound = false; break; }
            }
            if (allFound) break;
        }
        // v41.0: 如果向后扫描未找到参数寄存器的定义，检查前驱块的尾部指令。
        // 在 ARM 中，参数寄存器经常在前驱块中设置（例如，MOV r0, #value 在前驱块，
        // BL func 在当前块）。如果未找到，argSsaVers 保持为 0，
        // 会导致使用函数参数名（如 vm）作为调用参数，这是错误的。
        // 参考 IDA 的处理方式：追踪寄存器定义跨块边界。
        for (int i = 0; i < argCount; i++) {
            if (argSsaVers[i] == 0 && blk->predecessors.size() >= 1) {
                int argMreg = 100 + i;
                for (int predId : blk->predecessors) {
                    auto* predBlk = mba_->getBlock(predId);
                    if (!predBlk || !predBlk->tail) continue;
                    // Scan backwards from predecessor's tail
                    for (auto* scan = predBlk->tail; scan; scan = scan->prev) {
                        if (scan->def_mreg == argMreg) {
                            argSsaVers[i] = scan->ssa_version;
                            break;
                        }
                    }
                    if (argSsaVers[i] != 0) break;
                }
            }
        }
    }
    
    for (int i = 0; i < argCount; i++) {
        // Check if this argument has a resolved string constant
        if (insn->call_info && insn->call_info->string_args.count(i)) {
            auto strLit = std::make_unique<StringConst>();
            strLit->value = insn->call_info->string_args[i];
            call->args.push_back(std::move(strLit));
        } else {
            int arg_mreg = 100 + i;  // x0-x7 / r0-r3
            call->args.push_back(makeVarRef(arg_mreg, argSsaVers[i]));
        }
    }

    // If call has a return value and function doesn't return void, wrap in assignment
    if (insn->def_mreg >= 0 && !returnsVoid) {
        auto lhs = makeVarRef(insn->def_mreg, insn->ssa_version);
        auto assign = std::make_unique<Assign>(std::move(lhs), std::move(call));
        // Phase 3: Infer return type from KnownSymbolsDB
        if (db_) {
            const symdb::KnownSymbol* sym = db_->lookupByName(calleeName);
            if (sym && sym->has_return && !sym->return_type.empty()) {
                CType retType = CType::unknown();
                retType = CType::fromString(sym->return_type);
                if (retType.category != CType::TC_UNKNOWN) {
                    assign->result_type = retType;
                    auto* callNode = static_cast<Call*>(assign->value.get());
                    if (callNode) callNode->result_type = retType;
                }
            }
        }
        return assign;
    }

    return call;
}

// ── Get variable name for mreg + ssa_ver ──
// v9.11: Copy-chain based variable merging (对标 Ghidra HighVariable)
// Instead of union-find on mregs (which causes SSA version collisions),
// we build a copy-chain map: (dst_mreg, dst_ver) → (src_mreg, src_ver).
// When getVarName is called, we follow the chain to find the ultimate
// source. If the source is a parameter (ssa_ver 0), the parameter name
// is used. Otherwise, a new name is generated.
void CTreeBuilder::buildCopyMergeMap() {
    if (!mba_) return;
    copy_chain_.clear();

    // Parameter registers: only block merging when the source is a
    // parameter register at ssa_ver 0 (original function argument).
    // After the parameter is consumed and the register is redefined
    // (ssa_ver > 0), merging is safe.
    auto isArgReg = [this](int mreg) {
        if (is_aarch64_) {
            return mreg >= 100 && mreg <= 107;
        } else {
            return mreg >= 100 && mreg <= 103;
        }
    };

    // Scan all instructions for OP_MOV/OP_XDS/OP_XDU dst, src patterns
    for (auto& blk : mba_->blocks) {
        for (auto* insn = blk->head; insn; insn = insn->next) {
            if (insn->isDead()) continue;
            // v9.23: Also include XDS/XDU (sign/zero extend) in copy chain
            if (insn->opcode != mc::OP_MOV &&
                insn->opcode != mc::OP_XDS &&
                insn->opcode != mc::OP_XDU) continue;
            if (!insn->d.isReg() || !insn->l.isReg()) continue;

            int dst = insn->d.mreg;
            int src = insn->l.mreg;
            int dstVer = insn->ssa_version;
            int srcVer = insn->l.ssa_ver;

            if (dst < 0 || src < 0) continue;
            if (isSpecialReg(dst) || isSpecialReg(src)) continue;
            if (is_aarch64_ && (dst == 132 || src == 132)) continue;

            // v9.11: Only block merging when source is an arg register
            // at ssa_ver 0 (original function parameter).
            if (isArgReg(src) && srcVer == 0) continue;

            // Record: dst_vN = src_vM, so dst_vN should use src_vM's name
            copy_chain_[{dst, dstVer}] = {src, srcVer};
        }

        // v10.5: Also process OP_MOV nodes in phi_nodes vector.
        // phiSimplification converts PHI nodes with all-same sources to
        // OP_MOV, but leaves them in phi_nodes (not in the instruction list).
        // Without this, the copy chain doesn't have an entry for the
        // simplified PHI destination, causing it to get a new name
        // instead of sharing the source's name.
        for (auto* phi : blk->phi_nodes) {
            if (!phi || phi->isDead()) continue;
            if (phi->opcode != mc::OP_MOV) continue;
            if (!phi->l.isReg()) continue;
            int dst = phi->def_mreg;
            int src = phi->l.mreg;
            int dstVer = phi->ssa_version;
            int srcVer = phi->l.ssa_ver;
            if (dst < 0 || src < 0) continue;
            if (isSpecialReg(dst) || isSpecialReg(src)) continue;
            if (is_aarch64_ && (dst == 132 || src == 132)) continue;
            if (isArgReg(src) && srcVer == 0) continue;
            copy_chain_[{dst, dstVer}] = {src, srcVer};
        }
    }

    // v9.17: Process PHI nodes to unify loop-carried variable names.
    // A PHI rX_vN = φ(rX_vA, rX_vB, ...) means all versions are the
    // same variable. Map the PHI def and ALL sources to the first
    // source (typically the initial value before the loop).
    // Without this, the loop accumulator appears as an uninitialized
    // variable because the PHI creates a new SSA version that is
    // never explicitly assigned in the C output.
    for (auto& blk : mba_->blocks) {
        for (auto* phi : blk->phi_nodes) {
            int dst = phi->def_mreg;
            int dstVer = phi->ssa_version;
            if (dst < 0 || phi->l.phi_srcs.empty()) continue;

            // v10.5: Choose a base source that is NOT a parameter at
            // version 0. If the first source is (mreg, 0) — the entry
            // value — try to find a redefined source (version > 0) to
            // use as the base instead. This prevents PHI copy chains
            // from mapping redefined values back to the parameter name.
            //
            // Example: x4 = param (v0); x4 = got_xxx (v1); PHI(v0, v1)
            // Without this fix: PHI maps v1 → v0, so getVarName returns
            // "in_arg4" for the redefined value. With this fix: PHI uses
            // v1 as the base, so the redefined value gets its own name.
            int baseMreg = phi->l.phi_srcs[0].first;
            int baseVer = phi->l.phi_srcs[0].second;
            if (baseVer == 0) {
                for (size_t i = 1; i < phi->l.phi_srcs.size(); i++) {
                    int altMreg = phi->l.phi_srcs[i].first;
                    int altVer = phi->l.phi_srcs[i].second;
                    if (altMreg == dst && altVer > 0) {
                        baseMreg = altMreg;
                        baseVer = altVer;
                        break;
                    }
                }
            }

            // Map PHI def to the chosen base
            copy_chain_[{dst, dstVer}] = {baseMreg, baseVer};

            // Map all other sources to the same base.
            // This unifies the loop body's updated value with the
            // initial value, so the accumulator uses one name
            // throughout the function.
            for (size_t i = 0; i < phi->l.phi_srcs.size(); i++) {
                int srcMreg = phi->l.phi_srcs[i].first;
                int srcVer = phi->l.phi_srcs[i].second;
                // Only merge when the source is the same register
                // (same mreg, different SSA versions of the same variable)
                // v10.5: Skip the entry value (version 0) — it should
                // keep the parameter name, not be merged with the
                // redefined value.
                if (srcMreg == dst && srcVer > 0 &&
                    !(srcMreg == baseMreg && srcVer == baseVer)) {
                    copy_chain_[{srcMreg, srcVer}] = {baseMreg, baseVer};
                }
            }
        }
    }
}

// Follow copy chain to find the ultimate source.
// Returns the original (mreg, ssa_ver) if no chain exists.
std::pair<int,int> CTreeBuilder::resolveCopyChain(int mreg, int ssa_ver) {
    std::set<std::pair<int,int>> visited;
    auto key = std::make_pair(mreg, ssa_ver);
    while (copy_chain_.count(key)) {
        if (visited.count(key)) break;  // cycle detection
        visited.insert(key);
        key = copy_chain_[key];
    }
    return key;
}

std::string CTreeBuilder::getVarName(int mreg, int ssa_ver) {
    if (!mba_) return "r" + std::to_string(mreg);

    if (isSpecialReg(mreg)) {
        fprintf(stderr, "[DBG_0] getVarName(%d,%d) -> \"0\" (special reg)\n", mreg, ssa_ver);
        return "0";
    }
    if (is_aarch64_ && mreg == 132) {
        fprintf(stderr, "[DBG_0] getVarName(%d,%d) -> \"0\" (xzr/wzr)\n", mreg, ssa_ver);
        return "0";
    }

    // v9.11: Resolve through copy chain to find the ultimate source.
    // This makes OP_MOV chains share the same variable name without
    // the union-find collision problem.
    auto [rootMreg, rootVer] = resolveCopyChain(mreg, ssa_ver);

    // v9.16: Check parameter name mapping.
    // Use the original mreg but only if the copy chain resolves to the
    // SAME mreg (i.e., this is not a copy from a different register).
    // Without this guard, "mov r0, r1" (where r1 holds the accumulator)
    // would incorrectly name r0 after the parameter "in_arg0" instead
    // of the accumulator variable.
    auto pit = param_names_.find(mreg);
    if (pit != param_names_.end() && rootMreg == mreg) {
        // v10.5: Parameter registers (x0-x7/r0-r3) are reused after the
        // initial value is consumed. SSA version > 0 means this register
        // was redefined — the value is no longer the original parameter.
        // Without this, "in_arg0 = func(in_arg0)" loses the distinction
        // between the original parameter and the return value, producing
        // confusing output where the parameter name is overwritten.
        // This mirrors Ghidra's HighVariable splitting: each SSA version
        // of a reused parameter register becomes a distinct local variable.
        //
        // v24.0: Check BOTH rootVer (copy chain) and ssa_ver (original).
        // The copy chain may map a redefined value back to version 0
        // through PHI merging when the loop body's redefined register
        // is the same as the parameter register. In that case, rootVer
        // is 0 but ssa_ver > 0, and we must NOT use the parameter name.
        // Ghidra reference: HighVariable splitting ensures each SSA
        // version of a reused register is a separate local variable,
        // and only version 0 retains the original parameter name.
        if (rootVer != 0 || ssa_ver != 0) {
            // Fall through to generate a new name (uVar1, iVar2, etc.)
        } else {
            return pit->second;
        }
    }

    // Check local variable name mapping (original mreg)
    // v10.5: Same version guard as params — only version 0 is the original
    auto lit = local_var_names_.find(mreg);
    if (lit != local_var_names_.end() && rootVer == 0)
        return lit->second;

    // v9.11: Cache key uses resolved (rootMreg, rootVer) so that
    // different mregs that represent the same value share the same name.
    // For example, r5_v1 = r0_v0 and r1_v1 = r5_v1 both resolve to
    // (100, 0) and share the same cached name.
    auto key = std::make_pair(rootMreg, rootVer);
    auto vit = mreg_to_varname_.find(key);
    if (vit != mreg_to_varname_.end())
        return vit->second;

    // v9.15: Fallback for memory operands whose mem_base_ssa_ver is
    // not tracked at emission time (always 0). When the exact SSA
    // version is not found, try to find any SSA version of the same
    // mreg. This prevents creating "uVar1 /* UNINIT */" for a register
    // that was already named in another block (e.g., r4 loaded in block
    // 0 but used as store base in block 1 with ssa_ver=0).
    if (rootVer == 0) {
        for (auto& [k, v] : mreg_to_varname_) {
            if (k.first == rootMreg) {
                // Cache the mapping so subsequent lookups are fast
                mreg_to_varname_[key] = v;
                return v;
            }
        }
    }

    // v46.2: Use Hex-Rays-style tmp_N naming instead of type-prefixed names.
    // Simpler naming scheme: all temporary variables are tmp_1, tmp_2, etc.
    // Type information is preserved in the declaration (e.g., "int32_t tmp_1;").
    // This is more readable than Ghidra-style uVar1/iVar2/pVar3 naming.
    std::string name = "tmp_" + std::to_string(++var_counter_);
    mreg_to_varname_[key] = name;
    return name;
}

std::string CTreeBuilder::getRegName(int mreg) {
    if (!mba_) return "r" + std::to_string(mreg);
    return mba_->getRegName(mreg);
}

// ════════════════════════════════════════════════════════════════════
// Phase 3: makeVarRef helpers — create VarRef with type info from inference
// ════════════════════════════════════════════════════════════════════
ExprPtr CTreeBuilder::makeVarRef(int mreg, int ssa_ver) {
    std::string name = getVarName(mreg, ssa_ver);
    auto vr = std::make_unique<VarRef>(name);
    CType t = getInferredType(mreg, ssa_ver);
    if (t.category != CType::TC_UNKNOWN) {
        vr->result_type = t;
    }
    return vr;
}

ExprPtr CTreeBuilder::makeVarRef(const std::string& name, const CType& type) {
    auto vr = std::make_unique<VarRef>(name);
    if (type.category != CType::TC_UNKNOWN) {
        vr->result_type = type;
    }
    return vr;
}

// ── Check if instruction should produce a statement ──
// v3.14: Consolidated and complete filter for sp/pc/lr/fp elimination.
// The microcode pass (eliminateSpecialRegisters) handles the bulk at IR
// level. This function is a safety net that catches any residual cases.
bool CTreeBuilder::shouldEmitStmt(const mc::MicroInsn* insn) const {
    if (!insn || insn->isDead()) return false;
    switch (insn->opcode) {
        case mc::OP_NOP:
        case mc::OP_GOTO:
        case mc::OP_CBRANCH:
            return false;
        // v10.5: OP_PHI is no longer filtered here. PHI nodes are now
        // handled in the emission switch — they become explicit
        // assignments when the copy chain doesn't unify the names.
        case mc::OP_PHI:
            return true;
        default:
            break;
    }

    int sp = spMreg();
    int lr = lrMreg();
    int pc = pcMreg();
    int fp = fpMreg();

    auto isSpecial = [&](int mreg) {
        return mreg == sp || mreg == lr || mreg == pc || mreg == fp;
    };

    // ── Rule 1: Suppress any instruction that DEFINES sp/lr/pc/fp ──
    // (except OP_RET which is real control flow)
    if (insn->def_mreg >= 0 && isSpecial(insn->def_mreg) &&
        insn->opcode != mc::OP_RET) {
        return false;
    }

    // ── Rule 2: Suppress OP_STORE of lr/fp (prologue save) ──
    if (insn->opcode == mc::OP_STORE && insn->l.isReg() &&
        (insn->l.mreg == lr || insn->l.mreg == fp)) {
        return false;
    }

    // ── Rule 3: Suppress OP_ADD/OP_SUB involving sp (stack addr computation) ──
    // These are tracked by trackStackAddrRegs() and resolved to var_N at load/store.
    if (insn->opcode == mc::OP_ADD || insn->opcode == mc::OP_SUB) {
        bool lIsSp = insn->l.isReg() && insn->l.mreg == sp;
        bool rIsSp = insn->r.isReg() && insn->r.mreg == sp;
        if (lIsSp || rIsSp) {
            return false;
        }
    }

    return true;
}

// ════════════════════════════════════════════════════════════════════
// Beautification passes implementation
// ════════════════════════════════════════════════════════════════════

// Helper: recursively transform statements
template<typename F>
static void transformStmts(StmtPtr& root, F func) {
    if (!root) return;
    func(root);
    switch (root->type) {
        case NT_BLOCK: {
            auto* blk = static_cast<Block*>(root.get());
            for (auto& s : blk->statements)
                transformStmts(s, func);
            break;
        }
        case NT_IF: {
            auto* ifn = static_cast<If*>(root.get());
            transformStmts(ifn->then_branch, func);
            transformStmts(ifn->else_branch, func);
            break;
        }
        case NT_WHILE: {
            auto* wh = static_cast<While*>(root.get());
            transformStmts(wh->body, func);
            break;
        }
        case NT_DO_WHILE: {
            auto* dw = static_cast<DoWhile*>(root.get());
            transformStmts(dw->body, func);
            break;
        }
        case NT_FOR: {
            auto* fr = static_cast<For*>(root.get());
            transformStmts(fr->body, func);
            break;
        }
        case NT_SWITCH: {
            auto* sw = static_cast<Switch*>(root.get());
            for (auto& [val, body] : sw->cases)
                transformStmts(body, func);
            transformStmts(sw->default_body, func);
            break;
        }
        default:
            break;
    }
}

// ── Pass 1: Ternary recovery ──
void recoverTernary(StmtPtr& root) {
    transformStmts(root, [](StmtPtr& stmt) {
        if (!stmt || stmt->type != NT_BLOCK) return;
        auto* blk = static_cast<Block*>(stmt.get());
        if (blk->statements.size() < 3) return;

        // Look for pattern: if (c) { a = x; } else { a = y; }
        // → a = (c) ? x : y;
        for (size_t i = 0; i + 1 < blk->statements.size(); i++) {
            if (blk->statements[i]->type != NT_IF) continue;
            auto* ifNode = static_cast<If*>(blk->statements[i].get());

            // Check if then and else are both single-assignment blocks
            if (!ifNode->then_branch || !ifNode->else_branch) continue;
            if (ifNode->then_branch->type != NT_BLOCK) continue;
            if (ifNode->else_branch->type != NT_BLOCK) continue;

            auto* thenBlk = static_cast<Block*>(ifNode->then_branch.get());
            auto* elseBlk = static_cast<Block*>(ifNode->else_branch.get());

            if (thenBlk->statements.size() != 1 || elseBlk->statements.size() != 1)
                continue;
            if (thenBlk->statements[0]->type != NT_EXPR_STMT) continue;
            if (elseBlk->statements[0]->type != NT_EXPR_STMT) continue;

            auto* thenExpr = static_cast<ExprStmt*>(thenBlk->statements[0].get())->expr.get();
            auto* elseExpr = static_cast<ExprStmt*>(elseBlk->statements[0].get())->expr.get();

            if (!thenExpr || !elseExpr) continue;
            if (thenExpr->type != NT_ASSIGN || elseExpr->type != NT_ASSIGN) continue;

            auto* thenAssign = static_cast<Assign*>(thenExpr);
            auto* elseAssign = static_cast<Assign*>(elseExpr);

            // Check same target
            if (thenAssign->target->type != NT_VAR_REF ||
                elseAssign->target->type != NT_VAR_REF) continue;

            auto* thenTarget = static_cast<VarRef*>(thenAssign->target.get());
            auto* elseTarget = static_cast<VarRef*>(elseAssign->target.get());

            if (thenTarget->name != elseTarget->name) continue;

            // Create ternary: a = (c) ? x : y;
            auto tern = std::make_unique<Ternary>();
            tern->condition = std::move(ifNode->condition);
            tern->true_expr = std::move(thenAssign->value);
            tern->false_expr = std::move(elseAssign->value);

            auto assign = std::make_unique<Assign>();
            assign->target = std::make_unique<VarRef>(thenTarget->name);
            assign->value = std::move(tern);

            auto newStmt = std::make_unique<ExprStmt>();
            newStmt->expr = std::move(assign);

            blk->statements[i] = std::move(newStmt);
            // Remove the else part (which was at i+1, but since we replaced i, the else is gone)
        }
    });
}

// ── Pass 2: Compound assignment recovery ──
void recoverCompoundAssign(StmtPtr& root) {
    transformStmts(root, [](StmtPtr& stmt) {
        if (!stmt || stmt->type != NT_EXPR_STMT) return;
        auto* es = static_cast<ExprStmt*>(stmt.get());
        if (!es->expr || es->expr->type != NT_ASSIGN) return;

        auto* assign = static_cast<Assign*>(es->expr.get());
        if (assign->op != "=") return;
        if (assign->target->type != NT_VAR_REF) return;
        if (assign->value->type != NT_BINARY_OP) return;

        auto* binop = static_cast<BinaryOp*>(assign->value.get());
        if (binop->left->type != NT_VAR_REF) return;

        auto* target = static_cast<VarRef*>(assign->target.get());
        auto* binLeft = static_cast<VarRef*>(binop->left.get());

        if (target->name == binLeft->name) {
            // a = a op b → a op= b
            assign->op = binop->op + "=";
            assign->value = std::move(binop->right);
        }
    });
}

// ── Pass 3: Short-circuit logic recovery ──
void recoverShortCircuit(StmtPtr& root) {
    // if (a) { if (b) { body } } → if (a && b) { body }
    transformStmts(root, [](StmtPtr& stmt) {
        if (!stmt || stmt->type != NT_IF) return;
        auto* outer = static_cast<If*>(stmt.get());
        if (!outer->then_branch || outer->else_branch) return;
        if (outer->then_branch->type != NT_IF) return;

        auto* inner = static_cast<If*>(outer->then_branch.get());
        if (!inner->then_branch || inner->else_branch) return;
        if (!inner->condition || !outer->condition) return;

        // Combine: if (a && b) { inner->then_branch }
        auto andCond = std::make_unique<BinaryOp>("&&",
            std::move(outer->condition), std::move(inner->condition));
        outer->condition = std::move(andCond);
        outer->then_branch = std::move(inner->then_branch);
    });
}

// ── Pass 4: Loop normalization ──
void normalizeLoops(StmtPtr& root) {
    // v3.16: Implement loop normalization (Ghidra blockaction.cc inspired)
    // Pattern 1: while(1) { ... if (c) break; } → while (!c) { ... }
    // Pattern 2: while(1) { ... if (!c) break; } → while (c) { ... }
    // Pattern 3: do { ... if (c) break; } while(1) → while (!c) { ... }
    // v24.0: Search for if-break patterns in ALL statements, not just the last.
    // v24.1: Recursive search for nested if-break patterns.
    //   Pattern 4: while(1) { if (cond) { x++; break; } } — break inside then block
    //   Pattern 5: while(1) { if (a) { if (b) { break; } } } — nested if-break
    //
    // 对标 Ghidra CollapseStructure::labelLoops + ActionBlockStructure:
    //   Ghidra 在 CFG 级别识别循环退出边，然后由 CPrint 在 CTree 级别
    //   DO NOT 生成多余的 if(cond) break; 语句。我们的 normalizeLoops
    //   是 CTree 级别的后处理，将这些 if-break 模式提升为 while 条件。
    transformStmts(root, [](StmtPtr& stmt) {
        if (!stmt) return;
        
        // Recursive helper: search a statement tree for if-break pattern
        // Returns the If node pointer and the parent Block if found
        struct IfBreakResult {
            If* ifn = nullptr;
            Block* parent_block = nullptr;
            int parent_index = -1;
        };
        
        std::function<IfBreakResult(Block*, bool)> findIfBreakRecursive;
        findIfBreakRecursive = [&](Block* blk, bool searchNested) -> IfBreakResult {
            IfBreakResult result;
            if (!blk) return result;
            for (size_t i = 0; i < blk->statements.size(); i++) {
                auto& s = blk->statements[i];
                if (!s) continue;
                
                if (s->type == NT_IF) {
                    auto* ifn = static_cast<If*>(s.get());
                    
                    // Case A: if (cond) break;  (direct break as then_branch)
                    if (!ifn->else_branch && ifn->then_branch &&
                        ifn->then_branch->type == NT_BREAK) {
                        result.ifn = ifn;
                        result.parent_block = blk;
                        result.parent_index = (int)i;
                        return result;
                    }
                    
                    // Case B: if (cond) { stmts; break; } (break inside then block)
                    if (!ifn->else_branch && ifn->then_branch &&
                        ifn->then_branch->type == NT_BLOCK) {
                        auto* thenBlk = static_cast<Block*>(ifn->then_branch.get());
                        if (!thenBlk->statements.empty()) {
                            auto& lastStmt = thenBlk->statements.back();
                            if (lastStmt && lastStmt->type == NT_BREAK) {
                                result.ifn = ifn;
                                result.parent_block = blk;
                                result.parent_index = (int)i;
                                return result;
                            }
                        }
                    }
                    
                    // Case C: if (cond) { ... } else { ... break; } (break in else)
                    if (ifn->else_branch && ifn->else_branch->type == NT_BLOCK) {
                        auto* elseBlk = static_cast<Block*>(ifn->else_branch.get());
                        if (!elseBlk->statements.empty()) {
                            auto& lastStmt = elseBlk->statements.back();
                            if (lastStmt && lastStmt->type == NT_BREAK) {
                                // Negate condition for the loop
                                result.ifn = ifn;
                                result.parent_block = blk;
                                result.parent_index = (int)i;
                                return result;
                            }
                        }
                    }
                    
                    // Case D: if (cond) { if (nested) { break; } } (nested if-break)
                    if (searchNested) {
                        if (ifn->then_branch && ifn->then_branch->type == NT_BLOCK) {
                            auto nested = findIfBreakRecursive(
                                static_cast<Block*>(ifn->then_branch.get()), false);
                            if (nested.ifn) return nested;
                        }
                        if (ifn->else_branch && ifn->else_branch->type == NT_BLOCK) {
                            auto nested = findIfBreakRecursive(
                                static_cast<Block*>(ifn->else_branch.get()), false);
                            if (nested.ifn) return nested;
                        }
                    }
                }
                
                // Case E: search inside nested blocks (e.g., if inside while)
                if (searchNested) {
                    if (s->type == NT_WHILE || s->type == NT_DO_WHILE) {
                        // Don't search inside nested loops — they have their own break
                        continue;
                    }
                    if (s->type == NT_IF) {
                        // Already handled above, continue
                        continue;
                    }
                    // For other statement types (e.g., ExprStmt), no need to search
                }
            }
            return result;
        };
        
        // Helper: extract condition from if-break, handling negation
        // Returns the condition expression (ownership transferred)
        auto extractCondition = [](If* ifn) -> std::unique_ptr<Expr> {
            if (!ifn->condition) return nullptr;
            
            // Case A: if (cond) break; → condition is !cond
            if (!ifn->else_branch && ifn->then_branch &&
                ifn->then_branch->type == NT_BREAK) {
                return std::make_unique<UnaryOp>("!", cloneExpr(ifn->condition.get()));
            }
            
            // Case B: if (cond) { stmts; break; } → condition is !cond
            if (!ifn->else_branch && ifn->then_branch &&
                ifn->then_branch->type == NT_BLOCK) {
                return std::make_unique<UnaryOp>("!", cloneExpr(ifn->condition.get()));
            }
            
            // Case C: if (cond) { ... } else { ... break; } → condition is cond
            if (ifn->else_branch && ifn->else_branch->type == NT_BLOCK) {
                return cloneExpr(ifn->condition.get());
            }
            
            return nullptr;
        };
        
        // Remove the if-break statement from its parent block
        // Returns the remaining statements that should stay in the parent block
        auto removeIfBreak = [](Block* parentBlk, int idx, If* ifn) -> std::vector<StmtPtr> {
            std::vector<StmtPtr> remaining;
            if (!parentBlk) return remaining;
            
            // Remove the if-break statement, but keep any statements before it
            // (The statements after the if-break are unreachable — they stay in the loop body)
            for (int j = 0; j < (int)parentBlk->statements.size(); j++) {
                if (j == idx) continue; // Skip the if-break
                remaining.push_back(std::move(parentBlk->statements[j]));
            }
            
            // If the break was inside the then block (not the if itself), 
            // remove the break from the then block
            if (ifn->then_branch && ifn->then_branch->type == NT_BLOCK) {
                auto* thenBlk = static_cast<Block*>(ifn->then_branch.get());
                if (!thenBlk->statements.empty()) {
                    auto& last = thenBlk->statements.back();
                    if (last && last->type == NT_BREAK) {
                        last.reset(); // Remove the break from the then block
                    }
                }
            }
            
            // If the break was in the else block, remove the break from it
            if (ifn->else_branch && ifn->else_branch->type == NT_BLOCK) {
                auto* elseBlk = static_cast<Block*>(ifn->else_branch.get());
                if (!elseBlk->statements.empty()) {
                    auto& last = elseBlk->statements.back();
                    if (last && last->type == NT_BREAK) {
                        last.reset(); // Remove the break from the else block
                    }
                }
            }
            
            return remaining;
        };
        
        // Handle while(true) { body }
        if (stmt->type == NT_WHILE) {
            auto* wh = static_cast<While*>(stmt.get());
            // Check for constant-true condition (while(1) or while(true))
            if (wh->condition && wh->condition->type == NT_CONST) {
                auto* c = static_cast<Const*>(wh->condition.get());
                if (c->int_val != 0) {
                    // while(1) { body } — look for if(cond) break; anywhere in body
                    if (wh->body && wh->body->type == NT_BLOCK) {
                        auto* blk = static_cast<Block*>(wh->body.get());
                        auto result = findIfBreakRecursive(blk, true);
                        if (result.ifn && result.parent_block) {
                            // Transform: while(1) { body; if(cond) break; remaining }
                            //          → while(!cond) { body; remaining }
                            auto cond = extractCondition(result.ifn);
                            if (cond) {
                                wh->condition = std::move(cond);
                                auto remaining = removeIfBreak(
                                    result.parent_block, result.parent_index, result.ifn);
                                // Replace the parent block's statements with remaining
                                result.parent_block->statements.clear();
                                for (auto& s : remaining) {
                                    if (s) result.parent_block->statements.push_back(std::move(s));
                                }
                            }
                        }
                    }
                }
            }
        }
        // Handle do { body } while(1)
        if (stmt->type == NT_DO_WHILE) {
            auto* dw = static_cast<DoWhile*>(stmt.get());
            if (dw->condition && dw->condition->type == NT_CONST) {
                auto* c = static_cast<Const*>(dw->condition.get());
                if (c->int_val != 0) {
                    // do { body } while(1) — look for if(cond) break; anywhere in body
                    if (dw->body && dw->body->type == NT_BLOCK) {
                        auto* blk = static_cast<Block*>(dw->body.get());
                        auto result = findIfBreakRecursive(blk, true);
                        if (result.ifn && result.parent_block) {
                            auto cond = extractCondition(result.ifn);
                            if (cond) {
                                // Convert do-while(1) to while(!cond)
                                auto newWhile = std::make_unique<While>();
                                newWhile->condition = std::move(cond);
                                newWhile->body = std::move(dw->body);
                                // Remove the if-break from the new body
                                auto* nblk = static_cast<Block*>(newWhile->body.get());
                                auto remaining = removeIfBreak(
                                    nblk, result.parent_index, result.ifn);
                                // If the if-break was in a nested block, we need to rebuild
                                if (result.parent_block != nblk) {
                                    // The if statement itself stays, but the break is removed
                                    // from inside it. The if remains as a conditional statement.
                                    nblk->statements.clear();
                                    for (auto& s : remaining) {
                                        if (s) nblk->statements.push_back(std::move(s));
                                    }
                                } else {
                                    nblk->statements.clear();
                                    for (auto& s : remaining) {
                                        if (s) nblk->statements.push_back(std::move(s));
                                    }
                                }
                                stmt = std::move(newWhile);
                            }
                        }
                    }
                }
            }
        }
    });
}

// ── Pass 5: Cast minimization (对标 Ghidra castStrategy) ──
// 消除冗余类型转换:
//   1. 移除相同类型之间的 Cast  (int → int)
//   2. 移除赋值 RHS 的 Cast 当 RHS 已经是目标类型
//   3. 移除二元运算符操作数上的无意义 Cast
//   4. 移除指针运算中的 uintN_t* Cast
void minimizeCasts(StmtPtr& root) {
    if (!root) return;

    // 递归遍历所有表达式，移除冗余 Cast
    std::function<void(ExprPtr&)> processExpr = [&](ExprPtr& e) {
        if (!e) return;

        // 递归处理子表达式
        if (e->type == NT_BINARY_OP) {
            auto* bin = static_cast<BinaryOp*>(e.get());
            processExpr(bin->left);
            processExpr(bin->right);
        } else if (e->type == NT_UNARY_OP) {
            auto* un = static_cast<UnaryOp*>(e.get());
            processExpr(un->operand);
        } else if (e->type == NT_ASSIGN) {
            auto* assign = static_cast<Assign*>(e.get());
            processExpr(assign->target);
            processExpr(assign->value);
        } else if (e->type == NT_CAST) {
            auto* cast = static_cast<Cast*>(e.get());
            // 先递归处理内部表达式
            processExpr(cast->expr);

            if (!cast->expr) return;

            // 规则 1: 移除相同类型之间的 Cast
            if (cast->expr->result_type.category == cast->result_type.category &&
                cast->expr->result_type.width == cast->result_type.width) {
                // 用内部表达式替换 Cast 节点
                ExprPtr inner = std::move(cast->expr);
                e = std::move(inner);
                return;
            }

            // 规则 2: 如果内部表达式是另一个 Cast，移除中间 Cast
            if (cast->expr->type == NT_CAST) {
                auto* innerCast = static_cast<Cast*>(cast->expr.get());
                // 如果外部 Cast 的目标类型更宽，直接使用外部 Cast
                if (cast->result_type.width >= innerCast->result_type.width) {
                    ExprPtr inner = std::move(innerCast->expr);
                    cast->expr = std::move(inner);
                }
            }

            // 规则 3: 移除 void* 与具体指针类型之间的转换
            // 当内部是指针运算时，指针类型转换通常是冗余的
            if (cast->result_type.category == CType::TC_POINTER &&
                cast->expr->type == NT_BINARY_OP) {
                auto* bin = static_cast<BinaryOp*>(cast->expr.get());
                if (bin->op == "+" || bin->op == "-") {
                    // 检查是否其中一个操作数有指针类型
                    bool hasPtr = false;
                    if (bin->left && (bin->left->result_type.category == CType::TC_POINTER ||
                                      bin->left->result_type.category == CType::TC_STRUCT_PTR)) {
                        hasPtr = true;
                    }
                    if (bin->right && (bin->right->result_type.category == CType::TC_POINTER ||
                                       bin->right->result_type.category == CType::TC_STRUCT_PTR)) {
                        hasPtr = true;
                    }
                    if (hasPtr) {
                        // 指针算术已经产生指针结果，移除 Cast
                        ExprPtr inner = std::move(cast->expr);
                        e = std::move(inner);
                        return;
                    }
                }
            }

            // 规则 4: 对常量做 Cast 时，如果 Cast 是 int → uintN_t，保留
            // 但如果是 uintN_t → 相同宽度 uintN_t，移除
            if (cast->expr->type == NT_CONST) {
                auto* cst = static_cast<Const*>(cast->expr.get());
                // 如果常量值在目标类型范围内，且目标类型是整数，可以移除 Cast
                if (cast->result_type.width >= 4 && (uint64_t)cst->int_val < (1ULL << (cast->result_type.width * 8 - 1))) {
                    // 保留 Cast（因为类型可能影响后续操作符选择）
                    // 但标记为可隐藏
                }
            }
        } else if (e->type == NT_CALL) {
            auto* call = static_cast<Call*>(e.get());
            for (auto& arg : call->args) {
                processExpr(arg);
            }
        } else if (e->type == NT_MEMBER) {
            auto* member = static_cast<MemberAccess*>(e.get());
            processExpr(member->base);
        } else if (e->type == NT_INDEX) {
            auto* idx = static_cast<Index*>(e.get());
            processExpr(idx->array);
            processExpr(idx->index);
        } else if (e->type == NT_TERNARY) {
            auto* tern = static_cast<Ternary*>(e.get());
            processExpr(tern->condition);
            processExpr(tern->true_expr);
            processExpr(tern->false_expr);
        }
    };

    // 遍历所有语句
    std::function<void(StmtPtr&)> processStmt = [&](StmtPtr& s) {
        if (!s) return;
        if (s->type == NT_BLOCK) {
            auto* block = static_cast<Block*>(s.get());
            for (auto& child : block->statements) processStmt(child);
        } else if (s->type == NT_IF) {
            auto* ifStmt = static_cast<If*>(s.get());
            processExpr(ifStmt->condition);
            processStmt(ifStmt->then_branch);
            processStmt(ifStmt->else_branch);
        } else if (s->type == NT_WHILE) {
            auto* wh = static_cast<While*>(s.get());
            processExpr(wh->condition);
            processStmt(wh->body);
        } else if (s->type == NT_DO_WHILE) {
            auto* dw = static_cast<DoWhile*>(s.get());
            processStmt(dw->body);
            processExpr(dw->condition);
        } else if (s->type == NT_FOR) {
            auto* fr = static_cast<For*>(s.get());
            processStmt(fr->init);
            processExpr(fr->condition);
            processExpr(fr->increment);
            processStmt(fr->body);
        } else if (s->type == NT_SWITCH) {
            auto* sw = static_cast<Switch*>(s.get());
            processExpr(sw->expr);
            for (auto& cs : sw->cases) {
                processStmt(cs.second);
            }
        } else if (s->type == NT_EXPR_STMT) {
            auto* es = static_cast<ExprStmt*>(s.get());
            processExpr(es->expr);
        } else if (s->type == NT_RETURN) {
            auto* ret = static_cast<Return*>(s.get());
            processExpr(ret->value);
        }
    };

    processStmt(root);
}

// ── Pass 6: Constant folding ──
// Helper: try to evaluate a constant expression to a known integer value.
// Returns true if the value is known, and sets `out_val`.
// Handles: NT_CONST (direct integer), NT_VAR_REF with numeric name (VarRef("0")).
static bool evalConstInt(const Expr* expr, int64_t& out_val) {
    if (!expr) return false;
    if (expr->type == NT_CONST) {
        auto* c = static_cast<const Const*>(expr);
        if (!c->is_fp) {
            out_val = c->int_val;
            return true;
        }
        return false;
    }
    // v56.0: VarRef with numeric name (e.g., VarRef("0") from special registers)
    if (expr->type == NT_VAR_REF) {
        auto* vr = static_cast<const VarRef*>(expr);
        try {
            size_t pos = 0;
            int64_t val = std::stoll(vr->name, &pos, 0);
            if (pos == vr->name.size()) {
                out_val = val;
                return true;
            }
        } catch (...) {}
        return false;
    }
    return false;
}

void foldConstants(StmtPtr& root) {
    // v3.16: Basic constant folding (Ghidra ActionCopyPropagate inspired)
    // x + 0 → x, x - 0 → x, 0 + x → x, x * 1 → x, x * 0 → 0
    // (const1 OP const2) → computed_const
    // v55.0: Also handles NT_IF conditions — folds constant comparisons
    // like "0 == 1" and eliminates dead branches.
    // v56.0: Also handles VarRef("0") in addition to Const(0) for the
    // constant condition check — special registers are named "0" by
    // getVarName() and act as constant zero.
    transformStmts(root, [](StmtPtr& stmt) {
        // ── Handle NT_IF with constant condition ──
        if (stmt && stmt->type == NT_IF) {
            auto* ifn = static_cast<If*>(stmt.get());
            if (ifn->condition && ifn->condition->type == NT_BINARY_OP) {
                auto* bin = static_cast<BinaryOp*>(ifn->condition.get());
                int64_t lv, rv;
                if (evalConstInt(bin->left.get(), lv) &&
                    evalConstInt(bin->right.get(), rv)) {
                    bool condTrue = false;
                    if (bin->op == "==") condTrue = (lv == rv);
                    else if (bin->op == "!=") condTrue = (lv != rv);
                    else if (bin->op == "<") condTrue = (lv < rv);
                    else if (bin->op == ">") condTrue = (lv > rv);
                    else if (bin->op == "<=") condTrue = (lv <= rv);
                    else if (bin->op == ">=") condTrue = (lv >= rv);
                    else if (bin->op == "&&") condTrue = (lv && rv);
                    else if (bin->op == "||") condTrue = (lv || rv);
                    else return; // unknown operator, skip

                    if (condTrue) {
                        // Replace if with then-branch (or empty block if no then)
                        if (ifn->then_branch)
                            stmt = std::move(ifn->then_branch);
                        else
                            stmt = std::make_unique<Block>();
                    } else {
                        // Replace if with else-branch (or empty block if no else)
                        if (ifn->else_branch)
                            stmt = std::move(ifn->else_branch);
                        else
                            stmt = std::make_unique<Block>();
                    }
                    return;
                }
            }
            return;
        }

        // v25.0: Handle NT_WHILE with constant condition (对标 Ghidra foldConstants)
        // Fold while(0 <= 0) → while(1), while(0 != 0) → while(0) (dead loop removed)
        if (stmt && stmt->type == NT_WHILE) {
            auto* wh = static_cast<While*>(stmt.get());
            if (wh->condition && wh->condition->type == NT_BINARY_OP) {
                auto* bin = static_cast<BinaryOp*>(wh->condition.get());
                int64_t lv, rv;
                if (evalConstInt(bin->left.get(), lv) &&
                    evalConstInt(bin->right.get(), rv)) {
                    bool condTrue = false;
                    if (bin->op == "==") condTrue = (lv == rv);
                    else if (bin->op == "!=") condTrue = (lv != rv);
                    else if (bin->op == "<") condTrue = (lv < rv);
                    else if (bin->op == ">") condTrue = (lv > rv);
                    else if (bin->op == "<=") condTrue = (lv <= rv);
                    else if (bin->op == ">=") condTrue = (lv >= rv);
                    else if (bin->op == "&&") condTrue = (lv && rv);
                    else if (bin->op == "||") condTrue = (lv || rv);
                    else return;
                    wh->condition = std::make_unique<Const>(condTrue ? 1 : 0);
                }
            }
            return;
        }

        // v25.0: Handle NT_DO_WHILE with constant condition
        if (stmt && stmt->type == NT_DO_WHILE) {
            auto* dw = static_cast<DoWhile*>(stmt.get());
            if (dw->condition && dw->condition->type == NT_BINARY_OP) {
                auto* bin = static_cast<BinaryOp*>(dw->condition.get());
                int64_t lv, rv;
                if (evalConstInt(bin->left.get(), lv) &&
                    evalConstInt(bin->right.get(), rv)) {
                    bool condTrue = false;
                    if (bin->op == "==") condTrue = (lv == rv);
                    else if (bin->op == "!=") condTrue = (lv != rv);
                    else if (bin->op == "<") condTrue = (lv < rv);
                    else if (bin->op == ">") condTrue = (lv > rv);
                    else if (bin->op == "<=") condTrue = (lv <= rv);
                    else if (bin->op == ">=") condTrue = (lv >= rv);
                    else if (bin->op == "&&") condTrue = (lv && rv);
                    else if (bin->op == "||") condTrue = (lv || rv);
                    else return;
                    dw->condition = std::make_unique<Const>(condTrue ? 1 : 0);
                }
            }
            return;
        }

        // ── Handle assignments ──
        if (!stmt || stmt->type != NT_EXPR_STMT) return;
        auto* es = static_cast<ExprStmt*>(stmt.get());
        if (!es->expr || es->expr->type != NT_ASSIGN) return;
        auto* assign = static_cast<Assign*>(es->expr.get());
        if (!assign->value) return;

        // Fold within the value expression
        Expr* val = assign->value.get();
        if (val->type != NT_BINARY_OP) return;
        auto* bin = static_cast<BinaryOp*>(val);

        // Both operands constant → fold
        if (bin->left && bin->left->type == NT_CONST &&
            bin->right && bin->right->type == NT_CONST) {
            auto* lc = static_cast<Const*>(bin->left.get());
            auto* rc = static_cast<Const*>(bin->right.get());
            if (!lc->is_fp && !rc->is_fp) {
                int64_t result = 0;
                bool folded = true;
                if (bin->op == "+") result = lc->int_val + rc->int_val;
                else if (bin->op == "-") result = lc->int_val - rc->int_val;
                else if (bin->op == "*") result = lc->int_val * rc->int_val;
                else if (bin->op == "<<") result = lc->int_val << rc->int_val;
                else if (bin->op == ">>") result = lc->int_val >> rc->int_val;
                else if (bin->op == "|") result = lc->int_val | rc->int_val;
                else if (bin->op == "&") result = lc->int_val & rc->int_val;
                else if (bin->op == "^") result = lc->int_val ^ rc->int_val;
                else folded = false;

                if (folded) {
                    auto foldedConst = std::make_unique<Const>(result);
                    foldedConst->result_type = bin->result_type;
                    assign->value = std::move(foldedConst);
                    return;
                }
            }
        }

        // x + 0 → x, 0 + x → x
        if (bin->op == "+") {
            if (bin->right && bin->right->type == NT_CONST) {
                auto* rc = static_cast<Const*>(bin->right.get());
                if (rc->int_val == 0 && !rc->is_fp) {
                    assign->value = std::move(bin->left);
                    return;
                }
            }
            if (bin->left && bin->left->type == NT_CONST) {
                auto* lc = static_cast<Const*>(bin->left.get());
                if (lc->int_val == 0 && !lc->is_fp) {
                    assign->value = std::move(bin->right);
                    return;
                }
            }
        }
        // x - 0 → x
        if (bin->op == "-" && bin->right && bin->right->type == NT_CONST) {
            auto* rc = static_cast<Const*>(bin->right.get());
            if (rc->int_val == 0 && !rc->is_fp) {
                assign->value = std::move(bin->left);
                return;
            }
        }
        // x * 1 → x
        if (bin->op == "*" && bin->right && bin->right->type == NT_CONST) {
            auto* rc = static_cast<Const*>(bin->right.get());
            if (rc->int_val == 1 && !rc->is_fp) {
                assign->value = std::move(bin->left);
                return;
            }
        }
    });
}

// ── Pass 7: Dead assignment elimination ──
// ════════════════════════════════════════════════════════════════════
// v3.15: eliminateDeadAssigns — AST-level dead store elimination
// Mirrors Ghidra's ActionDeadCode: removes assignments whose target
// is never read before being overwritten or going out of scope.
//
// Algorithm: for each block, scan backward tracking "live" variables.
// An assignment x = expr is dead if x is not in the live set AND expr
// has no side effects (no function calls).
// ════════════════════════════════════════════════════════════════════

// Helper: collect all variable names referenced in an expression
static void collectExprVarRefs(const ctree::Expr* expr, std::set<std::string>& vars) {
    if (!expr) return;
    switch (expr->type) {
        case ctree::NT_VAR_REF: {
            const auto* vr = static_cast<const ctree::VarRef*>(expr);
            if (!vr->name.empty() && vr->name != "0")
                vars.insert(vr->name);
            break;
        }
        case ctree::NT_BINARY_OP: {
            const auto* bin = static_cast<const ctree::BinaryOp*>(expr);
            collectExprVarRefs(bin->left.get(), vars);
            collectExprVarRefs(bin->right.get(), vars);
            break;
        }
        case ctree::NT_UNARY_OP: {
            const auto* un = static_cast<const ctree::UnaryOp*>(expr);
            collectExprVarRefs(un->operand.get(), vars);
            break;
        }
        case ctree::NT_ASSIGN: {
            const auto* as = static_cast<const ctree::Assign*>(expr);
            collectExprVarRefs(as->target.get(), vars);
            collectExprVarRefs(as->value.get(), vars);
            break;
        }
        case ctree::NT_CALL: {
            const auto* call = static_cast<const ctree::Call*>(expr);
            for (auto& arg : call->args)
                collectExprVarRefs(arg.get(), vars);
            break;
        }
        case ctree::NT_CAST: {
            const auto* cast = static_cast<const ctree::Cast*>(expr);
            collectExprVarRefs(cast->expr.get(), vars);
            break;
        }
        case ctree::NT_MEMBER: {
            const auto* m = static_cast<const ctree::MemberAccess*>(expr);
            collectExprVarRefs(m->base.get(), vars);
            break;
        }
        case ctree::NT_TERNARY: {
            const auto* t = static_cast<const ctree::Ternary*>(expr);
            collectExprVarRefs(t->condition.get(), vars);
            collectExprVarRefs(t->true_expr.get(), vars);
            collectExprVarRefs(t->false_expr.get(), vars);
            break;
        }
        case ctree::NT_INDEX: {
            const auto* idx = static_cast<const ctree::Index*>(expr);
            collectExprVarRefs(idx->array.get(), vars);
            collectExprVarRefs(idx->index.get(), vars);
            break;
        }
        case ctree::NT_DEREF: {
            // v57.0: Dereference nodes like *ptr contain a variable reference
            // in their operand. Without this case, collectExprVarRefs silently
            // skips Deref, so "tmp_45" in "*tmp_45 = val" is not collected as
            // a live variable. This causes eliminateDeadAssigns to remove the
            // definition of tmp_45, leaving it without a visible assignment
            // and triggering the "UNINIT" marker.
            const auto* d = static_cast<const ctree::Deref*>(expr);
            collectExprVarRefs(d->operand.get(), vars);
            break;
        }
        case ctree::NT_GLOBAL_VAR: {
            // v57.0: GlobalVarRef should also be traversed — the variable
            // name inside is used as a reference.
            const auto* gv = static_cast<const ctree::GlobalVarRef*>(expr);
            if (!gv->name.empty() && gv->name != "0")
                vars.insert(gv->name);
            break;
        }
        case ctree::NT_STRING: {
            // String constants don't reference variables
            break;
        }
        default:
            break;
    }
}

// Helper: check if an expression has side effects (contains a function call)
bool exprHasSideEffects(const ctree::Expr* expr) {
    if (!expr) return false;
    switch (expr->type) {
        case ctree::NT_CALL:
            return true;
        case ctree::NT_BINARY_OP: {
            const auto* bin = static_cast<const ctree::BinaryOp*>(expr);
            return exprHasSideEffects(bin->left.get()) || exprHasSideEffects(bin->right.get());
        }
        case ctree::NT_UNARY_OP: {
            const auto* un = static_cast<const ctree::UnaryOp*>(expr);
            return exprHasSideEffects(un->operand.get());
        }
        case ctree::NT_ASSIGN: {
            const auto* as = static_cast<const ctree::Assign*>(expr);
            return exprHasSideEffects(as->value.get());
        }
        case ctree::NT_CAST: {
            const auto* cast = static_cast<const ctree::Cast*>(expr);
            return exprHasSideEffects(cast->expr.get());
        }
        case ctree::NT_TERNARY: {
            const auto* t = static_cast<const ctree::Ternary*>(expr);
            return exprHasSideEffects(t->condition.get()) ||
                   exprHasSideEffects(t->true_expr.get()) ||
                   exprHasSideEffects(t->false_expr.get());
        }
        case ctree::NT_INDEX: {
            const auto* idx = static_cast<const ctree::Index*>(expr);
            return exprHasSideEffects(idx->array.get()) ||
                   exprHasSideEffects(idx->index.get());
        }
        case ctree::NT_MEMBER: {
            const auto* m = static_cast<const ctree::MemberAccess*>(expr);
            return exprHasSideEffects(m->base.get());
        }
        default:
            return false;
    }
}

// Helper: get the target variable name of an assignment (if simple x = ...)
static std::string getAssignTarget(const ctree::Stmt* stmt) {
    if (!stmt || stmt->type != ctree::NT_EXPR_STMT) return "";
    const auto* es = static_cast<const ctree::ExprStmt*>(stmt);
    if (!es->expr || es->expr->type != ctree::NT_ASSIGN) return "";
    const auto* assign = static_cast<const ctree::Assign*>(es->expr.get());
    if (!assign->target || assign->target->type != ctree::NT_VAR_REF) return "";
    const auto* vr = static_cast<const ctree::VarRef*>(assign->target.get());
    return vr->name;
}

// v3.21: Collect all variables assigned anywhere in a statement tree.
// Used by eliminateDeadAssigns to seed the live set for loop bodies.
static std::string getAssignTarget(const ctree::Stmt* stmt);  // forward decl

static void collectAssignedVars(const ctree::Stmt* stmt, std::set<std::string>& vars) {
    if (!stmt) return;
    switch (stmt->type) {
        case ctree::NT_BLOCK: {
            const auto* blk = static_cast<const ctree::Block*>(stmt);
            for (const auto& s : blk->statements)
                collectAssignedVars(s.get(), vars);
            break;
        }
        case ctree::NT_EXPR_STMT: {
            std::string target = getAssignTarget(stmt);
            if (!target.empty()) vars.insert(target);
            break;
        }
        case ctree::NT_IF: {
            const auto* ifn = static_cast<const ctree::If*>(stmt);
            collectAssignedVars(ifn->then_branch.get(), vars);
            collectAssignedVars(ifn->else_branch.get(), vars);
            break;
        }
        case ctree::NT_WHILE:
        case ctree::NT_DO_WHILE: {
            const auto* wh = static_cast<const ctree::While*>(stmt);
            collectAssignedVars(wh->body.get(), vars);
            break;
        }
        default:
            break;
    }
}

// v26.0: Collect all variable references (reads) from a statement tree.
// Used by eliminateDeadAssigns alongside collectAssignedVars to ensure
// variables READ in branches (e.g., return uVar5 inside a nested loop)
// are properly propagated as "live" for code before the structure.
// Without this, statements like "uVar5 = uVar2 | 0x10000;" are incorrectly
// removed as dead because the return statement's var ref is not an assignment.
static void collectStmtVarRefs(const ctree::Stmt* stmt, std::set<std::string>& vars) {
    if (!stmt) return;
    switch (stmt->type) {
        case ctree::NT_BLOCK: {
            const auto* blk = static_cast<const ctree::Block*>(stmt);
            for (const auto& s : blk->statements)
                collectStmtVarRefs(s.get(), vars);
            break;
        }
        case ctree::NT_EXPR_STMT: {
            const auto* es = static_cast<const ctree::ExprStmt*>(stmt);
            collectExprVarRefs(es->expr.get(), vars);
            break;
        }
        case ctree::NT_IF: {
            const auto* ifn = static_cast<const ctree::If*>(stmt);
            collectExprVarRefs(ifn->condition.get(), vars);
            collectStmtVarRefs(ifn->then_branch.get(), vars);
            collectStmtVarRefs(ifn->else_branch.get(), vars);
            break;
        }
        case ctree::NT_WHILE: {
            const auto* wh = static_cast<const ctree::While*>(stmt);
            collectExprVarRefs(wh->condition.get(), vars);
            collectStmtVarRefs(wh->body.get(), vars);
            break;
        }
        case ctree::NT_DO_WHILE: {
            const auto* dw = static_cast<const ctree::DoWhile*>(stmt);
            collectExprVarRefs(dw->condition.get(), vars);
            collectStmtVarRefs(dw->body.get(), vars);
            break;
        }
        case ctree::NT_RETURN: {
            const auto* ret = static_cast<const ctree::Return*>(stmt);
            collectExprVarRefs(ret->value.get(), vars);
            break;
        }
        default:
            break;
    }
}

// v3.21: 递归版 DCE，支持向子结构传递 live 集
static void eliminateDeadAssignsRecursive(StmtPtr& root,
                                           std::set<std::string> initialLive);

void eliminateDeadAssigns(StmtPtr& root) {
    eliminateDeadAssignsRecursive(root, {});
}

static void eliminateDeadAssignsRecursive(StmtPtr& root,
                                           std::set<std::string> initialLive) {
    if (!root) return;
    switch (root->type) {
        case ctree::NT_BLOCK: {
            auto* blk = static_cast<ctree::Block*>(root.get());
            if (blk->statements.empty()) return;

            std::set<std::string> live = initialLive;
            std::vector<size_t> toRemove;

            // v50.0: Scan all statements to find variables referenced in return
            // statements. These variables must never be erased from the live set,
            // because the same variable may be assigned in different control flow
            // paths and the last assignment seen bottom-up may not be the one
            // that actually reaches the return.
            std::set<std::string> returnVars;
            for (const auto& st : blk->statements) {
                if (st->type == ctree::NT_RETURN) {
                    const auto* ret = static_cast<const ctree::Return*>(st.get());
                    collectExprVarRefs(ret->value.get(), returnVars);
                }
            }

            for (int i = (int)blk->statements.size() - 1; i >= 0; i--) {
                const auto& s = blk->statements[i];

                if (s->type == ctree::NT_RETURN || s->type == ctree::NT_BREAK ||
                    s->type == ctree::NT_CONTINUE) {
                    live.clear();
                    if (s->type == ctree::NT_RETURN) {
                        const auto* ret = static_cast<const ctree::Return*>(s.get());
                        collectExprVarRefs(ret->value.get(), live);
                    }
                    continue;
                }

                if (s->type == ctree::NT_IF) {
                    const auto* ifn = static_cast<const ctree::If*>(s.get());
                    collectExprVarRefs(ifn->condition.get(), live);
                    eliminateDeadAssignsRecursive(const_cast<StmtPtr&>(ifn->then_branch), live);
                    eliminateDeadAssignsRecursive(const_cast<StmtPtr&>(ifn->else_branch), live);
                    if (ifn->then_branch) {
                        collectAssignedVars(ifn->then_branch.get(), live);
                        collectStmtVarRefs(ifn->then_branch.get(), live); // v26.0: collect reads too
                    }
                    if (ifn->else_branch) {
                        collectAssignedVars(ifn->else_branch.get(), live);
                        collectStmtVarRefs(ifn->else_branch.get(), live); // v26.0: collect reads too
                    }
                    continue;
                }

                if (s->type == ctree::NT_WHILE) {
                    auto* wh = static_cast<ctree::While*>(s.get());
                    collectExprVarRefs(wh->condition.get(), live);
                    // v3.21: Seed loop body with condition vars + assigned vars
                    std::set<std::string> bodyLive;
                    collectExprVarRefs(wh->condition.get(), bodyLive);
                    if (wh->body) collectAssignedVars(wh->body.get(), bodyLive);
                    if (wh->body) collectStmtVarRefs(wh->body.get(), bodyLive); // v26.0: collect reads too
                    for (auto& v : live) bodyLive.insert(v);
                    eliminateDeadAssignsRecursive(wh->body, bodyLive);
                    if (wh->body) {
                        collectAssignedVars(wh->body.get(), live);
                        collectStmtVarRefs(wh->body.get(), live); // v26.0: collect reads too
                    }
                    continue;
                }

                if (s->type == ctree::NT_DO_WHILE) {
                    // v4.0 FIX: DoWhile has body first, then condition (opposite of While)
                    auto* dw = static_cast<ctree::DoWhile*>(s.get());
                    collectExprVarRefs(dw->condition.get(), live);
                    std::set<std::string> bodyLive;
                    collectExprVarRefs(dw->condition.get(), bodyLive);
                    if (dw->body) collectAssignedVars(dw->body.get(), bodyLive);
                    if (dw->body) collectStmtVarRefs(dw->body.get(), bodyLive); // v26.0: collect reads too
                    for (auto& v : live) bodyLive.insert(v);
                    eliminateDeadAssignsRecursive(dw->body, bodyLive);
                    if (dw->body) {
                        collectAssignedVars(dw->body.get(), live);
                        collectStmtVarRefs(dw->body.get(), live); // v26.0: collect reads too
                    }
                    continue;
                }

                std::string target = getAssignTarget(s.get());
                if (!target.empty()) {
                    const auto* es = static_cast<const ctree::ExprStmt*>(s.get());
                    const auto* assign = static_cast<const ctree::Assign*>(es->expr.get());

                    // v9.23: Compound assignments (x += y, x &= y, etc.) always
                    // read the target via the RHS. recoverCompoundAssign strips
                    // the target from the value (e.g. x &= y has value=VarRef(y)),
                    // so we must explicitly add the target to the live set.
                    bool isCompoundAssign = (assign->op != "=");

                    if (target.size() >= 4 && target[0] == 'a' && target[1] == 'r' &&
                        target[2] == 'g' && std::isdigit((unsigned char)target[3])) {
                        // v9.22: Same compound-assignment fix for arg vars
                        std::set<std::string> rhsVars;
                        collectExprVarRefs(assign->value.get(), rhsVars);
                        for (auto& v : rhsVars) live.insert(v);
                        if (isCompoundAssign) {
                            // Compound assignment implicitly reads target
                            live.insert(target);
                        } else if (rhsVars.find(target) == rhsVars.end()) {
                            // v50.0: Don't erase from live set if the variable is used
                            // in a return statement. This prevents incorrect DCE when
                            // the same variable is assigned in different control flow
                            // paths — the "last" assignment seen bottom-up may not be
                            // the one that actually reaches the return.
                            if (returnVars.find(target) == returnVars.end()) {
                                live.erase(target);
                            }
                            // If variable is in returnVars, keep it in live set so
                            // any earlier assignments to it in different control flow
                            // paths will also be preserved
                        }
                        continue;
                    }

                    // v50.0: Variables used in return statements must not be
                    // considered dead, even if they appear to be overwritten
                    // by a later assignment. The bottom-up analysis may see
                    // the "last" assignment first and incorrectly conclude
                    // that earlier assignments are dead.
                    bool isReturnVar = (returnVars.find(target) != returnVars.end());

                    bool isDead = (live.find(target) == live.end()) && !isReturnVar;
                    bool hasSideFX = exprHasSideEffects(assign->value.get());

                    // v9.23: Compound assignments can never be dead because
                    // they read the target variable (e.g. x += y reads x).
                    if (isCompoundAssign) {
                        isDead = false;
                    }

                    if (isDead && !hasSideFX) {
                        toRemove.push_back((size_t)i);
                    } else if (isDead && hasSideFX) {
                        // v50.2: For constructor calls (C++ object construction),
                        // keep the FULL assignment instead of stripping it to just
                        // the call. This makes the output match IDA style:
                        //   tmp_16 = std::string("JNI");  // instead of  std::string("JNI");
                        // A constructor call without its assignment looks orphaned
                        // and confusing in pseudo-C output.
                        // Destructors (_M_destroy, ~string) are excluded — they are
                        // void functions and "tmp_N = ~string()" is semantically wrong.
                        bool isCtor = false;
                        if (assign->value->type == ctree::NT_CALL) {
                            const auto* call = static_cast<const ctree::Call*>(assign->value.get());
                            if (!call->callee_name.empty()) {
                                std::string cn = call->callee_name;
                                // Match constructors only: std::basic_string<...>::basic_string(...)
                                // The pattern "::basic_string" is unique to the constructor name
                                // in the demangled symbol. Destructors like _M_destroy or
                                // ::~string do NOT contain this pattern.
                                if (cn.find("::basic_string") != std::string::npos) {
                                    isCtor = true;
                                }
                            }
                        }

                        if (isCtor) {
                            // v50.2: Keep the full assignment for constructor calls.
                            // This preserves the variable <-> constructor relationship
                            // in the output, e.g. tmp_16 = std::string("JNI");
                            std::set<std::string> rhsVars;
                            collectExprVarRefs(assign->value.get(), rhsVars);
                            for (auto& v : rhsVars) live.insert(v);
                        } else {
                            // v11.1: Ghidra-style dead assignment with side effects.
                            // The variable is dead but the RHS has side effects (e.g.,
                            // a function call). Ghidra keeps the call but drops the
                            // assignment:  deadVar = func(...)  →  func(...);
                            auto* es_mut = static_cast<ctree::ExprStmt*>(s.get());
                            auto* assign_mut = static_cast<ctree::Assign*>(es_mut->expr.get());
                            es_mut->expr = std::move(assign_mut->value);
                            // Still collect RHS var refs for liveness
                            std::set<std::string> rhsVars;
                            collectExprVarRefs(es_mut->expr.get(), rhsVars);
                            for (auto& v : rhsVars) live.insert(v);
                        }
                    } else {
                        // v9.22: For compound assignments (x = x + y), the target
                        // is also read by the RHS. Don't erase target from live —
                        // the previous assignment to target is still needed.
                        std::set<std::string> rhsVars;
                        collectExprVarRefs(assign->value.get(), rhsVars);
                        for (auto& v : rhsVars) live.insert(v);
                        if (isCompoundAssign) {
                            // Compound assignment implicitly reads target
                            live.insert(target);
                        } else if (rhsVars.find(target) == rhsVars.end()) {
                            // v50.0: Don't erase from live set if the variable is used
                            // in a return statement. This prevents incorrect DCE when
                            // the same variable is assigned in different control flow
                            // paths — the "last" assignment seen bottom-up may not be
                            // the one that actually reaches the return.
                            if (returnVars.find(target) == returnVars.end()) {
                                live.erase(target);
                            }
                            // If variable is in returnVars, keep it in live set so
                            // any earlier assignments to it in different control flow
                            // paths will also be preserved
                        }
                    }
                } else {
                    if (s->type == ctree::NT_EXPR_STMT) {
                        const auto* es = static_cast<const ctree::ExprStmt*>(s.get());
                        // v57.0: Also traverse the expression for non-assignment
                        // statements (e.g., *ptr = val where target is a Deref).
                        // This ensures variables used in the target (like "tmp_45"
                        // in "*tmp_45 = val") are added to the live set.
                        collectExprVarRefs(es->expr.get(), live);
                        // v57.0: Also handle the special case where the expression
                        // IS an assignment but with a non-VarRef target (e.g., Deref
                        // or UnaryOp("*", ...)). For these, we need to explicitly
                        // collect the target's variable references because the
                        // standard assignment handling (which calls collectExprVarRefs
                        // on the value only) is bypassed by the empty target check.
                        if (es->expr && es->expr->type == ctree::NT_ASSIGN) {
                            const auto* as = static_cast<const ctree::Assign*>(es->expr.get());
                            if (as->target) {
                                collectExprVarRefs(as->target.get(), live);
                            }
                        }
                    }
                }
            }

            std::sort(toRemove.begin(), toRemove.end(), std::greater<size_t>());
            for (size_t i = 0; i < toRemove.size(); i++) {
                blk->statements.erase(blk->statements.begin() + toRemove[i]);
            }
            break;
        }
        case ctree::NT_IF: {
            auto* ifn = static_cast<ctree::If*>(root.get());
            eliminateDeadAssignsRecursive(ifn->then_branch, initialLive);
            eliminateDeadAssignsRecursive(ifn->else_branch, initialLive);
            break;
        }
        case ctree::NT_WHILE: {
            auto* wh = static_cast<ctree::While*>(root.get());
            std::set<std::string> bodyLive;
            collectExprVarRefs(wh->condition.get(), bodyLive);
            if (wh->body) collectAssignedVars(wh->body.get(), bodyLive);
            for (auto& v : initialLive) bodyLive.insert(v);
            eliminateDeadAssignsRecursive(wh->body, bodyLive);
            break;
        }
        case ctree::NT_DO_WHILE: {
            // v4.0 FIX: DoWhile has body first, then condition (opposite of While)
            auto* dw = static_cast<ctree::DoWhile*>(root.get());
            std::set<std::string> bodyLive;
            collectExprVarRefs(dw->condition.get(), bodyLive);
            if (dw->body) collectAssignedVars(dw->body.get(), bodyLive);
            for (auto& v : initialLive) bodyLive.insert(v);
            eliminateDeadAssignsRecursive(dw->body, bodyLive);
            break;
        }
        default:
            break;
    }
}

// ════════════════════════════════════════════════════════════════════
// fcd-inspired AST optimization passes
// ════════════════════════════════════════════════════════════════════

// ── Helper: check if two expressions are structurally identical ──
static bool exprEqual(const Expr* a, const Expr* b) {
    if (!a || !b) return a == b;
    if (a->type != b->type) return false;
    switch (a->type) {
        case NT_VAR_REF: {
            auto* va = static_cast<const VarRef*>(a);
            auto* vb = static_cast<const VarRef*>(b);
            return va->name == vb->name;
        }
        case NT_CONST: {
            auto* ca = static_cast<const Const*>(a);
            auto* cb = static_cast<const Const*>(b);
            return ca->int_val == cb->int_val && ca->is_fp == cb->is_fp;
        }
        case NT_BINARY_OP: {
            auto* ba = static_cast<const BinaryOp*>(a);
            auto* bb = static_cast<const BinaryOp*>(b);
            return ba->op == bb->op &&
                   exprEqual(ba->left.get(), bb->left.get()) &&
                   exprEqual(ba->right.get(), bb->right.get());
        }
        case NT_UNARY_OP: {
            auto* ua = static_cast<const UnaryOp*>(a);
            auto* ub = static_cast<const UnaryOp*>(b);
            return ua->op == ub->op && exprEqual(ua->operand.get(), ub->operand.get());
        }
        default:
            return false;
    }
}

// ── Helper: negate a comparison expression ──
static ExprPtr negateExpr(ExprPtr expr) {
    if (!expr) return nullptr;
    if (expr->type == NT_BINARY_OP) {
        auto* bin = static_cast<BinaryOp*>(expr.get());
        std::string neg;
        if (bin->op == "==") neg = "!=";
        else if (bin->op == "!=") neg = "==";
        else if (bin->op == "<") neg = ">=";
        else if (bin->op == "<=") neg = ">";
        else if (bin->op == ">") neg = "<=";
        else if (bin->op == ">=") neg = "<";
        if (!neg.empty()) {
            bin->op = neg;
            return expr;
        }
    }
    // Fallback: wrap in logical NOT
    return std::make_unique<UnaryOp>("!", std::move(expr), true);
}

// ── Pass 8: Consecutive if-else merging (fcd: pass_consecutivecombine) ──
// Pattern 1: if (a) { X } if (a) { Y } → if (a) { X; Y }
// Pattern 2: if (a) { X } else { Y }  +  if (!a) { Y } else { X }
//          → if (a) { X } else { Y } (drop second, already covered)
void mergeConsecutiveIfs(StmtPtr& root) {
    transformStmts(root, [](StmtPtr& stmt) {
        if (!stmt || stmt->type != NT_BLOCK) return;
        auto* blk = static_cast<Block*>(stmt.get());
        if (blk->statements.size() < 2) return;

        for (size_t i = 0; i + 1 < blk->statements.size(); ) {
            if (blk->statements[i]->type != NT_IF) { i++; continue; }
            if (blk->statements[i+1]->type != NT_IF) { i++; continue; }

            auto* if1 = static_cast<If*>(blk->statements[i].get());
            auto* if2 = static_cast<If*>(blk->statements[i+1].get());

            if (!if1->condition || !if2->condition) { i++; continue; }

            // Check: same condition, no else on either
            if (exprEqual(if1->condition.get(), if2->condition.get()) &&
                !if1->else_branch && !if2->else_branch) {
                // Merge: if (a) { X; Y }
                if (if1->then_branch && if1->then_branch->type == NT_BLOCK &&
                    if2->then_branch && if2->then_branch->type == NT_BLOCK) {
                    auto* blk1 = static_cast<Block*>(if1->then_branch.get());
                    auto* blk2 = static_cast<Block*>(if2->then_branch.get());
                    for (auto& s : blk2->statements)
                        blk1->statements.push_back(std::move(s));
                    blk->statements.erase(blk->statements.begin() + i + 1);
                    continue;  // don't advance, check again
                }
            }
            i++;
        }
    });
}

// ── Pass 9: do-while → while promotion (fcd: pass_nestedcombiner) ──
// Pattern: do { if (c) { body } } while (c)
//        → while (c) { body }
// Also: do { body; if (c) break; } while (1) → while (!c) { body }
void promoteDoWhileToWhile(StmtPtr& root) {
    transformStmts(root, [](StmtPtr& stmt) {
        if (!stmt || stmt->type != NT_DO_WHILE) return;
        auto* dw = static_cast<DoWhile*>(stmt.get());
        if (!dw->body || !dw->condition) return;

        // Check if body is a single if with matching condition
        if (dw->body->type != NT_IF) return;
        auto* ifNode = static_cast<If*>(dw->body.get());
        if (!ifNode->then_branch || ifNode->else_branch) return;

        // Check: if condition == do-while condition
        if (exprEqual(ifNode->condition.get(), dw->condition.get())) {
            // Transform: do { if (c) { body } } while (c) → while (c) { body }
            auto whileNode = std::make_unique<While>();
            whileNode->condition = std::move(dw->condition);
            whileNode->body = std::move(ifNode->then_branch);
            whileNode->ea = dw->ea;
            stmt = std::move(whileNode);
            return;
        }

        // Check: if (!condition) break; → while (condition)
        if (ifNode->then_branch && ifNode->then_branch->type == NT_BREAK) {
            // do { body; if (!c) break; } while(1) → while (c) { body }
            // Negate the if condition to get the while condition
            auto whileCond = negateExpr(std::move(ifNode->condition));
            auto whileNode = std::make_unique<While>();
            whileNode->condition = std::move(whileCond);
            // Keep the rest of the body (if any) — but since body is just the if,
            // the body becomes empty. Skip this case for now.
        }
    });
}

// ── Pass 10: Nested if-and merging (fcd: pass_nestedcombiner) ──
// Pattern: if (a) { if (b) { body } } → if (a && b) { body }
// Enhanced: handles else branches too
// if (a) { if (b) { X } else { Y } } → if (a && b) { X } else if (a && !b) { Y }
void mergeNestedIfs(StmtPtr& root) {
    transformStmts(root, [](StmtPtr& stmt) {
        if (!stmt || stmt->type != NT_IF) return;
        auto* outer = static_cast<If*>(stmt.get());
        if (!outer->then_branch || !outer->condition) return;
        if (outer->else_branch) return;  // only merge when no outer else

        if (outer->then_branch->type != NT_IF) return;
        auto* inner = static_cast<If*>(outer->then_branch.get());
        if (!inner->then_branch || !inner->condition) return;
        if (inner->else_branch) return;  // only merge simple case

        // Merge: if (a && b) { inner->then_branch }
        auto andCond = std::make_unique<BinaryOp>("&&",
            std::move(outer->condition), std::move(inner->condition));
        outer->condition = std::move(andCond);
        outer->then_branch = std::move(inner->then_branch);
    });
}

// ════════════════════════════════════════════════════════════════════
// Pass 11: Switch-case folding (tiny-dec inspired)
// ════════════════════════════════════════════════════════════════════

// ── Helper: check if an expression is "var == const" ──
// Returns: variable name and constant value if matched, empty otherwise
static bool matchEqConst(const Expr* expr, std::string& varName, int64_t& constVal) {
    if (!expr || expr->type != NT_BINARY_OP) return false;
    const auto* bin = static_cast<const BinaryOp*>(expr);
    if (bin->op != "==") return false;

    // Left is variable, right is constant
    if (bin->left && bin->left->type == NT_VAR_REF &&
        bin->right && bin->right->type == NT_CONST) {
        varName = static_cast<const VarRef*>(bin->left.get())->name;
        constVal = static_cast<const Const*>(bin->right.get())->int_val;
        return true;
    }
    // Or right is variable, left is constant
    if (bin->right && bin->right->type == NT_VAR_REF &&
        bin->left && bin->left->type == NT_CONST) {
        varName = static_cast<const VarRef*>(bin->right.get())->name;
        constVal = static_cast<const Const*>(bin->left.get())->int_val;
        return true;
    }
    return false;
}

// ── Helper: check if an expression is "var != const" ──
static bool matchNeConst(const Expr* expr, std::string& varName, int64_t& constVal) {
    if (!expr || expr->type != NT_BINARY_OP) return false;
    const auto* bin = static_cast<const BinaryOp*>(expr);
    if (bin->op != "!=") return false;

    if (bin->left && bin->left->type == NT_VAR_REF &&
        bin->right && bin->right->type == NT_CONST) {
        varName = static_cast<const VarRef*>(bin->left.get())->name;
        constVal = static_cast<const Const*>(bin->right.get())->int_val;
        return true;
    }
    if (bin->right && bin->right->type == NT_VAR_REF &&
        bin->left && bin->left->type == NT_CONST) {
        varName = static_cast<const VarRef*>(bin->right.get())->name;
        constVal = static_cast<const Const*>(bin->left.get())->int_val;
        return true;
    }
    return false;
}

void foldSwitchFromIfChain(StmtPtr& root) {
    // v4.0: If root itself is an IF (not wrapped in Block), check it directly
    if (root && root->type == NT_IF) {
        auto* ifNode = static_cast<If*>(root.get());
        // Try to fold this IF chain into a switch
        // Use the same logic as the Block visitor below
        struct PendingCase {
            int64_t caseVal;
            If* sourceIf;
            bool isThenBranch;
        };
        std::vector<PendingCase> pendingCases;
        If* pendingDefaultIf = nullptr;
        bool pendingDefaultIsThen = false;
        std::string switchVar;
        bool isValidChain = false;

        If* currentIf = ifNode;
        while (currentIf) {
            std::string varName;
            int64_t constVal;

            if (matchEqConst(currentIf->condition.get(), varName, constVal)) {
                if (switchVar.empty()) switchVar = varName;
                else if (switchVar != varName) { isValidChain = (pendingCases.size() >= 3); break; }
                pendingCases.push_back({constVal, currentIf, true});
                if (currentIf->else_branch && currentIf->else_branch->type == NT_IF)
                    currentIf = static_cast<If*>(currentIf->else_branch.get());
                else if (currentIf->else_branch) { pendingDefaultIf = currentIf; pendingDefaultIsThen = false; isValidChain = true; break; }
                else { isValidChain = (pendingCases.size() >= 3); break; }
            } else if (matchNeConst(currentIf->condition.get(), varName, constVal)) {
                if (switchVar.empty()) switchVar = varName;
                else if (switchVar != varName) { isValidChain = (pendingCases.size() >= 3); break; }
                pendingCases.push_back({constVal, currentIf, false});
                if (currentIf->then_branch && currentIf->then_branch->type == NT_IF)
                    currentIf = static_cast<If*>(currentIf->then_branch.get());
                else if (currentIf->then_branch) { pendingDefaultIf = currentIf; pendingDefaultIsThen = true; isValidChain = true; break; }
                else { isValidChain = (pendingCases.size() >= 3); break; }
            } else {
                isValidChain = (pendingCases.size() >= 3);
                break;
            }
        }

        if (isValidChain && pendingCases.size() >= 3) {
            auto switchNode = std::make_unique<Switch>();
            switchNode->expr = std::make_unique<VarRef>(switchVar);
            for (auto& pc : pendingCases) {
                auto caseVal = std::make_unique<Const>(pc.caseVal);
                StmtPtr body = pc.isThenBranch ? std::move(pc.sourceIf->then_branch) : std::move(pc.sourceIf->else_branch);
                if (body && body->type == NT_BLOCK) {
                    auto* cb = static_cast<Block*>(body.get());
                    bool hasBreak = !cb->statements.empty() && cb->statements.back()->type == NT_BREAK;
                    bool hasReturn = !cb->statements.empty() && cb->statements.back()->type == NT_RETURN;
                    if (!hasBreak && !hasReturn) cb->statements.push_back(std::make_unique<Break>());
                }
                switchNode->cases.push_back({std::move(caseVal), std::move(body)});
            }
            if (pendingDefaultIf)
                switchNode->default_body = pendingDefaultIsThen ? std::move(pendingDefaultIf->then_branch) : std::move(pendingDefaultIf->else_branch);
            root = std::move(switchNode);
            return;
        }
    }

    transformStmts(root, [](StmtPtr& stmt) {
        if (!stmt || stmt->type != NT_BLOCK) return;
        auto* blk = static_cast<Block*>(stmt.get());

        // Scan for if-else chains that can be folded into switch
        for (size_t i = 0; i < blk->statements.size(); i++) {
            if (blk->statements[i]->type != NT_IF) continue;

            // v4.0: TWO-PASS approach to avoid corrupting branches.
            // Pass 1: walk the chain, validate, and count cases.
            // Pass 2: only if valid (>=3 cases), move branches and build switch.

            struct PendingCase {
                int64_t caseVal;
                If* sourceIf;       // which IF node holds this case
                bool isThenBranch;   // true: then_branch, false: else_branch
            };
            std::vector<PendingCase> pendingCases;
            If* pendingDefaultIf = nullptr;  // IF node whose fallback is default
            bool pendingDefaultIsThen = false;
            std::string switchVar;
            bool isValidChain = false;

            // ── Pass 1: validate ──
            If* currentIf = static_cast<If*>(blk->statements[i].get());
            while (currentIf) {
                std::string varName;
                int64_t constVal;

                if (matchEqConst(currentIf->condition.get(), varName, constVal)) {
                    if (switchVar.empty()) {
                        switchVar = varName;
                    } else if (switchVar != varName) {
                        isValidChain = (pendingCases.size() >= 3);
                        break;
                    }
                    pendingCases.push_back({constVal, currentIf, true});

                    if (currentIf->else_branch && currentIf->else_branch->type == NT_IF) {
                        currentIf = static_cast<If*>(currentIf->else_branch.get());
                    } else if (currentIf->else_branch) {
                        pendingDefaultIf = currentIf;
                        pendingDefaultIsThen = false;
                        isValidChain = true;
                        break;
                    } else {
                        isValidChain = (pendingCases.size() >= 3);
                        break;
                    }
                } else if (matchNeConst(currentIf->condition.get(), varName, constVal)) {
                    if (switchVar.empty()) {
                        switchVar = varName;
                    } else if (switchVar != varName) {
                        isValidChain = (pendingCases.size() >= 3);
                        break;
                    }
                    pendingCases.push_back({constVal, currentIf, false});

                    if (currentIf->then_branch && currentIf->then_branch->type == NT_IF) {
                        currentIf = static_cast<If*>(currentIf->then_branch.get());
                    } else if (currentIf->then_branch) {
                        pendingDefaultIf = currentIf;
                        pendingDefaultIsThen = true;
                        isValidChain = true;
                        break;
                    } else {
                        isValidChain = (pendingCases.size() >= 3);
                        break;
                    }
                } else {
                    isValidChain = (pendingCases.size() >= 3);
                    break;
                }
            }

            // ── Pass 2: build switch (only if valid) ──
            if (isValidChain && pendingCases.size() >= 3) {
                auto switchNode = std::make_unique<Switch>();
                switchNode->expr = std::make_unique<VarRef>(switchVar);

                for (auto& pc : pendingCases) {
                    auto caseVal = std::make_unique<Const>(pc.caseVal);
                    StmtPtr body = pc.isThenBranch
                        ? std::move(pc.sourceIf->then_branch)
                        : std::move(pc.sourceIf->else_branch);
                    if (body && body->type == NT_BLOCK) {
                        auto* caseBlock = static_cast<Block*>(body.get());
                        bool hasBreak = !caseBlock->statements.empty() &&
                                        caseBlock->statements.back()->type == NT_BREAK;
                        bool hasReturn = !caseBlock->statements.empty() &&
                                         caseBlock->statements.back()->type == NT_RETURN;
                        if (!hasBreak && !hasReturn) {
                            caseBlock->statements.push_back(std::make_unique<Break>());
                        }
                    }
                    switchNode->cases.push_back({std::move(caseVal), std::move(body)});
                }

                if (pendingDefaultIf) {
                    switchNode->default_body = pendingDefaultIsThen
                        ? std::move(pendingDefaultIf->then_branch)
                        : std::move(pendingDefaultIf->else_branch);
                }

                blk->statements[i] = std::move(switchNode);
            }
        }
    });
}

// ════════════════════════════════════════════════════════════════════
// Pass 12: Empty branch elimination (v3.6)
// ════════════════════════════════════════════════════════════════════

// Helper: check if a statement list is "empty" (no statements, or only
// statements that are bare comments/asm-hints with no side effects)
static bool isEmptyOrCommentsOnly(const std::vector<StmtPtr>& stmts) {
    for (auto& s : stmts) {
        if (!s) continue;
        if (s->type == NT_EXPR_STMT) {
            auto* es = static_cast<ExprStmt*>(s.get());
            // v3.12: Calls have side effects — body is NOT empty
            if (es->expr && es->expr->type == NT_CALL) return false;
            if (es->expr && es->expr->type == NT_ASSIGN) return false;  // assignments aren't empty
            // Bare expression (e.g., "x8;" or "/* clrex */") — treat as empty
            continue;
        }
        // Any other statement type is not empty
        return false;
    }
    return true;
}

void eliminateEmptyBranches(StmtPtr& root) {
    transformStmts(root, [](StmtPtr& stmt) {
        if (!stmt || stmt->type != NT_BLOCK) return;
        auto* blk = static_cast<Block*>(stmt.get());
        std::vector<StmtPtr> result;
        result.reserve(blk->statements.size());
        for (auto& s : blk->statements) {
            if (!s) continue;
            if (s->type == NT_IF) {
                auto* ifNode = static_cast<If*>(s.get());
                bool thenEmpty = ifNode->then_branch ?
                    (ifNode->then_branch->type == NT_BLOCK ?
                        isEmptyOrCommentsOnly(static_cast<Block*>(ifNode->then_branch.get())->statements) : false) : true;
                bool elseEmpty = !ifNode->else_branch ? true :
                    (ifNode->else_branch->type == NT_BLOCK ?
                        isEmptyOrCommentsOnly(static_cast<Block*>(ifNode->else_branch.get())->statements) : false);

                if (thenEmpty && elseEmpty) {
                    continue;
                } else if (thenEmpty && !elseEmpty) {
                    auto newIf = std::make_unique<If>();
                    // v8.4: Negate comparison operator directly instead of wrapping in !(...)
                    // e.g., !(x == 0) → x != 0, not !(x == 0)
                    if (ifNode->condition && ifNode->condition->type == NT_BINARY_OP) {
                        auto* bin = static_cast<BinaryOp*>(ifNode->condition.get());
                        const std::string& op = bin->op;
                        if (op == "==") bin->op = "!=";
                        else if (op == "!=") bin->op = "==";
                        else if (op == "<") bin->op = ">=";
                        else if (op == "<=") bin->op = ">";
                        else if (op == ">") bin->op = "<=";
                        else if (op == ">=") bin->op = "<";
                        else bin->op = "!" + op;  // fallback for non-comparison ops
                        newIf->condition = std::move(ifNode->condition);
                    } else {
                        newIf->condition = std::make_unique<UnaryOp>("!", std::move(ifNode->condition), true);
                    }
                    newIf->then_branch = std::move(ifNode->else_branch);
                    result.push_back(std::move(newIf));
                    continue;
                } else if (!thenEmpty && elseEmpty) {
                    auto newIf = std::make_unique<If>();
                    newIf->condition = std::move(ifNode->condition);
                    newIf->then_branch = std::move(ifNode->then_branch);
                    result.push_back(std::move(newIf));
                    continue;
                }
                result.push_back(std::move(s));
            } else if (s->type == NT_WHILE) {
                auto* whileNode = static_cast<While*>(s.get());
                if (whileNode->body && whileNode->body->type == NT_BLOCK &&
                    isEmptyOrCommentsOnly(static_cast<Block*>(whileNode->body.get())->statements)) {
                    continue;
                }
                result.push_back(std::move(s));
            } else if (s->type == NT_DO_WHILE) {
                auto* doNode = static_cast<DoWhile*>(s.get());
                if (doNode->body && doNode->body->type == NT_BLOCK &&
                    isEmptyOrCommentsOnly(static_cast<Block*>(doNode->body.get())->statements)) {
                    continue;
                }
                result.push_back(std::move(s));
            } else {
                result.push_back(std::move(s));
            }
        }
        blk->statements = std::move(result);
    });
}

// ════════════════════════════════════════════════════════════════════
// v5.1: eliminateConstantConditions — Remove dead constant conditions
// 对标 Ghidra: dead branch elimination after constant propagation
//
// Patterns handled:
// 1. if (0 == 0) continue; else continue;  →  just continue
// 2. if (0 == 0) { then } else { else }    →  { then }
// 3. if (0 != 0) { then } else { else }    →  { else }
// 4. if (x == x) { then }                  →  { then }
// 5. if (x != x) { then }                  →  remove
// 6. do { body } while (0 != 0)            →  { body } (no loop)
// 7. do { body } while (0 == 0)            →  while(1) { body }
// 8. while (0 == 0) { body }               →  while(1) { body }
// 9. while (0 != 0) { body }               →  remove
// ════════════════════════════════════════════════════════════════════

// Check if expression evaluates to a constant boolean
// Returns: 0=false, 1=true, -1=not constant
static int evalConstBool(const Expr* expr) {
    if (!expr) return -1;

    // Direct constant 0 or non-zero
    if (expr->type == NT_CONST) {
        auto* c = static_cast<const Const*>(expr);
        return c->int_val != 0 ? 1 : 0;
    }

    // v5.2: VarRef with a numeric name is actually a constant
    // (e.g., VarRef("0") from the zero register or special registers)
    if (expr->type == NT_VAR_REF) {
        auto* vr = static_cast<const VarRef*>(expr);
        if (!vr->name.empty()) {
            try {
                int64_t val = std::stoll(vr->name);
                return val != 0 ? 1 : 0;
            } catch (...) {
                // Not numeric — not a constant
            }
        }
        return -1;
    }

    // Binary comparison
    if (expr->type == NT_BINARY_OP) {
        auto* bin = static_cast<const BinaryOp*>(expr);
        // Check if both sides are the same constant
        if (bin->left && bin->right) {
            // Evaluate both sides to constants if possible
            int lv = evalConstBool(bin->left.get());
            int rv = evalConstBool(bin->right.get());

            // x == x pattern (same variable name, non-constant)
            if (lv == -1 && rv == -1 &&
                bin->left->type == NT_VAR_REF && bin->right->type == NT_VAR_REF) {
                auto* l = static_cast<const VarRef*>(bin->left.get());
                auto* rvr = static_cast<const VarRef*>(bin->right.get());
                if (l->name == rvr->name && !l->name.empty()) {
                    if (bin->op == "==" || bin->op == "<=" || bin->op == ">=") return 1;
                    if (bin->op == "!=" || bin->op == "<" || bin->op == ">") return 0;
                }
            }

            // const OP const pattern (both sides are known constants)
            if (lv >= 0 && rv >= 0) {
                if (bin->op == "==") return lv == rv ? 1 : 0;
                if (bin->op == "!=") return lv != rv ? 1 : 0;
                if (bin->op == "<")  return lv < rv ? 1 : 0;
                if (bin->op == "<=") return lv <= rv ? 1 : 0;
                if (bin->op == ">")  return lv > rv ? 1 : 0;
                if (bin->op == ">=") return lv >= rv ? 1 : 0;
            }
        }
    }

    // Cast of constant
    if (expr->type == NT_CAST) {
        auto* cast = static_cast<const Cast*>(expr);
        return evalConstBool(cast->expr.get());
    }

    return -1;
}

// v5.2: Strip Continue/Break statements from a statement tree.
// Used when unwrapping do { body } while(0) → { body }, since the
// Continue/Break were only meaningful inside the loop.
static void stripDanglingJumps(StmtPtr& stmt) {
    if (!stmt) return;
    if (stmt->type == NT_BLOCK) {
        auto* blk = static_cast<Block*>(stmt.get());
        std::vector<StmtPtr> filtered;
        filtered.reserve(blk->statements.size());
        for (auto& s : blk->statements) {
            if (s && (s->type == NT_CONTINUE || s->type == NT_BREAK))
                continue;  // skip dangling continue/break
            stripDanglingJumps(s);
            filtered.push_back(std::move(s));
        }
        blk->statements = std::move(filtered);
    } else if (stmt->type == NT_IF) {
        auto* ifn = static_cast<If*>(stmt.get());
        stripDanglingJumps(ifn->then_branch);
        stripDanglingJumps(ifn->else_branch);
    }
    // Don't recurse into loops — their continue/break are still valid
}

void eliminateConstantConditions(StmtPtr& root) {
    transformStmts(root, [](StmtPtr& stmt) {
        if (!stmt || stmt->type != NT_BLOCK) return;
        auto* blk = static_cast<Block*>(stmt.get());
        std::vector<StmtPtr> result;
        result.reserve(blk->statements.size());

        for (auto& s : blk->statements) {
            if (!s) continue;

            if (s->type == NT_IF) {
                auto* ifStmt = static_cast<If*>(s.get());
                int condVal = evalConstBool(ifStmt->condition.get());

                if (condVal == 1) {
                    // Always true: keep then branch, drop else
                    if (ifStmt->then_branch)
                        result.push_back(std::move(ifStmt->then_branch));
                } else if (condVal == 0) {
                    // Always false: drop then, keep else if present
                    if (ifStmt->else_branch)
                        result.push_back(std::move(ifStmt->else_branch));
                } else {
                    result.push_back(std::move(s));
                }
            } else if (s->type == NT_WHILE) {
                auto* whileStmt = static_cast<While*>(s.get());
                int condVal = evalConstBool(whileStmt->condition.get());

                if (condVal == 0) {
                    // while(0) → skip entirely
                    continue;
                }
                // while(1) or while(cond) → keep
                result.push_back(std::move(s));
            } else if (s->type == NT_DO_WHILE) {
                auto* doStmt = static_cast<DoWhile*>(s.get());
                int condVal = evalConstBool(doStmt->condition.get());

                if (condVal == 0) {
                    // do { body } while(0) → just { body }
                    // v5.2: Also strip Continue/Break from body since they
                    // were only meaningful inside the loop
                    if (doStmt->body) {
                        stripDanglingJumps(doStmt->body);
                        result.push_back(std::move(doStmt->body));
                    }
                } else {
                    result.push_back(std::move(s));
                }
            } else {
                result.push_back(std::move(s));
            }
        }
        blk->statements = std::move(result);
    });

    // Recurse into children
    if (!root) return;
    if (root->type == NT_BLOCK) {
        auto* blk = static_cast<Block*>(root.get());
        for (auto& s : blk->statements) {
            eliminateConstantConditions(s);
            if (s) {
                if (s->type == NT_IF) {
                    auto* ifStmt = static_cast<If*>(s.get());
                    eliminateConstantConditions(ifStmt->then_branch);
                    eliminateConstantConditions(ifStmt->else_branch);
                } else if (s->type == NT_WHILE) {
                    eliminateConstantConditions(static_cast<While*>(s.get())->body);
                } else if (s->type == NT_DO_WHILE) {
                    eliminateConstantConditions(static_cast<DoWhile*>(s.get())->body);
                } else if (s->type == NT_FOR) {
                    auto* forStmt = static_cast<For*>(s.get());
                    eliminateConstantConditions(forStmt->body);
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════
// v3.15: propagateCopies — Single-use temporary variable inlining
// Mirrors Ghidra's RulePropagateCopy: if a variable is assigned once
// and used exactly once in the following statement, inline its value
// and remove the assignment.
//
// Example:
//   t0 = g_20f4;
//   arg1 = *(uint64_t*)t0;
// →
//   arg1 = *(uint64_t*)g_20f4;
//
// This eliminates the explosion of t0-t59 temporary variables.
// ════════════════════════════════════════════════════════════════════

// Helper: replace all VarRef nodes with name `from` to a clone of `replacement`
void replaceVarRefs(ExprPtr& expr, const std::string& from, const Expr* replacement);

ExprPtr cloneExpr(const Expr* e) {
    if (!e) return nullptr;
    switch (e->type) {
        case NT_VAR_REF: {
            const auto* vr = static_cast<const VarRef*>(e);
            return std::make_unique<VarRef>(vr->name);
        }
        case NT_CONST: {
            const auto* c = static_cast<const Const*>(e);
            auto r = std::make_unique<Const>();
            r->int_val = c->int_val;
            r->fp_val = c->fp_val;
            r->is_fp = c->is_fp;
            r->is_signed = c->is_signed;
            r->result_type = c->result_type;
            return r;
        }
        case NT_STRING: {
            const auto* sc = static_cast<const StringConst*>(e);
            auto r = std::make_unique<StringConst>();
            r->value = sc->value;
            r->address = sc->address;
            r->result_type = sc->result_type;
            return r;
        }
        case NT_BINARY_OP: {
            const auto* bin = static_cast<const BinaryOp*>(e);
            return std::make_unique<BinaryOp>(bin->op, cloneExpr(bin->left.get()),
                                              cloneExpr(bin->right.get()));
        }
        case NT_UNARY_OP: {
            const auto* un = static_cast<const UnaryOp*>(e);
            return std::make_unique<UnaryOp>(un->op, cloneExpr(un->operand.get()), un->is_prefix);
        }
        case NT_ASSIGN: {
            const auto* as = static_cast<const Assign*>(e);
            return std::make_unique<Assign>(cloneExpr(as->target.get()),
                                            cloneExpr(as->value.get()), as->op);
        }
        case NT_CALL: {
            const auto* call = static_cast<const Call*>(e);
            auto r = std::make_unique<Call>();
            r->callee_name = call->callee_name;
            if (call->callee) r->callee = cloneExpr(call->callee.get());
            for (auto& arg : call->args)
                r->args.push_back(cloneExpr(arg.get()));
            r->result_type = call->result_type;
            return r;
        }
        case NT_CAST: {
            const auto* cast = static_cast<const Cast*>(e);
            auto r = std::make_unique<Cast>();
            r->target_type = cast->target_type;
            r->expr = cloneExpr(cast->expr.get());
            return r;
        }
        case NT_TERNARY: {
            const auto* t = static_cast<const Ternary*>(e);
            auto r = std::make_unique<Ternary>();
            r->condition = cloneExpr(t->condition.get());
            r->true_expr = cloneExpr(t->true_expr.get());
            r->false_expr = cloneExpr(t->false_expr.get());
            return r;
        }
        case NT_MEMBER: {
            const auto* m = static_cast<const MemberAccess*>(e);
            auto r = std::make_unique<MemberAccess>();
            r->base = cloneExpr(m->base.get());
            r->field_name = m->field_name;
            r->field_offset = m->field_offset;
            r->is_pointer = m->is_pointer;
            return r;
        }
        case NT_INDEX: {
            const auto* idx = static_cast<const Index*>(e);
            auto r = std::make_unique<Index>();
            r->array = cloneExpr(idx->array.get());
            r->index = cloneExpr(idx->index.get());
            r->result_type = idx->result_type;
            return r;
        }
        case NT_GLOBAL_VAR: {
            // v57.0: Clone GlobalVarRef properly — without this case,
            // the default fallback returns NullExpr, which causes the
            // global variable reference to be replaced with 0 during
            // propagateCopies, turning "got_pthread_create == 1" into
            // "0 == 1" (a dead branch that can't be eliminated because
            // the original condition is lost).
            const auto* gv = static_cast<const GlobalVarRef*>(e);
            auto r = std::make_unique<GlobalVarRef>();
            r->address = gv->address;
            r->name = gv->name;
            return r;
        }
        case NT_NULL:
            return std::make_unique<NullExpr>();
        default:
            // v3.15: NEVER return nullptr — use NullExpr as safe fallback
            // to prevent segfaults from unhandled expression types
            return std::make_unique<NullExpr>();
    }
}

void replaceVarRefs(ExprPtr& expr, const std::string& from, const Expr* replacement) {
    if (!expr) return;
    switch (expr->type) {
        case NT_VAR_REF: {
            const auto* vr = static_cast<VarRef*>(expr.get());
            if (vr->name == from) {
                expr = cloneExpr(replacement);
            }
            break;
        }
        case NT_BINARY_OP: {
            auto* bin = static_cast<BinaryOp*>(expr.get());
            replaceVarRefs(bin->left, from, replacement);
            replaceVarRefs(bin->right, from, replacement);
            break;
        }
        case NT_UNARY_OP: {
            auto* un = static_cast<UnaryOp*>(expr.get());
            replaceVarRefs(un->operand, from, replacement);
            break;
        }
        case NT_ASSIGN: {
            auto* as = static_cast<Assign*>(expr.get());
            // v38.0: Only replace in the RHS (value), not the LHS (target).
            // The target is a definition, not a use. Replacing the target
            // would turn "var30 = func();" into "uVar29 - 12 = func();"
            // when inlining the value of var30 = uVar29 - 12.
            //
            // v54.0: However, when the LHS is a Deref (e.g., *ptr = value),
            // the pointer variable IS read (to get the address), even though
            // it's in the LHS position. Replace in the Deref operand too.
            replaceVarRefs(as->value, from, replacement);
            if (as->target && as->target->type == NT_DEREF) {
                auto* d = static_cast<Deref*>(as->target.get());
                replaceVarRefs(d->operand, from, replacement);
            }
            // v58.0: Also handle UnaryOp("*", ...) targets (from buildStoreExpr).
            if (as->target && as->target->type == NT_UNARY_OP) {
                auto* un = static_cast<UnaryOp*>(as->target.get());
                if (un->is_prefix && un->op == "*") {
                    replaceVarRefs(un->operand, from, replacement);
                }
            }
            break;
        }
        case NT_CALL: {
            auto* call = static_cast<Call*>(expr.get());
            for (auto& arg : call->args)
                replaceVarRefs(arg, from, replacement);
            break;
        }
        case NT_CAST: {
            auto* cast = static_cast<Cast*>(expr.get());
            replaceVarRefs(cast->expr, from, replacement);
            break;
        }
        case NT_TERNARY: {
            auto* t = static_cast<Ternary*>(expr.get());
            replaceVarRefs(t->condition, from, replacement);
            replaceVarRefs(t->true_expr, from, replacement);
            replaceVarRefs(t->false_expr, from, replacement);
            break;
        }
        case NT_INDEX: {
            auto* idx = static_cast<Index*>(expr.get());
            replaceVarRefs(idx->array, from, replacement);
            replaceVarRefs(idx->index, from, replacement);
            break;
        }
        case NT_MEMBER: {
            auto* m = static_cast<MemberAccess*>(expr.get());
            replaceVarRefs(m->base, from, replacement);
            break;
        }
        case NT_DEREF: {
            auto* d = static_cast<Deref*>(expr.get());
            replaceVarRefs(d->operand, from, replacement);
            break;
        }
        case NT_ADDRESSOF: {
            auto* a = static_cast<AddressOf*>(expr.get());
            replaceVarRefs(a->operand, from, replacement);
            break;
        }
        default:
            break;
    }
}

// Helper: count references to a variable in an expression
int countVarRefs(const Expr* expr, const std::string& name) {
    if (!expr) return 0;
    switch (expr->type) {
        case NT_VAR_REF: {
            const auto* vr = static_cast<const VarRef*>(expr);
            return vr->name == name ? 1 : 0;
        }
        case NT_BINARY_OP: {
            const auto* bin = static_cast<const BinaryOp*>(expr);
            return countVarRefs(bin->left.get(), name) + countVarRefs(bin->right.get(), name);
        }
        case NT_UNARY_OP: {
            const auto* un = static_cast<const UnaryOp*>(expr);
            return countVarRefs(un->operand.get(), name);
        }
        case NT_ASSIGN: {
            const auto* as = static_cast<const Assign*>(expr);
            // v38.0: Only count references in the RHS (value), not the LHS (target).
            // The target is a definition, not a use. Counting the target as a
            // reference causes propagateCopies to incorrectly inline into the
            // LHS of a subsequent assignment to the same variable.
            // Example: "var30 = uVar29 - 12;" followed by "var30 = func();"
            // would count var30 as "used once" (in the second assignment's target)
            // and produce "uVar29 - 12 = func();" — a corrupted expression.
            //
            // v54.0: However, when the LHS is a Deref (e.g., *ptr = value),
            // the pointer variable IS read (to get the address), even though
            // it's in the LHS position. Count these references to prevent
            // propagateCopies from removing the pointer's definition:
            //   "ptr = ...; *ptr = val;"  →  ptr has 1 non-LHS ref + 1 Deref-LHS ref = 2 refs
            // Without this, ptr would appear "used once" and be inlined away,
            // leaving it without a definition.
            int count = countVarRefs(as->value.get(), name);
            if (as->target && as->target->type == NT_DEREF) {
                const auto* d = static_cast<const Deref*>(as->target.get());
                count += countVarRefs(d->operand.get(), name);
            }
            // v58.0: Also handle UnaryOp("*", ...) targets (from buildStoreExpr).
            // buildStoreExpr creates UnaryOp("*", Cast(ptr, addr)) not Deref nodes.
            // Without this, pointers used in store targets are not counted as
            // references, causing propagateCopies to inline their definition away
            // and produce *0 instead of *ptr_name.
            if (as->target && as->target->type == NT_UNARY_OP) {
                const auto* un = static_cast<const UnaryOp*>(as->target.get());
                if (un->is_prefix && un->op == "*") {
                    count += countVarRefs(un->operand.get(), name);
                }
            }
            return count;
        }
        case NT_CALL: {
            const auto* call = static_cast<const Call*>(expr);
            int count = 0;
            for (auto& arg : call->args)
                count += countVarRefs(arg.get(), name);
            return count;
        }
        case NT_CAST: {
            const auto* cast = static_cast<const Cast*>(expr);
            return countVarRefs(cast->expr.get(), name);
        }
        case NT_TERNARY: {
            const auto* t = static_cast<const Ternary*>(expr);
            return countVarRefs(t->condition.get(), name) +
                   countVarRefs(t->true_expr.get(), name) +
                   countVarRefs(t->false_expr.get(), name);
        }
        case NT_INDEX: {
            const auto* idx = static_cast<const Index*>(expr);
            return countVarRefs(idx->array.get(), name) +
                   countVarRefs(idx->index.get(), name);
        }
        case NT_MEMBER: {
            const auto* m = static_cast<const MemberAccess*>(expr);
            return countVarRefs(m->base.get(), name);
        }
        case NT_DEREF: {
            const auto* d = static_cast<const Deref*>(expr);
            return countVarRefs(d->operand.get(), name);
        }
        case NT_ADDRESSOF: {
            const auto* a = static_cast<const AddressOf*>(expr);
            return countVarRefs(a->operand.get(), name);
        }
        default:
            return 0;
    }
}

// Helper: count variable references in a statement (recurses into branches/loops)
// v32.0: Added proper recursion into if-else branches and loop bodies.
static int countVarRefsInStmt(const Stmt* stmt, const std::string& name) {
    if (!stmt) return 0;
    switch (stmt->type) {
        case NT_BLOCK: {
            const auto* blk = static_cast<const Block*>(stmt);
            int count = 0;
            for (auto& s : blk->statements)
                count += countVarRefsInStmt(s.get(), name);
            return count;
        }
        case NT_EXPR_STMT: {
            const auto* es = static_cast<const ExprStmt*>(stmt);
            return countVarRefs(es->expr.get(), name);
        }
        case NT_RETURN: {
            const auto* ret = static_cast<const Return*>(stmt);
            return countVarRefs(ret->value.get(), name);
        }
        case NT_IF: {
            const auto* ifn = static_cast<const If*>(stmt);
            return countVarRefs(ifn->condition.get(), name) +
                   countVarRefsInStmt(ifn->then_branch.get(), name) +
                   countVarRefsInStmt(ifn->else_branch.get(), name);
        }
        case NT_WHILE: {
            const auto* wh = static_cast<const While*>(stmt);
            return countVarRefs(wh->condition.get(), name) +
                   countVarRefsInStmt(wh->body.get(), name);
        }
        case NT_DO_WHILE: {
            const auto* dw = static_cast<const DoWhile*>(stmt);
            return countVarRefsInStmt(dw->body.get(), name) +
                   countVarRefs(dw->condition.get(), name);
        }
        case NT_FOR: {
            const auto* fr = static_cast<const For*>(stmt);
            return countVarRefsInStmt(fr->init.get(), name) +
                   countVarRefs(fr->condition.get(), name) +
                   countVarRefs(fr->increment.get(), name) +
                   countVarRefsInStmt(fr->body.get(), name);
        }
        case NT_SWITCH: {
            const auto* sw = static_cast<const Switch*>(stmt);
            int count = countVarRefs(sw->expr.get(), name);
            for (auto& [val, body] : sw->cases)
                count += countVarRefsInStmt(body.get(), name);
            count += countVarRefsInStmt(sw->default_body.get(), name);
            return count;
        }
        default:
            return 0;
    }
}

// Helper: check if a variable is re-assigned (as a target) in a statement tree.
// v38.0: Used by propagateCopies to prevent inlining when the variable is
// re-assigned before the use (e.g., "x = 5; ... x = 3; return x;" should
// NOT inline 5 into the return, because x is re-assigned to 3 in between).
static bool isVarReassignedInStmt(const Stmt* stmt, const std::string& name) {
    if (!stmt) return false;
    switch (stmt->type) {
        case NT_BLOCK: {
            const auto* blk = static_cast<const Block*>(stmt);
            for (const auto& s : blk->statements) {
                if (isVarReassignedInStmt(s.get(), name)) return true;
            }
            return false;
        }
        case NT_EXPR_STMT: {
            const auto* es = static_cast<const ExprStmt*>(stmt);
            if (!es->expr || es->expr->type != NT_ASSIGN) return false;
            const auto* assign = static_cast<const Assign*>(es->expr.get());
            if (!assign->target || assign->target->type != NT_VAR_REF) return false;
            const auto* vr = static_cast<const VarRef*>(assign->target.get());
            return vr->name == name;
        }
        case NT_IF: {
            const auto* ifn = static_cast<const If*>(stmt);
            if (isVarReassignedInStmt(ifn->then_branch.get(), name)) return true;
            if (isVarReassignedInStmt(ifn->else_branch.get(), name)) return true;
            return false;
        }
        case NT_WHILE:
            return isVarReassignedInStmt(static_cast<const While*>(stmt)->body.get(), name);
        case NT_DO_WHILE:
            return isVarReassignedInStmt(static_cast<const DoWhile*>(stmt)->body.get(), name);
        case NT_FOR:
            return isVarReassignedInStmt(static_cast<const For*>(stmt)->body.get(), name);
        default:
            return false;
    }
}

// Helper: replace variable references in a statement (recurses into branches/loops)
// v32.0: Added proper recursion into if-else branches and loop bodies.
static void replaceVarRefsInStmt(StmtPtr& stmt, const std::string& from, const Expr* replacement) {
    if (!stmt) return;
    switch (stmt->type) {
        case NT_BLOCK: {
            auto* blk = static_cast<Block*>(stmt.get());
            for (auto& s : blk->statements)
                replaceVarRefsInStmt(s, from, replacement);
            break;
        }
        case NT_EXPR_STMT: {
            auto* es = static_cast<ExprStmt*>(stmt.get());
            if (es->expr) replaceVarRefs(es->expr, from, replacement);
            break;
        }
        case NT_RETURN: {
            auto* ret = static_cast<Return*>(stmt.get());
            if (ret->value) replaceVarRefs(ret->value, from, replacement);
            break;
        }
        case NT_IF: {
            auto* ifn = static_cast<If*>(stmt.get());
            if (ifn->condition) replaceVarRefs(ifn->condition, from, replacement);
            replaceVarRefsInStmt(ifn->then_branch, from, replacement);
            replaceVarRefsInStmt(ifn->else_branch, from, replacement);
            break;
        }
        case NT_WHILE: {
            auto* wh = static_cast<While*>(stmt.get());
            if (wh->condition) replaceVarRefs(wh->condition, from, replacement);
            replaceVarRefsInStmt(wh->body, from, replacement);
            break;
        }
        case NT_DO_WHILE: {
            auto* dw = static_cast<DoWhile*>(stmt.get());
            replaceVarRefsInStmt(dw->body, from, replacement);
            if (dw->condition) replaceVarRefs(dw->condition, from, replacement);
            break;
        }
        case NT_FOR: {
            auto* fr = static_cast<For*>(stmt.get());
            replaceVarRefsInStmt(fr->init, from, replacement);
            if (fr->condition) replaceVarRefs(fr->condition, from, replacement);
            if (fr->increment) replaceVarRefs(fr->increment, from, replacement);
            replaceVarRefsInStmt(fr->body, from, replacement);
            break;
        }
        case NT_SWITCH: {
            auto* sw = static_cast<Switch*>(stmt.get());
            if (sw->expr) replaceVarRefs(sw->expr, from, replacement);
            for (auto& [val, body] : sw->cases)
                replaceVarRefsInStmt(body, from, replacement);
            replaceVarRefsInStmt(sw->default_body, from, replacement);
            break;
        }
        default:
            break;
    }
}

// ════════════════════════════════════════════════════════════════════
// v3.15: propagateCopies — Single-use temporary variable inlining
// Mirrors Ghidra's RulePropagateCopy: if a variable is assigned once
// and used exactly once, inline the value at the use site.
// ════════════════════════════════════════════════════════════════════
void propagateCopies(StmtPtr& root) {
    // v3.15-fix: Rewritten to eliminate dangling pointer bug.
    // OLD CODE stored raw Expr* pointers in a map. When inlining t0 first
    // and t1's value was "t0" (a VarRef), replaceVarRefs would destroy the
    // VarRef object, making defs["t1"].second a DANGLING POINTER.
    // Subsequent cloneExpr(dangling_ptr) → use-after-free → SIGSEGV.
    //
    // NEW CODE: process one variable at a time, clone the value BEFORE
    // any modifications, use the clone for replacement.
    transformStmts(root, [](StmtPtr& stmt) {
        if (!stmt || stmt->type != NT_BLOCK) return;
        auto* blk = static_cast<Block*>(stmt.get());
        if (blk->statements.size() < 2) return;

        // Process one inlineable temporary at a time, restart after each.
        // This avoids stale pointers from vector reallocation.
        bool changed = true;
        while (changed) {
            changed = false;

            for (size_t i = 0; i < blk->statements.size(); i++) {
                std::string target = getAssignTarget(blk->statements[i].get());
                if (target.empty()) continue;

                // v32.0: Allow inlining ANY variable that is used exactly once,
                // regardless of naming convention. The refCount == 1 check
                // ensures safety (no duplication of the expression). Previously
                // only tN/vN/tmpN/varN temporaries and constants were inlined;
                // iVarN/uVarN/bVarN/pVarN/fVarN were excluded. This change
                // eliminates single-use Ghidra-style variables that carry
                // simple values (e.g., iVarN = local_2c; ...iVarN...).
                //
                // Still skip: function parameters, stack locals, named params.
                if (target.substr(0, 4) == "arg_" || target.substr(0, 6) == "local_" ||
                    target == "env" || target == "this" || target == "vm")
                    continue;

                // Get the value expression
                const auto* es = static_cast<ExprStmt*>(blk->statements[i].get());
                const auto* assign = static_cast<Assign*>(es->expr.get());
                if (!assign || !assign->value) continue;

                // v54.0: Skip self-assignments (tmp_N = tmp_N). These are
                // pseudo-definitions from addEntryDefs that mark a register
                // as "has a definition" — inlining them removes the only
                // definition, leaving the variable without a visible assignment.
                if (assign->value->type == NT_VAR_REF) {
                    const auto* vr = static_cast<const VarRef*>(assign->value.get());
                    if (vr->name == target) continue;
                }

                // Don't inline if the value has side effects (calls)
                if (exprHasSideEffects(assign->value.get())) continue;

                // v58.0: Don't inline Const(0) values, which typically come
                // from unresolved special-register loads (e.g., fp-relative
                // PC loads that couldn't be resolved). Inlining Const(0) into
                // a dereference produces *0, which is meaningless decompiler
                // output. The expression remains *tmp_X, which is at least
                // recognizable as a dereference of an unknown pointer.
                if (assign->value->type == NT_CONST) {
                    const auto* c = static_cast<const Const*>(assign->value.get());
                    if (!c->is_fp && c->int_val == 0) continue;
                }

                // v3.15-fix: CLONE the value NOW, before any modifications.
                // This guarantees we always work with a stable copy.
                ExprPtr valueClone = cloneExpr(assign->value.get());
                if (!valueClone) continue;

                // Count references after definition (v32.0: recurse into branches/loops)
                int refCount = 0;
                for (size_t j = i + 1; j < blk->statements.size(); j++) {
                    refCount += countVarRefsInStmt(blk->statements[j].get(), target);
                }

                if (refCount != 1) continue;

                // v38.0: Check if the variable is re-assigned in any subsequent
                // statement (including inside branches/loops). If so, the inlining
                // is not safe because the value is overwritten before the use.
                // Example: "x = 5; ... x = 3; return x;" should NOT inline 5.
                bool reassigned = false;
                for (size_t j = i + 1; j < blk->statements.size(); j++) {
                    if (isVarReassignedInStmt(blk->statements[j].get(), target)) {
                        reassigned = true;
                        break;
                    }
                }
                if (reassigned) continue;

                // v50.1: Check if the variable is also defined BEFORE the current
                // definition. If so, inlining this definition would make the earlier
                // definition dead, which is incorrect when the two definitions are
                // in different control flow paths (e.g., one is the success path
                // return value, the other is the error path return value).
                // Example: "tmp_5 = JNI_VERSION; ... tmp_5 = -1; return tmp_5;"
                // should NOT inline tmp_5 = -1 because it would make the JNI_VERSION
                // assignment dead and the function would always return -1.
                bool definedBefore = false;
                for (size_t k = 0; k < i; k++) {
                    std::string prevTarget = getAssignTarget(blk->statements[k].get());
                    if (prevTarget == target) {
                        definedBefore = true;
                        break;
                    }
                }
                if (definedBefore) continue;

                // Replace references in subsequent statements (v32.0: recurse into branches/loops)
                for (size_t j = i + 1; j < blk->statements.size(); j++) {
                    replaceVarRefsInStmt(blk->statements[j], target, valueClone.get());
                }

                // Remove the now-dead definition
                blk->statements.erase(blk->statements.begin() + i);
                changed = true;
                break;  // Restart scan — indices have shifted
            }
        }
    });
}

// ════════════════════════════════════════════════════════════════════
// Pass 13: Useless statement removal (v3.6)
// Removes bare expression statements like "x8;" or "0;"
// ════════════════════════════════════════════════════════════════════
void eliminateUselessStmts(StmtPtr& root) {
    transformStmts(root, [](StmtPtr& stmt) {
        if (!stmt || stmt->type != NT_BLOCK) return;
        auto* blk = static_cast<Block*>(stmt.get());
        std::vector<StmtPtr> result;
        result.reserve(blk->statements.size());
        for (auto& s : blk->statements) {
            if (!s) continue;
            if (s->type == NT_EXPR_STMT) {
                auto* es = static_cast<ExprStmt*>(s.get());
                // v9.3: Keep statements with side effects:
                // - NT_CALL and NT_ASSIGN expressions (function calls, assignments)
                // - Statements with a comment (OP_HELPER — represents instructions
                //   with side effects we can't fully model, e.g. memory barriers)
                if (es->expr) {
                    if (es->expr->type == NT_CALL || es->expr->type == NT_ASSIGN) {
                        result.push_back(std::move(s));
                        continue;
                    }
                }
                if (!es->comment.empty()) {
                    result.push_back(std::move(s));
                    continue;
                }
                continue;
            }
            result.push_back(std::move(s));
        }
        blk->statements = std::move(result);
    });
}

// v3.16: Fuse address computation patterns
// Pattern: x = *(ptr); x = (const + x) → x = (const + *(ptr))
// Also: x = *(ptr); x = (x + const) → x = (*(ptr) + const)
// This eliminates the intermediate assignment from GOT load + ADD offset
void fuseAddressComputation(StmtPtr& root) {
    transformStmts(root, [](StmtPtr& stmt) {
        if (!stmt || stmt->type != NT_BLOCK) return;
        auto* blk = static_cast<Block*>(stmt.get());
        if (blk->statements.size() < 2) return;

        bool changed = true;
        while (changed) {
            changed = false;
            for (size_t i = 0; i + 1 < blk->statements.size(); i++) {
                auto& s1 = blk->statements[i];
                auto& s2 = blk->statements[i + 1];
                if (!s1 || !s2) continue;
                if (s1->type != NT_EXPR_STMT || s2->type != NT_EXPR_STMT) continue;

                auto* es1 = static_cast<ExprStmt*>(s1.get());
                auto* es2 = static_cast<ExprStmt*>(s2.get());
                if (!es1->expr || !es2->expr) continue;

                // Both must be assignments
                if (es1->expr->type != NT_ASSIGN || es2->expr->type != NT_ASSIGN) continue;

                auto* a1 = static_cast<Assign*>(es1->expr.get());
                auto* a2 = static_cast<Assign*>(es2->expr.get());

                // Both targets must be the same variable
                if (a1->target->type != NT_VAR_REF || a2->target->type != NT_VAR_REF) continue;
                auto* v1 = static_cast<VarRef*>(a1->target.get());
                auto* v2 = static_cast<VarRef*>(a2->target.get());
                if (v1->name != v2->name) continue;

                // s2's value must be (const OP x) or (x OP const) where x is the same var
                // and s1's value must be a load expression (unary deref or call)
                Expr* val2 = a2->value.get();
                if (!val2 || val2->type != NT_BINARY_OP) continue;
                auto* bin2 = static_cast<BinaryOp*>(val2);

                // Check if one operand is a const and the other is a ref to the same var
                bool constOnRight = false;
                bool constOnLeft = false;
                Expr* varOperand = nullptr; (void)varOperand;
                Expr* constOperand = nullptr;

                if (bin2->right && bin2->right->type == NT_CONST &&
                    bin2->left && bin2->left->type == NT_VAR_REF) {
                    auto* lv = static_cast<VarRef*>(bin2->left.get());
                    if (lv->name == v1->name) {
                        constOnRight = true;
                        varOperand = bin2->left.get();
                        constOperand = bin2->right.get();
                    }
                }
                if (bin2->left && bin2->left->type == NT_CONST &&
                    bin2->right && bin2->right->type == NT_VAR_REF) {
                    auto* rv = static_cast<VarRef*>(bin2->right.get());
                    if (rv->name == v1->name) {
                        constOnLeft = true;
                        varOperand = bin2->right.get();
                        constOperand = bin2->left.get();
                    }
                }

                if (!constOnRight && !constOnLeft) continue;

                // Only fuse for addition (address + offset pattern)
                if (bin2->op != "+" && bin2->op != "-") continue;

                // Check that the variable is NOT used between s1 and s2
                // (it isn't, since s2 immediately follows s1)
                // Also check that s1's value has no side effects
                if (exprHasSideEffects(a1->value.get())) continue;

                // Clone s1's value expression and build the fused expression
                ExprPtr fusedValue;
                if (constOnRight) {
                    // (cloned_value OP const)
                    fusedValue = std::make_unique<BinaryOp>(
                        bin2->op,
                        cloneExpr(a1->value.get()),
                        cloneExpr(constOperand)
                    );
                } else {
                    // (const OP cloned_value)
                    fusedValue = std::make_unique<BinaryOp>(
                        bin2->op,
                        cloneExpr(constOperand),
                        cloneExpr(a1->value.get())
                    );
                }

                // Replace s2's value with the fused expression
                a2->value = std::move(fusedValue);

                // Remove s1 (the load)
                blk->statements.erase(blk->statements.begin() + i);
                changed = true;
                break;
            }
        }
    });
}

// ── Pass 16: Flatten nested if-break chains (v4.7) ──
// Pattern: if (a) break; else { if (b) break; else { ... } }
// Flattens to: if (a) break; if (b) break; ...
// This fixes the massively nested if-else output from compiler loop unrolling.
void flattenNestedIfBreaks(StmtPtr& root) {
    if (!root) return;

    // Recurse into children first
    switch (root->type) {
    case NT_BLOCK: {
        auto* blk = static_cast<Block*>(root.get());
        // First, recursively flatten all children
        for (auto& s : blk->statements)
            flattenNestedIfBreaks(s);

        // Now flatten the chain: if (a) break; else { if (b) break; else { ... } }
        // → if (a) break; if (b) break; ...
        bool changed = true;
        while (changed) {
            changed = false;
            std::vector<StmtPtr> newStmts;
            for (size_t i = 0; i < blk->statements.size(); i++) {
                auto& s = blk->statements[i];
                if (s && s->type == NT_IF) {
                    auto* ifNode = static_cast<If*>(s.get());
                    // Check if then-branch is Break or Continue
                    bool isBreakOrCont = ifNode->then_branch &&
                        (ifNode->then_branch->type == NT_BREAK ||
                         ifNode->then_branch->type == NT_CONTINUE);

                    if (isBreakOrCont && ifNode->else_branch &&
                        ifNode->else_branch->type == NT_BLOCK) {
                        auto* elseBlk = static_cast<Block*>(ifNode->else_branch.get());
                        if (elseBlk->statements.size() == 1 &&
                            elseBlk->statements[0] &&
                            elseBlk->statements[0]->type == NT_IF) {
                            // Extract inner If from else branch
                            auto innerIf = std::move(elseBlk->statements[0]);
                            // Remove else from outer If (now it's just if-break)
                            ifNode->else_branch = nullptr;
                            // Add outer If and inner If as siblings
                            newStmts.push_back(std::move(s));
                            blk->statements[i] = std::move(innerIf);
                            // Retry at this position (the inner If might also be in a chain)
                            i--;
                            changed = true;
                            continue;
                        }
                    }
                }
                newStmts.push_back(std::move(s));
            }
            blk->statements = std::move(newStmts);
        }
        break;
    }
    case NT_IF: {
        auto* ifNode = static_cast<If*>(root.get());
        flattenNestedIfBreaks(ifNode->then_branch);
        flattenNestedIfBreaks(ifNode->else_branch);
        break;
    }
    case NT_WHILE: {
        auto* wh = static_cast<While*>(root.get());
        flattenNestedIfBreaks(wh->body);
        break;
    }
    case NT_DO_WHILE: {
        auto* dw = static_cast<DoWhile*>(root.get());
        flattenNestedIfBreaks(dw->body);
        break;
    }
    case NT_FOR: {
        auto* fr = static_cast<For*>(root.get());
        flattenNestedIfBreaks(fr->body);
        break;
    }
    default:
        break;
    }
}

// ── Pass 17: Collapse repeated COW destructor patterns (v4.7) ──
// Pattern: if (__sync_fetch_and_sub(&refVar, 1) == 1) { _M_destroy(strVar); }
// When this pattern appears multiple times in the same function (due to
// compiler-inlined COW destructors), we collapse them to avoid flooding.
//
// v4.9: Extended matching to handle cases where __sync_fetch_and_sub was
// extracted from the condition by earlier passes (propagateCopies, etc.).
// Also handles nested _M_destroy in IF bodies.
void collapseCowDestructors(StmtPtr& root) {
    if (!root) return;

    // Helper: search expression tree for __sync_fetch_and_sub, extract refVar from first arg
    static auto extractRefVar = [](Expr* e) -> std::string {
        if (!e) return "";
        // v4.9: Handle ASSIGN (e.g., tmp = __sync_fetch_and_sub(...))
        if (e->type == NT_ASSIGN) {
            auto* a = static_cast<Assign*>(e);
            e = a->value.get();
            if (!e) return "";
        }
        if (e->type == NT_CALL) {
            auto* c = static_cast<Call*>(e);
            if (c->callee_name.find("__sync_fetch_and_sub") != std::string::npos) {
                if (!c->args.empty() && c->args[0]) {
                    if (c->args[0]->type == NT_UNARY_OP) {
                        auto* unary = static_cast<UnaryOp*>(c->args[0].get());
                        if (unary->op == "&" && unary->operand && unary->operand->type == NT_VAR_REF)
                            return static_cast<VarRef*>(unary->operand.get())->name;
                    }
                    if (c->args[0]->type == NT_VAR_REF)
                        return static_cast<VarRef*>(c->args[0].get())->name;
                }
            }
        }
        return "";
    };

    // Helper: recursively search for _M_destroy in a statement, extract first arg
    static std::function<std::string(StmtPtr&)> extractStrVar;
    extractStrVar = [](StmtPtr& stmt) -> std::string {
        if (!stmt) return "";
        if (stmt->type == NT_EXPR_STMT) {
            auto* es = static_cast<ExprStmt*>(stmt.get());
            if (es->expr) {
                Expr* e = es->expr.get();
                // v4.9: Handle ASSIGN (e.g., vm = _M_destroy(...))
                if (e->type == NT_ASSIGN) {
                    e = static_cast<Assign*>(e)->value.get();
                    if (!e) return "";
                }
                if (e->type == NT_CALL) {
                    auto* c = static_cast<Call*>(e);
                    if (c->callee_name.find("_M_destroy") != std::string::npos) {
                        if (!c->args.empty() && c->args[0] && c->args[0]->type == NT_VAR_REF)
                            return static_cast<VarRef*>(c->args[0].get())->name;
                    }
                }
            }
        } else if (stmt->type == NT_BLOCK) {
            auto* blk = static_cast<Block*>(stmt.get());
            for (auto& s : blk->statements) {
                std::string r = extractStrVar(s);
                if (!r.empty()) return r;
            }
        } else if (stmt->type == NT_IF) {
            auto* ifn = static_cast<If*>(stmt.get());
            std::string r = extractStrVar(ifn->then_branch);
            if (!r.empty()) return r;
            r = extractStrVar(ifn->else_branch);
            if (!r.empty()) return r;
        }
        return "";
    };

    // Helper: recursively search for __sync_fetch_and_sub in a statement, extract refVar
    static std::function<std::string(StmtPtr&)> extractRefFromStmt;
    extractRefFromStmt = [](StmtPtr& stmt) -> std::string {
        if (!stmt) return "";
        if (stmt->type == NT_EXPR_STMT) {
            auto* es = static_cast<ExprStmt*>(stmt.get());
            std::string r = extractRefVar(es->expr.get());
            if (!r.empty()) return r;
        } else if (stmt->type == NT_BLOCK) {
            auto* blk = static_cast<Block*>(stmt.get());
            for (auto& s : blk->statements) {
                std::string r = extractRefFromStmt(s);
                if (!r.empty()) return r;
            }
        } else if (stmt->type == NT_IF) {
            auto* ifn = static_cast<If*>(stmt.get());
            std::string r = extractRefFromStmt(ifn->then_branch);
            if (!r.empty()) return r;
            r = extractRefFromStmt(ifn->else_branch);
            if (!r.empty()) return r;
        }
        return "";
    };

    // Recurse into children
    switch (root->type) {
    case NT_BLOCK: {
        auto* blk = static_cast<Block*>(root.get());
        for (auto& s : blk->statements)
            collapseCowDestructors(s);

        // Scan for COW destructor patterns
        struct CowPattern {
            std::string ref_var;   // refcount variable (from __sync_fetch_and_sub &arg)
            std::string str_var;   // variable passed to _M_destroy
            size_t first_idx;
        };
        std::vector<CowPattern> patterns;

        for (size_t i = 0; i < blk->statements.size(); i++) {
            auto& s = blk->statements[i];
            if (!s) continue;
            if (s->type != NT_IF) continue;
            auto* ifNode = static_cast<If*>(s.get());
            if (!ifNode->condition || !ifNode->then_branch) continue;

            // Try to extract strVar from body (recursively)
            std::string strVar = extractStrVar(ifNode->then_branch);
            if (strVar.empty()) continue;

            // Try to extract refVar from condition (direct __sync_fetch_and_sub)
            std::string refVar;
            if (ifNode->condition->type == NT_BINARY_OP) {
                auto* condBin = static_cast<BinaryOp*>(ifNode->condition.get());
                refVar = extractRefVar(condBin->left.get());
                if (refVar.empty()) refVar = extractRefVar(condBin->right.get());
            }

            // If not found in condition, check previous statement and else branch
            if (refVar.empty() && i > 0) {
                refVar = extractRefFromStmt(blk->statements[i-1]);
            }
            if (refVar.empty()) {
                refVar = extractRefFromStmt(ifNode->else_branch);
            }

            if (refVar.empty()) continue;

            // Check if this pattern already exists
            bool exists = false;
            for (auto& p : patterns) {
                if (p.ref_var == refVar && p.str_var == strVar) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                patterns.push_back({refVar, strVar, i});
            } else {
                // Duplicate pattern — mark for removal
                s.reset();
            }
        }

        // Remove null statements
        blk->statements.erase(
            std::remove_if(blk->statements.begin(), blk->statements.end(),
                           [](const StmtPtr& s) { return !s; }),
            blk->statements.end());
        break;
    }
    case NT_IF: {
        auto* ifNode = static_cast<If*>(root.get());
        collapseCowDestructors(ifNode->then_branch);
        collapseCowDestructors(ifNode->else_branch);
        break;
    }
    case NT_WHILE: {
        auto* wh = static_cast<While*>(root.get());
        collapseCowDestructors(wh->body);
        break;
    }
    case NT_DO_WHILE: {
        auto* dw = static_cast<DoWhile*>(root.get());
        collapseCowDestructors(dw->body);
        break;
    }
    case NT_FOR: {
        auto* fr = static_cast<For*>(root.get());
        collapseCowDestructors(fr->body);
        break;
    }
    default:
        break;
    }
}

// ── Pass 18: Eliminate unreachable code after return (v4.9) ──
// Removes statements after NT_RETURN within the same block.
// Also handles do { ... return; } while(...) pattern where code after
// the loop is unreachable because the loop body always returns.
void eliminateUnreachableCode(StmtPtr& root) {
    if (!root) return;

    switch (root->type) {
    case NT_BLOCK: {
        auto* blk = static_cast<Block*>(root.get());

        // Find the first RETURN statement
        ssize_t returnIdx = -1;
        for (size_t i = 0; i < blk->statements.size(); i++) {
            if (blk->statements[i] && blk->statements[i]->type == NT_RETURN) {
                returnIdx = (ssize_t)i;
                break;
            }
        }
        if (returnIdx < 0) break;  // No return — nothing to do

        // Check if there are statements after the return
        if ((size_t)(returnIdx + 1) >= blk->statements.size()) break;

        // v5.7: Check if there are loops or if-else blocks after the return.
        // Loops are always potentially reachable through back edges,
        // even if they appear after a RETURN in the linear sequence.
        // Similarly, if-else blocks after a return may be reachable through
        // the other branch of a conditional that was flattened into the
        // sequence. This happens when the function has an if-then pattern
        // where the then-branch ends with a RETURN, and the else-branch
        // (fall-through) contains loops or if-else. The structurer may emit
        // the RETURN at the top level, followed by the rest of the sequence.
        // Ghidra does NOT remove loops/if-else after returns — neither should we.
        // v61.0: Also check for IF blocks (not just loops), because the
        // structurer can emit a conditional return at the top level of a
        // SEQUENCE, followed by the if-else body that is reachable through
        // the other branch of the condition.
        bool hasStructuredAfterReturn = false;
        for (size_t i = (size_t)(returnIdx + 1); i < blk->statements.size(); i++) {
            if (blk->statements[i] &&
                (blk->statements[i]->type == NT_WHILE ||
                 blk->statements[i]->type == NT_DO_WHILE ||
                 blk->statements[i]->type == NT_FOR ||
                 blk->statements[i]->type == NT_IF)) {
                hasStructuredAfterReturn = true;
                break;
            }
        }

        if (hasStructuredAfterReturn) {
            // v5.7: Only remove non-loop/non-IF statements after the return.
            // Keep loops, IF blocks, and their preceding assignment statements
            // (which may be the conditions that feed into the IF blocks).
            std::vector<StmtPtr> kept;
            kept.push_back(std::move(blk->statements[returnIdx])); // keep the return

            // Move structured statements and their supporting assignments
            // to after the return
            for (size_t i = (size_t)(returnIdx + 1); i < blk->statements.size(); i++) {
                if (blk->statements[i] &&
                    (blk->statements[i]->type == NT_WHILE ||
                     blk->statements[i]->type == NT_DO_WHILE ||
                     blk->statements[i]->type == NT_FOR ||
                     blk->statements[i]->type == NT_IF)) {
                    kept.push_back(std::move(blk->statements[i]));
                }
            }

            // Truncate at the return, then append the kept blocks
            blk->statements.resize(returnIdx);
            for (auto& s : kept) {
                blk->statements.push_back(std::move(s));
            }
        } else {
            // No structured blocks after return — safe to truncate everything
            blk->statements.resize((size_t)(returnIdx + 1));
        }
        break;
    }
    default:
        break;
    }
}

// ════════════════════════════════════════════════════════════════════
// Pass 20: Function-level dead assignment elimination (v11.1)
// 对标 Ghidra RuleDeadCode + ActionDeadRemoval
// Scans the ENTIRE function body to find variables that are assigned
// but never read anywhere. Unlike eliminateDeadAssigns (which does
// block-local liveness), this catches cross-block dead variables.
// For dead assignments with side effects (function calls), strips the
// assignment but keeps the call expression.
// ════════════════════════════════════════════════════════════════════
static void collectAllVarReads(const ctree::Expr* expr,
                               std::map<std::string, int>& readCount) {
    if (!expr) return;
    switch (expr->type) {
        case NT_VAR_REF: {
            const auto* vr = static_cast<const VarRef*>(expr);
            readCount[vr->name]++;
            break;
        }
        case NT_BINARY_OP: {
            const auto* bin = static_cast<const BinaryOp*>(expr);
            collectAllVarReads(bin->left.get(), readCount);
            collectAllVarReads(bin->right.get(), readCount);
            break;
        }
        case NT_UNARY_OP: {
            const auto* un = static_cast<const UnaryOp*>(expr);
            collectAllVarReads(un->operand.get(), readCount);
            break;
        }
        case NT_ASSIGN: {
            const auto* as = static_cast<const Assign*>(expr);
            // For "x = expr" where x is a plain VarRef, x is written (not read).
            // But for "*ptr = expr" or "ptr->field = expr", the target expression
            // contains reads (of ptr) that MUST be counted.
            // For compound "x += expr", x is both read and written.
            bool plainVarTarget = (as->target && as->target->type == NT_VAR_REF);
            bool simpleAssign = (as->op == "=");
            if (!(plainVarTarget && simpleAssign))
                collectAllVarReads(as->target.get(), readCount);
            collectAllVarReads(as->value.get(), readCount);
            break;
        }
        case NT_CALL: {
            const auto* call = static_cast<const Call*>(expr);
            for (auto& arg : call->args)
                collectAllVarReads(arg.get(), readCount);
            break;
        }
        case NT_CAST: {
            const auto* cast = static_cast<const Cast*>(expr);
            collectAllVarReads(cast->expr.get(), readCount);
            break;
        }
        case NT_TERNARY: {
            const auto* t = static_cast<const Ternary*>(expr);
            collectAllVarReads(t->condition.get(), readCount);
            collectAllVarReads(t->true_expr.get(), readCount);
            collectAllVarReads(t->false_expr.get(), readCount);
            break;
        }
        case NT_INDEX: {
            const auto* idx = static_cast<const Index*>(expr);
            collectAllVarReads(idx->array.get(), readCount);
            collectAllVarReads(idx->index.get(), readCount);
            break;
        }
        case NT_MEMBER: {
            const auto* m = static_cast<const MemberAccess*>(expr);
            collectAllVarReads(m->base.get(), readCount);
            break;
        }
        case NT_DEREF: {
            const auto* d = static_cast<const Deref*>(expr);
            collectAllVarReads(d->operand.get(), readCount);
            break;
        }
        case NT_ADDRESSOF: {
            const auto* a = static_cast<const AddressOf*>(expr);
            collectAllVarReads(a->operand.get(), readCount);
            break;
        }
        default:
            break;
    }
}

static void collectAllVarReadsStmt(const ctree::Stmt* stmt,
                                   std::map<std::string, int>& readCount) {
    if (!stmt) return;
    switch (stmt->type) {
        case NT_BLOCK: {
            const auto* blk = static_cast<const Block*>(stmt);
            for (const auto& s : blk->statements)
                collectAllVarReadsStmt(s.get(), readCount);
            break;
        }
        case NT_EXPR_STMT: {
            const auto* es = static_cast<const ExprStmt*>(stmt);
            collectAllVarReads(es->expr.get(), readCount);
            break;
        }
        case NT_RETURN: {
            const auto* ret = static_cast<const Return*>(stmt);
            collectAllVarReads(ret->value.get(), readCount);
            break;
        }
        case NT_IF: {
            const auto* ifn = static_cast<const If*>(stmt);
            collectAllVarReads(ifn->condition.get(), readCount);
            collectAllVarReadsStmt(ifn->then_branch.get(), readCount);
            collectAllVarReadsStmt(ifn->else_branch.get(), readCount);
            break;
        }
        case NT_WHILE: {
            const auto* wh = static_cast<const While*>(stmt);
            collectAllVarReads(wh->condition.get(), readCount);
            collectAllVarReadsStmt(wh->body.get(), readCount);
            break;
        }
        case NT_DO_WHILE: {
            const auto* dw = static_cast<const DoWhile*>(stmt);
            collectAllVarReads(dw->condition.get(), readCount);
            collectAllVarReadsStmt(dw->body.get(), readCount);
            break;
        }
        case NT_FOR: {
            const auto* fr = static_cast<const For*>(stmt);
            collectAllVarReads(fr->condition.get(), readCount);
            collectAllVarReads(fr->increment.get(), readCount);
            collectAllVarReadsStmt(fr->init.get(), readCount);
            collectAllVarReadsStmt(fr->body.get(), readCount);
            break;
        }
        case NT_SWITCH: {
            const auto* sw = static_cast<const Switch*>(stmt);
            collectAllVarReads(sw->expr.get(), readCount);
            for (auto& [val, body] : sw->cases)
                collectAllVarReadsStmt(body.get(), readCount);
            collectAllVarReadsStmt(sw->default_body.get(), readCount);
            break;
        }
        default:
            break;
    }
}

static void eliminateGlobalDeadAssigns(StmtPtr& root,
                                       const std::map<std::string, int>& readCount) {
    if (!root) return;
    switch (root->type) {
        case NT_BLOCK: {
            auto* blk = static_cast<Block*>(root.get());
            std::vector<StmtPtr> result;
            result.reserve(blk->statements.size());
            for (auto& s : blk->statements) {
                if (!s) continue;
                // Recurse into nested structures first
                eliminateGlobalDeadAssigns(s, readCount);

                // Check if this is a dead assignment
                std::string target = getAssignTarget(s.get());
                if (!target.empty()) {
                    int reads = readCount.count(target) ? readCount.at(target) : 0;
                    if (reads == 0) {
                        // Variable is never read anywhere in the function
                        auto* es = static_cast<ExprStmt*>(s.get());
                        auto* assign = static_cast<Assign*>(es->expr.get());
                        if (exprHasSideEffects(assign->value.get())) {
                            // Keep the side effect, drop the assignment
                            es->expr = std::move(assign->value);
                            result.push_back(std::move(s));
                        }
                        // else: drop entirely
                        continue;
                    }
                }
                result.push_back(std::move(s));
            }
            blk->statements = std::move(result);
            break;
        }
        case NT_IF: {
            auto* ifn = static_cast<If*>(root.get());
            eliminateGlobalDeadAssigns(ifn->then_branch, readCount);
            eliminateGlobalDeadAssigns(ifn->else_branch, readCount);
            break;
        }
        case NT_WHILE: {
            auto* wh = static_cast<While*>(root.get());
            eliminateGlobalDeadAssigns(wh->body, readCount);
            break;
        }
        case NT_DO_WHILE: {
            auto* dw = static_cast<DoWhile*>(root.get());
            eliminateGlobalDeadAssigns(dw->body, readCount);
            break;
        }
        case NT_FOR: {
            auto* fr = static_cast<For*>(root.get());
            eliminateGlobalDeadAssigns(fr->body, readCount);
            break;
        }
        case NT_SWITCH: {
            auto* sw = static_cast<Switch*>(root.get());
            for (auto& [val, body] : sw->cases)
                eliminateGlobalDeadAssigns(body, readCount);
            eliminateGlobalDeadAssigns(sw->default_body, readCount);
            break;
        }
        default:
            break;
    }
}

void eliminateGlobalDeadCode(StmtPtr& root) {
    if (!root) return;
    // Count all variable reads in the entire function body
    std::map<std::string, int> readCount;
    collectAllVarReadsStmt(root.get(), readCount);

    // Eliminate assignments to variables that are never read
    eliminateGlobalDeadAssigns(root, readCount);
}

} // namespace ctree
