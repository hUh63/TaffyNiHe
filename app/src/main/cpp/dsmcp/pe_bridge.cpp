//
// pe_bridge.cpp - JNI bridge for PE (.dll) parsing and patching operations.
//
#include <jni.h>
#include <string>
#include <vector>
#include <cstdint>

#include "jni_helpers.h"
#include "pe_parser.h"
#include "json_builder.h"

using namespace dsmcp;
using namespace dsmcp_jni;

extern "C" {

// Parse a PE/DLL file and return its structure as JSON.
JNIEXPORT jstring JNICALL
Java_com_dsmcp_tool_engine_NativeBridge_nativePeParse(
        JNIEnv* env, jobject, jbyteArray jbytes) {
    auto data = jbytes_to_vector(env, jbytes);
    if (data.empty()) return env->NewStringUTF("{\"error\":\"empty\"}");
    PeFile pe = parse_pe(data.data(), data.size());
    return env->NewStringUTF(pe_to_json(pe).c_str());
}

// Patch bytes at a virtual address (VA = imageBase + RVA).
JNIEXPORT jbyteArray JNICALL
Java_com_dsmcp_tool_engine_NativeBridge_nativePePatchVa(
        JNIEnv* env, jobject, jbyteArray jbytes, jlong va, jbyteArray jpatch) {
    auto data = jbytes_to_vector(env, jbytes);
    auto patch = jbytes_to_vector(env, jpatch);
    if (data.empty() || patch.empty()) return env->NewByteArray(0);
    auto result = pe_patch_va(data.data(), data.size(),
                              static_cast<uint64_t>(va),
                              patch.data(), patch.size());
    return vector_to_jbytes(env, result);
}

// Patch bytes at an RVA.
JNIEXPORT jbyteArray JNICALL
Java_com_dsmcp_tool_engine_NativeBridge_nativePePatchRva(
        JNIEnv* env, jobject, jbyteArray jbytes, jlong rva, jbyteArray jpatch) {
    auto data = jbytes_to_vector(env, jbytes);
    auto patch = jbytes_to_vector(env, jpatch);
    if (data.empty() || patch.empty()) return env->NewByteArray(0);
    auto result = pe_patch_rva(data.data(), data.size(),
                               static_cast<uint32_t>(rva),
                               patch.data(), patch.size());
    return vector_to_jbytes(env, result);
}

// Patch bytes at a file offset.
JNIEXPORT jbyteArray JNICALL
Java_com_dsmcp_tool_engine_NativeBridge_nativePePatchOffset(
        JNIEnv* env, jobject, jbyteArray jbytes, jlong offset, jbyteArray jpatch) {
    auto data = jbytes_to_vector(env, jbytes);
    auto patch = jbytes_to_vector(env, jpatch);
    if (data.empty() || patch.empty()) return env->NewByteArray(0);
    auto result = pe_patch_offset(data.data(), data.size(),
                                  static_cast<uint64_t>(offset),
                                  patch.data(), patch.size());
    return vector_to_jbytes(env, result);
}

// Get the content of a named section.
JNIEXPORT jbyteArray JNICALL
Java_com_dsmcp_tool_engine_NativeBridge_nativePeGetSectionContent(
        JNIEnv* env, jobject, jbyteArray jbytes, jstring jsectionName) {
    auto data = jbytes_to_vector(env, jbytes);
    auto name = jstr_to_std(env, jsectionName);
    if (data.empty() || name.empty()) return env->NewByteArray(0);
    auto result = pe_get_section_content(data.data(), data.size(), name);
    return vector_to_jbytes(env, result);
}

// Replace the content of a named section.
JNIEXPORT jbyteArray JNICALL
Java_com_dsmcp_tool_engine_NativeBridge_nativePeSetSectionContent(
        JNIEnv* env, jobject, jbyteArray jbytes, jstring jsectionName, jbyteArray jcontent) {
    auto data = jbytes_to_vector(env, jbytes);
    auto name = jstr_to_std(env, jsectionName);
    auto content = jbytes_to_vector(env, jcontent);
    if (data.empty() || name.empty()) return env->NewByteArray(0);
    auto result = pe_set_section_content(data.data(), data.size(), name,
                                         content.data(), content.size());
    return vector_to_jbytes(env, result);
}

// Resolve a virtual address to a file offset.
JNIEXPORT jlong JNICALL
Java_com_dsmcp_tool_engine_NativeBridge_nativePeVaToOffset(
        JNIEnv* env, jobject, jbyteArray jbytes, jlong va) {
    auto data = jbytes_to_vector(env, jbytes);
    if (data.empty()) return -1;
    PeFile pe = parse_pe(data.data(), data.size());
    if (!pe.valid) return -1;
    uint64_t offset = pe_va_to_offset(pe, static_cast<uint64_t>(va));
    return (offset == UINT64_MAX) ? -1 : static_cast<jlong>(offset);
}

// Resolve an RVA to a file offset.
JNIEXPORT jlong JNICALL
Java_com_dsmcp_tool_engine_NativeBridge_nativePeRvaToOffset(
        JNIEnv* env, jobject, jbyteArray jbytes, jlong rva) {
    auto data = jbytes_to_vector(env, jbytes);
    if (data.empty()) return -1;
    PeFile pe = parse_pe(data.data(), data.size());
    if (!pe.valid) return -1;
    uint64_t offset = pe_rva_to_offset(pe, static_cast<uint32_t>(rva));
    return (offset == UINT64_MAX) ? -1 : static_cast<jlong>(offset);
}

}  // extern "C"
