#define _WIN32_WINNT 0x0A00
#include <windows.h>
#include <shellapi.h>
#include <dwmapi.h>
#include <commctrl.h>
#include <windowsx.h>
#include <jni.h>
#include <jawt.h>
#include <atomic>
#include <fstream>
#include <string>
#include <vector>
#include <cstdio>
#include <cwchar>

static HWND host;
static HWND cover;
static HWND caption, embeddedChild;
static HWND captionButtons[3]{};
static bool darkTheme = true;
static HICON smallIcon, largeIcon;
static HFONT captionFont;
static double scale = 1.0;
static HANDLE changed;
static HANDLE worker;
static std::atomic<int> clientWidth{1280}, clientHeight{800};
static std::atomic<bool> stopping{false}, active{false};
static std::atomic<bool> captionClient{true};
static bool trayInstalled = false;
static UINT taskbarCreated;
static constexpr UINT TRAY_MESSAGE = WM_APP + 20, QUIT_APP = WM_APP + 21, CAPTION_HIT = WM_APP + 14;
static const wchar_t *ORIGINAL_PROC = L"Heartbeat.Embedded.OriginalProc";
static int clippedWidth = -1, clippedHeight = -1;
static HWND clippedChild;
static bool clippedZoom = false;
static bool clippedVisible = false;
static bool ready = false;
static bool composeReady = false, introPreview = false;
static bool presenting = false;
static bool allowContent = false, mounted = false;
static std::atomic<bool> presentRequested{false};
static std::atomic<bool> revealRequested{false};
static ULONGLONG introStarted = 0;
static std::wstring runtimePath, optionsPath;
static void *renderer = nullptr;
static void *(*createRenderer)(double) = nullptr;
static void (*paintRenderer)(void *, HDC, int, int) = nullptr;
static void (*disposeRenderer)(void *) = nullptr;
static constexpr UINT READY = WM_APP + 7, STOPPED = WM_APP + 8, FAILED = WM_APP + 9, RAISE_COVER = WM_APP + 10;
static constexpr UINT REGISTER_CHILD = WM_APP + 12;
static constexpr UINT BEGIN_DRAG = WM_APP + 13;
static constexpr UINT REVEAL = WM_APP + 15;
static constexpr UINT MOUNTED = WM_APP + 16;
static ULONGLONG lastCaptionPress = 0;
static POINT lastCaptionPoint{};

static int scaled(int value) { return static_cast<int>(value * scale + .5); }
static COLORREF background() { return darkTheme ? RGB(32,32,34) : RGB(255,255,255); }
static bool installTray() {
    NOTIFYICONDATAW data{}; data.cbSize = sizeof(data); data.hWnd = host; data.uID = 1;
    data.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP; data.uCallbackMessage = TRAY_MESSAGE; data.hIcon = largeIcon;
    lstrcpynW(data.szTip, L"Heartbeat", ARRAYSIZE(data.szTip));
    trayInstalled = Shell_NotifyIconW(NIM_ADD, &data) != FALSE;
    if (trayInstalled) { data.uVersion = NOTIFYICON_VERSION_4; Shell_NotifyIconW(NIM_SETVERSION, &data); }
    return trayInstalled;
}
static void showFromTray() {
    ShowWindow(host, IsIconic(host) ? SW_RESTORE : SW_SHOW);
    SetForegroundWindow(host);
}

