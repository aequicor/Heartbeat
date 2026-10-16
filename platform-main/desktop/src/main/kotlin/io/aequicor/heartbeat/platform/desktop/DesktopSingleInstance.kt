package io.aequicor.heartbeat.platform.desktop

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.win32.StdCallLibrary
import io.aequicor.heartbeat.core.logging.Log
import java.awt.EventQueue
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Outcome of claiming the single release instance before the graph opens the user's data. */
internal sealed interface DesktopInstanceClaim {
    /** This process owns the instance until [DesktopInstance.close]; repeated launches activate its window. */
    data class Owner(val instance: DesktopInstance) : DesktopInstanceClaim

    /** A live process owns the user's data and was asked to show its window; this launch must end. */
    data object Forwarded : DesktopInstanceClaim

    /** The guard could not be set up; the launch proceeds without it, as it did before the guard existed. */
    data object Unguarded : DesktopInstanceClaim
}

/**
 * Claims the per-user instance in [directory]. Windows and Linux start a new process for every shortcut launch,
 * even while the running one hides in the tray; the lock keeps that process from opening the same data, and a
 * connection to the owner's socket asks it to show its window. [allowForeground] lets the owner take the
 * foreground from this launch, which the user just started. A lock taken by a crashed process is released by the
 * operating system, and its socket file is replaced.
 */
internal fun claimDesktopInstance(
    directory: Path,
    post: (Runnable) -> Unit = EventQueue::invokeLater,
    allowForeground: () -> Unit = ::allowForegroundActivation,
    connectAttempts: Int = CONNECT_ATTEMPTS,
): DesktopInstanceClaim {
    val log = Log.tag("DesktopInstance")
    val channel = try {
        ensurePrivateDirectory(directory)
        FileChannel.open(directory.resolve(LOCK_NAME), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    } catch (e: IOException) {
        log.w(e) { "Single-instance lock is unavailable; launching without it" }
        return DesktopInstanceClaim.Unguarded
    }
    val socket = instanceSocket(directory)
    val lock = try {
        channel.tryLockInProcess()
    } catch (e: IOException) {
        log.w(e) { "Single-instance lock cannot be taken; launching without it" }
        channel.close()
        return DesktopInstanceClaim.Unguarded
    }
    if (lock == null) {
        channel.close()
        allowForeground()
        if (forwardActivation(socket, connectAttempts)) {
            log.i { "Heartbeat is already running; asked it to show its window" }
        }
        return DesktopInstanceClaim.Forwarded
    }
    return try {
        ensurePrivateDirectory(socket.parent)
        Files.deleteIfExists(socket)
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        try {
            server.bind(UnixDomainSocketAddress.of(socket))
        } catch (e: IOException) {
            server.close()
            throw e
        }
        log.d { "Single instance claimed" }
        DesktopInstanceClaim.Owner(DesktopInstance(channel, lock, server, socket, post))
    } catch (e: IOException) {
        // Without a socket repeated launches could not reach this window, so they must not be locked out either.
        log.w(e) { "Activation socket cannot be bound; launching without the single-instance lock" }
        lock.release()
        channel.close()
        DesktopInstanceClaim.Unguarded
    }
}

/**
 * The running instance: accepts activation requests from repeated launches and routes them to the window on the
 * [post] thread (the AWT event queue), where every handler change happens too. A request that arrives before the
 * window registers is delivered on registration.
 */
internal class DesktopInstance(
    private val lockChannel: FileChannel,
    private val lock: FileLock,
    private val server: ServerSocketChannel,
    private val socket: Path,
    private val post: (Runnable) -> Unit,
) : AutoCloseable {
    private val log = Log.tag("DesktopInstance")
    private val isClosed = AtomicBoolean(false)
    private var handler: (() -> Unit)? = null
    private var isPending = false

    init {
        thread(isDaemon = true, name = "heartbeat-instance") { acceptRequests() }
    }

    /** Must be called on the [post] thread; the returned registration stops routing requests to [onActivate]. */
    fun onActivate(onActivate: () -> Unit): AutoCloseable {
        handler = onActivate
        if (isPending) {
            isPending = false
            onActivate()
        }
        return AutoCloseable { if (handler === onActivate) handler = null }
    }

    private fun acceptRequests() {
        try {
            while (true) {
                // The connection itself is the request; nothing is read, so a silent client cannot block the loop.
                server.accept().close()
                post(Runnable(::activate))
            }
        } catch (e: IOException) {
            if (isClosed.get()) return
            log.w(e) { "Activation socket stopped accepting; repeated launches cannot show this window" }
        }
    }

    private fun activate() {
        if (isClosed.get()) return
        log.i { "A repeated launch asked to show the window" }
        val current = handler
        if (current == null) isPending = true else current()
    }

    override fun close() {
        if (!isClosed.compareAndSet(false, true)) return
        try {
            server.close()
            Files.deleteIfExists(socket)
            lock.release()
        } catch (e: IOException) {
            log.w(e) { "Single-instance resources were not fully released" }
        } finally {
            lockChannel.close()
        }
    }
}

/** `run` in the per-user data root that `JvmStorageRoot` of `core:datastore:impl` uses: the lock guards that data. */
internal fun desktopInstanceDirectory(
    os: String = System.getProperty("os.name").orEmpty(),
    home: String = System.getProperty("user.home").orEmpty(),
    appData: String? = System.getenv("APPDATA"),
    xdgDataHome: String? = System.getenv("XDG_DATA_HOME"),
): Path {
    val root = when {
        os.startsWith("Windows", ignoreCase = true) ->
            Path.of(appData?.takeIf(String::isNotBlank) ?: "$home/AppData/Roaming", "Aequicor", "Heartbeat")

        os.startsWith("Mac", ignoreCase = true) -> Path.of(home, "Library", "Application Support", "Heartbeat")

        else -> Path.of(xdgDataHome?.takeIf { it.isNotBlank() && Path.of(it).isAbsolute } ?: "$home/.local/share")
            .resolve("heartbeat")
    }
    return root.resolve("run")
}

/**
 * `<directory>/instance.sock`, or a per-user temporary directory keyed by that path when it is too long for a Unix
 * domain socket address.
 */
internal fun instanceSocket(directory: Path): Path {
    val preferred = directory.resolve(SOCKET_NAME).toAbsolutePath().normalize()
    val bytes = preferred.toString().toByteArray(Charsets.UTF_8)
    if (bytes.size <= MAX_SOCKET_PATH_BYTES) return preferred
    val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).take(DIGEST_CHARS)
    return Path.of(System.getProperty("java.io.tmpdir"), "heartbeat-instance-$digest", SOCKET_NAME)
        .toAbsolutePath().normalize()
}

