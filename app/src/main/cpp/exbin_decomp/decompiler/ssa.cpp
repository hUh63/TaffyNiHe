// ssa.cpp — SSA construction (Cytron algorithm) + optimization passes
// Implements dominator tree (Cooper-Harvey-Kennedy), dominance frontier,
// phi placement, SSA renaming, and basic optimization passes.

#include "ssa.hpp"
#include "microcode_opt.hpp"
#include <algorithm>
#include <stack>
#include <set>
#include <map>
#include <functional>

namespace ssa {

// ── Helper: intersect function for Cooper-Harvey-Kennedy algorithm ──
// v4.8: Add bounds checking to prevent infinite loop on invalid idom entries
// (e.g. unreachable blocks that have no dominator).
//
// Reference: Cooper, Harvey, Kennedy "A Simple, Fast Dominance Algorithm"
// (2001). The intersect walks up the dominator tree from b1 and b2 using
// RPO numbers, advancing the pointer with the smaller RPO number until
// they meet. This is O(depth) per intersect instead of O(n).
static int intersect(int b1, int b2, const std::vector<int>& idom, const std::vector<int>& rpo_num) {
    int n = (int)idom.size();
    int max_iter = n * 2;  // safety limit
    while (b1 != b2 && max_iter-- > 0) {
        // v4.8: Guard against invalid idom entries (unreachable blocks)
        if (b1 < 0 || b1 >= n || idom[b1] < 0 || idom[b1] >= n) break;
        while (rpo_num[b1] > rpo_num[b2] && b1 >= 0 && b1 < n && idom[b1] >= 0 && idom[b1] != b1)
            b1 = idom[b1];
        if (b2 < 0 || b2 >= n || idom[b2] < 0 || idom[b2] >= n) break;
        while (rpo_num[b2] > rpo_num[b1] && b2 >= 0 && b2 < n && idom[b2] >= 0 && idom[b2] != b2)
            b2 = idom[b2];
    }
    return (b1 == b2) ? b1 : -1;
}

// ── Phase 1: Compute dominator tree (Cooper-Harvey-Kennedy) ──
// Reference: Ghidra flow.cc dominatorTree(). Iterates blocks in reverse
// post-order (forward) and applies the intersect() helper until a fixpoint.
void computeDominators(mc::MicrocodeBlockArray& mba) {
    int n = mba.numBlocks();
    if (n == 0) return;

    // Clear previous dominator-tree state (idempotent recomputation).
    for (int b = 0; b < n; b++) {
        mba.blocks[b]->dom_parent = -1;
        mba.blocks[b]->dom_children.clear();
    }

    // Step 1: Compute reverse postorder
    auto rpo = reversePostorder(mba);

    // Map block_id → RPO number
    std::vector<int> rpo_num(n, -1);
    for (int i = 0; i < (int)rpo.size(); i++)
        rpo_num[rpo[i]] = i;

    // Step 2: Initialize idom
    std::vector<int> idom(n, -1);
    idom[mba.entry_block] = mba.entry_block;

    // Step 3: Iterate until convergence
    bool changed = true;
    while (changed) {
        changed = false;
        for (int b : rpo) {
            if (b == mba.entry_block) continue;
            // Find first processed predecessor
            int new_idom = -1;
            for (int p : mba.blocks[b]->predecessors) {
                if (idom[p] != -1) {
                    if (new_idom == -1)
                        new_idom = p;
                    else
                        new_idom = intersect(p, new_idom, idom, rpo_num);
                }
            }
            if (new_idom != -1 && idom[b] != new_idom) {
                idom[b] = new_idom;
                changed = true;
            }
        }
    }

    // Step 4: Fill in dom_parent and dom_children
    for (int b = 0; b < n; b++) {
        if (b == mba.entry_block) {
            mba.blocks[b]->dom_parent = -1;
        } else if (idom[b] != -1 && idom[b] != b) {
            mba.blocks[b]->dom_parent = idom[b];
            mba.blocks[idom[b]]->dom_children.push_back(b);
        }
    }
}

// ── Phase 1b: Compute post-dominator tree ──
// Reference: Ghidra flow.cc postDominatorTree(). Mirrors the forward
// computation on the reverse CFG with a virtual exit node connecting to
// all blocks with no successors. Uses Cooper-Harvey-Kennedy intersect.
void computePostDominators(mc::MicrocodeBlockArray& mba) {
    int n = mba.numBlocks();
    if (n == 0) return;

    // Clear previous post-dominator-tree state.
    for (int b = 0; b < n; b++) {
        mba.blocks[b]->pdom_parent = -1;
        mba.blocks[b]->pdom_children.clear();
        mba.blocks[b]->pdom.clear();
    }

    // Step 1: Compute reverse-postorder on the REVERSE CFG.
    // In the reverse CFG, the virtual exit becomes the root.
    auto rpo = reversePostorderReverse(mba);

    std::vector<int> rpo_num(n, -1);
    for (int i = 0; i < (int)rpo.size(); i++)
        rpo_num[rpo[i]] = i;

    // Step 2: Identify "entry" blocks of the reverse CFG = exit blocks of
    // the forward CFG (blocks with no successors). These are post-dominated
    // only by the virtual exit (represented as themselves).
    std::vector<bool> isExit(n, false);
    for (int b = 0; b < n; b++) {
        if (mba.blocks[b]->successors.empty())
            isExit[b] = true;
    }

    // Step 3: Initialize ipdom. Exit blocks post-dominate themselves (root).
    std::vector<int> ipdom(n, -1);
    for (int b = 0; b < n; b++) {
        if (isExit[b])
            ipdom[b] = b;
    }

    // Step 4: Iterate to fixpoint over the reverse RPO.
    // For each block, ipdom(b) = intersect over successors s of b of ipdom(s).
    bool changed = true;
    int maxIter = n * 4 + 10;
    while (changed && maxIter-- > 0) {
        changed = false;
        for (int b : rpo) {
            if (isExit[b]) continue;
            int new_ipdom = -1;
            for (int s : mba.blocks[b]->successors) {
                if (s < 0 || s >= n) continue;
                if (ipdom[s] == -1) continue;
                if (new_ipdom == -1)
                    new_ipdom = s;
                else
                    new_ipdom = intersect(s, new_ipdom, ipdom, rpo_num);
            }
            if (new_ipdom != -1 && ipdom[b] != new_ipdom) {
                ipdom[b] = new_ipdom;
                changed = true;
            }
        }
    }

    // Step 5: Fill in pdom_parent and pdom_children.
    // Also fill pdom set (transitive post-dominators) for compatibility.
    for (int b = 0; b < n; b++) {
        if (isExit[b]) {
            mba.blocks[b]->pdom_parent = -1;
        } else if (ipdom[b] != -1 && ipdom[b] != b) {
            mba.blocks[b]->pdom_parent = ipdom[b];
            mba.blocks[ipdom[b]]->pdom_children.push_back(b);
        }
    }
    // Build the transitive pdom set by walking up the pdom tree.
    for (int b = 0; b < n; b++) {
        int runner = b;
        while (runner >= 0 && runner < n) {
            mba.blocks[b]->pdom.insert(runner);
            runner = mba.blocks[runner]->pdom_parent;
        }
    }
}

// ── Phase 1c: dominates — does block `a` dominate block `b`? ──
// Reference: Ghidra BlockBasic::dominates(). Walks dom_parent from b
// upward; a dominates b iff a is on that path (or a == b).
bool dominates(const mc::MicrocodeBlockArray& mba, int a, int b) {
    int n = mba.numBlocks();
    if (a < 0 || a >= n || b < 0 || b >= n) return false;
    if (a == b) return true;
    int runner = mba.blocks[b]->dom_parent;
    int guard = n + 5;
    while (runner >= 0 && runner < n && guard-- > 0) {
        if (runner == a) return true;
        if (runner == mba.blocks[runner]->dom_parent) break;  // self-loop guard
        runner = mba.blocks[runner]->dom_parent;
    }
    return false;
}

// ── Phase 1d: postDominates — does block `a` post-dominate block `b`? ──
bool postDominates(const mc::MicrocodeBlockArray& mba, int a, int b) {
    int n = mba.numBlocks();
    if (a < 0 || a >= n || b < 0 || b >= n) return false;
    if (a == b) return true;
    int runner = mba.blocks[b]->pdom_parent;
    int guard = n + 5;
    while (runner >= 0 && runner < n && guard-- > 0) {
        if (runner == a) return true;
        if (runner == mba.blocks[runner]->pdom_parent) break;  // self-loop guard
        runner = mba.blocks[runner]->pdom_parent;
    }
    return false;
}

// ── Phase 2: Compute dominance frontier ──
void computeDominanceFrontier(mc::MicrocodeBlockArray& mba) {
    int n = mba.numBlocks();
    for (int b = 0; b < n; b++) {
        auto& blk = mba.blocks[b];
        if (blk->predecessors.size() >= 2) {
            int idom_b = blk->dom_parent;
            for (int p : blk->predecessors) {
                int runner = p;
                while (runner != -1 && runner != idom_b) {
                    // Add b to DF(runner)
                    auto& df = mba.blocks[runner]->dom_frontier;
                    if (std::find(df.begin(), df.end(), b) == df.end())
                        df.push_back(b);
                    runner = mba.blocks[runner]->dom_parent;
                }
            }
        }
    }
}

// ── Phase 3: Place phi nodes ──
void placePhiNodes(mc::MicrocodeBlockArray& mba) {
    int n = mba.numBlocks();
    if (n == 0) return;

    // Collect all mregs that are defined, and their def sites
    std::map<int, std::set<int>> def_sites;  // mreg → set of block_ids where defined

    for (int b = 0; b < n; b++) {
        for (auto* insn = mba.blocks[b]->head; insn; insn = insn->next) {
            if (insn->def_mreg >= 0 && insn->def_mreg != 132) {  // skip zero register
                def_sites[insn->def_mreg].insert(b);
            }
        }
    }

    // For each mreg with multiple def sites, place phi nodes
    for (auto& [mreg, sites] : def_sites) {
        if (sites.size() < 2) continue;  // only need phi for multiply-defined vars

        // Worklist algorithm
        std::set<int> worklist(sites.begin(), sites.end());
        std::set<int> has_phi;

        while (!worklist.empty()) {
            int x = *worklist.begin();
            worklist.erase(worklist.begin());

            for (int df_block : mba.blocks[x]->dom_frontier) {
                if (has_phi.count(df_block)) continue;
                has_phi.insert(df_block);

                // Create phi node
                auto* phi = new mc::MicroInsn(mc::OP_PHI, mba.blocks[df_block]->start_addr);
                phi->def_mreg = mreg;
                phi->d = mc::Mop::reg(mreg, mba.getRegWidth(mreg));
                // Initialize phi sources: one per predecessor.
                // v9.21: Use -1 as placeholder instead of 0 to distinguish
                // uninitialized entries from actual SSA version 0.
                // renameSSA() will fill these with real versions.
                int num_preds = mba.blocks[df_block]->predecessors.size();
                phi->l.phi_srcs.resize(num_preds, {mreg, -1});

                // Insert at block head
                mba.blocks[df_block]->phi_nodes.push_back(phi);

                // If df_block is not already a def site, add to worklist
                if (!sites.count(df_block))
                    worklist.insert(df_block);
            }
        }
    }
}

// ── Phase 4: Rename SSA versions ──
void renameSSA(mc::MicrocodeBlockArray& mba) {
    int n = mba.numBlocks();
    if (n == 0) return;

    // Version counter per mreg
    std::map<int, int> next_version;
    // Version stack per mreg
    std::map<int, std::stack<int>> version_stacks;

    // Helper: get current version of mreg
    auto getCurrentVer = [&](int mreg) -> int {
        auto it = version_stacks.find(mreg);
        if (it == version_stacks.end() || it->second.empty())
            return 0;  // undefined (version 0)
        return it->second.top();
    };

    // Helper: create new version
    auto newVersion = [&](int mreg) -> int {
        int v = ++next_version[mreg];
        version_stacks[mreg].push(v);
        return v;
    };

    // DFS on dominator tree
    std::function<void(int)> renameBlock = [&](int b) {
        auto& blk = mba.blocks[b];

        // Track how many versions we push in this block (to pop later)
        std::vector<std::pair<int, int>> pushed;  // (mreg, count)

        // Rename phi node defs
        for (auto* phi : blk->phi_nodes) {
            int mreg = phi->def_mreg;
            if (mreg < 0) continue;
            int v = newVersion(mreg);
            phi->ssa_version = v;
            phi->d.ssa_ver = v;
            pushed.push_back({mreg, 1});
        }

        // Rename instructions
        for (auto* insn = blk->head; insn; insn = insn->next) {
            // Rename uses
            auto renameMop = [&](mc::Mop& m) {
                if (m.type == mc::MOP_REG && m.mreg >= 0 && m.mreg != 132) {
                    m.ssa_ver = getCurrentVer(m.mreg);
                } else if (m.type == mc::MOP_MEM && m.mem_base >= 0 && m.mem_base != 132) {
                    // v8.5: Rename memory base register with current SSA version
                    if (m.mem_base != 131) {  // not sp
                        m.mem_base_ssa_ver = getCurrentVer(m.mem_base);
                    }
                }
            };

            if (insn->opcode == mc::OP_PHI) {
                // Phi uses are renamed when processing predecessor blocks
                // (handled below in successor phi update)
            } else {
                renameMop(insn->l);
                renameMop(insn->r);
                // v9.30: For STORE, d is the memory address (MOP_MEM).
                // Rename it to set mem_base_ssa_ver so the CTree builder
                // can resolve the store base to the correct variable name.
                // Previously this was skipped ("no SSA on address"),
                // causing stores to create new UNINIT variable names
                // for the base register instead of reusing the existing name.
                if (insn->opcode == mc::OP_STORE || insn->opcode == mc::OP_FSTORE) {
                    renameMop(insn->d);
                }
            }

            // Rename def
            if (insn->def_mreg >= 0 && insn->def_mreg != 132) {
                int v = newVersion(insn->def_mreg);
                insn->ssa_version = v;
                if (insn->d.type == mc::MOP_REG)
                    insn->d.ssa_ver = v;
                pushed.push_back({insn->def_mreg, 1});
            }
        }

        // Update phi inputs of successor blocks
        for (int succ : blk->successors) {
            auto& succ_blk = mba.blocks[succ];
            // Find this block's index in successor's predecessors
            int pred_idx = -1;
            for (size_t i = 0; i < succ_blk->predecessors.size(); i++) {
                if (succ_blk->predecessors[i] == b) {
                    pred_idx = (int)i;
                    break;
                }
            }
            if (pred_idx < 0) continue;

            // Update each phi in successor
            for (auto* phi : succ_blk->phi_nodes) {
                int mreg = phi->def_mreg;
                if (mreg < 0) continue;
                if (pred_idx < (int)phi->l.phi_srcs.size()) {
                    // v9.21: getCurrentVer may return 0 if the mreg has never
                    // been defined along this path. This is valid for
                    // loop-entry edges where the variable is live-in.
                    int curVer = getCurrentVer(mreg);
                    phi->l.phi_srcs[pred_idx] = {mreg, curVer};
                }
            }
        }

        // Recurse on dominator tree children
        for (int child : blk->dom_children) {
            renameBlock(child);
        }

        // Pop versions defined in this block
        for (auto& [mreg, count] : pushed) {
            for (int i = 0; i < count; i++) {
                if (version_stacks[mreg].size() > 0)
                    version_stacks[mreg].pop();
            }
        }
    };

    renameBlock(mba.entry_block);
}

// ── Phase 5: Build memory SSA (对标 Ghidra heritage.cc) ──
//
// Ghidra's heritage.cc builds a full memory SSA by:
// 1. Tracking all STORE operations as memory definitions
// 2. At block merge points, inserting memory phi nodes
// 3. For each LOAD, finding the reaching definition(s)
//
// We implement a simplified version:
// - Track stack-relative stores/loads (sp + offset) as named locations
// - Track global stores/loads by address
// - At phi points, merge memory definitions
// - Annotate LOAD instructions with their reaching STORE version
// ── Phase 5b: Add entry block definitions for live-in registers ──
void addEntryDefs(mc::MicrocodeBlockArray& mba) {
    int entry = mba.entry_block;
    if (entry < 0 || entry >= mba.numBlocks()) return;
    auto* blk = mba.blocks[entry].get();
    if (!blk) return;

    // Scan the entry block's instructions to find which registers are
    // used before being defined. These are "live-in" registers that
    // have no definition in the function (e.g., callee-saved registers
    // like r4-r11, or parameter registers r0-r3 that are read before
    // being written).
    //
    // We add a MOV (undefined) definition at the beginning of the
    // entry block for each such register. This creates a labeled
    // variable in the C output instead of an "/* UNINIT */" name.
    std::set<int> used_in_entry;
    std::set<int> defined_in_entry;

    for (auto* insn = blk->head; insn; insn = insn->next) {
        if (insn->iprops & mc::IPROP_DEAD) continue;

        // Collect registers used in this instruction
        auto collectReg = [&](const mc::Mop& m) {
            if (m.type == mc::MOP_REG && m.mreg >= 0 && m.mreg != 132 && m.mreg != 131) {
                used_in_entry.insert(m.mreg);
            }
            // Also check memory base registers
            if (m.type == mc::MOP_MEM && m.mem_base >= 0 && m.mem_base != 131 && m.mem_base != 132) {
                used_in_entry.insert(m.mem_base);
            }
        };

        // Collect defined registers
        if (insn->def_mreg >= 0 && insn->def_mreg != 132) {
            defined_in_entry.insert(insn->def_mreg);
        }

        // Check operands (skip PHI which has special handling)
        if (insn->opcode != mc::OP_PHI) {
            collectReg(insn->l);
            collectReg(insn->r);
        }
        if (insn->opcode == mc::OP_STORE || insn->opcode == mc::OP_FSTORE) {
            collectReg(insn->d);
        }
    }

    // Also check PHI node source operands
    for (auto* phi : blk->phi_nodes) {
        if (phi->def_mreg >= 0 && phi->def_mreg != 132) {
            defined_in_entry.insert(phi->def_mreg);
        }
        for (auto& [pmreg, pver] : phi->l.phi_srcs) {
            if (pmreg >= 0 && pmreg != 132) {
                // Entry block's PHI sources come from the "virtual" predecessor
                // (the caller). These are live-in registers.
                used_in_entry.insert(pmreg);
            }
        }
    }

    // Find registers that are used but not defined in the entry block
    std::vector<int> undef_regs;
    for (int mreg : used_in_entry) {
        if (defined_in_entry.count(mreg) == 0) {
            undef_regs.push_back(mreg);
        }
    }

    // v25.0: Also add ALL callee-saved registers proactively.
    // For ARM32, the PUSH {r4-r11,lr} instruction generates STORE micro-
    // instructions with the register values in the `l` operand. However,
    // the SSA analysis may have already inserted definitions for these
    // registers (e.g., if the entry block uses them), or the STORE may
    // have been optimized away. Adding callee-saved registers by default
    // ensures they always get a proper variable name, even if the scan
    // above missed them.
    //
    // ARM32 AAPCS: r4-r11 (mreg 104-111) are callee-saved.
    // AArch64: x19-x28 (mreg 119-128) are callee-saved.
    if (mba.is_aarch64) {
        for (int mreg = 119; mreg <= 128; mreg++) {
            if (defined_in_entry.count(mreg) == 0) {
                undef_regs.push_back(mreg);
            }
        }
    } else {
        // ARM32: r4-r11 (mreg 104-111)
        for (int mreg = 104; mreg <= 111; mreg++) {
            if (defined_in_entry.count(mreg) == 0) {
                undef_regs.push_back(mreg);
            }
        }
    }

    // v54.0: Also add argument registers (r0-r3 for ARM32, x0-x7 for AArch64)
    // when they are used but not defined in the entry block. These registers
    // may hold function parameters (e.g., JNI_OnLoad's JavaVM* in r0) or
    // values loaded from the stack. Without this, they appear as "used before
    // assignment" in the decompiled output.
    if (mba.is_aarch64) {
        for (int mreg = 100; mreg <= 107; mreg++) {
            if (defined_in_entry.count(mreg) == 0 && used_in_entry.count(mreg) > 0) {
                undef_regs.push_back(mreg);
            }
        }
    } else {
        // ARM32: r0-r3 (mreg 100-103)
        for (int mreg = 100; mreg <= 103; mreg++) {
            if (defined_in_entry.count(mreg) == 0 && used_in_entry.count(mreg) > 0) {
                undef_regs.push_back(mreg);
            }
        }
    }

    if (undef_regs.empty()) return;

    // Insert MOV (undefined) definitions at the beginning of the entry block.
    // These create proper variable names for live-in registers.
    // We insert them in reverse order to maintain correct instruction order.
    std::sort(undef_regs.begin(), undef_regs.end());
    undef_regs.erase(std::unique(undef_regs.begin(), undef_regs.end()), undef_regs.end());
    for (int mreg : undef_regs) {
        // v54.0: Use MOV with immediate 0 instead of self-move (mreg, mreg).
        // Self-moves (mov r5, r5) generate "tmp_N = tmp_N" in the C tree,
        // which propagateCopies removes as a self-assignment, leaving the
        // variable without any definition. The result is an "uninitialized"
        // variable that has no visible assignment in the output.
        // Using MOV with immediate 0 generates "tmp_N = 0", which is a
        // real definition that survives copy propagation.
        auto* mov = new mc::MicroInsn(mc::OP_MOV, blk->start_addr);
        mov->def_mreg = mreg;
        mov->d = mc::Mop::reg(mreg, mba.getRegWidth(mreg));
        mov->l = mc::Mop::imm64(0, mba.getRegWidth(mreg));
        // Insert at the beginning of the block
        if (blk->head) {
            mov->next = blk->head;
            blk->head->prev = mov;
        }
        blk->head = mov;
        if (!blk->tail) blk->tail = mov;
        fprintf(stderr, "[DBG_ENTRY] Added entry def for mreg=%d\n", mreg);
    }
}

void buildMemorySSA(mc::MicrocodeBlockArray& mba) {
    // Step 1: Assign memory version numbers to STORE instructions
    // Each STORE creates a new memory version
    int mem_version = 0;
    std::map<std::pair<int, int64_t>, int> stack_loc_versions;  // (base_reg, offset) → last store version

    for (int b = 0; b < mba.numBlocks(); b++) {
        for (auto* insn = mba.blocks[b]->head; insn; insn = insn->next) {
            if (insn->opcode == mc::OP_STORE || insn->opcode == mc::OP_FSTORE) {
                mem_version++;
                insn->mem_version = mem_version;

                // Track stack-relative stores
                if (insn->d.isMem() && insn->d.mem_base >= 0) {
                    auto key = std::make_pair(insn->d.mem_base, insn->d.mem_offset);
                    stack_loc_versions[key] = mem_version;
                }
            }
        }
    }

    // Step 2: For each LOAD, find the reaching STORE version
    // This is a simplified reaching-definitions analysis:
    // - For stack-relative loads, use the tracked version from step 1
    // - For global loads, use version 0 (unknown)
    for (int b = 0; b < mba.numBlocks(); b++) {
        for (auto* insn = mba.blocks[b]->head; insn; insn = insn->next) {
            if ((insn->opcode == mc::OP_LOAD || insn->opcode == mc::OP_FLOAD) && insn->l.isMem()) {
                auto key = std::make_pair(insn->l.mem_base, insn->l.mem_offset);
                auto it = stack_loc_versions.find(key);
                if (it != stack_loc_versions.end()) {
                    insn->mem_version = it->second;
                } else {
                    insn->mem_version = 0;  // unknown source
                }
            }
        }
    }
}

} // namespace ssa

