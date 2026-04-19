#include "JNIUtils.h"

namespace JNIUtils { JavaVM* g_jvm = nullptr; }

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIUtils::g_jvm = vm;
    return JNI_VERSION_1_6;
}