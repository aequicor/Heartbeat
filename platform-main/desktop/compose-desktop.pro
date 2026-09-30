# The packaged worker is a second headless entry point, selected by the trusted desktop launcher.
-keep class io.aequicor.heartbeat.feature.worktreemode.impl.data.worker.WorktreeBuildWorkerKt {
    public static void main(java.lang.String[]);
}
