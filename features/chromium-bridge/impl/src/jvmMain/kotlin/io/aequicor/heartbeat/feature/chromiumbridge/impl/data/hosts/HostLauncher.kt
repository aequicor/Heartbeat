package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.hosts

import io.aequicor.heartbeat.feature.chromiumbridge.impl.domain.RegistrationProblem
import java.nio.file.Files
import java.nio.file.Path

/** How the browser-started wrapper runs the host. */
internal sealed interface HostLauncher {
    /** Installed build: the jpackage launcher in its private host mode (the jlink runtime has no `java`). */
    data class Packaged(val launcher: Path) : HostLauncher

    /** Build from a checkout: the running JVM's `java` with the app classpath and the host main class. */
    data class Development(val java: Path, val classpath: String) : HostLauncher

    /** This process cannot be relaunched as a host. */
    data class Unavailable(val problem: RegistrationProblem) : HostLauncher

    companion object {
        /** Detects the launcher of the current process. */
        fun current(isWindows: Boolean): HostLauncher {
            val packaged = System.getProperty("jpackage.app-path")?.let(Path::of)
            return if (packaged != null) packaged(packaged) else development(isWindows)
        }

        private fun packaged(launcher: Path): HostLauncher = when {
            "/AppTranslocation/" in launcher.toString() -> Unavailable(RegistrationProblem.Translocated)
            Files.isRegularFile(launcher) -> Packaged(launcher.toAbsolutePath())
            else -> noLauncher()
        }

        private fun development(isWindows: Boolean): HostLauncher {
            val java = Path.of(System.getProperty("java.home"), "bin", if (isWindows) "java.exe" else "java")
            val classpath = System.getProperty("java.class.path").orEmpty()
            val isUsable = Files.isRegularFile(java) && classpath.isNotEmpty()
            return if (isUsable) Development(java, classpath) else noLauncher()
        }

        private fun noLauncher() = Unavailable(RegistrationProblem.NoLauncher)
    }
}
