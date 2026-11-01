//
// dotnet_bridge.cpp - JNI bridge for .NET (.dll) metadata parsing, IL
// disassembly, and patching operations.
//
// Provides the JNI interface between Kotlin (NativeBridge.kt) and the
// C++ .NET metadata parser (dotnet_parser.h + il_disasm.h).
//
#include <jni.h>
#include <string>
#include <vector>
#include <cstdint>
#include <sstream>

#include "jni_helpers.h"
#include "pe_parser.h"
#include "dotnet_parser.h"
#include "il_disasm.h"
#include "json_builder.h"

using namespace dsmcp;
using namespace dsmcp_jni;

extern "C" {

// ── Helper: build error JSON with detail ──
static std::string error_json(const std::string& msg) {
    JsonBuilder jb;
    jb.str("error", msg);
    return jb.build();
}

// ── Helper: parse PE + .NET, return detailed error on failure ──
struct DnParseResult {
    PeFile pe;
    DotNetMetadata dn;
    std::string error;
    bool ok = false;
};

static DnParseResult parse_dn_full(const uint8_t* data, size_t len) {
    DnParseResult r;
    r.pe = parse_pe(data, len);
    if (!r.pe.valid) {
        r.error = "PE parse failed: " + r.pe.error;
        return r;
    }
    r.dn = parse_dotnet(data, len, r.pe);
    if (!r.dn.valid) {
        r.error = "Not a .NET/Mono assembly: " + r.dn.error;
        return r;
    }
    r.ok = true;
    return r;
}

// ── Detect .NET/Mono assembly and return diagnostic info ──
JNIEXPORT jstring JNICALL
Java_com_dsmcp_tool_engine_NativeBridge_nativeDotnetDetect(
        JNIEnv* env, jobject, jbyteArray jbytes) {
    auto data = jbytes_to_vector(env, jbytes);
    if (data.empty()) return env->NewStringUTF(error_json("empty data").c_str());

    const uint8_t* d = data.data();
    size_t len = data.size();

    JsonBuilder jb;
    jb.num("fileSize", static_cast<uint64_t>(len));

    // PE detection
    bool hasMZ = (len >= 2 && read_le16(d) == 0x5A4D);
    jb.boolean("hasMZHeader", hasMZ);

    if (!hasMZ) {
        jb.boolean("isDotNet", false);
        jb.str("error", "No MZ header - not a PE/DLL file");
        return env->NewStringUTF(jb.build().c_str());
    }

    uint32_t peOff = 0;
    bool hasPE = false;
    if (len >= 0x40) {
        peOff = read_le32(d + 0x3C);
        if (peOff + 4 <= len && read_le32(d + peOff) == 0x00004550) {
            hasPE = true;
        }
    }
    jb.boolean("hasPEHeader", hasPE);

    if (!hasPE) {
        jb.boolean("isDotNet", false);
        jb.str("error", "No PE signature found");
        return env->NewStringUTF(jb.build().c_str());
    }

    // Parse PE
    PeFile pe = parse_pe(d, len);
    jb.boolean("peValid", pe.valid);
    if (!pe.valid) {
        jb.boolean("isDotNet", false);
        jb.str("error", "PE parse failed: " + pe.error);
        return env->NewStringUTF(jb.build().c_str());
    }

    // Machine type as hex string
    {
        char machineBuf[8];
        std::snprintf(machineBuf, sizeof(machineBuf), "0x%04X", pe.machine);
        jb.str("peMachine", machineBuf);
    }
    jb.boolean("peIs64", pe.is64);
    jb.num("peSections", pe.numberOfSections);

    // Check data directory #14 (CLR header)
    const uint8_t* coff = d + peOff + 4;
    uint16_t optSize = read_le16(coff + 16);
    const uint8_t* optHdr = coff + 20;
    bool is64 = (read_le16(optHdr) == 0x20B);
    uint32_t numDataDirs;
    const uint8_t* dataDirs;
    if (is64) {
        numDataDirs = read_le32(optHdr + 108);
        dataDirs = optHdr + 112;
    } else {
        numDataDirs = read_le32(optHdr + 92);
        dataDirs = optHdr + 96;
    }

    bool hasClrDir = (numDataDirs > 14);
    jb.boolean("hasClrDataDir", hasClrDir);

    uint32_t clrRva = 0, clrSize = 0;
    if (hasClrDir) {
        clrRva = read_le32(dataDirs + 14 * 8);
        clrSize = read_le32(dataDirs + 14 * 8 + 4);
    }
    jb.num("clrRva", clrRva);
    jb.num("clrSize", clrSize);

    // Search for BSJB signature
    size_t sectionDataStart = peOff + 24 + optSize + (size_t)pe.numberOfSections * 40;
    uint64_t bsjbOff = find_valid_bsjb(d, len, sectionDataStart);
    if (bsjbOff == UINT64_MAX) {
        bsjbOff = find_valid_bsjb(d, len, 0);
    }
    jb.boolean("bsjbFound", bsjbOff != UINT64_MAX);
    if (bsjbOff != UINT64_MAX) {
        jb.num("bsjbOffset", bsjbOff);
    }

    // Try full .NET parse
    DotNetMetadata dn = parse_dotnet(d, len, pe);
    jb.boolean("isDotNet", dn.valid);
    if (dn.valid) {
        jb.str("assemblyName", dn.assemblyName);
        jb.num("metadataOffset", dn.metadataOffset);
        jb.num("metadataSize", dn.metadataSize);
        jb.num("validTables", dn.validTables);
        jb.num("typeCount", dn.tableRows[TBL_TYPEDEF]);
        jb.num("methodCount", dn.tableRows[TBL_METHODDEF]);
        jb.num("fieldCount", dn.tableRows[TBL_FIELD]);
        jb.num("assemblyRefCount", dn.tableRows[TBL_ASSEMBLYREF]);
        jb.boolean("hasStringsStream", dn.stringsSize > 0);
        jb.boolean("hasUsStream", dn.usSize > 0);
        jb.boolean("hasBlobStream", dn.blobSize > 0);
        jb.boolean("hasTablesStream", dn.tablesSize > 0);
    } else {
        jb.str("error", dn.error);
    }

    return env->NewStringUTF(jb.build().c_str());
}

// ── List all .NET types (TypeDef table) ──
JNIEXPORT jstring JNICALL
Java_com_dsmcp_tool_engine_NativeBridge_nativeDotnetListTypes(
        JNIEnv* env, jobject, jbyteArray jbytes) {
    auto data = jbytes_to_vector(env, jbytes);
    if (data.empty()) return env->NewStringUTF(error_json("empty").c_str());
    auto r = parse_dn_full(data.data(), data.size());
    if (!r.ok) return env->NewStringUTF(error_json(r.error).c_str());
    return env->NewStringUTF(dotnet_list_types_json(data.data(), data.size(), r.dn).c_str());
}

// ── List all .NET methods (MethodDef table) ──
JNIEXPORT jstring JNICALL
Java_com_dsmcp_tool_engine_NativeBridge_nativeDotnetListMethods(
        JNIEnv* env, jobject, jbyteArray jbytes, jint typeFilter) {
    auto data = jbytes_to_vector(env, jbytes);
    if (data.empty()) return env->NewStringUTF(error_json("empty").c_str());
    auto r = parse_dn_full(data.data(), data.size());
    if (!r.ok) return env->NewStringUTF(error_json(r.error).c_str());
    return env->NewStringUTF(
        dotnet_list_methods_json(data.data(), data.size(), r.dn, r.pe,
                                 static_cast<uint32_t>(typeFilter)).c_str());
}

// ── List user strings from #US heap ──
JNIEXPORT jstring JNICALL
Java_com_dsmcp_tool_engine_NativeBridge_nativeDotnetListStrings(
        JNIEnv* env, jobject, jbyteArray jbytes, jint maxCount) {
    auto data = jbytes_to_vector(env, jbytes);
    if (data.empty()) return env->NewStringUTF(error_json("empty").c_str());
    auto r = parse_dn_full(data.data(), data.size());
    if (!r.ok) return env->NewStringUTF(error_json(r.error).c_str());
    size_t max = (maxCount > 0) ? static_cast<size_t>(maxCount) : 5000;
    return env->NewStringUTF(
        dotnet_list_strings_json(data.data(), data.size(), r.dn, max).c_str());
}

// ── Dump IL for a method (hex) ──
JNIEXPORT jstring JNICALL
Java_com_dsmcp_tool_engine_NativeBridge_nativeDotnetDumpIl(
        JNIEnv* env, jobject, jbyteArray jbytes, jint methodToken) {
    auto data = jbytes_to_vector(env, jbytes);
    if (data.empty()) return env->NewStringUTF(error_json("empty").c_str());
    auto r = parse_dn_full(data.data(), data.size());
    if (!r.ok) return env->NewStringUTF(error_json(r.error).c_str());
    return env->NewStringUTF(
        dotnet_dump_il_json(data.data(), data.size(), r.dn, r.pe,
                            static_cast<uint32_t>(methodToken)).c_str());
}

// ── Disassemble IL (readable text + optional pseudo-C#) ──
JNIEXPORT jstring JNICALL
Java_com_dsmcp_tool_engine_NativeBridge_nativeDotnetDisasm(
        JNIEnv* env, jobject, jbyteArray jbytes, jint methodToken, jboolean pseudoCode) {
    auto data = jbytes_to_vector(env, jbytes);
    if (data.empty()) return env->NewStringUTF(error_json("empty").c_str());
    auto r = parse_dn_full(data.data(), data.size());
    if (!r.ok) return env->NewStringUTF(error_json(r.error).c_str());
    return env->NewStringUTF(
        dotnet_disasm_json(data.data(), data.size(), r.dn, r.pe,
                           static_cast<uint32_t>(methodToken),
                           pseudoCode == JNI_TRUE).c_str());
}

// ── Resolve a metadata token ──
JNIEXPORT jstring JNICALL
Java_com_dsmcp_tool_engine_NativeBridge_nativeDotnetResolveToken(
        JNIEnv* env, jobject, jbyteArray jbytes, jint token) {
    auto data = jbytes_to_vector(env, jbytes);
    if (data.empty()) return env->NewStringUTF(error_json("empty").c_str());
    auto r = parse_dn_full(data.data(), data.size());
    if (!r.ok) return env->NewStringUTF(error_json(r.error).c_str());
    return env->NewStringUTF(
        dotnet_resolve_token_json(data.data(), data.size(), r.dn, r.pe,
                                  static_cast<uint32_t>(token)).c_str());
}

// ── Edit IL bytes at a given offset within a method ──
JNIEXPORT jbyteArray JNICALL
Java_com_dsmcp_tool_engine_NativeBridge_nativeDotnetEditIl(
        JNIEnv* env, jobject, jbyteArray jbytes, jint methodToken,
        jint ilOffset, jbyteArray jpatch) {
    auto data = jbytes_to_vector(env, jbytes);
    auto patch = jbytes_to_vector(env, jpatch);
    if (data.empty() || patch.empty()) return env->NewByteArray(0);
    auto r = parse_dn_full(data.data(), data.size());
    if (!r.ok) return env->NewByteArray(0);
    auto result = dotnet_edit_il(data.data(), data.size(), r.pe, r.dn,
                                 static_cast<uint32_t>(methodToken),
                                 static_cast<uint32_t>(ilOffset),
                                 patch.data(), patch.size());
    return vector_to_jbytes(env, result);
}

// ── Edit a user string in #US heap ──
JNIEXPORT jbyteArray JNICALL
Java_com_dsmcp_tool_engine_NativeBridge_nativeDotnetEditString(
        JNIEnv* env, jobject, jbyteArray jbytes, jint usOffset, jstring jnewStr) {
    auto data = jbytes_to_vector(env, jbytes);
    auto newStr = jstr_to_std(env, jnewStr);
    if (data.empty()) return env->NewByteArray(0);
    auto r = parse_dn_full(data.data(), data.size());
    if (!r.ok) return env->NewByteArray(0);
    auto result = dotnet_edit_us_string(data.data(), data.size(), r.dn,
                                        static_cast<uint32_t>(usOffset), newStr);
    return vector_to_jbytes(env, result);
}

}  // extern "C"
