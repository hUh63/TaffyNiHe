// calling_convention.cpp — AArch64/ARM calling convention analysis implementation

#include "calling_convention.hpp"
#include <algorithm>
#include <cctype>
#include <sstream>

namespace mc {

// ── Helper: trim whitespace ──
static std::string trim(const std::string& s) {
    size_t start = s.find_first_not_of(" \t");
    if (start == std::string::npos) return "";
    size_t end = s.find_last_not_of(" \t");
    return s.substr(start, end - start + 1);
}

// ── Helper: split string by delimiter ──
static std::vector<std::string> split(const std::string& s, char delim) {
    std::vector<std::string> result;
    std::stringstream ss(s);
    std::string item;
    int depth = 0;  // handle nested parens/brackets
    std::string current;

    for (char c : s) {
        if (c == '(' || c == '[' || c == '<') depth++;
        else if (c == ')' || c == ']' || c == '>') depth--;

        if (c == delim && depth == 0) {
            result.push_back(trim(current));
            current.clear();
        } else {
            current += c;
        }
    }
    if (!current.empty())
        result.push_back(trim(current));
    return result;
}

// ── Parse a C signature string ──
// Examples:
//   "void* malloc(size_t)"           → return="void*", name="malloc", args=["size_t"]
//   "int add(int, int)"              → return="int", name="add", args=["int", "int"]
//   "void free(void*)"               → return="void", name="free", args=["void*"]
//   "int printf(char*, ...)"         → return="int", name="printf", args=["char*"], variadic=true
//   "void __android_log_print(int, const char*, ...)" → variadic=true
ParsedSignature CallingConvention::parseSignature(const std::string& sig) {
    ParsedSignature result;
    std::string s = trim(sig);
    if (s.empty()) return result;

    // Find the opening paren
    size_t parenPos = s.find('(');
    if (parenPos == std::string::npos) {
        // No args: "void func" or just "func"
        result.func_name = s;
        result.valid = true;
        result.is_void_return = false;
        return result;
    }

    // Find matching closing paren
    size_t closePos = s.rfind(')');
    if (closePos == std::string::npos || closePos <= parenPos) return result;

    // Before the paren: "return_type func_name"
    std::string before = trim(s.substr(0, parenPos));
    std::string argsStr = trim(s.substr(parenPos + 1, closePos - parenPos - 1));

    // Split "return_type func_name" — name is the last token
    // Handle cases like "void*", "const char*", "unsigned int", etc.
    // The function name is the last identifier-like token
    size_t nameStart = before.find_last_of(" \t*");
    if (nameStart == std::string::npos) {
        result.func_name = before;
        result.return_type = "";
    } else {
        // Find the last identifier (starts with letter or _)
        size_t i = before.size();
        while (i > 0 && (std::isalnum((unsigned char)before[i-1]) || before[i-1] == '_'))
            i--;
        result.func_name = before.substr(i);
        result.return_type = trim(before.substr(0, i));
    }

    // Parse arguments
    if (argsStr.empty() || argsStr == "void") {
        // No arguments
        result.valid = true;
    } else {
        auto args = split(argsStr, ',');
        for (auto& arg : args) {
            arg = trim(arg);
            if (arg == "...") {
                result.is_variadic = true;
            } else if (!arg.empty()) {
                result.args.push_back(arg);
            }
        }
        result.valid = true;
    }

    // Check return type
    if (result.return_type == "void" || result.return_type.empty()) {
        result.is_void_return = (result.return_type == "void");
    }

    return result;
}

int CallingConvention::countArgsFromSignature(const std::string& sig) {
    if (sig.empty()) return -1;
    auto parsed = parseSignature(sig);
    if (!parsed.valid) return -1;
    return parsed.argCount();
}

// ── Liveness analysis: find which arg registers are defined before the call ──
std::set<int> CallingConvention::getLiveArgRegs(const MicroInsn* callInsn,
                                                   const MicroBlock* block,
                                                   bool isAArch64) {
    std::set<int> liveRegs;
    int maxArgs = isAArch64 ? MAX_ARGS_AARCH64 : MAX_ARGS_ARM32;

    if (!callInsn || !block) return liveRegs;

    // Scan backwards through the block's instructions (linked list)
    // to find which argument registers were explicitly defined
    for (MicroInsn* insn = block->head; insn; insn = insn->next) {
        if (insn == callInsn) continue;  // Skip the call itself
        // Only scan instructions before the call
        // (We scan the whole block; the call is typically at the end)

        // Check if this instruction defines an argument register
        if (insn->def_mreg >= 100 && insn->def_mreg < 100 + maxArgs) {
            liveRegs.insert(insn->def_mreg);
        }

        // Also check the 'd' field for loads that define registers
        if (insn->d.isReg() && insn->d.mreg >= 100 && insn->d.mreg < 100 + maxArgs) {
            liveRegs.insert(insn->d.mreg);
        }
    }

    return liveRegs;
}

int CallingConvention::countArgsFromLiveness(const MicroInsn* callInsn,
                                               const MicroBlock* block,
                                               bool isAArch64) {
    // v9.8: noreturn functions (abort, exit, __builtin_trap, etc.) take 0 args
    // regardless of liveness — the registers are live from previous operations,
    // not because they're arguments to the noreturn call.
    if (callInsn->call_info && !callInsn->call_info->has_return) {
        return 0;
    }
    
    auto liveRegs = getLiveArgRegs(callInsn, block, isAArch64);
    const int* argRegs = isAArch64 ? ARG_REGS_AARCH64 : ARG_REGS_ARM32;
    int maxArgs = isAArch64 ? MAX_ARGS_AARCH64 : MAX_ARGS_ARM32;

    // Count consecutive live registers from x0
    // If x0, x1, x2 are live but x3 is not, assume 3 args
    // Even if x0 is not explicitly set, it might be a parameter passed through
    int count = 0;
    for (int i = 0; i < maxArgs; i++) {
        if (liveRegs.count(argRegs[i])) {
            count = i + 1;
        }
    }

    // If no registers are live, assume at least 1 (x0 might be passed through)
    if (count == 0 && !liveRegs.empty()) {
        count = 1;
    }

    return count;
}

// ════════════════════════════════════════════════════════════════════
// v5.8: r2-style callee-based parameter count inference
// 对标 r2 fcn_call_type + r_type_func_args_count:
// When a function calls a known library function (e.g., malloc, memcpy),
// the argument registers at that call site must have been set up.
// If they were set up by loading from the function's own parameters
// (ssa_ver==0), then those parameters are actual function args.
// Algorithm: scan all CALL sites, look up callee's known arg count,
// check if the arg registers at that call site are used with ssa_ver==0
// (meaning they came directly from the caller). Take the max across all
// call sites.
// ════════════════════════════════════════════════════════════════════
int CallingConvention::inferParamCountFromCallees(const MicrocodeBlockArray& mba,
                                                     bool isAArch64) {
    int maxInferred = -1;
    int maxArgs = isAArch64 ? MAX_ARGS_AARCH64 : MAX_ARGS_ARM32;
    const int* argRegs = isAArch64 ? ARG_REGS_AARCH64 : ARG_REGS_ARM32;

    for (auto& blk : mba.blocks) {
        if (!blk) continue;
        for (MicroInsn* insn = blk->head; insn; insn = insn->next) {
            if (insn->opcode != OP_CALL && insn->opcode != OP_ICALL) continue;
            if (insn->target_addr == 0) continue;

            // Look up callee name
            std::string calleeName;
            if (insn->call_info && !insn->call_info->target_name.empty()) {
                calleeName = insn->call_info->target_name;
            } else {
                auto nameIt = mba.global_names.find(insn->target_addr);
                if (nameIt == mba.global_names.end()) continue;
                calleeName = nameIt->second;
            }
            if (calleeName.empty()) continue;

            // Get known param count for this callee
            auto paramTypes = getParamTypesForCallee(calleeName);
            if (paramTypes.empty()) continue;
            int calleeArgCount = 0;
            for (auto& pt : paramTypes) {
                if (pt != "...") calleeArgCount++;
            }
            if (calleeArgCount == 0) continue;

            // Check which arg registers at this call site have ssa_ver==0
            // (meaning they came directly from the function's caller)
            int directParams = 0;
            for (int i = 0; i < calleeArgCount && i < maxArgs; i++) {
                int argMreg = argRegs[i];
                // Scan backwards from the call to find the definition of this register
                bool isDirectParam = false;
                MicroInsn* prev = insn->prev;
                while (prev) {
                    if (prev->def_mreg == argMreg) {
                        // Check if this definition is a MOV from ssa_ver==0
                        // (i.e., direct pass-through of the parameter)
                        if (prev->opcode == OP_MOV && prev->l.isReg() &&
                            prev->l.ssa_ver == 0 && prev->l.mreg == argMreg) {
                            isDirectParam = true;
                        }
                        break;
                    }
                    prev = prev->prev;
                }
                // Also check if the register is used directly with ssa_ver==0
                // at the call site (no intermediate definition at all)
                if (!isDirectParam) {
                    // Check if the register is used with ssa_ver==0 somewhere
                    // in this block before the call
                    for (MicroInsn* scan = blk->head; scan && scan != insn; scan = scan->next) {
                        if (scan->l.isReg() && scan->l.mreg == argMreg && scan->l.ssa_ver == 0) {
                            isDirectParam = true;
                            break;
                        }
                        if (scan->r.isReg() && scan->r.mreg == argMreg && scan->r.ssa_ver == 0) {
                            isDirectParam = true;
                            break;
                        }
                        // If the register was redefined, stop looking
                        if (scan->def_mreg == argMreg && scan->opcode != OP_MOV) {
                            break;
                        }
                    }
                }
                if (isDirectParam) {
                    directParams = i + 1;
                }
            }

            if (directParams > maxInferred) {
                maxInferred = directParams;
            }
        }
    }

    return maxInferred;
}

// ════════════════════════════════════════════════════════════════════
// v5.8: r2-style combined parameter count
// 对标 r2 r_anal_function_set_nargs:
// r2 determines nargs from multiple sources in priority order:
//   1. Function type signature (from demangled name / sdb_types)
//   2. Callee-based inference (from known callees in the body)
//   3. Register liveness (registers read before written)
//   4. Calling convention max args
// ════════════════════════════════════════════════════════════════════
int CallingConvention::getFunctionParamCount(const MicrocodeBlockArray& mba,
                                               const std::string& funcName,
                                               bool isAArch64) {
    int maxArgs = isAArch64 ? MAX_ARGS_AARCH64 : MAX_ARGS_ARM32;

    // Priority 0: Demangled C++ signature (对标 r2 r_type_func_guess)
    // Extract parameter count from demangled names like "Class::method(int, char*, long)"
    if (!funcName.empty() && funcName.find('(') != std::string::npos) {
        auto parsed = parseSignature(funcName);
        if (parsed.valid) {
            int count = 0;
            for (auto& arg : parsed.args) {
                if (arg != "...") count++;
            }
            // v8.5: Return 0 for valid empty params too (e.g., "void foo()").
            // Previously we only returned for count > 0, which caused empty
            // param lists to fall through to SSA liveness, overcounting.
            if (count <= maxArgs) {
                return count;
            }
        }
    }

    // Priority 1: Known function signature from name
    if (!funcName.empty()) {
        if (funcName == "JNI_OnLoad" || funcName == "JNI_OnUnload") {
            return 1;  // JavaVM*
        }
        if (funcName.substr(0, 5) == "Java_") {
            int ssaCount = countFunctionParams(mba, isAArch64);
            return (ssaCount > 2) ? ssaCount : 2;
        }
        auto paramTypes = getParamTypesForCallee(funcName);
        if (!paramTypes.empty()) {
            int count = 0;
            for (auto& pt : paramTypes) {
                if (pt != "...") count++;
            }
            return count;
        }
    }

    // Priority 2: Callee-based inference
    // 对标 r2 fcn_call_type: infer from what the function calls
    int calleeInferred = inferParamCountFromCallees(mba, isAArch64);

    // Priority 3: SSA liveness (registers read before defined)
    int ssaCount = countFunctionParams(mba, isAArch64);

    // v9.5: 栈参数检测 (对标 r2 anal_arm_cs.c + Ghidra StackVariableAnalyzer)
    // 如果 SSA 活跃性分析超过了寄存器参数上限，说明有栈参数。
    // 检测函数入口处的 [sp, #positive_offset] 加载来确定栈参数数量。
    int stackParamCount = 0;
    if (ssaCount > maxArgs) {
        stackParamCount = ssaCount - maxArgs;
        // 探测栈参数：在入口块(第一个块)中寻找 SP/FP 正偏移加载
        std::set<int> stackOffsets;
        if (!mba.blocks.empty() && mba.blocks[0]) {
            MicroInsn* insn = mba.blocks[0]->head;
            for (; insn; insn = insn->next) {
                // 检查是否从栈上加载参数: ldr rN, [sp, #off] 或 ldr xN, [x29, #off]
                if (insn->opcode == OP_LOAD && insn->l.isMem()) {
                    int base = insn->l.mem_base;
                    int offset = insn->l.mem_offset;
                    // SP (mreg 131) 或 FP (x29, mreg 129) 正偏移
                    if ((base == 131 || base == 129) && offset > 0) {
                        // 排除保存的 LR/FP 区域 (这些通常是负偏移或 frame setup)
                        // 正偏移表示从调用者的栈帧读取参数
                        stackOffsets.insert(offset);
                    }
                }
            }
        }
        // 如果入口块中检测到的栈偏移数量 >= stackParamCount，确认
        if ((int)stackOffsets.size() >= stackParamCount / 2) {
            // 栈参数确认
        } else {
            // 没有足够的栈访问证据，限制为寄存器上限
            stackParamCount = 0;
        }
    }

    // Take the max of callee inference and SSA liveness
    int result = std::max(calleeInferred, ssaCount);

    // v9.5: 不硬限制为 maxArgs，允许栈参数
    // Priority 4: Cap at calling convention max + stack params
    if (stackParamCount > 0) {
        result = maxArgs + stackParamCount;
    } else if (result > maxArgs) {
        result = maxArgs;
    }
    if (result < 0) result = 0;

    return result;
}

int CallingConvention::determineArgCount(const std::string& calleeName,
                                          const std::map<std::string, std::string>& sigMap,
                                          const MicroInsn* callInsn,
                                          const MicroBlock* block,
                                          bool isAArch64) {
    // Priority 1: Check signature map
    if (!calleeName.empty() && !sigMap.empty()) {
        auto it = sigMap.find(calleeName);
        if (it != sigMap.end()) {
            int n = countArgsFromSignature(it->second);
            if (n >= 0) return n;
        }
    }

    // Priority 2: Liveness analysis
    if (callInsn && block) {
        int n = countArgsFromLiveness(callInsn, block, isAArch64);
        if (n > 0) return n;
    }

    // Priority 3: Default — assume 4 args (common for AArch64)
    return isAArch64 ? 4 : 4;
}

bool CallingConvention::returnsVoid(const std::string& sig) {
    if (sig.empty()) return false;
    auto parsed = parseSignature(sig);
    return parsed.valid && parsed.is_void_return;
}

// ── v3.8: Determine which argument registers are actual parameters ──
// After SSA renaming, a register read before any definition has ssa_ver == 0.
// If an arg register is used with ssa_ver == 0, it's a parameter from the caller.
// This implements fcd pass_argrec's "live on entry" approach using SSA.
std::set<int> CallingConvention::getFunctionParams(const MicrocodeBlockArray& mba,
                                                      bool isAArch64) {
    std::set<int> params;
    int maxArgs = isAArch64 ? MAX_ARGS_AARCH64 : MAX_ARGS_ARM32;
    const int* argRegs = isAArch64 ? ARG_REGS_AARCH64 : ARG_REGS_ARM32;

    // v3.22: A register is a parameter if it's used with ssa_ver==0 anywhere,
    // OR if a PHI node has a source with ssa_ver==0 on an arg register.
    // PHI nodes are stored in blk->phi_nodes (not in the instruction chain).
    auto checkMop = [&](const Mop& m) {
        if (m.isReg() && m.ssa_ver == 0) {
            for (int i = 0; i < maxArgs; i++) {
                if (m.mreg == argRegs[i]) {
                    params.insert(m.mreg);
                }
            }
        }
    };

    // v3.22: Check PHI sources for ssa_ver==0 (parameter from caller)
    // PHI nodes at loop headers merge the parameter version (0) with
    // loop-carried versions, so the cmp uses the PHI output (version > 0),
    // not the original parameter (version 0).
    for (auto& blk_up : mba.blocks) {
        if (!blk_up) continue;
        for (MicroInsn* phi : blk_up->phi_nodes) {
            if (!phi || phi->opcode != OP_PHI) continue;
            for (auto& [srcMreg, srcVer] : phi->l.phi_srcs) {
                if (srcVer == 0) {
                    for (int i = 0; i < maxArgs; i++) {
                        if (srcMreg == argRegs[i]) {
                            params.insert(srcMreg);
                        }
                    }
                }
            }
        }
    }

    for (auto& blk_up : mba.blocks) {
        if (!blk_up) continue;
        for (MicroInsn* insn = blk_up->head; insn; insn = insn->next) {
            checkMop(insn->l);
            checkMop(insn->r);
            // v8.5: Also check memory base registers for arg regs.
            // LOAD/STORE instructions use MOP_MEM for the address, whose
            // base register is a use of the arg register (e.g., "ldr r0, [r0, #0x44]"
            // uses r0 as the memory base). The SSA pass does not rename memory
            // bases, so we check the mreg directly instead of ssa_ver.
            if (insn->l.isMem() && insn->l.mem_base >= 0) {
                for (int i = 0; i < maxArgs; i++) {
                    if (insn->l.mem_base == argRegs[i]) {
                        params.insert(insn->l.mem_base);
                    }
                }
            }
            if (insn->d.isMem() && insn->d.mem_base >= 0) {
                for (int i = 0; i < maxArgs; i++) {
                    if (insn->d.mem_base == argRegs[i]) {
                        params.insert(insn->d.mem_base);
                    }
                }
            }
        }
    }

    // v9.7: Detect pass-through parameters from CALL instructions.
    // When a function calls another function, the argument registers
    // (r0-r3 / x0-x7) that are NOT defined in this function body are
    // pass-through parameters from the caller. These are not captured
    // by the ssa_ver==0 scan because they're never explicitly read in
    // the microcode — they flow directly into the callee.
    // Build a set of all registers defined in this function
    std::set<int> definedRegs;
    for (auto& blk_up : mba.blocks) {
        if (!blk_up) continue;
        for (MicroInsn* insn = blk_up->head; insn; insn = insn->next) {
            if (insn->def_mreg >= 0) {
                definedRegs.insert(insn->def_mreg);
            }
        }
    }

    // Scan CALL instructions: any arg register that is forward-declared
    // as an argument to a call but never defined in this function is
    // a pass-through parameter.
    for (auto& blk_up : mba.blocks) {
        if (!blk_up) continue;
        for (MicroInsn* insn = blk_up->head; insn; insn = insn->next) {
            if (insn->opcode != OP_CALL && insn->opcode != OP_ICALL) continue;
            int callArgCount = maxArgs;  // conservatively assume max args
            if (insn->call_info && insn->call_info->arg_count > 0) {
                callArgCount = insn->call_info->arg_count;
            }
            if (callArgCount > maxArgs) callArgCount = maxArgs;
            for (int i = 0; i < callArgCount; i++) {
                int argMreg = argRegs[i];
                // If this register is not defined in the function,
                // it's a pass-through parameter from the caller.
                if (!definedRegs.count(argMreg)) {
                    params.insert(argMreg);
                }
            }
        }
    }

    return params;
}

int CallingConvention::countFunctionParams(const MicrocodeBlockArray& mba,
                                              bool isAArch64) {
    auto params = getFunctionParams(mba, isAArch64);

    // Count consecutive params from arg0.
    // If arg0 is not a param but arg1 is, we still include arg0
    // (ABI requires consecutive params).
    int maxArgs = isAArch64 ? MAX_ARGS_AARCH64 : MAX_ARGS_ARM32;
    const int* argRegs = isAArch64 ? ARG_REGS_AARCH64 : ARG_REGS_ARM32;

    int count = 0;
    for (int i = 0; i < maxArgs; i++) {
        if (params.count(argRegs[i])) {
            count = i + 1;
        }
    }

    return count;
}

// ════════════════════════════════════════════════════════════════════
// v5.6/v8.8: PLT symbol name → parameter type mapping
// 对标 r2 libr/anal/p/anal_arm_cs.c get_reg_type() + R2 sdb_types.
// r2 uses the PLT stub name to infer the argument register types
// at the call site. E.g., calling malloc(x0) → x0 is size_t.
// v8.8: 扩展为包含返回类型的完整签名数据库 (对标 Ghidra FuncPrototype).
// ════════════════════════════════════════════════════════════════════

// Internal: full signature record {param types, return type}
struct KnownFuncSig {
    std::vector<std::string> params;
    std::string ret;  // return type (empty = unknown)
    KnownFuncSig() = default;
    KnownFuncSig(std::vector<std::string> p, const char* r) : params(std::move(p)), ret(r) {}
    KnownFuncSig(std::vector<std::string> p, const std::string& r) : params(std::move(p)), ret(r) {}
};

// Helper to build a {name, KnownFuncSig} pair unambiguously (avoids
// brace-elision ambiguity when the param list has 1 element).
static std::pair<std::string, KnownFuncSig> sig(const char* name,
                                                  std::vector<std::string> params,
                                                  const char* ret) {
    return {name, KnownFuncSig(std::move(params), ret)};
}

// Shared signature database (对标 R2 sdb_types + Ghidra CParser builtins)
static const std::map<std::string, KnownFuncSig>& knownFuncDatabase() {
    static const std::map<std::string, KnownFuncSig> db = {
        // libc memory
        sig("malloc", {"size_t"}, "void*"),
        sig("calloc", {"size_t", "size_t"}, "void*"),
        sig("realloc", {"void*", "size_t"}, "void*"),
        sig("free", {"void*"}, "void"),
        sig("memcpy", {"void*", "const void*", "size_t"}, "void*"),
        sig("memmove", {"void*", "const void*", "size_t"}, "void*"),
        sig("memset", {"void*", "int", "size_t"}, "void*"),
        sig("memcmp", {"const void*", "const void*", "size_t"}, "int"),
        sig("memchr", {"const void*", "int", "size_t"}, "void*"),
        sig("alloca", {"size_t"}, "void*"),
        // libc string
        sig("strcpy", {"char*", "const char*"}, "char*"),
        sig("strncpy", {"char*", "const char*", "size_t"}, "char*"),
        sig("strcat", {"char*", "const char*"}, "char*"),
        sig("strncat", {"char*", "const char*", "size_t"}, "char*"),
        sig("strlen", {"const char*"}, "size_t"),
        sig("strcmp", {"const char*", "const char*"}, "int"),
        sig("strncmp", {"const char*", "const char*", "size_t"}, "int"),
        sig("strchr", {"const char*", "int"}, "char*"),
        sig("strrchr", {"const char*", "int"}, "char*"),
        sig("strstr", {"const char*", "const char*"}, "char*"),
        sig("strtok", {"char*", "const char*"}, "char*"),
        sig("strdup", {"const char*"}, "char*"),
        sig("atoi", {"const char*"}, "int"),
        sig("atol", {"const char*"}, "long"),
        sig("atoll", {"const char*"}, "long long"),
        sig("atof", {"const char*"}, "double"),
        sig("strtol", {"const char*", "char**", "int"}, "long"),
        sig("strtoul", {"const char*", "char**", "int"}, "unsigned long"),
        sig("strtod", {"const char*", "char**"}, "double"),
        // libc stdio
        sig("printf", {"const char*"}, "int"),
        sig("fprintf", {"void*", "const char*"}, "int"),
        sig("sprintf", {"char*", "const char*"}, "int"),
        sig("snprintf", {"char*", "size_t", "const char*"}, "int"),
        sig("puts", {"const char*"}, "int"),
        sig("fputs", {"const char*", "void*"}, "int"),
        sig("fopen", {"const char*", "const char*"}, "void*"),
        sig("fclose", {"void*"}, "int"),
        sig("fread", {"void*", "size_t", "size_t", "void*"}, "size_t"),
        sig("fwrite", {"const void*", "size_t", "size_t", "void*"}, "size_t"),
        sig("fseek", {"void*", "long", "int"}, "int"),
        sig("ftell", {"void*"}, "long"),
        sig("fgets", {"char*", "int", "void*"}, "char*"),
        sig("fgetc", {"void*"}, "int"),
        sig("getc", {"void*"}, "int"),
        sig("getchar", {}, "int"),
        sig("putchar", {"int"}, "int"),
        // libc process / stdlib
        sig("abort", {}, "void"),
        sig("exit", {"int"}, "void"),
        sig("_exit", {"int"}, "void"),
        sig("atexit", {"void*"}, "int"),
        sig("system", {"const char*"}, "int"),
        sig("getenv", {"const char*"}, "char*"),
        sig("rand", {}, "int"),
        sig("srand", {"unsigned int"}, "void"),
        sig("abs", {"int"}, "int"),
        sig("qsort", {"void*", "size_t", "size_t", "void*"}, "void"),
        sig("bsearch", {"const void*", "const void*", "size_t", "size_t", "void*"}, "void*"),
        // libc time
        sig("time", {"long*"}, "long"),
        sig("clock", {}, "long"),
        // pthread
        sig("pthread_create", {"void*", "void*", "void*", "void*"}, "int"),
        sig("pthread_join", {"void*", "void**"}, "int"),
        sig("pthread_mutex_init", {"void*", "void*"}, "int"),
        sig("pthread_mutex_lock", {"void*"}, "int"),
        sig("pthread_mutex_unlock", {"void*"}, "int"),
        sig("pthread_mutex_destroy", {"void*"}, "int"),
        sig("pthread_self", {}, "unsigned long"),
        sig("pthread_detach", {"void*"}, "int"),
        sig("pthread_exit", {"void*"}, "void"),
        // Android
        sig("dlopen", {"const char*", "int"}, "void*"),
        sig("dlsym", {"void*", "const char*"}, "void*"),
        sig("dlclose", {"void*"}, "int"),
        sig("dlerror", {}, "char*"),
        sig("__system_property_get", {"const char*", "char*"}, "int"),
        sig("__android_log_print", {"int", "const char*", "const char*"}, "int"),
        sig("__android_log_write", {"int", "const char*", "const char*"}, "int"),
        // errno
        sig("__errno", {}, "int*"),
        sig("__errno_location", {}, "int*"),
        // JNI (when called indirectly through env table)
        sig("GetEnv", {"void**", "int"}, "int"),
        sig("FindClass", {"void*", "const char*"}, "void*"),
        sig("GetMethodID", {"void*", "const char*", "const char*"}, "void*"),
        sig("GetFieldID", {"void*", "const char*", "const char*"}, "void*"),
        sig("NewStringUTF", {"void*", "const char*"}, "void*"),
        sig("GetStringUTFChars", {"void*", "void*", "char*"}, "const char*"),
        sig("ReleaseStringUTFChars", {"void*", "void*", "const char*"}, "void"),
        sig("CallVoidMethod", {"void*", "void*", "..."}, "void"),
        sig("CallIntMethod", {"void*", "void*", "..."}, "int"),
        sig("CallBooleanMethod", {"void*", "void*", "..."}, "bool"),
        sig("CallObjectMethod", {"void*", "void*", "..."}, "void*"),
        sig("NewGlobalRef", {"void*", "void*"}, "void*"),
        sig("DeleteLocalRef", {"void*", "void*"}, "void"),
        sig("DeleteGlobalRef", {"void*", "void*"}, "void"),
        sig("RegisterNatives", {"void*", "void*", "int"}, "int"),
        sig("GetJavaVM", {"void*", "void**"}, "int"),
    };
    return db;
}

std::vector<std::string> CallingConvention::getParamTypesForCallee(const std::string& calleeName) {
    const auto& db = knownFuncDatabase();
    auto it = db.find(calleeName);
    if (it != db.end()) return it->second.params;
    return {};
}

// v8.8: Get return type for a known function (对标 R2 sdb_types 返回类型)
std::string CallingConvention::getReturnTypeForCallee(const std::string& calleeName) {
    const auto& db = knownFuncDatabase();
    auto it = db.find(calleeName);
    if (it != db.end()) return it->second.ret;
    return "";
}

// ════════════════════════════════════════════════════════════════════
// v8.8: Parse a demangled C++ signature into scope/method/args.
// 对标 R2 r_type_func_guess + Ghidra DemangledParamParser.
// Handles: "ns::Class::method(int, char*, const Foo&)"
//   → scope="ns::Class::", method="method", args=[int, char*, const Foo&]
// Also handles plain "func(int, int)" with empty scope.
// ════════════════════════════════════════════════════════════════════
CallingConvention::DemangledSig CallingConvention::parseDemangledCpp(const std::string& name) {
    DemangledSig result;
    size_t parenPos = name.find('(');
    if (parenPos == std::string::npos) {
        // No parameter list — treat whole thing as method name, no args.
        result.method = trim(name);
        result.valid = true;
        return result;
    }
    size_t closePos = name.rfind(')');
    if (closePos == std::string::npos || closePos <= parenPos) return result;

    std::string before = trim(name.substr(0, parenPos));
    std::string argsStr = trim(name.substr(parenPos + 1, closePos - parenPos - 1));

    // Split scope::method. The method name is the last token after "::".
    size_t scopeEnd = before.rfind("::");
    if (scopeEnd != std::string::npos) {
        result.scope = before.substr(0, scopeEnd + 2);  // include trailing "::"
        result.method = before.substr(scopeEnd + 2);
    } else {
        result.scope = "";
        result.method = before;
    }

    // Parse args (respecting nested template/paren depth via split()).
    if (!argsStr.empty() && argsStr != "void") {
        for (auto& arg : split(argsStr, ',')) {
            arg = trim(arg);
            if (arg == "...") {
                result.args.push_back("...");
            } else if (!arg.empty()) {
                result.args.push_back(arg);
            }
        }
    }
    result.valid = true;
    return result;
}

// ════════════════════════════════════════════════════════════════════
// v8.8: Infer a function's return type from its body.
// 对标 Ghidra ActionReturnPrototype / FuncProto::resolveReturn:
// When the function body contains "return <call to known function>"
// where the call's result flows directly to the return register (x0/r0),
// inherit that callee's return type.
// Algorithm: scan all call points; if a call defines RET_MREG (x0/r0)
// and no later instruction in the same block redefines RET_MREG with a
// non-call op (so the value flows to a return), use the callee's return type.
// ════════════════════════════════════════════════════════════════════
std::string CallingConvention::inferReturnTypeFromBody(const MicrocodeBlockArray& mba,
                                                         bool isAArch64) {
    const int RET_MREG = 100;  // x0 / r0
    std::string inferred;

    for (auto& blk : mba.blocks) {
        if (!blk) continue;
        for (MicroInsn* insn = blk->head; insn; insn = insn->next) {
            // Look for a CALL/ICALL whose result is defined into RET_MREG.
            if (insn->opcode != OP_CALL && insn->opcode != OP_ICALL) continue;
            if (insn->target_addr == 0) continue;
            // The call must define the return register.
            if (insn->def_mreg != RET_MREG) continue;

            // Resolve callee name.
            std::string calleeName;
            if (insn->call_info && !insn->call_info->target_name.empty()) {
                calleeName = insn->call_info->target_name;
            } else {
                auto it = mba.global_names.find(insn->target_addr);
                if (it == mba.global_names.end()) continue;
                calleeName = it->second;
            }
            if (calleeName.empty()) continue;

            // Ensure RET_MREG is not redefined before the block's end with a
            // non-call op (i.e., the call result flows to the return).
            bool clobbered = false;
            for (MicroInsn* after = insn->next; after; after = after->next) {
                if (after->def_mreg == RET_MREG) {
                    // Another call that defines x0 is fine (different path),
                    // but a MOV/arithmetic op means the value was transformed.
                    if (after->opcode != OP_CALL && after->opcode != OP_ICALL) {
                        clobbered = true;
                    }
                    break;
                }
            }
            if (clobbered) continue;

            std::string retType = getReturnTypeForCallee(calleeName);
            if (!retType.empty() && retType != "void") {
                // First inferred non-void return type wins.
                if (inferred.empty()) inferred = retType;
            }
        }
    }
    return inferred;
}

// ════════════════════════════════════════════════════════════════════
// v9.5: Infer parameter types from all call sites in the function
// 对标 r2 anal_arm_cs.c + r_type_func_guess:
// 投票制冲突裁决 — 同一参数寄存器被不同 callee 推断为不同类型时，
// 取出现次数最多的类型。如果平局，优先选择更具体的类型（如 strlen 的
// const char* 优先于 memset 的 void*）。
// ════════════════════════════════════════════════════════════════════
std::map<int, InferredParamType> CallingConvention::inferParamTypesFromCallees(
    const MicrocodeBlockArray& mba,
    bool isAArch64) {

    int maxArgs = isAArch64 ? MAX_ARGS_AARCH64 : MAX_ARGS_ARM32;
    const int* argRegs = isAArch64 ? ARG_REGS_AARCH64 : ARG_REGS_ARM32;

    // Phase 1: 收集所有推断 (mreg → {type → count, source})
    struct TypeVote {
        int count = 0;
        std::string source;
        int specificity = 0;  // 越高越具体 (const char* = 4, char* = 3, void* = 2, int = 1)
    };
    std::map<int, std::map<std::string, TypeVote>> votes;

    // 类型具体性评分
    auto typeSpecificity = [](const std::string& t) -> int {
        if (t == "const char*") return 5;
        if (t == "char*") return 4;
        if (t == "FILE*") return 4;
        if (t.find("struct") != std::string::npos) return 4;
        if (t == "void*") return 3;
        if (t == "size_t") return 2;
        if (t == "unsigned long") return 2;
        if (t == "long") return 2;
        if (t == "int") return 1;
        if (t == "unsigned int") return 1;
        return 0;
    };

    for (auto& blk : mba.blocks) {
        if (!blk) continue;
        for (MicroInsn* insn = blk->head; insn; insn = insn->next) {
            if (insn->opcode != OP_CALL && insn->opcode != OP_ICALL) continue;
            if (insn->target_addr == 0) continue;

            auto nameIt = mba.global_names.find(insn->target_addr);
            if (nameIt == mba.global_names.end()) continue;
            const std::string& calleeName = nameIt->second;
            if (calleeName.empty()) continue;

            auto paramTypes = getParamTypesForCallee(calleeName);
            if (paramTypes.empty()) continue;

            for (int i = 0; i < (int)paramTypes.size() && i < maxArgs; i++) {
                int mreg = argRegs[i];
                if (paramTypes[i] == "...") continue;

                auto& tv = votes[mreg][paramTypes[i]];
                tv.count++;
                if (tv.source.empty()) tv.source = calleeName;
                tv.specificity = typeSpecificity(paramTypes[i]);
            }
        }
    }

    // Phase 2: 投票裁决
    std::map<int, InferredParamType> result;
    for (auto& [mreg, typeVotes] : votes) {
        std::string bestType;
        int bestScore = 0;  // score = count * 10 + specificity (tiebreaker)

        for (auto& [type, tv] : typeVotes) {
            int score = tv.count * 10 + tv.specificity;
            if (score > bestScore) {
                bestScore = score;
                bestType = type;
            }
        }

        if (!bestType.empty()) {
            InferredParamType ipt;
            ipt.mreg = mreg;
            ipt.ctype = bestType;
            ipt.source = typeVotes[bestType].source;
            result[mreg] = ipt;
        }
    }

    return result;
}

// v7.0/v8.8: Infer parameter types from demangled C++ function name
// 对标 R2 r_type_func_guess: extract params from demangled signature.
// v8.8: 使用 parseDemangledCpp 解析 ClassName::method(int, char*) 形式，
// 正确剥离类作用域，只取方法名后的参数列表。
std::map<int, InferredParamType> CallingConvention::inferParamTypesFromDemangled(
    const std::string& funcName,
    bool isAArch64) {

    std::map<int, InferredParamType> result;
    if (funcName.empty() || funcName.find('(') == std::string::npos) return result;

    // v8.8: 优先使用更健壮的 C++ demangled 解析器
    auto demangled = parseDemangledCpp(funcName);
    if (!demangled.valid || demangled.args.empty()) {
        // 回退到通用 C 签名解析
        auto parsed = parseSignature(funcName);
        if (!parsed.valid || parsed.args.empty()) return result;

        int maxArgs = isAArch64 ? MAX_ARGS_AARCH64 : MAX_ARGS_ARM32;
        const int* argRegs = isAArch64 ? ARG_REGS_AARCH64 : ARG_REGS_ARM32;

        for (int i = 0; i < (int)parsed.args.size() && i < maxArgs; i++) {
            const std::string& argType = parsed.args[i];
            if (argType == "..." || argType.empty()) continue;
            int mreg = argRegs[i];

            InferredParamType ipt;
            ipt.mreg = mreg;
            ipt.ctype = argType;
            ipt.source = funcName;
            result[mreg] = ipt;
        }
        return result;
    }

    int maxArgs = isAArch64 ? MAX_ARGS_AARCH64 : MAX_ARGS_ARM32;
    const int* argRegs = isAArch64 ? ARG_REGS_AARCH64 : ARG_REGS_ARM32;

    for (int i = 0; i < (int)demangled.args.size() && i < maxArgs; i++) {
        const std::string& argType = demangled.args[i];
        if (argType == "..." || argType.empty()) continue;
        int mreg = argRegs[i];

        InferredParamType ipt;
        ipt.mreg = mreg;
        ipt.ctype = argType;
        // 记录完整作用域+方法名作为来源
        ipt.source = demangled.scope + demangled.method;
        result[mreg] = ipt;
    }

    return result;
}

// ════════════════════════════════════════════════════════════════════
// v10.0: Calling convention auto-detection
// Detects the calling convention from microcode instruction patterns.
//
// Strategy:
//   - AArch64: always AAPCS64 (x0-x7 args, x0 return)
//   - ARM32 ARM mode: AAPCS (r0-r3 args)
//   - ARM32 Thumb vs Thumb2: distinguished by instruction width.
//     Thumb1 = 2-byte instructions only; Thumb2 = has 4-byte instructions.
//     We use source assembly string heuristics from the microcode to detect:
//       * Thumb1:  push {lr} (16-bit encoding), narrow instructions
//       * Thumb2:  movw, movt, ldr.w, str.w, .w/.n suffixes (32-bit encodings)
// ════════════════════════════════════════════════════════════════════

// Check if a source assembly string looks like a Thumb2 (32-bit) instruction.
// Thumb2 instructions use .w suffix or are known 32-bit encodings.
static bool isThumb2Insn(const std::string& asmStr) {
    // Check for .w suffix (e.g., "ldr.w", "str.w", "mov.w", "add.w", "sub.w")
    if (asmStr.find(".w") != std::string::npos) return true;
    // Check for .n suffix (explicit narrow, confirms mixed Thumb1/Thumb2)
    if (asmStr.find(".n") != std::string::npos) return true;
    // Known 32-bit Thumb2 instructions
    static const char* thumb2Patterns[] = {
        "movw", "movt",  // Wide literal load (32-bit)
        "mov32",          // Pseudo for movw+movt pair
        "ldr.w", "str.w",
        "ldr", ".w",      // Generic wide load/store
        "vldr", "vstr",   // VFP load/store (always 32-bit in Thumb)
        "vmov", "vmrs", "vmsr",  // VFP move/system (32-bit)
        "blx",            // Interworking call (32-bit in Thumb)
        "cbz", "cbnz",    // Compare and branch (32-bit in Thumb2)
        "it ", "itt", "ite", "ittt", "itee", "itet", "eitt", "eite",  // IT blocks
        "tbb", "tbh",     // Table branch
        "dmb", "dsb", "isb",  // Memory barriers
        "cpsie", "cpsid",  // Change processor state
        "mrs", "msr",      // System register access (32-bit Thumb)
        "ldr.w", "str.w", "add.w", "sub.w", "cmp.w",
        "mov.w", "and.w", "orr.w", "eor.w", "lsl.w", "lsr.w",
        "asr.w", "ror.w", "bic.w", "mvn.w", "tst.w", "teq.w",
    };
    std::string lower = asmStr;
    // Convert to lowercase for comparison
    for (auto& c : lower) c = std::tolower((unsigned char)c);

    for (auto pat : thumb2Patterns) {
        // Match as a word boundary: "movw" not inside "cmovw"
        if (lower.find(pat) != std::string::npos) {
            // Ensure it starts at a word boundary
            size_t pos = lower.find(pat);
            if (pos == 0 || !std::isalnum((unsigned char)lower[pos - 1])) {
                return true;
            }
        }
    }
    return false;
}

// Check if a source assembly string looks like a Thumb1 (16-bit) instruction.
// Thumb1 instructions are narrow (2-byte encoded).
static bool isThumb1Insn(const std::string& asmStr) {
    // push {lr} is a classic 16-bit Thumb prologue
    if (asmStr.find("push") != std::string::npos &&
        asmStr.find("{lr}") != std::string::npos) {
        return true;
    }
    // pop {pc} is a classic 16-bit Thumb epilogue
    if (asmStr.find("pop") != std::string::npos &&
        asmStr.find("{pc}") != std::string::npos) {
        return true;
    }
    // Narrow ALU operations without .w suffix
    // These are 16-bit when they operate on low registers only
    static const char* thumb1Patterns[] = {
        "mov r", "add r", "sub r", "cmp r",
        "and r", "orr r", "eor r", "lsl r", "lsr r",
        "asr r", "adc r", "sbc r", "ror r", "mul r",
        "bl ",       // branch with link (note space to avoid matching "blx")
        "bx ",       // branch and exchange
        "b  ",       // branch (note double space pattern)
    };
    std::string lower = asmStr;
    for (auto& c : lower) c = std::tolower((unsigned char)c);

    // Only match if there is NO .w suffix (which would indicate Thumb2)
    if (lower.find(".w") != std::string::npos) return false;

    for (auto pat : thumb1Patterns) {
        if (lower.find(pat) != std::string::npos) {
            return true;
        }
    }
    return false;
}

CallingConventionType CallingConvention::detectCallingConvention(
    const MicrocodeBlockArray& mba,
    bool is_aarch64,
    bool is_thumb) {

    // AArch64: always AAPCS64
    if (is_aarch64) {
        return CC_AARCH64_AAPCS64;
    }

    // ARM32, not Thumb: always standard AAPCS
    if (!is_thumb) {
        return CC_ARM32_AAPCS;
    }

    // ARM32 Thumb mode: need to distinguish Thumb vs Thumb2.
    // Scan all microcode instructions and classify by their source assembly.
    int thumb1Count = 0;
    int thumb2Count = 0;
    int totalInsn = 0;

    for (auto& blk : mba.blocks) {
        if (!blk) continue;
        for (MicroInsn* insn = blk->head; insn; insn = insn->next) {
            if (insn->opcode == OP_NOP) continue;
            ++totalInsn;

            const std::string& asmStr = insn->src_asm;
            if (asmStr.empty()) continue;

            // Check for Thumb2 indicators
            if (isThumb2Insn(asmStr)) {
                ++thumb2Count;
            }

            // Check for Thumb1 indicators
            if (isThumb1Insn(asmStr)) {
                ++thumb1Count;
            }
        }
    }

    // If we found any 32-bit Thumb2 instructions, it's Thumb2.
    // Thumb2 is a superset of Thumb1, so a Thumb2 code region
    // can contain both 2-byte and 4-byte instructions.
    if (thumb2Count > 0) {
        return CC_ARM32_THUMB2;
    }

    // If we only found 16-bit Thumb1 instructions, it's Thumb.
    if (thumb1Count > 0) {
        return CC_ARM32_THUMB;
    }

    // Fallback: if is_thumb is set but we cannot determine,
    // default to Thumb2 (most modern ARM32 code uses Thumb2).
    return CC_ARM32_THUMB2;
}

std::string CallingConvention::ccToString(CallingConventionType cc) {
    switch (cc) {
        case CC_AARCH64_AAPCS64: return "AAPCS64 (AArch64)";
        case CC_ARM32_AAPCS:     return "AAPCS (ARM32)";
        case CC_ARM32_THUMB:      return "AAPCS (Thumb)";
        case CC_ARM32_THUMB2:     return "AAPCS (Thumb-2)";
        case CC_UNKNOWN:          return "Unknown";
    }
    return "Unknown";
}

} // namespace mc
