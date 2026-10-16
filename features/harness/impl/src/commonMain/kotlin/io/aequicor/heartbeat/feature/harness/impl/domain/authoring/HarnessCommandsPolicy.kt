package io.aequicor.heartbeat.feature.harness.impl.domain.authoring

/**
 * Trusted review rules for script commands, shared by every review of harness source (agent edits and project
 * configuration): the review of the source is the only check their later unattended runs get.
 */
internal const val HARNESS_COMMANDS_POLICY = """
Commands declared with commands.define run whenever the script calls commands.run: on mini-app button presses
(native or WebView heartbeat.call), timers and events, in the user's project with the user's permissions and with
no later agent turn, review or user confirmation. This review is their only check. Words are not expanded or split
on POSIX; on Windows the host refuses cmd.exe specials, but a batch program (.bat, .cmd) or a program that parses its
own arguments may still interpret values. Parameter values come from untrusted surface input and pages, constrained
only by their declared patterns. Patterns must be simple anchored character classes without nested repetition.
The project must come from the trusted call workspace or session, never from a WorkspaceRef built from input.
Approve a declaration only if its fixed program and arguments are safe to repeat unattended for every value its
pattern allows, such as project builds, tests, linters, formatters and read-only git or gh queries. Return risk for
destructive or irreversible effects (deleting, resetting, force-pushing, publishing, deploying, installing),
credential or secret access, uploads, privilege changes, interpreters or shells running parameter-built code
(sh -c, python -c, node -e, eval) and patterns admitting options, paths outside the project or arbitrary words
in sensitive positions. Direct ProcessBuilder, Runtime.exec or other process APIs remain arbitrary execution.
"""
