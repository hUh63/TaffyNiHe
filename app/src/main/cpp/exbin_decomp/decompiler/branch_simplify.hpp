#pragma once
// branch_simplify.hpp — 空分支消除 + 条件翻转增强 (v3.7)
//
// 移植自 fcd ast/pass_consecutivecombine.cpp 的 ConsecutiveCombiner。
// 核心: 空体/恒真恒假/否定消除/同条件合并/反条件合并/do-while→while

#include "ctree.hpp"
#include <utility>

namespace ctree {

class BranchSimplifier {
public:
    // 入口: 对 root 做递归简化
    void simplify(StmtPtr& root);

private:
    // ── 处理 Block: 递归子语句 + 序列优化 + 清理 ──
    void cleanupBlock(Block* blk);
    void optimizeSequence(std::vector<StmtPtr>& stmts);
    void mergeBody(Block* target, StmtPtr body);

    // ── 处理单个 if (原地修改 root) ──
    void processIf(StmtPtr& root);

    // ── 处理循环 ──
    void processWhile(StmtPtr& root);
    void processDoWhile(StmtPtr& root);

    // ── 表达式比较工具 ──
    bool exprEqual(const Expr* a, const Expr* b);
    bool exprOpposite(const Expr* a, const Expr* b);
    std::pair<const Expr*, bool> countNegationDepth(const Expr* expr);
    ExprPtr negate(ExprPtr cond);
    bool isConstTrue(const Expr* e);
    bool isConstFalse(const Expr* e);
    bool isEmptyBody(const StmtPtr& body);
};

} // namespace ctree
