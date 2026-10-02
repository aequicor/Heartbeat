package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.Compatibility
import io.aequicor.heartbeat.feature.aiengine.facade.api.DisabledReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineActionKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCommand
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineEnablement
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagement
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagementState
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallationState
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchProblem
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchState
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginMethod
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginState
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RuntimeSummary
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.actions
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineLaunchConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineManager
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ReleaseFeeds
import io.aequicor.heartbeat.feature.aiengine.facade.api.validateLaunchSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Developer flags engine management reads. */
interface EngineFlags {
    /** Whether engine management is on, and its changes. */
    fun management(): Flow<Boolean>

    /** The catalog flag and the engine's own flag of [descriptor], and their changes. */
    fun developer(descriptor: EngineDescriptor): Flow<DeveloperFlags>
}

/** Developer flags of one engine. */
data class DeveloperFlags(val isCatalogOn: Boolean, val isEngineOn: Boolean)

/**
 * [EngineManagement] of the profile. The state follows flags, profile choices, managed copies, the runtime pool and
 * open handles; inspections and update checks are kept in memory with their time. A reconciler stops the idle
 * runtimes of engines that are switched off, exited or stale (started before their launch context changed), so new
 * sessions run with the current settings; a turn in flight is never interrupted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LongParameterList") // Coordinates every engine subsystem the panel manages; each one is a separate port.
class EngineManagementService(
    private val registry: EngineRegistry,
    private val flags: EngineFlags,
    private val preferences: EnginePreferences,
    private val catalog: EngineCatalog,
    private val bindings: StateFlow<List<EngineBinding>>,
    private val runtimes: EngineRuntimes,
    private val installs: ManagedInstallStore,
    private val launch: EngineLaunchConfig,
    private val feeds: ReleaseFeeds,
    connections: CliConnections,
    private val context: FacadeContext,
) : EngineManagement {
    private val log = Log.tag("EngineManagement")
    private val inspections = MutableStateFlow(emptyMap<EngineId, Inspection>())
    private val jobs = EngineJobs(
        registry,
        installs,
        launch,
        feeds,
        runtimes,
        context,
        object : JobHooks {
            override suspend fun changed(engine: EngineId) = reinspect(engine)

            override suspend fun signedIn(engine: EngineId) = connections.refresh(engine)
        },
    )

    override val state: StateFlow<EngineManagementState> = flags.management()
        .flatMapLatest { isOn -> if (isOn) managed() else flowOf(EngineManagementState.Off) }
        .stateIn(context.scope, SharingStarted.Eagerly, EngineManagementState.Off)

    init {
        context.scope.launch { loadInstalls() }
        context.scope.launch { state.collect(::reconcile) }
    }

    override suspend fun execute(engine: EngineId, command: EngineCommand) {
        log.i { "execute engine=${engine.value} command=${command::class.simpleName.orEmpty()}" }
        val current = state.value.takeIf { it.isEnabled }?.engines?.firstOrNull { it.descriptor.id == engine }
            ?: refuse("engine management is off or the engine is unknown engine=${engine.value}")
        when (command) {
            is EngineCommand.SetEnabled -> setEnabled(engine, command.isEnabled)
            EngineCommand.Inspect -> inspect(current.requireAction(EngineActionKind.Inspect))
            EngineCommand.CheckForUpdates -> checkForUpdates(current.requireAction(EngineActionKind.CheckForUpdates))
            is EngineCommand.Configure -> configure(current.requireAction(EngineActionKind.Configure), command.settings)
            EngineCommand.Restart -> restart(current.requireRestartable().descriptor.id)
            is EngineCommand.Start -> jobs.start(engine, current.requireStart(command.action))
            EngineCommand.Cancel -> jobs.cancel(engine)
            EngineCommand.Dismiss -> jobs.dismiss(engine)
            is EngineCommand.AnswerLogin -> jobs.answer(engine, command.code)
        }
    }

    private suspend fun setEnabled(engine: EngineId, isEnabled: Boolean) {
        preferences.update { chosen ->
            chosen.copy(disabled = if (isEnabled) chosen.disabled - engine else chosen.disabled + engine)
        }
        log.i { "engine switched engine=${engine.value} enabled=$isEnabled" }
        if (!isEnabled) runtimes.retire(engine)
    }

    private suspend fun inspect(engine: ManagedEngine) {
        val id = engine.descriptor.id
        runtimes.prune()
        val manager = registry.require(id).manager?.value
        val inspected = if (manager == null) builtIn() else inspectWith(manager, launch.context(id))
        if (engine.enablement.isEnabled) refreshAvailability(id)
        inspections.update { all ->
            val previous = all[id]
            val installation = inspected.installation.copy(
                latest = previous?.installation?.latest,
                compatibility = inspected.installation.compatibility,
            )
            all + (id to inspected.copy(installation = installation))
        }
        log.i { "engine inspected engine=${id.value} source=${inspected.installation.current?.source ?: "unknown"}" }
    }

    /** Inspects again after a job; the finished job may still be shown, so the action check is skipped. */
    private suspend fun reinspect(engine: EngineId) {
        val current = state.value.engines.firstOrNull { it.descriptor.id == engine } ?: return
        if (current.enablement.reasons.all { it == DisabledReason.DisabledByUser }) inspect(current)
    }

    private suspend fun inspectWith(manager: EngineManager, launchContext: LaunchContext): Inspection {
        val now = context.clock.now()
        val installation = bounded("inspect") { manager.inspect(launchContext) }
        val login = bounded("login status") { manager.loginStatus(launchContext) }
        val warnings = bounded("launch check") { manager.check(launchContext.settings) }
        val bundled = bounded("bundled version") { manager.bundledVersion() }
        val current = installation.value
        val compatibility = current?.version?.let(manager::compatibility)
        return Inspection(
            InstallationState(
                current = current,
                bundledVersion = bundled.value,
                compatibility = compatibility ?: Compatibility.Unknown,
                checkedAt = now,
                failure = installation.failure ?: login.failure,
            ),
            login.value ?: LoginState.Unknown,
            warnings.value.orEmpty(),
        )
    }

    private fun builtIn() = Inspection(
        InstallationState(current = Installation(InstallSource.BuiltIn), checkedAt = context.clock.now()),
        LoginState.NotApplicable,
        emptyList(),
    )

    private suspend fun checkForUpdates(engine: ManagedEngine) {
        val id = engine.descriptor.id
        val manager = registry.require(id).manager?.value ?: refuse("no installation to update engine=${id.value}")
        val release = bounded("release lookup") { manager.resolveRelease(feeds) }
        inspections.update { all ->
            val previous = all[id] ?: Inspection()
            val installation = previous.installation.copy(
                latest = release.value?.version ?: previous.installation.latest,
                checkedAt = context.clock.now(),
                failure = release.failure,
            )
            all + (id to previous.copy(installation = installation))
        }
        val latest = release.value?.version ?: "none"
        log.i { "release checked engine=${id.value} latest=$latest failed=${release.failure != null}" }
    }

    private suspend fun configure(engine: ManagedEngine, settings: LaunchSettings) {
        val id = engine.descriptor.id
        val problems = validateLaunchSettings(settings, engine.spec.launch, registry.platform)
        if (problems.isNotEmpty()) {
            log.w { "launch settings rejected engine=${id.value} problems=${problems.size}" }
            throw EngineException(EngineFailure.Request(RequestFailureReason.Invalid))
        }
        preferences.update { chosen -> chosen.copy(launch = chosen.launch + (id to settings)) }
        log.i { "launch settings saved engine=${id.value} isDefault=${settings.isDefault}" }
        val manager = registry.require(id).manager?.value
        val warnings = if (manager == null) emptyList() else bounded("launch check") { manager.check(settings) }.value
        inspections.update { all -> all + (id to (all[id] ?: Inspection()).copy(warnings = warnings.orEmpty())) }
        runtimes.retire(id)
    }

    private suspend fun restart(engine: EngineId) {
        val outcome = runtimes.retire(engine)
        if (outcome.busy > 0) {
            log.w { "restart left busy runtimes engine=${engine.value} busy=${outcome.busy}" }
            throw EngineException(EngineFailure.Session(SessionFailureReason.Busy))
        }
    }

    private suspend fun refreshAvailability(engine: EngineId) {
        try {
            catalog.refresh(engine)
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineException) {
            log.w(e) { "availability probe failed engine=${engine.value}" }
        }
    }

    /** Reads Heartbeat's copies once engine management is on; while it is off, their files are left untouched. */
    private suspend fun loadInstalls() {
        flags.management().first { it }
        try {
            installs.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ManagementException) {
            log.w(e) { "managed copies could not be read" }
        }
    }

    private suspend fun reconcile(current: EngineManagementState) {
        if (!current.isEnabled) return
        current.engines.filter { it.needsRetirement() }.forEach { engine ->
            val id = engine.descriptor.id
            log.i { "retire idle runtimes engine=${id.value} enabled=${engine.enablement.isEnabled}" }
            try {
                runtimes.retire(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: EngineException) {
                log.w(e) { "idle runtimes could not be retired engine=${id.value}" }
            } catch (e: Exception) {
                // Reconciliation keeps running for the profile whatever one runtime does when it stops.
                log.e(e) { "idle runtimes failed to stop engine=${id.value}" }
            }
        }
    }

    private fun managed(): Flow<EngineManagementState> {
        val developer = if (registry.all.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(registry.all.map { registration -> flags.developer(registration.descriptor) }) { values ->
                registry.all.map { it.descriptor.id }.zip(values).toMap()
            }
        }
        val local = combine(
            developer,
            preferences.observe(),
            installs.state,
            runtimes.entries,
            runtimes.sessions,
        ) { flagged, chosen, managed, pooled, sessions -> Inputs(flagged, chosen, managed, pooled, sessions) }
        return combine(
            local,
            bindings,
            catalog.state,
            inspections,
            jobs.jobs,
        ) { inputs, saved, listed, inspected, run ->
            EngineManagementState(
                isEnabled = true,
                platform = registry.platform,
                engines = registry.all.map { registration ->
                    val id = registration.descriptor.id
                    engine(registration, inputs, saved, listed, inspected[id]).copy(job = run[id])
                },
            )
        }
    }

    private fun engine(
        registration: EngineRegistration,
        inputs: Inputs,
        saved: List<EngineBinding>,
        listed: List<EngineInfo>,
        inspection: Inspection?,
    ): ManagedEngine {
        val descriptor = registration.descriptor
        val id = descriptor.id
        val isSupported = registry.supportsPlatform(registration)
        val flagged = inputs.developer[id] ?: DeveloperFlags(isCatalogOn = false, isEngineOn = false)
        val reasons = buildSet {
            if (!flagged.isCatalogOn) add(DisabledReason.CatalogFlagOff)
            if (!flagged.isEngineOn) add(DisabledReason.EngineFlagOff)
            if (id in inputs.preferences.disabled) {
                add(DisabledReason.DisabledByUser)
            }
            if (!isSupported) add(DisabledReason.UnsupportedPlatform)
        }
        val settings = inputs.preferences.launchOf(id)
        val managed = inputs.managed[id]
        return ManagedEngine(
            descriptor = descriptor,
            spec = registration.management,
            enablement = EngineEnablement(id !in inputs.preferences.disabled, reasons),
            availability = listed.firstOrNull { it.descriptor.id == id }?.availability
                ?: if (isSupported) EngineAvailability.Unknown else EngineAvailability.UnsupportedPlatform,
            connections = saved.count { it.engine == id },
            installation = installationOf(registration, inspection, managed),
            login = inspection?.login ?: defaultLogin(registration),
            launch = LaunchState(settings, inspection?.warnings.orEmpty()),
            runtime = runtimeOf(id, inputs, LaunchContext(settings, managed)),
        )
    }

    private fun installationOf(
        registration: EngineRegistration,
        inspection: Inspection?,
        managed: ManagedInstall?,
    ): InstallationState {
        val inspected = inspection?.installation ?: InstallationState()
        val current = inspected.current ?: Installation(InstallSource.BuiltIn).takeIf {
            registration.management.install == InstallSupport.BuiltIn
        }
        return inspected.copy(current = current, managed = managed)
    }

    private fun runtimeOf(engine: EngineId, inputs: Inputs, expected: LaunchContext): RuntimeSummary {
        val pooled = inputs.pooled.filter { it.engine == engine }
        val counts = inputs.sessions[engine]
        return RuntimeSummary(
            runtimes = pooled.size,
            openSessions = counts?.open ?: 0,
            activeTurns = counts?.activeTurns ?: 0,
            isStale = pooled.any { !it.isClosed && it.launch != expected },
            hasExited = pooled.any { it.isClosed },
            startedAt = pooled.minOfOrNull { it.startedAt },
        )
    }

    private fun defaultLogin(registration: EngineRegistration): LoginState = when (registration.management.login) {
        LoginSupport.Cli, LoginSupport.CliWithDeviceCode -> LoginState.Unknown
        LoginSupport.None, LoginSupport.Connections -> LoginState.NotApplicable
    }

    /** Runs one adapter call off the main thread within a time limit; failures become recorded failures. */
    private suspend fun <T> bounded(what: String, block: suspend () -> T): Bounded<T> = try {
        withTimeoutOrNull(ADAPTER_TIMEOUT_MILLIS) { Bounded<T>(withContext(context.io) { block() }) }
            ?: Bounded<T>(failure = ManagementFailure.Engine(EngineFailure.Transport(TransportFailureReason.Timeout)))
                .also { log.w { "$what timed out" } }
    } catch (e: CancellationException) {
        throw e
    } catch (e: EngineException) {
        log.w(e) { "$what failed" }
        Bounded(failure = ManagementFailure.Engine(e.failure))
    } catch (e: ManagementException) {
        log.w(e) { "$what failed" }
        Bounded(failure = e.failure)
    } catch (e: Exception) {
        log.e(e) { "$what crashed" }
        Bounded(failure = ManagementFailure.Engine(EngineFailure.Unknown()))
    }

    private fun ManagedEngine.requireStart(action: EngineAction): EngineAction {
        val kind = when (action) {
            EngineAction.Install -> EngineActionKind.Install
            EngineAction.Update -> EngineActionKind.Update
            EngineAction.Uninstall -> EngineActionKind.Uninstall
            is EngineAction.Login -> EngineActionKind.Login
            EngineAction.Logout -> EngineActionKind.Logout
        }
        requireAction(kind)
        val isDeviceCode = action is EngineAction.Login && action.method == LoginMethod.DeviceCode
        if (isDeviceCode && spec.login != LoginSupport.CliWithDeviceCode) refuse("no device code sign-in")
        return action
    }

    private fun ManagedEngine.requireAction(action: EngineActionKind): ManagedEngine =
        takeIf { action in actions() } ?: refuse("$action does not apply engine=${descriptor.id.value}")

    /** Restarting needs a runnable engine without a job; a turn in flight is answered with Busy by the restart. */
    private fun ManagedEngine.requireRestartable(): ManagedEngine {
        val isRunnable = enablement.reasons.all { it == DisabledReason.DisabledByUser }
        val isJobRunning = job?.phase?.isFinished == false
        if (!isRunnable || isJobRunning) refuse("restart does not apply engine=${descriptor.id.value}")
        return this
    }

    private fun ManagedEngine.needsRetirement(): Boolean = runtime.runtimes > 0 && runtime.activeTurns == 0 &&
        (!enablement.isEnabled || runtime.isStale || runtime.hasExited)

    private fun refuse(reason: String): Nothing {
        log.w { "management command refused: $reason" }
        throw EngineException(EngineFailure.Access(AccessFailureReason.OperationNotAllowed))
    }

    /** What the last inspection and update check found. */
    private data class Inspection(
        val installation: InstallationState = InstallationState(),
        val login: LoginState? = null,
        val warnings: List<LaunchProblem> = emptyList(),
    )

    private data class Inputs(
        val developer: Map<EngineId, DeveloperFlags>,
        val preferences: ProfileEnginePreferences,
        val managed: Map<EngineId, ManagedInstall>,
        val pooled: List<RuntimeEntry>,
        val sessions: Map<EngineId, SessionCounts>,
    )

    private data class Bounded<T>(val value: T? = null, val failure: ManagementFailure? = null)

    private companion object {
        const val ADAPTER_TIMEOUT_MILLIS = 15_000L
    }
}

