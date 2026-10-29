@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import io.aequicor.heartbeat.platform.nativelauncher.provisioning.BootstrapProvisioner
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ImageStore
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionException
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionProgress
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionStage
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionedImage
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.WinHttpTransport
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.wcstr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import platform.windows.BS_OWNERDRAW
import platform.windows.BeginPaint
import platform.windows.CLIP_DEFAULT_PRECIS
import platform.windows.CW_USEDEFAULT
import platform.windows.CloseHandle
import platform.windows.CreateFontW
import platform.windows.CreateSolidBrush
import platform.windows.CreateWindowExW
import platform.windows.DEFAULT_CHARSET
import platform.windows.DEFAULT_PITCH
import platform.windows.DEFAULT_QUALITY
import platform.windows.DRAWITEMSTRUCT
import platform.windows.DT_CENTER
import platform.windows.DT_SINGLELINE
import platform.windows.DT_VCENTER
import platform.windows.DefWindowProcW
import platform.windows.DeleteObject
import platform.windows.DestroyWindow
import platform.windows.DispatchMessageW
import platform.windows.DrawTextW
import platform.windows.EndPaint
import platform.windows.FF_DONTCARE
import platform.windows.FW_NORMAL
import platform.windows.FW_SEMIBOLD
import platform.windows.FindWindowW
import platform.windows.GetClientRect
import platform.windows.GetFileAttributesW
import platform.windows.GetMessageW
import platform.windows.GetModuleFileNameW
import platform.windows.GetModuleHandleW
import platform.windows.GetTickCount64
import platform.windows.HANDLE
import platform.windows.HBRUSH
import platform.windows.HDC
import platform.windows.HDC__
import platform.windows.HFONT
import platform.windows.HICON
import platform.windows.HICON__
import platform.windows.HMODULE
import platform.windows.HWND
import platform.windows.HWND__
import platform.windows.IMAGE_ICON
import platform.windows.INVALID_FILE_ATTRIBUTES
import platform.windows.InvalidateRect
import platform.windows.KillTimer
import platform.windows.LPARAM
import platform.windows.LRESULT
import platform.windows.LR_LOADFROMFILE
import platform.windows.LoadImageW
import platform.windows.MAX_PATH
import platform.windows.MSG
import platform.windows.MoveWindow
import platform.windows.OUT_DEFAULT_PRECIS
import platform.windows.PAINTSTRUCT
import platform.windows.PostMessageW
import platform.windows.PostQuitMessage
import platform.windows.RECT
import platform.windows.RegisterClassExW
import platform.windows.RoundRect
import platform.windows.SS_CENTER
import platform.windows.SS_ICON
import platform.windows.STM_SETICON
import platform.windows.SW_HIDE
import platform.windows.SW_SHOW
import platform.windows.SelectObject
import platform.windows.SendMessageW
import platform.windows.SetBkMode
import platform.windows.SetEnvironmentVariableW
import platform.windows.SetProcessDPIAware
import platform.windows.SetTextColor
import platform.windows.SetTimer
import platform.windows.SetWindowTextW
import platform.windows.ShowWindow
import platform.windows.TRANSPARENT
import platform.windows.TerminateProcess
import platform.windows.TranslateMessage
import platform.windows.UINT
import platform.windows.UpdateWindow
import platform.windows.WAIT_OBJECT_0
import platform.windows.WM_CLOSE
import platform.windows.WM_COMMAND
import platform.windows.WM_CTLCOLORSTATIC
import platform.windows.WM_DESTROY
import platform.windows.WM_DRAWITEM
import platform.windows.WM_PAINT
import platform.windows.WM_SETFONT
import platform.windows.WM_SIZE
import platform.windows.WM_TIMER
import platform.windows.WNDCLASSEXW
import platform.windows.WPARAM
import platform.windows.WS_CHILD
import platform.windows.WS_OVERLAPPEDWINDOW
import platform.windows.WS_VISIBLE
import platform.windows.WaitForSingleObject
import kotlin.concurrent.Volatile

/**
 * Нативное окно лончера (Win32) по макету `design/features/native-launcher/native-launcher.pen`:
 * системный title bar с иконкой, логотип, название, этап, прогресс-бар, байты, Отмена/Повторить.
 * Окно создаётся до старта JVM; сворачивание не останавливает загрузку, закрытие отменяет запуск.
 * Тема — из токенов дизайн-системы ([LauncherTheme]), обновления прогресса — не чаще 100 мс.
 */
