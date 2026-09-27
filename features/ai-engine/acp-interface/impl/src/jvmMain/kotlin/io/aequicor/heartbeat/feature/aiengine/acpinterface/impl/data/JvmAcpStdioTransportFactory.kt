package io.aequicor.heartbeat.feature.aiengine.acpinterface.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpCommand
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpException
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpStdioTransportFactory
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpTransport
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

@ContributesBinding(AppScope::class)
@Inject
internal class JvmAcpStdioTransportFactory(private val dispatchers: DispatcherProvider) : AcpStdioTransportFactory {
    private val log = Log.tag("JvmAcpStdioTransportFactory")
    override val isSupported: Boolean = true

    override suspend fun open(command: AcpCommand): AcpTransport {
        var opened: AcpTransport? = null
        var isTransferred = false
        try {
            return withContext(dispatchers.io) {
                log.i { "ACP starting stdio process" }
                JvmAcpTransport(start(command), dispatchers).also { opened = it }
            }.also { isTransferred = true }
        } finally {
            if (!isTransferred) opened?.close()
        }
    }

    /**
     * Platform errors name the executable or quote environment values, which are not safe for logs;
     * only a sanitized diagnostic leaves here.
     */
    private fun start(command: AcpCommand): Process = try {
        val builder = ProcessBuilder(listOf(command.executable) + command.arguments)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
        command.workingDirectory?.let { builder.directory(File(it)) }
        if (!command.isEnvironmentInherited) builder.environment().clear()
        builder.environment().putAll(command.environment)
        builder.start()
    } catch (e: IOException) {
        log.w(AcpDiagnostic(e)) { "ACP stdio process failed to start" }
        throw AcpException.LaunchFailed()
    } catch (e: IllegalArgumentException) {
        log.w(AcpDiagnostic(e)) { "ACP stdio process has invalid environment" }
        throw AcpException.LaunchFailed()
    } catch (e: SecurityException) {
        log.w(AcpDiagnostic(e)) { "ACP stdio process start denied" }
        throw AcpException.LaunchFailed()
    }
}
