package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.hosts

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.chromiumbridge.api.BrowserHost
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBrowser
import io.aequicor.heartbeat.feature.chromiumbridge.api.HostRegistration
import io.aequicor.heartbeat.feature.chromiumbridge.api.HostRegistrationFailure
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.BridgePaths
import io.aequicor.heartbeat.feature.chromiumbridge.impl.domain.HostRegistrationException
import io.aequicor.heartbeat.feature.chromiumbridge.impl.domain.NativeHostRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/** Where this build registers its host. */
internal data class HostEnvironment(
    /** Browser locations of this OS; empty where native messaging is not supported. */
    val locations: List<BrowserLocation>,
    val isWindows: Boolean,
    /** The bridge's own files directory; wrappers and Windows manifests live in `host/` under it. */
    val bridgeDirectory: Path,
    val socket: Path,
    val launcher: HostLauncher,
)

/**
 * Registers the host the way each browser looks it up: a manifest file in the browser's directory on macOS, a
 * per-user registry key naming a manifest on Windows. The manifest starts a wrapper in the bridge directory, which
 * runs this build's launcher with the app socket. A browser counts as registered for this build only when its
 * manifest starts this build's wrapper for every Heartbeat extension; scanning rewrites that wrapper when the app
 * moved or the socket changed, so updates keep working without a new registration.
 */
