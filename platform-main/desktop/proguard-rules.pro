# JCEF binds Java entry points from JNI by their exact names; jcefmaven reads build metadata with Gson.
# Keep the native tar.gz and build_meta.json resources in the runtime dependency jars.
-keep class org.cef.** { *; }
-keep class me.friwi.jcefmaven.** { *; }

# JBR binds its services by API names, nested interfaces and runtime annotations.
# These entry points are invisible to the shrinker's static reachability analysis.
-keep class com.jetbrains.** { *; }
-keep interface com.jetbrains.** { *; }
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,InnerClasses,EnclosingMethod

# Enum.valueOf and EnumSet discover values() reflectively, including UI enum mappings and TLS enums.
# Keep them public and unoptimized: privatizing values() also breaks Class.getEnumConstants().
# Without these members a surviving enum can fail with "is not an enum class" in the release image.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Okio's Kotlin multifile parts use Okio__*Kt names, unlike the **Kt__* pattern in Compose's defaults.
# Return-type specialization of buffer(Sink) produces invalid bytecode (BufferedSink vs RealBufferedSink):
# OkHttp's dispatcher dies with VerifyError and Ktor waits until its request timeout.
-keep,allowshrinking,allowobfuscation class okio.Okio__* { *; }

# Kotlin compilation uses reflective services and serialized script configurations. Keep the compiler,
# its shaded IntelliJ runtime and Kotlin metadata; scripts resolve the public API by its source names.
-keep class org.jetbrains.kotlin.** { *; }
# Compiler-shaded ASM uses reflection-backed EnumSet state. Optimizing CheckSignatureAdapter.State
# changes its enum shape and fails compiler initialization in the release image.
-keep class org.jetbrains.org.objectweb.asm.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class kotlinx.serialization.** { *; }
-keep class io.aequicor.heartbeat.feature.harness.api.** { *; }
-keep class io.aequicor.heartbeat.feature.harness.impl.data.script.** { *; }
-keep class io.aequicor.heartbeat.feature.aiengine.facade.api.** { *; }
-keep class io.aequicor.heartbeat.feature.scheduler.api.** { *; }
-keepattributes Signature,Exceptions

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

# Kotlin 2.4.20 compiler-embeddable keeps optional IDE/build-tool entry points in the same jar.
# Its published runtime POM does not require these integrations. The release compiler probe verifies the
# supported K2 + StringScriptSource path in the compiler worker; keep each missing type explicit so additions still fail.

# Static-analysis and Objective-C ownership annotations bundled references do not affect JVM execution.
-dontwarn kotlin.annotations.jvm.Mutable,kotlin.annotations.jvm.ReadOnly
-dontwarn org.checkerframework.checker.nullness.qual.NonNull,org.checkerframework.checker.nullness.qual.Nullable
-dontwarn org.jetbrains.annotations.SystemDependent,org.jetbrains.annotations.SystemIndependent
-dontwarn org.jetbrains.kotlin.com.google.common.annotations.VisibleForTesting
-dontwarn org.jetbrains.kotlin.com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn org.jetbrains.kotlin.com.google.errorprone.annotations.CheckReturnValue
-dontwarn org.jetbrains.kotlin.com.google.errorprone.annotations.CompatibleWith
-dontwarn org.jetbrains.kotlin.com.google.errorprone.annotations.DoNotCall
-dontwarn org.jetbrains.kotlin.com.google.errorprone.annotations.DoNotMock
-dontwarn org.jetbrains.kotlin.com.google.errorprone.annotations.ForOverride
-dontwarn org.jetbrains.kotlin.com.google.errorprone.annotations.Immutable
-dontwarn org.jetbrains.kotlin.com.google.errorprone.annotations.InlineMe
-dontwarn org.jetbrains.kotlin.com.google.errorprone.annotations.InlineMeValidationDisabled
-dontwarn org.jetbrains.kotlin.com.google.errorprone.annotations.RestrictedApi
-dontwarn org.jetbrains.kotlin.com.google.errorprone.annotations.concurrent.GuardedBy
-dontwarn org.jetbrains.kotlin.com.google.errorprone.annotations.concurrent.LazyInit
-dontwarn org.jetbrains.kotlin.com.google.j2objc.annotations.RetainedWith
-dontwarn org.jetbrains.kotlin.com.google.j2objc.annotations.Weak

# Gson is referenced only by HTTP/JSON compiler build reports; this host does not create those reporters.
-dontwarn org.jetbrains.kotlin.com.google.gson.Gson,org.jetbrains.kotlin.com.google.gson.GsonBuilder
-dontwarn org.jetbrains.kotlin.com.google.gson.JsonDeserializationContext,org.jetbrains.kotlin.com.google.gson.JsonDeserializer
-dontwarn org.jetbrains.kotlin.com.google.gson.JsonElement,org.jetbrains.kotlin.com.google.gson.JsonNull
-dontwarn org.jetbrains.kotlin.com.google.gson.JsonObject,org.jetbrains.kotlin.com.google.gson.JsonPrimitive
-dontwarn org.jetbrains.kotlin.com.google.gson.JsonSerializationContext,org.jetbrains.kotlin.com.google.gson.JsonSerializer

