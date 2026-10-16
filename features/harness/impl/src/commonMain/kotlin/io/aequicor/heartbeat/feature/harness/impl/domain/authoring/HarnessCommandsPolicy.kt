package io.aequicor.heartbeat.feature.harness.impl.domain.authoring

/**
 * Trusted review rules for script commands, shared by every review of harness source (agent edits and project
 * configuration): the review of the source is the only check their later unattended runs get.
 */
internal const val HARNESS_COMMANDS_POLICY = """
Commands declared with commands.define run whenever the script calls commands.run: on mini-app button presses
(native or WebView heartbeat.call), timers and events, in the user's project with the user's permissions and with
no later agent turn, review or user confirmation. This review is their only check. No shell parses the words;
parameter values come from untrusted surface input and pages, constrained only by their declared patterns.
Approve a declaration only if its fixed program and arguments are safe to repeat unattended for every value its
pattern allows, such as project builds, tests, linters, formatters and read-only git or gh queries. Return risk for
destructive or irreversible effects (deleting, resetting, force-pushing, publishing, deploying, installing),
credential or secret access, uploads, privilege changes, interpreters or shells running parameter-built code
(sh -c, python -c, node -e, eval) and patterns admitting options, paths outside the project or arbitrary words
in sensitive positions. Direct ProcessBuilder, Runtime.exec or other process APIs remain arbitrary execution.
"""