internal class DesktopNativeHostRegistry(
    private val environment: () -> HostEnvironment,
    private val registry: WindowsRegistry,
    private val io: CoroutineDispatcher,
) : NativeHostRegistry {
    private val log = Log.tag("NativeHostRegistry")

    override suspend fun scan(): List<BrowserHost> = withContext(io) {
        val current = environment()
        val hosts = current.locations.map { status(current, it) }
        if (hosts.any { it.registration == HostRegistration.Current }) refreshWrapper(current)
        log.i { "Native hosts scanned ${hosts.joinToString { "${it.browser}=${it.registration}" }}" }
        hosts
    }

    override suspend fun register(browser: ChromiumBrowser): BrowserHost = withContext(io) {
        val current = environment()
        val location = current.locations.firstOrNull { it.browser == browser }
            ?: throw HostRegistrationException(HostRegistrationFailure.UnsupportedPlatform)
        (current.launcher as? HostLauncher.Unavailable)?.let { throw HostRegistrationException(it.problem) }
        try {
            val wrapper = writeWrapper(current)
            val manifest = HostManifest.encode(HostManifest.of(wrapper.toString()))
            when (location) {
                is BrowserLocation.Directory -> writeAtomically(location.manifest, manifest)

                is BrowserLocation.RegistryKey -> {
                    val file = manifestFile(current)
                    writeAtomically(file, manifest)
                    registry.setDefaultValue(location.key, file.toString())
                }
            }
        } catch (e: IOException) {
            log.w(e.redactedFailure()) { "Native host registration failed browser=$browser" }
            throw HostRegistrationException(HostRegistrationFailure.WriteFailed, e)
        }
        log.i { "Native host registered browser=$browser" }
        status(current, location)
    }

    override suspend fun unregister(browser: ChromiumBrowser): BrowserHost = withContext(io) {
        val current = environment()
        val location = current.locations.firstOrNull { it.browser == browser }
            ?: return@withContext BrowserHost(browser, isDetected = false, HostRegistration.NotRegistered)
        try {
            when (location) {
                is BrowserLocation.Directory -> Files.deleteIfExists(location.manifest)
                is BrowserLocation.RegistryKey -> registry.deleteKey(location.key)
            }
        } catch (e: IOException) {
            log.w(e.redactedFailure()) { "Native host removal failed browser=$browser" }
            throw HostRegistrationException(HostRegistrationFailure.WriteFailed, e)
        }
        log.i { "Native host unregistered browser=$browser" }
        status(current, location)
    }

    private fun status(current: HostEnvironment, location: BrowserLocation): BrowserHost {
        val isDetected = Files.isDirectory(location.userData)
        val manifestPath = when (location) {
            is BrowserLocation.Directory -> location.manifest
            is BrowserLocation.RegistryKey -> registeredManifest(location)
        }
        val registration = when {
            manifestPath == null || !Files.isRegularFile(manifestPath) -> HostRegistration.NotRegistered

            readManifest(manifestPath)?.startsWrapper(wrapperPath(current).toString()) == true ->
                HostRegistration.Current

            else -> HostRegistration.OtherBuild
        }
        return BrowserHost(location.browser, isDetected, registration)
    }

    private fun registeredManifest(location: BrowserLocation.RegistryKey): Path? = try {
        registry.defaultValue(location.key)?.let(Path::of)
    } catch (e: IOException) {
        log.w(e) { "Native host key is unreadable browser=${location.browser}" }
        null
    }

    private fun readManifest(path: Path): HostManifest? = try {
        HostManifest.decode(Files.readString(path))
    } catch (e: IOException) {
        log.w(e.redactedFailure()) { "Native host manifest is unreadable" }
        null
    } catch (e: IllegalArgumentException) {
        log.w(e.redactedFailure()) { "Native host manifest is not a host manifest" }
        null
    }

    private fun refreshWrapper(current: HostEnvironment) {
        if (current.launcher is HostLauncher.Unavailable) return
        try {
            val path = wrapperPath(current)
            val expected = wrapperContents(current)
            val isScriptStale = !Files.isRegularFile(path) || Files.readString(path) != expected.script
            val areArgumentsStale = expected.arguments != null &&
                readOrNull(argumentsPath(current)) != expected.arguments
            if (isScriptStale || areArgumentsStale) {
                writeWrapper(current)
                log.i { "Native host launcher refreshed" }
            }
        } catch (e: IOException) {
            log.w(e.redactedFailure()) { "Native host launcher could not be refreshed" }
        }
    }

    private fun writeWrapper(current: HostEnvironment): Path {
        val directory = current.bridgeDirectory.resolve(HOST_DIRECTORY)
        BridgePaths.ensurePrivateDirectory(directory)
        val contents = wrapperContents(current)
        contents.arguments?.let { writeAtomically(argumentsPath(current), it) }
        val wrapper = wrapperPath(current)
        writeAtomically(wrapper, contents.script)
        if (isPosix) Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString(OWNER_EXECUTABLE))
        return wrapper
    }

    private fun wrapperContents(current: HostEnvironment): WrapperContents {
        val launcher = current.launcher
        val arguments = (launcher as? HostLauncher.Development)?.let(HostLauncherScript::argumentFile)
        val script = HostLauncherScript.script(
            launcher = launcher,
            socket = current.socket,
            isWindows = current.isWindows,
            argumentFile = arguments?.let { argumentsPath(current) },
        )
        return WrapperContents(script, arguments)
    }

    private fun wrapperPath(current: HostEnvironment): Path = current.bridgeDirectory.resolve(HOST_DIRECTORY)
        .resolve(HostLauncherScript.fileName(current.launcher, current.isWindows)).toAbsolutePath()

    private fun argumentsPath(current: HostEnvironment): Path = current.bridgeDirectory.resolve(HOST_DIRECTORY)
        .resolve("${HostLauncherScript.fileName(current.launcher, isWindows = false)}.args").toAbsolutePath()

    private fun manifestFile(current: HostEnvironment): Path = current.bridgeDirectory.resolve(HOST_DIRECTORY)
        .resolve("${HostLauncherScript.fileName(current.launcher, isWindows = false)}.json").toAbsolutePath()

    private fun readOrNull(path: Path): String? = if (Files.isRegularFile(path)) Files.readString(path) else null

    private fun writeAtomically(target: Path, text: String) {
        Files.createDirectories(checkNotNull(target.parent))
        val temporary = Files.createTempFile(target.parent, ".${target.fileName}", ".tmp")
        try {
            Files.writeString(temporary, text)
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private data class WrapperContents(val script: String, val arguments: String?)

    private companion object {
        const val HOST_DIRECTORY = "host"
        const val OWNER_EXECUTABLE = "rwx------"
        val isPosix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()
    }
}
