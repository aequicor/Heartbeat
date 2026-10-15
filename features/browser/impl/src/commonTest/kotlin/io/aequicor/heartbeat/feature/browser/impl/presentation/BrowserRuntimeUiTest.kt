package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserRuntimeFailure
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserRuntimePreparation
import kotlin.test.Test
import kotlin.test.assertEquals

class BrowserRuntimeUiTest {
    @Test
    fun `unknown download length shows received bytes while known length has bounded progress`() {
        assertEquals(
            BrowserRuntimeUi.Downloading(3, null),
            BrowserRuntimePreparation.Downloading(3L * 1024 * 1024, null).toPresentation(),
        )
        assertEquals(
            BrowserRuntimeUi.Downloading(0, null),
            BrowserRuntimePreparation.Downloading(1, 0).toPresentation(),
        )
        assertEquals(
            BrowserRuntimeUi.Downloading(0, 100),
            BrowserRuntimePreparation.Downloading(200, 100).toPresentation(),
        )
    }

    @Test
    fun `finished installation still needs native startup and safe failure survives its exception`() {
        assertEquals(BrowserRuntimeUi.Preparing, BrowserRuntimePreparation.Ready.toPresentation())
        assertEquals(
            BrowserRuntimeUi.Failed(BrowserRuntimeProblem.InvalidRuntime),
            BrowserRuntimePreparation.Failed(BrowserRuntimeFailure.InvalidRuntime).toPresentation().failed(),
        )
        assertEquals(BrowserRuntimeUi.Failed(BrowserRuntimeProblem.Unavailable), BrowserRuntimeUi.Preparing.failed())
    }
}
