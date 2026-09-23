#pragma once
// type_propagator.hpp — Type propagation
// 对标 Ghidra TypePropagator + Hex-Rays tinfo_t propagation

#include "microcode.hpp"
#include "lvar_allocator.hpp"

namespace mc {

// Type propagator: infers and propagates types across the microcode
class TypePropagator {
public:
    // Main entry: propagate types
    void propagate(MicrocodeBlockArray& mba, LVarAllocator& allocator);

private:
    MicrocodeBlockArray* mba_ = nullptr;
    LVarAllocator* allocator_ = nullptr;

    // Pass 1: Infer from calling convention
    //   x0-x7 → params, x0 → return value
    void inferFromCallingConvention();

    // Pass 2: Infer from known function calls
    //   If call target is known (FindClass, memcpy, etc.),
    //   propagate parameter types to argument setup instructions.
    void inferFromKnownCalls();

    // Pass 3: Infer from string constants
    //   ADRP+ADD pointing to .rodata → TC_STRING
    void inferFromStrings();

    // Pass 4: Infer from memory access width
    //   ldrb → 1 byte, ldrh → 2 bytes, ldr → 4/8 bytes
    void inferFromMemAccess();

    // Pass 5: Infer from pointer arithmetic
    //   Multiple fixed-offset accesses → struct pointer
    void inferFromPointerArith();

    // Pass 6: Infer from comparison instructions
    //   b.hs/b.lo → unsigned, b.ge/b.lt → signed
    void inferFromComparisons();

    // Pass 7: Cross-function type propagation
    void inferCrossFunction();

    // Helper: set variable type
    void setVarType(int mreg, int ssa_ver, const VarType& type);

    // Helper: get variable type
    VarType getVarType(int mreg, int ssa_ver) const;
};

} // namespace mc
