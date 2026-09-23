#pragma once
// var_namer.hpp — Semantic variable naming
// 对标 Ghidra var naming + Hex-Rays lvar naming

#include "microcode.hpp"
#include "lvar_allocator.hpp"

namespace mc {

// Variable namer: assigns semantic names to local variables
class VarNamer {
public:
    // Main entry: assign names to all variables
    void name(MicrocodeBlockArray& mba, LVarAllocator& allocator);

private:
    MicrocodeBlockArray* mba_ = nullptr;
    LVarAllocator* allocator_ = nullptr;

    // Naming priority (high to low):
    // 1. User-defined name (from symbol table / debug info)
    // 2. Known symbol name (JNI/env etc.)
    // 3. Semantic name (based on usage pattern)
    // 4. Stack variable default (var_XX)
    // 5. Parameter default (a1, a2, ...)
    // 6. Temp variable default (v1, v2, ...)

    std::string nameVariable(const LocalVar& var);
    std::string inferSemanticName(const LocalVar& var);

    // Rule 1: Environment pointer (JNI env / JavaVM)
    //   If var is first param and used for JNI calls (ldr x8, [x0, #offset]; blr x8)
    //   → "env" (JNI) or "vm" (JavaVM)
    std::string checkEnvPointer(const LocalVar& var);

    // Rule 2: String pointer
    //   If var comes from ADRP+ADD and address points to .rodata string
    //   → "str" / "path" / "name"
    std::string checkStringPointer(const LocalVar& var);

    // Rule 3: Loop counter
    //   If var is initialized to 0 in loop, incremented each iteration, compared with limit
    //   → "i" / "idx" / "count"
    std::string checkLoopCounter(const LocalVar& var);

    // Rule 4: Return value
    //   If var is last value assigned to x0 before return
    //   → "result" / "ret"
    std::string checkReturnValue(const LocalVar& var);

    // Rule 5: Size/length
    //   If var is used as memcpy/strlen argument or loop upper bound
    //   → "size" / "len"
    std::string checkSizeLength(const LocalVar& var);

    // Rule 6: This pointer (C++ method)
    //   If var is first param and accessed at multiple offsets
    //   → "this"
    std::string checkThisPointer(const LocalVar& var);
};

} // namespace mc
