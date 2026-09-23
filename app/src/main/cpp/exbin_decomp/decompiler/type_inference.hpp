#pragma once
// type_inference.hpp — Scalar type inference (v3.7)
//
// 移植自 tiny-dec analysis/types/transform.py 的 UnionFind + 证据驱动方案。

#include "microcode.hpp"
#include "ctree.hpp"
#include <map>
#include <vector>
#include <utility>
#include <set>

namespace ctree {

enum class ScalarKind {
    UNKNOWN, BOOL, SIGNED, UNSIGNED, POINTER, FLOAT, WORD
};

using EntityId = std::pair<int, int>;

// UnionFind: 合并 COPY/PHI/load-store 连接的 SSA 值
class UnionFind {
public:
    void add(const EntityId& e);
    EntityId find(const EntityId& e);
    void unite(const EntityId& a, const EntityId& b);
    std::map<EntityId, std::vector<EntityId>> groupedEntities() const;

private:
    std::map<EntityId, EntityId> parent_;
};

struct GroupInfo {
    bool contains_argument_home = false;
    bool contains_local_stack = false;
    bool contains_call_return = false;
};

class TypeInferencePass {
public:
    void analyze(const mc::MicrocodeBlockArray& mba);
    CType getType(int mreg, int ssa_version) const;
    CType getStackSlotType(int sp_offset) const;
    void dump() const;
    void setIsAArch64(bool is_aarch64);

    // v5.8: r2-style return type lookup for known functions
    // 对标 r2 sdb_types: known function return types
    static std::string getReturnTypeForCallee(const std::string& calleeName);
    // v9.4: r2-style parameter type lookup for known functions
    // 对标 r2 sdb_types: propagate known function parameter types
    static std::vector<std::string> getParamTypesForCallee(const std::string& calleeName);

    // v9.7: 返回类型推断 (对标 Ghidra actiontype.cc)
    // 扫描所有 ret 指令,收集返回寄存器(mreg 100)的类型证据
    // 通过 MULTIEQUAL 合并多路径类型 (对标 Ghidra 多 ret 块的类型合并)
    // - 没有 ret 定义 x0 → void
    // - ret 返回指针 (来自 ldr/load) → 指针类型
    // - ret 返回常量 → int/uint64_t
    bool isInferredReturnTypeValid() const { return inferred_return_valid_; }
    CType getInferredReturnType() const { return inferred_return_type_; }
    bool isInferredReturnVoid() const { return inferred_return_void_; }

private:
    void buildScalarIdentity(const mc::MicrocodeBlockArray& mba);
    void collectDirectEvidence(const mc::MicrocodeBlockArray& mba);
    void collectRelationalEvidence(const mc::MicrocodeBlockArray& mba);
    // v5.8: r2-style callee return type propagation
    void collectCallReturnTypes(const mc::MicrocodeBlockArray& mba);
    // v9.4: r2-style callee parameter type propagation
    void collectCallParamTypes(const mc::MicrocodeBlockArray& mba);
    // v9.4: pointer arithmetic detection (base + offset → pointer)
    void detectPointerArith(const mc::MicrocodeBlockArray& mba);
    // v9.4: struct field detection (aggregate base+offset pairs)
    void detectStructFields(const mc::MicrocodeBlockArray& mba);
    // v10.1: 数组类型检测 (对标 Ghidra TypeArray: 连续等间距内存访问 → 数组)
    void detectArrayTypes(const mc::MicrocodeBlockArray& mba);
    // v10.1: 联合体类型检测 (对标 Ghidra ActionResolveUnion)
    // 同一内存位置以不同宽度/类型访问 → union
    void detectUnionTypes(const mc::MicrocodeBlockArray& mba);
    // v10.1: 结构体类型推断 (对标 Ghidra StructureOffsetAnalysis)
    // 从 base+offset 模式推断完整结构体定义
    void inferStructTypes(const mc::MicrocodeBlockArray& mba);
    // v9.7: 返回类型推断 (对标 Ghidra actiontype.cc)
    // 扫描 ret 指令收集返回寄存器类型证据
    void inferReturnType(const mc::MicrocodeBlockArray& mba);
    static ScalarKind mergeKinds(const std::vector<ScalarKind>& kinds);
    static CType toCType(ScalarKind kind, int width);
    static ScalarKind ctypeStringToKind(const std::string& ct);
    bool isArgumentHome(int sp_offset) const;

    UnionFind uf_;
    std::map<EntityId, std::vector<ScalarKind>> direct_evidence_;
    std::map<EntityId, ScalarKind> resolved_kinds_;
    std::map<EntityId, int> entity_widths_;
    std::map<int, ScalarKind> stack_slot_kinds_;
    std::map<int, int> stack_slot_widths_;  // v3.20: track stack slot widths
    std::set<int> argument_home_offsets_;

    // v9.4: struct field detection — base_reg → {offset1, offset2, ...}
    std::map<int, std::set<int>> struct_field_offsets_;
    // v9.4: struct base registers that have been confirmed as struct pointers
    std::set<int> struct_base_regs_;

    // v10.1: 推断的结构体定义 (对标 Ghidra TypeStruct)
    // base_reg → (struct_name, fields[{offset, name, type}])
    struct InferredStruct {
        std::string name;
        std::vector<CType::StructField> fields;
        int total_size = 0;
    };
    std::map<int, InferredStruct> inferred_structs_;

    // v10.1: 推断的数组类型 (对标 Ghidra TypeArray)
    // base_reg → (element_type, element_size, count)
    struct InferredArray {
        CType element_type;
        int element_size = 0;
        int count = 0;
    };
    std::map<int, InferredArray> inferred_arrays_;

    // v10.1: 推断的联合体 (对标 Ghidra TypeUnion)
    // stack_offset / base_reg → union members
    struct InferredUnion {
        std::string name;
        std::vector<CType::UnionMember> members;
        int max_size = 0;
    };
    std::map<int, InferredUnion> inferred_unions_;

    // v10.1: 内存访问记录 (用于 union 检测)
    // location_key → {width1, width2, ...}
    std::map<std::string, std::set<int>> mem_access_widths_;

    // v9.7: 推断的函数返回类型 (对标 Ghidra Funcdata return type)
    // 通过扫描所有 ret 指令的返回寄存器(mreg 100)类型证据合并得到
    CType inferred_return_type_;
    bool inferred_return_valid_ = false;   // 是否成功推断
    bool inferred_return_void_ = false;    // 是否为 void 返回

    bool is_aarch64_ = true;
    int sp_mreg_ = 131;
    static constexpr int RET_MREG = 100;   // 返回寄存器 x0/r0
};

} // namespace ctree
