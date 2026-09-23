#pragma once
// c_printer.hpp — C code printer
// 对标 Ghidra PrintC + EmitPrettyPrint

#include "ctree.hpp"
#include <sstream>
#include <string>
#include <map>
#include <vector>

namespace ctree {

// v4.11: Simple struct/class definition for output
struct StructDef {
    std::string name;                   // demangled class name
    std::vector<std::string> baseClasses; // parent classes
    std::vector<std::string> vfuncNames;  // virtual function names
    bool isPolymorphic;                 // has vtable
};

// C code printer with Oppen-style pretty printing (simplified)
class CPrinter {
public:
    // Print a complete function
    std::string printFunction(const Function& func);

    // Print just the body (for testing)
    std::string printBody(const Stmt* body);

    // ── ELF symbol resolution (参考 Ghidra printc.cc pushSymbol) ──
    // Set the global name map (address → symbol name) from MicrocodeBlockArray.
    // When printing, VarRef nodes with g_XXXX names and Const nodes with
    // known addresses are resolved to real ELF symbol names.
    void setGlobalNames(const std::map<uint64_t, std::string>* names) {
        global_names_ = names;
    }
    void setStringRefs(const std::map<uint64_t, std::string>* refs) {
        string_refs_ = refs;
    }

    // v10.1: Set UTF-16 string map and string length map.
    // utf16Strings: addr → u16string content. When resolveConstAddr hits
    // such an address, the printer emits L"..." wide string literals.
    // stringLengths: addr → byte length. Used to annotate string literals
    // with a /* len=N */ comment for known-length strings.
    void setUtf16Strings(const std::map<uint64_t, std::u16string>* strs) {
        utf16_strings_ = strs;
    }
    void setStringLengths(const std::map<uint64_t, size_t>* lengths) {
        string_lengths_ = lengths;
    }

    // v4.11: Set struct/class definitions for output
    void setStructDefs(const std::map<std::string, StructDef>* defs) {
        struct_defs_ = defs;
    }

    // ── Cast hiding options (参考 Ghidra printc.cc castStrategy) ──
    // option_hide_exts: hide zero/sign extension casts (default: true)
    // option_nocasts: hide ALL casts (default: false)
    void setHideExtensions(bool hide) { option_hide_exts_ = hide; }
    void setNoCasts(bool nocasts) { option_nocasts_ = nocasts; }

private:
    std::ostringstream out_;
    int indent_level_ = 0;
    int label_counter_ = 0;

    // ── Oppen pretty-printing state (对标 Ghidra printlanguage.cc) ──
    // Ghidra's EmitPrettyPrint tracks the current column position and the
    // maximum line width to decide where to insert line breaks. We mirror
    // that here with a simplified Oppen-style algorithm: emitBreak(width)
    // emits a newline + indent when current_col_ + width would overflow
    // line_width_, otherwise emits a single space. emitText() emits the
    // text and updates current_col_ (resetting on '\n').
    int line_width_ = 120;        // max line width (对标 Ghidra line_width)
    int current_col_ = 0;         // current column position
    int indent_size_ = 4;         // spaces per indent level

    // Oppen-style line break: if current_col_ + text_width > line_width_,
    // emit newline + indent; otherwise emit a single space.
    void emitBreak(int width);
    // Emit text and track column position (resets on '\n').
    void emitText(const std::string& text);

    // ELF symbol tables (non-owning pointers, set before printing)
    const std::map<uint64_t, std::string>* global_names_ = nullptr;
    const std::map<uint64_t, std::string>* string_refs_ = nullptr;
    // v10.1: UTF-16 字符串与字符串长度映射
    const std::map<uint64_t, std::u16string>* utf16_strings_ = nullptr;
    const std::map<uint64_t, size_t>* string_lengths_ = nullptr;
    const std::map<std::string, StructDef>* struct_defs_ = nullptr;

    // v8.8: Ghidra-style variable rename map (对标 Ghidra HighSymbol naming).
    // Maps original decompiler-generated names (e.g., "tmp3", "v2") to
    // Ghidra-style names (e.g., "uVar1", "iVar2", "ptrVar3") based on
    // inferred type category. Built in emitLocalVarDecls, applied in
    // resolveVarName during expression emission.
    std::map<std::string, std::string> ghidra_rename_map_;

