#pragma once
// ctree.hpp — C Tree AST (对标 Hex-Rays cfunc_t/cinsn_t/cexpr_t)
// Abstract syntax tree for pseudo-C code generation.

#include "microcode.hpp"
#include <memory>
#include <vector>
#include <string>

namespace ctree {

// Node types (对标 ctype_t / Ghidra ctree opcodes)
enum NodeType {
    // ── Statements ──
    NT_BLOCK,
    NT_IF,
    NT_WHILE,
    NT_DO_WHILE,
    NT_FOR,
    NT_SWITCH,
    NT_RETURN,
    NT_BREAK,
    NT_CONTINUE,
    NT_GOTO,
    NT_EXPR_STMT,
    NT_DECL,
    NT_LABEL,
    NT_EMPTY_STMT,      // v10.0: 空语句 ";"
    NT_ASM,             // v10.0: 内联汇编 asm("...")
    // ── Expressions ──
    NT_BINARY_OP,
    NT_UNARY_OP,
    NT_ASSIGN,
    NT_CALL,
    NT_VAR_REF,
    NT_CONST,
    NT_STRING,
    NT_CAST,
    NT_MEMBER,
    NT_INDEX,
    NT_TERNARY,
    NT_INIT_LIST,       // v10.0: 初始化列表 {a, b, c}
    NT_NULL,
    // v10.0: 参考 Ghidra printc.cc 补全的专用表达式节点
    NT_DEREF,           // *expr (对标 Ghidra OP_PTRSUB / dereference)
    NT_ADDRESSOF,       // &expr (对标 Ghidra address-of)
    NT_SIZEOF,          // sizeof(type) or sizeof(expr)
    NT_COMMA,           // a, b (逗号表达式)
    NT_ENUM_CONST,      // ENUM_VALUE (枚举常量引用)
    NT_GLOBAL_VAR,      // g_xxxx / 全局变量引用 (带地址)
    NT_BITFIELD,        // expr : width (位域访问)
    NT_NEW,             // new Type or new Type(expr)
    NT_DELETE,          // delete expr or delete[] expr
    NT_THROW_EXPR       // throw expr
};

// Forward declarations
struct Node;
struct Expr;
struct Stmt;
using ExprPtr = std::unique_ptr<Expr>;
using StmtPtr = std::unique_ptr<Stmt>;
using NodePtr = std::unique_ptr<Node>;

// Type info (v10.1: 参考 Ghidra Datatype 层次结构补全)
struct CType {
    enum Category {
        TC_UNKNOWN, TC_VOID, TC_BOOL,
        TC_INT8, TC_UINT8, TC_INT16, TC_UINT16,
        TC_INT32, TC_UINT32, TC_INT64, TC_UINT64,
        TC_FLOAT, TC_DOUBLE,
        TC_POINTER, TC_ARRAY, TC_STRUCT_PTR,
        TC_FUNC_PTR, TC_STRING, TC_ENUM,
        // v10.1: 新增类型 (对标 Ghidra TypeStruct/TypeUnion/TypeArray)
        TC_STRUCT,         // 结构体值类型 (非指针)
        TC_UNION,          // 联合体
        TC_UNION_PTR,      // 联合体指针
        TC_TYPEDEF         // typedef 别名
    } category = TC_UNKNOWN;
    int width = 8;
    bool is_signed = false;
    bool is_const = false;
    std::string struct_name;
    int struct_offset = 0;
    std::string pointee_type;  // pointee type string (avoids dangling pointer)

    // v10.1: 数组类型信息 (对标 Ghidra TypeArray: arrayof + arraysize)
    int array_elem_count = 0;      // 元素个数 (0 = 未知)
    int array_elem_size = 0;       // 元素大小 (bytes)

    // v10.1: 结构体字段列表 (对标 Ghidra TypeStruct: components)
    // NOTE: 字段类型使用 shared_ptr<CType> 而非直接 CType，因为 CType
    // 不能包含自身实例（不完整类型）。这也与 Ghidra TypeStruct 持有
    // TypeComponent 指针的设计一致。
    struct StructField {
        int offset = 0;
        std::string name;
        std::shared_ptr<CType> type;
    };
    std::vector<StructField> struct_fields;

    // v10.1: 联合体成员列表 (对标 Ghidra TypeUnion)
    struct UnionMember {
        std::string name;
        std::shared_ptr<CType> type;
    };
    std::vector<UnionMember> union_members;

    // v10.1: typedef 别名名
    std::string typedef_name;

