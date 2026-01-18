# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Avoid broad keep rules: they effectively disable shrinking/obfuscation and increase APK size/attack surface.

# Glide proguard
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
  **[] $VALUES;
  public *;
}

-assumenosideeffects class com.maxwai.nclientv3.utility.LogUtility {
    public static void d(...);
    public static void i(...);
}

# WorkManager uses reflection to instantiate workers from their (obfuscated) class names stored in WorkRequest.
# Keeping the worker constructors avoids edge-case issues across R8 versions.
-keep class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-dontwarn com.bumptech.glide.load.resource.bitmap.VideoDecoder
