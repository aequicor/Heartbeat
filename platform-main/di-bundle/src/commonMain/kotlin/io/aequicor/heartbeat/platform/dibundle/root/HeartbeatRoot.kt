package io.aequicor.heartbeat.platform.dibundle.root

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.router.slot.SlotNavigation
import com.arkivanov.decompose.router.slot.activate
import com.arkivanov.decompose.router.slot.childSlot
import com.arkivanov.decompose.router.slot.dismiss
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.doOnDestroy
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.navigation.DeepLinkResult
import io.aequicor.heartbeat.core.navigation.LaunchMode
import io.aequicor.heartbeat.core.navigation.NavOptions
import io.aequicor.heartbeat.core.navigation.NavTarget
import io.aequicor.heartbeat.core.navigation.NavTransition
import io.aequicor.heartbeat.core.navigation.RootHost
import io.aequicor.heartbeat.core.navigation.Route
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.profilefacade.ProfileSession
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioRoute
import io.aequicor.heartbeat.feature.computeruse.api.CaptureOwner
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseActivity
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseIntent
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMachineKey
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
import io.aequicor.heartbeat.feature.computeruse.api.computerUseActivity
import io.aequicor.heartbeat.platform.dibundle.HeartbeatGraph
import io.aequicor.heartbeat.platform.dibundle.ProfileNavigation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer

/** Start routes of the two navigation trees; each must be registered in its tree. */
data class RootStart(
    /** Before sign-in: app routes only. */
    val guest: List<Route>,
    /** Inside a profile: profile or app routes. */
    val profile: List<Route>,
)

/**
 * Root component of the platform: hosts the guest navigation tree or the tree of the active profile and switches
 * between them by `ProfileSessions.active` (sign-in, sign-out, profile switch).
 *
 * Cold start: the slot is empty ("loading") until the persisted profile is restored. After process death the slot
 * comes back as `Profile(id)`, but the profile graph does not exist yet — the profile tree is created as soon as the
 * session is restored, from the navigation state saved before (unconsumed saved state survives another save).
 *
 * Deep links: [handleDeepLink] applies the link to the shown tree. A link that the guest tree cannot show (a profile
 * route) or that arrives while loading stays pending — also across process death — and is applied when a tree is
 * ready. Main thread only. Created once per platform root (`Activity`, desktop window, `UIViewController`).
 */
