package io.aequicor.heartbeat.feature.projectconfig.impl.data

import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigDialogs

/** Platforms without file dialogs for bundles (Android, iOS). */
internal object UnsupportedConfigDialogs : ConfigDialogs {
    override val isAvailable: Boolean = false

    override suspend fun pickBundle(): String? = null

    override suspend fun pickBundleTarget(suggestedName: String): String? = null
}
