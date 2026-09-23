// microcode_opt.cpp — Microcode optimization passes (Pass 7–12)
// 对标 Ghidra ruleaction.cc / RuleAlgebraicSimplify, RuleCopyPropagation,
// RuleConstant, RuleMultInverse, RuleStoreLoadForward, RuleBitwise.
//
// Passes 1–6 live in ssa.cpp; this file implements the additional passes
// declared in microcode_opt.hpp.

#include "microcode_opt.hpp"

#include <algorithm>
#include <cstdint>
#include <map>
#include <set>
#include <vector>

namespace mc {

// ──────────────────────────────────────────────────────────────────────
// Internal helpers
// ──────────────────────────────────────────────────────────────────────

// Compute the "all ones" value for a given byte width.
// width=8 → -1 (0xFFFFFFFFFFFFFFFF), width=4 → 0xFFFFFFFF, etc.
static inline int64_t allOnesValue(int width) {
    if (width >= 8) return -1;
    return ((int64_t)1 << (width * 8)) - 1;
}

// Compute the bit mask of a given byte width.
// width=8 → 0xFFFFFFFFFFFFFFFF, width=4 → 0x00000000FFFFFFFF, etc.
static inline uint64_t widthMask(int width) {
    if (width >= 8) return ~0ULL;
    return ((uint64_t)1 << (width * 8)) - 1;
}

// True if `imm` represents all-ones for the given width.
static inline bool isAllOnesImm(int64_t imm, int width) {
    return ((uint64_t)imm & widthMask(width)) == widthMask(width);
}

// Count how many times `mreg` is read (used) across the whole function.
// Uses are gathered via MicroInsn::getUseMregs(); one entry per operand use.
static int countGlobalUses(MicrocodeBlockArray& mba, int mreg) {
    int count = 0;
    for (int b = 0; b < mba.numBlocks(); b++) {
        for (MicroInsn* i = mba.blocks[b]->head; i; i = i->next) {
            if (i->isDead()) continue;
            auto uses = i->getUseMregs();
            for (int m : uses)
                if (m == mreg) count++;
        }
    }
    return count;
}

// Find the instruction that defines `mreg` earlier in the same block
// (scanning backwards from `before`). Returns nullptr if not found.
static MicroInsn* findLocalDef(MicroBlock* blk, MicroInsn* before, int mreg) {
    (void)blk;
    for (MicroInsn* i = before->prev; i; i = i->prev) {
        if (i->isDead()) continue;
        if (i->def_mreg == mreg) return i;
    }
    return nullptr;
}

// ──────────────────────────────────────────────────────────────────────
// Pass 7: Algebraic simplification (对标 Ghidra RuleAlgebraicSimplify)
//   x + 0 → x, x - 0 → x, x * 0 → 0, x * 1 → x
//   x - x → 0, x | x → x, x & x → x, x ^ x → 0
//   x | 0 → x, x & 0 → 0, x ^ 0 → x
//   x | 0xFFFF...F → 0xFFFF...F, x & 0xFFFF...F → x
// ──────────────────────────────────────────────────────────────────────
void algebraicSimplify(MicrocodeBlockArray& mba) {
    for (int b = 0; b < mba.numBlocks(); b++) {
        for (MicroInsn* insn = mba.blocks[b]->head; insn; insn = insn->next) {
            if (insn->isDead()) continue;
            // v5.1: Never simplify control flow instructions
            if (insn->opcode == OP_CBRANCH || insn->opcode == OP_CALL ||
                insn->opcode == OP_ICALL || insn->opcode == OP_RET ||
                insn->opcode == OP_GOTO || insn->opcode == OP_JTBL) continue;
            int w = insn->d.width;
            switch (insn->opcode) {
                case OP_ADD:
                    // x + 0 → mov x, l   (r == 0)
                    if (insn->r.isImm() && insn->r.imm == 0) {
                        insn->opcode = OP_MOV;
                        insn->r = Mop();
                        break;
                    }
                    // 0 + x → mov x, r   (l == 0)
                    if (insn->l.isImm() && insn->l.imm == 0) {
                        insn->opcode = OP_MOV;
                        insn->l = insn->r;
                        insn->r = Mop();
                        break;
                    }
                    break;

                case OP_SUB:
                    // x - 0 → mov x, l
                    if (insn->r.isImm() && insn->r.imm == 0) {
                        insn->opcode = OP_MOV;
                        insn->r = Mop();
                        break;
                    }
                    // x - x → ld x, #0
                    if (insn->l.isReg() && insn->r.isReg() &&
                        insn->l.mreg == insn->r.mreg) {
                        insn->opcode = OP_LDC;
                        insn->l = Mop::imm64(0, w);
                        insn->r = Mop();
                        break;
                    }
                    break;

                case OP_MUL:
                    // x * 0 → ld x, #0
                    if ((insn->r.isImm() && insn->r.imm == 0) ||
                        (insn->l.isImm() && insn->l.imm == 0)) {
                        insn->opcode = OP_LDC;
                        insn->l = Mop::imm64(0, w);
                        insn->r = Mop();
                        break;
                    }
                    // x * 1 → mov x, l   (r == 1)
                    if (insn->r.isImm() && insn->r.imm == 1) {
                        insn->opcode = OP_MOV;
                        insn->r = Mop();
                        break;
                    }
                    // 1 * x → mov x, r   (l == 1)
                    if (insn->l.isImm() && insn->l.imm == 1) {
                        insn->opcode = OP_MOV;
                        insn->l = insn->r;
                        insn->r = Mop();
                        break;
                    }
                    break;

                case OP_OR:
                    // x | x → mov x, l
                    if (insn->l.isReg() && insn->r.isReg() &&
                        insn->l.mreg == insn->r.mreg) {
                        insn->opcode = OP_MOV;
                        insn->r = Mop();
                        break;
                    }
                    // x | 0 → mov x, l   (r == 0)
                    if (insn->r.isImm() && insn->r.imm == 0) {
                        insn->opcode = OP_MOV;
                        insn->r = Mop();
                        break;
                    }
                    // 0 | x → mov x, r   (l == 0)
                    if (insn->l.isImm() && insn->l.imm == 0) {
                        insn->opcode = OP_MOV;
                        insn->l = insn->r;
                        insn->r = Mop();
                        break;
                    }
                    // x | 0xFFFF...F → ld x, #0xFFFF...F   (r == all-ones)
                    if (insn->r.isImm() && isAllOnesImm(insn->r.imm, w)) {
                        insn->opcode = OP_LDC;
                        insn->l = Mop::imm64(allOnesValue(w), w);
                        insn->r = Mop();
                        break;
                    }
                    // 0xFFFF...F | x → ld x, #0xFFFF...F   (l == all-ones)
                    if (insn->l.isImm() && isAllOnesImm(insn->l.imm, w)) {
                        insn->opcode = OP_LDC;
                        insn->l = Mop::imm64(allOnesValue(w), w);
                        insn->r = Mop();
                        break;
                    }
                    break;

                case OP_AND:
                    // x & x → mov x, l
                    if (insn->l.isReg() && insn->r.isReg() &&
                        insn->l.mreg == insn->r.mreg) {
                        insn->opcode = OP_MOV;
                        insn->r = Mop();
                        break;
                    }
                    // x & 0 → ld x, #0   (r == 0)
                    if (insn->r.isImm() && insn->r.imm == 0) {
                        insn->opcode = OP_LDC;
                        insn->l = Mop::imm64(0, w);
                        insn->r = Mop();
                        break;
                    }
                    // 0 & x → ld x, #0   (l == 0)
                    if (insn->l.isImm() && insn->l.imm == 0) {
                        insn->opcode = OP_LDC;
                        insn->l = Mop::imm64(0, w);
                        insn->r = Mop();
                        break;
                    }
                    // x & 0xFFFF...F → mov x, l   (r == all-ones)
                    if (insn->r.isImm() && isAllOnesImm(insn->r.imm, w)) {
                        insn->opcode = OP_MOV;
                        insn->r = Mop();
                        break;
                    }
                    // 0xFFFF...F & x → mov x, r   (l == all-ones)
                    if (insn->l.isImm() && isAllOnesImm(insn->l.imm, w)) {
                        insn->opcode = OP_MOV;
                        insn->l = insn->r;
                        insn->r = Mop();
                        break;
                    }
                    break;

                case OP_XOR:
                    // x ^ x → ld x, #0
                    if (insn->l.isReg() && insn->r.isReg() &&
                        insn->l.mreg == insn->r.mreg) {
                        insn->opcode = OP_LDC;
                        insn->l = Mop::imm64(0, w);
                        insn->r = Mop();
                        break;
                    }
                    // x ^ 0 → mov x, l   (r == 0)
                    if (insn->r.isImm() && insn->r.imm == 0) {
                        insn->opcode = OP_MOV;
                        insn->r = Mop();
                        break;
                    }
                    // 0 ^ x → mov x, r   (l == 0)
                    if (insn->l.isImm() && insn->l.imm == 0) {
                        insn->opcode = OP_MOV;
                        insn->l = insn->r;
                        insn->r = Mop();
                        break;
                    }
                    break;

                default:
                    break;
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────
// Pass 8: Copy propagation (对标 Ghidra RuleCopyPropagation)
//   mov x1, x0; ... use x1 → replace x1 with x0
//   Follows chains of MOV instructions (block-local, with invalidation).
// ──────────────────────────────────────────────────────────────────────
void copyPropagation(MicrocodeBlockArray& mba) {
    for (int b = 0; b < mba.numBlocks(); b++) {
        MicroBlock* blk = mba.blocks[b].get();
        // copyOf[x] = y  means register x currently holds the same value as y.
        std::map<int, int> copyOf;

        // Path-compressing root finder with cycle guard.
        auto findRoot = [&](int m) -> int {
            int root = m;
            std::set<int> seen;
            while (copyOf.count(root) && seen.insert(root).second)
                root = copyOf[root];
            return root;
        };

        // Invalidate `m`: it is being redefined, so the old copy is stale and
        // any copy that depended on `m` (copyOf[k] == m) is also stale.
        auto invalidate = [&](int m) {
            copyOf.erase(m);
            std::vector<int> stale;
            for (auto& kv : copyOf)
                if (kv.second == m) stale.push_back(kv.first);
            for (int k : stale) copyOf.erase(k);
        };

        // Rewrite a register operand to its copy root.
        auto rewriteUse = [&](Mop& m) {
            if (m.type == MOP_REG && m.mreg >= 0) {
                int root = findRoot(m.mreg);
                if (root != m.mreg) m.mreg = root;
            }
        };

        for (MicroInsn* insn = blk->head; insn; insn = insn->next) {
            if (insn->isDead()) continue;

            // v5.1: Never rewrite control flow instructions' operands.
            // CBRANCH conditions must not be copy-propagated — they encode
            // the actual comparison at the machine level, and rewriting
            // them causes "if (0 == 0)" and "if (x == x)" false folding.
            // CALL arguments must not be rewritten either — they use
            // argument registers (x0-x7) which are set by the caller.
            bool isControlFlow = (insn->opcode == OP_CBRANCH ||
                                  insn->opcode == OP_CALL ||
                                  insn->opcode == OP_ICALL ||
                                  insn->opcode == OP_RET ||
                                  insn->opcode == OP_GOTO ||
                                  insn->opcode == OP_JTBL);

            // 1) Rewrite uses first (before handling the def).
            if (!isControlFlow) {
                if (insn->opcode == OP_STORE || insn->opcode == OP_FSTORE) {
                    // store: l is the value (use), d is the memory address.
                    rewriteUse(insn->l);
                    if (insn->d.type == MOP_MEM && insn->d.mem_base >= 0) {
                        int root = findRoot(insn->d.mem_base);
                        if (root != insn->d.mem_base) insn->d.mem_base = root;
                    }
                } else if (insn->opcode != OP_PHI) {
                    rewriteUse(insn->l);
                    rewriteUse(insn->r);
                }
            }

            // 2) Handle the def.
            if (!isControlFlow && insn->def_mreg >= 0) {
                int defd = insn->def_mreg;
                // defd is being (re)defined — invalidate old copies.
                invalidate(defd);
                // If this is mov reg, reg, record the new copy relationship.
                if (insn->opcode == OP_MOV && insn->l.isReg() && insn->l.mreg >= 0) {
                    int src = findRoot(insn->l.mreg);
                    if (src != defd)
                        copyOf[defd] = src;
                }
            }

            // v5.1: CALL instructions invalidate ALL copies because the
            // callee may clobber any caller-saved register.
            if (insn->opcode == OP_CALL || insn->opcode == OP_ICALL) {
                copyOf.clear();
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────
// Pass 9: Constant folding (对标 Ghidra RuleConstant)
//   add x, 2, 3 → ld x, #5
//   mul x, 4, 6 → ld x, #24
//   and x, 0xFF, 0x10 → ld x, #0x10
//   shl/shr/sar with two immediates are folded as well.
// ──────────────────────────────────────────────────────────────────────
void constantFolding(MicrocodeBlockArray& mba) {
    for (int b = 0; b < mba.numBlocks(); b++) {
        for (MicroInsn* insn = mba.blocks[b]->head; insn; insn = insn->next) {
            if (insn->isDead()) continue;
            // v5.1: Never fold control flow instructions
            if (insn->opcode == OP_CBRANCH || insn->opcode == OP_CALL ||
                insn->opcode == OP_ICALL || insn->opcode == OP_RET ||
                insn->opcode == OP_GOTO || insn->opcode == OP_JTBL) continue;
            if (!insn->l.isImm() || !insn->r.isImm()) continue;

            int64_t a = insn->l.imm;
            int64_t c = insn->r.imm;
            int64_t result = 0;
            bool ok = true;

            switch (insn->opcode) {
                case OP_ADD: result = a + c; break;
                case OP_SUB: result = a - c; break;
                case OP_MUL: result = a * c; break;
                case OP_AND: result = a & c; break;
                case OP_OR:  result = a | c; break;
                case OP_XOR: result = a ^ c; break;
                // Logical/shift amount masked to 0..63 to avoid UB.
                case OP_SHL: result = (int64_t)((uint64_t)a << (c & 63)); break;
                case OP_SHR: result = (int64_t)((uint64_t)a >> (c & 63)); break;
                case OP_SAR: result = a >> (c & 63); break; // arithmetic (g++)
                default: ok = false; break;
            }
            if (!ok) continue;

            insn->opcode = OP_LDC;
            insn->l = Mop::imm64(result, insn->d.width);
            insn->r = Mop();
        }
    }
}

// ──────────────────────────────────────────────────────────────────────
// Pass 10: Multiplication inverse optimization (对标 Ghidra RuleMultInverse)
//   x * 2^n → x << n
//   x / 2^n → x >> n  (unsigned division, OP_UDIV)
// ──────────────────────────────────────────────────────────────────────
void multInverseOpt(MicrocodeBlockArray& mba) {
    for (int b = 0; b < mba.numBlocks(); b++) {
        for (MicroInsn* insn = mba.blocks[b]->head; insn; insn = insn->next) {
            if (insn->isDead()) continue;
            // v5.1: Never optimize control flow instructions
            if (insn->opcode == OP_CBRANCH || insn->opcode == OP_CALL ||
                insn->opcode == OP_ICALL || insn->opcode == OP_RET ||
                insn->opcode == OP_GOTO || insn->opcode == OP_JTBL) continue;
            if (!insn->r.isImm()) continue;

            int64_t c = insn->r.imm;
            // Only positive powers of two with n >= 1 are worthwhile
            // (c == 1, i.e. 2^0, is handled by algebraicSimplify as x*1 → x).
            if (c < 2) continue;
            if ((c & (c - 1)) != 0) continue; // not a power of two

            // Compute n = log2(c).
            int n = 0;
            int64_t tmp = c;
            while (tmp > 1) { tmp >>= 1; n++; }

            if (insn->opcode == OP_MUL) {
                insn->opcode = OP_SHL;
                insn->r = Mop::imm64(n, insn->r.width);
            } else if (insn->opcode == OP_UDIV) {
                insn->opcode = OP_SHR;
                insn->r = Mop::imm64(n, insn->r.width);
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────
// Pass 11: Store-Load forwarding (对标 Ghidra RuleStoreLoadForward)
//   store x0, [sp+8]; ... load x1, [sp+8] → mov x1, x0
//   Forwarded only when no intervening store to the same address, no call,
//   and the base/value registers are not redefined before the load.
// ──────────────────────────────────────────────────────────────────────
void storeLoadForward(MicrocodeBlockArray& mba) {
    for (int b = 0; b < mba.numBlocks(); b++) {
        for (MicroInsn* store = mba.blocks[b]->head; store; store = store->next) {
            if (store->isDead()) continue;
            if (store->opcode != OP_STORE && store->opcode != OP_FSTORE) continue;
            if (store->d.type != MOP_MEM) continue;
            // Only forward register or immediate values.
            if (!store->l.isReg() && !store->l.isImm()) continue;

            int   base     = store->d.mem_base;
            int64_t off    = store->d.mem_offset;
            int   width    = store->d.width;
            int   val_mreg = store->l.isReg() ? store->l.mreg : -1;
            int64_t val_imm = store->l.isImm() ? store->l.imm : 0;

            // Scan forward within the same block for a matching load.
            for (MicroInsn* scan = store->next; scan; scan = scan->next) {
                if (scan->isDead()) continue;

                // A call may modify arbitrary memory — stop searching.
                if (scan->isCall()) break;

                // Any intervening store to the *same* address overwrites the
                // value we wanted to forward — stop.
                if (scan->opcode == OP_STORE || scan->opcode == OP_FSTORE) {
                    if (scan->d.type == MOP_MEM &&
                        scan->d.mem_base == base &&
                        scan->d.mem_offset == off) {
                        break;
                    }
                    // A store to a different address is conservatively treated
                    // as non-aliasing (safe for distinct stack slots) — continue.
                    continue;
                }

                // If the base register is redefined, the address no longer
                // matches — stop.
                if (scan->def_mreg == base) break;
                // If the value register is redefined before the load, the MOV
                // would deliver the wrong (new) value — stop.
                if (val_mreg >= 0 && scan->def_mreg == val_mreg) break;

                // Look for a load from the exact same address + width.
                if ((scan->opcode == OP_LOAD || scan->opcode == OP_FLOAD) &&
                    scan->l.type == MOP_MEM &&
                    scan->l.mem_base == base &&
                    scan->l.mem_offset == off &&
                    scan->l.width == width) {
                    // Forward: load x1, [base+off] → mov x1, x0  (or ldc).
                    if (val_mreg >= 0) {
                        scan->opcode = OP_MOV;
                        scan->l = Mop::reg(val_mreg, width);
                    } else {
                        scan->opcode = OP_LDC;
                        scan->l = Mop::imm64(val_imm, width);
                    }
                    scan->r = Mop();
                    scan->iprops &= ~IPROP_LOAD;
                    break; // only forward the first matching load
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────
// Pass 12: Bit manipulation simplification (对标 Ghidra RuleBitwise)
//   (x << n) >> n → x & mask            (unsigned, OP_SHL then OP_SHR)
//   x & ~x → 0                          (OP_AND with one operand = NOT of other)
//   x | ~x → ~0                         (OP_OR  with one operand = NOT of other)
// ──────────────────────────────────────────────────────────────────────
void bitSimplify(MicrocodeBlockArray& mba) {
    for (int b = 0; b < mba.numBlocks(); b++) {
        MicroBlock* blk = mba.blocks[b].get();
        for (MicroInsn* insn = blk->head; insn; insn = insn->next) {
            if (insn->isDead()) continue;
            // v5.1: Never simplify control flow instructions
            if (insn->opcode == OP_CBRANCH || insn->opcode == OP_CALL ||
                insn->opcode == OP_ICALL || insn->opcode == OP_RET ||
                insn->opcode == OP_GOTO || insn->opcode == OP_JTBL) continue;

            // ── Pattern 1: (x << n) >> n → x & mask ──
            //   shl t, x, n
            //   shr d, t, n     →  and d, x, (full_mask >> n)
            if (insn->opcode == OP_SHR &&
                insn->l.isReg() && insn->r.isImm()) {
                int t = insn->l.mreg;
                int64_t n = insn->r.imm;
                MicroInsn* shf = findLocalDef(blk, insn, t);
                if (shf && shf->opcode == OP_SHL &&
                    shf->l.isReg() && shf->r.isImm() &&
                    shf->r.imm == n) {
                    // Only safe to rewrite (and drop the shl) if `t` is used
                    // solely by this shr.
                    if (countGlobalUses(mba, t) == 1) {
                        int x = shf->l.mreg;
                        int w = insn->d.width;
                        int bits = w * 8;
                        uint64_t full = widthMask(w);
                        uint64_t mask = (n >= bits) ? 0ULL : (full >> n);
                        insn->opcode = OP_AND;
                        insn->l = Mop::reg(x, w);
                        insn->r = Mop::imm64((int64_t)mask, w);
                        shf->iprops |= IPROP_DEAD;
                    }
                }
            }

            // ── Pattern 2 & 3: x & ~x → 0,  x | ~x → ~0 ──
            //   not t, x        (t = ~x)
            //   and d, x, t     →  ldc d, #0
            //   or  d, x, t     →  ldc d, #~0
            if ((insn->opcode == OP_AND || insn->opcode == OP_OR) &&
                insn->l.isReg() && insn->r.isReg()) {
                int a = insn->l.mreg;
                int c = insn->r.mreg;
                int w = insn->d.width;
                MicroInsn* defA = findLocalDef(blk, insn, a);
                MicroInsn* defC = findLocalDef(blk, insn, c);
                bool match = false;

                // Case: r = NOT(l's source)  →  not c, a  (i.e. t=c, x=a)
                if (defC && defC->opcode == OP_NOT &&
                    defC->l.isReg() && defC->l.mreg == a) {
                    match = true;
                }
                // Case: l = NOT(r's source)  →  not a, c  (i.e. t=a, x=c)
                if (!match && defA && defA->opcode == OP_NOT &&
                    defA->l.isReg() && defA->l.mreg == c) {
                    match = true;
                }

                if (match) {
                    if (insn->opcode == OP_AND) {
                        // x & ~x → 0
                        insn->opcode = OP_LDC;
                        insn->l = Mop::imm64(0, w);
                        insn->r = Mop();
                    } else {
                        // x | ~x → ~0
                        insn->opcode = OP_LDC;
                        insn->l = Mop::imm64(allOnesValue(w), w);
                        insn->r = Mop();
                    }
                }
            }
        }
    }
}

} // namespace mc
