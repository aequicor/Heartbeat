package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginCode
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LoginPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LoginSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClaudeSignInTest {
    // The fake CLI is a real process: its pipes and deadlines need a real clock.
    private val nativeDispatcher = Executors.newCachedThreadPool().asCoroutineDispatcher()
    private val directory = Files.createTempDirectory("claude-sign-in")
    private val program = directory.resolve("FakeClaude.java").also { Files.writeString(it, FAKE_CLAUDE) }
    private val state = directory.resolve("logged-in")
    private val launch = LaunchContext(LaunchSettings(executable = "/opt/claude/claude", homeDirectory = "/cfg"))
    private val prompts = CopyOnWriteArrayList<LoginPrompt>()
    private val code = CompletableDeferred<LoginCode>()
    private val session = object : LoginSession {
        override fun prompt(prompt: LoginPrompt) {
            prompts += prompt
            if (prompt is LoginPrompt.PasteCode) answer?.let { code.complete(LoginCode(it)) }
        }

        override suspend fun awaitCode(): LoginCode = code.await()
    }
    private var answer: String? = null
    private var environment: Map<String, String> = emptyMap()

    @AfterTest
    fun cleanUp() {
        nativeDispatcher.close()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `a failed logout or unchanged account is reported as rejection`() = runTest {
        Files.writeString(state, "x")
        assertLogin(LoginFailureReason.Rejected) { signIn("logout-failed").logout(launch) }
        assertTrue(Files.exists(state))
        assertLogin(LoginFailureReason.Rejected) { signIn("logout-unchanged").logout(launch) }
        assertTrue(Files.exists(state))
    }

    @Test
    fun `a pasted code signs in to the claude ai account`() = runTest {
        answer = "abc#def"

        val state = signIn("paste").login(launch, session)

        assertEquals(LoginState.SignedIn("me@example.com"), state)
        assertEquals(listOf(LoginPrompt.OpenUrl(URL), LoginPrompt.PasteCode(URL)), prompts.toList())
        assertEquals("/cfg", environment["CLAUDE_CONFIG_DIR"])
    }

    @Test
    fun `a sign-in the browser completes needs no code`() = runTest {
        assertEquals(LoginState.SignedIn("me@example.com"), signIn("callback").login(launch, session))
        assertFalse(code.isCompleted)
    }

    @Test
    fun `a wrong code or a page outside Anthropic fails`() = runTest {
        answer = "nope"
        assertLogin(LoginFailureReason.Rejected) { signIn("paste").login(launch, session) }

        prompts.clear()
        assertLogin(LoginFailureReason.UntrustedUrl) { signIn("untrusted").login(launch, session) }
        assertEquals(emptyList(), prompts.toList())
    }

    @Test
    fun `a CLI that signs in only from a terminal answers with the command to run there`() = runTest {
        val error = assertFailsWith<ManagementException> { signIn("tty").login(launch, session) }

        assertEquals(
            ManagementFailure.Login(
                LoginFailureReason.RequiresTerminal,
                "CLAUDE_CONFIG_DIR='/cfg' '/opt/claude/claude' auth login",
            ),
            error.failure,
        )
    }

    @Test
    fun `status and sign-out read and clear the login`() = runTest {
        val signIn = signIn("paste")
        assertEquals(LoginState.SignedOut, signIn.status(launch))
        Files.writeString(state, "x")
        assertEquals(LoginState.SignedIn("me@example.com"), signIn.status(launch))

        assertEquals(LoginState.SignedOut, signIn.logout(launch))
        assertFalse(Files.exists(state))
    }

    @Test
    fun `terminal commands quote paths for the shell of the host`() {
        val startup = ClaudeStartup("/Apps/it's/claude", InstallSource.Custom, isRunnable = true)
        assertEquals("'/Apps/it'\\''s/claude' auth login", claudeTerminalCommand(startup, isWindows = false))

        val windows = ClaudeStartup("C:\\it's\\claude.exe", InstallSource.Managed, true, configDirectory = "C:\\cfg")
        val command = claudeTerminalCommand(windows, isWindows = true)
        assertEquals("\$env:CLAUDE_CONFIG_DIR='C:\\cfg'; & 'C:\\it''s\\claude.exe' auth login", command)
        assertTrue(command.endsWith("auth login"))
    }

    private fun TestScope.signIn(mode: String): ClaudeSignIn {
        val test = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val main = test
            override val default = test
            override val io = nativeDispatcher
        }
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        return ClaudeSignIn(
            dispatchers,
            isWindows = false,
            starter = { command, _, environment ->
                this@ClaudeSignInTest.environment = environment
                ProcessBuilder(listOf(java, program.toString(), state.toString(), mode) + command.drop(1))
                    .redirectErrorStream(true)
                    .start()
            },
        )
    }

    private suspend fun assertLogin(reason: LoginFailureReason, block: suspend () -> Unit) {
        val error = assertFailsWith<ManagementException> { block() }
        assertEquals(ManagementFailure.Login(reason), error.failure)
    }
}

private const val URL = "https://claude.com/cai/oauth/authorize?code=true&state=s"

/** `claude auth …` as the sign-in sees it: the state file stands for the stored login. */
private const val FAKE_CLAUDE = """
import java.nio.file.*;

class FakeClaude {
    static final java.io.PrintStream out =
        new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true);
    static final java.io.PrintStream err =
        new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), true);

    public static void main(String[] args) throws Exception {
        Path state = Path.of(args[0]);
        String command = args[2] + " " + args[3];
        if (command.equals("auth status")) {
            out.println("warning: settings ignored");
            if (Files.exists(state)) {
                out.println("{\"loggedIn\": true, \"authMethod\": \"claude.ai\", \"email\": \"me@example.com\"}");
                System.exit(0);
            }
            out.println("{\"loggedIn\": false, \"authMethod\": \"none\"}");
            System.exit(1);
        } else if (command.equals("auth logout")) {
            if (args[1].equals("logout-failed")) System.exit(2);
            if (args[1].equals("logout-unchanged")) System.exit(0);
            Files.deleteIfExists(state);
            out.println("Successfully logged out");
        } else {
            login(state, args[1]);
        }
    }

    static void login(Path state, String mode) throws Exception {
        if (mode.equals("tty")) {
            err.println("Error: Raw mode is not supported on the current process.stdin");
            System.exit(1);
        }
        if (mode.equals("untrusted")) {
            out.println("Visit https://claude.ai.evil.example/oauth/authorize?x=1");
            out.flush();
            Thread.sleep(60_000);
        }
        out.println("Opening browser to sign in...");
        out.println("\u001B[1m$URL\u001B[0m");
        out.print("Paste code here if prompted > ");
        out.flush();
        if (mode.equals("paste")) {
            var line = new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();
            if (!"abc#def".equals(line)) {
                out.println("Invalid code");
                System.exit(1);
            }
        } else {
            Thread.sleep(200);
        }
        Files.writeString(state, "x");
        out.println();
        out.println("Login successful. Press Enter to continue");
        out.flush();
        System.in.read();
    }
}
"""
