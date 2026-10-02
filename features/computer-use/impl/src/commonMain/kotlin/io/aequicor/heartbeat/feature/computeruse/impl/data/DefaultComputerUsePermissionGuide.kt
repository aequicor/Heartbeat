package io.aequicor.heartbeat.feature.computeruse.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUsePermission
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUsePermissionGuide
import io.aequicor.heartbeat.feature.computeruse.impl.domain.PermissionGuidePanel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * The one guide panel of the application. Profiles show and hide it as a [PermissionGuidePanel]; the desktop host
 * observes it as a [ComputerUsePermissionGuide] and reports the user closing the panel.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<ComputerUsePermissionGuide>())
@ContributesBinding(AppScope::class, binding = binding<PermissionGuidePanel>())
@Inject
internal class DefaultComputerUsePermissionGuide :
    ComputerUsePermissionGuide,
    PermissionGuidePanel {
    private val log = Log.tag("ComputerUsePermissionGuide")
    private val current = MutableStateFlow<ComputerUsePermission?>(null)

    override val guide: StateFlow<ComputerUsePermission?> = current.asStateFlow()

    override fun show(permission: ComputerUsePermission) {
        val previous = current.getAndUpdate { permission }
        log.i { "permission guide shown permission=$permission replaced=${previous ?: "none"}" }
    }

    override fun hide(permission: ComputerUsePermission) {
        if (current.compareAndSet(permission, null)) log.i { "permission guide hidden permission=$permission" }
    }

    override fun dismiss() {
        val previous = current.getAndUpdate { null }
        log.i { "permission guide closed by the user permission=${previous ?: "none"}" }
    }
}
