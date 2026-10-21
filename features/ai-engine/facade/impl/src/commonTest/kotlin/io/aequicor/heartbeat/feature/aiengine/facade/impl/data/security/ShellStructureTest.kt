package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ShellStructureTest {
    @Test
    fun `every pipeline member and statement is represented`() {
        for (dialect in listOf(ShellDialect.Posix, ShellDialect.Bash)) {
            val parsed = parsed("ls -la core | head -n 2; rm fixture\nprintf done", dialect)
            assertEquals(
                listOf(
                    listOf(listOf("ls", "-la", "core"), listOf("head", "-n", "2")),
                    listOf(listOf("rm", "fixture")),
                    listOf(listOf("printf", "done")),
                ),
                parsed.statements.map { pipeline -> pipeline.commands.map { it.words } },
            )
        }
    }

    @Test
    fun `quoted operators and adjacent quoted fragments are literal words`() {
        val command = parsed("printf '%s' a\" b\"' c' 'x|y;z' \"\"", ShellDialect.Posix).onlyCommand()
        assertEquals(listOf("printf", "%s", "a b c", "x|y;z", ""), command.words)
    }

    @Test
    fun `posix quote and escape semantics preserve literal characters`() {
        val command = parsed(
            "printf 'literal \$HOME `x`' \"\\\$HOME \\q\" a\\ b a\\|b \\\nnext",
            ShellDialect.Bash,
        ).onlyCommand()
        assertEquals(listOf("printf", "literal \$HOME `x`", "\$HOME \\q", "a b", "a|b", "next"), command.words)
    }

    @Test
    fun `redirections retain targets descriptors kind and order`() {
        val command = parsed("cat < 'in file' 2>errors >>output", ShellDialect.Posix).onlyCommand()
        assertEquals(listOf("cat"), command.words)
        assertEquals(
            listOf(
                ShellRedirection("in file", ShellRedirectionKind.Input),
                ShellRedirection("errors", ShellRedirectionKind.Output, 2),
                ShellRedirection("output", ShellRedirectionKind.Append),
            ),
            command.redirects,
        )
    }

    @Test
    fun `quoted spaced and embedded digits are arguments rather than descriptors`() {
        val command = parsed("echo '2'>a 3 >b file4>c", ShellDialect.Posix).onlyCommand()
        assertEquals(listOf("echo", "2", "3", "file4"), command.words)
        assertEquals(listOf("a", "b", "c"), command.redirects.map { it.target })
        assertEquals(listOf(null, null, null), command.redirects.map { it.fileDescriptor })
    }

    @Test
    fun `unsupported suffix invalidates the entire input`() {
        val suffixes = listOf(
            "&& rm fixture",
            "|| rm fixture",
            "& rm fixture",
            "; (rm fixture)",
            "; if true; then rm fixture; fi",
            "; X=value ls",
            "; cat <<EOF",
            "; echo \$(rm fixture)",
        )
        for (dialect in listOf(ShellDialect.Posix, ShellDialect.Bash)) {
            suffixes.forEach { unknown("ls core $it", dialect) }
        }
    }

    @Test
    fun `expansions and control grammar are never treated as literal arguments`() {
        val inputs = listOf(
            "echo \$HOME", "echo \"\$HOME\"", "echo `whoami`", "ls *", "ls a?", "ls [ab]", "ls ~/core",
            "echo {a,b}", "function f { ls; }", "! ls", "time ls", "cat <(ls)", "echo ok # comment",
            "echo ok\r\nrm fixture", "echo \u0000", "echo !last", "echo \"!last\"",
        )
        inputs.forEach { unknown(it, ShellDialect.Bash) }
    }

    @Test
    fun `malformed pipelines quotes redirections and empty statements are unknown`() {
        val inputs = listOf(
            "", " \t", "| ls", "ls |", "ls | | cat", "ls ;; cat", "; ls", "ls; ;cat", "ls;\ncat",
            "cat >", "cat > >out", "cat > ''", "cat 2>&1", "cat <>file", "cat >|file", "cat >>>file",
            "echo 'open", "echo \"open", "echo trailing\\", ">file", "'' arg", "echo 999999999999999>out",
        )
        inputs.forEach { unknown(it, ShellDialect.Posix) }
    }

    @Test
    fun `one trailing posix separator is accepted`() {
        assertEquals(listOf("ls", "core"), parsed("ls core;", ShellDialect.Posix).onlyCommand().words)
        assertEquals(listOf("ls", "core"), parsed("ls core\n", ShellDialect.Posix).onlyCommand().words)
    }

    @Test
    fun `cmd quotes escapes and separators follow the cmd dialect`() {
        val command = parsed("DiR \"a|b; c\" a^ b a^|b 'x y' a;b", ShellDialect.Cmd).onlyCommand()
        assertEquals(listOf("DiR", "a|b; c", "a b", "a|b", "'x", "y'", "a;b"), command.words)
    }

    @Test
    fun `cmd pipelines retain dangerous suffixes and redirections`() {
        val result = parsed("dir core | del fixture 2>errors >>output", ShellDialect.Cmd)
        assertEquals(listOf("dir", "core"), result.statements.single().commands[0].words)
        val suffix = result.statements.single().commands[1]
        assertEquals(listOf("del", "fixture"), suffix.words)
        assertEquals(
            listOf(
                ShellRedirection("errors", ShellRedirectionKind.Output, 2),
                ShellRedirection("output", ShellRedirectionKind.Append),
            ),
            suffix.redirects,
        )
    }

    @Test
    fun `cmd dynamic compound and ambiguous arguments are unknown`() {
        val inputs = listOf(
            "dir & del fixture", "dir && del fixture", "dir || del fixture", "dir (core)",
            "echo %PATH%", "echo \"%PATH%\"", "echo !PATH!", "echo ^%PATH^%", "dir\ndel fixture",
            "echo ^", "echo ^\"foo^\"", "echo a\"b c\"", "echo \"a\"b", "echo \"a\\\"b\"",
            "echo \"a\"\"b\"", "dir 22>out", "dir 2>&1", "dir |", "dir >", "echo \"open",
        )
        inputs.forEach { unknown(it, ShellDialect.Cmd) }
    }

    @Test
    fun `cmd backslashes and single quotes are not posix escapes`() {
        assertEquals(
            listOf("type", "C:\\work\\file", "'literal'", "\$HOME"),
            parsed("type C:\\work\\file 'literal' \$HOME", ShellDialect.Cmd).onlyCommand().words,
        )
        assertEquals(
            listOf("echo", "^&"),
            parsed("echo \"^&\"", ShellDialect.Cmd).onlyCommand().words,
        )
    }

    @Test
    fun `aliases casing and nested interpreter names remain uninterpreted`() {
        assertEquals(listOf("LS", "core"), parsed("LS core", ShellDialect.Bash).onlyCommand().words)
        assertEquals(listOf("ls", "core"), parsed("ls core", ShellDialect.Bash).onlyCommand().words)
        assertEquals(
            listOf("sh", "-c", "rm fixture"),
            parsed("sh -c 'rm fixture'", ShellDialect.Posix).onlyCommand().words,
        )
    }

    @Test
    fun `powershell requires its own structural frontend`() {
        unknown("Get-ChildItem | Select-Object Name", ShellDialect.PowerShell)
    }

    @Test
    fun `oversized input cannot produce partial evidence`() {
        unknown("echo " + "x".repeat(65_536), ShellDialect.Posix)
    }

    @Test
    fun `cmd pipeline reparsing never drops caret escaped operators`() {
        unknown("echo ^& del fixture | more", ShellDialect.Cmd)
        unknown("dir | echo ^& del fixture", ShellDialect.Cmd)
    }

    @Test
    fun `many continuations do not recurse and retain the final statement`() {
        assertEquals(
            listOf("ls", "core"),
            parsed("\\\n ".repeat(10_000) + "ls core", ShellDialect.Posix).onlyCommand().words,
        )
    }

    private fun parsed(input: String, dialect: ShellDialect): ShellParseResult.Parsed =
        assertIs<ShellParseResult.Parsed>(parseShellStructure(input, dialect), input)

    private fun unknown(input: String, dialect: ShellDialect) {
        assertIs<ShellParseResult.Unknown>(parseShellStructure(input, dialect), input)
    }

    private fun ShellParseResult.Parsed.onlyCommand(): ShellCommand = statements.single().commands.single()
}
