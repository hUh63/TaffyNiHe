//
// dotnet_parser.h - Self-contained .NET metadata parser for PE/.dll files.
//
// Implements ECMA-335 (Common Language Infrastructure) metadata parsing
// without any external library dependency. Works on top of pe_parser.h.
//
// Provides:
//   - CLR runtime header detection (PE data directory #14)
//   - Metadata root + stream header parsing (#~, #Strings, #US, #Blob, #GUID)
//   - Key metadata table parsing: TypeDef, TypeRef, MethodDef, Field, Param,
//     MemberRef, TypeSpec, Assembly, AssemblyRef, Module, ModuleRef
//   - Method body location (Tiny/Fat headers) and IL extraction
//   - Token resolution (table_id << 24 | row -> name/description)
//   - User string (#US heap) extraction
//   - IL patching at method-level and user string editing
//
// Reference: ECMA-335, 6th edition, Partition II (Metadata definition and semantics)
//
#pragma once

#include "hex_util.h"
#include "json_builder.h"
#include "pe_parser.h"
#include <string>
#include <vector>
#include <cstdint>
#include <cstring>
#include <algorithm>

namespace dsmcp {

// ── .NET table indices (ECMA-335 II.22) ──
enum DnTable : uint8_t {
    TBL_MODULE         = 0x00,
    TBL_TYPEREF        = 0x01,
    TBL_TYPEDEF        = 0x02,
    TBL_FIELD          = 0x04,
    TBL_METHODDEF      = 0x06,
    TBL_PARAM          = 0x08,
    TBL_INTERFACEIMPL  = 0x09,
    TBL_MEMBERREF      = 0x0A,
    TBL_CONSTANT       = 0x0B,
    TBL_CUSTOMATTR     = 0x0C,
    TBL_STANDALONESIG  = 0x11,
    TBL_EVENTMAP       = 0x12,
    TBL_EVENT          = 0x14,
    TBL_PROPERTYMAP    = 0x15,
    TBL_PROPERTY       = 0x17,
    TBL_METHODSEMANTICS= 0x18,
    TBL_METHODIMPL     = 0x19,
    TBL_MODULEREF      = 0x1A,
    TBL_TYPESPEC       = 0x1B,
    TBL_IMPLMAP        = 0x1C,
    TBL_FIELDRVA       = 0x1D,
    TBL_ASSEMBLY       = 0x20,
    TBL_ASSEMBLYREF    = 0x21,
    TBL_FILE           = 0x22,
    TBL_EXPORTEDTYPE   = 0x23,
    TBL_MANIFESTRES    = 0x28,   // 原实现写成 0x24 — 与 NestedClass/GenericParam/MethodSpec 一起整组错位
    TBL_NESTEDCLASS    = 0x29,   // 原实现写成 0x25 — 整组错位 4，导致这三张表行宽恒为 0
    TBL_GENERICPARAM   = 0x2A,
    TBL_METHODSPEC     = 0x2B,
    TBL_GENERICPARAMCONSTRAINT = 0x2C,
    TBL_FIELDMARSHAL   = 0x0D,
    TBL_DECLSECURITY   = 0x0E,
    TBL_CLASSLAYOUT    = 0x0F,
    TBL_FIELDLAYOUT    = 0x10,
    TBL_MAX            = 0x2D,
};

// ── Coded index definitions (ECMA-335 II.24.2.6) ──
struct CodedIndexDef {
    uint8_t tagBits;
    uint8_t tableCount;
    DnTable tables[6];
};

// TypeDefOrRef: 2 bits, [TypeDef, TypeRef, TypeSpec]
static const CodedIndexDef CI_TYPEDEF_OR_REF = {2, 3, {TBL_TYPEDEF, TBL_TYPEREF, TBL_TYPESPEC, TBL_MODULE, TBL_MODULE, TBL_MODULE}};
// MemberRefParent: 3 bits, [TypeDef, TypeRef, ModuleRef, MethodDef, TypeSpec]
static const CodedIndexDef CI_MEMBERREF_PARENT = {3, 5, {TBL_TYPEDEF, TBL_TYPEREF, TBL_MODULEREF, TBL_METHODDEF, TBL_TYPESPEC, TBL_MODULE}};
// ResolutionScope: 2 bits, [Module, ModuleRef, AssemblyRef, TypeRef]
static const CodedIndexDef CI_RESOLUTION_SCOPE = {2, 4, {TBL_MODULE, TBL_MODULEREF, TBL_ASSEMBLYREF, TBL_TYPEREF, TBL_MODULE, TBL_MODULE}};
// MethodDefOrRef: 1 bit, [MethodDef, MemberRef]
static const CodedIndexDef CI_METHODDEF_OR_REF = {1, 2, {TBL_METHODDEF, TBL_MEMBERREF, TBL_MODULE, TBL_MODULE, TBL_MODULE, TBL_MODULE}};
// HasSemantics: 1 bit, [Event, Property]
static const CodedIndexDef CI_HAS_SEMANTICS = {1, 2, {TBL_EVENT, TBL_PROPERTY, TBL_MODULE, TBL_MODULE, TBL_MODULE, TBL_MODULE}};
// HasDeclSecurity: 2 bits, [TypeDef, MethodDef, Assembly]
static const CodedIndexDef CI_HAS_DECL_SECURITY = {2, 3, {TBL_TYPEDEF, TBL_METHODDEF, TBL_ASSEMBLY, TBL_MODULE, TBL_MODULE, TBL_MODULE}};

// ── Parsed .NET structures ──

struct DnMethodBody {
    bool valid = false;
    bool isFat = false;
    uint32_t rva = 0;
    uint64_t fileOffset = 0;     // offset of method header in file
    uint32_t headerSize = 0;     // 1 (tiny) or 12 (fat)
    uint32_t codeSize = 0;       // IL body size
    uint32_t maxStack = 0;
    uint32_t localVarSigTok = 0;
    uint64_t ilOffset = 0;       // file offset of IL body
    bool hasMoreSections = false;
};

struct DotNetMetadata {
    bool valid = false;
    std::string error;

    // Metadata location
    uint32_t metadataOffset = 0;  // file offset of metadata root
    uint32_t metadataSize = 0;

    // Heap index sizes
    uint8_t heapSizes = 0;     // HeapSizes byte from tables stream header
    bool stringsLong = false;  // #Strings index is 4 bytes
    bool guidLong = false;     // #GUID index is 4 bytes
    bool blobLong = false;     // #Blob index is 4 bytes

    // Stream locations (file offsets)
    uint32_t stringsOffset = 0, stringsSize = 0;
    uint32_t usOffset = 0, usSize = 0;
    uint32_t blobOffset = 0, blobSize = 0;
    uint32_t guidOffset = 0, guidSize = 0;
    uint32_t tablesOffset = 0, tablesSize = 0;

    // Table row counts and computed row sizes
    uint64_t validTables = 0;
    uint32_t tableRows[TBL_MAX] = {};
    uint32_t tableRowOffsets[TBL_MAX] = {};  // file offset of first row
    uint32_t tableRowSizes[TBL_MAX] = {};    // computed row size in bytes

