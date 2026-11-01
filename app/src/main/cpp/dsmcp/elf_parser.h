//
// elf_parser.h - Self-contained ELF parser and patcher for .so files.
//
// Supports 32/64-bit, little/big endian ELF files without any external
// library dependency. Provides:
//   - Full structural parsing (header, sections, symbols, segments, dynamic)
//   - VA-to-offset resolution for patching
//   - Section content read/write
//   - Symbol table inspection (symtab + dynsym)
//   - Basic string extraction from string-table sections
//
// All functions operate on an in-memory byte buffer so the Kotlin engine
// can manage workspaces and edit sessions entirely in managed memory.
//
#pragma once

#include "hex_util.h"
#include "json_builder.h"
#include <string>
#include <vector>
#include <cstdint>
#include <cstring>

namespace dsmcp {

// ── ELF constants ──
static const uint32_t ELF_MAGIC = 0x464C457F;  // "\x7fELF"

enum ElfClass : uint8_t { ELFCLASS32 = 1, ELFCLASS64 = 2 };
enum ElfData : uint8_t { ELFDATA2LSB = 1, ELFDATA2MSB = 2 };

// Section types
enum ShtType : uint32_t {
    SHT_NULL = 0, SHT_PROGBITS = 1, SHT_SYMTAB = 2, SHT_STRTAB = 3,
    SHT_RELA = 4, SHT_HASH = 5, SHT_DYNAMIC = 6, SHT_NOTE = 7,
    SHT_NOBITS = 8, SHT_REL = 9, SHT_DYNSYM = 11,
};

// Symbol bindings
enum SymBind : uint8_t { STB_LOCAL = 0, STB_GLOBAL = 1, STB_WEAK = 2 };
// Symbol types
enum SymType : uint8_t { STT_NOTYPE = 0, STT_OBJECT = 1, STT_FUNC = 2, STT_TLS = 6 };

// ── Parsed structures ──

struct ElfSection {
    std::string name;
    uint32_t type;
    uint64_t flags;
    uint64_t addr;       // virtual address
    uint64_t offset;     // file offset
    uint64_t size;
    uint32_t link;
    uint32_t info;
    uint64_t addralign;
    uint64_t entsize;
};

struct ElfSymbol {
    std::string name;
    uint8_t bind;
    uint8_t type;
    uint8_t visibility;
    uint16_t shndx;
    uint64_t value;   // address / offset
    uint64_t size;
    bool imported;
    bool exported;
};

struct ElfSegment {
    uint32_t type;
    uint32_t flags;
    uint64_t offset;
    uint64_t vaddr;
    uint64_t paddr;
    uint64_t filesz;
    uint64_t memsz;
    uint64_t align;
};

struct ElfDynamic {
    int64_t tag;
    uint64_t value;
};

struct ElfFile {
    bool valid = false;
    bool is64 = false;
    bool littleEndian = true;
    uint16_t type = 0;      // ET_DYN=3, ET_EXEC=2
    uint16_t machine = 0;   // EM_ARM=40, EM_AARCH64=183, EM_386=3, EM_X86_64=62
    uint64_t entry = 0;
    std::vector<ElfSection> sections;
    std::vector<ElfSymbol> symbols;      // .symtab
    std::vector<ElfSymbol> dynSymbols;   // .dynsym
    std::vector<ElfSegment> segments;
    std::vector<ElfDynamic> dynamics;
    std::string error;
};

// ── Inline byte readers that respect endianness ──

inline uint16_t rd16(const uint8_t* p, bool le) {
    return le ? read_le16(p) : read_be16(p);
}
inline uint32_t rd32(const uint8_t* p, bool le) {
    return le ? read_le32(p) : read_be32(p);
}
inline uint64_t rd64(const uint8_t* p, bool le) {
    return le ? read_le64(p) : read_be64(p);
}

// ── ELF parsing ──

inline ElfFile parse_elf(const uint8_t* data, size_t len) {
    ElfFile elf;
    if (len < 64) {
        elf.error = "file too small for ELF header";
        return elf;
    }
    // Magic check
    uint32_t magic = read_le32(data);
    if (magic != ELF_MAGIC) {
        elf.error = "not an ELF file (bad magic)";
        return elf;
    }
    elf.is64 = (data[4] == ELFCLASS64);
    elf.littleEndian = (data[5] == ELFDATA2LSB);
    if (data[4] != ELFCLASS32 && data[4] != ELFCLASS64) {
        elf.error = "invalid ELF class";
        return elf;
    }
    bool le = elf.littleEndian;
    bool is64 = elf.is64;

    // ELF header fields
    elf.type = rd16(data + 16, le);
    elf.machine = rd16(data + 18, le);

    uint64_t shoff;
    uint16_t shentsize, shnum, shstrndx;
    uint64_t phoff;
    uint16_t phentsize, phnum;

    if (is64) {
        elf.entry = rd64(data + 24, le);
        phoff = rd64(data + 32, le);
        shoff = rd64(data + 40, le);
        phentsize = rd16(data + 54, le);
        phnum = rd16(data + 56, le);
        shentsize = rd16(data + 58, le);
        shnum = rd16(data + 60, le);
        shstrndx = rd16(data + 62, le);
    } else {
        elf.entry = rd32(data + 24, le);
        phoff = rd32(data + 28, le);
        shoff = rd32(data + 32, le);
        phentsize = rd16(data + 42, le);
        phnum = rd16(data + 44, le);
        shentsize = rd16(data + 46, le);
        shnum = rd16(data + 48, le);
        shstrndx = rd16(data + 50, le);
    }

    // Parse section headers
    if (shoff > 0 && shentsize > 0 && shnum > 0 && shoff + (uint64_t)shnum * shentsize <= len) {
        // First, get the section name string table
        const uint8_t* shstr_base = nullptr;
        size_t shstr_size = 0;
        if (shstrndx < shnum) {
            const uint8_t* shstr_hdr = data + shoff + (uint64_t)shstrndx * shentsize;
            uint64_t str_off, str_size;
            if (is64) {
                str_off = rd64(shstr_hdr + 24, le);
                str_size = rd64(shstr_hdr + 32, le);
            } else {
                str_off = rd32(shstr_hdr + 16, le);
                str_size = rd32(shstr_hdr + 20, le);
            }
            if (str_off + str_size <= len) {
                shstr_base = data + str_off;
                shstr_size = str_size;
            }
        }

        elf.sections.reserve(shnum);
        for (uint16_t i = 0; i < shnum; ++i) {
            const uint8_t* sh = data + shoff + (uint64_t)i * shentsize;
            ElfSection sec;
            uint32_t name_idx = rd32(sh, le);
            sec.type = rd32(sh + 4, le);
            if (is64) {
                sec.flags = rd64(sh + 8, le);
                sec.addr = rd64(sh + 16, le);
                sec.offset = rd64(sh + 24, le);
                sec.size = rd64(sh + 32, le);
                sec.link = rd32(sh + 40, le);
                sec.info = rd32(sh + 44, le);
                sec.addralign = rd64(sh + 48, le);
                sec.entsize = rd64(sh + 56, le);
            } else {
                sec.flags = rd32(sh + 8, le);
                sec.addr = rd32(sh + 12, le);
                sec.offset = rd32(sh + 16, le);
                sec.size = rd32(sh + 20, le);
                sec.link = rd32(sh + 24, le);
                sec.info = rd32(sh + 28, le);
                sec.addralign = rd32(sh + 32, le);
                sec.entsize = rd32(sh + 36, le);
            }
            // Resolve name
            if (shstr_base && name_idx < shstr_size) {
                sec.name = std::string(reinterpret_cast<const char*>(shstr_base + name_idx));
            }
            elf.sections.push_back(std::move(sec));
        }
    }

    // Parse program headers (segments)
    if (phoff > 0 && phentsize > 0 && phnum > 0 && phoff + (uint64_t)phnum * phentsize <= len) {
        elf.segments.reserve(phnum);
        for (uint16_t i = 0; i < phnum; ++i) {
            const uint8_t* ph = data + phoff + (uint64_t)i * phentsize;
            ElfSegment seg;
            if (is64) {
                seg.type = rd32(ph, le);
                seg.flags = rd32(ph + 4, le);
                seg.offset = rd64(ph + 8, le);
                seg.vaddr = rd64(ph + 16, le);
                seg.paddr = rd64(ph + 24, le);
                seg.filesz = rd64(ph + 32, le);
                seg.memsz = rd64(ph + 40, le);
                seg.align = rd64(ph + 48, le);
            } else {
                seg.type = rd32(ph, le);
                seg.offset = rd32(ph + 4, le);
                seg.vaddr = rd32(ph + 8, le);
                seg.paddr = rd32(ph + 12, le);
                seg.filesz = rd32(ph + 16, le);
                seg.memsz = rd32(ph + 20, le);
                seg.flags = rd32(ph + 24, le);
                seg.align = rd32(ph + 28, le);
            }
            elf.segments.push_back(std::move(seg));
        }
    }

    // Parse dynamic entries
    for (const auto& sec : elf.sections) {
        if (sec.type != SHT_DYNAMIC || sec.offset == 0) continue;
        size_t entsize = is64 ? 16 : 8;
        size_t count = static_cast<size_t>(sec.size) / entsize;
        if (sec.offset + sec.size > len) break;
        const uint8_t* base = data + sec.offset;
        for (size_t i = 0; i < count; ++i) {
            ElfDynamic dyn;
            if (is64) {
                dyn.tag = static_cast<int64_t>(rd64(base + i * 16, le));
                dyn.value = rd64(base + i * 16 + 8, le);
            } else {
                dyn.tag = static_cast<int32_t>(rd32(base + i * 8, le));
                dyn.value = rd32(base + i * 8 + 4, le);
            }
            if (dyn.tag == 0) break;  // DT_NULL
            elf.dynamics.push_back(dyn);
        }
    }

    // Parse symbol tables (.symtab and .dynsym)
    auto parse_symbols = [&](uint32_t target_type, std::vector<ElfSymbol>& out) {
        for (const auto& sec : elf.sections) {
            if (sec.type != target_type) continue;
            if (sec.entsize == 0) continue;
            // Find the linked string table
            const uint8_t* str_base = nullptr;
            size_t str_size = 0;
            if (sec.link < elf.sections.size()) {
                const auto& strsec = elf.sections[sec.link];
                if (strsec.offset + strsec.size <= len) {
                    str_base = data + strsec.offset;
                    str_size = strsec.size;
                }
            }
            size_t sym_size = is64 ? 24 : 16;
            size_t actual_size = (sec.entsize > 0) ? sec.entsize : sym_size;
            size_t count = static_cast<size_t>(sec.size) / actual_size;
            if (sec.offset + sec.size > len) continue;
            const uint8_t* base = data + sec.offset;
            for (size_t i = 0; i < count; ++i) {
                const uint8_t* sym = base + i * actual_size;
                ElfSymbol s;
                uint32_t name_idx;
                if (is64) {
                    name_idx = rd32(sym, le);
                    s.type = sym[4] & 0x0f;
                    s.bind = (sym[4] >> 4) & 0x0f;
                    s.visibility = sym[5] & 0x03;
                    s.shndx = rd16(sym + 6, le);
                    s.value = rd64(sym + 8, le);
                    s.size = rd64(sym + 16, le);
                } else {
                    name_idx = rd32(sym, le);
                    s.value = rd32(sym + 4, le);
                    s.size = rd32(sym + 8, le);
                    s.type = sym[12] & 0x0f;
                    s.bind = (sym[12] >> 4) & 0x0f;
                    s.visibility = sym[13] & 0x03;  // Note: st_other in ELF32
                    s.shndx = rd16(sym + 14, le);
                }
                s.imported = (s.shndx == 0 && !s.name.empty());
                s.exported = (s.shndx != 0 && s.type == STT_FUNC && !s.name.empty());
                if (str_base && name_idx < str_size) {
                    s.name = std::string(reinterpret_cast<const char*>(str_base + name_idx));
                }
                if (!s.name.empty()) out.push_back(std::move(s));
            }
        }
    };

    parse_symbols(SHT_SYMTAB, elf.symbols);
    parse_symbols(SHT_DYNSYM, elf.dynSymbols);

    elf.valid = true;
    return elf;
}

// ── Helper: find file offset for a virtual address ──
// Returns UINT64_MAX if not found. Uses segments (program headers) for
// the VA-to-offset mapping, which works even on stripped binaries.
inline uint64_t va_to_offset(const ElfFile& elf, uint64_t va) {
    for (const auto& seg : elf.segments) {
        if (seg.type != 1) continue;  // PT_LOAD
        if (va >= seg.vaddr && va < seg.vaddr + seg.filesz) {
            return seg.offset + (va - seg.vaddr);
        }
    }
    // Fallback: use sections
    for (const auto& sec : elf.sections) {
        if (sec.size == 0) continue;
        if (va >= sec.addr && va < sec.addr + sec.size) {
            return sec.offset + (va - sec.addr);
        }
    }
    return UINT64_MAX;
}

// ── Helper: find section by name ──
inline const ElfSection* find_section(const ElfFile& elf, const std::string& name) {
    for (const auto& sec : elf.sections) {
        if (sec.name == name) return &sec;
    }
    return nullptr;
}

// ── JSON serialization of an ElfFile ──
inline std::string elf_to_json(const ElfFile& elf) {
    JsonBuilder j;
    if (!elf.valid) {
        j.str("error", elf.error.empty() ? "parse_failed" : elf.error);
        return j.build();
    }
    j.num("bits", elf.is64 ? 64 : 32);
    j.boolean("littleEndian", elf.littleEndian);
    j.num("type", elf.type);
    j.num("machine", elf.machine);
    j.num("entry", elf.entry);
    j.num("sectionCount", elf.sections.size());
    j.num("symbolCount", elf.symbols.size());
    j.num("dynSymbolCount", elf.dynSymbols.size());
    j.num("segmentCount", elf.segments.size());
    j.num("dynamicCount", elf.dynamics.size());

    // Sections
    j.begin_array("sections");
    for (const auto& sec : elf.sections) {
        j.begin_object();
        j.cstr("name", sec.name.c_str());
        j.num("type", sec.type);
        j.num("flags", sec.flags);
        j.num("addr", sec.addr);
        j.num("offset", sec.offset);
        j.num("size", sec.size);
        j.num("link", sec.link);
        j.num("info", sec.info);
        j.num("addralign", sec.addralign);
        j.num("entsize", sec.entsize);
        j.end_object();
    }
    j.end_array();

    // Dynamic symbols (most useful for SO analysis)
    j.begin_array("dynSymbols");
    for (const auto& sym : elf.dynSymbols) {
        j.begin_object();
        j.cstr("name", sym.name.c_str());
        j.cstr("bind", sym.bind == STB_LOCAL ? "LOCAL" :
                       sym.bind == STB_GLOBAL ? "GLOBAL" :
                       sym.bind == STB_WEAK ? "WEAK" : "OTHER");
        j.cstr("type", sym.type == STT_FUNC ? "FUNC" :
                       sym.type == STT_OBJECT ? "OBJECT" :
                       sym.type == STT_TLS ? "TLS" : "OTHER");
        j.num("value", sym.value);
        j.num("size", sym.size);
        j.num("shndx", sym.shndx);
        j.boolean("imported", sym.imported);
        j.boolean("exported", sym.exported);
        j.end_object();
    }
    j.end_array();

    // Static symbols
    j.begin_array("symbols");
    for (const auto& sym : elf.symbols) {
        j.begin_object();
        j.cstr("name", sym.name.c_str());
        j.cstr("bind", sym.bind == STB_LOCAL ? "LOCAL" :
                       sym.bind == STB_GLOBAL ? "GLOBAL" :
                       sym.bind == STB_WEAK ? "WEAK" : "OTHER");
        j.cstr("type", sym.type == STT_FUNC ? "FUNC" :
                       sym.type == STT_OBJECT ? "OBJECT" : "OTHER");
        j.num("value", sym.value);
        j.num("size", sym.size);
        j.end_object();
    }
    j.end_array();

    // Segments
    j.begin_array("segments");
    for (const auto& seg : elf.segments) {
        j.begin_object();
        j.num("type", seg.type);
        j.num("flags", seg.flags);
        j.num("offset", seg.offset);
        j.num("vaddr", seg.vaddr);
        j.num("filesz", seg.filesz);
        j.num("memsz", seg.memsz);
        j.end_object();
    }
    j.end_array();

    // Dynamic entries
    j.begin_array("dynamicEntries");
    for (const auto& dyn : elf.dynamics) {
        j.begin_object();
        j.snum("tag", dyn.tag);
        j.num("value", dyn.value);
        j.end_object();
    }
    j.end_array();

    return j.build();
}

// ── Patching operations ──
// All patching functions return a new byte vector with the modification applied.
// They do NOT modify the input buffer.

// Patch bytes at a virtual address. Returns empty vector on failure.
inline std::vector<uint8_t> elf_patch_va(
        const uint8_t* data, size_t len, uint64_t va, const uint8_t* patch, size_t plen) {
    ElfFile elf = parse_elf(data, len);
    if (!elf.valid) return {};
    uint64_t offset = va_to_offset(elf, va);
    if (offset == UINT64_MAX || offset + plen > len) return {};
    std::vector<uint8_t> out(data, data + len);
    std::memcpy(out.data() + offset, patch, plen);
    return out;
}

// Patch bytes at a file offset. Returns empty vector on failure.
inline std::vector<uint8_t> elf_patch_offset(
        const uint8_t* data, size_t len, uint64_t offset, const uint8_t* patch, size_t plen) {
    if (offset + plen > len) return {};
    std::vector<uint8_t> out(data, data + len);
    std::memcpy(out.data() + offset, patch, plen);
    return out;
}

// Read section content. Returns empty vector if section not found.
inline std::vector<uint8_t> elf_get_section_content(
        const uint8_t* data, size_t len, const std::string& sectionName) {
    ElfFile elf = parse_elf(data, len);
    if (!elf.valid) return {};
    const ElfSection* sec = find_section(elf, sectionName);
    if (!sec || sec->type == SHT_NOBITS) return {};
    if (sec->offset + sec->size > len) return {};
    return std::vector<uint8_t>(data + sec->offset, data + sec->offset + sec->size);
}

// Replace section content. If the new content is shorter, remaining bytes
// are zero-filled; if longer, it is truncated to the section size.
// Returns empty vector on failure.
inline std::vector<uint8_t> elf_set_section_content(
        const uint8_t* data, size_t len, const std::string& sectionName,
        const uint8_t* content, size_t clen) {
    ElfFile elf = parse_elf(data, len);
    if (!elf.valid) return {};
    const ElfSection* sec = find_section(elf, sectionName);
    if (!sec || sec->type == SHT_NOBITS) return {};
    if (sec->offset + sec->size > len) return {};
    std::vector<uint8_t> out(data, data + len);
    size_t write_len = std::min(static_cast<size_t>(sec->size), clen);
    std::memcpy(out.data() + sec->offset, content, write_len);
    // Zero-fill the remainder if content is shorter
    if (write_len < static_cast<size_t>(sec->size)) {
        std::memset(out.data() + sec->offset + write_len, 0,
                    static_cast<size_t>(sec->size) - write_len);
    }
    return out;
}

// Add an exported function symbol to the .dynsym table.
// This appends a new symbol entry and a new name to .dynstr.
// Returns empty vector on failure.
inline std::vector<uint8_t> elf_add_exported_function(
        const uint8_t* data, size_t len, uint64_t addr, const std::string& name) {
    ElfFile elf = parse_elf(data, len);
    if (!elf.valid) return {};
    // Find .dynsym and .dynstr
    const ElfSection* dynsym = find_section(elf, ".dynsym");
    const ElfSection* dynstr = find_section(elf, ".dynstr");
    if (!dynsym || !dynstr) return {};
    if (dynsym->link >= elf.sections.size()) return {};
    if (dynsym->offset == 0 || dynstr->offset == 0) return {};

    size_t sym_size = elf.is64 ? 24 : 16;
    size_t sym_count = static_cast<size_t>(dynsym->size) / sym_size;

    // Build new .dynstr: append the name at the end
    uint64_t str_offset = dynstr->offset;
    uint64_t str_size = dynstr->size;
    if (str_offset + str_size > len) return {};

    // New name offset = current end of dynstr
    uint32_t name_idx = static_cast<uint32_t>(str_size);

    // Create output buffer with expanded dynstr
    std::vector<uint8_t> out(data, data + len);
    // Insert the name bytes (including null terminator) at the end of dynstr
    size_t name_len = name.size() + 1;
    out.insert(out.begin() + str_offset + str_size, name.begin(), name.end());
    out.insert(out.begin() + str_offset + str_size + name.size(), 0);  // null terminator

    // Now we need to adjust offsets for sections/segments that come after
    // the insertion point. This is complex, so instead we take a simpler
    // approach: rebuild the symbol table entry at the end of .dynsym.
    //
    // Actually, the safest approach for a self-contained patcher is to
    // just write a new symbol entry into the existing .dynsym space if
    // there's room (many SOs have trailing null entries), or append.
    // For simplicity and correctness, we append to dynsym as well.
    //
    // We insert a new symbol entry right after the last dynsym entry.
    uint64_t sym_insert_offset = dynsym->offset + dynsym->size;

    // Build the symbol entry
    std::vector<uint8_t> sym_entry(sym_size, 0);
    if (elf.is64) {
        write_le32(sym_entry.data(), name_idx);
        sym_entry[4] = (STB_GLOBAL << 4) | STT_FUNC;  // bind=GLOBAL, type=FUNC
        sym_entry[5] = 0;  // visibility=DEFAULT
        write_le16(sym_entry.data() + 6, 1);  // shndx=.text (index 1 typically)
        write_le64(sym_entry.data() + 8, addr);
        write_le64(sym_entry.data() + 16, 0);  // size unknown
    } else {
        write_le32(sym_entry.data(), name_idx);
        write_le32(sym_entry.data() + 4, static_cast<uint32_t>(addr));
        write_le32(sym_entry.data() + 8, 0);  // size
        sym_entry[12] = (STB_GLOBAL << 4) | STT_FUNC;
        sym_entry[13] = 0;
        write_le16(sym_entry.data() + 14, 1);
    }

    // Insert the symbol entry (note: offsets may have shifted by name_len)
    // Recalculate since we inserted name bytes
    uint64_t adjusted_sym_offset = dynsym->offset;
    if (dynsym->offset > str_offset + str_size) {
        adjusted_sym_offset = dynsym->offset + name_len;
    }
    uint64_t insert_pos = adjusted_sym_offset + dynsym->size;
    out.insert(out.begin() + insert_pos, sym_entry.begin(), sym_entry.end());

    return out;
}

// Remove a symbol by name from both .symtab and .dynsym.
// This zeroes out the symbol entry (sets it to STN_UNDEF) rather than
// physically removing it, which preserves table size/alignment.
inline std::vector<uint8_t> elf_remove_symbol(
        const uint8_t* data, size_t len, const std::string& name) {
    ElfFile elf = parse_elf(data, len);
    if (!elf.valid) return {};
    std::vector<uint8_t> out(data, data + len);
    size_t sym_size = elf.is64 ? 24 : 16;
    bool found = false;

    auto remove_from = [&](const ElfSection& sec, const std::vector<ElfSymbol>& syms) {
        if (sec.offset == 0 || sec.size == 0) return;
        if (sec.offset + sec.size > len) return;
        // We need to iterate the raw symbol entries to find by name
        // Use the parsed symbols which are in order
        for (size_t i = 0; i < syms.size(); ++i) {
            if (syms[i].name != name) continue;
            uint64_t entry_offset = sec.offset + i * sym_size;
            if (entry_offset + sym_size <= len) {
                std::memset(out.data() + entry_offset, 0, sym_size);
                found = true;
            }
        }
    };

    for (const auto& sec : elf.sections) {
        if (sec.type == SHT_SYMTAB) remove_from(sec, elf.symbols);
        else if (sec.type == SHT_DYNSYM) remove_from(sec, elf.dynSymbols);
    }
    if (!found) return {};
    return out;
}

// Rename a symbol. The new name must fit in the existing .strtab/.dynstr
// space at the same offset (same or shorter length).
inline std::vector<uint8_t> elf_rename_symbol(
        const uint8_t* data, size_t len, const std::string& oldName,
        const std::string& newName) {
    if (newName.size() > oldName.size()) return {};  // can't grow
    ElfFile elf = parse_elf(data, len);
    if (!elf.valid) return {};
    std::vector<uint8_t> out(data, data + len);
    bool found = false;

    auto rename_in = [&](const ElfSection& symtab, const std::vector<ElfSymbol>& syms,
                         const ElfSection& strtab) {
        if (symtab.offset == 0 || strtab.offset == 0) return;
        if (strtab.offset + strtab.size > len) return;
        for (size_t i = 0; i < syms.size(); ++i) {
            if (syms[i].name != oldName) continue;
            size_t sym_size = elf.is64 ? 24 : 16;
            uint32_t name_idx;
            const uint8_t* sym = data + symtab.offset + i * sym_size;
            if (elf.is64) name_idx = read_le32(sym);
            else name_idx = read_le32(sym);
            // Write new name at the same offset in the string table
            uint64_t name_offset = strtab.offset + name_idx;
            if (name_offset + newName.size() + 1 <= strtab.offset + strtab.size) {
                std::memcpy(out.data() + name_offset, newName.c_str(), newName.size());
                out[name_offset + newName.size()] = 0;  // null terminate
                found = true;
            }
        }
    };

    const ElfSection* symtab = find_section(elf, ".symtab");
    const ElfSection* dynsym = find_section(elf, ".dynsym");
    const ElfSection* strtab = find_section(elf, ".strtab");
    const ElfSection* dynstr = find_section(elf, ".dynstr");

    if (symtab && strtab && symtab->link < elf.sections.size()) {
        rename_in(*symtab, elf.symbols, elf.sections[symtab->link]);
    }
    if (dynsym && dynstr && dynsym->link < elf.sections.size()) {
        rename_in(*dynsym, elf.dynSymbols, elf.sections[dynsym->link]);
    }
    if (!found) return {};
    return out;
}

// Extract printable strings from string-table sections (.rodata, .dynstr, etc.)
struct ExtractedString {
    uint64_t offset;
    std::string text;
    std::string section;
};

inline std::vector<ExtractedString> elf_extract_strings(
        const uint8_t* data, size_t len, const ElfFile& elf, size_t max_count = 5000) {
    std::vector<ExtractedString> out;
    for (const auto& sec : elf.sections) {
        if (out.size() >= max_count) break;
        if (sec.size == 0 || sec.type == SHT_NOBITS) continue;
        // Scan .rodata, .data, .dynstr, .strtab and similar
        bool scan = (sec.name == ".rodata" || sec.name == ".data" ||
                     sec.name == ".data.rel.ro" || sec.name == ".dynstr" ||
                     sec.name == ".strtab" || sec.name.find("rodata") != std::string::npos);
        if (!scan) continue;
        if (sec.offset + sec.size > len) continue;
        const uint8_t* base = data + sec.offset;
        size_t start = 0;
        for (size_t i = 0; i <= sec.size; ++i) {
            if (i == sec.size || base[i] == 0) {
                size_t slen = i - start;
                if (slen >= 4) {
                    // Check if printable
                    bool printable = true;
                    for (size_t j = start; j < i; ++j) {
                        unsigned char c = base[j];
                        if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                            printable = false;
                            break;
                        }
                    }
                    if (printable) {
                        ExtractedString es;
                        es.offset = sec.offset + start;
                        es.text = std::string(reinterpret_cast<const char*>(base + start), slen);
                        es.section = sec.name;
                        out.push_back(std::move(es));
                        if (out.size() >= max_count) break;
                    }
                }
                start = i + 1;
            }
        }
    }
    return out;
}

}  // namespace dsmcp
