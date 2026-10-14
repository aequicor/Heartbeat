package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.hosts

import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBrowser
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeProtocol
import java.nio.file.Path

/** Where a browser keeps user data and looks for native-messaging hosts. */
internal sealed interface BrowserLocation {
    val browser: ChromiumBrowser

    /** The browser's user data directory; it exists once the browser ran for this user. */
    val userData: Path

    /** macOS/Linux: the host manifest file in the browser's own directory. */
    data class Directory(override val browser: ChromiumBrowser, override val userData: Path, val manifest: Path) :
        BrowserLocation

    /** Windows: the per-user registry key whose default value names the manifest file. */
    data class RegistryKey(override val browser: ChromiumBrowser, override val userData: Path, val key: String) :
        BrowserLocation
}

/** Per-user locations of the supported browsers (no administrator rights needed). */
internal object BrowserLocations {
    private val macDirectories = mapOf(
        ChromiumBrowser.Chrome to "Google/Chrome",
        ChromiumBrowser.Edge to "Microsoft Edge",
        ChromiumBrowser.Brave to "BraveSoftware/Brave-Browser",
        ChromiumBrowser.Chromium to "Chromium",
        ChromiumBrowser.Vivaldi to "Vivaldi",
    )
    private val windowsVendors = mapOf(
        ChromiumBrowser.Chrome to ("Google\\Chrome" to "Google/Chrome/User Data"),
        ChromiumBrowser.Edge to ("Microsoft\\Edge" to "Microsoft/Edge/User Data"),
        ChromiumBrowser.Brave to ("BraveSoftware\\Brave-Browser" to "BraveSoftware/Brave-Browser/User Data"),
        ChromiumBrowser.Chromium to ("Chromium" to "Chromium/User Data"),
        ChromiumBrowser.Vivaldi to ("Vivaldi" to "Vivaldi/User Data"),
    )
    private val linuxDirectories = mapOf(
        ChromiumBrowser.Chrome to "google-chrome",
        ChromiumBrowser.Edge to "microsoft-edge",
        ChromiumBrowser.Brave to "BraveSoftware/Brave-Browser",
        ChromiumBrowser.Chromium to "chromium",
        ChromiumBrowser.Vivaldi to "vivaldi",
    )

    /** Linux: native browser packages store manifests below the XDG configuration directory. */
    fun linux(home: Path, configHome: String?): List<BrowserLocation> {
        val config = configHome?.takeIf { it.isNotBlank() }?.let(Path::of)?.takeIf { it.isAbsolute }
            ?: home.resolve(".config")
        return ChromiumBrowser.entries.map { browser ->
            val userData = config.resolve(checkNotNull(linuxDirectories[browser]))
            BrowserLocation.Directory(
                browser = browser,
                userData = userData,
                manifest = userData.resolve("NativeMessagingHosts").resolve("${BridgeProtocol.HOST_NAME}.json"),
            )
        }
    }

    /** macOS: `~/Library/Application Support/<browser>/NativeMessagingHosts/<host>.json`. */
    fun macOs(home: Path): List<BrowserLocation> = ChromiumBrowser.entries.map { browser ->
        val userData = home.resolve("Library/Application Support").resolve(checkNotNull(macDirectories[browser]))
        BrowserLocation.Directory(
            browser = browser,
            userData = userData,
            manifest = userData.resolve("NativeMessagingHosts").resolve("${BridgeProtocol.HOST_NAME}.json"),
        )
    }

    /** Windows: `HKCU\Software\<vendor>\NativeMessagingHosts\<host>`, user data under `%LOCALAPPDATA%`. */
    fun windows(localAppData: Path): List<BrowserLocation> = ChromiumBrowser.entries.map { browser ->
        val (vendor, userData) = checkNotNull(windowsVendors[browser])
        BrowserLocation.RegistryKey(
            browser = browser,
            userData = localAppData.resolve(userData),
            key = "Software\\$vendor\\NativeMessagingHosts\\${BridgeProtocol.HOST_NAME}",
        )
    }
}