// Intercept the actual heavyweight AWT canvas press before AWT turns it into an asynchronous Compose event.
static LRESULT CALLBACK embeddedProcedure(HWND window, UINT message, WPARAM w, LPARAM l) {
    auto original = reinterpret_cast<WNDPROC>(GetPropW(window, ORIGINAL_PROC));
    if ((message == WM_LBUTTONDOWN || message == WM_LBUTTONDBLCLK) && !captionClient.load()) {
        POINT point{GET_X_LPARAM(l), GET_Y_LPARAM(l)}; ClientToScreen(window, &point);
        POINT parent = point; ScreenToClient(host, &parent);
        if (parent.y >= 0 && parent.y < scaled(44)) {
            ReleaseCapture();
            PostMessageW(host, BEGIN_DRAG, 0, MAKELPARAM(point.x, point.y));
            return 0;
        }
    }
    if (message == WM_NCDESTROY) RemovePropW(window, ORIGINAL_PROC);
    return original ? CallWindowProcW(original, window, message, w, l) : DefWindowProcW(window, message, w, l);
}
static BOOL CALLBACK hookEmbeddedChild(HWND child, LPARAM) {
    if (child == cover || child == caption || (caption && IsChild(caption, child))) return TRUE;
    if (!GetPropW(child, ORIGINAL_PROC)) {
        auto original = reinterpret_cast<WNDPROC>(GetWindowLongPtrW(child, GWLP_WNDPROC));
        if (SetPropW(child, ORIGINAL_PROC, reinterpret_cast<HANDLE>(original)))
            SetWindowLongPtrW(child, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(embeddedProcedure));
    }
    return TRUE;
}
static void arrangeChrome() {
    int width = clientWidth.load(), height = clientHeight.load();
    int edge = IsZoomed(host) ? 0 : scaled(6);
    bool zoomed = IsZoomed(host) != FALSE;
    bool visible = ready || presenting || allowContent;
    if (embeddedChild && (clippedChild != embeddedChild || width != clippedWidth || height != clippedHeight || clippedZoom != zoomed || clippedVisible != visible)) {
        HRGN region = visible ? CreateRectRgn(edge, edge, width - edge, height - edge) : CreateRectRgn(0, 0, 0, 0);
        HRGN controls = CreateRectRgn(width - scaled(144), 0, width, scaled(44));
        CombineRgn(region, region, controls, RGN_DIFF); DeleteObject(controls);
        if (!SetWindowRgn(embeddedChild, region, FALSE)) DeleteObject(region);
        clippedChild = embeddedChild; clippedWidth = width; clippedHeight = height; clippedZoom = zoomed;
        clippedVisible = visible;
    }
    if (caption) {
        SetWindowPos(caption, HWND_TOP, width - scaled(144), 0, scaled(144), scaled(44), SWP_NOACTIVATE);
        for (int i = 0; i < 3; ++i) MoveWindow(captionButtons[i], scaled(i * 48), 0, scaled(48), scaled(44), TRUE);
        SetWindowTextW(captionButtons[1], IsZoomed(host) ? L"Восстановить" : L"Развернуть");
        InvalidateRect(caption, nullptr, FALSE);
    }
}

static LRESULT CALLBACK buttonProcedure(HWND window, UINT message, WPARAM w, LPARAM l, UINT_PTR, DWORD_PTR) {
    if (message == WM_MOUSEMOVE) {
        TRACKMOUSEEVENT track{sizeof(track), TME_LEAVE, window, 0}; TrackMouseEvent(&track);
        InvalidateRect(window, nullptr, FALSE);
    } else if (message == WM_MOUSELEAVE) InvalidateRect(window, nullptr, FALSE);
    return DefSubclassProc(window, message, w, l);
}

