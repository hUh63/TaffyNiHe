// branch_simplify.cpp — Branch simplification pass (v3.7)
//
// 移植自 fcd ast/pass_consecutivecombine.cpp 的 ConsecutiveCombiner。
// 实现: 空分支消除、条件翻转、恒真恒假消除、否定消除、同条件合并、
//       反条件合并、do-while → while 提升。
//
// 参考: /data/user/work/refs/fcd/fcd/ast/pass_consecutivecombine.cpp

#include "branch_simplify.hpp"
#include "ctree_beautify.hpp"
#include <algorithm>

namespace ctree {

// ════════════════════════════════════════════════════════════════════
// BranchSimplifier implementation
// ════════════════════════════════════════════════════════════════════

// ── 入口: 对 root 做递归简化 ──
void BranchSimplifier::simplify(StmtPtr& root) {
    if (!root) return;

    switch (root->type) {
        case NT_BLOCK: {
            auto* blk = static_cast<Block*>(root.get());
            // 先递归每个子语句 (自底向上)
            for (auto& s : blk->statements) {
                simplify(s);
            }
            // 对当前 block 做序列优化 (合并连续 if 等)
            optimizeSequence(blk->statements);
            // 清理空 Block (简化可能产生空 Block)
            cleanupBlock(blk);
            break;
        }
        case NT_IF: {
            auto* ifn = static_cast<If*>(root.get());
            simplify(ifn->then_branch);
            simplify(ifn->else_branch);
            // 处理单个 if (空体/恒真恒假/否定消除)
            processIf(root);
            break;
        }
        case NT_WHILE: {
            auto* wh = static_cast<While*>(root.get());
            simplify(wh->body);
            processWhile(root);
            break;
        }
        case NT_DO_WHILE: {
            auto* dw = static_cast<DoWhile*>(root.get());
            simplify(dw->body);
            processDoWhile(root);
            break;
        }
        case NT_FOR: {
            auto* fr = static_cast<For*>(root.get());
            simplify(fr->body);
            break;
        }
        case NT_SWITCH: {
            auto* sw = static_cast<Switch*>(root.get());
            for (auto& [val, body] : sw->cases) {
                simplify(body);
            }
            simplify(sw->default_body);
            break;
        }
        default:
            break;
    }
}

// ── 清理 Block 中的空语句 ──
void BranchSimplifier::cleanupBlock(Block* blk) {
    std::vector<StmtPtr> result;
    result.reserve(blk->statements.size());
    for (auto& s : blk->statements) {
        if (!s) continue;
        // 保留非空 Block 和所有非 Block 语句
        if (s->type == NT_BLOCK) {
            auto* sub = static_cast<Block*>(s.get());
            if (!sub->statements.empty()) {
                // 非空 Block: 如果只有一个语句, 提升它
                if (sub->statements.size() == 1) {
                    result.push_back(std::move(sub->statements[0]));
                } else {
                    result.push_back(std::move(s));
                }
            }
            // 空 Block: 丢弃
        } else {
            result.push_back(std::move(s));
        }
    }
    blk->statements = std::move(result);
}

// ── 处理语句序列: 合并连续 if ──
void BranchSimplifier::optimizeSequence(std::vector<StmtPtr>& stmts) {
    if (stmts.size() < 2) return;

    std::vector<StmtPtr> result;
    result.reserve(stmts.size());

    for (size_t i = 0; i < stmts.size(); i++) {
        if (!stmts[i]) continue;

        // 只处理 if 语句的合并
        if (stmts[i]->type == NT_IF && i + 1 < stmts.size() &&
            stmts[i + 1] && stmts[i + 1]->type == NT_IF) {

            auto* if1 = static_cast<If*>(stmts[i].get());
            auto* if2 = static_cast<If*>(stmts[i + 1].get());

            // 情况1: 相同条件 → 合并 body
            // if(c){a} if(c){b} → if(c){a;b}
            if (!if1->else_branch && !if2->else_branch &&
                exprEqual(if1->condition.get(), if2->condition.get())) {

                auto mergedIf = std::make_unique<If>();
                mergedIf->condition = std::move(if1->condition);

                auto mergedThen = std::make_unique<Block>();
                mergeBody(mergedThen.get(), std::move(if1->then_branch));
                mergeBody(mergedThen.get(), std::move(if2->then_branch));
                mergedIf->then_branch = std::move(mergedThen);

                result.push_back(std::move(mergedIf));
                i++;  // 跳过 if2
                continue;
            }

            // 情况2: 相反条件 → 合并为 if-else
            // if(c){a} if(!c){b} → if(c){a}else{b}
            if (!if1->else_branch && !if2->else_branch &&
                exprOpposite(if1->condition.get(), if2->condition.get())) {

                auto mergedIf = std::make_unique<If>();
                auto [core1, neg1] = countNegationDepth(if1->condition.get());
                if (neg1) {
                    // if1 条件是 !c → 用 if2 的正条件
                    mergedIf->condition = std::move(if2->condition);
                    mergedIf->then_branch = std::move(if2->then_branch);
                    mergedIf->else_branch = std::move(if1->then_branch);
                } else {
                    // if1 条件是 c → 用 c
                    mergedIf->condition = std::move(if1->condition);
                    mergedIf->then_branch = std::move(if1->then_branch);
                    mergedIf->else_branch = std::move(if2->then_branch);
                }

                result.push_back(std::move(mergedIf));
                i++;  // 跳过 if2
                continue;
            }
        }

        result.push_back(std::move(stmts[i]));
    }

    stmts = std::move(result);
}

// ── 合并 body 到目标 Block ──
void BranchSimplifier::mergeBody(Block* target, StmtPtr body) {
    if (!body) return;
    if (body->type == NT_BLOCK) {
        auto* sub = static_cast<Block*>(body.get());
        for (auto& s : sub->statements) {
            target->statements.push_back(std::move(s));
        }
    } else {
        target->statements.push_back(std::move(body));
    }
}

// ── 处理单个 if 语句 (原地修改 root) ──
void BranchSimplifier::processIf(StmtPtr& root) {
    if (!root || root->type != NT_IF) return;
    auto* ifn = static_cast<If*>(root.get());

    // v5.8: Collapse redundant if-continue/break inside loops.
    // if(c){continue}else{continue} → continue
    // if(c){break}else{break} → break
    if (ifn->then_branch && ifn->else_branch) {
        if (ifn->then_branch->type == NT_CONTINUE &&
            ifn->else_branch->type == NT_CONTINUE) {
            root = std::make_unique<Continue>();
            return;
        }
        if (ifn->then_branch->type == NT_BREAK &&
            ifn->else_branch->type == NT_BREAK) {
            root = std::make_unique<Break>();
            return;
        }
    }

    // v6.1: Remove redundant "else continue" in loop bodies.
    // In a loop, "if (c) { body } else { continue; }" is equivalent to
    // "if (c) { body }" because falling through to the loop condition
    // is the same as continue. Ghidra's ActionPreventContinue performs
    // this simplification.
    if (ifn->then_branch && ifn->else_branch &&
        ifn->else_branch->type == NT_CONTINUE &&
        ifn->then_branch->type != NT_CONTINUE) {
        ifn->else_branch = nullptr;
        // Re-wrap in block if needed (simplifyIf handles single-stmt if)
        return;
    }
    // Also handle: if (c) { continue; } else { body } → if (!c) { body }
    // where the continue is redundant in a loop
    if (ifn->then_branch && ifn->else_branch &&
        ifn->then_branch->type == NT_CONTINUE &&
        ifn->else_branch->type != NT_CONTINUE) {
        // Negate condition, swap branches
        auto newIf = std::make_unique<If>();
        newIf->condition = negate(std::move(ifn->condition));
        newIf->then_branch = std::move(ifn->else_branch);
        root = std::move(newIf);
        return;
    }

    // 1. 检查条件是否恒真/恒假
    if (isConstTrue(ifn->condition.get())) {
        // if(1){then}else{else} → then
        if (ifn->then_branch) {
            root = std::move(ifn->then_branch);
        } else {
            root = std::make_unique<Block>();
        }
        return;
    }
    if (isConstFalse(ifn->condition.get())) {
        // if(0){then}else{else} → else
        if (ifn->else_branch) {
            root = std::move(ifn->else_branch);
        } else {
            root = std::make_unique<Block>();
        }
        return;
    }

    // 2. 检查 then/else 是否为空
    bool thenEmpty = isEmptyBody(ifn->then_branch);
    bool elseEmpty = isEmptyBody(ifn->else_branch);

    if (thenEmpty && elseEmpty) {
        // 两者都空 → 删除整个 if
        root = std::make_unique<Block>();
        return;
    }

    if (thenEmpty && !elseEmpty) {
        // if(c){}else{body} → if(!c){body}
        auto newIf = std::make_unique<If>();
        newIf->condition = negate(std::move(ifn->condition));
        newIf->then_branch = std::move(ifn->else_branch);
        root = std::move(newIf);
        return;
    }

    // 3. 否定消除:
    //   if(!!c){a}else{b} → if(c){a}else{b}  (偶数层否定 → 去除所有 !!, 保持分支)
    //   if(!c){a}else{b}  → if(c){b}else{a}  (奇数层否定 → 去除 !, 交换分支)
    if (ifn->else_branch != nullptr) {
        auto [core, isNegated] = countNegationDepth(ifn->condition.get());
        // depth >= 1 时才需要处理 (core != 原表达式)
        if (core != ifn->condition.get() && core) {
            auto newIf = std::make_unique<If>();
            // 重建 core 表达式: 从原条件中逐层剥离 !
            ExprPtr newCond = std::move(ifn->condition);
            // 剥离所有 !
            while (newCond && newCond->type == NT_UNARY_OP) {
                auto* u = static_cast<UnaryOp*>(newCond.get());
                if (u->op != "!") break;
                newCond = std::move(u->operand);
            }
            newIf->condition = std::move(newCond);
            if (isNegated) {
                // 奇数层: 交换 then/else
                newIf->then_branch = std::move(ifn->else_branch);
                newIf->else_branch = std::move(ifn->then_branch);
            } else {
                // 偶数层: 保持分支
                newIf->then_branch = std::move(ifn->then_branch);
                newIf->else_branch = std::move(ifn->else_branch);
            }
            root = std::move(newIf);
            return;
        }
    }

    // 4. 无简化机会 → 保持不变
}

// ── 处理 while 循环 ──
void BranchSimplifier::processWhile(StmtPtr& /*root*/) {
    // while 循环暂无特殊优化
}

// ── 处理 do-while 循环 ──
// do { if(cond){ body } } while(cond) → while(cond){ body }
void BranchSimplifier::processDoWhile(StmtPtr& root) {
    if (!root || root->type != NT_DO_WHILE) return;
    auto* doNode = static_cast<DoWhile*>(root.get());

    if (!doNode->body || doNode->body->type != NT_BLOCK) return;
    auto* blk = static_cast<Block*>(doNode->body.get());
    if (blk->statements.size() != 1) return;
    if (blk->statements[0]->type != NT_IF) return;

    auto* innerIf = static_cast<If*>(blk->statements[0].get());
    if (innerIf->else_branch != nullptr) return;  // 必须无 else

    // 条件必须相同
    if (!exprEqual(innerIf->condition.get(), doNode->condition.get())) return;

    // 转换: do { if(cond){body} } while(cond) → while(cond){body}
    auto newWhile = std::make_unique<While>();
    newWhile->condition = std::move(doNode->condition);
    newWhile->body = std::move(innerIf->then_branch);
    root = std::move(newWhile);
}

// ════════════════════════════════════════════════════════════════════
// 表达式比较工具
// ════════════════════════════════════════════════════════════════════

bool BranchSimplifier::exprEqual(const Expr* a, const Expr* b) {
    if (!a && !b) return true;
    if (!a || !b) return false;
    if (a->type != b->type) return false;

    switch (a->type) {
        case NT_CONST: {
            auto* ca = static_cast<const Const*>(a);
            auto* cb = static_cast<const Const*>(b);
            return ca->is_fp == cb->is_fp &&
                   ca->int_val == cb->int_val &&
                   ca->fp_val == cb->fp_val;
        }
        case NT_VAR_REF: {
            auto* va = static_cast<const VarRef*>(a);
            auto* vb = static_cast<const VarRef*>(b);
            return va->name == vb->name && va->var_id == vb->var_id;
        }
        case NT_BINARY_OP: {
            auto* ba = static_cast<const BinaryOp*>(a);
            auto* bb = static_cast<const BinaryOp*>(b);
            return ba->op == bb->op &&
                   exprEqual(ba->left.get(), bb->left.get()) &&
                   exprEqual(ba->right.get(), bb->right.get());
        }
        case NT_UNARY_OP: {
            auto* ua = static_cast<const UnaryOp*>(a);
            auto* ub = static_cast<const UnaryOp*>(b);
            return ua->op == ub->op &&
                   ua->is_prefix == ub->is_prefix &&
                   exprEqual(ua->operand.get(), ub->operand.get());
        }
        case NT_ASSIGN: {
            auto* aa = static_cast<const Assign*>(a);
            auto* ab = static_cast<const Assign*>(b);
            return aa->op == ab->op &&
                   exprEqual(aa->target.get(), ab->target.get()) &&
                   exprEqual(aa->value.get(), ab->value.get());
        }
        case NT_STRING: {
            auto* sa = static_cast<const StringConst*>(a);
            auto* sb = static_cast<const StringConst*>(b);
            return sa->value == sb->value;
        }
        case NT_NULL:
            return true;
        default:
            return a->toString() == b->toString();
    }
}

bool BranchSimplifier::exprOpposite(const Expr* a, const Expr* b) {
    if (!a || !b) return false;

    // 检查 a == !b 或 b == !a
    auto [coreA, negA] = countNegationDepth(a);
    auto [coreB, negB] = countNegationDepth(b);

    // 如果 a 和 b 的核心相同, 但否定层数奇偶性不同 → 相反
    if (exprEqual(coreA, coreB) && (negA != negB)) {
        return true;
    }

    // 检查 BinaryOp 的相反运算符: a < b vs a >= b
    if (a->type == NT_BINARY_OP && b->type == NT_BINARY_OP) {
        auto* ba = static_cast<const BinaryOp*>(a);
        auto* bb = static_cast<const BinaryOp*>(b);
        if (exprEqual(ba->left.get(), bb->left.get()) &&
            exprEqual(ba->right.get(), bb->right.get())) {
            static const std::map<std::string, std::string> opposite = {
                {"==", "!="}, {"!=", "=="},
                {"<", ">="}, {">=", "<"},
                {">", "<="}, {"<=", ">"}
            };
            auto it = opposite.find(ba->op);
            if (it != opposite.end() && it->second == bb->op) {
                return true;
            }
        }
    }

    return false;
}

std::pair<const Expr*, bool> BranchSimplifier::countNegationDepth(const Expr* expr) {
    int depth = 0;
    const Expr* cur = expr;

    while (cur && cur->type == NT_UNARY_OP) {
        auto* u = static_cast<const UnaryOp*>(cur);
        if (u->op != "!") break;
        depth++;
        cur = u->operand.get();
    }

    return {cur, (depth % 2) == 1};
}

ExprPtr BranchSimplifier::negate(ExprPtr cond) {
    if (!cond) return std::make_unique<Const>(0);

    // 如果是 !x, 去掉否定
    if (cond->type == NT_UNARY_OP) {
        auto* u = static_cast<UnaryOp*>(cond.get());
        if (u->op == "!") {
            return std::move(u->operand);
        }
    }

    // 如果是 BinaryOp 的可取反运算符, 直接翻转运算符
    if (cond->type == NT_BINARY_OP) {
        auto* b = static_cast<BinaryOp*>(cond.get());
        static const std::map<std::string, std::string> negateOp = {
            {"==", "!="}, {"!=", "=="},
            {"<", ">="}, {">=", "<"},
            {">", "<="}, {"<=", ">"},
            {"&&", "||"}, {"||", "&&"}
        };
        auto it = negateOp.find(b->op);
        if (it != negateOp.end()) {
            auto newBin = std::make_unique<BinaryOp>();
            newBin->op = it->second;
            newBin->left = std::move(b->left);
            newBin->right = std::move(b->right);
            return newBin;
        }
    }

    // 一般情况: 返回 !(cond)
    return std::make_unique<UnaryOp>("!", std::move(cond), true);
}

bool BranchSimplifier::isConstTrue(const Expr* e) {
    if (!e) return false;
    if (e->type != NT_CONST) return false;
    auto* c = static_cast<const Const*>(e);
    if (c->is_fp) return c->fp_val != 0.0;
    return c->int_val != 0;
}

bool BranchSimplifier::isConstFalse(const Expr* e) {
    if (!e) return false;
    if (e->type != NT_CONST) return false;
    auto* c = static_cast<const Const*>(e);
    if (c->is_fp) return c->fp_val == 0.0;
    return c->int_val == 0;
}

bool BranchSimplifier::isEmptyBody(const StmtPtr& body) {
    if (!body) return true;
    if (body->type == NT_BLOCK) {
        auto* blk = static_cast<Block*>(body.get());
        return blk->statements.empty();
    }
    if (body->type == NT_NULL) return true;
    return false;
}

} // namespace ctree
