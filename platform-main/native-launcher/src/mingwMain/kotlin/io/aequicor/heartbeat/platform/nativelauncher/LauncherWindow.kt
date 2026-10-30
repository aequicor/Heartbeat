@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import io.aequicor.heartbeat.platform.nativelauncher.provisioning.BootstrapLayout
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.BootstrapProvisioner
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ImageStore
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionException
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionProgress
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionStage
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionedImage
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.windowsHttpTransport
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.windows.BS_OWNERDRAW
import platform.windows.BeginPaint
import platform.windows.CLIP_DEFAULT_PRECIS
import platform.windows.CloseHandle
import platform.windows.CreateFontW
import platform.windows.CreatePen
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
import platform.windows.GetClientRect
import platform.windows.GetDC
import platform.windows.GetDeviceCaps
import platform.windows.GetFileAttributesW
import platform.windows.GetForegroundWindow
import platform.windows.GetMessageW
import platform.windows.GetModuleHandleW
import platform.windows.GetMonitorInfoW
import platform.windows.GetProcAddress
import platform.windows.GetStockObject
import platform.windows.GetSystemMetrics
import platform.windows.GetTickCount64
import platform.windows.GetWindowPlacement
import platform.windows.GetWindowRect
import platform.windows.HANDLE
import platform.windows.HBRUSH
import platform.windows.HDC
import platform.windows.HDC__
import platform.windows.HFONT
import platform.windows.HICON
import platform.windows.HICON__
import platform.windows.HMODULE
import platform.windows.HTCAPTION
import platform.windows.HTCLIENT
import platform.windows.HWND
import platform.windows.HWND__
import platform.windows.IDC_ARROW
import platform.windows.IMAGE_ICON
import platform.windows.INVALID_FILE_ATTRIBUTES
import platform.windows.InvalidateRect
import platform.windows.IsIconic
import platform.windows.IsZoomed
import platform.windows.KillTimer
import platform.windows.LOGPIXELSX
import platform.windows.LOGPIXELSY
import platform.windows.LPARAM
import platform.windows.LRESULT
import platform.windows.LR_LOADFROMFILE
import platform.windows.LineTo
import platform.windows.LoadCursorW
import platform.windows.LoadImageW
import platform.windows.LoadLibraryW
import platform.windows.MINMAXINFO
import platform.windows.MONITORINFO
import platform.windows.MONITOR_DEFAULTTONEAREST
import platform.windows.MSG
import platform.windows.MonitorFromWindow
import platform.windows.MoveToEx
import platform.windows.MoveWindow
import platform.windows.NULL_BRUSH
import platform.windows.NULL_PEN
import platform.windows.OUT_DEFAULT_PRECIS
import platform.windows.PAINTSTRUCT
import platform.windows.POINT
import platform.windows.PS_SOLID
import platform.windows.PostMessageW
import platform.windows.PostQuitMessage
import platform.windows.RECT
import platform.windows.Rectangle
import platform.windows.RegisterClassExW
import platform.windows.ReleaseDC
import platform.windows.RoundRect
import platform.windows.SM_CXSCREEN
import platform.windows.SM_CYSCREEN
import platform.windows.SS_CENTER
import platform.windows.SS_ICON
import platform.windows.SS_LEFT
import platform.windows.STM_SETICON
import platform.windows.SW_HIDE
import platform.windows.SW_MAXIMIZE
import platform.windows.SW_MINIMIZE
import platform.windows.SW_RESTORE
import platform.windows.SW_SHOW
import platform.windows.ScreenToClient
import platform.windows.SelectObject
import platform.windows.SendMessageW
import platform.windows.SetBkMode
import platform.windows.SetEnvironmentVariableW
import platform.windows.SetProcessDPIAware
import platform.windows.SetTextColor
import platform.windows.SetTimer
import platform.windows.SetWindowTextW
import platform.windows.ShowWindow
import platform.windows.Sleep
import platform.windows.TRANSPARENT
import platform.windows.TerminateProcess
import platform.windows.TranslateMessage
import platform.windows.UINT
import platform.windows.UpdateWindow
import platform.windows.WAIT_OBJECT_0
import platform.windows.WINDOWPLACEMENT
import platform.windows.WM_APP
import platform.windows.WM_CLOSE
import platform.windows.WM_COMMAND
import platform.windows.WM_CTLCOLORSTATIC
import platform.windows.WM_DESTROY
import platform.windows.WM_DRAWITEM
import platform.windows.WM_GETMINMAXINFO
import platform.windows.WM_NCCALCSIZE
import platform.windows.WM_NCHITTEST
import platform.windows.WM_PAINT
import platform.windows.WM_SETFONT
import platform.windows.WM_SIZE
import platform.windows.WM_TIMER
import platform.windows.WNDCLASSEXW
import platform.windows.WPARAM
import platform.windows.WPF_RESTORETOMAXIMIZED
import platform.windows.WS_CHILD
import platform.windows.WS_OVERLAPPEDWINDOW
import platform.windows.WS_VISIBLE
import platform.windows.WaitForSingleObject
import kotlin.concurrent.Volatile
import kotlin.random.Random

