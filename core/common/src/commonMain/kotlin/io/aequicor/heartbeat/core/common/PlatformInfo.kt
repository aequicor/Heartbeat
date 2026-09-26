package io.aequicor.heartbeat.core.common

/** Operating system the app is running on. Desktop (JVM) resolves it at runtime. */
public enum class HostPlatform { Android, Ios, MacOs, Windows, Linux }

/** Runtime information about the host platform; bound per platform in the DI graph. */
public interface PlatformInfo {
    /** Current operating system. */
    public val host: HostPlatform
}
