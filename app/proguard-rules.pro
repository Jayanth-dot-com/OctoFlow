# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep MediaPipe LLM classes
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# Keep serialization
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
-keepclassmembers class com.octoflow.** {
    *** Companion;
}

# Keep coroutines
-dontwarn kotlinx.coroutines.**

# Keep accessibility service
-keep class com.octoflow.accessibility.service.** { *; }
-keep class com.octoflow.accessibility.model.** { *; }

# Keep LLM models and responses
-keepclassmembers class * implements com.octoflow.core.model.Action { *; }

# AndroidX
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Suppress warnings about missing classes from optional dependencies
-dontwarn ai.mlc.**
-dontwarn com.google.android.gms.**
-dontwarn org.tensorflow.**
-dontwarn org.pytorch.**