/**
 * Нативное окно лончера (Win32) по макету `design/features/native-launcher/native-launcher.pen`:
 * edge-to-edge как у основного приложения: стандартные стили окна (resize, Aero Snap, анимации,
 * тень, taskbar), видимая рамка и title bar убраны через WM_NCCALCSIZE; встроенная иконка,
 * кастомный caption с кнопками управления, логотип, название, этап, прогресс-бар, байты, Отмена/Повторить.
 * Окно создаётся до старта JVM; сворачивание не останавливает загрузку, закрытие отменяет запуск.
 * Тема — из токенов дизайн-системы ([LauncherTheme]), обновления прогресса — не чаще 100 мс.
 */
class LauncherWindow(
    private val config: LauncherConfig,
    private val store: ImageStore,
    private val args: List<String> = emptyList(),
) {
    private var cancelButton: HWND? = null
    internal var retryButton: HWND? = null
    private var captionIcon: HWND? = null
    private var captionText: HWND? = null
    internal var minimizeButton: HWND? = null
    internal var maximizeButton: HWND? = null
    internal var closeButton: HWND? = null
    internal var hwnd: HWND? = null
    private var stageText: HWND? = null
    private var bytesText: HWND? = null
    private var titleText: HWND? = null
    private var iconStatic: HWND? = null
    private var progressBar: HWND? = null
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

    private val pendingImage = MutableStateFlow<ProvisionedImage?>(null)
    private var handoffDirectory: Path? = null
    private var systemDpi = DEFAULT_DPI

    internal val theme = LauncherTheme.current()

    fun run(offline: ProvisionedImage?): Int {
        LauncherWindowRegistry.instance = this
        memScoped {
            SetProcessDPIAware()
            val screenDc = GetDC(null)
            systemDpi = GetDeviceCaps(screenDc, LOGPIXELSX).coerceAtLeast(DEFAULT_DPI)
            ReleaseDC(null, screenDc)
            val initialWidth = scale(WINDOW_WIDTH).coerceAtMost(GetSystemMetrics(SM_CXSCREEN) * SCREEN_FRACTION / 10)
            val initialHeight = scale(WINDOW_HEIGHT).coerceAtMost(GetSystemMetrics(SM_CYSCREEN) * SCREEN_FRACTION / 10)
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
                // Edge-to-edge как у основного приложения: стандартные стили (caption + thickframe) —
                // Windows сам даёт resize, Aero Snap, анимации, тень и taskbar. Видимую рамку убирает
                // WM_NCCALCSIZE (вся область окна становится клиентской), перетаскивание — WM_NCHITTEST.
                WS_OVERLAPPEDWINDOW.toUInt(),
                (GetSystemMetrics(SM_CXSCREEN) - initialWidth) / 2,
                (GetSystemMetrics(SM_CYSCREEN) - initialHeight) / 2,
                initialWidth,
                initialHeight,
                null,
                null,
                instance,
                null,
            )
            checkNotNull(window) { "Cannot create launcher window" }
            hwnd = window
            extendFrameIntoClientArea(window)
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
        while (provisioningJob?.isCompleted == false) Sleep(CLEANUP_POLL_MS)
        val code = childProcess?.let { process ->
            try {
                if (isCancelled) cancelChild(process)
                waitForProcessExit(process)
            } finally {
                CloseHandle(process)
            }
        } ?: 0
        handoffDirectory?.let { FileSystem.SYSTEM.deleteRecursively(it, mustExist = false) }
        return if (isCancelled) 0 else code
    }

    /**
     * DWM-тень у окна без рамки (WM_NCCALCSIZE = 0): при 1px-полях DWM рендерит тень,
     * не показывая нативный фрейм. dwmapi нет в платформенном klib, поэтому вызов —
     * через GetProcAddress; MARGINS — четыре int подряд, передаём как массив.
     */
    private fun extendFrameIntoClientArea(window: HWND) = memScoped {
        val dwmapi = LoadLibraryW("dwmapi.dll".wcstr.getPointer(this)) ?: return@memScoped
        val address = GetProcAddress(dwmapi, "DwmExtendFrameIntoClientArea") ?: return@memScoped
        val extend = address.reinterpret<CFunction<(HWND?, CPointer<IntVar>?) -> Int>>()
        val raw = platform.posix.calloc(MARGINS_INTS.toULong(), INT32_BYTES.toULong()) ?: return@memScoped
        val margins = raw.reinterpret<IntVar>()
        for (index in 0 until MARGINS_INTS) {
            (margins + index.toLong())?.pointed?.value = FRAME_MARGIN_PX
        }
        extend(window, margins)
        platform.posix.free(raw)
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
            // Без hCursor Windows не сбрасывает курсор над окном: остаются «песочные часы» запуска.
            wndClass.hCursor = LoadCursorW(null, IDC_ARROW)
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
        // Кастомный caption: свои кнопки управления окном вместо системного title bar.
        captionIcon = control("STATIC", "", window, SS_ICON or SS_LEFT)
        captionText = control("STATIC", "Heartbeat", window, SS_LEFT)
        minimizeButton = control("BUTTON", "", window, BS_OWNERDRAW)
        maximizeButton = control("BUTTON", "", window, BS_OWNERDRAW)
        closeButton = control("BUTTON", "", window, BS_OWNERDRAW)
        loadIcon(CAPTION_ICON_SIZE)?.let { small ->
            SendMessageW(captionIcon, STM_SETICON.toUInt(), small.rawValue.toLong().toULong(), 0L)
        }
        listOf(titleText to titleFont, stageText to bodyFont, bytesText to captionFont).forEach { (text, font) ->
            if (font != null) SendMessageW(text, WM_SETFONT.toUInt(), font.rawValue.toLong().toULong(), 1L)
        }
        captionFont?.let { font ->
            SendMessageW(captionText, WM_SETFONT.toUInt(), font.rawValue.toLong().toULong(), 1L)
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
            -scale(height), 0, 0, 0, weight, 0u, 0u, 0u, DEFAULT_CHARSET.toUInt(),
            OUT_DEFAULT_PRECIS.toUInt(), CLIP_DEFAULT_PRECIS.toUInt(), DEFAULT_QUALITY.toUInt(),
            (DEFAULT_PITCH or FF_DONTCARE).toUInt(), "Segoe UI".wcstr.getPointer(this),
        )
    }!!

    private fun loadIcon(size: Int): HICON? {
        val pixelSize = scale(size)
        val candidates = listOf(
            "icons/heartbeat.ico",
            "icons/dev/heartbeat.ico",
            "platform-main/desktop/icons/heartbeat.ico",
            "platform-main/desktop/icons/dev/heartbeat.ico",
        )
        val roots = listOf(executableDirectory(), ".")
        val fromFile = roots.firstNotNullOfOrNull { root ->
            candidates.firstNotNullOfOrNull { candidate ->
                val path = if (root == ".") candidate else "$root/$candidate"
                loadIconFrom(path, pixelSize)
            }
        }
        // Файла рядом с exe может не быть (E2E, dev-прогон): тогда иконка — из встроенной копии.
        return fromFile ?: loadEmbeddedIcon(pixelSize)
    }

    private fun loadEmbeddedIcon(size: Int): HICON? {
        val bytes = LauncherConfig.decodeBase64(EMBEDDED_ICON_BASE64)
        val temp = embeddedIconPath(bytes.size) ?: return null
        val fs = okio.FileSystem.SYSTEM
        if (fs.metadataOrNull(temp)?.size != bytes.size.toLong()) {
            fs.write(temp) { write(bytes) }
        }
        return loadIconFrom(temp.toString(), size)
    }

    private fun embeddedIconPath(size: Int): okio.Path? {
        val temp = launcherEnvironment("TEMP") ?: return null
        return "$temp/heartbeat-launcher-icon-v$ICON_FILE_VERSION-$size.ico".toPath()
    }

    private fun executableDirectory(): String = checkNotNull(nativeLauncherExecutable().parent).toString()

    private fun loadIconFrom(path: String, size: Int): HICON? = memScoped {
        if (GetFileAttributesW(path.wcstr.getPointer(this)) == INVALID_FILE_ATTRIBUTES) return@memScoped null
        LoadImageW(null, path.wcstr.getPointer(this), IMAGE_ICON.toUInt(), size, size, LR_LOADFROMFILE.toUInt())
            ?.reinterpret<HICON__>()
    }

    internal fun layout(window: HWND) {
        memScoped {
            val rect = alloc<RECT>()
            GetClientRect(window, rect.ptr)
            val centerX = (rect.right - rect.left) * DEFAULT_DPI / systemDpi / 2
            val centerY = (rect.bottom - rect.top) * DEFAULT_DPI / systemDpi / 2
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
            // Кастомный caption: иконка + заголовок слева, кнопки управления справа.
            val clientWidth = (rect.right - rect.left) * DEFAULT_DPI / systemDpi
            move(
                captionIcon,
                CAPTION_PADDING,
                (CAPTION_HEIGHT - CAPTION_ICON_SIZE) / 2,
                CAPTION_ICON_SIZE,
                CAPTION_ICON_SIZE,
            )
            move(
                captionText,
                CAPTION_PADDING + CAPTION_ICON_SIZE + CAPTION_PADDING,
                0,
                CAPTION_TITLE_WIDTH,
                CAPTION_HEIGHT,
            )
            move(closeButton, clientWidth - CAPTION_BUTTON_WIDTH, 0, CAPTION_BUTTON_WIDTH, CAPTION_HEIGHT)
            move(maximizeButton, clientWidth - CAPTION_BUTTON_WIDTH * 2, 0, CAPTION_BUTTON_WIDTH, CAPTION_HEIGHT)
            move(
                minimizeButton,
                clientWidth - CAPTION_BUTTON_WIDTH * CAPTION_BUTTON_COUNT,
                0,
                CAPTION_BUTTON_WIDTH,
                CAPTION_HEIGHT,
            )
        }
    }

    internal fun scale(value: Int): Int = value * systemDpi / DEFAULT_DPI

    private fun move(control: HWND?, x: Int, y: Int, width: Int, height: Int) {
        control?.let { MoveWindow(it, scale(x), scale(y), scale(width), scale(height), 1) }
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
                val image = config.currentImage(store) ?: windowsHttpTransport().use { transport ->
                    BootstrapProvisioner(
                        store = store,
                        transport = transport,
                        manifestUrl = config.manifestUrl,
                        signatureUrl = config.signatureUrl,
                        trustedKeys = config.trustedKeys,
                        expectedVersion = config.version,
                        expectedTarget = config.target,
                        expectedChannel = config.channel,
                    ).provision(
                        onProgress = { progress -> updateProgress(progress) },
                        isCancelled = { isCancelled },
                    )
                }
                pendingImage.value = image
                PostMessageW(hwnd, IMAGE_READY_MESSAGE.toUInt(), 0uL, 0L)
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

            else -> "Не удалось запустить приложение. Повторите попытку."
        }
        setText(stageText, text)
        retryButton?.let { ShowWindow(it, SW_SHOW) }
    }

    internal fun onImageReady() {
        val image = pendingImage.value ?: return
        pendingImage.value = null
        launcherLog.i("Launcher") { "Application image ready for native UI launch" }
        if (!isCancelled) startApplication(image)
    }

    private fun startApplication(image: ProvisionedImage) {
        try {
            startApplicationChecked(image)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            launcherLog.w("Launcher", error) { "Cannot initialize application handoff" }
            cancel()
        }
    }

    private fun startApplicationChecked(image: ProvisionedImage) {
        if (handoffStarted || isCancelled) return
        handoffStarted = true
        setText(stageText, "Запуск Heartbeat…")
        handoffDirectory?.let { FileSystem.SYSTEM.deleteRecursively(it, mustExist = false) }
        val handoffDir = store.root.resolve("handoff-${Random.nextLong()}")
        handoffDirectory = handoffDir
        FileSystem.SYSTEM.createDirectories(handoffDir)
        memScoped {
            check(
                SetEnvironmentVariableW(
                    "HEARTBEAT_HANDOFF_DIR".wcstr.getPointer(this),
                    handoffDir.toString().wcstr.getPointer(this),
                ) != 0,
            ) { "Cannot set native handoff directory" }
            check(
                SetEnvironmentVariableW(
                    "HEARTBEAT_NATIVE_LAUNCHER".wcstr.getPointer(this),
                    nativeLauncherExecutable().toString().wcstr.getPointer(this),
                ) != 0,
            ) { "Cannot set native launcher path" }
        }
        val executable = image.directory.resolve(BootstrapLayout.forPlatform("windows", config.channel).executable)
        val process = launchProcess(executable.toString(), args)
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
        try {
            onImageReady()
            checkHandoff()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            launcherLog.w("Launcher", error) { "Application handoff failed" }
            cancel()
        }
    }

    private fun checkHandoff() {
        val process = childProcess ?: return
        if (WaitForSingleObject(process, 0u) == WAIT_OBJECT_0) {
            KillTimer(hwnd, HANDOFF_TIMER_ID.toULong())
            val code = processExitCode(process)
            if (code == 0) {
                DestroyWindow(hwnd)
            } else {
                CloseHandle(process)
                childProcess = null
                handoffStarted = false
                val error = IllegalStateException("Application exited before its first window: $code")
                launcherLog.w("Launcher", error) { "Application exited before showing a window" }
                showError(error)
            }
            return
        }
        val directory = handoffDirectory ?: return
        val fs = FileSystem.SYSTEM
        if (fs.exists(directory.resolve("ready"))) {
            fs.write(directory.resolve("presentation.properties")) { writeUtf8(presentation().properties()) }
            KillTimer(hwnd, HANDOFF_TIMER_ID.toULong())
            DestroyWindow(hwnd)
            fs.write(directory.resolve("hidden")) { writeUtf8("hidden") }
        }
    }

    /** GetDeviceCaps matches the system-DPI-aware process; JVM/AWT uses logical pixels for window bounds. */
    private fun presentation(): HandoffPresentation = memScoped {
        val window = checkNotNull(hwnd)
        val placement = alloc<WINDOWPLACEMENT>()
        placement.length = sizeOf<WINDOWPLACEMENT>().toUInt()
        check(GetWindowPlacement(window, placement.ptr) != 0) { "Cannot read native window placement" }
        val rect = placement.rcNormalPosition
        val monitor = alloc<MONITORINFO>()
        monitor.cbSize = sizeOf<MONITORINFO>().toUInt()
        check(GetMonitorInfoW(MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST.toUInt()), monitor.ptr) != 0) {
            "Cannot read native window monitor"
        }
        val offsetX = monitor.rcWork.left - monitor.rcMonitor.left
        val offsetY = monitor.rcWork.top - monitor.rcMonitor.top
        val dc = GetDC(null)
        val dpiX = GetDeviceCaps(dc, LOGPIXELSX).coerceAtLeast(DEFAULT_DPI)
        val dpiY = GetDeviceCaps(dc, LOGPIXELSY).coerceAtLeast(DEFAULT_DPI)
        ReleaseDC(null, dc)
        HandoffPresentation(
            GetForegroundWindow() == window,
            IsIconic(window) != 0,
            (rect.left + offsetX) * DEFAULT_DPI / dpiX,
            (rect.top + offsetY) * DEFAULT_DPI / dpiY,
            (rect.right - rect.left) * DEFAULT_DPI / dpiX,
            (rect.bottom - rect.top) * DEFAULT_DPI / dpiY,
            IsZoomed(window) != 0 || (placement.flags and WPF_RESTORETOMAXIMIZED.toUInt()) != 0u,
        )
    }

    internal fun cancel() {
        if (isCancelled) return
        isCancelled = true
        hwnd?.let { ShowWindow(it, SW_HIDE) }
        provisioningJob?.cancel()
        try {
            handoffDirectory?.let { directory ->
                FileSystem.SYSTEM.write(directory.resolve("cancel")) { writeUtf8("cancel") }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            launcherLog.w("Launcher", error) { "Cannot request cooperative application cancellation" }
        } finally {
            DestroyWindow(hwnd)
        }
    }

    private fun cancelChild(process: HANDLE) {
        if (WaitForSingleObject(process, CANCELLATION_GRACE_MS) == WAIT_OBJECT_0) return
        launcherLog.w("Launcher", IllegalStateException("Child did not acknowledge cancellation")) {
            "Forcing application exit after cancellation grace period"
        }
        check(TerminateProcess(process, 1u) != 0 || WaitForSingleObject(process, 0u) == WAIT_OBJECT_0) {
            "Cannot stop launcher child"
        }
    }

    /**
     * Hit-test поверх стандартного: системная caption-полоса (HTCAPTION) даёт перетаскивание и Aero Snap
     * как у рамочного окна; над нашими кнопками возвращаем HTCLIENT, чтобы они получали клики;
     * HTCLIENT в пределах полосы caption поднимаем до HTCAPTION (область заголовка для drag).
     * Зоны ресайза (HTTOP/HTLEFT/…) остаются дефолтным результатом и не перекрываются.
     */
    internal fun onNcHitTest(lParam: LPARAM): LRESULT? = memScoped {
        val window = hwnd ?: return@memScoped null
        val screenX = (lParam.toInt() and 0xFFFF).toShort().toInt()
        val screenY = (lParam.toInt() shr 16).toShort().toInt()
        val base = DefWindowProcW(window, WM_NCHITTEST.toUInt(), 0uL, lParam)
        if (base != HTCAPTION.toLong() && base != HTCLIENT.toLong()) return@memScoped null
        val point = alloc<POINT>()
        point.x = screenX
        point.y = screenY
        ScreenToClient(window, point.ptr)
        val overButton = listOfNotNull(minimizeButton, maximizeButton, closeButton).any { button ->
            val rect = alloc<RECT>()
            GetWindowRect(button, rect.ptr)
            screenX >= rect.left && screenX < rect.right && screenY >= rect.top && screenY < rect.bottom
        }
        if (overButton) return@memScoped HTCLIENT.toLong()
        if (point.y < 0 || point.y >= scale(CAPTION_HEIGHT)) return@memScoped null
        HTCAPTION.toLong()
    }

    /** Максимизация без перекрытия панели задач: разворачиваем на рабочую область монитора. */
    internal fun onGetMinMaxInfo(lParam: LPARAM) {
        val info = lParam.toCPointer<MINMAXINFO>()?.pointed ?: return
        val window = hwnd ?: return
        info.ptMinTrackSize.x = scale(MIN_WINDOW_WIDTH)
        info.ptMinTrackSize.y = scale(MIN_WINDOW_HEIGHT)
        memScoped {
            val monitor = MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST.toUInt())
            val monitorInfo = alloc<MONITORINFO>()
            monitorInfo.cbSize = sizeOf<MONITORINFO>().toUInt()
            if (GetMonitorInfoW(monitor, monitorInfo.ptr) != 0) {
                info.ptMaxSize.x = monitorInfo.rcWork.right - monitorInfo.rcWork.left
                info.ptMaxSize.y = monitorInfo.rcWork.bottom - monitorInfo.rcWork.top
                info.ptMaxPosition.x = monitorInfo.rcWork.left - monitorInfo.rcMonitor.left
                info.ptMaxPosition.y = monitorInfo.rcWork.top - monitorInfo.rcMonitor.top
            }
        }
    }

    internal fun toggleMaximize() {
        val window = hwnd ?: return
        ShowWindow(window, if (IsZoomed(window) != 0) SW_RESTORE else SW_MAXIMIZE)
    }

    fun onCommand(control: HWND?) {
        when (control) {
            cancelButton -> cancel()
            retryButton -> startProvisioning()
            minimizeButton -> hwnd?.let { ShowWindow(it, SW_MINIMIZE) }
            maximizeButton -> toggleMaximize()
            closeButton -> hwnd?.let { PostMessageW(it, WM_CLOSE.toUInt(), 0uL, 0L) }
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
        private const val ICON_FILE_VERSION = 1

        // Кастомный caption (полоса вместо системного title bar).
        private const val CAPTION_HEIGHT = 32
        private const val CAPTION_BUTTON_WIDTH = 46
        private const val CAPTION_BUTTON_COUNT = 3
        private const val CAPTION_ICON_SIZE = 16
        private const val CAPTION_PADDING = 10
        private const val CAPTION_TITLE_WIDTH = 160
        internal const val CAPTION_GLYPH_HALF = 5
        private const val FRAME_MARGIN_PX = 1
        private const val MARGINS_INTS = 4
        private const val INT32_BYTES = 4
        private const val HANDOFF_TIMER_ID = 42
        internal const val IMAGE_READY_MESSAGE = WM_APP + 1
        private const val CANCELLATION_GRACE_MS = 30_000u
        private const val CLEANUP_POLL_MS = 10u
        private const val DEFAULT_DPI = 96
        private const val SCREEN_FRACTION = 9
        private const val MIN_WINDOW_WIDTH = 640
        private const val MIN_WINDOW_HEIGHT = 480
        private const val HANDOFF_INTERVAL_MS = 50
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
    val handled = LauncherWindowRegistry.instance?.let { window ->
        when (message.toInt()) {
            WM_SIZE -> {
                window.layout(hwnd!!)
                null
            }

            WM_COMMAND -> {
                window.onCommand(lParam.toCPointer<HWND__>())
                null
            }

            WM_NCCALCSIZE -> 0L

            WM_NCHITTEST -> window.onNcHitTest(lParam)

            WM_GETMINMAXINFO -> {
                window.onGetMinMaxInfo(lParam)
                null
            }

            WM_TIMER, LauncherWindow.IMAGE_READY_MESSAGE -> {
                window.onHandoffTick()
                null
            }

            WM_CLOSE -> {
                window.cancel()
                0L
            }

            WM_DESTROY -> {
                PostQuitMessage(0)
                null
            }

            WM_CTLCOLORSTATIC ->
                window.onCtlColorStatic(wParam.toLong().toCPointer<HDC__>())?.rawValue?.toLong()

            WM_DRAWITEM -> {
                drawButton(window, lParam)
                null
            }

            else -> null
        }
    }
    return handled ?: DefWindowProcW(hwnd, message, wParam, lParam)
}

private fun drawButton(window: LauncherWindow, lParam: LPARAM) {
    val item = lParam.toCPointer<DRAWITEMSTRUCT>()?.pointed ?: return
    if (item.hwndItem == window.minimizeButton || item.hwndItem == window.maximizeButton ||
        item.hwndItem == window.closeButton
    ) {
        drawCaptionButton(window, item)
        return
    }
    val dc = item.hDC
    val rect = item.rcItem
    val isPrimary = item.hwndItem == window.retryButton
    memScoped {
        val background = CreateSolidBrush(if (isPrimary) window.theme.primaryButton else window.theme.background)
        val previous = SelectObject(dc, background)
        // Без NULL_PEN фигура обводится стоковым чёрным пером.
        val nullPen = SelectObject(dc, GetStockObject(NULL_PEN))
        val corner = window.scale(LauncherWindow.BUTTON_CORNER_RADIUS)
        RoundRect(dc, rect.left, rect.top, rect.right, rect.bottom, corner, corner)
        SelectObject(dc, previous)
        SelectObject(dc, nullPen)
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
        // Без NULL_PEN контур фигуры — стоковым чёрным пером.
        val nullPen = SelectObject(dc, GetStockObject(NULL_PEN))
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
        SelectObject(dc, nullPen)
        DeleteObject(track)
        EndPaint(hwnd, paint.ptr)
    }
    return 0
}

/** Кнопки кастомного caption: плоский фон окна + глифы, нарисованные фигурами GDI (без зависимости от шрифтов). */
private fun drawCaptionButton(window: LauncherWindow, item: DRAWITEMSTRUCT) = memScoped {
    val dc = item.hDC
    val rect = item.rcItem
    // Фон кнопки — как у окна: owner-draw не стирает сам, контур гасим NULL_PEN.
    val background = CreateSolidBrush(window.theme.background)
    val previous = SelectObject(dc, background)
    val nullPen = SelectObject(dc, GetStockObject(NULL_PEN))
    Rectangle(dc, rect.left, rect.top, rect.right, rect.bottom)
    SelectObject(dc, previous)
    SelectObject(dc, nullPen)
    DeleteObject(background)

    val glyphColor = window.theme.textPrimary
    val cx = (rect.left + rect.right) / 2
    val cy = (rect.top + rect.bottom) / 2
    val half = window.scale(LauncherWindow.CAPTION_GLYPH_HALF)
    when (item.hwndItem) {
        window.minimizeButton -> {
            val brush = CreateSolidBrush(glyphColor)
            val old = SelectObject(dc, brush)
            val oldPen = SelectObject(dc, GetStockObject(NULL_PEN))
            Rectangle(dc, cx - half, cy - 1, cx + half, cy + 1)
            SelectObject(dc, old)
            SelectObject(dc, oldPen)
            DeleteObject(brush)
        }

        window.maximizeButton -> {
            val pen = CreatePen(PS_SOLID.toInt(), window.scale(CAPTION_PEN_WIDTH), glyphColor)
            val oldPen = SelectObject(dc, pen)
            val oldBrush = SelectObject(dc, GetStockObject(NULL_BRUSH.toInt()))
            if (window.hwnd?.let { IsZoomed(it) } != 0) {
                // Глиф восстановления: два перекрывающихся прямоугольника.
                Rectangle(dc, cx - half + 2, cy - half, cx + half, cy + half - 2)
                Rectangle(dc, cx - half, cy - half + 2, cx + half - 2, cy + half)
            } else {
                Rectangle(dc, cx - half, cy - half, cx + half, cy + half)
            }
            SelectObject(dc, oldPen)
            SelectObject(dc, oldBrush)
            DeleteObject(pen)
        }

        window.closeButton -> {
            val pen = CreatePen(PS_SOLID.toInt(), window.scale(CAPTION_PEN_WIDTH), glyphColor)
            val old = SelectObject(dc, pen)
            MoveToEx(dc, cx - half, cy - half, null)
            LineTo(dc, cx + half, cy + half)
            MoveToEx(dc, cx - half, cy + half, null)
            LineTo(dc, cx + half, cy - half)
            SelectObject(dc, old)
            DeleteObject(pen)
        }
    }
}

private const val CAPTION_PEN_WIDTH = 1

/** Единственное окно процесса лончера и его прогресс; WndProc — статические функции C. */
object LauncherWindowRegistry {
    var instance: LauncherWindow? = null

    @Volatile
    var progressPercent = 0
}
