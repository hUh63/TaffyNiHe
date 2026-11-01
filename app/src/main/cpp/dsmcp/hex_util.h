//
// hex_util.h - Hex encoding/decoding and byte array helpers.
// Part of DSMCP native layer. Self-contained, no external dependencies.
//
#pragma once

#include <string>
#include <vector>
#include <cstdint>
#include <cstring>

namespace dsmcp {

// Encode a byte array into a lowercase hex string with optional spaces.
inline std::string to_hex(const uint8_t* data, size_t len, bool spaced = false) {
    static const char* digits = "0123456789abcdef";
    std::string out;
    out.reserve(len * (spaced ? 3 : 2));
    for (size_t i = 0; i < len; ++i) {
        if (spaced && i > 0) out += ' ';
        out += digits[(data[i] >> 4) & 0x0f];
        out += digits[data[i] & 0x0f];
    }
    return out;
}

inline std::string to_hex(const std::vector<uint8_t>& data, bool spaced = false) {
    return to_hex(data.data(), data.size(), spaced);
}

// Parse a hex string (with or without spaces, 0x prefix, or ?? wildcards
// which are treated as 0x00) into a byte vector. Returns empty on failure.
inline std::vector<uint8_t> from_hex(const std::string& str) {
    std::vector<uint8_t> out;
    out.reserve(str.size() / 2);
    bool high_nibble = false;
    uint8_t byte_val = 0;
    bool started = false;
    for (size_t i = 0; i < str.size(); ++i) {
        char c = str[i];
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ',') continue;
        if (c == '0' && i + 1 < str.size() && (str[i + 1] == 'x' || str[i + 1] == 'X')) {
            ++i;
            continue;
        }
        if (c == '?' || c == '.') {
            // Wildcard nibble - treat as 0
            c = '0';
        }
        uint8_t v;
        if (c >= '0' && c <= '9') v = c - '0';
        else if (c >= 'a' && c <= 'f') v = c - 'a' + 10;
        else if (c >= 'A' && c <= 'F') v = c - 'A' + 10;
        else return {};  // invalid character
        if (!high_nibble) {
            byte_val = v << 4;
            high_nibble = true;
            started = true;
        } else {
            byte_val |= v;
            out.push_back(byte_val);
            high_nibble = false;
        }
    }
    if (high_nibble && started) {
        // Odd number of nibbles - pad the last nibble
        out.push_back(byte_val);
    }
    return out;
}

// Read little-endian values from a byte buffer at a given offset.
inline uint16_t read_le16(const uint8_t* p) {
    return static_cast<uint16_t>(p[0]) | (static_cast<uint16_t>(p[1]) << 8);
}

inline uint32_t read_le32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

inline uint64_t read_le64(const uint8_t* p) {
    return static_cast<uint64_t>(read_le32(p)) |
           (static_cast<uint64_t>(read_le32(p + 4)) << 32);
}

// Read big-endian values.
inline uint16_t read_be16(const uint8_t* p) {
    return (static_cast<uint16_t>(p[0]) << 8) | static_cast<uint16_t>(p[1]);
}

inline uint32_t read_be32(const uint8_t* p) {
    return (static_cast<uint32_t>(p[0]) << 24) | (static_cast<uint32_t>(p[1]) << 16) |
           (static_cast<uint32_t>(p[2]) << 8) | static_cast<uint32_t>(p[3]);
}

inline uint64_t read_be64(const uint8_t* p) {
    return (static_cast<uint64_t>(read_be32(p)) << 32) |
           static_cast<uint64_t>(read_be32(p + 4));
}

// Write little-endian values into a byte buffer.
inline void write_le16(uint8_t* p, uint16_t v) {
    p[0] = v & 0xff;
    p[1] = (v >> 8) & 0xff;
}

inline void write_le32(uint8_t* p, uint32_t v) {
    p[0] = v & 0xff;
    p[1] = (v >> 8) & 0xff;
    p[2] = (v >> 16) & 0xff;
    p[3] = (v >> 24) & 0xff;
}

inline void write_le64(uint8_t* p, uint64_t v) {
    write_le32(p, static_cast<uint32_t>(v));
    write_le32(p + 4, static_cast<uint32_t>(v >> 32));
}

}  // namespace dsmcp
