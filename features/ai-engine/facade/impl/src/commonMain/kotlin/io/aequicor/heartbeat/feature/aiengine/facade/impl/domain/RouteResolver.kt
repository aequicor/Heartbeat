package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity

/** A route that passed every gate; [route] fixes the checked source revision. */
data class ResolvedRoute(
    val registration: EngineRegistration,
    val binding: EngineBinding,
    val source: AuthSource,
    val context: EngineContext,
) {
    /** Credential and workspace route of the execution. */
    val route: ExecutionRoute =
        ExecutionRoute(context.engine, binding.id, source.info.id, source.info.revision, context.workspace)

    /** Pooled runtime identity: engine and source, across workspaces. */
    val identity: RuntimeIdentity = RuntimeIdentity(context.engine, source.info.id, source.info.revision)
}

/**
 * Resolves explicit routes: engine toggles and platform, an enabled binding of that engine, a present source and
 * its ownership/compatibility. There is never a fallback to another binding or source.
 */
class RouteResolver(
    private val registry: EngineRegistry,
    private val gate: EngineGate,
    private val bindings: EngineBindingsService,
) {
    private val log = Log.tag("RouteResolver")

    /** Checks the route of [binding]; [model] and [workspace] narrow the adapter's compatibility check. */
    suspend fun resolve(
        engine: EngineId,
        binding: EngineBindingId,
        workspace: WorkspaceRef? = null,
        model: ModelId? = null,
    ): ResolvedRoute {
        val registration = gate.requireEnabled(engine)
        if (!registry.supportsPlatform(registration)) fail(EngineUnavailable)
        val saved = bindings.requireBinding(binding, engine)
        if (!saved.isEnabled) {
            log.w { "binding disabled binding=${binding.value}" }
            fail(OperationNotAllowed)
        }
        val source = bindings.requireSource(saved.authSource)
        val context = EngineContext(engine, binding, workspace, model)
        bindings.requireAccepted(registration, source, context)
        return ResolvedRoute(registration, saved, source, context)
    }

    /**
     * Rechecks a fixed [route] before a subsequent turn. Besides every gate of [resolve], the binding must still
     * point at the same source with the same revision; an unknown revision cannot prove a change.
     */
    suspend fun recheck(route: ExecutionRoute, model: ModelId?): ResolvedRoute {
        val current = resolve(route.engine, route.binding, route.workspace, model)
        if (current.source.info.id != route.authSource || current.source.info.revision != route.revision) {
            log.w { "source changed binding=${route.binding.value} source=${route.authSource.value}" }
            fail(authFailure(AuthFailureReason.SourceChanged, route.authSource))
        }
        return current
    }
}
