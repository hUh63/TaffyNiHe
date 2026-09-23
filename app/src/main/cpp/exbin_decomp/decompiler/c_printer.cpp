// c_printer.cpp — C code printer implementation
// Generates compilable C code from CTree AST.
// GCC -c -Wall zero error/warning is the acceptance criterion.

#include "c_printer.hpp"
#include "lvar_allocator.hpp"
#include <cstdio>
#include <cctype>
#include <cstring>
#include <algorithm>
#include <set>
#include <map>
#include <sstream>

namespace ctree {

// ── Utility: format hex value ──
// 参考 Ghidra printc.cc: use decimal for small values, hex for large ones.
// Ghidra uses decimal for values that fit in a typical immediate field
// (0-0xFFF) and hex for larger values (addresses, bitmasks).
std::string CPrinter::formatHex(int64_t val) const {
    char buf[32];
    if (val == 0) return "0";
    // v6.3: Use decimal for small positive values (0-0xFFF)
    // to improve readability. Previously only 0-9 used decimal,
    // causing "0xa" instead of "10" in comparisons.
    if (val >= 0 && val <= 0xFFF) {
        snprintf(buf, sizeof(buf), "%lld", (long long)val);
        return buf;
    }
    if (val >= 0)
        snprintf(buf, sizeof(buf), "0x%llx", (unsigned long long)val);
    else
        snprintf(buf, sizeof(buf), "%lld", (long long)val);
    return buf;
}

// ── Utility: escape string ──
std::string CPrinter::escapeString(const std::string& s) const {
    std::string result;
    for (char c : s) {
        switch (c) {
            case '"':  result += "\\\""; break;
            case '\\': result += "\\\\"; break;
            case '\n': result += "\\n"; break;
            case '\t': result += "\\t"; break;
            case '\r': result += "\\r"; break;
            case '\0': result += "\\0"; break;
            default:
                if ((unsigned char)c >= 0x20 && (unsigned char)c < 0x7f)
                    result += c;
                else {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\x%02x", (unsigned char)c);
                    result += buf;
                }
        }
    }
    return result;
}

// ════════════════════════════════════════════════════════════════════
// v10.1: UTF-16 string helpers
// ════════════════════════════════════════════════════════════════════

// Escape a UTF-16 string for output inside a wide string literal L"...".
// ASCII-range printable chars pass through; common control chars get C
// escapes; everything else becomes \uXXXX (valid in a wide string literal).
std::string CPrinter::escapeUtf16(const std::u16string& ws) const {
    std::string result;
    for (char16_t ch : ws) {
        switch (ch) {
            case u'"':  result += "\\\""; break;
            case u'\\': result += "\\\\"; break;
            case u'\n': result += "\\n"; break;
            case u'\t': result += "\\t"; break;
            case u'\r': result += "\\r"; break;
            case 0:     result += "\\0"; break;
            default:
                if (ch >= 0x20 && ch < 0x7F) {
                    result += (char)ch;
                } else {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", (unsigned)ch);
                    result += buf;
                }
        }
    }
    return result;
}

// Convert a UTF-16 string to a best-effort ASCII/escape form stored in a
// std::string. Used when a UTF-16 string content must be carried through a
// std::string slot (e.g. MicroInsn::sym_name). ASCII chars pass through;
// non-ASCII become \uXXXX. This mirrors MicrocodeEmitter::resolveStringRef.
// v10.1: 引号与反斜杠在此处一并转义，因此 UTF-16 路径的调用方无需
// 再调用 escapeString() 二次转义（否则 \uXXXX 会被破坏为 \\uXXXX）。
std::string CPrinter::u16ToAscii(const std::u16string& ws) const {
    std::string result;
    result.reserve(ws.size());
    for (char16_t ch : ws) {
        if (ch == u'"') {
            result += "\\\"";
        } else if (ch == u'\\') {
            result += "\\\\";
        } else if (ch >= 0x20 && ch < 0x7F) {
            result += (char)ch;
        } else {
            char buf[8];
            snprintf(buf, sizeof(buf), "\\u%04x", (unsigned)ch);
            result += buf;
        }
    }
    return result;
}

// ── Utility: simplify C++ template names ──
// Reduces std::basic_string<...> → std::string, etc.
// This makes the pseudo-C output more readable by stripping
// the full template expansion from __cxa_demangle.
static std::string simplifyCppName(const std::string& name) {
    // Helper: find matching '>' for the template starting at openPos
    auto findMatchingAngle = [](const std::string& s, size_t openPos) -> size_t {
        int depth = 0;
        for (size_t i = openPos; i < s.size(); i++) {
            if (s[i] == '<') depth++;
            else if (s[i] == '>') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return std::string::npos;
    };

    // std::basic_string<char, ...> → std::string
    const char* patterns[][2] = {
        {"std::basic_string", "string"},
        {"std::__cxx11::basic_string", "string"},
        {"std::basic_istream", "istream"},
        {"std::__cxx11::basic_istream", "istream"},
        {"std::basic_ostream", "ostream"},
        {"std::__cxx11::basic_ostream", "ostream"},
        {"std::basic_iostream", "iostream"},
        {"std::__cxx11::basic_iostream", "iostream"},
        {"std::basic_stringstream", "stringstream"},
        {"std::__cxx11::basic_stringstream", "stringstream"},
    };

    for (auto& p : patterns) {
        const char* search = p[0];
        const char* replace = p[1];
        size_t pos = name.find(search);
        if (pos != std::string::npos) {
            // Found the pattern — now find the matching '>' for the template
            size_t openPos = name.find('<', pos);
            if (openPos == std::string::npos) continue;
            size_t closePos = findMatchingAngle(name, openPos);
            if (closePos == std::string::npos) continue;
            // Build the result: prefix + simplified_name + suffix
            std::string prefix = name.substr(0, pos);
            std::string suffix = name.substr(closePos + 1);
            // Remove trailing space before >
            if (!suffix.empty() && suffix[0] == ' ') suffix = suffix.substr(1);
            std::string result = prefix + "std::" + replace + suffix;

            // Also simplify constructor names in the result:
            // std::string::basic_string → std::string::string
            const char* ctorPatterns[][2] = {
                {"::basic_string", "::string"},
                {"::basic_istream", "::istream"},
                {"::basic_ostream", "::ostream"},
                {"::basic_iostream", "::iostream"},
                {"::basic_stringstream", "::stringstream"},
            };
            for (auto& cp : ctorPatterns) {
                size_t cpPos = result.find(cp[0]);
                while (cpPos != std::string::npos) {
                    result = result.substr(0, cpPos) + cp[1] +
                             result.substr(cpPos + strlen(cp[0]));
                    cpPos = result.find(cp[0], cpPos + strlen(cp[1]));
                }
            }

            return result;
        }
    }

    return name;
}

// v11.6: 检测表达式是否为构造函数调用
// 检查 callee_name 中最后两个 :: 分隔的标识符是否相同
// (如 ClassName::ClassName)，或特殊情况如 std::string::basic_string。
// 用于 emitCall 中跳过 this 指针参数。
static bool isConstructorCall(const std::string& callee_name) {
    // Strip parameter list first (跳过模板内的括号)
    std::string clean = callee_name;
    int angleDepth = 0;
    for (size_t i = 0; i < clean.size(); i++) {
        if (clean[i] == '<') angleDepth++;
        else if (clean[i] == '>') angleDepth--;
        else if (clean[i] == '(' && angleDepth == 0) {
            clean = clean.substr(0, i);
            break;
        }
    }

    // 找到最后一个 :: 和方法名
    size_t lastColon = clean.rfind("::");
    if (lastColon == std::string::npos || lastColon < 2) return false;
    std::string methodName = clean.substr(lastColon + 2);

    // 找到类名（最后一个 :: 之前的标识符）
    size_t secondLastColon = clean.rfind("::", lastColon - 1);
    std::string className;
    if (secondLastColon != std::string::npos) {
        std::string between = clean.substr(secondLastColon + 2, lastColon - secondLastColon - 2);
        // 移除模板参数获取裸类名: basic_string<char,...> → basic_string
        size_t tmpl = between.find('<');
        className = (tmpl != std::string::npos) ? between.substr(0, tmpl) : between;
    } else {
        // 没有命名空间，整个前面部分就是类名
        className = clean.substr(0, lastColon);
    }

    // 标准构造函数模式: ClassName::ClassName
    if (methodName == className) return true;

    // 特殊: std::string::basic_string (std::string 的构造函数 demangled 名)
    // 或 std::string::string (某些编译器)
    if ((methodName == "basic_string" || methodName == "string") &&
        (className == "string" || className == "basic_string"))
        return true;

    return false;
}

// v12.0: 检测 C++ 成员函数调用（非构造函数）
// 检查 callee_name 中是否有 :: 分隔符，且不是构造函数。
// 用于 emitCall 中跳过 this 指针参数（第一个参数），
// 因为成员函数的 this 指针通过 r0 隐式传递，不应出现在伪代码中。
// 参考 IDA 的处理方式：所有成员函数调用都不显示 this 指针。
static bool isMemberFunction(const std::string& callee_name) {
    // 先检测是否是构造函数（构造函数已有自己的处理逻辑）
    if (isConstructorCall(callee_name)) return false;

    // Strip parameter list first
    std::string clean = callee_name;
    int angleDepth = 0;
    for (size_t i = 0; i < clean.size(); i++) {
        if (clean[i] == '<') angleDepth++;
        else if (clean[i] == '>') angleDepth--;
        else if (clean[i] == '(' && angleDepth == 0) {
            clean = clean.substr(0, i);
            break;
        }
    }

    // 检查是否有 :: 分隔符（表示是成员函数）
    size_t lastColon = clean.rfind("::");
    if (lastColon == std::string::npos || lastColon < 2) return false;

    // 提取方法名
    std::string methodName = clean.substr(lastColon + 2);
    if (methodName.empty()) return false;

    return true;
}

// v9.8: 通用构造函数名简化: ClassName::ClassName → ClassName
// 例如: RakNet::SystemAddress::SystemAddress → RakNet::SystemAddress
// v10.5: Also strip demangled parameter type lists like (char const*, ...)
static std::string simplifyCtorName(const std::string& name) {
    // v10.5: Strip trailing parameter type list from demangled names.
    // Demangled C++ names include parameter types: ClassName::Method(int, char*)
    // We need to strip everything from the parameter-list '(' onwards.
    // The parameter-list '(' is the first '(' that is NOT inside angle brackets <>.
    std::string clean = name;
    {
        int angleDepth = 0;
        for (size_t i = 0; i < clean.size(); i++) {
            if (clean[i] == '<') angleDepth++;
            else if (clean[i] == '>') angleDepth--;
            else if (clean[i] == '(' && angleDepth == 0) {
                // Found the parameter-list '(' — strip from here
                clean = clean.substr(0, i);
                break;
            }
        }
    }

    // Find the last :: in the name
    size_t lastColon = clean.rfind("::");
    if (lastColon == std::string::npos || lastColon < 2) return clean;
    
    // Extract the last identifier (after ::)
    std::string lastIdent = clean.substr(lastColon + 2);
    
    // Find the second-to-last ::
    size_t secondLastColon = clean.rfind("::", lastColon - 1);
    std::string prevIdent;
    if (secondLastColon != std::string::npos) {
        prevIdent = clean.substr(secondLastColon + 2, lastColon - secondLastColon - 2);
    } else {
        prevIdent = clean.substr(0, lastColon);
    }
    
    // If the last two identifiers are the same, it's a constructor call
    if (lastIdent == prevIdent) {
        // Don't preserve suffix — printer adds () with arguments
        return clean.substr(0, lastColon);
    }
    
    return clean;
}

// ════════════════════════════════════════════════════════════════════
// ELF symbol resolution (参考 Ghidra printc.cc pushSymbol)
//
// Ghidra's pushSymbol() looks up the P-code address in the database
// and emits the real symbol name. We do the same at the print layer:
// VarRef "g_XXXX" → parse hex → lookup global_names_ → real name
// Const 0xNNNN   → lookup global_names_ → real name (or string_refs_)
//
// This is a print-layer defense-in-depth: even if the CTree builder
// couldn't resolve an address (e.g., computed via fuseAddressComputation),
// the printer can still substitute the real symbol name.
// ════════════════════════════════════════════════════════════════════

std::string CPrinter::resolveVarName(const std::string& name) const {
    // v8.8: Apply Ghidra-style variable rename if a mapping exists
    // (对标 Ghidra HighSymbol naming: uVar1/iVar2/ptrVar3).
    if (!ghidra_rename_map_.empty()) {
        auto rit = ghidra_rename_map_.find(name);
        if (rit != ghidra_rename_map_.end())
            return rit->second;
    }

    // Resolve g_XXXX and global_XXXX formats (generated by CTreeBuilder/microcode fallback)
    if (!global_names_)
        return name;

    const char* hex = nullptr;
    if (name.size() >= 9 && name[0] == 'g' && name[1] == 'l' && name[2] == 'o' &&
        name[3] == 'b' && name[4] == 'a' && name[5] == 'l' && name[6] == '_') {
        // global_%llx format (legacy, from older microcode emitter)
        hex = name.c_str() + 7;
    } else if (name.size() >= 3 && name[0] == 'g' && name[1] == '_') {
        // g_%llx format (current)
        hex = name.c_str() + 2;
    } else {
        return name;
    }

    char* endptr = nullptr;
    unsigned long long addr = strtoull(hex, &endptr, 16);
    if (endptr == hex || *endptr != '\0')
        return name;  // not valid hex

    // Look up in global_names_
    auto it = global_names_->find((uint64_t)addr);
    if (it != global_names_->end() && !it->second.empty())
        return it->second;

    // Not found — return original name (fallback per spec)
    return name;
}

std::string CPrinter::resolveConstAddr(int64_t val, bool& is_string,
                                       bool& is_utf16, size_t& out_len) const {
    is_string = false;
    is_utf16 = false;
    out_len = 0;
    uint64_t addr = (uint64_t)val;

    // v10.1: Check UTF-16 strings first. A UTF-16 hit takes precedence over
    // a plain ASCII string_ref because utf16Strings is populated only when
    // the data genuinely looked like UTF-16LE (high byte zero for every
    // code unit). When matched, the caller should emit L"...". The returned
    // string holds a best-effort ASCII/escape form of the content.
    if (utf16_strings_) {
        auto it = utf16_strings_->find(addr);
        if (it != utf16_strings_->end()) {
            is_string = true;
            is_utf16 = true;
            // Look up recorded byte length (fallback: 2 * code units)
            if (string_lengths_) {
                auto lit = string_lengths_->find(addr);
                if (lit != string_lengths_->end()) out_len = lit->second;
            }
            if (out_len == 0) out_len = it->second.size() * 2;
            return u16ToAscii(it->second);
        }
    }

    // Check string_refs_ first (string literal addresses)
    if (string_refs_) {
        auto it = string_refs_->find(addr);
        if (it != string_refs_->end()) {
            // v3.20: __const__ prefix → 常量折叠 (Ghidra-style)
            if (it->second.size() > 9 && it->second.substr(0, 9) == "__const__") {
                is_string = false;  // print as identifier, not string
                return it->second.substr(9);  // strip prefix
            }
            is_string = true;
            is_utf16 = false;
            // v10.1: Look up recorded byte length for length annotation
            if (string_lengths_) {
                auto lit = string_lengths_->find(addr);
                if (lit != string_lengths_->end()) out_len = lit->second;
            }
            if (out_len == 0) out_len = it->second.size();
            return it->second;
        }
    }

    // Check global_names_
    if (global_names_) {
        auto it = global_names_->find(addr);
        if (it != global_names_->end() && !it->second.empty())
            return it->second;
    }

    return "";  // not a known address
}

bool CPrinter::isKnownAddress(uint64_t addr) const {
    if (global_names_ && global_names_->find(addr) != global_names_->end())
        return true;
    if (string_refs_ && string_refs_->find(addr) != string_refs_->end())
        return true;
    return false;
}

// ════════════════════════════════════════════════════════════════════
// Cast hiding (参考 Ghidra printc.cc castStrategy)
//
// Ghidra's castStrategy decides whether a cast is necessary based on:
// 1. option_nocasts — hide ALL casts (for readability)
// 2. option_hide_exts — hide zero/sign extension casts
// 3. Type compatibility — if source and target are compatible, hide
//
// We hide:
// - All casts if option_nocasts_ is true
// - Width promotions (uint32→uint64) if option_hide_exts_ is true
// - Same-type casts (uint64→uint64)
// - Integer-to-pointer casts (common in decompiler output)
// - Pointer-to-pointer casts
// ════════════════════════════════════════════════════════════════════

bool CPrinter::shouldHideCast(const Cast* cast) const {
    // option_nocasts: hide everything
    if (option_nocasts_) return true;

    const CType& tgt = cast->target_type;
    const CType& src = cast->expr ? cast->expr->result_type : CType::unknown();

    // Helper: is integer category?
    auto isInt = [](CType::Category c) {
        return c == CType::TC_INT8 || c == CType::TC_UINT8 ||
               c == CType::TC_INT16 || c == CType::TC_UINT16 ||
               c == CType::TC_INT32 || c == CType::TC_UINT32 ||
               c == CType::TC_INT64 || c == CType::TC_UINT64 ||
               c == CType::TC_BOOL;
    };
    // Helper: is pointer category?
    auto isPtr = [](CType::Category c) {
        return c == CType::TC_POINTER || c == CType::TC_ARRAY ||
               c == CType::TC_STRUCT_PTR || c == CType::TC_FUNC_PTR ||
               c == CType::TC_STRING;
    };

    // If source type is unknown (not inferred), hide the cast —
    // decompiler output typically has unknown types that shouldn't show casts.
    // v9.28: Exception — when the target is a specific pointer type
    // (e.g., uint64_t*), the cast is meaningful. Hiding it would produce
    // *p instead of *(uint64_t*)p, which is invalid C when p is void*.
    if (src.category == CType::TC_UNKNOWN) {
        if (isPtr(tgt.category) && !tgt.pointee_type.empty() && tgt.pointee_type != "void")
            return false;
        return true;
    }

    // Same category and width → definitely hide
    if (src.category == tgt.category && src.width == tgt.width)
        return true;

    // ── Unconditional cast-hiding rules (always applied) ──
    // These are no-op or promotion casts that never change the bit
    // pattern in a semantically meaningful way for decompiler output.

    // bool → int: bool promotes to any integer (true→1, false→0).
    if (src.category == CType::TC_BOOL && isInt(tgt.category))
        return true;

    // Same width, different signedness (int32→uint32, int64→uint64):
    // the bit pattern is identical, so the cast is a no-op. This also
    // covers int8→uint8, int16→uint16 and the reverse directions.
    if (isInt(src.category) && isInt(tgt.category) &&
        src.width == tgt.width)
        return true;

    // float → double: usual floating-point promotion (no precision loss).
    if (src.category == CType::TC_FLOAT && tgt.category == CType::TC_DOUBLE)
        return true;

    // ── Conditional rules (only when option_hide_exts_ is true) ──
    if (option_hide_exts_) {
        // Hide ALL integer extensions: any int→int cast where the target
        // is at least as wide as the source, regardless of signedness.
        // This subsumes the previous signedness-specific rules and matches
        // Ghidra's castStrategy which treats every integer width promotion
        // as hideable. (int8→uint64, int32→uint64, uint8→int32, etc.)
        if (isInt(src.category) && isInt(tgt.category) &&
            tgt.width >= src.width)
            return true;

        // Integer to pointer (common: uint64 → void*)
        // v9.27: Don't hide when the operand is a bare constant.
        // *(int*)4 is valid C, but *4 is not — you can't dereference
        // a bare integer literal without a cast.
        if (isInt(src.category) && isPtr(tgt.category) && src.width >= tgt.width) {
            if (cast->expr && cast->expr->type == NT_CONST)
                return false;
            return true;
        }

        // Pointer to pointer
        if (isPtr(src.category) && isPtr(tgt.category))
            return true;

        // Pointer to integer (less common but safe to hide)
        if (isPtr(src.category) && isInt(tgt.category) && tgt.width >= src.width)
            return true;
    }

    return false;
}

// ════════════════════════════════════════════════════════════════════
// Operator precedence (参考 Ghidra printc.cc optoken table + parentheses())
//
// Ghidra uses a 70-level optoken table with stage values. We use a
// simplified 15-level system matching the C standard:
//
//   15: postfix  (->, ., [], ())
//   14: unary    (!, ~, *, &, -, +, ++, --)
//   13: mult     (*, /, %)
//   12: add      (+, -)
//   11: shift    (<<, >>)
//   10: rel      (<, <=, >, >=)
//    9: eq       (==, !=)
//    8: bit-and  (&)
//    7: bit-xor  (^)
//    6: bit-or   (|)
//    5: log-and  (&&)
//    4: log-or   (||)
//    3: ternary  (?:)
//    2: assign   (=, +=, -=, etc.)
//    1: comma    (,)
// ════════════════════════════════════════════════════════════════════

int CPrinter::getOpPrecedence(const std::string& op, bool is_unary) {
    // 对标 Ghidra printc.cc optoken table: Ghidra uses a ~70-entry stage
    // system. We expose the same information through a fine-grained 15-level
    // table that exactly mirrors the C standard precedence climbing so that
    // needParens() can make correct associativity decisions.
    if (is_unary) {
        // Postfix increment/decrement share the postfix tier (15) even when
        // reached through the unary path — they never require parens around
        // their operand and bind tighter than every binary operator.
        if (op == "++" || op == "--") return 15;  // postfix
        if (op == "!" || op == "~") return 14;    // logical/bitwise not
        if (op == "-" || op == "+") return 14;    // unary minus/plus
        if (op == "*" || op == "&") return 14;    // deref/addressof
        if (op == "sizeof") return 14;
        return 14;
    }
    // Postfix
    if (op == "()" || op == "[]") return 15;
    if (op == "->" || op == ".") return 15;
    if (op == "++" || op == "--") return 15;  // postfix
    // Unary — handled by is_unary branch above.
    // Multiplicative
    if (op == "*" || op == "/" || op == "%") return 13;
    // Additive
    if (op == "+" || op == "-") return 12;
    // Shift
    if (op == "<<" || op == ">>") return 11;
    // Relational
    if (op == "<" || op == "<=" || op == ">" || op == ">=") return 10;
    // Equality
    if (op == "==" || op == "!=") return 9;
    // Bitwise AND
    if (op == "&") return 8;
    // Bitwise XOR
    if (op == "^") return 7;
    // Bitwise OR
    if (op == "|") return 6;
    // Logical AND
    if (op == "&&") return 5;
    // Logical OR
    if (op == "||") return 4;
    // Conditional
    if (op == "?:") return 3;
    // Assignment
    if (op == "=" || op == "+=" || op == "-=" || op == "*=" ||
        op == "/=" || op == "%=" || op == "&=" || op == "|=" ||
        op == "^=" || op == "<<=" || op == ">>=") return 2;
    // Comma
    if (op == ",") return 1;
    return 15;  // unknown — treat as highest (no parens needed)
}

int CPrinter::getExprPrecedence(const Expr* expr) {
    if (!expr) return 15;
    switch (expr->type) {
        case NT_BINARY_OP: {
            auto* bin = static_cast<const BinaryOp*>(expr);
            return getOpPrecedence(bin->op);
        }
        case NT_UNARY_OP: {
            auto* un = static_cast<const UnaryOp*>(expr);
            return getOpPrecedence(un->op, true);
        }
        case NT_ASSIGN: {
            auto* as = static_cast<const Assign*>(expr);
            return getOpPrecedence(as->op);
        }
        case NT_TERNARY:
            return 3;
        case NT_CALL:
        case NT_INDEX:
        case NT_MEMBER:
            return 15;  // postfix — highest
        case NT_CAST:
            return 14;  // cast has same precedence as unary
        // Atoms — no parens ever needed
        case NT_VAR_REF:
        case NT_CONST:
        case NT_STRING:
        case NT_NULL:
        default:
            return 15;
    }
}

bool CPrinter::needParens(const Expr* child, int parent_prec, bool is_right) const {
    int child_prec = getExprPrecedence(child);

    // Child binds looser than parent → always need parens
    if (child_prec < parent_prec)
        return true;

    if (child_prec != parent_prec)
        return false;

    // Same precedence — apply associativity and the special rules below.
    //
    // 对标 Ghidra printc.cc parentheses(): Ghidra's parenthesization
    // considers not only precedence but also operator-specific quirks
    // (assignment RHS, ternary branch nesting). We mirror that here.

    bool parent_is_assign = (parent_prec == 2);   // assignment ops
    bool parent_is_ternary = (parent_prec == 3);  // ?:

    // Rule (对标 Ghidra): For an assignment's right operand at the same
    // precedence, the child must itself be an assignment — this is the
    // right-associative chaining case (a = b = c) and needs no parens.
    // Any non-assignment operator at a lower precedence (e.g. comma,
    // prec 1) was already caught by child_prec < parent_prec above, so
    // reaching here with parent_prec == 2 implies the child is an
    // assignment and we omit parens to allow chaining.
    if (parent_is_assign && is_right) {
        return false;  // a = b = c — chaining, no parens
    }

    // Rule (对标 Ghidra): For ternary branches, when the child is also a
    // ternary (same precedence), always add parens to make the nesting
    // explicit. C grammar would allow omitting them in some positions
    // (e.g. a ? b : c ? d : e), but Ghidra-style output favors clarity:
    //   a ? (b ? c : d) : (e ? f : g)
    // This applies to all three ternary positions (condition, true, false).
    if (parent_is_ternary) {
        return true;
    }

    // Standard associativity for remaining same-precedence cases:
    // Right-associative ops (assignment): left child at same level needs
    //   parens — e.g. (a = b) = c.
    // Left-associative ops (most binary): right child at same level needs
    //   parens — e.g. a - (b - c).
    if (parent_is_assign) {
        return !is_right;  // left child of assignment
    }
    return is_right;  // left-associative: right child
}

void CPrinter::emitIndent() {
    for (int i = 0; i < indent_level_; i++)
        out_ << "    ";
}

void CPrinter::emitNewline() {
    out_ << "\n";
}

// ════════════════════════════════════════════════════════════════════
// Oppen pretty-printing (对标 Ghidra printlanguage.cc EmitPrettyPrint)
//
// emitBreak(width): a "soft" break point. If emitting `width` more
// characters would overflow line_width_, we instead emit a newline
// followed by the current indentation, and reset current_col_ to the
// indent width. Otherwise we emit a single space and bump current_col_.
//
// emitText(text): emits literal text and tracks the column position.
// Any embedded '\n' resets current_col_ to 0 (mirroring what the
// terminal would do). Non-newline characters each advance current_col_.
// ════════════════════════════════════════════════════════════════════
void CPrinter::emitBreak(int width) {
    if (current_col_ + width > line_width_) {
        out_ << '\n';
        emitIndent();
        // After the newline + indent, the cursor sits at the indent column.
        current_col_ = indent_level_ * indent_size_;
    } else {
        out_ << ' ';
        current_col_++;
    }
}

void CPrinter::emitText(const std::string& text) {
    out_ << text;
    // Track column position (approximate — count chars since last newline).
    // This matches Ghidra's printlanguage.cc column tracking, which is
    // itself approximate and resets on explicit newlines.
    for (char c : text) {
        if (c == '\n') current_col_ = 0;
        else current_col_++;
    }
}

void CPrinter::emitType(const CType& type) {
    // 对标 Ghidra printc.cc emitType:
    // 1. Unknown types default to uint64_t (Ghidra uses "undefined")
    // 2. const-qualified types get "const" prefix
    // 3. Pointer types use proper spacing: "void *" not "void*"
    if (type.is_const) {
        out_ << "const ";
    }
    out_ << type.toCString();
}

void CPrinter::emitComment(const std::string& comment) {
    out_ << "/* " << comment << " */";
}

// ── Emit local variable declarations ──
void CPrinter::collectVarRefs(const Node* node, std::set<std::string>& vars) const {
    if (!node) return;

    switch (node->type) {
        case NT_VAR_REF: {
            const auto* vr = static_cast<const VarRef*>(node);
            if (!vr->name.empty() && vr->name != "0")
                vars.insert(vr->name);
            break;
        }
        case NT_BINARY_OP: {
            const auto* bin = static_cast<const BinaryOp*>(node);
            collectVarRefs(bin->left.get(), vars);
            collectVarRefs(bin->right.get(), vars);
            break;
        }
        case NT_UNARY_OP: {
            const auto* un = static_cast<const UnaryOp*>(node);
            collectVarRefs(un->operand.get(), vars);
            break;
        }
        case NT_ASSIGN: {
            const auto* as = static_cast<const Assign*>(node);
            collectVarRefs(as->target.get(), vars);
            collectVarRefs(as->value.get(), vars);
            break;
        }
        case NT_CALL: {
            const auto* call = static_cast<const Call*>(node);
            for (auto& arg : call->args)
                collectVarRefs(arg.get(), vars);
            // v4.5: For indirect calls like (*tmp3)(...), collect tmp3 as used var
            if (!call->callee_name.empty() && call->callee_name.size() > 2 &&
                call->callee_name[0] == '(' && call->callee_name[1] == '*') {
                size_t end = call->callee_name.find(')', 2);
                if (end != std::string::npos) {
                    std::string varName = call->callee_name.substr(2, end - 2);
                    if (!varName.empty()) vars.insert(varName);
                }
            }
            break;
        }
        case NT_CAST: {
            const auto* cast = static_cast<const Cast*>(node);
            collectVarRefs(cast->expr.get(), vars);
            break;
        }
        case NT_MEMBER: {
            const auto* mem = static_cast<const MemberAccess*>(node);
            collectVarRefs(mem->base.get(), vars);
            break;
        }
        case NT_INDEX: {
            const auto* idx = static_cast<const Index*>(node);
            collectVarRefs(idx->array.get(), vars);
            collectVarRefs(idx->index.get(), vars);
            break;
        }
        case NT_TERNARY: {
            const auto* tern = static_cast<const Ternary*>(node);
            collectVarRefs(tern->condition.get(), vars);
            collectVarRefs(tern->true_expr.get(), vars);
            collectVarRefs(tern->false_expr.get(), vars);
            break;
        }
        case NT_BLOCK: {
            const auto* block = static_cast<const Block*>(node);
            for (auto& s : block->statements)
                collectVarRefs(s.get(), vars);
            break;
        }
        case NT_IF: {
            const auto* ifn = static_cast<const If*>(node);
            collectVarRefs(ifn->condition.get(), vars);
            collectVarRefs(ifn->then_branch.get(), vars);
            collectVarRefs(ifn->else_branch.get(), vars);
            break;
        }
        case NT_WHILE: {
            const auto* wh = static_cast<const While*>(node);
            collectVarRefs(wh->condition.get(), vars);
            collectVarRefs(wh->body.get(), vars);
            break;
        }
        case NT_DO_WHILE: {
            const auto* dw = static_cast<const DoWhile*>(node);
            collectVarRefs(dw->body.get(), vars);
            collectVarRefs(dw->condition.get(), vars);
            break;
        }
        case NT_FOR: {
            const auto* fr = static_cast<const For*>(node);
            collectVarRefs(fr->init.get(), vars);
            collectVarRefs(fr->condition.get(), vars);
            collectVarRefs(fr->increment.get(), vars);
            collectVarRefs(fr->body.get(), vars);
            break;
        }
        case NT_RETURN: {
            const auto* ret = static_cast<const Return*>(node);
            collectVarRefs(ret->value.get(), vars);
            break;
        }
        case NT_EXPR_STMT: {
            const auto* es = static_cast<const ExprStmt*>(node);
            collectVarRefs(es->expr.get(), vars);
            break;
        }
        case NT_DECL: {
            const auto* decl = static_cast<const VarDecl*>(node);
            collectVarRefs(decl->init_expr.get(), vars);
            break;
        }
        case NT_SWITCH: {
            const auto* sw = static_cast<const Switch*>(node);
            collectVarRefs(sw->expr.get(), vars);
            for (auto& [val, body] : sw->cases)
                collectVarRefs(body.get(), vars);
            collectVarRefs(sw->default_body.get(), vars);
            break;
        }
        default:
            break;
    }
}

// 参考 Ghidra printc.cc emitLocalVarDecls: detect variables used in
// pointer contexts (*var, var->field, var[index]) for type inference.
void CPrinter::collectPointerVars(const Node* node, std::set<std::string>& ptr_vars) const {
    if (!node) return;

    switch (node->type) {
        case NT_UNARY_OP: {
            auto* un = static_cast<const UnaryOp*>(node);
            // *var → var is a pointer (direct dereference)
            // *(type*)var → var is a pointer (cast dereference)
            if (un->op == "*" && un->is_prefix && un->operand) {
                if (un->operand->type == NT_VAR_REF) {
                    auto* vr = static_cast<const VarRef*>(un->operand.get());
                    if (!vr->name.empty()) ptr_vars.insert(vr->name);
                }
                // v9.9: Handle *(type*)var — dereference through a cast
                else if (un->operand->type == NT_CAST) {
                    auto* cast = static_cast<const Cast*>(un->operand.get());
                    if (cast->expr && cast->expr->type == NT_VAR_REF) {
                        auto* vr = static_cast<const VarRef*>(cast->expr.get());
                        if (!vr->name.empty()) ptr_vars.insert(vr->name);
                    }
                }
            }
            collectPointerVars(un->operand.get(), ptr_vars);
            break;
        }
        case NT_MEMBER: {
            auto* mem = static_cast<const MemberAccess*>(node);
            // var->field → var is a pointer
            if (mem->is_pointer &&
                mem->base && mem->base->type == NT_VAR_REF) {
                auto* vr = static_cast<const VarRef*>(mem->base.get());
                if (!vr->name.empty()) ptr_vars.insert(vr->name);
            }
            // (*var).field → var is a pointer (via checkBitFieldMember)
            if (!mem->is_pointer && mem->base &&
                mem->base->type == NT_UNARY_OP) {
                auto* un = static_cast<const UnaryOp*>(mem->base.get());
                if (un->op == "*" && un->is_prefix &&
                    un->operand && un->operand->type == NT_VAR_REF) {
                    auto* vr = static_cast<const VarRef*>(un->operand.get());
                    if (!vr->name.empty()) ptr_vars.insert(vr->name);
                }
            }
            collectPointerVars(mem->base.get(), ptr_vars);
            break;
        }
        case NT_INDEX: {
            auto* idx = static_cast<const Index*>(node);
            // var[index] → var is a pointer
            if (idx->array && idx->array->type == NT_VAR_REF) {
                auto* vr = static_cast<const VarRef*>(idx->array.get());
                if (!vr->name.empty()) ptr_vars.insert(vr->name);
            }
            collectPointerVars(idx->array.get(), ptr_vars);
            collectPointerVars(idx->index.get(), ptr_vars);
            break;
        }
        case NT_BINARY_OP: {
            auto* bin = static_cast<const BinaryOp*>(node);
            // v9.8: 检测指针算术模式 VarRef + Const。
            // 当 UnaryOp("*", Cast(ptr, BinaryOp("+", VarRef, Const))) 时，
            // VarRef 是基址指针，但 UnaryOp 分支只看直接 operand 是 VarRef 的情况，
            // 无法穿透 Cast → BinaryOp 链。因此在 BinaryOp 层检测加法模式。
            if (bin->op == "+") {
                // VarRef + Const → VarRef is a pointer base
                if (bin->left && bin->left->type == NT_VAR_REF &&
                    bin->right && bin->right->type == NT_CONST) {
                    auto* vr = static_cast<const VarRef*>(bin->left.get());
                    if (!vr->name.empty() && vr->name != "0") ptr_vars.insert(vr->name);
                }
                if (bin->right && bin->right->type == NT_VAR_REF &&
                    bin->left && bin->left->type == NT_CONST) {
                    auto* vr = static_cast<const VarRef*>(bin->right.get());
                    if (!vr->name.empty() && vr->name != "0") ptr_vars.insert(vr->name);
                }
            }
            collectPointerVars(bin->left.get(), ptr_vars);
            collectPointerVars(bin->right.get(), ptr_vars);
            break;
        }
        case NT_ASSIGN: {
            auto* as = static_cast<const Assign*>(node);
            collectPointerVars(as->target.get(), ptr_vars);
            collectPointerVars(as->value.get(), ptr_vars);
            break;
        }
        case NT_CALL: {
            auto* call = static_cast<const Call*>(node);
            // v4.5: For indirect calls like (*tmp3)(...), mark tmp3 as pointer
            if (!call->callee_name.empty() && call->callee_name.size() > 2 &&
                call->callee_name[0] == '(' && call->callee_name[1] == '*') {
                // Extract var name from "(*varName)"
                size_t end = call->callee_name.find(')', 2);
                if (end != std::string::npos) {
                    std::string varName = call->callee_name.substr(2, end - 2);
                    if (!varName.empty()) ptr_vars.insert(varName);
                }
            }
            for (auto& arg : call->args)
                collectPointerVars(arg.get(), ptr_vars);
            break;
        }
        case NT_CAST: {
            auto* cast = static_cast<const Cast*>(node);
            collectPointerVars(cast->expr.get(), ptr_vars);
            break;
        }
        case NT_TERNARY: {
            auto* tern = static_cast<const Ternary*>(node);
            collectPointerVars(tern->condition.get(), ptr_vars);
            collectPointerVars(tern->true_expr.get(), ptr_vars);
            collectPointerVars(tern->false_expr.get(), ptr_vars);
            break;
        }
        case NT_BLOCK: {
            auto* block = static_cast<const Block*>(node);
            for (auto& s : block->statements)
                collectPointerVars(s.get(), ptr_vars);
            break;
        }
        case NT_IF: {
            auto* ifn = static_cast<const If*>(node);
            collectPointerVars(ifn->condition.get(), ptr_vars);
            collectPointerVars(ifn->then_branch.get(), ptr_vars);
            collectPointerVars(ifn->else_branch.get(), ptr_vars);
            break;
        }
        case NT_WHILE: {
            auto* wh = static_cast<const While*>(node);
            collectPointerVars(wh->condition.get(), ptr_vars);
            collectPointerVars(wh->body.get(), ptr_vars);
            break;
        }
        case NT_DO_WHILE: {
            auto* dw = static_cast<const DoWhile*>(node);
            collectPointerVars(dw->body.get(), ptr_vars);
            collectPointerVars(dw->condition.get(), ptr_vars);
            break;
        }
        case NT_FOR: {
            auto* fr = static_cast<const For*>(node);
            collectPointerVars(fr->init.get(), ptr_vars);
            collectPointerVars(fr->condition.get(), ptr_vars);
            collectPointerVars(fr->increment.get(), ptr_vars);
            collectPointerVars(fr->body.get(), ptr_vars);
            break;
        }
        case NT_RETURN: {
            auto* ret = static_cast<const Return*>(node);
            collectPointerVars(ret->value.get(), ptr_vars);
            break;
        }
        case NT_EXPR_STMT: {
            auto* es = static_cast<const ExprStmt*>(node);
            collectPointerVars(es->expr.get(), ptr_vars);
            break;
        }
        case NT_DECL: {
            auto* decl = static_cast<const VarDecl*>(node);
            collectPointerVars(decl->init_expr.get(), ptr_vars);
            break;
        }
        case NT_SWITCH: {
            auto* sw = static_cast<const Switch*>(node);
            collectPointerVars(sw->expr.get(), ptr_vars);
            for (auto& [val, body] : sw->cases)
                collectPointerVars(body.get(), ptr_vars);
            collectPointerVars(sw->default_body.get(), ptr_vars);
            break;
        }
        default:
            break;
    }
}

// ════════════════════════════════════════════════════════════════════
// v9.19: Collect variable names declared in for-loop init statements.
// For-loops like "for (int i = 0; ...)" declare variables that are
// scoped to the loop. We need to add them to the local_vars set so
// they are properly declared before the function body, avoiding
// undeclared identifier errors if they're referenced elsewhere.
// ════════════════════════════════════════════════════════════════════
void CPrinter::collectForInitDeclVars(const Node* node, std::set<std::string>& vars) const {
    if (!node) return;

    switch (node->type) {
        case NT_FOR: {
            const auto* fr = static_cast<const For*>(node);
            if (fr->init && fr->init->type == NT_DECL) {
                const auto* decl = static_cast<const VarDecl*>(fr->init.get());
                if (!decl->var_name.empty()) {
                    vars.insert(decl->var_name);
                }
            }
            collectForInitDeclVars(fr->body.get(), vars);
            break;
        }
        case NT_BLOCK: {
            const auto* block = static_cast<const Block*>(node);
            for (auto& s : block->statements)
                collectForInitDeclVars(s.get(), vars);
            break;
        }
        case NT_IF: {
            const auto* ifn = static_cast<const If*>(node);
            collectForInitDeclVars(ifn->then_branch.get(), vars);
            collectForInitDeclVars(ifn->else_branch.get(), vars);
            break;
        }
        case NT_WHILE: {
            const auto* wh = static_cast<const While*>(node);
            collectForInitDeclVars(wh->body.get(), vars);
            break;
        }
        case NT_DO_WHILE: {
            const auto* dw = static_cast<const DoWhile*>(node);
            collectForInitDeclVars(dw->body.get(), vars);
            break;
        }
        case NT_SWITCH: {
            const auto* sw = static_cast<const Switch*>(node);
            for (auto& c : sw->cases)
                collectForInitDeclVars(c.second.get(), vars);
            collectForInitDeclVars(sw->default_body.get(), vars);
            break;
        }
        default:
            break;
    }
}

// ════════════════════════════════════════════════════════════════════
// Phase 3: Collect inferred types from VarRef nodes in CTree
// Traverses the CTree and records the first non-UNKNOWN result_type
// for each variable name. Used by emitLocalVarDecls to emit proper
// type declarations instead of defaulting to uint64_t.
// ════════════════════════════════════════════════════════════════════
void CPrinter::collectVarTypes(const Node* node, std::map<std::string, CType>& types) const {
    if (!node) return;

    switch (node->type) {
        case NT_VAR_REF: {
            auto* vr = static_cast<const VarRef*>(node);
            if (!vr->name.empty() && vr->result_type.category != CType::TC_UNKNOWN) {
                auto it = types.find(vr->name);
                if (it == types.end()) {
                    types[vr->name] = vr->result_type;
                }
            }
            break;
        }
        case NT_BINARY_OP: {
            auto* bin = static_cast<const BinaryOp*>(node);
            collectVarTypes(bin->left.get(), types);
            collectVarTypes(bin->right.get(), types);
            break;
        }
        case NT_UNARY_OP: {
            auto* un = static_cast<const UnaryOp*>(node);
            collectVarTypes(un->operand.get(), types);
            break;
        }
        case NT_ASSIGN: {
            auto* as = static_cast<const Assign*>(node);
            collectVarTypes(as->target.get(), types);
            collectVarTypes(as->value.get(), types);
            break;
        }
        case NT_CALL: {
            auto* call = static_cast<const Call*>(node);
            for (auto& arg : call->args)
                collectVarTypes(arg.get(), types);
            break;
        }
        case NT_CAST: {
            auto* cast = static_cast<const Cast*>(node);
            collectVarTypes(cast->expr.get(), types);
            break;
        }
        case NT_MEMBER: {
            auto* mem = static_cast<const MemberAccess*>(node);
            collectVarTypes(mem->base.get(), types);
            break;
        }
        case NT_INDEX: {
            auto* idx = static_cast<const Index*>(node);
            collectVarTypes(idx->array.get(), types);
            collectVarTypes(idx->index.get(), types);
            break;
        }
        case NT_TERNARY: {
            auto* tern = static_cast<const Ternary*>(node);
            collectVarTypes(tern->condition.get(), types);
            collectVarTypes(tern->true_expr.get(), types);
            collectVarTypes(tern->false_expr.get(), types);
            break;
        }
        case NT_BLOCK: {
            auto* block = static_cast<const Block*>(node);
            for (auto& s : block->statements)
                collectVarTypes(s.get(), types);
            break;
        }
        case NT_EXPR_STMT: {
            auto* es = static_cast<const ExprStmt*>(node);
            collectVarTypes(es->expr.get(), types);
            break;
        }
        case NT_DECL: {
            auto* decl = static_cast<const VarDecl*>(node);
            collectVarTypes(decl->init_expr.get(), types);
            break;
        }
        case NT_RETURN: {
            auto* ret = static_cast<const Return*>(node);
            collectVarTypes(ret->value.get(), types);
            break;
        }
        case NT_IF: {
            auto* ifn = static_cast<const If*>(node);
            collectVarTypes(ifn->condition.get(), types);
            collectVarTypes(ifn->then_branch.get(), types);
            collectVarTypes(ifn->else_branch.get(), types);
            break;
        }
        case NT_WHILE: {
            auto* wh = static_cast<const While*>(node);
            collectVarTypes(wh->condition.get(), types);
            collectVarTypes(wh->body.get(), types);
            break;
        }
        case NT_DO_WHILE: {
            auto* dw = static_cast<const DoWhile*>(node);
            collectVarTypes(dw->condition.get(), types);
            collectVarTypes(dw->body.get(), types);
            break;
        }
        case NT_FOR: {
            auto* fr = static_cast<const For*>(node);
            collectVarTypes(fr->init.get(), types);
            collectVarTypes(fr->condition.get(), types);
            collectVarTypes(fr->increment.get(), types);
            collectVarTypes(fr->body.get(), types);
            break;
        }
        case NT_SWITCH: {
            auto* sw = static_cast<const Switch*>(node);
            collectVarTypes(sw->expr.get(), types);
            for (auto& c : sw->cases)
                collectVarTypes(c.second.get(), types);
            break;
        }
        default:
            break;
    }
}

// ════════════════════════════════════════════════════════════════════
// v8.8: collectBoolVars — detect variables that hold comparison/logical
// results (对标 Ghidra boolean analysis / PcodeCopy).
// A variable assigned the result of ==, !=, <, <=, >, >=, &&, ||, ! is a
// bool candidate.
// ════════════════════════════════════════════════════════════════════
static bool isComparisonOrLogicalOp(const std::string& op) {
    return op == "==" || op == "!=" || op == "<" || op == "<=" ||
           op == ">" || op == ">=" || op == "&&" || op == "||";
}

void CPrinter::collectBoolVars(const Node* node, std::set<std::string>& bool_vars) const {
    if (!node) return;
    switch (node->type) {
        case NT_ASSIGN: {
            auto* as = static_cast<const Assign*>(node);
            // target = <comparison/logical expr>
            if (as->target && as->target->type == NT_VAR_REF && as->value) {
                if (as->value->type == NT_BINARY_OP) {
                    auto* bin = static_cast<const BinaryOp*>(as->value.get());
                    if (isComparisonOrLogicalOp(bin->op)) {
                        auto* vr = static_cast<const VarRef*>(as->target.get());
                        if (!vr->name.empty() && vr->name != "0")
                            bool_vars.insert(vr->name);
                    }
                } else if (as->value->type == NT_UNARY_OP) {
                    auto* un = static_cast<const UnaryOp*>(as->value.get());
                    if (un->op == "!") {
                        auto* vr = static_cast<const VarRef*>(as->target.get());
                        if (!vr->name.empty() && vr->name != "0")
                            bool_vars.insert(vr->name);
                    }
                }
            }
            collectBoolVars(as->target.get(), bool_vars);
            collectBoolVars(as->value.get(), bool_vars);
            break;
        }
        case NT_VAR_REF: {
            // If a VarRef carries a BOOL result_type, mark it.
            auto* vr = static_cast<const VarRef*>(node);
            if (!vr->name.empty() && vr->result_type.category == CType::TC_BOOL)
                bool_vars.insert(vr->name);
            break;
        }
        case NT_BINARY_OP: {
            auto* bin = static_cast<const BinaryOp*>(node);
            collectBoolVars(bin->left.get(), bool_vars);
            collectBoolVars(bin->right.get(), bool_vars);
            break;
        }
        case NT_UNARY_OP: {
            auto* un = static_cast<const UnaryOp*>(node);
            collectBoolVars(un->operand.get(), bool_vars);
            break;
        }
        case NT_CAST: {
            auto* cast = static_cast<const Cast*>(node);
            collectBoolVars(cast->expr.get(), bool_vars);
            break;
        }
        case NT_TERNARY: {
            auto* tern = static_cast<const Ternary*>(node);
            collectBoolVars(tern->condition.get(), bool_vars);
            collectBoolVars(tern->true_expr.get(), bool_vars);
            collectBoolVars(tern->false_expr.get(), bool_vars);
            break;
        }
        case NT_CALL: {
            auto* call = static_cast<const Call*>(node);
            for (auto& arg : call->args)
                collectBoolVars(arg.get(), bool_vars);
            break;
        }
        case NT_MEMBER: {
            auto* mem = static_cast<const MemberAccess*>(node);
            collectBoolVars(mem->base.get(), bool_vars);
            break;
        }
        case NT_INDEX: {
            auto* idx = static_cast<const Index*>(node);
            collectBoolVars(idx->array.get(), bool_vars);
            collectBoolVars(idx->index.get(), bool_vars);
            break;
        }
        case NT_EXPR_STMT: {
            auto* es = static_cast<const ExprStmt*>(node);
            collectBoolVars(es->expr.get(), bool_vars);
            break;
        }
        case NT_DECL: {
            auto* decl = static_cast<const VarDecl*>(node);
            collectBoolVars(decl->init_expr.get(), bool_vars);
            break;
        }
        case NT_RETURN: {
            auto* ret = static_cast<const Return*>(node);
            collectBoolVars(ret->value.get(), bool_vars);
            break;
        }
        case NT_BLOCK: {
            auto* block = static_cast<const Block*>(node);
            for (auto& s : block->statements)
                collectBoolVars(s.get(), bool_vars);
            break;
        }
        case NT_IF: {
            auto* ifn = static_cast<const If*>(node);
            collectBoolVars(ifn->condition.get(), bool_vars);
            collectBoolVars(ifn->then_branch.get(), bool_vars);
            collectBoolVars(ifn->else_branch.get(), bool_vars);
            break;
        }
        case NT_WHILE: {
            auto* wh = static_cast<const While*>(node);
            collectBoolVars(wh->condition.get(), bool_vars);
            collectBoolVars(wh->body.get(), bool_vars);
            break;
        }
        case NT_DO_WHILE: {
            auto* dw = static_cast<const DoWhile*>(node);
            collectBoolVars(dw->body.get(), bool_vars);
            collectBoolVars(dw->condition.get(), bool_vars);
            break;
        }
        case NT_FOR: {
            auto* fr = static_cast<const For*>(node);
            collectBoolVars(fr->init.get(), bool_vars);
            collectBoolVars(fr->condition.get(), bool_vars);
            collectBoolVars(fr->increment.get(), bool_vars);
            collectBoolVars(fr->body.get(), bool_vars);
            break;
        }
        case NT_SWITCH: {
            auto* sw = static_cast<const Switch*>(node);
            collectBoolVars(sw->expr.get(), bool_vars);
            for (auto& c : sw->cases)
                collectBoolVars(c.second.get(), bool_vars);
            collectBoolVars(sw->default_body.get(), bool_vars);
            break;
        }
        default:
            break;
    }
}

// ════════════════════════════════════════════════════════════════════
// v8.8: collectIntVars — detect variables that participate in arithmetic
// ops (对标 Ghidra integer analysis). A variable used as an operand of
// +, -, *, /, %, <<, >>, &, |, ^ is an integer candidate.
// ════════════════════════════════════════════════════════════════════
static bool isArithmeticOp(const std::string& op) {
    return op == "+" || op == "-" || op == "*" || op == "/" || op == "%" ||
           op == "<<" || op == ">>" || op == "&" || op == "|" || op == "^" ||
           op == "+=" || op == "-=" || op == "*=" || op == "/=" || op == "%=" ||
           op == "&=" || op == "|=" || op == "^=" || op == "<<=" || op == ">>=";
}

void CPrinter::collectIntVars(const Node* node, std::set<std::string>& int_vars) const {
    if (!node) return;
    switch (node->type) {
        case NT_BINARY_OP: {
            auto* bin = static_cast<const BinaryOp*>(node);
            if (isArithmeticOp(bin->op)) {
                if (bin->left && bin->left->type == NT_VAR_REF) {
                    auto* vr = static_cast<const VarRef*>(bin->left.get());
                    if (!vr->name.empty() && vr->name != "0")
                        int_vars.insert(vr->name);
                }
                if (bin->right && bin->right->type == NT_VAR_REF) {
                    auto* vr = static_cast<const VarRef*>(bin->right.get());
                    if (!vr->name.empty() && vr->name != "0")
                        int_vars.insert(vr->name);
                }
            }
            collectIntVars(bin->left.get(), int_vars);
            collectIntVars(bin->right.get(), int_vars);
            break;
        }
        case NT_VAR_REF: {
            auto* vr = static_cast<const VarRef*>(node);
            if (!vr->name.empty() && vr->result_type.isInteger())
                int_vars.insert(vr->name);
            break;
        }
        case NT_ASSIGN: {
            auto* as = static_cast<const Assign*>(node);
            collectIntVars(as->target.get(), int_vars);
            collectIntVars(as->value.get(), int_vars);
            break;
        }
        case NT_UNARY_OP: {
            auto* un = static_cast<const UnaryOp*>(node);
            collectIntVars(un->operand.get(), int_vars);
            break;
        }
        case NT_CAST: {
            auto* cast = static_cast<const Cast*>(node);
            collectIntVars(cast->expr.get(), int_vars);
            break;
        }
        case NT_TERNARY: {
            auto* tern = static_cast<const Ternary*>(node);
            collectIntVars(tern->condition.get(), int_vars);
            collectIntVars(tern->true_expr.get(), int_vars);
            collectIntVars(tern->false_expr.get(), int_vars);
            break;
        }
        case NT_CALL: {
            auto* call = static_cast<const Call*>(node);
            for (auto& arg : call->args)
                collectIntVars(arg.get(), int_vars);
            break;
        }
        case NT_MEMBER: {
            auto* mem = static_cast<const MemberAccess*>(node);
            collectIntVars(mem->base.get(), int_vars);
            break;
        }
        case NT_INDEX: {
            auto* idx = static_cast<const Index*>(node);
            collectIntVars(idx->array.get(), int_vars);
            collectIntVars(idx->index.get(), int_vars);
            break;
        }
        case NT_EXPR_STMT: {
            auto* es = static_cast<const ExprStmt*>(node);
            collectIntVars(es->expr.get(), int_vars);
            break;
        }
        case NT_DECL: {
            auto* decl = static_cast<const VarDecl*>(node);
            collectIntVars(decl->init_expr.get(), int_vars);
            break;
        }
        case NT_RETURN: {
            auto* ret = static_cast<const Return*>(node);
            collectIntVars(ret->value.get(), int_vars);
            break;
        }
        case NT_BLOCK: {
            auto* block = static_cast<const Block*>(node);
            for (auto& s : block->statements)
                collectIntVars(s.get(), int_vars);
            break;
        }
        case NT_IF: {
            auto* ifn = static_cast<const If*>(node);
            collectIntVars(ifn->condition.get(), int_vars);
            collectIntVars(ifn->then_branch.get(), int_vars);
            collectIntVars(ifn->else_branch.get(), int_vars);
            break;
        }
        case NT_WHILE: {
            auto* wh = static_cast<const While*>(node);
            collectIntVars(wh->condition.get(), int_vars);
            collectIntVars(wh->body.get(), int_vars);
            break;
        }
        case NT_DO_WHILE: {
            auto* dw = static_cast<const DoWhile*>(node);
            collectIntVars(dw->body.get(), int_vars);
            collectIntVars(dw->condition.get(), int_vars);
            break;
        }
        case NT_FOR: {
            auto* fr = static_cast<const For*>(node);
            collectIntVars(fr->init.get(), int_vars);
            collectIntVars(fr->condition.get(), int_vars);
            collectIntVars(fr->increment.get(), int_vars);
            collectIntVars(fr->body.get(), int_vars);
            break;
        }
        case NT_SWITCH: {
            auto* sw = static_cast<const Switch*>(node);
            collectIntVars(sw->expr.get(), int_vars);
            for (auto& c : sw->cases)
                collectIntVars(c.second.get(), int_vars);
            collectIntVars(sw->default_body.get(), int_vars);
            break;
        }
        default:
            break;
    }
}

// ════════════════════════════════════════════════════════════════════
// v8.8: collectFloatVars — detect variables used in floating-point
// contexts (对标 Ghidra float analysis). A variable assigned an FP
// constant, or whose VarRef carries a FLOAT/DOUBLE result_type, is an
// FP candidate.
// ════════════════════════════════════════════════════════════════════
void CPrinter::collectFloatVars(const Node* node, std::set<std::string>& fp_vars) const {
    if (!node) return;
    switch (node->type) {
        case NT_VAR_REF: {
            auto* vr = static_cast<const VarRef*>(node);
            if (!vr->name.empty() &&
                (vr->result_type.category == CType::TC_FLOAT ||
                 vr->result_type.category == CType::TC_DOUBLE)) {
                fp_vars.insert(vr->name);
            }
            break;
        }
        case NT_ASSIGN: {
            auto* as = static_cast<const Assign*>(node);
            // target = <FP const>
            if (as->target && as->target->type == NT_VAR_REF && as->value) {
                if (as->value->type == NT_CONST) {
                    auto* c = static_cast<const Const*>(as->value.get());
                    if (c->is_fp) {
                        auto* vr = static_cast<const VarRef*>(as->target.get());
                        if (!vr->name.empty() && vr->name != "0")
                            fp_vars.insert(vr->name);
                    }
                }
                // Cast to float/double
                if (as->value->type == NT_CAST) {
                    auto* cast = static_cast<const Cast*>(as->value.get());
                    if (cast->target_type.category == CType::TC_FLOAT ||
                        cast->target_type.category == CType::TC_DOUBLE) {
                        auto* vr = static_cast<const VarRef*>(as->target.get());
                        if (!vr->name.empty() && vr->name != "0")
                            fp_vars.insert(vr->name);
                    }
                }
            }
            collectFloatVars(as->target.get(), fp_vars);
            collectFloatVars(as->value.get(), fp_vars);
            break;
        }
        case NT_BINARY_OP: {
            auto* bin = static_cast<const BinaryOp*>(node);
            collectFloatVars(bin->left.get(), fp_vars);
            collectFloatVars(bin->right.get(), fp_vars);
            break;
        }
        case NT_UNARY_OP: {
            auto* un = static_cast<const UnaryOp*>(node);
            collectFloatVars(un->operand.get(), fp_vars);
            break;
        }
        case NT_CAST: {
            auto* cast = static_cast<const Cast*>(node);
            collectFloatVars(cast->expr.get(), fp_vars);
            break;
        }
        case NT_TERNARY: {
            auto* tern = static_cast<const Ternary*>(node);
            collectFloatVars(tern->condition.get(), fp_vars);
            collectFloatVars(tern->true_expr.get(), fp_vars);
            collectFloatVars(tern->false_expr.get(), fp_vars);
            break;
        }
        case NT_CALL: {
            auto* call = static_cast<const Call*>(node);
            for (auto& arg : call->args)
                collectFloatVars(arg.get(), fp_vars);
            break;
        }
        case NT_MEMBER: {
            auto* mem = static_cast<const MemberAccess*>(node);
            collectFloatVars(mem->base.get(), fp_vars);
            break;
        }
        case NT_INDEX: {
            auto* idx = static_cast<const Index*>(node);
            collectFloatVars(idx->array.get(), fp_vars);
            collectFloatVars(idx->index.get(), fp_vars);
            break;
        }
        case NT_EXPR_STMT: {
            auto* es = static_cast<const ExprStmt*>(node);
            collectFloatVars(es->expr.get(), fp_vars);
            break;
        }
        case NT_DECL: {
            auto* decl = static_cast<const VarDecl*>(node);
            collectFloatVars(decl->init_expr.get(), fp_vars);
            break;
        }
        case NT_RETURN: {
            auto* ret = static_cast<const Return*>(node);
            collectFloatVars(ret->value.get(), fp_vars);
            break;
        }
        case NT_BLOCK: {
            auto* block = static_cast<const Block*>(node);
            for (auto& s : block->statements)
                collectFloatVars(s.get(), fp_vars);
            break;
        }
        case NT_IF: {
            auto* ifn = static_cast<const If*>(node);
            collectFloatVars(ifn->condition.get(), fp_vars);
            collectFloatVars(ifn->then_branch.get(), fp_vars);
            collectFloatVars(ifn->else_branch.get(), fp_vars);
            break;
        }
        case NT_WHILE: {
            auto* wh = static_cast<const While*>(node);
            collectFloatVars(wh->condition.get(), fp_vars);
            collectFloatVars(wh->body.get(), fp_vars);
            break;
        }
        case NT_DO_WHILE: {
            auto* dw = static_cast<const DoWhile*>(node);
            collectFloatVars(dw->body.get(), fp_vars);
            collectFloatVars(dw->condition.get(), fp_vars);
            break;
        }
        case NT_FOR: {
            auto* fr = static_cast<const For*>(node);
            collectFloatVars(fr->init.get(), fp_vars);
            collectFloatVars(fr->condition.get(), fp_vars);
            collectFloatVars(fr->increment.get(), fp_vars);
            collectFloatVars(fr->body.get(), fp_vars);
            break;
        }
        case NT_SWITCH: {
            auto* sw = static_cast<const Switch*>(node);
            collectFloatVars(sw->expr.get(), fp_vars);
            for (auto& c : sw->cases)
                collectFloatVars(c.second.get(), fp_vars);
            collectFloatVars(sw->default_body.get(), fp_vars);
            break;
        }
        default:
            break;
    }
}

// ════════════════════════════════════════════════════════════════════
// v8.8: ghidraVarPrefix — No longer used for naming (we use tmp_N).
// Kept for backward compatibility with any code that calls it.
// Returns "tmp_" for all types (Hex-Rays-style).
// ════════════════════════════════════════════════════════════════════
std::string CPrinter::ghidraVarPrefix(const CType& type) {
    (void)type;
    return "tmp_";
}

// ════════════════════════════════════════════════════════════════════
// collectCallArgVars — v9.25: collect variables used as call arguments
// These variables have values from PHI nodes, parameters, or copy chains
// and should not be marked as UNINIT.
// ════════════════════════════════════════════════════════════════════
static void collectCallArgVars(const Node* node, std::set<std::string>& vars) {
    if (!node) return;
    switch (node->type) {
        case NT_VAR_REF: {
            const auto* vr = static_cast<const VarRef*>(node);
            if (!vr->name.empty() && vr->name != "0")
                vars.insert(vr->name);
            break;
        }
        case NT_CALL: {
            const auto* call = static_cast<const Call*>(node);
            for (auto& arg : call->args)
                collectCallArgVars(arg.get(), vars);
            break;
        }
        case NT_BINARY_OP: {
            const auto* bin = static_cast<const BinaryOp*>(node);
            collectCallArgVars(bin->left.get(), vars);
            collectCallArgVars(bin->right.get(), vars);
            break;
        }
        case NT_UNARY_OP: {
            const auto* un = static_cast<const UnaryOp*>(node);
            collectCallArgVars(un->operand.get(), vars);
            break;
        }
        case NT_CAST: {
            const auto* cast = static_cast<const Cast*>(node);
            collectCallArgVars(cast->expr.get(), vars);
            break;
        }
        case NT_MEMBER: {
            const auto* mem = static_cast<const MemberAccess*>(node);
            collectCallArgVars(mem->base.get(), vars);
            break;
        }
        case NT_INDEX: {
            const auto* idx = static_cast<const Index*>(node);
            collectCallArgVars(idx->array.get(), vars);
            collectCallArgVars(idx->index.get(), vars);
            break;
        }
        case NT_TERNARY: {
            const auto* tern = static_cast<const Ternary*>(node);
            collectCallArgVars(tern->condition.get(), vars);
            collectCallArgVars(tern->true_expr.get(), vars);
            collectCallArgVars(tern->false_expr.get(), vars);
            break;
        }
        case NT_ASSIGN: {
            const auto* as = static_cast<const Assign*>(node);
            collectCallArgVars(as->value.get(), vars);
            break;
        }
        case NT_EXPR_STMT: {
            const auto* es = static_cast<const ExprStmt*>(node);
            collectCallArgVars(es->expr.get(), vars);
            break;
        }
        case NT_BLOCK: {
            const auto* blk = static_cast<const Block*>(node);
            for (auto& s : blk->statements)
                collectCallArgVars(s.get(), vars);
            break;
        }
        case NT_IF: {
            const auto* ifn = static_cast<const If*>(node);
            // v9.31: Also traverse the condition expression — if conditions
            // may contain function calls (e.g., if (func(a))), and their
            // arguments should be collected to avoid false UNINIT.
            collectCallArgVars(ifn->condition.get(), vars);
            collectCallArgVars(ifn->then_branch.get(), vars);
            collectCallArgVars(ifn->else_branch.get(), vars);
            break;
        }
        case NT_WHILE: {
            const auto* wh = static_cast<const While*>(node);
            collectCallArgVars(wh->condition.get(), vars);
            collectCallArgVars(wh->body.get(), vars);
            break;
        }
        case NT_DO_WHILE: {
            const auto* dw = static_cast<const DoWhile*>(node);
            collectCallArgVars(dw->condition.get(), vars);
            collectCallArgVars(dw->body.get(), vars);
            break;
        }
        case NT_FOR: {
            const auto* fr = static_cast<const For*>(node);
            collectCallArgVars(fr->init.get(), vars);
            collectCallArgVars(fr->condition.get(), vars);
            collectCallArgVars(fr->increment.get(), vars);
            collectCallArgVars(fr->body.get(), vars);
            break;
        }
        case NT_RETURN: {
            const auto* ret = static_cast<const Return*>(node);
            collectCallArgVars(ret->value.get(), vars);
            break;
        }
        case NT_DECL: {
            const auto* decl = static_cast<const VarDecl*>(node);
            collectCallArgVars(decl->init_expr.get(), vars);
            break;
        }
        case NT_SWITCH: {
            const auto* sw = static_cast<const Switch*>(node);
            collectCallArgVars(sw->expr.get(), vars);
            for (auto& c : sw->cases) {
                collectCallArgVars(c.second.get(), vars);
            }
            collectCallArgVars(sw->default_body.get(), vars);
            break;
        }
        default:
            break;
    }
}

// ════════════════════════════════════════════════════════════════════
// collectAssignedVars — v9.7: collect variables on LHS of assignments
// ════════════════════════════════════════════════════════════════════
void CPrinter::collectAssignedVars(const Node* node, std::set<std::string>& vars) const {
    if (!node) return;

    switch (node->type) {
        case NT_ASSIGN: {
            const auto* as = static_cast<const Assign*>(node);
            // Collect LHS (target) variable names
            if (as->target && as->target->type == NT_VAR_REF) {
                const auto* vr = static_cast<const VarRef*>(as->target.get());
                if (!vr->name.empty() && vr->name != "0")
                    vars.insert(vr->name);
            }
            // v54.0: When the LHS is a Deref (e.g., *ptr = value), the
            // pointer variable is used as a store target — treat it as
            // "assigned" so it won't be marked /* UNINIT */.
            if (as->target && as->target->type == NT_DEREF) {
                const auto* d = static_cast<const Deref*>(as->target.get());
                if (d->operand && d->operand->type == NT_VAR_REF) {
                    const auto* vr = static_cast<const VarRef*>(d->operand.get());
                    if (!vr->name.empty() && vr->name != "0")
                        vars.insert(vr->name);
                }
            }
            // Also recurse into RHS (value) and deeper LHS (e.g., member access)
            collectAssignedVars(as->target.get(), vars);
            collectAssignedVars(as->value.get(), vars);
            break;
        }
        case NT_BLOCK: {
            const auto* block = static_cast<const Block*>(node);
            for (auto& s : block->statements)
                collectAssignedVars(s.get(), vars);
            break;
        }
        case NT_EXPR_STMT: {
            const auto* es = static_cast<const ExprStmt*>(node);
            collectAssignedVars(es->expr.get(), vars);
            break;
        }
        case NT_IF: {
            const auto* ifn = static_cast<const If*>(node);
            // v9.31: Also traverse the condition — if conditions may
            // contain assignments (e.g., if ((x = get_val()))).
            collectAssignedVars(ifn->condition.get(), vars);
            collectAssignedVars(ifn->then_branch.get(), vars);
            collectAssignedVars(ifn->else_branch.get(), vars);
            break;
        }
        case NT_WHILE: {
            const auto* wh = static_cast<const While*>(node);
            collectAssignedVars(wh->condition.get(), vars);
            collectAssignedVars(wh->body.get(), vars);
            break;
        }
        case NT_DO_WHILE: {
            const auto* dw = static_cast<const DoWhile*>(node);
            collectAssignedVars(dw->condition.get(), vars);
            collectAssignedVars(dw->body.get(), vars);
            break;
        }
        case NT_FOR: {
            const auto* fr = static_cast<const For*>(node);
            collectAssignedVars(fr->init.get(), vars);
            collectAssignedVars(fr->condition.get(), vars);
            collectAssignedVars(fr->increment.get(), vars);
            collectAssignedVars(fr->body.get(), vars);
            break;
        }
        case NT_SWITCH: {
            const auto* sw = static_cast<const Switch*>(node);
            for (auto& c : sw->cases)
                collectAssignedVars(c.second.get(), vars);
            collectAssignedVars(sw->default_body.get(), vars);
            break;
        }
        case NT_DECL: {
            // v9.27: NT_DECL (VarDecl) with init_expr IS an assignment.
            const auto* decl = static_cast<const VarDecl*>(node);
            if (!decl->var_name.empty()) {
                vars.insert(decl->var_name);
            }
            // v9.31: Also traverse init_expr — nested assignments like
            // "uint64_t x = (y = 5);" should collect y as assigned.
            collectAssignedVars(decl->init_expr.get(), vars);
            break;
        }
        case NT_RETURN:
        case NT_DEREF:
        case NT_BINARY_OP:
        case NT_UNARY_OP:
        case NT_CALL:
        case NT_CAST:
        case NT_MEMBER:
        case NT_INDEX:
        case NT_TERNARY:
        case NT_VAR_REF:
        case NT_CONST:
        case NT_STRING:
        case NT_NULL:
            // v9.31: These node types can contain nested assignments
            // (e.g., "y = (x = 5) + 1", "z = a ? (b = 1) : (c = 2)").
            // Recursively traverse all sub-expressions to collect them.
            // Previously this was a no-op break, causing nested assignments
            // to be missed and their variables falsely marked UNINIT.
            // v55.0: Added NT_DEREF — dereference nodes like *ptr can
            // contain nested assignments in their operand.
            {
                std::vector<const Node*> children;
                if (node->type == NT_DEREF) {
                    auto* d = static_cast<const Deref*>(node);
                    if (d->operand) children.push_back(d->operand.get());
                } else if (node->type == NT_RETURN) {
                    auto* ret = static_cast<const Return*>(node);
                    if (ret->value) children.push_back(ret->value.get());
                } else if (node->type == NT_UNARY_OP) {
                    auto* un = static_cast<const UnaryOp*>(node);
                    if (un->operand) children.push_back(un->operand.get());
                } else if (node->type == NT_CALL) {
                    auto* call = static_cast<const Call*>(node);
                    for (auto& arg : call->args)
                        if (arg) children.push_back(arg.get());
                } else if (node->type == NT_CAST) {
                    auto* cast = static_cast<const Cast*>(node);
                    if (cast->expr) children.push_back(cast->expr.get());
                } else if (node->type == NT_MEMBER) {
                    auto* mem = static_cast<const MemberAccess*>(node);
                    if (mem->base) children.push_back(mem->base.get());
                } else if (node->type == NT_INDEX) {
                    auto* idx = static_cast<const Index*>(node);
                    if (idx->array) children.push_back(idx->array.get());
                    if (idx->index) children.push_back(idx->index.get());
                } else if (node->type == NT_TERNARY) {
                    auto* tern = static_cast<const Ternary*>(node);
                    if (tern->condition) children.push_back(tern->condition.get());
                    if (tern->true_expr) children.push_back(tern->true_expr.get());
                    if (tern->false_expr) children.push_back(tern->false_expr.get());
                }
                for (auto* child : children)
                    collectAssignedVars(child, vars);
            }
            break;
        default:
            break;
    }
}

// ════════════════════════════════════════════════════════════════════
// v11.0: countVarUsage — count READ usages of each variable in the AST.
// Used by emitLocalVarDecls to skip single-use temporaries (Ghidra-style
// copy-propagation safety net). A "use" is a VarRef that is read; the
// plain-VarRef target of a simple "=" assignment is a definition (write)
// and is NOT counted, matching the "used 0/1 times" semantics. Compound
// assignments (+=, etc.) and non-VarRef targets (a.f, *p) read the target
// and ARE counted. VarDecl names are definitions and are not counted.
// Recurses every AST node type so counts stay accurate across all constructs.
// ════════════════════════════════════════════════════════════════════
void CPrinter::countVarUsage(const Node* node,
                             std::map<std::string, int>& counts) const {
    if (!node) return;

    switch (node->type) {
        case NT_VAR_REF: {
            const auto* vr = static_cast<const VarRef*>(node);
            if (!vr->name.empty() && vr->name != "0")
                counts[vr->name]++;
            break;
        }
        case NT_BINARY_OP: {
            const auto* bin = static_cast<const BinaryOp*>(node);
            countVarUsage(bin->left.get(), counts);
            countVarUsage(bin->right.get(), counts);
            break;
        }
        case NT_UNARY_OP: {
            const auto* un = static_cast<const UnaryOp*>(node);
            countVarUsage(un->operand.get(), counts);
            break;
        }
        case NT_ASSIGN: {
            const auto* as = static_cast<const Assign*>(node);
            // A plain-VarRef target of a simple "=" is a pure definition
            // (write) — not a use, so skip it. Compound assignments and
            // non-VarRef targets (a.f = ..., *p = ...) read the target.
            bool plainVarTarget = (as->target && as->target->type == NT_VAR_REF);
            bool simpleAssign = (as->op == "=");
            if (!(plainVarTarget && simpleAssign))
                countVarUsage(as->target.get(), counts);
            // The RHS (value) is always a use.
            countVarUsage(as->value.get(), counts);
            break;
        }
        case NT_CALL: {
            const auto* call = static_cast<const Call*>(node);
            for (auto& arg : call->args)
                countVarUsage(arg.get(), counts);
            // Indirect calls like (*tmp3)(...) encode the callee in
            // callee_name — count tmp3 as a use (mirrors collectVarRefs).
            if (!call->callee_name.empty() && call->callee_name.size() > 2 &&
                call->callee_name[0] == '(' && call->callee_name[1] == '*') {
                size_t end = call->callee_name.find(')', 2);
                if (end != std::string::npos) {
                    std::string varName = call->callee_name.substr(2, end - 2);
                    if (!varName.empty() && varName != "0")
                        counts[varName]++;
                }
            }
            break;
        }
        case NT_CAST: {
            const auto* cast = static_cast<const Cast*>(node);
            countVarUsage(cast->expr.get(), counts);
            break;
        }
        case NT_MEMBER: {
            const auto* mem = static_cast<const MemberAccess*>(node);
            countVarUsage(mem->base.get(), counts);
            break;
        }
        case NT_INDEX: {
            const auto* idx = static_cast<const Index*>(node);
            countVarUsage(idx->array.get(), counts);
            countVarUsage(idx->index.get(), counts);
            break;
        }
        case NT_TERNARY: {
            const auto* tern = static_cast<const Ternary*>(node);
            countVarUsage(tern->condition.get(), counts);
            countVarUsage(tern->true_expr.get(), counts);
            countVarUsage(tern->false_expr.get(), counts);
            break;
        }
        case NT_BLOCK: {
            const auto* block = static_cast<const Block*>(node);
            for (auto& s : block->statements)
                countVarUsage(s.get(), counts);
            break;
        }
        case NT_IF: {
            const auto* ifn = static_cast<const If*>(node);
            countVarUsage(ifn->condition.get(), counts);
            countVarUsage(ifn->then_branch.get(), counts);
            countVarUsage(ifn->else_branch.get(), counts);
            break;
        }
        case NT_WHILE: {
            const auto* wh = static_cast<const While*>(node);
            countVarUsage(wh->condition.get(), counts);
            countVarUsage(wh->body.get(), counts);
            break;
        }
        case NT_DO_WHILE: {
            const auto* dw = static_cast<const DoWhile*>(node);
            countVarUsage(dw->body.get(), counts);
            countVarUsage(dw->condition.get(), counts);
            break;
        }
        case NT_FOR: {
            const auto* fr = static_cast<const For*>(node);
            countVarUsage(fr->init.get(), counts);
            countVarUsage(fr->condition.get(), counts);
            countVarUsage(fr->increment.get(), counts);
            countVarUsage(fr->body.get(), counts);
            break;
        }
        case NT_RETURN: {
            const auto* ret = static_cast<const Return*>(node);
            countVarUsage(ret->value.get(), counts);
            break;
        }
        case NT_EXPR_STMT: {
            const auto* es = static_cast<const ExprStmt*>(node);
            countVarUsage(es->expr.get(), counts);
            break;
        }
        case NT_DECL: {
            // var_name is a definition, not a use — only count init_expr.
            const auto* decl = static_cast<const VarDecl*>(node);
            countVarUsage(decl->init_expr.get(), counts);
            break;
        }
        case NT_SWITCH: {
            const auto* sw = static_cast<const Switch*>(node);
            countVarUsage(sw->expr.get(), counts);
            for (auto& [val, body] : sw->cases)
                countVarUsage(body.get(), counts);
            countVarUsage(sw->default_body.get(), counts);
            break;
        }
        // v10.0 expression nodes (recurse for completeness).
        case NT_DEREF: {
            const auto* d = static_cast<const Deref*>(node);
            countVarUsage(d->operand.get(), counts);
            break;
        }
        case NT_ADDRESSOF: {
            const auto* a = static_cast<const AddressOf*>(node);
            countVarUsage(a->operand.get(), counts);
            break;
        }
        case NT_COMMA: {
            const auto* c = static_cast<const CommaExpr*>(node);
            for (auto& item : c->items)
                countVarUsage(item.get(), counts);
            break;
        }
        case NT_INIT_LIST: {
            const auto* il = static_cast<const InitList*>(node);
            for (auto& item : il->items)
                countVarUsage(item.get(), counts);
            break;
        }
        case NT_BITFIELD: {
            const auto* bf = static_cast<const BitFieldAccess*>(node);
            countVarUsage(bf->base.get(), counts);
            break;
        }
        case NT_NEW: {
            const auto* n = static_cast<const NewExpr*>(node);
            countVarUsage(n->size_expr.get(), counts);
            for (auto& arg : n->ctor_args)
                countVarUsage(arg.get(), counts);
            break;
        }
        case NT_DELETE: {
            const auto* d = static_cast<const DeleteExpr*>(node);
            countVarUsage(d->operand.get(), counts);
            break;
        }
        case NT_THROW_EXPR: {
            const auto* t = static_cast<const ThrowExpr*>(node);
            countVarUsage(t->operand.get(), counts);
            break;
        }
        case NT_SIZEOF: {
            const auto* s = static_cast<const SizeOf*>(node);
            if (!s->is_type)
                countVarUsage(s->expr_arg.get(), counts);
            break;
        }
        case NT_ASM: {
            const auto* asmNode = static_cast<const AsmStmt*>(node);
            for (auto& in : asmNode->inputs)
                countVarUsage(in.get(), counts);
            for (auto& out : asmNode->outputs)
                countVarUsage(out.get(), counts);
            break;
        }
        case NT_LABEL: {
            const auto* lbl = static_cast<const Label*>(node);
            countVarUsage(lbl->stmt.get(), counts);
            break;
        }
        // Atom / no-subexpression nodes: nothing to recurse.
        case NT_CONST:
        case NT_STRING:
        case NT_NULL:
        case NT_ENUM_CONST:
        case NT_GLOBAL_VAR:
        case NT_BREAK:
        case NT_CONTINUE:
        case NT_GOTO:
        case NT_EMPTY_STMT:
        default:
            break;
    }
}

// ════════════════════════════════════════════════════════════════════
// emitLocalVarDecls
// ════════════════════════════════════════════════════════════════════
void CPrinter::emitLocalVarDecls(const Function& func,
    const std::vector<std::pair<std::string, CType>>* filtered_local_vars) {
    // Collect all variable names used in the body
    std::set<std::string> used_vars;
    collectVarRefs(func.body.get(), used_vars);

    // v11.5: Use pre-filtered local_vars if provided (from printFunction pass)
    const auto& local_vars = filtered_local_vars ? *filtered_local_vars : func.local_vars;

    // Determine which variables are already declared (parameters + explicit locals)
    std::set<std::string> declared;
    for (auto& [name, type] : func.params) {
        declared.insert(name);
    }

    for (auto& [name, type] : local_vars) {
        declared.insert(name);
    }

    // v4.5: Collect pointer-like vars and inferred types BEFORE declaring
    // local_vars, so we can override the stored type when a variable is
    // used in pointer context (e.g., indirect call like (*tmp3)(...)).
    std::set<std::string> ptr_vars;
    collectPointerVars(func.body.get(), ptr_vars);
    std::map<std::string, CType> inferred_types;
    collectVarTypes(func.body.get(), inferred_types);

    // v8.8: Collect type-usage patterns for improved variable type inference
    // (对标 Ghidra type analysis: bool/int/float detection from usage).
    // Priority: ptr_vars > fp_vars > bool_vars > int_vars > default.
    std::set<std::string> bool_vars;
    collectBoolVars(func.body.get(), bool_vars);
    std::set<std::string> int_vars;
    collectIntVars(func.body.get(), int_vars);
    std::set<std::string> fp_vars;
    collectFloatVars(func.body.get(), fp_vars);

    // v9.19: Collect variables declared in for-loop init statements.
    // These need to be in the declared set to avoid being emitted as
    // separate "uint64_t varname;" declarations.
    std::set<std::string> for_init_vars;
    collectForInitDeclVars(func.body.get(), for_init_vars);
    for (auto& v : for_init_vars) {
        declared.insert(v);
    }

    // Declare explicit local vars first
    // v3.15: Deduplicate by name — only declare each variable once.
    // If the same name appears with different types (e.g., var_8 as both
    // uint32_t and uint64_t), keep the first declaration.
    std::set<std::string> emitted;
    for (auto& [name, type] : local_vars) {
        if (emitted.count(name)) continue;       // v3.15: skip duplicates
        if (!used_vars.count(name)) continue;     // skip unused
        emitted.insert(name);
        emitIndent();
        // v4.5: If variable is used in pointer context (indirect call,
        // dereference, member access), override type to void*
        // v8.8: Also apply usage-based type refinement (fp/bool/int)
        // when the stored type is unknown (对标 Ghidra type analysis).
        // v8.8: Use Ghidra-style name if available (uVar1/iVar2/ptrVar3).
        std::string decl_name = name;
        auto rnIt = ghidra_rename_map_.find(name);
        if (rnIt != ghidra_rename_map_.end())
            decl_name = rnIt->second;
        if (ptr_vars.count(name)) {
            out_ << "void *" << decl_name << ";";
        } else if (type.category != CType::TC_UNKNOWN) {
            emitType(type);
            out_ << " " << decl_name << ";";
        } else if (fp_vars.count(name)) {
            out_ << "double " << decl_name << ";";
        } else if (bool_vars.count(name)) {
            out_ << "bool " << decl_name << ";";
        } else if (int_vars.count(name)) {
            out_ << "int " << decl_name << ";";
        } else {
            emitType(type);
            out_ << " " << decl_name << ";";
        }
        out_ << "\n";
    }

    // v9.7: Auto-declare any remaining variables that are used in the body.
    // v9.9: Declare ALL used variables, even those never assigned (LHS of =).
    // Variables that are used but never assigned may be pass-through parameters,
    // uninitialized locals, or decompiler artifacts. We mark them with a comment
    // so the user can identify them, but they must be declared to avoid C
    // compilation errors from undeclared identifiers.
    std::set<std::string> assigned_vars;
    collectAssignedVars(func.body.get(), assigned_vars);

    // v9.25: Collect variables used as function call arguments. These
    // variables have values from PHI nodes, parameters, or copy chains
    // and should not be marked as UNINIT.
    std::set<std::string> call_arg_vars;
    collectCallArgVars(func.body.get(), call_arg_vars);

    // v46.2: Simplified temp variable naming (Hex-Rays-style tmp_N).
    // No more Ghidra-style rename map — getVarName() generates tmp_N directly.
    // The rename map is still needed for legacy names (vN, tN patterns)
    // from the copy propagation pass, which we convert to tmp_N.
    ghidra_rename_map_.clear();
    {
        // Per-category counters for tmp_N sequential naming
        int tmp_counter = 0;
        // Helper lambda: check if a name is a decompiler temp eligible for rename
        auto isTempName = [](const std::string& name) -> bool {
            if (name.empty()) return false;
            auto isAllDigits = [](const std::string& s) {
                return !s.empty() && std::all_of(s.begin(), s.end(),
                    [](unsigned char c) { return std::isdigit(c); });
            };
            // tmp_N is the canonical name format — no rename needed
            if (name.size() > 4 && name.substr(0, 4) == "tmp_" && isAllDigits(name.substr(4)))
                return false;  // Already canonical, no rename needed
            // Legacy patterns from copy propagation: vN, tN, varN, tmpN (no underscore)
            if (name.size() >= 2 && name[0] == 'v' && isAllDigits(name.substr(1)))
                return true;
            if (name.size() >= 2 && name[0] == 't' && isAllDigits(name.substr(1)))
                return true;
            if (name.size() >= 4 && name.substr(0, 3) == "var" && isAllDigits(name.substr(3)))
                return true;
            if (name.size() >= 4 && name.substr(0, 3) == "tmp" && isAllDigits(name.substr(3)))
                return true;
            return false;
        };
        // Build rename map for legacy names → tmp_N
        for (const auto& var : used_vars) {
            if (!isTempName(var)) continue;
            if (declared.count(var)) continue;
            if (var == "sp" || var == "pc" || var == "fp" || var == "lr" ||
                var == "0" || var.empty())
                continue;
            if (var.substr(0, 2) == "g_" || var.substr(0, 4) == "got_")
                continue;
            if (var.substr(0, 6) == "local_")
                continue;
            if (var.size() >= 5 && var.substr(0, 4) == "arg_" &&
                std::isdigit((unsigned char)var[4]))
                continue;
            ghidra_rename_map_[var] = "tmp_" + std::to_string(++tmp_counter);
        }
    }

    // v11.0: Ghidra-style variable declaration reduction.
    // Count read-usage of each variable in the body so we can skip
    // single-use temporaries (assigned once, used 0 or 1 times). Ghidra
    // inlines these via copy propagation; propagateCopies handles the
    // common tN case, and this acts as a safety net for the rest.
    std::map<std::string, int> varUsageCount;
    countVarUsage(func.body.get(), varUsageCount);

    for (const auto& var : used_vars) {
        if (declared.count(var)) continue;
        // Skip special names
        if (var == "sp" || var == "pc" || var == "fp" || var == "lr" ||
            var == "0" || var.empty())
            continue;
        // Skip names starting with "g_" or "got_" (globals / GOT entries)
        if (var.substr(0, 2) == "g_" || var.substr(0, 4) == "got_")
            continue;
        // local_ prefixed stack variables are declared via local_vars
        if (var.substr(0, 6) == "local_")
            continue;
        // v8.6: Skip arg_N pattern names — these are parameter placeholder
        // names that were trimmed from the signature but still used as
        // temporaries in the body. They should not be re-declared as locals.
        if (var.size() >= 5 && var.substr(0, 4) == "arg_" &&
            std::isdigit((unsigned char)var[4]))
            continue;

        // v11.0: Ghidra-style variable declaration reduction.
        // Only skip truly dead variables (assigned but never read).
        // Single-use temporaries are kept because propagateCopies may
        // not have inlined all of them (especially vN/tmpN/uVarN patterns).
        int usage = varUsageCount.count(var) ? varUsageCount[var] : 0;
        if (assigned_vars.count(var)) {
            // Assigned but never read → dead variable, skip declaration.
            if (usage == 0) continue;
        } else {
            // Never assigned and never read → not in body at all, skip.
            if (usage == 0) continue;
        }

        declared.insert(var);
        emitIndent();
        // v4.5: ptr_vars overrides everything — indirect call / dereference
        // always means pointer type
        // v8.8: Improved type inference with usage-based detection
        // (对标 Ghidra type analysis + R2 var type guessing).
        // v6.2: Type priority — pointer > int > bool > fp > inferred > default(uint64_t).
        // A variable used in both arithmetic and boolean contexts should be int.
        // 对标 Ghidra: type propagation resolves conflicts by picking the
        // wider/more general type.
        // v8.8: Use Ghidra-style name if available (uVar1/iVar2/ptrVar3).
        std::string decl_name = var;
        auto rnIt = ghidra_rename_map_.find(var);
        if (rnIt != ghidra_rename_map_.end())
            decl_name = rnIt->second;
        if (ptr_vars.count(var)) {
            // v10.7: Initialize uninitialized variables to 0 (like Ghidra).
            if (!assigned_vars.count(var) && !call_arg_vars.count(var) &&
                !for_init_vars.count(var)) {
                out_ << "void *" << decl_name << " = 0; /* UNINIT */";
            } else {
                out_ << "void *" << decl_name << ";";
            }
        } else if (int_vars.count(var)) {
            if (!assigned_vars.count(var) && !call_arg_vars.count(var) &&
                !for_init_vars.count(var)) {
                out_ << "int " << decl_name << " = 0; /* UNINIT */";
            } else {
                out_ << "int " << decl_name << ";";
            }
        } else if (fp_vars.count(var)) {
            if (!assigned_vars.count(var) && !call_arg_vars.count(var) &&
                !for_init_vars.count(var)) {
                out_ << "double " << decl_name << " = 0.0; /* UNINIT */";
            } else {
                out_ << "double " << decl_name << ";";
            }
        } else if (bool_vars.count(var)) {
            if (!assigned_vars.count(var) && !call_arg_vars.count(var) &&
                !for_init_vars.count(var)) {
                out_ << "bool " << decl_name << " = false; /* UNINIT */";
            } else {
                out_ << "bool " << decl_name << ";";
            }
        } else {
            auto typeIt = inferred_types.find(var);
            if (typeIt != inferred_types.end() && typeIt->second.category != CType::TC_UNKNOWN) {
                emitType(typeIt->second);
                // v10.7: If uninitialized, add = 0 initializer.
                if (!assigned_vars.count(var) && !call_arg_vars.count(var) &&
                    !for_init_vars.count(var)) {
                    out_ << " " << decl_name << " = 0; /* UNINIT */";
                } else {
                    out_ << " " << decl_name << ";";
                }
            } else {
                // v10.7: If uninitialized, add = 0 initializer.
                if (!assigned_vars.count(var) && !call_arg_vars.count(var) &&
                    !for_init_vars.count(var)) {
                    out_ << "uint64_t " << decl_name << " = 0; /* UNINIT */";
                } else {
                    out_ << "uint64_t " << decl_name << ";";
                }
            }
        }
        out_ << "\n";
    }
}

// ── Print a complete function ──
std::string CPrinter::printFunction(const Function& func) {
    out_.str("");
    out_.clear();
    indent_level_ = 0;
    // v8.8: Clear Ghidra-style rename map (will be rebuilt in emitLocalVarDecls)
    ghidra_rename_map_.clear();

    // Optional comment (e.g. function signature)
    if (!func.comment.empty()) {
        out_ << "// " << func.comment << "\n";
    }

    // 参考 Ghidra printc.cc emitFunctionDeclaration + getActualParamCount:
    // Trim unused parameters — only emit params actually referenced in body.
    // v4.2: if body is null (pipeline failure), keep all params as-is.
    std::vector<std::pair<std::string, CType>> used_params;
    if (func.body) {
        std::set<std::string> used_vars;
        collectVarRefs(func.body.get(), used_vars);

        // v9.14: Detect pointer usage patterns for parameter type inference.
        // When a parameter (e.g. in_arg0) is used as *ptr, ptr->field,
        // ptr[index], or ptr + offset, its type should be void* instead
        // of uint64_t. This mirrors what emitLocalVarDecls does for locals.
        std::set<std::string> ptr_vars;
        collectPointerVars(func.body.get(), ptr_vars);

        for (auto& param : func.params) {
            // v9.28: Always include "this" in the parameter list for member
            // functions. For other parameters, only include if used.
            if (used_vars.count(param.first) || param.first == "this") {
                // If the parameter is used as a pointer, override its type
                if (ptr_vars.count(param.first) && !param.second.isPointer()) {
                    auto adjusted = param;
                    adjusted.second = CType::voidPtr();
                    used_params.push_back(adjusted);
                } else {
                    used_params.push_back(param);
                }
            }
        }
    } else {
        used_params = func.params;
    }

    // Function signature
    emitType(func.return_type);
    out_ << " ";
    // v7.0: Strip trailing "()" from demangled names like "Class::~Class()"
    // to avoid double parentheses: "Class::~Class()(params)" → "Class::~Class(params)"
    std::string displayName = func.name;
    // v8.1: Strip trailing "()" (e.g., "Class::~Class()" → "Class::~Class")
    if (displayName.size() > 2 && displayName.substr(displayName.size() - 2) == "()") {
        displayName = displayName.substr(0, displayName.size() - 2);
    }
    // v8.1: Strip trailing " const" suffix (e.g., "Mob::getHealth() const" → "Mob::getHealth()")
    // Also handle " volatile" and " const volatile"
    if (displayName.size() > 6 && displayName.substr(displayName.size() - 6) == " const") {
        displayName = displayName.substr(0, displayName.size() - 6);
    }
    if (displayName.size() > 15 && displayName.substr(displayName.size() - 15) == " const volatile") {
        displayName = displayName.substr(0, displayName.size() - 15);
    } else if (displayName.size() > 9 && displayName.substr(displayName.size() - 9) == " volatile") {
        displayName = displayName.substr(0, displayName.size() - 9);
    }
    // v8.1: Now strip trailing "()" again (after removing " const" from "getHealth() const")
    // v7.0: Strip return type prefix from demangled names
    // Example: "void std::vector<...>::method(...)" → "std::vector<...>::method(...)"
    // The return type is emitted separately by emitType() above.
    static const char* kReturnTypePrefixes[] = {
        "void ", "int ", "long ", "unsigned int ", "unsigned long ",
        "bool ", "char ", "double ", "float ", "short ", "unsigned short ",
        "long long ", "unsigned long long ", "signed char ", "unsigned char ",
        "size_t ", "ssize_t ", "wchar_t ", "char16_t ", "char32_t ",
        "uint8_t ", "int8_t ", "uint16_t ", "int16_t ",
        "uint32_t ", "int32_t ", "uint64_t ", "int64_t ",
    };
    for (const char* prefix : kReturnTypePrefixes) {
        size_t plen = strlen(prefix);
        if (displayName.size() > plen && displayName.substr(0, plen) == prefix) {
            if (displayName.find("::", plen) != std::string::npos ||
                displayName.find('(', plen) != std::string::npos) {
                displayName = displayName.substr(plen);
                break;
            }
        }
    }
    // v7.0: Strip parameter list from demangled names that have embedded signature
    // Example: "std::vector<...>::method<T>(int)" → "std::vector<...>::method<T>"
    // The parameter list at the end matches the pattern: (type, type, ...)
    if (!displayName.empty() && displayName.back() == ')') {
        // Find the matching '(' by scanning from the end, tracking nesting
        int depth = 0;
        ssize_t parenPos = -1;
        for (ssize_t i = (ssize_t)displayName.size() - 1; i >= 0; i--) {
            if (displayName[i] == ')') depth++;
            else if (displayName[i] == '(') {
                depth--;
                if (depth == 0) {
                    parenPos = i;
                    break;
                }
            }
        }
        if (parenPos > 0) {
            // Check if what's before the '(' is a valid function name (contains ::)
            std::string before = displayName.substr(0, parenPos);
            if (before.find("::") != std::string::npos) {
                displayName = before;
            }
        }
    }
    out_ << displayName << "(";
    if (used_params.empty()) {
        out_ << "void";
    } else {
        for (size_t i = 0; i < used_params.size(); i++) {
            if (i > 0) out_ << ", ";
            emitType(used_params[i].second);
            out_ << " " << used_params[i].first;
        }
    }
    out_ << ") {\n";

    indent_level_ = 1;

    // v11.5: 过滤局部变量 — 扫描函数体收集所有实际被引用的变量名，
    // 只声明实际使用的变量，移除未使用的变量声明。
    // 创建一个预过滤的local_vars列表，传递给emitLocalVarDecls以避免拷贝Function
    std::vector<std::pair<std::string, CType>> filtered_local_vars;
    bool has_filtered_vars = false;
    if (func.body && !func.local_vars.empty()) {
        std::set<std::string> used_vars;
        collectVarRefs(func.body.get(), used_vars);
        for (auto& [name, type] : func.local_vars) {
            if (used_vars.count(name)) {
                filtered_local_vars.push_back({name, type});
            }
        }
        has_filtered_vars = true;
    }

    // Local variable declarations
    emitLocalVarDecls(func, has_filtered_vars ? &filtered_local_vars : nullptr);

    if (!has_filtered_vars ? !func.local_vars.empty() : !filtered_local_vars.empty())
        out_ << "\n";

    // Body
    if (func.body) {
        emitStmt(func.body.get());
    }

    // Ensure function has a return
    out_ << "\n";
    indent_level_ = 0;
    out_ << "}\n";

    // v3.14: Post-print safety net — guarantee no sp/pc/lr/fp in output
    std::string raw = out_.str();
    return sanitizeOutput(raw);
}

// ── Print just the body ──
std::string CPrinter::printBody(const Stmt* body) {
    out_.str("");
    out_.clear();
    indent_level_ = 1;
    if (body) emitStmt(body);
    return out_.str();
}

// ════════════════════════════════════════════════════════════════════
// v3.14: sanitizeOutput — Post-print safety net
// Scans the final C code for any whole-word occurrence of sp/pc/lr/fp
// and replaces them with "0". This is the last line of defense after:
//   Layer 1: eliminateSpecialRegisters() — IR-level elimination
//   Layer 2: shouldEmitStmt() + getVarName() — CTree-level filtering
// If this function actually replaces anything, it indicates a bug in
// Layers 1-2 that should be investigated.
// ════════════════════════════════════════════════════════════════════
std::string CPrinter::sanitizeOutput(const std::string& code) {
    // Forbidden register names (checked as whole words)
    static const char* forbidden[] = {"sp", "pc", "lr", "fp"};

    auto isWordChar = [](char c) {
        return std::isalnum((unsigned char)c) || c == '_';
    };

    std::string result;
    result.reserve(code.size());

    size_t i = 0;
    while (i < code.size()) {
        bool matched = false;

        // Check word boundary before position i
        bool boundaryBefore = (i == 0) || !isWordChar(code[i - 1]);

        if (boundaryBefore) {
            for (const char* word : forbidden) {
                size_t wlen = std::strlen(word);
                if (i + wlen > code.size()) continue;
                if (code.compare(i, wlen, word) != 0) continue;

                // Check word boundary after
                bool boundaryAfter = (i + wlen >= code.size()) ||
                                     !isWordChar(code[i + wlen]);
                if (!boundaryAfter) continue;

                // Whole-word match — replace with "0"
                result += "0";
                i += wlen;
                matched = true;
                break;
            }
        }

        if (!matched) {
            result += code[i];
            i++;
        }
    }

    // v5.1: Remove dead constant-condition patterns as text-level safety net
    // Replace "0 == 0" with "1" (always true) and "0 != 0" with "0" (always false)
    // This handles the cbz/cbnz pattern where both operands are constant 0
    // v8.4 FIX: Check word boundaries to avoid matching "tmp0 == 0" as "0 == 0"
    std::string cleaned;
    cleaned.reserve(result.size());
    size_t pos = 0;
    while (pos < result.size()) {
        // Check word boundary before position
        bool boundaryBefore = (pos == 0) || !isWordChar(result[pos - 1]);

        // Look for "0 == 0" pattern (6 chars: '0',' ','=','=',' ','0')
        if (boundaryBefore && pos + 6 <= result.size() && result[pos] == '0' &&
            result[pos+1] == ' ' && result[pos+2] == '=' &&
            result[pos+3] == '=' && result[pos+4] == ' ' &&
            result[pos+5] == '0') {
            // Check word boundary after
            bool boundaryAfter = (pos + 6 >= result.size()) ||
                                 !isWordChar(result[pos + 6]);
            if (boundaryAfter) {
                cleaned += "1";
                pos += 6;
                continue;
            }
        }
        // Look for "0 != 0" pattern (6 chars: '0',' ','!','=',' ','0')
        if (boundaryBefore && pos + 6 <= result.size() && result[pos] == '0' &&
            result[pos+1] == ' ' && result[pos+2] == '!' &&
            result[pos+3] == '=' && result[pos+4] == ' ' &&
            result[pos+5] == '0') {
            // Check word boundary after
            bool boundaryAfter = (pos + 6 >= result.size()) ||
                                 !isWordChar(result[pos + 6]);
            if (boundaryAfter) {
                cleaned += "0";
                pos += 6;
                continue;
            }
        }
        cleaned += result[pos];
        pos++;
    }

    // v5.2: Text-level safety net for constant-condition patterns that
    // slipped through CTree-level eliminateConstantConditions.
    //
    // Patterns handled (using brace-depth tracking):
    //   if (1) { then } else { else }  →  { then }
    //   if (1) { then }                →  { then }
    //   do { body } while (0);         →  { body }
    //   while (0) { body }             →  (removed)
    //   if (0) { then } else { else }  →  { else }
    //   if (0) { then }                →  (removed)
    //
    // We run multiple passes because removing one pattern may expose another.
    for (int pass = 0; pass < 3; pass++) {
        std::string tmp;
        tmp.reserve(cleaned.size());
        size_t p = 0;
        bool changed = false;

        while (p < cleaned.size()) {
            // Helper: skip whitespace
            auto skipWs = [&](size_t& pos) {
                while (pos < cleaned.size() &&
                       (cleaned[pos] == ' ' || cleaned[pos] == '\t' ||
                        cleaned[pos] == '\n' || cleaned[pos] == '\r'))
                    pos++;
            };

            // Helper: find matching closing brace, starting from an opening '{'
            // Returns position after the matching '}', or string::npos if not found
            auto findMatchingBrace = [&](size_t start) -> size_t {
                // start should point to '{'
                if (start >= cleaned.size() || cleaned[start] != '{')
                    return std::string::npos;
                int depth = 1;
                size_t i = start + 1;
                while (i < cleaned.size() && depth > 0) {
                    if (cleaned[i] == '{') depth++;
                    else if (cleaned[i] == '}') depth--;
                    i++;
                }
                if (depth == 0) return i;  // position after '}'
                return std::string::npos;
            };

            // ── Pattern: if (1) { ... } [else { ... }] ──
            // ── Pattern: if (0) { ... } [else { ... }] ──
            if (p + 5 <= cleaned.size() && cleaned[p] == 'i' &&
                cleaned[p+1] == 'f' && cleaned[p+2] == ' ' &&
                cleaned[p+3] == '(' &&
                (cleaned[p+4] == '1' || cleaned[p+4] == '0') &&
                p + 6 <= cleaned.size() && cleaned[p+5] == ')') {
                // Check word boundary before "if"
                bool boundaryBefore = (p == 0) ||
                    (!std::isalnum((unsigned char)cleaned[p-1]) && cleaned[p-1] != '_');
                if (boundaryBefore) {
                    int condVal = cleaned[p+4] - '0';  // 1 or 0
                    size_t afterIf = p + 6;  // position after "if (N)"
                    skipWs(afterIf);

                    if (afterIf < cleaned.size() && cleaned[afterIf] == '{') {
                        size_t afterThen = findMatchingBrace(afterIf);
                        if (afterThen != std::string::npos) {
                            // Check for "else"
                            size_t elseStart = afterThen;
                            skipWs(elseStart);
                            bool hasElse = (elseStart + 4 <= cleaned.size() &&
                                           cleaned[elseStart] == 'e' &&
                                           cleaned[elseStart+1] == 'l' &&
                                           cleaned[elseStart+2] == 's' &&
                                           cleaned[elseStart+3] == 'e');
                            size_t afterElse = elseStart;
                            if (hasElse) {
                                afterElse = elseStart + 4;
                                skipWs(afterElse);
                                // Skip the else branch
                                if (afterElse < cleaned.size() && cleaned[afterElse] == '{') {
                                    afterElse = findMatchingBrace(afterElse);
                                } else {
                                    // Single statement else — skip to ';'
                                    while (afterElse < cleaned.size() &&
                                           cleaned[afterElse] != ';' &&
                                           cleaned[afterElse] != '\n')
                                        afterElse++;
                                    if (afterElse < cleaned.size()) afterElse++;  // skip ';'
                                }
                            }

                            // Emit the appropriate branch
                            if (condVal == 1) {
                                // Keep then branch content (inside braces)
                                tmp += cleaned.substr(afterIf, afterThen - afterIf);
                            } else {
                                // Keep else branch content if present
                                if (hasElse) {
                                    size_t elseContentStart = elseStart + 4;
                                    skipWs(elseContentStart);
                                    if (elseContentStart < cleaned.size() &&
                                        cleaned[elseContentStart] == '{') {
                                        size_t afterElseBrace = findMatchingBrace(elseContentStart);
                                        if (afterElseBrace != std::string::npos) {
                                            tmp += cleaned.substr(elseContentStart,
                                                                  afterElseBrace - elseContentStart);
                                        }
                                    }
                                }
                                // else: no else branch and cond is 0 → remove entirely
                            }
                            p = hasElse ? afterElse : afterThen;
                            changed = true;
                            continue;
                        }
                    }
                }
            }

            // ── Pattern: do { ... } while (0); ──
            if (p + 3 <= cleaned.size() && cleaned[p] == 'd' &&
                cleaned[p+1] == 'o' && cleaned[p+2] == ' ') {
                bool boundaryBefore = (p == 0) ||
                    (!std::isalnum((unsigned char)cleaned[p-1]) && cleaned[p-1] != '_');
                if (boundaryBefore) {
                    size_t bracePos = p + 3;
                    skipWs(bracePos);
                    if (bracePos < cleaned.size() && cleaned[bracePos] == '{') {
                        size_t afterBody = findMatchingBrace(bracePos);
                        if (afterBody != std::string::npos) {
                            // Check for "while (0);"
                            size_t wpos = afterBody;
                            skipWs(wpos);
                            if (wpos + 11 <= cleaned.size() &&
                                cleaned[wpos] == 'w' &&
                                cleaned[wpos+1] == 'h' &&
                                cleaned[wpos+2] == 'i' &&
                                cleaned[wpos+3] == 'l' &&
                                cleaned[wpos+4] == 'e' &&
                                cleaned[wpos+5] == ' ' &&
                                cleaned[wpos+6] == '(' &&
                                cleaned[wpos+7] == '0' &&
                                cleaned[wpos+8] == ')' &&
                                cleaned[wpos+9] == ';') {
                                // Keep body content (inside braces)
                                tmp += cleaned.substr(bracePos, afterBody - bracePos);
                                p = wpos + 10;  // after "while (0);"
                                changed = true;
                                continue;
                            }
                        }
                    }
                }
            }

            // ── Pattern: while (0) { ... }  → remove entirely ──
            if (p + 10 <= cleaned.size() && cleaned[p] == 'w' &&
                cleaned[p+1] == 'h' && cleaned[p+2] == 'i' &&
                cleaned[p+3] == 'l' && cleaned[p+4] == 'e' &&
                cleaned[p+5] == ' ' && cleaned[p+6] == '(' &&
                cleaned[p+7] == '0' && cleaned[p+8] == ')') {
                bool boundaryBefore = (p == 0) ||
                    (!std::isalnum((unsigned char)cleaned[p-1]) && cleaned[p-1] != '_');
                if (boundaryBefore) {
                    size_t bracePos = p + 9;
                    skipWs(bracePos);
                    if (bracePos < cleaned.size() && cleaned[bracePos] == '{') {
                        size_t afterBody = findMatchingBrace(bracePos);
                        if (afterBody != std::string::npos) {
                            // Remove entirely
                            p = afterBody;
                            changed = true;
                            continue;
                        }
                    }
                }
            }

            // ── Pattern: orphaned "continue;" or "break;" (not inside a loop)
            // These appear when do-while(0) or while(0) is unwrapped.
            // Strip the statement and its trailing semicolon.
            // We detect "orphaned" by checking brace depth == 0 (function body level).
            // This is a conservative check — only strips at the top level of the
            // function body, not inside nested blocks.
            // NOTE: This is handled by tracking brace depth across the full pass.
            // For simplicity, we skip this here and rely on CTree-level stripDanglingJumps.

            tmp += cleaned[p];
            p++;
        }

        cleaned = std::move(tmp);
        if (!changed) break;  // no more changes — stop early
    }

    // v5.2: Final pass — strip orphaned "continue;" and "break;" statements
    // that appear at brace depth 0 or 1 (function body level, not inside loops).
    // These are artifacts of do-while(0)/while(0) unwrapping where the
    // CTree-level stripDanglingJumps missed some cases.
    {
        std::string tmp;
        tmp.reserve(cleaned.size());
        size_t p = 0;
        int braceDepth = 0;
        while (p < cleaned.size()) {
            if (cleaned[p] == '{') {
                braceDepth++;
                tmp += cleaned[p];
                p++;
                continue;
            }
            if (cleaned[p] == '}') {
                braceDepth--;
                tmp += cleaned[p];
                p++;
                continue;
            }
            // Check for "continue;" or "break;" at depth <= 1 (function body)
            // Depth 0 = inside function body (after the opening brace of the function)
            // Depth 1 = inside a block directly in the function body
            // We only strip at depth <= 1 to avoid removing valid continue/break
            // inside loops (which are at depth >= 2).
            if (braceDepth <= 1 &&
                p + 9 <= cleaned.size() &&
                cleaned[p] == 'c' && cleaned[p+1] == 'o' &&
                cleaned[p+2] == 'n' && cleaned[p+3] == 't' &&
                cleaned[p+4] == 'i' && cleaned[p+5] == 'n' &&
                cleaned[p+6] == 'u' && cleaned[p+7] == 'e' &&
                cleaned[p+8] == ';') {
                // Check word boundary before
                bool boundaryBefore = (p == 0) ||
                    (!std::isalnum((unsigned char)cleaned[p-1]) && cleaned[p-1] != '_');
                if (boundaryBefore) {
                    p += 9;  // skip "continue;"
                    continue;
                }
            }
            if (braceDepth <= 1 &&
                p + 6 <= cleaned.size() &&
                cleaned[p] == 'b' && cleaned[p+1] == 'r' &&
                cleaned[p+2] == 'e' && cleaned[p+3] == 'a' &&
                cleaned[p+4] == 'k' && cleaned[p+5] == ';') {
                bool boundaryBefore = (p == 0) ||
                    (!std::isalnum((unsigned char)cleaned[p-1]) && cleaned[p-1] != '_');
                if (boundaryBefore) {
                    p += 6;  // skip "break;"
                    continue;
                }
            }
            tmp += cleaned[p];
            p++;
        }
        cleaned = std::move(tmp);
    }

    return cleaned;
}

// 参考 Ghidra printc.cc emitBlock: check if a statement is single
// (non-block, or block with exactly 1 statement) for brace omission.
bool CPrinter::isSingleStmt(const Stmt* stmt) {
    if (!stmt) return false;
    if (stmt->type != NT_BLOCK) return true;
    auto* blk = static_cast<const Block*>(stmt);
    return blk->statements.size() == 1;
}

bool CPrinter::isEmptyBranch(const Stmt* stmt) {
    if (!stmt) return true;
    if (stmt->type != NT_BLOCK) return false;
    auto* blk = static_cast<const Block*>(stmt);
    return blk->statements.empty();
}

// ── Emit statement ──
void CPrinter::emitStmt(const Stmt* stmt) {
    if (!stmt) return;

    switch (stmt->type) {
        case NT_BLOCK: {
            // 参考 Ghidra printc.cc emitBlock: skip dead code after
            // return/break/continue/goto — subsequent statements are unreachable.
            // v5.7: BUT keep loops, ifs, and all code after return — they're
            // reachable through back edges (loops) or other CBRANCH branches.
            // v38.0: In a decompiler, blocks are in node-ID order, not control
            // flow order. A return statement (from a conditional branch block)
            // may appear before other blocks that are actually reachable via
            // the other branch. Removing dead code after return here is too
            // aggressive — it causes loss of loop body blocks. The CTreeBuilder's
            // sequenceToStmt already handles this correctly (only skips after
            // return when NOT inside a loop). So we simply print all statements.
            auto* blk = static_cast<const Block*>(stmt);
            for (auto& s : blk->statements) {
                emitStmt(s.get());
            }
            break;
        }
        case NT_IF: {
            // 参考 Ghidra printc.cc emitIf: skip empty branches,
            // omit braces for single statements.
            auto* ifn = static_cast<const If*>(stmt);
            bool then_empty = isEmptyBranch(ifn->then_branch.get());
            bool else_empty = isEmptyBranch(ifn->else_branch.get());

            // Both empty — skip entirely
            if (then_empty && else_empty) {
                break;
            }

            emitIndent();

            // If then is empty but else is not, invert condition
            if (then_empty) {
                out_ << "if (!";
                emitExpr(ifn->condition.get());
                out_ << ")";
                if (isSingleStmt(ifn->else_branch.get()) && else_empty == false) {
                    out_ << "\n";
                    indent_level_++;
                    emitStmt(ifn->else_branch.get());
                    indent_level_--;
                } else {
                    out_ << " {\n";
                    indent_level_++;
                    emitStmt(ifn->else_branch.get());
                    indent_level_--;
                    emitIndent();
                    out_ << "}\n";
                }
                break;
            }

            out_ << "if (";
            emitExpr(ifn->condition.get());
            out_ << ")";

            // v6.2: Check if then-branch is a nested if (dangling else risk).
            // If so, always use braces to avoid GCC -Wdangling-else warning.
            // 参考 Ghidra printc.cc: always braces around nested if-else.
            auto isNestedIf = [](const Stmt* s) -> bool {
                if (!s) return false;
                if (s->type == NT_IF) return true;
                if (s->type == NT_BLOCK) {
                    auto* b = static_cast<const Block*>(s);
                    if (!b->statements.empty() && b->statements[0]->type == NT_IF)
                        return true;
                }
                return false;
            };
            bool thenIsNestedIf = isNestedIf(ifn->then_branch.get());

            // Single then statement, no else → omit braces (unless nested if)
            if (isSingleStmt(ifn->then_branch.get()) && else_empty && !thenIsNestedIf) {
                out_ << "\n";
                indent_level_++;
                emitStmt(ifn->then_branch.get());
                indent_level_--;
            } else if (isSingleStmt(ifn->then_branch.get()) &&
                       isSingleStmt(ifn->else_branch.get()) && !thenIsNestedIf) {
                // Both single → no braces on either
                out_ << "\n";
                indent_level_++;
                emitStmt(ifn->then_branch.get());
                indent_level_--;
                emitIndent();
                out_ << "else\n";
                indent_level_++;
                emitStmt(ifn->else_branch.get());
                indent_level_--;
            } else {
                // Multi-statement → use braces
                out_ << " {\n";
                indent_level_++;
                emitStmt(ifn->then_branch.get());
                indent_level_--;
                if (!else_empty) {
                    emitIndent();
                    out_ << "} else {\n";
                    indent_level_++;
                    emitStmt(ifn->else_branch.get());
                    indent_level_--;
                }
                emitIndent();
                out_ << "}\n";
            }
            break;
        }
        case NT_WHILE: {
            // 参考 Ghidra printc.cc emitWhile: omit braces for single body.
            auto* wh = static_cast<const While*>(stmt);
            emitIndent();
            out_ << "while (";
            emitExpr(wh->condition.get());
            out_ << ")";
            if (isSingleStmt(wh->body.get())) {
                out_ << "\n";
                indent_level_++;
                emitStmt(wh->body.get());
                indent_level_--;
            } else {
                out_ << " {\n";
                indent_level_++;
                if (wh->body) emitStmt(wh->body.get());
                indent_level_--;
                emitIndent();
                out_ << "}\n";
            }
            break;
        }
        case NT_DO_WHILE: {
            auto* dw = static_cast<const DoWhile*>(stmt);
            emitIndent();
            out_ << "do {\n";
            indent_level_++;
            if (dw->body) emitStmt(dw->body.get());
            indent_level_--;
            emitIndent();
            out_ << "} while (";
            emitExpr(dw->condition.get());
            out_ << ");\n";
            break;
        }
        case NT_FOR: {
            // 参考 Ghidra printc.cc emitFor: omit braces for single body.
            auto* fr = static_cast<const For*>(stmt);
            emitIndent();
            out_ << "for (";
            // init is a StmtPtr: handle ExprStmt, VarDecl, and other types
            if (fr->init) {
                if (fr->init->type == NT_EXPR_STMT) {
                    auto* es = static_cast<const ExprStmt*>(fr->init.get());
                    if (es->expr) emitExpr(es->expr.get());
                } else if (fr->init->type == NT_DECL) {
                    auto* decl = static_cast<const VarDecl*>(fr->init.get());
                    emitType(decl->var_type);
                    out_ << " " << decl->var_name;
                    if (decl->init_expr) {
                        out_ << " = ";
                        emitExpr(decl->init_expr.get());
                    }
                } else {
                    // v9.19: For other init types (e.g., Assign wrapped in
                    // ExprStmt), emit as a generic expression.
                    emitStmt(fr->init.get());
                }
            }
            out_ << "; ";
            if (fr->condition) emitExpr(fr->condition.get());
            out_ << "; ";
            if (fr->increment) emitExpr(fr->increment.get());
            out_ << ")";
            if (isSingleStmt(fr->body.get())) {
                out_ << "\n";
                indent_level_++;
                emitStmt(fr->body.get());
                indent_level_--;
            } else {
                out_ << " {\n";
                indent_level_++;
                if (fr->body) emitStmt(fr->body.get());
                indent_level_--;
                emitIndent();
                out_ << "}\n";
            }
            break;
        }
        case NT_RETURN: {
            auto* ret = static_cast<const Return*>(stmt);
            emitIndent();
            out_ << "return";
            if (ret->value) {
                out_ << " ";
                emitExpr(ret->value.get());
            }
            out_ << ";\n";
            break;
        }
        case NT_BREAK:
            emitIndent();
            out_ << "break;\n";
            break;
        case NT_CONTINUE:
            emitIndent();
            out_ << "continue;\n";
            break;
        case NT_GOTO: {
            auto* g = static_cast<const Goto*>(stmt);
            emitIndent();
            out_ << "goto " << g->label << ";\n";
            break;
        }
        case NT_EXPR_STMT: {
            auto* es = static_cast<const ExprStmt*>(stmt);
            if (!es->comment.empty()) {
                emitIndent();
                out_ << "/* " << es->comment << " */\n";
            } else if (es->expr) {
                emitIndent();
                emitExpr(es->expr.get());
                out_ << ";\n";
            }
            break;
        }
        case NT_DECL: {
            auto* decl = static_cast<const VarDecl*>(stmt);
            emitIndent();
            emitType(decl->var_type);
            out_ << " " << decl->var_name;
            if (decl->init_expr) {
                out_ << " = ";
                emitExpr(decl->init_expr.get());
            }
            out_ << ";\n";
            break;
        }
        case NT_LABEL: {
            auto* lbl = static_cast<const Label*>(stmt);
            out_ << lbl->name << ":\n";
            if (lbl->stmt) emitStmt(lbl->stmt.get());
            break;
        }
        case NT_SWITCH: {
            // 参考 Ghidra printc.cc emitSwitch: add break to case bodies
            // if the last statement isn't already a break/return/goto.
            auto* sw = static_cast<const Switch*>(stmt);
            emitIndent();
            out_ << "switch (";
            emitExpr(sw->expr.get());
            out_ << ") {\n";
            indent_level_++;
            for (auto& [val, body] : sw->cases) {
                emitIndent();
                out_ << "case ";
                emitExpr(val.get());
                out_ << ":\n";
                if (body) {
                    emitStmt(body.get());
                    // Add break if last statement isn't break/return/goto
                    if (body->type == NT_BLOCK) {
                        auto* blk = static_cast<const Block*>(body.get());
                        if (!blk->statements.empty()) {
                            auto& last = blk->statements.back();
                            if (last && last->type != NT_BREAK &&
                                last->type != NT_RETURN &&
                                last->type != NT_GOTO) {
                                emitIndent();
                                out_ << "break;\n";
                            }
                        }
                    } else if (body->type != NT_BREAK &&
                               body->type != NT_RETURN &&
                               body->type != NT_GOTO) {
                        emitIndent();
                        out_ << "break;\n";
                    }
                } else {
                    emitIndent();
                    out_ << "break;\n";
                }
            }
            if (sw->default_body) {
                emitIndent();
                out_ << "default:\n";
                emitStmt(sw->default_body.get());
            }
            indent_level_--;
            emitIndent();
            out_ << "}\n";
            break;
        }
        // v10.0: 空语句
        case NT_EMPTY_STMT: {
            emitIndent();
            out_ << ";\n";
            break;
        }
        // v10.0: 内联汇编语句
        case NT_ASM: {
            auto* a = static_cast<const AsmStmt*>(stmt);
            emitIndent();
            if (a->is_volatile) {
                out_ << "__asm__ __volatile__ (\"";
            } else {
                out_ << "asm (\"";
            }
            out_ << escapeString(a->asm_string) << "\"";
            if (!a->outputs.empty() || !a->inputs.empty()) {
                out_ << " : ";
                for (size_t i = 0; i < a->outputs.size(); i++) {
                    if (i > 0) out_ << ", ";
                    emitExpr(a->outputs[i].get());
                }
                out_ << " : ";
                for (size_t i = 0; i < a->inputs.size(); i++) {
                    if (i > 0) out_ << ", ";
                    emitExpr(a->inputs[i].get());
                }
            }
            out_ << ");\n";
            break;
        }
        default:
            break;
    }
}

// ── Emit expression ──
void CPrinter::emitExpr(const Expr* expr, int parent_prec, bool is_right) {
    if (!expr) {
        out_ << "0";
        return;
    }

    // 参考 Ghidra printc.cc parentheses(): centrally decide whether
    // this expression needs outer parens based on parent precedence
    // and operand position (left/right). This avoids double-parens
    // that would occur if both the parent and child added parens.
    bool need_outer = needParens(expr, parent_prec, is_right);
    if (need_outer) out_ << "(";

    switch (expr->type) {
        case NT_VAR_REF: {
            auto* vr = static_cast<const VarRef*>(expr);
            // 参考 Ghidra printc.cc pushSymbol: resolve g_XXXX to real ELF symbol
            // v4.10: Guard against empty variable names
            std::string resolved = resolveVarName(vr->name.empty() ? "var_0" : vr->name);
            out_ << resolved;
            break;
        }
        case NT_CONST: {
            auto* c = static_cast<const Const*>(expr);
            if (c->is_fp) {
                char buf[64];
                snprintf(buf, sizeof(buf), "%g", c->fp_val);
                out_ << buf;
            } else {
                // 参考 Ghidra printc.cc pushSymbol: resolve known addresses
                bool is_string = false;
                // v10.1: 同时获取 UTF-16 标志与字符串长度，用于输出
                // L"..." 宽字符串字面量与 /* len=N */ 长度注释。
                bool is_utf16 = false;
                size_t str_len = 0;
                std::string sym = resolveConstAddr(c->int_val, is_string,
                                                   is_utf16, str_len);
                if (!sym.empty()) {
                    if (is_string) {
                        if (is_utf16) {
                            // v10.1: UTF-16 字符串 → 输出 L"..." 宽字符串。
                            // sym 已是 u16ToAscii 的转义形式（含 \uXXXX），
                            // 不能再调用 escapeString()，否则 \uXXXX 会被
                            // 二次转义为 \\uXXXX。
                            out_ << "L\"" << sym << "\"";
                        } else {
                            out_ << "\"" << escapeString(sym) << "\"";
                        }
                        // v10.1: 对已知长度的字符串附加长度注释
                        if (str_len > 0) {
                            out_ << " /* len=" << str_len << " */";
                        }
                    } else {
                        out_ << sym;
                    }
                } else {
                    out_ << formatHex(c->int_val);
                }
            }
            break;
        }
        case NT_STRING: {
            auto* s = static_cast<const StringConst*>(expr);
            out_ << "\"" << escapeString(s->value) << "\"";
            break;
        }
        case NT_BINARY_OP: {
            emitBinaryOp(static_cast<const BinaryOp*>(expr), parent_prec);
            break;
        }
        case NT_UNARY_OP: {
            // 参考 Ghidra printc.cc checkArrayDeref:
            // *(ptr + offset) → ptr[offset] instead of *(ptr + offset)
            auto* unop = static_cast<const UnaryOp*>(expr);
            if (unop->is_prefix && unop->op == "*") {
                emitDeref(unop->operand.get());
            } else {
                emitUnaryOp(unop, parent_prec);
            }
            break;
        }
        case NT_ASSIGN: {
            emitAssign(static_cast<const Assign*>(expr), parent_prec);
            break;
        }
        case NT_CALL: {
            emitCall(static_cast<const Call*>(expr));
            break;
        }
        case NT_CAST: {
            emitCast(static_cast<const Cast*>(expr), parent_prec);
            break;
        }
        case NT_MEMBER: {
            emitMember(static_cast<const MemberAccess*>(expr));
            break;
        }
        case NT_INDEX: {
            // 参考 Ghidra printc.cc emitArray: array[index]
            auto* idx = static_cast<const Index*>(expr);
            emitExpr(idx->array.get(), 15, false);
            out_ << "[";
            emitExpr(idx->index.get(), 0, false);
            out_ << "]";
            break;
        }
        case NT_TERNARY: {
            emitTernary(static_cast<const Ternary*>(expr), parent_prec);
            break;
        }
        // v10.0: 参考 Ghidra printc.cc 补全的专用表达式节点
        case NT_DEREF: {
            // 对标 Ghidra emitDeref: *(ptr + offset) → ptr[offset]
            auto* d = static_cast<const Deref*>(expr);
            emitDeref(d->operand.get());
            break;
        }
        case NT_ADDRESSOF: {
            // 对标 Ghidra address-of: &expr
            auto* a = static_cast<const AddressOf*>(expr);
            out_ << "&";
            emitExpr(a->operand.get(), 14, false);
            break;
        }
        case NT_SIZEOF: {
            auto* sz = static_cast<const SizeOf*>(expr);
            out_ << "sizeof(";
            if (sz->is_type) {
                emitType(sz->type_arg);
            } else {
                emitExpr(sz->expr_arg.get(), 0, false);
            }
            out_ << ")";
            break;
        }
        case NT_COMMA: {
            // 逗号表达式 (a, b) — 最低优先级
            auto* ce = static_cast<const CommaExpr*>(expr);
            for (size_t i = 0; i < ce->items.size(); i++) {
                if (i > 0) out_ << ", ";
                emitExpr(ce->items[i].get(), 1, i > 0);
            }
            break;
        }
        case NT_ENUM_CONST: {
            auto* ec = static_cast<const EnumConstRef*>(expr);
            if (!ec->enum_name.empty()) out_ << ec->enum_name;
            else out_ << formatHex(ec->value);
            break;
        }
        case NT_GLOBAL_VAR: {
            // 对标 Ghidra pushSymbol: 解析全局变量名
            auto* gv = static_cast<const GlobalVarRef*>(expr);
            if (!gv->name.empty()) {
                out_ << resolveVarName(gv->name);
            } else {
                bool is_str = false;
                // v10.1: 同时获取 UTF-16 标志与字符串长度
                bool is_utf16 = false;
                size_t str_len = 0;
                std::string sym = resolveConstAddr((int64_t)gv->address, is_str,
                                                   is_utf16, str_len);
                if (!sym.empty()) {
                    if (is_str) {
                        if (is_utf16) {
                            // v10.1: UTF-16 → L"...", sym 已转义，勿二次转义
                            out_ << "L\"" << sym << "\"";
                        } else {
                            out_ << "\"" << escapeString(sym) << "\"";
                        }
                        if (str_len > 0) {
                            out_ << " /* len=" << str_len << " */";
                        }
                    } else {
                        out_ << sym;
                    }
                } else {
                    out_ << "g_" << formatHex((int64_t)gv->address);
                }
            }
            break;
        }
        case NT_BITFIELD: {
            // 位域访问: 输出为 (base >> offset) & mask
            // 对标 Ghidra subpiece → bitfield extraction
            auto* bf = static_cast<const BitFieldAccess*>(expr);
            out_ << "((";
            emitExpr(bf->base.get(), 0, false);
            out_ << " >> " << bf->offset << ") & 0x";
            int64_t mask = (bf->width >= 64) ? -1 : ((1LL << bf->width) - 1);
            out_ << std::hex << mask << std::dec << ")";
            break;
        }
        case NT_NEW: {
            auto* ne = static_cast<const NewExpr*>(expr);
            out_ << "new ";
            emitType(ne->alloc_type);
            if (ne->is_array && ne->size_expr) {
                out_ << "[";
                emitExpr(ne->size_expr.get(), 0, false);
                out_ << "]";
            }
            if (!ne->ctor_args.empty()) {
                out_ << "(";
                for (size_t i = 0; i < ne->ctor_args.size(); i++) {
                    if (i > 0) out_ << ", ";
                    emitExpr(ne->ctor_args[i].get(), 0, false);
                }
                out_ << ")";
            }
            break;
        }
        case NT_DELETE: {
            auto* de = static_cast<const DeleteExpr*>(expr);
            out_ << "delete";
            if (de->is_array) out_ << "[]";
            out_ << " ";
            emitExpr(de->operand.get(), 14, false);
            break;
        }
        case NT_THROW_EXPR: {
            auto* te = static_cast<const ThrowExpr*>(expr);
            out_ << "throw ";
            emitExpr(te->operand.get(), 0, false);
            break;
        }
        case NT_INIT_LIST: {
            auto* il = static_cast<const InitList*>(expr);
            out_ << "{";
            for (size_t i = 0; i < il->items.size(); i++) {
                if (i > 0) out_ << ", ";
                emitExpr(il->items[i].get(), 0, false);
            }
            out_ << "}";
            break;
        }
        case NT_NULL:
            // 对标 Ghidra: null/undefined expressions emit as 0
            // This is safer than FIXME comments which break C compilation
            out_ << "0";
            break;
        default:
            out_ << "/* unknown expr */";
            break;
    }

    if (need_outer) out_ << ")";
}

void CPrinter::emitBinaryOp(const BinaryOp* binop, int parent_prec) {
    // 参考 Ghidra printc.cc emitOp: outer parens handled by emitExpr.
    // We just emit left op right, passing our precedence to children.
    (void)parent_prec;
    int my_prec = getOpPrecedence(binop->op);
    
    emitExpr(binop->left.get(), my_prec, false);
    out_ << " " << binop->op << " ";
    emitExpr(binop->right.get(), my_prec, true);
}

void CPrinter::emitUnaryOp(const UnaryOp* unop, int parent_prec) {
    // 参考 Ghidra printc.cc emitUnary: outer parens handled by emitExpr.
    (void)parent_prec;
    int my_prec = 14;  // unary precedence
    if (unop->is_prefix) {
        out_ << unop->op;
        emitExpr(unop->operand.get(), my_prec, false);
    } else {
        // Postfix (e.g., x++, x--)
        emitExpr(unop->operand.get(), 15, false);
        out_ << unop->op;
    }
}

void CPrinter::emitAssign(const Assign* assign, int parent_prec) {
    // 参考 Ghidra printc.cc emitOp: assignment is right-associative.
    // Outer parens handled by emitExpr.
    (void)parent_prec;
    int my_prec = getOpPrecedence(assign->op);
    emitExpr(assign->target.get(), my_prec, false);
    out_ << " " << assign->op << " ";
    // v4.10: Guard against null/empty RHS — ARM64 ADRP+LDR fusion path
    // can produce assignments with unresolved RHS. Emit 0 (对标 Ghidra)
    // instead of an empty string or crash.
    if (!assign->value) {
        out_ << "0";
    } else {
        emitExpr(assign->value.get(), my_prec, true);
    }
}

void CPrinter::emitCall(const Call* call) {
    std::string calleeName;
    if (!call->callee_name.empty())
        calleeName = simplifyCtorName(simplifyCppName(call->callee_name));
    else if (call->callee)
        calleeName = "";  // will be emitted inline
    else
        calleeName = "unknown";

    // v11.6: 检测构造函数调用，跳过 this 指针参数（第一个参数）
    // v12.0: 扩展检测所有 C++ 成员函数调用，统一跳过 this 指针
    // 在 C++ 伪代码中，成员函数调用不显示 this 指针。
    // 例如: std::string("Hook") 而不是 std::string(iVar1, "Hook", iVar2)
    // 例如: std::string::_Rep::_M_destroy(allocator) 而不是 _M_destroy(this, allocator)
    // 参考 IDA 的处理方式：所有成员函数调用都不显示 this 指针。
    size_t argStart = 0;
    bool isStringCtor = false;  // v50.2: std::string constructor with allocator
    bool isMDestroy = false;    // v50.2: _M_destroy destructor
    if (!call->callee_name.empty()) {
        // 先简化名称再检测
        std::string simplifiedName = simplifyCppName(call->callee_name);
        if (isConstructorCall(simplifiedName) || isMemberFunction(simplifiedName)) {
            argStart = 1;  // Skip the 'this' pointer
        }
        // v50.2: 检测 std::string 构造函数（含 allocator 参数需要隐藏）
        // 模式: std::string("literal", allocator) → std::string("literal")
        if (isConstructorCall(simplifiedName) && 
            calleeName.find("std::string") != std::string::npos) {
            isStringCtor = true;
        }
        // v50.2: 检测 _M_destroy 析构函数，显示为 ~string
        // 模式: std::string::_Rep::_M_destroy(allocator) → ~string()
        if (simplifiedName.find("_M_destroy") != std::string::npos) {
            isMDestroy = true;
            // 显示为 ~string 而非 _M_destroy
            if (calleeName.find("std::string") != std::string::npos ||
                calleeName.find("string") != std::string::npos) {
                calleeName = "~string";
            }
        }
    }

    if (!call->callee_name.empty())
        out_ << calleeName;
    else if (call->callee)
        emitExpr(call->callee.get());
    else
        out_ << "unknown";

    out_ << "(";

    // Sync current_col_ with the actual output stream position.
    // (对标 Ghidra printlanguage.cc column tracking: most of this printer
    // writes through out_ directly, so we recompute the column from the
    // stream contents before making a wrap decision. This keeps the
    // Oppen state consistent with the real output.)
    {
        const std::string s = out_.str();
        size_t last_nl = s.rfind('\n');
        current_col_ = (last_nl == std::string::npos)
                       ? (int)s.size()
                       : (int)(s.size() - last_nl - 1);
    }

    // v50.2: For std::string constructor, suppress the allocator argument.
    // The last argument to basic_string(const char*, allocator) is always
    // a default-constructed allocator — useless noise in pseudo-C.
    // IDA Hex-Rays hides it too.
    size_t argEnd = call->args.size();
    if (isStringCtor && argEnd - argStart >= 2) {
        // Keep only the string literal arg, drop allocator
        argEnd = argStart + 1;
    }
    // v50.2: For _M_destroy destructor, suppress allocator arg.
    // v55.0: Keep the first argument (the `this` pointer) so the
    // output shows which object is being destroyed, e.g. ~string(tmp_32).
    // Previously all args were hidden, producing naked ~string() calls.
    if (isMDestroy) {
        argEnd = argStart + 1;  // Keep `this` pointer, drop allocator
    }

    // Estimate the total rendered width of the argument list. We use
    // ~20 chars per argument (对标 Ghidra's width-estimation heuristic
    // in printc.cc emitCall) plus 2 for each ", " separator.
    int totalWidth = 0;
    for (size_t i = argStart; i < argEnd; i++) {
        totalWidth += 20;  // estimated argument width
        if (i > argStart) totalWidth += 2;  // ", "
    }

    // If the argument list would overflow the line width, put each
    // argument on its own indented line (对标 Ghidra printc.cc emitCall
    // long-argument wrapping). Otherwise emit them inline.
    bool wrap = (argEnd > argStart) &&
                (current_col_ + totalWidth > line_width_);

    if (wrap) {
        // Each argument on its own line, indented one level deeper than
        // the call itself. The closing paren returns to the original
        // indent so subsequent statements stay aligned.
        indent_level_++;
        for (size_t i = argStart; i < argEnd; i++) {
            out_ << "\n";
            emitIndent();
            emitExpr(call->args[i].get());
            if (i + 1 < argEnd) out_ << ",";
        }
        indent_level_--;
        out_ << "\n";
        emitIndent();
    } else {
        for (size_t i = argStart; i < argEnd; i++) {
            if (i > argStart) out_ << ", ";
            emitExpr(call->args[i].get());
        }
    }
    out_ << ")";
}

void CPrinter::emitCast(const Cast* cast, int parent_prec) {
    // 参考 Ghidra printc.cc emitCast + castStrategy:
    // Hide unnecessary casts (width promotions, same-type, int→ptr)
    (void)parent_prec;
    if (shouldHideCast(cast)) {
        // Just emit the operand without the cast
        emitExpr(cast->expr.get(), 14, false);
    } else {
        out_ << "(" << cast->target_type.toCString() << ")";
        emitExpr(cast->expr.get(), 14, false);
    }
}

void CPrinter::emitMember(const MemberAccess* member) {
    // 参考 Ghidra printc.cc checkBitFieldMember:
    // (*ptr).field → ptr->field (cleaner member access via pointer)
    if (!member->is_pointer && member->base &&
        member->base->type == NT_UNARY_OP) {
        auto* un = static_cast<const UnaryOp*>(member->base.get());
        if (un->op == "*" && un->is_prefix) {
            // (*ptr).field → ptr->field
            emitExpr(un->operand.get(), 15, false);
            out_ << "->" << member->field_name;
            return;
        }
    }
    // Normal case: base.field or base->field
    emitExpr(member->base.get(), 15, false);
    out_ << (member->is_pointer ? "->" : ".") << member->field_name;
}

// 参考 Ghidra printc.cc checkArrayDeref:
// Detect *(ptr + offset) pattern and emit as ptr[offset].
// If the operand is a simple expression (not ptr + offset), emit *ptr.
// v8.0: Also detect *(Cast(ptr_to_type, (base + offset))) → base->field_0xNN
// when base is a known struct pointer (this, ptr, etc.)
void CPrinter::emitDeref(const Expr* operand) {
    // v9.32: If the operand is a Cast to a typed pointer (not void*),
    // keep the cast and emit *(type*)(base + offset) instead of
    // converting to base[offset]. Emitting base[offset] when base is
    // void* produces void* arithmetic, which is a GCC extension and
    // not valid standard C.
    bool hasTypedPtrCast = false;
    const Cast* typedCast = nullptr;
    const Expr* castOperand = operand;
    if (operand && operand->type == NT_CAST) {
        auto* cast = static_cast<const Cast*>(operand);
        // Check if the cast is to a typed pointer (not void*)
        if (cast->target_type.category == CType::TC_POINTER &&
            !cast->target_type.pointee_type.empty() &&
            cast->target_type.pointee_type != "void") {
            hasTypedPtrCast = true;
            typedCast = cast;
            castOperand = cast->expr.get();
        }
    }

    // Helper to strip Cast nodes to find the inner BinaryOp
    auto unwrap = [](const Expr* op) -> const Expr* {
        while (op && op->type == NT_CAST) {
            auto* cast = static_cast<const Cast*>(op);
            op = cast->expr.get();
        }
        return op;
    };

    const Expr* inner = unwrap(castOperand);

    if (inner && inner->type == NT_BINARY_OP) {
        auto* bin = static_cast<const BinaryOp*>(inner);
        if (bin->op == "+" || bin->op == "-") {
            // v8.0: Check if the right side is a constant offset
            // If so, and the base is a known struct pointer, emit as member access
            bool isConstOffset = false;
            int64_t constOff = 0;
            if (bin->right->type == NT_CONST) {
                auto* c = static_cast<const Const*>(bin->right.get());
                constOff = c->int_val;
                isConstOffset = true;
            }

            // Check if base is a known struct pointer (this, ptrVar, obj, etc.)
            // v11.0: Use resolveVarName() so Ghidra-style renamed pointers
            // (e.g. ptrVar1) are recognized. The raw vr->name is the original
            // decompiler temp (e.g. v11) which never matches the "ptr" prefix,
            // so the struct-pointer member-access path was unreachable before.
            bool isStructPtr = false;
            std::string varName;
            if (bin->left->type == NT_VAR_REF) {
                auto* vr = static_cast<const VarRef*>(bin->left.get());
                varName = resolveVarName(vr->name);  // renamed name (ptrVar1, this, obj, ...)
                // Known struct pointer names (Ghidra-style: ptrVar1, this, obj, etc.)
                if (varName == "this" || varName.find("ptr") == 0 ||
                    varName.find("this") == 0 || varName.find("obj") == 0) {
                    isStructPtr = true;
                }
            }

            if (isStructPtr && isConstOffset && constOff >= 0) {
                // v8.5: vtable pointer at offset 0 → this->__vtable
                if (constOff == 0 && varName == "this") {
                    emitExpr(bin->left.get(), 15, false);
                    out_ << "->__vtable";
                    return;
                }
                // *(this + 0x20) → this->field_0x20
                emitExpr(bin->left.get(), 15, false);
                out_ << "->field_0x";
                out_ << std::hex << constOff << std::dec;
                return;
            }

            // v9.32: If we have a typed pointer cast (e.g., int32_t*),
            // keep the cast and emit *(type*)(base + offset) instead of
            // base[offset]. This avoids void* arithmetic when base is void*.
            if (hasTypedPtrCast && typedCast) {
                out_ << "*(";
                out_ << typedCast->target_type.toCString() << ")(";
                emitExpr(bin->left.get(), 15, false);
                out_ << " " << bin->op << " ";
                emitExpr(bin->right.get(), 0, false);
                out_ << ")";
                return;
            }

            // v11.0: Ghidra-style — emit *(base + offset) instead of base[offset].
            // Array subscript (base[index]) is reserved for explicit array types,
            // which are emitted via the NT_INDEX node. For a generic pointer plus
            // a constant/variable offset, the dereference form matches Ghidra's
            // default output and avoids falsely implying array semantics.
            // When a typed pointer cast is available it is handled above; here we
            // have no reliable element type, so emit the honest *(base + offset).
            out_ << "*(";
            emitExpr(bin->left.get(), 15, false);
            out_ << " " << bin->op << " ";
            emitExpr(bin->right.get(), 0, false);
            out_ << ")";
            return;
        }
    }
    // Simple dereference: *ptr
    out_ << "*";
    emitExpr(operand, 14, false);
}

void CPrinter::emitTernary(const Ternary* tern, int parent_prec) {
    // 参考 Ghidra printc.cc emitTernary: outer parens handled by emitExpr.
    (void)parent_prec;
    int my_prec = 3;  // ternary precedence

    // Condition: at ternary level (left of right-assoc)
    emitExpr(tern->condition.get(), my_prec, false);
    out_ << " ? ";
    // True expr: at ternary level (left of right-assoc)
    emitExpr(tern->true_expr.get(), my_prec, false);
    out_ << " : ";
    // False expr: right of right-assoc, same level OK
    emitExpr(tern->false_expr.get(), my_prec, true);
}

// ── Function signature ──
std::string Function::signature() const {
    std::ostringstream ss;
    ss << return_type.toCString() << " " << name << "(";
    if (params.empty()) {
        ss << "void";
    } else {
        for (size_t i = 0; i < params.size(); i++) {
            if (i > 0) ss << ", ";
            ss << params[i].second.toCString() << " " << params[i].first;
        }
    }
    ss << ")";
    return ss.str();
}

} // namespace ctree
