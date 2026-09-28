package io.aequicor.heartbeat.feature.questionnaire.impl.di

import com.arkivanov.decompose.ComponentContext
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeFactory
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ext.retainedGraph
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.navigation.NavComponent
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.navigation.ProfileRouteBinding
import io.aequicor.heartbeat.core.navigation.RouteEntry
import io.aequicor.heartbeat.core.profilefacade.ProfileStartup
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireEnabled
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireIntent
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireMachineSpec
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireOutput
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireRoute
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireState
import io.aequicor.heartbeat.feature.questionnaire.impl.di.scope.QuestionnaireScope
import io.aequicor.heartbeat.feature.questionnaire.impl.domain.QuestionnaireJournal
import io.aequicor.heartbeat.feature.questionnaire.impl.domain.QuestionnaireStorage
import io.aequicor.heartbeat.feature.questionnaire.impl.presentation.component.QuestionnaireComponent
import io.aequicor.heartbeat.feature.questionnaire.impl.ui.QuestionnaireUiComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Screen graph of one questionnaire source, retained by its navigation entry under the profile. */
@GraphExtension(QuestionnaireScope::class)
interface QuestionnaireGraph {
    val factory: QuestionnaireComponent.Factory

    /** Creates the graph of a source's questionnaire screen. */
    @ContributesTo(ProfileScope::class)
    @GraphExtension.Factory
    fun interface Factory {
        /** Binds the shown source and the lifecycle owner of the navigation entry. */
        fun create(
            @Provides route: QuestionnaireRoute,
            @Provides @ForScope(QuestionnaireScope::class) scope: ScopeHandle,
        ): QuestionnaireGraph
    }
}

/**
 * The question queue belongs to the profile, so questions outlive the screens that show them.
 * It has no effects: sources deliver answers themselves. [QuestionnaireJournal] keeps open questions across restarts.
 */
@ContributesTo(ProfileScope::class)
@BindingContainer
object QuestionnaireBindings {
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun machine(
        launcher: MachineLauncher,
        @ForScope(ProfileScope::class) scope: ScopeHandle,
    ): Machine<QuestionnaireState, QuestionnaireIntent, QuestionnaireOutput> =
        launcher.launch(QuestionnaireMachineSpec, scope, EffectHandler.None)
}

/**
 * Starts the profile queue and its journal when the profile opens, so sources can
 * `MachineRegistry.send(QuestionnaireMachineKey, Ask)` before any questionnaire screen exists.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class QuestionnaireStartup(
    private val machine: Lazy<Machine<QuestionnaireState, QuestionnaireIntent, QuestionnaireOutput>>,
    @ForScope(ProfileScope::class) private val scope: ScopeHandle,
    private val storage: Lazy<QuestionnaireStorage>,
    private val toggles: FeatureToggles,
    private val dispatchers: DispatcherProvider,
) : ProfileStartup {
    private val log = Log.tag("QuestionnaireStartup")

    override fun start() {
        val queue = machine.value
        // Storage IO and saving run off the main thread; the machine accepts intents from any thread.
        // Storage is touched only once the questionnaire is enabled: a disabled one re-asks nothing.
        scope.coroutineScope.launch(dispatchers.io) {
            toggles.observe(QuestionnaireEnabled).first { it }
            val journal = try {
                QuestionnaireJournal(storage.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.e(e) { "Questionnaire storage is unavailable; open questions are not kept" }
                return@launch
            }
            journal.run(queue)
        }
    }
}

/** Registers the questionnaire flag in the toggle panel. */
@ContributesTo(AppScope::class)
@BindingContainer
object QuestionnaireToggleBindings {
    /** The type must be exactly `FeatureToggle<*>` to join the registry set. */
    @Provides
    @IntoSet
    fun questionnaire(): FeatureToggle<*> = QuestionnaireEnabled
}

/** Route of the questionnaire screen of one source. */
@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class QuestionnaireRouteEntry(
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val graphs: QuestionnaireGraph.Factory,
) : RouteEntry<QuestionnaireRoute>(QuestionnaireRoute::class, QuestionnaireRoute.serializer()) {
    override fun create(route: QuestionnaireRoute, context: ComponentContext, navigator: Navigator): NavComponent =
        context.retainedGraph(scopes, profile, name = "questionnaire") { graphs.create(route, it) }
            .factory.create(context).let { QuestionnaireUiComponent(it) }
}