/** Connections that use an engine's CLI login; after a sign-in they refresh their source revision. */
fun interface CliConnections {
    /** Refreshes the CLI-login connections of [engine]; best effort, failures are logged. */
    suspend fun refresh(engine: EngineId)
}

/** [CliConnections] that reconnect each CLI-login binding of the engine, which re-reads its account revision. */
class RefreshingCliConnections(private val bindings: EngineBindingsService, private val sources: AuthSources) :
    CliConnections {
    private val log = Log.tag("CliConnections")

    override suspend fun refresh(engine: EngineId) {
        bindings.state.value.filter { it.engine == engine }.forEach { binding ->
            if (sources.get(binding.authSource) !is AuthSource.CliLogin) return@forEach
            try {
                bindings.connect(engine, binding.authSource, binding.priority)
                log.i { "CLI connection refreshed engine=${engine.value} binding=${binding.id.value}" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: EngineException) {
                log.w(e) { "CLI connection could not be refreshed engine=${engine.value}" }
            }
        }
    }
}

/** The pooled runtimes and open handles engine management observes and stops. */
interface EngineRuntimes {
    /** Pooled runtimes. */
    val entries: StateFlow<List<RuntimeEntry>>

    /** Open handles and turns in flight per engine. */
    val sessions: Flow<Map<EngineId, SessionCounts>>

    /** Stops the idle runtimes of [engine]. */
    suspend fun retire(engine: EngineId): RetireOutcome

    /** Disposes runtimes that shut themselves down. */
    suspend fun prune(): Int
}

/** [EngineRuntimes] over the profile's pool and handle registry. */
class PooledEngineRuntimes(private val pool: RuntimePool, private val handles: ActiveSessionRegistry) : EngineRuntimes {
    override val entries: StateFlow<List<RuntimeEntry>> get() = pool.entries
    override val sessions: Flow<Map<EngineId, SessionCounts>> get() = handles.summary

    override suspend fun retire(engine: EngineId): RetireOutcome = pool.retire(engine)

    override suspend fun prune(): Int = pool.prune()
}
