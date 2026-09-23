#pragma once
// cfg_structure.hpp — Control flow structuring (DREAM method)
// Implements post-dominator tree, region reduction, goto elimination.
// 目标: goto < 5%

#include "microcode.hpp"
#include <memory>
#include <set>

namespace cfg {

// Region types (对标 Ghidra BlockGraph + Hex-Rays CTree control flow)
enum RegionType {
    REGION_BASIC_BLOCK,
    REGION_SEQUENCE,
    REGION_IF_THEN,
    REGION_IF_THEN_ELSE,
    REGION_WHILE,
    REGION_DO_WHILE,
    REGION_FOR,
    REGION_SWITCH,
    REGION_GOTO,
    REGION_BREAK,
    REGION_CONTINUE,
    REGION_RETURN,
    REGION_CONDITION  // v5.5: standalone condition region (Ghidra-style)
};

// Region node (tree structure representing structured CFG)
struct Region {
    RegionType type = REGION_BASIC_BLOCK;
    int block_id = -1;  // for REGION_BASIC_BLOCK
    uint64_t start_addr = 0;

    // Sequence
    std::vector<std::unique_ptr<Region>> children;

    // If-then-else
    mc::MicroInsn* condition = nullptr;
    std::unique_ptr<Region> then_region;
    std::unique_ptr<Region> else_region;
    bool condition_negated = false;
    // v5.5: OR/AND compound condition flag (Ghidra ruleBlockOr)
    bool is_or_condition = false;

    // Loops
    std::unique_ptr<Region> loop_body;
    mc::MicroInsn* loop_condition = nullptr;
    int loop_exit_target = -1;
    bool is_post_test = false;  // do-while
    // v3.23: Loop context for break/continue generation
    int header_block = -1;  // loop header block ID (continue target)
    int exit_block = -1;    // loop exit block ID (break target)

    // For loop
    mc::MicroInsn* for_init = nullptr;
    mc::MicroInsn* for_increment = nullptr;

    // Switch
    mc::MicroInsn* switch_expr = nullptr;
    std::vector<std::pair<int64_t, std::unique_ptr<Region>>> cases;
    std::unique_ptr<Region> default_region;

    // Goto
    int goto_target = -1;
    std::string goto_reason;

    Region() = default;
    Region(RegionType t) : type(t) {}

    // Deep clone (Region has unique_ptr members, so copy is deleted)
    std::unique_ptr<Region> clone() const;

    // Debug dump
    std::string dump(int indent = 0) const;
};

// Natural loop info
struct NaturalLoop {
    int header = -1;
    std::set<int> body;
    std::set<int> exits;
    int back_edge_from = -1;
    bool is_do_while = false;
    // v7.0: Loop nesting depth (Ghidra LoopBody nesting level).
    // 0 = outermost loop, 1 = loop nested inside one loop, etc.
    // Used by structuring to process inner loops before outer loops
    // and to validate containment relationships.
    int nesting_depth = 0;
    // v7.0: Parent loop header (-1 if outermost).
    // A loop L2's parent is the smallest loop L1 whose body strictly
    // contains L2's header (and body).
    int parent_loop_header = -1;
    // v7.0: Primary (preferred) exit block.
    // Set to the natural loop exit — the successor of the back-edge
    // (tail) block that leaves the loop. Falls back to *exits.begin().
    // Reference: Ghidra labels the header-exit as the loop's exit target.
    int primary_exit = -1;
};

// Control flow structurer
class CFGStructurer {
public:
    // Main entry: structure the CFG into a region tree
    std::unique_ptr<Region> structure(mc::MicrocodeBlockArray& mba);
    // Debug: print pdom values
    void computePostDominatorsDebug(mc::MicrocodeBlockArray& mba);

private:
    mc::MicrocodeBlockArray* mba_ = nullptr;

    // Compute post-dominator tree (reverse CFG, compute dominators)
    void computePostDominators();

    // Compute control dependence
    void computeControlDependence();

