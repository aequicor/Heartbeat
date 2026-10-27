# Rules of the bundle train that no SDK keep rule derives (see generateTrainSdkRules). They are read when the train is
# shrunk (-Pheartbeat.trainShrink=shrink) and describe only Heartbeat's own code: third-party libraries belong to the
# platform and are never processed, so they need no rules.

# --- Entry points ------------------------------------------------------------------------------------------------

# The launcher starts the application by these main classes.
-keep class io.aequicor.heartbeat.platform.desktop.*MainKt {
    public static void main(java.lang.String[]);
}

# The packaged workers are headless entry points, selected by the trusted desktop launcher.
-keep class io.aequicor.heartbeat.feature.worktreemode.impl.data.worker.WorktreeBuildWorkerKt {
    public static void main(java.lang.String[]);
}
-keep class io.aequicor.heartbeat.feature.plantumlsupport.impl.data.worker.PlantUmlWorkerKt {
    public static void main(java.lang.String[]);
}
-keep class io.aequicor.heartbeat.feature.harness.impl.data.script.worker.HarnessCompilerWorkerKt {
    public static void main(java.lang.String[]);
}
-keep class io.aequicor.heartbeat.feature.chromiumbridge.impl.data.host.ChromiumHostMainKt {
    public static void main(java.lang.String[]);
}

# The packaged compiler probe uses a JDK-only reflective boundary from the desktop launcher.
-keep class io.aequicor.heartbeat.feature.harness.impl.data.script.HarnessHostProbe {
    public static java.util.concurrent.CompletionStage run(java.lang.String,java.lang.String);
    public static java.util.concurrent.CompletionStage run(java.lang.String,java.lang.String,java.lang.String[]);
}

# The headless smoke of the shrunk train (verifyShrunkTrain) reads the health of the bundles through this function.
-keep class io.aequicor.heartbeat.platform.dibundle.BundleHealthProbeKt {
    public static java.util.Map bundleHealth(io.aequicor.heartbeat.platform.dibundle.HeartbeatGraph);
}

# The isolated worker loads its feature classes by name after the pinned engine JAR has been prepared.
-keep class io.aequicor.heartbeat.feature.plantumlsupport.impl.** { *; }

# Harness scripts are compiled at run time against the public API by its source names, and their compiler worker is
# loaded by name.
-keep class io.aequicor.heartbeat.feature.harness.api.** { *; }
-keep class io.aequicor.heartbeat.feature.harness.impl.data.script.** { *; }
-keep class io.aequicor.heartbeat.feature.aiengine.facade.api.** { *; }
-keep class io.aequicor.heartbeat.feature.scheduler.api.** { *; }

# --- Runtime-loaded bundles ---------------------------------------------------------------------------------------

# Entries are found by ServiceLoader and their accessor interfaces are called reflectively by the bundle host (the
# foundation's own import interface is proxied), so neither may be shrunk or renamed.
-keep class * implements io.aequicor.heartbeat.core.bundles.BundleEntry { public <init>(); }
-keep interface io.aequicor.heartbeat.bundles.** { *; }
-keep interface io.aequicor.heartbeat.platform.dibundle.FoundationBundleImports { *; }
-keep interface io.aequicor.heartbeat.platform.dibundle.FoundationProfileBundleImports { *; }
-keep interface io.aequicor.heartbeat.core.bundles.FoundationAppBindings { *; }
-keep interface io.aequicor.heartbeat.core.bundles.FoundationProfileBindings { *; }
-keep interface io.aequicor.heartbeat.core.bundles.FoundationProfileEngineBindings { *; }
-keep interface io.aequicor.heartbeat.core.bundles.FoundationProfileAttachmentBindings { *; }
-keep interface io.aequicor.heartbeat.core.bundles.FoundationProfileRuntimeBindings { *; }
-keep interface io.aequicor.heartbeat.core.bundles.FoundationProfileModelBindings { *; }
-keep interface io.aequicor.heartbeat.core.bundles.ConfigSectionPart { *; }
-keep interface io.aequicor.heartbeat.core.bundles.FoundationEngineSets { *; }
-keep interface io.aequicor.heartbeat.core.bundles.FoundationAgentRuntimeSets { *; }
-keep interface io.aequicor.heartbeat.core.profilefacade.ProfileStartupAccess { *; }
-adaptresourcefilecontents META-INF/services/io.aequicor.heartbeat.core.bundles.BundleEntry

# SLF4J discovers the application logging backend through its service descriptor.
-keep class io.aequicor.heartbeat.core.logging.Slf4jProvider { public <init>(); }
-adaptresourcefilecontents META-INF/services/org.slf4j.spi.SLF4JServiceProvider

# --- Serialization ------------------------------------------------------------------------------------------------

# kotlinx.serialization finds serializers by name: through the companion of a @Serializable class, the INSTANCE of an
# object or a nested class named like its serializer. Class names are the wire format of polymorphic data that is
# written without @SerialName, so they stay.
-keepattributes *Annotation*,InnerClasses,Signature,Exceptions,EnclosingMethod
-keepnames @kotlinx.serialization.Serializable class io.aequicor.heartbeat.**
-keepnames class io.aequicor.heartbeat.**$$serializer
-if @kotlinx.serialization.Serializable class io.aequicor.heartbeat.**
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class io.aequicor.heartbeat.** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class io.aequicor.heartbeat.** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Enums and Room -----------------------------------------------------------------------------------------------

# Enum.valueOf and EnumSet discover values() reflectively. Keep them public and unoptimized: privatizing values()
# also breaks Class.getEnumConstants().
-keepclassmembers enum io.aequicor.heartbeat.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Room instantiates the generated database and its constructor by name.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class * implements androidx.room.RoomDatabaseConstructor { *; }
