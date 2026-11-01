//
// json_builder.h - Lightweight JSON string builder for the native layer.
// Avoids pulling in a full JSON library; produces compact JSON output
// that Kotlin's org.json.JSONObject can parse directly.
//
#pragma once

#include <string>
#include <cstdint>
#include <cstdio>

namespace dsmcp {

class JsonBuilder {
public:
    JsonBuilder() : buf_(), first_key_(true), in_array_(false) {
        buf_ += "{";
    }

    ~JsonBuilder() = default;

    // Add a string field.
    JsonBuilder& str(const char* key, const std::string& val) {
        sep();
        buf_ += "\"";
        buf_ += key;
        buf_ += "\":";
        buf_ += "\"";
        buf_ += escape(val);
        buf_ += "\"";
        return *this;
    }

    // Add a C-string field.
    JsonBuilder& cstr(const char* key, const char* val) {
        return str(key, val ? std::string(val) : std::string());
    }

    // Add a 64-bit integer field.
    JsonBuilder& num(const char* key, uint64_t val) {
        sep();
        buf_ += "\"";
        buf_ += key;
        buf_ += "\":";
        buf_ += std::to_string(val);
        return *this;
    }

    // Add a signed integer field.
    JsonBuilder& snum(const char* key, int64_t val) {
        sep();
        buf_ += "\"";
        buf_ += key;
        buf_ += "\":";
        buf_ += std::to_string(val);
        return *this;
    }

    // Add a boolean field.
    JsonBuilder& boolean(const char* key, bool val) {
        sep();
        buf_ += "\"";
        buf_ += key;
        buf_ += "\":";
        buf_ += val ? "true" : "false";
        return *this;
    }

    // Begin an array field. Must be closed with end_array().
    JsonBuilder& begin_array(const char* key) {
        sep();
        buf_ += "\"";
        buf_ += key;
        buf_ += "\":[";
        first_key_ = true;
        in_array_ = true;
        return *this;
    }

    // Begin an object inside an array. Must be closed with end_object().
    JsonBuilder& begin_object() {
        if (!first_key_) buf_ += ",";
        first_key_ = true;
        buf_ += "{";
        return *this;
    }

    // End the current object (inside an array or top-level).
    JsonBuilder& end_object() {
        buf_ += "}";
        first_key_ = false;
        return *this;
    }

    // End the current array.
    JsonBuilder& end_array() {
        buf_ += "]";
        first_key_ = false;
        in_array_ = false;
        return *this;
    }

    // Add a raw JSON string value (already-formatted JSON) as a field.
    JsonBuilder& raw(const char* key, const std::string& json) {
        sep();
        buf_ += "\"";
        buf_ += key;
        buf_ += "\":";
        buf_ += json;
        return *this;
    }

    // Finalize and return the JSON string.
    std::string build() {
        buf_ += "}";
        return buf_;
    }

    // Get the current buffer content (for nesting).
    std::string& buffer() { return buf_; }
    const std::string& buffer() const { return buf_; }

private:
    void sep() {
        if (!first_key_) buf_ += ",";
        first_key_ = false;
    }

    static std::string escape(const std::string& s) {
        std::string out;
        out.reserve(s.size() + 8);
        for (unsigned char c : s) {
            switch (c) {
                case '"':  out += "\\\""; break;
                case '\\': out += "\\\\"; break;
                case '\n': out += "\\n"; break;
                case '\r': out += "\\r"; break;
                case '\t': out += "\\t"; break;
                default:
                    if (c < 0x20) {
                        char tmp[8];
                        std::snprintf(tmp, sizeof(tmp), "\\u%04x", c);
                        out += tmp;
                    } else {
                        out += static_cast<char>(c);
                    }
            }
        }
        return out;
    }

    std::string buf_;
    bool first_key_;
    bool in_array_;
};

}  // namespace dsmcp
