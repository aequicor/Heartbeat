package io.aequicor.heartbeat.feature.githubconnector.impl.data

import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnectorFailure
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubRepository
import io.aequicor.heartbeat.feature.githubconnector.impl.domain.GitHubAccess
import io.aequicor.heartbeat.feature.githubconnector.impl.domain.GitHubCli
import io.aequicor.heartbeat.feature.githubconnector.impl.domain.GitHubCliException
import java.io.File

/** Host facts of the CLI search; tests replace them. */
internal data class CliHost(
    val osName: String = System.getProperty("os.name").orEmpty(),
    val userHome: String = System.getProperty("user.home").orEmpty(),
    val path: String? = System.getenv("PATH"),
    val programFiles: List<String> = listOfNotNull(System.getenv("ProgramFiles"), System.getenv("LOCALAPPDATA")),
    val isExecutable: (File) -> Boolean = { it.isFile && it.canExecute() },
) {
    val isWindows: Boolean get() = osName.startsWith("Windows")
}

/**
 * The GitHub CLI behind a [CliRunner]: standard install locations and the user's PATH are searched, the token of
 * the chosen account is requested per call and handed to the child process only. Arguments are a fixed list, and
 * repository names are validated before they reach one.
 */
internal class ProcessGitHubCli(private val runner: CliRunner, private val host: CliHost = CliHost()) : GitHubCli {
    override suspend fun locate(preferred: String?): String? {
        val name = if (host.isWindows) "gh.exe" else "gh"
        val candidates = listOfNotNull(preferred?.let(::File)) + directories().map { File(it, name) }
        return candidates.firstOrNull { it.isAbsolute && host.isExecutable(it) }?.path
    }

    override suspend fun accounts(cli: String): List<String> {
        val result = runner.run(listOf(cli, "auth", "status", "--hostname", HOST), emptyMap(), mergeError = true)
        return AccountLine.findAll(result.output).map { it.groupValues[1] }.distinct().toList()
    }

    override suspend fun repositories(cli: String, account: String): List<GitHubRepository> {
        val result = runner.run(
            listOf(cli, "api", REPOSITORIES_PATH, "--jq", REPOSITORIES_QUERY),
            tokenEnvironment(cli, account),
            mergeError = false,
        )
        if (result.exitCode != 0) throw GitHubCliException(GitHubConnectorFailure.NoAccess)
        return result.output.lineSequence().mapNotNull(::repository).toList()
    }

    override suspend fun access(cli: String, account: String, repository: String): GitHubAccess {
        require(RepositoryName.matches(repository)) { "Invalid repository name" }
        val result = runner.run(
            listOf(cli, "api", "repos/$repository", "--jq", ".permissions.push"),
            tokenEnvironment(cli, account),
            mergeError = false,
        )
        return when {
            result.exitCode != 0 -> GitHubAccess.NoAccess
            result.output.trim() == "true" -> GitHubAccess.Granted
            else -> GitHubAccess.NoPullRequestAccess
        }
    }

    private suspend fun tokenEnvironment(cli: String, account: String): Map<String, String> {
        require(AccountName.matches(account)) { "Invalid account name" }
        val result = runner.run(
            listOf(cli, "auth", "token", "--hostname", HOST, "--user", account),
            emptyMap(),
            mergeError = false,
        )
        val token = result.output.trim()
        if (result.exitCode != 0 || token.isEmpty() || token.any { it.isWhitespace() }) {
            throw GitHubCliException(GitHubConnectorFailure.NoAccess)
        }
        return mapOf("GH_TOKEN" to token)
    }

    private fun repository(line: String): GitHubRepository? {
        val parts = line.split('\t')
        val name = parts.getOrNull(0).orEmpty()
        return if (parts.size == REPOSITORY_FIELDS && RepositoryName.matches(name) && parts[2] == "true") {
            GitHubRepository(name, isPrivate = parts[1] == "true")
        } else {
            null
        }
    }

    private fun directories(): List<String> {
        val standard = if (host.isWindows) {
            host.programFiles.map { File(File(it, "GitHub CLI"), "").path } +
                host.programFiles.map { File(File(File(it, "Programs"), "GitHub CLI"), "").path }
        } else {
            listOfNotNull(
                "/opt/homebrew/bin".takeIf { host.osName.startsWith("Mac") },
                "/usr/local/bin",
                File(File(host.userHome, ".local"), "bin").path,
                "/usr/bin",
            )
        }
        val path = host.path.orEmpty().split(if (host.isWindows) ';' else ':').filter(String::isNotBlank)
        return (standard + path).distinct()
    }

    private companion object {
        const val HOST = "github.com"
        const val REPOSITORIES_PATH =
            "user/repos?per_page=100&sort=pushed&affiliation=owner,collaborator,organization_member"
        const val REPOSITORIES_QUERY = ".[] | [.full_name, .private, .permissions.push] | @tsv"
        const val REPOSITORY_FIELDS = 3

        // Newer gh prints "account NAME", gh before multi-account support printed "as NAME".
        val AccountLine = Regex("""Logged in to github\.com (?:account|as) (\S+)""")
        val AccountName = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}")
        val RepositoryName = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")
    }
}
