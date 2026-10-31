#ifndef HEARTBEAT_WINDOWS_JVM_H
#define HEARTBEAT_WINDOWS_JVM_H
#include <windows.h>
#include <windowsx.h>
#include <jni.h>
#include <jawt.h>
#include <stdlib.h>
#include <string.h>

/* One VM per launcher process. The worker owns JNI and remains alive through selector and shutdown hooks. */
typedef struct {
    HWND window;
    HWND child;
    HWND cover;
    void (*paint_cover)(void *, HDC, int, int);
    void *cover_context;
    void (*dispose_cover)(void *);
    volatile LONG revealed;
    volatile LONG caption_client;
    HANDLE thread;
    HMODULE jvm_library;
    HMODULE jawt_library;
    wchar_t *runtime;
    JavaVMOption *options;
    int option_count;
    wchar_t **arguments;
    int argument_count;
    volatile LONG close_requested;
    ULONGLONG cancel_started;
    volatile LONG presented;
    volatile LONG mounted;
    volatile LONG selector_entered;
    volatile LONG vm_created;
    volatile LONG vm_stopped;
    int retired;
    wchar_t *previous_directory;
    int last_width;
    int last_height;
    int last_active;
    int last_dpi;
    int last_zoom;
    JAWT awt;
} HbJvm;
static HbJvm hb_jvm;

static void hb_host_clip(void);
static int hb_host_scale(int pixels);
static void hb_jvm_error(JNIEnv *env, const char *message);
static void hb_cover_layout(void) {
    if (!hb_jvm.cover) return;
    RECT rect; if (!GetClientRect(hb_jvm.window, &rect)) return;
    SetWindowPos(hb_jvm.cover, HWND_TOP, 0, 0, rect.right, rect.bottom, SWP_NOACTIVATE);
    HRGN region = CreateRectRgn(0, 0, rect.right, rect.bottom);
    HRGN buttons = CreateRectRgn(rect.right - hb_host_scale(144), 0, rect.right, hb_host_scale(44));
    CombineRgn(region, region, buttons, RGN_DIFF); DeleteObject(buttons);
    if (!SetWindowRgn(hb_jvm.cover, region, FALSE)) DeleteObject(region);
}
static void hb_cover_release(void) {
    if (hb_jvm.cover_context && hb_jvm.dispose_cover) hb_jvm.dispose_cover(hb_jvm.cover_context);
    hb_jvm.cover_context = NULL; hb_jvm.paint_cover = NULL; hb_jvm.dispose_cover = NULL;
}
static LRESULT CALLBACK hb_cover_proc(HWND window, UINT message, WPARAM w, LPARAM l) {
    if (message == WM_NCDESTROY) { hb_jvm.cover = NULL; hb_cover_release(); }
    if (message == WM_ERASEBKGND) return 1;
    if (message == WM_NCHITTEST) return HTTRANSPARENT;
    if (message == WM_PAINT) {
        PAINTSTRUCT paint; RECT rect;
        HDC dc = BeginPaint(window, &paint); GetClientRect(window, &rect);
        if (dc && hb_jvm.paint_cover) hb_jvm.paint_cover(hb_jvm.cover_context, dc, rect.right, rect.bottom);
        EndPaint(window, &paint); return 0;
    }
    if (message == WM_APP + 1 || message == WM_APP + 2) {
        /* Paint beneath the opaque sibling has completed on EDT before this message was posted. */
        DestroyWindow(window);
        hb_jvm.cover = NULL;
        if (message == WM_APP + 1) InterlockedExchange(&hb_jvm.revealed, 1);
        return 0;
    }
    return DefWindowProcW(window, message, w, l);
}
static int hb_jvm_install_cover(HWND parent, void (*paint)(void *, HDC, int, int),
                                void (*dispose)(void *), void *context) {
    hb_jvm.window = parent; hb_jvm.paint_cover = paint; hb_jvm.cover_context = context; hb_jvm.dispose_cover = dispose;
    WNDCLASSW cls = {0};
    cls.lpfnWndProc = hb_cover_proc; cls.hInstance = GetModuleHandleW(NULL);
    cls.lpszClassName = L"Heartbeat.EmbeddedCover"; cls.hCursor = LoadCursorW(NULL, IDC_ARROW);
    if (!RegisterClassW(&cls) && GetLastError() != ERROR_CLASS_ALREADY_EXISTS) return 0;
    hb_jvm.cover = CreateWindowExW(0, cls.lpszClassName, L"", WS_CHILD | WS_VISIBLE | WS_CLIPSIBLINGS,
                                  0, 0, 1, 1, parent, NULL, cls.hInstance, NULL);
    if (!hb_jvm.cover) return 0;
    hb_cover_layout();
    UpdateWindow(hb_jvm.cover);
    return 1;
}
static void hb_jvm_close_cover(void) {
    if (hb_jvm.cover) DestroyWindow(hb_jvm.cover);
    hb_jvm.cover = NULL; hb_cover_release();
}
/* The launcher scene (owner-drawn children included) needs WS_EX_COMPOSITED, but the embedded Studio presents an
   accelerated Compose surface: a composited window tree commits through one off-screen GDI bitmap, so every Studio
   frame would be read back into it and scrolling, animations and input would stall for the whole session. The style
   is dropped while the native cover still hides the window, before its first frame becomes visible. */