static LRESULT CALLBACK captionProcedure(HWND window, UINT message, WPARAM w, LPARAM l) {
    if (message == WM_ERASEBKGND) return 1;
    if (message == WM_COMMAND && HIWORD(w) == BN_CLICKED) {
        int id = LOWORD(w);
        if (id == 3) PostMessageW(host, WM_CLOSE, 0, 0);
        else PostMessageW(host, WM_SYSCOMMAND, id == 1 ? SC_MINIMIZE : (IsZoomed(host) ? SC_RESTORE : SC_MAXIMIZE), 0);
        return 0;
    }
    if (message == WM_DRAWITEM) {
        auto item = reinterpret_cast<DRAWITEMSTRUCT *>(l);
        POINT point; GetCursorPos(&point); ScreenToClient(item->hwndItem, &point);
        bool hover = PtInRect(&item->rcItem, point);
        COLORREF fill = background();
        if (hover) fill = item->CtlID == 3 ? RGB(196,43,28) : (darkTheme ? RGB(48,48,51) : RGB(235,235,237));
        HBRUSH brush = CreateSolidBrush(fill); FillRect(item->hDC, &item->rcItem, brush); DeleteObject(brush);
        SetBkMode(item->hDC, TRANSPARENT);
        SetTextColor(item->hDC, hover && item->CtlID == 3 ? RGB(255,255,255) : (darkTheme ? RGB(238,238,240) : RGB(36,36,38)));
        auto previous = SelectObject(item->hDC, captionFont);
        const wchar_t *glyph = item->CtlID == 1 ? L"\xE921" : item->CtlID == 3 ? L"\xE8BB" : IsZoomed(host) ? L"\xE923" : L"\xE922";
        DrawTextW(item->hDC, glyph, 1, &item->rcItem, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
        SelectObject(item->hDC, previous); return TRUE;
    }
    if (message == WM_PAINT) {
        PAINTSTRUCT paint; HDC dc = BeginPaint(window, &paint); RECT bounds; GetClientRect(window, &bounds);
        HBRUSH brush = CreateSolidBrush(background()); FillRect(dc, &bounds, brush); DeleteObject(brush);
        EndPaint(window, &paint); return 0;
    }
    return DefWindowProcW(window, message, w, l);
}

static LRESULT CALLBACK coverProcedure(HWND window, UINT message, WPARAM w, LPARAM l) {
    if (message == WM_NCHITTEST) {
        POINT point{GET_X_LPARAM(l), GET_Y_LPARAM(l)}; ScreenToClient(window, &point);
        RECT rect; GetClientRect(window, &rect); int edge = scaled(6);
        if (point.y < scaled(44) || point.x < edge || point.x >= rect.right - edge || point.y >= rect.bottom - edge)
            return HTTRANSPARENT;
    }
    if (message == WM_ERASEBKGND) return 1;
    if (message == WM_PAINT) {
        PAINTSTRUCT paint; HDC dc = BeginPaint(window, &paint);
        RECT bounds; GetClientRect(window, &bounds);
        if (renderer && paintRenderer) {
            paintRenderer(renderer, dc, bounds.right, bounds.bottom);
            EndPaint(window, &paint); return 0;
        }
        HBRUSH brush = CreateSolidBrush(RGB(32, 32, 32)); FillRect(dc, &bounds, brush); DeleteObject(brush);
        SetBkMode(dc, TRANSPARENT); SetTextColor(dc, RGB(205, 205, 205));
        DrawTextW(dc, L"Preparing the real AI Studio inside this native window...", -1, &bounds, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
        EndPaint(window, &paint); return 0;
    }
    return DefWindowProcW(window, message, w, l);
}

static bool exception(JNIEnv *env, const char *stage) {
    if (!env->ExceptionCheck()) return false;
    std::fprintf(stderr, "JNI exception: %s\n", stage);
    env->ExceptionDescribe(); env->ExceptionClear(); std::fflush(stderr);
    return true;
}

// Own every JNI resource on the invocation thread, including all partial-start failure paths.
struct JvmCleanup {
    JavaVM *vm;
    JNIEnv *env;
    jobject frame = nullptr;
    jclass probe = nullptr;
    bool mounted = false;
    ~JvmCleanup() {
        if (mounted && probe) {
            jmethodID unmount = env->GetStaticMethodID(probe, "unmount", "(Ljava/awt/Frame;)V");
            if (unmount) env->CallStaticVoidMethod(probe, unmount, frame);
            exception(env, "unmount");
        }
        if (frame) {
            jclass frameClass = env->GetObjectClass(frame);
            jmethodID dispose = frameClass ? env->GetMethodID(frameClass, "dispose", "()V") : nullptr;
            if (dispose) env->CallVoidMethod(frame, dispose);
            exception(env, "dispose embedded Frame");
            env->DeleteGlobalRef(frame);
        }
        std::fprintf(stderr, "DestroyJavaVM beginning\n"); std::fflush(stderr);
        jint result = vm->DestroyJavaVM();
        std::fprintf(stderr, "DestroyJavaVM=%d\n", result); std::fflush(stderr);
        if (stopping.load()) PostMessageW(host, STOPPED, 0, 0);
    }
};

static DWORD WINAPI startJvm(void *) {
    SetDllDirectoryW((runtimePath + L"\\bin").c_str());
    HMODULE jvmLibrary = LoadLibraryW((runtimePath + L"\\bin\\server\\jvm.dll").c_str());
    if (!jvmLibrary) { std::fprintf(stderr, "Load JVM failed %lu\n", GetLastError()); PostMessageW(host, FAILED, 0, 0); return 1; }
    auto create = reinterpret_cast<jint (JNICALL *)(JavaVM **, void **, void *)>(GetProcAddress(jvmLibrary, "JNI_CreateJavaVM"));
    std::ifstream input(optionsPath.c_str());
    std::vector<std::string> strings;
    for (std::string line; std::getline(input, line);) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (!line.empty()) strings.push_back(line);
    }
    std::vector<JavaVMOption> options;
    for (auto &line : strings) options.push_back({line.data(), nullptr});
    JavaVMInitArgs args{};
    args.version = JNI_VERSION_1_8; args.nOptions = (jint)options.size(); args.options = options.data();
    JavaVM *vm = nullptr; JNIEnv *env = nullptr;
    jint result = create ? create(&vm, reinterpret_cast<void **>(&env), &args) : JNI_ERR;
    std::fprintf(stderr, "JNI_CreateJavaVM=%d host=%p pid=%lu\n", result, host, GetCurrentProcessId()); std::fflush(stderr);
    if (result != JNI_OK) { PostMessageW(host, FAILED, 0, 0); return 2; }
    JvmCleanup cleanup{vm, env};
    jclass toolkit = env->FindClass("java/awt/Toolkit");
    if (exception(env, "Toolkit class") || !toolkit) { PostMessageW(host, FAILED, 0, 0); return 3; }
    env->CallStaticObjectMethod(toolkit, env->GetStaticMethodID(toolkit, "getDefaultToolkit", "()Ljava/awt/Toolkit;"));
    if (exception(env, "Toolkit initialization")) { PostMessageW(host, FAILED, 0, 0); return 4; }
    HMODULE jawtLibrary = LoadLibraryW((runtimePath + L"\\bin\\jawt.dll").c_str());
    auto getAwt = jawtLibrary ? reinterpret_cast<jboolean (JNICALL *)(JNIEnv *, JAWT *)>(GetProcAddress(jawtLibrary, "JAWT_GetAWT")) : nullptr;
    JAWT awt{}; awt.version = JAWT_VERSION_9;
    if (!getAwt || !getAwt(env, &awt) || !awt.CreateEmbeddedFrame) {
        std::fprintf(stderr, "JAWT API 9 unavailable, error=%lu\n", GetLastError()); PostMessageW(host, FAILED, 0, 0); return 5;
    }
    jobject localFrame = awt.CreateEmbeddedFrame(env, host);
    if (exception(env, "CreateEmbeddedFrame") || !localFrame) { PostMessageW(host, FAILED, 0, 0); return 6; }
    jobject frame = env->NewGlobalRef(localFrame);
    cleanup.frame = frame;
    SendMessageW(host, RAISE_COVER, 0, 0);
    jclass probe = env->FindClass("io/aequicor/heartbeat/platform/desktop/EmbeddedHostProbe");
    if (exception(env, "ComposePanel probe class") || !probe) { PostMessageW(host, FAILED, 0, 0); return 7; }
    cleanup.probe = probe;
    jmethodID mount = env->GetStaticMethodID(probe, "mount", "(Ljava/awt/Frame;J)V");
    if (exception(env, "mount method") || !mount) { PostMessageW(host, FAILED, 0, 0); return 8; }
    awt.SetBounds(env, frame, 0, 0, clientWidth.load(), clientHeight.load());
    env->CallStaticVoidMethod(probe, mount, frame, (jlong)(intptr_t)host);
    if (exception(env, "mount ComposePanel")) { PostMessageW(host, FAILED, 0, 0); return 9; }
    cleanup.mounted = true;
    SendMessageW(host, MOUNTED, 0, 0);
    SendMessageW(host, RAISE_COVER, 0, 0);
    int width = -1, height = -1; bool wasActive = !active.load();
    jmethodID present = env->GetStaticMethodID(probe, "present", "(Ljava/awt/Frame;)V");
    if (exception(env, "present method") || !present) { PostMessageW(host, FAILED, 0, 0); return 10; }
    while (!stopping.load()) {
        int w = clientWidth.load(), h = clientHeight.load();
        if (w > 0 && h > 0 && (w != width || h != height)) {
            awt.SetBounds(env, frame, 0, 0, w, h); width = w; height = h;
        }
        bool isActive = active.load();
        if (isActive != wasActive) {
            awt.SynthesizeWindowActivation(env, frame, isActive ? JNI_TRUE : JNI_FALSE); wasActive = isActive;
        }
        if (exception(env, "embedded presentation")) break;
        if (revealRequested.exchange(false)) {
            jmethodID reveal = env->GetStaticMethodID(probe, "reveal", "(Ljava/awt/Frame;J)V");
            if (reveal) env->CallStaticVoidMethod(probe, reveal, frame, (jlong)GetTickCount64());
            if (exception(env, "continue Compose intro")) { PostMessageW(host, FAILED, 0, 0); break; }
        }
        if (presentRequested.exchange(false)) {
            env->CallStaticVoidMethod(probe, present, frame);
            if (exception(env, "present ready Studio")) { PostMessageW(host, FAILED, 0, 0); break; }
            PostMessageW(host, REVEAL, 0, 0);
        }
        WaitForSingleObject(changed, INFINITE);
    }
    return 0;
}

static LRESULT CALLBACK windowProcedure(HWND window, UINT message, WPARAM w, LPARAM l) {
    if (message == taskbarCreated && taskbarCreated) { trayInstalled = false; installTray(); return 0; }
    switch (message) {
        case CAPTION_HIT: captionClient.store(w != 0); return 0;
        case TRAY_MESSAGE: {
            UINT event = LOWORD(l);
            if (event == NIN_SELECT || event == NIN_KEYSELECT || event == WM_LBUTTONDBLCLK) showFromTray();
            else if (event == WM_CONTEXTMENU || event == WM_RBUTTONUP) {
                POINT point; GetCursorPos(&point); HMENU menu = CreatePopupMenu();
                AppendMenuW(menu, MF_STRING, 1, L"Открыть Heartbeat"); AppendMenuW(menu, MF_SEPARATOR, 0, nullptr);
                AppendMenuW(menu, MF_STRING, 2, L"Выйти"); SetForegroundWindow(window);
                UINT command = TrackPopupMenu(menu, TPM_RETURNCMD | TPM_RIGHTBUTTON, point.x, point.y, 0, window, nullptr);
                DestroyMenu(menu);
                if (command == 1) showFromTray();
                if (command == 2) PostMessageW(window, QUIT_APP, 0, 0);
                PostMessageW(window, WM_NULL, 0, 0);
            }
            return 0;
        }
        case BEGIN_DRAG: {
            std::fprintf(stderr, "NATIVE_CAPTION_GESTURE\n"); std::fflush(stderr);
            ULONGLONG now = GetTickCount64();
            POINT point{GET_X_LPARAM(l), GET_Y_LPARAM(l)};
            bool twice = now - lastCaptionPress <= GetDoubleClickTime() &&
                         abs(point.x - lastCaptionPoint.x) <= GetSystemMetrics(SM_CXDOUBLECLK) &&
                         abs(point.y - lastCaptionPoint.y) <= GetSystemMetrics(SM_CYDOUBLECLK);
            lastCaptionPress = now; lastCaptionPoint = point;
            if (twice) {
                lastCaptionPress = 0;
                PostMessageW(window, WM_SYSCOMMAND, IsZoomed(window) ? SC_RESTORE : SC_MAXIMIZE, 0);
                return 0;
            }
            return DefWindowProcW(window, WM_NCLBUTTONDOWN, HTCAPTION, l);
        }
        case WM_NCCALCSIZE:
            if (w && IsZoomed(window)) {
                MONITORINFO monitor{sizeof(monitor)};
                if (GetMonitorInfoW(MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST), &monitor)) {
                    reinterpret_cast<NCCALCSIZE_PARAMS *>(l)->rgrc[0] = monitor.rcWork;
                }
            }
            return 0;
        case WM_NCHITTEST: {
            POINT point{GET_X_LPARAM(l), GET_Y_LPARAM(l)}; ScreenToClient(window, &point);
            RECT rect; GetClientRect(window, &rect); int edge = IsZoomed(window) ? 0 : scaled(6);
            bool left = point.x < edge, right = point.x >= rect.right - edge;
            bool top = point.y < edge, bottom = point.y >= rect.bottom - edge;
            if (top) return left ? HTTOPLEFT : right ? HTTOPRIGHT : HTTOP;
            if (bottom) return left ? HTBOTTOMLEFT : right ? HTBOTTOMRIGHT : HTBOTTOM;
            if (left) return HTLEFT; if (right) return HTRIGHT;
            return point.y < scaled(44) ? HTCAPTION : HTCLIENT;
        }
        case WM_TIMER:
            if (cover) InvalidateRect(cover, nullptr, FALSE);
            if (mounted && !allowContent && (!introPreview || GetTickCount64() - introStarted >= 4200)) {
                allowContent = true; arrangeChrome();
                if (cover) SetWindowPos(cover, HWND_TOP, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
                if (caption) SetWindowPos(caption, HWND_TOP, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
            }
            if (composeReady && introPreview && GetTickCount64() - introStarted >= 4200) PostMessageW(window, READY, 0, 0);
            return 0;
        case WM_GETMINMAXINFO: {
            auto limits = reinterpret_cast<MINMAXINFO *>(l);
            limits->ptMinTrackSize.x = 860;
            limits->ptMinTrackSize.y = 600;
            MONITORINFO monitor{sizeof(monitor)};
            if (GetMonitorInfoW(MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST), &monitor)) {
                limits->ptMaxPosition = {monitor.rcWork.left - monitor.rcMonitor.left, monitor.rcWork.top - monitor.rcMonitor.top};
                limits->ptMaxSize = {monitor.rcWork.right - monitor.rcWork.left, monitor.rcWork.bottom - monitor.rcWork.top};
            }
            return 0;
        }
        case WM_SIZE:
            clientWidth.store(LOWORD(l)); clientHeight.store(HIWORD(l));
            if (cover) MoveWindow(cover, 0, 0, LOWORD(l), HIWORD(l), TRUE);
            arrangeChrome();
            InvalidateRect(window, nullptr, FALSE);
            if (changed) SetEvent(changed);
            return 0;
        case WM_PARENTNOTIFY:
            if (LOWORD(w) == WM_CREATE && cover && reinterpret_cast<HWND>(l) != cover && reinterpret_cast<HWND>(l) != caption && !ready) {
                HWND child = reinterpret_cast<HWND>(l);
                wchar_t name[128]{}; GetClassNameW(child, name, ARRAYSIZE(name));
                if (std::wcsncmp(name, L"Heartbeat", 9) != 0) {
                    HRGN hidden = CreateRectRgn(0, 0, 0, 0);
                    if (!SetWindowRgn(child, hidden, FALSE)) DeleteObject(hidden);
                    SetWindowLongPtrW(child, GWL_STYLE, GetWindowLongPtrW(child, GWL_STYLE) | WS_CLIPSIBLINGS);
                }
            }
            if (LOWORD(w) == WM_CREATE && cover && reinterpret_cast<HWND>(l) != cover) {
                SetWindowPos(cover, HWND_TOP, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
            }
            if (caption) SetWindowPos(caption, HWND_TOP, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
            break;
        case WM_ACTIVATE:
            active.store(LOWORD(w) != WA_INACTIVE);
            if (changed) SetEvent(changed);
            return 0;
        case WM_DPICHANGED: {
            scale = HIWORD(w) / 96.0;
            if (renderer) { disposeRenderer(renderer); renderer = createRenderer(scale); }
            if (captionFont) DeleteObject(captionFont);
            captionFont = CreateFontW(-scaled(10), 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE, DEFAULT_CHARSET,
                                     OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY, DEFAULT_PITCH, L"Segoe MDL2 Assets");
            clippedWidth = -1;
            RECT *bounds = reinterpret_cast<RECT *>(l);
            SetWindowPos(window, nullptr, bounds->left, bounds->top, bounds->right - bounds->left, bounds->bottom - bounds->top,
                         SWP_NOACTIVATE | SWP_NOZORDER);
            return 0;
        }
        case WM_PAINT: {
            PAINTSTRUCT paint; HDC dc = BeginPaint(window, &paint);
            RECT bounds; GetClientRect(window, &bounds);
            HBRUSH brush = CreateSolidBrush(background()); FillRect(dc, &bounds, brush); DeleteObject(brush);
            RECT sidebar = bounds; sidebar.right = scaled(264);
            brush = CreateSolidBrush(darkTheme ? RGB(40,40,43) : RGB(244,244,245)); FillRect(dc, &sidebar, brush); DeleteObject(brush);
            EndPaint(window, &paint); return 0;
        }
        case WM_ERASEBKGND: return 1;
        case READY:
            composeReady = true;
            if (introPreview && GetTickCount64() - introStarted < 4200) return 0;
            if (ready || presenting) return 0;
            presenting = true;
            arrangeChrome();
            if (cover) SetWindowPos(cover, HWND_TOP, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
            if (caption) SetWindowPos(caption, HWND_TOP, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
            presentRequested.store(true); SetEvent(changed);
            return 0;
        case REVEAL:
            if (ready) return 0;
            ready = true; std::fprintf(stderr, "FIRST_COMPOSE_FRAME host=%p pid=%lu\n", window, GetCurrentProcessId());
            KillTimer(window, 1);
            arrangeChrome();
            EnumChildWindows(window, hookEmbeddedChild, 0);
            if (embeddedChild) RedrawWindow(embeddedChild, nullptr, nullptr, RDW_INVALIDATE | RDW_UPDATENOW | RDW_ALLCHILDREN | RDW_NOERASE);
            DwmFlush();
            if (cover) {
                SetWindowPos(cover, HWND_BOTTOM, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_NOREDRAW);
                DwmFlush(); DestroyWindow(cover); cover = nullptr;
            }
            revealRequested.store(true); SetEvent(changed);
            std::fflush(stderr); InvalidateRect(window, nullptr, FALSE); return 0;
        case FAILED:
            SetWindowTextW(window, L"Heartbeat Embedded Host Probe - FAILED (see native log)"); return 0;
        case RAISE_COVER:
            if (cover) SetWindowPos(cover, HWND_TOP, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
            if (caption) SetWindowPos(caption, HWND_TOP, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
            return 0;
        case REGISTER_CHILD:
            embeddedChild = reinterpret_cast<HWND>(l); arrangeChrome(); EnumChildWindows(window, hookEmbeddedChild, 0); return 0;
        case MOUNTED: mounted = true; return 0;
        case WM_CLOSE:
            if (trayInstalled || installTray()) {
                ShowWindow(window, SW_HIDE);
                std::fprintf(stderr, "HIDDEN_TO_TRAY pid=%lu\n", GetCurrentProcessId()); std::fflush(stderr);
            }
            return 0;
        case QUIT_APP:
            ShowWindow(window, SW_HIDE);
            stopping.store(true); SetEvent(changed);
            if (!worker || WaitForSingleObject(worker, 0) == WAIT_OBJECT_0) DestroyWindow(window);
            return 0;
        case STOPPED: DestroyWindow(window); return 0;
        case WM_DESTROY: {
            NOTIFYICONDATAW data{}; data.cbSize = sizeof(data); data.hWnd = window; data.uID = 1;
            if (trayInstalled) Shell_NotifyIconW(NIM_DELETE, &data);
            PostQuitMessage(0); return 0;
        }
    }
    return DefWindowProcW(window, message, w, l);
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR, int) {
    int count = 0; LPWSTR *args = CommandLineToArgvW(GetCommandLineW(), &count);
    if (count != 3 && count != 4) return 64;
    runtimePath = args[1]; optionsPath = args[2];
    std::wstring rendererPath = count == 4 ? args[3] : L"";
    LocalFree(args);
    std::wstring log = optionsPath + L".native.log";
    _wfreopen(log.c_str(), L"w", stderr);
    _wfreopen((optionsPath + L".stdout.log").c_str(), L"w", stdout);
    std::ifstream themeOptions(optionsPath.c_str());
    for (std::string line; std::getline(themeOptions, line);) {
        if (line.find("-Dheartbeat.embedded.dark=false") == 0) darkTheme = false;
    }
    SetEnvironmentVariableW(L"HEARTBEAT_LAUNCHER_THEME", darkTheme ? L"dark" : L"light");
    auto setDpi = reinterpret_cast<BOOL (WINAPI *)(HANDLE)>(GetProcAddress(GetModuleHandleW(L"user32.dll"), "SetProcessDpiAwarenessContext"));
    if (setDpi) setDpi(reinterpret_cast<HANDLE>(static_cast<intptr_t>(-4))); else SetProcessDPIAware();
    wchar_t preview[4]{};
    introPreview = GetEnvironmentVariableW(L"HEARTBEAT_EMBEDDED_INTRO_PREVIEW", preview, ARRAYSIZE(preview)) > 0 && preview[0] == L'1';
    introStarted = GetTickCount64();
    SetEnvironmentVariableW(L"HEARTBEAT_EMBEDDED_STARTED", std::to_wstring(introStarted).c_str());
    if (!rendererPath.empty()) {
        HMODULE library = LoadLibraryW(rendererPath.c_str());
        createRenderer = library ? reinterpret_cast<void *(*)(double)>(GetProcAddress(library, "hb_renderer_create")) : nullptr;
        paintRenderer = library ? reinterpret_cast<void (*)(void *, HDC, int, int)>(GetProcAddress(library, "hb_renderer_paint")) : nullptr;
        disposeRenderer = library ? reinterpret_cast<void (*)(void *)>(GetProcAddress(library, "hb_renderer_dispose")) : nullptr;
        if (!createRenderer || !paintRenderer || !disposeRenderer) return 66;
        auto getDpi = reinterpret_cast<UINT (WINAPI *)()>(GetProcAddress(GetModuleHandleW(L"user32.dll"), "GetDpiForSystem"));
        scale = getDpi ? getDpi() / 96.0 : 1.0;
        renderer = createRenderer(scale);
    }
    changed = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    WNDCLASSEXW type{}; type.cbSize = sizeof(type); type.hInstance = instance;
    type.lpfnWndProc = windowProcedure; type.lpszClassName = L"HeartbeatEmbeddedHostProbe";
    smallIcon = static_cast<HICON>(LoadImageW(instance, MAKEINTRESOURCEW(1), IMAGE_ICON, 16, 16, 0));
    largeIcon = static_cast<HICON>(LoadImageW(instance, MAKEINTRESOURCEW(1), IMAGE_ICON, 32, 32, 0));
    type.hIcon = largeIcon; type.hIconSm = smallIcon;
    type.hCursor = LoadCursorW(nullptr, IDC_ARROW); RegisterClassExW(&type);
    RECT bounds{0, 0, 1280, 800};
    host = CreateWindowExW(WS_EX_APPWINDOW, type.lpszClassName, L"Heartbeat",
                          WS_OVERLAPPEDWINDOW | WS_CLIPCHILDREN,
                          500, 250, bounds.right - bounds.left, bounds.bottom - bounds.top,
                          nullptr, nullptr, instance, nullptr);
    if (!host) return 65;
    taskbarCreated = RegisterWindowMessageW(L"TaskbarCreated");
    installTray();
    SendMessageW(host, WM_SETICON, ICON_SMALL, reinterpret_cast<LPARAM>(smallIcon));
    SendMessageW(host, WM_SETICON, ICON_BIG, reinterpret_cast<LPARAM>(largeIcon));
    MARGINS margins{1,1,1,1}; DwmExtendFrameIntoClientArea(host, &margins);
    BOOL dark = darkTheme; DwmSetWindowAttribute(host, 20, &dark, sizeof(dark));
    COLORREF frameColor = background(); DwmSetWindowAttribute(host, 35, &frameColor, sizeof(frameColor));
    WNDCLASSEXW coverType{}; coverType.cbSize = sizeof(coverType); coverType.hInstance = instance;
    coverType.lpfnWndProc = coverProcedure; coverType.lpszClassName = L"HeartbeatEmbeddedCover";
    RegisterClassExW(&coverType);
    cover = CreateWindowExW(0, coverType.lpszClassName, L"", WS_CHILD | WS_VISIBLE | WS_CLIPSIBLINGS,
                            0, 0, clientWidth.load(), clientHeight.load(), host, nullptr, instance, nullptr);
    WNDCLASSEXW captionType{}; captionType.cbSize = sizeof(captionType); captionType.hInstance = instance;
    captionType.lpfnWndProc = captionProcedure; captionType.lpszClassName = L"HeartbeatEmbeddedCaption";
    captionType.hCursor = LoadCursorW(nullptr, IDC_ARROW); RegisterClassExW(&captionType);
    caption = CreateWindowExW(0, captionType.lpszClassName, L"", WS_CHILD | WS_VISIBLE | WS_CLIPCHILDREN | WS_CLIPSIBLINGS,
                              1136, 0, 144, 44, host, nullptr, instance, nullptr);
    captionFont = CreateFontW(-scaled(10), 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE, DEFAULT_CHARSET,
                              OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY, DEFAULT_PITCH, L"Segoe MDL2 Assets");
    const wchar_t *buttonNames[]{L"Свернуть", L"Развернуть", L"Закрыть"};
    for (int i = 0; i < 3; ++i) {
        captionButtons[i] = CreateWindowExW(0, L"BUTTON", buttonNames[i], WS_CHILD | WS_VISIBLE | BS_OWNERDRAW,
                                           i * 48, 0, 48, 44, caption, reinterpret_cast<HMENU>(static_cast<intptr_t>(i + 1)), instance, nullptr);
        SetWindowSubclass(captionButtons[i], buttonProcedure, 1, 0);
    }
    arrangeChrome();
    std::fprintf(stderr, "NATIVE_WINDOW_CREATED host=%p pid=%lu\n", host, GetCurrentProcessId()); std::fflush(stderr);
    ShowWindow(host, SW_SHOW); UpdateWindow(host);
    SetForegroundWindow(host);
    SetTimer(host, 1, 16, nullptr);
    worker = CreateThread(nullptr, 4 * 1024 * 1024, startJvm, nullptr, 0, nullptr);
    MSG message;
    while (GetMessageW(&message, nullptr, 0, 0) > 0) { TranslateMessage(&message); DispatchMessageW(&message); }
    if (worker) { WaitForSingleObject(worker, INFINITE); CloseHandle(worker); }
    if (renderer) disposeRenderer(renderer);
    DeleteObject(captionFont); DestroyIcon(smallIcon); DestroyIcon(largeIcon);
    CloseHandle(changed);
    return 0;
}