    // Detect natural loops
    std::vector<NaturalLoop> detectLoops();

    // Region reduction: repeatedly fold innermost structures
    std::unique_ptr<Region> reduce();

    // Pattern matchers
    std::unique_ptr<Region> trySequence(std::set<int>& remaining);
    std::unique_ptr<Region> tryIfThenElse(int branch_block, std::set<int>& remaining);
    std::unique_ptr<Region> tryWhileLoop(const NaturalLoop& loop, std::set<int>& remaining);
    std::unique_ptr<Region> tryDoWhileLoop(const NaturalLoop& loop, std::set<int>& remaining);
    std::unique_ptr<Region> trySwitch(int switch_block, std::set<int>& remaining);

    // Goto elimination (DREAM transformations)
    void eliminateGotos(Region* root);
    void convertConditionalGoto(Region* root);
    void recoverBreakContinue(Region* root);
    void promoteLoopCondition(Region* root);

    // Detected loops (filled by detectLoops, used by trySequence)
    std::vector<NaturalLoop> loops_;

    // v4.11: Pre-computed set of all loop exit blocks for fast if-break detection
    std::set<int> loop_exit_blocks_;

    // Set of loop headers that have already been structured (prevents infinite recursion)
    std::set<int> processed_loop_headers_;

    // v4.12: Pre-computed dominator trees for O(1) merge point finding.
    // idom_[b] = immediate dominator of block b (-1 = entry, -2 = not computed)
    // ipdom_[b] = immediate post-dominator of block b (-1 = exit, -2 = not computed)
    std::vector<int> idom_;
    std::vector<int> ipdom_;

    // Compute dominator and post-dominator trees
    void computeDominators(mc::MicrocodeBlockArray* mba);

    // O(1) merge point using post-dominator tree
    int findMergePointFast(int a, int b);

    // Check if block `a` dominates block `b` using the immediate dominator tree.
    // Walk up from b's idom chain; if we reach a, then a dominates b.
    bool dominates(int a, int b) const {
        if (a == b) return true;
        if (a < 0 || b < 0 || b >= (int)idom_.size()) return false;
        int cur = b;
        while (cur >= 0 && cur < (int)idom_.size()) {
            if (cur == a) return true;
            if (idom_[cur] == cur) break;  // safety: self-loop
            cur = idom_[cur];
        }
        return false;
    }

    // Find loop by header block; returns nullptr if not a loop header or already processed
    const NaturalLoop* findLoopByHeader(int header) const {
        if (processed_loop_headers_.count(header))
            return nullptr;  // already structured, skip
        for (auto& l : loops_)
            if (l.header == header) return &l;
        return nullptr;
    }
};

// ════════════════════════════════════════════════════════════════════
// Ghidra-style fixpoint rule engine (对标 Ghidra blockaction.cc)
//
// Instead of recursive trySequence, this uses a flat graph of region
// nodes where rules iteratively collapse subgraphs. No recursion,
// no depth limits — just a fixpoint loop with max iterations (100).
//
// Rules in priority order (highest first):
//   1. ruleDoWhile    — collapse do-while loops
//   2. ruleWhileDo    — collapse while loops
//   3. ruleIfElse     — collapse if-then-else
//   4. ruleIfThen     — collapse if-then (no else)
//   5. ruleCollapse   — collapse single-block regions
//   6. ruleSequence   — merge sequential regions
//   7. ruleLabelLoops — label loop headers/breaks
//   8. ruleBlockGoto  — collapse goto edges (v5.5, Ghidra ruleBlockGoto)
//   9. ruleBlockOr    — merge OR/AND conditions (v5.5, Ghidra ruleBlockOr)
//  10. ruleBlockCat   — merge block lists (v5.5, Ghidra ruleBlockCat)
//
// v5.5: selectGoto() — when all rules stall, pick an edge to mark as
//       unstructured goto, then continue. This is Ghidra's key mechanism
//       for handling irreducible CFGs WITHOUT flattening.
//
// Reference: Ghidra CollapseStructure::collapseAll, collapseInternal,
// selectGoto, ruleBlockGoto, ruleBlockOr, ruleBlockCat, ruleBlockDoWhile,
// ruleBlockWhileDo, ruleBlockIfElse, ruleBlockSwitch,
// ruleCollapseBlock, ruleLabelLoops, ruleMergeBlocks
// ════════════════════════════════════════════════════════════════════

class FixpointStructurer {
public:
    std::unique_ptr<Region> structure(mc::MicrocodeBlockArray& mba);

private:
    mc::MicrocodeBlockArray* mba_ = nullptr;
    std::vector<NaturalLoop> loops_;
    std::set<int> loop_headers_;
    std::set<int> loop_exits_;

