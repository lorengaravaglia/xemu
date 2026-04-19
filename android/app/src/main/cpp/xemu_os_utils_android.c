#include <jni.h>
#include <android/log.h>
#include <sys/system_properties.h>
#include <stdio.h>

#define LOG_TAG "xemu-android-os-utils-stub"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

const char *xemu_get_os_info(void) {
    static char os_info[PROP_VALUE_MAX + 128]; // Max length of build.version.release + some extra space
    char android_version[PROP_VALUE_MAX];
    char manufacturer[PROP_VALUE_MAX];
    char model[PROP_VALUE_MAX];

    __system_property_get("ro.build.version.release", android_version);
    __system_property_get("ro.product.manufacturer", manufacturer);
    __system_property_get("ro.product.model", model);

    snprintf(os_info, sizeof(os_info), "Android %s (%s %s)", android_version, manufacturer, model);
    return os_info;
}

const char *xemu_get_cpu_info(void) {
    // This will return an empty string, or you can implement actual CPU info retrieval
    // For a stub, an empty string is sufficient to prevent compilation errors.
    return "";
}