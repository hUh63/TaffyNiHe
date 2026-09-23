#pragma once
// ctree_beautify.hpp — CTree beautification/optimization passes (declarations)
// 对标 Ghidra C code generation + r2 analysis
// Real implementations are in ctree_impl.cpp

#include "ctree.hpp"
#include <algorithm>
#include <set>
#include <stack>
#include <vector>
#include <cstdio>

// Debug control
#ifndef DBG_CFG_VERBOSE
#define DBG_CFG_VERBOSE 1
#endif
#if DBG_CFG_VERBOSE
#define DBG_PRINT(...) fprintf(stderr, __VA_ARGS__)
#else
#define DBG_PRINT(...) ((void)0)
#endif

namespace ctree {

// ── All beautify functions are declared here and defined in ctree_impl.cpp ──

// v4.9: eliminate unreachable code after return/break/continue
void eliminateUnreachableCode(StmtPtr& root);

// v3.15: propagateCopies — Single-use temporary variable inlining
void propagateCopies(StmtPtr& root);

// v3.16: fuseAddressComputation — fuse GOT-based address calculations
void fuseAddressComputation(StmtPtr& root);

// v3.15: foldConstants — constant folding on CTree expressions
void foldConstants(StmtPtr& root);

// v5.1: eliminateConstantConditions — remove if(0) etc.
void eliminateConstantConditions(StmtPtr& root);

// v3.15: recoverCompoundAssign — x = x + 1 → x += 1
void recoverCompoundAssign(StmtPtr& root);

// v3.15: recoverTernary — if(x) y = a; else y = b; → y = x ? a : b;
void recoverTernary(StmtPtr& root);

// v3.15: recoverShortCircuit — recover && and || from nested ifs
void recoverShortCircuit(StmtPtr& root);

// mergeConsecutiveIfs — merge if(x) if(y) → if(x && y)
void mergeConsecutiveIfs(StmtPtr& root);

// promoteDoWhileToWhile — convert do-while to while where possible
void promoteDoWhileToWhile(StmtPtr& root);

// mergeNestedIfs — merge nested ifs with same condition
void mergeNestedIfs(StmtPtr& root);

// foldSwitchFromIfChain — convert if-else chains to switch
void foldSwitchFromIfChain(StmtPtr& root);

// normalizeLoops — normalize loop structures
void normalizeLoops(StmtPtr& root);

// minimizeCasts — remove redundant casts
void minimizeCasts(StmtPtr& root);

// eliminateDeadAssigns — remove assignments to unused variables
void eliminateDeadAssigns(StmtPtr& root);

// eliminateEmptyBranches — remove if/else where both branches are empty
void eliminateEmptyBranches(StmtPtr& root);

// eliminateUselessStmts — remove null/empty statements
void eliminateUselessStmts(StmtPtr& root);

// Helper to count statements in a block
static int countStmts(const StmtPtr& r) {
    if (!r || r->type != NT_BLOCK) return -1;
    return (int)((Block*)r.get())->statements.size();
}

// ─── Entry point ───
inline void beautify(StmtPtr& root) {
    DBG_PRINT("[DBG_BS] start: %d stmts\n", countStmts(root));
    eliminateUnreachableCode(root); // v4.9: remove dead code after return first
    DBG_PRINT("[DBG_BS] after elimUnreachable: %d stmts\n", countStmts(root));
    propagateCopies(root);       // v3.15: inline temporaries first
    DBG_PRINT("[DBG_BS] after propagateCopies: %d stmts\n", countStmts(root));
    fuseAddressComputation(root); // v3.16: fuse GOT+ADD address calculations
    foldConstants(root);
    DBG_PRINT("[DBG_BS] after foldConstants: %d stmts\n", countStmts(root));
    eliminateConstantConditions(root); // v5.1: remove if(0==0) etc.
    DBG_PRINT("[DBG_BS] after elimConstCond: %d stmts\n", countStmts(root));
    recoverCompoundAssign(root);
    recoverTernary(root);
    recoverShortCircuit(root);
    mergeConsecutiveIfs(root);
    promoteDoWhileToWhile(root);
    mergeNestedIfs(root);
    foldSwitchFromIfChain(root);
    normalizeLoops(root);
    minimizeCasts(root);
    DBG_PRINT("[DBG_BS] before elimDeadAssigns: %d stmts\n", countStmts(root));
    eliminateDeadAssigns(root);
    DBG_PRINT("[DBG_BS] after elimDeadAssigns: %d stmts\n", countStmts(root));
    eliminateEmptyBranches(root);
    eliminateUselessStmts(root);
    DBG_PRINT("[DBG_BS] after elimUseless: %d stmts\n", countStmts(root));

    // Run again after empty branch removal may expose new opportunities
    eliminateUnreachableCode(root); // v4.9: BS may expose new unreachable paths
    eliminateConstantConditions(root); // v5.1: re-check after simplification
    DBG_PRINT("[DBG_BS] after 2nd elimConstCond: %d stmts\n", countStmts(root));
    eliminateDeadAssigns(root);
    DBG_PRINT("[DBG_BS] after 2nd elimDeadAssigns: %d stmts\n", countStmts(root));
    eliminateEmptyBranches(root);
    DBG_PRINT("[DBG_BS] final: %d stmts\n", countStmts(root));
}

} // namespace ctree