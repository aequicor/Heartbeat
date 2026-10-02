package io.aequicor.heartbeat.feature.aiengine.facade.api.spi

import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.Compatibility
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchProblem
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginCode
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginMethod
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall

/**
 * What an adapter starts its processes with: the profile's [settings] and Heartbeat's verified copy ([managed]),
 * if one is installed. Paths are never logged.
 */
public data class LaunchContext(val settings: LaunchSettings = LaunchSettings(), val managed: ManagedInstall? = null) {
    override fun toString(): String = "LaunchContext(isDefault=${settings.isDefault}, hasManaged=${managed != null})"
}

/**
 * Launch context of an engine, read by its adapter each time it starts a process or runtime. It is the default
 * context while engine management is off, so adapters behave exactly as without management.
 */
public fun interface EngineLaunchConfig {
    /** Current context of [engine]. */
    public suspend fun context(engine: EngineId): LaunchContext

    /** Constants of the configuration. */
    public companion object {
        /** No settings and no managed copy: the adapter's own defaults. */
        public val Default: EngineLaunchConfig = EngineLaunchConfig { LaunchContext() }
    }
}

/**
 * Optional management of an adapter, called only by the facade's engine management, never by UI. Every call is
 * bounded in time and kills the processes it started. Failures are [EngineException] or [ManagementException];
 * the default implementations refuse operations an engine does not support.
 */
public interface EngineManager {
    /**
     * The executable the engine would run now and its version: the custom executable of [launch], else the managed
     * copy, else a bundled one, else a system installation. Never installs or signs in.
     */
    public suspend fun inspect(launch: LaunchContext): Installation

    /** File checks of [settings] (an executable that is missing, a directory that is a file); never blocks saving. */
    public suspend fun check(settings: LaunchSettings): List<LaunchProblem> = emptyList()

    /** Version shipped inside Heartbeat, for bundled engines; null otherwise. */
    public suspend fun bundledVersion(): String? = null

    /** Whether [version] was verified with this Heartbeat build. */
    public fun compatibility(version: String): Compatibility = Compatibility.Unknown

    /** The newest release for this platform, resolved through [feeds]. */
    public suspend fun resolveRelease(feeds: ReleaseFeeds): InstallPlan = unsupported()

    /** CLI sign-in state with [launch]; [LoginState.NotApplicable] for engines signing in through connections. */
    public suspend fun loginStatus(launch: LaunchContext): LoginState = LoginState.NotApplicable

    /**
     * Signs the CLI in with [method], showing prompts through [session], and returns the resulting state. Cancelling
     * the call cancels the sign-in and stops every process it started.
     */
    public suspend fun login(launch: LaunchContext, method: LoginMethod, session: LoginSession): LoginState =
        unsupported()

    /** Signs the CLI out and returns the resulting state. */
    public suspend fun logout(launch: LaunchContext): LoginState = unsupported()
}

/** What a running sign-in shows the user and asks back. */
public interface LoginSession {
    /** Shows [prompt]; a later prompt replaces it. */
    public fun prompt(prompt: LoginPrompt)

    /** Waits until the user passes the code the sign-in page showed. */
    public suspend fun awaitCode(): LoginCode
}

/** A sign-in step for the user. URLs and codes are never logged. */
public sealed interface LoginPrompt {
    /** Open [url] (an https page of the provider); with a device code, enter [userCode] there. */
    public data class OpenUrl(val url: String, val userCode: String? = null) : LoginPrompt {
        override fun toString(): String = "OpenUrl(***)"
    }

    /** Paste the code the sign-in page at [url] shows. */
    public data class PasteCode(val url: String? = null) : LoginPrompt {
        override fun toString(): String = "PasteCode(***)"
    }
}

private fun unsupported(): Nothing = throw EngineException(
    EngineFailure.Access(AccessFailureReason.OperationNotAllowed),
)
