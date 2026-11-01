//
// pe_parser.h - Self-contained PE/COFF parser and patcher for .dll files.
//
// Supports PE32 and PE32+ (64-bit) formats without any external library.
// Provides:
//   - Full structural parsing (DOS/COFF/Optional headers, sections)
//   - Import and export directory parsing
//   - RVA-to-offset resolution for patching
//   - Section content read/write
//
#pragma once

#include "hex_util.h"
#include "json_builder.h"
#include <string>
#include <vector>
#include <cstdint>
#include <cstring>

namespace dsmcp {

// ── PE constants ──
static const uint16_t PE_DOS_MAGIC = 0x5A4D;  // "MZ"
static const uint32_t PE_SIGNATURE = 0x00004550;  // "PE\0\0"

// Section characteristics flags
enum PeSectionFlags : uint32_t {
    PE_SCN_CNT_CODE = 0x00000020,
    PE_SCN_CNT_INITIALIZED_DATA = 0x00000040,
    PE_SCN_MEM_EXECUTE = 0x20000000,
    PE_SCN_MEM_READ = 0x40000000,
    PE_SCN_MEM_WRITE = 0x80000000,
};

// ── Parsed structures ──

struct PeSection {
    std::string name;
    uint32_t virtualSize;
    uint32_t virtualAddress;  // RVA
    uint32_t sizeOfRawData;
    uint32_t pointerToRawData;
    uint32_t characteristics;
};

struct PeExportFunc {
    std::string name;
    uint32_t rva;
    uint16_t ordinal;
};

struct PeImportEntry {
    std::string dllName;
    std::vector<std::string> functions;
};

struct PeFile {
    bool valid = false;
    bool is64 = false;
    uint16_t machine = 0;       // 0x14c=i386, 0x8664=AMD64
    uint16_t numberOfSections = 0;
    uint32_t characteristics = 0;
    uint32_t entryPoint = 0;    // RVA
    uint64_t imageBase = 0;
    uint32_t sectionAlignment = 0;
    uint32_t fileAlignment = 0;
    uint32_t sizeOfImage = 0;
    uint32_t sizeOfHeaders = 0;
    std::vector<PeSection> sections;
    std::vector<PeExportFunc> exports;
    std::vector<PeImportEntry> imports;
    std::string error;
};

// ── PE parsing ──

inline PeFile parse_pe(const uint8_t* data, size_t len) {
    PeFile pe;
    if (len < 64) {
        pe.error = "file too small for PE";
        return pe;
    }
    // DOS header
    if (read_le16(data) != PE_DOS_MAGIC) {
        pe.error = "not a PE file (no MZ signature)";
        return pe;
    }
    uint32_t pe_offset = read_le32(data + 0x3C);
    if (pe_offset + 4 > len) {
        pe.error = "invalid PE header offset";
        return pe;
    }
    // PE signature
    if (read_le32(data + pe_offset) != PE_SIGNATURE) {
        pe.error = "invalid PE signature";
        return pe;
    }
    // COFF header (20 bytes after signature)
    const uint8_t* coff = data + pe_offset + 4;
    if (pe_offset + 24 > len) {
        pe.error = "truncated COFF header";
        return pe;
    }
    pe.machine = read_le16(coff);
    pe.numberOfSections = read_le16(coff + 2);
    pe.characteristics = read_le32(coff + 18);

    // Optional header
    const uint8_t* opt = coff + 20;
    if (pe_offset + 24 + 2 > len) {
        pe.error = "truncated optional header";
        return pe;
    }
    uint16_t opt_magic = read_le16(opt);
    pe.is64 = (opt_magic == 0x20B);  // PE32+
    bool is64 = pe.is64;

    if (is64) {
        if (pe_offset + 24 + 112 > len) {
            pe.error = "truncated PE32+ optional header";
            return pe;
        }
        pe.entryPoint = read_le32(opt + 16);
        pe.imageBase = read_le64(opt + 24);
        pe.sectionAlignment = read_le32(opt + 32);
        pe.fileAlignment = read_le32(opt + 36);
        pe.sizeOfImage = read_le32(opt + 56);
        pe.sizeOfHeaders = read_le32(opt + 60);
    } else {
        if (pe_offset + 24 + 96 > len) {
            pe.error = "truncated PE32 optional header";
            return pe;
        }
        pe.entryPoint = read_le32(opt + 16);
        pe.imageBase = read_le32(opt + 28);
        pe.sectionAlignment = read_le32(opt + 32);
        pe.fileAlignment = read_le32(opt + 36);
        pe.sizeOfImage = read_le32(opt + 56);
        pe.sizeOfHeaders = read_le32(opt + 60);
    }

    // Section headers
    uint32_t opt_size = read_le16(coff + 16);
    const uint8_t* sec_hdr = opt + opt_size;
    size_t sec_hdr_size = 40;
    if (pe_offset + 24 + opt_size + (size_t)pe.numberOfSections * sec_hdr_size > len) {
        pe.error = "truncated section headers";
        return pe;
    }
    pe.sections.reserve(pe.numberOfSections);
    for (uint16_t i = 0; i < pe.numberOfSections; ++i) {
        const uint8_t* sh = sec_hdr + (size_t)i * sec_hdr_size;
        PeSection sec;
        // Section name is 8 bytes, null-padded
        char name_buf[9] = {};
        std::memcpy(name_buf, sh, 8);
        sec.name = std::string(name_buf);
        sec.virtualSize = read_le32(sh + 8);
        sec.virtualAddress = read_le32(sh + 12);
        sec.sizeOfRawData = read_le32(sh + 16);
        sec.pointerToRawData = read_le32(sh + 20);
        sec.characteristics = read_le32(sh + 36);
        pe.sections.push_back(std::move(sec));
    }

    // Parse data directories (imports & exports)
    // Export directory is data directory index 0
    // Import directory is data directory index 1
    uint32_t num_data_dirs;
    const uint8_t* data_dirs;
    if (is64) {
        num_data_dirs = read_le32(opt + 108);
        data_dirs = opt + 112;
    } else {
        num_data_dirs = read_le32(opt + 92);
        data_dirs = opt + 96;
    }
    // Each data directory entry is 8 bytes (RVA + Size)
    auto get_dir = [&](uint32_t index) -> std::pair<uint32_t, uint32_t> {
        if (index >= num_data_dirs) return {0, 0};
        const uint8_t* dir = data_dirs + (size_t)index * 8;
        return {read_le32(dir), read_le32(dir + 4)};  // RVA, Size
    };

    // Parse export directory
    auto [exp_rva, exp_size] = get_dir(0);

    pe.valid = true;

    // Now parse exports (sections are available)
    if (exp_rva > 0 && exp_size > 0) {
        uint64_t exp_off = UINT64_MAX;
        for (const auto& sec : pe.sections) {
            if (exp_rva >= sec.virtualAddress &&
                exp_rva < sec.virtualAddress + std::max(sec.virtualSize, sec.sizeOfRawData)) {
                exp_off = sec.pointerToRawData + (exp_rva - sec.virtualAddress);
                break;
            }
        }
        if (exp_off != UINT64_MAX && exp_off + 40 <= len) {
            const uint8_t* edir = data + exp_off;
            uint32_t name_rva = read_le32(edir + 12);
            uint32_t num_funcs = read_le32(edir + 20);
            uint32_t num_names = read_le32(edir + 24);
            uint32_t func_table_rva = read_le32(edir + 28);
            uint32_t name_table_rva = read_le32(edir + 32);
            uint32_t ordinal_table_rva = read_le32(edir + 36);

            // Resolve RVAs to file offsets
            auto rva_to_off = [&](uint32_t rva) -> uint64_t {
                for (const auto& sec : pe.sections) {
                    if (rva >= sec.virtualAddress &&
                        rva < sec.virtualAddress + std::max(sec.virtualSize, sec.sizeOfRawData)) {
                        return sec.pointerToRawData + (rva - sec.virtualAddress);
                    }
                }
                return UINT64_MAX;
            };

            uint64_t func_off = rva_to_off(func_table_rva);
            uint64_t name_off = rva_to_off(name_table_rva);
            uint64_t ord_off = rva_to_off(ordinal_table_rva);

            if (func_off != UINT64_MAX && name_off != UINT64_MAX && ord_off != UINT64_MAX) {
                uint32_t count = std::min(num_names, num_funcs);
                for (uint32_t i = 0; i < count; ++i) {
                    if (name_off + (size_t)(i + 1) * 4 > len) break;
                    uint32_t fname_rva = read_le32(data + name_off + (size_t)i * 4);
                    uint64_t fname_off = rva_to_off(fname_rva);
                    if (fname_off == UINT64_MAX || fname_off >= len) continue;
                    // Read null-terminated name
                    std::string fname;
                    for (size_t j = fname_off; j < len && data[j] != 0; ++j) {
                        fname += static_cast<char>(data[j]);
                    }
                    if (ord_off + (size_t)(i + 1) * 2 > len) break;
                    uint16_t ordinal = read_le16(data + ord_off + (size_t)i * 2);
                    uint32_t func_rva = 0;
                    if (func_off + (size_t)(ordinal + 1) * 4 <= len) {
                        func_rva = read_le32(data + func_off + (size_t)ordinal * 4);
                    }
                    pe.exports.push_back({fname, func_rva, ordinal});
                }
            }
        }
    }

    // Parse import directory
    auto [imp_rva, imp_size] = get_dir(1);
    if (imp_rva > 0) {
        uint64_t imp_off = UINT64_MAX;
        for (const auto& sec : pe.sections) {
            if (imp_rva >= sec.virtualAddress &&
                imp_rva < sec.virtualAddress + std::max(sec.virtualSize, sec.sizeOfRawData)) {
                imp_off = sec.pointerToRawData + (imp_rva - sec.virtualAddress);
                break;
            }
        }
        if (imp_off != UINT64_MAX) {
            auto rva_to_off = [&](uint32_t rva) -> uint64_t {
                for (const auto& sec : pe.sections) {
                    if (rva >= sec.virtualAddress &&
                        rva < sec.virtualAddress + std::max(sec.virtualSize, sec.sizeOfRawData)) {
                        return sec.pointerToRawData + (rva - sec.virtualAddress);
                    }
                }
                return UINT64_MAX;
            };

            // Each import descriptor is 20 bytes, terminated by all-zero entry
            for (size_t i = 0; imp_off + (i + 1) * 20 <= len; ++i) {
                const uint8_t* desc = data + imp_off + i * 20;
                uint32_t name_rva = read_le32(desc + 12);
                if (name_rva == 0) break;  // end of import table
                uint64_t name_off = rva_to_off(name_rva);
                if (name_off == UINT64_MAX || name_off >= len) continue;
                std::string dll_name;
                for (size_t j = name_off; j < len && data[j] != 0; ++j) {
                    dll_name += static_cast<char>(data[j]);
                }
                PeImportEntry entry;
                entry.dllName = dll_name;

                // Parse the import lookup table (ILT)
                uint32_t ilt_rva = read_le32(desc);  // OriginalFirstThunk
                uint32_t ilt_rva_use = (ilt_rva != 0) ? ilt_rva : read_le32(desc + 16);
                if (ilt_rva_use > 0) {
                    uint64_t ilt_off = rva_to_off(ilt_rva_use);
                    if (ilt_off != UINT64_MAX) {
                        size_t thunk_size = is64 ? 8 : 4;
                        for (size_t j = 0; ilt_off + (j + 1) * thunk_size <= len; ++j) {
                            uint64_t thunk;
                            if (is64) {
                                thunk = read_le64(data + ilt_off + j * 8);
                                if (thunk == 0) break;
                            } else {
                                thunk = read_le32(data + ilt_off + j * 4);
                                if (thunk == 0) break;
                            }
                            // Check if import by ordinal (high bit set)
                            uint64_t high_bit = is64 ? 0x8000000000000000ULL : 0x80000000;
                            if (thunk & high_bit) {
                                // Import by ordinal
                                uint16_t ord = static_cast<uint16_t>(thunk & 0xFFFF);
                                entry.functions.push_back("ordinal:" + std::to_string(ord));
                            } else {
                                // Import by name
                                uint32_t hint_rva = static_cast<uint32_t>(thunk);
                                uint64_t hint_off = rva_to_off(hint_rva);
                                if (hint_off != UINT64_MAX && hint_off + 2 < len) {
                                    std::string fname;
                                    for (size_t k = hint_off + 2; k < len && data[k] != 0; ++k) {
                                        fname += static_cast<char>(data[k]);
                                    }
                                    entry.functions.push_back(fname);
                                }
                            }
                        }
                    }
                }
                pe.imports.push_back(std::move(entry));
            }
        }
    }

    return pe;
}

// ── RVA to file offset ──
inline uint64_t pe_rva_to_offset(const PeFile& pe, uint32_t rva) {
    for (const auto& sec : pe.sections) {
        uint32_t sec_size = std::max(sec.virtualSize, sec.sizeOfRawData);
        if (rva >= sec.virtualAddress && rva < sec.virtualAddress + sec_size) {
            return sec.pointerToRawData + (rva - sec.virtualAddress);
        }
    }
    // Headers themselves
    if (rva < pe.sizeOfHeaders) return rva;
    return UINT64_MAX;
}

// ── VA to offset (VA = imageBase + RVA) ──
inline uint64_t pe_va_to_offset(const PeFile& pe, uint64_t va) {
    if (va < pe.imageBase) return UINT64_MAX;
    return pe_rva_to_offset(pe, static_cast<uint32_t>(va - pe.imageBase));
}

// ── JSON serialization ──
inline std::string pe_to_json(const PeFile& pe) {
    JsonBuilder j;
    if (!pe.valid) {
        j.str("error", pe.error.empty() ? "parse_failed" : pe.error);
        return j.build();
    }
    j.num("bits", pe.is64 ? 64 : 32);
    j.cstr("machine", pe.machine == 0x14c ? "i386" :
                     pe.machine == 0x8664 ? "AMD64" :
                     pe.machine == 0x1c0 ? "ARM" :
                     pe.machine == 0xAA64 ? "ARM64" : "unknown");
    j.num("machineCode", pe.machine);
    j.num("entryPoint", pe.entryPoint);
    j.num("imageBase", pe.imageBase);
    j.num("sectionAlignment", pe.sectionAlignment);
    j.num("fileAlignment", pe.fileAlignment);
    j.num("sizeOfImage", pe.sizeOfImage);
    j.num("sizeOfHeaders", pe.sizeOfHeaders);
    j.num("sectionCount", pe.sections.size());
    j.num("exportCount", pe.exports.size());
    j.num("importCount", pe.imports.size());

    // Sections
    j.begin_array("sections");
    for (const auto& sec : pe.sections) {
        j.begin_object();
        j.cstr("name", sec.name.c_str());
        j.num("virtualSize", sec.virtualSize);
        j.num("virtualAddress", sec.virtualAddress);
        j.num("sizeOfRawData", sec.sizeOfRawData);
        j.num("pointerToRawData", sec.pointerToRawData);
        j.num("characteristics", sec.characteristics);
        j.boolean("executable", (sec.characteristics & PE_SCN_MEM_EXECUTE) != 0);
        j.boolean("readable", (sec.characteristics & PE_SCN_MEM_READ) != 0);
        j.boolean("writable", (sec.characteristics & PE_SCN_MEM_WRITE) != 0);
        j.end_object();
    }
    j.end_array();

    // Exports
    j.begin_array("exports");
    for (const auto& exp : pe.exports) {
        j.begin_object();
        j.cstr("name", exp.name.c_str());
        j.num("rva", exp.rva);
        j.num("ordinal", exp.ordinal);
        j.end_object();
    }
    j.end_array();

    // Imports
    j.begin_array("imports");
    for (const auto& imp : pe.imports) {
        j.begin_object();
        j.cstr("dll", imp.dllName.c_str());
        j.begin_array("functions");
        for (const auto& fn : imp.functions) {
            j.buffer() += (j.buffer().back() == '[' ? "" : ",");
            j.buffer() += "\"";
            // Inline escape
            for (char c : fn) {
                if (c == '"') j.buffer() += "\\\"";
                else if (c == '\\') j.buffer() += "\\\\";
                else j.buffer() += c;
            }
            j.buffer() += "\"";
        }
        j.end_array();
        j.end_object();
    }
    j.end_array();

    return j.build();
}

// ── Patching operations ──

// Patch bytes at a virtual address (VA = imageBase + RVA).
inline std::vector<uint8_t> pe_patch_va(
        const uint8_t* data, size_t len, uint64_t va, const uint8_t* patch, size_t plen) {
    PeFile pe = parse_pe(data, len);
    if (!pe.valid) return {};
    uint64_t offset = pe_va_to_offset(pe, va);
    if (offset == UINT64_MAX || offset + plen > len) return {};
    std::vector<uint8_t> out(data, data + len);
    std::memcpy(out.data() + offset, patch, plen);
    return out;
}

// Patch bytes at an RVA.
inline std::vector<uint8_t> pe_patch_rva(
        const uint8_t* data, size_t len, uint32_t rva, const uint8_t* patch, size_t plen) {
    PeFile pe = parse_pe(data, len);
    if (!pe.valid) return {};
    uint64_t offset = pe_rva_to_offset(pe, rva);
    if (offset == UINT64_MAX || offset + plen > len) return {};
    std::vector<uint8_t> out(data, data + len);
    std::memcpy(out.data() + offset, patch, plen);
    return out;
}

// Patch bytes at a file offset.
inline std::vector<uint8_t> pe_patch_offset(
        const uint8_t* data, size_t len, uint64_t offset, const uint8_t* patch, size_t plen) {
    if (offset + plen > len) return {};
    std::vector<uint8_t> out(data, data + len);
    std::memcpy(out.data() + offset, patch, plen);
    return out;
}

// Find section by name (PE section names are up to 8 chars).
inline const PeSection* pe_find_section(const PeFile& pe, const std::string& name) {
    for (const auto& sec : pe.sections) {
        if (sec.name == name) return &sec;
    }
    return nullptr;
}

// Read section content.
inline std::vector<uint8_t> pe_get_section_content(
        const uint8_t* data, size_t len, const std::string& sectionName) {
    PeFile pe = parse_pe(data, len);
    if (!pe.valid) return {};
    const PeSection* sec = pe_find_section(pe, sectionName);
    if (!sec || sec->pointerToRawData == 0) return {};
    uint32_t read_size = std::min(sec->sizeOfRawData, sec->virtualSize);
    if (read_size == 0) read_size = sec->sizeOfRawData;
    if (sec->pointerToRawData + read_size > len) return {};
    return std::vector<uint8_t>(data + sec->pointerToRawData,
                                data + sec->pointerToRawData + read_size);
}

// Replace section content.
inline std::vector<uint8_t> pe_set_section_content(
        const uint8_t* data, size_t len, const std::string& sectionName,
        const uint8_t* content, size_t clen) {
    PeFile pe = parse_pe(data, len);
    if (!pe.valid) return {};
    const PeSection* sec = pe_find_section(pe, sectionName);
    if (!sec || sec->pointerToRawData == 0) return {};
    if (sec->pointerToRawData + sec->sizeOfRawData > len) return {};
    std::vector<uint8_t> out(data, data + len);
    size_t write_len = std::min(static_cast<size_t>(sec->sizeOfRawData), clen);
    std::memcpy(out.data() + sec->pointerToRawData, content, write_len);
    if (write_len < static_cast<size_t>(sec->sizeOfRawData)) {
        std::memset(out.data() + sec->pointerToRawData + write_len, 0,
                    static_cast<size_t>(sec->sizeOfRawData) - write_len);
    }
    return out;
}

}  // namespace dsmcp
