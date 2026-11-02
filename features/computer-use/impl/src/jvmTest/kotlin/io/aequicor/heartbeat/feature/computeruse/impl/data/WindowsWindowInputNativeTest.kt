package io.aequicor.heartbeat.feature.computeruse.impl.data

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.win32.StdCallLibrary
import io.aequicor.heartbeat.feature.computeruse.api.InputAction
import io.aequicor.heartbeat.feature.computeruse.api.InputOutcome
import io.aequicor.heartbeat.feature.computeruse.api.ScreenBounds
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ScreenPoint
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.awt.event.InputEvent
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A real external Win32 EDIT control, created without activation; no user's application receives test input. */
class WindowsWindowInputNativeTest {
    @Test
    fun `real background edit receives Unicode while foreground and cursor stay unchanged`() = runTest {
        if (!Platform.isWindows()) return@runTest
        val encoded = Base64.getEncoder().encodeToString(PROBE.toByteArray(Charsets.UTF_16LE))
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
            .redirectErrorStream(true).start()
        val reader = process.inputStream.bufferedReader()
        try {
            val handles = CompletableFuture.supplyAsync { reader.readLine() }.get(30, TimeUnit.SECONDS)
            val root = Pointer(checkNotNull(handles).toLongOrNull() ?: error("Probe did not start: $handles"))
            val native = Native.load("user32", ProbeLib::class.java)
            val users = Native.load("user32", WindowInputLib::class.java)
            val foreground = native.GetForegroundWindow()
            val before = NativePoint()
            assertTrue(native.GetCursorPos(before))
            val origin = NativePoint()
            assertTrue(native.ClientToScreen(root, origin))
            val driver = WindowsWindowInputDriver(users, root) { listOf(ScreenBounds(0, 0, 8192, 8192)) }
            driver.move(ScreenPoint(origin.x + 30, origin.y + 30))
            driver.pressButton(InputEvent.BUTTON1_DOWN_MASK)
            driver.releaseButton(InputEvent.BUTTON1_DOWN_MASK)
            val devices = object : DesktopInputDevices {
                override val isAvailable = true
                override fun create(): DesktopInputDriver = driver.also { it.beginAction() }
            }
            val injector = DesktopInputInjector(TestDispatchers(StandardTestDispatcher(testScheduler)), devices)
            val text = "Hello Я😀a\bb\r\nc"
            assertEquals(InputOutcome.Applied, injector.apply(InputAction.Type(text)) { null })
            // This message is queued after the input on the probe's sole UI thread: its response is a barrier,
            // not a sleep or an assumption about how quickly the external process dispatches the messages.
            assertTrue(users.PostMessageW(root, 0x8001, WPARAM(0), LPARAM(0)))
            val result = CompletableFuture.supplyAsync { reader.readLine() }.get(10, TimeUnit.SECONDS)
            val decoded = String(Base64.getDecoder().decode(result), Charsets.UTF_8)
            assertEquals("Hello Я😀b\r\nc", decoded)
            val after = NativePoint()
            assertTrue(native.GetCursorPos(after))
            assertEquals(before.x to before.y, after.x to after.y)
            assertEquals(foreground, native.GetForegroundWindow())
        } finally {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            reader.close()
        }
    }

    @Suppress("FunctionNaming") // Test-only user32 ABI; all production input is through WindowInputLib.
    private interface ProbeLib : StdCallLibrary {
        fun GetForegroundWindow(): Pointer?
        fun GetCursorPos(point: NativePoint): Boolean
        fun ClientToScreen(window: Pointer, point: NativePoint): Boolean
    }

    private companion object {
        // The probe has one message loop and one standard EDIT child. ShowWindow(SW_SHOWNOACTIVATE) preserves
        // foreground ownership. UTF-8 output carries surrogate pairs back to the JVM without a codepage loss.
        val PROBE = """
            [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding
            Add-Type -TypeDefinition @'
            using System;
            using System.Runtime.InteropServices;
            using System.Text;
            public static class InputProbe {
                delegate IntPtr Proc(IntPtr h, uint m, IntPtr w, IntPtr l);
                [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
                struct WC {
                    public uint style; public Proc proc; public int a, b; public IntPtr instance, icon, cursor, brush;
                    public string menu, name;
                }
                [StructLayout(LayoutKind.Sequential)]
                struct MSG {
                    public IntPtr hwnd; public uint message; public IntPtr w, l; public uint time;
                    public int x, y; public uint extra;
                }
                [DllImport("user32", CharSet=CharSet.Unicode)] static extern ushort RegisterClassW(ref WC wc);
                [DllImport("user32", CharSet=CharSet.Unicode)] static extern IntPtr CreateWindowExW(
                    uint ex, string cls, string title, uint style, int x, int y, int width, int height,
                    IntPtr parent, IntPtr menu, IntPtr instance, IntPtr param);
                [DllImport("user32")] static extern bool ShowWindow(IntPtr h, int how);
                [DllImport("user32")] static extern int GetMessageW(out MSG msg, IntPtr hwnd, uint min, uint max);
                [DllImport("user32")] static extern bool TranslateMessage(ref MSG msg);
                [DllImport("user32")] static extern IntPtr DispatchMessageW(ref MSG msg);
                [DllImport("user32")] static extern IntPtr DefWindowProcW(IntPtr h, uint m, IntPtr w, IntPtr l);
                [DllImport("user32", CharSet=CharSet.Unicode)] static extern int GetWindowTextW(
                    IntPtr h, StringBuilder text, int size);
                static IntPtr edit;
                static readonly Proc Callback = OnMessage;
                static IntPtr OnMessage(IntPtr h, uint m, IntPtr w, IntPtr l) {
                    if (m == 0x8001) {
                        var text = new StringBuilder(200);
                        GetWindowTextW(edit, text, text.Capacity);
                        Console.WriteLine(Convert.ToBase64String(Encoding.UTF8.GetBytes(text.ToString())));
                        Console.Out.Flush(); return IntPtr.Zero;
                    }
                    return DefWindowProcW(h, m, w, l);
                }
                public static void Run() {
                    var wc = new WC { name = "HeartbeatBackgroundInputProbe", proc = Callback };
                    if (RegisterClassW(ref wc) == 0) throw new Exception("RegisterClassW failed");
                    var root = CreateWindowExW(0x08000000, wc.name, "Heartbeat input probe", 0x80000000,
                        100, 100, 300, 200, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero);
                    edit = CreateWindowExW(0, "EDIT", "", 0x50001004, 10, 10, 250, 150,
                        root, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero);
                    if (root == IntPtr.Zero || edit == IntPtr.Zero) throw new Exception("CreateWindowExW failed");
                    ShowWindow(root, 4);
                    Console.WriteLine(root.ToInt64()); Console.Out.Flush();
                    MSG msg;
                    while (GetMessageW(out msg, IntPtr.Zero, 0, 0) > 0) {
                        TranslateMessage(ref msg); DispatchMessageW(ref msg);
                    }
                }
            }
            '@
            [InputProbe]::Run()
        """.trimIndent()
    }
}
