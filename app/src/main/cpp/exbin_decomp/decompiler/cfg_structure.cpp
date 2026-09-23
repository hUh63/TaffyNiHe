// cfg_structure.cpp — Control flow structuring (Ghidra-style region reduction)
//
// v4.1: 简化重写，使用 BFS 交集法确定 merge point
//   核心算法参考 Ghidra 的 BlockGraph::structureLoops() + CollapseStructure 思想：
//   1. 检测循环（back edge）
//   2. 对每个 CBRANCH 块，用 BFS 交集法确定 merge point
//   3. 然后在 merge point 之前收集各分支块
//   4. 按支配树顺序遍历

#include "cfg_structure.hpp"
#include <algorithm>
#include <cstdio>
#include <cstring>
#include <sstream>
#include <chrono>
#include <queue>
#include <functional>
#include <iostream>
#include <cassert>

// Debug control: 0 = minimal output, 1 = verbose debug
#ifndef DBG_CFG_VERBOSE
#define DBG_CFG_VERBOSE 1
#endif
#if DBG_CFG_VERBOSE
#define DBG_PRINT(...) fprintf(stderr, __VA_ARGS__)
#else
#define DBG_PRINT(...) ((void)0)
#endif

namespace cfg {

// ──── Region::clone ────
std::unique_ptr<Region> Region::clone() const {
    auto r = std::make_unique<Region>(type);
    r->block_id = block_id;
    r->start_addr = start_addr;
    r->condition = condition;
    r->condition_negated = condition_negated;
    r->is_or_condition = is_or_condition;
    r->loop_condition = loop_condition;
    r->loop_exit_target = loop_exit_target;
    r->is_post_test = is_post_test;
    r->header_block = header_block;
    r->exit_block = exit_block;
    r->for_init = for_init;
    r->for_increment = for_increment;
    r->switch_expr = switch_expr;
    r->goto_target = goto_target;
    r->goto_reason = goto_reason;
    for (auto& child : children)
        r->children.push_back(child->clone());
    if (then_region) r->then_region = then_region->clone();
    if (else_region) r->else_region = else_region->clone();
    if (loop_body) r->loop_body = loop_body->clone();
    for (auto& c : cases)
        r->cases.emplace_back(c.first, c.second ? c.second->clone() : nullptr);
    if (default_region) r->default_region = default_region->clone();
    return r;
}

// ──── Region::dump ────
std::string Region::dump(int indent) const {
    std::string pad(indent, ' ');
    std::ostringstream ss;
    switch (type) {
    case REGION_BASIC_BLOCK:
        ss << pad << "BLOCK(b" << block_id << ")";
        break;
    case REGION_SEQUENCE:
        ss << pad << "SEQUENCE";
        break;
    case REGION_IF_THEN:
        ss << pad << "IF";
        if (condition_negated) ss << "(NOT)";
        break;
    case REGION_IF_THEN_ELSE:
        ss << pad << "IF_ELSE";
        if (condition_negated) ss << "(NOT)";
        break;
    case REGION_WHILE:
        ss << pad << "WHILE";
        if (condition_negated) ss << "(NOT)";
        break;
    case REGION_DO_WHILE:
        ss << pad << "DO_WHILE";
        if (condition_negated) ss << "(NOT)";
        break;
    case REGION_FOR:
        ss << pad << "FOR";
        break;
    case REGION_SWITCH:
        ss << pad << "SWITCH";
        break;
    case REGION_GOTO:
        ss << pad << "GOTO(b" << goto_target << ")";
        break;
    case REGION_BREAK:
        ss << pad << "BREAK";
        break;
    case REGION_CONTINUE:
        ss << pad << "CONTINUE";
        break;
    case REGION_RETURN:
        ss << pad << "RETURN";
        break;
    case REGION_CONDITION:
        ss << pad << "CONDITION";
        if (is_or_condition) ss << "(OR)";
        if (condition_negated) ss << "(NOT)";
        break;
    }
    ss << "\n";
    if (condition) {
        ss << pad << "  cond: b" << condition->target_block << "\n";
    }
    if (then_region) {
        ss << pad << "  then:\n" << then_region->dump(indent + 4);
    }
    if (else_region) {
        ss << pad << "  else:\n" << else_region->dump(indent + 4);
    }
    if (loop_body) {
        ss << pad << "  body:\n" << loop_body->dump(indent + 4);
    }
    for (auto& c : children) {
        if (c) ss << c->dump(indent);
    }
    return ss.str();
}

// ──── Compute post-dominators (proper iterative data-flow) ────
void CFGStructurer::computePostDominators() {
    int n = mba_->numBlocks();
    if (n == 0) return;

    int virtualExit = n;

    // 初始化：pdom[i] = {i} ∪ all_blocks (except entry)
    for (int i = 0; i < n; i++) {
        auto* blk = mba_->getBlock(i);
        blk->pdom_parent = -1;
        blk->pdom_children.clear();
        blk->pdom.clear();
        for (int j = 0; j < n; j++) blk->pdom.insert(j);
        blk->pdom.insert(virtualExit);
    }

    // 出口块（无后继）的 pdom = {self}
    for (int i = 0; i < n; i++) {
        auto* blk = mba_->getBlock(i);
        if (blk->successors.empty()) {
            blk->pdom.clear();
            blk->pdom.insert(i);
            blk->pdom.insert(virtualExit);
        }
    }

    bool changed = true;
    int maxIter = 200;
    while (changed && --maxIter > 0) {
        changed = false;
        for (int i = n - 1; i >= 0; i--) {
            auto* blk = mba_->getBlock(i);
            if (blk->successors.empty()) continue; // 出口块不变

            std::set<int> newPdom;
            bool first = true;
            for (int succ : blk->successors) {
                if (succ < 0 || succ >= n) continue;
                auto& succPdom = mba_->getBlock(succ)->pdom;
                if (first) {
                    for (int x : succPdom) {
                        if (x < n) newPdom.insert(x);
                    }
                    if (succPdom.count(virtualExit)) newPdom.insert(virtualExit);
                    first = false;
                } else {
                    std::set<int> intersect;
                    for (int x : newPdom) {
                        if (succPdom.count(x)) intersect.insert(x);
                    }
                    newPdom = std::move(intersect);
                }
            }
            newPdom.insert(i); // n ∈ pdom(n)

            if (newPdom != blk->pdom) {
                blk->pdom = std::move(newPdom);
                changed = true;
            }
        }
    }

    // 构建 pdom 树：pdom_parent[i] = pdom(i) 中（除 i 外）"最近"的节点
    // "最近" = 在 CFG 中距离 i 最近（即 i 的 pdom 中，pdom_parent 是唯一的直接后支配者）
    for (int i = 0; i < n; i++) {
        auto* blk = mba_->getBlock(i);
        blk->pdom_parent = -1;
        // 严格 pdom = pdom(i) \ {i, virtualExit}
        // pdom_parent 是严格 pdom 中的唯一节点，它支配所有其他严格 pdom 节点
        // 简化：找严格 pdom 中 successors 数量最少的（通常就是直接 pdom_parent）
        int best = -1;
        for (int p : blk->pdom) {
            if (p == i || p >= n) continue;
            if (best < 0) { best = p; continue; }
            // 检查 p 是否支配 best（p 在 best 的 pdom 中）
            if (mba_->getBlock(best)->pdom.count(p)) {
                best = p; // p 更接近 i（p 支配 best）
            }
        }
        if (best >= 0) {
            blk->pdom_parent = best;
            mba_->getBlock(best)->pdom_children.push_back(i);
        }
    }
}

void CFGStructurer::computeControlDependence() {
    // 简化：在 tryIfThenElse 中通过 BFS 交集确定 merge point
}

// ──── Loop detection ────
// v4.8: Ghidra-style loop detection — dominator-based AND address-based work together.
// The dominator tree finds reducible loops (standard back edges where succ dominates src).
// The address-based fallback finds irreducible loops (goto-based, dom tree may miss them).
// Both methods run independently and results are merged, ensuring no loop is missed.
//
// Ghidra reference: BlockGraph::structureLoops() + CollapseStructure::labelLoops()
// A back edge (u → v) is an edge where v dominates u (standard definition).
// For irreducible loops, Ghidra uses edge classification (f_irreducible flag).
std::vector<NaturalLoop> CFGStructurer::detectLoops() {
    std::vector<NaturalLoop> result;
    int n = mba_->numBlocks();
    if (n == 0) return result;

    // Helper: check if 'ancestor' is an ancestor of 'node' in dominator tree.
    // Uses the idom_ array (computed by computeDominators) rather than
    // the block's dom_parent field (which is never set by CFGStructurer).
    // dom_parent is a legacy field from the old iterative dominator code
    // and is not maintained by the current computeDominators() implementation.
    auto isDomAncestor = [&](int ancestor, int node) -> bool {
        if (ancestor == node) return true;
        if (ancestor < 0 || node < 0 || ancestor >= (int)idom_.size() || node >= (int)idom_.size()) {
            fprintf(stderr, "[DBG_DOM] bounds: ancestor=%d node=%d idom_.size()=%zu\n", ancestor, node, idom_.size());
            return false;
        }
        if (idom_[node] == -2) {
            fprintf(stderr, "[DBG_DOM] not computed: node=%d\n", node);
            return false; // not computed
        }
        int cur = node;
        int maxDepth = (int)idom_.size() + 5;
        while (cur >= 0 && maxDepth-- > 0) {
            if (cur == ancestor) return true;
            if (cur >= (int)idom_.size() || idom_[cur] == cur) break; // safety
            cur = idom_[cur];
        }
        return false;
    };

    struct BackEdge {
        int from;
        int to;
        bool is_do_while;
        bool is_dominator_based; // true = found by dom tree, false = found by address
    };
    std::vector<BackEdge> backEdges;

    // ═══ Step 1: Dominator-based back edge detection (Ghidra standard) ═══
    // v47.0: GOTO back edges = while loop (pre-tested or while(true)).
    // Only CBRANCH back edges = do-while (post-tested).
    // Reference: Ghidra ruleBlockDoWhile requires the back edge block to
    // contain the loop condition (CBRANCH). A GOTO back edge means the
    // condition is at the header (while) or doesn't exist (while(true)).
    for (int i = 0; i < n; i++) {
        auto* blk = mba_->getBlock(i);
        for (int succ : blk->successors) {
            if (succ < 0 || succ >= n) continue;
            if (isDomAncestor(succ, i)) {
                bool isDoWhile = false;
                // GOTO back edge → while loop (NOT do-while)
                // if (blk->tail && blk->tail->opcode == mc::OP_GOTO &&
                //     blk->tail->target_block == succ) {
                //     isDoWhile = true;  // WRONG: GOTO is not a condition
                // }
                // CBRANCH back edge → may be do-while (condition at bottom)
                if (blk->tail && blk->tail->opcode == mc::OP_CBRANCH) {
                    for (int s : blk->successors) {
                        if (s == succ) { isDoWhile = true; break; }
                    }
                }
                backEdges.push_back({i, succ, isDoWhile, true});
            }
        }
    }

    // ═══ Step 2: Address-based fallback (ALWAYS runs, not just when dom fails) ═══
    // v4.8: This catches irreducible loops that the dominator tree misses.
    // We collect address-based back edges AND merge with dom-based ones.
    // Two blocks are a back edge if: src.addr > dst.addr AND dst-reachable-from-src
    for (int i = 0; i < n; i++) {
        auto* src = mba_->getBlock(i);
        for (int succ : src->successors) {
            if (succ < 0 || succ >= n) continue;
            auto* dst = mba_->getBlock(succ);
            if (src->start_addr > dst->start_addr) {
                // Check if this edge is already found by dominator
                bool alreadyFound = false;
                for (auto& be : backEdges) {
                    if (be.from == i && be.to == succ) { alreadyFound = true; break; }
                }
                if (alreadyFound) continue;

                // Verify this actually forms a cycle: can we reach src from dst?
                // v4.8: Remove depth limit — use visited set to prevent infinite loops
                std::set<int> visited;
                std::queue<int> q;
                q.push(succ);
                visited.insert(succ);
                bool reachesSrc = false;
                while (!q.empty()) {
                    int cur = q.front(); q.pop();
                    if (cur == i) { reachesSrc = true; break; }
                    auto* cb = mba_->getBlock(cur);
                    for (int next : cb->successors) {
                        if (next < 0 || next >= n) continue;
                        if (visited.count(next)) continue;
                        visited.insert(next);
                        q.push(next);
                    }
                }
                if (reachesSrc) {
                    bool isDoWhile = false;
                    // GOTO back edge → while loop (NOT do-while)
                    // if (src->tail && src->tail->opcode == mc::OP_GOTO &&
                    //     src->tail->target_block == succ) isDoWhile = true;
                    // CBRANCH back edge → may be do-while (condition at bottom)
                    if (src->tail && src->tail->opcode == mc::OP_CBRANCH) {
                        for (int s : src->successors) {
                            if (s == succ) { isDoWhile = true; break; }
                        }
                    }
                    backEdges.push_back({i, succ, isDoWhile, false});
                }
            }
        }
    }

    // ═══ Step 3: Build natural loops from back edges ═══
    // v4.8: Prefer dominator-based back edges (more reliable loop condition).
    // Multiple back edges to the same header are merged into one loop.
    for (auto& be : backEdges) {
        int header = be.to;
        bool exists = false;
        for (auto& l : result) {
            if (l.header == header) {
                exists = true;
                auto* beBlk = mba_->getBlock(be.from);
                auto* curBlk = mba_->getBlock(l.back_edge_from);
                bool beIsCbranch = (beBlk && beBlk->tail &&
                                    beBlk->tail->opcode == mc::OP_CBRANCH);
                bool curIsCbranch = (curBlk && curBlk->tail &&
                                     curBlk->tail->opcode == mc::OP_CBRANCH);

                // v4.8: Dominator-based CBRANCH always wins
                if (be.is_dominator_based && beIsCbranch && !curIsCbranch) {
                    l.back_edge_from = be.from; l.is_do_while = true;
                } else if (be.is_dominator_based && beIsCbranch && curIsCbranch && be.from > l.back_edge_from) {
                    l.back_edge_from = be.from; l.is_do_while = true;
                } else if (!be.is_dominator_based && !curIsCbranch && be.from > l.back_edge_from) {
                    l.back_edge_from = be.from;
                }
                break;
            }
        }
        if (exists) continue;

        NaturalLoop loop;
        loop.header = header;
        loop.back_edge_from = be.from;
        loop.is_do_while = be.is_do_while;

        // v5.7: Collect body using standard natural loop algorithm.
        // 对标 Ghidra blockaction.cc + r2 libr/anal/anal.c:
        // For back edge u→v, loop body = {v} ∪ {all nodes that can reach u
        // without going through v}. Computed via backward BFS from u to v.
        // PREVIOUS BUG: used address-range bounded BFS, which missed blocks
        // whose addresses fell outside [headerAddr, maxAddr].
        //
        // v7.0: Improved backward BFS using a precomputed reverse adjacency
        // map (block → predecessors). This is O(V+E) instead of the previous
        // O(V^2) that scanned all blocks to find predecessors on the fly.
        // Reference: Ghidra LoopBody uses BlockGraph reverse edges directly.
        std::set<int> body;
        body.insert(header);

        if (be.from != header) {
            // Backward BFS: find all nodes that can reach be.from
            // without going through header.
            // v7.0: Use the block's own `predecessors` list (already maintained
            // by the microcode emitter) instead of scanning all blocks.
            //
            // v18.0: Add dominance check — only include blocks that are
            // dominated by the loop header. This prevents pre-header code
            // (e.g., entry blocks, if-else branches that feed into the loop)
            // from being included in the loop body. The standard natural loop
            // definition requires all body blocks to be dominated by the header.
            // Without this, the backward BFS from the back-edge source can
            // traverse through GOTO blocks (like b7 = GOTO to b17) all the way
            // back to the entry block, bloating the loop body with non-loop code.
            fprintf(stderr, "[DBG_DOM] Backward BFS from be.from=%d to header=%d\n", be.from, header);
            std::queue<int> bq;
            std::set<int> visited;
            bq.push(be.from);
            visited.insert(be.from);
            body.insert(be.from);

            while (!bq.empty()) {
                int cur = bq.front(); bq.pop();
                // Iterate the predecessors list of cur directly.
                auto* curBlk = mba_->getBlock(cur);
                if (!curBlk) continue;
                for (int p : curBlk->predecessors) {
                    if (p < 0 || p >= n) continue;
                    if (p == header) continue;       // don't cross the header
                    if (body.count(p)) continue;     // already in body
                    if (visited.count(p)) continue;  // already queued
                    // v18.0: Only include blocks that are dominated by the
                    // loop header. This prevents pre-header code from being
                    // absorbed into the loop body.
                    if (!isDomAncestor(header, p)) {
                        fprintf(stderr, "[DBG_DOM] loop hdr=%d: p=%d NOT dominated by hdr (idom_[%d]=%d), skipping\n", header, p, p, p >= 0 && p < (int)idom_.size() ? idom_[p] : -999);
                        continue;
                    } else {
                        fprintf(stderr, "[DBG_DOM] loop hdr=%d: p=%d IS dominated by hdr (idom_[%d]=%d), adding\n", header, p, p, p >= 0 && p < (int)idom_.size() ? idom_[p] : -999);
                    }
                    visited.insert(p);
                    body.insert(p);
                    bq.push(p);
                }
            }
        }

        loop.body = body;
        fprintf(stderr, "[DBG_DOM] loop hdr=%d: body size=%zu\n", header, body.size());

        // v40.0: 移除循环体中包含 return 指令的块。这些块是函数退出点，
        // 不应该被当作循环体的一部分。如果它们被包含在循环体中，
        // C 树生成器会在循环体内部生成 return 语句，导致后续代码变成死代码。
        // 参考 IDA 的处理方式：return 块作为循环退出点，不影响循环结构。
        //
        // v40.1: 扩展检测范围 — 不仅要检查块是否以 OP_RET 结尾，还要检查
        // 块是否包含定义返回寄存器 (mreg 100) 的指令后跟 GOTO 到纯返回块。
        // 在 ARM 中，常见的返回模式是:
        //   MOV r0, #-1    (定义返回寄存器)
        //   B epilogue     (跳转到只有 RET 的 epilogue 块)
        // 这种模式不会被 v40.0 的 OP_RET 检查捕获，但 C 树生成器的
        // shouldFoldReturn 会将其折叠成 "return -1;" 语句。
        // 参考 Ghidra ActionReturn 的折叠逻辑。
        {
            auto isReturnOnlyBlock = [&](int blockId) -> bool {
                auto* b = mba_->getBlock(blockId);
                if (!b || !b->head) return false;
                return b->head->opcode == mc::OP_RET && b->head->next == nullptr;
            };

            auto isReturnBlock = [&](int blockId) -> bool {
                auto* b = mba_->getBlock(blockId);
                if (!b) return false;
                // Case 1: Block ends with OP_RET (already handled by v40.0)
                if (b->tail && b->tail->opcode == mc::OP_RET) return true;
                // Case 2: Block contains an instruction that defines the return
                // register (mreg 100) and the block's successor is a return-only
                // block. This handles the ARM pattern:
                //   MOV r0, #-1; B epilogue_block
                // where epilogue_block contains only RET.
                if (b->tail && b->tail->opcode == mc::OP_GOTO &&
                    b->successors.size() >= 1) {
                    // Check if the GOTO target is a return-only block
                    int gotoTarget = b->successors[0];
                    if (isReturnOnlyBlock(gotoTarget)) {
                        // Check if any instruction in this block defines the
                        // return register (mreg 100)
                        for (auto* insn = b->head; insn; insn = insn->next) {
                            if (insn->def_mreg == 100) return true;
                        }
                    }
                }
                // Case 3: Block has a single successor that is a return-only
                // block AND the last real instruction defines the return register
                // (no explicit GOTO, just fall-through)
                if (b->successors.size() == 1) {
                    int succ = b->successors[0];
                    if (isReturnOnlyBlock(succ)) {
                        for (auto* insn = b->head; insn; insn = insn->next) {
                            if (insn->def_mreg == 100) return true;
                        }
                    }
                }
                return false;
            };

            std::set<int> returnBlocks;
            for (int bid : loop.body) {
                if (isReturnBlock(bid)) {
                    returnBlocks.insert(bid);
                    fprintf(stderr, "[DBG_DOM] loop hdr=%d: removing return block %d from body\n", header, bid);
                }
            }
            for (int bid : returnBlocks) {
                loop.body.erase(bid);
            }
        }

        // v7.0: Improved exit-block selection (Ghidra LoopBody exit detection).
        // Collect all successors of body blocks that leave the body.
        // Then prefer exits reachable from the loop's tail (back_edge_from)
        // — these are the "natural" loop exits used by ruleBlockDoWhile /
        // ruleBlockWhileDo to pick the structured exit target. Remaining
        // exits (e.g. from mid-loop breaks) are kept in the set but the
        // primary_exit field records the preferred one.
        //
        // Reference: Ghidra CollapseStructure::labelLoops marks the header
        // exit (the successor of the back-edge block that leaves the loop)
        // as the primary loop exit.
        std::vector<int> preferredExits;
        std::set<int> allExits;
        // First, gather exits reachable directly from the back-edge block.
        auto* backSrcBlk = mba_->getBlock(be.from);
        if (backSrcBlk) {
            for (int next : backSrcBlk->successors) {
                if (next >= 0 && next < n && !loop.body.count(next)) {
                    preferredExits.push_back(next);
                    allExits.insert(next);
                }
            }
        }
        // Then gather all other exits from the body.
        for (int bid : loop.body) {
            auto* b = mba_->getBlock(bid);
            for (int next : b->successors) {
                if (next >= 0 && next < n && !loop.body.count(next)) {
                    allExits.insert(next);
                }
            }
        }
        loop.exits = allExits;
        // v7.0: Set the primary exit to the first tail-reachable exit.
        // This is the successor of the back-edge block — the natural
        // "fall-through after the loop" target.
        if (!preferredExits.empty()) {
            loop.primary_exit = preferredExits[0];
        } else if (!allExits.empty()) {
            loop.primary_exit = *allExits.begin();
        } else {
            loop.primary_exit = -1;
        }
        result.push_back(loop);
    }

    // ═══ Step 4: Post-validate is_do_while (v4.10) ═══
    // Ghidra reference: ruleBlockDoWhile checks that the loop header does NOT
    // have a CBRANCH that exits the loop. If the header has a CBRANCH exit,
    // it's a WHILE loop (header contains the condition), not a DO-WHILE.
    // This fixes the ARM64 nesting explosion where while loops are misclassified
    // as do-while, causing the body to be structured incorrectly.
    //
    // v5.9: Self-loops (back_edge_from == header) are ALWAYS do-while.
    // In a self-loop, the header IS the loop body. The CBRANCH at the end
    // of the header checks the condition and either loops back (continue)
    // or exits (fallthrough). This is the canonical do-while pattern.
    // The old code incorrectly downgraded self-loops to while, causing
    // condition inversion and missing do-while structure.
    for (auto& loop : result) {
        if (!loop.is_do_while) continue;
        // v5.9: Self-loops are always do-while — skip downgrade
        if (loop.back_edge_from == loop.header) continue;
        auto* hdr = mba_->getBlock(loop.header);
        if (hdr && hdr->tail && hdr->tail->opcode == mc::OP_CBRANCH) {
            bool hdrExitsLoop = false;
            for (int hs : hdr->successors) {
                if (hs < 0 || hs >= n) continue;
                if (!loop.body.count(hs)) {
                    hdrExitsLoop = true;
                    break;
                }
            }
            if (hdrExitsLoop) {
                // v6.0: Check if the back edge is conditional (CBRANCH targeting header).
                // If so, the back edge block has the loop condition, and the header's
                // exit CBRANCH is a mid-loop break — keep as do-while.
                // Ghidra reference: ruleBlockDoWhile distinguishes break conditions
                // from loop conditions by checking if the back edge is conditional.
                auto* backBlk = mba_->getBlock(loop.back_edge_from);
                if (backBlk && backBlk->tail &&
                    backBlk->tail->opcode == mc::OP_CBRANCH &&
                    backBlk->tail->target_block == loop.header) {
                    continue;  // Back edge is conditional — do-while with break
                }
                loop.is_do_while = false;
            }
        }
    }

    // ═══ Step 5: Compute loop nesting hierarchy (v7.0) ═══
    // Reference: Ghidra LoopBody / CollapseStructure::labelLoops builds a
    // nesting hierarchy so inner loops are structured before outer loops.
    //
    // Loop L1 contains loop L2 (L2 is nested inside L1) iff:
    //   - L2.header ∈ L1.body, AND
    //   - L2.body ⊆ L1.body
    // The parent of L2 is the SMALLEST such L1 (the innermost container).
    // The nesting_depth of L2 = parent.nesting_depth + 1.
    //
    // This is used by trySequence to process innermost loops first and by
    // collectBranch to avoid crossing into nested loop bodies.
    for (auto& loop : result) {
        loop.nesting_depth = 0;
        loop.parent_loop_header = -1;
    }
    for (size_t i = 0; i < result.size(); i++) {
        int bestParent = -1;
        int bestParentSize = -1;
        for (size_t j = 0; j < result.size(); j++) {
            if (i == j) continue;
            // Does loop j contain loop i?
            if (!result[j].body.count(result[i].header)) continue;
            // Check full containment of i's body in j's body.
            bool contained = true;
            for (int b : result[i].body) {
                if (!result[j].body.count(b)) { contained = false; break; }
            }
            if (!contained) continue;
            // j contains i. Pick the smallest container (innermost parent).
            int jSize = (int)result[j].body.size();
            if (bestParent < 0 || jSize < bestParentSize) {
                bestParent = (int)j;
                bestParentSize = jSize;
            }
        }
        if (bestParent >= 0) {
            result[i].parent_loop_header = result[bestParent].header;
        }
    }
    // Compute depth via BFS from outermost loops (parent == -1).
    // Iterate to fixpoint in case of deeply nested chains.
    bool depthChanged = true;
    int depthGuard = (int)result.size() + 5;
    while (depthChanged && depthGuard-- > 0) {
        depthChanged = false;
        for (size_t i = 0; i < result.size(); i++) {
            if (result[i].parent_loop_header < 0) {
                if (result[i].nesting_depth != 0) {
                    result[i].nesting_depth = 0;
                    depthChanged = true;
                }
                continue;
            }
            // Find parent loop index.
            int parentDepth = -1;
            for (size_t j = 0; j < result.size(); j++) {
                if (result[j].header == result[i].parent_loop_header) {
                    parentDepth = result[j].nesting_depth;
                    break;
                }
            }
            if (parentDepth >= 0) {
                int newDepth = parentDepth + 1;
                if (result[i].nesting_depth != newDepth) {
                    result[i].nesting_depth = newDepth;
                    depthChanged = true;
                }
            }
        }
    }

    return result;
}

// v4.12: Global time & bailout check for all structuring functions.
// Once we hit the time limit, ALL subsequent calls bail out immediately
// with flat blocks. This prevents the recursive "bailout cascade" where
// each of N recursive trySequence calls takes 8s to time out individually.
// Reference: Ghidra blockaction.cc maxIterations (100) per rule engine run.
static thread_local auto g_struct_start = std::chrono::steady_clock::now();
static bool g_struct_start_set = false;
static bool g_struct_bailout = false;  // v4.12: once set, never recover
static const int MAX_STRUCT_SECONDS = 20;  // v11.2: increased for large functions (e.g., 6368-byte sub_2a02a0)

static bool isStructTimedOut() {
    if (g_struct_bailout) return true;  // v4.12: silent fast path
    if (!g_struct_start_set) {
        g_struct_start = std::chrono::steady_clock::now();
        g_struct_start_set = true;
    }
    auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(
        std::chrono::steady_clock::now() - g_struct_start).count();
    if (elapsed > MAX_STRUCT_SECONDS) {
        g_struct_bailout = true;
        fprintf(stderr, "  [STRUCT] WARNING: time bailout at %llds\n",
                (long long)elapsed);
        return true;
    }
    return false;
}

// ──── Main entry ────
std::unique_ptr<Region> CFGStructurer::structure(mc::MicrocodeBlockArray& mba) {
    mba_ = &mba;
    processed_loop_headers_.clear();
    loops_.clear();
    loop_exit_blocks_.clear();

    // v4.12: Reset global struct timer and bailout flag for this function
    g_struct_start = std::chrono::steady_clock::now();
    g_struct_start_set = true;
    g_struct_bailout = false;

    int n = mba_->numBlocks();
    if (n == 0) return nullptr;

    // v5.5: Ghidra-style — NEVER flatten. The recursive CFGStructurer handles
    // all function sizes. FixpointStructurer is only used as a fallback when
    // recursive structuring fails (returns null or leaves blocks unprocessed).
    // Reference: Ghidra CollapseStructure::collapseAll has no block-count limit.

    // v4.12: Pre-compute dominator trees for O(1) merge point finding
    // NOTE: This MUST run before detectLoops() because the loop body
    // collection uses the dominator tree (isDomAncestor) to filter out
    // pre-header blocks. Without this, the backward BFS from the back
    // edge source can traverse through GOTO blocks back to the entry,
    // bloating the loop body with non-loop code.
    computeDominators(mba_);
    // Debug: verify idom_ values after computation
    {
        int computedCount = 0;
        for (int i = 0; i < (int)idom_.size(); i++) {
            if (idom_[i] != -2) computedCount++;
        }
        fprintf(stderr, "[DBG_DOM] after computeDominators: %d/%zu computed. idom_[0]=%d idom_[1]=%d idom_[2]=%d idom_[6]=%d idom_[7]=%d idom_[17]=%d idom_[57]=%d\n",
                computedCount, idom_.size(), idom_[0], idom_[1], idom_[2], idom_[6], idom_[7], idom_[17], idom_[57]);
    }

    // v4.7: Skip computePostDominators — it's O(n^2) and results are never used
    // in the structuring logic. This is a significant performance win.
    loops_ = detectLoops();

    // v4.11: Pre-compute loop exit blocks for fast if-break detection
    loop_exit_blocks_.clear();
    for (auto& loop : loops_) {
        for (int exit : loop.exits) {
            loop_exit_blocks_.insert(exit);
        }
    }

    std::set<int> remaining;
    for (int i = 0; i < n; i++) remaining.insert(i);

    // v9.8: Filter unreachable blocks before structuring.
    // Blocks after noreturn calls (abort, exit, etc.) are garbage
    // (literal pool data, padding) and must not be included in the region.
    {
        std::set<int> reachable;
        std::queue<int> q;
        q.push(0);  // entry block
        reachable.insert(0);
        while (!q.empty()) {
            int cur = q.front(); q.pop();
            auto* blk = mba_->getBlock(cur);
            if (!blk) continue;
            // Stop at noreturn calls — code after is unreachable
            if (blk->tail && blk->tail->opcode == mc::OP_CALL &&
                blk->tail->call_info && !blk->tail->call_info->has_return) {
                continue;
            }
            if (blk->tail && blk->tail->opcode == mc::OP_RET) continue;
            for (int succ : blk->successors) {
                if (succ >= 0 && succ < n && !reachable.count(succ)) {
                    reachable.insert(succ);
                    q.push(succ);
                }
            }
        }
        // Remove unreachable blocks from remaining
        for (auto it = remaining.begin(); it != remaining.end(); ) {
            if (!reachable.count(*it)) {
                it = remaining.erase(it);
            } else {
                ++it;
            }
        }
    }

    // v5.8: Always use FixpointStructurer for functions with > 40 blocks
    // to avoid deeply nested if-break chains from the recursive structurer.
    if (n > 40) {
        fprintf(stderr, "[DBG] CFGStructurer: %d blocks > 40, delegating to FixpointStructurer\n", n);
        FixpointStructurer fpStructurer;
        auto fpRegion = fpStructurer.structure(mba);
        if (fpRegion) {
            fprintf(stderr, "[DBG] CFGStructurer: FixpointStructurer returned tree type=%d\n", fpRegion->type);
            return fpRegion;
        }
        fprintf(stderr, "[DBG] CFGStructurer: FixpointStructurer returned null, continuing with recursive\n");
    }

    auto region = trySequence(remaining);

    // v5.5: Ghidra-style — if recursive structuring failed or left blocks
    // unprocessed, delegate to FixpointStructurer instead of returning a
    // partial/flattened result. Ghidra NEVER flattens.
    if (!region || !remaining.empty()) {
        FixpointStructurer fpStructurer;
        auto fpRegion = fpStructurer.structure(mba);
        if (fpRegion) {
            return fpRegion;
        }
        // If FixpointStructurer also fails, fall through with whatever we have
    }

    if (region) {
        eliminateGotos(region.get());
    }

    return region;
}

// ──── BFS 交集法：找到从两个分支出发最先共同到达的块 ────
// 返回 merge point 的 block_id，如果找不到返回 -1
// v4.10: Cache for findMergePoint results — key is (succTrue, succFalse) pair
static std::map<std::pair<int,int>, int> mergePointCache_;

static int findMergePoint(mc::MicrocodeBlockArray* mba, int succTrue, int succFalse) {
    int n = mba->numBlocks();
    
    // v4.10: Cache lookup — CFG is immutable during structuring
    auto key = std::make_pair(succTrue, succFalse);
    auto it = mergePointCache_.find(key);
    if (it != mergePointCache_.end()) return it->second;

    // BFS 从 succTrue 出发，记录每个块的最短距离
    std::map<int, int> distTrue;
    std::queue<int> q;
    q.push(succTrue);
    distTrue[succTrue] = 0;
    while (!q.empty()) {
        int cur = q.front(); q.pop();
        int d = distTrue[cur];
        auto* blk = mba->getBlock(cur);
        for (int next : blk->successors) {
            if (next < 0 || next >= n) continue;
            if (distTrue.count(next)) continue;
            distTrue[next] = d + 1;
            q.push(next);
        }
    }

    // BFS 从 succFalse 出发，找到第一个也在 distTrue 中的块
    std::map<int, int> distFalse;
    q.push(succFalse);
    distFalse[succFalse] = 0;
    int bestMerge = -1;
    int bestTotal = 999999;
    while (!q.empty()) {
        int cur = q.front(); q.pop();
        int d = distFalse[cur];
        if (distTrue.count(cur)) {
            int total = distTrue[cur] + d;
            if (total < bestTotal) {
                bestTotal = total;
                bestMerge = cur;
            }
        }
        auto* blk = mba->getBlock(cur);
        for (int next : blk->successors) {
            if (next < 0 || next >= n) continue;
            if (distFalse.count(next)) continue;
            distFalse[next] = d + 1;
            q.push(next);
        }
    }

    mergePointCache_[key] = bestMerge;
    return bestMerge;
}

// ════════════════════════════════════════════════════════════════════
// v4.12: Dominator tree computation for O(1) merge point finding.
// Reference: Ghidra flow.cc dominatorTree() — uses Cooper-Harvey-Kennedy
// algorithm for fast dominator computation.
// ════════════════════════════════════════════════════════════════════
void CFGStructurer::computeDominators(mc::MicrocodeBlockArray* mba) {
    int n = mba->numBlocks();
    fprintf(stderr, "[DBG_DOM] computeDominators: n=%d\n", n);
    idom_.assign(n, -2);
    ipdom_.assign(n, -2);
    if (n == 0) return;

    // ── Forward dominators (IDOM) ──
    // Entry block (0) dominates itself
    idom_[0] = -1; // -1 = no dominator (entry)
    bool changed = true;
    std::vector<int> rpo; // reverse post-order
    {
        std::set<int> visited;
        std::function<void(int)> dfs = [&](int u) {
            visited.insert(u);
            rpo.push_back(u);
            auto* blk = mba->getBlock(u);
            if (blk) {
                for (int s : blk->successors) {
                    if (s >= 0 && s < n && !visited.count(s)) dfs(s);
                }
            }
        };
        dfs(0);
    }
    int iterCount = 0;
    while (changed) {
        changed = false;
        iterCount++;
        for (int b : rpo) {
            if (b == 0) continue;
            int newIdom = -2;
            auto* blk = mba->getBlock(b);
            if (blk) {
                for (int p : blk->predecessors) {
                    if (p < 0 || p >= n) continue;
                    if (idom_[p] == -2) continue; // not yet computed
                    if (newIdom == -2) { newIdom = p; continue; }
                    // Intersect: find the nearest common ancestor in the
                    // dominator tree by walking up BOTH chains.
                    // Standard algorithm: collect all ancestors of a, then
                    // walk up c's chain and find the first common ancestor.
                    // The old algorithm only walked up a's chain, which failed
                    // when c was not an ancestor of a (returned -1 = entry).
                    {
                        int a = newIdom, c = p;
                        std::set<int> ancestors;
                        while (a >= 0) {
                            ancestors.insert(a);
                            if (a == c) break;  // fast path: c is ancestor of a
                            a = idom_[a];
                        }
                        while (c >= 0) {
                            if (ancestors.count(c)) {
                                newIdom = c;
                                break;
                            }
                            c = idom_[c];
                        }
                        if (c < 0) newIdom = -1;  // common ancestor is entry
                    }
                }
            }
            if (newIdom >= -1 && newIdom != idom_[b]) {
                idom_[b] = newIdom;
                changed = true;
            }
        }
    }
    fprintf(stderr, "[DBG_DOM] fixpoint converged in %d iterations\n", iterCount);

    // v20: Fix any remaining -2 nodes (unreachable from entry in predecessor graph)
    for (int i = 0; i < n; i++) {
        if (idom_[i] == -2) {
            idom_[i] = -1;
            fprintf(stderr, "[DBG_DOM] Fixed unreachable node %d: idom_=-1 (entry)\n", i);
        }
    }

    // ── Post-dominators (IPDOM) ──
    // Build reverse CFG, find exit node (last block with no successors)
    int exitNode = -1;
    for (int i = n-1; i >= 0; i--) {
        auto* blk = mba->getBlock(i);
        if (blk && blk->successors.empty()) { exitNode = i; break; }
    }
    if (exitNode < 0) exitNode = n - 1; // fallback

    ipdom_[exitNode] = -1; // -1 = no post-dominator (exit)
    changed = true;
    // Reverse post-order on reverse CFG
    std::vector<int> rpoRev;
    {
        std::set<int> visited;
        std::function<void(int)> dfsRev = [&](int u) {
            visited.insert(u);
            rpoRev.push_back(u);
            auto* blk = mba->getBlock(u);
            if (blk) {
                for (int p : blk->predecessors) {
                    if (p >= 0 && p < n && !visited.count(p)) dfsRev(p);
                }
            }
        };
        dfsRev(exitNode);
    }
    int iter = 0;
    while (changed && iter++ < 50) {
        changed = false;
        for (int b : rpoRev) {
            if (b == exitNode) continue;
            int newIpdom = -2;
            auto* blk = mba->getBlock(b);
            if (blk) {
                for (int s : blk->successors) {
                    if (s < 0 || s >= n) continue;
                    if (ipdom_[s] == -2) continue;
                    if (newIpdom == -2) { newIpdom = s; continue; }
                    // Intersect in post-dominator tree (walk up BOTH chains)
                    {
                        int a = newIpdom, c = s;
                        std::set<int> ancestors;
                        while (a >= 0) {
                            ancestors.insert(a);
                            if (a == c) break;
                            a = ipdom_[a];
                        }
                        while (c >= 0) {
                            if (ancestors.count(c)) {
                                newIpdom = c;
                                break;
                            }
                            c = ipdom_[c];
                        }
                        if (c < 0) newIpdom = -1;
                    }
                }
            }
            if (newIpdom >= -1 && newIpdom != ipdom_[b]) {
                ipdom_[b] = newIpdom;
                changed = true;
            }
        }
    }

    // v20: Fix any remaining -2 post-dominator nodes
    for (int i = 0; i < n; i++) {
        if (ipdom_[i] == -2) {
            ipdom_[i] = -1;
        }
    }
}

int CFGStructurer::findMergePointFast(int a, int b) {
    int n = (int)ipdom_.size();
    if (a < 0 || a >= n || b < 0 || b >= n) return -1;
    if (a == b) return a;
    // v7.0: Walk up the post-dominator tree from both nodes, find the
    // nearest common post-dominator (the merge point). This is the
    // immediate common post-dominator of the two branch successors.
    // Reference: Ghidra uses the post-dominator tree directly to find
    // the merge point of a conditional (ipdom of the branch block).
    //
    // We collect all post-dominators of `a` (transitive ancestors in the
    // pdom tree), then walk up from `b` until we hit one. The first hit
    // is the nearest common post-dominator = the merge point.
    std::set<int> aAncestors;
    int cur = a;
    int guard = n + 5;  // v7.0: cycle guard
    while (cur >= 0 && cur < n && guard-- > 0) {
        aAncestors.insert(cur);
        int next = ipdom_[cur];
        if (next == cur) break;  // self-loop guard
        cur = next;
    }
    cur = b;
    guard = n + 5;
    while (cur >= 0 && cur < n && guard-- > 0) {
        if (aAncestors.count(cur)) {
            // v7.0: Validate that `cur` actually post-dominates both a and b.
            // (It does by construction: it's an ancestor of a in the pdom
            // tree and we reached it walking up from b.) Return it.
            return cur;
        }
        int next = ipdom_[cur];
        if (next == cur) break;  // self-loop guard
        cur = next;
    }
    return -1;
}

// ──── trySequence: 按 CFG 前序遍历，构建结构化区域 ────
// v4.1: 简化版，每次只处理 remaining 中的第一个块
// v4.10: Add fixpoint protection — total iteration limit across all recursive calls
static const int MAX_STRUCT_DEPTH = 10;   // v5.7: balanced (was 20, originally 5)
static const int MAX_TOTAL_ITERATIONS = 800;  // v5.7: balanced (was 2000, originally 300)

std::unique_ptr<Region> CFGStructurer::trySequence(std::set<int>& remaining) {
    if (remaining.empty()) return nullptr;

    int n = mba_->numBlocks();

    // v5.5: Ghidra-style — NEVER flatten. All flatten bailouts removed.
    // Large functions are delegated to FixpointStructurer by structure().
    // Here we only have safety nets that return nullptr (caller falls back).

    // v4.12: Global time check — return nullptr so caller can fall back
    if (isStructTimedOut()) {
        return nullptr;
    }

    // v4.10: Global fixpoint protection — return nullptr instead of flattening
    static thread_local int totalIterations = 0;
    struct IterGuard { IterGuard() { totalIterations++; } ~IterGuard() { totalIterations--; } };

    static int depth = 0;
    struct DepthGuard { DepthGuard() { depth++; } ~DepthGuard() { depth--; } };
    DepthGuard guard;
    
    if (depth > MAX_STRUCT_DEPTH || totalIterations > MAX_TOTAL_ITERATIONS) {
        return nullptr;
    }

    IterGuard iterGuard;
    auto seq = std::make_unique<Region>(REGION_SEQUENCE);

    // v5.7: Control-flow-following block selection (对标 Ghidra CollapseStructure)
    // Instead of always picking *remaining.begin() (numerical order), we follow
    // the control flow from the entry block. This ensures loops are placed
    // BEFORE returns in the sequence, not after.
    // PREVIOUS BUG: blocks were picked in numerical order, so if a RETURN
    // block had a lower number than loop headers, loops ended up after the
    // return and got removed by eliminateUnreachableCode.
    int nextBlock = -1;  // -1 = no preference, use *remaining.begin()

    int iterCount = 0;
    while (!remaining.empty()) {
        iterCount++;
        if (iterCount > 200 || isStructTimedOut()) {
            break;
        }

        // v5.7: Pick next block — follow control flow if possible
        int cur;
        if (nextBlock >= 0 && remaining.count(nextBlock)) {
            cur = nextBlock;
        } else {
            cur = *remaining.begin();
        }
        nextBlock = -1;  // reset for next iteration
        int beforeCount = (int)remaining.size();

        // ── 检测：是否为 loop header？ ──
        // v5.9: Skip already-processed loop headers. When a self-loop's
        // header is included in the body, trySequence would re-detect it
        // as a loop header, causing infinite recursion (cut by depth guard,
        // resulting in empty body). Check processed_loop_headers_ first.
        const NaturalLoop* loop = findLoopByHeader(cur);
        if (loop && !processed_loop_headers_.count(cur)) {
            processed_loop_headers_.insert(cur);
            if (loop->is_do_while) {
                auto r = tryDoWhileLoop(*loop, remaining);
                if (r) seq->children.push_back(std::move(r));
            } else {
                auto r = tryWhileLoop(*loop, remaining);
                if (r) seq->children.push_back(std::move(r));
            }
            int afterCount = (int)remaining.size();
            if (beforeCount <= afterCount && beforeCount > 1) {
                remaining.erase(cur);
            }
            // v5.7: After a loop, follow the loop exit block
            // v7.0: Prefer the primary (tail-reachable) exit when available.
            if (!loop->exits.empty()) {
                int exitBlk = (loop->primary_exit >= 0) ? loop->primary_exit
                                                        : *loop->exits.begin();
                if (exitBlk >= 0 && exitBlk < n && remaining.count(exitBlk)) {
                    nextBlock = exitBlk;
                }
            }
            continue;
        }

        // ── 检测：是否为 CBRANCH 块？ ──
        auto* blk = mba_->getBlock(cur);
        mc::MicroInsn* tail = blk->tail;
        if (tail && tail->opcode == mc::OP_CBRANCH && (int)blk->successors.size() >= 2) {
            if (isStructTimedOut()) goto fallback_block;
            auto ifRegion = tryIfThenElse(cur, remaining);
            if (ifRegion) {
                seq->children.push_back(std::move(ifRegion));
                int afterCount = (int)remaining.size();
                if (beforeCount <= afterCount && beforeCount > 1) {
                    remaining.erase(cur);
                }
                // v5.7: After if-then-else, follow the merge point
                // tryIfThenElse consumes the then/else branches and the merge
                // point. The next block should be the merge point's successor.
                // For now, fall through to *remaining.begin() — the merge point
                // itself was consumed, so the next remaining block should be
                // the one after it.
                continue;
            }
        }

        fallback_block:
        blk = mba_->getBlock(cur);
        if (!blk) {
            remaining.erase(cur);
            continue;
        }

        // v24.2: 对标 Ghidra CollapseStructure::ActionBlockStructure
        // 当遇到 RETURN 块时，检查是否有其他路径仍然在 remaining 中。
        // 如果 RETURN 块的前驱块有其他后继仍在 remaining 中，说明
        // 这个 RETURN 块是条件分支的一部分，不应该作为独立的基本块
        // 添加到序列中。应该保留在 remaining 中，在后续的 if-else
        // 结构化时被处理。
        // Ghidra reference: CollapseStructure 确保 RETURN 块永远不会
        // 在还有未处理路径时作为独立基本块出现在序列顶层。
        if (blk->tail && blk->tail->opcode == mc::OP_RET) {
            // Check if any predecessor of this block has other successors
            // still in remaining (i.e., there are alternative paths).
            bool hasOtherPaths = false;
            for (int pred : blk->predecessors) {
                if (pred < 0 || pred >= n) continue;
                auto* predBlk = mba_->getBlock(pred);
                if (predBlk) {
                    for (int succ : predBlk->successors) {
                        if (succ >= 0 && succ < n && succ != cur && remaining.count(succ)) {
                            hasOtherPaths = true;
                            break;
                        }
                    }
                }
                if (hasOtherPaths) break;
            }
            // Also check if the RETURN block's predecessors have CBRANCH
            // instructions — this means the RETURN is a branch target of a
            // conditional, and should be part of an if-else structure.
            bool hasCbranchPredecessor = false;
            for (int pred : blk->predecessors) {
                if (pred < 0 || pred >= n) continue;
                auto* predBlk = mba_->getBlock(pred);
                if (predBlk && predBlk->tail && predBlk->tail->opcode == mc::OP_CBRANCH) {
                    hasCbranchPredecessor = true;
                    // Check if the other branch of this CBRANCH is still in remaining
                    for (int succ : predBlk->successors) {
                        if (succ >= 0 && succ < n && succ != cur && remaining.count(succ)) {
                            hasOtherPaths = true;
                            break;
                        }
                    }
                    break;
                }
            }
            // If there are other paths, keep this RETURN block in remaining
            // so it will be structured as part of an if-else later.
            if (hasOtherPaths) {
                // Don't erase from remaining — let it be processed later
                // as part of the if-else structure.
                continue;
            }
            // v24.2: If the RETURN block has a CBRANCH predecessor but no other
            // paths in remaining, the other branch was already processed.
            // This means the RETURN block should be emitted now. Fall through
            // to add it as a basic block.
            if (hasCbranchPredecessor) {
                // The other branch was already processed — emit this RETURN
                // as a basic block. The CPrinter will handle the ordering.
                // Fall through to add as basic block.
            }
        }

        auto bb = std::make_unique<Region>(REGION_BASIC_BLOCK);
        bb->block_id = cur;
        bb->start_addr = blk->start_addr;
        remaining.erase(cur);
        seq->children.push_back(std::move(bb));

        // v5.7: Follow the control flow — if this block has a single successor,
        // make it the next block to process.
        if ((int)blk->successors.size() == 1) {
            int succ = blk->successors[0];
            if (succ >= 0 && succ < n && remaining.count(succ)) {
                nextBlock = succ;
            }
        } else if ((int)blk->successors.size() == 0) {
            // No successors (e.g., RETURN or unreachable) — don't set nextBlock.
            // Fall through to *remaining.begin() on next iteration.
        }
    }

    if (seq->children.size() == 1) {
        return std::move(seq->children[0]);
    }
    return seq;
}

// ──── tryIfThenElse: 构建 if-then 或 if-then-else ────
// v4.1: 使用 BFS 交集法确定 merge point
// v4.3: 添加条件验证 — 过滤常量比较、虚假分支
// v4.4: 完善 9 步检查 — 拒绝所有无效 CBRANCH
std::unique_ptr<Region> CFGStructurer::tryIfThenElse(int branch_block, std::set<int>& remaining) {
    int n = mba_->numBlocks();
    auto* blk = mba_->getBlock(branch_block);
    if (!blk) return nullptr;

    // v4.12: Quick bailout — if we're already timed out, don't even try BFS
    if (isStructTimedOut()) return nullptr;

    // ── v4.4: 9 步条件验证 ──
    // 1. 块必须在 remaining 中
    if (!remaining.count(branch_block)) return nullptr;

    // 2. tail 必须是 CBRANCH
    if (!blk->tail || blk->tail->opcode != mc::OP_CBRANCH) return nullptr;

    // 3. 至少有两个后继
    if ((int)blk->successors.size() < 2) return nullptr;

    int succTrue = blk->successors[0];
    int succFalse = blk->successors[1];

    // 4. 两个后继必须有效且在范围内
    if (succTrue < 0 || succTrue >= n) return nullptr;
    if (succFalse < 0 || succFalse >= n) return nullptr;

    // 5. 两个分支目标不能相同
    if (succTrue == succFalse) return nullptr;

    // 6. 分支目标不能是自身（无限循环，由 loop 处理）
    if (succTrue == branch_block || succFalse == branch_block) return nullptr;

    // 7. 条件不能是常量比较（if (0x... == 0) 虚假分支）
    if (blk->tail->l.isImm() && blk->tail->r.isImm()) {
        // v5.8: Silent skip — constant comparison is always resolved at compile time
        return nullptr;
    }

    // 8. 至少一个操作数是有效寄存器（mreg >= 0）
    bool lIsValidReg = blk->tail->l.isReg() && blk->tail->l.mreg >= 0;
    bool rIsValidReg = blk->tail->r.isReg() && blk->tail->r.mreg >= 0;
    bool lIsValidImm = blk->tail->l.isImm();
    bool rIsValidImm = blk->tail->r.isImm();
    if (!lIsValidReg && !lIsValidImm) return nullptr;
    if (!rIsValidReg && !rIsValidImm) return nullptr;
    // 不能两个都是无效寄存器（mreg < 0）
    if (blk->tail->l.isReg() && blk->tail->l.mreg < 0 &&
        blk->tail->r.isReg() && blk->tail->r.mreg < 0)
        return nullptr;

    // 9. 条件码必须有效
    if (blk->tail->cond == mc::CC_NONE) return nullptr;

    remaining.erase(branch_block);

    // v4.12: O(1) merge point using pre-computed post-dominator tree.
    // Falls back to BFS findMergePoint if the fast method returns -1.
    int merge = findMergePointFast(succTrue, succFalse);
    if (merge < 0) merge = findMergePoint(mba_, succTrue, succFalse);

    // v24.2: 对标 Ghidra CollapseStructure::ActionBlockStructure
    // 当 merge point 找不到时，检查是否一个分支以 RETURN 结束。
    // 如果其中一个后继是 RETURN 块，这是 if-else 的合法模式：
    //   if (cond) { ...; return; } else { ... }
    // 此时不需要 merge point，因为 RETURN 分支独立结束。
    // Ghidra 的 CollapseStructure 在 ActionBlockStructure 中
    // 处理这种模式：当一个分支的终端是 RETURN 时，仍然构建
    // if-else 结构，而不需要两个分支汇聚于同一个 merge point。
    if (merge < 0) {
        // Check if one successor is a RETURN block
        auto* blkTrue = (succTrue >= 0 && succTrue < n) ? mba_->getBlock(succTrue) : nullptr;
        auto* blkFalse = (succFalse >= 0 && succFalse < n) ? mba_->getBlock(succFalse) : nullptr;
        bool trueIsReturn = blkTrue && blkTrue->tail && blkTrue->tail->opcode == mc::OP_RET;
        bool falseIsReturn = blkFalse && blkFalse->tail && blkFalse->tail->opcode == mc::OP_RET;
        // Also check if the successor block is a terminal with no successors (RETURN/abort)
        bool trueIsTerminal = blkTrue && blkTrue->successors.empty();
        bool falseIsTerminal = blkFalse && blkFalse->successors.empty();
        // If one branch is a RETURN/terminal, the other branch's blocks converge
        // at the RETURN block's predecessors (or end independently).
        // Use -1 as stop (collect until dead end) for the non-terminal branch.
        if ((trueIsReturn || trueIsTerminal) && !falseIsReturn && !falseIsTerminal) {
            // True branch is RETURN — structure as if-then-else with RETURN as then
            merge = -1;  // No merge point, but we still structure
        } else if ((falseIsReturn || falseIsTerminal) && !trueIsReturn && !trueIsTerminal) {
            // False branch is RETURN — structure as if-then-else with RETURN as else
            merge = -1;
        }
    }

    // v7.0: collectBranch — collect blocks from `start` along the CFG up to
    // (excluding) `stop`, bounded by `remaining`. Defined here so the
    // short-circuit (&& / ||) handlers below can reuse it.
    // Ghidra reference: CollapseStructure::collectBlockBoundary() uses the
    // other branch's entry as a boundary, preventing block overlap.
    auto collectBranch = [&](int start, int stop, std::set<int>& branchBlocks, int otherSucc) {
        if (start < 0 || start >= n) return;
        std::queue<int> q;
        std::set<int> visited;
        q.push(start);
        visited.insert(start);
        int maxDepth = 100;  // v4.12: tightened from 500 to prevent BFS explosion
        while (!q.empty() && maxDepth-- > 0) {
            // v4.12: Bail out mid-BFS if timed out
            if (isStructTimedOut()) break;
            int cur = q.front(); q.pop();
            if (cur == stop) continue;
            if (cur == branch_block) continue;
            // v5.10: Don't collect the other branch's entry point
            if (cur == otherSucc && cur != start) continue;
            // v4.4: For loop headers, collect the block itself but don't follow
            // successors that go into the loop body. This prevents nested loops
            // from being flattened into the parent if-then-else branch.
            const NaturalLoop* curLoop = findLoopByHeader(cur);
            if (curLoop && cur != start) {
                // This is a nested loop header (not the branch entry).
                // Collect it but only follow EXIT successors, not loop body.
                branchBlocks.insert(cur);
                auto* cb = mba_->getBlock(cur);
                for (int next : cb->successors) {
                    if (next < 0 || next >= n) continue;
                    if (visited.count(next)) continue;
                    if (next == stop) continue;
                    if (next == otherSucc) continue;  // v5.10: don't cross to other branch
                    if (curLoop->body.count(next)) continue; // skip loop body
                    visited.insert(next);
                    q.push(next);
                }
                continue;
            }
            if (curLoop && cur == start) {
                // Branch entry IS a loop header. Collect it, follow only exits.
                branchBlocks.insert(cur);
                auto* cb = mba_->getBlock(cur);
                for (int next : cb->successors) {
                    if (next < 0 || next >= n) continue;
                    if (visited.count(next)) continue;
                    if (next == stop) continue;
                    if (next == otherSucc) continue;  // v5.10: don't cross to other branch
                    if (curLoop->body.count(next)) continue; // skip loop body
                    visited.insert(next);
                    q.push(next);
                }
                continue;
            }
            branchBlocks.insert(cur);
            auto* cb = mba_->getBlock(cur);
            for (int next : cb->successors) {
                if (next < 0 || next >= n) continue;
                if (visited.count(next)) continue;
                if (next == stop) continue;
                if (next == otherSucc) continue;  // v5.10: don't cross to other branch
                // v4.4: Don't cross into nested loop bodies via loop header
                if (findLoopByHeader(next) && next != start) continue;
                visited.insert(next);
                q.push(next);
            }
        }
    };

    // v7.0: Short-circuit condition recovery (Ghidra ruleBlockOr).
    // Detect && and || patterns where a branch target is itself a CBRANCH
    // that shares the same merge point.
    //
    // && pattern:  if (A) { if (B) goto merge; else goto tail; } goto merge;
    //   branch_block → succTrue (CBRANCH B) → merge
    //                → succFalse (GOTO merge)
    //   i.e. succTrue is a CBRANCH whose merge == this merge, and succFalse
    //   jumps directly to merge. This is "if (A && B)".
    //
    // || pattern:  if (A) goto merge; if (B) goto merge; ...
    //   branch_block → succTrue (GOTO merge)   [A true → skip B]
    //                → succFalse (CBRANCH B) → merge
    //   i.e. succFalse is a CBRANCH whose merge == this merge, and succTrue
    //   jumps directly to merge. This is "if (A || B)".
    //
    // We detect these BEFORE collectBranch so we can structure them as a
    // single compound condition instead of nested if-then-else.
    // Reference: Ghidra CollapseStructure::ruleBlockOr detects the diamond
    // pattern A→B, A→C, B→D, C→D and merges into a compound condition.
    auto isDirectJumpTo = [&](int from, int target) -> bool {
        if (from < 0 || from >= n) return false;
        auto* fb = mba_->getBlock(from);
        if (!fb || !fb->tail) return false;
        if (fb->tail->opcode == mc::OP_GOTO && fb->tail->target_block == target)
            return true;
        // A block with a single successor that is the target also counts
        // (the CBRANCH was already lowered to unconditional flow).
        if (fb->successors.size() == 1 && fb->successors[0] == target)
            return true;
        return false;
    };
    auto isCbranchBlock = [&](int b) -> bool {
        if (b < 0 || b >= n) return false;
        auto* fb = mba_->getBlock(b);
        return fb && fb->tail && fb->tail->opcode == mc::OP_CBRANCH &&
               (int)fb->successors.size() >= 2;
    };

    // v7.0: && detection — succTrue is a CBRANCH, succFalse jumps to merge.
    if (merge >= 0 && isCbranchBlock(succTrue) && isDirectJumpTo(succFalse, merge)) {
        auto* innerBlk = mba_->getBlock(succTrue);
        int innerMerge = findMergePointFast(innerBlk->successors[0],
                                            innerBlk->successors[1]);
        if (innerMerge < 0)
            innerMerge = findMergePoint(mba_, innerBlk->successors[0],
                                        innerBlk->successors[1]);
        if (innerMerge == merge) {
            // Confirmed && pattern: if (condA && condB) { ...trueBody... }
            // The true body is the inner CBRANCH's "true" successor path to merge.
            // Structure: IF_THEN with compound condition, body = inner true path.
            if (isStructTimedOut()) return nullptr;
            // Collect the inner true-branch blocks (from inner true succ to merge).
            std::set<int> andTrueBlocks;
            collectBranch(succTrue, merge, andTrueBlocks, -1);
            // The inner CBRANCH block itself is part of the condition, not the body.
            andTrueBlocks.erase(succTrue);
            for (int b : andTrueBlocks) remaining.erase(b);

            auto ifRegion = std::make_unique<Region>(REGION_IF_THEN);
            ifRegion->block_id = branch_block;
            ifRegion->condition = blk->tail;
            // v7.0: Mark as AND compound condition. The ctree builder
            // (ctree_impl.cpp) emits "condA && condB" when is_or_condition
            // is false and the then_region contains the second condition.
            ifRegion->is_or_condition = false;
            if (ifRegion->condition) ifRegion->start_addr = ifRegion->condition->ea;
            // Store the inner condition as the then_region's condition via
            // a nested IF_THEN that carries the second condition.
            auto innerIf = std::make_unique<Region>(REGION_IF_THEN);
            innerIf->block_id = succTrue;
            innerIf->condition = innerBlk->tail;
            if (innerIf->condition) innerIf->start_addr = innerIf->condition->ea;
            auto thenBody = trySequence(andTrueBlocks);
            innerIf->then_region = std::move(thenBody);
            ifRegion->then_region = std::move(innerIf);
            return ifRegion;
        }
    }

    // v7.0: || detection — succFalse is a CBRANCH, succTrue jumps to merge.
    if (merge >= 0 && isCbranchBlock(succFalse) && isDirectJumpTo(succTrue, merge)) {
        auto* innerBlk = mba_->getBlock(succFalse);
        int innerMerge = findMergePointFast(innerBlk->successors[0],
                                            innerBlk->successors[1]);
        if (innerMerge < 0)
            innerMerge = findMergePoint(mba_, innerBlk->successors[0],
                                        innerBlk->successors[1]);
        if (innerMerge == merge) {
            // Confirmed || pattern: if (condA || condB) { ...trueBody... }
            // The true body is the inner CBRANCH's "true" successor path to merge.
            if (isStructTimedOut()) return nullptr;
            std::set<int> orTrueBlocks;
            collectBranch(succFalse, merge, orTrueBlocks, -1);
            orTrueBlocks.erase(succFalse);
            for (int b : orTrueBlocks) remaining.erase(b);

            auto ifRegion = std::make_unique<Region>(REGION_IF_THEN);
            ifRegion->block_id = branch_block;
            ifRegion->condition = blk->tail;
            // v7.0: Mark as OR compound condition for the ctree builder.
            ifRegion->is_or_condition = true;
            if (ifRegion->condition) ifRegion->start_addr = ifRegion->condition->ea;
            auto innerIf = std::make_unique<Region>(REGION_IF_THEN);
            innerIf->block_id = succFalse;
            innerIf->condition = innerBlk->tail;
            if (innerIf->condition) innerIf->start_addr = innerIf->condition->ea;
            auto thenBody = trySequence(orTrueBlocks);
            innerIf->then_region = std::move(thenBody);
            ifRegion->then_region = std::move(innerIf);
            return ifRegion;
        }
    }

    // v7.0: collectBranch lambda is defined above (before short-circuit
    // detection) so the && / || handlers can reuse it.

    std::set<int> trueBlocks;
    std::set<int> falseBlocks;
    collectBranch(succTrue, merge, trueBlocks, succFalse);
    collectBranch(succFalse, merge, falseBlocks, succTrue);

    // v4.10/v4.11: If-break pattern detection.
    // When processing a loop body, CBRANCH blocks often have one branch going
    // to a loop exit (break) and the other falling through to the next block.
    // Use pre-computed loop_exit_blocks_ for O(1) lookup instead of O(n_loops).
    bool trueIsBreak = loop_exit_blocks_.count(succTrue) > 0;
    bool falseIsBreak = loop_exit_blocks_.count(succFalse) > 0;

    // v5.8: Also check if successors are loop headers (continue targets).
    // Without this, when both branches go to loop headers (both continue),
    // the code falls through to regular if-then-else creating useless
    // "if (cond) continue; else continue;" patterns.
    bool trueIsContinue = false;
    bool falseIsContinue = false;
    for (auto& loop : loops_) {
        if (loop.header == succTrue) trueIsContinue = true;
        if (loop.header == succFalse) falseIsContinue = true;
    }

    // v5.8: Both branches are loop-related (break or continue) —
    // don't fall through to regular if-then-else which creates useless code.
    // Handle the case BEFORE the existing single-branch-break checks,
    // so we don't emit "if (cond) continue; else continue;"
    if (trueIsContinue && falseIsContinue) {
        // Both branches go back to the loop header — redundant branch.
        // Return nullptr so this block is treated as a basic block
        // (its non-CBRANCH instructions will be emitted, CBRANCH skipped).
        // branch_block was already erased by trySequence; do NOT re-add it.
        return nullptr;
    }

    // v6.1: One branch is continue (→ loop header), the other is break (→ loop exit).
    // This is the LOOP CONDITION pattern — the CBRANCH decides whether to continue
    // or exit the loop. It should NOT be structured as an if-break in the body;
    // it's already captured by doWhileToStmt's loop_condition.
    // Return nullptr so the block is emitted as a basic block (body instructions
    // emitted, CBRANCH skipped by shouldEmitStmt).
    // Ghidra reference: ruleBlockDoWhile separates the condition CBRANCH
    // from the body. The condition CBRANCH is the back edge block's tail.
    if ((trueIsContinue && falseIsBreak) || (trueIsBreak && falseIsContinue)) {
        return nullptr;
    }

    if (trueIsBreak && falseIsBreak) {
        // Both branches go to loop exits — emit if-break for the true branch
        if (isStructTimedOut()) return nullptr;
        for (int b : trueBlocks) remaining.erase(b);
        auto ifRegion = std::make_unique<Region>(REGION_IF_THEN);
        ifRegion->block_id = branch_block;
        ifRegion->condition = blk->tail;
        if (ifRegion->condition) ifRegion->start_addr = ifRegion->condition->ea;
        auto breakRegion = std::make_unique<Region>(REGION_BREAK);
        ifRegion->then_region = std::move(breakRegion);
        return ifRegion;
    }

    if (trueIsBreak && !falseIsBreak) {
        // True branch is a break — emit "if (cond) break;" and leave
        // the false branch blocks in remaining for sequential processing.
        // v4.12: If timed out, return nullptr BEFORE modifying remaining
        if (isStructTimedOut()) return nullptr;

        for (int b : trueBlocks) remaining.erase(b);
        // falseBlocks stay in remaining — they will be processed next as sequential code

        std::unique_ptr<Region> ifRegion;
        // v6.0: When trueBlocks is empty, the branch goes directly to the exit.
        // This happens when the CBRANCH target IS the loop exit block.
        // Ghidra reference: ruleIfBreakGoto handles direct-branch-to-exit.
        if (trueBlocks.empty()) {
            // True branch goes directly to exit — create IF_THEN with BREAK
            ifRegion = std::make_unique<Region>(REGION_IF_THEN);
            ifRegion->block_id = branch_block;
            ifRegion->condition = blk->tail;
            if (ifRegion->condition) ifRegion->start_addr = ifRegion->condition->ea;
            auto breakRegion = std::make_unique<Region>(REGION_BREAK);
            ifRegion->then_region = std::move(breakRegion);
        } else if (trueBlocks.size() == 1 && *trueBlocks.begin() == succTrue) {
            // Single block break — create IF_THEN with BREAK child
            ifRegion = std::make_unique<Region>(REGION_IF_THEN);
            ifRegion->block_id = branch_block;
            ifRegion->condition = blk->tail;
            if (ifRegion->condition) ifRegion->start_addr = ifRegion->condition->ea;
            auto breakRegion = std::make_unique<Region>(REGION_BREAK);
            ifRegion->then_region = std::move(breakRegion);
        } else {
            // Complex then branch — still create IF_THEN, collect the branch
            ifRegion = std::make_unique<Region>(REGION_IF_THEN);
            ifRegion->block_id = branch_block;
            ifRegion->condition = blk->tail;
            if (ifRegion->condition) ifRegion->start_addr = ifRegion->condition->ea;
            auto thenRegion = trySequence(trueBlocks);
            ifRegion->then_region = std::move(thenRegion);
        }
        return ifRegion;
    }

    if (falseIsBreak && !trueIsBreak) {
        // False branch is a break — emit "if (!cond) break;" by negating the condition
        // and swapping the branches. Leave the true branch blocks in remaining.
        // v4.12: If timed out, return nullptr BEFORE modifying remaining
        if (isStructTimedOut()) return nullptr;

        for (int b : falseBlocks) remaining.erase(b);
        // trueBlocks stay in remaining — they will be processed next as sequential code

        std::unique_ptr<Region> ifRegion;
        // v6.0: When falseBlocks is empty, the fall-through goes directly to the exit.
        if (falseBlocks.empty()) {
            ifRegion = std::make_unique<Region>(REGION_IF_THEN);
            ifRegion->block_id = branch_block;
            ifRegion->condition = blk->tail;
            ifRegion->condition_negated = true;
            if (ifRegion->condition) ifRegion->start_addr = ifRegion->condition->ea;
            auto breakRegion = std::make_unique<Region>(REGION_BREAK);
            ifRegion->then_region = std::move(breakRegion);
        } else if (falseBlocks.size() == 1 && *falseBlocks.begin() == succFalse) {
            ifRegion = std::make_unique<Region>(REGION_IF_THEN);
            ifRegion->block_id = branch_block;
            ifRegion->condition = blk->tail;
            ifRegion->condition_negated = true;
            if (ifRegion->condition) ifRegion->start_addr = ifRegion->condition->ea;
            auto breakRegion = std::make_unique<Region>(REGION_BREAK);
            ifRegion->then_region = std::move(breakRegion);
        } else {
            ifRegion = std::make_unique<Region>(REGION_IF_THEN);
            ifRegion->block_id = branch_block;
            ifRegion->condition = blk->tail;
            ifRegion->condition_negated = true;
            if (ifRegion->condition) ifRegion->start_addr = ifRegion->condition->ea;
            auto thenRegion = trySequence(falseBlocks);
            ifRegion->then_region = std::move(thenRegion);
        }
        return ifRegion;
    }

    // 从 remaining 中移除已收集的块
    // v4.2: merge point 必须保留在 remaining 中，由 trySequence 在 if-then-else
    // 之后继续处理。移除 merge point 会导致函数尾部代码丢失。
    // v4.12: Check for timeout BEFORE modifying remaining — if we're timed out,
    // return nullptr so caller falls through to fallback_block without losing blocks.
    if (isStructTimedOut()) return nullptr;
    for (int b : trueBlocks) remaining.erase(b);
    for (int b : falseBlocks) remaining.erase(b);
    // merge point stays in remaining — it will be processed after this if-then-else

    // 递归构建子区域
    auto thenRegion = trySequence(trueBlocks);
    auto elseRegion = trySequence(falseBlocks);

    // v4.2: hasElse 判定 — 只要 elseRegion 非空即为有效 else 分支
    // 旧逻辑用 !elseRegion->children.empty() 会错误地忽略单基本块 else 分支
    // （因为 REGION_BASIC_BLOCK 没有 children）
    bool hasElse = (elseRegion != nullptr);

    std::unique_ptr<Region> result;
    if (hasElse) {
        result = std::make_unique<Region>(REGION_IF_THEN_ELSE);
    } else {
        result = std::make_unique<Region>(REGION_IF_THEN);
    }

    result->block_id = branch_block;
    result->condition = blk->tail;
    if (result->condition) {
        result->start_addr = result->condition->ea;
    }
    result->then_region = std::move(thenRegion);
    if (hasElse) {
        result->else_region = std::move(elseRegion);
    }

    // v7.0: if-else-if chain detection (Ghidra ruleBlockCat for conditionals).
    // When two adjacent if-then-else share the same merge point, the inner
    // if appears as the else_region of the outer if. This is the canonical
    // "if (A) { ... } else if (B) { ... } else { ... }" chain.
    //
    // We validate that the else_region (if it is an IF_THEN/IF_THEN_ELSE)
    // shares the same merge point as the outer if — i.e. the inner if's
    // branches also converge to `merge`. When this holds, the structure is
    // already correct (else_region = inner if). We just ensure the inner
    // if's condition is preserved (not flattened) so the C printer emits
    // "else if" instead of "else { if (...) }".
    //
    // Reference: Ghidra ruleBlockCat merges sequential conditional blocks
    // that share a merge target into a single if-else-if chain.
    if (hasElse && result->else_region && merge >= 0) {
        auto& inner = result->else_region;
        if (inner->type == REGION_IF_THEN || inner->type == REGION_IF_THEN_ELSE) {
            // The inner if is an else-if candidate. Verify its block_id is
            // the false successor (succFalse), confirming adjacency.
            if (inner->block_id == succFalse) {
                // Confirmed if-else-if chain. The structure is already
                // correct: outer if's else_region = inner if.
                // No transformation needed — the C printer (ctree_impl)
                // already emits "else if" when else_region is an IF region.
                // We just ensure the inner condition is not negated
                // incorrectly (it should use its own condition as-is).
            }
        }
    }

    return result;
}

// ──── tryWhileLoop ────
std::unique_ptr<Region> CFGStructurer::tryWhileLoop(const NaturalLoop& loop, std::set<int>& remaining) {
    (void)mba_;
    auto* header = mba_->getBlock(loop.header);
    if (!header) return nullptr;

    // v4.12: Quick bailout — if timed out, flatten the loop
    if (isStructTimedOut()) {
        auto seq = std::make_unique<Region>(REGION_SEQUENCE);
        for (int b : loop.body) {
            auto bb = std::make_unique<Region>(REGION_BASIC_BLOCK);
            bb->block_id = b;
            remaining.erase(b);
            seq->children.push_back(std::move(bb));
        }
        return seq;
    }

    remaining.erase(loop.header);

    auto result = std::make_unique<Region>(REGION_WHILE);
    // v47.0: Only set loop_condition if header->tail is a CBRANCH.
    // For while(true) loops (no header CBRANCH), leave loop_condition
    // as nullptr so whileToStmt generates `while (1)`.
    result->loop_condition = (header->tail && header->tail->opcode == mc::OP_CBRANCH)
                             ? header->tail : nullptr;
    result->header_block = loop.header;
    if (!loop.exits.empty()) {
        // v7.0: Prefer the primary (tail-reachable) exit when available.
        result->exit_block = (loop.primary_exit >= 0) ? loop.primary_exit
                                                      : *loop.exits.begin();
    }

    // v5.9: For self-loops (back_edge_from == header), the header IS the
    // loop body. Include it in bodyRemaining so its non-CBRANCH instructions
    // get emitted as the loop body. For normal do-while loops, the header
    // is the entry point and its instructions are emitted before the loop.
    bool isSelfLoop = (loop.back_edge_from == loop.header);
    std::set<int> bodyRemaining;
    for (int b : loop.body) {
        if (!isSelfLoop && b == loop.header) continue;
        bodyRemaining.insert(b);
    }
    for (int e : loop.exits) bodyRemaining.erase(e);
    for (int b : loop.body) remaining.erase(b);

    result->loop_body = trySequence(bodyRemaining);

    // v50.3: Collapse fake while loop — but only if the back edge is
    // UNREACHABLE from the header. A return in a conditional branch
    // (e.g., the else-branch of an if-else inside the loop) does NOT
    // make the loop fake — the loop can still iterate on the then-branch.
    // The old code (v47.0) checked ALL body blocks for OP_RET, which
    // incorrectly collapsed real loops that contain a return in a
    // conditional path (e.g., JNI_OnLoad's outer loop).
    //
    // Correct approach: do a forward BFS from the loop header through
    // the body blocks. If the back edge source block is reachable, the
    // loop is real and should NOT be collapsed.
    // Ghidra reference: ruleCollapseBlock collapses do-while only when
    // all exits lead to return/goto — meaning the back edge condition
    // itself is unreachable.
    if (result->loop_body) {
        bool isRealLoop = false;
        if (loop.back_edge_from >= 0) {
            // Forward BFS from header through body blocks
            std::set<int> visited;
            std::queue<int> q;
            q.push(loop.header);
            visited.insert(loop.header);
            while (!q.empty() && !isRealLoop) {
                int cur = q.front(); q.pop();
                auto* blk = mba_->getBlock(cur);
                if (!blk) continue;
                // If we reached the back edge source, the loop is real
                if (cur == loop.back_edge_from) {
                    isRealLoop = true;
                    break;
                }
                // Only traverse within the loop body
                int numBlocks = mba_->numBlocks();
                for (int s : blk->successors) {
                    if (s < 0 || s >= numBlocks) continue;
                    if (!loop.body.count(s)) continue;
                    if (visited.count(s)) continue;
                    visited.insert(s);
                    q.push(s);
                }
            }
        }
        if (!isRealLoop) {
            // v50.1: When collapsing a fake loop, also remove any dangling
            // BREAK regions inside the loop body. These BREAK nodes were
            // created when GOTO-to-loop-exit edges were converted to
            // REGION_BREAK, but they are meaningless once the loop is collapsed.
            auto stripBreakRegions = [](std::unique_ptr<Region>& r, auto& selfRef) -> void {
                if (!r) return;
                std::vector<std::unique_ptr<Region>> filtered;
                for (auto& child : r->children) {
                    if (child->type == REGION_BREAK) {
                        continue;  // skip dangling BREAK
                    }
                    selfRef(child, selfRef);
                    filtered.push_back(std::move(child));
                }
                r->children = std::move(filtered);
            };
            if (result->loop_body)
                stripBreakRegions(result->loop_body, stripBreakRegions);
            
            // Replace while loop with sequence, preserving the body structure
            auto seq = std::make_unique<Region>(REGION_SEQUENCE);
            seq->children.push_back(std::move(result->loop_body));
            return seq;
        }
    }

    return result;
}

// ──── tryDoWhileLoop ────
std::unique_ptr<Region> CFGStructurer::tryDoWhileLoop(const NaturalLoop& loop, std::set<int>& remaining) {
    (void)mba_;
    auto* backBlk = mba_->getBlock(loop.back_edge_from);
    if (!backBlk) return nullptr;

    // v4.12: Quick bailout — if timed out, flatten the loop
    if (isStructTimedOut()) {
        auto seq = std::make_unique<Region>(REGION_SEQUENCE);
        for (int b : loop.body) {
            auto bb = std::make_unique<Region>(REGION_BASIC_BLOCK);
            bb->block_id = b;
            remaining.erase(b);
            seq->children.push_back(std::move(bb));
        }
        return seq;
    }

    remaining.erase(loop.header);

    auto result = std::make_unique<Region>(REGION_DO_WHILE);

    // v4.5: Find the CBRANCH block that provides the loop condition.
    // The back_edge_from may be a GOTO block (unconditional jump);
    // we need the CBRANCH block that has the header as a successor to
    // extract the actual loop condition (e.g. "sVar2 != 0").
    mc::MicroInsn* condInsn = nullptr;
    if (backBlk->tail && backBlk->tail->opcode == mc::OP_CBRANCH) {
        condInsn = backBlk->tail;
    } else {
        // Search loop body for a CBRANCH block with header as successor
        for (int b : loop.body) {
            auto* blk = mba_->getBlock(b);
            if (blk->tail && blk->tail->opcode == mc::OP_CBRANCH) {
                for (int succ : blk->successors) {
                    if (succ == loop.header) {
                        condInsn = blk->tail;
                        goto found_cond;
                    }
                }
            }
        }
        found_cond:;
    }
    result->loop_condition = condInsn;
    result->header_block = loop.header;
    result->is_post_test = true;
    if (!loop.exits.empty()) {
        // v7.0: Prefer the primary (tail-reachable) exit when available.
        result->exit_block = (loop.primary_exit >= 0) ? loop.primary_exit
                                                      : *loop.exits.begin();
    }

    // v6.1: Always include the header in bodyRemaining.
    // For do-while loops, the header IS part of the loop body — it contains
    // the loop body instructions (e.g. sub, cmp) and possibly a mid-loop
    // break CBRANCH. The loop condition comes from the back edge block,
    // which is excluded below.
    //
    // For self-loops (back_edge_from == header), the header contains BOTH
    // the body and the condition. tryIfThenElse detects the self-loop
    // (succ == branch_block) and returns nullptr, so the header falls
    // through to basic block emission (CBRANCH skipped, body emitted).
    //
    // For non-self-loops, the header's CBRANCH (if any) is detected as a
    // break (target = exit) by tryIfThenElse, generating "if (cond) break;".
    //
    // Ghidra reference: ruleBlockDoWhile includes the header in the body
    // and separates the condition block (back edge) from the body.
    std::set<int> bodyRemaining;
    for (int b : loop.body) {
        bodyRemaining.insert(b);
    }
    // v6.1: The back edge block is NOW included in bodyRemaining.
    // Previously (v6.0), it was excluded to avoid generating redundant
    // "if (cond) continue; else break;" in the body. But this lost body
    // instructions that precede the loop condition (e.g. `n--` before
    // `cmp; b.gt header`).
    //
    // Now, the loop-condition detection in tryIfThenElse handles this:
    // when a CBRANCH has one successor = loop header (continue) and the
    // other = loop exit (break), tryIfThenElse returns nullptr, causing
    // the block to be emitted as a basic block (body instructions emitted,
    // CBRANCH skipped by shouldEmitStmt).
    //
    // Ghidra reference: ruleBlockDoWhile keeps the condition block in the
    // body but only emits its non-CBRANCH instructions.
    for (int e : loop.exits) bodyRemaining.erase(e);
    for (int b : loop.body) remaining.erase(b);

    result->loop_body = trySequence(bodyRemaining);

    // v50.3: Collapse fake do-while — but only if the back edge is
    // UNREACHABLE from the header. A return in a conditional branch
    // (e.g., the else-branch of an if-else inside the loop) does NOT
    // make the loop fake — the loop can still iterate on the then-branch.
    // The old code (v47.0) checked ALL body blocks for OP_RET, which
    // incorrectly collapsed real loops that contain a return in a
    // conditional path (e.g., JNI_OnLoad's outer loop).
    //
    // Correct approach: do a forward BFS from the loop header through
    // the body blocks. If the back edge source block is reachable, the
    // loop is real and should NOT be collapsed.
    // Ghidra reference: ruleCollapseBlock collapses do-while only when
    // all exits lead to return/goto — meaning the back edge condition
    // itself is unreachable.
    if (result->loop_body) {
        bool isRealLoop = false;
        if (loop.back_edge_from >= 0) {
            // Forward BFS from header through body blocks
            std::set<int> visited;
            std::queue<int> q;
            q.push(loop.header);
            visited.insert(loop.header);
            while (!q.empty() && !isRealLoop) {
                int cur = q.front(); q.pop();
                auto* blk = mba_->getBlock(cur);
                if (!blk) continue;
                // If we reached the back edge source, the loop is real
                if (cur == loop.back_edge_from) {
                    isRealLoop = true;
                    break;
                }
                // Only traverse within the loop body
                int numBlocks = mba_->numBlocks();
                for (int s : blk->successors) {
                    if (s < 0 || s >= numBlocks) continue;
                    if (!loop.body.count(s)) continue;
                    if (visited.count(s)) continue;
                    visited.insert(s);
                    q.push(s);
                }
            }
        }
        if (!isRealLoop) {
            // v50.1: When collapsing a fake do-while loop, also remove
            // any dangling BREAK regions inside the loop body. These
            // BREAK nodes were created when GOTO-to-loop-exit edges
            // were converted to REGION_BREAK, but they are meaningless
            // once the loop is collapsed.
            auto stripBreakRegions = [](std::unique_ptr<Region>& r, auto& selfRef) -> void {
                if (!r) return;
                std::vector<std::unique_ptr<Region>> filtered;
                for (auto& child : r->children) {
                    if (child->type == REGION_BREAK) {
                        continue;  // skip dangling BREAK
                    }
                    selfRef(child, selfRef);
                    filtered.push_back(std::move(child));
                }
                r->children = std::move(filtered);
            };
            if (result->loop_body)
                stripBreakRegions(result->loop_body, stripBreakRegions);
            
            // Replace do-while with sequence, preserving the body structure
            auto seq = std::make_unique<Region>(REGION_SEQUENCE);
            seq->children.push_back(std::move(result->loop_body));
            return seq;
        }
    }

    return result;
}

// ──── trySwitch ────
// v4.8: Implement switch detection using Ghidra's approach:
// A switch has a single entry block, multiple case blocks (each with 0-1 outs),
// and a shared exit block. We detect it when a block has 3+ successors.
//
// Ghidra reference: CollapseStructure::ruleBlockSwitch()
// BlockSwitch: first component = switch body, subsequent = case components.
// Each case has 0 or 1 outgoing edges; if it has an edge, it flows to the
// next case or to the formal exit block.
std::unique_ptr<Region> CFGStructurer::trySwitch(int switch_block, std::set<int>& remaining) {
    int n = mba_->numBlocks();
    auto* blk = mba_->getBlock(switch_block);
    if (!blk) return nullptr;
    if (!remaining.count(switch_block)) return nullptr;

    // A switch needs at least 3 successors (more than a simple if-else)
    if ((int)blk->successors.size() < 3) return nullptr;

    // The switch must be an indirect branch (BR) or a computed jump
    // Check if it's a BR instruction (not a conditional branch)
    if (!blk->tail) return nullptr;
    // v10.0: Accept OP_JTBL (jump table) in addition to OP_GOTO/OP_CBRANCH.
    // AArch64 (ldr+br) and ARM32 (ldr pc) jump tables emit OP_JTBL.
    if (blk->tail->opcode != mc::OP_GOTO && blk->tail->opcode != mc::OP_CBRANCH &&
        blk->tail->opcode != mc::OP_JTBL)
        return nullptr;

    // For indirect branches (BR), the switch is the switch expression
    // For direct branches with many successors, it's a jump table
    auto result = std::make_unique<Region>(REGION_SWITCH);
    result->switch_expr = blk->tail;
    result->block_id = switch_block;
    result->start_addr = blk->start_addr;

    remaining.erase(switch_block);

    // Collect case blocks: each successor is a potential case
    std::set<int> caseBlocks;
    std::set<int> allCaseBlocks;
    for (int succ : blk->successors) {
        if (succ < 0 || succ >= n) continue;
        if (succ == switch_block) continue;
        caseBlocks.insert(succ);
        allCaseBlocks.insert(succ);
    }

    // Find the common exit block using BFS intersection
    int mergePoint = -1;
    if (caseBlocks.size() >= 2) {
        auto it = caseBlocks.begin();
        int firstCase = *it; ++it;
        mergePoint = findMergePoint(mba_, firstCase, *it);
        // Verify merge point is reachable from all case blocks
        if (mergePoint >= 0) {
            for (int cb : caseBlocks) {
                if (cb == firstCase || cb == *caseBlocks.begin()) continue;
                int mp2 = findMergePoint(mba_, cb, mergePoint);
                if (mp2 != mergePoint && mp2 != cb) {
                    mergePoint = -1; break;
                }
            }
        }
    }

    // Build case regions
    int caseIdx = 0;
    for (int caseBlock : allCaseBlocks) {
        // Collect blocks from this case to the merge point
        std::set<int> branchBlocks;
        {
            std::queue<int> q;
            std::set<int> visited;
            q.push(caseBlock);
            visited.insert(caseBlock);
            while (!q.empty()) {
                int cur = q.front(); q.pop();
                if (cur == mergePoint) continue;
                if (cur == switch_block) continue;
                if (allCaseBlocks.count(cur) && cur != caseBlock) continue; // don't cross into other cases
                branchBlocks.insert(cur);
                auto* cb = mba_->getBlock(cur);
                for (int next : cb->successors) {
                    if (next < 0 || next >= n) continue;
                    if (visited.count(next)) continue;
                    visited.insert(next);
                    q.push(next);
                }
            }
        }

        for (int b : branchBlocks) remaining.erase(b);

        auto caseRegion = trySequence(branchBlocks);
        if (caseRegion) {
            // v9.1/v10.0: Extract actual case value from the switch instruction
            // Priority:
            //   1) Real case value from case_values vector (populated by
            //      handleAarch64JumpTable/handleArm32JumpTable/handleTbbTbh
            //      from ELF jump table data)
            //   2) Sequential index (fallback when case_values is empty)
            int caseVal = caseIdx;
            
            // v10.0: For switch table instructions (OP_JTBL or IPROP_SWITCH),
            // prefer the real case values extracted from the jump table.
            // The case_values vector stores the actual case constants
            // (e.g. 0, 1, 5, 99, ...) in table-position order, which maps
            // directly to the successor ordering.
            bool hasCaseValues = (blk->tail && 
                                  !blk->tail->case_values.empty() &&
                                  caseIdx < (int)blk->tail->case_values.size());
            
            if (hasCaseValues) {
                // Use the real case value extracted from the jump table.
                // This handles sparse switch tables (e.g. case 0, 1, 5, 99)
                // where the table position maps to a non-sequential value.
                caseVal = (int)blk->tail->case_values[caseIdx];
            } else if (blk->tail && (blk->tail->iprops & mc::IPROP_SWITCH)) {
                // TBB/TBH or jump table without ELF data: use sequential
                // indices 0, 1, 2, ... as case values.
                caseVal = caseIdx;
            }
            // For CBRANCH-based switch (if-else chain), case_values may also
            // be populated; the hasCaseValues check above handles it.
            
            result->cases.push_back({caseVal, std::move(caseRegion)});
            caseIdx++;
        }
    }

    // v9.1: Detect default region
    // If mergePoint is a valid block and not in allCaseBlocks, it's the default
    if (mergePoint >= 0 && mergePoint < n && !allCaseBlocks.count(mergePoint)) {
        std::set<int> defaultBlocks;
        defaultBlocks.insert(mergePoint);
        // Collect the default branch (may include following blocks)
        remaining.erase(mergePoint);
        auto defaultRegion = trySequence(defaultBlocks);
        if (defaultRegion) {
            result->default_region = std::move(defaultRegion);
        }
    }

    // Keep merge point in remaining
    return result;
}

// v4.8: post-order traversal for break/continue recovery
// Walk the region tree, find GOTO regions inside loops, and classify
// them as break (target is exit block) or continue (target is header).
static void recoverBreakContinueImpl(Region* root, const std::vector<NaturalLoop>& loops,
                                     mc::MicrocodeBlockArray* mba) {
    if (!root) return;

    // Recurse into children
    for (auto& c : root->children) recoverBreakContinueImpl(c.get(), loops, mba);
    if (root->then_region) recoverBreakContinueImpl(root->then_region.get(), loops, mba);
    if (root->else_region) recoverBreakContinueImpl(root->else_region.get(), loops, mba);
    if (root->loop_body) recoverBreakContinueImpl(root->loop_body.get(), loops, mba);

    // Process SEQUENCE: find GOTO children and check if they target loop exits/headers
    if (root->type == REGION_SEQUENCE) {
        for (size_t i = 0; i < root->children.size(); i++) {
            auto& c = root->children[i];
            if (!c || c->type != REGION_GOTO) continue;
            if (c->goto_target < 0) continue;

            for (auto& loop : loops) {
                if (c->goto_target == loop.header) { c->type = REGION_CONTINUE; break; }
                if (loop.exits.count(c->goto_target)) { c->type = REGION_BREAK; break; }
            }
        }
    }

    if (root->type == REGION_IF_THEN || root->type == REGION_IF_THEN_ELSE) {
        auto checkRegion = [&](std::unique_ptr<Region>& r) {
            if (!r) return;
            if (r->type == REGION_SEQUENCE) {
                for (auto& c : r->children) {
                    if (!c || c->type != REGION_GOTO) continue;
                    if (c->goto_target < 0) continue;
                    for (auto& loop : loops) {
                        if (c->goto_target == loop.header) { c->type = REGION_CONTINUE; break; }
                        if (loop.exits.count(c->goto_target)) { c->type = REGION_BREAK; break; }
                    }
                }
            } else if (r->type == REGION_GOTO && r->goto_target >= 0) {
                for (auto& loop : loops) {
                    if (r->goto_target == loop.header) { r->type = REGION_CONTINUE; break; }
                    if (loop.exits.count(r->goto_target)) { r->type = REGION_BREAK; break; }
                }
            }
        };
        checkRegion(root->then_region);
        checkRegion(root->else_region);
    }
}

// ──── Goto elimination ────
void CFGStructurer::eliminateGotos(Region* root) {
    if (!root) return;
    // v4.12: Skip goto elimination if we already timed out — the
    // region tree may be deeply nested due to recursion bailout,
    // making the O(N²) traversal here prohibitively expensive.
    if (isStructTimedOut()) return;
    convertConditionalGoto(root);
    recoverBreakContinue(root);
    recoverBreakContinueImpl(root, loops_, mba_);
    promoteLoopCondition(root);
    for (auto& c : root->children) eliminateGotos(c.get());
    if (root->then_region) eliminateGotos(root->then_region.get());
    if (root->else_region) eliminateGotos(root->else_region.get());
    if (root->loop_body) eliminateGotos(root->loop_body.get());
}

void CFGStructurer::convertConditionalGoto(Region* root) {
    if (!root) return;
    // Convert GOTO regions inside if-then-else to break/continue/goto
    // based on whether the target is a loop exit or loop header.
    for (auto& c : root->children) convertConditionalGoto(c.get());
    if (root->then_region) convertConditionalGoto(root->then_region.get());
    if (root->else_region) convertConditionalGoto(root->else_region.get());
    if (root->loop_body) convertConditionalGoto(root->loop_body.get());
}

void CFGStructurer::recoverBreakContinue(Region* root) {
    // v4.8: Ghidra-style break/continue recovery.
    // Walk the region tree and convert GOTO regions that target
    // a loop's exit block (break) or header block (continue).
    if (!root) return;

    // Recurse first
    for (auto& c : root->children) recoverBreakContinue(c.get());
    if (root->then_region) recoverBreakContinue(root->then_region.get());
    if (root->else_region) recoverBreakContinue(root->else_region.get());
    if (root->loop_body) recoverBreakContinue(root->loop_body.get());

    // Process GOTO regions inside loops
    if (root->type == REGION_GOTO && root->goto_target >= 0) {
        // Walk up to find enclosing loop
        // We need to find the enclosing loop by checking the region tree
        // Since we don't have parent pointers, we process in the caller
    }
}

void CFGStructurer::promoteLoopCondition(Region* root) {
    // v4.8: Promote loop conditions from inner blocks to the loop header.
    // This handles cases where the condition is not at the tail of the
    // back-edge block but somewhere inside the loop body.
    //
    // v24.0: Search through ALL region types, not just REGION_SEQUENCE.
    // The loop body may be a REGION_IF_ELSE, REGION_WHILE, REGION_DO_WHILE,
    // or REGION_BASIC_BLOCK. In each case, we recursively search for the
    // last CBRANCH instruction in the loop body's blocks.
    // 对标 Ghidra CollapseStructure::labelLoops: searches the entire loop
    // body for the conditional branch that exits the loop, not just the
    // immediate sequence children.
    //
    // v24.1: Added fallback scan of ALL blocks in the function when the
    // region-based search fails. This handles the case where the CBRANCH
    // is in a block that hasn't been added to the region tree yet, or
    // where the loop body region is incomplete.
    // 对标 Ghidra: labelLoops scans the entire BlockGraph for back edges,
    // not just the already-structured regions.
    if (!root) return;

    // Recurse
    for (auto& c : root->children) promoteLoopCondition(c.get());
    if (root->then_region) promoteLoopCondition(root->then_region.get());
    if (root->else_region) promoteLoopCondition(root->else_region.get());
    if (root->loop_body) promoteLoopCondition(root->loop_body.get());

    // For loops, if loop_condition is null, try to find it from the loop body
    if ((root->type == REGION_WHILE || root->type == REGION_DO_WHILE) && !root->loop_condition) {
        mc::MicroInsn* found = nullptr;

        // Search all blocks in the loop body for the last CBRANCH
        std::function<void(Region*)> searchBody;
        searchBody = [&](Region* r) {
            if (!r || found) return;
            // BASIC_BLOCK: check the tail instruction for CBRANCH
            if (r->type == REGION_BASIC_BLOCK && r->block_id >= 0) {
                auto* blk = mba_ ? mba_->getBlock(r->block_id) : nullptr;
                if (blk && blk->tail && blk->tail->opcode == mc::OP_CBRANCH) {
                    found = blk->tail;
                    return;
                }
            }
            // If this region has a loop_condition already, use it
            if (r->loop_condition) {
                found = r->loop_condition;
                return;
            }
            // If this is a loop, check the header block
            if (r->header_block >= 0) {
                auto* hdr = mba_ ? mba_->getBlock(r->header_block) : nullptr;
                if (hdr && hdr->tail && hdr->tail->opcode == mc::OP_CBRANCH) {
                    found = hdr->tail;
                    return;
                }
            }
            // Recurse through children in reverse order (last child first)
            for (int i = (int)r->children.size() - 1; i >= 0; i--) {
                searchBody(r->children[i].get());
                if (found) return;
            }
            // Recurse through sub-regions
            if (r->then_region) searchBody(r->then_region.get());
            if (r->else_region) searchBody(r->else_region.get());
            if (r->loop_body) searchBody(r->loop_body.get());
        };

        if (root->loop_body) {
            searchBody(root->loop_body.get());
        }

        // Also check the header block itself
        if (!found && root->header_block >= 0) {
            auto* hdr = mba_ ? mba_->getBlock(root->header_block) : nullptr;
            if (hdr && hdr->tail && hdr->tail->opcode == mc::OP_CBRANCH) {
                // v47.0: Only set loop_condition if the CBRANCH is a back edge
                // or conditional exit. Check if the branch target is outside
                // the loop body (i.e., it's the loop exit).
                bool target_in_body = false;
                if (root->loop_body) {
                    std::function<bool(Region*, int)> inBody = [&](Region* r, int blkId) -> bool {
                        if (!r) return false;
                        if (r->type == REGION_BASIC_BLOCK && r->block_id == blkId) return true;
                        for (auto& c : r->children)
                            if (inBody(c.get(), blkId)) return true;
                        if (r->then_region && inBody(r->then_region.get(), blkId)) return true;
                        if (r->else_region && inBody(r->else_region.get(), blkId)) return true;
                        if (r->loop_body && inBody(r->loop_body.get(), blkId)) return true;
                        return false;
                    };
                    target_in_body = inBody(root->loop_body.get(), hdr->tail->target_block);
                }
                if (!target_in_body) {
                    found = hdr->tail;
                }
            }
        }

        // v24.1: Fallback: scan ALL blocks in the function for the loop header's
        // CBRANCH instruction. This handles the case where the region tree is
        // incomplete (e.g., the loop body hasn't been fully structured yet).
        if (!found && root->header_block >= 0 && mba_) {
            auto* hdr = mba_->getBlock(root->header_block);
            if (hdr) {
                // Scan all instructions in the header block for CBRANCH
                for (mc::MicroInsn* insn = hdr->head; insn; insn = insn->next) {
                    if (insn->opcode == mc::OP_CBRANCH) {
                        // Check if target is outside the loop body
                        bool target_in_body = false;
                        if (root->loop_body) {
                            std::function<bool(Region*, int)> inBody = [&](Region* r, int blkId) -> bool {
                                if (!r) return false;
                                if (r->type == REGION_BASIC_BLOCK && r->block_id == blkId) return true;
                                for (auto& c : r->children)
                                    if (inBody(c.get(), blkId)) return true;
                                if (r->then_region && inBody(r->then_region.get(), blkId)) return true;
                                if (r->else_region && inBody(r->else_region.get(), blkId)) return true;
                                if (r->loop_body && inBody(r->loop_body.get(), blkId)) return true;
                                return false;
                            };
                            target_in_body = inBody(root->loop_body.get(), insn->target_block);
                        }
                        if (!target_in_body) {
                            found = insn;
                            break;
                        }
                    }
                }
            }
        }

        if (found) {
            root->loop_condition = found;
            fprintf(stderr, "[DBG_PROMOTE] loop_condition promoted for header_block=%d: CBRANCH at 0x%llx cond=%d target=%d\n",
                    root->header_block, (unsigned long long)found->ea, found->cond, found->target_block);
        }
    }
}

// ──── Debug ────
void CFGStructurer::computePostDominatorsDebug(mc::MicrocodeBlockArray& mba) {
    mba_ = &mba;
    computePostDominators();

    fprintf(stderr, "=== Post-dominator debug ===\n");
    int n = mba_->numBlocks();
    for (int i = 0; i < n; i++) {
        auto* blk = mba_->getBlock(i);
        fprintf(stderr, "b%d: pdom_parent=b%d  children=[", i, blk->pdom_parent);
        for (size_t j = 0; j < blk->pdom_children.size(); j++) {
            if (j > 0) fprintf(stderr, ",");
            fprintf(stderr, "b%d", blk->pdom_children[j]);
        }
        fprintf(stderr, "]  succ_count=%zu", blk->successors.size());
        if (!blk->successors.empty()) fprintf(stderr, " succ[0]=b%d", blk->successors[0]);
        if (blk->successors.size() >= 2) fprintf(stderr, " succ[1]=b%d", blk->successors[1]);
        fprintf(stderr, "\n");
    }
}

// ════════════════════════════════════════════════════════════════════
// FixpointStructurer — Ghidra-style fixpoint rule engine
// 对标 Ghidra blockaction.cc + CollapseStructure
// ════════════════════════════════════════════════════════════════════

// ── Build initial graph: one node per basic block ──
// succs stores block IDs (not node IDs), since node IDs change after collapse.
// Block IDs are stable because they refer to mba_ basic blocks.
void FixpointStructurer::buildInitial() {
    int n = mba_->numBlocks();
    nodes_.clear();
    nodes_.reserve(n);
    
    // v20: Only create nodes for blocks that are reachable from the entry block.
    // Blocks that are not reachable (e.g., exception handler targets that are
    // only reachable through special edges) should not be included in the CFG
    // graph, as they will never be structured by the fixpoint rules and will
    // remain as orphaned nodes that pollute the output.
    std::set<int> reachableBlocks;
    {
        fprintf(stderr, "[DBG_BUILD] BFS from block 0: succs=[");
        {
            auto* b0 = mba_->getBlock(0);
            if (b0) for (size_t si = 0; si < b0->successors.size(); si++) fprintf(stderr, "%s%d", si?",":"", b0->successors[si]);
        }
        fprintf(stderr, "]\n");
        std::queue<int> q;
        q.push(0);
        reachableBlocks.insert(0);
        while (!q.empty()) {
            int cur = q.front(); q.pop();
            auto* blk = mba_->getBlock(cur);
            if (blk) {
                for (int s : blk->successors) {
                    if (s >= 0 && s < n && !reachableBlocks.count(s)) {
                        reachableBlocks.insert(s);
                        q.push(s);
                    }
                }
            }
        }
    }
    
    // Map: original block ID → new node ID (for blocks that are reachable)
    fprintf(stderr, "[DBG_BUILD] buildInitial: %d total blocks, %zu reachable from entry\n", n, reachableBlocks.size());
    for (int i = 0; i < n; i++) {
        if (!reachableBlocks.count(i)) {
            fprintf(stderr, "[DBG_BUILD]  block %d NOT reachable\n", i);
        }
    }
    std::map<int, int> blockToNode;
    int nodeId = 0;
    for (int i = 0; i < n; i++) {
        if (!reachableBlocks.count(i)) continue;
        blockToNode[i] = nodeId;
        
        RNode node;
        node.id = nodeId;
        node.blocks.insert(i);
        node.type = REGION_BASIC_BLOCK;
        node.start_addr = mba_->getBlock(i)->start_addr;
        nodes_.push_back(std::move(node));
        nodeId++;
    }
    
    // Second pass: set succs using mapped node IDs
    for (auto& node : nodes_) {
        int blkId = *node.blocks.begin();
        auto* blk = mba_->getBlock(blkId);
        if (blk) {
            for (int s : blk->successors) {
                if (blockToNode.count(s)) {
                    node.succs.push_back(s);  // store original block ID
                }
            }
        }
    }
    
    recomputeEdges();
}

// ── Recompute edges: rebuild preds from succs, clean up stale entries ──
// v5.5: Also clean up succs — remove self-references and duplicates by node.
// After collapsing nodes, succs may contain block IDs that now belong to
// the same node. These must be removed or rules will see phantom edges.
void FixpointStructurer::recomputeEdges() {
    // v12.0 debug: track entry node's succs
    static int recCount = 0;
    recCount++;
    if (recCount % 10 == 0 || recCount == 1) {
        int entryN = findNodeByBlock(0);
        if (entryN >= 0) {
            DBG_PRINT("[DBG_REC] #%d entryNode%d succs={", recCount, entryN);
            for (int s : nodes_[entryN].succs) fprintf(stderr, "%d ", s);
            fprintf(stderr, "}\n");
        }
    }
    
    // Clean up succs: remove self-references and duplicates (by target node)
    for (auto& node : nodes_) {
        std::set<int> seenNodes;
        std::vector<int> cleanedSuccs;
        for (int succ_blk : node.succs) {
            int succ_node = findNodeByBlock(succ_blk);
            if (succ_node >= 0 && succ_node != node.id && !seenNodes.count(succ_node)) {
                seenNodes.insert(succ_node);
                cleanedSuccs.push_back(succ_blk);
            }
        }
        node.succs = std::move(cleanedSuccs);
    }

    // Rebuild preds from cleaned succs
    for (auto& node : nodes_)
        node.preds.clear();
    for (auto& node : nodes_) {
        for (int succ_blk : node.succs) {
            int succ_node = findNodeByBlock(succ_blk);
            if (succ_node >= 0 && succ_node != node.id)
                nodes_[succ_node].preds.push_back(node.id);
        }
    }

    // Deduplicate preds
    for (auto& node : nodes_) {
        std::set<int> seenPreds(node.preds.begin(), node.preds.end());
        node.preds.assign(seenPreds.begin(), seenPreds.end());
    }
}

// ── Find node containing a given block ──
int FixpointStructurer::findNodeByBlock(int block_id) const {
    for (auto& node : nodes_) {
        if (node.blocks.count(block_id))
            return node.id;
    }
    return -1;
}

// ── Dominator computation (same as CFGStructurer) ──
void FixpointStructurer::computeDominators() {
    int n = mba_->numBlocks();
    idom_.assign(n, -2);
    ipdom_.assign(n, -2);
    if (n == 0) return;

    // Forward dominators
    idom_[0] = -1;
    std::vector<int> rpo;
    {
        std::set<int> visited;
        std::function<void(int)> dfs = [&](int u) {
            visited.insert(u);
            rpo.push_back(u);
            auto* blk = mba_->getBlock(u);
            if (blk) {
                for (int s : blk->successors) {
                    if (s >= 0 && s < n && !visited.count(s)) dfs(s);
                }
            }
        };
        dfs(0);
    }
    bool changed = true;
    while (changed) {
        changed = false;
        for (int b : rpo) {
            if (b == 0) continue;
            int newIdom = -2;
            auto* blk = mba_->getBlock(b);
            if (blk) {
                for (int p : blk->predecessors) {
                    if (p < 0 || p >= n) continue;
                    if (idom_[p] == -2) continue;
                    if (newIdom == -2) { newIdom = p; continue; }
                    // Intersect: find the nearest common ancestor in the
                    // dominator tree by walking up BOTH chains.
                    {
                        int a = newIdom, c = p;
                        std::set<int> ancestors;
                        while (a >= 0) {
                            ancestors.insert(a);
                            if (a == c) break;
                            a = idom_[a];
                        }
                        while (c >= 0) {
                            if (ancestors.count(c)) {
                                newIdom = c;
                                break;
                            }
                            c = idom_[c];
                        }
                        if (c < 0) newIdom = -1;
                    }
                }
            }
            if (newIdom >= -1 && newIdom != idom_[b]) {
                idom_[b] = newIdom;
                changed = true;
            }
        }
    }

    // v20: Fix any remaining -2 nodes (unreachable from entry in predecessor graph).
    // These are blocks that exist in the MicroBlockArray but are not reachable
    // from block 0 via the predecessor chain (e.g., exception handler targets
    // that are only reachable through special edges). Set them to be dominated
    // by the entry block so the dominator tree is complete.
    for (int i = 0; i < n; i++) {
        if (idom_[i] == -2) {
            idom_[i] = -1;
            fprintf(stderr, "[DBG_DOM] Fixed unreachable node %d: idom_=-1 (entry)\n", i);
        }
    }
    
    // v44.0 debug: dump dominator tree for key blocks
    fprintf(stderr, "[DBG_DOM] DTREE: idom_[17]=%d idom_[19]=%d idom_[21]=%d idom_[22]=%d idom_[23]=%d idom_[24]=%d idom_[25]=%d idom_[26]=%d idom_[40]=%d idom_[56]=%d idom_[67]=%d idom_[68]=%d idom_[69]=%d\n",
            idom_[17], idom_[19], idom_[21], idom_[22], idom_[23], idom_[24], idom_[25], idom_[26], idom_[40], idom_[56], idom_[67], idom_[68], idom_[69]);

    // Post-dominators
    int exitNode = -1;
    for (int i = n-1; i >= 0; i--) {
        if (mba_->getBlock(i)->successors.empty()) { exitNode = i; break; }
    }
    if (exitNode < 0) exitNode = n - 1;
    ipdom_[exitNode] = -1;

    std::vector<int> rpoRev;
    {
        std::set<int> visited;
        std::function<void(int)> dfsRev = [&](int u) {
            visited.insert(u);
            rpoRev.push_back(u);
            auto* blk = mba_->getBlock(u);
            if (blk) {
                for (int p : blk->predecessors) {
                    if (p >= 0 && p < n && !visited.count(p)) dfsRev(p);
                }
            }
        };
        dfsRev(exitNode);
    }
    changed = true;
    int iter = 0;
    while (changed && iter++ < 50) {
        changed = false;
        for (int b : rpoRev) {
            if (b == exitNode) continue;
            int newIpdom = -2;
            auto* blk = mba_->getBlock(b);
            if (blk) {
                for (int s : blk->successors) {
                    if (s < 0 || s >= n) continue;
                    if (ipdom_[s] == -2) continue;
                    if (newIpdom == -2) { newIpdom = s; continue; }
                    // Intersect in post-dominator tree (walk up BOTH chains)
                    {
                        int a = newIpdom, c = s;
                        std::set<int> ancestors;
                        while (a >= 0) {
                            ancestors.insert(a);
                            if (a == c) break;
                            a = ipdom_[a];
                        }
                        while (c >= 0) {
                            if (ancestors.count(c)) {
                                newIpdom = c;
                                break;
                            }
                            c = ipdom_[c];
                        }
                        if (c < 0) newIpdom = -1;
                    }
                }
            }
            if (newIpdom >= -1 && newIpdom != ipdom_[b]) {
                ipdom_[b] = newIpdom;
                changed = true;
            }
        }
    }

    // v20: Fix any remaining -2 post-dominator nodes
    for (int i = 0; i < n; i++) {
        if (ipdom_[i] == -2) {
            ipdom_[i] = -1;
        }
    }
}

int FixpointStructurer::findMergePointFast(int a, int b) {
    int n = (int)ipdom_.size();
    if (a < 0 || a >= n || b < 0 || b >= n) return -1;
    if (a == b) return a;
    // v7.0: Same algorithm as CFGStructurer::findMergePointFast — find the
    // nearest common post-dominator via pdom-tree ancestor walk.
    // Added cycle/self-loop guards for robustness.
    std::set<int> ancestors;
    int cur = a;
    int guard = n + 5;
    while (cur >= 0 && cur < n && guard-- > 0) {
        ancestors.insert(cur);
        int next = ipdom_[cur];
        if (next == cur) break;  // self-loop guard
        cur = next;
    }
    cur = b;
    guard = n + 5;
    while (cur >= 0 && cur < n && guard-- > 0) {
        if (ancestors.count(cur)) return cur;
        int next = ipdom_[cur];
        if (next == cur) break;  // self-loop guard
        cur = next;
    }
    return -1;
}

int FixpointStructurer::findMergePointBFS(int a, int b) {
    int n = mba_->numBlocks();
    std::map<int, int> distA;
    std::queue<int> q;
    q.push(a);
    distA[a] = 0;
    while (!q.empty()) {
        int cur = q.front(); q.pop();
        auto* blk = mba_->getBlock(cur);
        for (int next : blk->successors) {
            if (next < 0 || next >= n) continue;
            if (distA.count(next)) continue;
            distA[next] = distA[cur] + 1;
            q.push(next);
        }
    }
    std::map<int, int> distB;
    q.push(b);
    distB[b] = 0;
    int best = -1, bestDist = 999999;
    while (!q.empty()) {
        int cur = q.front(); q.pop();
        if (distA.count(cur)) {
            int d = distA[cur] + distB[cur];
            if (d < bestDist) { bestDist = d; best = cur; }
        }
        auto* blk = mba_->getBlock(cur);
        for (int next : blk->successors) {
            if (next < 0 || next >= n) continue;
            if (distB.count(next)) continue;
            distB[next] = distB[cur] + 1;
            q.push(next);
        }
    }
    return best;
}

// ════════════════════════════════════════════════════════════════════
// ════════════════════════════════════════════════════════════════════
// Rule 0: ruleFor — detect for-loop (init+cond+inc) patterns
// v11.3: New rule. Runs BEFORE ruleWhileDo.
// Reference: r2's for-loop detection in `rz_analysis_extract_for_loop`.
//
// Pattern: A for-loop has:
//   init:  A block before the loop header assigns a value to a register
//   cond:  The loop header's CBRANCH compares that register with a limit
//   inc:   The back edge (or a body block) increments/decrements that register
//
// If detected, we create a FOR region that includes the init, cond, and inc.
// ════════════════════════════════════════════════════════════════════
bool FixpointStructurer::ruleFor() {
    for (auto& loop : loops_) {
        if (loop.is_do_while) continue;

        int hdrNode = findNodeByBlock(loop.header);
        if (hdrNode < 0) continue;
        if (nodes_[hdrNode].type != REGION_BASIC_BLOCK) continue;

        auto* hdr = mba_->getBlock(loop.header);
        if (!hdr || !hdr->tail || hdr->tail->opcode != mc::OP_CBRANCH) continue;
        auto* cond = hdr->tail;

        // Condition must involve a register
        bool hasReg = false;
        int loopVar = -1;
        if (cond->l.isReg() && cond->r.isImm()) {
            loopVar = cond->l.mreg;
            hasReg = true;
        } else if (cond->r.isReg() && cond->l.isImm()) {
            loopVar = cond->r.mreg;
            hasReg = true;
        } else if (cond->l.isReg() && cond->r.isReg()) {
            loopVar = cond->l.mreg;  // reg-reg compare, use LHS
            hasReg = true;
        }
        if (!hasReg || loopVar < 0) continue;

        // Find init: a predecessor OUTSIDE the loop body that defines loopVar
        int initBlock = -1;
        mc::MicroInsn* initInsn = nullptr;
        for (int pred : hdr->predecessors) {
            if (pred < 0 || pred >= mba_->numBlocks()) continue;
            if (loop.body.count(pred)) continue;
            auto* predBlk = mba_->getBlock(pred);
            if (!predBlk) continue;
            // Scan backward to find the last definition of loopVar
            for (auto* insn = predBlk->head; insn; insn = insn->next) {
                if (insn->iprops & mc::IPROP_DEAD) continue;
                // v11.3: Accept MOV, LDC, or any ALU op that defines the loop var
                if (insn->def_mreg == loopVar) {
                    initInsn = insn;
                    initBlock = pred;
                }
            }
        }
        if (!initInsn || initBlock < 0) continue;

        // Find increment: a block in the loop body that modifies loopVar
        mc::MicroInsn* incInsn = nullptr;
        // First check the back-edge block
        int backBlk = loop.back_edge_from;
        auto* backBlock = mba_->getBlock(backBlk);
        if (backBlock) {
            for (auto* insn = backBlock->head; insn; insn = insn->next) {
                if (insn->iprops & mc::IPROP_DEAD) continue;
                if ((insn->opcode == mc::OP_ADD || insn->opcode == mc::OP_SUB ||
                     insn->opcode == mc::OP_AND || insn->opcode == mc::OP_OR ||
                     insn->opcode == mc::OP_XOR || insn->opcode == mc::OP_SHL) &&
                    insn->def_mreg == loopVar) {
                    incInsn = insn;
                    break;
                }
            }
        }
        // If not found, search all body blocks (excluding header)
        if (!incInsn) {
            for (int bid : loop.body) {
                if (bid == loop.header) continue;
                auto* blk = mba_->getBlock(bid);
                if (!blk) continue;
                for (auto* insn = blk->head; insn; insn = insn->next) {
                    if (insn->iprops & mc::IPROP_DEAD) continue;
                    if ((insn->opcode == mc::OP_ADD || insn->opcode == mc::OP_SUB) &&
                        insn->def_mreg == loopVar) {
                        incInsn = insn;
                        break;
                    }
                }
                if (incInsn) break;
            }
        }
        // v11.3: For loops without explicit inc are still valid while loops — skip
        if (!incInsn) continue;

        // At this point we have a for-loop pattern.
        // We need to collapse the init block, header, and body into a FOR region.
        int initNode = findNodeByBlock(initBlock);
        if (initNode < 0 || initNode == hdrNode) continue;

        // Collect body node IDs
        std::set<int> bodyNodes;
        bodyNodes.insert(initNode);
        bodyNodes.insert(hdrNode);
        for (int b : loop.body) {
            int nid = findNodeByBlock(b);
            if (nid >= 0 && nid != hdrNode) bodyNodes.insert(nid);
        }

        // Find the back-edge node
        int backNode = -1;
        for (int nid : bodyNodes) {
            if (nid == hdrNode || nid == initNode) continue;
            for (int s_blk : nodes_[nid].succs) {
                if (s_blk == loop.header) { backNode = nid; break; }
            }
            if (backNode >= 0) break;
        }

        // Collect exit blocks (block IDs)
        // v31.0: Filter out dead blocks from exit blocks.
        std::set<int> exitBlocks;
        for (int nid : bodyNodes) {
            for (int s_blk : nodes_[nid].succs) {
                if (!loop.body.count(s_blk)) {
                    auto* sBlk = mba_->getBlock(s_blk);
                    if (sBlk) {
                        bool isDead = false;
                        for (auto* insn = sBlk->head; insn; insn = insn->next) {
                            if (insn->iprops & mc::IPROP_DEAD) { isDead = true; break; }
                        }
                        if (isDead) continue;
                    }
                    exitBlocks.insert(s_blk);
                }
            }
        }
        for (int s : hdr->successors) {
            if (!loop.body.count(s)) {
                auto* sBlk = mba_->getBlock(s);
                if (sBlk) {
                    bool isDead = false;
                    for (auto* insn = sBlk->head; insn; insn = insn->next) {
                        if (insn->iprops & mc::IPROP_DEAD) { isDead = true; break; }
                    }
                    if (isDead) continue;
                }
                exitBlocks.insert(s);
            }
        }

        // Build FOR region
        auto forRegion = std::make_unique<Region>(REGION_FOR);
        forRegion->loop_condition = cond;
        forRegion->for_init = initInsn;
        forRegion->for_increment = incInsn;
        forRegion->header_block = loop.header;
        if (!loop.exits.empty())
            forRegion->exit_block = (loop.primary_exit >= 0) ? loop.primary_exit
                                                             : *loop.exits.begin();

        // Build body: all body nodes except header and init
        std::set<int> bodyWithoutHeaderInit = bodyNodes;
        bodyWithoutHeaderInit.erase(hdrNode);
        bodyWithoutHeaderInit.erase(initNode);

        auto bodySeq = std::make_unique<Region>(REGION_SEQUENCE);
        for (int nid : bodyWithoutHeaderInit) {
            if (nodes_[nid].region) {
                bodySeq->children.push_back(nodes_[nid].region->clone());
            } else {
                auto bb = std::make_unique<Region>(REGION_BASIC_BLOCK);
                bb->block_id = *nodes_[nid].blocks.begin();
                bb->start_addr = nodes_[nid].start_addr;
                bodySeq->children.push_back(std::move(bb));
            }
        }
        forRegion->loop_body = std::move(bodySeq);

        // Create new node for the collapsed FOR
        RNode newNode;
        newNode.id = (int)nodes_.size();
        newNode.type = REGION_FOR;
        newNode.region = std::move(forRegion);
        newNode.start_addr = mba_->getBlock(loop.header)->start_addr;
        for (int nid : bodyNodes)
            for (int b : nodes_[nid].blocks)
                newNode.blocks.insert(b);
        newNode.succs.assign(exitBlocks.begin(), exitBlocks.end());
        newNode.loop_header = loop.header;
        if (!loop.exits.empty())
            newNode.loop_exit = (loop.primary_exit >= 0) ? loop.primary_exit
                                                         : *loop.exits.begin();

        // Remove old nodes and add the new FOR node
        std::vector<RNode> newNodes;
        for (auto& node : nodes_)
            if (!bodyNodes.count(node.id))
                newNodes.push_back(std::move(node));
        newNodes.push_back(std::move(newNode));
        for (size_t i = 0; i < newNodes.size(); i++)
            newNodes[i].id = (int)i;
        nodes_ = std::move(newNodes);
        recomputeEdges();
        
        // v14.0: Remove this loop's header from loop_headers_ to prevent
        // the outer loop's safety check from blocking (same as ruleDoWhile).
        fprintf(stderr, "[DBG_FOR] collapsed for loop hdr=%d, removing from loop_headers_\n", loop.header);
        loop_headers_.erase(loop.header);
        return true;
    }
    return false;
}

// ════════════════════════════════════════════════════════════════════
// Rule 1: ruleDoWhile — collapse do-while loops
// 对标 Ghidra ruleBlockDoWhile:
// A do-while has a loop header, a body, and a back edge from a CBRANCH
// block that checks the condition and jumps back to the header.
// The header is the first block of the loop and has no CBRANCH exit.
// ════════════════════════════════════════════════════════════════════
bool FixpointStructurer::ruleDoWhile() {
    // v12.0 debug: dump node state at entry
    DBG_PRINT("[DBG_DW_ENTRY] nodes_=%zu, loops_=%zu\n", nodes_.size(), loops_.size());
    for (int h : {25, 17, 12}) {
        int nid = findNodeByBlock(h);
        DBG_PRINT("[DBG_DW_ENTRY] block%d -> node%d (type=%d)\n", h, nid, nid>=0?nodes_[nid].type:-1);
    }
    
    int dwCount = 0;
    fprintf(stderr, "[DBG_DW_START] iterating %zu loops\n", loops_.size());
    for (auto& loop : loops_) {
        dwCount++;
        DBG_PRINT("[DBG_DW_ITER] dwCount=%d loop.header=%d is_dw=%d\n", dwCount, loop.header, loop.is_do_while);
        if (!loop.is_do_while) continue;
        
        // Find the node containing the loop header
        int hdrNode = findNodeByBlock(loop.header);
        if (hdrNode < 0) { DBG_PRINT("[DBG_RULE_DEBUG] doWhile[%d]: header %d not found in nodes (%zu nodes)\n", dwCount, loop.header, nodes_.size()); continue; }
        
        // Verify: header node should be a basic block (not yet collapsed)
        if (nodes_[hdrNode].type != REGION_BASIC_BLOCK) { DBG_PRINT("[DBG_RULE_DEBUG] doWhile[%d]: hdr=%d hdrNode=%d type=%d (not BASIC_BLOCK), bodySize=%zu\n", dwCount, loop.header, hdrNode, nodes_[hdrNode].type, loop.body.size()); continue; }
        DBG_PRINT("[DBG_DW_ITER] hdr=%d -> node%d type=%d OK, bodySize=%zu\n", loop.header, hdrNode, nodes_[hdrNode].type, loop.body.size());
        
        // Collect all nodes that are part of this loop body
        std::set<int> bodyNodes;
        for (int b : loop.body) {
            int nid = findNodeByBlock(b);
            if (nid >= 0) bodyNodes.insert(nid);
        }
        // v5.9: Allow self-loops (bodyNodes.size() == 1)
        if (bodyNodes.empty()) continue;
        DBG_PRINT("[DBG_DW_BODY] hdr=%d bodyNodes.size=%zu\n", loop.header, bodyNodes.size());
        
        // v13.0: Safety check — if the body nodes include a loop header of
        // another loop, skip this loop to prevent consuming the outer loop
        // header. This is a critical guard for nested loop handling.
        //
        // v60.0: Also check if the other loop header is actually in the
        // loop.body (the original block-level body). The bodyNodes are
        // computed from loop.body using findNodeByBlock, but after the
        // fixpoint rules run (ruleCollapse, ruleSequence), nodes from the
        // loop body may be merged into the same node as another loop header.
        // In this case, the merged node contains the other loop header but
        // the loop.body does NOT contain it. We must check loop.body to
        // avoid incorrectly skipping the loop.
        {
            bool hasOtherLoopHeader = false;
            int otherHdr = -1;
            for (int nid : bodyNodes) {
                for (int b : nodes_[nid].blocks) {
                    // v60.0: Only flag if the other loop header is actually
                    // in the loop.body (original block-level body), not just
                    // in a merged node that happens to contain blocks from
                    // both the loop body and another loop header.
                    if (loop_headers_.count(b) && b != loop.header && loop.body.count(b)) {
                        hasOtherLoopHeader = true;
                        otherHdr = b;
                        break;
                    }
                }
                if (hasOtherLoopHeader) break;
            }
            if (hasOtherLoopHeader) {
                DBG_PRINT("[DBG_RULE_DEBUG] doWhile[%d]: hdr=%d SKIP — body contains other loop header hdr=%d, loop_headers_={", dwCount, loop.header, otherHdr);
                for (int h : loop_headers_) fprintf(stderr, "%d ", h);
                fprintf(stderr, "} bodyNodes={");
                for (int nid : bodyNodes) {
                    fprintf(stderr, "n%d[", nid);
                    for (int b : nodes_[nid].blocks) fprintf(stderr, "%d,", b);
                    fprintf(stderr, "] ");
                }
                fprintf(stderr, "}\n");
                continue;
            }
        }
        
        // v5.9: For self-loops, the header IS the back-edge node
        bool isSelfLoop = (loop.back_edge_from == loop.header);
        
        // Find the back-edge node (the one that has header block as successor)
        int backNode = -1;
        if (isSelfLoop) {
            backNode = hdrNode;
        } else {
            for (int nid : bodyNodes) {
                if (nid == hdrNode) continue;
                for (int s_blk : nodes_[nid].succs) {
                    // v14.1: The back-edge may be to the original header (block 7)
                    // rather than the corrected header (block 17). Check if the
                    // successor block is IN the loop body (which includes both
                    // the original and corrected headers).
                    if (loop.body.count(s_blk)) { backNode = nid; break; }
                }
                if (backNode >= 0) break;
            }
        }
        DBG_PRINT("[DBG_DW_BACK] hdr=%d backNode=%d isSelfLoop=%d\n", loop.header, backNode, isSelfLoop);
        if (backNode < 0) continue;
        
        // Find the CBRANCH instruction for the condition
        mc::MicroInsn* condInsn = nullptr;
        int backBlk = loop.back_edge_from;
        auto* backBlock = mba_->getBlock(backBlk);
        if (backBlock && backBlock->tail && backBlock->tail->opcode == mc::OP_CBRANCH) {
            condInsn = backBlock->tail;
        } else {
            for (int b : nodes_[backNode].blocks) {
                auto* blk = mba_->getBlock(b);
                if (blk->tail && blk->tail->opcode == mc::OP_CBRANCH) {
                    for (int s : blk->successors) {
                        if (nodes_[backNode].blocks.count(s) || s == loop.header) {
                            condInsn = blk->tail; break;
                        }
                    }
                }
                if (condInsn) break;
            }
        }
        
        // Collect exit blocks (block IDs, not node IDs)
        // v50.3: Use the original block-level successors from the microcode
        // array instead of the node-level succs. The node-level succs may
        // have been modified by fixpoint rules (e.g., ruleBlockCat merging
        // nodes), which can cause incorrect exit block identification.
        // Using the original block successors ensures we correctly identify
        // all loop exit blocks.
        // v31.0: Filter out dead blocks from exit blocks. Dead blocks
        // (e.g., b20 in JNI_OnLoad) are not valid successors and would
        // cause incorrect merge point detection in subsequent rules.
        std::set<int> exitBlocks;
        int numBlocks = mba_->numBlocks();
        for (int bid : loop.body) {
            auto* bBlk = mba_->getBlock(bid);
            if (!bBlk) continue;
            for (int s_blk : bBlk->successors) {
                if (s_blk < 0 || s_blk >= numBlocks) continue;
                if (loop.body.count(s_blk)) continue;
                // Skip dead blocks as exit blocks
                auto* sBlk = mba_->getBlock(s_blk);
                if (sBlk) {
                    bool isDead = false;
                    for (auto* insn = sBlk->head; insn; insn = insn->next) {
                        if (insn->iprops & mc::IPROP_DEAD) { isDead = true; break; }
                    }
                    if (isDead) continue;
                }
                exitBlocks.insert(s_blk);
            }
        }
        
        // Build the do-while region
        auto doWhileRegion = std::make_unique<Region>(REGION_DO_WHILE);
        doWhileRegion->loop_condition = condInsn;
        doWhileRegion->header_block = loop.header;
        doWhileRegion->is_post_test = true;
        if (!loop.exits.empty())
            // v7.0: Prefer the primary (tail-reachable) exit when available.
            doWhileRegion->exit_block = (loop.primary_exit >= 0) ? loop.primary_exit
                                                                 : *loop.exits.begin();
        
        // Build loop body from body nodes
        // v5.9: For self-loops, include the header in the body (its non-CBRANCH
        // instructions form the loop body). For normal do-while, exclude header.
        std::set<int> bodyWithoutHeader = bodyNodes;
        if (!isSelfLoop) {
            bodyWithoutHeader.erase(hdrNode);
        }
        
        auto bodySeq = std::make_unique<Region>(REGION_SEQUENCE);
        for (int nid : bodyWithoutHeader) {
            if (nodes_[nid].region) {
                bodySeq->children.push_back(
                    nodes_[nid].region->clone());
            } else {
                auto bb = std::make_unique<Region>(REGION_BASIC_BLOCK);
                bb->block_id = *nodes_[nid].blocks.begin();
                bb->start_addr = nodes_[nid].start_addr;
                bodySeq->children.push_back(std::move(bb));
            }
        }
        doWhileRegion->loop_body = std::move(bodySeq);

        // v50.3: Collapse fake do-while — but only if the back edge is
        // UNREACHABLE from the header. A return in a conditional branch
        // (e.g., the else-branch of an if-else inside the loop) does NOT
        // make the loop fake — the loop can still iterate on the then-branch.
        // The old code (v47.0) checked ALL body blocks for OP_RET, which
        // incorrectly collapsed real loops that contain a return in a
        // conditional path (e.g., JNI_OnLoad's outer loop).
        //
        // Correct approach: do a forward BFS from the loop header through
        // the body blocks. If the back edge source block is reachable, the
        // loop is real and should NOT be collapsed.
        // Ghidra reference: ruleCollapseBlock collapses do-while only when
        // all exits lead to return/goto — meaning the back edge condition
        // itself is unreachable.
        {
            bool isRealLoop = false;
            if (loop.back_edge_from >= 0) {
                // Forward BFS from header through body blocks
                std::set<int> visited;
                std::queue<int> q;
                q.push(loop.header);
                visited.insert(loop.header);
                while (!q.empty() && !isRealLoop) {
                    int cur = q.front(); q.pop();
                    auto* blk = mba_->getBlock(cur);
                    if (!blk) continue;
                    // If we reached the back edge source, the loop is real
                    if (cur == loop.back_edge_from) {
                        isRealLoop = true;
                        break;
                    }
                    // Only traverse within the loop body
                    int numBlocks = mba_->numBlocks();
                    for (int s : blk->successors) {
                        if (s < 0 || s >= numBlocks) continue;
                        if (!loop.body.count(s)) continue;
                        if (visited.count(s)) continue;
                        visited.insert(s);
                        q.push(s);
                    }
                }
            }
            if (!isRealLoop) {
                // v50.1: When collapsing a fake do-while loop, also remove
                // any dangling BREAK regions inside the loop body. These
                // BREAK nodes were created when GOTO-to-loop-exit edges
                // were converted to REGION_BREAK, but they are meaningless
                // once the loop is collapsed.
                auto stripBreakRegions = [](std::unique_ptr<Region>& r, auto& selfRef) -> void {
                    if (!r) return;
                    std::vector<std::unique_ptr<Region>> filtered;
                    for (auto& child : r->children) {
                        if (child->type == REGION_BREAK) {
                            continue;  // skip dangling BREAK
                        }
                        selfRef(child, selfRef);
                        filtered.push_back(std::move(child));
                    }
                    r->children = std::move(filtered);
                };
                if (doWhileRegion->loop_body)
                    stripBreakRegions(doWhileRegion->loop_body, stripBreakRegions);
                
                auto seq = std::make_unique<Region>(REGION_SEQUENCE);
                seq->children.push_back(std::move(doWhileRegion->loop_body));
                doWhileRegion = std::move(seq);  // Replace do-while with sequence
            }
        }

        // Create new node for the collapsed loop
        RNode newNode;
        newNode.id = (int)nodes_.size();
        newNode.type = REGION_DO_WHILE;
        newNode.region = std::move(doWhileRegion);
        newNode.start_addr = mba_->getBlock(loop.header)->start_addr;
        for (int nid : bodyNodes)
            for (int b : nodes_[nid].blocks)
                newNode.blocks.insert(b);
        newNode.succs.assign(exitBlocks.begin(), exitBlocks.end());
        newNode.loop_header = loop.header;
        if (!loop.exits.empty())
            // v7.0: Prefer the primary (tail-reachable) exit when available.
            newNode.loop_exit = (loop.primary_exit >= 0) ? loop.primary_exit
                                                         : *loop.exits.begin();
        
        // Remove old nodes and add new
        std::vector<RNode> newNodes;
        for (auto& node : nodes_)
            if (!bodyNodes.count(node.id))
                newNodes.push_back(std::move(node));
        newNodes.push_back(std::move(newNode));
        for (size_t i = 0; i < newNodes.size(); i++)
            newNodes[i].id = (int)i;
        nodes_ = std::move(newNodes);
        recomputeEdges();
        
        // v14.0: Remove this loop's header from loop_headers_ to prevent
        // the outer loop's safety check from blocking. When a nested loop
        // is collapsed, its header block is now part of a collapsed node.
        // The outer loop's bodyNodes will include this collapsed node, but
        // the safety check would see the inner loop's header in loop_headers_
        // and skip the outer loop unnecessarily. Removing the header here
        // allows the outer loop to be processed next.
        // v52.0: Also remove ALL loop headers that are inside the collapsed
        // region. When a while loop is collapsed inside a do-while body, the
        // while loop's header is removed from loop_headers_, but any OTHER
        // loop headers inside the while loop's body (e.g., a self-loop) are
        // NOT removed. This causes the outer do-while's safety check to see
        // the remaining loop header and skip the outer loop permanently.
        // Fix: scan all loop headers and remove any that are inside the
        // collapsed region of blocks.
        {
            std::set<int> collapsedBlocks = loop.body;
            collapsedBlocks.insert(loop.header);
            for (auto it = loop_headers_.begin(); it != loop_headers_.end(); ) {
                if (collapsedBlocks.count(*it)) {
                    fprintf(stderr, "[DBG_DW] removing loop header %d from loop_headers_ (inside collapsed region)\n", *it);
                    it = loop_headers_.erase(it);
                } else {
                    ++it;
                }
            }
        }
        // v14.1: Also mark the loop as processed (header = -1) so subsequent
        // iterations of ruleDoWhile don't try to process it again. Other rules
        // (blockCat, collapse, sequence) may merge the collapsed DO_WHILE node
        // into a BASIC_BLOCK node, which would cause findNodeByBlock(header)
        // to return a BASIC_BLOCK type and bypass the type check.
        loop.header = -1;
        return true;
    }
    return false;
}

// ════════════════════════════════════════════════════════════════════
// Rule 2: ruleWhileDo — collapse while loops
// 对标 Ghidra ruleBlockWhileDo:
// A while loop has a header with a CBRANCH that exits the loop.
// The body is the set of blocks reachable from the header's fall-through
// that eventually lead back to the header.
// ════════════════════════════════════════════════════════════════════
bool FixpointStructurer::ruleWhileDo() {
    (void)mba_;
    bool debugOnce = false;
    for (auto& loop : loops_) {
        if (loop.is_do_while) continue;
        
        int hdrNode = findNodeByBlock(loop.header);
        if (hdrNode < 0) { if (!debugOnce) { DBG_PRINT("[DBG_RULE_DEBUG] whileDo: header %d not found in nodes\n", loop.header); debugOnce = true; } continue; }
        if (nodes_[hdrNode].type != REGION_BASIC_BLOCK) { if (!debugOnce) { DBG_PRINT("[DBG_RULE_DEBUG] whileDo: hdrNode %d type=%d not BASIC_BLOCK\n", hdrNode, nodes_[hdrNode].type); debugOnce = true; } continue; }
        
        auto* hdr = mba_->getBlock(loop.header);
        if (!hdr || !hdr->tail || hdr->tail->opcode != mc::OP_CBRANCH) { if (!debugOnce) { DBG_PRINT("[DBG_RULE_DEBUG] whileDo: header %d no CBRANCH tail\n", loop.header); debugOnce = true; } continue; }
        
        // Determine which successor is the loop body entry and which is exit.
        // v60.0: Use forward BFS from each successor to find the body entry.
        // The old code relied on loop.body.count(s), but loop.body may not
        // include the actual body entry (e.g., when the body entry block is
        // not on the backward path from the back edge source to the header).
        // Use forward BFS: if the successor can reach the header via forward
        // edges, it's the body entry. If not, it's the loop exit.
        int bodyEntry = -1;
        int loopExit = -1;
        for (int s : hdr->successors) {
            if (s == loop.header) continue;  // skip self-loop
            // v60.0: Forward BFS from this successor to check if it can reach
            // the loop header (which means it's the body entry, not the exit).
            bool canReachHeader = false;
            {
                std::set<int> visited;
                std::queue<int> bq;
                bq.push(s);
                visited.insert(s);
                while (!bq.empty() && !canReachHeader) {
                    int cur = bq.front(); bq.pop();
                    auto* curBlk = mba_->getBlock(cur);
                    if (!curBlk) continue;
                    for (int ns : curBlk->successors) {
                        if (ns < 0 || ns >= mba_->numBlocks()) continue;
                        if (ns == loop.header) { canReachHeader = true; break; }
                        if (visited.count(ns)) continue;
                        visited.insert(ns);
                        bq.push(ns);
                    }
                }
            }
            if (canReachHeader) {
                bodyEntry = s;
            } else {
                loopExit = s;
            }
        }
        if (bodyEntry < 0) {
            if (!debugOnce) {
                DBG_PRINT("[DBG_RULE_DEBUG] whileDo: header %d no body entry, successors=[%d %d]\n",
                        loop.header, hdr->successors.size()>=1?hdr->successors[0]:-1,
                        hdr->successors.size()>=2?hdr->successors[1]:-1);
                debugOnce = true;
            }
            continue;
        }
        
        // v60.0: Collect body nodes using forward BFS from the body entry.
        // Traverse through successors until we reach a block that has the
        // header as a successor (the back edge). This is more robust than
        // using the pre-computed loop.body, which may be incomplete.
        std::set<int> bodyNodes;
        bodyNodes.insert(hdrNode);
        {
            std::set<int> visited;
            std::queue<int> bq;
            bq.push(bodyEntry);
            visited.insert(bodyEntry);
            // Add the body entry node to bodyNodes
            int beNode = findNodeByBlock(bodyEntry);
            if (beNode >= 0 && beNode != hdrNode) bodyNodes.insert(beNode);
            
            while (!bq.empty()) {
                int cur = bq.front(); bq.pop();
                auto* curBlk = mba_->getBlock(cur);
                if (!curBlk) continue;
                for (int ns : curBlk->successors) {
                    if (ns < 0 || ns >= mba_->numBlocks()) continue;
                    // If this successor goes back to the header, we've found
                    // the back edge. Don't traverse into the header.
                    if (ns == loop.header) continue;
                    if (visited.count(ns)) continue;
                    visited.insert(ns);
                    bq.push(ns);
                    // Add the node containing this block
                    int nid = findNodeByBlock(ns);
                    if (nid >= 0 && nid != hdrNode) bodyNodes.insert(nid);
                }
            }
        }
        
        // Find the back-edge node (successor block == loop.header)
        // v60.0: Use the forward BFS body
        int backNode = -1;
        for (int nid : bodyNodes) {
            if (nid == hdrNode) continue;
            for (int s_blk : nodes_[nid].succs) {
                if (s_blk == loop.header) { backNode = nid; break; }
            }
            if (backNode >= 0) break;
        }
        if (backNode < 0) { if (!debugOnce) { DBG_PRINT("[DBG_RULE_DEBUG] whileDo: no backNode for header %d, bodyNodes=", loop.header); for (int n : bodyNodes) fprintf(stderr, "%d ", n); fprintf(stderr, "\n"); debugOnce = true; } continue; }
        
        // Collect exit blocks (block IDs)
        // v60.0: Use the forward BFS body instead of loop.body
        // v31.0: Filter out dead blocks from exit blocks.
        std::set<int> exitBlocks;
        // Build a set of blocks in the forward BFS body for exit detection
        std::set<int> bodyBlocks;
        for (int nid : bodyNodes) {
            for (int b : nodes_[nid].blocks)
                bodyBlocks.insert(b);
        }
        for (int nid : bodyNodes) {
            for (int s_blk : nodes_[nid].succs) {
                if (!bodyBlocks.count(s_blk) && s_blk != loop.header) {
                    auto* sBlk = mba_->getBlock(s_blk);
                    if (sBlk) {
                        bool isDead = false;
                        for (auto* insn = sBlk->head; insn; insn = insn->next) {
                            if (insn->iprops & mc::IPROP_DEAD) { isDead = true; break; }
                        }
                        if (isDead) continue;
                    }
                    exitBlocks.insert(s_blk);
                }
            }
        }
        
        // Build while region
        auto whileRegion = std::make_unique<Region>(REGION_WHILE);
        whileRegion->loop_condition = hdr->tail;
        whileRegion->header_block = loop.header;
        if (!loop.exits.empty())
            // v7.0: Prefer the primary (tail-reachable) exit when available.
            whileRegion->exit_block = (loop.primary_exit >= 0) ? loop.primary_exit
                                                               : *loop.exits.begin();
        
        // Build body from non-header body nodes
        std::set<int> bodyWithoutHeader = bodyNodes;
        bodyWithoutHeader.erase(hdrNode);
        
        auto bodySeq = std::make_unique<Region>(REGION_SEQUENCE);
        for (int nid : bodyWithoutHeader) {
            if (nodes_[nid].region) {
                bodySeq->children.push_back(
                    nodes_[nid].region->clone());
            } else {
                auto bb = std::make_unique<Region>(REGION_BASIC_BLOCK);
                bb->block_id = *nodes_[nid].blocks.begin();
                bb->start_addr = nodes_[nid].start_addr;
                bodySeq->children.push_back(std::move(bb));
            }
        }
        whileRegion->loop_body = std::move(bodySeq);
        
        // Create new node
        RNode newNode;
        newNode.id = (int)nodes_.size();
        newNode.type = REGION_WHILE;
        newNode.region = std::move(whileRegion);
        newNode.start_addr = hdr->start_addr;
        for (int nid : bodyNodes)
            for (int b : nodes_[nid].blocks)
                newNode.blocks.insert(b);
        newNode.succs.assign(exitBlocks.begin(), exitBlocks.end());
        newNode.loop_header = loop.header;
        if (!loop.exits.empty())
            // v7.0: Prefer the primary (tail-reachable) exit when available.
            newNode.loop_exit = (loop.primary_exit >= 0) ? loop.primary_exit
                                                         : *loop.exits.begin();
        
        // Replace nodes
        std::vector<RNode> newNodes;
        for (auto& node : nodes_)
            if (!bodyNodes.count(node.id))
                newNodes.push_back(std::move(node));
        newNodes.push_back(std::move(newNode));
        for (size_t i = 0; i < newNodes.size(); i++)
            newNodes[i].id = (int)i;
        nodes_ = std::move(newNodes);
        
        recomputeEdges();
        
        // v14.0: Remove this loop's header from loop_headers_ to prevent
        // the outer loop's safety check from blocking (same as ruleDoWhile).
        // v52.0: Also remove ALL loop headers inside the collapsed region.
        {
            std::set<int> collapsedBlocks = loop.body;
            collapsedBlocks.insert(loop.header);
            for (auto it = loop_headers_.begin(); it != loop_headers_.end(); ) {
                if (collapsedBlocks.count(*it)) {
                    fprintf(stderr, "[DBG_WD] removing loop header %d from loop_headers_ (inside collapsed region)\n", *it);
                    it = loop_headers_.erase(it);
                } else {
                    ++it;
                }
            }
        }
        return true;
    }
    return false;
}

// ════════════════════════════════════════════════════════════════════
// Rule 3: ruleIfElse — collapse if-then-else (LOCAL pattern matching)
// v5.5: Rewritten to use local pattern matching like Ghidra.
// Old BFS approach swallowed entire branches incorrectly.
//
// Ghidra ruleBlockIfElse patterns:
//   Pattern 1 (if-then-else): A→B, A→C, B→D, C→D, B.pred={A}, C.pred={A}
//     → collapse A,B,C into if-else, successor = D
//   Pattern 2 (if-then): A→B, A→C, B→C, B.pred={A}
//     → collapse A,B into if-then, successor = C
//   Pattern 3 (if-then, negated): A→B, A→C, C→B, C.pred={A}
//     → collapse A,C into if-then (negated), successor = B
// ════════════════════════════════════════════════════════════════════
bool FixpointStructurer::ruleIfElse() {
    static int debugCount = 0;
    debugCount++;
    bool ifDebugOnce = false;
    for (size_t ni = 0; ni < nodes_.size(); ni++) {
        auto& node = nodes_[ni];
        // Must be a BASIC_BLOCK node with a CBRANCH
        if (node.type != REGION_BASIC_BLOCK) continue;
        if (node.succs.size() != 2) continue;

        // Find the CBRANCH block in this node
        int cbrBlock = -1;
        for (int b : node.blocks) {
            auto* blk = mba_->getBlock(b);
            if (blk && blk->tail && blk->tail->opcode == mc::OP_CBRANCH) {
                cbrBlock = b;
                break;
            }
        }
        if (cbrBlock < 0) continue;

        auto* blk = mba_->getBlock(cbrBlock);
        if (!blk || blk->successors.size() < 2) continue;

        int succTrue_blk = blk->successors[0];  // fall-through (condition true)
        int succFalse_blk = blk->successors[1]; // branch target (condition false)
        if (succTrue_blk == succFalse_blk) continue;

        int trueNode = findNodeByBlock(succTrue_blk);
        int falseNode = findNodeByBlock(succFalse_blk);
        if (trueNode < 0 || falseNode < 0) continue;
        if (trueNode == (int)ni || falseNode == (int)ni) continue;
        
        // v11.5: Skip if the CBRANCH block itself is a loop header, or
        // if either successor is a loop header. Loop headers must not be
        // consumed by ifElse, as they are handled by the loop rules
        // (doWhile, whileDo, for). Consuming a loop header here would
        // prevent the loop rules from matching it later.
        if (loop_headers_.count(cbrBlock) ||
            loop_headers_.count(succTrue_blk) ||
            loop_headers_.count(succFalse_blk)) {
            continue;
        }
        
        // v21: Debug first CBRANCH node (debug info)
        if (!ifDebugOnce) {
            fprintf(stderr, "[DBG_IFELSE] node %d cbr=%d succs=[%d->n%d, %d->n%d]\n",
                    (int)ni, cbrBlock, succTrue_blk, trueNode, succFalse_blk, falseNode);
            ifDebugOnce = true;
        }

        // Helper: build a region from a node
        auto nodeToRegionLambda = [&](int nid) -> std::unique_ptr<Region> {
            if (nid < 0) return nullptr;
            auto& n = nodes_[nid];
            if (n.region) return n.region->clone();
            auto bb = std::make_unique<Region>(REGION_BASIC_BLOCK);
            bb->block_id = *n.blocks.begin();
            bb->start_addr = n.start_addr;
            return bb;
        };

        // ── Pattern 1: if-then-else ──
        // A→B, A→C, B→D, C→D, B.pred={A}, C.pred={A}
        // Both B and C have exactly 1 successor, both point to the same block D
        if (trueNode != falseNode) {
            auto& tn = nodes_[trueNode];
            auto& fn = nodes_[falseNode];

            // Check if both branches have exactly 1 successor
            if (tn.succs.size() == 1 && fn.succs.size() == 1) {
                int tSucc_blk = tn.succs[0];
                int fSucc_blk = fn.succs[0];
                int tSuccNode = findNodeByBlock(tSucc_blk);
                int fSuccNode = findNodeByBlock(fSucc_blk);

                // Both successors must be the same node (the merge point)
                if (tSuccNode == fSuccNode && tSuccNode >= 0 && tSuccNode != (int)ni) {
                    // Check that B and C each have exactly 1 predecessor (A)
                    if (tn.preds.size() == 1 && fn.preds.size() == 1) {
                        // Debug: log the merge point
                        {
                            fprintf(stderr, "[DBG_P1] cbrNode=%d tSuccNode=%d fSuccNode=%d mergeNode=%d\n",
                                    (int)ni, trueNode, falseNode, tSuccNode);
                            fprintf(stderr, "[DBG_P1] mergeNode blocks={");
                            for (int b : nodes_[tSuccNode].blocks) fprintf(stderr, "%d ", b);
                            fprintf(stderr, "} regionType=%d\n",
                                    nodes_[tSuccNode].region ? (int)nodes_[tSuccNode].region->type : -1);
                        }
                        auto ifRegion = std::make_unique<Region>(REGION_IF_THEN_ELSE);
                        ifRegion->condition = blk->tail;
                        ifRegion->start_addr = blk->start_addr;
                        ifRegion->block_id = blk->block_id;  // v19: Set block_id for ifToStmt
                        ifRegion->then_region = nodeToRegionLambda(trueNode);
                        ifRegion->else_region = nodeToRegionLambda(falseNode);

                        // Collect collapsed nodes
                        std::set<int> collapsed = {(int)ni, trueNode, falseNode};
                        RNode newNode;
                        newNode.id = (int)nodes_.size();
                        newNode.type = REGION_IF_THEN_ELSE;
                        newNode.region = std::move(ifRegion);
                        newNode.start_addr = blk->start_addr;
                        for (int nid : collapsed)
                            for (int b : nodes_[nid].blocks)
                                newNode.blocks.insert(b);
                        newNode.succs.push_back(tSucc_blk);

                        std::vector<RNode> newNodes;
                        for (size_t j = 0; j < nodes_.size(); j++) {
                            if (collapsed.count((int)j)) continue;
                            newNodes.push_back(std::move(nodes_[j]));
                        }
                        newNodes.push_back(std::move(newNode));
                        for (size_t j = 0; j < newNodes.size(); j++)
                            newNodes[j].id = (int)j;
                        nodes_ = std::move(newNodes);
                        recomputeEdges();
                        return true;
                    }
                }
            }

            // ── Pattern 2: if-then (true branch falls through to false) ──
            // A→B, A→C, B→C, B.pred={A}
            // True branch has 1 successor = false branch's block
            if (tn.succs.size() == 1 && tn.succs[0] == succFalse_blk) {
                if (tn.preds.size() == 1) {
                    auto ifRegion = std::make_unique<Region>(REGION_IF_THEN);
                    ifRegion->condition = blk->tail;
                    ifRegion->start_addr = blk->start_addr;
                    ifRegion->block_id = blk->block_id;  // v19: Set block_id for ifToStmt
                    ifRegion->then_region = nodeToRegionLambda(trueNode);

                    std::set<int> collapsed = {(int)ni, trueNode};
                    RNode newNode;
                    newNode.id = (int)nodes_.size();
                    newNode.type = REGION_IF_THEN;
                    newNode.region = std::move(ifRegion);
                    newNode.start_addr = blk->start_addr;
                    for (int nid : collapsed)
                        for (int b : nodes_[nid].blocks)
                            newNode.blocks.insert(b);
                    newNode.succs.push_back(succFalse_blk);

                    std::vector<RNode> newNodes;
                    for (size_t j = 0; j < nodes_.size(); j++) {
                        if (collapsed.count((int)j)) continue;
                        newNodes.push_back(std::move(nodes_[j]));
                    }
                    newNodes.push_back(std::move(newNode));
                    for (size_t j = 0; j < newNodes.size(); j++)
                        newNodes[j].id = (int)j;
                    nodes_ = std::move(newNodes);
                    recomputeEdges();
                    return true;
                }
            }

            // ── Pattern 3: if-then (false branch falls through to true) ──
            // A→B, A→C, C→B, C.pred={A}
            // False branch has 1 successor = true branch's block
            if (fn.succs.size() == 1 && fn.succs[0] == succTrue_blk) {
                if (fn.preds.size() == 1) {
                    auto ifRegion = std::make_unique<Region>(REGION_IF_THEN);
                    ifRegion->condition = blk->tail;
                    ifRegion->condition_negated = true;
                    ifRegion->start_addr = blk->start_addr;
                    ifRegion->then_region = nodeToRegionLambda(falseNode);

                    std::set<int> collapsed = {(int)ni, falseNode};
                    RNode newNode;
                    newNode.id = (int)nodes_.size();
                    newNode.type = REGION_IF_THEN;
                    newNode.region = std::move(ifRegion);
                    newNode.start_addr = blk->start_addr;
                    for (int nid : collapsed)
                        for (int b : nodes_[nid].blocks)
                            newNode.blocks.insert(b);
                    newNode.succs.push_back(succTrue_blk);

                    std::vector<RNode> newNodes;
                    for (size_t j = 0; j < nodes_.size(); j++) {
                        if (collapsed.count((int)j)) continue;
                        newNodes.push_back(std::move(nodes_[j]));
                    }
                    newNodes.push_back(std::move(newNode));
                    for (size_t j = 0; j < newNodes.size(); j++)
                        newNodes[j].id = (int)j;
                    nodes_ = std::move(newNodes);
                    recomputeEdges();
                    return true;
                }
            }
        }

        if (trueNode != falseNode) {
            // ── Pattern 4.5: One branch is a RETURN/terminal block ──
            // v24.2: 对标 Ghidra CollapseStructure::ActionBlockStructure
            // A→B, A→C, where one branch (B or C) is a RETURN block.
            // This is a common pattern: if (cond) { ...; return; } else { ... }
            // The RETURN branch has 0 successors (no merge point needed).
            // Ghidra handles this by allowing if-else with one terminal branch
            // that does not converge back to the merge point.
            {
                // Check if true branch is a RETURN block (0 successors or OP_RET)
                auto& tn = nodes_[trueNode];
                auto& fn = nodes_[falseNode];
                bool tnIsReturn = false, fnIsReturn = false;
                // Check if the node has no successors (dead end)
                if (tn.succs.empty()) tnIsReturn = true;
                if (fn.succs.empty()) fnIsReturn = true;
                // Also check if the block ends with OP_RET
                if (!tnIsReturn) {
                    for (int b : tn.blocks) {
                        auto* blk = mba_->getBlock(b);
                        if (blk && blk->tail && blk->tail->opcode == mc::OP_RET) {
                            tnIsReturn = true; break;
                        }
                    }
                }
                if (!fnIsReturn) {
                    for (int b : fn.blocks) {
                        auto* blk = mba_->getBlock(b);
                        if (blk && blk->tail && blk->tail->opcode == mc::OP_RET) {
                            fnIsReturn = true; break;
                        }
                    }
                }
                // One branch is RETURN, the other is NOT
                if (tnIsReturn && !fnIsReturn) {
                    // True branch is RETURN — structure as if-then-else
                    // with RETURN as the then branch
                    auto ifRegion = std::make_unique<Region>(REGION_IF_THEN_ELSE);
                    ifRegion->condition = blk->tail;
                    ifRegion->start_addr = blk->start_addr;
                    ifRegion->block_id = blk->block_id;
                    ifRegion->then_region = nodeToRegionLambda(trueNode);
                    ifRegion->else_region = nodeToRegionLambda(falseNode);
                    
                    std::set<int> collapsed = {(int)ni, trueNode, falseNode};
                    RNode newNode;
                    newNode.id = (int)nodes_.size();
                    newNode.type = REGION_IF_THEN_ELSE;
                    newNode.region = std::move(ifRegion);
                    newNode.start_addr = blk->start_addr;
                    for (int nid : collapsed)
                        for (int b : nodes_[nid].blocks)
                            newNode.blocks.insert(b);
                    // The if-else region has no successor (the RETURN branch is terminal)
                    // But the else branch's successor(s) should be the region's successors
                    for (int s : fn.succs) {
                        newNode.succs.push_back(s);
                    }
                    
                    std::vector<RNode> newNodes;
                    for (size_t j = 0; j < nodes_.size(); j++) {
                        if (collapsed.count((int)j)) continue;
                        newNodes.push_back(std::move(nodes_[j]));
                    }
                    newNodes.push_back(std::move(newNode));
                    for (size_t j = 0; j < newNodes.size(); j++)
                        newNodes[j].id = (int)j;
                    nodes_ = std::move(newNodes);
                    recomputeEdges();
                    return true;
                }
                if (fnIsReturn && !tnIsReturn) {
                    // False branch is RETURN — structure as if-then-else
                    // with RETURN as the else branch
                    auto ifRegion = std::make_unique<Region>(REGION_IF_THEN_ELSE);
                    ifRegion->condition = blk->tail;
                    ifRegion->start_addr = blk->start_addr;
                    ifRegion->block_id = blk->block_id;
                    ifRegion->then_region = nodeToRegionLambda(trueNode);
                    ifRegion->else_region = nodeToRegionLambda(falseNode);
                    
                    std::set<int> collapsed = {(int)ni, trueNode, falseNode};
                    RNode newNode;
                    newNode.id = (int)nodes_.size();
                    newNode.type = REGION_IF_THEN_ELSE;
                    newNode.region = std::move(ifRegion);
                    newNode.start_addr = blk->start_addr;
                    for (int nid : collapsed)
                        for (int b : nodes_[nid].blocks)
                            newNode.blocks.insert(b);
                    // The if-else region has no successor (the RETURN branch is terminal)
                    // But the then branch's successor(s) should be the region's successors
                    for (int s : tn.succs) {
                        newNode.succs.push_back(s);
                    }
                    
                    std::vector<RNode> newNodes;
                    for (size_t j = 0; j < nodes_.size(); j++) {
                        if (collapsed.count((int)j)) continue;
                        newNodes.push_back(std::move(nodes_[j]));
                    }
                    newNodes.push_back(std::move(newNode));
                    for (size_t j = 0; j < newNodes.size(); j++)
                        newNodes[j].id = (int)j;
                    nodes_ = std::move(newNodes);
                    recomputeEdges();
                    return true;
                }
            }
            
            // ── Pattern 4: Complex if-else with BFS-based body collection ──
            // A→B, A→C, where B and C have complex internal structure
            // with multiple exits. Use BFS from both branches to find the
            // merge point. v21: Skips merge point candidates that are inside
            // a loop body (but not loop headers), since these are side exits
            // of inner branches, not true merge points.
            // Reference: Ghidra ruleBlockIfElse handles this by collecting
            // all blocks that are post-dominated by the branch target.
            
            // v21: Build a set of nodes that are in loop bodies but NOT loop
            // headers. These are "side exit" nodes: they are reachable from
            // the true branch through a side exit (e.g., b9→b21 inside the
            // loop), and also reachable from the false branch through the
            // loop structure. Such nodes must not be identified as merge
            // points because they are not the true convergence point of
            // the two branches.
            std::set<int> loopBodyNodes;
            for (auto& loop : loops_) {
                for (int b : loop.body) {
                    int nid = findNodeByBlock(b);
                    if (nid >= 0 && !loop_headers_.count(b)) {
                        loopBodyNodes.insert(nid);
                    }
                }
            }
            
            // BFS from true branch to find all reachable nodes
            std::set<int> thenNodes;
            std::set<int> thenVisited;
            std::queue<int> tq;
            tq.push(trueNode);
            thenVisited.insert(trueNode);
            thenNodes.insert(trueNode);
            bool foundMerge = false;
            int mergePoint = -1;
            int mergeIter = 0;
            
            // v27.0: Check if the true branch target is a collapsed loop region.
            // If so, the loop region is the merge point — the if-else ends at the
            // loop, and the loop body should not be absorbed into the if-else branch.
            if (nodes_[trueNode].region) {
                auto rt = nodes_[trueNode].region->type;
                if (rt == REGION_DO_WHILE || rt == REGION_WHILE || rt == REGION_FOR) {
                    foundMerge = true;
                    mergePoint = trueNode;
                    // Do NOT add to thenNodes (it's a loop, not part of the if-else)
                    thenNodes.erase(trueNode);
                }
            }
            
            // v33.0: Don't stop the while loop when foundMerge is true.
            // Continue processing the queue to collect all nodes that are
            // reachable from the true branch (e.g., b10-b16 which are between
            // b9 and the merge point). Previously, the while loop exited
            // immediately when foundMerge was set to true, causing blocks
            // b10-b16 to be lost from the then branch.
            while (!tq.empty()) {
                int cur = tq.front(); tq.pop();
                if (cur < 0 || cur >= (int)nodes_.size()) continue;
                // v33.0: Skip the merge point node if found — don't traverse
                // into it (the merge point is the end of the if-else structure).
                if (foundMerge && cur == mergePoint) continue;
                for (int s_blk : nodes_[cur].succs) {
                    int sn = findNodeByBlock(s_blk);
                    if (sn < 0 || sn == (int)ni || thenVisited.count(sn)) continue;
                    
                    // v34.0: Check if the BFS candidate node has the same CBRANCH
                    // condition as the current node. If so, the candidate node is a
                    // separate if-else structure that should NOT be absorbed into the
                    // current if-else's then branch. This prevents infinite nesting
                    // of identical if-else conditions (e.g., if(x>30){ if(x>30){...} }).
                    // Reference: Ghidra's ruleBlockIfElse handles this by checking
                    // that the merge point is not a CBRANCH with the same condition.
                    {
                        auto* curBlk = mba_->getBlock(cbrBlock);
                        auto* candBlk = mba_->getBlock(s_blk);
                        if (curBlk && curBlk->tail && curBlk->tail->opcode == mc::OP_CBRANCH &&
                            candBlk && candBlk->tail && candBlk->tail->opcode == mc::OP_CBRANCH) {
                            auto* curInsn = curBlk->tail;
                            auto* candInsn = candBlk->tail;
                            bool sameCond = (curInsn->cond == candInsn->cond);
                            bool sameL = false;
                            if (curInsn->l.isReg() && candInsn->l.isReg()) {
                                sameL = (curInsn->l.mreg == candInsn->l.mreg &&
                                         curInsn->l.ssa_ver == candInsn->l.ssa_ver);
                            } else if (!curInsn->l.isReg() && !candInsn->l.isReg()) {
                                sameL = (curInsn->l.imm == candInsn->l.imm);
                            }
                            bool sameR = false;
                            if (curInsn->r.isReg() && candInsn->r.isReg()) {
                                sameR = (curInsn->r.mreg == candInsn->r.mreg &&
                                         curInsn->r.ssa_ver == candInsn->r.ssa_ver);
                            } else if (!curInsn->r.isReg() && !candInsn->r.isReg()) {
                                sameR = (curInsn->r.imm == candInsn->r.imm);
                            }
                            if (sameCond && sameL && sameR) {
                                fprintf(stderr, "[DBG_P4] v34.0: node sn=%d has SAME CBRANCH cond as cbrNode=%d (b%d), skipping\n",
                                        sn, (int)ni, cbrBlock);
                                thenVisited.insert(sn);
                                // Do NOT add to thenNodes (it's a separate if-else structure)
                                // But continue BFS from it to find the actual merge point
                                tq.push(sn);
                                continue;  // skip to next successor
                            }
                        }
                    }
                    
                    // Check if this node is reachable from the false branch
                    // (i.e., it's a merge point candidate). v21: Skip nodes
                    // that are in loop bodies (side exits), as they are not
                    // true merge points. Also skip adding them to thenNodes
                    // since they are not part of the unique if-else branch.
                    // v26.0: LOOP BODY NODES CAN BE MERGE POINTS. Post-loop
                    // blocks that are inside a loop body (e.g., a do-while
                    // after the if-else structure) are reachable from both
                    // the true and false branches. They must be identified as
                    // merge points so the if-else structure ends cleanly and
                    // the post-loop code is placed AFTER the if-else.
                    // Previously, loop body nodes were completely skipped as
                    // merge point candidates, causing the entire post-loop
                    // code to be absorbed into the else branch.
                    bool isLoopBodyNode = loopBodyNodes.count(sn);
                    // v28.0: ALWAYS skip loop body nodes as merge points,
                    // regardless of whether they're reachable from the
                    // false branch. The merge point must be the loop
                    // header (or a block outside the loop), not a block
                    // inside the loop body.
                    // 
                    // Previously, only loop body nodes NOT reachable from
                    // the false branch were skipped. Loop body nodes that
                    // WERE reachable from the false branch (like b21, the
                    // "Failed to find init" section inside the loop) were
                    // incorrectly identified as merge points, causing the
                    // entire loop body to be absorbed into the if-else branch.
                    if (isLoopBodyNode) {
                        // v31.0: Check if this loop body node is actually a
                        // collapsed loop region (DO_WHILE, WHILE, FOR). If so,
                        // use it as the merge point instead of skipping it.
                        // The loop region is the correct merge point because
                        // both branches converge at the loop header, and the
                        // loop body should not be absorbed into the if-else.
                        bool isCollapsedLoop = false;
                        if (nodes_[sn].region) {
                            auto rt = nodes_[sn].region->type;
                            if (rt == REGION_DO_WHILE || rt == REGION_WHILE || rt == REGION_FOR) {
                                isCollapsedLoop = true;
                            }
                        }
                        if (isCollapsedLoop) {
                            fprintf(stderr, "[DBG_P4] loop body node sn=%d is collapsed loop, using as merge point!\n", sn);
                            foundMerge = true;
                            mergePoint = sn;
                            // v33.0: Don't break the for loop — continue processing
                            // remaining successors so they get added to thenNodes.
                            // Previously, break caused b10-b16 to be lost from the
                            // then branch when the collapsed loop was reached via a
                            // different path (e.g., b21 -> collapsed loop), because
                            // b10 was never processed as a successor of b9.
                            // Don't push to queue — we've found the merge point and
                            // don't need to traverse into the collapsed loop.
                            continue;
                        }
                        thenVisited.insert(sn);
                        // Do NOT add to thenNodes (these are loop body nodes,
                        // not part of the unique if-else branch)
                        tq.push(sn);
                        continue;
                    }
                    // This node is NOT a loop body node. Check if it's
                    // reachable from the false branch (i.e., it's a merge
                    // point candidate).
                    bool reachableFromFalse = false;
                    {
                        std::set<int> fwdVisited;
                        std::queue<int> fwdQ;
                        fwdQ.push(falseNode);
                        fwdVisited.insert(falseNode);
                        while (!fwdQ.empty() && !reachableFromFalse) {
                            int fcur = fwdQ.front(); fwdQ.pop();
                            if (fcur == sn) { reachableFromFalse = true; break; }
                            for (int fs_blk : nodes_[fcur].succs) {
                                int fsn = findNodeByBlock(fs_blk);
                                if (fsn >= 0 && !fwdVisited.count(fsn)) {
                                    fwdVisited.insert(fsn);
                                    fwdQ.push(fsn);
                                }
                            }
                        }
                    }
                    if (reachableFromFalse) {
                        // Found a merge point candidate. v22.0: Check if
                        // this merge point is a loop header. If it is, skip
                        // it — loop headers must be handled by the loop rules
                        // (doWhile/whileDo/for), not by if-else. Consuming a
                        // loop header here would prevent the loop from being
                        // structured, causing the entire loop body to be
                        // flattened into an if-else branch.
                        bool isLoopHeader = false;
                        for (int b : nodes_[sn].blocks) {
                            if (loop_headers_.count(b)) {
                                isLoopHeader = true;
                                break;
                            }
                        }
                        // v27.0: Also check if the node is a collapsed loop
                        // region. After the loop is collapsed, the loop header
                        // is removed from loop_headers_ (to prevent the outer
                        // loop's safety check from blocking). So we need to
                        // check the node type as well.
                        if (!isLoopHeader && nodes_[sn].region) {
                            auto rt = nodes_[sn].region->type;
                            if (rt == REGION_DO_WHILE || rt == REGION_WHILE || rt == REGION_FOR) {
                                isLoopHeader = true;
                            }
                        }
                        fprintf(stderr, "[DBG_P4] cbrNode=%d sn=%d blocks={", (int)ni, sn);
                        for (int b : nodes_[sn].blocks) fprintf(stderr, "%d ", b);
                        fprintf(stderr, "} isLoopHdr=%d isLoopBody=%d regionType=%d\n",
                                isLoopHeader, (int)isLoopBodyNode,
                                nodes_[sn].region ? (int)nodes_[sn].region->type : -1);
                        if (isLoopHeader) {
                            // v27.0: Use the loop header as the merge point.
                            // The if-else region ends at the loop header, and the
                            // loop header is the start of the next region (loop).
                            // Previously, we continued BFS from the loop header,
                            // which caused the entire loop body (and its exit
                            // blocks) to be absorbed into the if-else branch.
                            // This is incorrect — the loop body is a separate
                            // control flow structure, not part of the if-else.
                            fprintf(stderr, "[DBG_P4] using loop header node%d as merge point!\n", sn);
                            foundMerge = true;
                            mergePoint = sn;
                            // Do NOT add the loop header to thenNodes (it's not
                            // part of the if-else branch — it's the start of
                            // the loop).
                            break;
                        }
                        // v30.0: Check if the merge point candidate is a
                        // CBRANCH node. If it is, the BFS has found an inner
                        // IF_ELSE condition as the merge point, which is
                        // incorrect — the actual merge point is the merge
                        // point of the inner structure (the post-dominator
                        // of the inner CBRANCH). Follow the post-dominator
                        // chain to find the actual merge point.
                        bool isCbranchNode = false;
                        int cbrBlockId = -1;
                        for (int b : nodes_[sn].blocks) {
                            auto* blk2 = mba_->getBlock(b);
                            if (blk2 && blk2->tail && blk2->tail->opcode == mc::OP_CBRANCH) {
                                isCbranchNode = true;
                                cbrBlockId = b;
                                break;
                            }
                        }
                        // Also check if the node is a collapsed IF_THEN or
                        // IF_THEN_ELSE region (previously collapsed inner IF).
                        if (!isCbranchNode && nodes_[sn].region) {
                            auto rt = nodes_[sn].region->type;
                            if (rt == REGION_IF_THEN || rt == REGION_IF_THEN_ELSE) {
                                isCbranchNode = true;
                                // For collapsed IF_ELSE regions, use the
                                // region's block_id (the CBRANCH block).
                                cbrBlockId = nodes_[sn].region->block_id;
                            }
                        }
                        if (isCbranchNode && cbrBlockId >= 0) {
                            fprintf(stderr, "[DBG_CBR] merge point sn=%d is CBRANCH b%d, following post-dominator chain\n",
                                    sn, cbrBlockId);
                            // Follow the post-dominator chain to find the
                            // actual merge point (the first non-CBRANCH
                            // post-dominator).
                            int pdom = mba_->getBlock(cbrBlockId)->pdom_parent;
                            int pdomChainIter = 0;
                            while (pdom >= 0 && pdomChainIter < 20) {
                                auto* pdomBlk = mba_->getBlock(pdom);
                                if (!pdomBlk) break;
                                bool isPdomCbr = (pdomBlk->tail && pdomBlk->tail->opcode == mc::OP_CBRANCH);
                                if (!isPdomCbr) {
                                    // Found the actual merge point block.
                                    // Check if this block's node is reachable
                                    // from both branches.
                                    int pdomNode = findNodeByBlock(pdom);
                                    if (pdomNode >= 0) {
                                        fprintf(stderr, "[DBG_CBR] merge point sn=%d is CBRANCH b%d, following post-dominator chain to b%d (node%d)\n",
                                                sn, cbrBlockId, pdom, pdomNode);
                                        // Check if the merge point is a loop header
                                        bool isLoopHdr = false;
                                        for (int blkId : nodes_[pdomNode].blocks) {
                                            if (loop_headers_.count(blkId)) {
                                                isLoopHdr = true;
                                                break;
                                            }
                                        }
                                        if (!isLoopHdr && nodes_[pdomNode].region) {
                                            auto rt = nodes_[pdomNode].region->type;
                                            if (rt == REGION_DO_WHILE || rt == REGION_WHILE || rt == REGION_FOR) {
                                                isLoopHdr = true;
                                            }
                                        }
                                        if (isLoopHdr) {
                                            foundMerge = true;
                                            mergePoint = pdomNode;
                                            break;
                                        }
                                        // Check if the candidate is still a CBRANCH
                                        // (shouldn't happen since we already
                                        // checked, but be safe)
                                        bool stillCbr = false;
                                        for (int blkId : nodes_[pdomNode].blocks) {
                                            auto* blk3 = mba_->getBlock(blkId);
                                            if (blk3 && blk3->tail && blk3->tail->opcode == mc::OP_CBRANCH) {
                                                stillCbr = true;
                                                break;
                                            }
                                        }
                                        if (!stillCbr) {
                                            // Use the post-dominator node as the merge point
                                            foundMerge = true;
                                            mergePoint = pdomNode;
                                            break;
                                        }
                                    }
                                    break;
                                }
                                pdom = pdomBlk->pdom_parent;
                                pdomChainIter++;
                            }
                            if (foundMerge) break;
                            // If the post-dominator chain didn't find a valid
                            // merge point, skip this CBRANCH node and continue
                            // the BFS to find the actual merge point.
                            thenVisited.insert(sn);
                            tq.push(sn);
                            continue;
                        }
                        // Found the merge point!
                        fprintf(stderr, "[DBG_P4] merge point found: node%d blocks={", sn);
                        for (int b : nodes_[sn].blocks) fprintf(stderr, "%d ", b);
                        fprintf(stderr, "}\n");
                        foundMerge = true;
                        mergePoint = sn;
                        break;
                    }
                    thenVisited.insert(sn);
                    thenNodes.insert(sn);
                    tq.push(sn);
                }
                mergeIter++;
                if (mergeIter > 1000) {  // Safety limit
                    break;
                }
            }
            fprintf(stderr, "[DBG_P4] after BFS: foundMerge=%d mergePoint=%d thenNodes.size=%zu\n",
                    (int)foundMerge, mergePoint, thenNodes.size());
            
            if (foundMerge && mergePoint >= 0) {
                // BFS from false branch, stopping at merge point
                // v28.0: Also skip loop body nodes (same as the true branch BFS),
                // to prevent loop body blocks from being absorbed into the else
                // branch. The merge point is the loop header (or a block outside
                // the loop), and loop body blocks are not part of the if-else.
                std::set<int> elseNodes;
                std::set<int> elseVisited;
                std::queue<int> eq;
                eq.push(falseNode);
                elseVisited.insert(falseNode);
                elseNodes.insert(falseNode);
                while (!eq.empty()) {
                    int cur = eq.front(); eq.pop();
                    if (cur < 0 || cur >= (int)nodes_.size()) continue;
                    for (int s_blk : nodes_[cur].succs) {
                        int sn = findNodeByBlock(s_blk);
                        if (sn < 0 || sn == (int)ni || sn == mergePoint || elseVisited.count(sn)) continue;
                        // v28.0: Skip loop body nodes (they are not part of the
                        // if-else branches). But still traverse through them to
                        // reach the merge point.
                        if (loopBodyNodes.count(sn)) {
                            elseVisited.insert(sn);
                            eq.push(sn);
                            continue;
                        }
                        // v34.0: Check if the false branch candidate node has the
                        // same CBRANCH condition as the current node. If so, skip
                        // adding it to elseNodes (it's a separate if-else structure).
                        {
                            auto* curBlk = mba_->getBlock(cbrBlock);
                            auto* candBlk = mba_->getBlock(s_blk);
                            if (curBlk && curBlk->tail && curBlk->tail->opcode == mc::OP_CBRANCH &&
                                candBlk && candBlk->tail && candBlk->tail->opcode == mc::OP_CBRANCH) {
                                auto* curInsn = curBlk->tail;
                                auto* candInsn = candBlk->tail;
                                bool sameCond = (curInsn->cond == candInsn->cond);
                                bool sameL = false;
                                if (curInsn->l.isReg() && candInsn->l.isReg()) {
                                    sameL = (curInsn->l.mreg == candInsn->l.mreg &&
                                             curInsn->l.ssa_ver == candInsn->l.ssa_ver);
                                } else if (!curInsn->l.isReg() && !candInsn->l.isReg()) {
                                    sameL = (curInsn->l.imm == candInsn->l.imm);
                                }
                                bool sameR = false;
                                if (curInsn->r.isReg() && candInsn->r.isReg()) {
                                    sameR = (curInsn->r.mreg == candInsn->r.mreg &&
                                             curInsn->r.ssa_ver == candInsn->r.ssa_ver);
                                } else if (!curInsn->r.isReg() && !candInsn->r.isReg()) {
                                    sameR = (curInsn->r.imm == candInsn->r.imm);
                                }
                                if (sameCond && sameL && sameR) {
                                    fprintf(stderr, "[DBG_P4] v34.0: else BFS node sn=%d has SAME CBRANCH cond as cbrNode=%d (b%d), skipping\n",
                                            sn, (int)ni, cbrBlock);
                                    elseVisited.insert(sn);
                                    eq.push(sn);
                                    continue;
                                }
                            }
                        }
                        elseVisited.insert(sn);
                        elseNodes.insert(sn);
                        eq.push(sn);
                    }
                }
                
                // Remove any nodes that are in both sets (shared nodes)
                std::set<int> shared;
                for (int nid : thenNodes) {
                    if (elseNodes.count(nid)) shared.insert(nid);
                }
                for (int nid : shared) {
                    thenNodes.erase(nid);
                    elseNodes.erase(nid);
                }
                
                fprintf(stderr, "[DBG_P4] thenNodes: ");
                for (int nid : thenNodes) {
                    fprintf(stderr, "n%d{b", nid);
                    for (int b : nodes_[nid].blocks) fprintf(stderr, "%d ", b);
                    fprintf(stderr, "} ");
                }
                fprintf(stderr, "\n[DBG_P4] elseNodes: ");
                for (int nid : elseNodes) {
                    fprintf(stderr, "n%d{b", nid);
                    for (int b : nodes_[nid].blocks) fprintf(stderr, "%d ", b);
                    fprintf(stderr, "} ");
                }
                fprintf(stderr, "\n");
                
                // Build the then region as a sequence of nodes
                auto thenSeq = std::make_unique<Region>(REGION_SEQUENCE);
                for (int nid : thenNodes) {
                    if (nodes_[nid].region)
                        addRegionFlatten(thenSeq, nodes_[nid].region->clone());
                    else {
                        auto bb = std::make_unique<Region>(REGION_BASIC_BLOCK);
                        bb->block_id = *nodes_[nid].blocks.begin();
                        bb->start_addr = nodes_[nid].start_addr;
                        thenSeq->children.push_back(std::move(bb));
                    }
                }
                
                // Build the else region as a sequence of nodes
                auto elseSeq = std::make_unique<Region>(REGION_SEQUENCE);
                for (int nid : elseNodes) {
                    if (nodes_[nid].region)
                        addRegionFlatten(elseSeq, nodes_[nid].region->clone());
                    else {
                        auto bb = std::make_unique<Region>(REGION_BASIC_BLOCK);
                        bb->block_id = *nodes_[nid].blocks.begin();
                        bb->start_addr = nodes_[nid].start_addr;
                        elseSeq->children.push_back(std::move(bb));
                    }
                }
                
                // Create the IF_THEN_ELSE region
                auto ifRegion = std::make_unique<Region>(REGION_IF_THEN_ELSE);
                ifRegion->condition = blk->tail;
                ifRegion->start_addr = blk->start_addr;
                ifRegion->block_id = blk->block_id;  // Set block_id so ifToStmt emits non-CBRANCH instructions
                ifRegion->then_region = std::move(thenSeq);
                ifRegion->else_region = std::move(elseSeq);
                
                // Collapse all nodes
                std::set<int> collapsed = {(int)ni};
                for (int nid : thenNodes) collapsed.insert(nid);
                for (int nid : elseNodes) collapsed.insert(nid);
                
                RNode newNode;
                newNode.id = (int)nodes_.size();
                newNode.type = REGION_IF_THEN_ELSE;
                newNode.region = std::move(ifRegion);
                newNode.start_addr = blk->start_addr;
                for (int nid : collapsed)
                    for (int b : nodes_[nid].blocks)
                        newNode.blocks.insert(b);
                // The successor is the merge point's block
                int mergePointBlk = *nodes_[mergePoint].blocks.begin();
                newNode.succs.push_back(mergePointBlk);
                
                // Replace nodes
                std::vector<RNode> newNodes;
                for (size_t j = 0; j < nodes_.size(); j++) {
                    if (collapsed.count((int)j)) continue;
                    newNodes.push_back(std::move(nodes_[j]));
                }
                newNodes.push_back(std::move(newNode));
                for (size_t j = 0; j < newNodes.size(); j++)
                    newNodes[j].id = (int)j;
                nodes_ = std::move(newNodes);
                recomputeEdges();
                return true;
            }
        }
    }
    return false;
}

// ════════════════════════════════════════════════════════════════════
// Rule 4: ruleIfThen — collapse simple if-then (no else)
// 对标 Ghidra ruleBlockIfElse with empty else branch
// ════════════════════════════════════════════════════════════════════
bool FixpointStructurer::ruleIfThen() {
    // Handled by ruleIfElse which already handles missing else branch
    return false;
}

// ════════════════════════════════════════════════════════════════════
// v5.8: Helper to flatten nested sequences when building regions.
// If child is a SEQUENCE, flatten its children into seq.
// Otherwise, add child as-is.
// ════════════════════════════════════════════════════════════════════
void FixpointStructurer::addRegionFlatten(std::unique_ptr<Region>& seq, std::unique_ptr<Region> child) {
    if (!child) return;
    if (!seq) return;
    if (child->type == REGION_SEQUENCE) {
        for (auto& c : child->children) {
            seq->children.push_back(std::move(c));
        }
    } else {
        seq->children.push_back(std::move(child));
    }
}

// ════════════════════════════════════════════════════════════════════
// Rule 5: ruleCollapse — collapse single-block regions
// 对标 Ghidra ruleCollapseBlock:
// Basic blocks with exactly one successor and one predecessor can be
// merged with their successor (if the successor has exactly one
// predecessor). This reduces the graph size.
// ════════════════════════════════════════════════════════════════════
bool FixpointStructurer::ruleCollapse() {
    for (size_t i = 0; i < nodes_.size(); i++) {
        auto& node = nodes_[i];
        // v5.5: Removed type check — any node type can be collapsed
        if (node.succs.size() != 1) continue;
        if (node.preds.size() != 1) continue;
        // v21: Preserve if-else, loop, and switch structured regions.
        // These regions must NOT be collapsed because the collapse would
        // flatten the structured control flow into a flat sequence,
        // losing the if-else/loop structure.
        if (node.type == REGION_DO_WHILE || node.type == REGION_WHILE || 
            node.type == REGION_FOR || node.type == REGION_IF_THEN_ELSE ||
            node.type == REGION_IF_THEN || node.type == REGION_SWITCH) continue;
        
        // succs[0] is a block ID — find the node containing it
        int succ_blk = node.succs[0];
        int succ = findNodeByBlock(succ_blk);
        if (succ < 0 || succ == (int)i) continue;
        // v21: Also skip if successor has a structured region
        if (nodes_[succ].type == REGION_DO_WHILE || nodes_[succ].type == REGION_WHILE || 
            nodes_[succ].type == REGION_FOR || nodes_[succ].type == REGION_IF_THEN_ELSE ||
            nodes_[succ].type == REGION_IF_THEN || nodes_[succ].type == REGION_SWITCH) continue;
        
        auto& succNode = nodes_[succ];
        // v5.5: Removed type check — any node type can be merged
        if (succNode.preds.size() != 1) continue;
        
        // Merge: collapse into a sequence
        auto seq = std::make_unique<Region>(REGION_SEQUENCE);
        // v5.8: Use addRegionFlatten to prevent deep nesting
        if (node.region) {
            addRegionFlatten(seq, node.region->clone());
        } else {
            auto bb1 = std::make_unique<Region>(REGION_BASIC_BLOCK);
            bb1->block_id = *node.blocks.begin();
            bb1->start_addr = node.start_addr;
            seq->children.push_back(std::move(bb1));
        }
        
        if (succNode.region) {
            addRegionFlatten(seq, succNode.region->clone());
        } else {
            auto bb2 = std::make_unique<Region>(REGION_BASIC_BLOCK);
            bb2->block_id = *succNode.blocks.begin();
            bb2->start_addr = succNode.start_addr;
            seq->children.push_back(std::move(bb2));
        }
        
        RNode newNode;
        newNode.id = (int)nodes_.size();
        newNode.type = REGION_BASIC_BLOCK;
        newNode.region = std::move(seq);
        newNode.start_addr = node.start_addr;
        for (int b : node.blocks) newNode.blocks.insert(b);
        for (int b : succNode.blocks) newNode.blocks.insert(b);
        newNode.succs = succNode.succs;
        newNode.goto_succs = succNode.goto_succs;  // v5.5: preserve goto edges
        
        // Replace
        std::vector<RNode> newNodes;
        for (size_t j = 0; j < nodes_.size(); j++) {
            if (j == i || j == (size_t)succ) continue;
            newNodes.push_back(std::move(nodes_[j]));
        }
        newNodes.push_back(std::move(newNode));
        for (size_t j = 0; j < newNodes.size(); j++)
            newNodes[j].id = (int)j;
        nodes_ = std::move(newNodes);
        
        recomputeEdges();
        return true;
    }
    return false;
}

// ════════════════════════════════════════════════════════════════════
// Rule 6: ruleSequence — merge sequential nodes into a single sequence
// 对标 Ghidra ruleMergeBlocks:
// Two adjacent nodes where the first has exactly one successor (the
// second) and the second has exactly one predecessor (the first) can
// be merged into a sequence.
// ════════════════════════════════════════════════════════════════════
bool FixpointStructurer::ruleSequence() {
    for (size_t i = 0; i < nodes_.size(); i++) {
        auto& node = nodes_[i];
        if (node.succs.size() != 1) continue;
        // v21: Preserve if-else, loop, and switch structured regions.
        // These regions must NOT be merged into a sequence because it would
        // flatten the structured control flow. IF_THEN_ELSE regions are
        // especially important to preserve since they contain the condition
        // and two branches which must remain in the correct tree structure.
        if (node.type == REGION_DO_WHILE || node.type == REGION_WHILE || 
            node.type == REGION_FOR || node.type == REGION_IF_THEN_ELSE ||
            node.type == REGION_IF_THEN || node.type == REGION_SWITCH) continue;
        
        // succs[0] is a block ID — find the node containing it
        int succ_blk = node.succs[0];
        int succ = findNodeByBlock(succ_blk);
        if (succ < 0 || succ == (int)i) continue;
        // v21: Also skip if successor has a structured region
        auto& succNode = nodes_[succ];
        if (succNode.type == REGION_DO_WHILE || succNode.type == REGION_WHILE || 
            succNode.type == REGION_FOR || succNode.type == REGION_IF_THEN_ELSE ||
            succNode.type == REGION_IF_THEN || succNode.type == REGION_SWITCH) continue;
        if (succNode.preds.size() != 1) continue;
        if (succNode.preds[0] != (int)i) continue;
        
        // Merge: create a sequence
        auto seq = std::make_unique<Region>(REGION_SEQUENCE);
        if (node.region)
            addRegionFlatten(seq, node.region->clone());
        else {
            auto bb = std::make_unique<Region>(REGION_BASIC_BLOCK);
            bb->block_id = *node.blocks.begin();
            bb->start_addr = node.start_addr;
            seq->children.push_back(std::move(bb));
        }
        if (succNode.region)
            addRegionFlatten(seq, succNode.region->clone());
        else {
            auto bb = std::make_unique<Region>(REGION_BASIC_BLOCK);
            bb->block_id = *succNode.blocks.begin();
            bb->start_addr = succNode.start_addr;
            seq->children.push_back(std::move(bb));
        }
        
        RNode newNode;
        newNode.id = (int)nodes_.size();
        newNode.type = REGION_BASIC_BLOCK;
        newNode.region = std::move(seq);
        newNode.start_addr = node.start_addr;
        for (int b : node.blocks) newNode.blocks.insert(b);
        for (int b : succNode.blocks) newNode.blocks.insert(b);
        newNode.succs = succNode.succs;
        
        std::vector<RNode> newNodes;
        for (size_t j = 0; j < nodes_.size(); j++) {
            if (j == i || j == (size_t)succ) continue;
            newNodes.push_back(std::move(nodes_[j]));
        }
        newNodes.push_back(std::move(newNode));
        for (size_t j = 0; j < newNodes.size(); j++)
            newNodes[j].id = (int)j;
        nodes_ = std::move(newNodes);
        
        recomputeEdges();
        return true;
    }
    return false;
}

// ════════════════════════════════════════════════════════════════════
// Rule 7: ruleLabelLoops — label loop headers and classify breaks/continues
// 对标 Ghidra ruleLabelLoops + r2's loop classification:
// Walk the graph and mark nodes that are inside loops. Convert
// goto_succs edges to BREAK/CONTINUE when the target is a loop exit/header.
// ════════════════════════════════════════════════════════════════════
bool FixpointStructurer::ruleLabelLoops() {
    // v23.0: Counter to prevent infinite loops. This rule can be
    // triggered repeatedly when the same goto_succs keep being found.
    // The fixpoint loop will call this rule at most 3 times per function.
    label_loops_counter_++;
    if (label_loops_counter_ > 3) {
        return false;  // Prevent infinite loop
    }

    bool changed = false;

    // Step 1: Assign loop context to nodes that are inside loops
    for (auto& loop : loops_) {
        for (int bid : loop.body) {
            int nid = findNodeByBlock(bid);
            if (nid < 0) continue;
            if (nodes_[nid].loop_header < 0) {
                nodes_[nid].loop_header = loop.header;
                nodes_[nid].loop_exit = (loop.primary_exit >= 0) ? loop.primary_exit
                                        : (!loop.exits.empty() ? *loop.exits.begin() : -1);
                changed = true;
            }
        }
    }

    // Step 2: Convert goto_succs to BREAK/CONTINUE when targets match loop context
    for (auto& node : nodes_) {
        if (node.goto_succs.empty()) continue;
        if (node.loop_header < 0) continue;

        std::set<int> remainingGotos;
        for (int targetBlk : node.goto_succs) {
            int targetNode = findNodeByBlock(targetBlk);
            if (targetNode < 0) {
                remainingGotos.insert(targetBlk);
                continue;
            }

            // v17.0: Check if the target block is a RETURN block. If so,
            // skip BREAK conversion — the goto is effectively a "goto return"
            // which should remain as a goto, not become a BREAK. A RETURN
            // block is a block whose tail instruction is OP_RET.
            bool targetIsReturn = false;
            {
                auto* tBlk = mba_->getBlock(targetBlk);
                if (tBlk && tBlk->tail && tBlk->tail->opcode == mc::OP_RET) {
                    targetIsReturn = true;
                }
            }

            // Check if target is this node's loop exit → BREAK
            if (!targetIsReturn && node.loop_exit >= 0) {
                int exitNid = findNodeByBlock(node.loop_exit);
                if (exitNid >= 0) {
                    auto& exitNode = nodes_[exitNid];
                    if (exitNode.blocks.count(targetBlk) ||
                        targetBlk == node.loop_exit) {
                        // Convert to BREAK
                        if (!node.region) {
                            node.region = std::make_unique<Region>(REGION_BREAK);
                        } else {
                            // Append BREAK to the region
                            auto seq = std::make_unique<Region>(REGION_SEQUENCE);
                            addRegionFlatten(seq, node.region->clone());
                            auto brk = std::make_unique<Region>(REGION_BREAK);
                            seq->children.push_back(std::move(brk));
                            node.region = std::move(seq);
                        }
                        changed = true;
                        continue;
                    }
                }
            }

            // Check if target is this node's loop header → CONTINUE
            if (node.loop_header >= 0) {
                int hdrNid = findNodeByBlock(node.loop_header);
                if (hdrNid >= 0) {
                    auto& hdrNode = nodes_[hdrNid];
                    if (hdrNode.blocks.count(targetBlk) ||
                        targetBlk == node.loop_header) {
                        // Convert to CONTINUE
                        if (!node.region) {
                            node.region = std::make_unique<Region>(REGION_CONTINUE);
                        } else {
                            auto seq = std::make_unique<Region>(REGION_SEQUENCE);
                            addRegionFlatten(seq, node.region->clone());
                            auto cont = std::make_unique<Region>(REGION_CONTINUE);
                            seq->children.push_back(std::move(cont));
                            node.region = std::move(seq);
                        }
                        changed = true;
                        continue;
                    }
                }
            }

            remainingGotos.insert(targetBlk);
        }

        if (remainingGotos.size() != node.goto_succs.size()) {
            node.goto_succs = remainingGotos;
            recomputeEdges();
        }
    }

    // Step 3: Also handle the region tree — if a region has goto_succs
    // that point to a loop exit, emit a BREAK as the last statement
    // (This handles cases where the region tree already has goto regions)
    // v23.0: Actually remove the goto_succs that match the loop exit,
    // preventing infinite loop in the fixpoint (previously just set
    // changed=true without modifying goto_succs, causing re-trigger).
    for (auto& node : nodes_) {
        if (!node.region) continue;
        if (node.loop_header < 0) continue;
        // Check if this region type can contain a break/continue
        if (node.type == REGION_IF_THEN || node.type == REGION_IF_THEN_ELSE ||
            node.type == REGION_DO_WHILE || node.type == REGION_WHILE ||
            node.type == REGION_FOR) {
            // If any goto_succs edges remain, they might be unstructured
            if (!node.goto_succs.empty()) {
                std::set<int> remainingGotos;
                for (int gs : node.goto_succs) {
                    if (node.loop_exit >= 0 && gs == node.loop_exit) {
                        // This goto edge is already handled by the loop structure
                        changed = true;
                    } else {
                        remainingGotos.insert(gs);
                    }
                }
                if (remainingGotos.size() != node.goto_succs.size()) {
                    node.goto_succs = remainingGotos;
                }
            }
        }
    }

    return changed;
}

// ════════════════════════════════════════════════════════════════════
// v5.5: selectGoto — pick an edge to mark as unstructured goto
// Reference: Ghidra CollapseStructure::selectGoto
// When all structuring rules stall, this breaks the deadlock by
// selecting an edge that prevents further structuring and marking
// it as a goto. The heuristic prefers:
//   1. Edges from blocks with multiple successors to blocks with
//      multiple predecessors (cross edges)
//   2. Back edges that aren't part of a recognized loop
//   3. Any remaining unstructured edge
// ════════════════════════════════════════════════════════════════════
bool FixpointStructurer::selectGoto() {
    if (!mba_) return false;
    
    // v5.5: Use the CURRENT node graph (node.succs), not the original CFG.
    // After collapsing, node.succs reflects the current state.
    int best_from = -1, best_to = -1;
    int best_score = -1;
    
    for (size_t i = 0; i < nodes_.size(); i++) {
        auto& node = nodes_[i];
        // Any node type can have goto edges
        if (node.succs.empty()) continue;
        
        // v52.0: Skip CBRANCH blocks — their successors are always legitimate
        // conditional branches. Marking a CBRANCH edge as goto would incorrectly
        // convert a conditional branch into an unstructured goto, losing the
        // condition. The CBRANCH block's edges should be handled by ruleIfElse.
        // If ruleIfElse can't find a pattern, the CBRANCH block remains as-is
        // and the buildRegionTree output will still emit it correctly.
        {
            bool isCbranch = false;
            for (int b : node.blocks) {
                auto* blk = mba_->getBlock(b);
                if (blk && blk->tail && blk->tail->opcode == mc::OP_CBRANCH) {
                    isCbranch = true;
                    break;
                }
            }
            if (isCbranch) continue;
        }
        
        for (int succ_blk : node.succs) {
            if (isGotoEdge(*node.blocks.begin(), succ_blk)) continue;
            
            int succNode = findNodeByBlock(succ_blk);
            if (succNode < 0 || succNode == (int)i) continue;
            
            auto& succNodeRef = nodes_[succNode];
            
            // Score: prefer edges where target has many predecessors
            // and source has many successors (cross edges)
            // v12.0: Invert the score to prefer edges to nodes with FEWER
            // predecessors. This prevents the selectGoto from marking the
            // main control flow edge (e.g., entry node -> DO_WHILE node)
            // which has many predecessors, and instead selects cross edges
            // that are less critical.
            int score = (int)node.succs.size() * 10 - (int)succNodeRef.preds.size();
            
            // Skip if this is a simple 1->1 edge (ruleCollapse/ruleSequence should handle it)
            if (node.succs.size() == 1 && succNodeRef.preds.size() == 1)
                continue;
            
            // Don't mark the only edge of a node (would orphan it)
            if (node.succs.size() == 1)
                continue;
            
            // v52.0: Skip if the edge target is a block with no successors (return).
            // Edges to return blocks are always legitimate exit edges, not gotos.
            {
                auto* tgtBlk = mba_->getBlock(succ_blk);
                if (tgtBlk && tgtBlk->tail && tgtBlk->tail->opcode == mc::OP_RET) {
                    continue;
                }
            }
            
            if (score > best_score) {
                best_score = score;
                best_from = *node.blocks.begin();
                best_to = succ_blk;
            }
        }
    }
    
    if (best_from < 0) return false;
    
    DBG_PRINT("[DBG_SG] selectGoto: b%d->b%d (fromNode=%d score=%d)\n",
            best_from, best_to, findNodeByBlock(best_from), best_score);
    
    markGotoEdge(best_from, best_to);
    return true;
}

// ════════════════════════════════════════════════════════════════════
// v5.5: isGotoEdge / markGotoEdge — track unstructured edges
// Reference: Ghidra FlowBlock::isGotoOut / setGotoBranch
// ════════════════════════════════════════════════════════════════════
bool FixpointStructurer::isGotoEdge(int from_block, int to_block) {
    int node = findNodeByBlock(from_block);
    if (node < 0) return false;
    return nodes_[node].goto_succs.count(to_block) > 0;
}

void FixpointStructurer::markGotoEdge(int from_block, int to_block) {
    int node = findNodeByBlock(from_block);
    if (node >= 0) {
        nodes_[node].goto_succs.insert(to_block);
        // v5.5: Remove the edge from succs so other rules see a simpler graph.
        // Reference: Ghidra effectively removes goto edges from the block graph.
        auto& succs = nodes_[node].succs;
        succs.erase(std::remove(succs.begin(), succs.end(), to_block), succs.end());
        recomputeEdges();
    }
}

// ════════════════════════════════════════════════════════════════════
// v5.5: ruleBlockGoto — disabled
// selectGoto() already removes goto edges from succs, allowing other
// rules to match. Goto edges are tracked in goto_succs and handled
// by buildRegionTree(). The old implementation used blk->successors
// (original CFG, never modified) causing infinite oscillation.
// ════════════════════════════════════════════════════════════════════
bool FixpointStructurer::ruleBlockGoto() {
    return false;
}

// ════════════════════════════════════════════════════════════════════
// v10.6: ruleSwitch — detect switch-case from OP_JTBL instruction
// Pattern: A block ending with OP_JTBL (jump table) has N successors.
// Each successor is a case body. All case bodies typically merge to a
// common exit block (the post-switch merge point).
//
// The OP_JTBL instruction stores:
//   - case_values:  sequential case indices (0, 1, 2, ...)
//   - case_targets: target addresses for each case
//   - switch_table_addr: address of the jump table in ELF data
//
// We map each successor to its case value using case_targets and the
// block start addresses. If multiple cases jump to the same block,
// they share the same case body (fallthrough).
// ════════════════════════════════════════════════════════════════════
bool FixpointStructurer::ruleSwitch() {
    if (!mba_) return false;
    int n = mba_->numBlocks();

    for (int i = 0; i < (int)nodes_.size(); i++) {
        RNode& node = nodes_[i];
        if (node.type != REGION_BASIC_BLOCK) continue;
        if (node.blocks.empty()) continue;

        int blockId = *node.blocks.begin();
        auto* blk = mba_->getBlock(blockId);
        if (!blk || !blk->tail) continue;

        // v10.6: Look for OP_JTBL (jump table) instruction
        if (blk->tail->opcode != mc::OP_JTBL) continue;

        // Must have 3+ successors (switch needs at least 3 cases)
        // v10.6: successors are now properly populated by edge resolution
        if ((int)blk->successors.size() < 3) continue;

        auto* jtbl = blk->tail;

        // Collect unique case blocks (some cases may share the same target)
        // Map: block_id → list of case values
        std::map<int, std::vector<int64_t>> blockToCases;

        // Map case target addresses to block IDs
        // case_targets[i] is the target address for case_values[i]
        for (size_t ci = 0; ci < jtbl->case_targets.size() && ci < jtbl->case_values.size(); ci++) {
            uint64_t targetAddr = jtbl->case_targets[ci];
            // Find the block containing this address
            int targetBlock = -1;
            for (int bi = 0; bi < n; bi++) {
                auto* b2 = mba_->getBlock(bi);
                if (!b2) continue;
                if (b2->start_addr == targetAddr) {
                    targetBlock = bi;
                    break;
                }
                // Also check if target is inside a block (block start <= target < next block)
                if (b2->start_addr <= targetAddr) {
                    // Check if this is the closest block
                    if (targetBlock < 0 || b2->start_addr > mba_->getBlock(targetBlock)->start_addr) {
                        targetBlock = bi;
                    }
                }
            }
            if (targetBlock >= 0 && targetBlock != blockId) {
                blockToCases[targetBlock].push_back((int64_t)jtbl->case_values[ci]);
            }
        }

        // If case_targets mapping failed, fall back to using successors directly
        if (blockToCases.empty()) {
            int caseIdx = 0;
            for (int succ : blk->successors) {
                if (succ >= 0 && succ < n && succ != blockId) {
                    blockToCases[succ].push_back(caseIdx);
                    caseIdx++;
                }
            }
        }

        if (blockToCases.size() < 3) continue;

        // Collect case block IDs
        std::vector<int> caseBlocks;
        for (auto& [bid, _] : blockToCases) {
            caseBlocks.push_back(bid);
        }

        // Find merge point using post-dominator intersection (like r2's switch_analysis)
        // Reference: r2 uses the intersection of post-dominators of all case blocks.
        // Fallback: simple majority voting if pdom fails.
        int mergePoint = -1;
        {
            // v11.3: Try post-dominator intersection first (more robust than majority voting)
            // Use findMergePointFast (pairwise ipdom walk) to find the nearest common
            // post-dominator of all case blocks.
            if (!caseBlocks.empty()) {
                int common = caseBlocks[0];
                for (size_t ci = 1; ci < caseBlocks.size(); ci++) {
                    common = findMergePointFast(common, caseBlocks[ci]);
                    if (common < 0) break;
                }
                if (common >= 0 && common != caseBlocks[0]) {
                    // Verify common is not one of the case blocks
                    bool isCaseBlock = false;
                    for (int cb : caseBlocks) {
                        if (common == cb) { isCaseBlock = true; break; }
                    }
                    if (!isCaseBlock) mergePoint = common;
                }
            }

            // Fallback: simple majority voting (original approach)
            if (mergePoint < 0) {
                std::map<int, int> succCount;
                for (int cb : caseBlocks) {
                    auto* cblk = mba_->getBlock(cb);
                    if (!cblk) continue;
                    for (int s : cblk->successors) {
                        succCount[s]++;
                    }
                }
                // Merge point: reachable from most case blocks (>= 2/3)
                for (auto& [s, cnt] : succCount) {
                    if (cnt >= (int)caseBlocks.size() * 2 / 3) {
                        mergePoint = s;
                        break;
                    }
                }
            }
        }

        // Build switch region
        auto region = std::make_unique<Region>(REGION_SWITCH);
        region->block_id = blockId;
        region->switch_expr = jtbl;
        region->start_addr = blk->start_addr;

        // Build case regions with proper case values
        for (auto& [cb, caseVals] : blockToCases) {
            // Check if this case block is still an active RNode
            bool found = false;
            for (int j = 0; j < (int)nodes_.size(); j++) {
                if (nodes_[j].blocks.count(cb)) {
                    found = true;
                    break;
                }
            }
            if (!found) continue;

            auto caseRegion = std::make_unique<Region>(REGION_BASIC_BLOCK);
            caseRegion->block_id = cb;
            caseRegion->start_addr = mba_->getBlock(cb) ? mba_->getBlock(cb)->start_addr : 0;

            // If multiple case values map to the same block, use the first
            // (fallthrough semantics handle the rest)
            if (!caseVals.empty()) {
                region->cases.push_back({caseVals[0], std::move(caseRegion)});
            }
        }

        if (region->cases.size() < 3) continue;

        // Set default region (merge point or fall-through)
        if (mergePoint >= 0) {
            auto defaultRegion = std::make_unique<Region>(REGION_BASIC_BLOCK);
            defaultRegion->block_id = mergePoint;
            defaultRegion->start_addr = mba_->getBlock(mergePoint) ?
                mba_->getBlock(mergePoint)->start_addr : 0;
            region->default_region = std::move(defaultRegion);
        }

        // Replace the switch node
        node.type = REGION_SWITCH;
        node.region = std::move(region);

        // v10.6: Update the switch node's successors to point to the merge point
        // (the block after all case bodies). The original successors pointed to
        // case blocks, which are being removed from nodes_.
        node.succs.clear();
        if (mergePoint >= 0) {
            node.succs.push_back(mergePoint);
        }

        // Remove case blocks from nodes_ (they're now part of the switch)
        nodes_.erase(
            std::remove_if(nodes_.begin(), nodes_.end(),
                [&](const RNode& rn) {
                    for (int cb : caseBlocks) {
                        if (rn.blocks.count(cb)) return true;
                    }
                    return false;
                }),
            nodes_.end()
        );

        // v10.6: Recompute edges after modifying nodes_ (like other rules do)
        recomputeEdges();

        return true;
    }
    return false;
}

// ════════════════════════════════════════════════════════════════════
// v5.5: ruleBlockOr — merge OR/AND conditional patterns
// Reference: Ghidra CollapseStructure::ruleBlockOr
// Pattern: A → B, A → C, B → D, C → D
// This is: if (A || B) goto D  (OR pattern)
// Or dually: if (A && B) goto D (AND pattern)
// ════════════════════════════════════════════════════════════════════
bool FixpointStructurer::ruleBlockOr() {
    if (!mba_) return false;
    
    for (size_t ni = 0; ni < nodes_.size(); ni++) {
        auto& node = nodes_[ni];
        if (node.type != REGION_BASIC_BLOCK) continue;
        if (node.succs.size() != 2) continue;
        
        // Find the CBRANCH block
        int cbrBlock = -1;
        for (int b : node.blocks) {
            auto* blk = mba_->getBlock(b);
            if (blk->tail && blk->tail->opcode == mc::OP_CBRANCH) {
                cbrBlock = b;
                break;
            }
        }
        if (cbrBlock < 0) continue;
        
        auto* blk = mba_->getBlock(cbrBlock);
        int succ1 = blk->successors[0];
        int succ2 = blk->successors[1];
        
        int node1 = findNodeByBlock(succ1);
        int node2 = findNodeByBlock(succ2);
        if (node1 < 0 || node2 < 0 || node1 == node2) continue;
        if (node1 == (int)ni || node2 == (int)ni) continue;
        
        // Check if both successors have exactly one successor each,
        // and both go to the same target
        auto& n1 = nodes_[node1];
        auto& n2 = nodes_[node2];
        if (n1.succs.size() != 1 || n2.succs.size() != 1) continue;
        if (n1.succs[0] != n2.succs[0]) continue;
        
        // Check that both branches have no goto edges
        if (!n1.goto_succs.empty() || !n2.goto_succs.empty()) continue;
        
        // Check that neither branch is a loop header
        int mergeBlk = n1.succs[0];
        int mergeNode = findNodeByBlock(mergeBlk);
        if (mergeNode < 0) continue;
        
        // v5.8: Standard if-then-else pattern detected (was misnamed "ruleBlockOr").
        // Build proper if-then-else using then_region/else_region, NOT children.
        // The old code used children which are never read by ifToStmt, causing
        // empty if-then-else with spurious break/continue generation.
        auto ifElse = std::make_unique<Region>(REGION_IF_THEN_ELSE);
        ifElse->condition = blk->tail;
        if (ifElse->condition) ifElse->start_addr = ifElse->condition->ea;
        ifElse->block_id = blk->block_id;  // v19: Set block_id for ifToStmt
        
        // True branch
        if (n1.region) {
            ifElse->then_region = n1.region->clone();
        } else {
            auto bb = std::make_unique<Region>(REGION_BASIC_BLOCK);
            bb->block_id = *n1.blocks.begin();
            bb->start_addr = n1.start_addr;
            ifElse->then_region = std::move(bb);
        }
        
        // False branch
        if (n2.region) {
            ifElse->else_region = n2.region->clone();
        } else {
            auto bb = std::make_unique<Region>(REGION_BASIC_BLOCK);
            bb->block_id = *n2.blocks.begin();
            bb->start_addr = n2.start_addr;
            ifElse->else_region = std::move(bb);
        }
        
        // Create new node
        RNode newNode;
        newNode.id = (int)nodes_.size();
        newNode.type = REGION_IF_THEN_ELSE;
        newNode.region = std::move(ifElse);
        newNode.start_addr = node.start_addr;
        for (int b : node.blocks) newNode.blocks.insert(b);
        for (int b : n1.blocks) newNode.blocks.insert(b);
        for (int b : n2.blocks) newNode.blocks.insert(b);
        newNode.succs = {mergeBlk};
        
        // Remove old nodes, add new one
        std::vector<RNode> newNodes;
        for (size_t j = 0; j < nodes_.size(); j++) {
            if (j == ni || j == (size_t)node1 || j == (size_t)node2) continue;
            newNodes.push_back(std::move(nodes_[j]));
        }
        newNodes.push_back(std::move(newNode));
        for (size_t j = 0; j < newNodes.size(); j++)
            newNodes[j].id = (int)j;
        nodes_ = std::move(newNodes);
        recomputeEdges();
        return true;
    }
    return false;
}

// ════════════════════════════════════════════════════════════════════
// v5.5: ruleBlockCat — enhanced block list merging
// Reference: Ghidra CollapseStructure::ruleBlockCat
// Merge a chain of blocks where each has exactly one successor
// and the successor has exactly one predecessor, into a BlockList.
// Unlike ruleCollapse, this works on ANY node type (not just BASIC_BLOCK)
// and can merge longer chains in one step.
// ════════════════════════════════════════════════════════════════════
bool FixpointStructurer::ruleBlockCat() {
    for (size_t i = 0; i < nodes_.size(); i++) {
        auto& node = nodes_[i];
        if (node.succs.size() != 1) continue;
        if (!node.goto_succs.empty()) continue;
        
        int succ_blk = node.succs[0];
        int succ = findNodeByBlock(succ_blk);
        if (succ < 0 || succ == (int)i) continue;
        
        auto& succNode = nodes_[succ];
        if (succNode.preds.size() != 1) continue;
        if (!succNode.goto_succs.empty()) continue;
        
        // Don't merge if successor is a loop header (would break loop structure)
        if (loop_headers_.count(succ_blk)) continue;
        
        // v12.0: Don't merge if the successor node is a collapsed structure
        // (has a region already). Merging a collapsed node into a basic block
        // chain would lose the control flow structure. Also, don't merge if
        // the result would have no successors (terminal merge).
        if (succNode.region) continue;
        if (succNode.succs.empty()) continue;
        
        // Build a BlockList (sequence) region
        auto seq = std::make_unique<Region>(REGION_SEQUENCE);
        
        // v5.8: Use addRegionFlatten to prevent deep nesting
        if (node.region) {
            addRegionFlatten(seq, node.region->clone());
        } else {
            auto bb = std::make_unique<Region>(REGION_BASIC_BLOCK);
            bb->block_id = *node.blocks.begin();
            bb->start_addr = node.start_addr;
            seq->children.push_back(std::move(bb));
        }
        
        if (succNode.region) {
            addRegionFlatten(seq, succNode.region->clone());
        } else {
            auto bb = std::make_unique<Region>(REGION_BASIC_BLOCK);
            bb->block_id = *succNode.blocks.begin();
            bb->start_addr = succNode.start_addr;
            seq->children.push_back(std::move(bb));
        }
        
        RNode newNode;
        newNode.id = (int)nodes_.size();
        newNode.type = REGION_BASIC_BLOCK;
        newNode.region = std::move(seq);
        newNode.start_addr = node.start_addr;
        for (int b : node.blocks) newNode.blocks.insert(b);
        for (int b : succNode.blocks) newNode.blocks.insert(b);
        newNode.succs = succNode.succs;
        newNode.goto_succs = succNode.goto_succs;
        
        std::vector<RNode> newNodes;
        for (size_t j = 0; j < nodes_.size(); j++) {
            if (j == i || j == (size_t)succ) continue;
            newNodes.push_back(std::move(nodes_[j]));
        }
        newNodes.push_back(std::move(newNode));
        for (size_t j = 0; j < newNodes.size(); j++)
            newNodes[j].id = (int)j;
        nodes_ = std::move(newNodes);
        recomputeEdges();
        return true;
    }
    return false;
}

// ════════════════════════════════════════════════════════════════════
// Main fixpoint loop
// 对标 Ghidra blockaction.cc run():
//   while (changed) {
//     for each rule (in priority order):
//       if rule applies → changed = true, restart from first rule
//   }
// ════════════════════════════════════════════════════════════════════
std::unique_ptr<Region> FixpointStructurer::structure(mc::MicrocodeBlockArray& mba) {
    mba_ = &mba;
    
    // v23.0: Reset label_loops_counter for each function
    label_loops_counter_ = 0;
    
    int n = mba_->numBlocks();
    if (n == 0) return nullptr;
    
    // v9.12: Filter unreachable blocks before structuring.
    // Blocks after noreturn calls (abort, exit, etc.) and blocks after
    // unconditional branches are garbage (literal pool data, padding) and
    // must not be included in the structured region.
    // This mirrors the reachability filter in CFGStructurer::structure().
    {
        std::set<int> reachable;
        std::queue<int> q;
        q.push(0);  // entry block
        reachable.insert(0);
        while (!q.empty()) {
            int cur = q.front(); q.pop();
            auto* blk = mba_->getBlock(cur);
            if (!blk) continue;
            // Stop at noreturn calls and RET — code after is unreachable
            if (blk->tail && blk->tail->opcode == mc::OP_CALL &&
                blk->tail->call_info && !blk->tail->call_info->has_return) {
                continue;
            }
            if (blk->tail && blk->tail->opcode == mc::OP_RET) continue;
            for (int succ : blk->successors) {
                if (succ >= 0 && succ < n && !reachable.count(succ)) {
                    reachable.insert(succ);
                    q.push(succ);
                }
            }
        }
        // Remove unreachable blocks from the microcode array
        // by marking them as empty (all instructions DEAD)
        // NOTE: successors are NOT cleared here — they are still needed
        // for the loop detection BFS below. Clearing successors would
        // break the BFS reachability check when a path goes through a
        // dead block (e.g. b19→b20→b21, where b20 is dead but the path
        // from b7 to b25 must traverse b20→b21).
        // v51.0 debug: dump reachable set
        fprintf(stderr, "[DBG_REACH] reachable set (%zu blocks):", reachable.size());
        for (int r : reachable) fprintf(stderr, " %d", r);
        fprintf(stderr, "\n");
        for (int i = 0; i < n; i++) {
            if (!reachable.count(i)) {
                auto* blk = mba_->getBlock(i);
                if (blk) {
                    for (auto* insn = blk->head; insn; insn = insn->next) {
                        insn->iprops |= mc::IPROP_DEAD;
                    }
                }
                fprintf(stderr, "[DBG_REACH] block %d marked DEAD (not reachable)\n", i);
            }
        }
    }
    
    // Step 1: Repair CFG — fix blocks that have no successors.
    // The linear sweep disassembler may create blocks for data/padding that the
    // microcode emitter cannot decode (e.g. b20 in JNI_OnLoad at 0x1c04 between
    // b19 at 0x1c00 and b21 at 0x1c08). Such blocks: (a) have no successors,
    // (b) may have NO predecessors either (the emitter doesn't set up preds for
    // dead/data blocks), and (c) break the forward BFS reachability check for
    // loop detection. The fix: infer the fall-through successor based on the
    // address of the next block. This is safe because the block is dead
    // (IPROP_DEAD) and will be ignored by the structuring rules, but the
    // successor edge is needed for the forward BFS path.
    //
    // v11.5: Also repair dead blocks with no predecessors — the old check
    // `!blk->predecessors.empty()` missed dead blocks where the microcode
    // emitter didn't set up predecessors (e.g., b20 in JNI_OnLoad is a data
    // block created by the linear sweep, not by the emitter, so it has no
    // predecessors). Without this fix, the forward BFS gets stuck at b20.
    for (int i = 0; i < n; i++) {
        auto* blk = mba_->getBlock(i);
        if (!blk) continue;
        if (blk->successors.empty()) {
            // Check if the block is dead (IPROP_DEAD) — if so, always repair
            // regardless of predecessors. Non-dead blocks still need predecessors
            // to be considered for repair (otherwise they're truly dead code).
            bool isDead = false;
            for (auto* insn = blk->head; insn; insn = insn->next) {
                if (insn->iprops & mc::IPROP_DEAD) { isDead = true; break; }
            }
            // Skip non-dead blocks with no predecessors — they're truly unreachable
            if (!isDead && blk->predecessors.empty()) continue;
            
            DBG_PRINT("[DBG_CFG] b%d has %zu preds, %zu succs, dead=%d\n",
                    i, blk->predecessors.size(), blk->successors.size(), isDead);
            // Check if the block ends with a RET or noreturn CALL
            bool isTerminal = false;
            if (blk->tail) {
                DBG_PRINT("[DBG_CFG] b%d tail op=%d\n", i, (int)blk->tail->opcode);
                if (blk->tail->opcode == mc::OP_RET) isTerminal = true;
                if (blk->tail->opcode == mc::OP_CALL &&
                    blk->tail->call_info && !blk->tail->call_info->has_return)
                    isTerminal = true;
            }
            if (isTerminal) {
                DBG_PRINT("[DBG_CFG] b%d is terminal, skipping\n", i);
                continue;
            }
            
            // Find the next block's start address (fall-through target)
            // For ARM32, all instructions are 4 bytes. For other architectures,
            // use the last instruction's address + 4 (Thumb2) or instruction size.
            uint64_t startAddr = blk->start_addr;
            uint64_t lastAddr = startAddr;
            for (auto* insn = blk->head; insn; insn = insn->next) {
                if (insn->ea > lastAddr) lastAddr = insn->ea;
            }
            // ARM32: 4 bytes per instruction. If the block has a tail instruction,
            // the fall-through is at the next instruction after the tail.
            // For ARM32, assume 4 bytes (all instructions are 4 bytes).
            // For Thumb2, would need to check the instruction type.
            uint64_t blkEnd = lastAddr + 4;
            // Search for a block whose start_addr matches the fall-through
            int nextBlock = -1;
            for (int j = 0; j < n; j++) {
                if (j == i) continue;
                auto* nb = mba_->getBlock(j);
                if (!nb) continue;
                if (nb->start_addr == blkEnd) {
                    nextBlock = j;
                    break;
                }
            }
            if (nextBlock >= 0) {
                blk->successors.push_back(nextBlock);
                // Also add this block as a predecessor of the next block
                bool exists = false;
                for (int p : mba_->getBlock(nextBlock)->predecessors) {
                    if (p == i) { exists = true; break; }
                }
                if (!exists) {
                    mba_->getBlock(nextBlock)->predecessors.push_back(i);
                }
                DBG_PRINT("[DBG_CFG] repaired b%d (0x%lx): added successor b%d (0x%lx)\n",
                        i, (unsigned long)startAddr,
                        nextBlock, (unsigned long)blkEnd);
            } else {
                // If no direct successor found, try the next block by index
                // This handles the case where blocks are not in address order
                for (int j = i + 1; j < n; j++) {
                    auto* nb = mba_->getBlock(j);
                    if (!nb) continue;
                    // Check if this block is reachable (not dead) and adjacent
                    bool nbDead = false;
                    for (auto* insn = nb->head; insn; insn = insn->next) {
                        if (insn->iprops & mc::IPROP_DEAD) { nbDead = true; break; }
                    }
                    if (nbDead) continue;
                    // Only add if the address is close (within 4-8 bytes)
                    if (nb->start_addr >= blkEnd && nb->start_addr <= blkEnd + 4) {
                        blk->successors.push_back(j);
                        bool exists = false;
                        for (int p : mba_->getBlock(j)->predecessors) {
                            if (p == i) { exists = true; break; }
                        }
                        if (!exists) {
                            mba_->getBlock(j)->predecessors.push_back(i);
                        }
                        DBG_PRINT("[DBG_CFG] repaired b%d (0x%lx): added fallthrough b%d (0x%lx)\n",
                                i, (unsigned long)startAddr,
                                j, (unsigned long)nb->start_addr);
                        break;
                    }
                }
            }
        }
    }
    
    // v11.5: Second pass — connect predecessors of terminal dead blocks to the
    // next live block. This is needed because the first pass skips terminal
    // blocks (e.g., b20 in JNI_OnLoad is a dead block with a noreturn CALL),
    // but the FORWARD BFS used by loop detection gets stuck at terminal blocks
    // that have no successors. The BACKWARD BFS also fails because the next
    // live block (e.g., b21) has no predecessors (no block has it as a successor
    // since the terminal dead block's successor is never set).
    //
    // Fix: for each terminal dead block, find its predecessors and connect them
    // directly to the next live block (by address), skipping the dead block.
    for (int i = 0; i < n; i++) {
        auto* blk = mba_->getBlock(i);
        if (!blk) continue;
        if (!blk->successors.empty()) continue;
        // Check if this block is both dead AND terminal
        bool isDead = false;
        for (auto* insn = blk->head; insn; insn = insn->next) {
            if (insn->iprops & mc::IPROP_DEAD) { isDead = true; break; }
        }
        if (!isDead) continue;
        bool isTerminal = false;
        if (blk->tail) {
            if (blk->tail->opcode == mc::OP_RET) isTerminal = true;
            if (blk->tail->opcode == mc::OP_CALL &&
                blk->tail->call_info && !blk->tail->call_info->has_return)
                isTerminal = true;
        }
        if (!isTerminal) continue;
        
        // Find the next live block after this one (by address)
        uint64_t startAddr = blk->start_addr;
        uint64_t lastAddr = startAddr;
        for (auto* insn = blk->head; insn; insn = insn->next) {
            if (insn->ea > lastAddr) lastAddr = insn->ea;
        }
        uint64_t blkEnd = lastAddr + 4;
        
        int nextLive = -1;
        for (int j = 0; j < n; j++) {
            if (j == i) continue;
            auto* nb = mba_->getBlock(j);
            if (!nb) continue;
            bool nbDead = false;
            for (auto* insn = nb->head; insn; insn = insn->next) {
                if (insn->iprops & mc::IPROP_DEAD) { nbDead = true; break; }
            }
            if (nbDead) continue;
            if (nb->start_addr >= blkEnd && nb->start_addr <= blkEnd + 8) {
                nextLive = j;
                break;
            }
        }
        if (nextLive < 0) continue;
        
        // Connect each predecessor of this dead block to the next live block
        for (int pred : blk->predecessors) {
            auto* predBlk = mba_->getBlock(pred);
            if (!predBlk) continue;
            // Check if already connected
            bool alreadyConnected = false;
            for (int s : predBlk->successors) {
                if (s == nextLive) { alreadyConnected = true; break; }
            }
            if (alreadyConnected) continue;
            
            predBlk->successors.push_back(nextLive);
            auto* nextBlk = mba_->getBlock(nextLive);
            if (nextBlk) {
                bool predExists = false;
                for (int p : nextBlk->predecessors) {
                    if (p == pred) { predExists = true; break; }
                }
                if (!predExists) {
                    nextBlk->predecessors.push_back(pred);
                }
            }
            DBG_PRINT("[DBG_CFG] repaired b%d->b%d: skipped dead b%d, connected b%d->b%d (0x%lx)\n",
                    pred, nextLive, i, pred, nextLive, (unsigned long)blkEnd);
        }
    }
    
    // Step 2: Compute dominators
    // NOTE: This runs BEFORE clearing successors of dead blocks, so the
    // dominator computation sees the complete CFG including edges through
    // dead blocks. This is important for correct RPO computation.
    computeDominators();
    
    // v11.3 debug: dump block graph
    DBG_PRINT("[DBG_BLK] Block graph (%d blocks):\n", n);
    for (int i = 0; i < n; i++) {
        auto* blk = mba_->getBlock(i);
        if (!blk) continue;
        // Check if block is dead (all instructions IPROP_DEAD)
        // v51.0: Use "all dead" check instead of "any dead" — the optimizer
        // marks individual instructions as dead, but the block is still alive.
        int liveCount = 0;
        int deadCount = 0;
        for (auto* insn = blk->head; insn; insn = insn->next) {
            if (insn->iprops & mc::IPROP_DEAD) deadCount++;
            else liveCount++;
        }
        if (liveCount == 0 && deadCount > 0) continue;
        fprintf(stderr, "  b%d @ 0x%lx:", i, (unsigned long)blk->start_addr);
        for (int s : blk->successors) {
            if (s >= 0 && s < n) {
                auto* sb = mba_->getBlock(s);
                fprintf(stderr, " b%d(0x%lx)", s, (unsigned long)(sb?sb->start_addr:0));
            }
        }
        fprintf(stderr, "\n");
    }
    
    // Step 2: Loop detection — collect ALL back edges first, then merge body
    // from ALL back edges for each unique header.
    // Uses the original successors (not yet cleared) so the BFS that
    // verifies reachability can traverse through all blocks, including
    // those marked as dead (IPROP_DEAD) but whose successors are intact.
    // Reference: Ghidra BlockGraph::structureLoops() + CollapseStructure::labelLoops()
    loop_headers_.clear();
    loop_exits_.clear();
    loops_.clear();
    
    // First pass: collect all back edges using dominator tree + reachability
    // v34.0: Use the dominator tree (via dominates()) instead of the block-index
    // heuristic. The block-index heuristic (succ > i) misses back edges where
    // the source has a lower index than the destination (e.g., inner loop back
    // edges from block 18/21 to block 25 in JNI_OnLoad). The dominator check
    // is correct: if succ dominates i, then i -> succ is a back edge.
    //
    // v35.0: However, the dominator check is too strict for irreducible loops
    // where the loop body has multiple entry points (e.g., JNI_OnLoad's outer
    // loop where b21 is reachable from both b17 inside the loop and b9 outside).
    // In such cases, the loop header (b17) does NOT dominate all body blocks.
    // Fall back to the block-index heuristic (succ < i) for edges that pass
    // reachability but fail the dominator check. This handles irreducible loops
    // that Ghidra and r2 also detect.
    //
    // v11.4: Reachability uses forward BFS (via successors). The CFG has been
    // repaired by Step 1 (CFG repair fills in missing successors for dead blocks
    // that have predecessors but no successors), so the forward BFS can traverse
    // through blocks that were previously dead ends.
    struct BackEdge { int from; int to; };
    std::vector<BackEdge> allBackEdges;
    for (int i = 0; i < n; i++) {
        auto* blk = mba_->getBlock(i);
        if (!blk) continue;
        for (int succ : blk->successors) {
            if (succ < 0 || succ >= n) continue;
            // v34.0: Use dominator tree instead of block-index heuristic.
            // v35.0: Fall back to block-index heuristic (succ < i) for
            // irreducible loops where the dominator check fails.
            bool isBackEdge = dominates(succ, i);
            if (!isBackEdge) {
                // v35.0: For irreducible loops, use the block-index heuristic.
                // If succ < i, the edge goes to a block with a lower index,
                // which is a potential back edge. This is a heuristic that
                // works for both reducible and irreducible loops.
                // The old code used `succ < i` without the dominator check,
                // which worked for JNI_OnLoad's outer loop (b40->b7, b42->b7).
                if (succ < i) {
                    isBackEdge = true;
                }
            }
            if (!isBackEdge) continue;
            
            // Verify reachability: can we reach i from succ?
            // Uses forward BFS from succ (header) through successors to i.
            // The CFG has been repaired, so dead blocks like b20 now have
            // successors allowing the BFS to reach the back edge block.
            // v11.5: If forward BFS fails (e.g., dead blocks whose successors
            // couldn't be repaired), try backward BFS from i to succ using
            // predecessors. This is more robust because dead blocks with no
            // successors but valid predecessors won't break the backward BFS.
            bool reachesSrc = false;
            int visitedCount = 0;
            {
                std::set<int> visited;
                std::queue<int> q;
                q.push(succ);
                visited.insert(succ);
                while (!q.empty()) {
                    int cur = q.front(); q.pop();
                    visitedCount++;
                    if (cur == i) { reachesSrc = true; break; }
                    auto* cb = mba_->getBlock(cur);
                    if (!cb) continue;
                    for (int ns : cb->successors) {
                        if (ns < 0 || ns >= n) continue;
                        if (visited.count(ns)) continue;
                        visited.insert(ns);
                        q.push(ns);
                    }
                }
            }
            // v11.5: Fallback — backward BFS using predecessors
            if (!reachesSrc) {
                std::set<int> visited;
                std::queue<int> q;
                q.push(i);
                visited.insert(i);
                while (!q.empty()) {
                    int cur = q.front(); q.pop();
                    visitedCount++;
                    if (cur == succ) { reachesSrc = true; break; }
                    auto* cb = mba_->getBlock(cur);
                    if (!cb) continue;
                    for (int p : cb->predecessors) {
                        if (p < 0 || p >= n) continue;
                        if (visited.count(p)) continue;
                        visited.insert(p);
                        q.push(p);
                    }
                }
                DBG_PRINT("[DBG_BE] b%d->b%d: fwd_fail=1 back_reachable=%d visited=%d dom=%d\n", i, succ, reachesSrc, visitedCount, dominates(succ, i));
            } else {
                DBG_PRINT("[DBG_BE] b%d->b%d: reachable=%d visited=%d dom=%d\n", i, succ, reachesSrc, visitedCount, dominates(succ, i));
            }
            if (!reachesSrc) continue;
            
            allBackEdges.push_back({i, succ});
        }
    }
    
    // Second pass: for each unique header, merge body from ALL back edges
    // Sort by header to group back edges to the same header
    std::sort(allBackEdges.begin(), allBackEdges.end(),
        [](const BackEdge& a, const BackEdge& b) { return a.to < b.to; });
    
    // v13.0: Build a preliminary set of all loop headers (before body collection)
    // so the body collection for inner loops can exclude outer loop headers.
    std::map<int,int> headerToActual;  // header -> actualHeader
    // First pass: compute actualHeader for each unique header
    for (size_t beIdx = 0; beIdx < allBackEdges.size(); ) {
        int header = allBackEdges[beIdx].to;
        // Find the range
        size_t endIdx = beIdx;
        while (endIdx < allBackEdges.size() && allBackEdges[endIdx].to == header) {
            endIdx++;
        }
        // Compute actualHeader
        int actualHeader = header;
        {
            auto* hdrBlk = mba_->getBlock(header);
            if (hdrBlk && hdrBlk->tail && hdrBlk->tail->opcode != mc::OP_CBRANCH) {
                std::set<int> hdrVisited;
                std::queue<int> hq;
                hq.push(header);
                hdrVisited.insert(header);
                while (!hq.empty()) {
                    int cur = hq.front(); hq.pop();
                    auto* cb = mba_->getBlock(cur);
                    if (!cb) break;
                    if (cb->tail && cb->tail->opcode == mc::OP_CBRANCH) {
                        actualHeader = cur;
                        break;
                    }
                    if (cb->successors.size() == 1) {
                        int ns = cb->successors[0];
                        if (ns >= 0 && ns < n && !hdrVisited.count(ns)) {
                            hdrVisited.insert(ns);
                            hq.push(ns);
                        }
                    } else {
                        break;
                    }
                }
                if (actualHeader != header) {
                    fprintf(stderr, "[DBG_LOOP] corrected header: b%d -> b%d (CBRANCH at 0x%lx)\n",
                            header, actualHeader, (unsigned long)mba_->getBlock(actualHeader)->start_addr);
                }
            }
        }
        headerToActual[header] = actualHeader;
        loop_headers_.insert(actualHeader);
        beIdx = endIdx;
    }
    
    // Second pass: collect body for each unique header
    // v36.0: Process in REVERSE order (inner loops first, outer loops second).
    // Inner loops tend to have higher block indices, so processing back edges in
    // reverse order naturally processes inner loops before outer loops. This is
    // critical for the allLoopBodyBlocks tracking: inner loop body blocks must be
    // collected first, so that the outer loop can include them as shared blocks.
    // Without this, the outer loop's blocks are added to allLoopBodyBlocks first,
    // and the inner loop's backward BFS incorrectly skips blocks that are shared
    // between the two loops (e.g., blocks 56 and 68 in JNI_OnLoad).
    for (size_t beIdx = allBackEdges.size(); beIdx > 0; ) {
        --beIdx;
        int header = allBackEdges[beIdx].to;
        int actualHeader = headerToActual[header];
        
        // v18.1: Dominator check helper — only include blocks that are
        // dominated by the loop header. This prevents pre-header code
        // from being absorbed into the loop body.
        auto isDomAncestor = [&](int ancestor, int node) -> bool {
            if (ancestor == node) return true;
            if (ancestor < 0 || node < 0 || ancestor >= (int)idom_.size() || node >= (int)idom_.size())
                return false;
            if (idom_[node] == -2) return false; // not computed
            int cur = node;
            int maxDepth = (int)idom_.size() + 5;
            while (cur >= 0 && maxDepth-- > 0) {
                if (cur == ancestor) return true;
                if (cur >= (int)idom_.size() || idom_[cur] == cur) break;
                cur = idom_[cur];
            }
            return false;
        };
        
        NaturalLoop loop;
        loop.header = actualHeader;
        
        // Collect body from ALL back edges to this header
        // Uses the block's predecessors list (already maintained by the
        // microcode emitter). This is O(V+E) and handles non-contiguous
        // loop bodies correctly, unlike the old address-range bounded BFS
        // which could miss blocks whose addresses fell outside the range.
        //
        // v13.0: Exclude blocks that are loop headers of OTHER loops.
        // This prevents the inner loop from including the outer loop
        // header, while allowing the outer loop to collect a complete
        // body from all back edges.
        std::set<int> body;
        body.insert(actualHeader);  // actual header is always in the body
        // v27.0: Also add the original (pre-correction) header to the body.
        // The back edge goes to the original header, which then falls through
        // to the actual header. The original header is not found by the backward
        // BFS (which stops at the actual header) or the forward-reachability
        // check (which starts from the actual header). But it's part of the loop
        // because the back edge targets it. Without this, the successor-based
        // filtering (v27.0) would incorrectly remove blocks that have successors
        // to the original header (e.g., b40's successor b7).
        if (header != actualHeader) {
            body.insert(header);
        }
        
        bool is_do_while = false;
        int lastBackEdgeFrom = -1;
        
        // v36.0: Find the range of back edges to this header (reverse order).
        // In reverse iteration, we go backwards to find the start of the group.
        size_t startIdx = beIdx;
        while (startIdx > 0 && allBackEdges[startIdx - 1].to == header) {
            startIdx--;
        }
        
        // v45.0: Compute forward reachability from the loop header.
        // This is used to limit the backward BFS to blocks that are on
        // the forward path from the header. Blocks that are NOT reachable
        // from the header (e.g., pre-header blocks that are predecessors
        // of the header) should not have their predecessors traversed,
        // as they are not part of the natural loop body.
        // For example, for the inner loop with header=25, block 24 is a
        // predecessor of 25 but NOT reachable from 25 (24 → 25 is a forward
        // edge, but 25 does NOT have 24 as a successor). So 24 is a pre-header
        // block and should not be traversed through.
        std::set<int> reachableFromHeader;
        {
            std::queue<int> rq;
            rq.push(actualHeader);
            reachableFromHeader.insert(actualHeader);
            while (!rq.empty()) {
                int cur = rq.front(); rq.pop();
                auto* curBlk = mba_->getBlock(cur);
                if (!curBlk) continue;
                for (int s : curBlk->successors) {
                    if (s < 0 || s >= n) continue;
                    if (reachableFromHeader.count(s)) continue;
                    reachableFromHeader.insert(s);
                    rq.push(s);
                }
            }
        }
        fprintf(stderr, "[DBG_DOM] FP loop hdr=%d: reachableFromHeader.size=%zu\n", actualHeader, reachableFromHeader.size());
        
        // Process ALL back edges to this header, merging body from each
        for (size_t j = startIdx; j <= beIdx; j++) {
            int i = allBackEdges[j].from;
            auto* blk = mba_->getBlock(i);
            bool isCbr = (blk && blk->tail && blk->tail->opcode == mc::OP_CBRANCH);
            
            // Keep back_edge_from logic matching original behavior:
            // - First back edge: always set
            // - Subsequent back edges: only update if CBRANCH
            if (lastBackEdgeFrom < 0) {
                lastBackEdgeFrom = i;
                is_do_while = isCbr;
            } else if (isCbr) {
                lastBackEdgeFrom = i;
                is_do_while = true;
            }
            
            // Backward BFS from this back edge source to the header
            if (i != actualHeader) {
                std::queue<int> bq;
                std::set<int> visited;
                bq.push(i);
                visited.insert(i);
                if (!body.count(i)) body.insert(i);
                
                while (!bq.empty()) {
                    int cur = bq.front(); bq.pop();
                    auto* curBlk = mba_->getBlock(cur);
                    if (!curBlk) continue;
                    for (int p : curBlk->predecessors) {
                        if (p < 0 || p >= n) continue;
                        if (p == actualHeader) continue;  // don't cross the actual header
                        if (body.count(p)) continue;
                        if (visited.count(p)) continue;
                        // v43.0: When encountering another loop header, just add it
                        // to the body WITHOUT traversing through its predecessors.
                        // The old traversal (v36.0) caused the inner loop to include
                        // the outer loop's blocks (e.g., the entire function body
                        // was added to the inner loop's body because the backward
                        // BFS from b67 traversed through b17's predecessors).
                        //
                        // The correct approach: skip the traversal and let the
                        // post-processing step (v43.0 merge) merge inner loop bodies
                        // into outer loop bodies. This ensures each loop only
                        // collects blocks that are on the backward path to its own
                        // header, while nested loops still get their full body.
                        if (p != actualHeader && (loop_headers_.count(p) || headerToActual.count(p))) {
                            // v46.0: Don't add other loop headers to the body.
                            // Previously (v43.0), we added them to the body and
                            // relied on the merge step to merge inner loop bodies
                            // into outer loop bodies. However, this caused mutual
                            // inclusion (both loops contain each other's headers),
                            // which triggered the SKIP check in ruleDoWhile for
                            // both loops, creating a deadlock.
                            //
                            // The fix: DON'T add other loop headers to the body.
                            // The merge step now uses forward reachability to
                            // determine nesting (loop A contains loop B if B's
                            // header is reachable from A's header), and only
                            // merges in one direction (inner → outer).
                            fprintf(stderr, "[DBG_DOM] FP loop hdr=%d: skipping other loop header p=%d (no body addition, no traversal)\n", actualHeader, p);
                            visited.insert(p);
                            // body.insert(p);  // v46.0: DON'T add to body
                            continue;
                        }
                        // v45.0: Check if the predecessor is reachable from the loop
                        // header via forward edges. If not, the block is a pre-header
                        // block (e.g., block 24 for header 25) that should be added
                        // to the body without traversing its predecessors. This
                        // prevents the backward BFS from going through the pre-header
                        // chain all the way back to the entry block.
                        if (!reachableFromHeader.count(p)) {
                            // Not reachable from header — add to body without
                            // traversing predecessors.
                            fprintf(stderr, "[DBG_DOM] FP loop hdr=%d: p=%d adding via backward BFS (not reachable from header, no traversal)\n", actualHeader, p);
                            visited.insert(p);
                            body.insert(p);
                            continue;
                        }
                        fprintf(stderr, "[DBG_DOM] FP loop hdr=%d: p=%d adding via backward BFS\n", actualHeader, p);
                        visited.insert(p);
                        body.insert(p);
                        bq.push(p);
                    }
                }
            }
        }
        
        // v28.0: Fix loop exit filtering — check ALL successors, not ANY.
        // Previous versions (v26-v27) were too aggressive, removing legitimate
        // loop body blocks.
        //
        // Key insight: The forward-reachability check from the header already
        // ensures all blocks in `body` are reachable from the header. The
        // filtering should only remove blocks that are clearly NOT part of the
        // natural loop (i.e., blocks accidentally included by forward BFS).
        //
        // Rules:
        // 1. Blocks DOMINATED by the header: NEVER remove. These are guaranteed
        //    to be part of the natural loop by definition. This includes the
        //    loop header itself, return statements inside the loop, and loop
        //    exit blocks. These are all legitimate loop body blocks.
        //    Example: b26 (return inside loop) is dominated by b17, has no
        //    body successors, but is a legitimate loop body block.
        //    Example: b25 (inner loop header) is dominated by b17, has no
        //    body successors (it's the inner loop's CBRANCH), but is a
        //    legitimate loop body block.
        // 2. Blocks NOT dominated by the header: Only remove if ALL successors
        //    are outside the body. If ANY successor is inside the body, the
        //    block is a legitimate loop body block (e.g., a merge point or
        //    loop exit that also has internal successors).
        //    Example: b19 is not dominated by hdr=25, has succ 20 (outside)
        //    AND succ 21 (inside body). Keep it — it's a legitimate exit.
        // 3. The original header and actual header are always kept.
        //
        // Also exclude the ORIGINAL header (`header`, e.g., b7) and the
        // ACTUAL header (`actualHeader`, e.g., b17) from the "outside body"
        // check, so blocks like b40 (the back edge CBRANCH, successor b7)
        // are not removed.
        std::set<int> toRemove;
        for (int bid : body) {
            auto* b = mba_->getBlock(bid);
            if (!b) continue;
            // Always keep the loop headers (original and actual)
            if (bid == actualHeader || bid == header) {
                continue;
            }
            // v44.1: Keep blocks that are headers of OTHER loops.
            // This is needed for irreducible loops where the loop header
            // does NOT dominate all body blocks. The merge step (v43.0)
            // uses the relationship "loop[i].body contains loop[j].header"
            // to merge nested loops. If we remove other loop headers from
            // the body, the merge fails.
            if (loop_headers_.count(bid) || headerToActual.count(bid)) {
                continue;
            }
            // v44.1: Check if this block is dominated by the loop header.
            // For REDUCIBLE loops, all body blocks are dominated by the header.
            // For IRREDUCIBLE loops, this check may fail for legitimate body
            // blocks. Use the successor-based check as a fallback.
            if (isDomAncestor(actualHeader, bid)) {
                // Dominated by header — always keep.
                continue;
            }
            // Not dominated by header: only remove if ALL successors are
            // outside the body (the block was accidentally included by the
            // backward BFS through a path that goes outside the loop).
            if (b->successors.empty()) {
                toRemove.insert(bid);
                continue;
            }
            bool anyBodySucc = false;
            for (int succ : b->successors) {
                if (succ < 0 || succ >= n) continue;
                if (body.count(succ) || succ == actualHeader || succ == header) {
                    anyBodySucc = true;
                    break;
                }
            }
            if (!anyBodySucc) {
                toRemove.insert(bid);
                fprintf(stderr, "[DBG_DOM] FP loop hdr=%d: bid=%d removed from body (not dominated, all succ outside)\n", actualHeader, bid);
            }
        }
        for (int bid : toRemove) {
            body.erase(bid);
        }
        
        loop.back_edge_from = lastBackEdgeFrom;
        
        // v46.0: Keep is_do_while as determined by the back edge source.
        // Do NOT override based on the header's CBRANCH, because a loop
        // can have a header CBRANCH that branches within the body (e.g.,
        // the outer loop b17→b18/b19, both in body) or to the exit (e.g.,
        // the inner loop b25→b7 exit). The back edge source's CBRANCH is
        // the more reliable indicator of the loop's condition location.
        loop.is_do_while = is_do_while;
        loop.body = body;
        
        // Collect exits: successors of body blocks that leave the body
        // v31.0: Filter out dead blocks from loop exits. A dead block (e.g.,
        // b20 in JNI_OnLoad) has all instructions marked IPROP_DEAD and is
        // not a valid loop exit. Including dead blocks as loop exits causes
        // the DO_WHILE node's successors to include dead blocks, which then
        // become merge point candidates in the IF_ELSE Pattern 4 BFS.
        // This results in the IF_ELSE using the dead block as the merge point
        // instead of the actual loop header, causing the loop body to be
        // excluded from the structured tree.
        for (int bid : body) {
            auto* b = mba_->getBlock(bid);
            if (!b) continue;
            for (int s : b->successors) {
                if (s >= 0 && s < n && !body.count(s)) {
                    // Skip dead blocks as loop exits
                    auto* sBlk = mba_->getBlock(s);
                    if (sBlk) {
                        bool isDead = false;
                        for (auto* insn = sBlk->head; insn; insn = insn->next) {
                            if (insn->iprops & mc::IPROP_DEAD) { isDead = true; break; }
                        }
                        if (isDead) continue;
                    }
                    loop.exits.insert(s);
                }
            }
        }
        loops_.push_back(loop);
        
        // v36.0: In reverse iteration, move to the previous group
        beIdx = startIdx;
    }
    
    for (auto& loop : loops_) {
        loop_headers_.insert(loop.header);
        for (int e : loop.exits) loop_exits_.insert(e);
    }
    
    // v11.5: Sort loops so that inner loops (higher address) are processed
    // before outer loops (lower address). This ensures that when the fixpoint
    // loop collapses a loop, the inner loop's header is still a basic block
    // node (not yet consumed by the outer loop's collapse).
    // Reference: Ghidra CollapseStructure processes loops in reverse RPO
    // order, which naturally processes inner loops first.
    std::sort(loops_.begin(), loops_.end(),
        [](const NaturalLoop& a, const NaturalLoop& b) {
            return a.header > b.header;  // higher header address first
        });
    
    // v46.0: Merge inner loop bodies into outer loop bodies using
    // forward reachability. Since the backward BFS no longer adds
    // other loop headers to the body (v46.0), we can't rely on
    // body- contains-header checks. Instead, we use forward reachability:
    // loop A contains loop B if B's header is reachable from A's header
    // via forward edges.
    //
    // Important: Only merge in one direction (outer → inner). If both
    // headers are reachable from each other (irreducible), skip the merge
    // to prevent mutual inclusion deadlock.
    //
    // Since loops are sorted by header address (higher first = inner first),
    // we check: if the outer loop (lower address) can reach the inner loop
    // (higher address) via forward edges, merge the inner loop's body into
    // the outer loop.
    for (size_t i = 0; i < loops_.size(); i++) {
        for (size_t j = 0; j < loops_.size(); j++) {
            if (i == j) continue;
            // Only merge from outer (lower address) to inner (higher address)
            if (loops_[i].header < loops_[j].header) {
                // i is outer, j is inner — check if j's header is reachable from i's header
                bool reachable = false;
                {
                    std::queue<int> rq;
                    std::set<int> rvisited;
                    rq.push(loops_[i].header);
                    rvisited.insert(loops_[i].header);
                    while (!rq.empty() && !reachable) {
                        int cur = rq.front(); rq.pop();
                        auto* curBlk = mba_->getBlock(cur);
                        if (!curBlk) continue;
                        for (int s : curBlk->successors) {
                            if (s < 0 || s >= n) continue;
                            if (s == loops_[j].header) { reachable = true; break; }
                            if (rvisited.count(s)) continue;
                            rvisited.insert(s);
                            rq.push(s);
                        }
                    }
                }
                // Also check the reverse: is i's header reachable from j's header?
                // If both are reachable (irreducible), skip the merge.
                bool reverseReachable = false;
                if (reachable) {
                    std::queue<int> rq;
                    std::set<int> rvisited;
                    rq.push(loops_[j].header);
                    rvisited.insert(loops_[j].header);
                    while (!rq.empty() && !reverseReachable) {
                        int cur = rq.front(); rq.pop();
                        auto* curBlk = mba_->getBlock(cur);
                        if (!curBlk) continue;
                        for (int s : curBlk->successors) {
                            if (s < 0 || s >= n) continue;
                            if (s == loops_[i].header) { reverseReachable = true; break; }
                            if (rvisited.count(s)) continue;
                            rvisited.insert(s);
                            rq.push(s);
                        }
                    }
                }
                if (reachable && !reverseReachable) {
                    // j is nested inside i — merge inner body into outer body
                    fprintf(stderr, "[DBG_LOOP] merge: outer hdr=%d reachable to inner hdr=%d, merging %zu blocks into outer\n",
                            loops_[i].header, loops_[j].header, loops_[j].body.size());
                    // Also add the inner loop's header to the outer loop's body
                    loops_[i].body.insert(loops_[j].header);
                    for (int b : loops_[j].body) {
                        loops_[i].body.insert(b);
                    }
                    // Re-collect exits for the outer loop
                    loops_[i].exits.clear();
                    for (int bid : loops_[i].body) {
                        auto* b = mba_->getBlock(bid);
                        if (!b) continue;
                        for (int s : b->successors) {
                            if (s >= 0 && s < n && !loops_[i].body.count(s)) {
                                auto* sBlk = mba_->getBlock(s);
                                if (sBlk) {
                                    bool isDead = false;
                                    for (auto* insn = sBlk->head; insn; insn = insn->next) {
                                        if (insn->iprops & mc::IPROP_DEAD) { isDead = true; break; }
                                    }
                                    if (isDead) continue;
                                }
                                loops_[i].exits.insert(s);
                            }
                        }
                    }
                } else if (reachable && reverseReachable) {
                    fprintf(stderr, "[DBG_LOOP] irreducible: hdr=%d and hdr=%d mutually reachable, skipping merge\n",
                            loops_[i].header, loops_[j].header);
                }
            }
        }
    }
    
    // v11.4: Now safe to clear successors of dead blocks, since loop
    // detection is complete and no longer needs the original successors.
    // The buildInitial() below creates nodes for ALL blocks (including
    // dead ones), but dead blocks with no successors won't interfere.
    //
    // v51.0: Fix — only clear successors if ALL instructions in the block
    // are marked IPROP_DEAD. The optimizer (deadCodeElimination) marks
    // individual instructions as dead when their definitions are unused,
    // but this does NOT mean the entire block is dead. Checking "any
    // instruction is dead" incorrectly treats alive blocks as dead,
    // causing them to lose their successors and be excluded from the
    // initial node graph in buildInitial().
    for (int i = 0; i < n; i++) {
        auto* blk = mba_->getBlock(i);
        if (blk) {
            int liveCount = 0;
            int deadCount = 0;
            for (auto* insn = blk->head; insn; insn = insn->next) {
                if (insn->iprops & mc::IPROP_DEAD) deadCount++;
                else liveCount++;
            }
            bool allDead = (liveCount == 0 && deadCount > 0);
            if (allDead) {
                fprintf(stderr, "[DBG_CLR] b%d clearing succs (all %d insns dead, %d live)\n", i, deadCount, liveCount);
                blk->successors.clear();
            } else if (deadCount > 0) {
                // Only some instructions are dead — the block is still alive
                fprintf(stderr, "[DBG_CLR] b%d NOT clearing (only %d/%d dead, %d live)\n", i, deadCount, deadCount+liveCount, liveCount);
            }
        }
    }
    
    // Step 3: Build initial graph
    buildInitial();
    
    // v12.0 debug: verify initial node state for loop headers
    for (auto& loop : loops_) {
        int hdrNode = findNodeByBlock(loop.header);
        int backNode = findNodeByBlock(loop.back_edge_from);
        (void)hdrNode; (void)backNode;
        DBG_PRINT("[DBG_INIT] loop[%d]: hdr=%d->node%d type=%d back=%d->node%d\n",
                loop.header, loop.header, hdrNode,
                hdrNode >= 0 ? nodes_[hdrNode].type : -1,
                loop.back_edge_from, backNode);
    }
    // Also check node 12 and 25 specifically
    DBG_PRINT("[DBG_INIT] nodes_ size=%zu\n", nodes_.size());
    for (int ni = 0; ni < (int)nodes_.size() && ni < 70; ni++) {
        auto& n = nodes_[ni];
        if (n.blocks.count(17) || n.blocks.count(25) || n.blocks.count(12)) {
            DBG_PRINT("[DBG_INIT] node%d: blocks={", ni);
            for (int b : n.blocks) fprintf(stderr, "%d ", b);
            fprintf(stderr, "} type=%d\n", n.type);
        }
    }
    
    // v11.3 debug: dump loops and initial graph
    fprintf(stderr, "[DBG_LOOP] %zu loops detected:\n", loops_.size());
    for (auto& l : loops_) {
        fprintf(stderr, "  header=%d back=%d is_dw=%d body=%zu exits=[",
                l.header, l.back_edge_from, l.is_do_while, l.body.size());
        for (int e : l.exits) fprintf(stderr, "%d ", e);
        fprintf(stderr, "]\n");
    }
    fprintf(stderr, "[DBG_LOOP] %d initial nodes, loops in headers:",
            (int)nodes_.size());
    for (int h : loop_headers_) fprintf(stderr, " %d", h);
    fprintf(stderr, "\n");
    
    // v12.0 debug: verify node state before fixpoint loop starts
    DBG_PRINT("[DBG_FP] Fixpoint loop starting, nodes_=%zu\n", nodes_.size());
    for (auto& loop : loops_) {
        int hdrNode = findNodeByBlock(loop.header);
        DBG_PRINT("[DBG_FP] loop hdr=%d -> node%d (type=%d blocks={",
                loop.header, hdrNode, hdrNode >= 0 ? nodes_[hdrNode].type : -1);
        if (hdrNode >= 0) for (int b : nodes_[hdrNode].blocks) fprintf(stderr, "%d ", b);
        fprintf(stderr, "})\n");
    }
    
    // v53.0: Two-phase fixpoint loop with if-else pre-pass.
    // Reference: Ghidra CollapseStructure::collapseAll + collapseInternal
    //
    // Phase 0 (new in v53.0): Run if-else EXHAUSTIVELY before loop rules.
    // This ensures CBRANCH blocks inside loop bodies are structured as
    // IF_ELSE regions BEFORE the loop rules collapse them. Without this,
    // when the outer loop is collapsed, the inner CBRANCH blocks are
    // BASIC_BLOCK nodes that get absorbed into the loop region without
    // ever being processed by ruleIfElse — causing the conditional
    // branches inside the loop to be lost.
    //
    // ruleIfElse already has a safety check (v11.5, line 3355) that
    // skips CBRANCH blocks that are loop headers or whose successors
    // are loop headers. So it's safe to run before loop rules — loop
    // headers are protected and will still be found by the loop rules.
    //
    // Ghidra reference: ruleBlockIfElse runs before ruleBlockDoWhile
    // in the fixpoint loop. Our Phase 0 mirrors this ordering.
    //
    // Phase 1-5 (v22.0): Loop rules, then remaining structural rules.
    // Collapse/sequence still run AFTER ifElse (v20) to prevent the
    // collapse rule from merging a CBRANCH block with its predecessor.
    //
    // Rule order (v53.0):
    //   0. ifElse — structure CBRANCH blocks inside loop bodies FIRST
    //   1. doWhile/whileDo/for — detect loop patterns
    //   2. ifElse/switch — detect conditional patterns on remaining nodes
    //   3. collapse/sequence — merge chains
    //   4. blockCat/blockOr/blockGoto — merge remaining blocks & handle goto
    //   5. labelLoops — classify breaks/continues last
    int maxIter = 500;
    bool changed = true;
    while (changed && maxIter-- > 0) {
        changed = false;
        
        // Phase 0: Run if-else exhaustively to structure CBRANCH blocks
        // inside loop bodies BEFORE loop rules collapse them.
        // ruleIfElse already skips loop headers (v11.5), so this is safe.
        // Reference: Ghidra ruleBlockIfElse runs before ruleBlockDoWhile.
        {
            int ifElseGuard = 50;
            bool ifElseChanged = true;
            while (ifElseChanged && ifElseGuard-- > 0) {
                ifElseChanged = false;
                if (ruleIfElse()) { changed = true; ifElseChanged = true; }
            }
            if (changed) continue;
        }
        
        // Step 1: Detect loop patterns. After Phase 0, loop bodies already
        // have CBRANCH blocks structured as IF_ELSE regions, so the loop
        // rules will preserve the conditional structure inside the body.
        if (ruleDoWhile())       { changed = true; fprintf(stderr, "[DBG_RULE] doWhile\n"); continue; }
        if (ruleFor())           { changed = true; fprintf(stderr, "[DBG_RULE] for\n"); continue; }
        if (ruleWhileDo())       { changed = true; fprintf(stderr, "[DBG_RULE] whileDo\n"); continue; }
        // Step 2: Detect conditional patterns on remaining nodes.
        // v20: ifElse must run before collapse to prevent the collapse rule
        // from merging a CBRANCH block with its predecessor (e.g., b0→b1).
        if (ruleIfElse())        { changed = true; fprintf(stderr, "[DBG_RULE] ifElse\n"); continue; }
        if (ruleSwitch())        { changed = true; fprintf(stderr, "[DBG_RULE] switch\n"); continue; }
        // Step 3: Merge chains to expose clean branch patterns
        if (ruleCollapse())      { changed = true; fprintf(stderr, "[DBG_RULE] collapse\n"); continue; }
        if (ruleSequence())      { changed = true; fprintf(stderr, "[DBG_RULE] sequence\n"); continue; }
        // Step 4: Merge remaining blocks & handle goto edges
        if (ruleBlockGoto())     { changed = true; fprintf(stderr, "[DBG_RULE] blockGoto\n"); continue; }
        if (ruleBlockOr())       { changed = true; fprintf(stderr, "[DBG_RULE] blockOr\n"); continue; }
        if (ruleBlockCat())      { changed = true; fprintf(stderr, "[DBG_RULE] blockCat\n"); continue; }
        // Step 5: Classify loop exits/continues last
        if (ruleLabelLoops())    { changed = true; fprintf(stderr, "[DBG_RULE] labelLoops\n"); continue; }
        
        // v5.5: When all rules stall, use selectGoto to break the deadlock
        if (!changed && nodes_.size() > 1) {
            if (selectGoto()) { changed = true; fprintf(stderr, "[DBG_RULE] selectGoto\n"); continue; }
        }
    }
    
    // Build final region tree from remaining nodes
    auto result = buildRegionTree();
    // v11.3 debug: log node count after fixpoint
    if (result) {
        fprintf(stderr, "[DBG] Fixpoint: %zu nodes after fixpoint, tree type=%d\n",
                nodes_.size(), result->type);
        // Dump the tree for debugging
        std::string dump = result->dump();
        fprintf(stderr, "[DBG] Tree dump (%zu chars):\n%s\n", dump.size(), dump.c_str());
    }
    return result;
}

// ── Build the final region tree from the node graph ──
std::unique_ptr<Region> FixpointStructurer::buildRegionTree() {
    if (nodes_.empty()) return nullptr;
    
    // v5.8: Helper to check if a node's blocks contain a return or noreturn call.
    // Used to stop reachability traversal at return/noreturn nodes (dead code after).
    auto nodeHasReturn = [&](int node_id) -> bool {
        if (node_id < 0 || node_id >= (int)nodes_.size()) return false;
        for (int blk : nodes_[node_id].blocks) {
            auto* block = mba_->getBlock(blk);
            if (!block) continue;
            if (block->tail && block->tail->opcode == mc::OP_RET) return true;
            // v9.8: noreturn calls (abort, exit, __builtin_trap) also terminate
            if (block->tail && block->tail->opcode == mc::OP_CALL &&
                block->tail->call_info && !block->tail->call_info->has_return) {
                return true;
            }
        }
        return false;
    };
    
    // v5.8: Reachability check — only include nodes reachable from the entry.
    // Without this, unreachable code after return statements pollutes the output.
    // Start from the node containing block 0 (the entry block), not node index 0.
    std::set<int> reachable;
    std::queue<int> q;
    int entryNode = findNodeByBlock(0);
    if (entryNode < 0) {
        // Fallback: if block 0 is not in any node, use node 0
        entryNode = 0;
    }
    q.push(entryNode);
    reachable.insert(entryNode);
    while (!q.empty()) {
        int cur = q.front(); q.pop();
        if (cur < 0 || cur >= (int)nodes_.size()) continue;
        // v12.0: Relaxed nodeHasReturn check — only stop traversal if the
        // node is a TERMINAL node (has return AND no outgoing edges to other
        // nodes). The old check stopped traversal at ANY node containing a
        // return, which prevented the reachability from reaching collapsed
        // nodes (DO_WHILE, IF_ELSE) that follow the entry node.
        if (nodeHasReturn(cur)) {
            bool allSuccsInternal = true;
            for (int succ_blk : nodes_[cur].succs) {
                int succ_node = findNodeByBlock(succ_blk);
                if (succ_node >= 0 && succ_node != cur) {
                    allSuccsInternal = false;
                    break;
                }
            }
            if (allSuccsInternal) {
                DBG_PRINT("[DBG_BT] node%d has return and all succs internal, stopping\n", cur);
                continue;
            }
            DBG_PRINT("[DBG_BT] node%d has return but has external succs, continuing\n", cur);
        }
        for (int succ_blk : nodes_[cur].succs) {
            int succ_node = findNodeByBlock(succ_blk);
            if (succ_node >= 0 && !reachable.count(succ_node)) {
                reachable.insert(succ_node);
                q.push(succ_node);
            }
        }
    }
    
    // v59.0: Comprehensive orphaned-node recovery.
    // After the fixpoint rules run, the node graph may have nodes that are
    // not reachable from the entry via succs edges. This can happen when:
    //   - A rule collapses a CBRANCH node and its branches, but the new
    //     IF_ELSE/DO_WHILE node's succs only point to the merge point,
    //     causing other successors of the original CBRANCH to be orphaned.
    //   - A node's successor block was consumed by another node during
    //     ruleBlockCat merging, causing the BFS to lose the edge.
    //   - Structured regions (DO_WHILE, IF_ELSE) have their succs consumed
    //     by subsequent rules, making them unreachable from the entry.
    //
    // Strategy: recover ALL orphaned nodes whose blocks are not covered
    // by the reachable set, regardless of node type. This ensures no
    // blocks are lost from the output. Also recover structured regions
    // even if their blocks ARE covered (to preserve control flow structure).
    {
        // First pass: collect all blocks covered by current reachable set
        std::set<int> coveredBlocks;
        for (int nid : reachable) {
            for (int b : nodes_[nid].blocks)
                coveredBlocks.insert(b);
        }
        
        // Second pass: add orphaned nodes
        for (int i = 0; i < (int)nodes_.size(); i++) {
            if (reachable.count(i)) continue;
            if (nodes_[i].blocks.empty()) continue;
            
            bool hasUncoveredBlocks = false;
            for (int b : nodes_[i].blocks) {
                if (!coveredBlocks.count(b)) {
                    hasUncoveredBlocks = true;
                    break;
                }
            }
            
            // Add node if:
            // 1. It has any uncovered block (blocks would be lost), OR
            // 2. It's a structured region (preserve control flow structure)
            bool isStructured = (nodes_[i].region != nullptr);
            if (hasUncoveredBlocks || isStructured) {
                reachable.insert(i);
                DBG_PRINT("[DBG_BT] Added orphaned node%d (hasUncovered=%d isStructured=%d type=%d)\n",
                        i, hasUncoveredBlocks, isStructured, nodes_[i].type);
                // Update covered blocks for subsequent iterations
                for (int b : nodes_[i].blocks)
                    coveredBlocks.insert(b);
            }
        }
        
        // v59.0: Final safety check — verify ALL blocks from ALL nodes
        // are covered by the reachable set. If any block is missing,
        // force-add the node containing it.
        for (int i = 0; i < (int)nodes_.size(); i++) {
            if (reachable.count(i)) continue;
            if (nodes_[i].blocks.empty()) continue;
            bool anyMissing = false;
            for (int b : nodes_[i].blocks) {
                if (!coveredBlocks.count(b)) { anyMissing = true; break; }
            }
            if (anyMissing) {
                reachable.insert(i);
                DBG_PRINT("[DBG_BT] SAFETY: force-added orphaned node%d (blocks would be lost)\n", i);
                for (int b : nodes_[i].blocks) coveredBlocks.insert(b);
            }
        }
    }
    
    // Collect reachable nodes in control flow order (BFS from entry).
    // IMPORTANT: The order must follow the control flow, not the node index.
    // Without this, a successor node (e.g., b57 = loop exit) can appear
    // BEFORE its predecessor (e.g., the DO_WHILE loop itself) in the
    // sequence, producing incorrect C code where the exit block is
    // emitted before the loop body.
    //
    // v53.0: Sort successors by block address before BFS traversal. This
    // ensures that successors with lower addresses (earlier in control flow)
    // are visited before successors with higher addresses (later in control
    // flow, e.g., return blocks). Without this, node0's succs={13, 1} causes
    // the BFS to visit the return block (b13) before the loop body entry (b1),
    // producing an incorrect sequence where the return appears before the loop.
    //
    // v53.0: Also put RETURN blocks LAST in the sorted order. This ensures
    // that the return block is always at the end of the SEQUENCE, regardless
    // of its address. Without this, eliminateUnreachableCode in the beautifier
    // removes all statements after the return block (DO_WHILE, IF_ELSE, etc.).
    auto sortSuccsByAddr = [&](const std::vector<int>& succs) -> std::vector<int> {
        std::vector<int> sorted = succs;
        std::sort(sorted.begin(), sorted.end(), [&](int a, int b) {
            auto* blkA = mba_->getBlock(a);
            auto* blkB = mba_->getBlock(b);
            if (!blkA) return false;
            if (!blkB) return true;
            // v53.0: Put return blocks last in the BFS order
            bool aIsRet = blkA->tail && blkA->tail->opcode == mc::OP_RET;
            bool bIsRet = blkB->tail && blkB->tail->opcode == mc::OP_RET;
            if (aIsRet != bIsRet) return bIsRet;  // return blocks go last
            return blkA->start_addr < blkB->start_addr;
        });
        return sorted;
    };
    
    // Also dump all nodes' succs for debugging
    DBG_PRINT("[DBG_BT] ALL nodes (reachable=%zu):\n", reachable.size());
    for (int i = 0; i < (int)nodes_.size(); i++) {
        if (!reachable.count(i)) continue;
        fprintf(stderr, "  node%d type=%d has_region=%d blocks={", i, nodes_[i].type, nodes_[i].region != nullptr);
        for (int b : nodes_[i].blocks) fprintf(stderr, "%d ", b);
        fprintf(stderr, "} succs={");
        for (int s : nodes_[i].succs) fprintf(stderr, "%d ", s);
        fprintf(stderr, "}\n");
    }
    
    // Build reachableNodes in BFS order from the entry node.
    // v59.0: Single BFS traversal (removed duplicate BFS that was in the else branch).
    // The single BFS handles both single-node and multi-node cases.
    // v59.0: Also collect orphaned nodes that were added to reachable but
    // not visited by the BFS (because their succs edges are broken).
    std::vector<int> reachableNodes;
    {
        std::set<int> ordered;
        std::queue<int> orderQ;
        orderQ.push(entryNode);
        ordered.insert(entryNode);
        while (!orderQ.empty()) {
            int cur = orderQ.front(); orderQ.pop();
            if (cur < 0 || cur >= (int)nodes_.size()) continue;
            if (!reachable.count(cur)) continue;
            reachableNodes.push_back(cur);
            DBG_PRINT("[DBG_BT]  reachable: node%d type=%d has_region=%d blocks={", 
                    cur, nodes_[cur].type, nodes_[cur].region != nullptr);
            for (int b : nodes_[cur].blocks) fprintf(stderr, "%d ", b);
            fprintf(stderr, "} succs={");
            for (int s : nodes_[cur].succs) fprintf(stderr, "%d ", s);
            fprintf(stderr, "}\n");
            // v53.0: Sort successors by block address for correct BFS order
            auto sortedSuccs = sortSuccsByAddr(nodes_[cur].succs);
            for (int succ_blk : sortedSuccs) {
                int succ_node = findNodeByBlock(succ_blk);
                if (succ_node >= 0 && !ordered.count(succ_node)) {
                    ordered.insert(succ_node);
                    orderQ.push(succ_node);
                }
            }
        }
        // v59.0: Add orphaned nodes that are reachable but not visited by BFS
        // (because their succs edges are broken or point to already-visited nodes).
        for (int i = 0; i < (int)nodes_.size(); i++) {
            if (reachable.count(i) && !ordered.count(i)) {
                reachableNodes.push_back(i);
                ordered.insert(i);
                DBG_PRINT("[DBG_BT]  added orphaned node%d (reachable but not visited by BFS)\n", i);
            }
        }
    }
    
    // v24.3: 增强 RETURN 块排序 — 移动所有 TERMINAL 节点
    // (包括被 fixpoint 规则合并进 Sequence 的节点) 到 reachableNodes 末尾。
    // v59.0 修复只检查 REGION_BASIC_BLOCK 且 region 为空的节点，
    // 遗漏了被 ruleSequence/ruleCollapse/ruleBlockCat 合并的 RETURN 块。
    //
    // 对标 Ghidra CollapseStructure::ActionBlockStructure:
    // Ghidra 在 BlockGraph 排序阶段使用结构化区域图的拓扑排序，
    // 天然将 terminal RETURN 块放在序列末尾。我们的 fixpoint 规则
    // 运行后的 BFS 顺序可能违反这个排序，因为 RETURN 块作为入口
    // 节点的直接后继会过早被访问。
    //
    // v24.3 修复: 检查所有节点（不限于 BASIC_BLOCK），如果节点
    // 包含 RETURN 且该 RETURN 块自身的后继为空（即 RETURN 块本身
    // 是 terminal），则移到末尾。注意这里检查的是 RETURN 块自身
    // 的 successors，而不是节点的 succs（节点 succs 可能来自其他
    // 被合并的块，如 fixpoint 规则将多个块合并到一个节点时，
    // 非 RETURN 块的后继会被保留）。
    // 如果 RETURN 块有外部后继（如 DO_WHILE 体内部包含 return，
    // 循环体的后继仍然存在），则保留在原位置。
    {
        std::vector<int> nonReturn, returnNodes;
        for (int nid : reachableNodes) {
            bool isRet = false;
            bool retBlockHasExternalSuccs = false;
            for (int b : nodes_[nid].blocks) {
                auto* blk = mba_->getBlock(b);
                if (blk && blk->tail && blk->tail->opcode == mc::OP_RET) {
                    isRet = true;
                    // 检查 RETURN 块自身的 successors（块级别，不是节点级别）
                    // 如果 RETURN 块有外部后继（指向其他节点的块），则不是 terminal
                    for (int succ_blk : blk->successors) {
                        int succ_node = findNodeByBlock(succ_blk);
                        if (succ_node >= 0 && succ_node != nid && reachable.count(succ_node)) {
                            retBlockHasExternalSuccs = true;
                            break;
                        }
                    }
                    // 如果某个 RETURN 块是 terminal，我们就找到了目标
                    if (!retBlockHasExternalSuccs) {
                        break;
                    }
                }
            }
            // 只移动 TERMINAL 的 RETURN 节点到末尾
            // 条件：节点包含 RETURN 块，且该 RETURN 块自身没有外部后继
            // v24.3 DEBUG
            if (isRet) {
                fprintf(stderr, "[DBG_BT_RET] node%d: hasExternalSuccs=%d blocks={", nid, retBlockHasExternalSuccs);
                for (int b : nodes_[nid].blocks) fprintf(stderr, "%d ", b);
                fprintf(stderr, "}\n");
            }
            if (isRet && !retBlockHasExternalSuccs) {
                returnNodes.push_back(nid);
            } else {
                nonReturn.push_back(nid);
            }
        }
        fprintf(stderr, "[DBG_BT_RET] BEFORE: reachableNodes=[");
        for (int nid : reachableNodes) fprintf(stderr, "%d ", nid);
        fprintf(stderr, "]\n");
        nonReturn.insert(nonReturn.end(), returnNodes.begin(), returnNodes.end());
        reachableNodes = std::move(nonReturn);
        fprintf(stderr, "[DBG_BT_RET] AFTER: reachableNodes=[");
        for (int nid : reachableNodes) fprintf(stderr, "%d ", nid);
        fprintf(stderr, "]\n");
    }
    
    if (reachableNodes.size() == 1) {
        int id = reachableNodes[0];
        if (nodes_[id].region)
            return std::move(nodes_[id].region);
        auto bb = std::make_unique<Region>(REGION_BASIC_BLOCK);
        bb->block_id = *nodes_[id].blocks.begin();
        bb->start_addr = nodes_[id].start_addr;
        return bb;
    }
    
    // Multiple nodes: build a sequence of reachable nodes only
    // v20: Deduplicate by tracking which blocks have been seen. Skip nodes
    // whose blocks are already covered by a previously processed node,
    // preventing duplicate code in the output.
    // v59.0: Don't skip nodes that have structured regions (DO_WHILE, IF_ELSE,
    // etc.), even if their blocks are already covered. Structured regions are
    // legitimate control flow structures that must be preserved in the output.
    auto seq = std::make_unique<Region>(REGION_SEQUENCE);
    std::set<int> seenBlocks;
    for (int id : reachableNodes) {
        auto& node = nodes_[id];
        // Check if all blocks in this node are already covered
        bool allBlocksSeen = true;
        for (int b : node.blocks) {
            if (!seenBlocks.count(b)) { allBlocksSeen = false; break; }
        }
        if (allBlocksSeen && !node.blocks.empty()) {
            // v59.0: Don't skip structured regions — they preserve control flow
            // structure that would be lost if skipped.
            if (!node.region) {
                DBG_PRINT("[DBG_BT] Skipping node%d (blocks already covered, no region)\n", id);
                continue;
            }
            DBG_PRINT("[DBG_BT] Keeping node%d (blocks covered but has region, type=%d)\n", id, node.region->type);
        }
        // Mark blocks as seen
        for (int b : node.blocks) seenBlocks.insert(b);
        
        if (node.region)
            addRegionFlatten(seq, node.region->clone());
        else {
            auto bb = std::make_unique<Region>(REGION_BASIC_BLOCK);
            bb->block_id = *node.blocks.begin();
            bb->start_addr = node.start_addr;
            seq->children.push_back(std::move(bb));
        }
    }
    return seq;
}

std::unique_ptr<Region> FixpointStructurer::nodeToRegion(int node_id, std::set<int>& visited) {
    if (node_id < 0 || node_id >= (int)nodes_.size()) return nullptr;
    if (visited.count(node_id)) return nullptr;
    visited.insert(node_id);
    
    auto& node = nodes_[node_id];
    if (node.region) return node.region->clone();
    
    auto bb = std::make_unique<Region>(REGION_BASIC_BLOCK);
    bb->block_id = *node.blocks.begin();
    bb->start_addr = node.start_addr;
    return bb;
}

} // namespace cfg