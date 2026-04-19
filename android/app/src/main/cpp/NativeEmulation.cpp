#include <jni.h>
#include <android/native_window_jni.h>
#include "xemu_android.h"
#include "JNIUtils.h"

extern "C" {
    JNIEXPORT void JNICALL
    Java_com_xemu_NativeInterface_startEmulation(JNIEnv* env, jobject thiz, jobject surface, jstring configPath) {
        std::string path = JNIUtils::toString(env, configPath);
        ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
        // Trigger xemu core startup logic
    }
}