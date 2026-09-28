package io.aequicor.heartbeat.feature.welcome.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.navigation.Route
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.machineSpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Entry route of a fresh application session. */
@Serializable
@SerialName("welcome")
public data object WelcomeRoute : Route

/** Destinations offered after the introduction. */
@Serializable
public enum class WelcomeDestination { Studio, Toggles }

/** Welcome presentation and durable pending navigation. */
@Serializable
public sealed interface WelcomeState : MachineState {
    /** The component has not started its workflow yet. */
    @Serializable public data object Idle : WelcomeState

    /** The startup animation preference is being read. */
    @Serializable public data object Checking : WelcomeState

    /** The finite cinematic presentation is active. */
    @Serializable public data object Intro : WelcomeState

    /** The destination actions are available. */
    @Serializable public data object Ready : WelcomeState

    /** The studio needs a profile; the local one is being opened before navigation. */
    @Serializable public data object OpeningProfile : WelcomeState

    /** A destination request awaiting acknowledgement by the navigation component. */
    @Serializable public data class Opening(val destination: WelcomeDestination) : WelcomeState

    /** A destination is open above the retained welcome entry. */
    @Serializable public data object Away : WelcomeState
}

/** User actions and acknowledgements from the component. */
public sealed interface WelcomeIntent : MachineIntent {
    /** Intents accepted from screens and other features. */
    public sealed interface Public : WelcomeIntent {
        /** Starts the feature workflow once. */
        public data object Start : Public

        /** Immediately exposes the welcome actions. */
        public data object Skip : Public

        /** Requests one of the welcome destinations. */
        public data class Open(val destination: WelcomeDestination) : Public
    }

    /** Results and lifecycle acknowledgements produced inside this feature. */
    public sealed interface Internal : WelcomeIntent {
        /** Supplies the resolved startup animation preference. */
        public data class Configured(val isAnimated: Boolean) : Internal

        /** The presentation reached its final frame. */
        public data object Finished : Internal

        /** A profile is active; the studio can be opened inside it. */
        public data object ProfileOpened : Internal

        /** The component has applied the pending navigation request. */
        public data object NavigationHandled : Internal

        /** The welcome entry became active again. */
        public data object Returned : Internal

        /** Settings or the profile could not be prepared; return to the static welcome. */
        public data object Failed : Internal
    }
}

/** Settings and profile IO are machine effects; the frame clock belongs to the presentation. */
public sealed interface WelcomeEffect : MachineEffect {
    /** Reads the device-local cinematic preference. */
    public data object ReadSettings : WelcomeEffect

    /** Opens the local profile unless a profile is already active. */
    public data object OpenProfile : WelcomeEffect
}

/** Navigation is acknowledged state, not an ephemeral output. */
public sealed interface WelcomeOutput : MachineOutput

/** Address of the welcome machine. */
public object WelcomeMachineKey :
    MachineKey<WelcomeState, WelcomeIntent, WelcomeIntent.Public, WelcomeEffect, WelcomeOutput> {
    override val name: String = "welcome"
}

/** Completed cinematic feature; disabling it preserves access to both destinations. */
public val CinematicIntro: FeatureToggle.Flag = FeatureToggle.Flag(
    "welcome.cinematic_intro",
    "Кинематографичное вступление при запуске",
    default = true,
)

/**
 * Idle --Start--> Checking --Configured--> Intro/Ready; Intro --Finished/Skip--> Ready.
 * Ready --Open(Toggles)--> Opening --NavigationHandled--> Away --Returned--> Ready.
 * Ready --Open(Studio)--> OpeningProfile [OpenProfile] --ProfileOpened--> Opening(Studio).
 * Settings or profile failure (Failed) and Skip during preparation lead to Ready. Duplicate opens are ignored.
 * OS restoration continues the session at Ready; a fresh session starts at Idle.
 */
public val WelcomeMachineSpec: MachineSpec<WelcomeState, WelcomeIntent, WelcomeEffect, WelcomeOutput> =
    machineSpec(WelcomeMachineKey, WelcomeState.Idle) {
        state<WelcomeState.Idle> {
            on<WelcomeIntent.Public.Start> {
                goto<WelcomeState.Checking> { WelcomeState.Checking }
                effect { WelcomeEffect.ReadSettings }
            }
            on<WelcomeIntent.Public.Skip> { goto<WelcomeState.Ready> { WelcomeState.Ready } }
        }
        state<WelcomeState.Checking> {
            on<WelcomeIntent.Internal.Configured>(guard = { intent.isAnimated }) {
                goto<WelcomeState.Intro> { WelcomeState.Intro }
            }
            on<WelcomeIntent.Internal.Configured>(guard = { !intent.isAnimated }) {
                goto<WelcomeState.Ready> { WelcomeState.Ready }
            }
            on<WelcomeIntent.Internal.Failed> { goto<WelcomeState.Ready> { WelcomeState.Ready } }
            on<WelcomeIntent.Public.Skip> { goto<WelcomeState.Ready> { WelcomeState.Ready } }
        }
        state<WelcomeState.Intro> {
            on<WelcomeIntent.Internal.Finished> { goto<WelcomeState.Ready> { WelcomeState.Ready } }
            on<WelcomeIntent.Public.Skip> { goto<WelcomeState.Ready> { WelcomeState.Ready } }
        }
        state<WelcomeState.Ready> {
            on<WelcomeIntent.Public.Open>(guard = { intent.destination == WelcomeDestination.Studio }) {
                goto<WelcomeState.OpeningProfile> { WelcomeState.OpeningProfile }
                effect { WelcomeEffect.OpenProfile }
            }
            on<WelcomeIntent.Public.Open>(guard = { intent.destination != WelcomeDestination.Studio }) {
                goto<WelcomeState.Opening> { WelcomeState.Opening(intent.destination) }
            }
        }
        state<WelcomeState.OpeningProfile> {
            on<WelcomeIntent.Internal.ProfileOpened> {
                goto<WelcomeState.Opening> { WelcomeState.Opening(WelcomeDestination.Studio) }
            }
            on<WelcomeIntent.Internal.Failed> { goto<WelcomeState.Ready> { WelcomeState.Ready } }
        }
        state<WelcomeState.Opening> {
            on<WelcomeIntent.Internal.NavigationHandled> { goto<WelcomeState.Away> { WelcomeState.Away } }
        }
        state<WelcomeState.Away> {
            on<WelcomeIntent.Internal.Returned> { goto<WelcomeState.Ready> { WelcomeState.Ready } }
        }
        onEffectFailure { _, _ -> WelcomeIntent.Internal.Failed }
        persist(WelcomeState.serializer()) { restore(WelcomeState.Ready) }
    }
