// high_variable.hpp — v9.6 HighVariable concept (对标 Ghidra HighVariable)
// 统一变量表示：将多个 SSA 版本 (Varnode) 合并为一个逻辑变量
//
// 核心思路 (对标 Ghidra varcode.cc):
//   1. COPY 合并: mov rN, rM → rN 和 rM 是同一个变量
//   2. PHI 合并: phi(rN:v0, rN:v1) → v0 和 v1 是同一个变量
//   3. LOAD/STORE 合并: 同一栈槽的多个 LOAD 是同一个变量
//   4. 类型统一: 所有 SSA 版本共享同一个推断类型
//
// v9.7: 寄存器宽度合并 (对标 Ghidra heritage.cc SUBPIECE/PIECE)
//   - 当 x0(64位) 和 w0(32位) 引用同一物理寄存器时，合并为同一 HighVariable
//   - 寄存器别名检测: x0/w0 是同一物理寄存器的不同宽度视图 (same mreg, diff width)
//   - Cover (活跃范围) 计算: 合并后的变量覆盖所有实例的 SSA 版本范围
//
// 与当前 buildCopyMergeMap 的区别:
//   - 当前: 仅合并 OP_MOV 链 (reg → reg)
//   - 新增: 合并 PHI 节点、LOAD/STORE 栈槽、间接内存写入
//   - 新增: 为每个 HighVariable 维护类型信息
//   - 新增: 实例级宽度跟踪 + 寄存器别名合并

#pragma once

#include "microcode.hpp"
#include <map>
#include <set>
#include <vector>
#include <string>
#include <cstdint>

namespace ctree {

// 变量类型分类
enum class HighVarType {
    UNKNOWN,
    INT,        // 整数
    UINT,       // 无符号整数
    POINTER,    // 指针
    FLOAT,      // 浮点
    BOOL,       // 布尔
    STRUCT_PTR, // 结构体指针
    FUNC_PTR,   // 函数指针
    STRING,     // 字符串
    VOID        // void
};

// 一个 HighVariable 代表一个逻辑变量
// 它可以对应多个 SSA 版本 (mreg, ssa_ver) 对
struct HighVariable {
    int id;                     // 唯一 ID
    std::string name;           // 变量名 (uVar1, iVar2, ptr3, ...)
    HighVarType type;           // 推断的类型
    int width;                  // 位宽 (4, 8, ...) — 合并后的最大宽度
    bool is_param;              // 是否是函数参数
    int param_idx;              // 参数索引 (如果是参数)
    bool is_stack;              // 是否是栈变量
    int stack_offset;           // 栈偏移 (如果是栈变量)
    bool is_global;             // 是否是全局变量

    // 所有属于这个 HighVariable 的 (mreg, ssa_ver) 对
    std::set<std::pair<int, int>> instances;

    // v9.7: 实例级宽度跟踪 (对标 Ghidra Varnode size)
    // 同一物理寄存器的不同宽度视图 (x0=8, w0=4) 共享同一 mreg,
    // 但通过 Mop.width 区分。这里记录每个实例的实际宽度。
    // key = (mreg, ssa_ver), value = byte width
    std::map<std::pair<int, int>, int> instance_widths;

    // v9.7: Cover (活跃范围) — 对标 Ghidra HighVariable cover
    // 记录该变量被定义和使用的指令集合,用于判断活跃区间。
    // cover_start = 最早定义点 (block_id, insn序号)
    // cover_end   = 最后使用点   (block_id, insn序号)
    int cover_start_block = -1;
    int cover_start_seq = -1;
    int cover_end_block = -1;
    int cover_end_seq = -1;

    // 用于类型推断: 如果这个变量被用作指针基址，记录偏移集合
    std::set<int> struct_offsets;

    HighVariable() : id(0), type(HighVarType::UNKNOWN), width(0),
                     is_param(false), param_idx(-1), is_stack(false),
                     stack_offset(0), is_global(false) {}

    // v9.7: 检查是否包含指定宽度的实例
    bool hasInstanceWidth(int w) const {
        for (auto& [key, iw] : instance_widths) {
            if (iw == w) return true;
        }
        return false;
    }
};

// HighVariable 管理器
// 对标 Ghidra HighVariable 类的合并逻辑
class HighVariableManager {
public:
    HighVariableManager() = default;

    // 构建 HighVariable 列表
    // 从微码块中扫描所有 SSA 版本，按规则合并
    void build(const mc::MicrocodeBlockArray& mba, bool is_aarch64);

    // 查找 (mreg, ssa_ver) 对应的 HighVariable
    const HighVariable* find(int mreg, int ssa_ver);

    // 获取所有 HighVariable
    const std::vector<HighVariable>& all() const { return variables_; }

    // 获取参数对应的 HighVariable
    std::vector<const HighVariable*> getParams();

    // 获取局部变量对应的 HighVariable
    std::vector<const HighVariable*> getLocals();

    // 获取栈变量
    std::vector<const HighVariable*> getStackVars();

    // 为变量生成类型化名称
    static std::string makeTypeName(const HighVariable& hv);

    // v9.7: 获取推断的返回类型 (用于函数返回类型推断)
    // 扫描所有 ret 指令,收集返回寄存器(mreg 100)的类型证据
    HighVarType getInferredReturnType() const { return inferred_return_type_; }

    // v9.7: 获取寄存器别名映射 (x0/w0 → 基础 mreg)
    // 同一物理寄存器的不同宽度视图共享同一 mreg
    static int getBaseMreg(int mreg) { return mreg; }

private:
    std::vector<HighVariable> variables_;
    // (mreg, ssa_ver) → HighVariable 索引
    std::map<std::pair<int, int>, int> instance_to_hv_;

    // 合并规则
    void mergeCopyChains(const mc::MicrocodeBlockArray& mba, bool is_aarch64);
    void mergePhiNodes(const mc::MicrocodeBlockArray& mba);
    void mergeStackSlots(const mc::MicrocodeBlockArray& mba, bool is_aarch64);
    void mergeLoadStore(const mc::MicrocodeBlockArray& mba);
    // v9.7: 寄存器宽度别名合并 (对标 Ghidra heritage.cc SUBPIECE/PIECE)
    // 同一 mreg 的不同宽度实例 (x0=8, w0=4) 合并为同一 HighVariable
    void mergeRegisterWidthAliases(const mc::MicrocodeBlockArray& mba);
    // v9.7: Cover (活跃范围) 计算 (对标 Ghidra cover.cc)
    // 合并后的变量覆盖所有实例的定义-使用区间
    void computeCover(const mc::MicrocodeBlockArray& mba);

    // 推断每个 HighVariable 的类型
    void inferTypes(const mc::MicrocodeBlockArray& mba, bool is_aarch64);

    // 辅助
    int getOrCreateHV(int mreg, int ssa_ver);
    // v9.7: 带宽度的创建/获取
    int getOrCreateHVWithWidth(int mreg, int ssa_ver, int width);
    void mergeHV(int from, int to);
    int findRoot(int hv_idx);
    std::vector<int> hv_parent_;  // Union-Find for HV merging

    // v9.7: 记录实例宽度 (mreg, ssa_ver) → width
    std::map<std::pair<int, int>, int> instance_widths_;

    // v9.7: 推断的函数返回类型
    HighVarType inferred_return_type_ = HighVarType::UNKNOWN;

    bool is_aarch64_ = false;
    static constexpr int SP_MREG = 131;
    static constexpr int FP_MREG = 129;
    static constexpr int RET_MREG = 100;  // 返回寄存器 x0/r0
};

} // namespace ctree