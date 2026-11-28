# ==============================================================================
# QemuSDL Release R8 / ProGuard Rules
# ==============================================================================

# 1. SDL2 Android Java layer — invoked dynamically from libSDL2.so via JNI
-keep class org.libsdl.app.** { *; }
-keepclassmembers class org.libsdl.app.** { *; }
-dontwarn org.libsdl.app.**

# 2. QemuSDL native JNI bridge & SDLActivity subclass
-keep class com.qemusdl.app.sdl.NativeQemuBridge { *; }
-keep class com.qemusdl.app.ui.viewport.QemuSdlActivity { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# 3. Kotlinx Serialization (VmConfig JSON persistence, RuntimeProbeStatus, Telemetry, Nav3 keys)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.qemusdl.app.**$$serializer { *; }
-keepclassmembers class com.qemusdl.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.qemusdl.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class * extends java.lang.Enum {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Preserve readable stack traces without retaining debug overhead
-keepattributes SourceFile,LineNumberTable
