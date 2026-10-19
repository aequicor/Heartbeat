package io.aequicor.heartbeat.platform.desktop

import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Host lifecycle, not a claim that asynchronous feature recovery or external reconciliation has completed. */
internal enum class WorkerPhase { Starting, Recovering, Running, Waiting, Stopping, Failed }

/** Allowlisted reasons only: raw exceptions, identifiers, prompts and credentials never enter the control channel. */
internal enum class WorkerProblem {
    None,
    FeatureDisabled,
    StorageUnavailable,
    RecoveryFailed,
    InteractionRequired,
    CleanupFailed,
}

/** Safe aggregate projection; clients cannot execute code or grant permissions through this protocol. */
internal data class WorkerSnapshot(
    val phase: WorkerPhase,
    val problem: WorkerProblem = WorkerProblem.None,
    val workflows: Int = 0,
    val decisions: Int = 0,
    val isHostRequired: Boolean = false,
) {
    fun encode(): String = listOf(phase.name, problem.name, workflows, decisions, isHostRequired).joinToString("|")

    companion object {
        fun decode(text: String): WorkerSnapshot {
            val fields = text.split('|')
            require(fields.size == SNAPSHOT_FIELDS)
            return WorkerSnapshot(
                WorkerPhase.valueOf(fields[0]),
                WorkerProblem.valueOf(fields[1]),
                fields[2].toInt().also { require(it >= 0) },
                fields[SNAPSHOT_DECISIONS].toInt().also { require(it >= 0) },
                fields[SNAPSHOT_NEEDS_HOST].toBooleanStrict(),
            )
        }
    }
}

/** A bounded, authenticated local control plane. Status/stop clients never open application stores. */
internal class WorkerControl(
    private val owner: DesktopStorageOwner,
    private val isWorker: Boolean,
    private val snapshot: () -> WorkerSnapshot,
    private val stop: () -> Unit,
) : AutoCloseable {
    private val log = Log.tag("WorkerControl")
    private val token = HexFormat.of().formatHex(ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes))
    private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)) }
    private val closed = AtomicBoolean(false)
    private val metadata = owner.directory.resolve("control")
    private val listener: Thread

    init {
        try {
            val temporary = Files.createTempFile(owner.directory, "control-", ".tmp")
            try {
                privateRuntimePath(temporary)
                Files.writeString(temporary, "1\n${server.localPort}\n$token\n")
                Files.move(temporary, metadata, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temporary)
            }
            listener = thread(name = "Heartbeat-worker-control", isDaemon = true) { listen() }
        } catch (error: CancellationException) {
            server.close()
            throw error
        } catch (error: Exception) {
            server.close()
            throw error
        }
    }

    private fun listen() {
        while (!closed.get()) {
            try {
                server.accept().use(::handle)
            } catch (error: SocketException) {
                if (!closed.get()) log.w(error) { "Worker control socket failed" }
            } catch (error: IOException) {
                log.w(error) { "Worker control request failed" }
            } catch (error: IllegalArgumentException) {
                log.w(error) { "Worker control rejected an invalid frame" }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = CONTROL_TIMEOUT_MS
        val fields = readFrame(socket).split('|')
        val isAuthorized = fields.size == REQUEST_FIELDS && fields[0] == "1" && MessageDigest.isEqual(
            fields[1].toByteArray(UTF_8),
            token.toByteArray(UTF_8),
        )
        val response = when {
            !isAuthorized -> "refused"
            !isWorker -> "ui"
            fields[2] == "status" -> snapshot().encode()
            fields[2] == "stop" -> snapshot().copy(phase = WorkerPhase.Stopping).encode()
            else -> "refused"
        }
        writeFrame(socket, response)
        if (isAuthorized && isWorker && fields[2] == "stop") stop()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        server.close()
        listener.join(CONTROL_TIMEOUT_MS.toLong())
        Files.deleteIfExists(metadata)
    }
}

/** Reads private endpoint metadata only. Failure never permits opening the profile alongside its owner. */
internal fun workerRequest(root: Path, command: String): WorkerSnapshot? {
    require(command == "status" || command == "stop")
    val file = root.toRealPath().resolve("execution-owner/control")
    require(!Files.isSymbolicLink(file))
    val fields = Files.newInputStream(file).use { input ->
        val bytes = input.readNBytes(MAX_FRAME_BYTES + 1)
        require(bytes.size <= MAX_FRAME_BYTES)
        bytes.toString(UTF_8).lines()
    }
    require(fields.size == METADATA_FIELDS && fields[0] == "1" && fields[2].matches(Regex("[0-9a-f]{64}")))
    return Socket().use { socket ->
        socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), fields[1].toInt()), CONTROL_TIMEOUT_MS)
        socket.soTimeout = CONTROL_TIMEOUT_MS
        writeFrame(socket, "1|${fields[2]}|$command")
        when (val response = readFrame(socket)) {
            "ui" -> null
            "refused" -> error("Worker control request was refused")
            else -> WorkerSnapshot.decode(response)
        }
    }
}

private fun readFrame(socket: Socket): String {
    val input = DataInputStream(socket.getInputStream())
    val size = input.readInt()
    require(size in 1..MAX_FRAME_BYTES)
    return ByteArray(size).also(input::readFully).toString(UTF_8)
}

private fun writeFrame(socket: Socket, value: String) {
    val bytes = value.toByteArray(UTF_8)
    require(bytes.size in 1..MAX_FRAME_BYTES)
    DataOutputStream(socket.getOutputStream()).apply {
        writeInt(bytes.size)
        write(bytes)
        flush()
    }
}

private const val MAX_FRAME_BYTES = 8192
private const val CONTROL_TIMEOUT_MS = 2000
private const val TOKEN_BYTES = 32

private const val SNAPSHOT_FIELDS = 5
private const val SNAPSHOT_NEEDS_HOST = 4
private const val SNAPSHOT_DECISIONS = 3
private const val METADATA_FIELDS = 4
private const val REQUEST_FIELDS = 3