static void hb_host_release_composition(void) {
    if (!hb_jvm.window) return;
    LONG_PTR style = GetWindowLongPtrW(hb_jvm.window, GWL_EXSTYLE);
    if (!(style & WS_EX_COMPOSITED)) return;
    SetWindowLongPtrW(hb_jvm.window, GWL_EXSTYLE, style & ~WS_EX_COMPOSITED);
    SetWindowPos(hb_jvm.window, NULL, 0, 0, 0, 0,
                 SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);
    /* Invalidate only: the present call arrives from the AWT thread, so the owning thread repaints on its own pump. */
    RedrawWindow(hb_jvm.window, NULL, NULL, RDW_INVALIDATE | RDW_ERASE | RDW_ALLCHILDREN);
}
/* Read by the JNI smoke test: the Studio must not keep the launcher's composition style. */
static int hb_host_is_composited(void) {
    if (!hb_jvm.window) return 0;
    return (GetWindowLongPtrW(hb_jvm.window, GWL_EXSTYLE) & WS_EX_COMPOSITED) != 0;
}
/* Clip the AWT surface while it prepares; the native brand remains visible until the first complete frame. */
static int hb_host_scale(int pixels) {
    typedef UINT (WINAPI *GetDpi)(HWND);
    GetDpi dpi = (GetDpi)GetProcAddress(GetModuleHandleW(L"user32.dll"), "GetDpiForWindow");
    return MulDiv(pixels, dpi ? dpi(hb_jvm.window) : 96, 96);
}
static void hb_host_clip(void) {
    if (!hb_jvm.child) return;
    RECT bounds; if (!GetClientRect(hb_jvm.window, &bounds)) return;
    int edge = IsZoomed(hb_jvm.window) ? 0 : hb_host_scale(6);
    HRGN region = hb_jvm.child ? CreateRectRgn(edge, edge, bounds.right - edge, bounds.bottom - edge) :
                                    CreateRectRgn(0, 0, 0, 0);
    HRGN buttons = CreateRectRgn(bounds.right - hb_host_scale(144), 0, bounds.right, hb_host_scale(44));
    CombineRgn(region, region, buttons, RGN_DIFF); DeleteObject(buttons);
    if (!SetWindowRgn(hb_jvm.child, region, FALSE)) DeleteObject(region);
}
static LRESULT CALLBACK hb_embedded_proc(HWND window, UINT message, WPARAM w, LPARAM l) {
    WNDPROC original = (WNDPROC)GetPropW(window, L"Heartbeat.OriginalEmbeddedProc");
    if ((message == WM_LBUTTONDOWN || message == WM_LBUTTONDBLCLK) && !hb_jvm.caption_client) {
        POINT point = {GET_X_LPARAM(l), GET_Y_LPARAM(l)};
        ClientToScreen(window, &point);
        POINT parent = point; ScreenToClient(hb_jvm.window, &parent);
        if (parent.y >= 0 && parent.y < hb_host_scale(44)) {
            ReleaseCapture();
            PostMessageW(hb_jvm.window, message == WM_LBUTTONDBLCLK ? WM_NCLBUTTONDBLCLK : WM_NCLBUTTONDOWN,
                         HTCAPTION, MAKELPARAM(point.x, point.y));
            return 0;
        }
    }
    if (message == WM_NCDESTROY) RemovePropW(window, L"Heartbeat.OriginalEmbeddedProc");
    return original ? CallWindowProcW(original, window, message, w, l) : DefWindowProcW(window, message, w, l);
}
static BOOL CALLBACK hb_hook_embedded(HWND child, LPARAM unused) {
    if (!GetPropW(child, L"Heartbeat.OriginalEmbeddedProc")) {
        WNDPROC original = (WNDPROC)GetWindowLongPtrW(child, GWLP_WNDPROC);
        if (SetPropW(child, L"Heartbeat.OriginalEmbeddedProc", (HANDLE)original)) {
            SetWindowLongPtrW(child, GWLP_WNDPROC, (LONG_PTR)hb_embedded_proc);
        }
    }
    return TRUE;
}
static void hb_jvm_child_created(HWND child) {
    if (!hb_jvm.jvm_library || GetParent(child) != hb_jvm.window) return;
    wchar_t name[64];
    if (GetClassNameW(child, name, 64) && wcsncmp(name, L"SunAwt", 6) == 0) {
        HRGN region = CreateRectRgn(0, 0, 0, 0);
        if (!SetWindowRgn(child, region, FALSE)) DeleteObject(region);
        hb_cover_layout();
    }
}
static void JNICALL hb_host_attach(JNIEnv *env, jclass type, jlong child) {
    HWND window = (HWND)(intptr_t)child;
    if (!window || GetParent(window) != hb_jvm.window) {
        hb_jvm_error(env, "AWT peer does not belong to the embedded host"); return;
    }
    hb_jvm.child = window;
    SetWindowLongPtrW(window, GWL_STYLE, GetWindowLongPtrW(window, GWL_STYLE) | WS_CLIPSIBLINGS);
    hb_host_clip();
    hb_hook_embedded(window, 0);
    EnumChildWindows(window, hb_hook_embedded, 0);
    hb_cover_layout();
}
static void JNICALL hb_host_caption(JNIEnv *env, jclass type, jboolean client) {
    InterlockedExchange(&hb_jvm.caption_client, client);
}
static void hb_jvm_error(JNIEnv *env, const char *message) {
    if ((*env)->ExceptionCheck(env)) return;
    jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (type) (*env)->ThrowNew(env, type, message);
}
static jint JNICALL hb_host_version(JNIEnv *env, jclass type) { return 1; }
static jlong JNICALL hb_host_handle(JNIEnv *env, jclass type) { return (jlong)(intptr_t)hb_jvm.window; }
static jboolean JNICALL hb_host_close(JNIEnv *env, jclass type) {
    return InterlockedExchange(&hb_jvm.close_requested, 0) ? JNI_TRUE : JNI_FALSE;
}
static void JNICALL hb_host_abandon(JNIEnv *env, jclass type) {
    if (hb_jvm.mounted) { hb_jvm_error(env, "Cannot abandon a live embedded frame"); return; }
    if (hb_jvm.cover && !PostMessageW(hb_jvm.cover, WM_APP + 2, 0, 0)) {
        hb_jvm_error(env, "Cannot discard the native cover");
    }
}
static jboolean JNICALL hb_host_revealed(JNIEnv *env, jclass type) {
    return InterlockedCompareExchange(&hb_jvm.revealed, 0, 0) ? JNI_TRUE : JNI_FALSE;
}
static jlong JNICALL hb_host_child(JNIEnv *env, jclass type) {
    HWND child = GetWindow(hb_jvm.window, GW_CHILD);
    while (child) {
        wchar_t name[64];
        if (GetClassNameW(child, name, 64) && wcsncmp(name, L"SunAwt", 6) == 0) return (jlong)(intptr_t)child;
        child = GetWindow(child, GW_HWNDNEXT);
    }
    return 0;
}
static void JNICALL hb_host_present(JNIEnv *env, jclass type) {
    if (!hb_jvm.child || !IsWindow(hb_jvm.child)) {
        hb_jvm_error(env, "Embedded peer was not attached"); return;
    }
    /* The cover still hides the Studio, so the discarded composition surface repaints invisibly. */
    hb_host_release_composition();
    InterlockedExchange(&hb_jvm.presented, 1);
    if (hb_jvm.cover) {
        if (!PostMessageW(hb_jvm.cover, WM_APP + 1, 0, 0)) {
            InterlockedExchange(&hb_jvm.presented, 0);
            hb_jvm_error(env, "Cannot reveal the embedded application");
        }
    } else InterlockedExchange(&hb_jvm.revealed, 1);
}
static jobject JNICALL hb_host_frame(JNIEnv *env, jclass type) {
    if (InterlockedCompareExchange(&hb_jvm.mounted, 0, 0)) {
        hb_jvm_error(env, "Embedded frame already claimed"); return NULL;
    }
    jclass toolkit = (*env)->FindClass(env, "java/awt/Toolkit");
    if (!toolkit) return NULL;
    jmethodID method = (*env)->GetStaticMethodID(env, toolkit, "getDefaultToolkit", "()Ljava/awt/Toolkit;");
    if (!method) return NULL;
    (*env)->CallStaticObjectMethod(env, toolkit, method);
    if ((*env)->ExceptionCheck(env)) return NULL;
    typedef jboolean (JNICALL *GetAwt)(JNIEnv *, JAWT *);
    GetAwt get_awt = (GetAwt)GetProcAddress(hb_jvm.jawt_library, "JAWT_GetAWT");
    hb_jvm.awt.version = JAWT_VERSION_9;
    if (!get_awt || !get_awt(env, &hb_jvm.awt) || !hb_jvm.awt.CreateEmbeddedFrame ||
        !hb_jvm.awt.SetBounds || !hb_jvm.awt.SynthesizeWindowActivation) {
        jclass unsupported = (*env)->FindClass(env, "java/lang/UnsupportedOperationException");
        if (unsupported) (*env)->ThrowNew(env, unsupported, "JAWT API 9 is unavailable");
        return NULL;
    }
    jobject frame = hb_jvm.awt.CreateEmbeddedFrame(env, hb_jvm.window);
    if (frame && !(*env)->ExceptionCheck(env)) {
        InterlockedExchange(&hb_jvm.mounted, 1);
        hb_jvm.last_width = hb_jvm.last_height = hb_jvm.last_active = -1;
    }
    if (!frame && !(*env)->ExceptionCheck(env)) {
        jclass unsupported = (*env)->FindClass(env, "java/lang/UnsupportedOperationException");
        if (unsupported) (*env)->ThrowNew(env, unsupported, "JAWT could not create an embedded frame");
    }
    return frame;
}
static void JNICALL hb_host_sync(JNIEnv *env, jclass type, jobject frame) {
    RECT bounds;
    if (!frame || !hb_jvm.awt.SetBounds || !GetClientRect(hb_jvm.window, &bounds)) {
        hb_jvm_error(env, "Embedded window is unavailable"); return;
    }
    if (bounds.right != hb_jvm.last_width || bounds.bottom != hb_jvm.last_height) {
        hb_jvm.awt.SetBounds(env, frame, 0, 0, bounds.right, bounds.bottom);
        hb_host_clip();
        hb_cover_layout();
        hb_jvm.last_width = bounds.right; hb_jvm.last_height = bounds.bottom;
    }
    int dpi = hb_host_scale(96);
    int zoom = IsZoomed(hb_jvm.window);
    if (dpi != hb_jvm.last_dpi || zoom != hb_jvm.last_zoom) {
        hb_host_clip(); hb_cover_layout();
        hb_jvm.last_dpi = dpi; hb_jvm.last_zoom = zoom;
    }
    int active = GetForegroundWindow() == hb_jvm.window;
    if (active != hb_jvm.last_active && !(*env)->ExceptionCheck(env)) {
        hb_jvm.awt.SynthesizeWindowActivation(env, frame, active);
        hb_jvm.last_active = active;
    }
}
static int hb_register_host(JNIEnv *env) {
    jclass loader_type = (*env)->FindClass(env, "java/lang/ClassLoader");
    if (!loader_type) return 0;
    jmethodID system_loader = (*env)->GetStaticMethodID(env, loader_type, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
    if (!system_loader) return 0;
    jobject loader = (*env)->CallStaticObjectMethod(env, loader_type, system_loader);
    if (!loader || (*env)->ExceptionCheck(env)) return 0;
    jmethodID load = (*env)->GetMethodID(env, loader_type, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    if (!load) return 0;
    jstring name = (*env)->NewStringUTF(env, "io.aequicor.heartbeat.selector.EmbeddedHost");
    if (!name) return 0;
    jclass host = (jclass)(*env)->CallObjectMethod(env, loader, load, name);
    if ((*env)->ExceptionCheck(env)) {
        jthrowable error = (*env)->ExceptionOccurred(env);
        (*env)->ExceptionClear(env);
        jclass missing = (*env)->FindClass(env, "java/lang/ClassNotFoundException");
        if (missing && (*env)->IsInstanceOf(env, error, missing)) return 2;
        if (!(*env)->ExceptionCheck(env)) (*env)->Throw(env, error);
        return 0;
    }
    if (!host) return 0;
    JNINativeMethod methods[] = {
        {"abandon", "()V", (void *)hb_host_abandon},
        {"isRevealed", "()Z", (void *)hb_host_revealed},
        {"childHandle", "()J", (void *)hb_host_child},
        {"attachChild", "(J)V", (void *)hb_host_attach},
        {"captionClient", "(Z)V", (void *)hb_host_caption},
        {"nativeVersion", "()I", (void *)hb_host_version},
        {"createFrame", "()Ljava/awt/Frame;", (void *)hb_host_frame},
        {"handle", "()J", (void *)hb_host_handle},
        {"synchronize", "(Ljava/awt/Frame;)V", (void *)hb_host_sync},
        {"takeCloseRequest", "()Z", (void *)hb_host_close},
        {"present", "()V", (void *)hb_host_present}
    };
    return (*env)->RegisterNatives(env, host, methods, sizeof(methods) / sizeof(methods[0])) == JNI_OK;
}
static DWORD WINAPI hb_jvm_main(void *unused) {
    typedef jint (JNICALL *CreateVm)(JavaVM **, void **, void *);
    CreateVm create_vm = (CreateVm)GetProcAddress(hb_jvm.jvm_library, "JNI_CreateJavaVM");
    JavaVMInitArgs init = {0};
    init.version = JNI_VERSION_1_8;
    init.nOptions = hb_jvm.option_count;
    init.options = hb_jvm.options;
    init.ignoreUnrecognized = JNI_FALSE;
    JavaVM *vm = NULL;
    JNIEnv *env = NULL;
    if (!create_vm || create_vm(&vm, (void **)&env, &init) != JNI_OK) return 1;
    InterlockedExchange(&hb_jvm.vm_created, 1);
    DWORD result = 1;
    if (hb_register_host(env) == 1) {
        jclass selector = (*env)->FindClass(env, "io/aequicor/heartbeat/selector/SelectorMain");
        jclass string = selector ? (*env)->FindClass(env, "java/lang/String") : NULL;
        if (selector && string) {
            jmethodID main = (*env)->GetStaticMethodID(env, selector, "main", "([Ljava/lang/String;)V");
            jobjectArray args = main ? (*env)->NewObjectArray(env, hb_jvm.argument_count, string, NULL) : NULL;
            if (main && args) {
                for (int i = 0; i < hb_jvm.argument_count && !(*env)->ExceptionCheck(env); i++) {
                    jstring arg = (*env)->NewString(env, (const jchar *)hb_jvm.arguments[i], wcslen(hb_jvm.arguments[i]));
                    if (arg) { (*env)->SetObjectArrayElement(env, args, i, arg); (*env)->DeleteLocalRef(env, arg); }
                }
                if (!(*env)->ExceptionCheck(env)) {
                    jclass host = (*env)->FindClass(env, "io/aequicor/heartbeat/selector/EmbeddedHost");
                    jmethodID cleanup = host ? (*env)->GetStaticMethodID(env, host, "installCleanup", "()V") : NULL;
                    if (cleanup) (*env)->CallStaticVoidMethod(env, host, cleanup);
                    if (!(*env)->ExceptionCheck(env)) {
                        InterlockedExchange(&hb_jvm.selector_entered, 1);
                        (*env)->CallStaticVoidMethod(env, selector, main, args);
                    }
                    if (!(*env)->ExceptionCheck(env)) result = 0;
                }
            }
        }
    }
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); (*env)->ExceptionClear(env); }
    if ((*vm)->DestroyJavaVM(vm) == JNI_OK) InterlockedExchange(&hb_jvm.vm_stopped, 1);
    else result = 1;
    return result;
}
static void hb_jvm_free_inputs(void) {
    for (int i = 0; hb_jvm.options && i < hb_jvm.option_count; i++) free(hb_jvm.options[i].optionString);
    for (int i = 0; hb_jvm.arguments && i < hb_jvm.argument_count; i++) free(hb_jvm.arguments[i]);
    free(hb_jvm.options); free(hb_jvm.arguments); free(hb_jvm.runtime);
    hb_jvm.options = NULL; hb_jvm.arguments = NULL; hb_jvm.runtime = NULL;
}
static char *hb_jvm_option(const char *utf8) {
    int count = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, utf8, -1, NULL, 0);
    if (!count) return NULL;
    wchar_t *wide = (wchar_t *)calloc(count, sizeof(wchar_t));
    if (!wide) return NULL;
    if (!MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, utf8, -1, wide, count)) { free(wide); return NULL; }
    UINT page = GetACP();
    BOOL replaced = FALSE;
    BOOL *replacement = page == CP_UTF8 ? NULL : &replaced;
    DWORD flags = page == CP_UTF8 ? WC_ERR_INVALID_CHARS : WC_NO_BEST_FIT_CHARS;
    int bytes = WideCharToMultiByte(page, flags, wide, -1, NULL, 0, NULL, replacement);
    char *result = bytes > 0 && !replaced ? (char *)malloc(bytes) : NULL;
    if (result && (!WideCharToMultiByte(page, flags, wide, -1, result, bytes, NULL, replacement) || replaced)) {
        free(result); result = NULL;
    }
    free(wide);
    return result;
}
/* Input strings are copied before the worker starts. A failed start never leaves a running worker. */
static int hb_jvm_start(HWND window, const wchar_t *runtime, const char **options, int option_count,
                        const wchar_t **arguments, int argument_count) {
    if (hb_jvm.thread || hb_jvm.jvm_library || hb_jvm.retired) return 0;
    wchar_t *previous_directory = NULL;
    int changed_directory = 0;
    hb_jvm.window = window;
    hb_jvm.options = (JavaVMOption *)calloc(option_count, sizeof(JavaVMOption));
    hb_jvm.arguments = (wchar_t **)calloc(argument_count ? argument_count : 1, sizeof(wchar_t *));
    hb_jvm.runtime = _wcsdup(runtime);
    hb_jvm.option_count = option_count;
    hb_jvm.argument_count = argument_count;
    if (!hb_jvm.options || !hb_jvm.arguments || !hb_jvm.runtime) goto failed;
    for (int i = 0; i < option_count; i++) {
        hb_jvm.options[i].optionString = hb_jvm_option(options[i]);
        if (!hb_jvm.options[i].optionString) goto failed;
    }
    for (int i = 0; i < argument_count; i++) {
        hb_jvm.arguments[i] = _wcsdup(arguments[i]);
        if (!hb_jvm.arguments[i]) goto failed;
    }
    size_t capacity = wcslen(runtime) + 40;
    wchar_t *path = (wchar_t *)calloc(capacity, sizeof(wchar_t));
    if (!path) goto failed;
    _snwprintf(path, capacity, L"%ls\\bin", runtime);
    DWORD previous_length = GetDllDirectoryW(0, NULL);
    previous_directory = (wchar_t *)calloc((size_t)previous_length + 1, sizeof(wchar_t));
    if (!previous_directory) { free(path); goto failed; }
    if (previous_length) GetDllDirectoryW(previous_length + 1, previous_directory);
    if (!SetDllDirectoryW(path)) { free(path); goto failed; }
    changed_directory = 1;
    _snwprintf(path, capacity, L"%ls\\bin\\server\\jvm.dll", runtime);
    hb_jvm.jvm_library = LoadLibraryW(path);
    _snwprintf(path, capacity, L"%ls\\bin\\jawt.dll", runtime);
    hb_jvm.jawt_library = LoadLibraryW(path);
    free(path);
    if (!hb_jvm.jvm_library || !hb_jvm.jawt_library) goto failed;
    hb_jvm.thread = CreateThread(NULL, 4 * 1024 * 1024, hb_jvm_main, NULL, 0, NULL);
    if (!hb_jvm.thread) goto failed;
    hb_jvm.previous_directory = previous_directory;
    return 1;
failed:
    if (changed_directory) SetDllDirectoryW(previous_directory && *previous_directory ? previous_directory : NULL);
    free(previous_directory);
    if (hb_jvm.jawt_library) FreeLibrary(hb_jvm.jawt_library);
    if (hb_jvm.jvm_library) FreeLibrary(hb_jvm.jvm_library);
    hb_jvm.jawt_library = NULL; hb_jvm.jvm_library = NULL;
    hb_jvm_free_inputs();
    return 0;
}
static HANDLE hb_jvm_thread(void) { return hb_jvm.thread; }
static void hb_jvm_request_cancel(void) { if (!hb_jvm.cancel_started) hb_jvm.cancel_started = GetTickCount64(); }
static int hb_jvm_cancel_expired(void) {
    return hb_jvm.cancel_started && GetTickCount64() - hb_jvm.cancel_started >= 30000;
}
static int hb_jvm_exit_code(void) {
    DWORD code;
    if (!hb_jvm.thread || WaitForSingleObject(hb_jvm.thread, 0) != WAIT_OBJECT_0) return -1;
    return GetExitCodeThread(hb_jvm.thread, &code) ? (int)code : 1;
}
static int hb_jvm_can_fallback(void) {
    return hb_jvm.thread && WaitForSingleObject(hb_jvm.thread, 0) == WAIT_OBJECT_0 &&
           !hb_jvm.selector_entered && (!hb_jvm.vm_created || hb_jvm.vm_stopped);
}
static int hb_jvm_prepare_fallback(void) {
    if (!hb_jvm_can_fallback()) return 0;
    int restored = SetDllDirectoryW(hb_jvm.previous_directory && *hb_jvm.previous_directory ? hb_jvm.previous_directory : NULL) != 0;
    free(hb_jvm.previous_directory); hb_jvm.previous_directory = NULL;
    CloseHandle(hb_jvm.thread); hb_jvm.thread = NULL; hb_jvm.retired = 1;
    hb_jvm_close_cover();
    hb_jvm_free_inputs();
    return restored;
}
static int hb_jvm_is_mounted(void) { return InterlockedCompareExchange(&hb_jvm.mounted, 0, 0); }
static int hb_jvm_is_presented(void) { return InterlockedCompareExchange(&hb_jvm.presented, 0, 0); }
static void hb_jvm_request_close(void) { InterlockedExchange(&hb_jvm.close_requested, 1); }
#endif
