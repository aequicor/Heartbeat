package io.aequicor.heartbeat.feature.aiengine.facade.api.spi

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthContextKey
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthOwnerId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthenticatorId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.isVisibleTo
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionMethod
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef

/** Engine-specific authentication context. Only the factory decides which parts contribute to its context key. */
public data class EngineContext(
    val engine: EngineId,
    val binding: EngineBindingId,
    val workspace: WorkspaceRef? = null,
    val model: ModelId? = null,
)

/**
 * Adapter registration, imported only by engine modules, facade implementation and the application bundle.
 * Constructing a registration or reading its descriptor performs no IO. The bundle contributes registrations
 * through DI; it is the only module allowed to depend on adapter implementations.
 */
public class EngineRegistration(
    public val descriptor: EngineDescriptor,
    public val authOwner: AuthOwnerId,
    public val factory: Lazy<EngineFactory>,
    public val sessionSources: List<EngineSessionSource> = emptyList(),
    public val authenticators: Set<AuthenticatorId> = emptySet(),
) {
    init {
        require(sessionSources.all { it.source.engine == descriptor.id }) { "Foreign engine session source" }
        require(
            sessionSources.map { it.source.id }.distinct().size == sessionSources.size,
        ) { "Duplicate session source" }
        require(
            descriptor.connectionMethods.filterIsInstance<ConnectionMethod.CliLogin>().all { it.owner == authOwner },
        ) { "CLI login method of a foreign owner" }
    }

    /** Ownership is checked first, so a factory can narrow compatibility but never share a foreign CLI login. */
    public fun accepts(source: AuthSource, context: EngineContext): Boolean =
        context.engine == descriptor.id && source.isVisibleTo(authOwner) && factory.value.accepts(source, context)
}

/** Adapter factory. Facade applies ownership/toggle gates before delegating. No method silently changes sources. */
public interface EngineFactory {
    /** Explicit installation probe. */
    public suspend fun checkRequirements(): EngineAvailability

    /** Pure narrowing check against endpoint/provider/credential kind; must not read credential values. */
    public fun accepts(source: AuthSource, context: EngineContext): Boolean

    /** Non-sensitive key for significant context parts; never raw paths, accounts, keys or tokens. */
    public fun authContext(context: EngineContext): AuthContextKey

    /** Lists models visible through the exact source/context; source material is resolved inside trusted adapters. */
    public suspend fun discoverModels(source: AuthSource, context: EngineContext): List<ModelInfo>

    /**
     * Creates a runtime after route checks. Pool by engine/source within a profile, across all workspaces.
     * A revision change retires the old runtime before replacement, never starts two credential rotators.
     */
    public suspend fun createRuntime(identity: RuntimeIdentity): EngineRuntime
}

/**
 * Native process/client lifetime, independent of UI handles. Accepted operations and their observation outlive
 * machine-state effects. Runtime transport must serialize writes or reject externally busy native sessions.
 * Closing an individual ActiveSession releases a lease; only the owner closes the pooled runtime.
 */
public interface EngineRuntime {
    /** Fixed credential identity; workspace and model belong to individual sessions. */
    public val identity: RuntimeIdentity

    /** Runtime operations use the same capability contracts as facade handles. */
    public val features: EngineFeatures

    /** Releases runtime resources at profile shutdown or idle eviction; reconciles active turns before shutdown. */
    public suspend fun close()
}

/** Identity of one profile-owned process/client; revision is checked on every attachment and turn. */
public data class RuntimeIdentity(val engine: EngineId, val source: AuthSourceId, val revision: AuthRevision)

/**
 * Validates the complete registration set before exposing any descriptors or constructing factories.
 * CLI owner namespaces must be unique, otherwise one engine could accept another engine's login.
 */
public fun validateEngineRegistrations(registrations: Collection<EngineRegistration>) {
    require(registrations.map { it.descriptor.id }.distinct().size == registrations.size) { "Duplicate engine id" }
    require(registrations.map { it.authOwner }.distinct().size == registrations.size) { "Duplicate auth owner" }
}