class HeartbeatRoot(
    context: ComponentContext,
    private val graph: HeartbeatGraph,
    private val start: RootStart,
    private val localProfile: ProfileId? = null,
) : ComponentContext by context {

    private val log = Log.tag("NAV")
    private val navigation = SlotNavigation<RootConfig>()
    private val scope = CoroutineScope(SupervisorJob() + graph.dispatchers.main)
    private var pendingLink: String? = stateKeeper.consume(PENDING_LINK_KEY, String.serializer())
    private val mutableComputerUse = MutableStateFlow(ComputerUseActivity())
    private var foregroundCaptureOwner: CaptureOwner.Agent? = null

    /** Active agent capture; desktop presentation ends on release, disable, failure or profile closure. */
    val computerUse: StateFlow<ComputerUseActivity> = mutableComputerUse.asStateFlow()

    /** The shown tree; `child == null` while the persisted profile is being restored. */
    val slot: Value<ChildSlot<RootConfig, RootChild>> = childSlot(
        source = navigation,
        serializer = RootConfig.serializer(),
        key = "root",
        childFactory = ::createChild,
    )

    init {
        stateKeeper.register(PENDING_LINK_KEY, String.serializer()) { pendingLink }
        lifecycle.doOnDestroy { scope.cancel() }
        scope.launch { restoreAndFollowSessions() }
        scope.launch {
            graph.machines.observe(ComputerUseMachineKey)
                .collectLatest { machine ->
                    foregroundCaptureOwner = null
                    updateComputerUse(ComputerUseActivity())
                    machine?.state?.collect { state ->
                        updateComputerUse(state.computerUseActivity())
                        showComputerUseSession(state)
                    }
                }
        }
    }

    private fun updateComputerUse(activity: ComputerUseActivity) {
        if (mutableComputerUse.value == activity) return
        log.i { "root: computer use active=${activity.isActive}, screens=${activity.screens.size}" }
        mutableComputerUse.value = activity
    }

    private fun showComputerUseSession(state: ComputerUseState) {
        val owner = (state as? ComputerUseState.Capturing)?.owner as? CaptureOwner.Agent
        if (owner == null || owner == foregroundCaptureOwner) {
            foregroundCaptureOwner = owner
            return
        }
        val host = (slot.value.child?.instance as? RootChild.Profile)?.host?.value ?: return
        // Keep the existing studio and bring it forward once; later user navigation remains theirs.
        foregroundCaptureOwner = owner
        host.navigator.navigate(
            AiStudioRoute,
            NavOptions(LaunchMode.BringToFront, NavTarget.Root, NavTransition.None),
        )
    }

    /** Asks computer use to stop the agent that owns the current capture; the machine decides what that ends. */
    fun stopComputerUse() {
        log.i { "root: stop computer use requested" }
        val capture = graph.machines.find(ComputerUseMachineKey)?.state?.value as? ComputerUseState.Capturing
        if (capture == null || capture.owner !is CaptureOwner.Agent) {
            log.w { "root: no agent capture to stop" }
            return
        }
        scope.launch {
            val result = graph.machines.send(ComputerUseMachineKey, ComputerUseIntent.Public.StopAgent(capture.session))
            log.i { "root: stop computer use result=$result" }
        }
    }

    /**
     * Applies a deep link (Android intent, iOS `onOpenURL`, desktop URI handler). The link is not logged:
     * it may carry personal data.
     */
    fun handleDeepLink(uri: String) {
        pendingLink = uri
        applyPendingLink()
    }

    private suspend fun restoreAndFollowSessions() {
        val restored = try {
            graph.profileSessions.restore()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "root: profile restore failed" }
            null
        }
        if (restored == null && localProfile != null) {
            try {
                graph.profileSessions.open(localProfile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.e(e) { "root: local profile could not be opened" }
            }
        }
        graph.profileSessions.active.collect(::show)
    }

    private fun show(session: ProfileSession?) {
        val config = session?.let { RootConfig.Profile(it.id) } ?: RootConfig.Guest
        val child = slot.value.child
        val profile = child?.instance as? RootChild.Profile
        if (session != null && child?.configuration == config && profile?.hasDifferentGraph(session) == true) {
            // StateFlow can conflate sign-out and sign-in. The same profile id can now own a new graph;
            // destroy the old child context (including retained instances) before reusing its configuration.
            navigation.dismiss { show(session) }
            return
        }
        navigation.activate(config) {
            log.i { "root: ${config.logName}" }
            // After process death the restored child has no graph yet, so attach without losing its saved state.
            if (session != null) (slot.value.child?.instance as? RootChild.Profile)?.attach(session)
            applyPendingLink()
            graph.machines.find(ComputerUseMachineKey)?.state?.value?.let(::showComputerUseSession)
        }
    }

    private fun createChild(config: RootConfig, context: ComponentContext): RootChild = when (config) {
        RootConfig.Guest -> RootChild.Guest(graph.guestNavigation.create(context, start.guest, name = GUEST_TREE))

        is RootConfig.Profile -> {
            val child = RootChild.Profile(config.id, context, start.profile)
            graph.profileSessions.active.value?.let(child::attach)
            child
        }
    }

    private fun applyPendingLink() {
        val uri = pendingLink ?: return
        val child = slot.value.child?.instance
        val host = child?.readyHost()
        if (host == null) {
            log.i { "root: deep link deferred until navigation is ready" }
            return
        }
        when (host.handleDeepLink(uri)) {
            DeepLinkResult.Handled, DeepLinkResult.Rejected -> pendingLink = null

            DeepLinkResult.NoMatch -> if (child is RootChild.Guest) {
                log.i { "root: deep link deferred until sign-in" }
            } else {
                pendingLink = null
                log.w { "root: deep link matches no route of the profile tree, dropped" }
            }
        }
    }

    private fun RootChild.readyHost(): RootHost? = when (this) {
        is RootChild.Guest -> host
        is RootChild.Profile -> host.value
    }

    private val RootConfig.logName: String
        get() = when (this) {
            RootConfig.Guest -> GUEST_TREE
            is RootConfig.Profile -> "$PROFILE_TREE ${id.value}"
        }

    private companion object {
        const val PENDING_LINK_KEY = "root-pending-deep-link"
        const val GUEST_TREE = "guest"
        const val PROFILE_TREE = "profile"
    }
}

/** Configuration of the root slot. */
@Serializable
sealed interface RootConfig {
    /** Signed out. */
    @Serializable
    @SerialName("guest")
    data object Guest : RootConfig

    /** Profile [id] is active. */
    @Serializable
    @SerialName("profile")
    data class Profile(val id: ProfileId) : RootConfig
}

/** Child of the root slot. */
sealed interface RootChild {
    /** The guest tree. */
    data class Guest(val host: RootHost) : RootChild

    /**
     * The tree of profile [id]. [host] is `null` only right after process death, until the session is restored.
     */
    class Profile internal constructor(
        val id: ProfileId,
        private val context: ComponentContext,
        private val initial: List<Route>,
    ) : RootChild {
        private val log = Log.tag("NAV")
        private val mutableHost = MutableStateFlow<RootHost?>(null)
        private var attachedSession: ProfileSession? = null

        /** Root host of the profile tree. */
        val host: StateFlow<RootHost?> = mutableHost.asStateFlow()

        internal fun hasDifferentGraph(session: ProfileSession): Boolean =
            attachedSession?.let { it.graph !== session.graph } == true

        internal fun attach(session: ProfileSession) {
            if (mutableHost.value != null || session.id != id) return
            val navigation = (session.graph as ProfileNavigation).navigation
            mutableHost.value = navigation.create(context, initial, name = "profile")
            attachedSession = session
            log.i { "root: profile tree of ${id.value} attached" }
        }
    }
}
