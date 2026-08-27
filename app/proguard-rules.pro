# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in C:\Users\...\AppData\Local\Android\Sdk/tools/proguard/proguard-android.txt

# ── Keep ViewBinding generated classes ──
-keep class com.example.macconverter.databinding.** { *; }

# ── Keep inner data classes used by Kotlin (RootResult) ──
-keep class com.example.macconverter.MainActivity$RootResult { *; }

# ── Keep Kotlin metadata for data classes ──
-keepclassmembers class * {
    @kotlin.Metadata *;
}

# ── Keep NetworkInterface reflection usage ──
-keep class java.net.NetworkInterface { *; }

# ── Keep Material Components that use reflection ──
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# ── Keep AndroidX classes ──
-keep class androidx.** { *; }
-dontwarn androidx.**

# ── Prevent stripping of Kotlin coroutines ──
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.** { volatile <fields>; }
