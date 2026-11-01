//
// jni_helpers.h - Shared JNI helper functions for DSMCP bridge files.
//
#pragma once

#include <jni.h>
#include <string>
#include <vector>
#include <cstdint>
#include <cstring>

namespace dsmcp_jni {

inline void throw_runtime(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls != nullptr) env->ThrowNew(cls, message);
    env->DeleteLocalRef(cls);
}

inline std::vector<uint8_t> jbytes_to_vector(JNIEnv* env, jbyteArray arr) {
    if (!arr) return {};
    jsize len = env->GetArrayLength(arr);
    if (len <= 0) return {};
    std::vector<uint8_t> data(static_cast<size_t>(len));
    env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte*>(data.data()));
    return data;
}

inline jbyteArray vector_to_jbytes(JNIEnv* env, const std::vector<uint8_t>& data) {
    jbyteArray result = env->NewByteArray(static_cast<jsize>(data.size()));
    if (result == nullptr) return nullptr;
    if (!data.empty()) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(data.size()),
                                reinterpret_cast<const jbyte*>(data.data()));
    }
    return result;
}

inline std::string jstr_to_std(JNIEnv* env, jstring jstr) {
    if (!jstr) return {};
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string s(chars ? chars : "");
    env->ReleaseStringUTFChars(jstr, chars);
    return s;
}

}  // namespace dsmcp_jni