# Incremental build-tool CRI serialization is not used by the fresh in-memory script compiler.
-dontwarn org.jetbrains.kotlin.buildtools.internal.cri.CriDataSerializerImpl
-dontwarn org.jetbrains.kotlin.buildtools.internal.cri.CriDataSerializerImpl$SerializedLookupData

# FileUtil explicitly probes Trove with Class.forName and catches ClassNotFoundException.
-dontwarn gnu.trove.TObjectHashingStrategy
# The supported standalone script pipeline does not call IntelliJ ThreadContext. This is not a disabled
# service: its implicit-blocking flag defaults to true, and entering that branch requires IntellijCoroutines.
# Keep the release probe as the gate for this reachability assumption after compiler upgrades.
-dontwarn kotlinx.coroutines.internal.intellij.IntellijCoroutines
# Document diff is used by IDE editor services, not the standalone script compiler.
-dontwarn org.jetbrains.kotlin.com.intellij.util.diff.Diff,org.jetbrains.kotlin.com.intellij.util.diff.Diff$Change
-dontwarn org.jetbrains.kotlin.com.intellij.util.diff.FilesTooBigForDiffException
# This branch is selected only for FileScriptSource; the host always supplies StringScriptSource.
-dontwarn org.jetbrains.kotlin.com.intellij.openapi.vfs.LocalFileSystem
# The bundled no-op metrics counter does not access its Context parameter; no telemetry SDK is installed.
-dontwarn org.jetbrains.kotlin.io.opentelemetry.context.Context
-dontwarn org.jetbrains.kotlin.io.opentelemetry.context.propagation.ContextPropagators
# Optional JLine Nano editor character-set detection; the host never starts an interactive terminal editor.
-dontwarn org.mozilla.universalchardet.UniversalDetector

# The scripting explain transformer returns before these accesses unless explainField is configured.
# Harness compilation stores no explainField/refinement callbacks in its configuration.
-dontwarn org.jetbrains.kotlin.powerassert.diagram.ExplainKt,org.jetbrains.kotlin.powerassert.diagram.ExplainVariable
-dontwarn org.jetbrains.kotlin.powerassert.diagram.SourceFile,org.jetbrains.kotlin.powerassert.diagram.SourceFile$Companion

# invoke/invokeExact are JVM signature-polymorphic methods, not missing JDK overloads.
-dontwarn java.lang.invoke.MethodHandle
# Legacy -Xuse-javac integration targets old javac internals; K2 scripts use the normal Kotlin frontend.
-dontwarn org.jetbrains.kotlin.javac.JavacWrapper
-dontwarn org.jetbrains.kotlin.javac.resolve.StaticImportFieldScope
-dontwarn org.jetbrains.kotlin.javac.resolve.StaticImportOnDemandFieldScope

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

# PlantUML (in-process diagram engine) builds diagrams, skins and commands reflectively (it ships a GraalVM
# reflect-config); keep it whole.
-keep class net.sourceforge.plantuml.** { *; }
-keep class net.atmp.** { *; }
-keep class smetana.** { *; }
-keep class gen.** { *; }
-keep class h.** { *; }
-keep class com.plantuml.** { *; }
-keep class org.stathissideris.** { *; }
# The PlantUML jar also carries its browser (TeaVM), PDF export (OpenPDF) and Ant task entry points; the app renders
# PNG through the JVM engine only.
-dontwarn org.teavm.jso.**
-dontwarn org.teavm.interop.**
-dontwarn org.openpdf.text.**
-dontwarn org.apache.tools.ant.**
-dontwarn net.sourceforge.plantuml.ant.CheckZipTask,net.sourceforge.plantuml.ant.PlantUmlTask

# Optional browser dependency integrations: enumerate missing types so new warnings still fail.

