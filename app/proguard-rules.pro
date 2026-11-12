-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.slurpdrome.**$$serializer { *; }
-keepclassmembers class dev.slurpdrome.** { *** Companion; }
-keepclasseswithmembers class dev.slurpdrome.** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Instantiated by name via reflection (manifest meta-data / Glance action callbacks).
-keep class dev.slurpdrome.player.CastOptionsProvider { <init>(); *; }
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }
