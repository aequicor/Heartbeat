# The packaged workers are headless entry points, selected by the trusted desktop launcher.
-keep class io.aequicor.heartbeat.feature.worktreemode.impl.data.worker.WorktreeBuildWorkerKt {
    public static void main(java.lang.String[]);
}
-keep class io.aequicor.heartbeat.feature.plantumlsupport.impl.data.worker.PlantUmlWorkerKt {
    public static void main(java.lang.String[]);
}

# The packaged compiler probe uses a JDK-only reflective boundary from the desktop launcher.
-keep class io.aequicor.heartbeat.feature.harness.impl.data.script.HarnessHostProbe {
    public static java.util.concurrent.CompletionStage run(java.lang.String,java.lang.String);
}
