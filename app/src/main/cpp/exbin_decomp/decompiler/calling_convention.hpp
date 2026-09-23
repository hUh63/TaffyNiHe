// calling_convention.hpp — AArch64/ARM calling convention analysis
// Based on DREAM's calling convention recovery approach.
//
// Determines:
//   1. Number of arguments for a function call (from signature or liveness)
//   2. Return type (from signature)
//   3. Argument registers (x0-x7 for AArch64, r0-r3 for ARM32)
//
// AArch64 AAPCS64:
//   - Arguments: x0-x7 (integer/pointer), v0-v7 (floating point)
//   - Return: x0 (integer/pointer), v0 (floating point)
//   - Callee-saved: x19-x28, x29(fp), x30(lr)
//   - Caller-saved: x0-x18 (except x8 sometimes used as indirect result location)

#pragma once

#include <string>
#include <vector>
#include <set>
#include <map>
#include "microcode.hpp"

namespace mc {

// ── Calling convention type enumeration ──
enum CallingConventionType {
    CC_AARCH64_AAPCS64,  // AArch64: x0-x7 args, x0 return
    CC_ARM32_AAPCS,      // ARM32 ARM mode: r0-r3 args
    CC_ARM32_THUMB,      // ARM32 Thumb (16-bit instructions)
    CC_ARM32_THUMB2,     // ARM32 Thumb-2 (mixed 16/32-bit instructions)
    CC_UNKNOWN           // Cannot determine
};

// AArch64 argument registers (x0-x7)
constexpr int ARG_REGS_AARCH64[] = {100, 101, 102, 103, 104, 105, 106, 107};
constexpr int MAX_ARGS_AARCH64 = 8;

// ARM32 argument registers (r0-r3)
constexpr int ARG_REGS_ARM32[] = {100, 101, 102, 103};
constexpr int MAX_ARGS_ARM32 = 4;

// v9.5: Float argument registers (对标 r2 anal_arm_cs.c: float arg regs)
// AArch64: v0-v7 (SIMD/FP regs, mreg 200-207)
// ARM32:   d0-d7 (VFP double regs, mreg 200-207)
//          s0-s15 (VFP single regs mapped to d0-d7 parent)
constexpr int FLOAT_ARG_REGS[] = {200, 201, 202, 203, 204, 205, 206, 207};
constexpr int MAX_FLOAT_ARGS = 8;

// Return value register
constexpr int RET_REG = 100;  // x0 / r0

// v5.6: Callee-saved registers (对标 r2 cc_aarch64/cc_aarch32)
// AArch64: x19-x28, x29(fp), x30(lr), sp(x31)
// ARM32: r4-r11, sp(r13), lr(r14), pc(r15)
constexpr int CALLEE_SAVED_AARCH64[] = {119, 120, 121, 122, 123, 124, 125, 126, 127, 128, 129, 131};
constexpr int CALLEE_SAVED_ARM32[]   = {104, 105, 106, 107, 108, 109, 110, 111, 131, 132};

// v5.6: Inferred parameter type (对标 r2 anal_arm_cs.c get_reg_type)
// When a function calls a known function (e.g. malloc, memcpy),
// the argument registers at that call site have known types.
struct InferredParamType {
    int mreg;           // argument register mreg (100-107)
    std::string ctype;  // inferred C type ("void*", "size_t", "int", etc.)
    std::string source; // what call site provided this inference
};

// Parsed function signature
struct ParsedSignature {
    std::string return_type;        // "int", "void*", "void", etc.
    std::string func_name;          // function name
    std::vector<std::string> args;  // argument type strings
    bool is_variadic = false;       // has "..." at the end
    bool is_void_return = false;    // return type is void
    bool valid = false;             // parsing succeeded

    int argCount() const { return (int)args.size(); }
};

// Calling convention analyzer
class CallingConvention {
public:
    // Parse a C signature string like "void* malloc(size_t)" → ParsedSignature
    static ParsedSignature parseSignature(const std::string& sig);

    // Count arguments from a signature string
    // Returns -1 if unknown, 0+ if known
    static int countArgsFromSignature(const std::string& sig);

    // Count arguments using liveness analysis
    // Scans backwards from the call instruction in the same block
    // to find which argument registers (x0-x7) were explicitly defined
    static int countArgsFromLiveness(const MicroInsn* callInsn,
                                      const MicroBlock* block,
                                      bool isAArch64 = true);

    // Get the set of argument registers that are "live" before a call
    // (i.e., were assigned since the last call or function entry)
    static std::set<int> getLiveArgRegs(const MicroInsn* callInsn,
                                         const MicroBlock* block,
                                         bool isAArch64 = true);

    // Determine the best estimate for argument count
    // Priority: signature > liveness > default(4)
    static int determineArgCount(const std::string& calleeName,
                                  const std::map<std::string, std::string>& sigMap,
                                  const MicroInsn* callInsn,
                                  const MicroBlock* block,
                                  bool isAArch64 = true);

