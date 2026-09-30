# JBR binds its services by API names, nested interfaces and runtime annotations.
# These entry points are invisible to the shrinker's static reachability analysis.
-keep class com.jetbrains.** { *; }
-keep interface com.jetbrains.** { *; }
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,InnerClasses,EnclosingMethod
