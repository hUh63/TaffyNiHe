// ir_optimizer.cpp — v8.7 Enhanced IR optimization passes
// 常量折叠、代数化简、拷贝传播、死代码消除 (增强版)

#include "ir_optimizer.hpp"
#include <algorithm>
#include <iostream>
#include <map>
#include <set>
#include <vector>
#include <queue>
#include <tuple>

namespace mc {

// ═══════════════════════════════════════════════════════════════
// Helper: Check if two Mops are the same memory location
// ═══════════════════════════════════════════════════════════════
static bool sameMemoryLoc(const Mop& a, const Mop& b) {
    if (!a.isMem() || !b.isMem()) return false;
    return a.mem_base == b.mem_base &&
           a.mem_base_ssa_ver == b.mem_base_ssa_ver &&
           a.mem_offset == b.mem_offset &&
           a.width == b.width;
}

// ═══════════════════════════════════════════════════════════════
// Helper: Try to evaluate a binary operation on two constants
// ═══════════════════════════════════════════════════════════════
static bool tryFoldBinary(MicroOp op, int64_t a, int64_t b, int64_t& result) {
    switch (op) {
        case OP_ADD: result = a + b; return true;
        case OP_SUB: result = a - b; return true;
        case OP_MUL: result = a * b; return true;
        case OP_AND: result = a & b; return true;
        case OP_OR:  result = a | b; return true;
        case OP_XOR: result = a ^ b; return true;
        case OP_SHL: result = a << (b & 63); return true;
        case OP_SHR: result = (uint64_t)a >> (b & 63); return true;
        case OP_SAR: result = a >> (b & 63); return true;
        case OP_UDIV: if (b != 0) { result = (uint64_t)a / (uint64_t)b; return true; } break;
        case OP_SDIV: if (b != 0) { result = a / b; return true; } break;
        default: break;
    }
    return false;
}

// ═══════════════════════════════════════════════════════════════
// Pass 1: Enhanced constant folding
// ═══════════════════════════════════════════════════════════════
void constantFoldingV2(MicrocodeBlockArray& mba) {
    for (int b = 0; b < mba.numBlocks(); b++) {
        auto& blk = mba.blocks[b];
        for (auto* insn = blk->head; insn; insn = insn->next) {
            if (insn->iprops & IPROP_DEAD) continue;
            // Skip control flow
            if (insn->isBranch() || insn->isCall() || insn->isRet() || insn->isJumpTable()) continue;
            if (insn->opcode == OP_STORE || insn->opcode == OP_LOAD ||
                insn->opcode == OP_FSTORE || insn->opcode == OP_FLOAD) continue;

            if (insn->l.isImm() && insn->r.isImm()) {
                int64_t result;
                if (tryFoldBinary(insn->opcode, insn->l.imm, insn->r.imm, result)) {
                    insn->opcode = OP_LDC;
                    insn->l = Mop::imm64(result, insn->l.width);
                    insn->r = Mop();
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Pass 2: Enhanced algebraic simplification
// ═══════════════════════════════════════════════════════════════
void algebraicSimplifyV2(MicrocodeBlockArray& mba) {
    for (int b = 0; b < mba.numBlocks(); b++) {
        auto& blk = mba.blocks[b];
        for (auto* insn = blk->head; insn; insn = insn->next) {
            if (insn->iprops & IPROP_DEAD) continue;
            if (insn->isBranch() || insn->isCall() || insn->isRet() || insn->isJumpTable()) continue;
            if (insn->opcode == OP_STORE || insn->opcode == OP_LOAD ||
                insn->opcode == OP_FSTORE || insn->opcode == OP_FLOAD) continue;

            // x + 0 → x (MOV)
            if (insn->opcode == OP_ADD && insn->r.isImm() && insn->r.imm == 0) {
                insn->opcode = OP_MOV;
                insn->r = Mop();
                continue;
            }
            // x - 0 → x (MOV)
            if (insn->opcode == OP_SUB && insn->r.isImm() && insn->r.imm == 0) {
                insn->opcode = OP_MOV;
                insn->r = Mop();
                continue;
            }
            // x * 0 → 0
            if (insn->opcode == OP_MUL && insn->r.isImm() && insn->r.imm == 0) {
                insn->opcode = OP_LDC;
                insn->l = Mop::imm64(0, insn->l.width);
                insn->r = Mop();
                continue;
            }
            // x * 1 → x (MOV)
            if (insn->opcode == OP_MUL && insn->r.isImm() && insn->r.imm == 1) {
                insn->opcode = OP_MOV;
                insn->r = Mop();
                continue;
            }
            // x | 0 → x (MOV)
            if (insn->opcode == OP_OR && insn->r.isImm() && insn->r.imm == 0) {
                insn->opcode = OP_MOV;
                insn->r = Mop();
                continue;
            }
            // x & 0 → 0
            if (insn->opcode == OP_AND && insn->r.isImm() && insn->r.imm == 0) {
                insn->opcode = OP_LDC;
                insn->l = Mop::imm64(0, insn->l.width);
                insn->r = Mop();
                continue;
            }
            // x & -1 → x (MOV)
            if (insn->opcode == OP_AND && insn->r.isImm() && insn->r.imm == -1) {
                insn->opcode = OP_MOV;
                insn->r = Mop();
                continue;
            }
            // x ^ 0 → x (MOV)
            if (insn->opcode == OP_XOR && insn->r.isImm() && insn->r.imm == 0) {
                insn->opcode = OP_MOV;
                insn->r = Mop();
                continue;
            }
            // x - x → 0
            if (insn->opcode == OP_SUB && insn->l.isReg() && insn->r.isReg() &&
                insn->l.mreg == insn->r.mreg && insn->l.ssa_ver == insn->r.ssa_ver) {
                insn->opcode = OP_LDC;
                insn->l = Mop::imm64(0, insn->l.width);
                insn->r = Mop();
                continue;
            }
            // x ^ x → 0
            if (insn->opcode == OP_XOR && insn->l.isReg() && insn->r.isReg() &&
                insn->l.mreg == insn->r.mreg && insn->l.ssa_ver == insn->r.ssa_ver) {
                insn->opcode = OP_LDC;
                insn->l = Mop::imm64(0, insn->l.width);
                insn->r = Mop();
                continue;
            }
            // x | x → x (MOV)
            if (insn->opcode == OP_OR && insn->l.isReg() && insn->r.isReg() &&
                insn->l.mreg == insn->r.mreg && insn->l.ssa_ver == insn->r.ssa_ver) {
                insn->opcode = OP_MOV;
                insn->r = Mop();
                continue;
            }
            // x << 0 → x (MOV)
            if (insn->opcode == OP_SHL && insn->r.isImm() && insn->r.imm == 0) {
                insn->opcode = OP_MOV;
                insn->r = Mop();
                continue;
            }
            // x >> 0 → x (MOV)
            if ((insn->opcode == OP_SHR || insn->opcode == OP_SAR) &&
                insn->r.isImm() && insn->r.imm == 0) {
                insn->opcode = OP_MOV;
                insn->r = Mop();
                continue;
            }
            // 0 + x → x (MOV)
            if (insn->opcode == OP_ADD && insn->l.isImm() && insn->l.imm == 0) {
                insn->opcode = OP_MOV;
                insn->l = insn->r;
                insn->r = Mop();
                continue;
            }
            // 0 | x → x (MOV)
            if (insn->opcode == OP_OR && insn->l.isImm() && insn->l.imm == 0) {
                insn->opcode = OP_MOV;
                insn->l = insn->r;
                insn->r = Mop();
                continue;
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Pass 3: Enhanced copy propagation
// ═══════════════════════════════════════════════════════════════
void copyPropagationV2(MicrocodeBlockArray& mba) {
    // Track copy chains: (mreg, ssa_ver) → (source_mreg, source_ssa_ver)
    std::map<std::pair<int,int>, std::pair<int,int>> copyChain;

    auto resolveCopy = [&](int mreg, int ssa) -> std::pair<int,int> {
        std::set<std::pair<int,int>> visited;
        auto key = std::make_pair(mreg, ssa);
        while (copyChain.count(key)) {
            if (visited.count(key)) break; // cycle
            visited.insert(key);
            key = copyChain[key];
        }
        return key;
    };

    auto rewriteMop = [&](Mop& m) {
        if (m.isReg() && m.mreg >= 0 && m.mreg != 132) {
            auto resolved = resolveCopy(m.mreg, m.ssa_ver);
            m.mreg = resolved.first;
            m.ssa_ver = resolved.second;
        }
    };

    for (int b = 0; b < mba.numBlocks(); b++) {
        auto& blk = mba.blocks[b];
        copyChain.clear();

        for (auto* insn = blk->head; insn; insn = insn->next) {
            if (insn->iprops & IPROP_DEAD) continue;

            // CALL invalidates all copy chains (registers may be clobbered)
            if (insn->isCall()) {
                copyChain.clear();
                continue;
            }

            // Rewrite uses
            if (insn->opcode != OP_PHI) {
                rewriteMop(insn->l);
                rewriteMop(insn->r);
                if (insn->opcode == OP_STORE || insn->opcode == OP_FSTORE) {
                    rewriteMop(insn->d);
                }
            }

            // v9.8: 修复bug — 先失效旧拷贝链，再记录新拷贝链。
            // 原代码顺序: 记录 → 失效 → 新记录被立即擦除，导致copy propagation完全失效
            // Invalidate: if d is defined, remove OLD copy chain entries
            if (insn->def_mreg >= 0) {
                copyChain.erase(std::make_pair(insn->def_mreg, insn->ssa_version));
            }
            // Record copy: MOV d = l (where l is a register)
            if (insn->opcode == OP_MOV && insn->l.isReg() && insn->def_mreg >= 0) {
                copyChain[std::make_pair(insn->def_mreg, insn->ssa_version)] =
                    std::make_pair(insn->l.mreg, insn->l.ssa_ver);
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Pass 4: Enhanced dead code elimination
// ═══════════════════════════════════════════════════════════════
void deadCodeEliminationV2(MicrocodeBlockArray& mba) {
    // v9.32: Clear all IPROP_DEAD markers before starting DCE.
    // Previous DCE calls may have left stale dead markers that cause
    // "dead cascade" — subsequent passes skip dead instructions, and
    // DCE re-evaluates from a corrupted state. By clearing first,
    // each DCE call starts from a clean slate.
    for (int b = 0; b < mba.numBlocks(); b++) {
        for (auto* insn = mba.blocks[b]->head; insn; insn = insn->next) {
            insn->iprops &= ~IPROP_DEAD;
        }
        for (auto* phi : mba.blocks[b]->phi_nodes) {
            phi->iprops &= ~IPROP_DEAD;
        }
    }

    bool changed = true;
    int maxIter = 10;
    while (changed && maxIter-- > 0) {
        changed = false;
        // Collect all used mregs
        std::set<std::pair<int,int>> used; // (mreg, ssa_ver)

        for (int b = 0; b < mba.numBlocks(); b++) {
            auto& blk = mba.blocks[b];

            // v9.29: Collect PHI source operands from phi_nodes FIRST.
            // PHI nodes are stored in blk->phi_nodes (a separate vector),
            // NOT in the instruction linked list. Without this loop, DCE
            // may incorrectly mark definitions that only feed into PHI
            // nodes as dead, breaking loop-carried variable chains.
            for (auto* phi : blk->phi_nodes) {
                if (phi->iprops & IPROP_DEAD) continue;
                for (auto& [pmreg, pver] : phi->l.phi_srcs) {
                    if (pmreg >= 0 && pmreg != 132) {
                        used.insert(std::make_pair(pmreg, pver));
                    }
                }
            }

            for (auto* insn = blk->head; insn; insn = insn->next) {
                if (insn->iprops & IPROP_DEAD) continue;
                if (insn->hasSideEffect()) {
                    // Side effects are always "used"
                    // But also mark their operands as used
                }
                auto collectUse = [&](const Mop& m) {
                    if (m.isReg() && m.mreg >= 0 && m.mreg != 132) {
                        used.insert(std::make_pair(m.mreg, m.ssa_ver));
                    }
                    if (m.isMem() && m.mem_base >= 0 && m.mem_base != 131 && m.mem_base != 132) {
                        used.insert(std::make_pair(m.mem_base, m.mem_base_ssa_ver));
                    }
                };
                collectUse(insn->l);
                collectUse(insn->r);
                if (insn->opcode == OP_STORE || insn->opcode == OP_FSTORE) {
                    collectUse(insn->d);
                }
                // v9.19: Collect PHI source operands from inline PHI instructions
                // (backup for any PHI nodes that are in the instruction list).
                if (insn->opcode == OP_PHI) {
                    for (auto& [pmreg, pver] : insn->l.phi_srcs) {
                        if (pmreg >= 0 && pmreg != 132) {
                            used.insert(std::make_pair(pmreg, pver));
                        }
                    }
                }
                // v9.10: CALL instructions use argument registers.
                // Recursively follow the definition chain for each argument register
                // to find ALL mregs that contribute to the argument value.
                // This prevents DCE from removing instructions like LOAD that
                // are part of the argument computation chain.
                if (insn->isCall()) {
                    int numArgRegs = mba.is_aarch64 ? 8 : 4;
                    // Worklist: (mreg, ssa_ver) pairs to trace
                    std::vector<std::pair<int,int>> worklist;
                    std::set<std::pair<int,int>> visited;

                    for (int ai = 0; ai < numArgRegs; ai++) {
                        int argMreg = 100 + ai;
                        // Scan backwards to find the most recent defining instruction
                        for (auto* prev = insn->prev; prev; prev = prev->prev) {
                            if (prev->iprops & IPROP_DEAD) continue;
                            if (prev->def_mreg == argMreg) {
                                auto key = std::make_pair(argMreg, prev->ssa_version);
                                if (!visited.count(key)) {
                                    visited.insert(key);
                                    worklist.push_back(key);
                                    used.insert(key);
                                }
                                break;
                            }
                        }
                        // v9.29: Also check phi_nodes for the argument register.
                        // If the defining instruction is a PHI node (not in the
                        // linked list), we need to trace its sources.
                        for (auto* phi : blk->phi_nodes) {
                            if (phi->iprops & IPROP_DEAD) continue;
                            if (phi->def_mreg == argMreg) {
                                auto key = std::make_pair(argMreg, phi->ssa_version);
                                if (!visited.count(key)) {
                                    visited.insert(key);
                                    worklist.push_back(key);
                                    used.insert(key);
                                }
                                break;
                            }
                        }
                    }

                    // Recursively trace operands of each definition
                    while (!worklist.empty()) {
                        auto [mreg, ssa] = worklist.back();
                        worklist.pop_back();

                        // First, check if this is defined by a PHI node
                        bool foundInPhi = false;
                        for (auto* phi : blk->phi_nodes) {
                            if (phi->iprops & IPROP_DEAD) continue;
                            if (phi->def_mreg == mreg && phi->ssa_version == ssa) {
                                foundInPhi = true;
                                // Trace PHI source operands
                                for (auto& [pmreg, pver] : phi->l.phi_srcs) {
                                    if (pmreg >= 0 && pmreg != 132) {
                                        auto key = std::make_pair(pmreg, pver);
                                        if (!visited.count(key)) {
                                            visited.insert(key);
                                            worklist.push_back(key);
                                            used.insert(key);
                                        }
                                    }
                                }
                                break;
                            }
                        }
                        if (foundInPhi) continue;

                        // Find the instruction that defines this (mreg, ssa)
                        for (auto* prev = insn->prev; prev; prev = prev->prev) {
                            if (prev->iprops & IPROP_DEAD) continue;
                            if (prev->def_mreg == mreg && prev->ssa_version == ssa) {
                                // Collect register operands of this defining instruction
                                auto addToWorklist = [&](const Mop& m) {
                                    if (m.isReg() && m.mreg >= 0 && m.mreg != 132) {
                                        auto key = std::make_pair(m.mreg, m.ssa_ver);
                                        if (!visited.count(key)) {
                                            visited.insert(key);
                                            worklist.push_back(key);
                                            used.insert(key);
                                        }
                                    }
                                    if (m.isMem() && m.mem_base >= 0 && m.mem_base != 131 && m.mem_base != 132) {
                                        auto key = std::make_pair(m.mem_base, m.mem_base_ssa_ver);
                                        if (!visited.count(key)) {
                                            visited.insert(key);
                                            worklist.push_back(key);
                                            used.insert(key);
                                        }
                                    }
                                };
                                addToWorklist(prev->l);
                                addToWorklist(prev->r);
                                if (prev->opcode == OP_STORE || prev->opcode == OP_FSTORE) {
                                    addToWorklist(prev->d);
                                }
                                break;
                            }
                        }
                    }
                }
            }
        }

        // Mark unused definitions as dead
        for (int b = 0; b < mba.numBlocks(); b++) {
            auto& blk = mba.blocks[b];
            for (auto* insn = blk->head; insn; insn = insn->next) {
                if (insn->iprops & IPROP_DEAD) continue;
                if (insn->hasSideEffect()) continue;
                if (insn->def_mreg < 0) continue;
                auto key = std::make_pair(insn->def_mreg, insn->ssa_version);
                if (!used.count(key)) {
                    insn->iprops |= IPROP_DEAD;
                    changed = true;
                }
            }
            // v9.29: Also mark dead PHI nodes from phi_nodes.
            // PHI nodes are not in the instruction linked list, so the
            // loop above doesn't see them. We need to check them separately.
            for (auto* phi : blk->phi_nodes) {
                if (phi->iprops & IPROP_DEAD) continue;
                if (phi->hasSideEffect()) continue;
                if (phi->def_mreg < 0) continue;
                auto key = std::make_pair(phi->def_mreg, phi->ssa_version);
                if (!used.count(key)) {
                    phi->iprops |= IPROP_DEAD;
                    changed = true;
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Pass 5: Memory load folding
// ═══════════════════════════════════════════════════════════════
void memoryLoadFolding(MicrocodeBlockArray& mba) {
    for (int b = 0; b < mba.numBlocks(); b++) {
        auto& blk = mba.blocks[b];
        for (auto* insn = blk->head; insn; insn = insn->next) {
            if (insn->iprops & IPROP_DEAD) continue;
            if (insn->opcode != OP_LOAD && insn->opcode != OP_FLOAD) continue;
            if (!insn->l.isMem()) continue;

            // v8.7: If base register is 0 (null), load is null dereference → result is 0
            // This handles cases like: tmp5_v2 = 0; tmp5_v3 = *tmp5_v2
            if (insn->l.mem_base == 132) { // xzr/wzr = zero register
                insn->opcode = OP_LDC;
                insn->l = Mop::imm64(0, insn->l.width);
                insn->iprops &= ~IPROP_LOAD;
                continue;
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Pass 6: Store-after-store elimination (intra + cross-block)
// ═══════════════════════════════════════════════════════════════
void storeAfterStoreElimination(MicrocodeBlockArray& mba) {
    // Phase 1: Intra-block elimination (adjacent stores to same address)
    for (int b = 0; b < mba.numBlocks(); b++) {
        auto& blk = mba.blocks[b];
        MicroInsn* prev = nullptr;
        for (auto* insn = blk->head; insn; insn = insn->next) {
            if (insn->iprops & IPROP_DEAD) continue;
            if ((insn->opcode == OP_STORE || insn->opcode == OP_FSTORE) && prev && (prev->opcode == OP_STORE || prev->opcode == OP_FSTORE) &&
                !(prev->iprops & IPROP_DEAD)) {
                if (sameMemoryLoc(insn->d, prev->d)) {
                    prev->iprops |= IPROP_DEAD;
                }
            }
            prev = insn;
        }
    }

    // v9.21: Phase 2 — Cross-block store elimination.
    // If a block's last store to address A is immediately overwritten
    // by a store to A at the start of EVERY successor block (before any
    // intervening load), the store is dead. This handles patterns like:
    //
    //   if (cond) { *p = 1; } else { *p = 2; }
    //   *p = 3;   ← dead: both branches overwrite it
    //
    for (int b = 0; b < mba.numBlocks(); b++) {
        auto& blk = mba.blocks[b];
        if (blk->successors.empty()) continue;

        // Find the last non-dead store in this block (scan backwards)
        MicroInsn* lastStore = nullptr;
        for (auto* insn = blk->tail; insn; insn = insn->prev) {
            if (insn->iprops & IPROP_DEAD) continue;
            if (insn->opcode == OP_STORE || insn->opcode == OP_FSTORE) {
                lastStore = insn;
                break;
            }
            // Stop at barriers: branches, calls, returns, loads
            if (insn->isBranch() || insn->isCall() || insn->isRet() ||
                insn->isJumpTable() || insn->isLoad()) {
                break;
            }
        }
        if (!lastStore) continue;

        // Check if ALL successors overwrite this store before any load
        bool allOverwrite = true;
        for (int succ : blk->successors) {
            auto& succBlk = mba.blocks[succ];
            bool foundOverwrite = false;
            for (auto* insn = succBlk->head; insn; insn = insn->next) {
                if (insn->iprops & IPROP_DEAD) continue;
                if (insn->opcode == OP_PHI) continue;
                if (insn->opcode == OP_STORE || insn->opcode == OP_FSTORE) {
                    if (sameMemoryLoc(insn->d, lastStore->d)) {
                        foundOverwrite = true;
                    }
                    break; // Stop at first store (matches or not)
                }
                // Stop at first load or call (can't guarantee overwrite)
                if (insn->opcode == OP_LOAD || insn->opcode == OP_FLOAD || insn->isCall()) break;
            }
            if (!foundOverwrite) {
                allOverwrite = false;
                break;
            }
        }

        if (allOverwrite) {
            lastStore->iprops |= IPROP_DEAD;
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Pass 7: Branch simplification
// ═══════════════════════════════════════════════════════════════
void branchSimplification(MicrocodeBlockArray& mba) {
    for (int b = 0; b < mba.numBlocks(); b++) {
        auto& blk = mba.blocks[b];
        for (auto* insn = blk->head; insn; insn = insn->next) {
            if (insn->iprops & IPROP_DEAD) continue;
            if (insn->opcode != OP_CBRANCH) continue;

            // If the condition is comparing with a constant, evaluate
            if (insn->l.isImm() && insn->r.isImm()) {
                bool condResult = false;
                int64_t a = insn->l.imm, b_val = insn->r.imm;
                switch (insn->cond) {
                    case CC_EQ: condResult = (a == b_val); break;
                    case CC_NE: condResult = (a != b_val); break;
                    case CC_LT: condResult = (a < b_val); break;
                    case CC_LE: condResult = (a <= b_val); break;
                    case CC_GT: condResult = (a > b_val); break;
                    case CC_GE: condResult = (a >= b_val); break;
                    // v11.3: Fix — unsigned comparisons were missing,
                    // causing branchSimplification to skip them and leave
                    // dead code paths unresolved. Reference: r2's cond_is_true.
                    case CC_HS: condResult = ((uint64_t)a >= (uint64_t)b_val); break;  // unsigned >=
                    case CC_LO: condResult = ((uint64_t)a < (uint64_t)b_val); break;   // unsigned <
                    case CC_HI: condResult = ((uint64_t)a > (uint64_t)b_val); break;   // unsigned >
                    case CC_LS: condResult = ((uint64_t)a <= (uint64_t)b_val); break;  // unsigned <=
                    default: break;
                }
                if (condResult) {
                    insn->opcode = OP_GOTO;
                    insn->cond = CC_NONE;
                } else {
                    insn->iprops |= IPROP_DEAD;
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Pass 8: Phi node simplification
// ═══════════════════════════════════════════════════════════════
void phiSimplification(MicrocodeBlockArray& mba) {
    for (int b = 0; b < mba.numBlocks(); b++) {
        auto& blk = mba.blocks[b];
        auto& phis = blk->phi_nodes;

        for (auto it = phis.begin(); it != phis.end(); ) {
            auto* phi = *it;
            if (phi->iprops & IPROP_DEAD) {
                ++it;
                continue;
            }

            // If all phi sources are the same (mreg, ssa), replace with MOV
            bool allSame = true;
            std::pair<int,int> first = {-1, 0};
            for (auto& src : phi->l.phi_srcs) {
                if (first.first == -1) {
                    first = src;
                } else if (src != first) {
                    allSame = false;
                    break;
                }
            }

            if (allSame && first.first >= 0) {
                phi->opcode = OP_MOV;
                phi->l = Mop::reg(first.first);
                phi->l.ssa_ver = first.second;
                phi->l.phi_srcs.clear();
            }
            ++it;
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Pass 9: Unused parameter elimination
// ═══════════════════════════════════════════════════════════════
void unusedParamElimination(MicrocodeBlockArray& mba) {
    // v9.8: 修复两个bug:
    // 1. 参数寄存器范围从硬编码100-107改为根据is_aarch64动态确定
    // 2. 添加CALL指令的隐式参数使用检测
    int maxParamMreg = mba.is_aarch64 ? 107 : 103;  // x0-x7 or r0-r3

    // Collect all used mregs across the entire function
    std::set<int> usedMregs;

    for (int b = 0; b < mba.numBlocks(); b++) {
        auto& blk = mba.blocks[b];
        for (auto* insn = blk->head; insn; insn = insn->next) {
            if (insn->iprops & IPROP_DEAD) continue;
            auto collect = [&](const Mop& m) {
                if (m.isReg() && m.mreg >= 0 && m.mreg != 132) {
                    usedMregs.insert(m.mreg);
                }
                if (m.isMem() && m.mem_base >= 0 && m.mem_base != 131 && m.mem_base != 132) {
                    usedMregs.insert(m.mem_base);
                }
            };
            collect(insn->l);
            collect(insn->r);
            if (insn->opcode == OP_STORE || insn->opcode == OP_FSTORE) collect(insn->d);
            // v9.8: CALL instructions implicitly use argument registers.
            // The backward scan from DCE keeps arg-computing instructions alive,
            // but this pass also needs to detect the implicit arg register usage.
            if (insn->isCall()) {
                int numArgRegs = mba.is_aarch64 ? 8 : 4;
                for (int ai = 0; ai < numArgRegs; ai++) {
                    usedMregs.insert(100 + ai);
                }
            }
        }
    }

    // Mark param regs that are written but never read as dead
    for (int b = 0; b < mba.numBlocks(); b++) {
        auto& blk = mba.blocks[b];
        for (auto* insn = blk->head; insn; insn = insn->next) {
            if (insn->iprops & IPROP_DEAD) continue;
            if (insn->def_mreg < 0) continue;
            // Parameter registers: use dynamic range
            if (insn->def_mreg >= 100 && insn->def_mreg <= maxParamMreg) {
                if (!usedMregs.count(insn->def_mreg)) {
                    insn->iprops |= IPROP_DEAD;
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Pass 10: Cross-block constant propagation
// 对标 Ghidra 规则引擎的常量传播：
//   1) 若 phi 节点的所有源都解析为同一个常量，则将 phi 替换为 OP_LDC。
//   2) 将 OP_LDC / OP_MOV(imm) 定义的常量值传播到所有使用该 (mreg, ssa_ver)
//      的寄存器操作数处（跨块有效，因为 SSA 定义支配所有使用点）。
//   零寄存器(132) 恒为 0；仅替换 MOP_REG 操作数，MEM 操作数交给
//   memoryLoadFolding / constantFolding 处理。
// ═══════════════════════════════════════════════════════════════
void crossBlockConstantPropagation(MicrocodeBlockArray& mba) {
    bool changed = true;
    int maxIter = 5;
    while (changed && maxIter-- > 0) {
        changed = false;

        // 收集所有“值为常量”的定义: (mreg, ssa_ver) -> (value, width)
        // 包括 OP_LDC，以及 OP_MOV l=imm（本质等价于加载常量）。
        std::map<std::pair<int,int>, std::pair<int64_t,int>> constDefs;
        for (int b = 0; b < mba.numBlocks(); b++) {
            auto& blk = mba.blocks[b];
            for (auto* insn = blk->head; insn; insn = insn->next) {
                if (insn->iprops & IPROP_DEAD) continue;
                if (insn->def_mreg < 0) continue;
                if ((insn->opcode == OP_LDC || insn->opcode == OP_MOV) &&
                    insn->l.isImm()) {
                    constDefs[std::make_pair(insn->def_mreg, insn->ssa_version)] =
                        std::make_pair(insn->l.imm,
                                       insn->l.width > 0 ? insn->l.width : 8);
                }
            }
            // phi_nodes 中的指令可能已被前一轮转为 OP_LDC，一并收集。
            for (auto* phi : blk->phi_nodes) {
                if (phi->iprops & IPROP_DEAD) continue;
                if (phi->def_mreg < 0) continue;
                if ((phi->opcode == OP_LDC || phi->opcode == OP_MOV) &&
                    phi->l.isImm()) {
                    constDefs[std::make_pair(phi->def_mreg, phi->ssa_version)] =
                        std::make_pair(phi->l.imm,
                                       phi->l.width > 0 ? phi->l.width : 8);
                }
            }
        }

        // 解析 (mreg, ver) 是否为常量。零寄存器(132) 恒为 0。
        auto resolveConst = [&](int mreg, int ver,
                                int64_t& val, int& width) -> bool {
            if (mreg == 132) {  // xzr/wzr
                val = 0;
                width = 8;
                return true;
            }
            auto it = constDefs.find(std::make_pair(mreg, ver));
            if (it != constDefs.end()) {
                val = it->second.first;
                width = it->second.second;
                return true;
            }
            return false;
        };

        // ── Phase 1: phi 节点常量替换 ──
        for (int b = 0; b < mba.numBlocks(); b++) {
            auto& blk = mba.blocks[b];
            for (auto* phi : blk->phi_nodes) {
                if (phi->iprops & IPROP_DEAD) continue;
                if (phi->l.phi_srcs.empty()) continue;

                bool allConst = true;
                int64_t constVal = 0;
                int constWidth = 8;
                bool firstConst = true;

                for (auto& src : phi->l.phi_srcs) {
                    int64_t val = 0;
                    int w = 8;
                    if (!resolveConst(src.first, src.second, val, w)) {
                        allConst = false;
                        break;
                    }
                    if (firstConst) {
                        constVal = val;
                        constWidth = w;
                        firstConst = false;
                    } else if (constVal != val) {
                        allConst = false;
                        break;
                    }
                }

                if (allConst && !firstConst) {
                    phi->opcode = OP_LDC;
                    phi->l = Mop::imm64(constVal, constWidth);
                    phi->l.phi_srcs.clear();
                    // 立即登记，便于本轮后续 phi / 使用点引用。
                    if (phi->def_mreg >= 0) {
                        constDefs[std::make_pair(phi->def_mreg, phi->ssa_version)] =
                            std::make_pair(constVal, constWidth);
                    }
                    changed = true;
                }
            }
        }

        // ── Phase 2: 将常量传播到寄存器使用处（跨块） ──
        // 仅替换 MOP_REG 操作数；跳过零寄存器(132) 的使用（由其它 pass
        // 专门处理），避免干扰 memoryLoadFolding 等对 132 的特殊判断。
        for (int b = 0; b < mba.numBlocks(); b++) {
            auto& blk = mba.blocks[b];
            for (auto* insn = blk->head; insn; insn = insn->next) {
                if (insn->iprops & IPROP_DEAD) continue;
                if (insn->isBranch() || insn->isCall() ||
                    insn->isRet() || insn->isJumpTable()) continue;
                if (insn->opcode == OP_PHI) continue;

                auto tryReplace = [&](Mop& m) -> bool {
                    if (!m.isReg()) return false;
                    if (m.mreg < 0 || m.mreg == 132) return false;
                    int64_t val = 0;
                    int w = 8;
                    if (!resolveConst(m.mreg, m.ssa_ver, val, w)) return false;
                    // 按使用宽度截断，避免位宽不匹配导致的符号/零扩展错误。
                    if (m.width > 0 && m.width < 8) {
                        uint64_t mask = (1ULL << (m.width * 8)) - 1;
                        val = (int64_t)((uint64_t)val & mask);
                    }
                    m = Mop::imm64(val, m.width > 0 ? m.width : w);
                    return true;
                };

                bool c = false;
                c |= tryReplace(insn->l);
                c |= tryReplace(insn->r);
                if (c) changed = true;
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Pass 11: Strength reduction
// 将乘/除 2 的幂转换为位移：
//   x * 2^n   → x << n   (n >= 1)
//   x / 2^n   → x >> n   (unsigned, n >= 1)
// n == 0 (即 x*1 / x/1) 已由 algebraicSimplifyV2 处理，这里跳过。
// ═══════════════════════════════════════════════════════════════
void strengthReduction(MicrocodeBlockArray& mba) {
    // 计算 2 的幂的对数（仅对 val > 0 且为 2 的幂调用；使用拷贝避免溢出）。
    auto log2pow2 = [](int64_t v) -> int {
        int s = 0;
        while (v > 1) { v >>= 1; s++; }
        return s;
    };

    for (int b = 0; b < mba.numBlocks(); b++) {
        for (auto* insn = mba.blocks[b]->head; insn; insn = insn->next) {
            if (insn->iprops & IPROP_DEAD) continue;
            if (insn->isBranch() || insn->isCall() ||
                insn->isRet() || insn->isJumpTable()) continue;

            // x * 2^n → x << n
            if (insn->opcode == OP_MUL && insn->r.isImm()) {
                int64_t val = insn->r.imm;
                if (val > 1 && (val & (val - 1)) == 0) {
                    int shift = log2pow2(val);
                    insn->opcode = OP_SHL;
                    insn->r.imm = shift;
                }
                continue;
            }

            // x / 2^n (unsigned) → x >> n
            if (insn->opcode == OP_UDIV && insn->r.isImm()) {
                int64_t val = insn->r.imm;
                if (val > 1 && (val & (val - 1)) == 0) {
                    int shift = log2pow2(val);
                    insn->opcode = OP_SHR;
                    insn->r.imm = shift;
                }
                continue;
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Pass 12: Redundant load elimination
// 若同一内存地址（同 base / base_ssa_ver / offset / width / signed）
// 在块内被连续加载，且中间没有 store / call 改写内存，则将后续 load
// 替换为从首次 load 结果的 OP_MOV。volatile load 不参与消除。
// store 失效时按 (base, ver, offset) 失效，覆盖任意 width/signed。
// ═══════════════════════════════════════════════════════════════
void redundantLoadElimination(MicrocodeBlockArray& mba) {
    // (mem_base, mem_base_ssa_ver, mem_offset, width, mem_signed)
    using LoadKey = std::tuple<int, int, int64_t, int, int>;

    for (int b = 0; b < mba.numBlocks(); b++) {
        auto& blk = mba.blocks[b];
        std::map<LoadKey, MicroInsn*> lastLoad;

        for (auto* insn = blk->head; insn; insn = insn->next) {
            if (insn->iprops & IPROP_DEAD) continue;

            // CALL 可能改写任意内存，清空所有缓存。
            if (insn->isCall()) {
                lastLoad.clear();
                continue;
            }

            if ((insn->opcode == OP_LOAD || insn->opcode == OP_FLOAD) && insn->l.isMem()) {
                // volatile load 必须保留，既不消除也不作为复用源。
                if (insn->iprops & IPROP_VOLATILE) continue;

                LoadKey key = std::make_tuple(
                    insn->l.mem_base,
                    insn->l.mem_base_ssa_ver,
                    insn->l.mem_offset,
                    insn->l.width,
                    insn->l.mem_signed ? 1 : 0);

                auto it = lastLoad.find(key);
                if (it != lastLoad.end() && it->second &&
                    it->second->def_mreg >= 0) {
                    // 用首次 load 的结果做 MOV，消除冗余 load。
                    int prevDef = it->second->def_mreg;
                    int prevVer = it->second->ssa_version;
                    int loadWidth = insn->l.width > 0 ? insn->l.width : 8;
                    insn->opcode = OP_MOV;
                    insn->l = Mop::reg(prevDef, loadWidth);
                    insn->l.ssa_ver = prevVer;
                    insn->r = Mop();
                    insn->iprops &= ~IPROP_LOAD;
                    // 不覆盖 lastLoad[key]，后续同址 load 仍指向首次 load。
                } else if (insn->def_mreg >= 0) {
                    lastLoad[key] = insn;
                }
                continue;
            }

            // STORE 改写该地址，按 (base, ver, offset) 失效所有 width/signed
            // 的缓存（部分覆盖也会使先前 load 失效）。
            if ((insn->opcode == OP_STORE || insn->opcode == OP_FSTORE) && insn->d.isMem()) {
                int sbase = insn->d.mem_base;
                int sver = insn->d.mem_base_ssa_ver;
                int64_t soff = insn->d.mem_offset;
                for (auto it = lastLoad.begin(); it != lastLoad.end(); ) {
                    if (std::get<0>(it->first) == sbase &&
                        std::get<1>(it->first) == sver &&
                        std::get<2>(it->first) == soff) {
                        it = lastLoad.erase(it);
                    } else {
                        ++it;
                    }
                }
                continue;
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Pass 13: Sweep dead instructions
// ═══════════════════════════════════════════════════════════════
void sweepDeadInstructions(MicrocodeBlockArray& mba) {
    for (int b = 0; b < mba.numBlocks(); b++) {
        auto& blk = mba.blocks[b];
        MicroInsn* prev = nullptr;
        MicroInsn* insn = blk->head;
        while (insn) {
            MicroInsn* next = insn->next;
            if (insn->iprops & IPROP_DEAD) {
                // Remove dead instruction from linked list
                if (prev) {
                    prev->next = next;
                } else {
                    blk->head = next;
                }
                if (next) {
                    next->prev = prev;
                }
                // Don't delete the instruction (it might be referenced elsewhere)
                // Just unlink it from the block
            } else {
                prev = insn;
            }
            insn = next;
        }
        // Update tail pointer
        blk->tail = prev;
        if (blk->tail && blk->tail->next) {
            blk->tail->next = nullptr;
        }
    }

    // Also sweep dead phi nodes
    for (int b = 0; b < mba.numBlocks(); b++) {
        auto& blk = mba.blocks[b];
        auto& phis = blk->phi_nodes;
        phis.erase(
            std::remove_if(phis.begin(), phis.end(),
                [](MicroInsn* phi) { return phi->iprops & IPROP_DEAD; }),
            phis.end()
        );
    }
}

// ═══════════════════════════════════════════════════════════════
// Optimize V2: 组合优化入口
// ═══════════════════════════════════════════════════════════════
void optimizeV2(MicrocodeBlockArray& mba) {
    int maxIter = 10;

    for (int iter = 0; iter < maxIter; iter++) {
        int beforeInsns = 0;
        for (int b = 0; b < mba.numBlocks(); b++) {
            for (auto* insn = mba.blocks[b]->head; insn; insn = insn->next) {
                if (!(insn->iprops & IPROP_DEAD)) beforeInsns++;
            }
        }

        algebraicSimplifyV2(mba);
        constantFoldingV2(mba);
        copyPropagationV2(mba);
        crossBlockConstantPropagation(mba);  // 跨块常量传播
        strengthReduction(mba);              // 强度削减
        redundantLoadElimination(mba);       // 冗余加载消除
        memoryLoadFolding(mba);
        storeAfterStoreElimination(mba);
        branchSimplification(mba);
        phiSimplification(mba);
        deadCodeEliminationV2(mba);
        unusedParamElimination(mba);

        int afterInsns = 0;
        for (int b = 0; b < mba.numBlocks(); b++) {
            for (auto* insn = mba.blocks[b]->head; insn; insn = insn->next) {
                if (!(insn->iprops & IPROP_DEAD)) afterInsns++;
            }
        }

        if (afterInsns == beforeInsns) break; // 固定点收敛
    }

    // v9.9: Sweep dead instructions after convergence
    sweepDeadInstructions(mba);

    mba.maturity = MMAT_GLBOPT3;
}

} // namespace mc