    // Cast hiding options (参考 Ghidra printc.cc castStrategy)
    bool option_hide_exts_ = true;   // hide zero/sign extensions
    bool option_nocasts_ = false;    // hide ALL casts

    void emitIndent();
    void emitNewline();
    void emitStmt(const Stmt* stmt);
    void emitExpr(const Expr* expr, int parent_prec = 0, bool is_right = false);
    void emitBlock(const Block* block, bool force_braces = true);
    void emitIf(const If* if_node);
    void emitWhile(const While* while_node);
    void emitDoWhile(const DoWhile* do_node);
    void emitFor(const For* for_node);
    void emitSwitch(const Switch* sw);
    void emitReturn(const Return* ret);
    void emitCall(const Call* call);
    void emitVarDecl(const VarDecl* decl);
    void emitType(const CType& type);
    void emitComment(const std::string& comment);

    // Operand printing helpers
    void emitBinaryOp(const BinaryOp* binop, int parent_prec);
    void emitUnaryOp(const UnaryOp* unop, int parent_prec);
    void emitAssign(const Assign* assign, int parent_prec);
    void emitMember(const MemberAccess* member);
    void emitCast(const Cast* cast, int parent_prec);
    void emitTernary(const Ternary* tern, int parent_prec);

    // 参考 Ghidra printc.cc checkArrayDeref:
    // *(ptr + offset) → ptr[offset] (array subscript instead of deref)
    // *ptr → *ptr (simple dereference)
    void emitDeref(const Expr* operand);

    // ── Operator precedence (参考 Ghidra printc.cc parentheses/emitOp) ──
    // Full C operator precedence table. Higher = binds tighter.
    // Covers all C operators: assignment(2) → comma(1), ternary(3),
    // logical(4-5), bitwise(6-8), comparison(9-10), shift(11),
    // arithmetic(12-13), unary(14), postfix(15).
    static int getOpPrecedence(const std::string& op, bool is_unary = false);

    // Get the precedence of an expression node (for parenthesization).
    // Returns 15 (highest) for atoms like VarRef/Const/Call.
    static int getExprPrecedence(const Expr* expr);

    // 参考 Ghidra printc.cc parentheses(): decide whether child needs
    // parens given the parent operator precedence and operand position.
    // is_right: true if child is the right operand of a binary op.
    bool needParens(const Expr* child, int parent_prec, bool is_right) const;

    // Local variable declarations
    // v11.5: Added optional filtered_local_vars parameter to support
    // pre-filtering in printFunction — only declare variables actually
    // referenced in the function body, removing unused declarations.
    void emitLocalVarDecls(const Function& func,
        const std::vector<std::pair<std::string, CType>>* filtered_local_vars = nullptr);

    // v3.14: Post-print safety net — scan final output for any residual
    // sp/pc/lr/fp register names (as whole words) and sanitize them.
    // This is the last line of defense after IR-level elimination
    // (eliminateSpecialRegisters) and CTree filtering (shouldEmitStmt).
    std::string sanitizeOutput(const std::string& code);

    // Auto-declare: scan AST body for VarRef nodes and declare unknown ones
    void collectVarRefs(const Node* node, std::set<std::string>& vars) const;

    // 参考 Ghidra printc.cc emitLocalVarDecls: detect variables used in
    // pointer contexts (deref, member access, array index) for type inference.
    void collectPointerVars(const Node* node, std::set<std::string>& ptr_vars) const;

    // Phase 3: Collect inferred types from VarRef nodes in CTree
    void collectVarTypes(const Node* node, std::map<std::string, CType>& types) const;

    // v8.8: Collect variables that appear as the result of comparison/logical
    // ops (==, !=, <, >, &&, ||) → bool candidates (对标 Ghidra boolean analysis).
    void collectBoolVars(const Node* node, std::set<std::string>& bool_vars) const;

