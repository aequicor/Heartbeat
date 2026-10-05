# JBR binds its services by API names, nested interfaces and runtime annotations.
# These entry points are invisible to the shrinker's static reachability analysis.
-keep class com.jetbrains.** { *; }
-keep interface com.jetbrains.** { *; }
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,InnerClasses,EnclosingMethod

# Optional integrations missing from the desktop runtime. Rules name the missing classes, not the libraries,
# so new unresolved references from the same libraries still fail the build.

# GraalVM native-image SDK: OkHttp, kotlin-logging and Nucleus substitutions and features run only in native images.
-dontwarn org.graalvm.nativeimage.**
-dontwarn org.graalvm.word.**
-dontwarn com.oracle.svm.core.annotate.**

# OkHttp probes optional TLS providers and falls back to the JDK provider when they are absent.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.openjsse.**

# kotlin-logging (Koog) binds Logback only with -Dkotlin-logging-to-logback=true; otherwise it logs through SLF4J.
-dontwarn ch.qos.logback.classic.**

# FlowMVI 3.2.1 is annotated with the pre-Kotlin 2.3 name of kotlin.MustUseReturnValues; the JVM skips
# annotations of missing types.
-dontwarn kotlin.MustUseReturnValue

# The macOS kit's Nucleus window and application APIs, unused here, reference absent window backends: Tao is
# excluded in design-system/adaptive, and no DecoratedWindow/DecoratedDialog backend (JBR, JNI) is chosen. The app
# draws its own window chrome on the JBR API and uses only the kit's controls and theme.
-dontwarn dev.nucleusframework.window.tao.**
-dontwarn dev.nucleusframework.window.DecoratedDialogKt
-dontwarn dev.nucleusframework.window.DecoratedWindowKt
-dontwarn dev.nucleusframework.window.TitleBarPlacement,dev.nucleusframework.window.TitleBarPlacement$*
-dontwarn dev.nucleusframework.window.WindowAppearance*
-dontwarn dev.nucleusframework.window.WindowChromeInsets*
-dontwarn dev.nucleusframework.window.WindowControlsKt
-dontwarn dev.nucleusframework.window.WindowDragArea*
-dontwarn dev.nucleusframework.window.WindowGlassRegion*
-dontwarn dev.nucleusframework.window.WindowScaffold*

# compose-fluent v0.1.0 (the latest release) targets Compose 1.8, where TextManager actions were Function0.
# The menu is installed only by FluentTheme, which the app never applies: HbTheme loads kit themes in the
# Platform visual style only, used by the UIKit sandbox.
-dontwarn io.github.composefluent.component.FluentTextContextMenu

# ServiceLoader providers: ProGuard does not read META-INF/services, so it would strip the implementations that
# reachable code loads by interface.
-keep class * implements com.arkivanov.decompose.mainthread.MainThreadChecker { <init>(); }
-keep class * implements io.ktor.client.HttpClientEngineContainer { <init>(); }
-keep class * implements io.ktor.serialization.kotlinx.KotlinxSerializationExtensionProvider { <init>(); }

# JNA: its native dispatcher calls back into JNA by name, and it maps Library interfaces, Callbacks and Structure
# fields reflectively (core:secrets, desktop dialogs, computer use, window integration).
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }

# Room's bundled SQLite registers all of its JNI methods on load; one stripped unused method fails the driver.
-keepclassmembers class androidx.sqlite.driver.bundled.** { native <methods>; }