# JOGL SWT and OpenAL backends are not used by the windowed Swing browser.
# No SWT widgets or OpenAL audio engine are loaded by Heartbeat.
-dontwarn com.jogamp.openal.AL
-dontwarn com.jogamp.openal.ALC
-dontwarn com.jogamp.openal.ALCConstants
-dontwarn com.jogamp.openal.ALCcontext
-dontwarn com.jogamp.openal.ALCdevice
-dontwarn com.jogamp.openal.ALConstants
-dontwarn com.jogamp.openal.ALExt
-dontwarn com.jogamp.openal.ALFactory
-dontwarn com.jogamp.openal.JoalVersion
-dontwarn com.jogamp.openal.util.ALHelpers
-dontwarn org.eclipse.swt.SWT
-dontwarn org.eclipse.swt.SWTException
-dontwarn org.eclipse.swt.graphics.Color
-dontwarn org.eclipse.swt.graphics.GCData
-dontwarn org.eclipse.swt.graphics.Point
-dontwarn org.eclipse.swt.graphics.Rectangle
-dontwarn org.eclipse.swt.internal.DPIUtil
-dontwarn org.eclipse.swt.layout.FillLayout
-dontwarn org.eclipse.swt.widgets.Canvas
-dontwarn org.eclipse.swt.widgets.Composite
-dontwarn org.eclipse.swt.widgets.Control
-dontwarn org.eclipse.swt.widgets.Display
-dontwarn org.eclipse.swt.widgets.Event
-dontwarn org.eclipse.swt.widgets.Listener
-dontwarn org.eclipse.swt.widgets.Scrollable
-dontwarn org.eclipse.swt.widgets.Shell

# jcefmaven extracts the pinned tar.gz with Commons Compress. XZ, Brotli, Zstd and
# Pack200 are optional codecs in its POM and are never selected for the browser archive.
-dontwarn com.github.luben.zstd.BufferPool
-dontwarn com.github.luben.zstd.Zstd
-dontwarn com.github.luben.zstd.ZstdInputStream
-dontwarn com.github.luben.zstd.ZstdOutputStream
-dontwarn org.brotli.dec.BrotliInputStream
-dontwarn org.objectweb.asm.AnnotationVisitor
-dontwarn org.objectweb.asm.Attribute
-dontwarn org.objectweb.asm.ClassReader
-dontwarn org.objectweb.asm.ClassVisitor
-dontwarn org.objectweb.asm.FieldVisitor
-dontwarn org.objectweb.asm.Label
-dontwarn org.objectweb.asm.MethodVisitor
-dontwarn org.objectweb.asm.Opcodes
-dontwarn org.objectweb.asm.Type
-dontwarn org.tukaani.xz.ARMOptions
-dontwarn org.tukaani.xz.ARMThumbOptions
-dontwarn org.tukaani.xz.DeltaOptions
-dontwarn org.tukaani.xz.FilterOptions
-dontwarn org.tukaani.xz.FinishableWrapperOutputStream
-dontwarn org.tukaani.xz.IA64Options
-dontwarn org.tukaani.xz.LZMA2InputStream
-dontwarn org.tukaani.xz.LZMA2Options
-dontwarn org.tukaani.xz.LZMAInputStream
-dontwarn org.tukaani.xz.LZMAOutputStream
-dontwarn org.tukaani.xz.MemoryLimitException
-dontwarn org.tukaani.xz.PowerPCOptions
-dontwarn org.tukaani.xz.SPARCOptions
-dontwarn org.tukaani.xz.SingleXZInputStream
-dontwarn org.tukaani.xz.UnsupportedOptionsException
-dontwarn org.tukaani.xz.X86Options
-dontwarn org.tukaani.xz.XZ
-dontwarn org.tukaani.xz.XZInputStream
-dontwarn org.tukaani.xz.XZOutputStream

# Inherited SWT/ASM members cannot resolve without those optional superclasses.
# Restrict these member warnings to the unused adapters, not all JOGL or archive code.
-dontwarn com.jogamp.newt.swt.NewtCanvasSWT
-dontwarn com.jogamp.opengl.swt.GLCanvas

# JOGL's JavaFX canvas is another unused window backend. Some packaging JDKs include
# JavaFX modules and others do not; the browser always creates the Swing/AWT surface.
-dontwarn com.sun.javafx.tk.TKStage
-dontwarn javafx.application.Platform
-dontwarn javafx.beans.property.BooleanProperty
-dontwarn javafx.beans.property.DoubleProperty
-dontwarn javafx.beans.property.ReadOnlyObjectProperty
-dontwarn javafx.beans.value.ChangeListener
-dontwarn javafx.beans.value.ObservableValue
-dontwarn javafx.event.Event
-dontwarn javafx.event.EventHandler
-dontwarn javafx.geometry.Bounds
-dontwarn javafx.scene.Scene
-dontwarn javafx.scene.canvas.Canvas
-dontwarn javafx.stage.Window
-dontwarn javafx.stage.WindowEvent
-dontwarn com.jogamp.newt.javafx.NewtCanvasJFX

-dontwarn org.apache.commons.compress.harmony.pack200.NewAttribute,org.apache.commons.compress.harmony.pack200.NewAttribute$ErrorAttribute
-dontwarn org.apache.commons.compress.harmony.pack200.Pack200ClassReader
-dontwarn org.apache.commons.compress.harmony.pack200.Segment$SegmentAnnotationVisitor
