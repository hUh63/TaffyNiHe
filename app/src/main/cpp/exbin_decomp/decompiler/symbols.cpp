// symbols.cpp — Known symbols database + struct database implementation
// JNI function table, C++ mangled names, libc functions

#include "known_symbols.hpp"
#include "struct_database.hpp"
#include <algorithm>

namespace symdb {

// ════════════════════════════════════════════════════════════════════
// KnownSymbolsDB implementation
// ════════════════════════════════════════════════════════════════════

KnownSymbolsDB::KnownSymbolsDB() {
    loadBuiltinSymbols();
    loadJNISymbols();
    loadCxxSymbols();
    loadLibcSymbols();
    loadAndroidSymbols();
}

void KnownSymbolsDB::loadBuiltinSymbols() {
    // Built-in compiler functions
    KnownSymbol s;
    s.match_type = MATCH_BY_NAME;
    s.module = "built-in";

    s.real_name = "__builtin_trap"; s.return_type = "void"; s.has_return = false;
    s.role = KnownSymbol::ROLE_NONE;
    by_name_[s.real_name] = s;

    s.real_name = "__cxa_throw"; s.return_type = "void"; s.has_return = false;
    by_name_[s.real_name] = s;

    s.real_name = "__cxa_allocate_exception"; s.return_type = "void*"; s.has_return = true;
    by_name_[s.real_name] = s;

    s.real_name = "__stack_chk_fail"; s.return_type = "void"; s.has_return = false;
    by_name_[s.real_name] = s;
}

void KnownSymbolsDB::loadJNISymbols() {
    // JNINativeInterface function table offsets
    // Each entry is at offset (index * 8) from the JNIEnv pointer
    struct JNIEntry {
        int offset;
        const char* name;
        const char* ret_type;
        int arg_count;
        bool has_return;
    };

    // JNI function table (JNINativeInterface)
    // The table starts at offset 0x00, with 4 reserved entries
    static const JNIEntry jni_table[] = {
        {0x20,  "GetVersion",            "int32_t",  1, true},
        {0x28,  "DefineClass",           "void*",    4, true},
        {0x30,  "FindClass",             "void*",    2, true},
        {0x38,  "FromReflectedMethod",   "void*",    2, true},
        {0x40,  "FromReflectedField",    "void*",    2, true},
        {0x48,  "ToReflectedMethod",     "void*",    4, true},
        {0x50,  "GetSuperclass",         "void*",    2, true},
        {0x58,  "IsAssignableFrom",      "uint8_t",  3, true},
        {0x60,  "ToReflectedField",      "void*",    4, true},
        {0x68,  "Throw",                 "int32_t",  2, true},
        {0x70,  "ThrowNew",              "int32_t",  4, true},
        {0x78,  "ExceptionOccurred",     "void*",    1, true},
        {0x80,  "ExceptionDescribe",     "void",     1, false},
        {0x88,  "ExceptionClear",        "void",     1, false},
        {0x90,  "FatalError",            "void",     2, false},
        {0x98,  "PushLocalFrame",        "int32_t",  2, true},
        {0xA0,  "PopLocalFrame",         "void*",    2, true},
        {0xA8,  "NewGlobalRef",          "void*",    2, true},
        {0xB0,  "DeleteGlobalRef",       "void",     2, false},
        {0xB8,  "DeleteLocalRef",        "void",     2, false},
        {0xC0,  "IsSameObject",          "uint8_t",  3, true},
        {0xC8,  "NewLocalRef",           "void*",    2, true},
        {0xD0,  "EnsureLocalCapacity",   "int32_t",  2, true},
        {0xD8,  "AllocObject",           "void*",    2, true},
        {0xE0,  "NewObject",             "void*",    4, true},
        {0xE8,  "NewObjectV",            "void*",    4, true},
        {0xF0,  "NewObjectA",            "void*",    4, true},
        {0xF8,  "GetObjectClass",        "void*",    2, true},
        {0x100, "IsInstanceOf",          "uint8_t",  3, true},
        {0x108, "GetMethodID",           "void*",    4, true},
        {0x110, "CallObjectMethod",      "void*",    3, true},
        {0x118, "CallObjectMethodV",     "void*",    4, true},
        {0x120, "CallObjectMethodA",     "void*",    4, true},
        {0x128, "CallBooleanMethod",     "uint8_t",  3, true},
        {0x130, "CallBooleanMethodV",    "uint8_t",  4, true},
        {0x138, "CallBooleanMethodA",    "uint8_t",  4, true},
        {0x140, "CallByteMethod",        "int8_t",   3, true},
        {0x148, "CallCharMethod",        "uint16_t", 3, true},
        {0x150, "CallShortMethod",       "int16_t",  3, true},
        {0x158, "CallIntMethod",         "int32_t",  3, true},
        {0x160, "CallLongMethod",        "int64_t",  3, true},
        {0x168, "CallFloatMethod",       "float",    3, true},
        {0x170, "CallDoubleMethod",      "double",   3, true},
        {0x178, "CallVoidMethod",        "void",     3, false},
        {0x180, "CallVoidMethodV",       "void",     4, false},
        {0x188, "CallVoidMethodA",       "void",     4, false},
        {0x1F0, "GetFieldID",            "void*",    4, true},
        {0x1F8, "GetObjectField",        "void*",    3, true},
        {0x200, "GetBooleanField",       "uint8_t",  3, true},
        {0x208, "GetByteField",          "int8_t",   3, true},
        {0x210, "GetCharField",          "uint16_t", 3, true},
        {0x218, "GetShortField",         "int16_t",  3, true},
        {0x220, "GetIntField",           "int32_t",  3, true},
        {0x228, "GetLongField",          "int64_t",  3, true},
        {0x230, "GetFloatField",         "float",    3, true},
        {0x238, "GetDoubleField",        "double",   3, true},
        {0x240, "SetObjectField",        "void",     4, false},
        {0x248, "SetBooleanField",       "void",     4, false},
        {0x250, "SetByteField",          "void",     4, false},
        {0x258, "SetCharField",          "void",     4, false},
        {0x260, "SetShortField",         "void",     4, false},
        {0x268, "SetIntField",           "void",     4, false},
        {0x270, "SetLongField",          "void",     4, false},
        {0x278, "SetFloatField",         "void",     4, false},
        {0x280, "SetDoubleField",        "void",     4, false},
        {0x2F0, "GetStaticMethodID",     "void*",    4, true},
        {0x2F8, "CallStaticObjectMethod","void*",    3, true},
        {0x300, "CallStaticBooleanMethod","uint8_t", 3, true},
        {0x308, "CallStaticIntMethod",   "int32_t",  3, true},
        {0x310, "CallStaticVoidMethod",  "void",     3, false},
        {0x318, "CallStaticVoidMethodV", "void",     4, false},
        {0x360, "GetStaticFieldID",      "void*",    4, true},
        {0x368, "GetStaticObjectField",  "void*",    3, true},
        {0x370, "GetStaticIntField",     "int32_t",  3, true},
        {0x380, "SetStaticObjectField",  "void",     4, false},
        {0x388, "SetStaticIntField",     "void",     4, false},
        {0x3E0, "NewString",             "void*",    3, true},
        {0x3E8, "GetStringLength",       "int32_t",  2, true},
        {0x3F0, "GetStringChars",        "const uint16_t*", 3, true},
        {0x3F8, "ReleaseStringChars",    "void",     3, false},
        {0x400, "NewStringUTF",          "void*",    2, true},
        {0x408, "GetStringUTFLength",    "int32_t",  2, true},
        {0x410, "GetStringUTFChars",     "const char*", 3, true},
        {0x418, "ReleaseStringUTFChars", "void",     3, false},
        {0x420, "GetArrayLength",        "int32_t",  2, true},
        {0x428, "NewObjectArray",        "void*",    5, true},
        {0x430, "GetObjectArrayElement", "void*",    3, true},
        {0x438, "SetObjectArrayElement", "void",     4, false},
        {0x440, "NewBooleanArray",       "void*",    2, true},
        {0x448, "NewByteArray",          "void*",    2, true},
        {0x450, "NewCharArray",          "void*",    2, true},
        {0x458, "NewShortArray",         "void*",    2, true},
        {0x460, "NewIntArray",           "void*",    2, true},
        {0x468, "NewLongArray",          "void*",    2, true},
        {0x470, "NewFloatArray",         "void*",    2, true},
        {0x478, "NewDoubleArray",        "void*",    2, true},
        {0x480, "GetBooleanArrayElements","uint8_t*", 3, true},
        {0x488, "GetByteArrayElements",  "int8_t*",  3, true},
        {0x500, "GetIntArrayElements",   "int32_t*", 3, true},
        {0x540, "ReleaseBooleanArrayElements", "void", 4, false},
        {0x548, "ReleaseByteArrayElements",   "void", 4, false},
        {0x5C0, "GetIntArrayRegion",     "void",     5, false},
        {0x5C8, "GetByteArrayRegion",    "void",     5, false},
        {0x5E0, "SetIntArrayRegion",     "void",     5, false},
        {0x5E8, "SetByteArrayRegion",    "void",     5, false},
        {0x600, "RegisterNatives",       "int32_t",  4, true},
        {0x608, "UnregisterNatives",     "int32_t",  2, true},
        {0x610, "MonitorEnter",          "int32_t",  2, true},
        {0x618, "MonitorExit",           "int32_t",  2, true},
        {0x620, "GetJavaVM",             "int32_t",  2, true},
        {0x628, "GetStringRegion",       "void",     5, false},
        {0x630, "GetStringUTFRegion",    "void",     5, false},
        {0x638, "GetPrimitiveArrayCritical", "void*", 3, true},
        {0x640, "ReleasePrimitiveArrayCritical", "void", 4, false},
        {0x648, "GetStringCritical",     "const uint16_t*", 3, true},
        {0x650, "ReleaseStringCritical", "void",     3, false},
        {0x658, "NewWeakGlobalRef",      "void*",    2, true},
        {0x660, "DeleteWeakGlobalRef",   "void",     2, false},
        {0x668, "ExceptionCheck",        "uint8_t",  1, true},
        {0x670, "NewDirectByteBuffer",   "void*",    4, true},
        {0x678, "GetDirectBufferAddress", "void*",   2, true},
        {0x680, "GetDirectBufferCapacity", "int64_t", 2, true},
        {0x688, "GetObjectRefType",      "int32_t",  3, true},
    };

    for (const auto& e : jni_table) {
        KnownSymbol sym;
        sym.match_type = MATCH_BY_OFFSET;
        sym.real_name = e.name;
        sym.module = "JNI";
        sym.jni_offset = e.offset;
        sym.return_type = e.ret_type;
        sym.arg_count = e.arg_count;
        sym.has_return = e.has_return;
        sym.role = KnownSymbol::ROLE_JNI_CALL;
        sym.jni_env_param = "env";
        by_jni_offset_[e.offset] = sym;
        by_name_[e.name] = sym;
    }
}

void KnownSymbolsDB::loadCxxSymbols() {
    // Common C++ runtime functions
    struct CxxEntry {
        const char* mangled;
        const char* real_name;
        const char* ret_type;
        int arg_count;
    };

    static const CxxEntry cxx_table[] = {
        {"_Znwm",   "operator new",         "void*",    1},
        {"_Znam",   "operator new[]",       "void*",    1},
        {"_ZdlPv",  "operator delete",      "void",     1},
        {"_ZdaPv",  "operator delete[]",    "void",     1},
        {"_ZSt18_Rb_tree_decrementPKSt18_Rb_tree_node_base", "Rb_tree_decrement", "void", 1},
    };

    for (const auto& e : cxx_table) {
        KnownSymbol sym;
        sym.match_type = MATCH_BY_MANGLED;
        sym.mangled_name = e.mangled;
        sym.real_name = e.real_name;
        sym.module = "stdc++";
        sym.return_type = e.ret_type;
        sym.arg_count = e.arg_count;
        sym.has_return = (e.ret_type[0] != 'v' && e.ret_type[0] != 'V');
        by_mangled_[e.mangled] = sym;
        by_name_[e.real_name] = sym;
    }
}

void KnownSymbolsDB::loadLibcSymbols() {
    struct LibcEntry {
        const char* name;
        const char* ret_type;
        int arg_count;
        bool has_return;
        KnownSymbol::Role role;
    };

    static const LibcEntry libc_table[] = {
        {"memcpy",   "void*",    3, true,  KnownSymbol::ROLE_MEMCPY},
        {"memmove",  "void*",    3, true,  KnownSymbol::ROLE_MEMMOVE},
        {"memset",   "void*",    3, true,  KnownSymbol::ROLE_MEMSET},
        {"memcmp",   "int32_t",  3, true,  KnownSymbol::ROLE_NONE},
        {"memchr",   "void*",    3, true,  KnownSymbol::ROLE_NONE},
        {"strcpy",   "char*",    2, true,  KnownSymbol::ROLE_STRCPY},
        {"strncpy",  "char*",    3, true,  KnownSymbol::ROLE_NONE},
        {"strcat",   "char*",    2, true,  KnownSymbol::ROLE_NONE},
        {"strlen",   "uint64_t", 1, true,  KnownSymbol::ROLE_STRLEN},
        {"strcmp",   "int32_t",  2, true,  KnownSymbol::ROLE_NONE},
        {"strncmp",  "int32_t",  3, true,  KnownSymbol::ROLE_NONE},
        {"strchr",   "char*",    2, true,  KnownSymbol::ROLE_NONE},
        {"strrchr",  "char*",    2, true,  KnownSymbol::ROLE_NONE},
        {"strstr",   "char*",    2, true,  KnownSymbol::ROLE_NONE},
        {"strtok",   "char*",    2, true,  KnownSymbol::ROLE_NONE},
        {"atoi",     "int32_t",  1, true,  KnownSymbol::ROLE_NONE},
        {"atol",     "int64_t",  1, true,  KnownSymbol::ROLE_NONE},
        {"atof",     "double",   1, true,  KnownSymbol::ROLE_NONE},
        {"strtol",   "int64_t",  3, true,  KnownSymbol::ROLE_NONE},
        {"strtoul",  "uint64_t", 3, true,  KnownSymbol::ROLE_NONE},
        {"malloc",   "void*",    1, true,  KnownSymbol::ROLE_MALLOC},
        {"free",     "void",     1, false, KnownSymbol::ROLE_FREE},
        {"calloc",   "void*",    2, true,  KnownSymbol::ROLE_NONE},
        {"realloc",  "void*",    2, true,  KnownSymbol::ROLE_NONE},
        {"abort",    "void",     0, false, KnownSymbol::ROLE_NONE},
        {"exit",     "void",     1, false, KnownSymbol::ROLE_NONE},
        {"printf",   "int32_t",  1, true,  KnownSymbol::ROLE_NONE},
        {"fprintf",  "int32_t",  2, true,  KnownSymbol::ROLE_NONE},
        {"sprintf",  "int32_t",  3, true,  KnownSymbol::ROLE_NONE},
        {"snprintf", "int32_t",  4, true,  KnownSymbol::ROLE_NONE},
        {"puts",     "int32_t",  1, true,  KnownSymbol::ROLE_NONE},
        {"fputs",    "int32_t",  2, true,  KnownSymbol::ROLE_NONE},
        {"fputc",    "int32_t",  2, true,  KnownSymbol::ROLE_NONE},
        {"fopen",    "void*",    2, true,  KnownSymbol::ROLE_NONE},
        {"fclose",   "int32_t",  1, true,  KnownSymbol::ROLE_NONE},
        {"fread",    "uint64_t", 4, true,  KnownSymbol::ROLE_NONE},
        {"fwrite",   "uint64_t", 4, true,  KnownSymbol::ROLE_NONE},
        {"fseek",    "int32_t",  3, true,  KnownSymbol::ROLE_NONE},
        {"ftell",    "int64_t",  1, true,  KnownSymbol::ROLE_NONE},
        {"perror",   "void",     1, false, KnownSymbol::ROLE_NONE},
        {"errno",    "int32_t",  0, true,  KnownSymbol::ROLE_NONE},
        {"__android_log_print", "int32_t", 3, true, KnownSymbol::ROLE_NONE},
        {"__android_log_write", "int32_t", 3, true, KnownSymbol::ROLE_NONE},
        {"pthread_create",   "int32_t", 4, true,  KnownSymbol::ROLE_NONE},
        {"pthread_join",     "int32_t", 2, true,  KnownSymbol::ROLE_NONE},
        {"pthread_mutex_init",   "int32_t", 2, true, KnownSymbol::ROLE_NONE},
        {"pthread_mutex_lock",   "int32_t", 1, true, KnownSymbol::ROLE_NONE},
        {"pthread_mutex_unlock", "int32_t", 1, true, KnownSymbol::ROLE_NONE},
        {"pthread_mutex_destroy","int32_t", 1, true, KnownSymbol::ROLE_NONE},
        {"__pthread_gettid", "int32_t", 2, true, KnownSymbol::ROLE_NONE},
    };

    for (const auto& e : libc_table) {
        KnownSymbol sym;
        sym.match_type = MATCH_BY_NAME;
        sym.real_name = e.name;
        sym.module = "libc";
        sym.return_type = e.ret_type;
        sym.arg_count = e.arg_count;
        sym.has_return = e.has_return;
        sym.role = e.role;
        by_name_[e.name] = sym;
    }
}

void KnownSymbolsDB::loadAndroidSymbols() {
    // Android-specific symbols
    struct AndroidEntry {
        const char* name;
        const char* ret_type;
        int arg_count;
        bool has_return;
    };

    static const AndroidEntry android_table[] = {
        {"dlopen",    "void*",    2, true},
        {"dlsym",     "void*",    2, true},
        {"dlclose",   "int32_t",  1, true},
        {"dlerror",   "const char*", 0, true},
        {"__system_property_get", "int32_t", 2, true},
        {"__system_property_set",  "int32_t", 2, true},
        {"signal",    "void*",    2, true},
        {"kill",      "int32_t",  2, true},
        {"getpid",    "int32_t",  0, true},
        {"gettid",    "int32_t",  0, true},
        {"getuid",    "uint32_t", 0, true},
        {"time",      "int64_t",  1, true},
        {"gettimeofday", "int32_t", 2, true},
        {"clock_gettime", "int32_t", 2, true},
    };

    for (const auto& e : android_table) {
        KnownSymbol sym;
        sym.match_type = MATCH_BY_NAME;
        sym.real_name = e.name;
        sym.module = "android";
        sym.return_type = e.ret_type;
        sym.arg_count = e.arg_count;
        sym.has_return = e.has_return;
        by_name_[e.name] = sym;
    }
}

const KnownSymbol* KnownSymbolsDB::lookupByName(const std::string& name) const {
    auto it = by_name_.find(name);
    if (it != by_name_.end()) return &it->second;
    return nullptr;
}

const KnownSymbol* KnownSymbolsDB::lookupByJNIOffset(int offset) const {
    auto it = by_jni_offset_.find(offset);
    if (it != by_jni_offset_.end()) return &it->second;
    return nullptr;
}

const KnownSymbol* KnownSymbolsDB::lookupByMangled(const std::string& mangled) const {
    // Try exact match
    auto it = by_mangled_.find(mangled);
    if (it != by_mangled_.end()) return &it->second;
    // Try prefix match for C++ mangled names starting with _Z
    if (mangled.length() > 2 && mangled[0] == '_' && mangled[1] == 'Z') {
        for (const auto& [key, sym] : by_mangled_) {
            if (mangled.substr(0, key.length()) == key)
                return &sym;
        }
    }
    return nullptr;
}

const KnownSymbol* KnownSymbolsDB::lookupByAddress(uint64_t addr) const {
    (void)addr;
    // TODO: implement address-based lookup from symbol table
    return nullptr;
}

void KnownSymbolsDB::addSymbol(const KnownSymbol& sym) {
    by_name_[sym.real_name] = sym;
    if (sym.match_type == MATCH_BY_MANGLED)
        by_mangled_[sym.mangled_name] = sym;
    if (sym.jni_offset >= 0)
        by_jni_offset_[sym.jni_offset] = sym;
}

// ── JNI call pattern detection ──
JNICallPattern detectJNICall(const mc::MicroInsn* call_insn,
                             const mc::MicrocodeBlockArray& mba) {
    JNICallPattern pattern;
    (void)mba;

    if (!call_insn || call_insn->opcode != mc::OP_ICALL)
        return pattern;

    // Check if this is a JNI call: ldr xN, [env, #offset]; blr xN
    // The call instruction has l = register holding the function pointer
    // We need to trace back to find the load instruction
    if (call_insn->l.isReg()) {
        // TODO: trace back through SSA to find the load
        // For now, just return empty pattern
    }

    return pattern;
}

// ── v10.7: Known noreturn function names ──
// (对标 Ghidra NonReturningFunctions + analyzers.cpp kKnownNoReturnNames)
// Single source of truth for noreturn detection.
static const char* const kKnownNoReturnNames[] = {
    "abort",
    "exit",
    "_exit",
    "_Exit",
    "__stack_chk_fail",
    "__stack_chk_fail_local",
    "__assert_fail",
    "__assert",
    "__assert2",
    "__cxa_throw",
    "__cxa_rethrow",
    "__cxa_bad_cast",
    "__cxa_bad_typeid",
    "__cxa_call_unexpected",
    "__cxa_pure_virtual",
    "__cxa_deleted_virtual",
    "longjmp",
    "siglongjmp",
    "_longjmp",
    "pthread_exit",
    "__android_log_assert",
    "__android_log_assert_dummy",
    "_Unwind_Resume",
    "__gcc_unreachable",
    "__builtin_unreachable",
    "__builtin_trap",
    "__trap",
    "quick_exit",
    "__fortify_fail",
    "__chk_fail",
    "exit_group",       // Linux syscall exit_group
    "syscall_exit",     // bionic syscall exit
    "__SEGV",            // signal handlers that don't return
    "__BUS",
    "__ILL",
    "__FPE",
    // C++ runtime noreturn
    "__throw_bad_alloc",
    "__throw_out_of_range_fmt",
    "__throw_length_error",
    "__throw_logic_error",
    "__throw_runtime_error",
    "__throw_out_of_range",
    "__throw_bad_cast",
    "__throw_bad_typeid",
    "std::terminate",
    "std::abort",
    // Android/bionic specific
    "__libc_init",
    "__libc_preinit",
    "android_thread_abort",
    // __cxa atexit handlers that don't return
    "__cxa_atexit_to_thread",
};

bool isKnownNoReturnName(const std::string& name) {
    if (name.empty()) return false;
    // Strip @plt / @@version suffixes
    std::string n = name;
    size_t at = n.find('@');
    if (at != std::string::npos) n = n.substr(0, at);
    // Strip leading underscore variant
    for (const char* k : kKnownNoReturnNames) {
        if (n == k) return true;
        if (!n.empty() && n[0] == '_' && (n.substr(1) == k)) return true;
    }
    return false;
}

// ── Resolve symbols in microcode ──
void resolveSymbols(mc::MicrocodeBlockArray& mba, const KnownSymbolsDB& db) {
    for (int b = 0; b < mba.numBlocks(); b++) {
        for (auto* insn = mba.blocks[b]->head; insn; insn = insn->next) {
            if (insn->opcode == mc::OP_CALL || insn->opcode == mc::OP_ICALL) {
                if (insn->call_info) {
                    // Try to resolve by address
                    if (insn->call_info->target_addr != 0) {
                        // Check if address is a known function
                        auto it = mba.global_names.find(insn->call_info->target_addr);
                        if (it != mba.global_names.end()) {
                            const auto* sym = db.lookupByName(it->second);
                            if (sym) {
                                insn->call_info->target_name = sym->real_name;
                                insn->call_info->is_known = true;
                                insn->call_info->return_type = sym->return_type;
                                // v9.8: Don't blindly set arg_count from symbol DB —
                                // variadic functions (printf, fprintf, etc.) have
                                // a minimum arg_count but actual calls have more.
                                // The CTree builder uses liveness analysis (better).
                                // Only set has_return — this is critical for noreturn.
                                insn->call_info->has_return = sym->has_return;
                            }
                        }
                    }

                    // v10.7: Check against the comprehensive noreturn name list.
                    // This catches noreturn functions that are NOT in the
                    // KnownSymbolsDB (e.g. __stack_chk_fail, __cxa_throw,
                    // longjmp, _Unwind_Resume, etc.).
                    // Without this, fall-through edges are created from
                    // noreturn call blocks, producing garbage code from
                    // literal pool data after the call.
                    if (insn->call_info->has_return) {
                        if (isKnownNoReturnName(insn->call_info->target_name)) {
                            insn->call_info->has_return = false;
                        }
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════
// StructDatabase implementation
// ════════════════════════════════════════════════════════════════════

StructDatabase::StructDatabase() {
    loadJNINativeInterface();
    loadJavaVM();
    loadJNINativeMethod();
    loadCommonStructs();
}

void StructDatabase::loadJNINativeInterface() {
    StructDef s;
    s.name = "JNINativeInterface";
    s.size = 0x690;

    // Reserved entries (4 pointers)
    for (int i = 0; i < 4; i++) {
        char name[32];
        snprintf(name, sizeof(name), "reserved%d", i);
        s.fields.push_back({i * 8, 8, name, "void*"});
    }

    // Common JNI functions (subset matching the known_symbols table)
    s.fields.push_back({0x20, 8, "GetVersion", "void*"});
    s.fields.push_back({0x28, 8, "DefineClass", "void*"});
    s.fields.push_back({0x30, 8, "FindClass", "void*"});
    s.fields.push_back({0x50, 8, "GetSuperclass", "void*"});
    s.fields.push_back({0x68, 8, "Throw", "void*"});
    s.fields.push_back({0x70, 8, "ThrowNew", "void*"});
    s.fields.push_back({0x78, 8, "ExceptionOccurred", "void*"});
    s.fields.push_back({0x80, 8, "ExceptionDescribe", "void*"});
    s.fields.push_back({0x88, 8, "ExceptionClear", "void*"});
    s.fields.push_back({0xA8, 8, "NewGlobalRef", "void*"});
    s.fields.push_back({0xB0, 8, "DeleteGlobalRef", "void*"});
    s.fields.push_back({0xB8, 8, "DeleteLocalRef", "void*"});
    s.fields.push_back({0xF8, 8, "GetObjectClass", "void*"});
    s.fields.push_back({0x108, 8, "GetMethodID", "void*"});
    s.fields.push_back({0x1F0, 8, "GetFieldID", "void*"});
    s.fields.push_back({0x2F0, 8, "GetStaticMethodID", "void*"});
    s.fields.push_back({0x360, 8, "GetStaticFieldID", "void*"});
    s.fields.push_back({0x3E0, 8, "NewString", "void*"});
    s.fields.push_back({0x400, 8, "NewStringUTF", "void*"});
    s.fields.push_back({0x410, 8, "GetStringUTFChars", "void*"});
    s.fields.push_back({0x418, 8, "ReleaseStringUTFChars", "void*"});
    s.fields.push_back({0x420, 8, "GetArrayLength", "void*"});
    s.fields.push_back({0x428, 8, "NewObjectArray", "void*"});
    s.fields.push_back({0x600, 8, "RegisterNatives", "void*"});

    structs_[s.name] = s;
}

void StructDatabase::loadJavaVM() {
    StructDef s;
    s.name = "JavaVM";
    s.size = 0x20;
    s.fields.push_back({0x00, 8, "reserved0", "void*"});
    s.fields.push_back({0x08, 8, "reserved1", "void*"});
    s.fields.push_back({0x10, 8, "reserved2", "void*"});
    s.fields.push_back({0x18, 8, "DestroyJavaVM", "void*"});
    structs_[s.name] = s;
}

void StructDatabase::loadJNINativeMethod() {
    StructDef s;
    s.name = "JNINativeMethod";
    s.size = 0x18;
    s.fields.push_back({0x00, 8, "name", "const char*"});
    s.fields.push_back({0x08, 8, "signature", "const char*"});
    s.fields.push_back({0x10, 8, "fnPtr", "void*"});
    structs_[s.name] = s;
}

void StructDatabase::loadCommonStructs() {
    // pthread_mutex_t (simplified)
    {
        StructDef s;
        s.name = "pthread_mutex_t";
        s.size = 0x28;
        s.fields.push_back({0x00, 4, "value", "int32_t"});
        structs_[s.name] = s;
    }

    // timespec
    {
        StructDef s;
        s.name = "timespec";
        s.size = 0x10;
        s.fields.push_back({0x00, 8, "tv_sec", "int64_t"});
        s.fields.push_back({0x08, 8, "tv_nsec", "int64_t"});
        structs_[s.name] = s;
    }

    // FILE (simplified)
    {
        StructDef s;
        s.name = "FILE";
        s.size = 0x100;
        s.fields.push_back({0x00, 8, "_flags", "int32_t"});
        s.fields.push_back({0x08, 8, "_IO_read_ptr", "char*"});
        s.fields.push_back({0x10, 8, "_IO_read_end", "char*"});
        s.fields.push_back({0x18, 8, "_IO_read_base", "char*"});
        s.fields.push_back({0x20, 8, "_IO_write_base", "char*"});
        s.fields.push_back({0x28, 8, "_IO_write_ptr", "char*"});
        s.fields.push_back({0x30, 8, "_IO_write_end", "char*"});
        s.fields.push_back({0x38, 8, "_IO_buf_base", "char*"});
        s.fields.push_back({0x40, 8, "_IO_buf_end", "char*"});
        structs_[s.name] = s;
    }
}

bool StructDatabase::lookupField(const std::string& struct_name, int offset,
                                  StructFieldDef& out_field) const {
    auto it = structs_.find(struct_name);
    if (it == structs_.end()) return false;

    for (const auto& f : it->second.fields) {
        if (f.offset == offset) {
            out_field = f;
            return true;
        }
    }
    return false;
}

std::string StructDatabase::identifyStruct(const std::vector<int>& accessed_offsets) const {
    // Try to match the set of accessed offsets against known structs
    for (const auto& [name, s] : structs_) {
        int match_count = 0;
        for (int off : accessed_offsets) {
            for (const auto& f : s.fields) {
                if (f.offset == off) {
                    match_count++;
                    break;
                }
            }
        }
        // If more than 50% of accessed offsets match, consider it a match
        if (match_count > 0 && match_count >= (int)accessed_offsets.size() / 2) {
            return name;
        }
    }
    return "";
}

const StructDef* StructDatabase::getStruct(const std::string& name) const {
    auto it = structs_.find(name);
    if (it != structs_.end()) return &it->second;
    return nullptr;
}

bool StructDatabase::isJNIOffset(int offset, StructFieldDef& out_field) const {
    return lookupField("JNINativeInterface", offset, out_field);
}

// ── Field access conversion helper ──
FieldAccessResult convertFieldAccess(const StructDatabase& db,
                                      const std::string& base_type,
                                      int offset,
                                      const std::string& base_name) {
    FieldAccessResult result;

    // Check if base_type is a known struct
    const StructDef* s = db.getStruct(base_type);
    if (!s) {
        // Check if it's JNI interface
        if (base_type == "JNIEnv*" || base_type == "JNIEnv") {
            s = db.getStruct("JNINativeInterface");
            if (s) {
                StructFieldDef field;
                if (db.lookupField("JNINativeInterface", offset, field)) {
                    result.found = true;
                    result.struct_name = "JNINativeInterface";
                    result.field_name = field.name;
                    result.field_type = field.type;
                    result.c_expr = base_name + "->" + field.name;
                    return result;
                }
            }
        }
        return result;
    }

    StructFieldDef field;
    if (db.lookupField(base_type, offset, field)) {
        result.found = true;
        result.struct_name = base_type;
        result.field_name = field.name;
        result.field_type = field.type;
        result.c_expr = base_name + "->" + field.name;
    }

    return result;
}

} // namespace symdb
