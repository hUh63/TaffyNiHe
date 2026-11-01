//
// native-lib.cpp - JNI entry point and format detection for DSMCP.
//
// This is the main native library loaded by Kotlin (libdsmcp_native.so).
// It provides format detection and delegates to elf_bridge / pe_bridge
// for format-specific parsing and patching operations.
//
#include <jni.h>
#include <string>
#include <vector>
#include <cstdint>
#include <cstring>

#include "hex_util.h"
#include "elf_parser.h"
#include "pe_parser.h"

namespace {

// Throw a RuntimeException with the given message.
void throw_runtime(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls != nullptr) env->ThrowNew(cls, message);
    env->DeleteLocalRef(cls);
}

// Convert a jbyteArray to a std::vector<uint8_t>.
std::vector<uint8_t> jbytes_to_vector(JNIEnv* env, jbyteArray arr) {
    if (!arr) return {};
    jsize len = env->GetArrayLength(arr);
    if (len <= 0) return {};
    std::vector<uint8_t> data(static_cast<size_t>(len));
    env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte*>(data.data()));
    return data;
}

// Convert a std::vector<uint8_t> to a jbyteArray.
jbyteArray vector_to_jbytes(JNIEnv* env, const std::vector<uint8_t>& data) {
    jbyteArray result = env->NewByteArray(static_cast<jsize>(data.size()));
    if (result == nullptr) return nullptr;
    if (!data.empty()) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(data.size()),
                                reinterpret_cast<const jbyte*>(data.data()));
    }
    return result;
}

// Convert a jstring to a std::string.
std::string jstr_to_std(JNIEnv* env, jstring jstr) {
    if (!jstr) return {};
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string s(chars ? chars : "");
    env->ReleaseStringUTFChars(jstr, chars);
    return s;
}

}  // namespace

extern "C" {

// ── Format detection ──
// Returns "elf", "pe", or "unknown" based on magic bytes.
JNIEXPORT jstring JNICALL
Java_com_dsmcp_tool_engine_NativeBridge_nativeDetectFormat(
        JNIEnv* env, jobject, jbyteArray jbytes) {
    if (!jbytes) return env->NewStringUTF("unknown");
    jsize len = env->GetArrayLength(jbytes);
    if (len < 4) return env->NewStringUTF("unknown");
    jbyte buf[8];
    jsize read_len = std::min(len, (jsize)8);
    env->GetByteArrayRegion(jbytes, 0, read_len, buf);

    // ELF: \x7fELF
    if ((uint8_t)buf[0] == 0x7f && buf[1] == 'E' && buf[2] == 'L' && buf[3] == 'F') {
        return env->NewStringUTF("elf");
    }
    // PE/DLL: MZ
    if ((uint8_t)buf[0] == 'M' && (uint8_t)buf[1] == 'Z') {
        // Verify PE signature exists
        if (len >= 64) {
            uint8_t header[64];
            env->GetByteArrayRegion(jbytes, 0, 64, reinterpret_cast<jbyte*>(header));
            uint32_t pe_off = dsmcp::read_le32(header + 0x3C);
            if (pe_off + 4 < (uint32_t)len) {
                uint8_t sig[4];
                env->GetByteArrayRegion(jbytes, pe_off, 4, reinterpret_cast<jbyte*>(sig));
                if (sig[0] == 'P' && sig[1] == 'E' && sig[2] == 0 && sig[3] == 0) {
                    return env->NewStringUTF("pe");
                }
            }
            // MZ without PE signature - still might be a DOS executable
            return env->NewStringUTF("pe");
        }
        return env->NewStringUTF("pe");
    }
    return env->NewStringUTF("unknown");
}

// ── Hex utilities exposed to Kotlin ──
JNIEXPORT jstring JNICALL
Java_com_dsmcp_tool_engine_NativeBridge_nativeToHex(
        JNIEnv* env, jobject, jbyteArray jbytes, jboolean spaced) {
    auto data = jbytes_to_vector(env, jbytes);
    if (data.empty()) return env->NewStringUTF("");
    std::string hex = dsmcp::to_hex(data, spaced == JNI_TRUE);
    return env->NewStringUTF(hex.c_str());
}

JNIEXPORT jbyteArray JNICALL
Java_com_dsmcp_tool_engine_NativeBridge_nativeFromHex(
        JNIEnv* env, jobject, jstring jhex) {
    std::string hex = jstr_to_std(env, jhex);
    auto bytes = dsmcp::from_hex(hex);
    return vector_to_jbytes(env, bytes);
}

// ── Hex dump (formatted hex+offset display) ──
JNIEXPORT jstring JNICALL
Java_com_dsmcp_tool_engine_NativeBridge_nativeHexDump(
        JNIEnv* env, jobject, jbyteArray jbytes, jint offset, jint length) {
    auto data = jbytes_to_vector(env, jbytes);
    if (data.empty() || offset < 0 || (size_t)offset >= data.size()) {
        return env->NewStringUTF("");
    }
    size_t start = static_cast<size_t>(offset);
    size_t end = std::min(data.size(), start + (size_t)(length > 0 ? length : 256));
    std::string out;
    char line[128];
    for (size_t i = start; i < end; i += 16) {
        // Offset
        std::snprintf(line, sizeof(line), "%08zx  ", i);
        out += line;
        // Hex bytes
        for (size_t j = 0; j < 16; ++j) {
            if (i + j < end) {
                std::snprintf(line, sizeof(line), "%02x ", data[i + j]);
                out += line;
            } else {
                out += "   ";
            }
            if (j == 7) out += " ";
        }
        out += " |";
        // ASCII
        for (size_t j = 0; j < 16; ++j) {
            if (i + j < end) {
                unsigned char c = data[i + j];
                out += (c >= 0x20 && c < 0x7f) ? static_cast<char>(c) : '.';
            } else {
                out += ' ';
            }
        }
        out += "|\n";
    }
    return env->NewStringUTF(out.c_str());
}

// ── Byte search ──
JNIEXPORT jstring JNICALL
Java_com_dsmcp_tool_engine_NativeBridge_nativeSearchBytes(
        JNIEnv* env, jobject, jbyteArray jbytes, jstring jpattern) {
    auto data = jbytes_to_vector(env, jbytes);
    auto pattern = dsmcp::from_hex(jstr_to_std(env, jpattern));
    if (data.empty() || pattern.empty()) {
        return env->NewStringUTF("{\"hits\":[]}");
    }
    dsmcp::JsonBuilder j;
    j.begin_array("hits");
    size_t count = 0;
    for (size_t i = 0; i + pattern.size() <= data.size() && count < 1000; ++i) {
        bool match = true;
        for (size_t k = 0; k < pattern.size(); ++k) {
            if (data[i + k] != pattern[k]) { match = false; break; }
        }
        if (match) {
            j.begin_object();
            j.num("offset", i);
            j.end_object();
            ++count;
        }
    }
    j.end_array();
    j.num("count", count);
    return env->NewStringUTF(j.build().c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_dsmcp_tool_engine_NativeBridge_nativeAvailable(JNIEnv*, jobject) {
    return JNI_TRUE;
}

}  // extern "C"