    std::string toCString() const {
        // 对标 Ghidra printc.cc type printing:
        // - Pointers use "type *" with space (Ghidra style)
        // - Unknown defaults to uint64_t (decompiler convention)
        switch (category) {
            case TC_VOID: return "void";
            case TC_BOOL: return "bool";
            case TC_INT8: return is_signed ? "int8_t" : "uint8_t";
            case TC_UINT8: return "uint8_t";
            case TC_INT16: return is_signed ? "int16_t" : "uint16_t";
            case TC_UINT16: return "uint16_t";
            case TC_INT32: return is_signed ? "int32_t" : "uint32_t";
            case TC_UINT32: return "uint32_t";
            case TC_INT64: return is_signed ? "int64_t" : "uint64_t";
            case TC_UINT64: return "uint64_t";
            case TC_FLOAT: return "float";
            case TC_DOUBLE: return "double";
            case TC_POINTER:
                return pointee_type.empty() ? "void *" : pointee_type + " *";
            case TC_ARRAY: {
                std::string elem = pointee_type.empty() ? "uint64_t" : pointee_type;
                if (array_elem_count > 0)
                    return elem + "[" + std::to_string(array_elem_count) + "]";
                return elem + "[]";
            }
            case TC_STRING: return "char *";
            case TC_FUNC_PTR: return "void *";
            case TC_STRUCT_PTR:
                return (struct_name.empty() ? "void" : struct_name) + " *";
            case TC_STRUCT:
                return struct_name.empty() ? "struct <unknown>" : struct_name;
            case TC_UNION:
                return union_members.empty() ? "union <unknown>" :
                       ("union " + struct_name);
            case TC_UNION_PTR:
                return (struct_name.empty() ? "union <unknown>" : struct_name) + " *";
            case TC_ENUM: return "int";
            case TC_TYPEDEF:
                return typedef_name.empty() ? "uint64_t" : typedef_name;
            default: return "uint64_t";
        }
    }

    bool isPointer() const {
        return category == TC_POINTER || category == TC_STRING ||
               category == TC_FUNC_PTR || category == TC_STRUCT_PTR ||
               category == TC_UNION_PTR;
    }

    bool isInteger() const {
        return category >= TC_INT8 && category <= TC_UINT64;
    }

    static CType unknown() { return {}; }
    static CType u64() { CType t; t.category = TC_UINT64; t.width = 8; return t; }
    static CType u32() { CType t; t.category = TC_UINT32; t.width = 4; return t; }
    static CType u16() { CType t; t.category = TC_UINT16; t.width = 2; return t; }
    static CType u8() { CType t; t.category = TC_UINT8; t.width = 1; return t; }
    static CType i64() { CType t; t.category = TC_INT64; t.width = 8; t.is_signed = true; return t; }
    static CType i32() { CType t; t.category = TC_INT32; t.width = 4; t.is_signed = true; return t; }
    static CType i16() { CType t; t.category = TC_INT16; t.width = 2; t.is_signed = true; return t; }
    static CType i8() { CType t; t.category = TC_INT8; t.width = 1; t.is_signed = true; return t; }
    static CType f32() { CType t; t.category = TC_FLOAT; t.width = 4; t.is_signed = true; return t; }
    static CType f64() { CType t; t.category = TC_DOUBLE; t.width = 8; t.is_signed = true; return t; }
    static CType ptrTo(CType c) { CType t; t.category = TC_POINTER; t.width = 8; t.pointee_type = c.toCString(); return t; }
    static CType str() { CType t; t.category = TC_STRING; t.width = 8; return t; }
    static CType voidPtr() { CType t; t.category = TC_POINTER; t.width = 8; return t; }

