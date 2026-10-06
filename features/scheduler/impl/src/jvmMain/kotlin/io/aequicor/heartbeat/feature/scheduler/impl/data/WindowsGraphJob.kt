package io.aequicor.heartbeat.feature.scheduler.impl.data

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT.HANDLE
import com.sun.jna.win32.StdCallLibrary
import io.aequicor.heartbeat.core.logging.Log
import kotlin.uuid.Uuid

/** Kill-on-close containment is installed while PowerShell still waits for the persisted-identity handshake. */
internal class WindowsGraphJob private constructor(private val handle: HANDLE, val name: String) : AutoCloseable {
    override fun close() {
        check(Kernel32.INSTANCE.CloseHandle(handle)) { "Could not close graph process job" }
    }

    fun terminate() {
        check(native.TerminateJobObject(handle, 1)) { "Could not stop process job" }
    }

    fun isEmpty(): Boolean = Memory(ACCOUNTING_BYTES.toLong()).use { info ->
        check(native.QueryInformationJobObject(handle, BASIC_ACCOUNTING, info, ACCOUNTING_BYTES, null))
        info.getInt(ACTIVE_PROCESSES_OFFSET) == 0
    }

    companion object {
        private const val ACCOUNTING_BYTES = 48
        private const val ACTIVE_PROCESSES_OFFSET = 40L
        private const val BASIC_ACCOUNTING = 1
        private const val JOB_QUERY_TERMINATE = 0x0004 or 0x0008
        private const val NOT_FOUND = 2
        private const val POINTER_BYTES = 8
        private const val LIMITS_BYTES = 144
        private const val LIMIT_FLAGS_OFFSET = 16L
        private const val KILL_ON_JOB_CLOSE = 0x2000
        private const val EXTENDED_LIMITS = 9
        private val log = Log.tag("WindowsGraphJob")
        private val native by lazy { Native.load("kernel32", JobApi::class.java) }

        /** Named jobs disappear only after their last handle closes and every associated process exits. */
        fun inspect(name: String, terminate: Boolean = false): Boolean {
            val handle = native.OpenJobObjectW(JOB_QUERY_TERMINATE, false, WString(name))
                ?: return Kernel32.INSTANCE.GetLastError() == NOT_FOUND
            return WindowsGraphJob(handle, name).use { job ->
                if (terminate) job.terminate()
                job.isEmpty()
            }
        }

        fun attach(process: Process): WindowsGraphJob {
            check(Native.POINTER_SIZE == POINTER_BYTES) { "Graph process jobs require 64-bit Windows" }
            val name = "Local\\HeartbeatGraph_" + Uuid.random().toHexString()
            val job = checkNotNull(native.CreateJobObjectW(null, WString(name))) { "Could not create process job" }
            var isAttached = false
            try {
                // JOBOBJECT_EXTENDED_LIMIT_INFORMATION, Win64 ABI: LimitFlags is at offset 16.
                val limits = Memory(LIMITS_BYTES.toLong()).apply {
                    clear()
                    setInt(LIMIT_FLAGS_OFFSET, KILL_ON_JOB_CLOSE)
                }
                check(
                    native.SetInformationJobObject(job, EXTENDED_LIMITS, limits, LIMITS_BYTES),
                ) { "Could not enable job cleanup" }
                val child = checkNotNull(Kernel32.INSTANCE.OpenProcess(0x0100 or 0x0001, false, process.pid().toInt()))
                try {
                    check(native.AssignProcessToJobObject(job, child)) { "Could not contain the command process" }
                    isAttached = true
                    return WindowsGraphJob(job, name)
                } finally {
                    if (!Kernel32.INSTANCE.CloseHandle(child)) log.w { "process identity handle close failed" }
                }
            } finally {
                if (!isAttached && !Kernel32.INSTANCE.CloseHandle(job)) log.w { "unused process job close failed" }
            }
        }
    }
}

/** Exact Win32 names are required for native symbol lookup. */
@Suppress("FunctionNaming") // Native ABI symbol names.
internal interface JobApi : StdCallLibrary {
    fun CreateJobObjectW(attributes: Pointer?, name: WString?): HANDLE?
    fun OpenJobObjectW(access: Int, inherit: Boolean, name: WString): HANDLE?
    fun TerminateJobObject(job: HANDLE, exitCode: Int): Boolean
    fun QueryInformationJobObject(
        job: HANDLE,
        informationClass: Int,
        information: Pointer,
        length: Int,
        returned: Pointer?,
    ): Boolean
    fun SetInformationJobObject(job: HANDLE, informationClass: Int, information: Pointer, length: Int): Boolean
    fun AssignProcessToJobObject(job: HANDLE, process: HANDLE): Boolean
}
