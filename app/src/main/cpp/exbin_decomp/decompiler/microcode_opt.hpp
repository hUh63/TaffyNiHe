#pragma once
// microcode_opt.hpp — Microcode optimization passes
// 对标 Hex-Rays MMAT_PREOPTIMIZED ~ MMAT_LOCOPT

#include "microcode.hpp"

namespace mc {

// Pass 1: Redundant mov elimination
// If mov x0, x1 and x0 is not used later (or all uses can be replaced with x1),
// delete the mov and replace uses.
void eliminateRedundantMov(MicrocodeBlockArray& mba);

// Pass 2: Local constant propagation
// Block-level: if ld x0, #imm, replace subsequent uses of x0 with imm.
// Cross-block: use SSA def-use chains. If phi inputs all same constant, replace.
void localConstantPropagation(MicrocodeBlockArray& mba);

// Pass 3: Dead store elimination
// If store x0, [addr] and x0 is never loaded later (via memory SSA), delete.
void deadStoreElimination(MicrocodeBlockArray& mba);

// Pass 4: Dead code elimination
// If instruction's def has no use, delete it (except side-effect instructions).
void deadCodeElimination(MicrocodeBlockArray& mba);

// Pass 5: Expression folding (algebraic simplification)
// add x, x, 0 -> mov x, x (then eliminated by Pass 1)
// mul x, x, 1 -> mov x, x
// sub x, a, a -> ld x, #0
// etc.
void foldExpressions(MicrocodeBlockArray& mba);

// Pass 6: Condition code elimination (ARM-specific)
// Combine cmp + b.eq into cbranch (x0 == x1) directly.
void eliminateConditionCodes(MicrocodeBlockArray& mba);

// Run all optimization passes to fixed point (bounded iterations)
inline void optimize(MicrocodeBlockArray& mba) {
    for (int iter = 0; iter < 10; iter++) {
        int before = 0;
        for (auto& b : mba.blocks)
            for (auto* i = b->head; i; i = i->next)
                if (!i->isDead()) before++;

        eliminateRedundantMov(mba);
        localConstantPropagation(mba);
        deadStoreElimination(mba);
        deadCodeElimination(mba);
        foldExpressions(mba);
        eliminateConditionCodes(mba);

        int after = 0;
        for (auto& b : mba.blocks)
            for (auto* i = b->head; i; i = i->next)
                if (!i->isDead()) after++;

        if (after >= before) break;  // fixed point
    }
    mba.maturity = MMAT_GLBOPT3;
}

} // namespace mc
