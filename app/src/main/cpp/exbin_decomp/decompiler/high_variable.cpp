// high_variable.cpp — v9.6 HighVariable implementation (对标 Ghidra HighVariable)
#include "high_variable.hpp"
#include <algorithm>
#include <cstdio>
#include <cstring>

namespace ctree {

// ════════════════════════════════════════════════════════════════════
// 构建 HighVariable 列表
// ════════════════════════════════════════════════════════════════════
void HighVariableManager::build(const mc::MicrocodeBlockArray& mba, bool is_aarch64) {
    is_aarch64_ = is_aarch64;
    variables_.clear();
    instance_to_hv_.clear();
    hv_parent_.clear();
    instance_widths_.clear();
    inferred_return_type_ = HighVarType::UNKNOWN;

    // Phase 1: 为每个 (mreg, ssa_ver) 创建独立的 HighVariable
    // v9.7: 同时记录实例宽度 (对标 Ghidra Varnode size)
    for (auto& blk : mba.blocks) {
        if (!blk) continue;
        for (mc::MicroInsn* insn = blk->head; insn; insn = insn->next) {
            if (insn->isDead()) continue;
            // 定义点
            if (insn->def_mreg >= 0) {
                getOrCreateHVWithWidth(insn->def_mreg, insn->ssa_version,
                                       insn->d.width > 0 ? insn->d.width : (is_aarch64 ? 8 : 4));
            }
            // 使用点
            auto registerUse = [this](const mc::Mop& m) {
                if (m.isReg() && m.mreg >= 0) {
                    int w = m.width > 0 ? m.width : 8;
                    getOrCreateHVWithWidth(m.mreg, m.ssa_ver, w);
                }
            };
            registerUse(insn->l);
            registerUse(insn->r);
            if (insn->opcode == mc::OP_STORE && insn->d.isMem()) {
                if (insn->d.mem_base >= 0) {
                    getOrCreateHVWithWidth(insn->d.mem_base, 0, 8); // 基址寄存器
                }
            }
        }
        // PHI 节点
        for (mc::MicroInsn* phi : blk->phi_nodes) {
            if (!phi || phi->opcode != mc::OP_PHI) continue;
            if (phi->def_mreg >= 0) {
                getOrCreateHVWithWidth(phi->def_mreg, phi->ssa_version,
                                       phi->d.width > 0 ? phi->d.width : (is_aarch64 ? 8 : 4));
            }
            for (auto& [srcMreg, srcVer] : phi->l.phi_srcs) {
                getOrCreateHVWithWidth(srcMreg, srcVer, is_aarch64 ? 8 : 4);
            }
        }
    }

    // Phase 2: 合并 — COPY 链
    mergeCopyChains(mba, is_aarch64);

    // Phase 3: 合并 — PHI 节点
    mergePhiNodes(mba);

    // Phase 4: 合并 — 栈槽
    mergeStackSlots(mba, is_aarch64);

    // Phase 5: 合并 — LOAD/STORE 连接的变量
    mergeLoadStore(mba);

    // v9.7 Phase 6: 寄存器宽度别名合并 (对标 Ghidra heritage.cc)
    // 同一 mreg 的不同宽度实例 (x0=8, w0=4) 合并
    mergeRegisterWidthAliases(mba);

    // v9.7 Phase 7: 合并后的宽度统一 (取最大宽度)
    // 对标 Ghidra: HighVariable 的 size 是其 Varnode 的最大 size
    for (auto& hv : variables_) {
        int maxW = 0;
        for (auto& [key, w] : hv.instance_widths) {
            if (w > maxW) maxW = w;
        }
        if (maxW > hv.width) hv.width = maxW;
    }
    // 同步实例宽度到根 HV
    for (size_t i = 0; i < variables_.size(); i++) {
        int root = findRoot((int)i);
        if (root != (int)i) {
            for (auto& [key, w] : variables_[i].instance_widths) {
                variables_[root].instance_widths[key] = w;
                if (w > variables_[root].width) variables_[root].width = w;
                variables_[root].instances.insert(key);
            }
        }
    }

    // v9.7 Phase 8: Cover (活跃范围) 计算
    computeCover(mba);

    // Phase 9: 类型推断
    inferTypes(mba, is_aarch64);

    // v9.7: 推断函数返回类型 (扫描 ret 指令)
    // 对标 Ghidra actiontype.cc: 返回寄存器(x0)的类型 = 函数返回类型
    bool anyRetDefinesReturn = false;
    std::vector<HighVarType> retTypeEvidence;
    for (auto& blk : mba.blocks) {
        if (!blk) continue;
        for (mc::MicroInsn* insn = blk->head; insn; insn = insn->next) {
            if (insn->isDead()) continue;
            if (insn->opcode != mc::OP_RET) continue;
            // ret 指令的 l 操作数是返回寄存器 (mreg 100)
            if (insn->l.isReg() && insn->l.mreg == RET_MREG) {
                anyRetDefinesReturn = true;
                // 查找该返回值的 HighVariable 类型
                const HighVariable* hv = find(insn->l.mreg, insn->l.ssa_ver);
                if (hv && hv->type != HighVarType::UNKNOWN) {
                    retTypeEvidence.push_back(hv->type);
                }
            }
        }
    }
    if (!anyRetDefinesReturn) {
        // 没有 ret 指令定义返回寄存器 → void
        inferred_return_type_ = HighVarType::VOID;
    } else if (!retTypeEvidence.empty()) {
        // 有类型证据: 优先级 POINTER > STRUCT_PTR > INT > 其他
        bool hasPointer = false, hasStructPtr = false, hasInt = false;
        for (auto t : retTypeEvidence) {
            if (t == HighVarType::POINTER) hasPointer = true;
            else if (t == HighVarType::STRUCT_PTR) hasStructPtr = true;
            else if (t == HighVarType::INT || t == HighVarType::UINT) hasInt = true;
        }
        if (hasStructPtr) inferred_return_type_ = HighVarType::STRUCT_PTR;
        else if (hasPointer) inferred_return_type_ = HighVarType::POINTER;
        else if (hasInt) inferred_return_type_ = HighVarType::INT;
        else inferred_return_type_ = retTypeEvidence[0];
    } else {
        // ret 定义了返回寄存器但类型未知 → 默认 INT
        inferred_return_type_ = HighVarType::INT;
    }
}

// ════════════════════════════════════════════════════════════════════
// 查找
// ════════════════════════════════════════════════════════════════════
const HighVariable* HighVariableManager::find(int mreg, int ssa_ver) {
    auto it = instance_to_hv_.find({mreg, ssa_ver});
    if (it == instance_to_hv_.end()) return nullptr;
    int idx = it->second;
    // 跟随 Union-Find 到根
    while (idx >= 0 && idx < (int)hv_parent_.size() && hv_parent_[idx] != idx) {
        idx = hv_parent_[idx];
    }
    if (idx >= 0 && idx < (int)variables_.size()) return &variables_[idx];
    return nullptr;
}

// ════════════════════════════════════════════════════════════════════
// 辅助
// ════════════════════════════════════════════════════════════════════
int HighVariableManager::getOrCreateHV(int mreg, int ssa_ver) {
    auto key = std::make_pair(mreg, ssa_ver);
    auto it = instance_to_hv_.find(key);
    if (it != instance_to_hv_.end()) return it->second;

    int idx = (int)variables_.size();
    HighVariable hv;
    hv.id = idx;
    hv.instances.insert(key);
    variables_.push_back(hv);
    hv_parent_.push_back(idx);
    instance_to_hv_[key] = idx;
    return idx;
}

// v9.7: 带宽度的创建/获取 (对标 Ghidra Varnode size tracking)
int HighVariableManager::getOrCreateHVWithWidth(int mreg, int ssa_ver, int width) {
    int idx = getOrCreateHV(mreg, ssa_ver);
    // 记录实例宽度 (取最大值,对标 Ghidra: Varnode size 不可变,但同一 HV
    // 可能有不同 size 的 Varnode,如 x0=8, w0=4)
    auto key = std::make_pair(mreg, ssa_ver);
    auto it = instance_widths_.find(key);
    if (it == instance_widths_.end() || width > it->second) {
        instance_widths_[key] = width;
    }
    // 同步到 HV 的 instance_widths
    int root = findRoot(idx);
    if (root >= 0 && root < (int)variables_.size()) {
        auto& hv = variables_[root];
        auto iwit = hv.instance_widths.find(key);
        if (iwit == hv.instance_widths.end() || width > iwit->second) {
            hv.instance_widths[key] = width;
        }
        if (width > hv.width) hv.width = width;
    }
    return idx;
}

int HighVariableManager::findRoot(int hv_idx) {
    if (hv_idx < 0 || hv_idx >= (int)hv_parent_.size()) return hv_idx;
    if (hv_parent_[hv_idx] == hv_idx) return hv_idx;
    int root = findRoot(hv_parent_[hv_idx]);
    hv_parent_[hv_idx] = root;  // path compression
    return root;
}

void HighVariableManager::mergeHV(int from, int to) {
    int rootFrom = findRoot(from);
    int rootTo = findRoot(to);
    if (rootFrom == rootTo) return;
    // 优先保留较小的索引
    if (rootFrom < rootTo) {
        hv_parent_[rootTo] = rootFrom;
    } else {
        hv_parent_[rootFrom] = rootTo;
    }
}

// ════════════════════════════════════════════════════════════════════
// Phase 2: COPY 链合并 (对标 Ghidra varcode.cc mergeAdjacent)
// ════════════════════════════════════════════════════════════════════
void HighVariableManager::mergeCopyChains(const mc::MicrocodeBlockArray& mba, bool is_aarch64) {
    auto isArgReg = [is_aarch64](int mreg) {
        if (is_aarch64) return mreg >= 100 && mreg <= 107;
        return mreg >= 100 && mreg <= 103;
    };

    for (auto& blk : mba.blocks) {
        if (!blk) continue;
        for (mc::MicroInsn* insn = blk->head; insn; insn = insn->next) {
            if (insn->isDead()) continue;
            if (insn->opcode != mc::OP_MOV) continue;
            if (!insn->d.isReg() || !insn->l.isReg()) continue;

            int dst = insn->d.mreg;
            int src = insn->l.mreg;
            if (dst < 0 || src < 0) continue;
            if (dst == 132 || src == 132) continue;  // xzr

            // 不合并参数寄存器
            if (isArgReg(dst) && isArgReg(src)) continue;
            if (isArgReg(dst) || isArgReg(src)) continue;

            int hvDst = getOrCreateHV(dst, insn->ssa_version);
            int hvSrc = getOrCreateHV(src, insn->l.ssa_ver);
            mergeHV(hvDst, hvSrc);
        }
    }
}

// ════════════════════════════════════════════════════════════════════
// Phase 3: PHI 节点合并 (对标 Ghidra mergeHigh)
// ════════════════════════════════════════════════════════════════════
void HighVariableManager::mergePhiNodes(const mc::MicrocodeBlockArray& mba) {
    for (auto& blk : mba.blocks) {
        if (!blk) continue;
        for (mc::MicroInsn* phi : blk->phi_nodes) {
            if (!phi || phi->opcode != mc::OP_PHI) continue;
            if (phi->def_mreg < 0) continue;

            int hvDef = getOrCreateHV(phi->def_mreg, phi->ssa_version);
            for (auto& [srcMreg, srcVer] : phi->l.phi_srcs) {
                int hvSrc = getOrCreateHV(srcMreg, srcVer);
                mergeHV(hvDef, hvSrc);
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════
// Phase 4: 栈槽合并 (对标 Ghidra RangeHint)
// 同一栈偏移的多个 LOAD 是同一个变量
// ════════════════════════════════════════════════════════════════════
void HighVariableManager::mergeStackSlots(const mc::MicrocodeBlockArray& mba, bool is_aarch64) {
    (void)is_aarch64;
    // stack_offset → [hv indices]
    std::map<int, std::vector<int>> slot_to_hvs;

    for (auto& blk : mba.blocks) {
        if (!blk) continue;
        for (mc::MicroInsn* insn = blk->head; insn; insn = insn->next) {
            if (insn->isDead()) continue;
            // LOAD: d = mem[sp, offset] → 栈变量
            if (insn->opcode == mc::OP_LOAD && insn->l.isMem()) {
                int base = insn->l.mem_base;
                int offset = insn->l.mem_offset;
                if (base == SP_MREG || base == FP_MREG) {
                    if (insn->def_mreg >= 0) {
                        int hv = getOrCreateHV(insn->def_mreg, insn->ssa_version);
                        slot_to_hvs[offset].push_back(hv);
                    }
                }
            }
            // STORE: mem[sp, offset] = r → 栈变量写入
            if (insn->opcode == mc::OP_STORE && insn->d.isMem()) {
                int base = insn->d.mem_base;
                int offset = insn->d.mem_offset;
                if (base == SP_MREG || base == FP_MREG) {
                    if (insn->l.isReg() && insn->l.mreg >= 0) {
                        int hv = getOrCreateHV(insn->l.mreg, insn->l.ssa_ver);
                        slot_to_hvs[offset].push_back(hv);
                    }
                }
            }
        }
    }

    // 合并同一栈偏移的所有 HV
    for (auto& [offset, hvs] : slot_to_hvs) {
        if (hvs.size() < 2) continue;
        // 标记为栈变量
        int root = hvs[0];
        for (size_t i = 1; i < hvs.size(); i++) {
            mergeHV(hvs[i], root);
        }
        root = findRoot(root);
        variables_[root].is_stack = true;
        variables_[root].stack_offset = offset;
    }
}

// ════════════════════════════════════════════════════════════════════
// Phase 5: LOAD/STORE 连接合并 (对标 Ghidra mergeAddrTied)
// v24.1: 同时处理 OP_FSTORE/OP_FLOAD (浮点类型)
// ════════════════════════════════════════════════════════════════════
void HighVariableManager::mergeLoadStore(const mc::MicrocodeBlockArray& mba) {
    for (auto& blk : mba.blocks) {
        if (!blk) continue;
        for (mc::MicroInsn* insn = blk->head; insn; insn = insn->next) {
            if (insn->isDead()) continue;
            // STORE addr, value → LOAD addr 的 value 是同一个变量
            // v24.1: 也处理 OP_FSTORE (浮点存储)
            if ((insn->opcode == mc::OP_STORE || insn->opcode == mc::OP_FSTORE) &&
                insn->d.isMem() && insn->l.isReg()) {
                int storeValReg = insn->l.mreg;
                int storeValVer = insn->l.ssa_ver;
                int storeBase = insn->d.mem_base;
                int storeOff = insn->d.mem_offset;

                int hvStore = getOrCreateHV(storeValReg, storeValVer);

                // 查找同地址的 LOAD (v24.1: 也查找 OP_FLOAD)
                for (auto& blk2 : mba.blocks) {
                    if (!blk2) continue;
                    for (mc::MicroInsn* insn2 = blk2->head; insn2; insn2 = insn2->next) {
                        if ((insn2->opcode == mc::OP_LOAD || insn2->opcode == mc::OP_FLOAD) &&
                            insn2->l.isMem()) {
                            if (insn2->l.mem_base == storeBase &&
                                insn2->l.mem_offset == storeOff &&
                                insn2->def_mreg >= 0) {
                                int hvLoad = getOrCreateHV(insn2->def_mreg, insn2->ssa_version);
                                mergeHV(hvStore, hvLoad);
                            }
                        }
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════
// v9.7 Phase 6: 寄存器宽度别名合并 (对标 Ghidra heritage.cc SUBPIECE/PIECE)
//
// 在 AArch64 中, x0 和 w0 是同一物理寄存器的不同宽度视图,共享同一 mreg(100)。
// Ghidra 使用 SUBPIECE 操作处理这种关系:
//   - 写 w0 后读 x0: x0 = PIECE(0, w0)  (高 32 位补零)
//   - 写 x0 后读 w0: w0 = SUBPIECE(x0, 0)  (取低 32 位)
//
// 在我们的 IR 中, w0 和 x0 已经共享同一 mreg (由 microcode_emitter 的
// parseReg 保证), 只是 Mop.width 不同 (4 vs 8)。因此同一 mreg 的不同
// SSA 版本已经天然属于同一 HighVariable。但我们需要处理一个特殊情况:
// 当一个 32 位操作定义 w0 (ssa_ver=N), 后续读取 x0 (ssa_ver=N) 时,
// 它们应该被视为同一变量。
//
// 此函数额外检测 OP_XDU/OP_XDS (零扩展/符号扩展) 模式:
//   x0 = ZEXT(w0) → x0 和 w0 是同一变量的不同宽度视图
// ════════════════════════════════════════════════════════════════════
void HighVariableManager::mergeRegisterWidthAliases(const mc::MicrocodeBlockArray& mba) {
    for (auto& blk : mba.blocks) {
        if (!blk) continue;
        for (mc::MicroInsn* insn = blk->head; insn; insn = insn->next) {
            if (insn->isDead()) continue;

            // OP_XDU (零扩展) / OP_XDS (符号扩展):
            // d = ZEXT(l) → d 和 l 是同一变量的不同宽度视图
            // 对标 Ghidra: SUBPIECE/PIECE 合并
            if (insn->opcode == mc::OP_XDU || insn->opcode == mc::OP_XDS) {
                if (insn->def_mreg < 0 || !insn->l.isReg()) continue;
                int dst = insn->def_mreg;
                int src = insn->l.mreg;
                if (dst < 0 || src < 0) continue;
                // 不合并零寄存器
                if (dst == 132 || src == 132) continue;

                int hvDst = getOrCreateHVWithWidth(dst, insn->ssa_version,
                                                    insn->d.width > 0 ? insn->d.width : 8);
                int hvSrc = getOrCreateHVWithWidth(src, insn->l.ssa_ver,
                                                    insn->l.width > 0 ? insn->l.width : 4);
                mergeHV(hvDst, hvSrc);
            }

            // OP_MOV 中, 如果 dst 和 src 是同一 mreg (不同宽度视图),
            // 也应合并。这在正常代码中不应出现,但防御性处理。
            if (insn->opcode == mc::OP_MOV && insn->d.isReg() && insn->l.isReg()) {
                if (insn->d.mreg == insn->l.mreg && insn->d.mreg >= 0 &&
                    insn->d.mreg != 132) {
                    int hvDst = getOrCreateHVWithWidth(insn->d.mreg, insn->ssa_version,
                                                       insn->d.width > 0 ? insn->d.width : 8);
                    int hvSrc = getOrCreateHVWithWidth(insn->l.mreg, insn->l.ssa_ver,
                                                       insn->l.width > 0 ? insn->l.width : 4);
                    mergeHV(hvDst, hvSrc);
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════
// v9.7 Phase 8: Cover (活跃范围) 计算 (对标 Ghidra cover.cc)
//
// 对标 Ghidra HighVariable::cover:
//   - Cover 表示变量的活跃区间集合
//   - 合并后的 HighVariable 的 cover 是所有实例 cover 的并集
//   - 用于判断变量是否在某点活跃 (liveness)
//
// 简化实现: 记录该变量最早定义点和最后使用点 (block_id, insn序号)
// ════════════════════════════════════════════════════════════════════
void HighVariableManager::computeCover(const mc::MicrocodeBlockArray& mba) {
    // 遍历所有指令,为每个实例记录定义/使用位置
    for (size_t bid = 0; bid < mba.blocks.size(); bid++) {
        auto& blk = mba.blocks[bid];
        if (!blk) continue;
        int seq = 0;
        for (mc::MicroInsn* insn = blk->head; insn; insn = insn->next, seq++) {
            if (insn->isDead()) continue;

            // 定义点: 更新 cover_start
            if (insn->def_mreg >= 0) {
                auto it = instance_to_hv_.find({insn->def_mreg, insn->ssa_version});
                if (it != instance_to_hv_.end()) {
                    int root = findRoot(it->second);
                    if (root >= 0 && root < (int)variables_.size()) {
                        auto& hv = variables_[root];
                        // cover_start = 最早定义点
                        if (hv.cover_start_block < 0 ||
                            (bid < (size_t)hv.cover_start_block) ||
                            (bid == (size_t)hv.cover_start_block && seq < hv.cover_start_seq)) {
                            hv.cover_start_block = (int)bid;
                            hv.cover_start_seq = seq;
                        }
                        // cover_end = 最后定义或使用点
                        if (hv.cover_end_block < 0 ||
                            (bid > (size_t)hv.cover_end_block) ||
                            (bid == (size_t)hv.cover_end_block && seq > hv.cover_end_seq)) {
                            hv.cover_end_block = (int)bid;
                            hv.cover_end_seq = seq;
                        }
                    }
                }
            }

            // 使用点: 更新 cover_end
            auto updateUse = [&](const mc::Mop& m) {
                if (m.isReg() && m.mreg >= 0) {
                    auto it = instance_to_hv_.find({m.mreg, m.ssa_ver});
                    if (it != instance_to_hv_.end()) {
                        int root = findRoot(it->second);
                        if (root >= 0 && root < (int)variables_.size()) {
                            auto& hv = variables_[root];
                            if (hv.cover_end_block < 0 ||
                                (bid > (size_t)hv.cover_end_block) ||
                                (bid == (size_t)hv.cover_end_block && seq > hv.cover_end_seq)) {
                                hv.cover_end_block = (int)bid;
                                hv.cover_end_seq = seq;
                            }
                        }
                    }
                }
            };
            updateUse(insn->l);
            updateUse(insn->r);
        }
    }
}

// ════════════════════════════════════════════════════════════════════
// Phase 9: 类型推断 (对标 Ghidra TypeOp 迭代传播)
// 对每个 HighVariable 推断其类型
// ════════════════════════════════════════════════════════════════════
void HighVariableManager::inferTypes(const mc::MicrocodeBlockArray& mba, bool is_aarch64) {
    auto isArgReg = [is_aarch64](int mreg) {
        if (is_aarch64) return mreg >= 100 && mreg <= (is_aarch64 ? 107 : 103);
        return mreg >= 100 && mreg <= 103;
    };

    // 初始: 参数寄存器 → 标记为参数
    for (auto& hv : variables_) {
        for (auto& [mreg, ssa_ver] : hv.instances) {
            if (isArgReg(mreg) && ssa_ver == 0) {
                hv.is_param = true;
                hv.param_idx = mreg - 100;
                break;
            }
        }
    }

    // v9.7: 根据宽度推断基础类型 (对标 Ghidra type propagation)
    // width=1 → INT (char), width=2 → INT (short), width=4 → INT (int)
    // width=8 → INT (long) 或 POINTER (需要后续证据)
    for (auto& hv : variables_) {
        if (hv.type != HighVarType::UNKNOWN) continue;
        if (hv.width > 0 && hv.width <= 4) {
            // 小宽度 → 整数 (非指针)
            hv.type = HighVarType::INT;
        }
    }

    // 迭代定点类型传播 (最多 10 轮)
    for (int iter = 0; iter < 10; iter++) {
        bool changed = false;

        for (auto& blk : mba.blocks) {
            if (!blk) continue;
            for (mc::MicroInsn* insn = blk->head; insn; insn = insn->next) {
                if (insn->isDead()) continue;

                auto getHV = [this](int mreg, int ssa_ver) -> HighVariable* {
                    auto it = instance_to_hv_.find({mreg, ssa_ver});
                    if (it == instance_to_hv_.end()) return nullptr;
                    int root = findRoot(it->second);
                    if (root >= 0 && root < (int)variables_.size()) return &variables_[root];
                    return nullptr;
                };

                // 规则 1: LOAD/FLOAD 基址 → 指针 (v24.1: 也处理 OP_FLOAD)
                if ((insn->opcode == mc::OP_LOAD || insn->opcode == mc::OP_FLOAD) && insn->l.isMem()) {
                    int base = insn->l.mem_base;
                    if (base >= 0 && base != SP_MREG && base != FP_MREG) {
                        // 找到基址寄存器的最新定义
                        mc::MicroInsn* prev = insn->prev;
                        while (prev) {
                            if (prev->def_mreg == base) {
                                auto* hv = getHV(base, prev->ssa_version);
                                if (hv && hv->type == HighVarType::UNKNOWN) {
                                    hv->type = HighVarType::POINTER;
                                    changed = true;
                                }
                                break;
                            }
                            prev = prev->prev;
                        }
                    }
                    // 如果加载的是已知全局变量，结果可能是 POINTER
                    // (v24.1: FLOAD 加载全局变量也产生 POINTER)
                    if (insn->l.isGlobal()) {
                        if (insn->def_mreg >= 0) {
                            auto* hv = getHV(insn->def_mreg, insn->ssa_version);
                            if (hv && hv->type == HighVarType::UNKNOWN) {
                                hv->type = HighVarType::POINTER;
                                changed = true;
                            }
                        }
                    }
                    // v24.1: FLOAD 的结果本身就是 FLOAT 类型
                    // 对标 Ghidra FLOAT_LOAD: 浮点加载产生浮点变量
                    if (insn->opcode == mc::OP_FLOAD && insn->def_mreg >= 0) {
                        auto* hv = getHV(insn->def_mreg, insn->ssa_version);
                        if (hv && hv->type == HighVarType::UNKNOWN) {
                            hv->type = HighVarType::FLOAT;
                            changed = true;
                        }
                    }
                }

                // 规则 2: STORE/FSTORE 目标 → 指针基址 (v24.1: 也处理 OP_FSTORE)
                if ((insn->opcode == mc::OP_STORE || insn->opcode == mc::OP_FSTORE) && insn->d.isMem()) {
                    int base = insn->d.mem_base;
                    if (base >= 0 && base != SP_MREG && base != FP_MREG) {
                        mc::MicroInsn* prev = insn->prev;
                        while (prev) {
                            if (prev->def_mreg == base) {
                                auto* hv = getHV(base, prev->ssa_version);
                                if (hv && hv->type == HighVarType::UNKNOWN) {
                                    hv->type = HighVarType::POINTER;
                                    // 跟踪偏移用于结构体检测
                                    hv->struct_offsets.insert((int)insn->d.mem_offset);
                                    changed = true;
                                }
                                break;
                            }
                            prev = prev->prev;
                        }
                    }
                    // v24.1: FSTORE 的源寄存器是 FLOAT 类型
                    // 对标 Ghidra FLOAT_STORE: 存储的浮点值类型为 FLOAT
                    if (insn->opcode == mc::OP_FSTORE && insn->l.isReg()) {
                        auto* hv = getHV(insn->l.mreg, insn->l.ssa_ver);
                        if (hv && hv->type == HighVarType::UNKNOWN) {
                            hv->type = HighVarType::FLOAT;
                            changed = true;
                        }
                    }
                }

                // 规则 3: ADD/SUB 指针算术 → 结果是指针
                if ((insn->opcode == mc::OP_ADD || insn->opcode == mc::OP_SUB) &&
                    insn->def_mreg >= 0) {
                    auto* hvDef = getHV(insn->def_mreg, insn->ssa_version);
                    if (!hvDef || hvDef->type != HighVarType::UNKNOWN) continue;

                    auto* hvL = insn->l.isReg() ? getHV(insn->l.mreg, insn->l.ssa_ver) : nullptr;
                    auto* hvR = insn->r.isReg() ? getHV(insn->r.mreg, insn->r.ssa_ver) : nullptr;

                    bool lIsPtr = hvL && hvL->type == HighVarType::POINTER;
                    bool rIsPtr = hvR && hvR->type == HighVarType::POINTER;
                    bool lIsImm = insn->l.isImm();
                    bool rIsImm = insn->r.isImm();

                    if ((lIsPtr && rIsImm) || (rIsPtr && lIsImm)) {
                        hvDef->type = HighVarType::POINTER;
                        changed = true;
                    }
                }

                // 规则 4: CALL 返回值类型传播
                if ((insn->opcode == mc::OP_CALL || insn->opcode == mc::OP_ICALL) &&
                    insn->def_mreg >= 0) {
                    auto* hvRet = getHV(insn->def_mreg, insn->ssa_version);
                    if (!hvRet) continue;

                    // 检查 callee 名称
                    if (insn->call_info && !insn->call_info->target_name.empty()) {
                        const std::string& callee = insn->call_info->target_name;
                        // 已知返回指针的函数
                        if (callee.find("malloc") != std::string::npos ||
                            callee.find("calloc") != std::string::npos ||
                            callee.find("realloc") != std::string::npos ||
                            callee.find("strdup") != std::string::npos ||
                            callee.find("operator new") != std::string::npos ||
                            callee.find("_Znwm") != std::string::npos ||
                            callee.find("_Znam") != std::string::npos) {
                            if (hvRet->type == HighVarType::UNKNOWN) {
                                hvRet->type = HighVarType::POINTER;
                                changed = true;
                            }
                        }
                    }
                }

                // 规则 5: SET 指令 (比较结果) → 操作数是整数
                if (insn->opcode >= mc::OP_SETZ && insn->opcode <= mc::OP_SETO) {
                    auto setInt = [&](const mc::Mop& m) {
                        if (m.isReg()) {
                            auto* hv = getHV(m.mreg, m.ssa_ver);
                            if (hv && hv->type == HighVarType::UNKNOWN) {
                                hv->type = HighVarType::INT;
                                changed = true;
                            }
                        }
                    };
                    setInt(insn->l);
                    setInt(insn->r);
                }
            }
        }

        if (!changed) break;  // 定点
    }

    // 结构体检测: 同一基址 ≥3 个不同偏移 → 结构体指针
    for (auto& hv : variables_) {
        if (hv.type == HighVarType::POINTER && hv.struct_offsets.size() >= 3) {
            hv.type = HighVarType::STRUCT_PTR;
        }
    }
}

// ════════════════════════════════════════════════════════════════════
// 辅助
// ════════════════════════════════════════════════════════════════════
std::vector<const HighVariable*> HighVariableManager::getParams() {
    std::vector<const HighVariable*> result;
    std::set<int> seen; // 去重 (Union-Find 合并后)
    for (auto& hv : variables_) {
        int root = findRoot(hv.id);
        if (seen.count(root)) continue;
        seen.insert(root);
        if (variables_[root].is_param) {
            result.push_back(&variables_[root]);
        }
    }
    // 按参数索引排序
    std::sort(result.begin(), result.end(),
              [](const HighVariable* a, const HighVariable* b) {
                  return a->param_idx < b->param_idx;
              });
    return result;
}

std::vector<const HighVariable*> HighVariableManager::getLocals() {
    std::vector<const HighVariable*> result;
    std::set<int> seen;
    for (auto& hv : variables_) {
        int root = findRoot(hv.id);
        if (seen.count(root)) continue;
        seen.insert(root);
        if (!variables_[root].is_param && !variables_[root].is_stack && !variables_[root].is_global) {
            result.push_back(&variables_[root]);
        }
    }
    return result;
}

std::vector<const HighVariable*> HighVariableManager::getStackVars() {
    std::vector<const HighVariable*> result;
    std::set<int> seen;
    for (auto& hv : variables_) {
        int root = findRoot(hv.id);
        if (seen.count(root)) continue;
        seen.insert(root);
        if (variables_[root].is_stack) {
            result.push_back(&variables_[root]);
        }
    }
    return result;
}

std::string HighVariableManager::makeTypeName(const HighVariable& hv) {
    // 对标 Ghidra: 根据类型生成变量名
    static int varCounter = 0;
    switch (hv.type) {
        case HighVarType::POINTER:   return "pVar" + std::to_string(++varCounter);
        case HighVarType::STRUCT_PTR: return "sPtr" + std::to_string(++varCounter);
        case HighVarType::FUNC_PTR:   return "fnPtr" + std::to_string(++varCounter);
        case HighVarType::INT:        return "iVar" + std::to_string(++varCounter);
        case HighVarType::UINT:       return "uVar" + std::to_string(++varCounter);
        case HighVarType::FLOAT:      return "fVar" + std::to_string(++varCounter);
        case HighVarType::BOOL:       return "bVar" + std::to_string(++varCounter);
        case HighVarType::STRING:     return "str" + std::to_string(++varCounter);
        default:                      return "var" + std::to_string(++varCounter);
    }
}

} // namespace ctree