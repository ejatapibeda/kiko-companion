-keep class ai.onnxruntime.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature
-dontwarn ai.onnxruntime.**
-keep class com.coactive.companion.DebugConfigReceiver { *; }
# kotlinx.serialization
-keepclassmembers @kotlinx.serialization.Serializable class com.coactive.companion.** {
    *** Companion; *** INSTANCE; kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.coactive.companion.llm.** { *; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.errorprone.annotations.**
# Kontrak spotapi (kotlinx.serialization DTO) & controller Spotify
-keep class com.coactive.companion.spotify.** { *; }