    // Phase 3: Parse C type string (e.g., "void*", "int", "size_t")
    static CType fromString(const std::string& s) {
        if (s.empty()) return unknown();
        if (s == "void") { CType t; t.category = TC_VOID; t.width = 0; return t; }
        if (s == "bool" || s == "_Bool") { CType t; t.category = TC_BOOL; t.width = 1; return t; }
        if (s == "char" || s == "int8_t") return i8();
        if (s == "unsigned char" || s == "uint8_t") return u8();
        if (s == "short" || s == "int16_t") return i16();
        if (s == "unsigned short" || s == "uint16_t") return u16();
        if (s == "int" || s == "int32_t") return i32();
        if (s == "unsigned int" || s == "uint32_t" || s == "unsigned") return u32();
        if (s == "long" || s == "int64_t") return i64();
        if (s == "unsigned long" || s == "uint64_t" || s == "size_t") return u64();
        if (s == "long long") return i64();
        if (s == "unsigned long long") return u64();
        if (s == "float") return f32();
        if (s == "double") return f64();
        if (s == "void*") return voidPtr();
        if (s == "const char*") return str();
        if (s == "char*") { CType t = voidPtr(); t.pointee_type = "char"; return t; }
        // Pointer types ending with *
        if (s.size() >= 2 && s.back() == '*') {
            CType t = voidPtr();
            t.pointee_type = s.substr(0, s.size() - 1);
            return t;
        }
        return unknown();
    }
};

// Node base class
struct Node {
    NodeType type;
    uint64_t ea = 0;
    Node* parent = nullptr;
    std::string comment;

    Node(NodeType t) : type(t) {}
    virtual ~Node() = default;
    virtual std::string toString() const { return "/* node */"; }
};

// Expression node
struct Expr : Node {
    CType result_type;

