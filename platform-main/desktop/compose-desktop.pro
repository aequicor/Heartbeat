# The packaged workers are headless entry points, selected by the trusted desktop launcher.
-keep class io.aequicor.heartbeat.feature.worktreemode.impl.data.worker.WorktreeBuildWorkerKt {
    public static void main(java.lang.String[]);
}
-keep class io.aequicor.heartbeat.feature.plantumlsupport.impl.data.worker.PlantUmlWorkerKt {
    public static void main(java.lang.String[]);
}
