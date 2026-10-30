@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import io.aequicor.heartbeat.platform.nativelauncher.provisioning.BootstrapLayout
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ImageStore
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionException
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionProgress
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionStage
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionedImage
import kotlinx.cinterop.alloc
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import platform.windows.CloseHandle
import platform.windows.CreateSolidBrush
import platform.windows.CreateWindowExW
import platform.windows.DRAWITEMSTRUCT
import platform.windows.DefWindowProcW
import platform.windows.DeleteObject
import platform.windows.DestroyWindow
import platform.windows.DispatchMessageW
import platform.windows.FW_NORMAL
import platform.windows.GetClientRect
import platform.windows.GetMessageW
import platform.windows.GetModuleHandleW
import platform.windows.GetSystemMetrics
import platform.windows.GetTickCount64
import platform.windows.GetWindowRect
import platform.windows.HANDLE
import platform.windows.HBRUSH
import platform.windows.HDC
import platform.windows.HDC__
import platform.windows.HFONT
import platform.windows.HICON
import platform.windows.HMODULE
import platform.windows.HWND
import platform.windows.HWND__
import platform.windows.IDC_ARROW
import platform.windows.InvalidateRect
import platform.windows.IsZoomed
import platform.windows.KillTimer
import platform.windows.LPARAM
import platform.windows.LRESULT
import platform.windows.LoadCursorW
import platform.windows.MSG
import platform.windows.POINT
import platform.windows.PostMessageW
import platform.windows.PostQuitMessage
import platform.windows.RECT
import platform.windows.RegisterClassExW
import platform.windows.SM_CXSCREEN
import platform.windows.SM_CYSCREEN
import platform.windows.SW_HIDE
import platform.windows.SW_MAXIMIZE
import platform.windows.SW_MINIMIZE
import platform.windows.SW_RESTORE
import platform.windows.SW_SHOW
import platform.windows.SetBkMode
import platform.windows.SetProcessDPIAware
import platform.windows.SetTextColor
import platform.windows.SetTimer
import platform.windows.ShowWindow
import platform.windows.TRANSPARENT
import platform.windows.TranslateMessage
import platform.windows.UINT
import platform.windows.UpdateWindow
import platform.windows.WAIT_OBJECT_0
import platform.windows.WM_CLOSE
import platform.windows.WM_COMMAND
import platform.windows.WM_CTLCOLORSTATIC
import platform.windows.WM_DESTROY
import platform.windows.WM_DRAWITEM
import platform.windows.WM_GETMINMAXINFO
import platform.windows.WM_NCCALCSIZE
import platform.windows.WM_NCHITTEST
import platform.windows.WM_PAINT
import platform.windows.WM_SIZE
import platform.windows.WM_TIMER
import platform.windows.WNDCLASSEXW
import platform.windows.WPARAM
import platform.windows.WS_OVERLAPPEDWINDOW
import platform.windows.WaitForSingleObject
import kotlin.concurrent.Volatile

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
    private var embeddedMode = false
    private var embeddedExecutable = ""
    private var previousJvmPath: String? = null
    internal var cancelButton: HWND? = null
    internal var retryButton: HWND? = null
    internal var captionText: HWND? = null
    internal var minimizeButton: HWND? = null
    internal var maximizeButton: HWND? = null
    internal var closeButton: HWND? = null
    internal var hwnd: HWND? = null
    private var provisioningJob: Job? = null
    internal var bodyFont: HFONT? = null
    internal var captionFont: HFONT? = null
    private var backgroundBrush: HBRUSH? = null
    private var sidebarBrush: HBRUSH? = null
    private var handoffShowRequested = false
    private var handoffShowAtMillis = 0L
    private var handoffPresentation: LauncherPresentation? = null
    private var snapshotBlockedAtMillis = 0L
    private var handoffDirectory = store.root.resolve("handoff")
        .resolve("${platform.windows.GetCurrentProcessId()}-${GetTickCount64()}")
    private var handoffAttempt = 0
    internal var isMoving = false
    private var isMovePublished = false
    private val controlLayout = LauncherControlLayout()
    private var systemDpi = DEFAULT_DPI
    private var isSnapshotDirty = false
    private var composeWindow: HWND? = null
    internal var hoveredButton: HWND? = null
    private var lastProgressMs = 0L
    private var renderer: LauncherFrameRenderer? = null
    internal val controlFrames = LauncherControlFrames()
    private var motion: LauncherMotion? = null
    internal var previousTip: HWND? = null
    internal var nextTip: HWND? = null
    private var isRussian = true
    private var isDownloadButtonVisible = false
    private var areTipButtonsVisible = false
    private var isRetryVisible = false

    @Volatile
    private var sceneStatus = LauncherSceneStatus()

    @Volatile
    private var isCancelled = false

    @Volatile
    private var handoffStarted = false

    @Volatile
    private var childProcess: HANDLE? = null

    internal var theme = LauncherTheme.current()
        private set

    private val pendingImage = MutableStateFlow<ProvisionedImage?>(null)

    fun run(offline: ProvisionedImage?): Int {
        sceneStatus = LauncherSceneStatus(isOpening = offline != null)
        LauncherWindowRegistry.instance = this
        memScoped {
            SetProcessDPIAware()
            systemDpi = launcherSystemDpi()
            val initialWidth = scale(
                WINDOW_WIDTH,
            ).coerceAtMost(GetSystemMetrics(SM_CXSCREEN) * SCREEN_PERCENT / PERCENT)
            val initialHeight = scale(
                WINDOW_HEIGHT,
            ).coerceAtMost(GetSystemMetrics(SM_CYSCREEN) * SCREEN_PERCENT / PERCENT)
            motion = LauncherMotion(offline == null, isLauncherMotionReduced(), GetTickCount64().toLong())
            val language = platform.windows.GetUserDefaultUILanguage().toInt() and PRIMARY_LANGUAGE_MASK
            isRussian = language == RUSSIAN_LANGUAGE
            val instance = GetModuleHandleW(null)
            backgroundBrush = CreateSolidBrush(theme.background)
            sidebarBrush = CreateSolidBrush(theme.sidebar)
            bodyFont = createLauncherFont(scale(BODY_FONT_SIZE), FW_NORMAL)
            captionFont = createLauncherFont(scale(CAPTION_FONT_SIZE), FW_NORMAL)
            val icon = loadLauncherIcon(scale(ICON_SIZE))
            registerWindowClass(instance, icon)
            val window = CreateWindowExW(
                // Commit the parent scene and owner-drawn children together during live resize.
                platform.windows.WS_EX_COMPOSITED.toUInt(),
                WINDOW_CLASS.wcstr.getPointer(this),
                "Heartbeat".wcstr.getPointer(this),
                // Edge-to-edge как у основного приложения: стандартные стили (caption + thickframe) —
                // Windows сам даёт resize, Aero Snap, анимации, тень и taskbar. Видимую рамку убирает
                // WM_NCCALCSIZE (вся область окна становится клиентской), перетаскивание — WM_NCHITTEST.
                (WS_OVERLAPPEDWINDOW or platform.windows.WS_CLIPCHILDREN).toUInt(),
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
            initializeRendering(window)
            extendFrameIntoClientArea(window)
            applyLauncherFrameTheme(window, theme)
            createControls(window)
            // Показ только после создания контролов: белого всплеска пустого окна нет.
            ShowWindow(window, SW_SHOW)
            UpdateWindow(window)
            SetTimer(hwnd, HANDOFF_TIMER_ID.toULong(), HANDOFF_INTERVAL_MS.toUInt(), null)
        }
        if (offline != null) {
            startApplication(offline)
        } else {
            startProvisioning()
        }
        messageLoop()
        io.aequicor.heartbeat.platform.nativelauncher.windowsjvm.hb_jvm_close_cover()
        renderer?.close()
        controlFrames.close()
        provisioningJob?.cancel()
        return finishLauncherChild(provisioningJob, childProcess, isCancelled, handoffDirectory)
    }

    private fun initializeRendering(window: HWND) {
        renderer = LauncherFrameRenderer(
            window,
            systemDpi.toDouble() / DEFAULT_DPI,
            checkNotNull(motion),
            theme,
            { sceneStatus },
            isRussian,
            ::showError,
        )
    }

    private fun registerWindowClass(instance: HMODULE?, icon: HICON?) {
        memScoped {
            val className = WINDOW_CLASS.wcstr.getPointer(this)
            val wndClass = alloc<WNDCLASSEXW>()
            wndClass.cbSize = sizeOf<WNDCLASSEXW>().toUInt()
            wndClass.lpfnWndProc = staticCFunction(::launcherWndProc)
            wndClass.style = (platform.windows.CS_HREDRAW or platform.windows.CS_VREDRAW).toUInt()
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

    // Native interactive child bounds mirror the approved scene coordinates.
    @Suppress("MagicNumber")
    internal fun layout(window: HWND) = memScoped {
        controlLayout.begin()
        val rect = alloc<RECT>()
        GetClientRect(window, rect.ptr)
        val width = unscale(rect.right)
        val height = unscale(rect.bottom)
        val sidebar = sidebarWidth(width)
        val cx = sidebar + (width - sidebar) / 2
        val cy = height / 2
        move(cancelButton, cx - 40, cy + 92, 80, 32)
        move(retryButton, cx - 48, cy + 92, 96, 32)
        val tipsY = minOf(cy + 260, height - 120) + 25
        move(previousTip, cx - 92, tipsY, 32, 32)
        move(nextTip, cx + 60, tipsY, 32, 32)
        move(
            captionText,
            CAPTION_PADDING,
            (CAPTION_HEIGHT - CAPTION_FONT_SIZE) / 2,
            CAPTION_TITLE_WIDTH,
            CAPTION_FONT_SIZE + 4,
        )
        move(closeButton, width - CAPTION_BUTTON_WIDTH, 0, CAPTION_BUTTON_WIDTH, CAPTION_HEIGHT)
        move(maximizeButton, width - CAPTION_BUTTON_WIDTH * 2, 0, CAPTION_BUTTON_WIDTH, CAPTION_HEIGHT)
        move(
            minimizeButton,
            width - CAPTION_BUTTON_WIDTH * CAPTION_BUTTON_COUNT,
            0,
            CAPTION_BUTTON_WIDTH,
            CAPTION_HEIGHT,
        )
        controlLayout.commit(window)
    }

    private fun move(control: HWND?, x: Int, y: Int, width: Int, height: Int) {
        controlLayout.move(control, scale(x), scale(y), scale(width), scale(height))
    }

    internal fun scale(value: Int): Int = value * systemDpi / DEFAULT_DPI

    private fun unscale(value: Int): Int = value * DEFAULT_DPI / systemDpi

    private fun messageLoop() {
        memScoped {
            val message = alloc<MSG>()
            while (GetMessageW(message.ptr, null, 0u, 0u) > 0) {
                if (platform.windows.IsDialogMessageW(hwnd, message.ptr) == 0) {
                    TranslateMessage(message.ptr)
                    DispatchMessageW(message.ptr)
                }
            }
        }
    }

    private fun startProvisioning() {
        sceneStatus = LauncherSceneStatus()
        handoffStarted = false
        provisioningJob = startLauncherProvisioning(
            config,
            store,
            ::updateProgress,
            ::showError,
            { isCancelled },
        ) { image ->
            launcherLog.i("Launcher") { "pendingImage: provisioning completed" }
            pendingImage.value = image
            PostMessageW(hwnd, IMAGE_READY_MESSAGE.toUInt(), 0uL, 0L)
        }
    }

    private fun updateProgress(progress: ProvisionProgress) {
        val now = GetTickCount64().toLong()
        if (now - lastProgressMs < PROGRESS_INTERVAL_MS && progress.stage == ProvisionStage.DOWNLOAD) return
        lastProgressMs = now
        sceneStatus = LauncherSceneStatus(
            progress = progress,
            isOpening = progress.stage == ProvisionStage.EXTRACT || progress.stage == ProvisionStage.ACTIVATE,
        )
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
        sceneStatus = sceneStatus.copy(isOpening = false, error = text)
    }

    private fun onImageReady() {
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
        sceneStatus = sceneStatus.copy(isOpening = true, error = null)
        handoffAttempt++
        handoffDirectory = store.root.resolve("handoff")
            .resolve("${platform.windows.GetCurrentProcessId()}-${GetTickCount64()}-$handoffAttempt")
        composeWindow = null
        handoffPresentation = null
        snapshotBlockedAtMillis = 0L
        val handoffDir = handoffDirectory
        val fs = okio.FileSystem.SYSTEM
        fs.createDirectories(handoffDir)
        listOf("ready", "show", "presented", "hidden", "failed", "frame-error").forEach { name ->
            if (fs.exists(handoffDir.resolve(name))) fs.delete(handoffDir.resolve(name))
        }
        handoffShowRequested = false
        handoffPresentation = checkNotNull(writeHandoffPresentation(hwnd, handoffDir, theme, isMoving = isMoving)) {
            "Cannot publish the initial launcher presentation"
        }
        configureLauncherEnvironment(handoffDir)
        val executable = image.directory.resolve(
            BootstrapLayout.forPlatform("windows", config.channel).executable,
        ).toString()
        embeddedExecutable = executable
        previousJvmPath = launcherEnvironment("PATH")
        embeddedMode = startEmbeddedJvm(
            checkNotNull(hwnd),
            image.directory,
            executable,
            config.target,
            args,
            systemDpi.toDouble() / DEFAULT_DPI,
        )
        val process = if (embeddedMode) {
            io.aequicor.heartbeat.platform.nativelauncher.windowsjvm.hb_jvm_thread()
        } else {
            launchProcess(executable, args)
        }
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
            handoffTick()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            launcherLog.w("Launcher", error) { "Native window handoff failed" }
            failHandoff(handoffDirectory, "Не удалось передать окно приложения")
        }
    }

    private fun handoffTick() {
        // DWM moves the existing surface. Repainting a supersampled scene and publishing geometry
        // every 16 ms during the system move loop competes with pointer tracking on the UI thread.
        if (isMoving) {
            if (!isMovePublished && handoffPresentation != null) isMovePublished = publishSnapshot() != null
            return
        }
        onImageReady()
        updateButtonHover()
        if (!embeddedMode || !isEmbeddedContentPresented()) animateScene()
        if (embeddedMode && !isCancelled && embeddedJvmCanFallback()) {
            childProcess = null
            embeddedMode = false
            childProcess = fallbackEmbeddedJvm(embeddedExecutable, args, previousJvmPath)
        }
        if (embeddedMode && advanceEmbeddedHost(hwnd)) return
        val process = childProcess
        if (process != null && isChildAlive(process)) publishSnapshot()?.let(::advanceHandoff)
    }

    private fun animateScene() {
        val clock = motion ?: return
        val now = GetTickCount64().toLong()
        val status = sceneStatus
        val focused = platform.windows.GetFocus()
        val isTipPaused = isPointerOverTips() || tipDirection(focused) != null
        clock.tick(now, status, isTipPaused)
        val frame = clock.frame(now, status)
        val showDownload = status.isDownloadCancellable && frame.download > .5
        if (showDownload != isDownloadButtonVisible) {
            ShowWindow(cancelButton, if (showDownload) platform.windows.SW_SHOWNA else SW_HIDE)
            isDownloadButtonVisible = showDownload
        }
        val showTips = status.error == null && !status.isOpening &&
            status.progress.stage == ProvisionStage.DOWNLOAD && frame.download > .5
        if (showTips != areTipButtonsVisible) {
            listOf(previousTip, nextTip).forEach {
                ShowWindow(it, if (showTips) platform.windows.SW_SHOWNA else SW_HIDE)
            }
            areTipButtonsVisible = showTips
        }
        updateRetryVisibility(status.error != null)
        if (platform.windows.IsIconic(hwnd) == 0) InvalidateRect(hwnd, null, 0)
    }

    private fun updateRetryVisibility(showRetry: Boolean) {
        if (showRetry != isRetryVisible) {
            ShowWindow(retryButton, if (showRetry) platform.windows.SW_SHOWNA else SW_HIDE)
            isRetryVisible = showRetry
        }
    }

    // Hover bounds include the entire approved tip row, not just the two navigation arrows.
    @Suppress("MagicNumber")
    private fun isPointerOverTips(): Boolean = areTipButtonsVisible &&
        isPointerOverLauncherTips(hwnd, systemDpi.toDouble() / DEFAULT_DPI)

    internal fun tipDirection(button: HWND?): Int? = when {
        button == null -> null
        button == previousTip -> -1
        button == nextTip -> 1
        else -> null
    }

    private fun updateTheme() {
        val next = LauncherTheme.current()
        if (next == theme) return
        theme = next
        renderer?.updateTheme(theme)
        val previousBackground = backgroundBrush
        sidebarBrush?.let { DeleteObject(it) }
        backgroundBrush = CreateSolidBrush(theme.background)
        // The registered class must not retain the deleted brush when Windows clears resize pixels.
        platform.windows.SetClassLongPtrW(
            hwnd,
            platform.windows.GCLP_HBRBACKGROUND,
            backgroundBrush?.rawValue?.toLong() ?: 0L,
        )
        previousBackground?.let { DeleteObject(it) }
        sidebarBrush = CreateSolidBrush(theme.sidebar)
        applyLauncherFrameTheme(hwnd, theme)
        isSnapshotDirty = true
        InvalidateRect(hwnd, null, 0)
        listOf(
            captionText,
            cancelButton,
            retryButton,
            previousTip,
            nextTip,
            minimizeButton,
            maximizeButton,
            closeButton,
        ).forEach { InvalidateRect(it, null, 0) }
    }

    private fun isChildAlive(process: HANDLE): Boolean {
        if (WaitForSingleObject(process, 0u) == WAIT_OBJECT_0) {
            if (processExitCode(process) == 0) {
                DestroyWindow(hwnd)
            } else {
                childProcess = null
                handoffStarted = false
                CloseHandle(process)
                val error = ProvisionException.Network("Приложение завершилось до появления окна")
                launcherLog.w("Launcher", error) { "Application exited before showing a window" }
                showError(error)
            }
            return false
        }
        return true
    }

    private fun publishSnapshot(): LauncherPresentation? {
        val handoffDir = handoffDirectory
        val snapshot = writeHandoffPresentation(hwnd, handoffDir, theme, handoffPresentation, isMoving, isSnapshotDirty)
        if (snapshot == null) {
            if (snapshotBlockedAtMillis == 0L) snapshotBlockedAtMillis = GetTickCount64().toLong()
            if (GetTickCount64().toLong() - snapshotBlockedAtMillis > HANDOFF_TIMEOUT_MS) {
                failHandoff(handoffDir, "Невозможно обновить положение окна")
            }
            return null
        }
        snapshotBlockedAtMillis = 0L
        isSnapshotDirty = false
        handoffPresentation = snapshot
        return snapshot
    }

    private fun advanceHandoff(snapshot: LauncherPresentation) {
        val handoffDir = handoffDirectory
        val fs = okio.FileSystem.SYSTEM
        if (platform.windows.IsIconic(hwnd) != 0) handoffShowAtMillis = GetTickCount64().toLong()
        if (!handoffShowRequested && fs.exists(handoffDir.resolve("ready"))) {
            requestHandoff(handoffDir)
        }
        val presented = handoffDir.resolve("presented.${snapshot.revision}")
        if (!isMoving && fs.exists(presented) && fs.read(presented) { readUtf8() } == snapshot.record) {
            val shouldActivate = platform.windows.GetForegroundWindow() == hwnd
            if (shouldActivate) {
                val readyWindow = checkNotNull(composeWindow)
                check(platform.windows.SetForegroundWindow(readyWindow) != 0) {
                    "Cannot transfer foreground ownership to the ready Studio window"
                }
            }
            KillTimer(hwnd, HANDOFF_TIMER_ID.toULong())
            suppressHandoffCloseAnimation(hwnd)
            val hidden = handoffDir.resolve("hidden.tmp")
            fs.write(hidden) { writeUtf8(if (shouldActivate) "activate" else "background") }
            fs.atomicMove(hidden, handoffDir.resolve("hidden"))
            DestroyWindow(hwnd)
        } else if (fs.exists(handoffDir.resolve("frame-error"))) {
            failHandoff(handoffDir, "Compose could not present its window")
        } else if (handoffShowRequested && GetTickCount64().toLong() - handoffShowAtMillis > HANDOFF_TIMEOUT_MS) {
            failHandoff(handoffDir, "Compose did not confirm a ready window before the handoff deadline")
        }
    }

    private fun updateButtonHover() = memScoped {
        val point = alloc<POINT>()
        platform.windows.GetCursorPos(point.ptr)
        val hover = listOfNotNull(
            cancelButton,
            retryButton,
            previousTip,
            nextTip,
            minimizeButton,
            maximizeButton,
            closeButton,
        ).firstOrNull {
            val rect = alloc<RECT>()
            GetWindowRect(it, rect.ptr)
            platform.windows.IsWindowVisible(it) != 0 && point.x >= rect.left && point.x < rect.right &&
                point.y >= rect.top && point.y < rect.bottom
        }
        if (hover != hoveredButton) {
            hoveredButton?.let { InvalidateRect(it, null, 0) }
            hoveredButton = hover
            hover?.let { InvalidateRect(it, null, 0) }
        }
    }

    private fun failHandoff(directory: okio.Path, reason: String) {
        val error = IllegalStateException(reason)
        launcherLog.w("Launcher", error) { "Handoff failed; releasing the startup cover" }
        try {
            okio.FileSystem.SYSTEM.write(directory.resolve("failed")) { writeUtf8(reason) }
        } catch (writeError: okio.IOException) {
            launcherLog.w("Launcher", writeError) { "Cannot write the handoff failure marker" }
        } finally {
            KillTimer(hwnd, HANDOFF_TIMER_ID.toULong())
            DestroyWindow(hwnd)
        }
    }

    private fun requestHandoff(directory: okio.Path) {
        val fs = okio.FileSystem.SYSTEM
        if (fs.read(directory.resolve("ready")) { readUtf8() }.trim() != "v2") {
            // Compatibility with a previously installed train. New trains always use the covered v2 handoff.
            KillTimer(hwnd, HANDOFF_TIMER_ID.toULong())
            writeLegacyHandoff(hwnd, directory)
            suppressHandoffCloseAnimation(hwnd)
            DestroyWindow(hwnd)
            fs.write(directory.resolve("hidden")) { writeUtf8("hidden") }
            return
        }
        composeWindow = fs.read(directory.resolve("compose-window")) { readUtf8() }.trim().toLong().toCPointer<HWND__>()
        handoffShowRequested = true
        handoffShowAtMillis = GetTickCount64().toLong()
        fs.write(directory.resolve("show")) { writeUtf8("v2") }
    }

    internal fun beginMove() {
        isMoving = true
        isMovePublished = false
        // Even a drag shorter than a timer tick must invalidate the old painted acknowledgement.
        isSnapshotDirty = true
        composeWindow?.let { window ->
            if (platform.windows.IsWindow(window) != 0) {
                platform.windows.SetLayeredWindowAttributes(window, 0u, 0u, platform.windows.LWA_ALPHA.toUInt())
            }
        }
        // Use the guarded timer entry point: filesystem failures must not escape a Win32 callback.
        onHandoffTick()
    }

    internal fun cancel() {
        if (embeddedMode && io.aequicor.heartbeat.platform.nativelauncher.windowsjvm.hb_jvm_is_presented() != 0) {
            io.aequicor.heartbeat.platform.nativelauncher.windowsjvm.hb_jvm_request_close()
            return
        }
        if (isCancelled) return
        isCancelled = true
        if (embeddedMode) io.aequicor.heartbeat.platform.nativelauncher.windowsjvm.hb_jvm_request_cancel()
        hwnd?.let { ShowWindow(it, SW_HIDE) }
        provisioningJob?.cancel()
        try {
            handoffDirectory.let { directory ->
                okio.FileSystem.SYSTEM.write(directory.resolve("cancel")) { writeUtf8("cancel") }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            launcherLog.w("Launcher", error) { "Cannot request cooperative application cancellation" }
        } finally {
            if (!embeddedMode) DestroyWindow(hwnd)
        }
    }

    private fun toggleMaximize() {
        val window = hwnd ?: return
        ShowWindow(window, if (IsZoomed(window) != 0) SW_RESTORE else SW_MAXIMIZE)
    }

    fun onCommand(control: HWND?) {
        when (control) {
            cancelButton -> cancel()
            retryButton -> startProvisioning()
            previousTip -> motion?.changeTip(-1, GetTickCount64().toLong())
            nextTip -> motion?.changeTip(1, GetTickCount64().toLong())
            minimizeButton -> hwnd?.let { ShowWindow(it, SW_MINIMIZE) }
            maximizeButton -> toggleMaximize()
            closeButton -> hwnd?.let { PostMessageW(it, WM_CLOSE.toUInt(), 0uL, 0L) }
        }
    }

    private fun onCtlColorStatic(dc: HDC?, control: HWND?): HBRUSH? {
        SetTextColor(dc, theme.textPrimary)
        SetBkMode(dc, TRANSPARENT)
        return if (control == captionText) sidebarBrush else backgroundBrush
    }

    internal fun onVisualMessage(message: UINT, wParam: WPARAM, lParam: LPARAM): LRESULT? = when (message.toInt()) {
        IMAGE_READY_MESSAGE -> {
            onHandoffTick()
            0L
        }

        WM_PAINT -> {
            renderer?.paint()
            0L
        }

        platform.windows.WM_SETTINGCHANGE, platform.windows.WM_THEMECHANGED -> {
            updateTheme()
            0L
        }

        WM_CTLCOLORSTATIC, platform.windows.WM_CTLCOLORBTN ->
            onCtlColorStatic(wParam.toLong().toCPointer<HDC__>(), lParam.toCPointer<HWND__>())
                ?.rawValue?.toLong()

        WM_DRAWITEM -> {
            drawButton(this, lParam)
            1L
        }

        platform.windows.WM_ERASEBKGND -> {
            // Never expose a background-only frame. WM_PAINT reuses this completed scene.
            renderer?.erase(wParam.toLong().toCPointer<HDC__>())
            1L
        }

        else -> null
    }

    companion object {
        private const val DEFAULT_DPI = 96
        private const val SCREEN_PERCENT = 90
        private const val PERCENT = 100
        private const val PRIMARY_LANGUAGE_MASK = 0x3ff
        private const val RUSSIAN_LANGUAGE = 0x19
        internal const val IMAGE_READY_MESSAGE = platform.windows.WM_APP + 1
        private const val WINDOW_CLASS = "HeartbeatLauncherWindow"
        private const val WINDOW_WIDTH = 1280
        private const val WINDOW_HEIGHT = 900
        private const val SIDEBAR_WIDTH = 264
        private const val COMPACT_BREAKPOINT = 720
        private const val HANDOFF_TIMEOUT_MS = 30_000L

        // Кастомный caption (полоса вместо системного title bar).
        internal const val CAPTION_HEIGHT = 44
        private const val CAPTION_BUTTON_WIDTH = 48
        private const val CAPTION_BUTTON_COUNT = 3
        private const val CAPTION_PADDING = 12
        private const val CAPTION_TITLE_WIDTH = 160
        internal const val CAPTION_GLYPH_HALF = 5
        private const val HANDOFF_TIMER_ID = 42
        private const val HANDOFF_INTERVAL_MS = 16
        private const val PROGRESS_INTERVAL_MS = 100L
        private const val BODY_FONT_SIZE = 13
        private const val CAPTION_FONT_SIZE = 13
        private const val ICON_SIZE = 48
        internal const val BUTTON_CORNER_RADIUS = 12
    }

    private fun sidebarWidth(width: Int): Int = if (width >= COMPACT_BREAKPOINT) SIDEBAR_WIDTH else 0
}

private fun launcherWndProc(hwnd: HWND?, message: UINT, wParam: WPARAM, lParam: LPARAM): LRESULT {
    val handled = LauncherWindowRegistry.instance?.let { window ->
        when (message.toInt()) {
            WM_SIZE -> {
                window.layout(hwnd!!)
                0L
            }

            WM_COMMAND -> {
                window.onCommand(lParam.toCPointer<HWND__>())
                null
            }

            WM_NCCALCSIZE -> if (wParam != 0uL) platform.windows.WVR_REDRAW.toLong() else 0L

            WM_NCHITTEST -> window.onNcHitTest(lParam)

            WM_GETMINMAXINFO -> {
                window.onGetMinMaxInfo(lParam)
                null
            }

            WM_TIMER -> {
                window.onHandoffTick()
                null
            }

            WM_CLOSE -> {
                window.cancel()
                0L
            }

            else -> handleEmbeddedParentMessage(message, wParam, lParam)
                ?: window.onVisualMessage(message, wParam, lParam) ?: launcherAuxiliaryMessage(window, message)
        }
    }
    return handled ?: DefWindowProcW(hwnd, message, wParam, lParam)
}

private fun launcherAuxiliaryMessage(window: LauncherWindow, message: UINT): LRESULT? = when (message.toInt()) {
    WM_DESTROY -> {
        PostQuitMessage(0)
        null
    }

    platform.windows.WM_ENTERSIZEMOVE -> {
        window.beginMove()
        0L
    }

    platform.windows.WM_EXITSIZEMOVE -> {
        window.isMoving = false
        0L
    }

    else -> null
}

private fun drawButton(window: LauncherWindow, lParam: LPARAM) {
    val item = lParam.toCPointer<DRAWITEMSTRUCT>()?.pointed ?: return
    window.controlFrames.draw(window, item)
}

/** Единственное окно процесса лончера и его прогресс; WndProc — статические функции C. */
object LauncherWindowRegistry {
    var instance: LauncherWindow? = null
}
