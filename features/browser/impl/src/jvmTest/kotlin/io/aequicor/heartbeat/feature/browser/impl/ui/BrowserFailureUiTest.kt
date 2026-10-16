package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.browser.api.BrowserLoadFailure
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserFailureUi
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserPhase
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserScreenError
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserScreenIntent
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserScreenState
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserSecurityUi
import io.aequicor.heartbeat.feature.browser.impl.resources.Res
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_failure_details_collapsed
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_failure_details_expanded
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_security_certificate_exception
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_security_https
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_security_insecure
import org.jetbrains.compose.resources.stringResource
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class BrowserFailureUiTest {
    private val failure = BrowserFailureUi(
        "tab",
        BrowserLoadFailure(
            "https://example.com/",
            "ERR_CERT_DATE_INVALID",
            isCertificate = true,
            isProceedAllowed = true,
            id = "one",
        ),
    )
    private val fatal = failure.copy(failure = failure.failure.copy(isProceedAllowed = false))

    @Test
    fun `invalid address feedback stays visible above a previous load failure`() = runSkikoComposeUiTest {
        setContent {
            HbTheme {
                BrowserScreenContent(
                    screen().copy(
                        error = BrowserScreenError.InvalidAddress,
                    ),
                    {},
                ) {}
            }
        }
        onNodeWithTag("browser-error").assertIsDisplayed()
        onNodeWithTag("browser-failure-page").assertIsDisplayed()
    }

    @Test
    fun `certificate warning replaces native content and hides bypass until expanded`() = runSkikoComposeUiTest {
        var state by mutableStateOf(screen())
        var nativeCount = 0
        val sent = mutableListOf<BrowserScreenIntent>()
        setContent {
            HbTheme {
                BrowserScreenContent(state, {
                    sent += it
                    if (it == BrowserScreenIntent.ToggleErrorDetails) state = state.copy(isErrorDetailsExpanded = true)
                }) {
                    DisposableEffect(Unit) {
                        nativeCount++
                        onDispose { nativeCount-- }
                    }
                }
            }
        }
        onNodeWithTag("browser-failure-page").assertIsDisplayed()
        onNodeWithTag("browser-proceed-certificate").assertDoesNotExist()
        onNodeWithTag("browser-back-to-safety").performClick()
        onNodeWithTag("browser-back-to-safety").performKeyInput {
            pressKey(Key.Tab)
            pressKey(Key.Enter)
        }
        onNodeWithTag("browser-proceed-certificate").performScrollTo().performClick()
        runOnIdle {
            assertEquals(0, nativeCount)
            assertEquals(BrowserScreenIntent.ProceedCertificate(failure), sent.last())
            state = screen().copy(failure = fatal, isErrorDetailsExpanded = true)
        }
        onNodeWithTag("browser-proceed-certificate").assertDoesNotExist()
        onNodeWithTag("browser-back-to-safety").performScrollTo().performClick()
        runOnIdle {
            assertEquals(BrowserScreenIntent.ReturnToSafety(fatal), sent.last())
        }
    }

    @Test
    fun `network failures show exact code and reload`() = runSkikoComposeUiTest {
        val sent = mutableListOf<BrowserScreenIntent>()
        setContent {
            HbTheme {
                BrowserScreenContent(
                    screen().copy(failure = network("ERR_NAME_NOT_RESOLVED")),
                    { sent += it },
                )
            }
        }
        onNodeWithText("ERR_NAME_NOT_RESOLVED").assertIsDisplayed()
        onNodeWithTag("browser-failure-reload").performClick()
        runOnIdle { assertEquals(BrowserScreenIntent.Reload, sent.single()) }
    }

    @Test
    fun `network failure without specific details offers no duplicate details`() = runSkikoComposeUiTest {
        setContent {
            HbTheme {
                val unreachable = screen().copy(
                    failure = network("ERR_ADDRESS_UNREACHABLE"),
                    error = BrowserScreenError.LoadFailed,
                )
                BrowserScreenContent(unreachable, {}) {
                    error("Native content must stay hidden behind a network failure page")
                }
            }
        }
        onNodeWithTag("browser-failure-reload").assertIsDisplayed()
        onNodeWithTag("browser-failure-details").assertDoesNotExist()
        onNodeWithTag("browser-error").assertDoesNotExist()
    }

    @Test
    fun `agent controlled warning explains why no decision is offered`() = runSkikoComposeUiTest {
        setContent {
            HbTheme {
                BrowserScreenContent(screen().copy(failure = failure.copy(isDecisionAvailable = false)), {})
            }
        }
        onNodeWithTag("browser-failure-agent").assertIsDisplayed()
        onNodeWithTag("browser-back-to-safety").assertDoesNotExist()
        onNodeWithTag("browser-failure-details").assertDoesNotExist()
    }

    @Test
    fun `details toggle announces expansion`() = runSkikoComposeUiTest {
        var state by mutableStateOf(screen())
        var collapsed = ""
        var expanded = ""
        setContent {
            collapsed = stringResource(Res.string.browser_failure_details_collapsed)
            expanded = stringResource(Res.string.browser_failure_details_expanded)
            HbTheme { BrowserScreenContent(state, {}) }
        }
        onNodeWithTag("browser-failure-details", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, collapsed))
        state = state.copy(isErrorDetailsExpanded = true)
        onNodeWithTag("browser-failure-details", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expanded))
    }

    @Test
    fun `connection indicator names insecure states and keeps https neutral`() = runSkikoComposeUiTest {
        var security by mutableStateOf(BrowserSecurityUi.Http)
        var insecure = ""
        var exception = ""
        var https = ""
        setContent {
            insecure = stringResource(Res.string.browser_security_insecure)
            exception = stringResource(Res.string.browser_security_certificate_exception)
            https = stringResource(Res.string.browser_security_https)
            HbTheme { BrowserSecurityIndicator(security) }
        }
        onNodeWithText(insecure).assertIsDisplayed()
        security = BrowserSecurityUi.CertificateException
        onNodeWithText(insecure).assertIsDisplayed()
        onNodeWithContentDescription(exception).assertExists()
        security = BrowserSecurityUi.Https
        onNodeWithText(insecure).assertDoesNotExist()
        onNodeWithContentDescription(https).assertExists()
        security = BrowserSecurityUi.Unknown
        onNodeWithText(insecure).assertDoesNotExist()
        onNodeWithContentDescription(https).assertDoesNotExist()
    }

    @Test
    fun `warnings fit compact and desktop viewports in light and dark themes`() {
        for (width in listOf(420, 1280)) {
            for (dark in listOf(false, true)) {
                runSkikoComposeUiTest(size = Size(width.toFloat(), 700f)) {
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(1f)) {
                            HbTheme(darkTheme = dark, dimensions = HbDimensions.Desktop) {
                                BrowserScreenContent(screen().copy(isErrorDetailsExpanded = true), {})
                            }
                        }
                    }
                    onNodeWithTag("browser-back-to-safety").assertIsDisplayed()
                    onNodeWithTag("browser-proceed-certificate").performScrollTo().assertIsDisplayed()
                    onNodeWithTag("browser-open").assertIsDisplayed()
                    val folder = File("build/reports/browser").apply { mkdirs() }
                    ImageIO.write(captureToImage().toAwtImage(), "png", File(folder, "certificate-$width-$dark.png"))
                }
            }
        }
    }

    private fun network(code: String) = BrowserFailureUi(
        "tab",
        BrowserLoadFailure("https://example.com/", code, id = "network"),
    )

    private fun screen() = BrowserScreenState(
        phase = BrowserPhase.Ready,
        url = failure.url,
        address = failure.url,
        selectedTabId = "tab",
        failure = failure,
        security = BrowserSecurityUi.CertificateException,
    )
}
