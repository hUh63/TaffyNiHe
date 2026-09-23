#pragma once
// struct_database.hpp — Struct field offset database
// JNI/JavaVM struct layouts + field lookup

#include <string>
#include <vector>
#include <unordered_map>

namespace symdb {

// Struct field definition
struct StructFieldDef {
    int offset;
    int size;
    std::string name;
    std::string type;
};

// Struct definition
struct StructDef {
    std::string name;
    int size;
    std::vector<StructFieldDef> fields;
};

// Struct database: maps struct names to field layouts
class StructDatabase {
public:
    StructDatabase();

    // Lookup field by struct name and offset
    bool lookupField(const std::string& struct_name, int offset,
                     StructFieldDef& out_field) const;

    // Identify struct type from offset access pattern
    // Returns struct name if pattern matches a known struct, empty otherwise.
    std::string identifyStruct(const std::vector<int>& accessed_offsets) const;

    // Get struct definition
    const StructDef* getStruct(const std::string& name) const;

    // Check if offset is a known JNI offset
    bool isJNIOffset(int offset, StructFieldDef& out_field) const;

private:
    std::unordered_map<std::string, StructDef> structs_;

    void loadJNINativeInterface();
    void loadJavaVM();
    void loadJNINativeMethod();
    void loadCommonStructs();
};

// Field access conversion helper
// Converts *(type*)(base + offset) to base->fieldName if known
struct FieldAccessResult {
    bool found = false;
    std::string struct_name;
    std::string field_name;
    std::string field_type;
    std::string c_expr;  // e.g. "env->FindClass"
};

FieldAccessResult convertFieldAccess(const StructDatabase& db,
                                      const std::string& base_type,
                                      int offset,
                                      const std::string& base_name);

} // namespace symdb
