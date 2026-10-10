package io.aequicor.heartbeat.feature.harness.impl.presentation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.essenty.instancekeeper.getOrCreate
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.harness.api.HarnessDetailRoute
import io.aequicor.heartbeat.feature.harness.api.HarnessItemRoute
import io.aequicor.heartbeat.feature.harness.api.HarnessToolsRoute
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessMachine
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessCodeChecks
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessLibraryClient
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessToolCatalogs
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.HarnessRunsMachine

private val log = Log.tag("HarnessHost")

/** The library section; the library belongs to the profile and outlives the screen. */
@AssistedInject
internal class HarnessLibraryComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    @Assisted screen: ScopeHandle,
    machine: Lazy<HarnessMachine>,
    factory: HeartbeatStoreFactory,
) : ComponentContext by context {
    val model: HarnessLibraryModel = instanceKeeper.getOrCreate(MODEL_KEY) {
        Retained(
            HarnessLibraryModel(machine.value, factory, screen.coroutineScope),
        )
    }.model

    /** Opens the details of a harness. */
    fun openDetail(harness: String) {
        log.i { "Open harness details" }
        navigator.navigate(HarnessDetailRoute(harness))
    }

    fun close() = navigator.close()

    private class Retained(val model: HarnessLibraryModel) : InstanceKeeper.Instance

    /** Metro factory for a lifecycle-owned screen instance. */
    @AssistedFactory
    fun interface Factory {
        fun create(context: ComponentContext, navigator: Navigator, screen: ScopeHandle): HarnessLibraryComponent
    }
}

/** One harness; deleting it closes the screen. */
@AssistedInject
internal class HarnessDetailComponent(
    @Assisted context: ComponentContext,
    @Assisted navigator: Navigator,
    @Assisted screen: ScopeHandle,
    @Assisted harness: String,
    machine: Lazy<HarnessMachine>,
    runs: Lazy<HarnessRunsMachine>,
    workspaces: LocalWorkspaces,
    factory: HeartbeatStoreFactory,
) : ComponentContext by context {
    val model: HarnessDetailModel = instanceKeeper.getOrCreate(MODEL_KEY) {
        Retained(HarnessDetailModel(harness, machine.value, runs.value, workspaces, factory, screen.coroutineScope))
    }.model

    /** Navigation of this entry; the model only requests it through actions. */
    val navigation: HarnessDetailNavigation = HarnessDetailNavigation(
        openItem = { item ->
            log.i { "Open harness item editor" }
            navigator.navigate(HarnessItemRoute(harness, item))
        },
        openTools = {
            log.i { "Open harness tool policy" }
            navigator.navigate(HarnessToolsRoute(harness))
        },
        close = navigator::close,
    )

    private class Retained(val model: HarnessDetailModel) : InstanceKeeper.Instance

    /** Metro factory for a lifecycle-owned screen instance. */
    @AssistedFactory
    fun interface Factory {
        fun create(
            context: ComponentContext,
            navigator: Navigator,
            screen: ScopeHandle,
            harness: String,
        ): HarnessDetailComponent
    }
}

/** Navigation of the detail screen, performed by its component. */
internal data class HarnessDetailNavigation(
    val openItem: (String) -> Unit,
    val openTools: () -> Unit,
    val close: () -> Unit,
)

/** Editor of one item; unsaved texts stay in the profile draft cache when the screen closes. */
@AssistedInject
internal class HarnessItemComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    @Assisted screen: ScopeHandle,
    @Assisted harness: String,
    @Assisted item: String,
    machine: Lazy<HarnessMachine>,
    library: HarnessLibraryClient,
    checks: HarnessCodeChecks,
    drafts: HarnessDrafts,
    factory: HeartbeatStoreFactory,
) : ComponentContext by context {
    val model: HarnessItemModel = instanceKeeper.getOrCreate(MODEL_KEY) {
        Retained(
            HarnessItemModel(
                harness,
                item,
                machine.value,
                HarnessItemEditing(library, checks, drafts),
                factory,
                screen.coroutineScope,
            ),
        )
    }.model

    fun close() = navigator.close()

    private class Retained(val model: HarnessItemModel) : InstanceKeeper.Instance

    /** Metro factory for a lifecycle-owned screen instance. */
    @AssistedFactory
    fun interface Factory {
        fun create(
            context: ComponentContext,
            navigator: Navigator,
            screen: ScopeHandle,
            harness: String,
            item: String,
        ): HarnessItemComponent
    }
}

/** Tool policy of one harness. */
@AssistedInject
internal class HarnessToolsComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    @Assisted screen: ScopeHandle,
    @Assisted harness: String,
    machine: Lazy<HarnessMachine>,
    catalogs: HarnessToolCatalogs,
    factory: HeartbeatStoreFactory,
) : ComponentContext by context {
    val model: HarnessToolsModel = instanceKeeper.getOrCreate(MODEL_KEY) {
        Retained(HarnessToolsModel(harness, machine.value, catalogs, factory, screen.coroutineScope))
    }.model

    fun close() = navigator.close()

    private class Retained(val model: HarnessToolsModel) : InstanceKeeper.Instance

    /** Metro factory for a lifecycle-owned screen instance. */
    @AssistedFactory
    fun interface Factory {
        fun create(
            context: ComponentContext,
            navigator: Navigator,
            screen: ScopeHandle,
            harness: String,
        ): HarnessToolsComponent
    }
}

private const val MODEL_KEY = "harness-model"
