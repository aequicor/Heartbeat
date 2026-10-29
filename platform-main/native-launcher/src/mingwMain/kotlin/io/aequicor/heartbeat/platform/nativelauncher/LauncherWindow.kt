@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import io.aequicor.heartbeat.platform.nativelauncher.provisioning.BootstrapProvisioner
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ImageStore
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionException
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionProgress
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionStage
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionedImage
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.WinHttpTransport
import kotlinx.cinterop.alloc
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.wcstr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import platform.windows.*
import kotlin.concurrent.Volatile

/**
 * Нативное окно лончера (Win32): системный title bar, этап, прогресс-бар, байты, отмена/повтор.
 * Окно создаётся до старта JVM; сворачивание не останавливает загрузку, закрытие отменяет запуск.
 * Фоновый provisioning живёт в Dispatchers.Default, обновления окна — через PostMessageW (только UI-поток).
 */
class LauncherWindow(
    private val config: LauncherConfig,
    private val store: ImageStore,
) {
    private var hwnd: HWND? = null
    private var stageText: HWND? = null
    private var bytesText: HWND? = null
    private var progressBar: HWND? = null
    private var cancelButton: HWND? = null
    private var retryButton: HWND? = null
    private var provisioningJob: Job? = null

    @Volatile
    private var isCancelled = false

    @Volatile
    private var handoffStarted = false

    @Volatile
    private var childProcess: HANDLE? = null

    fun run(offline: ProvisionedImage?) {
        LauncherWindowRegistry.instance = this
        memScoped {
            SetProcessDPIAware()
            val instance = GetModuleHandleW(null)
            val classNamePointer = WINDOW_CLASS.wcstr.getPointer(this)
            val wndClass = alloc<WNDCLASSEXW>().apply {
                cbSize = sizeOf<WNDCLASSEXW>().toUInt()
                lpfnWndProc = staticCFunction(::launcherWndProc)
                hInstance = instance
                lpszClassName = classNamePointer
                hbrBackground = GetStockObject(WHITE_BRUSH)!!.reinterpret()
            }
            RegisterClassExW(wndClass.ptr)
            val window = CreateWindowExW(
                0u,
                WINDOW_CLASS.wcstr.getPointer(this),
                "Heartbeat".wcstr.getPointer(this),
                (WS_OVERLAPPEDWINDOW or WS_VISIBLE).toUInt(),
                CW_USEDEFAULT,
                CW_USEDEFAULT,
                WINDOW_WIDTH,
                WINDOW_HEIGHT,
                null,
                null,
                instance,
                null,
            )
            checkNotNull(window) { "Cannot create launcher window" }
            hwnd = window
            createControls(window)
            ShowWindow(window, SW_SHOW)
            UpdateWindow(window)
        }
        if (offline != null) {
            startApplication(offline)
        } else {
            startProvisioning()
        }
        messageLoop()
        provisioningJob?.cancel()
        childProcess?.let { CloseHandle(it) }
    }

    private fun createControls(window: HWND) {
        stageText = control("STATIC", "Подготовка Heartbeat…", window)
        bytesText = control("STATIC", "", window)
        progressBar = control(PROGRESS_CLASS, "", window)
        cancelButton = control("BUTTON", "Отмена", window)
        retryButton = control("BUTTON", "Повторить", window)
        retryButton?.let { ShowWindow(it, SW_HIDE) }
        layout(window)
    }

    private fun control(className: String, text: String, parent: HWND): HWND? = memScoped {
        CreateWindowExW(
            0u,
            className.wcstr.getPointer(this),
            text.wcstr.getPointer(this),
            (WS_CHILD or WS_VISIBLE).toUInt(),
            0,
            0,
            0,
            0,
            parent,
            null,
            GetModuleHandleW(null),
            null,
        )
    }

    internal fun layout(window: HWND) {
        memScoped {
            val rect = alloc<RECT>()
            GetClientRect(window, rect.ptr)
            val centerX = (rect.right - rect.left) / 2
            val centerY = (rect.bottom - rect.top) / 2
            move(stageText, centerX - 160, centerY - 70, 320, 24)
            move(progressBar, centerX - 160, centerY - 32, 320, 12)
            move(bytesText, centerX - 160, centerY - 8, 320, 22)
            move(cancelButton, centerX - 40, centerY + 36, 80, 28)
            move(retryButton, centerX - 124, centerY + 36, 96, 28)
        }
    }

    private fun move(control: HWND?, x: Int, y: Int, width: Int, height: Int) {
        control?.let { MoveWindow(it, x, y, width, height, 1) }
    }

    private fun messageLoop() {
        memScoped {
            val message = alloc<MSG>()
            while (GetMessageW(message.ptr, null, 0u, 0u) > 0) {
                TranslateMessage(message.ptr)
                DispatchMessageW(message.ptr)
            }
        }
    }

    private fun startProvisioning() {
        retryButton?.let { ShowWindow(it, SW_HIDE) }
        provisioningJob = CoroutineScope(Dispatchers.Default).launch {
            try {
                require(config.trustedKeys.isNotEmpty()) {
                    "Bootstrap public key is not configured (HEARTBEAT_BOOTSTRAP_PUBLIC_KEY)"
                }
                val provisioner = BootstrapProvisioner(
                    store = store,
                    transport = WinHttpTransport(),
                    manifestUrl = config.manifestUrl,
                    signatureUrl = config.signatureUrl,
                    trustedKeys = config.trustedKeys,
                )
                val image = provisioner.provision(
                    onProgress = { progress -> updateProgress(progress) },
                    isCancelled = { isCancelled },
                )
                startApplication(image)
            } catch (error: Throwable) {
                showError(error)
            }
        }
    }

    private fun updateProgress(progress: ProvisionProgress) {
        PostMessageW(hwnd, WM_APP_PROGRESS.toUInt(), 0uL, progress.stage.ordinal.toLong())
        val text = when (progress.stage) {
            ProvisionStage.MANIFEST -> "Проверка манифеста…"
            ProvisionStage.DOWNLOAD -> "Загрузка ${progress.artifact ?: "компонентов"}…"
            ProvisionStage.EXTRACT -> "Распаковка…"
            ProvisionStage.ACTIVATE -> "Активация…"
        }
        setText(stageText, text)
        val total = progress.totalBytes
        if (total > 0) {
            val percent = (progress.downloadedBytes * 100 / total).toInt()
            SendMessageW(progressBar, PBM_SETPOS.toUInt(), percent.toULong(), 0L)
            setText(bytesText, "${progress.downloadedBytes / MB} МБ из ${total / MB} МБ · $percent %")
        }
    }

    private fun showError(error: Throwable) {
        if (isCancelled) {
            PostMessageW(hwnd, WM_CLOSE.toUInt(), 0uL, 0L)
            return
        }
        val text = when (error) {
            is ProvisionException.Network -> "Нет соединения или сервер недоступен. Проверьте интернет и нажмите «Повторить»."
            is ProvisionException.BadSignature -> "Манифест не прошёл проверку подписи."
            is ProvisionException.InsufficientSpace -> "Недостаточно свободного места на диске."
            is ProvisionException.CorruptedDownload -> "Загруженный файл повреждён. Нажмите «Повторить»."
            else -> "Ошибка запуска: ${error.message}"
        }
        setText(stageText, text)
        retryButton?.let { ShowWindow(it, SW_SHOW) }
        LauncherLog.w("Launcher", error) { "Provisioning failed" }
    }

    private fun startApplication(image: ProvisionedImage) {
        if (handoffStarted) return
        handoffStarted = true
        setText(stageText, "Запуск Heartbeat…")
        val executable = image.directory.resolve("Heartbeat.exe").toString()
        val process = launchProcess(executable)
        childProcess = process
        if (process == null) {
            handoffStarted = false
            showError(ProvisionException.Network("Cannot start $executable"))
            return
        }
        SetTimer(hwnd, HANDOFF_TIMER_ID.toULong(), HANDOFF_INTERVAL_MS.toUInt(), null)
    }

    internal fun onHandoffTick() {
        val process = childProcess ?: return
        if (WaitForSingleObject(process, 0u) == WAIT_OBJECT_0) {
            KillTimer(hwnd, HANDOFF_TIMER_ID.toULong())
            showError(ProvisionException.Network("Приложение завершилось до появления окна"))
            return
        }
        if (memScoped { FindWindowW(null, "Heartbeat".wcstr.getPointer(this)) } != null) {
            // Готовое окно приложения видно: убираем лончер без смены фокуса и геометрии приложения.
            KillTimer(hwnd, HANDOFF_TIMER_ID.toULong())
            DestroyWindow(hwnd)
        }
    }

    internal fun cancel() {
        isCancelled = true
        provisioningJob?.cancel()
        childProcess?.let { process ->
            val appWindow = memScoped { FindWindowW(null, "Heartbeat".wcstr.getPointer(this)) }
            if (appWindow != null) {
                PostMessageW(appWindow, WM_CLOSE.toUInt(), 0uL, 0L)
            } else {
                TerminateProcess(process, 1u)
            }
        }
        DestroyWindow(hwnd)
    }

    fun onCommand(controlId: Long) {
        when (controlId) {
            IDC_CANCEL -> cancel()
            IDC_RETRY -> startProvisioning()
        }
    }

    companion object {
        private const val WINDOW_CLASS = "HeartbeatLauncherWindow"
        private const val WINDOW_WIDTH = 1280
        private const val WINDOW_HEIGHT = 800
        private const val WM_APP_PROGRESS = 0x8001
        private const val IDC_CANCEL = 1001L
        private const val IDC_RETRY = 1002L
        private const val HANDOFF_TIMER_ID = 42
        private const val HANDOFF_INTERVAL_MS = 250
        private const val PROGRESS_CLASS = "msctls_progress32"
        private const val MB = 1_000_000L
    }
}

private fun setText(control: HWND?, text: String) {
    control?.let { handle -> memScoped { SetWindowTextW(handle, text.wcstr.getPointer(this)) } }
}

private fun launcherWndProc(hwnd: HWND?, message: UINT, wParam: WPARAM, lParam: LPARAM): LRESULT {
    val window = LauncherWindowRegistry.instance
    when (message.toInt()) {
        WM_SIZE -> window?.layout(hwnd!!)
        WM_COMMAND -> window?.onCommand(wParam.toLong() and 0xffffL)
        WM_TIMER -> window?.onHandoffTick()
        WM_CLOSE -> window?.cancel()
        WM_DESTROY -> PostQuitMessage(0)
    }
    return DefWindowProcW(hwnd!!, message, wParam, lParam)
}

/** Единственное окно процесса лончера; WndProc — статическая функция C, состояние берёт из реестра. */
object LauncherWindowRegistry {
    var instance: LauncherWindow? = null
}
