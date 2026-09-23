// ndk_decompile.cpp — JNI bridge for the C++ ARM32/AArch64 decompiler
// Receives disassembly data from Java, runs the full decompiler pipeline,
// and returns generated pseudo-C code as a string.
//
// Pipeline: AsmInsn → MicrocodeEmitter → SSA → Optimize → CFGStructure → CTree → Beautify → CPrinter
//
// v4.0: Phase 5 naming (in_argN, local_hex, tmpN), semantic naming (vm/env),
//       callee scanning, ELF symbol filtering, ARM32 return type, secondary_names_

#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <cctype>
#include <map>
#include <fstream>
#include <iterator>
#include <algorithm>

#include "microcode_emitter.hpp"
#include "ssa.hpp"
#include "microcode_opt.hpp"
#include "ir_optimizer.hpp"
#include "cfg_structure.hpp"
#include "ctree_builder.hpp"
#include "ctree_beautify.hpp"
#include "c_printer.hpp"
#include "elf_symbol_resolver.hpp"
#include "calling_convention.hpp"
#include "known_symbols.hpp"

using namespace mc;
using namespace ssa;
using namespace cfg;
using namespace ctree;


// ── JNI: nativeDecompileFunction ──
// Java signature:
//   private static native String nativeDecompileFunction(
//       String funcName, long funcAddr, int machine, boolean isThumb,
//       String soPath,
//       String[] mnemonics, String[] opStrs, long[] addresses, int[] sizes,
//       long[] labelAddrs, String[] labelNames,
//       long[] importAddrs, String[] importNames,
//       String[] sigNames, String[] sigStrings,
//       long[] symAddrs, String[] symNames);
extern "C" JNIEXPORT jstring JNICALL
Java_com_exbin_app_nativebridge_NativeBridge_nativeDecompileFunction(
        JNIEnv* env, jclass /*clazz*/,
        jstring jFuncName, jlong jFuncAddr, jint jMachine, jboolean jIsThumb,
        jstring jSoPath,
        jobjectArray jMnemonics, jobjectArray jOpStrs, jlongArray jAddresses, jintArray jSizes,
        jlongArray jLabelAddrs, jobjectArray jLabelNames,
        jlongArray jImportAddrs, jobjectArray jImportNames,
        jobjectArray jSigNames, jobjectArray jSigStrings,
        jlongArray jSymAddrs, jobjectArray jSymNames) {

    try {
        // ── 基本参数提取 ──
        const char* funcNameC = env->GetStringUTFChars(jFuncName, nullptr);
        std::string funcName = funcNameC ? funcNameC : "sub_0";
        env->ReleaseStringUTFChars(jFuncName, funcNameC);

        uint64_t funcAddr = (uint64_t)jFuncAddr;
        bool isAArch64 = (jMachine == 183);   // EM_AARCH64
        bool isThumb = (jIsThumb != JNI_FALSE);
        (void)isThumb;

        // ── 真实地址（ARM32 Thumb 需清除最低位） ──
        uint64_t realAddr = funcAddr;
        if (!isAArch64) realAddr &= ~1ULL;

        // ── C++ ELF 符号解析 ──
        ElfSymbolMap elfSyms;
        bool hasElfSyms = false;
        std::vector<uint8_t> elfRawData;
        if (jSoPath) {
            const char* soPathC = env->GetStringUTFChars(jSoPath, nullptr);
            if (soPathC && soPathC[0]) {
                std::string soPath = soPathC;
                elfSyms = ElfSymbolResolver::resolve(soPath);
                hasElfSyms = elfSyms.valid;
                // 读取原始 ELF 字节（用于 literal pool 读取）
                std::ifstream elfIfs(soPath, std::ios::binary);
                if (elfIfs) {
                    elfRawData.assign((std::istreambuf_iterator<char>(elfIfs)),
                                      std::istreambuf_iterator<char>());
                }
            }
            env->ReleaseStringUTFChars(jSoPath, soPathC);
        }

        // ── 提取反汇编指令 ──
        jsize insnCount = env->GetArrayLength(jMnemonics);
        if (insnCount == 0) {
            return env->NewStringUTF("// 无指令\n");
        }

        std::vector<AsmInsn> insns;
        insns.reserve(insnCount);
        jlong* addrs = env->GetLongArrayElements(jAddresses, nullptr);
        jint* sizes = env->GetIntArrayElements(jSizes, nullptr);

        for (jsize i = 0; i < insnCount; i++) {
            AsmInsn insn;
            insn.addr = addrs ? (uint64_t)addrs[i] : funcAddr + i * 4;
            insn.bytes_size = sizes ? (size_t)sizes[i] : 4;

            jstring jMnem = (jstring)env->GetObjectArrayElement(jMnemonics, i);
            if (jMnem) {
                const char* m = env->GetStringUTFChars(jMnem, nullptr);
                insn.mnemonic = m ? m : "nop";
                env->ReleaseStringUTFChars(jMnem, m);
                env->DeleteLocalRef(jMnem);
            }
            jstring jOp = (jstring)env->GetObjectArrayElement(jOpStrs, i);
            if (jOp) {
                const char* o = env->GetStringUTFChars(jOp, nullptr);
                insn.op_str = o ? o : "";
                env->ReleaseStringUTFChars(jOp, o);
                env->DeleteLocalRef(jOp);
            }

            std::string& m = insn.mnemonic;
            for (char& c : m) c = (char)std::tolower((unsigned char)c);
            for (char& c : insn.op_str) c = (char)std::tolower((unsigned char)c);
            insn.is_branch = (m == "b" || m.substr(0,2) == "b." || m == "cbz" || m == "cbnz" ||
                              m == "tbz" || m == "tbnz" || m == "br" || m == "bx" ||
                              (!isAArch64 && m.length() > 1 && m[0] == 'b' &&
                               (m[1] == 'e' || m[1] == 'n' || m[1] == 'c' || m[1] == 'h' ||
                                m[1] == 'l' || m[1] == 'm' || m[1] == 'p' || m[1] == 'v' ||
                                m[1] == 'g')));
            insn.is_call = (m == "bl" || m == "blr" || m == "blx");
            insn.is_ret = (m == "ret" || m == "pop");
            insns.push_back(std::move(insn));
        }

        if (addrs) env->ReleaseLongArrayElements(jAddresses, addrs, JNI_ABORT);
        if (sizes) env->ReleaseIntArrayElements(jSizes, sizes, JNI_ABORT);

        // ── 合并全局名称（优先使用 ELF 解析结果） ──
        // ① 过滤掉 PLT_STUB 和 GOT_ENTRY（避免 ADRP 错误解析），但保留 PLT_STUB 用于调用解析
        std::map<uint64_t, std::string> globalNames;
        if (hasElfSyms) {
            for (auto& [addr, sym] : elfSyms.byAddr) {
                if (sym.type == SymType::PLT_STUB || sym.type == SymType::GOT_ENTRY)
                    continue;
                globalNames[addr] = sym.name;
            }
            for (auto& [addr, name] : elfSyms.globalVars) {
                if (globalNames.find(addr) == globalNames.end())
                    globalNames[addr] = name;
            }
            // 单独加入 PLT_STUB 以便 call 解析
            for (auto& [addr, sym] : elfSyms.byAddr) {
                if (sym.type == SymType::PLT_STUB) {
                    globalNames[addr] = sym.name;
                }
            }
            // v34: GOT 条目 → got_ 前缀名
            for (auto& [gotAddr, name] : elfSyms.gotToName) {
                if (globalNames.find(gotAddr) == globalNames.end()) {
                    globalNames[gotAddr] = "got_" + name;
                }
            }
            // v34: 数据全局符号补录
            for (auto& [addr, sym] : elfSyms.byAddr) {
                if (globalNames.find(addr) != globalNames.end()) continue;
                if (sym.type == SymType::DATA_GLOBAL) {
                    globalNames[addr] = sym.name;
                }
            }
        }

        // ② 合并 Java 传递的标签/导入/符号（若 ELF 解析不可用则使用）
        if (!hasElfSyms) {
            if (jLabelAddrs && jLabelNames) {
                jsize labelCount = env->GetArrayLength(jLabelAddrs);
                jlong* lAddrs = env->GetLongArrayElements(jLabelAddrs, nullptr);
                for (jsize i = 0; i < labelCount; i++) {
                    jstring jName = (jstring)env->GetObjectArrayElement(jLabelNames, i);
                    if (jName && lAddrs) {
                        const char* n = env->GetStringUTFChars(jName, nullptr);
                        if (n) globalNames[(uint64_t)lAddrs[i]] = n;
                        env->ReleaseStringUTFChars(jName, n);
                        env->DeleteLocalRef(jName);
                    }
                }
                if (lAddrs) env->ReleaseLongArrayElements(jLabelAddrs, lAddrs, JNI_ABORT);
            }
            if (jImportAddrs && jImportNames) {
                jsize impCount = env->GetArrayLength(jImportAddrs);
                jlong* iAddrs = env->GetLongArrayElements(jImportAddrs, nullptr);
                for (jsize i = 0; i < impCount; i++) {
                    jstring jName = (jstring)env->GetObjectArrayElement(jImportNames, i);
                    if (jName && iAddrs) {
                        const char* n = env->GetStringUTFChars(jName, nullptr);
                        if (n) globalNames[(uint64_t)iAddrs[i]] = n;
                        env->ReleaseStringUTFChars(jName, n);
                        env->DeleteLocalRef(jName);
                    }
                }
                if (iAddrs) env->ReleaseLongArrayElements(jImportAddrs, iAddrs, JNI_ABORT);
            }
            if (jSymAddrs && jSymNames) {
                jsize symCount = env->GetArrayLength(jSymAddrs);
                jlong* sAddrs = env->GetLongArrayElements(jSymAddrs, nullptr);
                for (jsize i = 0; i < symCount; i++) {
                    jstring jName = (jstring)env->GetObjectArrayElement(jSymNames, i);
                    if (jName && sAddrs) {
                        const char* n = env->GetStringUTFChars(jName, nullptr);
                        if (n) {
                            uint64_t a = (uint64_t)sAddrs[i];
                            if (globalNames.find(a) == globalNames.end())
                                globalNames[a] = n;
                        }
                        env->ReleaseStringUTFChars(jName, n);
                        env->DeleteLocalRef(jName);
                    }
                }
                if (sAddrs) env->ReleaseLongArrayElements(jSymAddrs, sAddrs, JNI_ABORT);
            }
        }

        // ── 签名映射 ──
        std::map<std::string, std::string> sigMap;
        if (jSigNames && jSigStrings) {
            jsize sigCount = env->GetArrayLength(jSigNames);
            for (jsize i = 0; i < sigCount; i++) {
                jstring jN = (jstring)env->GetObjectArrayElement(jSigNames, i);
                jstring jS = (jstring)env->GetObjectArrayElement(jSigStrings, i);
                if (jN && jS) {
                    const char* n = env->GetStringUTFChars(jN, nullptr);
                    const char* s = env->GetStringUTFChars(jS, nullptr);
                    if (n && s) sigMap[n] = s;
                    env->ReleaseStringUTFChars(jN, n);
                    env->ReleaseStringUTFChars(jS, s);
                    env->DeleteLocalRef(jN);
                    env->DeleteLocalRef(jS);
                }
            }
        }

        // ════════════════════════════════════════════════════════════
        // Phase 5a: 语义参数命名 (Ghidra ScopeLocal-style)
        // ════════════════════════════════════════════════════════════
        std::map<int, std::string> semanticParamNames;
        const int firstParamReg = 100;  // x0 / r0
        const int secondParamReg = 101; // x1 / r1

        // v8.1: C++ 成员函数检测: 给 this 参数命名
        // 任何包含 :: 的函数都可能是 C++ 成员函数，this 是隐式第一个参数
        // 例外: 已知命名空间自由函数 (如 std::sort)，但对 MC SO 无害
        bool isCppMemberFunc = false;
        if (funcName.find("::") != std::string::npos) {
            // 提取最后一个 :: 之后的方法名，strip " const" 后缀
            size_t lastColon = funcName.rfind("::");
            std::string methodPart = (lastColon != std::string::npos) ? funcName.substr(lastColon + 2) : "";
            if (methodPart.size() > 6 && methodPart.substr(methodPart.size() - 6) == " const") {
                methodPart = methodPart.substr(0, methodPart.size() - 6);
            }
            char firstChar = methodPart.empty() ? 0 : methodPart[0];
            if (firstChar == '~' || isupper((unsigned char)firstChar) || firstChar == '_' ||
                islower((unsigned char)firstChar) || funcName.find("operator") != std::string::npos) {
                isCppMemberFunc = true;
                semanticParamNames[firstParamReg] = "this";
            }
        }

        // JNI 函数检测 (v34): Java_* → env/this, JNI_OnLoad → vm
        if (funcName.substr(0, 5) == "Java_") {
            semanticParamNames[firstParamReg] = "env";
            semanticParamNames[secondParamReg] = "this";
        } else if (funcName == "JNI_OnLoad" || funcName == "JNI_OnUnload") {
            semanticParamNames[firstParamReg] = "vm";
        }
        // 其余: env 模式在微码发射后检测（见下文）

        // ════════════════════════════════════════════════════════════
        // 反编译流水线
        // ════════════════════════════════════════════════════════════

        MicrocodeEmitter emitter;
        emitter.setIsAArch64(isAArch64);
        emitter.setIsThumb(isThumb);  // v9.13: Thumb mode PC offset correction

        // 设置全局名称、字符串引用、GOT→名称映射、ELF 原始数据
        if (!globalNames.empty()) emitter.setGlobalNames(globalNames);
        if (hasElfSyms && !elfSyms.stringRefs.empty())
            emitter.setStringRefs(elfSyms.stringRefs);
        // v10.1: 传递字符串长度与 UTF-16 字符串元数据（ADRP/LDR literal pool 识别）
        if (hasElfSyms && !elfSyms.stringLengths.empty())
            emitter.setStringLengths(elfSyms.stringLengths);
        if (hasElfSyms && !elfSyms.utf16Strings.empty())
            emitter.setUtf16Strings(elfSyms.utf16Strings);
        if (!elfRawData.empty())
            emitter.setElfData(elfRawData.data(), elfRawData.size(), 0);
        if (hasElfSyms && !elfSyms.gotToName.empty())
            emitter.setGotToName(elfSyms.gotToName);

        // v34: 构建 vtable 共识映射 + JNI 函数表注入（修复 vfunc_N → GetEnv/FindClass）
        if (hasElfSyms) {
            std::map<int, std::string> vtableConsensus;
            int entrySize = isAArch64 ? 8 : 4;
            // 统计各 offset 出现次数最多的函数名（跨虚表投票）
            {
                std::map<int, std::map<std::string, int>> offsetCounts;
                for (auto& [vaddr, vt] : elfSyms.vtableMap) {
                    for (size_t i = 0; i < vt.entries.size() && i < vt.entryNames.size(); i++) {
                        if (vt.entries[i] == 0) continue;
                        if (vt.entryNames[i].empty() || vt.entryNames[i] == "<null>") continue;
                        int offset = (int)(i * entrySize);
                        offsetCounts[offset][vt.entryNames[i]]++;
                    }
                }
                for (auto& [offset, nameCounts] : offsetCounts) {
                    std::string bestName;
                    int bestCount = 0;
                    for (auto& [name, count] : nameCounts) {
                        if (count > bestCount) { bestCount = count; bestName = name; }
                    }
                    if (!bestName.empty()) vtableConsensus[offset] = bestName;
                }
            }
            // JNIInvokeInterface (JavaVM 函数表): idx3-7
            static const struct { int idx; const char* name; } jniJavaVMTable[] = {
                {3, "DestroyJavaVM"}, {4, "AttachCurrentThread"}, {5, "DetachCurrentThread"},
                {6, "GetEnv"}, {7, "AttachCurrentThreadAsDaemon"},
            };
            for (auto& e : jniJavaVMTable) {
                int offset = e.idx * entrySize;
                if (vtableConsensus.find(offset) == vtableConsensus.end())
                    vtableConsensus[offset] = e.name;
            }
            // JNINativeInterface (JNIEnv 函数表): 常用偏移
            static const struct { int idx; const char* name; } jniEnvTable[] = {
                {4, "GetVersion"}, {6, "FindClass"}, {7, "FromReflectedMethod"},
                {8, "FromReflectedField"}, {9, "ToReflectedMethod"}, {16, "GetSuperclass"},
                {24, "GetMethodID"}, {25, "CallObjectMethod"}, {26, "CallBooleanMethod"},
                {27, "CallByteMethod"}, {28, "CallCharMethod"}, {29, "CallShortMethod"},
                {30, "CallIntMethod"}, {31, "CallLongMethod"}, {32, "CallFloatMethod"},
                {33, "CallDoubleMethod"}, {34, "CallVoidMethod"}, {46, "CallStaticObjectMethod"},
                {47, "CallStaticBooleanMethod"}, {48, "CallStaticByteMethod"},
                {49, "CallStaticCharMethod"}, {50, "CallStaticShortMethod"},
                {51, "CallStaticIntMethod"}, {52, "CallStaticLongMethod"},
                {53, "CallStaticFloatMethod"}, {54, "CallStaticDoubleMethod"},
                {55, "CallStaticVoidMethod"}, {56, "GetFieldID"}, {57, "GetStaticFieldID"},
                {128, "NewStringUTF"}, {130, "GetStringUTFChars"}, {131, "ReleaseStringUTFChars"},
                {192, "RegisterNatives"}, {193, "UnregisterNatives"}, {194, "MonitorEnter"},
                {195, "MonitorExit"}, {196, "GetJavaVM"},
            };
            for (auto& e : jniEnvTable) {
                int offset = e.idx * entrySize;
                if (vtableConsensus.find(offset) == vtableConsensus.end())
                    vtableConsensus[offset] = e.name;
            }
            if (!vtableConsensus.empty())
                emitter.setVtableConsensus(vtableConsensus);
            // v9.0: 已知虚表地址（VTABLE_PTR 检测用）
            std::set<uint64_t> vtableAddrs;
            for (auto& [vaddr, vt] : elfSyms.vtableMap)
                vtableAddrs.insert(vaddr);
            emitter.setVtableAddresses(vtableAddrs);
        }

        // 发射微码（使用 realAddr 作为起始地址）
        auto mba = emitter.emit(insns, realAddr);

        // 补充全局名称（从 ELF 符号表填充，若尚未存在）
        if (hasElfSyms) {
            for (auto& [addr, sym] : elfSyms.byAddr) {
                if (mba.global_names.find(addr) == mba.global_names.end())
                    mba.global_names[addr] = sym.name;
            }
            for (auto& [addr, str] : elfSyms.stringRefs)
                mba.string_refs[addr] = str;
            for (auto& [addr, name] : elfSyms.globalVars) {
                if (mba.global_names.find(addr) == mba.global_names.end())
                    mba.global_names[addr] = name;
            }
            // v10.1: 字符串长度与 UTF-16 同步（CPrinter 打印宽字符串）
            for (auto& [addr, len] : elfSyms.stringLengths)
                mba.string_lengths[addr] = len;
            for (auto& [addr, ws] : elfSyms.utf16Strings)
                mba.utf16_strings[addr] = ws;
            // v9.12: 数据/代码段范围（字面池校验：命中数据段的 PC 相对值解析为全局引用）
            mba.data_section_ranges = elfSyms.dataSectionRanges;
            mba.code_section_ranges = elfSyms.codeSectionRanges;
            // v34: 注册 vtable 条目到 global_names
            for (auto& [vtableAddr, vt] : elfSyms.vtableMap) {
                if (mba.global_names.find(vtableAddr) == mba.global_names.end())
                    mba.global_names[vtableAddr] = "vtable_" + vt.className;
                for (size_t i = 0; i < vt.entries.size() && i < vt.entryNames.size(); i++) {
                    if (vt.entries[i] == 0) continue;
                    if (mba.global_names.find(vt.entries[i]) == mba.global_names.end()) {
                        if (!vt.entryNames[i].empty() && vt.entryNames[i] != "<null>") {
                            mba.global_names[vt.entries[i]] = vt.entryNames[i];
                        }
                    }
                }
            }
        }
        for (auto& [addr, name] : globalNames) {
            if (mba.global_names.find(addr) == mba.global_names.end())
                mba.global_names[addr] = name;
        }

        // ── Phase 1b: 扫描所有 CALL 指令，注入 callee 符号名 ──
        // 确保内部函数（非 PLT，在 .symtab 而非 .dynsym 中）也能解析为真实名称
        if (hasElfSyms) {
            for (auto& blk : mba.blocks) {
                for (MicroInsn* insn = blk->head; insn; insn = insn->next) {
                    if ((insn->opcode == mc::OP_CALL) && insn->target_addr != 0) {
                        uint64_t callee = insn->target_addr;
                        // 若为 ARM32 且地址含 Thumb 位，清除以便查找符号表
                        if (!isAArch64) callee &= ~1ULL;
                        if (mba.global_names.find(callee) == mba.global_names.end()) {
                            auto it = elfSyms.byAddr.find(callee);
                            if (it == elfSyms.byAddr.end()) {
                                // 尝试用原始地址查找（含 Thumb）
                                it = elfSyms.byAddr.find(insn->target_addr);
                            }
                            if (it != elfSyms.byAddr.end() && !it->second.name.empty()) {
                                mba.global_names[insn->target_addr] = it->second.name;
                            } else {
                                // v34: ±4 偏移容错（函数入口对齐差异）
                                for (int64_t delta = -4; delta <= 4; delta += 4) {
                                    auto nit = elfSyms.byAddr.find(insn->target_addr + delta);
                                    if (nit != elfSyms.byAddr.end() && !nit->second.name.empty()) {
                                        mba.global_names[insn->target_addr] = nit->second.name;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Phase 5a (续): JNIEnv* 检测（在微码发射后，利用微码模式） ──
        // v8.1: 只在非 C++ 成员函数中检测 env 模式
        // C++ 成员函数的 vtable dispatch (ldr xN, [this]; blr xN) 会被误判为 env
        if (!isCppMemberFunc && semanticParamNames.find(firstParamReg) == semanticParamNames.end()) {
            bool firstParamIsEnv = false;
            for (auto& blk : mba.blocks) {
                for (MicroInsn* insn = blk->head; insn; insn = insn->next) {
                    if (insn->opcode == mc::OP_LOAD &&
                        insn->l.mem_base == 100 &&
                        insn->def_mreg >= 0) {
                        int loadedReg = insn->def_mreg;
                        MicroInsn* next = insn->next;
                        while (next && next->def_mreg != loadedReg) {
                            if ((next->opcode == mc::OP_ICALL || next->opcode == mc::OP_CALL) &&
                                next->l.mreg == loadedReg) {
                                firstParamIsEnv = true;
                                break;
                            }
                            next = next->next;
                        }
                        if (firstParamIsEnv) break;
                    }
                }
                if (firstParamIsEnv) break;
            }
            if (firstParamIsEnv) {
                semanticParamNames[firstParamReg] = "env";
            }
        }

        // ── Phase 5a (续): demangled C++ 签名参数推断 (对标 r2 r_type_func_guess) ──
        // C++ 成员函数 this 占用 mreg=100，显式参数从 mreg=101 开始
        {
            int paramOffset = isCppMemberFunc ? 1 : 0;
            auto demangledParamTypes = mc::CallingConvention::inferParamTypesFromDemangled(funcName, isAArch64);
            for (auto& [mreg, ipt] : demangledParamTypes) {
                int shiftedMreg = mreg + paramOffset;
                if (shiftedMreg >= 108) continue;
                int idx = shiftedMreg - 100;
                if (semanticParamNames.find(shiftedMreg) != semanticParamNames.end()) continue;
                const std::string& ctype = ipt.ctype;
                if (ctype.find("const ") == 0) {
                    std::string stripped = ctype.substr(6);
                    if (stripped.find("char*") != std::string::npos || stripped == "char*") {
                        semanticParamNames[shiftedMreg] = "str" + std::to_string(idx);
                    } else if (stripped.find('*') != std::string::npos) {
                        semanticParamNames[shiftedMreg] = "ptr" + std::to_string(idx);
                    } else {
                        semanticParamNames[shiftedMreg] = "arg" + std::to_string(idx);
                    }
                } else if (ctype.find("char*") != std::string::npos || ctype == "const char*") {
                    semanticParamNames[shiftedMreg] = "str" + std::to_string(idx);
                } else if (ctype.find('*') != std::string::npos) {
                    semanticParamNames[shiftedMreg] = "ptr" + std::to_string(idx);
                } else if (ctype == "int" || ctype == "long" || ctype == "size_t" ||
                           ctype == "unsigned int" || ctype == "unsigned long") {
                    semanticParamNames[shiftedMreg] = "val" + std::to_string(idx);
                } else if (ctype == "bool" || ctype == "_Bool") {
                    semanticParamNames[shiftedMreg] = "flag" + std::to_string(idx);
                } else if (ctype == "float" || ctype == "double") {
                    semanticParamNames[shiftedMreg] = "fp" + std::to_string(idx);
                } else {
                    semanticParamNames[shiftedMreg] = "arg" + std::to_string(idx);
                }
            }
        }

        // ── Phase 5a (续): 从 known callees 推断参数类型 (对标 r2 get_reg_type) ──
        {
            auto inferredTypes = mc::CallingConvention::inferParamTypesFromCallees(mba, isAArch64);
            for (auto& [mreg, ipt] : inferredTypes) {
                if (semanticParamNames.find(mreg) != semanticParamNames.end()) continue;
                int idx = mreg - 100;
                if (idx < 0 || idx > 7) continue;
                const std::string& ct = ipt.ctype;
                if (ct.find("char*") != std::string::npos || ct == "const char*") {
                    semanticParamNames[mreg] = "str" + std::to_string(idx);
                } else if (ct.find('*') != std::string::npos) {
                    semanticParamNames[mreg] = "ptr" + std::to_string(idx);
                } else if (ct == "int" || ct == "long" || ct == "size_t") {
                    semanticParamNames[mreg] = "val" + std::to_string(idx);
                }
            }
        }

        if (mba.numBlocks() == 0) {
            return env->NewStringUTF("// 反编译失败: 无法生成微码\n");
        }

        // v9.8: 解析所有 CALL 符号（设置 has_return、arg_count 等）
        symdb::KnownSymbolsDB resolveDB;
        symdb::resolveSymbols(mba, resolveDB);

        // v9.8: noreturn 调用后处理 — 清除 fallthrough successors
        // 否则 abort()/exit() 后的块会被当作可达，字面池数据被当作垃圾代码输出
        for (int b = 0; b < mba.numBlocks(); b++) {
            auto* blk = mba.getBlock(b);
            if (blk && blk->tail && blk->tail->opcode == mc::OP_CALL &&
                blk->tail->call_info) {
                // v10.7: has_return 仍为 true 但目标是已知 noreturn 函数 → 修正
                if (blk->tail->call_info->has_return &&
                    symdb::isKnownNoReturnName(blk->tail->call_info->target_name)) {
                    blk->tail->call_info->has_return = false;
                }
                if (!blk->tail->call_info->has_return) {
                    for (int succ : blk->successors) {
                        auto* succBlk = mba.getBlock(succ);
                        if (succBlk) {
                            auto& preds = succBlk->predecessors;
                            preds.erase(std::remove(preds.begin(), preds.end(), b), preds.end());
                        }
                    }
                    blk->successors.clear();
                }
            }
        }

        // Phase 2: SSA
        computeDominators(mba);
        computePostDominators(mba);
        computeDominanceFrontier(mba);
        placePhiNodes(mba);
        renameSSA(mba);
        addEntryDefs(mba);
        buildMemorySSA(mba);

        // Phase 3: 优化 (v34 optimizeV2: 12-pass 增强优化)
        optimizeV2(mba);

        // Phase 4: 结构化 (v34: FixpointStructurer 回退)
        std::unique_ptr<cfg::Region> region;
        {
            CFGStructurer structurer;
            region = structurer.structure(mba);
            if (!region) {
                cfg::FixpointStructurer fpStructurer;
                region = fpStructurer.structure(mba);
            }
        }

        // CTree 构建
        CTreeBuilder builder;
        // Phase 5: 语义参数命名 + in_argN 后备
        std::vector<std::pair<int, std::string>> paramMap;
        int maxParams = isAArch64 ? 8 : 4;
        for (int i = 0; i < maxParams; i++) {
            int mreg = 100 + i;
            auto it = semanticParamNames.find(mreg);
            if (it != semanticParamNames.end())
                paramMap.push_back({mreg, it->second});
            else
                paramMap.push_back({mreg, "in_arg" + std::to_string(i)});
        }
        // v11.5: std::string 签名映射补充（修复构造函数/析构函数参数数量错误）
        // buildCallExpr 查找签名时 callee_name 是 demangled 名，normalizeSigName 会尝试
        // 包括简化后的 "std::string::basic_string" 形式，故这里用简化名
        if (sigMap.find("std::string::basic_string") == sigMap.end())
            sigMap["std::string::basic_string"] = "int32_t std::string::basic_string(char * a1, char * a2, int32_t * a3);";
        if (sigMap.find("std::string::_Rep::_M_destroy") == sigMap.end())
            sigMap["std::string::_Rep::_M_destroy"] = "int32_t std::string::_Rep::_M_destroy(void * this, int32_t * a1);";
        if (sigMap.find("std::string::append") == sigMap.end())
            sigMap["std::string::append"] = "int32_t std::string::append(void * this, char * a1);";
        if (sigMap.find("std::string::assign") == sigMap.end())
            sigMap["std::string::assign"] = "int32_t std::string::assign(void * this, char * a1);";
        if (sigMap.find("std::basic_string::basic_string") == sigMap.end())
            sigMap["std::basic_string::basic_string"] = "int32_t std::basic_string::basic_string(char * a1, char * a2, int32_t * a3);";
        if (sigMap.find("std::basic_string::_Rep::_M_destroy") == sigMap.end())
            sigMap["std::basic_string::_Rep::_M_destroy"] = "int32_t std::basic_string::_Rep::_M_destroy(void * this, int32_t * a1);";
        if (sigMap.find("std::basic_string::append") == sigMap.end())
            sigMap["std::basic_string::append"] = "int32_t std::basic_string::append(void * this, char * a1);";
        if (sigMap.find("std::basic_string::assign") == sigMap.end())
            sigMap["std::basic_string::assign"] = "int32_t std::basic_string::assign(void * this, char * a1);";

        builder.setParamNames(paramMap);
        builder.setSignatureMap(sigMap);
        builder.setIsAArch64(isAArch64);
        builder.setFuncName(funcName);
        builder.setIsMemberFunction(isCppMemberFunc);

        // v9.0: 间接调用解析器（虚函数调用 → vtable 函数名）
        mc::IndirectCallResolver icallResolver(elfSyms, isAArch64);
        builder.setIndirectCallResolver(&icallResolver);

        // Phase 3: KnownSymbolsDB（调用返回类型推断）
        symdb::KnownSymbolsDB knownSymbols;
        builder.setKnownSymbolsDB(&knownSymbols);

        // Phase 5: secondaryNames（ELF 符号 + vtable 条目）
        std::map<uint64_t, std::string> secondaryNames;
        for (auto& [addr, sym] : elfSyms.byAddr) {
            if (!sym.name.empty()) secondaryNames[addr] = sym.name;
        }
        for (auto& [vtableAddr, vt] : elfSyms.vtableMap) {
            for (size_t i = 0; i < vt.entries.size() && i < vt.entryNames.size(); i++) {
                if (vt.entries[i] != 0 && !vt.entryNames[i].empty() && vt.entryNames[i] != "<null>") {
                    secondaryNames[vt.entries[i]] = vt.entryNames[i];
                }
            }
        }
        if (!secondaryNames.empty()) builder.setSecondaryNames(&secondaryNames);

        StmtPtr body = nullptr;
        if (region) {
            body = builder.build(*region, mba);
        } else {
            // v4.2: safety net — region is null, return raw disassembly
            std::string diag = "// CFG结构化失败: region为空\n";
            diag += "// 原始指令:\n";
            for (auto& insn : insns) {
                char buf[128];
                snprintf(buf, sizeof(buf), "//   0x%llx: %s %s\n",
                         (unsigned long long)insn.addr,
                         insn.mnemonic.c_str(), insn.op_str.c_str());
                diag += buf;
            }
            return env->NewStringUTF(diag.c_str());
        }

        if (!body) {
            // v4.2: safety net — body is null, return raw disassembly
            std::string diag = "// CTree构建失败: body为空\n";
            diag += "// 原始指令:\n";
            for (auto& insn : insns) {
                char buf[128];
                snprintf(buf, sizeof(buf), "//   0x%llx: %s %s\n",
                         (unsigned long long)insn.addr,
                         insn.mnemonic.c_str(), insn.op_str.c_str());
                diag += buf;
            }
            return env->NewStringUTF(diag.c_str());
        }

        if (body) beautify(body);

        // v8.1: 空函数体检查 (PLT stub 或无法解析)
        bool bodyEmpty = !body || insns.empty();
        if (!bodyEmpty && body->type == NT_BLOCK) {
            auto* blk = static_cast<Block*>(body.get());
            if (blk->statements.empty()) bodyEmpty = true;
        }
        if (bodyEmpty) {
            std::string diag = "// 反编译失败: 空函数体 (可能是 PLT stub 或无法解析)\n";
            diag += "// 原始指令:\n";
            for (auto& insn : insns) {
                char buf[128];
                snprintf(buf, sizeof(buf), "//   0x%llx: %s %s\n",
                         (unsigned long long)insn.addr,
                         insn.mnemonic.c_str(), insn.op_str.c_str());
                diag += buf;
            }
            return env->NewStringUTF(diag.c_str());
        }

        // 组装 Function
        Function func;
        func.name = funcName;
        func.entry_addr = funcAddr;   // 保留原始地址（可能含 Thumb 位）
        func.body = std::move(body);
        // ARM32: 返回 int32_t, AArch64: 返回 uint64_t
        func.return_type.category = isAArch64 ? CType::TC_UINT64 : CType::TC_INT32;
        func.return_type.width = isAArch64 ? 8 : 4;
        if (!isAArch64) func.return_type.is_signed = true;  // v9.32: ARM32 默认 int32_t

        // v34: 从 demangled 签名解析返回类型 (对标 r2 r_type_func_guess)
        {
            bool returnTypeSet = false;
            if (funcName.find('(') != std::string::npos) {
                auto parsed = mc::CallingConvention::parseSignature(funcName);
                if (parsed.valid && !parsed.return_type.empty()) {
                    const std::string& rt = parsed.return_type;
                    if (rt == "int" || rt == "int32_t") {
                        func.return_type.category = CType::TC_INT32;
                        func.return_type.width = 4;
                        func.return_type.is_signed = true;
                        returnTypeSet = true;
                    } else if (rt == "unsigned int" || rt == "uint32_t") {
                        func.return_type.category = CType::TC_UINT32;
                        func.return_type.width = 4;
                        returnTypeSet = true;
                    } else if (rt == "long" || rt == "int64_t" || rt == "long long") {
                        func.return_type.category = CType::TC_INT64;
                        func.return_type.width = 8;
                        func.return_type.is_signed = true;
                        returnTypeSet = true;
                    } else if (rt == "unsigned long" || rt == "uint64_t") {
                        func.return_type.category = CType::TC_UINT64;
                        func.return_type.width = 8;
                        returnTypeSet = true;
                    } else if (rt == "bool" || rt == "_Bool") {
                        func.return_type.category = CType::TC_BOOL;
                        func.return_type.width = 1;
                        returnTypeSet = true;
                    } else if (rt == "float") {
                        func.return_type.category = CType::TC_FLOAT;
                        func.return_type.width = 4;
                        returnTypeSet = true;
                    } else if (rt == "double") {
                        func.return_type.category = CType::TC_DOUBLE;
                        func.return_type.width = 8;
                        returnTypeSet = true;
                    } else if (rt.find('*') != std::string::npos) {
                        func.return_type.category = CType::TC_POINTER;
                        func.return_type.width = isAArch64 ? 8 : 4;
                        returnTypeSet = true;
                    }
                }
            }
            // 析构函数 → void
            if (!returnTypeSet && funcName.find("::~") != std::string::npos) {
                func.return_type.category = CType::TC_VOID;
                func.return_type.width = 0;
                returnTypeSet = true;
            }
            (void)returnTypeSet;
        }

        // 签名注释
        auto sigIt = sigMap.find(funcName);
        if (sigIt != sigMap.end()) {
            func.comment = "// " + sigIt->second;
        }

        // 栈槽变量
        for (auto& [off, slot] : builder.getStackSlots()) {
            func.local_vars.push_back({slot.first, slot.second});
        }

        // 参数列表（使用实际参数数量 + 语义名称）
        {
            int actualCount = builder.getActualParamCount();
            if (actualCount < 0) actualCount = 0;
            int maxArgs = isAArch64 ? 8 : 4;

            // v8.1: C++ 成员函数参数数量修正
            // 若 demangled 签名无显式参数，实际只有 this；否则上限 = 显式参数 + 1
            if (isCppMemberFunc && actualCount > 1) {
                auto dParams = mc::CallingConvention::inferParamTypesFromDemangled(funcName, isAArch64);
                if (dParams.empty()) {
                    actualCount = 1;  // 只有 this
                } else {
                    actualCount = std::min(actualCount, (int)dParams.size() + 1);
                }
            }
            // v8.5: C++ 成员函数至少有一个参数 (this)
            if (isCppMemberFunc && actualCount == 0) {
                actualCount = 1;
            }

            if (actualCount > maxArgs) actualCount = maxArgs;
            for (int i = 0; i < actualCount; i++) {
                int mreg = 100 + i;
                // v10.5: 跳过被 trimParameterList 修剪的参数
                // 这些寄存器在 SSA v0 从未被读取（在首次使用前已被重定义），不是真实参数
                if (!builder.isParamActive(mreg)) continue;
                std::string argName;
                auto sit = semanticParamNames.find(mreg);
                if (sit != semanticParamNames.end())
                    argName = sit->second;
                else
                    argName = "in_arg" + std::to_string(i);
                CType t = builder.getParamType(i);
                func.params.push_back({argName, t});
            }
            // 若没有参数，加一个占位
            if (func.params.empty()) {
                CType t;
                t.category = isAArch64 ? CType::TC_UINT64 : CType::TC_INT32;
                t.width = isAArch64 ? 8 : 4;
                func.params.push_back({"in_arg0", t});
            }
        }

        // 打印
        CPrinter printer;
        printer.setGlobalNames(&mba.global_names);
        printer.setStringRefs(&mba.string_refs);
        std::string cCode = printer.printFunction(func);

        return env->NewStringUTF(cCode.c_str());

    } catch (const std::exception& e) {
        std::string err = "// 反编译异常: ";
        err += e.what();
        err += "\n";
        return env->NewStringUTF(err.c_str());
    } catch (...) {
        return env->NewStringUTF("// 反编译异常: 未知错误\n");
    }
}