    Expr(NodeType t) : Node(t) {}
};

// Statement node
struct Stmt : Node {
    Stmt(NodeType t) : Node(t) {}
};

// ── Statement nodes ──

struct Block : Stmt {
    std::vector<StmtPtr> statements;
    Block() : Stmt(NT_BLOCK) {}
    std::string toString() const override;
};

struct If : Stmt {
    ExprPtr condition;
    StmtPtr then_branch;
    StmtPtr else_branch;  // nullptr if no else
    If() : Stmt(NT_IF) {}
    std::string toString() const override;
};

struct While : Stmt {
    ExprPtr condition;
    StmtPtr body;
    While() : Stmt(NT_WHILE) {}
    std::string toString() const override;
};

struct DoWhile : Stmt {
    StmtPtr body;
    ExprPtr condition;
    DoWhile() : Stmt(NT_DO_WHILE) {}
    std::string toString() const override;
};

struct For : Stmt {
    StmtPtr init;
    ExprPtr condition;
    ExprPtr increment;
    StmtPtr body;
    For() : Stmt(NT_FOR) {}
    std::string toString() const override;
};

struct Switch : Stmt {
    ExprPtr expr;
    std::vector<std::pair<ExprPtr, StmtPtr>> cases;
    StmtPtr default_body;
    Switch() : Stmt(NT_SWITCH) {}
    std::string toString() const override;
};

struct Return : Stmt {
    ExprPtr value;  // nullptr for void return
    Return() : Stmt(NT_RETURN) {}
    std::string toString() const override;
};

struct Break : Stmt {
    Break() : Stmt(NT_BREAK) {}
    std::string toString() const override { return "break;"; }
};

struct Continue : Stmt {
    Continue() : Stmt(NT_CONTINUE) {}
    std::string toString() const override { return "continue;"; }
};

struct Goto : Stmt {
    std::string label;
    Goto() : Stmt(NT_GOTO) {}
    std::string toString() const override { return "goto " + label + ";"; }
};

struct Label : Stmt {
    std::string name;
    StmtPtr stmt;
    Label() : Stmt(NT_LABEL) {}
    std::string toString() const override { return name + ":"; }
};

struct ExprStmt : Stmt {
    ExprPtr expr;
    ExprStmt() : Stmt(NT_EXPR_STMT) {}
    std::string toString() const override;
};

struct VarDecl : Stmt {
    std::string var_name;
    CType var_type;
    ExprPtr init_expr;
    VarDecl() : Stmt(NT_DECL) {}
    std::string toString() const override;
};

// v10.0: Empty statement — ";" (对标 Ghidra OP_EMPTY)
struct EmptyStmt : Stmt {
    EmptyStmt() : Stmt(NT_EMPTY_STMT) {}
    std::string toString() const override { return ";"; }
};

// v10.0: Inline assembly statement — asm("...") (对标 Ghidra CALLOTHER / userop)
struct AsmStmt : Stmt {
    std::string asm_string;   // raw assembly text
    std::vector<ExprPtr> inputs;
    std::vector<ExprPtr> outputs;
    bool is_volatile = false;
    AsmStmt() : Stmt(NT_ASM) {}
    std::string toString() const override;
};

// ── Expression nodes ──

struct BinaryOp : Expr {
    std::string op;
    ExprPtr left;
    ExprPtr right;
    BinaryOp() : Expr(NT_BINARY_OP) {}
    BinaryOp(const std::string& o, ExprPtr l, ExprPtr r)
        : Expr(NT_BINARY_OP), op(o), left(std::move(l)), right(std::move(r)) {}
    std::string toString() const override;
};

struct UnaryOp : Expr {
    std::string op;
    ExprPtr operand;
    bool is_prefix = true;
    UnaryOp() : Expr(NT_UNARY_OP) {}
    UnaryOp(const std::string& o, ExprPtr e, bool pre = true)
        : Expr(NT_UNARY_OP), op(o), operand(std::move(e)), is_prefix(pre) {}
    std::string toString() const override;
};

struct Assign : Expr {
    std::string op = "=";  // "=", "+=", "-=", etc.
    ExprPtr target;
    ExprPtr value;
    Assign() : Expr(NT_ASSIGN) {}
    Assign(ExprPtr t, ExprPtr v, const std::string& o = "=")
        : Expr(NT_ASSIGN), op(o), target(std::move(t)), value(std::move(v)) {}
    std::string toString() const override;
};

struct Call : Expr {
    ExprPtr callee;
    std::vector<ExprPtr> args;
    std::string callee_name;
    Call() : Expr(NT_CALL) {}
    std::string toString() const override;
};

struct VarRef : Expr {
    std::string name;
    int var_id = -1;
    VarRef() : Expr(NT_VAR_REF) {}
    VarRef(const std::string& n) : Expr(NT_VAR_REF), name(n) {}
    std::string toString() const override { return name.empty() ? "<unknown>" : name; }
};

struct Const : Expr {
    int64_t int_val = 0;
    double fp_val = 0.0;
    bool is_fp = false;
    bool is_signed = false;
    Const() : Expr(NT_CONST) {}
    Const(int64_t v) : Expr(NT_CONST), int_val(v) { result_type = CType::u64(); }
    std::string toString() const override;
};

struct StringConst : Expr {
    std::string value;
    uint64_t address = 0;
    StringConst() : Expr(NT_STRING) { result_type = CType::str(); }
    StringConst(const std::string& v) : Expr(NT_STRING), value(v) { result_type = CType::str(); }
    std::string toString() const override;
};

struct Cast : Expr {
    CType target_type;
    ExprPtr expr;
    Cast() : Expr(NT_CAST) {}
    Cast(CType t, ExprPtr e) : Expr(NT_CAST), target_type(t), expr(std::move(e)) { result_type = t; }
    std::string toString() const override;
};

struct MemberAccess : Expr {
    ExprPtr base;
    std::string field_name;
    int field_offset = 0;
    bool is_pointer = false;  // true: base->field, false: base.field
    MemberAccess() : Expr(NT_MEMBER) {}
    std::string toString() const override;
};

struct Index : Expr {
    ExprPtr array;
    ExprPtr index;
    Index() : Expr(NT_INDEX) {}
    std::string toString() const override;
};

struct Ternary : Expr {
    ExprPtr condition;
    ExprPtr true_expr;
    ExprPtr false_expr;
    Ternary() : Expr(NT_TERNARY) {}
    Ternary(ExprPtr cond, ExprPtr t, ExprPtr f)
        : Expr(NT_TERNARY), condition(std::move(cond)), true_expr(std::move(t)), false_expr(std::move(f)) {}
    std::string toString() const override;
};

struct NullExpr : Expr {
    NullExpr() : Expr(NT_NULL) {}
    std::string toString() const override { return ""; }
};

// v10.0: Dereference expression — *expr (对标 Ghidra OP_PTRSUB / deref)
// Replaces UnaryOp("*", ...) for semantic clarity.
struct Deref : Expr {
    ExprPtr operand;
    Deref() : Expr(NT_DEREF) {}
    Deref(ExprPtr e) : Expr(NT_DEREF), operand(std::move(e)) {}
    std::string toString() const override;
};

// v10.0: Address-of expression — &expr (对标 Ghidra address-of)
struct AddressOf : Expr {
    ExprPtr operand;
    AddressOf() : Expr(NT_ADDRESSOF) {}
    AddressOf(ExprPtr e) : Expr(NT_ADDRESSOF), operand(std::move(e)) {}
    std::string toString() const override;
};

// v10.0: sizeof expression — sizeof(type) or sizeof(expr)
struct SizeOf : Expr {
    bool is_type = false;      // true: sizeof(Type), false: sizeof(expr)
    CType type_arg;            // for sizeof(type)
    ExprPtr expr_arg;          // for sizeof(expr)
    SizeOf() : Expr(NT_SIZEOF) {}
    std::string toString() const override;
};

// v10.0: Comma expression — (a, b) (对标 Ghidra comma operator)
struct CommaExpr : Expr {
    std::vector<ExprPtr> items;
    CommaExpr() : Expr(NT_COMMA) {}
    std::string toString() const override;
};

// v10.0: Enum constant reference — ENUM_VALUE (对标 Ghidra TypeEnum)
struct EnumConstRef : Expr {
    std::string enum_name;     // e.g., "STATUS_OK"
    std::string enum_type;     // optional: enum type name
    int64_t value = 0;
    EnumConstRef() : Expr(NT_ENUM_CONST) {}
    std::string toString() const override;
};

// v10.0: Global variable reference — g_0xADDR (带地址的全局变量)
// 对标 Ghidra pushSymbol: 从全局地址解析出的符号引用
struct GlobalVarRef : Expr {
    std::string name;          // resolved symbol name or "g_0x..."
    uint64_t address = 0;
    CType var_type;
    GlobalVarRef() : Expr(NT_GLOBAL_VAR) {}
    std::string toString() const override;
};

// v10.0: Bit-field access — expr : width (对标 Ghidra subpiece / bitfield)
struct BitFieldAccess : Expr {
    ExprPtr base;
    int offset = 0;    // bit offset within the word
    int width = 0;     // bit width
    BitFieldAccess() : Expr(NT_BITFIELD) {}
    std::string toString() const override;
};

// v10.0: new expression — new Type or new Type(expr) (C++ 反编译)
struct NewExpr : Expr {
    CType alloc_type;
    ExprPtr size_expr;         // for new Type[n]
    std::vector<ExprPtr> ctor_args;
    bool is_array = false;
    NewExpr() : Expr(NT_NEW) {}
    std::string toString() const override;
};

// v10.0: delete expression — delete expr or delete[] expr (C++ 反编译)
struct DeleteExpr : Expr {
    ExprPtr operand;
    bool is_array = false;     // true: delete[] expr
    DeleteExpr() : Expr(NT_DELETE) {}
    std::string toString() const override;
};

// v10.0: throw expression — throw expr (C++ 异常)
struct ThrowExpr : Expr {
    ExprPtr operand;
    ThrowExpr() : Expr(NT_THROW_EXPR) {}
    ThrowExpr(ExprPtr e) : Expr(NT_THROW_EXPR), operand(std::move(e)) {}
    std::string toString() const override;
};

// v10.0: Initialization list — {a, b, c} (已有枚举值 NT_INIT_LIST，补结构体)
struct InitList : Expr {
    std::vector<ExprPtr> items;
    InitList() : Expr(NT_INIT_LIST) {}
    std::string toString() const override;
};

// ── Function definition ──
struct Function {
    std::string name;
    uint64_t entry_addr = 0;
    CType return_type;
    std::vector<std::pair<std::string, CType>> params;
    StmtPtr body;
    std::vector<std::pair<std::string, CType>> local_vars;
    std::string comment;  // optional comment (e.g. function signature)

