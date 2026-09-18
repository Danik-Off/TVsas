# All JSON parsing is hand-written (org.json), so no model classes need to be kept.
# OkHttp, Coil, Media3 and Leanback ship their own consumer rules.

# Keep line numbers for readable crash reports from users' TV boxes.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Fragments are instantiated by class name from the Leanback framework.
-keep public class * extends androidx.fragment.app.Fragment

# Strip verbose logging in release.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
