//
// il_disasm.h - Lightweight IL disassembler for .NET method bodies.
//
// Decodes CIL (Common Intermediate Language) bytecodes into readable text
// with metadata token resolution. Covers ~80% of common IL patterns.
//
// Reference: ECMA-335, Partition III (CIL Instruction Set)
//
#pragma once

#include "hex_util.h"
#include "json_builder.h"
#include "dotnet_parser.h"
#include <string>
#include <vector>
#include <cstdint>
#include <cstdio>
#include <sstream>

namespace dsmcp {

// ── IL opcode table ──

struct IlOpcode {
    const char* name;
    uint8_t operandType;  // 0=none, 1=int8, 2=uint8, 3=int16, 4=uint16,
                           // 5=int32, 6=uint32, 7=int64, 8=uint64,
                           // 9=float32, 10=float64,
                           // 11=token(4 bytes), 12=branch8(int8), 13=branch32(int32),
                           // 14=switch
};

enum IlOperandType : uint8_t {
    OT_NONE = 0,
    OT_INT8 = 1,
    OT_UINT8 = 2,
    OT_INT16 = 3,
    OT_UINT16 = 4,
    OT_INT32 = 5,
    OT_UINT32 = 6,
    OT_INT64 = 7,
    OT_UINT64 = 8,
    OT_FLOAT32 = 9,
    OT_FLOAT64 = 10,
    OT_TOKEN = 11,
    OT_BRANCH8 = 12,
    OT_BRANCH32 = 13,
    OT_SWITCH = 14,
};

// Single-byte opcodes (0x00 - 0xFE)
static const IlOpcode OPCODES_1[256] = {
    {"nop",           OT_NONE},       // 0x00
    {"break",         OT_NONE},       // 0x01
    {"ldarg.0",       OT_NONE},       // 0x02
    {"ldarg.1",       OT_NONE},       // 0x03
    {"ldarg.2",       OT_NONE},       // 0x04
    {"ldarg.3",       OT_NONE},       // 0x05
    {"ldloc.0",       OT_NONE},       // 0x06
    {"ldloc.1",       OT_NONE},       // 0x07
    {"ldloc.2",       OT_NONE},       // 0x08
    {"ldloc.3",       OT_NONE},       // 0x09
    {"stloc.0",       OT_NONE},       // 0x0A
    {"stloc.1",       OT_NONE},       // 0x0B
    {"stloc.2",       OT_NONE},       // 0x0C
    {"stloc.3",       OT_NONE},       // 0x0D
    {"ldarg.s",       OT_UINT8},      // 0x0E
    {"ldarga.s",      OT_UINT8},      // 0x0F
    {"starg.s",       OT_UINT8},      // 0x10
    {"ldloc.s",       OT_UINT8},      // 0x11
    {"ldloca.s",      OT_UINT8},      // 0x12
    {"stloc.s",       OT_UINT8},      // 0x13
    {"ldnull",        OT_NONE},       // 0x14
    {"ldc.i4.m1",     OT_NONE},       // 0x15
    {"ldc.i4.0",      OT_NONE},       // 0x16
    {"ldc.i4.1",      OT_NONE},       // 0x17
    {"ldc.i4.2",      OT_NONE},       // 0x18
    {"ldc.i4.3",      OT_NONE},       // 0x19
    {"ldc.i4.4",      OT_NONE},       // 0x1A
    {"ldc.i4.5",      OT_NONE},       // 0x1B
    {"ldc.i4.6",      OT_NONE},       // 0x1C
    {"ldc.i4.7",      OT_NONE},       // 0x1D
    {"ldc.i4.8",      OT_NONE},       // 0x1E
    {"ldc.i4.s",      OT_INT8},       // 0x1F
    {"ldc.i4",        OT_INT32},      // 0x20
    {"ldc.i8",        OT_INT64},      // 0x21
    {"ldc.r4",        OT_FLOAT32},    // 0x22
    {"ldc.r8",        OT_FLOAT64},    // 0x23
    {"<unused>",      OT_NONE},       // 0x24
    {"dup",           OT_NONE},       // 0x25
    {"pop",           OT_NONE},       // 0x26
    {"jmp",           OT_TOKEN},      // 0x27
    {"call",          OT_TOKEN},      // 0x28
    {"calli",         OT_TOKEN},      // 0x29
    {"ret",           OT_NONE},       // 0x2A
    {"br.s",          OT_BRANCH8},    // 0x2B
    {"brfalse.s",     OT_BRANCH8},    // 0x2C
    {"brtrue.s",      OT_BRANCH8},    // 0x2D
    {"beq.s",         OT_BRANCH8},    // 0x2E
    {"bge.s",         OT_BRANCH8},    // 0x2F
    {"bgt.s",         OT_BRANCH8},    // 0x30
    {"ble.s",         OT_BRANCH8},    // 0x31
    {"blt.s",         OT_BRANCH8},    // 0x32
    {"bne.un.s",      OT_BRANCH8},    // 0x33
    {"bge.un.s",      OT_BRANCH8},    // 0x34
    {"bgt.un.s",      OT_BRANCH8},    // 0x35
    {"ble.un.s",      OT_BRANCH8},    // 0x36
    {"blt.un.s",      OT_BRANCH8},    // 0x37
    {"br",            OT_BRANCH32},   // 0x38
    {"brfalse",       OT_BRANCH32},   // 0x39
    {"brtrue",        OT_BRANCH32},   // 0x3A
    {"beq",           OT_BRANCH32},   // 0x3B
    {"bge",           OT_BRANCH32},   // 0x3C
    {"bgt",           OT_BRANCH32},   // 0x3D
    {"ble",           OT_BRANCH32},   // 0x3E
    {"blt",           OT_BRANCH32},   // 0x3F
    {"bne.un",        OT_BRANCH32},   // 0x40
    {"bge.un",        OT_BRANCH32},   // 0x41
    {"bgt.un",        OT_BRANCH32},   // 0x42
    {"ble.un",        OT_BRANCH32},   // 0x43
    {"blt.un",        OT_BRANCH32},   // 0x44
    {"switch",        OT_SWITCH},     // 0x45
    {"ldind.i1",      OT_NONE},       // 0x46
    {"ldind.u1",      OT_NONE},       // 0x47
    {"ldind.i2",      OT_NONE},       // 0x48
    {"ldind.u2",      OT_NONE},       // 0x49
    {"ldind.i4",      OT_NONE},       // 0x4A
    {"ldind.u4",      OT_NONE},       // 0x4B
    {"ldind.i8",      OT_NONE},       // 0x4C
    {"ldind.u",       OT_NONE},       // 0x4D
    {"ldind.r4",      OT_NONE},       // 0x4E
    {"ldind.r8",      OT_NONE},       // 0x4F
    {"ldind.ref",     OT_NONE},       // 0x50
    {"stind.ref",     OT_NONE},       // 0x51
    {"stind.i1",      OT_NONE},       // 0x52
    {"stind.i2",      OT_NONE},       // 0x53
    {"stind.i4",      OT_NONE},       // 0x54
    {"stind.i8",      OT_NONE},       // 0x55
    {"stind.r4",      OT_NONE},       // 0x56
    {"stind.r8",      OT_NONE},       // 0x57
    {"add",           OT_NONE},       // 0x58
    {"sub",           OT_NONE},       // 0x59
    {"mul",           OT_NONE},       // 0x5A
    {"div",           OT_NONE},       // 0x5B
    {"div.un",        OT_NONE},       // 0x5C
    {"rem",           OT_NONE},       // 0x5D
    {"rem.un",        OT_NONE},       // 0x5E
    {"and",           OT_NONE},       // 0x5F
    {"or",            OT_NONE},       // 0x60
    {"xor",           OT_NONE},       // 0x61
    {"shl",           OT_NONE},       // 0x62
    {"shr",           OT_NONE},       // 0x63
    {"shr.un",        OT_NONE},       // 0x64
    {"neg",           OT_NONE},       // 0x65
    {"not",           OT_NONE},       // 0x66
    {"conv.i1",       OT_NONE},       // 0x67
    {"conv.i2",       OT_NONE},       // 0x68
    {"conv.i4",       OT_NONE},       // 0x69
    {"conv.i8",       OT_NONE},       // 0x6A
    {"conv.r4",       OT_NONE},       // 0x6B
    {"conv.r8",       OT_NONE},       // 0x6C
    {"conv.u4",       OT_NONE},       // 0x6D
    {"conv.u8",       OT_NONE},       // 0x6E
    {"callvirt",      OT_TOKEN},      // 0x6F
    {"cpobj",         OT_TOKEN},      // 0x70
    {"ldobj",         OT_TOKEN},      // 0x71
    {"ldstr",         OT_TOKEN},      // 0x72
    {"newobj",        OT_TOKEN},      // 0x73
    {"castclass",     OT_TOKEN},      // 0x74
    {"isinst",        OT_TOKEN},      // 0x75
    {"conv.r.un",     OT_NONE},       // 0x76
    {"<unused>",      OT_NONE},       // 0x77
    {"unbox",         OT_TOKEN},      // 0x78
    {"throw",         OT_NONE},       // 0x7A
    {"ldfld",         OT_TOKEN},      // 0x7B
    {"ldflda",        OT_TOKEN},      // 0x7C
    {"stfld",         OT_TOKEN},      // 0x7D
    {"ldsfld",        OT_TOKEN},      // 0x7E
    {"ldsflda",       OT_TOKEN},      // 0x7F
    {"stsfld",        OT_TOKEN},      // 0x80
    {"stobj",         OT_TOKEN},      // 0x81
    {"conv.ovf.i1.un",OT_NONE},       // 0x82
    {"conv.ovf.i2.un",OT_NONE},       // 0x83
    {"conv.ovf.i4.un",OT_NONE},       // 0x84
    {"conv.ovf.i8.un",OT_NONE},       // 0x85
    {"conv.ovf.u1.un",OT_NONE},       // 0x86
    {"conv.ovf.u2.un",OT_NONE},       // 0x87
    {"conv.ovf.u4.un",OT_NONE},       // 0x88
    {"conv.ovf.u8.un",OT_NONE},       // 0x89
    {"conv.ovf.i.un", OT_NONE},       // 0x8A
    {"conv.ovf.u.un", OT_NONE},       // 0x8B
    {"box",           OT_TOKEN},      // 0x8C
    {"newarr",        OT_TOKEN},      // 0x8D
    {"ldlen",         OT_NONE},       // 0x8E
    {"ldelema",       OT_TOKEN},      // 0x8F
    {"ldelem.i1",     OT_NONE},       // 0x90
    {"ldelem.u1",     OT_NONE},       // 0x91
    {"ldelem.i2",     OT_NONE},       // 0x92
    {"ldelem.u2",     OT_NONE},       // 0x93
    {"ldelem.i4",     OT_NONE},       // 0x94
    {"ldelem.u4",     OT_NONE},       // 0x95
    {"ldelem.i8",     OT_NONE},       // 0x96
    {"ldelem.r4",     OT_NONE},       // 0x97
    {"ldelem.r8",     OT_NONE},       // 0x98
    {"ldelem.ref",    OT_NONE},       // 0x99
    {"stelem.i",      OT_NONE},       // 0x9B
    {"stelem.i1",     OT_NONE},       // 0x9C
    {"stelem.i2",     OT_NONE},       // 0x9D
    {"stelem.i4",     OT_NONE},       // 0x9E
    {"stelem.i8",     OT_NONE},       // 0x9F
    {"stelem.r4",     OT_NONE},       // 0xA0
    {"stelem.r8",     OT_NONE},       // 0xA1
    {"stelem.ref",    OT_NONE},       // 0xA2
    {"ldelem",        OT_TOKEN},      // 0xA3
    {"stelem",        OT_TOKEN},      // 0xA4
    {"unbox.any",     OT_TOKEN},      // 0xA5
    {"conv.ovf.i1",   OT_NONE},       // 0xB3
    {"conv.ovf.u1",   OT_NONE},       // 0xB4
    {"conv.ovf.i2",   OT_NONE},       // 0xB5
    {"conv.ovf.u2",   OT_NONE},       // 0xB6
    {"conv.ovf.i4",   OT_NONE},       // 0xB7
    {"conv.ovf.u4",   OT_NONE},       // 0xB8
    {"conv.ovf.i8",   OT_NONE},       // 0xB9
    {"conv.ovf.u8",   OT_NONE},       // 0xBA
    {"refanyval",     OT_TOKEN},      // 0xC2
    {"ckfinite",      OT_NONE},       // 0xC3
    {"mkrefany",      OT_TOKEN},      // 0xC6
    {"ldtoken",       OT_TOKEN},      // 0xD0
    {"conv.u2",       OT_NONE},       // 0xD1
    {"conv.u1",       OT_NONE},       // 0xD2
    {"conv.i",        OT_NONE},       // 0xD3
    {"conv.ovf.i",    OT_NONE},       // 0xD4
    {"conv.ovf.u",    OT_NONE},       // 0xD5
    {"add.ovf",       OT_NONE},       // 0xD6
    {"add.ovf.un",    OT_NONE},       // 0xD7
    {"mul.ovf",       OT_NONE},       // 0xD8
    {"mul.ovf.un",    OT_NONE},       // 0xD9
    {"sub.ovf",       OT_NONE},       // 0xDA
    {"sub.ovf.un",    OT_NONE},       // 0xDB
    {"endfinally",    OT_NONE},       // 0xDC
    {"leave",         OT_BRANCH32},   // 0xDD
    {"leave.s",       OT_BRANCH8},    // 0xDE
    {"stind.i",       OT_NONE},       // 0xDF
    {"conv.u",        OT_NONE},       // 0xE0
    {"<unknown>",     OT_NONE},       // 0xE1-0xFE
};

// Fill gaps with unknown
static void init_opcodes() {} // table is static-initialized

// Two-byte opcodes (0xFE prefix)
static const IlOpcode OPCODES_2[256] = {
    {"arglist",       OT_NONE},       // 0x00
    {"ceq",           OT_NONE},       // 0x01
    {"cgt",           OT_NONE},       // 0x02
    {"cgt.un",        OT_NONE},       // 0x03
    {"clt",           OT_NONE},       // 0x04
    {"clt.un",        OT_NONE},       // 0x05
    {"ldftn",         OT_TOKEN},      // 0x06
    {"ldvirtftn",     OT_TOKEN},      // 0x07
    {"<unknown>",     OT_NONE},       // 0x08
    {"ldarg",         OT_UINT16},     // 0x09
    {"ldarga",        OT_UINT16},     // 0x0A
    {"starg",         OT_UINT16},     // 0x0B
    {"ldloc",         OT_UINT16},     // 0x0C
    {"ldloca",        OT_UINT16},     // 0x0D
    {"stloc",         OT_UINT16},     // 0x0E
    {"localloc",      OT_NONE},       // 0x0F
    {"<unknown>",     OT_NONE},       // 0x10
    {"endfilter",     OT_NONE},       // 0x11
    {"unaligned.",    OT_UINT8},      // 0x12
    {"volatile.",     OT_NONE},       // 0x13
    {"tail.",         OT_NONE},       // 0x14
    {"initobj",       OT_TOKEN},      // 0x15
    {"constrained.",  OT_TOKEN},      // 0x16
    {"cpblk",         OT_NONE},       // 0x17
    {"initblk",       OT_NONE},       // 0x18
    {"<unknown>",     OT_NONE},       // 0x19
    {"rethrow",       OT_NONE},       // 0x1A
    {"<unknown>",     OT_NONE},       // 0x1B
    {"sizeof",        OT_TOKEN},      // 0x1C
    {"refanytype",    OT_NONE},       // 0x1D
    {"<unknown>",     OT_NONE},       // 0x1E+
};

// ── Disassemble IL to readable text ──
inline std::string disassemble_il(const uint8_t* ilData, size_t ilLen,
                                   const uint8_t* fileData, size_t fileLen,
                                   const DotNetMetadata& dn, const PeFile& pe,
                                   uint64_t ilBaseOffset) {
    std::string out;
    size_t pc = 0;
    char line[512];

    while (pc < ilLen) {
        // Offset
        snprintf(line, sizeof(line), "IL_%0404X: ", (unsigned)pc);
        out += line;

        uint8_t op = ilData[pc];
        const IlOpcode* opcode;
        size_t opLen;

        if (op == 0xFE) {
            // Two-byte opcode
            if (pc + 1 >= ilLen) {
                out += "fe <truncated>\n";
                break;
            }
            uint8_t op2 = ilData[pc + 1];
            opcode = &OPCODES_2[op2];
            opLen = 2;
        } else {
            opcode = &OPCODES_1[op];
            opLen = 1;
        }

        out += opcode->name;

        // Check bounds
        size_t operandStart = pc + opLen;
        if (operandStart >= ilLen && opcode->operandType != OT_NONE) {
            out += " <truncated operand>\n";
            break;
        }

        // Process operand
        switch (opcode->operandType) {
            case OT_NONE:
                break;
            case OT_INT8: {
                int8_t v = static_cast<int8_t>(ilData[operandStart]);
                snprintf(line, sizeof(line), " %d", v);
                out += line;
                opLen += 1;
                break;
            }
            case OT_UINT8: {
                uint8_t v = ilData[operandStart];
                snprintf(line, sizeof(line), " %u", v);
                out += line;
                opLen += 1;
                break;
            }
            case OT_INT16: {
                if (operandStart + 2 > ilLen) { out += " <truncated>\n"; break; }
                int16_t v = static_cast<int16_t>(read_le16(ilData + operandStart));
                snprintf(line, sizeof(line), " %d", v);
                out += line;
                opLen += 2;
                break;
            }
            case OT_UINT16: {
                if (operandStart + 2 > ilLen) { out += " <truncated>\n"; break; }
                uint16_t v = read_le16(ilData + operandStart);
                snprintf(line, sizeof(line), " %u", v);
                out += line;
                opLen += 2;
                break;
            }
            case OT_INT32: {
                if (operandStart + 4 > ilLen) { out += " <truncated>\n"; break; }
                int32_t v = static_cast<int32_t>(read_le32(ilData + operandStart));
                snprintf(line, sizeof(line), " %d", v);
                out += line;
                opLen += 4;
                break;
            }
            case OT_INT64: {
                if (operandStart + 8 > ilLen) { out += " <truncated>\n"; break; }
                uint64_t v = read_le64(ilData + operandStart);
                snprintf(line, sizeof(line), " 0x%016llx", (unsigned long long)v);
                out += line;
                opLen += 8;
                break;
            }
            case OT_FLOAT32: {
                if (operandStart + 4 > ilLen) { out += " <truncated>\n"; break; }
                float f;
                uint32_t raw = read_le32(ilData + operandStart);
                std::memcpy(&f, &raw, 4);
                snprintf(line, sizeof(line), " %g", f);
                out += line;
                opLen += 4;
                break;
            }
            case OT_FLOAT64: {
                if (operandStart + 8 > ilLen) { out += " <truncated>\n"; break; }
                double d;
                uint64_t raw = read_le64(ilData + operandStart);
                std::memcpy(&d, &raw, 8);
                snprintf(line, sizeof(line), " %g", d);
                out += line;
                opLen += 8;
                break;
            }
            case OT_TOKEN: {
                if (operandStart + 4 > ilLen) { out += " <truncated>\n"; break; }
                uint32_t tok = read_le32(ilData + operandStart);
                std::string resolved = resolve_token(fileData, fileLen, dn, pe, tok);
                // Special case: ldstr — resolve user string
                if (op == 0x72) {  // ldstr
                    uint32_t usIdx = tok & 0x00FFFFFF;
                    std::string str = read_us_heap(fileData, fileLen, dn, usIdx);
                    snprintf(line, sizeof(line), " 0x%08X \"%s\"", tok, str.c_str());
                } else {
                    snprintf(line, sizeof(line), " 0x%08X /* %s */", tok, resolved.c_str());
                }
                out += line;
                opLen += 4;
                break;
            }
            case OT_BRANCH8: {
                int8_t target = static_cast<int8_t>(ilData[operandStart]);
                uint32_t targetOff = static_cast<uint32_t>(pc + opLen + 1 + target);
                snprintf(line, sizeof(line), " IL_%04X", targetOff);
                out += line;
                opLen += 1;
                break;
            }
            case OT_BRANCH32: {
                if (operandStart + 4 > ilLen) { out += " <truncated>\n"; break; }
                int32_t target = static_cast<int32_t>(read_le32(ilData + operandStart));
                uint32_t targetOff = static_cast<uint32_t>(pc + opLen + 4 + target);
                snprintf(line, sizeof(line), " IL_%04X", targetOff);
                out += line;
                opLen += 4;
                break;
            }
            case OT_SWITCH: {
                if (operandStart + 4 > ilLen) { out += " <truncated>\n"; break; }
                uint32_t numTargets = read_le32(ilData + operandStart);
                opLen += 4;
                snprintf(line, sizeof(line), " (%u targets)", numTargets);
                out += line;
                // Skip targets
                if (operandStart + 4 + numTargets * 4 > ilLen) { out += " <truncated>\n"; break; }
                for (uint32_t i = 0; i < numTargets; ++i) {
                    int32_t target = static_cast<int32_t>(read_le32(ilData + operandStart + 4 + i * 4));
                    uint32_t targetOff = static_cast<uint32_t>(pc + opLen + numTargets * 4 + target);
                    snprintf(line, sizeof(line), "\n             -> IL_%04X", targetOff);
                    out += line;
                }
                opLen += numTargets * 4;
                break;
            }
            default:
                break;
        }

        out += "\n";
        pc += opLen;
        if (opLen == 0) break; // safety
    }

    return out;
}

// ── Generate pseudo-C# from IL ──
// This is a simplified decompiler that handles common patterns.
inline std::string il_to_pseudocode(const uint8_t* ilData, size_t ilLen,
                                     const uint8_t* fileData, size_t fileLen,
                                     const DotNetMetadata& dn, const PeFile& pe,
                                     const std::string& methodName) {
    std::string out = "// Pseudo-code (approximate decompilation)\n";
    out += "// Method: " + methodName + "\n";
    out += "// IL size: " + std::to_string(ilLen) + " bytes\n\n";

    // Simple analysis: identify method calls, string loads, returns, branches
    size_t pc = 0;
    int indent = 1;
    char line[512];

    while (pc < ilLen) {
        uint8_t op = ilData[pc];
        const IlOpcode* opcode;
        size_t opLen;

        if (op == 0xFE) {
            if (pc + 1 >= ilLen) break;
            uint8_t op2 = ilData[pc + 1];
            opcode = &OPCODES_2[op2];
            opLen = 2;
        } else {
            opcode = &OPCODES_1[op];
            opLen = 1;
        }

        size_t operandStart = pc + opLen;

        switch (op) {
            case 0x72: { // ldstr
                if (operandStart + 4 <= ilLen) {
                    uint32_t tok = read_le32(ilData + operandStart);
                    uint32_t usIdx = tok & 0x00FFFFFF;
                    std::string str = read_us_heap(fileData, fileLen, dn, usIdx);
                    snprintf(line, sizeof(line), "%s\"%s\"\n", std::string(indent * 2, ' ').c_str(), str.c_str());
                    out += line;
                }
                opLen += 4;
                break;
            }
            case 0x28: // call
            case 0x6F: // callvirt
            case 0x73: { // newobj
                if (operandStart + 4 <= ilLen) {
                    uint32_t tok = read_le32(ilData + operandStart);
                    std::string resolved = resolve_token(fileData, fileLen, dn, pe, tok);
                    const char* opName = (op == 0x28) ? "call" : (op == 0x6F) ? "callvirt" : "newobj";
                    snprintf(line, sizeof(line), "%s%s /* %s */\n",
                             std::string(indent * 2, ' ').c_str(), opName, resolved.c_str());
                    out += line;
                }
                opLen += 4;
                break;
            }
            case 0x2A: // ret
                snprintf(line, sizeof(line), "%sreturn\n", std::string(indent * 2, ' ').c_str());
                out += line;
                break;
            case 0x2C: // brfalse.s
            case 0x39: { // brfalse
                out += std::string(indent * 2, ' ') + "if (false) goto ...\n";
                if (opcode->operandType == OT_BRANCH8) opLen += 1;
                else opLen += 4;
                break;
            }
            case 0x2D: // brtrue.s
            case 0x3A: { // brtrue
                out += std::string(indent * 2, ' ') + "if (true) goto ...\n";
                if (opcode->operandType == OT_BRANCH8) opLen += 1;
                else opLen += 4;
                break;
            }
            case 0x20: { // ldc.i4
                if (operandStart + 4 <= ilLen) {
                    int32_t v = static_cast<int32_t>(read_le32(ilData + operandStart));
                    snprintf(line, sizeof(line), "%spush %d\n", std::string(indent * 2, ' ').c_str(), v);
                    out += line;
                }
                opLen += 4;
                break;
            }
            case 0x1F: { // ldc.i4.s
                int8_t v = static_cast<int8_t>(ilData[operandStart]);
                snprintf(line, sizeof(line), "%spush %d\n", std::string(indent * 2, ' ').c_str(), v);
                out += line;
                opLen += 1;
                break;
            }
            case 0x7B: // ldfld
            case 0x7E: { // ldsfld
                if (operandStart + 4 <= ilLen) {
                    uint32_t tok = read_le32(ilData + operandStart);
                    std::string resolved = resolve_token(fileData, fileLen, dn, pe, tok);
                    snprintf(line, sizeof(line), "%s%s /* %s */\n",
                             std::string(indent * 2, ' ').c_str(),
                             (op == 0x7B) ? "ldfld" : "ldsfld", resolved.c_str());
                    out += line;
                }
                opLen += 4;
                break;
            }
            case 0x7D: // stfld
            case 0x80: { // stsfld
                if (operandStart + 4 <= ilLen) {
                    uint32_t tok = read_le32(ilData + operandStart);
                    std::string resolved = resolve_token(fileData, fileLen, dn, pe, tok);
                    snprintf(line, sizeof(line), "%s%s /* %s */\n",
                             std::string(indent * 2, ' ').c_str(),
                             (op == 0x7D) ? "stfld" : "stsfld", resolved.c_str());
                    out += line;
                }
                opLen += 4;
                break;
            }
            default:
                // For other opcodes, just note them
                break;
        }

        // Handle operand length for other types
        if (op != 0x72 && op != 0x28 && op != 0x6F && op != 0x73 && op != 0x2A &&
            op != 0x2C && op != 0x39 && op != 0x2D && op != 0x3A && op != 0x20 &&
            op != 0x1F && op != 0x7B && op != 0x7E && op != 0x7D && op != 0x80) {
            switch (opcode->operandType) {
                case OT_NONE: break;
                case OT_INT8: case OT_UINT8: opLen += 1; break;
                case OT_INT16: case OT_UINT16: opLen += 2; break;
                case OT_INT32: case OT_UINT32: case OT_TOKEN: case OT_BRANCH32:
                    opLen += 4; break;
                case OT_INT64: opLen += 8; break;
                case OT_FLOAT32: opLen += 4; break;
                case OT_FLOAT64: opLen += 8; break;
                case OT_BRANCH8: opLen += 1; break;
                case OT_SWITCH: {
                    if (operandStart + 4 <= ilLen) {
                        uint32_t n = read_le32(ilData + operandStart);
                        opLen += 4 + n * 4;
                    }
                    break;
                }
            }
        }

        if (operandStart < ilLen && opLen > 0) {
            pc += opLen;
        } else {
            pc += (pc + 1 <= ilLen) ? 1 : 0;
        }
        if (pc <= 0) break;
    }

    return out;
}

// ── JSON: disassemble method IL ──
inline std::string dotnet_disasm_json(const uint8_t* data, size_t len,
                                       const DotNetMetadata& dn, const PeFile& pe,
                                       uint32_t methodToken, bool pseudoCode) {
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
    j.num("token", methodToken);

    if (rva == 0) { j.str("error", "method has no body"); return j.build(); }

    DnMethodBody mb = parse_method_body(data, len, pe, dn, rva);
    if (!mb.valid) { j.str("error", "failed to parse method body"); return j.build(); }

    j.num("ilSize", mb.codeSize);
    j.num("ilOffset", mb.ilOffset);

    if (mb.ilOffset + mb.codeSize > len) { j.str("error", "IL body out of range"); return j.build(); }

    std::string disasm = disassemble_il(data + mb.ilOffset, mb.codeSize,
                                         data, len, dn, pe, mb.ilOffset);
    j.cstr("disassembly", disasm.c_str());

    if (pseudoCode) {
        std::string pseudo = il_to_pseudocode(data + mb.ilOffset, mb.codeSize,
                                               data, len, dn, pe, name);
        j.cstr("pseudoCode", pseudo.c_str());
    }

    return j.build();
}

}  // namespace dsmcp
