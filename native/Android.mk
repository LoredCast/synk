LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE    := synk_native
LOCAL_C_INCLUDES := $(LOCAL_PATH)/include
LOCAL_SRC_FILES := \
    src/sha256.c \
    src/scanner.c \
    src/diff3.c \
    src/planner.c \
    src/watcher.c \
    jni/synk_jni.c
LOCAL_CFLAGS    := -std=c11 -O2 -Wall -Wextra -fvisibility=hidden
LOCAL_LDLIBS    := -llog
include $(BUILD_SHARED_LIBRARY)
