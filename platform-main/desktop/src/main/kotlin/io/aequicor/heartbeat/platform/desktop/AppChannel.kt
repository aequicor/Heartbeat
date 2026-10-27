package io.aequicor.heartbeat.platform.desktop

/**
 * The channel this launch shows itself as: its name and badged icon. A dev or beta build (`heartbeat.channel`) is that
 * channel for good; a release build becomes [Beta] while it runs a bundle train from the beta channel, which the
 * platform launcher states as `heartbeat.train.channel` (a release train clears it, so the app turns back by itself).
 */
internal enum class AppChannel(val id: String?, val title: String) {
    Release(null, "Heartbeat"),
    Beta("beta", "Heartbeat Beta"),
    Dev("dev", "Heartbeat Dev"),
    ;

    companion object {
        fun resolve(
            buildChannel: String? = System.getProperty("heartbeat.channel"),
            trainChannel: String? = System.getProperty("heartbeat.train.channel"),
        ): AppChannel = when {
            buildChannel == "dev" -> Dev
            buildChannel == "beta" || trainChannel == "beta" -> Beta
            else -> Release
        }

        /** The channel of this launch, read once: the properties are fixed before the application starts. */
        val current: AppChannel by lazy { resolve() }
    }
}
