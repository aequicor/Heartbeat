package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.withoutLabel
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePreparation
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

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
    private val workspaces: io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces? = null,
) {
    private val log = Log.tag("RouteResolver")

    /** Checks the route of [binding]; [model] and [workspace] narrow the adapter's compatibility check. */
    suspend fun resolve(
        engine: EngineId,
        binding: EngineBindingId,
        workspace: WorkspaceRef? = null,
        model: ModelId? = null,
    ): ResolvedRoute {
        val resolved = checkedRoute(engine, binding, workspace, model)
        // Adapters without persistent route state learn the route again after a restart; bind is idempotent.
        resolved.registration.factory.value.bind(resolved.binding.id, resolved.source)
        return resolved
    }

    /** Reads a session route without restoring an adapter binding before compatibility admission. */
    suspend fun resolveSession(
        engine: EngineId,
        binding: EngineBindingId,
        workspace: WorkspaceRef? = null,
        model: ModelId? = null,
    ): ResolvedRoute = checkedRoute(engine, binding, workspace, model)

    /** Revalidates the complete source snapshot without adapter writes, including unknown auth revisions. */
    suspend fun recheckSession(expected: ResolvedRoute): ResolvedRoute {
        val context = expected.context
        val current = checkedRoute(context.engine, context.binding, context.workspace, context.model)
        if (current.source.withoutLabel() != expected.source.withoutLabel()) {
            fail(authFailure(AuthFailureReason.SourceChanged, expected.source.info.id))
        }
        return current
    }

    /** Restores a canonical route only after the selected candidate passed baseline admission. */
    suspend fun bindAdmitted(expected: ResolvedRoute) {
        val current = recheckSession(expected)
        current.registration.factory.value.bind(current.binding.id, current.source)
    }

    /** Checks the complete strict source context without mutating adapter routes or opening a runtime. */
    suspend fun resolveIsolated(target: EngineTarget, expectedSource: AuthSource): ResolvedRoute {
        val current = checkedRoute(target.engine, target.binding, model = target.model)
        if (current.source.withoutLabel() != expectedSource.withoutLabel()) {
            fail(authFailure(AuthFailureReason.SourceChanged, expectedSource.info.id))
        }
        return current
    }

    private suspend fun checkedRoute(
        engine: EngineId,
        binding: EngineBindingId,
        workspace: WorkspaceRef? = null,
        model: ModelId? = null,
    ): ResolvedRoute {
        val registration = gate.requireEnabled(engine)
        val virtual = workspace?.let { workspaces?.virtual(it) }
        check(workspace?.value?.startsWith("sandbox-") != true || virtual != null) { "VirtualWorkspaceUnavailable" }
        if (virtual != null) {
            if (!registration.descriptor.isVirtualWorkspaceSupported) fail(OperationNotAllowed)
            virtual.revision()
        }
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

    /** Model catalog revision of [engine]'s adapter, or null for an unknown engine. */
    fun modelCatalogRevision(engine: EngineId): Int? = registry.find(engine)?.modelCatalogRevision

    /** Waits passively for first use to prepare the runtime; cancelling the profile releases the waiter. */
    suspend fun awaitRuntimePrepared(engine: EngineId) {
        val factory = registry.find(engine)?.factory?.value ?: fail(EngineUnavailable)
        factory.preparation.first { it == EnginePreparation.Ready }
    }

    /** Saved bindings and their changes; used to hide data of removed bindings. */
    val saved: StateFlow<List<EngineBinding>> get() = bindings.state

    /** Currently saved bindings (enabled or not), read consistently with binding mutations. */
    suspend fun savedNow(): List<EngineBinding> = bindings.saved()

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
