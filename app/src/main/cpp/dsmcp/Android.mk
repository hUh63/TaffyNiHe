LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE    := dsmcp_native
LOCAL_SRC_FILES := native-lib.cpp \
                   elf_bridge.cpp \
                   pe_bridge.cpp \
                   dotnet_bridge.cpp

LOCAL_CPPFLAGS  := -std=c++17 -fexceptions -frtti
LOCAL_LDLIBS    := -llog

include $(BUILD_SHARED_LIBRARY)
