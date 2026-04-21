#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <string>
#include <unistd.h>
#define SDL_MAIN_HANDLED
#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>
#include "xemu_android.h"
#include "xemu_hud_stub.h"

#define LOG_TAG "xemu-android"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static std::string jstringToString(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string s(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return s;
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_xemu_NativeInterface_startEmulation(
    JNIEnv *env, jclass clazz, jobject surface,
    jstring configPath, jstring mcpxPath, jstring biosPath,
    jstring hddPath, jstring isoPath) {

    LOGI("NativeInterface.startEmulation called");
    // SDL_SetMainReady() tells SDL that app init is complete without SDLActivity.
    SDL_SetMainReady();

    std::string c_config  = jstringToString(env, configPath);
    std::string c_mcpx    = jstringToString(env, mcpxPath);
    std::string c_bios    = jstringToString(env, biosPath);
    std::string c_hdd     = jstringToString(env, hddPath);
    std::string c_iso     = jstringToString(env, isoPath);

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        LOGI("Failed to obtain ANativeWindow.");
        return;
    }

    LOGI("Starting xemu with System Files:");
    LOGI("  Config: %s", c_config.c_str());
    LOGI("  MCPX:   %s", c_mcpx.c_str());
    LOGI("  BIOS:   %s", c_bios.c_str());
    LOGI("  HDD:    %s", c_hdd.c_str());
    LOGI("  ISO:    %s", c_iso.empty() ? "(none)" : c_iso.c_str());

    xemu_android_start(
        c_config.c_str(),
        c_mcpx.c_str(),
        c_bios.c_str(),
        c_hdd.c_str(),
        c_iso.c_str(),
        window
    );
}

JNIEXPORT void JNICALL
Java_com_xemu_NativeInterface_stopEmulation(JNIEnv *env, jclass clazz) {
    xemu_android_stop();
}

JNIEXPORT void JNICALL
Java_com_xemu_NativeInterface_pauseEmulation(JNIEnv *env, jclass clazz) {
    xemu_android_vm_pause();
}

JNIEXPORT void JNICALL
Java_com_xemu_NativeInterface_resumeEmulation(JNIEnv *env, jclass clazz) {
    xemu_android_vm_resume();
}

JNIEXPORT void JNICALL
Java_com_xemu_NativeInterface_setSurface(JNIEnv *env, jclass clazz, jobject surface) {
    if (surface == nullptr) {
        xemu_android_surface_destroyed();
    } else {
        ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
        if (!window) {
            LOGI("setSurface: ANativeWindow_fromSurface returned null");
            return;
        }
        xemu_android_surface_created(window);
    }
}

JNIEXPORT void JNICALL
Java_com_xemu_NativeInterface_sendButtonDown(JNIEnv *env, jclass clazz, jint mask) {
    xemu_android_set_button((uint32_t)mask, 1);
}

JNIEXPORT void JNICALL
Java_com_xemu_NativeInterface_sendButtonUp(JNIEnv *env, jclass clazz, jint mask) {
    xemu_android_set_button((uint32_t)mask, 0);
}

JNIEXPORT void JNICALL
Java_com_xemu_NativeInterface_sendAxis(JNIEnv *env, jclass clazz, jint axis, jint value) {
    xemu_android_set_axis(axis, (int16_t)value);
}

JNIEXPORT void JNICALL
Java_com_xemu_NativeInterface_requestExit(JNIEnv *env, jclass clazz) {
    xemu_android_request_exit();
}

JNIEXPORT void JNICALL
Java_com_xemu_NativeInterface_setAspectRatio(JNIEnv *env, jclass clazz, jboolean wide) {
    xemu_hud_set_aspect_16x9((bool)wide);
}

} // extern "C"