    // v8.8: Collect variables that participate in arithmetic ops (+, -, *, /,
    // %, <<, >>, &, |, ^) → integer candidates (对标 Ghidra integer analysis).
    void collectIntVars(const Node* node, std::set<std::string>& int_vars) const;

    // v8.8: Collect variables that are assigned/compared with floating-point
    // constants or used in FP casts → float/double candidates.
    void collectFloatVars(const Node* node, std::set<std::string>& fp_vars) const;

    // v8.8: Ghidra-style variable naming based on inferred type category.
    // Returns "uVar"/"iVar"/"bVar"/"fVar"/"ptrVar"/"var" prefix.
    static std::string ghidraVarPrefix(const CType& type);

    // v9.7: Collect variables that are ASSIGNED to (appear as LHS of =).
    // Used by emitLocalVarDecls to avoid declaring uninitialized variables
    // that are only read but never written.
    void collectAssignedVars(const Node* node, std::set<std::string>& vars) const;

    // v9.19: Collect variable names declared in for-loop init (VarDecl nodes).
    // These need to be added to local_vars so they are declared before the body.
    void collectForInitDeclVars(const Node* node, std::set<std::string>& vars) const;

    // v11.0: Count READ usages of each variable in the AST (对标 Ghidra
    // copy-propagation safety net). Used by emitLocalVarDecls to skip
    // single-use temporaries. A plain-VarRef target of a simple "=" is a
    // definition (write) and is NOT counted; everything else is a use.
    void countVarUsage(const Node* node, std::map<std::string, int>& counts) const;

    // 参考 Ghidra printc.cc emitBlock: check if a statement is a single
    // non-block statement (for brace omission).
    static bool isSingleStmt(const Stmt* stmt);
    // Check if a branch is empty (null or empty Block)
    static bool isEmptyBranch(const Stmt* stmt);

    // Utility
    std::string formatHex(int64_t val) const;
    std::string escapeString(const std::string& s) const;
    // v10.1: Escape a UTF-16 string for output inside L"...".
    // ASCII-range chars pass through (with C escaping); non-ASCII code
    // units become \uXXXX (suitable for a wide string literal).
    std::string escapeUtf16(const std::u16string& ws) const;
    // v10.1: Convert a UTF-16 string to a best-effort ASCII/escape form
    // (used to store content in a std::string sym_name slot).
    std::string u16ToAscii(const std::u16string& ws) const;

    // ── ELF symbol resolution helpers (参考 Ghidra printc.cc pushSymbol) ──
    // Resolve a g_XXXX VarRef name to a real ELF symbol name.
    // Returns the original name if not found in global_names_.
    std::string resolveVarName(const std::string& name) const;

    // Resolve a constant integer that might be a known address.
    // Returns the symbol name if found, or empty string if not.
    // Also checks string_refs_ for string literal addresses.
    // v10.1: is_utf16 is set true when the address maps to a UTF-16 string;
    //        in that case the returned string holds the UTF-16 content
    //        converted to a best-effort ASCII/escape form, and the caller
    //        should emit it as L"...". out_len receives the recorded byte
    //        length (0 if unknown) for length annotation.
    std::string resolveConstAddr(int64_t val, bool& is_string,
                                 bool& is_utf16, size_t& out_len) const;
    // Backward-compatible overload (treats the address as ASCII-only).
    std::string resolveConstAddr(int64_t val, bool& is_string) const {
        bool dummy_utf16 = false;
        size_t dummy_len = 0;
        return resolveConstAddr(val, is_string, dummy_utf16, dummy_len);
    }

    // Check if an address is in global_names_ (exact or nearby match)
    bool isKnownAddress(uint64_t addr) const;

    // 参考 Ghidra printc.cc castStrategy: decide whether to hide a cast.
    // Returns true if the cast should be omitted from output.
    bool shouldHideCast(const Cast* cast) const;
};

// Standalone: print a CTree node to string
inline std::string printExpr(const Expr* expr) {
    if (!expr) return "";
    CPrinter printer;
    std::string body = printer.printBody(nullptr);
    return expr->toString();
}

} // namespace ctree
