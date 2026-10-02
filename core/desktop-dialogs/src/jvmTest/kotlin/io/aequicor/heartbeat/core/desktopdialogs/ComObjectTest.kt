package io.aequicor.heartbeat.core.desktopdialogs

import com.sun.jna.CallbackReference
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary.StdCallCallback
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ComObjectTest {
    @Test
    fun `failed HRESULT is propagated but Release reference count is not checked`() {
        if (!Platform.isWindows()) return
        val failure = 0x80004005.toInt()
        withComResult(failure) { com ->
            val error = assertFailsWith<IllegalStateException> { com.callChecked(0) }
            assertTrue(error.message.orEmpty().contains("0x80004005"))
            com.close()
        }
    }

    @Test
    fun `successful HRESULT including S_FALSE is accepted`() {
        if (!Platform.isWindows()) return
        withComResult(0) { it.callChecked(0) }
        withComResult(1) { it.callChecked(0) }
    }

    private fun withComResult(result: Int, body: (ComObject) -> Unit) {
        val callback = object : ResultCallback {
            override fun invoke(self: Pointer): Int = result
        }
        Memory(3L * Native.POINTER_SIZE).use { vtable ->
            val function = CallbackReference.getFunctionPointer(callback)
            vtable.setPointer(0, function)
            vtable.setPointer(2L * Native.POINTER_SIZE, function)
            Memory(Native.POINTER_SIZE.toLong()).use { instance ->
                instance.setPointer(0, vtable)
                body(ComObject(instance))
            }
        }
        java.lang.ref.Reference.reachabilityFence(callback)
    }

    private interface ResultCallback : StdCallCallback {
        fun invoke(self: Pointer): Int
    }
}
