#pragma once
// known_symbols.hpp — Known symbols database
// 对标 Hex-Rays library function roles + Ghidra FunctionPrototype

#include "microcode.hpp"
#include <string>
#include <vector>
#include <unordered_map>

namespace symdb {

// Match type for symbol identification
enum MatchType {
    MATCH_BY_OFFSET,     // JNI function table offset
    MATCH_BY_NAME,       // known function name
    MATCH_BY_MANGLED,    // C++ mangled name (_ZN7...)
    MATCH_BY_SIGNATURE,  // function signature pattern
    MATCH_BY_PATTERN     // instruction pattern
};

// Known symbol entry
struct KnownSymbol {
    MatchType match_type = MATCH_BY_NAME;
    std::string mangled_name;   // C++ mangled name (_ZN7...)
    std::string real_name;      // real function name (FindClass, __cxa_throw)
    std::string module;         // owning module (libc, libdl, JNI, stdc++)

    // Signature
    std::string return_type;
    std::vector<std::string> param_types;
    std::vector<std::string> param_names;
    int arg_count = 0;
    bool has_return = true;

    // JNI specific
    int jni_offset = -1;       // JNI function table offset
    std::string jni_env_param; // first param name ("env")

    // Role (对标 Hex-Rays ROLE_*)
    enum Role {
        ROLE_NONE,
        ROLE_MEMSET,
        ROLE_MEMCPY,
        ROLE_MEMMOVE,
        ROLE_STRCPY,
        ROLE_STRLEN,
        ROLE_MALLOC,
        ROLE_FREE,
        ROLE_JNI_CALL
    } role = ROLE_NONE;
};

// Symbol database
class KnownSymbolsDB {
public:
    KnownSymbolsDB();

    // Lookup by name
    const KnownSymbol* lookupByName(const std::string& name) const;

    // Lookup by JNI offset
    const KnownSymbol* lookupByJNIOffset(int offset) const;

    // Lookup by mangled name
    const KnownSymbol* lookupByMangled(const std::string& mangled) const;

    // Lookup by address (via symbol table)
    const KnownSymbol* lookupByAddress(uint64_t addr) const;

    // Add a symbol
    void addSymbol(const KnownSymbol& sym);

    // Get all JNI symbols (for pattern matching)
    const std::unordered_map<int, KnownSymbol>& getJNISymbols() const { return by_jni_offset_; }

private:
    std::unordered_map<std::string, KnownSymbol> by_name_;
    std::unordered_map<int, KnownSymbol> by_jni_offset_;
    std::unordered_map<std::string, KnownSymbol> by_mangled_;

    void loadBuiltinSymbols();
    void loadJNISymbols();
    void loadCxxSymbols();
    void loadLibcSymbols();
    void loadAndroidSymbols();
};

// JNI call pattern detector
struct JNICallPattern {
    int env_offset = -1;
    std::string func_name;
    int arg_count = 0;
};

// Detect JNI call pattern from microcode
// Looks for: load function ptr from [env + offset], then call it
JNICallPattern detectJNICall(const mc::MicroInsn* call_insn,
                             const mc::MicrocodeBlockArray& mba);

// Resolve symbols in microcode (replace call targets with known names)
void resolveSymbols(mc::MicrocodeBlockArray& mba, const KnownSymbolsDB& db);

// v10.7: Check if a function name is a known noreturn function.
// This is the single source of truth for noreturn detection, used by
// resolveSymbols() and the post-pass in main.cpp.
// (对标 Ghidra NonReturningFunctions + analyzers.cpp kKnownNoReturnNames)
bool isKnownNoReturnName(const std::string& name);

} // namespace symdb