    // Assembly info
    std::string assemblyName;
    uint16_t asmMajorVersion = 0;
    uint16_t asmMinorVersion = 0;
};

// ── Forward declarations ──
inline uint64_t dn_rva_to_offset(const PeFile& pe, uint32_t rva);
inline DotNetMetadata parse_metadata_root(const uint8_t* data, size_t len,
                                           DotNetMetadata dn, const PeFile& pe);

// ── Read a string from the #Strings heap ──
inline std::string read_strings_heap(const uint8_t* data, size_t len,
                                      const DotNetMetadata& dn, uint32_t idx) {
    if (dn.stringsSize == 0 || idx >= dn.stringsSize) return "";
    uint32_t off = dn.stringsOffset + idx;
    if (off >= len) return "";
    std::string s;
    for (size_t i = off; i < len && data[i] != 0; ++i) {
        s += static_cast<char>(data[i]);
    }
    return s;
}

// ── Read a user string from the #US heap ──
inline std::string read_us_heap(const uint8_t* data, size_t len,
                                 const DotNetMetadata& dn, uint32_t idx) {
    if (dn.usSize == 0 || idx == 0 || idx >= dn.usSize) return "";
    uint32_t off = dn.usOffset + idx;
    if (off >= len) return "";
    // Read compressed length
    uint32_t strLen = 0;
    if (data[off] == 0) return "";
    if ((data[off] & 0x80) == 0) {
        strLen = data[off] & 0x7F;
        off += 1;
    } else if ((data[off] & 0xC0) == 0x80) {
        if (off + 1 >= len) return "";
        strLen = ((data[off] & 0x3F) << 8) | data[off + 1];
        off += 2;
    } else if ((data[off] & 0xE0) == 0xC0) {
        if (off + 3 >= len) return "";
        strLen = ((data[off] & 0x1F) << 24) | (data[off + 1] << 16) |
                 (data[off + 2] << 8) | data[off + 3];
        off += 4;
    } else {
        return "";
    }
    if (strLen == 0) return "";
    // Last byte is terminal flag, actual string is strLen-1 bytes
    uint32_t charLen = (strLen > 0) ? strLen - 1 : 0;
    if (off + charLen > len) return "";
    // Decode UTF-16LE to ASCII (approximate for display)
    std::string s;
    for (uint32_t i = 0; i + 1 < charLen; i += 2) {
        uint16_t ch = data[off + i] | (data[off + i + 1] << 8);
        if (ch < 0x80) {
            s += static_cast<char>(ch);
        } else if (ch < 0x800) {
            s += static_cast<char>(0xC0 | (ch >> 6));
            s += static_cast<char>(0x80 | (ch & 0x3F));
        } else {
            s += static_cast<char>(0xE0 | (ch >> 12));
            s += static_cast<char>(0x80 | ((ch >> 6) & 0x3F));
            s += static_cast<char>(0x80 | (ch & 0x3F));
        }
    }
    return s;
}

// ── Read compressed unsigned integer (for blob/signature) ──
inline uint32_t read_compressed_uint(const uint8_t* p, size_t maxLen, size_t& bytesConsumed) {
    if (maxLen == 0) { bytesConsumed = 0; return 0; }
    if ((p[0] & 0x80) == 0) {
        bytesConsumed = 1;
        return p[0] & 0x7F;
    } else if ((p[0] & 0xC0) == 0x80) {
        if (maxLen < 2) { bytesConsumed = 0; return 0; }
        bytesConsumed = 2;
        return ((p[0] & 0x3F) << 8) | p[1];
    } else if ((p[0] & 0xE0) == 0xC0) {
        if (maxLen < 4) { bytesConsumed = 0; return 0; }
        bytesConsumed = 4;
        return ((p[0] & 0x1F) << 24) | (p[1] << 16) | (p[2] << 8) | p[3];
    }
    bytesConsumed = 0;
    return 0;
}

// ── Compute coded index size ──
inline uint8_t coded_index_size(const DotNetMetadata& dn, const CodedIndexDef& ci) {
    uint32_t maxRows = 0;
    for (uint8_t i = 0; i < ci.tableCount; ++i) {
        uint8_t tbl = ci.tables[i];
        if (tbl < TBL_MAX && dn.tableRows[tbl] > maxRows) {
            maxRows = dn.tableRows[tbl];
        }
    }
    uint32_t maxVal = maxRows << ci.tagBits;
    return (maxVal < 0x10000) ? 2 : 4;
}

// ── Simple table index size ──
inline uint8_t table_index_size(const DotNetMetadata& dn, DnTable tbl) {
    return (tbl < TBL_MAX && dn.tableRows[tbl] < 0x10000) ? 2 : 4;
}

// ── String heap index size ──
inline uint8_t string_idx_size(const DotNetMetadata& dn) {
    return dn.stringsLong ? 4 : 2;
}

// ── Blob heap index size ──
inline uint8_t blob_idx_size(const DotNetMetadata& dn) {
    return dn.blobLong ? 4 : 2;
}

// ── GUID heap index size ──
inline uint8_t guid_idx_size(const DotNetMetadata& dn) {
    return dn.guidLong ? 4 : 2;
}

// ── Read a variable-size index from a buffer ──
inline uint32_t read_idx(const uint8_t* p, uint8_t size) {
    if (size == 2) return read_le16(p);
    return read_le32(p);
}

// ── Compute row sizes for all present tables ──
inline void compute_row_sizes(DotNetMetadata& dn) {
    // Helper: get coded index size
    auto ci_size = [&](const CodedIndexDef& ci) -> uint8_t {
        return coded_index_size(dn, ci);
    };

    for (uint8_t t = 0; t < TBL_MAX; ++t) {
        if (!(dn.validTables & (1ULL << t))) continue;
        if (dn.tableRows[t] == 0) continue;
        uint32_t size = 0;
        switch (t) {
            case TBL_MODULE: // Generation(2) Name(str) Mvid(guid) EncId(guid) EncBaseId(guid)
                size = 2 + string_idx_size(dn) + 3 * guid_idx_size(dn);
                break;
            case TBL_TYPEREF: // ResolutionScope(coded) Name(str) Namespace(str)
                size = ci_size(CI_RESOLUTION_SCOPE) + 2 * string_idx_size(dn);
                break;
            case TBL_TYPEDEF: // Flags(4) Name(str) Namespace(str) Extends(coded TypeDefOrRef) FieldList(Field idx) MethodList(MethodDef idx)
                size = 4 + 2 * string_idx_size(dn) + ci_size(CI_TYPEDEF_OR_REF) +
                       table_index_size(dn, TBL_FIELD) + table_index_size(dn, TBL_METHODDEF);
                break;
            case TBL_FIELD: // Flags(2) Name(str) Signature(blob)
                size = 2 + string_idx_size(dn) + blob_idx_size(dn);
                break;
            case TBL_METHODDEF: // RVA(4) ImplFlags(2) Flags(2) Name(str) Signature(blob) ParamList(Param idx)
                size = 4 + 2 + 2 + string_idx_size(dn) + blob_idx_size(dn) +
                       table_index_size(dn, TBL_PARAM);
                break;
            case TBL_PARAM: // Flags(2) Sequence(2) Name(str)
                size = 2 + 2 + string_idx_size(dn);
                break;
            case TBL_INTERFACEIMPL: // Class(TypeDef idx) Interface(coded TypeDefOrRef)
                size = table_index_size(dn, TBL_TYPEDEF) + ci_size(CI_TYPEDEF_OR_REF);
                break;
            case TBL_MEMBERREF: // Class(coded MemberRefParent) Name(str) Signature(blob)
                size = ci_size(CI_MEMBERREF_PARENT) + string_idx_size(dn) + blob_idx_size(dn);
                break;
            case TBL_CONSTANT: // Type(1) padding(1) Parent(coded HasConstant) Value(blob)
                // HasConstant: 2 bits, [Field, Param, Property]
                { static const CodedIndexDef ci = {2, 3, {TBL_FIELD, TBL_PARAM, TBL_PROPERTY, TBL_MODULE, TBL_MODULE, TBL_MODULE}};
                  size = 1 + 1 + coded_index_size(dn, ci) + blob_idx_size(dn); }
                break;
            case TBL_FIELDMARSHAL: // Parent(coded HasFieldMarshal) NativeType(blob)
                { static const CodedIndexDef ci = {1, 2, {TBL_FIELD, TBL_PARAM, TBL_MODULE, TBL_MODULE, TBL_MODULE, TBL_MODULE}};
                  size = coded_index_size(dn, ci) + blob_idx_size(dn); }
                break;
            case TBL_DECLSECURITY: // Action(2) Parent(coded HasDeclSecurity) PermissionSet(blob)
                { static const CodedIndexDef ci = {2, 3, {TBL_TYPEDEF, TBL_METHODDEF, TBL_ASSEMBLY, TBL_MODULE, TBL_MODULE, TBL_MODULE}};
                  size = 2 + coded_index_size(dn, ci) + blob_idx_size(dn); }
                break;
            case TBL_CLASSLAYOUT: // PackingSize(2) ClassSize(4) Parent(TypeDef idx)
                size = 2 + 4 + table_index_size(dn, TBL_TYPEDEF);
                break;
            case TBL_FIELDLAYOUT: // Offset(4) Field(Field idx)
                size = 4 + table_index_size(dn, TBL_FIELD);
                break;
            case TBL_CUSTOMATTR: // Parent(coded HasCustomAttribute) Type(coded CustomAttributeType) Value(blob)
                // HasCustomAttribute: 5 bits, many tables — use max of all
                { // Simplified: use 4 bytes if any table has > 8191 rows, else 2
                  uint32_t maxCA = 0;
                  DnTable caTables[] = {TBL_METHODDEF, TBL_FIELD, TBL_TYPEREF, TBL_TYPEDEF, TBL_PARAM,
                                        TBL_INTERFACEIMPL, TBL_MEMBERREF, TBL_MODULE, TBL_PROPERTY, TBL_EVENT};
                  for (auto tt : caTables) { if (dn.tableRows[tt] > maxCA) maxCA = dn.tableRows[tt]; }
                  uint8_t caSize = ((maxCA << 5) < 0x10000) ? 2 : 4;
                  // CustomAttributeType: 3 bits, [MethodDef, MemberRef, ...]
                  uint32_t maxCT = std::max(dn.tableRows[TBL_METHODDEF], dn.tableRows[TBL_MEMBERREF]);
                  uint8_t ctSize = ((maxCT << 3) < 0x10000) ? 2 : 4;
                  size = caSize + ctSize + blob_idx_size(dn); }
                break;
            case TBL_STANDALONESIG: // Signature(blob)
                size = blob_idx_size(dn);
                break;
            case TBL_EVENTMAP: // Parent(TypeDef idx) EventList(Event idx)
                size = table_index_size(dn, TBL_TYPEDEF) + table_index_size(dn, TBL_EVENT);
                break;
            case TBL_EVENT: // EventFlags(2) Name(str) EventType(coded TypeDefOrRef)
                size = 2 + string_idx_size(dn) + ci_size(CI_TYPEDEF_OR_REF);
                break;
            case TBL_PROPERTYMAP: // Parent(TypeDef idx) PropertyList(Property idx)
                size = table_index_size(dn, TBL_TYPEDEF) + table_index_size(dn, TBL_PROPERTY);
                break;
            case TBL_PROPERTY: // Flags(2) Name(str) Type(blob)
                size = 2 + string_idx_size(dn) + blob_idx_size(dn);
                break;
            case TBL_METHODSEMANTICS: // Semantics(2) Method(MethodDef idx) Association(coded HasSemantics)
                size = 2 + table_index_size(dn, TBL_METHODDEF) + ci_size(CI_HAS_SEMANTICS);
                break;
            case TBL_METHODIMPL: // Class(TypeDef idx) MethodBody(coded MethodDefOrRef) MethodDeclaration(coded MethodDefOrRef)
                size = table_index_size(dn, TBL_TYPEDEF) + 2 * ci_size(CI_METHODDEF_OR_REF);
                break;
            case TBL_MODULEREF: // Name(str)
                size = string_idx_size(dn);
                break;
            case TBL_TYPESPEC: // Signature(blob)
                size = blob_idx_size(dn);
                break;
            case TBL_IMPLMAP: // MappingFlags(2) MemberForwarded(coded MemberForwarded) ImportName(str) ImportScope(ModuleRef idx)
                // MemberForwarded: 1 bit, [Field, MethodDef]
                { uint32_t maxMF = std::max(dn.tableRows[TBL_FIELD], dn.tableRows[TBL_METHODDEF]);
                  uint8_t mfSize = ((maxMF << 1) < 0x10000) ? 2 : 4;
                  size = 2 + mfSize + string_idx_size(dn) + table_index_size(dn, TBL_MODULEREF); }
                break;
            case TBL_FIELDRVA: // RVA(4) Field(Field idx)
                size = 4 + table_index_size(dn, TBL_FIELD);
                break;
            case TBL_ASSEMBLY: // HashAlgId(4) MajorVer(2) MinorVer(2) Build(2) Rev(2) Flags(4) PublicKey(blob) Name(str) Culture(str)
                size = 4 + 2 + 2 + 2 + 2 + 4 + blob_idx_size(dn) + 2 * string_idx_size(dn);
                break;
            case TBL_ASSEMBLYREF: // MajorVer(2) MinorVer(2) Build(2) Rev(2) Flags(4) PublicKeyOrToken(blob) Name(str) Culture(str) HashValue(blob)
                size = 2 + 2 + 2 + 2 + 4 + blob_idx_size(dn) + 2 * string_idx_size(dn) + blob_idx_size(dn);
                break;
            case TBL_FILE: // Flags(4) Name(str) HashValue(blob)
                size = 4 + string_idx_size(dn) + blob_idx_size(dn);
                break;
            case TBL_EXPORTEDTYPE: // Flags(4) TypeDefId(4) Name(str) Namespace(str) Implementation(coded Implementation)
                // Implementation: 2 bits, [File, AssemblyRef, ExportedType]
                { uint32_t maxImpl = std::max({dn.tableRows[TBL_FILE], dn.tableRows[TBL_ASSEMBLYREF], dn.tableRows[TBL_EXPORTEDTYPE]});
                  uint8_t implSize = ((maxImpl << 2) < 0x10000) ? 2 : 4;
                  size = 4 + 4 + 2 * string_idx_size(dn) + implSize; }
                break;
            case TBL_MANIFESTRES: // Offset(4) Flags(4) Name(str) Implementation(coded Implementation)
                { uint32_t maxImpl = std::max({dn.tableRows[TBL_FILE], dn.tableRows[TBL_ASSEMBLYREF], dn.tableRows[TBL_EXPORTEDTYPE]});
                  uint8_t implSize = ((maxImpl << 2) < 0x10000) ? 2 : 4;
                  size = 4 + 4 + string_idx_size(dn) + implSize; }
                break;
            case TBL_NESTEDCLASS: // NestedClass(TypeDef idx) EnclosingClass(TypeDef idx)
                size = 2 * table_index_size(dn, TBL_TYPEDEF);
                break;
            case TBL_GENERICPARAM: // Number(2) Flags(2) Owner(coded TypeOrMethodDef) Name(str)
                // TypeOrMethodDef: 1 bit, [TypeDef, MethodDef]
                { uint32_t maxTM = std::max(dn.tableRows[TBL_TYPEDEF], dn.tableRows[TBL_METHODDEF]);
                  uint8_t tmSize = ((maxTM << 1) < 0x10000) ? 2 : 4;
                  size = 2 + 2 + tmSize + string_idx_size(dn); }
                break;
            case TBL_METHODSPEC: // Method(coded MethodDefOrRef) Instantiation(blob)
                size = ci_size(CI_METHODDEF_OR_REF) + blob_idx_size(dn);
                break;
            case TBL_GENERICPARAMCONSTRAINT: // Owner(GenericParam idx) Constraint(coded TypeDefOrRef)
                size = table_index_size(dn, TBL_GENERICPARAM) + ci_size(CI_TYPEDEF_OR_REF);
                break;
            default:
                size = 0; // 未覆盖的表
                break;
        }
        if (size == 0 && dn.tableRows[t] > 0) {
            // 行宽算不出来就不能硬跳：后面每张表的偏移都会错，读出来是别的表的中间字节
            dn.error = "unsupported metadata table " + std::to_string((int)t) +
                       " (" + std::to_string(dn.tableRows[t]) + " rows)";
            dn.valid = false;
            return;
        }
        dn.tableRowSizes[t] = size;
    }
}

// ── Get a pointer to a specific row in a table ──
inline const uint8_t* get_table_row(const uint8_t* data, size_t len,
                                     const DotNetMetadata& dn, DnTable tbl, uint32_t row) {
    if (tbl >= TBL_MAX) return nullptr;
    if (!(dn.validTables & (1ULL << tbl))) return nullptr;
    if (row == 0 || row > dn.tableRows[tbl]) return nullptr;
    if (dn.tableRowSizes[tbl] == 0) return nullptr;
    uint32_t off = dn.tableRowOffsets[tbl] + (row - 1) * dn.tableRowSizes[tbl];
    if (off + dn.tableRowSizes[tbl] > len) return nullptr;
    return data + off;
}

// ── Parse method body header ──
inline DnMethodBody parse_method_body(const uint8_t* data, size_t len,
                                       const PeFile& pe, const DotNetMetadata& dn,
                                       uint32_t rva) {
    DnMethodBody mb;
    mb.rva = rva;
    uint64_t off = dn_rva_to_offset(pe, rva);
    if (off == UINT64_MAX || off >= len) return mb;
    mb.fileOffset = off;
    uint8_t firstByte = data[off];
    // Tiny format: (byte & 0x03) == 0x02
    if ((firstByte & 0x03) == 0x02) {
        mb.isFat = false;
        mb.headerSize = 1;
        mb.codeSize = (firstByte >> 2) & 0x3F;
        mb.maxStack = 8;
        mb.localVarSigTok = 0;
        mb.ilOffset = off + 1;
        mb.valid = true;
    } else if ((firstByte & 0x03) == 0x03) {
        // Fat format
        if (off + 12 > len) return mb;
        mb.isFat = true;
        uint16_t flags = read_le16(data + off);
        mb.hasMoreSections = (flags & 0x08) != 0;
        mb.headerSize = 12;
        mb.maxStack = read_le16(data + off + 2);
        mb.codeSize = read_le32(data + off + 4);
        mb.localVarSigTok = read_le32(data + off + 8);
        mb.ilOffset = off + 12;
        mb.valid = true;
    }
    return mb;
}

// ── Main parser: parse .NET metadata from PE data ──
// ── Validate a BSJB signature match ──
// Returns true if the data at [off] looks like a valid .NET metadata root.
// Checks: BSJB signature + version string + at least one stream header.
inline bool validate_bsjb(const uint8_t* data, size_t len, size_t off) {
    if (off + 16 > len) return false;
    // Signature must be 0x424A5342
    if (read_le32(data + off) != 0x424A5342) return false;
    // Skip MajorVersion(2) + MinorVersion(2) + Reserved(4) = 8 bytes
    uint32_t versionLen = read_le32(data + off + 12);
    // Version string length should be reasonable (1-256 bytes)
    if (versionLen == 0 || versionLen > 256) return false;
    size_t afterVersion = off + 16 + versionLen;
    afterVersion = (afterVersion + 3) & ~3u;
    // Need at least Flags(2) + Streams(2) = 4 bytes after version string
    if (afterVersion + 4 > len) return false;
    uint16_t numStreams = read_le16(data + afterVersion + 2);
    // Reasonable number of streams (1-16)
    if (numStreams == 0 || numStreams > 16) return false;
    // Check first stream header exists
    if (afterVersion + 4 + 8 > len) return false;
    return true;
}

// ── Search for a valid BSJB metadata root ──
// Scans the file for BSJB signatures and validates each match.
// Searches from PE section data area first (skip DOS/PE headers),
// then falls back to entire file.
inline uint64_t find_valid_bsjb(const uint8_t* data, size_t len, size_t startOff = 0) {
    const uint32_t bsjbSig = 0x424A5342;
    // Search from startOff (usually past PE headers) to end of file
    for (size_t i = startOff; i + 4 <= len; ++i) {
        if (read_le32(data + i) == bsjbSig) {
            if (validate_bsjb(data, len, i)) {
                return i;
            }
            // False positive — keep searching
        }
    }
    // If startOff > 0, try from beginning as last resort
    if (startOff > 0) {
        for (size_t i = 0; i + 4 <= len && i < startOff; ++i) {
            if (read_le32(data + i) == bsjbSig) {
                if (validate_bsjb(data, len, i)) {
                    return i;
                }
            }
        }
    }
    return UINT64_MAX;
}

inline DotNetMetadata parse_dotnet(const uint8_t* data, size_t len, const PeFile& pe) {
    DotNetMetadata dn;
    if (!pe.valid) {
        dn.error = "PE parse failed: " + pe.error;
        return dn;
    }

    // ── Try standard CLR header path first ──
    // Re-parse optional header to find data directories
    if (len < 64) { dn.error = "file too small for .NET metadata"; return dn; }
    uint32_t peOff = read_le32(data + 0x3C);
    if (peOff + 24 > len) { dn.error = "invalid PE offset"; return dn; }
    const uint8_t* coff = data + peOff + 4;
    uint16_t optSize = read_le16(coff + 16);
    const uint8_t* optHdr = coff + 20;
    if (peOff + 24 + optSize > len) { dn.error = "truncated optional header"; return dn; }

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

    // Compute start of section data (skip PE headers to avoid false BSJB matches)
    size_t sectionDataStart = peOff + 24 + optSize + pe.numberOfSections * 40;

    // CLR header is data directory index 14
    bool triedClrPath = false;
    if (numDataDirs > 14) {
        uint32_t clrRva = read_le32(dataDirs + 14 * 8);
        uint32_t clrSize = read_le32(dataDirs + 14 * 8 + 4);
        if (clrRva != 0 && clrSize != 0) {
            triedClrPath = true;
            uint64_t clrOff = dn_rva_to_offset(pe, clrRva);
            if (clrOff != UINT64_MAX && clrOff + 72 <= len) {
                // CLR header: cb(4), MajorRT(2), MinorRT(2), MetaDataRVA(4), MetaDataSize(4)
                uint32_t metaDataRva = read_le32(data + clrOff + 8);
                uint32_t metaDataSize = read_le32(data + clrOff + 12);
                if (metaDataRva != 0) {
                    uint64_t metaOff = dn_rva_to_offset(pe, metaDataRva);
                    if (metaOff != UINT64_MAX && metaOff + 16 <= len) {
                        dn.metadataOffset = static_cast<uint32_t>(metaOff);
                        dn.metadataSize = metaDataSize;
                        if (read_le32(data + metaOff) == 0x424A5342) {
                            return parse_metadata_root(data, len, dn, pe);
                        }
                    }
                }
            }
        }
    }

    // ── Fallback: search for validated BSJB signature ──
    // This handles Mono/Unity DLLs with missing/bad CLR headers or bad RVA mappings
    uint64_t foundOff = find_valid_bsjb(data, len, sectionDataStart);
    if (foundOff == UINT64_MAX) {
        // Try from offset 0 as last resort
        foundOff = find_valid_bsjb(data, len, 0);
    }
    if (foundOff == UINT64_MAX) {
        if (triedClrPath) {
            dn.error = "CLR header found but metadata RVA resolution failed, and no valid BSJB signature found";
        } else {
            dn.error = "no CLR header (data dir #14 missing or zero), and no valid BSJB signature found — not a .NET/Mono assembly";
        }
        return dn;
    }
    dn.metadataOffset = static_cast<uint32_t>(foundOff);
    return parse_metadata_root(data, len, dn, pe);
}

// ── Parse metadata root from BSJB signature ──
// Called after dn.metadataOffset is set (either from CLR header or fallback search)
inline DotNetMetadata parse_metadata_root(const uint8_t* data, size_t len,
                                           DotNetMetadata dn, const PeFile& pe) {
    uint32_t metaOff = dn.metadataOffset;
    if (metaOff + 16 > len) { dn.error = "metadata root truncated"; return dn; }

    // Verify BSJB signature
    uint32_t sig = read_le32(data + metaOff);
    if (sig != 0x424A5342) { dn.error = "invalid metadata signature at offset"; return dn; }

    // Signature(4) + MajorVersion(2) + MinorVersion(2) + Reserved(4) = 12 字节，
    // 然后是 Length(4)，版本串从 +16 开始。原实现把 Length 读在 +16（读到的是
    // 版本串首 4 字节），于是每个真实程序集都会报 "truncated version string"。
    uint32_t off = metaOff + 12;
    if (off + 4 > len) { dn.error = "truncated metadata root"; return dn; }
    uint32_t versionLen = read_le32(data + off);
    off += 4;
    if (versionLen == 0 || versionLen > 4096) { dn.error = "invalid metadata version length"; return dn; }
    // Version string is versionLen bytes, padded to 4-byte boundary
    if (off + versionLen > len) { dn.error = "truncated version string"; return dn; }
    off += versionLen;
    // Align to 4 bytes
    off = (off + 3) & ~3u;
    if (off + 4 > len) { dn.error = "truncated metadata headers"; return dn; }

    // Flags(2) = 0, Streams(2)
    uint16_t numStreams = read_le16(data + off + 2);
    off += 4;

    // Parse stream headers
    for (uint16_t i = 0; i < numStreams; ++i) {
        if (off + 8 > len) { dn.error = "truncated stream header"; return dn; }
        uint32_t sOff = read_le32(data + off);
        uint32_t sSize = read_le32(data + off + 4);
        off += 8;
        // Stream name: null-terminated, padded to 4 bytes
        std::string name;
        while (off < len && data[off] != 0) {
            name += static_cast<char>(data[off]);
            ++off;
        }
        ++off; // skip null terminator
        // Align to 4 bytes
        off = (off + 3) & ~3u;

        // Map stream name to metadata fields
        if (name == "#Strings") {
            dn.stringsOffset = dn.metadataOffset + sOff;
            dn.stringsSize = sSize;
        } else if (name == "#US") {
            dn.usOffset = dn.metadataOffset + sOff;
            dn.usSize = sSize;
        } else if (name == "#Blob") {
            dn.blobOffset = dn.metadataOffset + sOff;
            dn.blobSize = sSize;
        } else if (name == "#GUID") {
            dn.guidOffset = dn.metadataOffset + sOff;
            dn.guidSize = sSize;
        } else if (name == "#~" || name == "#-") {
            dn.tablesOffset = dn.metadataOffset + sOff;
            dn.tablesSize = sSize;
        }
    }

    if (dn.tablesOffset == 0) { dn.error = "no metadata tables stream found"; return dn; }

    // Parse tables stream header
    uint32_t toff = dn.tablesOffset;
    if (toff + 24 > len) { dn.error = "truncated tables stream"; return dn; }
    // Reserved(4), MajorVersion(1), MinorVersion(1), HeapSizes(1), Reserved(1)
    // Valid(8), Sorted(8)
    dn.heapSizes = data[toff + 6];
    dn.stringsLong = (dn.heapSizes & 0x01) != 0;
    dn.guidLong = (dn.heapSizes & 0x02) != 0;
    dn.blobLong = (dn.heapSizes & 0x04) != 0;
    dn.validTables = read_le64(data + toff + 8);
    // Sorted mask at toff+16, skip

    toff += 24; // past header

    // Read row counts for each present table
    for (uint8_t t = 0; t < TBL_MAX; ++t) {
        if (dn.validTables & (1ULL << t)) {
            if (toff + 4 > len) { dn.error = "truncated row counts"; return dn; }
            dn.tableRows[t] = read_le32(data + toff);
            toff += 4;
        }
    }

    // Compute row sizes for all tables
    compute_row_sizes(dn);

    // Compute table data offsets
    for (uint8_t t = 0; t < TBL_MAX; ++t) {
        if (!(dn.validTables & (1ULL << t)) || dn.tableRows[t] == 0) continue;
        dn.tableRowOffsets[t] = toff;
        toff += dn.tableRows[t] * dn.tableRowSizes[t];
    }

    // Parse assembly name
    if (dn.validTables & (1ULL << TBL_ASSEMBLY)) {
        const uint8_t* row = get_table_row(data, len, dn, TBL_ASSEMBLY, 1);
        if (row) {
            // Assembly: HashAlgId(4) Major(2) Minor(2) Build(2) Rev(2) Flags(4) PublicKey(blob) Name(str) Culture(str)
            uint8_t bIdx = blob_idx_size(dn);
            uint8_t sIdx = string_idx_size(dn);
            const uint8_t* p = row + 4 + 2 + 2 + 2 + 2 + 4 + bIdx;
            uint32_t nameIdx = read_idx(p, sIdx);
            dn.assemblyName = read_strings_heap(data, len, dn, nameIdx);
            dn.asmMajorVersion = read_le16(row + 4);
            dn.asmMinorVersion = read_le16(row + 6);
        }
    }

    dn.valid = dn.error.empty();
    return dn;
}

// ── RVA to file offset (using PE sections) ──
inline uint64_t dn_rva_to_offset(const PeFile& pe, uint32_t rva) {
    return pe_rva_to_offset(pe, rva);
}

// ── Token type names ──
inline const char* token_type_name(uint8_t table) {
    switch (table) {
        case TBL_MODULE: return "Module";
        case TBL_TYPEREF: return "TypeRef";
        case TBL_TYPEDEF: return "TypeDef";
        case TBL_FIELD: return "Field";
        case TBL_METHODDEF: return "MethodDef";
        case TBL_PARAM: return "Param";
        case TBL_INTERFACEIMPL: return "InterfaceImpl";
        case TBL_MEMBERREF: return "MemberRef";
        case TBL_CONSTANT: return "Constant";
        case TBL_CUSTOMATTR: return "CustomAttribute";
        case TBL_STANDALONESIG: return "StandAloneSig";
        case TBL_EVENTMAP: return "EventMap";
        case TBL_EVENT: return "Event";
        case TBL_PROPERTYMAP: return "PropertyMap";
        case TBL_PROPERTY: return "Property";
        case TBL_METHODSEMANTICS: return "MethodSemantics";
        case TBL_METHODIMPL: return "MethodImpl";
        case TBL_MODULEREF: return "ModuleRef";
        case TBL_TYPESPEC: return "TypeSpec";
        case TBL_IMPLMAP: return "ImplMap";
        case TBL_FIELDRVA: return "FieldRVA";
        case TBL_ASSEMBLY: return "Assembly";
        case TBL_ASSEMBLYREF: return "AssemblyRef";
        case TBL_FILE: return "File";
        case TBL_EXPORTEDTYPE: return "ExportedType";
        case TBL_MANIFESTRES: return "ManifestResource";
        case TBL_NESTEDCLASS: return "NestedClass";
        case TBL_GENERICPARAM: return "GenericParam";
        case TBL_METHODSPEC: return "MethodSpec";
        case TBL_GENERICPARAMCONSTRAINT: return "GenericParamConstraint";
        default: return "Unknown";
    }
}

// ── Resolve a token to a human-readable name ──
inline std::string resolve_token(const uint8_t* data, size_t len,
                                  const DotNetMetadata& dn, const PeFile& pe,
                                  uint32_t token) {
    uint8_t tbl = (token >> 24) & 0xFF;
    uint32_t row = token & 0x00FFFFFF;
    if (row == 0) return std::string(token_type_name(tbl)) + "[row=0]";

    switch (tbl) {
        case TBL_TYPEDEF: {
            const uint8_t* r = get_table_row(data, len, dn, TBL_TYPEDEF, row);
            if (!r) return "TypeDef[row=" + std::to_string(row) + "]";
            uint8_t sIdx = string_idx_size(dn);
            uint32_t nameIdx = read_idx(r + 4, sIdx);
            uint32_t nsIdx = read_idx(r + 4 + sIdx, sIdx);
            std::string ns = read_strings_heap(data, len, dn, nsIdx);
            std::string name = read_strings_heap(data, len, dn, nameIdx);
            return ns.empty() ? ("TypeDef:" + name) : ("TypeDef:" + ns + "." + name);
        }
        case TBL_TYPEREF: {
            const uint8_t* r = get_table_row(data, len, dn, TBL_TYPEREF, row);
            if (!r) return "TypeRef[row=" + std::to_string(row) + "]";
            uint8_t rsSize = coded_index_size(dn, CI_RESOLUTION_SCOPE);
            uint8_t sIdx = string_idx_size(dn);
            uint32_t nameIdx = read_idx(r + rsSize, sIdx);
            uint32_t nsIdx = read_idx(r + rsSize + sIdx, sIdx);
            std::string ns = read_strings_heap(data, len, dn, nsIdx);
            std::string name = read_strings_heap(data, len, dn, nameIdx);
            return ns.empty() ? ("TypeRef:" + name) : ("TypeRef:" + ns + "." + name);
        }
        case TBL_METHODDEF: {
            const uint8_t* r = get_table_row(data, len, dn, TBL_METHODDEF, row);
            if (!r) return "MethodDef[row=" + std::to_string(row) + "]";
            uint8_t sIdx = string_idx_size(dn);
            uint8_t bIdx = blob_idx_size(dn);
            uint32_t nameIdx = read_idx(r + 8, sIdx); // after RVA(4) + ImplFlags(2) + Flags(2)
            std::string name = read_strings_heap(data, len, dn, nameIdx);
            return "MethodDef:" + name;
        }
        case TBL_FIELD: {
            const uint8_t* r = get_table_row(data, len, dn, TBL_FIELD, row);
            if (!r) return "Field[row=" + std::to_string(row) + "]";
            uint8_t sIdx = string_idx_size(dn);
            uint32_t nameIdx = read_idx(r + 2, sIdx); // after Flags(2)
            std::string name = read_strings_heap(data, len, dn, nameIdx);
            return "Field:" + name;
        }
        case TBL_MEMBERREF: {
            const uint8_t* r = get_table_row(data, len, dn, TBL_MEMBERREF, row);
            if (!r) return "MemberRef[row=" + std::to_string(row) + "]";
            uint8_t mrpSize = coded_index_size(dn, CI_MEMBERREF_PARENT);
            uint8_t sIdx = string_idx_size(dn);
            uint32_t nameIdx = read_idx(r + mrpSize, sIdx);
            std::string name = read_strings_heap(data, len, dn, nameIdx);
            return "MemberRef:" + name;
        }
        case TBL_PARAM: {
            const uint8_t* r = get_table_row(data, len, dn, TBL_PARAM, row);
            if (!r) return "Param[row=" + std::to_string(row) + "]";
            uint8_t sIdx = string_idx_size(dn);
            uint32_t nameIdx = read_idx(r + 4, sIdx); // after Flags(2) + Sequence(2)
            std::string name = read_strings_heap(data, len, dn, nameIdx);
            return "Param:" + name;
        }
        case TBL_TYPESPEC: {
            return "TypeSpec[row=" + std::to_string(row) + "]";
        }
        case TBL_METHODSPEC: {
            return "MethodSpec[row=" + std::to_string(row) + "]";
        }
        case TBL_ASSEMBLYREF: {
            const uint8_t* r = get_table_row(data, len, dn, TBL_ASSEMBLYREF, row);
            if (!r) return "AssemblyRef[row=" + std::to_string(row) + "]";
            uint8_t bIdx = blob_idx_size(dn);
            uint8_t sIdx = string_idx_size(dn);
            const uint8_t* p = r + 2 + 2 + 2 + 2 + 4 + bIdx; // skip to Name
            uint32_t nameIdx = read_idx(p, sIdx);
            std::string name = read_strings_heap(data, len, dn, nameIdx);
            return "AssemblyRef:" + name;
        }
        default: {
            return std::string(token_type_name(tbl)) + "[row=" + std::to_string(row) + "]";
        }
    }
}

// ── JSON: list all types ──
inline std::string dotnet_list_types_json(const uint8_t* data, size_t len,
                                           const DotNetMetadata& dn) {
    JsonBuilder j;
    if (!dn.valid) { j.str("error", dn.error.empty() ? "parse_failed" : dn.error); return j.build(); }
    j.cstr("assembly", dn.assemblyName.c_str());
    j.num("typeCount", dn.tableRows[TBL_TYPEDEF]);

    j.begin_array("types");
    uint32_t typeCount = dn.tableRows[TBL_TYPEDEF];
    uint8_t sIdx = string_idx_size(dn);
    uint8_t ciSize = coded_index_size(dn, CI_TYPEDEF_OR_REF);
    uint8_t fldIdx = table_index_size(dn, TBL_FIELD);
    uint8_t mtdIdx = table_index_size(dn, TBL_METHODDEF);

    for (uint32_t i = 1; i <= typeCount && i <= 5000; ++i) {
        const uint8_t* r = get_table_row(data, len, dn, TBL_TYPEDEF, i);
        if (!r) break;
        uint32_t flags = read_le32(r);
        uint32_t nameIdx = read_idx(r + 4, sIdx);
        uint32_t nsIdx = read_idx(r + 4 + sIdx, sIdx);
        uint32_t extendsRaw = read_idx(r + 4 + 2 * sIdx, ciSize);
        uint32_t fieldStart = read_idx(r + 4 + 2 * sIdx + ciSize, fldIdx);
        uint32_t methodStart = read_idx(r + 4 + 2 * sIdx + ciSize + fldIdx, mtdIdx);

        std::string name = read_strings_heap(data, len, dn, nameIdx);
        std::string ns = read_strings_heap(data, len, dn, nsIdx);

        // Count methods: from this row's methodStart to next row's methodStart
        uint32_t methodEnd = dn.tableRows[TBL_METHODDEF] + 1;
        if (i < typeCount) {
            const uint8_t* nr = get_table_row(data, len, dn, TBL_TYPEDEF, i + 1);
            if (nr) methodEnd = read_idx(nr + 4 + 2 * sIdx + ciSize + fldIdx, mtdIdx);
        }
        uint32_t methodCount = (methodEnd > methodStart) ? (methodEnd - methodStart) : 0;

        // Count fields similarly
        uint32_t fieldEnd = dn.tableRows[TBL_FIELD] + 1;
        if (i < typeCount) {
            const uint8_t* nr = get_table_row(data, len, dn, TBL_TYPEDEF, i + 1);
            if (nr) fieldEnd = read_idx(nr + 4 + 2 * sIdx + ciSize, fldIdx);
        }
        uint32_t fieldCount = (fieldEnd > fieldStart) ? (fieldEnd - fieldStart) : 0;

        // Resolve extends
        uint8_t extTag = extendsRaw & ((1 << CI_TYPEDEF_OR_REF.tagBits) - 1);
        uint32_t extRow = extendsRaw >> CI_TYPEDEF_OR_REF.tagBits;
        std::string extendsName;
        if (extRow > 0 && extTag < CI_TYPEDEF_OR_REF.tableCount) {
            DnTable extTbl = CI_TYPEDEF_OR_REF.tables[extTag];
            uint32_t extTok = (extTbl << 24) | extRow;
            extendsName = resolve_token(data, len, dn, {}, extTok);
        }

        j.begin_object();
        j.num("token", (TBL_TYPEDEF << 24) | i);
        j.cstr("name", name.c_str());
        j.cstr("namespace", ns.c_str());
        j.cstr("fullName", (ns.empty() ? name : ns + "." + name).c_str());
        j.num("flags", flags);
        j.num("fieldCount", fieldCount);
        j.num("methodCount", methodCount);
        j.cstr("extends", extendsName.c_str());
        j.end_object();
    }
    j.end_array();
    return j.build();
}

// ── JSON: list all methods ──
inline std::string dotnet_list_methods_json(const uint8_t* data, size_t len,
                                             const DotNetMetadata& dn, const PeFile& pe,
                                             uint32_t typeFilter) {
    JsonBuilder j;
    if (!dn.valid) { j.str("error", dn.error.empty() ? "parse_failed" : dn.error); return j.build(); }
    j.num("methodCount", dn.tableRows[TBL_METHODDEF]);

    j.begin_array("methods");
    uint32_t methodCount = dn.tableRows[TBL_METHODDEF];
    uint8_t sIdx = string_idx_size(dn);
    uint8_t bIdx = blob_idx_size(dn);
    uint8_t pIdx = table_index_size(dn, TBL_PARAM);

    for (uint32_t i = 1; i <= methodCount && i <= 10000; ++i) {
        const uint8_t* r = get_table_row(data, len, dn, TBL_METHODDEF, i);
        if (!r) break;
        uint32_t rva = read_le32(r);
        uint16_t implFlags = read_le16(r + 4);
        uint16_t flags = read_le16(r + 6);
        uint32_t nameIdx = read_idx(r + 8, sIdx);
        // signature at r + 8 + sIdx
        uint32_t paramStart = read_idx(r + 8 + sIdx + bIdx, pIdx);

        std::string name = read_strings_heap(data, len, dn, nameIdx);

        // Count params
        uint32_t paramEnd = dn.tableRows[TBL_PARAM] + 1;
        if (i < methodCount) {
            const uint8_t* nr = get_table_row(data, len, dn, TBL_METHODDEF, i + 1);
            if (nr) paramEnd = read_idx(nr + 8 + sIdx + bIdx, pIdx);
        }
        uint32_t paramCount = (paramEnd > paramStart) ? (paramEnd - paramStart) : 0;

        // Parse method body to get IL size
        DnMethodBody mb;
        std::string ilHex;
        uint32_t ilSize = 0;
        if (rva > 0) {
            mb = parse_method_body(data, len, pe, dn, rva);
            if (mb.valid) {
                ilSize = mb.codeSize;
                // Preview first 32 bytes of IL
                size_t previewLen = std::min((size_t)32, (size_t)mb.codeSize);
                if (mb.ilOffset + previewLen <= len) {
                    ilHex = to_hex(data + mb.ilOffset, previewLen, true);
                }
            }
        }

        j.begin_object();
        j.num("token", (TBL_METHODDEF << 24) | i);
        j.num("row", i);
        j.cstr("name", name.c_str());
        j.num("rva", rva);
        j.num("flags", flags);
        j.num("implFlags", implFlags);
        j.num("paramCount", paramCount);
        j.boolean("hasBody", mb.valid);
        j.boolean("isFat", mb.isFat);
        j.num("ilSize", ilSize);
        j.num("maxStack", mb.maxStack);
        j.num("localVarSigTok", mb.localVarSigTok);
        j.num("fileOffset", mb.fileOffset);
        j.num("ilOffset", mb.ilOffset);
        j.cstr("ilPreview", ilHex.c_str());
        j.end_object();
    }
    j.end_array();
    return j.build();
}

// ── JSON: list user strings from #US heap ──
inline std::string dotnet_list_strings_json(const uint8_t* data, size_t len,
                                             const DotNetMetadata& dn, size_t maxCount) {
    JsonBuilder j;
    if (!dn.valid) { j.str("error", dn.error.empty() ? "parse_failed" : dn.error); return j.build(); }
    if (dn.usSize == 0) { j.str("error", "no #US stream"); return j.build(); }

    j.begin_array("strings");
    size_t count = 0;
    uint32_t off = dn.usOffset + 1; // skip first null byte
    uint32_t end = dn.usOffset + dn.usSize;

    while (off < end && count < maxCount) {
        if (off >= len) break;
        // Read compressed length
        size_t consumed;
        uint32_t blobLen = read_compressed_uint(data + off, end - off, consumed);
        if (consumed == 0 || blobLen == 0) {
            off += (consumed > 0 ? consumed : 1);
            continue;
        }
        uint32_t strOff = off; // offset of length prefix in #US
        uint32_t dataOff = off + consumed;
        if (dataOff + blobLen > len || dataOff + blobLen > end) break;

        // Decode string (UTF-16LE, last byte is terminal)
        std::string s = read_us_heap(data, len, dn, strOff - dn.usOffset);
        if (!s.empty()) {
            j.begin_object();
            j.num("usOffset", strOff - dn.usOffset);
            j.num("heapOffset", strOff);
            j.num("byteLength", blobLen);
            j.cstr("text", s.c_str());
            j.end_object();
            ++count;
        }
        off = dataOff + blobLen;
    }
    j.end_array();
    j.num("count", count);
    return j.build();
}

// ── JSON: resolve a token ──
inline std::string dotnet_resolve_token_json(const uint8_t* data, size_t len,
                                              const DotNetMetadata& dn, const PeFile& pe,
                                              uint32_t token) {
    JsonBuilder j;
    if (!dn.valid) { j.str("error", dn.error.empty() ? "parse_failed" : dn.error); return j.build(); }
    uint8_t tbl = (token >> 24) & 0xFF;
    uint32_t row = token & 0x00FFFFFF;
    j.num("token", token);
    j.cstr("table", token_type_name(tbl));
    j.num("tableId", tbl);
    j.num("row", row);
    j.cstr("resolved", resolve_token(data, len, dn, pe, token).c_str());
    return j.build();
}

// ── JSON: dump IL for a method ──
inline std::string dotnet_dump_il_json(const uint8_t* data, size_t len,
                                        const DotNetMetadata& dn, const PeFile& pe,
                                        uint32_t methodToken) {
    JsonBuilder j;
    if (!dn.valid) { j.str("error", dn.error.empty() ? "parse_failed" : dn.error); return j.build(); }
    uint8_t tbl = (methodToken >> 24) & 0xFF;
    uint32_t row = methodToken & 0x00FFFFFF;
    if (tbl != TBL_METHODDEF) { j.str("error", "token is not a MethodDef"); return j.build(); }

    const uint8_t* r = get_table_row(data, len, dn, TBL_METHODDEF, row);
    if (!r) { j.str("error", "method row not found"); return j.build(); }

    uint32_t rva = read_le32(r);
    uint8_t sIdx = string_idx_size(dn);
    uint32_t nameIdx = read_idx(r + 8, sIdx);
    std::string name = read_strings_heap(data, len, dn, nameIdx);
    j.cstr("methodName", name.c_str());
    j.num("rva", rva);

    if (rva == 0) { j.str("error", "method has no body (abstract/external)"); return j.build(); }

    DnMethodBody mb = parse_method_body(data, len, pe, dn, rva);
    if (!mb.valid) { j.str("error", "failed to parse method body"); return j.build(); }

    j.boolean("isFat", mb.isFat);
    j.num("headerSize", mb.headerSize);
    j.num("codeSize", mb.codeSize);
    j.num("maxStack", mb.maxStack);
    j.num("localVarSigTok", mb.localVarSigTok);
    j.num("ilFileOffset", mb.ilOffset);

    // Dump IL as hex
    size_t dumpLen = std::min((size_t)mb.codeSize, (size_t)65536);
    if (mb.ilOffset + dumpLen <= len) {
        j.cstr("ilHex", to_hex(data + mb.ilOffset, dumpLen, true).c_str());
    }

    return j.build();
}

// ── Patch IL bytes at a given IL offset within a method ──
inline std::vector<uint8_t> dotnet_edit_il(
        const uint8_t* data, size_t len, const PeFile& pe, const DotNetMetadata& dn,
        uint32_t methodToken, uint32_t ilOffset, const uint8_t* patch, size_t plen) {
    if (!dn.valid) return {};
    uint8_t tbl = (methodToken >> 24) & 0xFF;
    uint32_t row = methodToken & 0x00FFFFFF;
    if (tbl != TBL_METHODDEF) return {};

    const uint8_t* r = get_table_row(data, len, dn, TBL_METHODDEF, row);
    if (!r) return {};
    uint32_t rva = read_le32(r);
    if (rva == 0) return {};

    DnMethodBody mb = parse_method_body(data, len, pe, dn, rva);
    if (!mb.valid) return {};

    uint64_t patchOff = mb.ilOffset + ilOffset;
    if (patchOff + plen > len) return {};
    if (ilOffset + plen > mb.codeSize) return {};

    std::vector<uint8_t> out(data, data + len);
    std::memcpy(out.data() + patchOff, patch, plen);
    return out;
}

// ── Edit a user string in #US heap ──
// Replaces the string content at the given #US offset with a new string.
// The new string must fit within the original blob size (or equal).
inline std::vector<uint8_t> dotnet_edit_us_string(
        const uint8_t* data, size_t len, const DotNetMetadata& dn,
        uint32_t usOffset, const std::string& newStr) {
    if (!dn.valid || dn.usSize == 0) return {};
    uint32_t off = dn.usOffset + usOffset;
    if (off >= len || off >= dn.usOffset + dn.usSize) return {};

    // Read original blob length
    size_t consumed;
    uint32_t origBlobLen = read_compressed_uint(data + off, dn.usOffset + dn.usSize - off, consumed);
    if (consumed == 0 || origBlobLen == 0) return {};

    uint32_t dataOff = off + consumed;
    // Original string byte length (excluding terminal byte) = origBlobLen - 1
    uint32_t origStrLen = origBlobLen - 1;

    // Encode new string as UTF-16LE
    std::vector<uint8_t> newBytes;
    for (size_t i = 0; i < newStr.size(); ++i) {
        unsigned char c = newStr[i];
        if (c < 0x80) {
            newBytes.push_back(c);
            newBytes.push_back(0);
        } else {
            // Decode UTF-8 sequence
            uint32_t ch;
            int extra;
            if ((c & 0xE0) == 0xC0) { ch = c & 0x1F; extra = 1; }
            else if ((c & 0xF0) == 0xE0) { ch = c & 0x0F; extra = 2; }
            else if ((c & 0xF8) == 0xF0) { ch = c & 0x07; extra = 3; }
            else { ch = c; extra = 0; }
            for (int j = 0; j < extra && i + 1 < newStr.size(); ++j) {
                ++i;
                ch = (ch << 6) | (newStr[i] & 0x3F);
            }
            // Encode as UTF-16
            if (ch < 0x10000) {
                newBytes.push_back(ch & 0xFF);
                newBytes.push_back((ch >> 8) & 0xFF);
            } else {
                // Surrogate pair
                ch -= 0x10000;
                uint16_t hi = 0xD800 + (ch >> 10);
                uint16_t lo = 0xDC00 + (ch & 0x3FF);
                newBytes.push_back(hi & 0xFF);
                newBytes.push_back((hi >> 8) & 0xFF);
                newBytes.push_back(lo & 0xFF);
                newBytes.push_back((lo >> 8) & 0xFF);
            }
        }
    }

    // New blob: newBytes + terminal byte
    uint32_t newStrLen = static_cast<uint32_t>(newBytes.size());
    uint32_t newBlobLen = newStrLen + 1; // +1 for terminal byte

    if (newBlobLen > origBlobLen) {
        // Can't expand — would require shifting all data after this point
        // For safety, we only support in-place edits (same or shorter)
        return {};
    }

    std::vector<uint8_t> out(data, data + len);
    // Write new string bytes
    std::memcpy(out.data() + dataOff, newBytes.data(), newStrLen);
    // Write terminal byte
    out[dataOff + newStrLen] = 0;
    // Zero-fill remaining space if new is shorter
    if (newBlobLen < origBlobLen) {
        std::memset(out.data() + dataOff + newBlobLen, 0, origBlobLen - newBlobLen);
    }
    return out;
}

}  // namespace dsmcp
