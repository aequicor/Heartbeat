package io.aequicor.heartbeat.feature.githubconnector.impl.data

import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnectorFailure
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubRepository
import io.aequicor.heartbeat.feature.githubconnector.impl.domain.GitHubAccess
import io.aequicor.heartbeat.feature.githubconnector.impl.domain.GitHubCliException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProcessGitHubCliTest {
    private class Call(val command: List<String>, val environment: Map<String, String>, val merged: Boolean)

    private class ScriptedRunner(private val answers: (List<String>) -> CliResult) : CliRunner {
        val calls = mutableListOf<Call>()
        override suspend fun run(
            command: List<String>,
            environment: Map<String, String>,
            mergeError: Boolean,
        ): CliResult {
            calls += Call(command, environment, mergeError)
            return answers(command)
        }
    }

    private fun host(existing: Set<String>, path: String? = "/usr/bin:/bin", os: String = "Mac OS X") =
        CliHost(os, "/Users/me", path, emptyList()) { it.path in existing }

    @Test
    fun `the cli is found in the standard directories before the user path`() = runTest {
        val runner = ScriptedRunner { CliResult(0, "") }
        val found = ProcessGitHubCli(runner, host(setOf("/opt/homebrew/bin/gh", "/usr/bin/gh")))
        assertEquals("/opt/homebrew/bin/gh", found.locate(null))
        assertEquals("/usr/bin/gh", ProcessGitHubCli(runner, host(setOf("/usr/bin/gh"))).locate(null))
        val custom = ProcessGitHubCli(runner, host(setOf("/custom/gh", "/usr/bin/gh")))
        assertEquals("/custom/gh", custom.locate("/custom/gh"))
        assertNull(ProcessGitHubCli(runner, host(emptySet())).locate(null))
        assertNull(ProcessGitHubCli(runner, host(setOf("gh"))).locate("gh"))
    }

    @Test
    fun `accounts are read from the status output without duplicates`() = runTest {
        val output = """
            github.com
              ✓ Logged in to github.com account aequicor (keyring)
              - Active account: true
              ✓ Logged in to github.com account work-account (keyring)
              ✓ Logged in to github.com account aequicor (keyring)
        """.trimIndent()
        val runner = ScriptedRunner { CliResult(1, output) }
        assertEquals(listOf("aequicor", "work-account"), ProcessGitHubCli(runner, host(emptySet())).accounts("/gh"))
        assertEquals(true, runner.calls.single().merged)
    }

    @Test
    fun `repositories keep only pushable valid names and pass the token to the child only`() = runTest {
        val runner = ScriptedRunner { command ->
            if ("token" in command) {
                CliResult(0, "tok123\n")
            } else {
                CliResult(0, "me/repo\ttrue\ttrue\nme/ro\tfalse\tfalse\n--flag\ttrue\ttrue\nme/pub\tfalse\ttrue\n")
            }
        }
        val repos = ProcessGitHubCli(runner, host(emptySet())).repositories("/gh", "me")
        assertEquals(listOf(GitHubRepository("me/repo", true), GitHubRepository("me/pub", false)), repos)
        val token = runner.calls.first()
        assertEquals(listOf("/gh", "auth", "token", "--hostname", "github.com", "--user", "me"), token.command)
        assertEquals(emptyMap(), token.environment)
        assertEquals(mapOf("GH_TOKEN" to "tok123"), runner.calls.last().environment)
        assertEquals(false, runner.calls.last().merged)
    }

    @Test
    fun `a missing token or a failed listing is an access failure`() = runTest {
        val noToken = ProcessGitHubCli(ScriptedRunner { CliResult(1, "") }, host(emptySet()))
        assertEquals(
            GitHubConnectorFailure.NoAccess,
            assertFailsWith<GitHubCliException> { noToken.repositories("/gh", "me") }.failure,
        )
        val failing = ProcessGitHubCli(
            ScriptedRunner { if ("token" in it) CliResult(0, "tok") else CliResult(1, "") },
            host(emptySet()),
        )
        assertEquals(
            GitHubConnectorFailure.NoAccess,
            assertFailsWith<GitHubCliException> { failing.repositories("/gh", "me") }.failure,
        )
    }

    @Test
    fun `access distinguishes read only repositories and rejects hostile names`() = runTest {
        fun cli(push: CliResult) = ProcessGitHubCli(
            ScriptedRunner { if ("token" in it) CliResult(0, "tok") else push },
            host(emptySet()),
        )
        assertEquals(GitHubAccess.Granted, cli(CliResult(0, "true\n")).access("/gh", "me", "me/repo"))
        assertEquals(GitHubAccess.NoPullRequestAccess, cli(CliResult(0, "false\n")).access("/gh", "me", "me/repo"))
        assertEquals(GitHubAccess.NoAccess, cli(CliResult(1, "")).access("/gh", "me", "me/repo"))
        assertFailsWith<IllegalArgumentException> { cli(CliResult(0, "true")).access("/gh", "me", "--hostname/x") }
        assertFailsWith<IllegalArgumentException> { cli(CliResult(0, "true")).access("/gh", "-bad", "me/repo") }
    }

    @Test
    fun `inherited credentials are dropped from the child environment`() {
        val environment = mutableMapOf("GH_TOKEN" to "x", "gh_host" to "evil", "PATH" to "/bin")
        sanitizeCliEnvironment(environment)
        val expected = mapOf("PATH" to "/bin", "GH_PROMPT_DISABLED" to "1", "GH_PAGER" to "cat", "NO_COLOR" to "1")
        assertEquals(expected, environment)
    }
}
