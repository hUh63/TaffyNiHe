// ir_optimizer.hpp — v8.7 Enhanced IR optimization passes
// 常量折叠、代数化简、拷贝传播、死代码消除 (增强版)
// 对标 Ghidra Rules + 自定义增强

#pragma once
#include "microcode.hpp"
#include <functional>

namespace mc {

// ── Pass 1: Enhanced constant folding ──
// 折叠双常量运算，包括内存操作中的常量偏移
void constantFoldingV2(MicrocodeBlockArray& mba);

// ── Pass 2: Enhanced algebraic simplification ──
// 处理 x+0, x-0, x*0, x*1, x-x, x|0, x&0, x^x, x<<0, x>>0 等
void algebraicSimplifyV2(MicrocodeBlockArray& mba);

// ── Pass 3: Enhanced copy propagation ──
// 跨块复制传播，处理 phi 节点
void copyPropagationV2(MicrocodeBlockArray& mba);

// ── Pass 4: Enhanced dead code elimination ──
// 消除死代码，包括未使用的内存操作
void deadCodeEliminationV2(MicrocodeBlockArray& mba);

// ── Pass 5: Memory load folding ──
// 当 base 为常量 0 时，将 LOAD 替换为 0 (null dereference)
// 当 offset 可折叠时简化
void memoryLoadFolding(MicrocodeBlockArray& mba);

// ── Pass 6: Store-after-store elimination ──
// 连续写入同一地址时，消除前面的 store
void storeAfterStoreElimination(MicrocodeBlockArray& mba);

// ── Pass 7: Conditional branch simplification ──
// 当条件为常量时，将 CBRANCH 替换为 GOTO 或 NOP
void branchSimplification(MicrocodeBlockArray& mba);

// ── Pass 8: Phi node simplification ──
// 移除只有一个源或所有源相同的 phi 节点
void phiSimplification(MicrocodeBlockArray& mba);

// ── Pass 9: Unused parameter elimination ──
// 标记从未被读取的参数寄存器为死
void unusedParamElimination(MicrocodeBlockArray& mba);

// ── Pass 10: Cross-block constant propagation ──
// 跨块常量传播：phi 所有源解析为同一常量时替换为 OP_LDC，
// 并将 OP_LDC/MOV(imm) 定义的常量传播到所有使用点（跨块有效）。
// 对标 Ghidra 规则引擎的常量传播。
void crossBlockConstantPropagation(MicrocodeBlockArray& mba);

// ── Pass 11: Strength reduction ──
// 强度削减：x * 2^n → x << n，x / 2^n (unsigned) → x >> n
void strengthReduction(MicrocodeBlockArray& mba);

// ── Pass 12: Redundant load elimination ──
// 冗余加载消除：同一地址在块内连续 load 且中间无 store/call 时，
// 将后续 load 替换为从首次 load 结果的 MOV。
void redundantLoadElimination(MicrocodeBlockArray& mba);

// ── 组合优化入口 ──
// 运行所有 v2 优化 pass，固定点迭代直到收敛
void optimizeV2(MicrocodeBlockArray& mba);

} // namespace mc