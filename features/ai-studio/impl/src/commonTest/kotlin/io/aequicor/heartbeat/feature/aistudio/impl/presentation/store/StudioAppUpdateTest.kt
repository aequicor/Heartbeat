package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.api.StudioPane
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioScreenHost
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioModelTest.Fixture
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AppUpdateFailureUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AppUpdateStageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AppUpdateUi
import io.aequicor.heartbeat.feature.appupdate.api.AppRelease
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateFailure
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateIntent
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateState
import io.aequicor.heartbeat.feature.appupdate.api.InstallProgress
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StudioAppUpdateTest {
    private val ready = AiStudioState.Ready(
        panes = listOf(StudioPane(0)),
        focusedPaneId = 0,
        settings = DefaultRunSettings,
        defaultProjectId = "p-heartbeat",
    )
    private val release = AppRelease(
        version = "1.4.0",
        assetName = "Heartbeat-1.4.0-macos-arm64.dmg",
        url = "https://github.com/aequicor/Heartbeat/releases/download/v1.4.0/Heartbeat-1.4.0-macos-arm64.dmg",
        sha256 = "a".repeat(64),
        size = 200,
        pageUrl = "https://github.com/aequicor/Heartbeat/releases/tag/v1.4.0",
    )

    @Test
    fun `the sidebar offers a newer release and follows its installation`() = runTest {
        val fixture = Fixture(this, ready)
        val updates = fixture.efforts.appUpdate
        val screen = fixture.subscribe()
        assertNull(screen.states.value.appUpdate)

        updates.state.value = AppUpdateState.Available(release)
        runCurrent()
        assertEquals(
            AppUpdateUi("1.4.0", AppUpdateStageUi.Ready, pageUrl = release.pageUrl),
            screen.states.value.appUpdate,
        )

        updates.state.value = AppUpdateState.Installing(release, InstallProgress.Downloading(50, 200))
        runCurrent()
        assertEquals(AppUpdateStageUi.Downloading, screen.states.value.appUpdate?.stage)
        assertEquals(25, screen.states.value.appUpdate?.percent)

        updates.state.value = AppUpdateState.Restarting(release)
        runCurrent()
        assertEquals(AppUpdateStageUi.Restarting, screen.states.value.appUpdate?.stage)
    }

    @Test
    fun `a failed attempt offers a retry and a protected installation a manual download`() = runTest {
        val fixture = Fixture(this, ready)
        val updates = fixture.efforts.appUpdate
        val screen = fixture.subscribe()

        updates.state.value = AppUpdateState.Available(release, AppUpdateFailure.Corrupted)
        runCurrent()
        assertEquals(AppUpdateFailureUi.Corrupted, screen.states.value.appUpdate?.failure)
        assertEquals(AppUpdateStageUi.Ready, screen.states.value.appUpdate?.stage)

        updates.state.value = AppUpdateState.Available(release, AppUpdateFailure.ManualInstallRequired)
        runCurrent()
        assertEquals(
            AppUpdateUi("1.4.0", AppUpdateStageUi.ManualInstall, pageUrl = release.pageUrl),
            screen.states.value.appUpdate,
        )
    }

    @Test
    fun `without an update machine nothing is offered`() = runTest {
        val fixture = Fixture(this, ready)
        fixture.efforts.appUpdate.ref.value = null
        val screen = fixture.subscribe()
        runCurrent()
        assertNull(screen.states.value.appUpdate)
    }

    @Test
    fun `a remote host neither observes nor installs native app updates`() = runTest {
        val fixture = Fixture(
            this,
            ready,
            host = StudioScreenHost.Remote,
        )
        val screen = fixture.subscribe()
        fixture.efforts.appUpdate.state.value = AppUpdateState.Available(release)
        fixture.model.store.intent(AiStudioScreenIntent.InstallAppUpdate)
        runCurrent()

        assertNull(screen.states.value.appUpdate)
        assertEquals(emptyList(), fixture.efforts.appUpdate.sent)
    }

    @Test
    fun `the button asks the update machine to install`() = runTest {
        val fixture = Fixture(this, ready)
        fixture.subscribe()

        fixture.model.store.intent(AiStudioScreenIntent.InstallAppUpdate)
        runCurrent()

        assertEquals(listOf<AppUpdateIntent>(AppUpdateIntent.Public.Install), fixture.efforts.appUpdate.sent)
    }
}
