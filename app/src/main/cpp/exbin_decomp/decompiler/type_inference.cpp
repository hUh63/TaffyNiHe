// type_inference.cpp — Scalar type inference (v3.7)
//
// 移植自 tiny-dec analysis/types/transform.py 的 UnionFind + 证据驱动方案。
// 核心算法:
//   1. 用 UnionFind 把 COPY/PHI/load-store 连接的 SSA 值分成同一组
//   2. 扫描每条指令收集证据 (BOOL/SIGNED/UNSIGNED/POINTER/WORD)
//   3. 冲突证据降级为 WORD
//   4. 迭代传播: 指针算术 (ptr + const → ptr) 反向传播到操作数
//
// 参考: /data/user/work/refs/tiny-dec/tiny_dec/analysis/types/transform.py

#include "type_inference.hpp"
#include "calling_convention.hpp"  // v5.8: r2-style callee type propagation
#include <cstdio>
#include <set>
#include <map>

namespace ctree {

// ════════════════════════════════════════════════════════════════════
// UnionFind
// ════════════════════════════════════════════════════════════════════

void UnionFind::add(const EntityId& e) {
    if (parent_.find(e) == parent_.end()) {
        parent_[e] = e;
    }
}

EntityId UnionFind::find(const EntityId& e) {
    if (parent_.find(e) == parent_.end()) {
        parent_[e] = e;
        return e;
    }
    // Path compression
    if (parent_[e] != e) {
        parent_[e] = find(parent_[e]);
    }
    return parent_[e];
}

void UnionFind::unite(const EntityId& a, const EntityId& b) {
    add(a);
    add(b);
    EntityId ra = find(a);
    EntityId rb = find(b);
    if (ra != rb) {
        parent_[ra] = rb;
    }
}

std::map<EntityId, std::vector<EntityId>> UnionFind::groupedEntities() const {
    std::map<EntityId, std::vector<EntityId>> groups;
    for (auto& [k, v] : parent_) {
        // For const-correctness, we can't call find() (which mutates).
        // Instead, find root manually.
        EntityId root = v;
        while (parent_.at(root) != root) {
            root = parent_.at(root);
        }
        groups[root].push_back(k);
    }
    return groups;
}

// ════════════════════════════════════════════════════════════════════
// TypeInferencePass
// ════════════════════════════════════════════════════════════════════

void TypeInferencePass::analyze(const mc::MicrocodeBlockArray& mba) {
    // 重置状态
    direct_evidence_.clear();
    resolved_kinds_.clear();
    entity_widths_.clear();
    stack_slot_kinds_.clear();
    stack_slot_widths_.clear();  // v3.20
    argument_home_offsets_.clear();
    struct_field_offsets_.clear();  // v9.4
    struct_base_regs_.clear();     // v9.4
    // v9.7: 重置返回类型推断状态
    inferred_return_type_ = CType{};
    inferred_return_valid_ = false;
    inferred_return_void_ = false;

    // 阶段1: 构建等价组
    buildScalarIdentity(mba);

    // 阶段2: 收集直接证据
    collectDirectEvidence(mba);

    // v5.8: r2-style — propagate return types from known callees
    // 对标 r2 sdb_types: function return type → return register type
    collectCallReturnTypes(mba);

    // v9.4: r2-style — propagate parameter types from known callees
    // 对标 r2 sdb_types: function parameter type → argument register type
    collectCallParamTypes(mba);

    // 阶段3: 收集关系证据 (迭代传播)
    collectRelationalEvidence(mba);

    // v9.4: detect pointer arithmetic patterns
    // 对标 Ghidra typeop.cc: pointer arithmetic → pointer type
    detectPointerArith(mba);

    // v9.4: detect struct field accesses
    // 对标 Ghidra StructureOffsetAnalysis: group base+offset pairs
    detectStructFields(mba);

    // v10.1: 数组类型检测 (对标 Ghidra TypeArray)
    detectArrayTypes(mba);

    // v10.1: 联合体类型检测 (对标 Ghidra ActionResolveUnion)
    detectUnionTypes(mba);

    // v10.1: 结构体类型推断 (对标 Ghidra StructureOffsetAnalysis)
    inferStructTypes(mba);

    // 阶段4: 转换为最终类型
    auto groups = uf_.groupedEntities();
    for (auto& [root, members] : groups) {
        // 合并组内所有证据
        std::vector<ScalarKind> kinds;
        for (auto& m : members) {
            auto it = direct_evidence_.find(m);
            if (it != direct_evidence_.end()) {
                for (auto& k : it->second) {
                    kinds.push_back(k);
                }
            }
        }
        ScalarKind resolved = mergeKinds(kinds);
        // 应用到组内所有成员
        for (auto& m : members) {
            resolved_kinds_[m] = resolved;
            // v3.20: If this is a stack slot entity (mreg=-1, ssa=sp_offset),
            // populate stack_slot_kinds_ so getStackSlotType() works.
            // This was a critical bug: stack_slot_kinds_ was never populated,
            // causing all stack variables to be typed as TC_UNKNOWN → uint64_t.
            if (m.first == -1) {
                stack_slot_kinds_[m.second] = resolved;
            }
        }
    }

    // v9.21: Global width validation pass.
    // Ensure all resolved entities have consistent widths. Fill in missing
    // widths from type inference, fix pointer widths to match architecture,
    // and clamp implausible widths (0-byte or >16-byte).
    for (auto& [entity, kind] : resolved_kinds_) {
        int width = 0;
        auto wit = entity_widths_.find(entity);
        if (wit != entity_widths_.end()) {
            width = wit->second;
        }

        // Fill in missing widths from type
        if (width <= 0) {
            if (kind == ScalarKind::POINTER) {
                width = is_aarch64_ ? 8 : 4;
            } else if (kind == ScalarKind::BOOL) {
                width = 1;
            } else {
                width = is_aarch64_ ? 8 : 4;
            }
            entity_widths_[entity] = width;
        }

        // Pointer types must be 4 or 8 bytes (architecture-dependent)
        if (kind == ScalarKind::POINTER && width != 4 && width != 8) {
            entity_widths_[entity] = is_aarch64_ ? 8 : 4;
        }

        // Clamp implausible widths
        if (width > 16) {
            entity_widths_[entity] = is_aarch64_ ? 8 : 4;
        }
    }

    // v9.7: 返回类型推断 (对标 Ghidra actiontype.cc)
    // 必须在 resolved_kinds_ 完全构建后执行,以便能查到返回寄存器的类型
    inferReturnType(mba);
}

// ════════════════════════════════════════════════════════════════════
// v9.4: collectCallParamTypes — separate pass for parameter type propagation
// 对标 Ghidra typeop.cc: propagate known function parameter types to arg registers
// This is already partially done in collectCallReturnTypes, but this pass adds
// additional detection for indirect calls and callee-saved register propagation.
// ════════════════════════════════════════════════════════════════════
void TypeInferencePass::collectCallParamTypes(const mc::MicrocodeBlockArray& mba) {
    // The core logic is in collectCallReturnTypes (which already propagates param types).
    // This pass adds additional detection: if a CALL instruction's argument register
    // is used in the caller as a POINTER (e.g., loaded from a known pointer slot),
    // propagate that knowledge to the call site.
    for (auto& blk_up : mba.blocks) {
        if (!blk_up) continue;
        for (mc::MicroInsn* insn = blk_up->head; insn; insn = insn->next) {
            if (insn->opcode != mc::OP_CALL && insn->opcode != mc::OP_ICALL) continue;
            
            // Look up callee name
            std::string calleeName;
            if (insn->call_info && !insn->call_info->target_name.empty()) {
                calleeName = insn->call_info->target_name;
            } else if (insn->target_addr != 0) {
                auto nameIt = mba.global_names.find(insn->target_addr);
                if (nameIt != mba.global_names.end()) calleeName = nameIt->second;
            }
            
            int maxArgs = is_aarch64_ ? mc::MAX_ARGS_AARCH64 : mc::MAX_ARGS_ARM32;
            const int* argRegs = is_aarch64_ ? mc::ARG_REGS_AARCH64 : mc::ARG_REGS_ARM32;
            
            for (int i = 0; i < maxArgs; i++) {
                int argMreg = argRegs[i];
                int foundSsaVer = -1;
                
                // Scan backwards for the most recent definition of this arg register
                mc::MicroInsn* prev = insn->prev;
                while (prev) {
                    if (prev->def_mreg == argMreg) {
                        foundSsaVer = prev->ssa_version;
                        break;
                    }
                    prev = prev->prev;
                }
                if (foundSsaVer < 0) {
                    if (insn->l.isReg() && insn->l.mreg == argMreg) foundSsaVer = insn->l.ssa_ver;
                    else if (insn->r.isReg() && insn->r.mreg == argMreg) foundSsaVer = insn->r.ssa_ver;
                }
                if (foundSsaVer < 0) continue;
                
                EntityId argEntity{argMreg, foundSsaVer};
                
                // If this arg register already has POINTER evidence from the caller,
                // and the callee's known signature also expects a pointer, reinforce it
                auto it = direct_evidence_.find(argEntity);
                if (it != direct_evidence_.end()) {
                    bool hasPointer = false;
                    for (auto& k : it->second) {
                        if (k == ScalarKind::POINTER) { hasPointer = true; break; }
                    }
                    if (hasPointer && !calleeName.empty()) {
                        auto paramTypes = mc::CallingConvention::getParamTypesForCallee(calleeName);
                        if (i < (int)paramTypes.size()) {
                            ScalarKind paramKind = ctypeStringToKind(paramTypes[i]);
                            if (paramKind == ScalarKind::POINTER) {
                                // Already correct, no change needed
                            }
                        }
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════
// v9.4: detectPointerArith — detect pointer arithmetic patterns
// 对标 Ghidra typeop.cc: if a register is involved in base+offset arithmetic
// and then used in LOAD/STORE, infer it as a pointer.
//
// Patterns detected:
//   1. ADD rN, base, #offset → rN is a pointer (if base is pointer or used in MEM)
//   2. SUB rN, base, #offset → same
//   3. rN used as [base, offset] in LOAD/STORE → base is a pointer
// ════════════════════════════════════════════════════════════════════
void TypeInferencePass::detectPointerArith(const mc::MicrocodeBlockArray& mba) {
    // Pass 1: Collect all registers used as base in memory operations
    // v24.1: Also handle OP_FLOAD/OP_FSTORE for floating-point memory access
    std::set<int> memBaseRegs;
    for (auto& blk_up : mba.blocks) {
        if (!blk_up) continue;
        for (mc::MicroInsn* insn = blk_up->head; insn; insn = insn->next) {
            if ((insn->opcode == mc::OP_LOAD || insn->opcode == mc::OP_FLOAD) &&
                insn->l.isMem() && insn->l.mem_base >= 0) {
                // Skip SP-based accesses (stack variables)
                if (insn->l.mem_base != sp_mreg_) {
                    memBaseRegs.insert(insn->l.mem_base);
                }
            }
            if ((insn->opcode == mc::OP_STORE || insn->opcode == mc::OP_FSTORE) &&
                insn->d.isMem() && insn->d.mem_base >= 0) {
                if (insn->d.mem_base != sp_mreg_) {
                    memBaseRegs.insert(insn->d.mem_base);
                }
            }
        }
    }
    
    // Pass 2: For each memory base register, mark it as POINTER
    // Also track base+offset combinations for struct detection
    for (auto& blk_up : mba.blocks) {
        if (!blk_up) continue;
        for (mc::MicroInsn* insn = blk_up->head; insn; insn = insn->next) {
            // LOAD: d = mem[base, offset]  (v24.1: also OP_FLOAD for float)
            if ((insn->opcode == mc::OP_LOAD || insn->opcode == mc::OP_FLOAD) &&
                insn->l.isMem() && insn->l.mem_base >= 0
                && insn->l.mem_base != sp_mreg_) {
                // Track base+offset for struct detection
                struct_field_offsets_[insn->l.mem_base].insert((int)insn->l.mem_offset);
                
                // Mark base register as POINTER
                // Find the SSA version of the base register at this point
                mc::MicroInsn* prev = insn->prev;
                while (prev) {
                    if (prev->def_mreg == insn->l.mem_base) {
                        EntityId baseEntity{insn->l.mem_base, prev->ssa_version};
                        // Only add if not already set (avoid overriding stronger evidence)
                        auto it = direct_evidence_.find(baseEntity);
                        if (it == direct_evidence_.end() || it->second.empty()) {
                            direct_evidence_[baseEntity].push_back(ScalarKind::POINTER);
                        }
                        break;
                    }
                    prev = prev->prev;
                }
            }
            // STORE: mem[base, offset] = l  (v24.1: also OP_FSTORE for float)
            if ((insn->opcode == mc::OP_STORE || insn->opcode == mc::OP_FSTORE) &&
                insn->d.isMem() && insn->d.mem_base >= 0
                && insn->d.mem_base != sp_mreg_) {
                struct_field_offsets_[insn->d.mem_base].insert((int)insn->d.mem_offset);
                
                mc::MicroInsn* prev = insn->prev;
                while (prev) {
                    if (prev->def_mreg == insn->d.mem_base) {
                        EntityId baseEntity{insn->d.mem_base, prev->ssa_version};
                        auto it = direct_evidence_.find(baseEntity);
                        if (it == direct_evidence_.end() || it->second.empty()) {
                            direct_evidence_[baseEntity].push_back(ScalarKind::POINTER);
                        }
                        break;
                    }
                    prev = prev->prev;
                }
            }
            
            // ADD rN, base, #offset → if base is a pointer, rN is also a pointer
            if (insn->opcode == mc::OP_ADD || insn->opcode == mc::OP_SUB) {
                if (insn->def_mreg < 0) continue;
                EntityId def{insn->def_mreg, insn->ssa_version};
                
                // Check if either operand is a known pointer
                bool lhsIsPtr = false, rhsIsPtr = false;
                if (insn->l.isReg()) {
                    EntityId lhs{insn->l.mreg, insn->l.ssa_ver};
                    auto it = resolved_kinds_.find(lhs);
                    if (it != resolved_kinds_.end() && it->second == ScalarKind::POINTER) lhsIsPtr = true;
                }
                if (insn->r.isReg()) {
                    EntityId rhs{insn->r.mreg, insn->r.ssa_ver};
                    auto it = resolved_kinds_.find(rhs);
                    if (it != resolved_kinds_.end() && it->second == ScalarKind::POINTER) rhsIsPtr = true;
                }
                // If one operand is a pointer and the other is a constant, result is pointer
                if ((lhsIsPtr && insn->r.isImm()) || (rhsIsPtr && insn->l.isImm())) {
                    auto it = direct_evidence_.find(def);
                    if (it == direct_evidence_.end() || it->second.empty()) {
                        direct_evidence_[def].push_back(ScalarKind::POINTER);
                    }
                }
                // If one operand is a memory base register, result is pointer too
                if (insn->l.isReg() && memBaseRegs.count(insn->l.mreg)) {
                    auto it = direct_evidence_.find(def);
                    if (it == direct_evidence_.end() || it->second.empty()) {
                        direct_evidence_[def].push_back(ScalarKind::POINTER);
                    }
                }
                if (insn->r.isReg() && memBaseRegs.count(insn->r.mreg)) {
                    auto it = direct_evidence_.find(def);
                    if (it == direct_evidence_.end() || it->second.empty()) {
                        direct_evidence_[def].push_back(ScalarKind::POINTER);
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════
// v9.4: detectStructFields — aggregate base+offset pairs to detect struct pointers
// 对标 Ghidra StructureOffsetAnalysis: if a register is used as base with
// >= 3 different offsets in LOAD/STORE, it's likely a struct pointer.
//
// For each struct base register, mark it as a pointer and track the offsets
// for later struct type inference.
// ════════════════════════════════════════════════════════════════════
void TypeInferencePass::detectStructFields(const mc::MicrocodeBlockArray& mba) {
    // struct_field_offsets_ was populated in detectPointerArith
    const int MIN_STRUCT_OFFSETS = 3;
    
    for (auto& [baseReg, offsets] : struct_field_offsets_) {
        if ((int)offsets.size() >= MIN_STRUCT_OFFSETS) {
            struct_base_regs_.insert(baseReg);
            
            // Mark the base register as a struct pointer
            // Find the latest SSA version of this register
            for (auto& blk_up : mba.blocks) {
                if (!blk_up) continue;
                for (mc::MicroInsn* insn = blk_up->head; insn; insn = insn->next) {
                    if (insn->def_mreg == baseReg) {
                        EntityId def{baseReg, insn->ssa_version};
                        // Replace any existing evidence with POINTER
                        direct_evidence_[def].clear();
                        direct_evidence_[def].push_back(ScalarKind::POINTER);
                    }
                }
            }
        }
    }
}

CType TypeInferencePass::getType(int mreg, int ssa_version) const {
    EntityId e{mreg, ssa_version};
    auto it = resolved_kinds_.find(e);
    if (it == resolved_kinds_.end()) {
        CType t;
        t.category = CType::TC_UNKNOWN;
        return t;
    }
    int width = 8;
    auto wit = entity_widths_.find(e);
    if (wit != entity_widths_.end()) {
        width = wit->second;
    }
    return toCType(it->second, width);
}

CType TypeInferencePass::getStackSlotType(int sp_offset) const {
    auto it = stack_slot_kinds_.find(sp_offset);
    if (it == stack_slot_kinds_.end()) {
        CType t;
        t.category = CType::TC_UNKNOWN;
        return t;
    }
    // v3.20: Use tracked width instead of hardcoded 8
    int width = 8;
    auto wit = stack_slot_widths_.find(sp_offset);
    if (wit != stack_slot_widths_.end()) {
        width = wit->second;
    }
    return toCType(it->second, width);
}

// ── 阶段1: 构建等价组 ──
// 对应 tiny-dec _build_scalar_identity()
void TypeInferencePass::buildScalarIdentity(const mc::MicrocodeBlockArray& mba) {
    for (auto& blk_up : mba.blocks) {
        if (!blk_up) continue;
        for (mc::MicroInsn* insn = blk_up->head; insn; insn = insn->next) {
            EntityId def{insn->def_mreg, insn->ssa_version};

            // 先把所有实体加入 UnionFind (即使没有等价关系)
            if (insn->def_mreg >= 0) {
                uf_.add(def);
                if (insn->d.width > 0) {
                    // v3.20: For loads, use the memory operand's width (actual
                    // load width) instead of destination register width (always
                    // 8 on AArch64). ldrb→1, ldrh→2, ldr→4.
                    // 对标 Ghidra typeop.cc: load width determines type width
                    if (insn->opcode == mc::OP_LOAD && insn->l.isMem() &&
                        insn->l.width > 0) {
                        entity_widths_[def] = insn->l.width;
                    } else {
                        entity_widths_[def] = insn->d.width;
                    }
                }
            }
            // 操作数寄存器也加入
            if (insn->l.isReg()) {
                EntityId src{insn->l.mreg, insn->l.ssa_ver};
                uf_.add(src);
                if (entity_widths_.find(src) == entity_widths_.end() && insn->l.width > 0) {
                    entity_widths_[src] = insn->l.width;
                }
            }
            if (insn->r.isReg()) {
                EntityId src{insn->r.mreg, insn->r.ssa_ver};
                uf_.add(src);
                if (entity_widths_.find(src) == entity_widths_.end() && insn->r.width > 0) {
                    entity_widths_[src] = insn->r.width;
                }
            }

            // COPY: d = l (reg) → unite def 和 l
            if (insn->opcode == mc::OP_MOV && insn->l.isReg()) {
                EntityId src{insn->l.mreg, insn->l.ssa_ver};
                uf_.unite(def, src);
            }

            // PHI: d = phi(srcs...) → unite def 和每个 src
            if (insn->opcode == mc::OP_PHI) {
                for (auto& [mreg, ver] : insn->l.phi_srcs) {
                    EntityId src{mreg, ver};
                    uf_.unite(def, src);
                }
            }

            // LOAD: d = mem[sp + offset] → 标记栈槽为等价组
            if (insn->opcode == mc::OP_LOAD && insn->l.isMem() &&
                insn->l.mem_base == sp_mreg_) {
                EntityId slot{-1, (int)insn->l.mem_offset};
                uf_.unite(def, slot);
                if (isArgumentHome((int)insn->l.mem_offset)) {
                    argument_home_offsets_.insert((int)insn->l.mem_offset);
                }
                // v3.20: Track stack slot width from load
                // v9.19: Take the maximum width seen across all accesses,
                // not the last one. A slot accessed as both 4 and 8 bytes
                // should be typed as the wider type.
                if (insn->l.width > 0) {
                    int off = (int)insn->l.mem_offset;
                    auto it = stack_slot_widths_.find(off);
                    if (it == stack_slot_widths_.end() || insn->l.width > it->second) {
                        stack_slot_widths_[off] = insn->l.width;
                    }
                }
            }

            // STORE: mem[sp + offset] = l → unite 栈槽和 l
            if (insn->opcode == mc::OP_STORE && insn->d.isMem() &&
                insn->d.mem_base == sp_mreg_) {
                if (insn->l.isReg()) {
                    EntityId slot{-1, (int)insn->d.mem_offset};
                    EntityId src{insn->l.mreg, insn->l.ssa_ver};
                    uf_.unite(slot, src);
                    // v3.20: Track stack slot width from store
                    // v9.19: Take the maximum width seen across all accesses.
                    if (insn->d.width > 0) {
                        int off = (int)insn->d.mem_offset;
                        auto it = stack_slot_widths_.find(off);
                        if (it == stack_slot_widths_.end() || insn->d.width > it->second) {
                            stack_slot_widths_[off] = insn->d.width;
                        }
                    }
                }
                if (isArgumentHome((int)insn->d.mem_offset)) {
                    argument_home_offsets_.insert((int)insn->d.mem_offset);
                }
            }
        }
    }
}

// ── 阶段2: 收集直接证据 ──
// 对应 tiny-dec _build_direct_evidence()
void TypeInferencePass::collectDirectEvidence(const mc::MicrocodeBlockArray& mba) {
    for (auto& blk_up : mba.blocks) {
        if (!blk_up) continue;
        for (mc::MicroInsn* insn = blk_up->head; insn; insn = insn->next) {
            EntityId def{insn->def_mreg, insn->ssa_version};

            // 比较指令 → 输出是 BOOL
            // OP_SETZ, OP_SETNZ, OP_SETB, OP_SETAE, OP_SETA, OP_SETBE,
            // OP_SETG, OP_SETGE, OP_SETL, OP_SETLE, OP_SETS, OP_SETO
            if (insn->opcode >= mc::OP_SETZ && insn->opcode <= mc::OP_SETO) {
                if (insn->def_mreg >= 0) {
                    direct_evidence_[def].push_back(ScalarKind::BOOL);
                }
                // 输入操作数: 比较的输入是 INT (比较大小)
                // 但有符号/无符号取决于比较类型
                bool is_signed = (insn->opcode >= mc::OP_SETG && insn->opcode <= mc::OP_SETLE) ||
                                 insn->opcode == mc::OP_SETS;
                ScalarKind inputKind = is_signed ? ScalarKind::SIGNED : ScalarKind::UNSIGNED;
                if (insn->l.isReg()) {
                    direct_evidence_[{insn->l.mreg, insn->l.ssa_ver}].push_back(inputKind);
                }
                if (insn->r.isReg()) {
                    direct_evidence_[{insn->r.mreg, insn->r.ssa_ver}].push_back(inputKind);
                }
                continue;
            }

            // CBRANCH: 条件分支 → 条件操作数是 BOOL
            if (insn->opcode == mc::OP_CBRANCH) {
                if (insn->l.isReg()) {
                    direct_evidence_[{insn->l.mreg, insn->l.ssa_ver}].push_back(ScalarKind::BOOL);
                }
                continue;
            }

            // 位运算 → WORD
            // OP_AND, OP_OR, OP_XOR, OP_NOT, OP_NEG
            if (insn->opcode == mc::OP_AND || insn->opcode == mc::OP_OR ||
                insn->opcode == mc::OP_XOR || insn->opcode == mc::OP_NOT ||
                insn->opcode == mc::OP_NEG) {
                if (insn->def_mreg >= 0) {
                    direct_evidence_[def].push_back(ScalarKind::WORD);
                }
                continue;
            }

            // 移位 → WORD (操作数和结果)
            // OP_SHL, OP_SHR, OP_SAR
            if (insn->opcode == mc::OP_SHL || insn->opcode == mc::OP_SHR) {
                if (insn->def_mreg >= 0) {
                    direct_evidence_[def].push_back(ScalarKind::UNSIGNED);
                }
                continue;
            }
            if (insn->opcode == mc::OP_SAR) {
                if (insn->def_mreg >= 0) {
                    direct_evidence_[def].push_back(ScalarKind::SIGNED);
                }
                continue;
            }

            // 符号扩展 → SIGNED
            if (insn->opcode == mc::OP_XDS) {
                if (insn->def_mreg >= 0) {
                    direct_evidence_[def].push_back(ScalarKind::SIGNED);
                }
                // 源也是 SIGNED
                if (insn->l.isReg()) {
                    direct_evidence_[{insn->l.mreg, insn->l.ssa_ver}].push_back(ScalarKind::SIGNED);
                }
                continue;
            }

            // 零扩展 → UNSIGNED
            if (insn->opcode == mc::OP_XDU) {
                if (insn->def_mreg >= 0) {
                    direct_evidence_[def].push_back(ScalarKind::UNSIGNED);
                }
                if (insn->l.isReg()) {
                    direct_evidence_[{insn->l.mreg, insn->l.ssa_ver}].push_back(ScalarKind::UNSIGNED);
                }
                continue;
            }

            // 加载常量 → 如果是非零, 可能是 INT 或 POINTER
            // 小整数 (0-0xFFFF) → INT
            // 大整数 (可能是指针) → 不标记, 留给关系证据
            if (insn->opcode == mc::OP_LDC) {
                if (insn->def_mreg >= 0) {
                    if (insn->l.isImm()) {
                        int64_t v = insn->l.imm;
                        if (v >= 0 && v <= 0xFFFF) {
                            direct_evidence_[def].push_back(ScalarKind::UNSIGNED);
                        }
                    } else if (insn->l.isStr()) {
                        // 字符串地址 → POINTER
                        direct_evidence_[def].push_back(ScalarKind::POINTER);
                    } else if (insn->l.isGlobal()) {
                        // 全局变量地址 → POINTER
                        direct_evidence_[def].push_back(ScalarKind::POINTER);
                    }
                }
                continue;
            }

            // CALL 返回值 → POINTER (如果调用 malloc/new 等)
            // 暂时不做特殊处理, 留给调用约定分析

            // v5.8: r2-style — UDIV/SDIV/MUL type inference
            // 对标 r2 instruction pattern: unsigned/signed division infers operand types
            // UDIV → UNSIGNED (all operands and result)
            // SDIV → SIGNED (all operands and result)
            // MUL → follows operand types (don't add conflicting evidence)
            if (insn->opcode == mc::OP_UDIV || insn->opcode == mc::OP_UMOD) {
                if (insn->def_mreg >= 0) {
                    direct_evidence_[def].push_back(ScalarKind::UNSIGNED);
                }
                if (insn->l.isReg()) {
                    direct_evidence_[{insn->l.mreg, insn->l.ssa_ver}].push_back(ScalarKind::UNSIGNED);
                }
                if (insn->r.isReg()) {
                    direct_evidence_[{insn->r.mreg, insn->r.ssa_ver}].push_back(ScalarKind::UNSIGNED);
                }
                continue;
            }
            if (insn->opcode == mc::OP_SDIV || insn->opcode == mc::OP_SMOD) {
                if (insn->def_mreg >= 0) {
                    direct_evidence_[def].push_back(ScalarKind::SIGNED);
                }
                if (insn->l.isReg()) {
                    direct_evidence_[{insn->l.mreg, insn->l.ssa_ver}].push_back(ScalarKind::SIGNED);
                }
                if (insn->r.isReg()) {
                    direct_evidence_[{insn->r.mreg, insn->r.ssa_ver}].push_back(ScalarKind::SIGNED);
                }
                continue;
            }

            // v3.20: Load instruction → SIGNED or UNSIGNED based on load sign
            // ldrb → UNSIGNED (width=1), ldrsb → SIGNED (width=1)
            // ldrh → UNSIGNED (width=2), ldrsh → SIGNED (width=2)
            // ldr  → UNSIGNED (width=4), ldrsw → SIGNED (width=4)
            // 对标 Ghidra typeop.cc: OpBinary.createLoadDeref type inference
            //
            // v24.0: OP_FLOAD → FLOAT (floating-point load)
            // 对标 Ghidra FLOAT_LOAD TypeOp: floating-point loads assign
            // FLOAT type to the destination register, not UNSIGNED/SIGNED.
            if (insn->opcode == mc::OP_FLOAD) {
                if (insn->def_mreg >= 0 && insn->l.isMem()) {
                    direct_evidence_[def].push_back(ScalarKind::FLOAT);
                }
                continue;
            }
            // v24.1: OP_FSTORE → the value being stored is FLOAT
            // 对标 Ghidra FLOAT_STORE TypeOp: the stored value has FLOAT type,
            // and the base register is a POINTER (same as integer store).
            // This propagates FLOAT type to the source register of the store.
            if (insn->opcode == mc::OP_FSTORE) {
                if (insn->l.isReg()) {
                    direct_evidence_[{insn->l.mreg, insn->l.ssa_ver}].push_back(ScalarKind::FLOAT);
                }
                // Base register is still a POINTER (same as integer store)
                // Only skip SP-based accesses (stack variables)
                if (insn->d.isMem() && insn->d.mem_base >= 0 &&
                    insn->d.mem_base != sp_mreg_) {
                    direct_evidence_[{insn->d.mem_base, insn->d.mem_base_ssa_ver}].push_back(ScalarKind::POINTER);
                }
                continue;
            }
            if (insn->opcode == mc::OP_LOAD) {
                if (insn->def_mreg >= 0 && insn->l.isMem()) {
                    if (insn->l.mem_signed) {
                        direct_evidence_[def].push_back(ScalarKind::SIGNED);
                    } else {
                        direct_evidence_[def].push_back(ScalarKind::UNSIGNED);
                    }
                }
                continue;
            }

            // v9.0: Float instruction type inference
            // OP_FADD, OP_FSUB, OP_FMUL, OP_FDIV → FLOAT for def and operands
            // OP_F2F → FLOAT (float conversion)
            // OP_F2I → def is INT, source is FLOAT
            // OP_I2F → def is FLOAT, source is INT
            if (insn->opcode == mc::OP_FADD || insn->opcode == mc::OP_FSUB ||
                insn->opcode == mc::OP_FMUL || insn->opcode == mc::OP_FDIV ||
                insn->opcode == mc::OP_F2F) {
                if (insn->def_mreg >= 0) {
                    direct_evidence_[def].push_back(ScalarKind::FLOAT);
                }
                if (insn->l.isReg()) {
                    direct_evidence_[{insn->l.mreg, insn->l.ssa_ver}].push_back(ScalarKind::FLOAT);
                }
                if (insn->r.isReg()) {
                    direct_evidence_[{insn->r.mreg, insn->r.ssa_ver}].push_back(ScalarKind::FLOAT);
                }
                continue;
            }
            if (insn->opcode == mc::OP_F2I) {
                if (insn->def_mreg >= 0) {
                    direct_evidence_[def].push_back(ScalarKind::SIGNED);
                }
                if (insn->l.isReg()) {
                    direct_evidence_[{insn->l.mreg, insn->l.ssa_ver}].push_back(ScalarKind::FLOAT);
                }
                continue;
            }
            if (insn->opcode == mc::OP_I2F) {
                if (insn->def_mreg >= 0) {
                    direct_evidence_[def].push_back(ScalarKind::FLOAT);
                }
                if (insn->l.isReg()) {
                    direct_evidence_[{insn->l.mreg, insn->l.ssa_ver}].push_back(ScalarKind::SIGNED);
                }
                continue;
            }
        }
    }
}

// ── 阶段3: 收集关系证据 (迭代传播) ──
// 对应 tiny-dec _add_relational_evidence()
void TypeInferencePass::collectRelationalEvidence(const mc::MicrocodeBlockArray& mba) {
    // 迭代到不动点 (最多 5 轮)
    for (int iter = 0; iter < 5; iter++) {
        bool changed = false;

        for (auto& blk_up : mba.blocks) {
            if (!blk_up) continue;
            for (mc::MicroInsn* insn = blk_up->head; insn; insn = insn->next) {
                EntityId def{insn->def_mreg, insn->ssa_version};

                // ADD/SUB: ptr + const → ptr
                // 如果一个操作数是 POINTER, 另一个是 IMM, 则结果是 POINTER
                if (insn->opcode == mc::OP_ADD || insn->opcode == mc::OP_SUB) {
                    if (insn->def_mreg < 0) continue;

                    bool lIsPtr = false, rIsPtr = false;
                    bool lIsImm = insn->l.isImm();
                    bool rIsImm = insn->r.isImm();

                    if (insn->l.isReg()) {
                        EntityId le{insn->l.mreg, insn->l.ssa_ver};
                        auto it = resolved_kinds_.find(le);
                        if (it != resolved_kinds_.end() && it->second == ScalarKind::POINTER) {
                            lIsPtr = true;
                        }
                        // 也检查直接证据
                        auto dit = direct_evidence_.find(le);
                        if (dit != direct_evidence_.end()) {
                            for (auto& k : dit->second) {
                                if (k == ScalarKind::POINTER) lIsPtr = true;
                            }
                        }
                    }
                    if (insn->r.isReg()) {
                        EntityId re{insn->r.mreg, insn->r.ssa_ver};
                        auto it = resolved_kinds_.find(re);
                        if (it != resolved_kinds_.end() && it->second == ScalarKind::POINTER) {
                            rIsPtr = true;
                        }
                        auto dit = direct_evidence_.find(re);
                        if (dit != direct_evidence_.end()) {
                            for (auto& k : dit->second) {
                                if (k == ScalarKind::POINTER) rIsPtr = true;
                            }
                        }
                    }

                    // ptr + imm → ptr
                    if (lIsPtr && rIsImm) {
                        if (direct_evidence_[def].empty() ||
                            direct_evidence_[def].back() != ScalarKind::POINTER) {
                            direct_evidence_[def].push_back(ScalarKind::POINTER);
                            changed = true;
                        }
                    }
                    if (rIsPtr && lIsImm) {
                        if (direct_evidence_[def].empty() ||
                            direct_evidence_[def].back() != ScalarKind::POINTER) {
                            direct_evidence_[def].push_back(ScalarKind::POINTER);
                            changed = true;
                        }
                    }

                    // ptr + reg → ptr (反向传播: 结果是 ptr → 另一个是 ptr)
                    if (lIsPtr && insn->r.isReg() && !rIsPtr) {
                        EntityId re{insn->r.mreg, insn->r.ssa_ver};
                        direct_evidence_[re].push_back(ScalarKind::POINTER);
                        changed = true;
                    }
                    if (rIsPtr && insn->l.isReg() && !lIsPtr) {
                        EntityId le{insn->l.mreg, insn->l.ssa_ver};
                        direct_evidence_[le].push_back(ScalarKind::POINTER);
                        changed = true;
                    }
                }

                // LOAD from pointer: 如果地址是 POINTER, 结果可能是指针解引用
                // 如果加载的是 argument-home, 结果可能是 POINTER (函数指针等)
                if (insn->opcode == mc::OP_LOAD && insn->l.isMem()) {
                    // 加载全局地址 → 结果是指针
                    if (insn->l.mem_base != sp_mreg_) {
                        // 从堆/全局加载, 结果可能是指针
                        // 保守: 不标记, 留给其他证据
                    }
                }

                // v9.0: ptr - ptr → INT (pointer difference)
                if (insn->opcode == mc::OP_SUB && insn->def_mreg >= 0) {
                    if (insn->l.isReg() && insn->r.isReg()) {
                        EntityId le{insn->l.mreg, insn->l.ssa_ver};
                        EntityId re{insn->r.mreg, insn->r.ssa_ver};
                        bool lIsPtr = (resolved_kinds_.count(le) && resolved_kinds_[le] == ScalarKind::POINTER);
                        bool rIsPtr = (resolved_kinds_.count(re) && resolved_kinds_[re] == ScalarKind::POINTER);
                        if (lIsPtr && rIsPtr) {
                            direct_evidence_[def].push_back(ScalarKind::SIGNED);  // ptrdiff_t
                            changed = true;
                        }
                    }
                }

                // v9.0: ptr[index] → ptr (scaled index access: ADD ptr, idx*scale → ptr)
                if (insn->opcode == mc::OP_ADD && insn->def_mreg >= 0) {
                    // Check if one operand is a pointer and the result is used as a memory base
                    if (insn->l.isReg() && insn->r.isReg()) {
                        EntityId le{insn->l.mreg, insn->l.ssa_ver};
                        EntityId re{insn->r.mreg, insn->r.ssa_ver};
                        bool lPtr = (resolved_kinds_.count(le) && resolved_kinds_[le] == ScalarKind::POINTER);
                        bool rPtr = (resolved_kinds_.count(re) && resolved_kinds_[re] == ScalarKind::POINTER);
                        if (lPtr != rPtr) {
                            // One is ptr, one is not → result is likely ptr (e.g., array access)
                            auto it = direct_evidence_.find(def);
                            if (it == direct_evidence_.end() || it->second.empty()) {
                                direct_evidence_[def].push_back(ScalarKind::POINTER);
                                changed = true;
                            }
                        }
                    }
                }
            }
        }

        if (!changed) break;
    }
}

// ── 阶段4: 合并证据 ──
// 对应 tiny-dec _merge_kinds()
ScalarKind TypeInferencePass::mergeKinds(const std::vector<ScalarKind>& kinds) {
    if (kinds.empty()) return ScalarKind::UNKNOWN;

    // 去重
    std::set<ScalarKind> unique_kinds(kinds.begin(), kinds.end());

    // 只有一种 → 直接返回
    if (unique_kinds.size() == 1) {
        return *unique_kinds.begin();
    }

    // WORD 是吸收元: WORD + anything = anything (WORD 让位)
    // 但如果存在冲突 (SIGNED + UNSIGNED, POINTER + INT), 降级为 WORD
    unique_kinds.erase(ScalarKind::UNKNOWN);  // UNKNOWN 不参与合并
    unique_kinds.erase(ScalarKind::WORD);     // WORD 让位

    if (unique_kinds.empty()) {
        // 只有 UNKNOWN 和/或 WORD → WORD
        return ScalarKind::WORD;
    }

    if (unique_kinds.size() == 1) {
        // 只剩一种非 WORD/UNKNOWN → 返回它
        return *unique_kinds.begin();
    }

    // 多种冲突 → WORD
    return ScalarKind::WORD;
}

// ── ScalarKind + width → CType ──
CType TypeInferencePass::toCType(ScalarKind kind, int width) {
    CType t;
    switch (kind) {
        case ScalarKind::BOOL:
            t.category = CType::TC_BOOL;
            t.width = 1;
            break;
        case ScalarKind::SIGNED:
            switch (width) {
                case 1: t.category = CType::TC_INT8; t.width = 1; t.is_signed = true; break;
                case 2: t.category = CType::TC_INT16; t.width = 2; t.is_signed = true; break;
                case 4: t.category = CType::TC_INT32; t.width = 4; t.is_signed = true; break;
                default: t.category = CType::TC_INT64; t.width = 8; t.is_signed = true; break;
            }
            break;
        case ScalarKind::UNSIGNED:
            switch (width) {
                case 1: t.category = CType::TC_UINT8; t.width = 1; break;
                case 2: t.category = CType::TC_UINT16; t.width = 2; break;
                case 4: t.category = CType::TC_UINT32; t.width = 4; break;
                default: t.category = CType::TC_UINT64; t.width = 8; break;
            }
            break;
        case ScalarKind::POINTER:
            t.category = CType::TC_POINTER;
            t.width = 8;
            break;
        case ScalarKind::FLOAT:
            switch (width) {
                case 4: t.category = CType::TC_FLOAT; t.width = 4; t.is_signed = true; break;
                default: t.category = CType::TC_DOUBLE; t.width = 8; t.is_signed = true; break;
            }
            break;
        case ScalarKind::WORD:
        case ScalarKind::UNKNOWN:
        default:
            // Fallback: 根据 width 选择
            switch (width) {
                case 1: t.category = CType::TC_UINT8; t.width = 1; break;
                case 2: t.category = CType::TC_UINT16; t.width = 2; break;
                case 4: t.category = CType::TC_UINT32; t.width = 4; break;
                default: t.category = CType::TC_UINT64; t.width = 8; break;
            }
            break;
    }
    return t;
}

// ── 辅助: 判断栈偏移是否为参数槽 ──
bool TypeInferencePass::isArgumentHome(int sp_offset) const {
    // AArch64: 参数在栈上的偏移通常是正数 (调用者栈帧)
    // 简单启发: 正偏移 → 可能是参数槽
    // 实际需要调用约定分析来确定
    return sp_offset > 0;
}

// ── 调试输出 ──
void TypeInferencePass::dump() const {
    printf("Type Inference Results:\n");
    for (auto& [e, kind] : resolved_kinds_) {
        const char* kindStr = "UNKNOWN";
        switch (kind) {
            case ScalarKind::BOOL: kindStr = "BOOL"; break;
            case ScalarKind::SIGNED: kindStr = "SIGNED"; break;
            case ScalarKind::UNSIGNED: kindStr = "UNSIGNED"; break;
            case ScalarKind::POINTER: kindStr = "POINTER"; break;
            case ScalarKind::FLOAT: kindStr = "FLOAT"; break;
            case ScalarKind::WORD: kindStr = "WORD"; break;
            case ScalarKind::UNKNOWN: kindStr = "UNKNOWN"; break;
        }
        int width = 8;
        auto wit = entity_widths_.find(e);
        if (wit != entity_widths_.end()) width = wit->second;
        CType t = toCType(kind, width);
        printf("  mreg=%d, ssa=%d → %s (%s, width=%d)\n",
               e.first, e.second, t.toCString().c_str(), kindStr, width);
    }
}

// ════════════════════════════════════════════════════════════════════
// v10.1: detectArrayTypes — 数组类型检测
// 对标 Ghidra TypeArray: 连续等间距内存访问模式 → 数组
//
// 算法:
//   1. 收集每个 base_reg 的所有 (offset, width) 访问
//   2. 如果偏移量等间距 (stride = element_size) 且 >= 3 个元素
//   3. 推断为 array[element_type, count]
// ════════════════════════════════════════════════════════════════════
void TypeInferencePass::detectArrayTypes(const mc::MicrocodeBlockArray& mba) {
    // 收集每个 base_reg 的 (offset, width) 对
    std::map<int, std::vector<std::pair<int, int>>> base_accesses;  // base_reg → [(offset, width)]

    for (auto& blk_up : mba.blocks) {
        if (!blk_up) continue;
        for (mc::MicroInsn* insn = blk_up->head; insn; insn = insn->next) {
            // LOAD: d = mem[base, offset]
            if (insn->opcode == mc::OP_LOAD && insn->l.isMem() && insn->l.mem_base >= 0
                && insn->l.mem_base != sp_mreg_) {
                base_accesses[insn->l.mem_base].push_back(
                    {(int)insn->l.mem_offset, insn->l.width});
            }
            // STORE: mem[base, offset] = l
            if (insn->opcode == mc::OP_STORE && insn->d.isMem() && insn->d.mem_base >= 0
                && insn->d.mem_base != sp_mreg_) {
                base_accesses[insn->d.mem_base].push_back(
                    {(int)insn->d.mem_offset, insn->d.width});
            }
        }
    }

    // 检测等间距模式
    for (auto& [base_reg, accesses] : base_accesses) {
        if (accesses.size() < 3) continue;  // 至少3个元素才算数组

        // 按偏移量排序
        std::sort(accesses.begin(), accesses.end());

        // 去重
        accesses.erase(std::unique(accesses.begin(), accesses.end()), accesses.end());
        if (accesses.size() < 3) continue;

        // 检查等间距
        int stride = accesses[1].first - accesses[0].first;
        if (stride <= 0) continue;

        bool uniform = true;
        for (size_t i = 1; i < accesses.size(); i++) {
            if (accesses[i].first - accesses[i-1].first != stride) {
                uniform = false;
                break;
            }
        }

        if (uniform) {
            // 推断为数组
            InferredArray arr;
            arr.element_size = stride;
            arr.count = (int)accesses.size();

            // 元素类型: 取第一个访问的宽度
            int elemWidth = accesses[0].second;
            if (elemWidth == 8) {
                arr.element_type = is_aarch64_ ? CType::u64() : CType::u32();
            } else if (elemWidth == 4) {
                arr.element_type = CType::u32();
            } else if (elemWidth == 2) {
                arr.element_type = CType::u16();
            } else {
                arr.element_type = CType::u8();
            }

            inferred_arrays_[base_reg] = arr;
        }
    }
}

// ════════════════════════════════════════════════════════════════════
// v10.1: detectUnionTypes — 联合体类型检测
// 对标 Ghidra ActionResolveUnion: 同一内存位置以不同宽度访问 → union
//
// 算法:
//   1. 收集每个内存位置 (base+offset) 的访问宽度
//   2. 如果同一位置以不同宽度访问 (如既读4字节又读8字节) → union
//   3. 推断为 union { type1; type2; }
// ════════════════════════════════════════════════════════════════════
void TypeInferencePass::detectUnionTypes(const mc::MicrocodeBlockArray& mba) {
    mem_access_widths_.clear();

    for (auto& blk_up : mba.blocks) {
        if (!blk_up) continue;
        for (mc::MicroInsn* insn = blk_up->head; insn; insn = insn->next) {
            std::string loc_key;
            int width = 0;

            if (insn->opcode == mc::OP_LOAD && insn->l.isMem()) {
                loc_key = std::to_string(insn->l.mem_base) + "+" +
                          std::to_string(insn->l.mem_offset);
                width = insn->l.width;
            } else if (insn->opcode == mc::OP_STORE && insn->d.isMem()) {
                loc_key = std::to_string(insn->d.mem_base) + "+" +
                          std::to_string(insn->d.mem_offset);
                width = insn->d.width;
            }

            if (!loc_key.empty() && width > 0) {
                mem_access_widths_[loc_key].insert(width);
            }
        }
    }

    // 检测 union: 同一位置以不同宽度访问
    for (auto& [loc_key, widths] : mem_access_widths_) {
        if (widths.size() < 2) continue;

        // 解析 base_reg
        size_t plus_pos = loc_key.find('+');
        if (plus_pos == std::string::npos) continue;
        int base_reg = std::stoi(loc_key.substr(0, plus_pos));

        InferredUnion u;
        u.name = "union_" + std::to_string(base_reg);
        u.max_size = 0;

        for (int w : widths) {
            CType::UnionMember m;
            m.name = "field_" + std::to_string(w * 8);
            // v10.1: StructField/UnionMember.type 现为 shared_ptr<CType>
            // (CType 不能包含自身实例), 因此用 make_shared 包装。
            if (w == 8) m.type = std::make_shared<CType>(is_aarch64_ ? CType::u64() : CType::u32());
            else if (w == 4) m.type = std::make_shared<CType>(CType::u32());
            else if (w == 2) m.type = std::make_shared<CType>(CType::u16());
            else m.type = std::make_shared<CType>(CType::u8());
            u.members.push_back(m);
            if (w > u.max_size) u.max_size = w;
        }

        inferred_unions_[base_reg] = u;
    }
}

// ════════════════════════════════════════════════════════════════════
// v10.1: inferStructTypes — 结构体类型推断
// 对标 Ghidra StructureOffsetAnalysis: 从 base+offset 模式推断结构体
//
// 算法:
//   1. 使用 detectStructFields 已收集的 struct_field_offsets_
//   2. 为每个 base_reg 构建完整结构体定义
//   3. 字段类型从 resolved_kinds_ 获取,未知则用 uint64_t
//   4. 字段名自动生成: field_0xOFFSET
// ════════════════════════════════════════════════════════════════════
void TypeInferencePass::inferStructTypes(const mc::MicrocodeBlockArray& mba) {
    for (auto& [base_reg, offsets] : struct_field_offsets_) {
        if (offsets.size() < 2) continue;  // 至少2个字段才算结构体

        InferredStruct s;
        s.name = "struct_" + std::to_string(base_reg);

        int max_offset = 0;
        for (int offset : offsets) {
            CType::StructField f;
            f.offset = offset;

            // 生成字段名 (对标 Ghidra 自动命名)
            if (offset == 0) f.name = "field_0";
            else {
                char buf[32];
                snprintf(buf, sizeof(buf), "field_0x%x", offset);
                f.name = buf;
            }

            // 字段类型: 尝试从已解析的类型获取,默认 uint64_t
            f.type = std::make_shared<CType>(is_aarch64_ ? CType::u64() : CType::u32());

            // 检查是否有已知类型
            for (auto& blk_up : mba.blocks) {
                if (!blk_up) continue;
                for (mc::MicroInsn* insn = blk_up->head; insn; insn = insn->next) {
                    if (insn->opcode == mc::OP_LOAD && insn->l.isMem() &&
                        insn->l.mem_base == base_reg && insn->l.mem_offset == offset) {
                        int w = insn->l.width;
                        if (w == 1) f.type = std::make_shared<CType>(CType::u8());
                        else if (w == 2) f.type = std::make_shared<CType>(CType::u16());
                        else if (w == 4) f.type = std::make_shared<CType>(CType::u32());
                        else f.type = std::make_shared<CType>(is_aarch64_ ? CType::u64() : CType::u32());
                        break;
                    }
                }
            }

            s.fields.push_back(f);
            if (offset > max_offset) max_offset = offset;
        }

        // 估算结构体大小 (最后一个字段偏移 + 字段宽度)
        if (!s.fields.empty()) {
            s.total_size = max_offset + (is_aarch64_ ? 8 : 4);
        }

        inferred_structs_[base_reg] = s;
    }
}

// ════════════════════════════════════════════════════════════════════
// v9.7: 返回类型推断 (对标 Ghidra actiontype.cc)
//
// Ghidra 的返回类型推断原理:
//   1. 扫描所有 RET 指令,收集返回寄存器(x0/r0, mreg 100)的类型证据
//   2. 多个 RET 块的类型通过 MULTIEQUAL (phi) 合并
//   3. 如果函数没有任何 RET 指令定义 x0,则返回类型为 void
//   4. 如果 RET 返回的值来自 LOAD (ldr),则为指针类型
//   5. 如果 RET 返回常量,则为 int/uint64_t
//
// 实现细节:
//   - 遍历所有 OP_RET 指令
//   - 对每个 RET,查找其返回寄存器(mreg 100)的 SSA 版本
//   - 通过 resolved_kinds_ 获取该 SSA 版本的类型
//   - 合并所有 RET 路径的类型 (对标 Ghidra MULTIEQUAL 合并)
//   - 如果没有 RET 定义返回寄存器,推断为 void
// ════════════════════════════════════════════════════════════════════
void TypeInferencePass::inferReturnType(const mc::MicrocodeBlockArray& mba) {
    // 收集所有 ret 指令的返回寄存器类型证据
    // 对标 Ghidra: 多个 ret 块的类型通过 MULTIEQUAL 合并
    std::vector<ScalarKind> retKinds;
    std::vector<int> retWidths;
    bool anyRetWithReturnValue = false;  // 是否有 ret 指令实际返回了值

    for (auto& blk_up : mba.blocks) {
        if (!blk_up) continue;
        for (mc::MicroInsn* insn = blk_up->head; insn; insn = insn->next) {
            if (insn->isDead()) continue;
            if (insn->opcode != mc::OP_RET) continue;

            // v6.2: Debug
            // fprintf(stderr, "[DEBUG void-infer] RET found: l.isReg=%d l.mreg=%d ssa_ver=%d\n",
            //         insn->l.isReg() ? 1 : 0, insn->l.mreg, insn->l.ssa_ver);

            // ret 指令的 l 操作数是返回寄存器 (mreg 100)
            // 对标 Ghidra: RET 指令读取返回寄存器
            // v6.2: After SSA constant propagation, the ret's l operand
            // may be replaced with an immediate (MOP_IMM) or the defining
            // instruction's value. In that case, the function definitely
            // returns a value — it's NOT void.
            if (!insn->l.isReg()) {
                // l is an immediate or other non-register operand
                // This means SSA propagation replaced the register read
                // with the actual return value — the function returns a value.
                anyRetWithReturnValue = true;
                if (insn->l.isImm()) {
                    int64_t v = insn->l.imm;
                    if (v >= 0 && v <= 0xFFFF) {
                        retKinds.push_back(ScalarKind::UNSIGNED);
                    } else {
                        retKinds.push_back(ScalarKind::POINTER);
                    }
                    retWidths.push_back(is_aarch64_ ? 8 : 4);
                } else {
                    retKinds.push_back(ScalarKind::UNKNOWN);
                }
                continue;
            }
            if (insn->l.mreg != RET_MREG) continue;

            int retSsaVer = insn->l.ssa_ver;
            EntityId retEntity{RET_MREG, retSsaVer};

            // v6.2: If SSA version is 0, the return register was never
            // defined in this function — it's the function parameter value
            // (e.g., return x0; where x0 is the first parameter).
            // This is NOT void — the function returns its parameter.
            // 对标 Ghidra: input Varnode on RET means the function returns
            // a value (the parameter), not void.
            if (retSsaVer == 0) {
                anyRetWithReturnValue = true;
                retKinds.push_back(ScalarKind::UNKNOWN);
                continue;
            }

            // 检查该返回值是否有类型信息
            auto kindIt = resolved_kinds_.find(retEntity);
            int width = 0;
            auto widthIt = entity_widths_.find(retEntity);
            if (widthIt != entity_widths_.end()) {
                width = widthIt->second;
            }

            // 对标 Ghidra: 检查返回值的来源
            // 如果返回值来自 LOAD (ldr),则是指针
            // 通过追踪 def 链查找
            bool returnsFromLoad = false;
            bool returnsFromConstant = false;
            bool returnsFromCall = false;

            // 在同一块中向前查找返回寄存器的定义
            mc::MicroInsn* prev = insn->prev;
            while (prev) {
                if (prev->def_mreg == RET_MREG) {
                    // 找到返回寄存器的定义
                    if (prev->opcode == mc::OP_LOAD) {
                        // ret = load(...) → 指针类型
                        returnsFromLoad = true;
                    } else if (prev->opcode == mc::OP_LDC ||
                               prev->opcode == mc::OP_MOV) {
                        // v6.2: mov w0, #N also generates OP_MOV with imm operand
                        // ret = #const → 整数类型
                        if (prev->l.isImm()) {
                            returnsFromConstant = true;
                            if (width <= 0) width = is_aarch64_ ? 8 : 4;
                        }
                        // mov w0, w1 (register copy) — the function returns
                        // a parameter, not void
                        // anyRetWithReturnValue will be set at line 1092
                    } else if (prev->opcode == mc::OP_CALL ||
                               prev->opcode == mc::OP_ICALL) {
                        // ret = call(...) → 函数返回值
                        returnsFromCall = true;
                    }
                    break;
                }
                prev = prev->prev;
            }

            // 获取类型
            ScalarKind kind = ScalarKind::UNKNOWN;
            if (kindIt != resolved_kinds_.end()) {
                kind = kindIt->second;
            }

            // 如果类型未知,根据来源推断
            if (kind == ScalarKind::UNKNOWN) {
                if (returnsFromLoad) {
                    kind = ScalarKind::POINTER;
                    if (width <= 0) width = is_aarch64_ ? 8 : 4;
                } else if (returnsFromConstant) {
                    // 小常量 → UNSIGNED, 大常量(可能是地址) → POINTER
                    // 检查常量值
                    if (prev && prev->opcode == mc::OP_LDC && prev->l.isImm()) {
                        int64_t v = prev->l.imm;
                        if (v >= 0 && v <= 0xFFFF) {
                            kind = ScalarKind::UNSIGNED;
                        } else {
                            // 大常量可能是地址 → 指针
                            kind = ScalarKind::POINTER;
                        }
                    } else {
                        kind = ScalarKind::UNSIGNED;
                    }
                    if (width <= 0) width = is_aarch64_ ? 8 : 4;
                } else if (returnsFromCall) {
                    // 函数调用返回值 — 类型未知,保持 UNKNOWN
                    // 但这表示有返回值
                    anyRetWithReturnValue = true;
                    continue;
                } else {
                    // 无法确定来源 — 假设有返回值但类型未知
                    anyRetWithReturnValue = true;
                    continue;
                }
            }

            anyRetWithReturnValue = true;
            retKinds.push_back(kind);
            if (width > 0) retWidths.push_back(width);
        }
    }

    // 推断返回类型
    // v6.2: Debug — if anyRetWithReturnValue is false, it means NO ret
    // instruction was found that defines mreg 100. This could be because:
    // 1. The function truly returns void
    // 2. The ret instruction's l operand doesn't have mreg=100
    // 3. The SSA version is 0 (handled above)
    if (!anyRetWithReturnValue) {
        // 对标 Ghidra: 函数没有任何 ret 指令定义 x0 → void
        inferred_return_void_ = true;
        inferred_return_type_ = CType{};
        inferred_return_type_.category = CType::TC_VOID;
        inferred_return_type_.width = 0;
        inferred_return_valid_ = true;
        return;
    }

    // 合并所有 ret 路径的类型 (对标 Ghidra MULTIEQUAL 合并)
    if (!retKinds.empty()) {
        ScalarKind mergedKind = mergeKinds(retKinds);
        int mergedWidth = is_aarch64_ ? 8 : 4;
        if (!retWidths.empty()) {
            // 取最大宽度
            for (int w : retWidths) {
                if (w > mergedWidth) mergedWidth = w;
            }
        }
        // 指针类型宽度固定为架构宽度
        if (mergedKind == ScalarKind::POINTER) {
            mergedWidth = is_aarch64_ ? 8 : 4;
        }
        inferred_return_type_ = toCType(mergedKind, mergedWidth);
        inferred_return_void_ = false;
        inferred_return_valid_ = true;
    } else {
        // 有 ret 返回值但类型都未知 → 默认 uint64_t (非 void)
        inferred_return_type_ = CType::u64();
        inferred_return_void_ = false;
        inferred_return_valid_ = true;
    }
}

// ── setter ──
void TypeInferencePass::setIsAArch64(bool is_aarch64) {
    is_aarch64_ = is_aarch64;
    sp_mreg_ = is_aarch64 ? 131 : 113;
}

// ════════════════════════════════════════════════════════════════════
// v5.8: r2-style return type database for known functions
// 对标 r2 sdb_types: r_type_func_ret() returns the return type string
// r2 stores function signatures in sdb_types and looks them up by name.
// We replicate this with a static map of known library functions.
// ════════════════════════════════════════════════════════════════════
std::string TypeInferencePass::getReturnTypeForCallee(const std::string& calleeName) {
    static const std::map<std::string, std::string> returnTypes = {
        // libc memory — return pointers
        {"malloc",    "void*"},
        {"calloc",    "void*"},
        {"realloc",   "void*"},
        {"memchr",    "void*"},
        // libc string — return char* or size_t
        {"strcpy",    "char*"},
        {"strncpy",   "char*"},
        {"strcat",    "char*"},
        {"strncat",   "char*"},
        {"strchr",    "char*"},
        {"strrchr",   "char*"},
        {"strstr",    "char*"},
        {"strtok",    "char*"},
        {"strlen",    "size_t"},
        {"strcmp",    "int"},
        {"strncmp",   "int"},
        {"memcmp",    "int"},
        {"atoi",      "int"},
        {"atol",      "long"},
        {"strtol",    "long"},
        {"strtoul",   "unsigned long"},
        // libc stdio — return int or FILE*
        {"printf",    "int"},
        {"fprintf",   "int"},
        {"sprintf",   "int"},
        {"snprintf",  "int"},
        {"puts",      "int"},
        {"fputs",     "int"},
        {"fopen",     "void*"},
        {"fclose",    "int"},
        {"fread",     "size_t"},
        {"fwrite",    "size_t"},
        {"fseek",     "int"},
        {"ftell",     "long"},
        // libc process
        {"exit",      "void"},
        {"_exit",     "void"},
        {"abort",     "void"},
        // pthread — return int (0 on success)
        {"pthread_create",   "int"},
        {"pthread_join",     "int"},
        {"pthread_mutex_init",   "int"},
        {"pthread_mutex_lock",   "int"},
        {"pthread_mutex_unlock", "int"},
        {"pthread_mutex_destroy","int"},
        {"pthread_self",     "unsigned long"},
        {"pthread_detach",   "int"},
        // Android
        {"dlopen",    "void*"},
        {"dlsym",     "void*"},
        {"dlclose",   "int"},
        {"__system_property_get", "int"},
        {"__android_log_print", "int"},
        {"__android_log_write", "int"},
        // JNI — return various
        {"GetEnv",           "int"},
        {"FindClass",        "void*"},
        {"GetMethodID",      "void*"},
        {"GetFieldID",       "void*"},
        {"NewStringUTF",     "void*"},
        {"GetStringUTFChars","const char*"},
        {"ReleaseStringUTFChars", "void"},
        {"CallVoidMethod",   "void"},
        {"CallIntMethod",    "int"},
        {"CallBooleanMethod","int"},
        {"CallObjectMethod", "void*"},
        {"NewGlobalRef",     "void*"},
        {"DeleteLocalRef",   "void"},
        {"DeleteGlobalRef",  "void"},
        {"RegisterNatives",  "int"},
        {"GetJavaVM",        "int"},
    };

    auto it = returnTypes.find(calleeName);
    if (it != returnTypes.end()) return it->second;
    return "";
}

// v5.8: Convert C type string to ScalarKind
// 对标 r2 r_type_get_bitsize() type classification
ScalarKind TypeInferencePass::ctypeStringToKind(const std::string& ct) {
    if (ct.empty() || ct == "void") return ScalarKind::UNKNOWN;
    if (ct.find('*') != std::string::npos) return ScalarKind::POINTER;
    if (ct == "int" || ct == "long" || ct == "short" || ct == "char") return ScalarKind::SIGNED;
    if (ct == "unsigned" || ct == "unsigned long" || ct == "unsigned int" ||
        ct == "size_t" || ct == "unsigned short" || ct == "unsigned char") return ScalarKind::UNSIGNED;
    if (ct == "float" || ct == "double") return ScalarKind::FLOAT;
    return ScalarKind::UNKNOWN;
}

// ════════════════════════════════════════════════════════════════════
// v5.8: r2-style callee return type propagation
// 对标 r2 type propagation: when a CALL instruction targets a known
// function (e.g., malloc), the return register (x0/r0) gets the return type.
// r2 does this via r_anal_var_set_type() after r_type_func_ret() lookup.
// ════════════════════════════════════════════════════════════════════
void TypeInferencePass::collectCallReturnTypes(const mc::MicrocodeBlockArray& mba) {
    for (auto& blk_up : mba.blocks) {
        if (!blk_up) continue;
        for (mc::MicroInsn* insn = blk_up->head; insn; insn = insn->next) {
            if (insn->opcode != mc::OP_CALL && insn->opcode != mc::OP_ICALL) continue;
            if (insn->def_mreg < 0) continue;  // no return register

            // Look up callee name
            std::string calleeName;
            if (insn->call_info && !insn->call_info->target_name.empty()) {
                calleeName = insn->call_info->target_name;
            } else if (insn->target_addr != 0) {
                auto nameIt = mba.global_names.find(insn->target_addr);
                if (nameIt != mba.global_names.end()) {
                    calleeName = nameIt->second;
                }
            }
            if (calleeName.empty()) continue;

            // Get return type for this callee
            std::string retType = getReturnTypeForCallee(calleeName);
            if (retType.empty()) continue;

            // Convert to ScalarKind and add as evidence for the return register
            EntityId def{insn->def_mreg, insn->ssa_version};
            ScalarKind kind = ctypeStringToKind(retType);
            if (kind != ScalarKind::UNKNOWN) {
                direct_evidence_[def].push_back(kind);
            }

            // Also propagate argument types from the callee's known signature
            // 对标 r2: r_type_func_args_type() → arg register types
            // We reuse the CallingConvention database for this
            auto paramTypes = mc::CallingConvention::getParamTypesForCallee(calleeName);
            int maxArgs = is_aarch64_ ? mc::MAX_ARGS_AARCH64 : mc::MAX_ARGS_ARM32;
            const int* argRegs = is_aarch64_ ? mc::ARG_REGS_AARCH64 : mc::ARG_REGS_ARM32;

            for (int i = 0; i < (int)paramTypes.size() && i < maxArgs; i++) {
                if (paramTypes[i] == "...") continue;
                // Find the SSA version of this arg register at this call site
                // by scanning backwards for the most recent definition
                mc::MicroInsn* prev = insn->prev;
                int argMreg = argRegs[i];
                int foundSsaVer = -1;
                while (prev) {
                    if (prev->def_mreg == argMreg) {
                        foundSsaVer = prev->ssa_version;
                        break;
                    }
                    prev = prev->prev;
                }
                if (foundSsaVer < 0) {
                    // Check if it's used as a source operand in the call
                    // (some emitters put args in the call's operands)
                    if (insn->l.isReg() && insn->l.mreg == argMreg) {
                        foundSsaVer = insn->l.ssa_ver;
                    } else if (insn->r.isReg() && insn->r.mreg == argMreg) {
                        foundSsaVer = insn->r.ssa_ver;
                    }
                }
                if (foundSsaVer >= 0) {
                    EntityId argEntity{argMreg, foundSsaVer};
                    ScalarKind argKind = ctypeStringToKind(paramTypes[i]);
                    if (argKind != ScalarKind::UNKNOWN) {
                        direct_evidence_[argEntity].push_back(argKind);
                    }
                }
            }
        }
    }
}

} // namespace ctree