    std::string signature() const;
};

// ── Operator precedence ──
inline int getPrecedence(const std::string& op) {
    if (op == "||") return 1;
    if (op == "&&") return 2;
    if (op == "|" || op == "^") return 3;
    if (op == "&") return 4;
    if (op == "==" || op == "!=") return 5;
    if (op == "<" || op == ">" || op == "<=" || op == ">=") return 6;
    if (op == "<<" || op == ">>") return 7;
    if (op == "+" || op == "-") return 8;
    if (op == "*" || op == "/" || op == "%") return 9;
    return 10;  // unary, member access, etc.
}

inline bool needParen(const Expr* expr, int parent_prec, bool is_right = false) {
    if (expr->type != NT_BINARY_OP) return false;
    auto* bin = static_cast<const BinaryOp*>(expr);
    int my_prec = getPrecedence(bin->op);
    if (my_prec < parent_prec) return true;
    if (my_prec == parent_prec && is_right) return true;  // right-associative
    return false;
}

// ── Helper function declarations (used by ctree_beautify.hpp) ──
// These are defined in ctree_impl.cpp
int countVarRefs(const Expr* expr, const std::string& name);
void replaceVarRefs(ExprPtr& expr, const std::string& from, const Expr* replacement);
ExprPtr cloneExpr(const Expr* e);
bool exprHasSideEffects(const Expr* expr);

} // namespace ctree
