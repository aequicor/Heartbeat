package io.aequicor.heartbeat.feature.aistudio.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.test.Test
import kotlin.test.assertEquals

class StudioLiveDefaultsMachineTest {
    private val first = RunSettings("route-a", ReasoningEffort.High, ApprovalMode.Ask)
    private val latest = first.copy(
        modelId = "missing-exact-b",
        nativeEffort = StudioEffortOverride("missing-exact-b", null),
    )
    private val other = first.copy(modelId = "route-c")
    private val ready = AiStudioState.Ready(
        panes = listOf(StudioPane(0, settings = first), StudioPane(1, settings = other)),
        focusedPaneId = 0,
        settings = first,
    )

    @Test
    fun `observed defaults are only a seed and new pages capture them without changing their neighbor`() {
        val observed = ready.copy(nextSessionSettings = latest)
        AiStudioMachineSpec.assertTransition(ready, AiStudioIntent.Internal.DefaultsObserved(latest), observed)
        AiStudioMachineSpec.assertTransition(
            observed,
            AiStudioIntent.Public.NewSession("project", 1),
            observed.copy(
                panes = listOf(ready.panes[0], StudioPane(1, projectId = "project", settings = latest, pageId = 1)),
                focusedPaneId = 1,
                nextPageId = 2,
            ),
        )
        AiStudioMachineSpec.assertTransition(
            observed,
            AiStudioIntent.Public.OpenBeside(null),
            observed.copy(
                panes = listOf(ready.panes[0], StudioPane(1, settings = latest, pageId = 1)),
                focusedPaneId = 1,
                nextPageId = 2,
            ),
        )
    }

    @Test
    fun `editing one page keeps neighbor and live seed while submission captures its exact parameters`() {
        val observed = ready.copy(nextSessionSettings = latest)
        val choice = other.copy(nativeEffort = StudioEffortOverride(other.modelId, "native-low"))
        val version = observed.settingsVersion.copy(revision = 1)
        val edited = observed.copy(
            settings = choice,
            panes = listOf(ready.panes[0], ready.panes[1].copy(settings = choice)),
            settingsVersion = version,
        )
        AiStudioMachineSpec.assertTransition(
            observed,
            AiStudioIntent.Public.UpdateSettings(choice, 1),
            edited,
            effects = listOf(AiStudioEffect.SaveSettings(choice, version)),
        )
        AiStudioMachineSpec.assertTransition(
            edited,
            AiStudioIntent.Public.Submit(0, "hello"),
            edited.copy(
                panes = listOf(ready.panes[0].copy(isCreating = true, createRequestId = 0), edited.panes[1]),
                nextCreateRequestId = 1,
            ),
            effects = listOf(AiStudioEffect.CreateSession(0, null, "hello", first, requestId = 0)),
        )
    }

    @Test
    fun `an accepted snapshot wins over a delayed observer for all new page paths`() {
        val stale = ready.copy(nextSessionSettings = first)
        val accepted = StudioSessionSettings("accepted-route", null, ApprovalMode.AutoEdits)
        val captured = first.copy(
            modelId = accepted.modelId,
            approval = accepted.approval,
            nativeEffort = StudioEffortOverride(accepted.modelId, null),
        )
        AiStudioMachineSpec.assertTransition(
            stale,
            AiStudioIntent.Public.NewSession("project", 1, accepted),
            stale.copy(
                panes = listOf(stale.panes[0], StudioPane(1, projectId = "project", settings = captured, pageId = 1)),
                focusedPaneId = 1,
                nextPageId = 2,
            ),
        )
        AiStudioMachineSpec.assertTransition(
            stale,
            AiStudioIntent.Public.OpenBeside(null, accepted),
            stale.copy(
                panes = listOf(stale.panes[0], StudioPane(1, settings = captured, pageId = 1)),
                focusedPaneId = 1,
                nextPageId = 2,
            ),
        )
        val showingChat = stale.copy(panes = listOf(StudioPane(0, sessionId = "chat"), stale.panes[1]))
        AiStudioMachineSpec.assertTransition(
            showingChat,
            AiStudioIntent.Public.Edit("chat", SessionEdit.SetArchived(true), accepted),
            showingChat.copy(
                panes = listOf(StudioPane(0, settings = captured, pageId = 1), showingChat.panes[1]),
                nextPageId = 2,
            ),
            effects = listOf(AiStudioEffect.Apply("chat", SessionEdit.SetArchived(true))),
        )
    }

    @Test
    fun `models discovered after blank startup freeze native default in both drafts`() {
        val blank = first.copy(modelId = "", nativeEffort = null)
        val initial = ready.copy(
            settings = blank,
            nextSessionSettings = blank,
            panes = listOf(StudioPane(0, settings = blank), StudioPane(1, settings = blank)),
        )
        val selected = blank.copy(modelId = "engine-route", nativeEffort = StudioEffortOverride("engine-route", null))
        val discovered = initial.copy(
            settings = selected,
            nextSessionSettings = selected,
            panes = listOf(StudioPane(0, settings = selected), StudioPane(1, settings = selected)),
        )
        AiStudioMachineSpec.assertTransition(
            initial,
            AiStudioIntent.Internal.ModelsChanged(listOf("engine-route"), mapOf("engine-route" to null)),
            discovered,
        )
        val other = selected.copy(nativeEffort = StudioEffortOverride("engine-route", "high"))
        val edited = discovered.copy(
            settings = other,
            panes = listOf(discovered.panes[0], discovered.panes[1].copy(settings = other)),
            settingsVersion = discovered.settingsVersion.copy(revision = 1),
        )
        AiStudioMachineSpec.assertTransition(
            discovered,
            AiStudioIntent.Public.UpdateSettings(other, 1),
            edited,
            effects = listOf(AiStudioEffect.SaveSettings(other, edited.settingsVersion)),
        )
        AiStudioMachineSpec.assertTransition(
            edited,
            AiStudioIntent.Public.Submit(0, "hello"),
            edited.copy(
                panes = listOf(edited.panes[0].copy(isCreating = true, createRequestId = 0), edited.panes[1]),
                nextCreateRequestId = 1,
            ),
            effects = listOf(AiStudioEffect.CreateSession(0, null, "hello", selected, requestId = 0)),
        )
    }

    @Test
    fun `stale effort choice cannot edit a creating or replaced page`() {
        val update = AiStudioIntent.Public.UpdateSettings(first, 0, expectedPageId = 0, expectedModelId = first.modelId)
        val creating = ready.copy(panes = listOf(ready.panes[0].copy(isCreating = true), ready.panes[1]))
        val replaced = ready.copy(panes = listOf(ready.panes[0].copy(pageId = 1), ready.panes[1]), nextPageId = 2)
        AiStudioMachineSpec.assertIgnored(creating, update)
        AiStudioMachineSpec.assertIgnored(replaced, update)
    }

    @Test
    fun `blank observation cannot erase known exact seed and observer failure changes no draft`() {
        val observed = ready.copy(nextSessionSettings = latest)
        AiStudioMachineSpec.assertTransition(
            observed,
            AiStudioIntent.Internal.DefaultsObserved(first.copy(modelId = "")),
            observed,
        )
        assertEquals(null, AiStudioMachineSpec.onEffectFailure(AiStudioEffect.ObserveDefaults, IllegalStateException()))
    }
}
