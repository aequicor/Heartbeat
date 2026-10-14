package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.app

import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.browser.api.BrowserDocument
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentHandle
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentRoute
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiAppRoute
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiIntent
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiMachineSpec
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiOutput
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiState
import io.aequicor.heartbeat.feature.serviceasui.api.UiCallContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiHostContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiHostId
import io.aequicor.heartbeat.feature.serviceasui.api.UiLibrary
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodResult
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethods
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwner
import io.aequicor.heartbeat.feature.serviceasui.api.UiPlacement
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurfaceId
import io.aequicor.heartbeat.feature.serviceasui.impl.data.UnsupportedUiPlatform
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCard
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardAdded
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardSource
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardStorage
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiMethodCalls
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiWebDocuments
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.PrMap
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.surface
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceModel
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.testStores
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

internal val AppHost = UiHostId("pane-1")
internal val AppChat = SessionRef(EngineId("pi"), SessionSourceId("s"), "chat")
internal val AppStart = UiHostContext(UiCallContext(preselected = mapOf(PrMap.kind to setOf(PrMap.id))))
internal val AppChatContext = UiHostContext(UiCallContext(session = AppChat))
internal val AppSurface = surface("gym", UiPlacement.Card).let {
    it.copy(
        content = it.content.copy(
            spec = """{"version":1,"state":{"draft":""},
        "data":{"snapshot":{"method":"read"}},"body":{
        "type":"web","html":"<input id='draft'>","bridge":true,"data":"{{data.snapshot}}"}}""",
        ),
    )
}

internal class AppFixture(scope: TestScope, context: UiHostContext = AppStart) {
    val machine = AppMachine(
        ServiceUiState.Ready(hosts = mapOf(AppHost to context), library = UiLibrary(listOf(AppSurface))),
    )
    val cards = AppCards()
    val calls = AppCalls()
    val web = AppWeb()
    val model = AppHostModel(
        ServiceUiAppRoute(AppHost.value),
        machine,
        cards,
        calls,
        { session, child -> SurfaceModel(session, calls, UnsupportedUiPlatform, testStores(scope), child, web) },
        object : PlatformInfo {
            override val host = HostPlatform.MacOs
        },
        AppScope(scope.backgroundScope),
    )
    val surface: SurfaceModel get() = model.surfaces.value.values.single()

    suspend fun bind(context: UiHostContext) {
        machine.send(ServiceUiIntent.Public.BindHost(AppHost, context))
    }

    fun receipt(args: JsonObject = JsonObject(emptyMap())) = UiCard(
        "receipt",
        AppChat,
        "call",
        "Gym",
        UiCardSource.Surface(AppSurface.id),
        args,
        Instant.fromEpochSeconds(0),
    )
}

internal class AppMachine(initial: ServiceUiState) : Machine<ServiceUiState, ServiceUiIntent, ServiceUiOutput> {
    override val name = "service_ui"
    override val state = MutableStateFlow(initial)
    override val outputs: Flow<ServiceUiOutput> = emptyFlow()
    val ready: ServiceUiState.Ready get() = state.value as ServiceUiState.Ready
    override suspend fun send(intent: ServiceUiIntent): SendResult {
        val resolution = ServiceUiMachineSpec.resolve(state.value, intent) ?: return SendResult.Ignored
        state.value = resolution.to
        return SendResult.Accepted
    }
}

internal class AppCards : UiCardStorage {
    val live = MutableStateFlow<UiCard?>(null)
    override fun observeLive(chat: SessionRef): Flow<UiCard?> = live
    override fun observe(id: String): Flow<UiCard?> = live
    override suspend fun latest(chat: SessionRef, surface: UiSurfaceId): UiCard? = live.value
    override suspend fun add(card: UiCard): UiCardAdded = error("unused")
}

internal class AppCalls : UiMethodCalls {
    val active = MutableStateFlow(setOf(PrMap))
    var owners: (UiCallContext) -> Flow<Set<UiOwner>> = { active }
    val made = mutableListOf<Pair<String, UiCallContext>>()
    var reply: suspend (String, UiCallContext) -> UiMethodResult = { _, context ->
        UiMethodResult.Success(JsonPrimitive(context.session?.nativeId ?: "start"))
    }
    override fun activeOwners(context: UiCallContext): Flow<Set<UiOwner>> = owners(context)
    override fun methods(owner: UiOwner): Flow<UiMethods> = flowOf(UiMethods(true))
    override suspend fun call(
        owner: UiOwner,
        method: String,
        arguments: JsonObject,
        context: UiCallContext,
    ): UiMethodResult {
        made += method to context
        return reply(method, context)
    }
}

internal class AppWeb : UiWebDocuments {
    override val availability: Flow<Boolean> = flowOf(true)
    val opened = mutableListOf<AppDocument>()
    override fun open(document: BrowserDocument): BrowserDocumentHandle =
        AppDocument(document, BrowserDocumentRoute("doc-${opened.size}")).also { opened += it }
}

internal class AppDocument(val document: BrowserDocument, override val route: BrowserDocumentRoute) :
    BrowserDocumentHandle {
    val updates = mutableListOf<JsonElement?>()
    var isClosed = false
    override fun update(data: JsonElement?) {
        updates += data
    }
    override fun close() {
        isClosed = true
    }
}

private class AppScope(override val coroutineScope: CoroutineScope) : ScopeHandle {
    override val name = "test-app"
    override val savedState: ScopeSavedState get() = error("unused")
    override val isClosed = false
    override fun onClose(action: () -> Unit): DisposableHandle = DisposableHandle { }
}
