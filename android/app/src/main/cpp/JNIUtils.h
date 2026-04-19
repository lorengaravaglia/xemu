#pragma once
#include <jni.h>
#include <string>

namespace JNIUtils {
        extern JavaVM* g_jvm;

    inline std::string toString(JNIEnv* env, jstring jstr) {
        if (jstr == nullptr) return {};
        const char* c_str = env->GetStringUTFChars(jstr, nullptr);
        std::string str(c_str);
        env->ReleaseStringUTFChars(jstr, c_str);
        return str;
    }
}