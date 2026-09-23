#pragma once
// ssa.hpp — SSA construction (Cytron algorithm)
// Implements dominator tree (Cooper-Harvey-Kennedy), dominance frontier,
// phi placement, and SSA renaming.

#include "microcode.hpp"
#include <stack>
#include <set>
#include <functional>

namespace ssa {

// Phase 1: Compute dominator tree using Cooper-Harvey-Kennedy algorithm
// Reference: Ghidra flow.cc dominatorTree() — uses RPO-ordered iteration
// with the classic intersect() helper that walks up the dom tree.
// Result: each MicroBlock.dom_parent and dom_children are filled.
void computeDominators(mc::MicrocodeBlockArray& mba);

// Phase 1b: Compute post-dominator tree (reverse CFG dominators)
// Reference: Ghidra flow.cc postDominatorTree() — mirrors the forward
// computation on the reverse CFG, with a virtual exit node.
// Result: each MicroBlock.pdom_parent and pdom_children are filled.
// Used by merge-point computation in cfg_structure.
void computePostDominators(mc::MicrocodeBlockArray& mba);

// Phase 1c: Dominance relation query.
// Returns true if block `a` dominates block `b` (a is an ancestor of b
// in the dominator tree, or a == b). Requires computeDominators first.
// Reference: Ghidra BlockBasic::dominates() walks dom_parent upward.
bool dominates(const mc::MicrocodeBlockArray& mba, int a, int b);

// Phase 1d: Post-dominance relation query.
// Returns true if block `a` post-dominates block `b` (a is an ancestor
// of b in the post-dominator tree, or a == b). Requires
// computePostDominators first.
bool postDominates(const mc::MicrocodeBlockArray& mba, int a, int b);

// Phase 2: Compute dominance frontier for each block
// Result: each MicroBlock.dom_frontier is filled.
void computeDominanceFrontier(mc::MicrocodeBlockArray& mba);

// Phase 3: Place phi nodes at dominance frontiers
// For each mreg with multiple definitions, place phi at DF of def sites.
void placePhiNodes(mc::MicrocodeBlockArray& mba);

// Phase 4: Rename SSA versions
// Walk dominator tree in preorder, maintain version stacks per mreg.
void renameSSA(mc::MicrocodeBlockArray& mba);

// Phase 5: Build memory SSA (simplified Novillo method)
// Partition memory into stack and global regions, build MemoryDef/Use/Phi.
void buildMemorySSA(mc::MicrocodeBlockArray& mba);

// Phase 5b: Add entry block definitions for registers used before defined
// After SSA renaming, the entry block may use registers that have never been
// defined (e.g., callee-saved registers like r4-r11 in ARM32, or parameter
// registers like r0-r3 that are read before being written). This function
// scans the entry block and adds MOV (undefined) definitions for any such
// register, so the CTree builder can emit proper variable names.
void addEntryDefs(mc::MicrocodeBlockArray& mba);

// Convenience: run all SSA construction phases
inline void buildSSA(mc::MicrocodeBlockArray& mba) {
    computeDominators(mba);
    computePostDominators(mba);
    computeDominanceFrontier(mba);
    placePhiNodes(mba);
    renameSSA(mba);
    addEntryDefs(mba);
    buildMemorySSA(mba);
}

// ── Implementation helpers ──

// Get all mregs defined in a block
inline std::set<int> getBlockDefs(const mc::MicroBlock& blk) {
    std::set<int> defs;
    for (auto* phi : blk.phi_nodes) {
        if (phi->def_mreg >= 0)
            defs.insert(phi->def_mreg);
    }
    for (auto* insn = blk.head; insn; insn = insn->next) {
        if (insn->def_mreg >= 0)
            defs.insert(insn->def_mreg);
    }
    return defs;
}

// Check if a block has a phi for a given mreg
inline bool hasPhi(const mc::MicroBlock& blk, int mreg) {
    for (auto* phi : blk.phi_nodes)
        if (phi->def_mreg == mreg) return true;
    return false;
}

// Compute reverse postorder traversal of CFG
inline std::vector<int> reversePostorder(const mc::MicrocodeBlockArray& mba) {
    std::vector<int> result;
    std::vector<bool> visited(mba.numBlocks(), false);
    std::vector<int> postorder;

    // DFS to get postorder
    std::function<void(int)> dfs = [&](int b) {
        if (b < 0 || b >= mba.numBlocks() || visited[b]) return;
        visited[b] = true;
        for (int s : mba.blocks[b]->successors)
            dfs(s);
        postorder.push_back(b);
    };
    dfs(mba.entry_block);

    // Reverse it
    result = postorder;
    std::reverse(result.begin(), result.end());
    return result;
}

// Compute reverse postorder traversal of the REVERSE CFG (successors
// become predecessors). This is used for post-dominator computation.
// Starts from the virtual exit (all blocks with no successors).
// Reference: Ghidra flow.cc builds the reverse graph implicitly.
inline std::vector<int> reversePostorderReverse(const mc::MicrocodeBlockArray& mba) {
    int n = mba.numBlocks();
    std::vector<int> postorder;
    std::vector<bool> visited(n, false);

    // Collect exit blocks (no successors) — these connect to the virtual exit.
    std::vector<int> exitBlocks;
    for (int i = 0; i < n; i++) {
        if (mba.blocks[i]->successors.empty())
            exitBlocks.push_back(i);
    }

    // DFS on reverse CFG: follow predecessors instead of successors.
    std::function<void(int)> dfs = [&](int b) {
        if (b < 0 || b >= n || visited[b]) return;
        visited[b] = true;
        for (int p : mba.blocks[b]->predecessors)
            dfs(p);
        postorder.push_back(b);
    };
    for (int e : exitBlocks)
        dfs(e);
    // Cover any remaining blocks (cycles not reachable from exits).
    for (int i = 0; i < n; i++)
        dfs(i);

    std::reverse(postorder.begin(), postorder.end());
    return postorder;
}

// Helper: find all exit blocks (blocks with no successors).
inline std::vector<int> findExitBlocks(const mc::MicrocodeBlockArray& mba) {
    std::vector<int> exits;
    for (int i = 0; i < mba.numBlocks(); i++) {
        if (mba.blocks[i]->successors.empty())
            exits.push_back(i);
    }
    return exits;
}

} // namespace ssa
