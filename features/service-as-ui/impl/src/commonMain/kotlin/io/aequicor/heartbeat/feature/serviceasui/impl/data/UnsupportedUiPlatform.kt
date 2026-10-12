package io.aequicor.heartbeat.feature.serviceasui.impl.data

import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiPlatform
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiPlatformResult

/** Files of surfaces where the platform offers no dialogs for them (Android, iOS): every operation is unsupported. */
internal object UnsupportedUiPlatform : UiPlatform {
    override suspend fun save(text: String, name: String, extensions: List<String>): UiPlatformResult =
        UiPlatformResult.Unsupported

    override suspend fun reveal(path: String): UiPlatformResult = UiPlatformResult.Unsupported

    override suspend fun pick(isFolder: Boolean, extensions: List<String>): UiPlatformResult =
        UiPlatformResult.Unsupported
}
