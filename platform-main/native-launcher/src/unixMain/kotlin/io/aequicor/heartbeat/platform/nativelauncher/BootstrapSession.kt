package io.aequicor.heartbeat.platform.nativelauncher

import io.aequicor.heartbeat.platform.nativelauncher.provisioning.BootstrapLayout
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.BootstrapProvisioner
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.HttpTransport
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ImageStore
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionException
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionProgress
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionedImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import okio.Path
import kotlin.random.Random

/** Native UI reads one state snapshot on its own thread; all provisioning and process waits run in the owned scope. */
internal class BootstrapSession(private val config: LauncherConfig, private val args: List<String>) {
    sealed interface State {
        data object Preparing : State
        data class Downloading(val progress: ProvisionProgress) : State
        data class ReadyToLaunch(val image: ProvisionedImage) : State
        data object Cancelling : State
        data object Launching : State
        data class Handoff(val directory: Path, val childPid: Int) : State
        data object Running : State
        data class Failed(val message: String) : State
        data class Finished(val code: Int) : State
    }

    val state = MutableStateFlow<State>(State.Preparing)
    private val fs = FileSystem.SYSTEM
    private val store = ImageStore(config.imageRoot)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun start() {
        state.value = State.Preparing
        job = scope.launch {
            try {
                val preparationJob = checkNotNull(coroutineContext[Job])
                val image = config.currentImage(store) ?: createUnixTransport().use { transport ->
                    BootstrapProvisioner(
                        store,
                        transport,
                        config.manifestUrl,
                        config.signatureUrl,
                        config.trustedKeys,
                        expectedVersion = config.version,
                        expectedTarget = config.target,
                        expectedChannel = config.channel,
                    ).provision(
                        onProgress = { state.value = State.Downloading(it) },
                        isCancelled = { !preparationJob.isActive },
                    )
                }
                ensureActive()
                state.value = State.ReadyToLaunch(image)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                launcherLog.w("Launcher", error) { "Cannot prepare application image" }
                state.value = State.Failed(preparationFailureMessage(error))
            }
        }
    }

    /** Called by the UI after preparing the native application's launch policy, before the JVM starts. */
    fun launch(image: ProvisionedImage) {
        if (!state.compareAndSet(State.ReadyToLaunch(image), State.Launching)) return
        job = scope.launch {
            val handoff = config.imageRoot.resolve("handoff-${Random.nextLong()}")
            var child: UnixChild? = null
            try {
                fs.createDirectories(handoff)
                ensureActive()
                val executable = image.directory.resolve(
                    BootstrapLayout.forPlatform(
                        config.target.substringBefore('-'),
                        config.channel,
                    ).executable,
                )
                child = UnixChild.start(
                    executable,
                    args,
                    mapOf(
                        "HEARTBEAT_HANDOFF_DIR" to handoff.toString(),
                        "HEARTBEAT_NATIVE_LAUNCHER" to nativeLauncherExecutable().toString(),
                    ),
                )
                awaitApplication(child, handoff)
            } catch (error: CancellationException) {
                if (!fs.exists(handoff.resolve("hidden"))) {
                    withContext(NonCancellable) { child?.let { cancelChild(it, handoff) } }
                }
                throw error
            } catch (error: Exception) {
                launcherLog.w("Launcher", error) { "Cannot launch application" }
                child?.terminate()
                state.value = State.Failed("Не удалось запустить приложение. Повторите попытку.")
            } finally {
                fs.deleteRecursively(handoff, mustExist = false)
            }
        }
    }

    private suspend fun cancelChild(child: UnixChild, handoff: Path) {
        try {
            fs.write(handoff.resolve("cancel")) { writeUtf8("cancel") }
            val exited = withTimeoutOrNull(CANCELLATION_GRACE_MILLIS) {
                while (child.poll() == null) delay(POLL_MILLIS)
                true
            } ?: false
            if (!exited) {
                launcherLog.w("Launcher", IllegalStateException("Child did not acknowledge cancellation")) {
                    "Forcing application exit after cancellation grace period"
                }
                child.terminate()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            launcherLog.w("Launcher", error) { "Cooperative application cancellation failed" }
            child.terminate()
        }
    }

    private suspend fun awaitApplication(child: UnixChild, handoff: Path) {
        var isReady = false
        while (true) {
            val code = child.poll()
            if (code != null) {
                state.value = State.Finished(code)
                launcherLog.i("Launcher") { "Application exited: $code" }
                return
            }
            if (!isReady && fs.exists(handoff.resolve("ready"))) {
                isReady = true
                state.value = State.Handoff(handoff, child.pid)
                launcherLog.i("Launcher") { "Application first frame ready" }
            }
            delay(POLL_MILLIS)
        }
    }

    /** The UI hides its window first; only then may the JVM show its first window. */
    fun acknowledgeHandoff(directory: Path, presentation: HandoffPresentation = HandoffPresentation(true, false)) {
        val current = state.value as? State.Handoff ?: return
        if (current.directory != directory || !state.compareAndSet(current, State.Running)) return
        fs.write(directory.resolve("presentation.properties")) { writeUtf8(presentation.properties()) }
        fs.write(directory.resolve("hidden")) { writeUtf8("hidden") }
        launcherLog.i("Launcher") { "Native window hidden; application handoff acknowledged" }
    }

    fun cancel() {
        if (state.value is State.Cancelling || state.value is State.Finished) return
        state.value = State.Cancelling
        val current = job
        current?.cancel()
        scope.launch {
            current?.join()
            state.value = State.Finished(0)
            launcherLog.i("Launcher") { "Native launch cancelled" }
        }
    }

    fun dispose() = scope.cancel()

    private fun preparationFailureMessage(error: Exception): String = when (error) {
        is ProvisionException.Network ->
            "Не удалось загрузить компоненты. Проверьте подключение к сети и повторите попытку."

        is ProvisionException.BadSignature ->
            "Не удалось проверить подпись компонентов. Установите свежую версию лончера."

        is ProvisionException.InsufficientSpace -> "Недостаточно свободного места для подготовки приложения."

        is ProvisionException.CorruptedDownload -> "Загрузка повреждена. Повторите попытку."

        is ProvisionException.LockTimeout -> "Подготовка уже идёт в другом окне. Повторите попытку позже."

        else -> "Не удалось подготовить приложение. Повторите попытку или установите свежую версию лончера."
    }

    companion object {
        private const val CANCELLATION_GRACE_MILLIS = 30_000L
        private const val POLL_MILLIS = 50L
    }
}

internal expect fun createUnixTransport(): HttpTransport
