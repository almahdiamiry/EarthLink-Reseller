# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep Room database and entities safe from obfuscation so SQL queries don't break
-keep class com.example.core.model.** { *; }
-keep class com.example.core.database.** { *; }

# Keep Retrofit/Moshi models for JSON serialization
-keepattributes *Annotation*
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}

# Protect sensitive billing logic/repositories from aggressive shrinking
-keepnames class com.example.data.repository.** { *; }

# Remove verbose/debug/info logging in release, but keep Log.e for production error diagnosis
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
}

# Preserve line numbers for crash reporting
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep class com.alamiry.earthlinkreseller.BuildConfig { *; }
-keepclassmembers class com.alamiry.earthlinkreseller.BuildConfig {
    public static <fields>;
}