/** A lock held by this JVM (another claim in the same process) counts as held, like one of another process. */
private fun FileChannel.tryLockInProcess(): FileLock? = try {
    tryLock()
} catch (e: OverlappingFileLockException) {
    Log.tag("DesktopInstance").w(e) { "Single-instance lock is already held in this process" }
    null
}

/** The owner may still be binding its socket right after taking the lock, so connecting is retried briefly. */
private fun forwardActivation(socket: Path, attempts: Int): Boolean {
    val log = Log.tag("DesktopInstance")
    var isDelivered = false
    var attempt = 0
    while (!isDelivered && attempt < attempts) {
        if (attempt > 0) Thread.sleep(CONNECT_RETRY_MS)
        attempt++
        isDelivered = try {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { it.connect(UnixDomainSocketAddress.of(socket)) }
        } catch (e: IOException) {
            if (attempt == attempts) log.w(e) { "Running Heartbeat did not accept the request to show its window" }
            false
        }
    }
    return isDelivered
}

private fun ensurePrivateDirectory(directory: Path) {
    Files.createDirectories(directory)
    if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString(OWNER_ONLY))
    }
}

/**
 * Windows lets only the foreground process raise a window; the launch the user just started hands that right to
 * any process so the owner's window comes to the front instead of only flashing on the taskbar.
 */
private fun allowForegroundActivation() {
    if (!Platform.isWindows()) return
    try {
        if (!foregroundUser.AllowSetForegroundWindow(ASFW_ANY)) {
            Log.tag("DesktopInstance").d { "Foreground handoff refused: ${Native.getLastError()}" }
        }
    } catch (e: LinkageError) {
        Log.tag("DesktopInstance").w(e) { "Foreground handoff is unavailable; the window may only flash" }
    }
}

private val foregroundUser by lazy { Native.load("user32", ForegroundUser32::class.java) }

@Suppress("FunctionNaming") // Fixed Win32 ABI entry point.
private interface ForegroundUser32 : StdCallLibrary {
    fun AllowSetForegroundWindow(processId: Int): Boolean
}

private const val ASFW_ANY = -1
private const val LOCK_NAME = "instance.lock"
private const val SOCKET_NAME = "instance.sock"
private const val CONNECT_ATTEMPTS = 30
private const val CONNECT_RETRY_MS = 100L

// sun_path is 104 bytes on macOS and 108 on Windows, including the terminator.
private const val MAX_SOCKET_PATH_BYTES = 100
private const val DIGEST_CHARS = 12
private const val OWNER_ONLY = "rwx------"
