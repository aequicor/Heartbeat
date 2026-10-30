package io.aequicor.heartbeat.platform.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.platform.dibundle.createDesktopHostDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.ResourceBundle

/**
 * First screen of a macOS launch of an installed build, shown before anything asks the Keychain: it explains the
 * system notice ("wants to use confidential information") and what to answer, so that notice does not come
 * unannounced. Shown once per installed version, because an update changes the code signature and macOS asks again.
 * Launches with local development secrets, other hosts and unversioned launches skip it. It has no feature toggle: it
 * runs before the graph, where toggles cannot be read yet; a failure to show it is logged and never stops the launch.
 */
internal fun showKeychainIntroIfNeeded(
    isMac: Boolean,
    hasLocalSecrets: Boolean,
    version: String?,
    directory: Path,
    show: () -> Unit = ::showKeychainIntro,
) {
    if (!isMac || hasLocalSecrets || version == null) return
    val log = Log.tag("KeychainIntro")
    if (!VERSION.matches(version)) {
        log.w { "unexpected application version, the Keychain introduction is skipped" }
        return
    }
    val seen = directory.resolve("keychain-intro-$version")
    if (Files.exists(seen)) return
    log.i { "showing the Keychain introduction version=$version" }
    try {
        show()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The introduction only prepares the user for a system prompt: it never keeps the application from starting.
        log.w(e) { "Keychain introduction could not be shown" }
        return
    }
    try {
        Files.createDirectories(directory)
        Files.createFile(seen)
    } catch (e: IOException) {
        // The introduction then shows again at the next launch; the launch itself goes on.
        log.w(e) { "Keychain introduction marker could not be written" }
    }
}

/** Blocks until the user continues or closes the window. */
private fun showKeychainIntro() {
    val labels = ResourceBundle.getBundle("keychain_intro")
    val handoff = DesktopHandoff.directory()
    val dispatchers = createDesktopHostDispatchers()
    application(exitProcessOnExit = false) {
        val state = rememberWindowState(size = DpSize.Unspecified, position = WindowPosition(Alignment.Center))
        var isVisible by remember { mutableStateOf(handoff == null) }
        val continueLaunch = {
            handoff?.let(DesktopHandoff::requestActivation)
            exitApplication()
        }
        Window(
            onCloseRequest = continueLaunch,
            state = state,
            title = labels.getString("title"),
            resizable = false,
            visible = isVisible,
        ) {
            HbTheme { KeychainIntroContent(labels, onContinue = continueLaunch) }
            LaunchedEffect(handoff) {
                if (handoff != null) {
                    val presentation = withContext(dispatchers.io) {
                        DesktopHandoff.awaitHidden(handoff)
                        DesktopHandoff.presentation(handoff)
                    }
                    window.isAutoRequestFocus = presentation.isActivationRequested && !presentation.isMinimized
                    state.isMinimized = presentation.isMinimized
                    isVisible = true
                }
            }
        }
    }
}

@Composable
private fun KeychainIntroContent(labels: ResourceBundle, onContinue: () -> Unit) {
    Column(
        modifier = Modifier.background(HbTheme.surfaces.backdrop)
            .width(HbTheme.dimensions.keychainIntroWidth).padding(HbTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(HbTheme.spacing.m),
    ) {
        HbText(
            labels.getString("heading"),
            Modifier.semantics { heading() },
            style = HbTheme.typography.title,
        )
        HbText(labels.getString("body"))
        HbText(labels.getString("advice"), color = HbTheme.colors.textSecondary)
        HbButton(text = labels.getString("continue"), onClick = onContinue)
    }
}

private val VERSION = Regex("[0-9A-Za-z.+-]+")
