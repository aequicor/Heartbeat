package io.aequicor.heartbeat.feature.scheduler.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.scheduler.api.LocalCommandOutcome
import io.aequicor.heartbeat.feature.scheduler.api.LocalCommandRequest
import io.aequicor.heartbeat.feature.scheduler.api.LocalCommands

/**
 * Local commands of other features on the scheduler's contained runner: the program and its arguments are quoted
 * literally for the runner's shell, so no word is expanded or split. Independent of scheduler toggles and capacity:
 * the caller owns admission. Programs, arguments and output are never logged.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class RunnerLocalCommands(private val runner: CommandRunner, private val workspaces: LocalWorkspaces) :
    LocalCommands {
    private val log = Log.tag("LocalCommands")

    override val isAvailable: Boolean get() = runner.isAvailable && runner.shell != null && workspaces.isAvailable

    override suspend fun run(request: LocalCommandRequest): LocalCommandOutcome {
        val shell = runner.shell
        if (!runner.isAvailable || shell == null || !workspaces.isAvailable) return LocalCommandOutcome.Unsupported
        val command = quotedCommand(shell, request.program, request.arguments) ?: run {
            log.i { "local command refused: arguments cannot pass through $shell unchanged" }
            return LocalCommandOutcome.Invalid
        }
        val directory = workspaces.resolve(request.workspace) ?: run {
            log.i { "local command refused: the project is not available" }
            return LocalCommandOutcome.NoProject
        }
        val outcome = runner.run(directory, command, request.timeout)
        return outcome.exitCode?.let { LocalCommandOutcome.Exited(it, outcome.output) }
            ?: LocalCommandOutcome.TimedOut(outcome.output)
    }
}
