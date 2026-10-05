# JBR binds its services by API names, nested interfaces and runtime annotations.
# These entry points are invisible to the shrinker's static reachability analysis.
-keep class com.jetbrains.** { *; }
-keep interface com.jetbrains.** { *; }
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,InnerClasses,EnclosingMethod

# PlantUML (in-process diagram engine) builds diagrams, skins and commands reflectively (it ships a GraalVM
# reflect-config) and references optional libraries absent from the MIT build; keep it whole and quiet the rest.
-keep class net.sourceforge.plantuml.** { *; }
-keep class net.atmp.** { *; }
-keep class smetana.** { *; }
-keep class gen.** { *; }
-keep class h.** { *; }
-keep class com.plantuml.** { *; }
-keep class org.stathissideris.** { *; }
-dontwarn net.sourceforge.plantuml.**
-dontwarn net.atmp.**
-dontwarn smetana.**
-dontwarn gen.**
-dontwarn h.**
-dontwarn com.plantuml.**
-dontwarn org.stathissideris.**
-dontwarn org.eclipse.elk.**
-dontwarn org.apache.batik.**
-dontwarn org.scilab.forge.jlatexmath.**
-dontwarn org.apache.tools.ant.**
-dontwarn org.teavm.**
-dontwarn com.lowagie.**
