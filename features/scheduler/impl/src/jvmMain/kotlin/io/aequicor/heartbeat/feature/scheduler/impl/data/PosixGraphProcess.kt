package io.aequicor.heartbeat.feature.scheduler.impl.data

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.StringArray
import com.sun.jna.ptr.IntByReference
import io.aequicor.heartbeat.core.logging.Log
import java.io.InputStream
import java.io.OutputStream

/** A child in its own process group, initially waiting for a durable-identity handshake on stdin. */
internal class PosixGraphProcess private constructor(
    private val child: Int,
    private val readFd: Int,
    private val writeFd: Int,
    private val libc: SpawnLibC,
) : Process() {
    private var result: Int? = null
    private val output = object : InputStream() {
        private var isClosed = false
        override fun available(): Int {
            val size = IntByReference()
            check(libc.ioctl(readFd, if (Platform.isMac()) 0x4004667fL else 0x541bL, size) == 0) { "Pipe query failed" }
            return size.value
        }
        override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and BYTE_MASK }
        override fun read(bytes: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val buffer = Memory(len.toLong())
            val count = libc.read(readFd, buffer, len.toLong()).toInt()
            check(count >= 0) { "Pipe read failed" }
            if (count == 0) return -1
            buffer.read(0, bytes, off, count)
            return count
        }
        override fun close() {
            if (!isClosed) {
                isClosed = true
                closeFd(libc, readFd)
            }
        }
    }
    private val input = object : OutputStream() {
        private var isClosed = false
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
        override fun write(bytes: ByteArray, off: Int, len: Int) {
            val buffer = Memory(len.toLong())
            buffer.write(0, bytes, off, len)
            check(libc.write(writeFd, buffer, len.toLong()) == len.toLong()) { "Process handshake failed" }
        }
        override fun close() {
            if (!isClosed) {
                isClosed = true
                closeFd(libc, writeFd)
            }
        }
    }

    override fun getInputStream(): InputStream = output
    override fun getOutputStream(): OutputStream = input
    override fun getErrorStream(): InputStream = InputStream.nullInputStream()
    override fun pid(): Long = child.toLong()
    override fun toHandle(): ProcessHandle = ProcessHandle.of(pid()).orElseThrow()
    override fun info(): ProcessHandle.Info = toHandle().info()
    override fun isAlive(): Boolean {
        if (result != null) return false
        val status = IntByReference()
        val waited = libc.waitpid(child, status, 1)
        check(waited >= 0) { "Process status unavailable" }
        if (waited == child) {
            result =
                if (status.value and SIGNAL_MASK ==
                    0
                ) {
                    (status.value shr EXIT_SHIFT) and BYTE_MASK
                } else {
                    SIGNAL_EXIT + (status.value and SIGNAL_MASK)
                }
        }
        return result == null
    }
    override fun exitValue(): Int = if (isAlive) throw IllegalThreadStateException() else checkNotNull(result)
    override fun waitFor(): Int {
        if (result == null) {
            val status = IntByReference()
            check(libc.waitpid(child, status, 0) == child) { "Process wait failed" }
            result =
                if (status.value and SIGNAL_MASK ==
                    0
                ) {
                    (status.value shr EXIT_SHIFT) and BYTE_MASK
                } else {
                    SIGNAL_EXIT + (status.value and SIGNAL_MASK)
                }
        }
        return checkNotNull(result)
    }
    override fun destroy() {
        signalGroup(pid(), SIGTERM)
    }
    override fun destroyForcibly(): Process {
        signalGroup(pid(), SIGKILL)
        return this
    }

    companion object {
        private const val SIGNAL_MASK = 127
        private const val EXIT_SHIFT = 8
        private const val BYTE_MASK = 255
        private const val SIGNAL_EXIT = 128
        private const val SIGTERM = 15
        private const val SIGKILL = 9
        private const val ESRCH = 3
        private val log = Log.tag("GraphProcess")
        private val libc by lazy { Native.load(Platform.C_LIBRARY_NAME, SpawnLibC::class.java) }

        fun start(directory: String, command: String, environment: Map<String, String>): PosixGraphProcess {
            val incoming = IntArray(2)
            val outgoing = IntArray(2)
            check(libc.pipe(incoming) == 0) { "Cannot create process input" }
            if (libc.pipe(outgoing) != 0) {
                incoming.forEach { closeFd(libc, it) }
                error("Cannot create process output")
            }
            (incoming + outgoing).forEach { check(libc.fcntl(it, 2, 1) == 0) }
            val attrs = Memory(1_024)
            val actions = Memory(1_024)
            val pid = IntByReference()
            var areAttributesReady = false
            var areActionsReady = false
            var isStarted = false
            try {
                check(libc.posix_spawnattr_init(attrs) == 0)
                areAttributesReady = true
                check(libc.posix_spawnattr_setflags(attrs, 2) == 0)
                check(libc.posix_spawnattr_setpgroup(attrs, 0) == 0)
                check(libc.posix_spawn_file_actions_init(actions) == 0)
                areActionsReady = true
                check(libc.posix_spawn_file_actions_adddup2(actions, incoming[0], 0) == 0)
                check(libc.posix_spawn_file_actions_adddup2(actions, outgoing[1], 1) == 0)
                check(libc.posix_spawn_file_actions_adddup2(actions, outgoing[1], 2) == 0)
                (incoming + outgoing).forEach { check(libc.posix_spawn_file_actions_addclose(actions, it) == 0) }
                val script = "IFS= read -r gate && [ \"\$gate\" = heartbeat-run ] || exit 125; " +
                    "cd -- \"\$1\" || exit 125; exec /bin/sh -c \"\$2\""
                val argv = StringArray(arrayOf("/bin/sh", "-c", script, "heartbeat", directory, command))
                val env = StringArray(environment.map { "${it.key}=${it.value}" }.toTypedArray())
                check(libc.posix_spawn(pid, "/bin/sh", actions, attrs, argv, env) == 0) { "Cannot spawn command" }
                isStarted = true
                return PosixGraphProcess(pid.value, outgoing[0], incoming[1], libc)
            } finally {
                if (areActionsReady) check(libc.posix_spawn_file_actions_destroy(actions) == 0)
                if (areAttributesReady) check(libc.posix_spawnattr_destroy(attrs) == 0)
                closeFd(libc, incoming[0])
                closeFd(libc, outgoing[1])
                if (!isStarted) {
                    closeFd(libc, incoming[1])
                    closeFd(libc, outgoing[0])
                }
            }
        }

        fun isGroupStopped(group: Long): Boolean {
            val code = libc.kill(-group.toInt(), 0)
            return code != 0 && Native.getLastError() == ESRCH
        }

        fun signalGroup(group: Long, signal: Int) {
            if (libc.kill(-group.toInt(), signal) != 0 && Native.getLastError() != ESRCH) {
                log.w { "process group signal could not be confirmed" }
            }
        }

        private fun closeFd(libc: SpawnLibC, fd: Int) {
            if (libc.close(fd) != 0) log.w { "process pipe close failed" }
        }
    }
}

