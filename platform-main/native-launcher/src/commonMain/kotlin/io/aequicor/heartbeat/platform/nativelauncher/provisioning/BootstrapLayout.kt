package io.aequicor.heartbeat.platform.nativelauncher.provisioning

/** Paths relative to an activated image, including the macOS bundle suffix needed by Launch Services and updates. */
data class BootstrapLayout(val runtime: String, val seed: String, val executable: String) {
    companion object {
        fun forPlatform(os: String, channel: String): BootstrapLayout {
            val name = when (channel) {
                "release" -> "Heartbeat"
                "beta" -> "HeartbeatBeta"
                "dev" -> "HeartbeatDev"
                else -> error("Unsupported bootstrap channel: $channel")
            }
            return when (os) {
                "windows" -> BootstrapLayout("runtime", "app/resources/seed", "$name.exe")

                "linux" -> BootstrapLayout("lib/runtime", "lib/app/resources/seed", "bin/$name")

                "macos" -> BootstrapLayout(
                    "$name.app/Contents/runtime",
                    "$name.app/Contents/app/resources/seed",
                    "$name.app/Contents/MacOS/$name",
                )

                else -> error("Unsupported bootstrap platform: $os")
            }
        }
    }
}
