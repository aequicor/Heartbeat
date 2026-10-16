package io.aequicor.heartbeat.feature.aiengine.controlpanel.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigCapability

/** Refreshes an already shown inventory after a successful change; opening a project also lists its resources. */
internal fun EngineControlState.Active.resourceRefresh(result: ControlResult): ControlOperation.ListResources? {
    val operation = pending ?: return null
    val config = snapshot?.configuration?.takeIf { it.isEnabled }?.engine(operation.engine) ?: return null
    if (ConfigCapability.Resources !in config.schema.capabilities) return null
    return when (operation) {
        is ControlOperation.OpenProject, is ControlOperation.SaveProject ->
            (result as? ControlResult.Project)?.project?.let { ControlOperation.ListResources(it.engine, it.workspace) }

        is ControlOperation.SaveProfile, is ControlOperation.Import, is ControlOperation.SetTrust ->
            resources?.takeIf { it.engine == operation.engine }?.let {
                ControlOperation.ListResources(
                    it.engine,
                    it.workspace,
                )
            }

        is ControlOperation.ListResources, is ControlOperation.PreviewImport, is ControlOperation.Manage -> null
    }
}