    // Check if a function returns void based on signature
    static bool returnsVoid(const std::string& sig);

    // v3.8: Determine which argument registers are actual parameters
    // Scans the function body for reads of arg registers before they are
    // defined. A register that is read before written is a parameter
    // (value comes from caller). Returns the set of arg mregs that are params.
    // This implements fcd pass_argrec's "live on entry" approach.
    static std::set<int> getFunctionParams(const MicrocodeBlockArray& mba,
                                             bool isAArch64 = true);

    // v3.8: Count actual parameters (convenience wrapper)
    static int countFunctionParams(const MicrocodeBlockArray& mba,
                                    bool isAArch64 = true);

    // v5.8: r2-style callee-based parameter count inference
    // 对标 r2 fcn_call_type + r_type_func_args_count:
    // If the function calls a known function (e.g., malloc) and passes
    // arg registers directly (without modification), those registers
    // are likely the function's own parameters.
    // Returns the max arg count inferred from all call sites, or -1 if none.
    static int inferParamCountFromCallees(const MicrocodeBlockArray& mba,
                                            bool isAArch64 = true);

    // v5.8: r2-style combined parameter count
    // 对标 r2 r_anal_function_set_nargs:
    // Priority:
    //   1. Known function signature (from symbol name / sdb_types)
    //   2. Callee-based inference (from known callees in the body)
    //   3. SSA liveness (registers read before defined, ssa_ver==0)
    //   4. Calling convention max args
    static int getFunctionParamCount(const MicrocodeBlockArray& mba,
                                      const std::string& funcName,
                                      bool isAArch64 = true);

    // v5.6: Infer parameter types from known callee names (对标 r2 get_reg_type)
    // Scans all CALL instructions in the function body.
    // When a call targets a known function (e.g., "malloc", "memcpy"),
    // the argument registers at that call site get typed.
    // Returns a map: mreg → InferredParamType (best inference per register).
    static std::map<int, InferredParamType> inferParamTypesFromCallees(
        const MicrocodeBlockArray& mba,
        bool isAArch64 = true);

    // v7.0: Infer parameter types from demangled C++ function name
    // (对标 r2 r_type_func_guess: extract params from demangled signature)
    // Example: "Class::method(int, char*, long)" → {100: "int", 101: "char*", 102: "long"}
    static std::map<int, InferredParamType> inferParamTypesFromDemangled(
        const std::string& funcName,
        bool isAArch64 = true);

    // v5.6: Get parameter type string for a known function name
    // Returns empty vector if the function is not in the known database.
    // 对标 r2 anal_arm_cs.c: PLT symbol name → param type mapping
    static std::vector<std::string> getParamTypesForCallee(const std::string& calleeName);

    // v8.8: Get return type string for a known function name (对标 R2 sdb_types).
    // Returns empty string if unknown / void-by-default-unknown.
    static std::string getReturnTypeForCallee(const std::string& calleeName);

    // v8.8: Infer a function's return type from its body (对标 Ghidra
    // ActionReturnPrototype): when the function calls a known function and
    // directly returns that call's result, inherit the callee's return type.
    // Returns empty string if no inference is possible.
    static std::string inferReturnTypeFromBody(const MicrocodeBlockArray& mba,
                                                bool isAArch64 = true);

    // v8.8: Parse a demangled C++ signature and return both the
    // qualified method name and the parameter type list.
    // Example: "ns::Class::method(int, char*, long)" →
    //          {scope="ns::Class::", method="method", args=[int, char*, long]}
    struct DemangledSig {
        std::string scope;       // class/namespace qualifier (e.g. "Class::")
        std::string method;      // method name
        std::vector<std::string> args;
        bool valid = false;
    };
    static DemangledSig parseDemangledCpp(const std::string& name);

    // ── v10.0: Calling convention auto-detection ──
    // Detects the calling convention from microcode instruction patterns.
    //   - AArch64: always AAPCS64 (x0-x7 args, x0 return)
    //   - ARM32 ARM mode: AAPCS (r0-r3 args)
    //   - ARM32 Thumb vs Thumb2: distinguished by instruction width:
    //     Thumb1 uses 2-byte instructions, Thumb2 uses 4-byte instructions.
    //     Detection heuristics:
    //       * Thumb:  look for push {lr} sequences (16-bit push)
    //       * Thumb2: look for 32-bit Thumb instructions (movw/movt, ldr.w/str.w)
    static CallingConventionType detectCallingConvention(const MicrocodeBlockArray& mba,
                                                          bool is_aarch64,
                                                          bool is_thumb);

    // Convert CallingConventionType to human-readable string
    static std::string ccToString(CallingConventionType cc);
};

} // namespace mc