class LauncherWindow(private val config: LauncherConfig, private val store: ImageStore) {
    private var hwnd: HWND? = null
    private var stageText: HWND? = null
    private var bytesText: HWND? = null
    private var titleText: HWND? = null
    private var iconStatic: HWND? = null
    private var progressBar: HWND? = null
    private var cancelButton: HWND? = null
    internal var retryButton: HWND? = null
    private var provisioningJob: Job? = null
    private var titleFont: HFONT? = null
    private var bodyFont: HFONT? = null
    private var captionFont: HFONT? = null
    private var backgroundBrush: HBRUSH? = null
    private var lastProgressMs = 0L

    @Volatile
    private var isCancelled = false

    @Volatile
    private var handoffStarted = false

    @Volatile
    private var childProcess: HANDLE? = null

    internal val theme = LauncherTheme.current()

    fun run(offline: ProvisionedImage?) {
        LauncherWindowRegistry.instance = this
        memScoped {
            SetProcessDPIAware()
            val instance = GetModuleHandleW(null)
            backgroundBrush = CreateSolidBrush(theme.background)
            titleFont = createFont(TITLE_FONT_SIZE, FW_SEMIBOLD)
            bodyFont = createFont(BODY_FONT_SIZE, FW_NORMAL)
            captionFont = createFont(CAPTION_FONT_SIZE, FW_NORMAL)
            val icon = loadIcon(ICON_SIZE)
            registerWindowClass(instance, icon)
            registerProgressClass(instance)
            val window = CreateWindowExW(
                0u,
                WINDOW_CLASS.wcstr.getPointer(this),
                "Heartbeat".wcstr.getPointer(this),
                WS_OVERLAPPEDWINDOW.toUInt(),
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
            createControls(window, icon)
            // Показ только после создания контролов: белого всплеска пустого окна нет.
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

    private fun registerWindowClass(instance: HMODULE?, icon: HICON?) {
        memScoped {
            val className = WINDOW_CLASS.wcstr.getPointer(this)
            val wndClass = alloc<WNDCLASSEXW>()
            wndClass.cbSize = sizeOf<WNDCLASSEXW>().toUInt()
            wndClass.lpfnWndProc = staticCFunction(::launcherWndProc)
            wndClass.hInstance = instance
            wndClass.lpszClassName = className
            wndClass.hbrBackground = backgroundBrush
            wndClass.hIcon = icon
            wndClass.hIconSm = icon
            RegisterClassExW(wndClass.ptr)
        }
    }

    private fun registerProgressClass(instance: HMODULE?) {
        memScoped {
            val className = PROGRESS_CLASS.wcstr.getPointer(this)
            val wndClass = alloc<WNDCLASSEXW>()
            wndClass.cbSize = sizeOf<WNDCLASSEXW>().toUInt()
            wndClass.lpfnWndProc = staticCFunction(::progressWndProc)
            wndClass.hInstance = instance
            wndClass.lpszClassName = className
            wndClass.hbrBackground = backgroundBrush
            RegisterClassExW(wndClass.ptr)
        }
    }

    private fun createControls(window: HWND, icon: HICON?) {
        titleText = control("STATIC", "Heartbeat", window, SS_CENTER)
        stageText = control("STATIC", "Подготовка Heartbeat…", window, SS_CENTER)
        bytesText = control("STATIC", "", window, SS_CENTER)
        progressBar = control(PROGRESS_CLASS, "", window)
        iconStatic = control("STATIC", "", window, SS_ICON or SS_CENTER)
        if (icon != null) {
            SendMessageW(iconStatic, STM_SETICON.toUInt(), icon.rawValue.toLong().toULong(), 0L)
        }
        cancelButton = control("BUTTON", "Отмена", window, BS_OWNERDRAW)
        retryButton = control("BUTTON", "Повторить", window, BS_OWNERDRAW)
        retryButton?.let { ShowWindow(it, SW_HIDE) }
        listOf(titleText to titleFont, stageText to bodyFont, bytesText to captionFont).forEach { (text, font) ->
            if (font != null) SendMessageW(text, WM_SETFONT.toUInt(), font.rawValue.toLong().toULong(), 1L)
        }
        layout(window)
    }

    private fun control(className: String, text: String, parent: HWND, style: Int = 0): HWND? = memScoped {
        CreateWindowExW(
            0u,
            className.wcstr.getPointer(this),
            text.wcstr.getPointer(this),
            (WS_CHILD or WS_VISIBLE or style).toUInt(),
            0, 0, 0, 0,
            parent,
            null,
            GetModuleHandleW(null),
            null,
        )
    }

    private fun createFont(height: Int, weight: Int): HFONT = memScoped {
        CreateFontW(
            -height, 0, 0, 0, weight, 0u, 0u, 0u, DEFAULT_CHARSET.toUInt(),
            OUT_DEFAULT_PRECIS.toUInt(), CLIP_DEFAULT_PRECIS.toUInt(), DEFAULT_QUALITY.toUInt(),
            (DEFAULT_PITCH or FF_DONTCARE).toUInt(), "Segoe UI".wcstr.getPointer(this),
        )
    }!!

    private fun loadIcon(size: Int): HICON? {
        val candidates = listOf(
            "icons/heartbeat.ico",
            "icons/dev/heartbeat.ico",
            "platform-main/desktop/icons/heartbeat.ico",
            "platform-main/desktop/icons/dev/heartbeat.ico",
        )
        val roots = listOf(executableDirectory(), ".")
        return roots.firstNotNullOfOrNull { root ->
            candidates.firstNotNullOfOrNull { candidate ->
                val path = if (root == ".") candidate else "$root/$candidate"
                loadIconFrom(path, size)
            }
        }
    }

    private fun executableDirectory(): String = memScoped {
        val buffer = allocArray<UShortVar>(MAX_PATH)
        val length = GetModuleFileNameW(null, buffer, MAX_PATH.toUInt())
        if (length == 0u) return@memScoped "."
        val path = buffer.toKString()
        path.substringBeforeLast('\\', path)
    }

    private fun loadIconFrom(path: String, size: Int): HICON? = memScoped {
        if (GetFileAttributesW(path.wcstr.getPointer(this)) == INVALID_FILE_ATTRIBUTES) return@memScoped null
        LoadImageW(null, path.wcstr.getPointer(this), IMAGE_ICON.toUInt(), size, size, LR_LOADFROMFILE.toUInt())
            ?.reinterpret<HICON__>()
    }

    internal fun layout(window: HWND) {
        memScoped {
            val rect = alloc<RECT>()
            GetClientRect(window, rect.ptr)
            val centerX = (rect.right - rect.left) / 2
            val centerY = (rect.bottom - rect.top) / 2
            val buttonsX = centerX - (RETRY_BUTTON_WIDTH + BUTTON_GAP + CANCEL_BUTTON_WIDTH) / 2
            move(iconStatic, centerX - ICON_SIZE / 2, centerY - ICON_TOP_OFFSET, ICON_SIZE, ICON_SIZE)
            move(titleText, centerX - TITLE_WIDTH / 2, centerY - TITLE_TOP_OFFSET, TITLE_WIDTH, TITLE_HEIGHT)
            move(stageText, centerX - CONTENT_WIDTH / 2, centerY - STAGE_TOP_OFFSET, CONTENT_WIDTH, STAGE_HEIGHT)
            move(
                progressBar,
                centerX - CONTENT_WIDTH / 2,
                centerY - PROGRESS_TOP_OFFSET,
                CONTENT_WIDTH,
                PROGRESS_HEIGHT,
            )
            move(bytesText, centerX - CONTENT_WIDTH / 2, centerY + BYTES_TOP_OFFSET, CONTENT_WIDTH, BYTES_HEIGHT)
            move(retryButton, buttonsX, centerY + BUTTONS_TOP_OFFSET, RETRY_BUTTON_WIDTH, BUTTON_HEIGHT)
            move(
                cancelButton,
                buttonsX + RETRY_BUTTON_WIDTH + BUTTON_GAP,
                centerY + BUTTONS_TOP_OFFSET,
                CANCEL_BUTTON_WIDTH,
                BUTTON_HEIGHT,
            )
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
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                launcherLog.w("Launcher", error) { "Provisioning failed" }
                showError(error)
            }
        }
    }

    private fun updateProgress(progress: ProvisionProgress) {
        val now = GetTickCount64().toLong()
        if (now - lastProgressMs < PROGRESS_INTERVAL_MS && progress.stage == ProvisionStage.DOWNLOAD) return
        lastProgressMs = now
        setText(
            stageText,
            when (progress.stage) {
                ProvisionStage.MANIFEST -> "Проверка манифеста…"
                ProvisionStage.DOWNLOAD -> "Загрузка ${progress.artifact ?: "компонентов"}…"
                ProvisionStage.EXTRACT -> "Распаковка…"
                ProvisionStage.ACTIVATE -> "Активация…"
            },
        )
        val total = progress.totalBytes
        if (total > 0) {
            val percent = (progress.downloadedBytes * 100 / total).toInt()
            LauncherWindowRegistry.progressPercent = percent
            progressBar?.let { InvalidateRect(it, null, 1) }
            setText(bytesText, "${progress.downloadedBytes / MB} МБ из ${total / MB} МБ · $percent %")
        }
    }

    private fun showError(error: Throwable) {
        if (isCancelled) {
            PostMessageW(hwnd, WM_CLOSE.toUInt(), 0uL, 0L)
            return
        }
        val text = when (error) {
            is ProvisionException.Network ->
                "Нет соединения или сервер недоступен. Проверьте интернет и нажмите «Повторить»."

            is ProvisionException.BadSignature -> "Манифест не прошёл проверку подписи."

            is ProvisionException.InsufficientSpace -> "Недостаточно свободного места на диске."

            is ProvisionException.CorruptedDownload -> "Загруженный файл повреждён. Нажмите «Повторить»."

            else -> "Ошибка запуска: ${error.message}"
        }
        setText(stageText, text)
        retryButton?.let { ShowWindow(it, SW_SHOW) }
    }

    private fun startApplication(image: ProvisionedImage) {
        if (handoffStarted) return
        handoffStarted = true
        setText(stageText, "Запуск Heartbeat…")
        val handoffDir = store.root.resolve("handoff")
        val fs = okio.FileSystem.SYSTEM
        fs.createDirectories(handoffDir)
        if (fs.exists(handoffDir.resolve("ready"))) fs.delete(handoffDir.resolve("ready"))
        if (fs.exists(handoffDir.resolve("hidden"))) fs.delete(handoffDir.resolve("hidden"))
        memScoped {
            SetEnvironmentVariableW(
                "HEARTBEAT_HANDOFF_DIR".wcstr.getPointer(this),
                handoffDir.toString().wcstr.getPointer(this),
            )
        }
        val executable = image.directory.resolve("Heartbeat.exe").toString()
        val process = launchProcess(executable)
        childProcess = process
        if (process == null) {
            handoffStarted = false
            val error = ProvisionException.Network("Cannot start $executable")
            launcherLog.w("Launcher", error) { "Cannot start application from provisioned image" }
            showError(error)
            return
        }
        SetTimer(hwnd, HANDOFF_TIMER_ID.toULong(), HANDOFF_INTERVAL_MS.toUInt(), null)
    }

    internal fun onHandoffTick() {
        val process = childProcess ?: return
        if (WaitForSingleObject(process, 0u) == WAIT_OBJECT_0) {
            KillTimer(hwnd, HANDOFF_TIMER_ID.toULong())
            val error = ProvisionException.Network("Приложение завершилось до появления окна")
            launcherLog.w("Launcher", error) { "Application exited before showing a window" }
            showError(error)
            return
        }
        val handoffDir = store.root.resolve("handoff")
        val fs = okio.FileSystem.SYSTEM
        if (fs.exists(handoffDir.resolve("ready"))) {
            // Приложение нарисовало первый кадр и ждёт `hidden`: уничтожаем окно лончера и только затем
            // разрешаем ему показаться — видимого второго окна и второй иконки не возникает.
            KillTimer(hwnd, HANDOFF_TIMER_ID.toULong())
            DestroyWindow(hwnd)
            fs.write(handoffDir.resolve("hidden")) { writeUtf8("hidden") }
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

    fun onCommand(control: HWND?) {
        when (control) {
            cancelButton -> cancel()
            retryButton -> startProvisioning()
        }
    }

    internal fun onCtlColorStatic(dc: HDC?): HBRUSH? {
        SetTextColor(dc, theme.textPrimary)
        SetBkMode(dc, TRANSPARENT)
        return backgroundBrush
    }

    companion object {
        private const val WINDOW_CLASS = "HeartbeatLauncherWindow"
        private const val PROGRESS_CLASS = "HeartbeatLauncherProgress"
        private const val WINDOW_WIDTH = 1280
        private const val WINDOW_HEIGHT = 800
        private const val HANDOFF_TIMER_ID = 42
        private const val HANDOFF_INTERVAL_MS = 250
        private const val PROGRESS_INTERVAL_MS = 100L
        private const val MB = 1_000_000L
        private const val TITLE_FONT_SIZE = 24
        private const val BODY_FONT_SIZE = 16
        private const val CAPTION_FONT_SIZE = 13
        private const val ICON_SIZE = 48
        private const val ICON_TOP_OFFSET = 164
        private const val TITLE_WIDTH = 200
        private const val TITLE_HEIGHT = 32
        private const val TITLE_TOP_OFFSET = 96
        private const val CONTENT_WIDTH = 320
        private const val STAGE_HEIGHT = 24
        private const val STAGE_TOP_OFFSET = 52
        private const val PROGRESS_HEIGHT = 6
        private const val PROGRESS_TOP_OFFSET = 8
        private const val BYTES_HEIGHT = 22
        private const val BYTES_TOP_OFFSET = 10
        private const val BUTTON_HEIGHT = 32
        private const val BUTTONS_TOP_OFFSET = 56
        private const val RETRY_BUTTON_WIDTH = 96
        private const val CANCEL_BUTTON_WIDTH = 80
        private const val BUTTON_GAP = 12
        internal const val BUTTON_CORNER_RADIUS = 6
        internal const val PROGRESS_CORNER_RADIUS = 3
    }
}

private fun setText(control: HWND?, text: String) {
    control?.let { handle -> memScoped { SetWindowTextW(handle, text.wcstr.getPointer(this)) } }
}

private fun launcherWndProc(hwnd: HWND?, message: UINT, wParam: WPARAM, lParam: LPARAM): LRESULT {
    val window = LauncherWindowRegistry.instance ?: return DefWindowProcW(hwnd, message, wParam, lParam)
    when (message.toInt()) {
        WM_SIZE -> window.layout(hwnd!!)

        WM_COMMAND -> window.onCommand(lParam.toCPointer<HWND__>())

        WM_TIMER -> window.onHandoffTick()

        WM_CLOSE -> window.cancel()

        WM_DESTROY -> PostQuitMessage(0)

        WM_CTLCOLORSTATIC -> {
            val brush = window.onCtlColorStatic(wParam.toLong().toCPointer<HDC__>())
            if (brush != null) return brush.rawValue.toLong()
        }

        WM_DRAWITEM -> drawButton(window, lParam)
    }
    return DefWindowProcW(hwnd, message, wParam, lParam)
}

private fun drawButton(window: LauncherWindow, lParam: LPARAM) {
    val item = lParam.toCPointer<DRAWITEMSTRUCT>()?.pointed ?: return
    val dc = item.hDC
    val rect = item.rcItem
    val isPrimary = item.hwndItem == window.retryButton
    memScoped {
        val background = CreateSolidBrush(if (isPrimary) window.theme.primaryButton else window.theme.background)
        val previous = SelectObject(dc, background)
        val corner = LauncherWindow.BUTTON_CORNER_RADIUS
        RoundRect(dc, rect.left, rect.top, rect.right, rect.bottom, corner, corner)
        SelectObject(dc, previous)
        DeleteObject(background)
        SetBkMode(dc, TRANSPARENT)
        SetTextColor(dc, if (isPrimary) window.theme.primaryButtonText else window.theme.textPrimary)
        val text = if (isPrimary) "Повторить" else "Отмена"
        DrawTextW(dc, text, -1, rect.ptr, BUTTON_TEXT_FLAGS)
    }
}

private val BUTTON_TEXT_FLAGS = (DT_CENTER or DT_VCENTER or DT_SINGLELINE).toUInt()

private fun progressWndProc(hwnd: HWND?, message: UINT, wParam: WPARAM, lParam: LPARAM): LRESULT {
    if (message.toInt() != WM_PAINT) return DefWindowProcW(hwnd, message, wParam, lParam)
    memScoped {
        val paint = alloc<PAINTSTRUCT>()
        val dc = BeginPaint(hwnd, paint.ptr)
        val rect = alloc<RECT>()
        GetClientRect(hwnd, rect.ptr)
        val theme = LauncherTheme.current()
        val track = CreateSolidBrush(theme.progressTrack)
        val previous = SelectObject(dc, track)
        val corner = LauncherWindow.PROGRESS_CORNER_RADIUS
        RoundRect(dc, rect.left, rect.top, rect.right, rect.bottom, corner, corner)
        val percent = LauncherWindowRegistry.progressPercent.coerceIn(0, 100)
        if (percent > 0) {
            val fill = CreateSolidBrush(theme.progressFill)
            SelectObject(dc, fill)
            val width = (rect.right - rect.left) * percent / 100
            RoundRect(dc, rect.left, rect.top, rect.left + width, rect.bottom, corner, corner)
            DeleteObject(fill)
        }
        SelectObject(dc, previous)
        DeleteObject(track)
        EndPaint(hwnd, paint.ptr)
    }
    return 0
}

/** Единственное окно процесса лончера и его прогресс; WndProc — статические функции C. */
object LauncherWindowRegistry {
    var instance: LauncherWindow? = null

    @Volatile
    var progressPercent = 0
}
