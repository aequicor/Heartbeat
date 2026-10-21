package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineSwitch
import io.aequicor.heartbeat.feature.aistudio.api.StudioPane
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionConfiguration
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingChange
import io.aequicor.heartbeat.feature.aistudio.impl.data.InMemoryStudioRepository
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioModel
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRepository
import io.aequicor.heartbeat.feature.aistudio.impl.domain.TestClock
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class StudioModelSelectionTest {
    @Test
    fun `same binding selection uses native configuration when supported and otherwise carries context`() = runTest {
        val source = EngineTarget(EngineId("engine"), EngineBindingId("route"), ModelId("old"))
        val target = source.copy(model = ModelId("next"))
        val initial = AiStudioState.Ready(
            focusedPaneId = 0,
            settings = DefaultRunSettings,
            defaultProjectId = null,
            panes = listOf(StudioPane(0, sessionId = "chat")),
            configurations = mapOf(
                "chat" to StudioSessionConfiguration(StudioSessionSettings(source.studioModelId())),
            ),
            isEngineSwitchingAvailable = true,
        )
        for (native in listOf(false, true)) {
            val repository = object : StudioRepository by InMemoryStudioRepository(TestClock(this)) {
                override fun observeModels() = flowOf(
                    listOf(StudioModel(target.studioModelId(), "Next", isModelSwitchSupported = native)),
                )
            }
            val fixture = AiStudioModelTest.Fixture(this, initial, repository)
            fixture.subscribe()
            fixture.model.store.intent(
                AiStudioScreenIntent.SelectModel(target.studioModelId(), 0, source.studioModelId(), "chat"),
            )
            runCurrent()
            val expected = if (native) {
                AiStudioIntent.Public.ChangeSessionSetting("chat", StudioSettingChange.Model(target.studioModelId()))
            } else {
                AiStudioIntent.Public.SwitchSessionEngine("chat", StudioEngineSwitch(source, target))
            }
            assertEquals(
                expected,
                fixture.machine.sent.filterNot { it is AiStudioIntent.Public.ObserveUsageTargets }.last(),
            )
        }
    }

    @Test
    fun `delayed model selection cannot change another chat or new page after pane navigation`() = runTest {
        val target = EngineTarget(EngineId("engine"), EngineBindingId("route"), ModelId("old"))
        val old = target.studioModelId()
        val next = target.copy(engine = EngineId("other")).studioModelId()
        val initial = AiStudioState.Ready(
            focusedPaneId = 0,
            settings = DefaultRunSettings,
            defaultProjectId = null,
            panes = listOf(StudioPane(0, sessionId = "original")),
            configurations = mapOf(
                "original" to StudioSessionConfiguration(StudioSessionSettings(old, null, ApprovalMode.Ask)),
                "replacement" to StudioSessionConfiguration(StudioSessionSettings(old, null, ApprovalMode.Ask)),
            ),
            isEngineSwitchingAvailable = true,
        )
        val fixture = AiStudioModelTest.Fixture(this, initial)
        val screen = fixture.subscribe()
        val delayed = AiStudioScreenIntent.SelectModel(next, 0, old, expectedSessionId = "original")
        for (destination in listOf("replacement", null)) {
            fixture.machine.state.value = initial.copy(panes = listOf(StudioPane(0, sessionId = destination)))
            runCurrent()
            fixture.model.store.intent(delayed)
            runCurrent()
        }
        assertEquals(
            listOf<AiStudioIntent>(AiStudioIntent.Public.Start),
            fixture.machine.sent.filterNot { it is AiStudioIntent.Public.ObserveUsageTargets },
        )
        assertEquals(DefaultRunSettings.toUi(), screen.states.value.settings)
        fixture.machine.state.value = initial
        runCurrent()
        fixture.model.store.intent(delayed.copy(expectedSessionId = null))
        fixture.model.store.intent(delayed.copy(expectedSessionId = ""))
        runCurrent()
        assertEquals(
            listOf<AiStudioIntent>(AiStudioIntent.Public.Start),
            fixture.machine.sent.filterNot { it is AiStudioIntent.Public.ObserveUsageTargets },
        )
        fixture.model.store.intent(delayed)
        runCurrent()
        assertEquals(
            AiStudioIntent.Public.SwitchSessionEngine(
                "original",
                StudioEngineSwitch(target, target.copy(engine = EngineId("other"))),
            ),
            fixture.machine.sent.filterNot { it is AiStudioIntent.Public.ObserveUsageTargets }.last(),
        )
    }
}