    // Region graph node (flat graph, one node per structured region)
    struct RNode {
        int id;
        std::set<int> blocks;              // basic blocks in this region
        std::vector<int> succs;            // successor block IDs (in original CFG)
        std::vector<int> preds;            // predecessor block IDs
        RegionType type = REGION_BASIC_BLOCK;
        std::unique_ptr<Region> region;    // for non-basic-block regions
        mc::MicroInsn* condition = nullptr;
        bool condition_negated = false;
        int loop_header = -1;              // enclosing loop header (for break/continue)
        int loop_exit = -1;                // enclosing loop exit
        uint64_t start_addr = 0;
        // v5.5: goto edge tracking (Ghidra isGotoOut)
        std::set<int> goto_succs;          // successor block IDs marked as goto
    };

    std::vector<RNode> nodes_;

    // Rule implementations (return true if any change was made)
    bool ruleDoWhile();
    bool ruleFor();          // v11.3: detect for-loop (init+cond+inc) patterns
    bool ruleWhileDo();
    bool ruleIfElse();
    bool ruleIfThen();
    bool ruleCollapse();
    bool ruleSequence();
    bool ruleLabelLoops();
    bool ruleBlockGoto();    // v5.5: collapse goto edges
    bool ruleBlockOr();      // v5.5: merge OR/AND conditions
    bool ruleBlockCat();     // v5.5: merge block lists (enhanced sequence)
    bool ruleSwitch();       // v9.1: detect switch-case with jump table

    // v5.5: selectGoto — when all rules stall, pick an edge to mark as
    // unstructured goto. Returns true if an edge was marked.
    // Reference: Ghidra CollapseStructure::selectGoto
    bool selectGoto();

    // Helpers
    void buildInitial();
    void recomputeEdges();
    int findNodeByBlock(int block_id) const;
    std::unique_ptr<Region> buildRegionTree();
    std::unique_ptr<Region> nodeToRegion(int node_id, std::set<int>& visited);
    bool isGotoEdge(int from_block, int to_block);  // v5.5
    void markGotoEdge(int from_block, int to_block); // v5.5
    // v5.8: Flatten nested sequences — add region to seq, flattening if it's already a sequence
    static void addRegionFlatten(std::unique_ptr<Region>& seq, std::unique_ptr<Region> child);

    // v23.0: Prevent ruleLabelLoops from infinite looping
    int label_loops_counter_ = 0;

    // Dominator helpers
    std::vector<int> idom_;
    std::vector<int> ipdom_;
    void computeDominators();
    int findMergePointFast(int a, int b);
    int findMergePointBFS(int a, int b);

    // Check if block `a` dominates block `b` using the immediate dominator tree.
    // Walk up from b's idom chain; if we reach a, then a dominates b.
    bool dominates(int a, int b) const {
        if (a == b) return true;
        if (a < 0 || b < 0 || b >= (int)idom_.size()) return false;
        int cur = b;
        while (cur >= 0 && cur < (int)idom_.size()) {
            if (cur == a) return true;
            if (idom_[cur] == cur) break;  // safety: self-loop
            cur = idom_[cur];
        }
        return false;
    }
};

} // namespace cfg
