# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /Users/lorengaravaglia/Library/Android/sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.

# For more details, see
#   http://developer.android.com/guide/developing/tools-proguard.html

# Keep all JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep classes called by JNI
-keep class com.xemu.NativeInterface { *; }
-keep class com.xemu.NativeInput { *; }
