package io.aequicor.heartbeat.feature.projectconfig.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.desktopdialogs.DesktopFileDialogs
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigLayout
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigDialogs

/** Native open and save panels limited to `.hbai` files. */
@ContributesBinding(AppScope::class)
@Inject
internal class DesktopConfigDialogs(private val dialogs: DesktopFileDialogs) : ConfigDialogs {
    private val log = Log.tag("ConfigDialogs")

    override val isAvailable: Boolean = true

    private companion object {
        const val SUFFIX = ".${ConfigLayout.BUNDLE_EXTENSION}"
    }

    override suspend fun pickBundle(): String? =
        dialogs.pickFiles(extensions = listOf(ConfigLayout.BUNDLE_EXTENSION)).firstOrNull()
            .also { log.i { "Bundle to open chosen=${it != null}" } }

    override suspend fun pickBundleTarget(suggestedName: String): String? =
        dialogs.pickSaveLocation(suggestedName = suggestedName, extensions = listOf(ConfigLayout.BUNDLE_EXTENSION))
            ?.let { path -> if (path.endsWith(SUFFIX)) path else path + SUFFIX }
            .also { log.i { "Bundle target chosen=${it != null}" } }
}
