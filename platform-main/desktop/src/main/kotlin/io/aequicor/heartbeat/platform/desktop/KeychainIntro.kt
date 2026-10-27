package io.aequicor.heartbeat.platform.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
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
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.ResourceBundle

/**
 * First screen of a macOS launch of an installed build, shown before anything asks the Keychain: it explains the
 * system notice ("wants to use confidential information") and what to answer, so that notice does not come
 * unannounced. Shown once per installed version, because an update changes the code signature and macOS asks again.
 * Launches with local development secrets, other hosts and unversioned launches skip it.
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
    val seen = directory.resolve("keychain-intro-$version")
    if (Files.exists(seen)) return
    log.i { "showing the Keychain introduction version=$version" }
    show()
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
    application(exitProcessOnExit = false) {
        val state = rememberWindowState(size = DpSize.Unspecified, position = WindowPosition.PlatformDefault)
        Window(
            onCloseRequest = ::exitApplication,
            state = state,
            title = labels.getString("title"),
            resizable = false,
        ) {
            HbTheme { KeychainIntroContent(labels, onContinue = ::exitApplication) }
        }
    }
}

@Composable
private fun KeychainIntroContent(labels: ResourceBundle, onContinue: () -> Unit) {
    Column(
        modifier = Modifier.width(HbTheme.dimensions.keychainIntroWidth).padding(HbTheme.spacing.xl),
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
