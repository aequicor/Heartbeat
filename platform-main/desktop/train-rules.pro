# Rules of the bundle train that no SDK keep rule derives (see generateTrainSdkRules). Used when the train is shrunk
# (-Pheartbeat.trainShrink=shrink); third-party libraries are never processed.

# Entry points of the bundles: found by ServiceLoader; their import/export interfaces are proxied by the bundle host.
-keep class * implements io.aequicor.heartbeat.core.bundles.BundleEntry { public <init>(); }
-keep interface io.aequicor.heartbeat.bundles.** { *; }
-adaptresourcefilecontents META-INF/services/io.aequicor.heartbeat.core.bundles.BundleEntry
