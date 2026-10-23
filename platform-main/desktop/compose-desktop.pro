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

# The isolated worker loads its feature classes by name after the pinned engine JAR has been prepared.
-keep class io.aequicor.heartbeat.feature.plantumlsupport.impl.** { *; }

# SLF4J discovers the application logging backend through its service descriptor.
-keep class io.aequicor.heartbeat.core.logging.Slf4jProvider { public <init>(); }

# Runtime-loaded bundles: entries are found by ServiceLoader and their accessor interfaces are called reflectively
# by the bundle host (the foundation's own import interface is proxied), so neither may be shrunk or renamed.
-keep class * implements io.aequicor.heartbeat.core.bundles.BundleEntry { public <init>(); }
-keep interface io.aequicor.heartbeat.bundles.** { *; }
-keep interface io.aequicor.heartbeat.platform.dibundle.FoundationBundleImports { *; }
-keep interface io.aequicor.heartbeat.platform.dibundle.FoundationProfileBundleImports { *; }
-keep interface io.aequicor.heartbeat.core.bundles.FoundationAppBindings { *; }
-keep interface io.aequicor.heartbeat.core.bundles.FoundationProfileBindings { *; }
-keep interface io.aequicor.heartbeat.core.bundles.FoundationProfileEngineBindings { *; }
-keep interface io.aequicor.heartbeat.core.bundles.FoundationProfileRuntimeBindings { *; }
-keep interface io.aequicor.heartbeat.core.bundles.FoundationEngineSets { *; }
-keep interface io.aequicor.heartbeat.core.bundles.FoundationAgentRuntimeSets { *; }
-keep interface io.aequicor.heartbeat.core.profilefacade.ProfileStartupAccess { *; }
-adaptresourcefilecontents META-INF/services/io.aequicor.heartbeat.core.bundles.BundleEntry