// ════════════════════════════════════════════════════════════════════
// Optimization passes (microcode_opt.hpp implementation)
// ════════════════════════════════════════════════════════════════════

namespace mc {

// ── Pass 1: Redundant mov elimination ──
void eliminateRedundantMov(MicrocodeBlockArray& mba) {
    for (int b = 0; b < mba.numBlocks(); b++) {
        for (auto* insn = mba.blocks[b]->head; insn; insn = insn->next) {
            if (insn->opcode == OP_MOV && insn->l.isReg() && insn->d.isReg()) {
                // Check if this is mov x0, x1 where x0 is not used later
                // For safety, only eliminate if both are the same register (nop mov)
                if (insn->l.mreg == insn->d.mreg) {
                    insn->iprops |= IPROP_DEAD;
                }
            }
        }
    }
}

// ── Pass 2: Local constant propagation ──
void localConstantPropagation(MicrocodeBlockArray& mba) {
    for (int b = 0; b < mba.numBlocks(); b++) {
        // Map: mreg → immediate value (for this block)
        std::map<int, int64_t> const_vals;

        for (auto* insn = mba.blocks[b]->head; insn; insn = insn->next) {
            // Replace uses with known constants
            auto tryReplace = [&](Mop& m) {
                if (m.type == MOP_REG && m.mreg >= 0) {
                    auto it = const_vals.find(m.mreg);
                    if (it != const_vals.end()) {
                        m = Mop::imm64(it->second, m.width);
                    }
                }
            };

            if (insn->opcode != OP_PHI && insn->opcode != OP_STORE && insn->opcode != OP_FSTORE) {
                tryReplace(insn->l);
                tryReplace(insn->r);
            }

            // Track new constant definitions
            if (insn->opcode == OP_LDC && insn->l.isImm() && insn->def_mreg >= 0) {
                const_vals[insn->def_mreg] = insn->l.imm;
            } else if (insn->opcode == OP_MOV && insn->l.isImm() && insn->def_mreg >= 0) {
                const_vals[insn->def_mreg] = insn->l.imm;
            } else if (insn->def_mreg >= 0) {
                // Non-constant def: remove from const_vals
                const_vals.erase(insn->def_mreg);
            }
        }
    }
}

// ── Pass 3: Dead store elimination ──
void deadStoreElimination(MicrocodeBlockArray& mba) {
    // Conservative: only eliminate stores that are immediately overwritten
    for (int b = 0; b < mba.numBlocks(); b++) {
        auto* insn = mba.blocks[b]->head;
        while (insn && insn->next) {
            if ((insn->opcode == OP_STORE || insn->opcode == OP_FSTORE) && (insn->next->opcode == OP_STORE || insn->next->opcode == OP_FSTORE)) {
                // Check if same address
                if (insn->d.type == MOP_MEM && insn->next->d.type == MOP_MEM &&
                    insn->d.mem_base == insn->next->d.mem_base &&
                    insn->d.mem_offset == insn->next->d.mem_offset &&
                    insn->d.width == insn->next->d.width) {
                    // Same address: first store is dead
                    insn->iprops |= IPROP_DEAD;
                }
            }
            insn = insn->next;
        }
    }
}

// ── Pass 4: Dead code elimination ──
void deadCodeElimination(MicrocodeBlockArray& mba) {
    bool changed = true;
    int iterations = 0;
    while (changed && iterations < 10) {
        changed = false;
        iterations++;

        // Collect all used mregs
        std::set<int> used_mregs;
        for (int b = 0; b < mba.numBlocks(); b++) {
            // v9.29: Collect PHI source mregs from phi_nodes FIRST.
            // PHI nodes are stored in blk->phi_nodes, not in the instruction
            // linked list. Without this, definitions that only feed into
            // PHI nodes may be incorrectly marked as dead.
            for (auto* phi : mba.blocks[b]->phi_nodes) {
                if (phi->isDead()) continue;
                auto phi_uses = phi->getUseMregs();
                for (int m : phi_uses) used_mregs.insert(m);
            }
            for (auto* insn = mba.blocks[b]->head; insn; insn = insn->next) {
                if (insn->isDead()) continue;
                auto uses = insn->getUseMregs();
                for (int m : uses) used_mregs.insert(m);
                // v9.3: CALL instructions implicitly use argument registers.
                // getUseMregs() only collects explicit operands (l, r, d),
                // but arguments in r0-r3 (ARM32) / x0-x7 (AArch64) are
                // also "used" by the call. Without this, DCE kills the
                // instructions that compute call arguments.
                if (insn->isCall()) {
                    int numArgRegs = mba.is_aarch64 ? 8 : 4;
                    for (int ai = 0; ai < numArgRegs; ai++) {
                        used_mregs.insert(100 + ai);
                    }
                }
            }
        }

        // Mark instructions with unused defs as dead
        for (int b = 0; b < mba.numBlocks(); b++) {
            // v9.29: Also mark dead PHI nodes from phi_nodes
            for (auto* phi : mba.blocks[b]->phi_nodes) {
                if (phi->isDead()) continue;
                if (phi->hasSideEffect()) continue;
                if (phi->def_mreg >= 0 && phi->def_mreg != 132) {
                    if (used_mregs.find(phi->def_mreg) == used_mregs.end()) {
                        phi->iprops |= IPROP_DEAD;
                        changed = true;
                    }
                }
            }
            for (auto* insn = mba.blocks[b]->head; insn; insn = insn->next) {
                if (insn->isDead()) continue;
                if (insn->hasSideEffect()) continue;
                if (insn->def_mreg >= 0 && insn->def_mreg != 132) {
                    if (used_mregs.find(insn->def_mreg) == used_mregs.end()) {
                        insn->iprops |= IPROP_DEAD;
                        changed = true;
                    }
                }
            }
        }
    }
}

// ── Pass 5: Expression folding (algebraic simplification) ──
void foldExpressions(MicrocodeBlockArray& mba) {
    for (int b = 0; b < mba.numBlocks(); b++) {
        for (auto* insn = mba.blocks[b]->head; insn; insn = insn->next) {
            if (insn->isDead()) continue;

            switch (insn->opcode) {
                case OP_ADD:
                    // add x, a, 0 → mov x, a
                    if (insn->r.isImm() && insn->r.imm == 0) {
                        insn->opcode = OP_MOV;
                        insn->r = Mop();
                    }
                    // add x, 0, a → mov x, a
                    if (insn->l.isImm() && insn->l.imm == 0) {
                        insn->opcode = OP_MOV;
                        insn->l = insn->r;
                        insn->r = Mop();
                    }
                    break;
                case OP_SUB:
                    // sub x, a, 0 → mov x, a
                    if (insn->r.isImm() && insn->r.imm == 0) {
                        insn->opcode = OP_MOV;
                        insn->r = Mop();
                    }
                    // sub x, a, a → ld x, #0
                    if (insn->l.isReg() && insn->r.isReg() &&
                        insn->l.mreg == insn->r.mreg) {
                        insn->opcode = OP_LDC;
                        insn->l = Mop::imm64(0, insn->d.width);
                        insn->r = Mop();
                    }
                    break;
                case OP_MUL:
                    // mul x, a, 0 → ld x, #0
                    if (insn->r.isImm() && insn->r.imm == 0) {
                        insn->opcode = OP_LDC;
                        insn->l = Mop::imm64(0, insn->d.width);
                        insn->r = Mop();
                    }
                    // mul x, a, 1 → mov x, a
                    if (insn->r.isImm() && insn->r.imm == 1) {
                        insn->opcode = OP_MOV;
                        insn->r = Mop();
                    }
                    break;
                case OP_OR:
                    // or x, a, a → mov x, a
                    if (insn->l.isReg() && insn->r.isReg() &&
                        insn->l.mreg == insn->r.mreg) {
                        insn->opcode = OP_MOV;
                        insn->r = Mop();
                    }
                    break;
                case OP_XOR:
                    // xor x, a, a → ld x, #0
                    if (insn->l.isReg() && insn->r.isReg() &&
                        insn->l.mreg == insn->r.mreg) {
                        insn->opcode = OP_LDC;
                        insn->l = Mop::imm64(0, insn->d.width);
                        insn->r = Mop();
                    }
                    break;
                case OP_AND:
                    // and x, a, a → mov x, a
                    if (insn->l.isReg() && insn->r.isReg() &&
                        insn->l.mreg == insn->r.mreg) {
                        insn->opcode = OP_MOV;
                        insn->r = Mop();
                    }
                    break;
                case OP_SHL:
                case OP_SHR:
                case OP_SAR:
                    // shift by 0 → mov
                    if (insn->r.isImm() && insn->r.imm == 0) {
                        insn->opcode = OP_MOV;
                        insn->r = Mop();
                    }
                    break;
                default:
                    break;
            }
        }
    }
}

// ── Pass 6: Condition code elimination ──
void eliminateConditionCodes(MicrocodeBlockArray& mba) {
    // ARM's cmp + b.eq → direct cbranch with comparison operands
    // The emitter already emits cmp as OP_SUB (volatile, no def)
    // and b.eq as OP_CBRANCH with l/r operands from tracked cmp state.
    // So this is already handled at emission time.
    // Here we just clean up dead cmp instructions (those not followed by cbranch).
    for (int b = 0; b < mba.numBlocks(); b++) {
        auto* insn = mba.blocks[b]->head;
        while (insn) {
            if (insn->opcode == OP_SUB && insn->def_mreg == -1 &&
                (insn->iprops & IPROP_VOLATILE)) {
                // This is a cmp — check if next is cbranch
                if (!insn->next || insn->next->opcode != OP_CBRANCH) {
                    // Dead cmp — mark as dead
                    insn->iprops |= IPROP_DEAD;
                }
            }
            insn = insn->next;
        }
    }
}

} // namespace mc