/** Only the POSIX spawn and pipe ABI; opaque native attributes are overallocated and initialized by libc. */
@Suppress(
    "FunctionNaming",
    "TooManyFunctions",
    "LongParameterList",
) // A single libc ABI handle; names are native symbols.
internal interface SpawnLibC : Library {
    fun pipe(fds: IntArray): Int
    fun fcntl(fd: Int, command: Int, vararg values: Any): Int
    fun close(fd: Int): Int
    fun read(fd: Int, buffer: Pointer, size: Long): Long
    fun write(fd: Int, buffer: Pointer, size: Long): Long
    fun ioctl(fd: Int, operation: Long, vararg values: Any): Int
    fun waitpid(pid: Int, status: IntByReference, flags: Int): Int
    fun kill(pid: Int, signal: Int): Int
    fun posix_spawnattr_init(attributes: Pointer): Int
    fun posix_spawnattr_destroy(attributes: Pointer): Int
    fun posix_spawnattr_setflags(attributes: Pointer, flags: Short): Int
    fun posix_spawnattr_setpgroup(attributes: Pointer, group: Int): Int
    fun posix_spawn_file_actions_init(actions: Pointer): Int
    fun posix_spawn_file_actions_destroy(actions: Pointer): Int
    fun posix_spawn_file_actions_adddup2(actions: Pointer, fd: Int, target: Int): Int
    fun posix_spawn_file_actions_addclose(actions: Pointer, fd: Int): Int
    fun posix_spawn(
        pid: IntByReference,
        file: String,
        actions: Pointer,
        attributes: Pointer,
        argv: Pointer,
        env: Pointer,
    ): Int
}
