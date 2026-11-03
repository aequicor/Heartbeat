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
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCandidateInspection
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCommand
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityObservation
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineEnablement
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInspection
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagement
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagementState
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostAppState
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallationState
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchProblem
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettingsSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettingsToken
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchState
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginMethod
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginState
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RuntimePreference
import io.aequicor.heartbeat.feature.aiengine.facade.api.RuntimeSummary
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.actions
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineCandidateInspector
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineLaunchConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineManager
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ReleaseFeeds
import io.aequicor.heartbeat.feature.aiengine.facade.api.validateLaunchSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Developer flags engine management reads. */
interface EngineFlags {
    /** Whether engine management is on, and its changes. */
    fun management(): Flow<Boolean>

    /** Whether engine configuration is on, and its changes; adapters get the configuration only while it is. */
    fun configuration(): Flow<Boolean> = flowOf(false)

    /** The catalog flag and the engine's own flag of [descriptor], and their changes. */
    fun developer(descriptor: EngineDescriptor): Flow<DeveloperFlags>
}

/** Developer flags of one engine. */
data class DeveloperFlags(val isCatalogOn: Boolean, val isEngineOn: Boolean, val isMcpOn: Boolean = false)

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

    // Inspect and a launch change must never publish snapshots across each other; coordination is scoped per engine.
    private val inspectionLocks = registry.all.associate { it.descriptor.id to Mutex() }

    // Accessed only under the corresponding inspection lock. Identity prevents ABA and cross-owner reuse.
    private val launchTokens = registry.all.associate { it.descriptor.id to LaunchTokenSlot() }

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

    private val snapshots: StateFlow<Snapshot> = flags.management()
        .flatMapLatest { isOn -> if (isOn) managed() else flowOf(Snapshot()) }
        .stateIn(context.scope, SharingStarted.Eagerly, Snapshot())

    override val state: StateFlow<EngineManagementState> = snapshots.map { it.state }
        .stateIn(context.scope, SharingStarted.Eagerly, EngineManagementState.Off)

    init {
        context.scope.launch { loadInstalls() }
        context.scope.launch { snapshots.collect(::reconcile) }
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
            EngineCommand.OpenHostApp -> openHostApp(current.requireAction(EngineActionKind.OpenHostApp))
        }
    }

    /**
     * Opens the host application within the adapter time limit, like every adapter call ([bounded]); a failure
     * reaches the caller as an [EngineException].
     */
    private suspend fun openHostApp(engine: ManagedEngine) {
        val id = engine.descriptor.id
        val manager = registry.require(id).manager?.value ?: refuse("no host application engine=${id.value}")
        val failure = bounded("host application opening") { manager.openHostApp() }.failure
        if (failure != null) {
            throw EngineException((failure as? ManagementFailure.Engine)?.failure ?: EngineFailure.Unknown())
        }
        log.i { "host application opened engine=${id.value}" }
    }

    private suspend fun setEnabled(engine: EngineId, isEnabled: Boolean) {
        preferences.update { chosen ->
            chosen.copy(disabled = if (isEnabled) chosen.disabled - engine else chosen.disabled + engine)
        }
        log.i { "engine switched engine=${engine.value} enabled=$isEnabled" }
        if (!isEnabled) runtimes.retire(engine)
    }

    override suspend fun inspect(engine: EngineId): EngineInspection {
        val lock = inspectionLocks[engine] ?: refuse("unknown engine=${engine.value}")
        return lock.withLock {
            val current = state.value.takeIf { it.isEnabled }?.engines?.firstOrNull { it.descriptor.id == engine }
                ?: refuse("engine management is off or the engine is unknown engine=${engine.value}")
            inspectCurrent(current.requireAction(EngineActionKind.Inspect))
        }
    }

    override suspend fun snapshotLaunchSettings(engine: EngineId): LaunchSettingsSnapshot {
        val lock = inspectionLocks[engine] ?: refuse("unknown engine=${engine.value}")
        return lock.withLock {
            val current = state.value.takeIf { it.isEnabled }?.engines?.firstOrNull { it.descriptor.id == engine }
                ?: refuse("engine management is off or the engine is unknown engine=${engine.value}")
            current.requireAction(EngineActionKind.Configure)
            val settings = preferences.load().launchOf(engine).detached()
            LaunchSettingsSnapshot(settings, launchTokens.getValue(engine).forSettings(settings))
        }
    }

    override suspend fun inspectCandidate(
        engine: EngineId,
        expected: LaunchSettingsSnapshot,
        candidate: LaunchSettings,
    ): EngineCandidateInspection? {
        val lock = inspectionLocks[engine] ?: refuse("unknown engine=${engine.value}")
        return lock.withLock {
            val current = state.value.takeIf { it.isEnabled }?.engines?.firstOrNull { it.descriptor.id == engine }
                ?: refuse("engine management is off or the engine is unknown engine=${engine.value}")
            current.requireAction(EngineActionKind.Configure)
            current.requireAction(EngineActionKind.Inspect)
            val saved = expected.settings.detached()
            checkCandidateSnapshot(engine, expected, saved)
            if (candidate.copy(
                    executable = saved.executable,
                    runtime = saved.runtime,
                    hostRuntimeVersion = saved.hostRuntimeVersion,
                ) != saved || (candidate.executable.isNullOrBlank() && candidate.runtime == RuntimePreference.Auto)
            ) {
                throw EngineException(EngineFailure.Request(RequestFailureReason.Invalid))
            }
            validateLaunchChange(current, candidate.detached(), saved)
            val currentLaunch = launch.context(engine)
            val isolated = LaunchContext(
                settings = LaunchSettings(
                    executable = candidate.executable,
                    runtime = candidate.runtime,
                    hostRuntimeVersion = candidate.hostRuntimeVersion,
                ),
                managed = currentLaunch.managed.takeIf {
                    candidate.executable.isNullOrBlank() && candidate.runtime == RuntimePreference.Managed
                },
            )
            val inspector = registry.require(engine).manager?.value as? EngineCandidateInspector
            val observation = if (inspector == null) {
                Bounded<EngineCandidateInspection?>()
            } else {
                bounded("candidate inspection") { inspector.inspectCandidate(isolated) }
            }
            observation.failure?.let { throw ManagementException(it) }
            checkCandidateSnapshot(engine, expected, saved)
            if (launch.context(engine) != currentLaunch) changedLaunch()
            val latest = state.value.takeIf { it.isEnabled }?.engines?.firstOrNull { it.descriptor.id == engine }
                ?: refuse("engine management is off or the engine is unknown engine=${engine.value}")
            latest.requireAction(EngineActionKind.Configure)
            latest.requireAction(EngineActionKind.Inspect)
            observation.value?.also { validateCandidate(engine, candidate, isolated.managed, it) }
        }
    }

    private suspend fun checkCandidateSnapshot(
        engine: EngineId,
        expected: LaunchSettingsSnapshot,
        saved: LaunchSettings,
    ) {
        val slot = launchTokens.getValue(engine)
        val actual = preferences.load().launchOf(engine)
        if (slot.value !== expected.token || slot.value?.settings != saved || actual != saved) {
            changedLaunch()
        }
    }

    private suspend fun inspect(engine: ManagedEngine): EngineInspection = inspect(engine.descriptor.id)

    private suspend fun inspectCurrent(engine: ManagedEngine): EngineInspection {
        val id = engine.descriptor.id
        val savedLaunch = preferences.load().launchOf(id)
        runtimes.prune()
        val registration = registry.require(id)
        val manager = registration.manager?.value
        val hasHostApp = registration.management.hostApp != null
        val inspectedLaunch = launch.context(id)
        val inspected = if (manager == null) builtIn() else inspectWith(id, manager, inspectedLaunch, hasHostApp)
        if (launch.context(id) != inspectedLaunch || preferences.load().launchOf(id) != savedLaunch) {
            throw EngineException(EngineFailure.Session(SessionFailureReason.Changed))
        }
        val isProbeAllowed = manager == null ||
            (
                inspected.installation.compatibilityObservation?.decision?.isBaselineAllowed == true &&
                    !inspected.isCompatibilityProbeFailed
            )
        if (engine.enablement.isEnabled && isProbeAllowed) refreshAvailability(id)
        if (launch.context(id) != inspectedLaunch || preferences.load().launchOf(id) != savedLaunch) {
            throw EngineException(EngineFailure.Session(SessionFailureReason.Changed))
        }
        inspections.update { all ->
            val previous = all[id]
            val installation = inspected.installation.copy(
                latest = previous?.installation?.latest,
                compatibility = inspected.installation.compatibility,
            )
            all + (id to inspected.copy(installation = installation))
        }
        log.i { "engine inspected engine=${id.value} source=${inspected.installation.current?.source ?: "unknown"}" }
        val tokenSlot = launchTokens.getValue(id)
        val token = tokenSlot.forSettings(savedLaunch.detached())
        return EngineInspection(
            id,
            inspected.installation.copy(managed = inspectedLaunch.managed),
            inspected.login ?: LoginState.Unknown,
            LaunchState(inspectedLaunch.settings, inspected.warnings),
            inspected.hostApp,
            LaunchSettingsSnapshot(savedLaunch.detached(), token),
        )
    }

    /** Inspects again after a job; the finished job may still be shown, so the action check is skipped. */
    private suspend fun reinspect(engine: EngineId) {
        inspectionLocks.getValue(engine).withLock {
            val current = state.value.engines.firstOrNull { it.descriptor.id == engine } ?: return@withLock
            if (current.enablement.reasons.all { it == DisabledReason.DisabledByUser }) inspectCurrent(current)
        }
    }

    private suspend fun inspectWith(
        engine: EngineId,
        manager: EngineManager,
        launchContext: LaunchContext,
        hasHostApp: Boolean,
    ): Inspection {
        val now = context.clock.now()
        val installation = bounded("inspect") { manager.inspect(launchContext) }
        val current = installation.value
        val observed = current?.let { inspectCompatibility(engine, manager, it, launchContext) }
        val compatibility = compatibilityOf(observed)
        val isRuntimeLocated = hasRunnableInstallation(current)
        val isLoginAllowed = isRuntimeLocated && permitsFollowingInspection(observed)
        val login = if (isLoginAllowed) {
            bounded("login status") { manager.loginStatus(launchContext) }
        } else {
            Bounded<LoginState>(LoginState.Unknown)
        }
        val warnings = bounded("launch check") { manager.check(launchContext.settings) }
        val bundled = bounded("bundled version") { manager.bundledVersion() }
        val hostApp = if (hasHostApp) {
            bounded(
                "host application",
            ) { manager.inspectHostApp() }.let { it.value ?: HostAppState(failure = it.failure) }
        } else {
            null
        }
        return Inspection(
            InstallationState(
                current = current,
                bundledVersion = bundled.value,
                compatibility = compatibility,
                checkedAt = now,
                failure = installation.failure ?: observed?.failure ?: login.failure,
                compatibilityObservation = observed?.value,
            ),
            login.value ?: LoginState.Unknown,
            warnings.value.orEmpty(),
            hostApp,
            isCompatibilityProbeFailed = !isRuntimeLocated || observed?.failure != null,
        )
    }

    private fun compatibilityOf(observed: Bounded<EngineCompatibilityObservation?>?): Compatibility = when {
        observed?.failure != null -> Compatibility.Unknown
        observed?.value != null -> observed.value.decision.status
        else -> Compatibility.Unknown
    }

    private fun permitsFollowingInspection(observed: Bounded<EngineCompatibilityObservation?>?): Boolean =
        observed?.value?.decision?.isBaselineAllowed == true && observed.failure == null

    private suspend fun inspectCompatibility(
        engine: EngineId,
        manager: EngineManager,
        installation: Installation,
        launch: LaunchContext,
    ): Bounded<EngineCompatibilityObservation?> = bounded("compatibility probe") {
        manager.inspectCompatibility(installation, launch)?.also { report ->
            val observedVersion = report.evidence.identity.runtime
            val isVersionChanged = installation.version != null && observedVersion != null &&
                installation.version != observedVersion
            if (report.requirements.engine != engine || isVersionChanged) {
                throw EngineException(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))
            }
        }
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
        inspectionLocks.getValue(id).withLock {
            val current = state.value.takeIf { it.isEnabled }?.engines?.firstOrNull { it.descriptor.id == id }
                ?: refuse("engine management is off or the engine is unknown engine=${id.value}")
            configureCurrent(current.requireAction(EngineActionKind.Configure), settings)
        }
    }

    override suspend fun configureIfUnchanged(
        engine: EngineId,
        expected: LaunchSettingsSnapshot,
        settings: LaunchSettings,
        onSaved: (LaunchSettingsSnapshot) -> Unit,
    ) {
        val lock = inspectionLocks[engine] ?: refuse("unknown engine=${engine.value}")
        lock.withLock {
            val current = state.value.takeIf { it.isEnabled }?.engines?.firstOrNull { it.descriptor.id == engine }
                ?: refuse("engine management is off or the engine is unknown engine=${engine.value}")
            configureCurrent(current.requireAction(EngineActionKind.Configure), settings, expected, onSaved)
        }
    }

    private suspend fun configureCurrent(
        engine: ManagedEngine,
        settings: LaunchSettings,
        expected: LaunchSettingsSnapshot? = null,
        onSaved: (LaunchSettingsSnapshot) -> Unit = {},
    ) {
        val id = engine.descriptor.id
        val desired = settings.detached()
        val expectedSettings = expected?.settings?.detached()
        val slot = launchTokens.getValue(id)
        if (expected != null && (expected.token !== slot.value || slot.value?.settings != expectedSettings)) {
            changedLaunch()
        }
        // Invalidate even an identical write or a storage failure with an indeterminate outcome.
        slot.invalidate()
        val (checkedSettings, isMcpPreserved) = validateLaunchChange(engine, desired, expectedSettings)
        currentCoroutineContext().ensureActive()
        val isChanged = persistLaunch(id, desired, expectedSettings, isMcpPreserved, onSaved)
        if (!isChanged) return
        val manager = registry.require(id).manager?.value
        val warnings = if (manager == null) {
            emptyList()
        } else {
            bounded("launch check") { manager.check(checkedSettings) }.value
        }
        log.i { "launch check finished engine=${id.value} warnings=${warnings?.size ?: 0}" }
        inspections.update { all -> all + (id to (all[id] ?: Inspection()).copy(warnings = warnings.orEmpty())) }
        runtimes.retire(id)
    }

    private fun validateLaunchChange(
        engine: ManagedEngine,
        desired: LaunchSettings,
        expected: LaunchSettings?,
    ): Pair<LaunchSettings, Boolean> {
        val id = engine.descriptor.id
        val isMcpPreserved = LaunchOption.Mcp !in engine.spec.launch.options &&
            LaunchOption.Mcp in registry.require(id).management.launch.options
        val checkedSettings = if (isMcpPreserved) desired.copy(isMcpEnabled = false) else desired
        val validationSettings = if (isMcpPreserved && expected?.isMcpEnabled == true) {
            checkedSettings
        } else {
            desired
        }
        val problems = validateLaunchSettings(validationSettings, engine.spec.launch, registry.platform)
        if (problems.isNotEmpty()) {
            log.w { "launch settings rejected engine=${id.value} problems=${problems.size}" }
            throw EngineException(EngineFailure.Request(RequestFailureReason.Invalid))
        }
        return checkedSettings to isMcpPreserved
    }

    private suspend fun persistLaunch(
        id: EngineId,
        desired: LaunchSettings,
        expected: LaunchSettings?,
        isMcpPreserved: Boolean,
        onSaved: (LaunchSettingsSnapshot) -> Unit,
    ): Boolean {
        val slot = launchTokens.getValue(id)
        var isChanged = false
        val saved = withContext(NonCancellable) {
            val persisted = preferences.update { chosen ->
                if (expected != null && chosen.launchOf(id) != expected) changedLaunch()
                val updated = if (isMcpPreserved) {
                    desired.copy(isMcpEnabled = chosen.launchOf(id).isMcpEnabled)
                } else {
                    desired
                }
                isChanged = updated != chosen.launchOf(id)
                chosen.copy(launch = chosen.launch + (id to updated))
            }.launchOf(id)
            if (isChanged) invalidateInspection(id)
            val nextToken = slot.forSettings(persisted.detached())
            onSaved(LaunchSettingsSnapshot(persisted.detached(), nextToken))
            persisted
        }
        log.i { "launch settings saved engine=${id.value} isDefault=${saved.isDefault}" }
        return isChanged
    }

    private fun invalidateInspection(id: EngineId) {
        log.i { "launch inspection invalidated engine=${id.value}" }
        inspections.update { all ->
            val previous = all[id]
            if (previous == null) {
                all
            } else {
                val installation = previous.installation.copy(
                    current = null,
                    compatibility = Compatibility.Unknown,
                    compatibilityObservation = null,
                    checkedAt = null,
                    failure = null,
                )
                all + (
                    id to previous.copy(
                        installation = installation,
                        login = LoginState.Unknown,
                        isCompatibilityProbeFailed = false,
                    )
                )
            }
        }
    }

    private suspend fun restart(engine: EngineId) {
        val outcome = runtimes.retire(engine)
        if (outcome.busy > 0) {
            log.w { "restart left busy runtimes engine=${engine.value} busy=${outcome.busy}" }
            throw EngineException(EngineFailure.Session(SessionFailureReason.Busy))
        }
    }

    private suspend fun refreshAvailability(engine: EngineId) {
        bounded("availability probe") { catalog.refresh(engine) }
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

    private suspend fun reconcile(snapshot: Snapshot) {
        val current = snapshot.state
        if (!current.isEnabled) return
        current.engines.filter { it.needsRetirement() }.forEach { engine ->
            val id = engine.descriptor.id
            log.i { "retire idle runtimes engine=${id.value} enabled=${engine.enablement.isEnabled}" }
            try {
                val expected = snapshot.expected[id]?.takeIf { engine.enablement.isEnabled }
                runtimes.retire(id, expected)
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

    private fun managed(): Flow<Snapshot> {
        val developer = if (registry.all.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(registry.all.map { registration -> flags.developer(registration.descriptor) }) { values ->
                registry.all.map { it.descriptor.id }.zip(values).toMap()
            }
        }
        val chosen = combine(preferences.observe(), flags.configuration(), ::Pair)
        val local = combine(
            developer,
            chosen,
            installs.state,
            runtimes.entries,
            runtimes.sessions,
        ) { flagged, (saved, isConfigured), managed, pooled, sessions ->
            Inputs(flagged, saved, isConfigured, managed, pooled, sessions)
        }
        return combine(
            local,
            bindings,
            catalog.state,
            inspections,
            jobs.jobs,
        ) { inputs, saved, listed, inspected, run ->
            val state = EngineManagementState(
                isEnabled = true,
                platform = registry.platform,
                engines = registry.all.map { registration ->
                    val id = registration.descriptor.id
                    engine(registration, inputs, saved, listed, inspected[id]).copy(job = run[id])
                },
            )
            Snapshot(state, registry.all.associate { it.descriptor.id.let { id -> id to inputs.contextOf(id) } })
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
        val settings = inputs.contextOf(id).settings
        return ManagedEngine(
            descriptor = descriptor,
            // Package jobs act on the configured package list, which exists only while configuration is on.
            spec = registration.management.let {
                it.copy(
                    hasPackages = it.hasPackages && inputs.isConfigured,
                    launch = if (flagged.isMcpOn) {
                        it.launch
                    } else {
                        it.launch.copy(options = it.launch.options - LaunchOption.Mcp)
                    },
                )
            },
            enablement = EngineEnablement(id !in inputs.preferences.disabled, reasons),
            availability = listed.firstOrNull { it.descriptor.id == id }?.availability
                ?: if (isSupported) EngineAvailability.Unknown else EngineAvailability.UnsupportedPlatform,
            connections = saved.count { it.engine == id },
            installation = installationOf(registration, inspection, inputs.managed[id]),
            login = inspection?.login ?: defaultLogin(registration),
            launch = LaunchState(settings, inspection?.warnings.orEmpty()),
            runtime = runtimeOf(id, inputs, inputs.contextOf(id)),
            hostApp = inspection?.hostApp?.takeIf { registration.management.hostApp != null },
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
            EngineAction.SyncPackages, EngineAction.UpdatePackages -> EngineActionKind.Packages
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

    private fun ManagedEngine.needsRetirement(): Boolean = runtime.runtimes > 0 &&
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
        val hostApp: HostAppState? = null,
        val isCompatibilityProbeFailed: Boolean = false,
    )

    private data class Inputs(
        val developer: Map<EngineId, DeveloperFlags>,
        val preferences: ProfileEnginePreferences,
        val isConfigured: Boolean,
        val managed: Map<EngineId, ManagedInstall>,
        val pooled: List<RuntimeEntry>,
        val sessions: Map<EngineId, SessionCounts>,
    ) {
        /** The context adapters of [engine] start with now; runtimes started with another one are stale. */
        fun contextOf(engine: EngineId): LaunchContext {
            val saved = preferences.contextOf(engine, managed[engine], isConfigured)
            return if (developer[engine]?.isMcpOn == true) {
                saved
            } else {
                saved.copy(settings = saved.settings.copy(isMcpEnabled = false))
            }
        }
    }

    /** Management state and the launch context each engine starts with now. */
    private data class Snapshot(
        val state: EngineManagementState = EngineManagementState.Off,
        val expected: Map<EngineId, LaunchContext> = emptyMap(),
    )

    private data class Bounded<T>(val value: T? = null, val failure: ManagementFailure? = null)

    private companion object {
        const val ADAPTER_TIMEOUT_MILLIS = 15_000L
    }
}

private fun hasRunnableInstallation(installation: Installation?): Boolean =
    installation != null && installation.source != InstallSource.Missing && installation.isRunnable

private fun changedLaunch(): Nothing = throw EngineException(EngineFailure.Session(SessionFailureReason.Changed))

private fun LaunchSettings.detached(): LaunchSettings = copy(
    configOverrides = configOverrides.toList(),
    environment = environment.toList(),
)

private fun defaultLogin(registration: EngineRegistration): LoginState = when (registration.management.login) {
    LoginSupport.Cli, LoginSupport.CliWithDeviceCode -> LoginState.Unknown
    LoginSupport.None, LoginSupport.Connections -> LoginState.NotApplicable
}

private class LaunchToken(val settings: LaunchSettings) : LaunchSettingsToken

private class LaunchTokenSlot {
    var value: LaunchToken? = null
        private set

    fun invalidate() {
        value = null
    }

    fun forSettings(settings: LaunchSettings): LaunchToken = value?.takeIf { it.settings == settings }
        ?: LaunchToken(settings).also { value = it }
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

    /** Stops idle runtimes of [engine]; a non-null [expected] preserves live runtimes already using that context. */
    suspend fun retire(engine: EngineId, expected: LaunchContext? = null): RetireOutcome

    /** Disposes runtimes that shut themselves down. */
    suspend fun prune(): Int
}

/** [EngineRuntimes] over the profile's pool and handle registry. */
class PooledEngineRuntimes(private val pool: RuntimePool, private val handles: ActiveSessionRegistry) : EngineRuntimes {
    override val entries: StateFlow<List<RuntimeEntry>> get() = pool.entries
    override val sessions: Flow<Map<EngineId, SessionCounts>> get() = handles.summary

    override suspend fun retire(engine: EngineId, expected: LaunchContext?): RetireOutcome = pool.retire(
        engine,
        expected,
    )

    override suspend fun prune(): Int = pool.prune()
}
