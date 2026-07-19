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
    jstring hddPath, jstring isoPath, jstring renderer,
    jstring hookLibDir, jstring driverDir, jstring driverName) {

    LOGI("NativeInterface.startEmulation called");
    // SDL_SetMainReady() tells SDL that app init is complete without SDLActivity.
    SDL_SetMainReady();

    std::string c_config       = jstringToString(env, configPath);
    std::string c_mcpx         = jstringToString(env, mcpxPath);
    std::string c_bios         = jstringToString(env, biosPath);
    std::string c_hdd          = jstringToString(env, hddPath);
    std::string c_iso          = jstringToString(env, isoPath);
    std::string c_renderer     = jstringToString(env, renderer);
    std::string c_hook_lib_dir = jstringToString(env, hookLibDir);
    std::string c_driver_dir   = jstringToString(env, driverDir);
    std::string c_driver_name  = jstringToString(env, driverName);

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        LOGI("Failed to obtain ANativeWindow.");
        return;
    }

    LOGI("Starting xemu with System Files:");
    LOGI("  Config:   %s", c_config.c_str());
    LOGI("  MCPX:     %s", c_mcpx.c_str());
    LOGI("  BIOS:     %s", c_bios.c_str());
    LOGI("  HDD:      %s", c_hdd.c_str());
    LOGI("  ISO:      %s", c_iso.empty() ? "(none)" : c_iso.c_str());
    LOGI("  Renderer: %s", c_renderer.empty() ? "(default)" : c_renderer.c_str());
    LOGI("  Driver:   %s/%s", c_driver_dir.empty() ? "(system)" : c_driver_dir.c_str(),
                               c_driver_name.empty() ? "" : c_driver_name.c_str());

    xemu_android_start(
        c_config.c_str(),
        c_mcpx.c_str(),
        c_bios.c_str(),
        c_hdd.c_str(),
        c_iso.c_str(),
        c_renderer.empty() ? nullptr : c_renderer.c_str(),
        c_hook_lib_dir.empty() ? nullptr : c_hook_lib_dir.c_str(),
        c_driver_dir.empty() ? nullptr : c_driver_dir.c_str(),
        c_driver_name.empty() ? nullptr : c_driver_name.c_str(),
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
Java_com_xemu_NativeInterface_flushBlockDevices(JNIEnv *env, jclass clazz) {
    xemu_android_flush_block_devices();
}

JNIEXPORT jint JNICALL
Java_com_xemu_NativeInterface_getRenderedFrameCount(JNIEnv *env, jclass clazz) {
    return xemu_android_get_rendered_frame_count();
}

JNIEXPORT jint JNICALL
Java_com_xemu_NativeInterface_getWorstFrameTimeMs(JNIEnv *env, jclass clazz) {
    return xemu_android_get_worst_frame_time_ms();
}

JNIEXPORT jint JNICALL
Java_com_xemu_NativeInterface_getCompiledShaderCount(JNIEnv *env, jclass clazz) {
    return xemu_android_get_compiled_shader_count();
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
Java_com_xemu_NativeInterface_setAspectRatio(JNIEnv *env, jclass clazz, jint ratio) {
    xemu_hud_set_aspect_ratio((int)ratio);
}

JNIEXPORT void JNICALL
Java_com_xemu_NativeInterface_setFilterNearest(JNIEnv *env, jclass clazz, jboolean nearest) {
    xemu_hud_set_filter_nearest((bool)nearest);
}

JNIEXPORT void JNICALL
Java_com_xemu_NativeInterface_setSurfaceScale(JNIEnv *env, jclass clazz, jint scale) {
    xemu_android_set_surface_scale((unsigned int)scale);
}

JNIEXPORT void JNICALL
Java_com_xemu_NativeInterface_saveState(JNIEnv *env, jclass clazz, jstring name) {
    std::string s = jstringToString(env, name);
    xemu_android_save_state(s.c_str());
}

JNIEXPORT void JNICALL
Java_com_xemu_NativeInterface_loadState(JNIEnv *env, jclass clazz, jstring name) {
    std::string s = jstringToString(env, name);
    xemu_android_load_state(s.c_str());
}

JNIEXPORT jintArray JNICALL
Java_com_xemu_NativeInterface_getRumble(JNIEnv *env, jclass clazz) {
    uint16_t l = 0, r = 0;
    xemu_android_get_rumble(&l, &r);
    jintArray result = env->NewIntArray(2);
    jint vals[2] = { (jint)l, (jint)r };
    env->SetIntArrayRegion(result, 0, 2, vals);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_com_xemu_NativeInterface_listStates(JNIEnv *env, jclass clazz) {
    char **names = nullptr;
    int n = xemu_android_list_states(&names);

    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(n, stringClass, nullptr);
    for (int i = 0; i < n; i++) {
        jstring jstr = env->NewStringUTF(names[i] ? names[i] : "");
        env->SetObjectArrayElement(result, i, jstr);
        env->DeleteLocalRef(jstr);
    }
    xemu_android_free_state_names(names, n);
    return result;
}

} // extern "C"
