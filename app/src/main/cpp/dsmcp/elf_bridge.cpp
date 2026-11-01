//
// elf_bridge.cpp - JNI bridge for ELF (.so) parsing and patching operations.
//
#include <jni.h>
#include <string>
#include <vector>
#include <cstdint>

#include "jni_helpers.h"
#include "elf_parser.h"
#include "json_builder.h"

using namespace dsmcp;
using namespace dsmcp_jni;

extern "C" {

// Parse an ELF file and return its structure as JSON.
JNIEXPORT jstring JNICALL
Java_com_dsmcp_tool_engine_NativeBridge_nativeElfParse(
        JNIEnv* env, jobject, jbyteArray jbytes) {
    auto data = jbytes_to_vector(env, jbytes);
    if (data.empty()) return env->NewStringUTF("{\"error\":\"empty\"}");
    ElfFile elf = parse_elf(data.data(), data.size());
    return env->NewStringUTF(elf_to_json(elf).c_str());
}

// Patch bytes at a virtual address. Returns the patched byte array.
JNIEXPORT jbyteArray JNICALL
Java_com_dsmcp_tool_engine_NativeBridge_nativeElfPatchVa(
        JNIEnv* env, jobject, jbyteArray jbytes, jlong va, jbyteArray jpatch) {
    auto data = jbytes_to_vector(env, jbytes);
    auto patch = jbytes_to_vector(env, jpatch);
    if (data.empty() || patch.empty()) return env->NewByteArray(0);
    auto result = elf_patch_va(data.data(), data.size(),
                               static_cast<uint64_t>(va),
                               patch.data(), patch.size());
    return vector_to_jbytes(env, result);
}

// Patch bytes at a file offset.
JNIEXPORT jbyteArray JNICALL
Java_com_dsmcp_tool_engine_NativeBridge_nativeElfPatchOffset(
        JNIEnv* env, jobject, jbyteArray jbytes, jlong offset, jbyteArray jpatch) {
    auto data = jbytes_to_vector(env, jbytes);
    auto patch = jbytes_to_vector(env, jpatch);
    if (data.empty() || patch.empty()) return env->NewByteArray(0);
    auto result = elf_patch_offset(data.data(), data.size(),
                                   static_cast<uint64_t>(offset),
                                   patch.data(), patch.size());
    return vector_to_jbytes(env, result);
}

// Get the content of a named section.
JNIEXPORT jbyteArray JNICALL
Java_com_dsmcp_tool_engine_NativeBridge_nativeElfGetSectionContent(
        JNIEnv* env, jobject, jbyteArray jbytes, jstring jsectionName) {
    auto data = jbytes_to_vector(env, jbytes);
    auto name = jstr_to_std(env, jsectionName);
    if (data.empty() || name.empty()) return env->NewByteArray(0);
    auto result = elf_get_section_content(data.data(), data.size(), name);
    return vector_to_jbytes(env, result);
}

// Replace the content of a named section.
JNIEXPORT jbyteArray JNICALL
Java_com_dsmcp_tool_engine_NativeBridge_nativeElfSetSectionContent(
        JNIEnv* env, jobject, jbyteArray jbytes, jstring jsectionName, jbyteArray jcontent) {
    auto data = jbytes_to_vector(env, jbytes);
    auto name = jstr_to_std(env, jsectionName);
    auto content = jbytes_to_vector(env, jcontent);
    if (data.empty() || name.empty()) return env->NewByteArray(0);
    auto result = elf_set_section_content(data.data(), data.size(), name,
                                          content.data(), content.size());
    return vector_to_jbytes(env, result);
}

// Add an exported function symbol.
JNIEXPORT jbyteArray JNICALL
Java_com_dsmcp_tool_engine_NativeBridge_nativeElfAddExportedFunction(
        JNIEnv* env, jobject, jbyteArray jbytes, jlong addr, jstring jname) {
    auto data = jbytes_to_vector(env, jbytes);
    auto name = jstr_to_std(env, jname);
    if (data.empty() || name.empty()) return env->NewByteArray(0);
    auto result = elf_add_exported_function(data.data(), data.size(),
                                            static_cast<uint64_t>(addr), name);
    return vector_to_jbytes(env, result);
}

// Remove a symbol by name.
JNIEXPORT jbyteArray JNICALL
Java_com_dsmcp_tool_engine_NativeBridge_nativeElfRemoveSymbol(
        JNIEnv* env, jobject, jbyteArray jbytes, jstring jname) {
    auto data = jbytes_to_vector(env, jbytes);
    auto name = jstr_to_std(env, jname);
    if (data.empty() || name.empty()) return env->NewByteArray(0);
    auto result = elf_remove_symbol(data.data(), data.size(), name);
    return vector_to_jbytes(env, result);
}

// Rename a symbol (new name must be same length or shorter).
JNIEXPORT jbyteArray JNICALL
Java_com_dsmcp_tool_engine_NativeBridge_nativeElfRenameSymbol(
        JNIEnv* env, jobject, jbyteArray jbytes, jstring joldName, jstring jnewName) {
    auto data = jbytes_to_vector(env, jbytes);
    auto oldName = jstr_to_std(env, joldName);
    auto newName = jstr_to_std(env, jnewName);
    if (data.empty() || oldName.empty()) return env->NewByteArray(0);
    auto result = elf_rename_symbol(data.data(), data.size(), oldName, newName);
    return vector_to_jbytes(env, result);
}

// Extract strings from the ELF and return as JSON.
JNIEXPORT jstring JNICALL
Java_com_dsmcp_tool_engine_NativeBridge_nativeElfExtractStrings(
        JNIEnv* env, jobject, jbyteArray jbytes, jint maxCount) {
    auto data = jbytes_to_vector(env, jbytes);
    if (data.empty()) return env->NewStringUTF("{\"strings\":[]}");
    ElfFile elf = parse_elf(data.data(), data.size());
    if (!elf.valid) return env->NewStringUTF("{\"error\":\"parse_failed\"}");
    size_t max = (maxCount > 0) ? static_cast<size_t>(maxCount) : 5000;
    auto strings = elf_extract_strings(data.data(), data.size(), elf, max);

    JsonBuilder j;
    j.num("count", strings.size());
    j.begin_array("strings");
    for (const auto& s : strings) {
        j.begin_object();
        j.num("offset", s.offset);
        j.cstr("section", s.section.c_str());
        j.cstr("text", s.text.c_str());
        j.end_object();
    }
    j.end_array();
    return env->NewStringUTF(j.build().c_str());
}

// Resolve a virtual address to a file offset.
JNIEXPORT jlong JNICALL
Java_com_dsmcp_tool_engine_NativeBridge_nativeElfVaToOffset(
        JNIEnv* env, jobject, jbyteArray jbytes, jlong va) {
    auto data = jbytes_to_vector(env, jbytes);
    if (data.empty()) return -1;
    ElfFile elf = parse_elf(data.data(), data.size());
    if (!elf.valid) return -1;
    uint64_t offset = va_to_offset(elf, static_cast<uint64_t>(va));
    return (offset == UINT64_MAX) ? -1 : static_cast<jlong>(offset);
}

}  // extern "C"
