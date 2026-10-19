package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopGitPushEvidenceTest {
    private val directory = Files.createTempDirectory("git-push-evidence").toRealPath()
    private val homeDirectory = Files.createDirectory(directory.resolve("test-home"))
    private val helperDirectory = Files.createTempDirectory("installed-git-helpers").toRealPath()
    private val dispatcher = Executors.newCachedThreadPool().asCoroutineDispatcher()
    private val dispatchers = object : DispatcherProvider {
        override val main = dispatcher
        override val default = dispatcher
        override val io = dispatcher
    }
    private val evidence = DesktopGitPushEvidence(dispatchers)

    @AfterTest
    fun cleanup() {
        dispatcher.close()
        directory.toFile().deleteRecursively()
        helperDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `ordinary origin forms capture stable branch head and effective destination`() = runTest {
        repository()
        for (command in listOf("git push", "git push origin feature", "git push -u origin feature")) {
            val first = capture(command)
            assertEquals("captured", first.text("status"), first.toString())
            assertEquals("feature", first.text("currentBranch"))
            assertEquals(git("rev-parse", "HEAD"), first.text("head"))
            assertEquals(directory.toString(), first.text("root"))
            assertEquals(
                "https://github.com/example/project.git",
                first["originPushUrls"]?.jsonArray?.single()?.jsonPrimitive?.content,
            )
            assertEquals(first, capture(command))
        }
    }

    @Test
    fun `other remote defaults tags mirrors and force refspecs remain visible`() = runTest {
        repository()
        val before = capture()
        val values = mapOf(
            "remote.pushDefault" to "other",
            "branch.feature.pushRemote" to "other",
            "branch.feature.remote" to "other",
            "branch.feature.merge" to "refs/heads/elsewhere",
            "push.followTags" to "true",
            "push.recurseSubmodules" to "on-demand",
            "submodule.recurse" to "true",
            "remote.origin.mirror" to "true",
            "remote.origin.push" to "+refs/heads/*:refs/heads/*",
        )
        for ((key, value) in values) git("config", key, value)
        val changed = capture()
        assertNotEquals(before, changed)
        val config = assertNotNull(changed["config"]).jsonObject
        for ((key, value) in values) {
            assertEquals(value, config[key.lowercase()]?.jsonArray?.last()?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `branch mismatch and head changes are explicit evidence`() = runTest {
        repository()
        val before = capture("git push origin other")
        assertEquals("other", before.text("requestedBranch"))
        assertEquals("feature", before.text("currentBranch"))
        git("commit", "--allow-empty", "-m", "second")
        assertNotEquals(before["head"], capture("git push origin other")["head"])
    }

    @Test
    fun `effective push URL follows local rewrite and multiple push URLs`() = runTest {
        repository()
        git("config", "url.https://github.com/rewritten/.pushInsteadOf", "https://github.com/example/")
        assertEquals(
            "https://github.com/rewritten/project.git",
            capture()["originPushUrls"]?.jsonArray?.single()?.jsonPrimitive?.content,
        )
        git("config", "--add", "remote.origin.pushurl", "https://github.com/one/project.git")
        git("config", "--add", "remote.origin.pushurl", "https://github.com/two/project.git")
        assertEquals(2, capture()["originPushUrls"]?.jsonArray?.size)
    }

    @Test
    fun `pre push hooks and custom transport are marked without reading their contents`() = runTest {
        repository()
        val marker = directory.resolve("hook-ran")
        val secret = "credential-not-for-review"
        Files.writeString(directory.resolve("hooks/pre-push"), "#!/bin/sh\ntouch '$marker'\n# $secret\n")
        git("config", "core.sshCommand", "custom-ssh --password $secret")
        val captured = capture()
        assertEquals("true", captured.text("prePushHookPresent"))
        assertEquals("true", captured.text("customTransportOrPushOptions"))
        assertFalse(secret in captured.toString())
        assertFalse(Files.exists(marker))
    }

    @Test
    fun `standard credential helpers are named but shell helpers remain private hazards`() = runTest {
        repository()
        val home = Files.createDirectory(directory.resolve("credential-home"))
        val suffix = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) ".exe" else ""
        for (helper in listOf("osxkeychain", "manager", "manager-core", "cache")) {
            val path = Files.writeString(helperDirectory.resolve("git-credential-$helper$suffix"), "never execute")
            assertTrue(path.toFile().setExecutable(true) || Files.isExecutable(path))
        }
        val environment = testEnvironment().toMutableMap().apply {
            put("HOME", home.toString())
            put("PATH", helperDirectory.toString() + File.pathSeparator + get("PATH"))
        }
        git("config", "--add", "credential.helper", "")
        val transportOnly = capture(environment = environment).text("customTransportOrPushOptions")
        git("config", "--add", "credential.helper", "osxkeychain")
        val standard = capture(environment = environment)
        assertEquals(transportOnly, standard.text("customTransportOrPushOptions"))
        assertEquals("osxkeychain", standard["standardCredentialHelpers"]?.jsonArray?.single()?.jsonPrimitive?.content)
        assertNotNull(standard["standardCredentialHelperExecutables"]?.jsonObject?.get("osxkeychain"))
        val secret = "credential-helper-secret"
        git("config", "--add", "credential.helper", "!touch $secret")
        val custom = capture(environment = environment)
        assertEquals("true", custom.text("customTransportOrPushOptions"))
        assertFalse(secret in custom.toString())
        assertFalse(Files.exists(directory.resolve(secret)))
        git("config", "--add", "credential.helper", "")
        val reset = capture(environment = environment)
        assertTrue(reset["standardCredentialHelpers"]!!.jsonArray.isEmpty())
        assertTrue(reset["standardCredentialHelperExecutables"]!!.jsonObject.isEmpty())
        assertEquals(transportOnly, reset.text("customTransportOrPushOptions"))
        git("config", "credential.https://github.com.helper", "!echo $secret")
        val scoped = capture(environment = environment)
        assertEquals("true", scoped.text("customTransportOrPushOptions"))
        assertFalse(secret in scoped.toString())
    }

    @Test
    fun `project credential helper shadowing is an unknown effect`() = runTest {
        repository()
        val suffix = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) ".exe" else ""
        val execPath = File(git("--exec-path"))
        val helper = listOf("manager-core", "manager", "osxkeychain", "cache").firstOrNull {
            !execPath.resolve("git-credential-$it$suffix").exists()
        }
        if (helper != null) {
            val path = Files.writeString(directory.resolve("git-credential-$helper$suffix"), "never execute")
            assertTrue(path.toFile().setExecutable(true) || Files.isExecutable(path))
            git("config", "--add", "credential.helper", "")
            git("config", "--add", "credential.helper", helper)
            val environment = testEnvironment().toMutableMap().apply {
                put("PATH", directory.toString() + File.pathSeparator + get("PATH"))
            }
            val captured = capture(environment = environment)
            assertEquals("true", captured.text("customTransportOrPushOptions"))
            assertNull(captured["standardCredentialHelperExecutables"]?.jsonObject?.get(helper))
        }
    }

    @Test
    fun `credentials query tokens custom transports and malformed config are not disclosed`() = runTest {
        repository()
        val secret = "never-disclose-this"
        for (url in listOf(
            "https://user:$secret@github.com/example/project.git",
            "https://$secret@github.com/example/project.git",
            "https://github.com/example/project.git?token=$secret",
            "ext::sh -c $secret",
            "git@github.com:example/project.git",
            "ssh://git@github.com/example/project.git",
        )) {
            git("config", "remote.origin.url", url)
            val captured = capture()
            assertEquals("unavailable", captured.text("status"))
            assertFalse(secret in captured.toString())
        }
        git("config", "remote.origin.url", "https://github.com/example/project.git")
        git("config", "branch.feature.remote", "https://user:$secret@github.com/private.git")
        assertEquals("unavailable", capture().text("status"))
        assertFalse(secret in capture().toString())
    }

    @Test
    fun `unsupported pushes never receive evidence for the push exception`() = runTest {
        for (command in listOf(
            "git push --force origin feature", "git push --delete origin feature", "git push origin :feature",
            "git push --tags", "git push --mirror", "git push other feature", "git push https://example.com/a feature",
            "git push origin feature; true", "git push origin feature && true", "git -C elsewhere push",
        )) {
            assertEquals("localGit", capture(command).text("kind"), command)
        }
        assertEquals("unavailable", capture().text("status"))
    }

    @Test
    fun `supplied child environment controls config and has a stable private digest`() = runTest {
        repository()
        val home = Files.createDirectory(directory.resolve("child-home"))
        git("config", "--file", home.resolve(".gitconfig").toString(), "push.followTags", "true")
        val environment = testEnvironment().toMutableMap().apply { put("HOME", home.toString()) }
        val child = capture(environment = environment)
        assertEquals("captured", child.text("status"))
        assertEquals(
            "true",
            child["config"]?.jsonObject?.get("push.followtags")?.jsonArray?.last()?.jsonPrimitive?.content,
        )
        assertEquals(child, capture(environment = environment))
        environment["HEARTBEAT_PRIVATE_TEST"] = "child-value-never-disclose"
        val changed = capture(environment = environment)
        assertNotEquals(child["executionEnvironmentDigest"], changed["executionEnvironmentDigest"])
        assertFalse("child-value-never-disclose" in changed.toString())
        environment["GIT_CONFIG_COUNT"] = "override-value-never-disclose"
        val refused = capture(environment = environment)
        assertEquals("unavailable", refused.text("status"))
        assertEquals("true", refused.text("environmentOverrides"))
        assertFalse("override-value-never-disclose" in refused.toString())
    }

    @Test
    fun `generic project evidence binds manifest scripts repository and safe GitHub routing`() = runTest {
        repository()
        val manifest = directory.resolve("package.json")
        Files.writeString(manifest, "{\"dependencies\":{\"declared\":\"1.0\"},\"scripts\":{\"postinstall\":\"true\"}}")
        val environment = testEnvironment().toMutableMap().apply {
            put("GH_REPO", "example/project")
            put("GH_HOST", "github.com")
        }
        val first = captureProject(environment)
        assertEquals("projectNetwork", first.text("kind"))
        assertEquals("example/project", first["environmentFacts"]?.jsonObject?.text("GH_REPO"))
        assertEquals("captured", first["repository"]?.jsonObject?.text("status"))
        assertNull(first["repository"]?.jsonObject?.get("requestedBranch"))
        assertEquals("not inspected", first["configurationCoverage"]?.jsonObject?.text("globalConfiguration"))
        Files.writeString(manifest, "{\"scripts\":{\"postinstall\":\"changed command\"}}")
        val changed = captureProject(environment)
        assertNotEquals(first["reviewFiles"], changed["reviewFiles"])
        environment["GH_REPO"] = "other/project"
        val other = captureProject(environment, "gh pr create")
        assertEquals("other/project", other["environmentFacts"]?.jsonObject?.text("GH_REPO"))
        environment["GH_REPO"] = "https://user:private-value@github.com/example/project"
        val redacted = captureProject(environment, "gh pr create")
        assertEquals("unavailable", redacted["environmentFacts"]?.jsonObject?.text("status"))
        assertFalse("private-value" in redacted.toString())
        environment["GH_REPO"] = "example/project"
        environment["GIT_HEARTBEAT_UNSAFE_OVERRIDE_PRESENT"] = "1"
        val unknown = captureProject(environment)
        assertEquals("unavailable", unknown["environmentFacts"]?.jsonObject?.text("status"))
    }

    @Test
    fun `XDG config discovery stays supported and effective Git settings are captured`() = runTest {
        repository()
        val configHome = Files.createDirectory(directory.resolve("xdg-config"))
        val config = Files.createDirectory(configHome.resolve("git")).resolve("config")
        git("config", "--file", config.toString(), "push.followTags", "true")
        val environment = testEnvironment() + ("XDG_CONFIG_HOME" to configHome.toString())
        val captured = capture(environment = environment)
        assertEquals("captured", captured.text("status"))
        assertEquals(
            "true",
            captured["config"]?.jsonObject?.get("push.followtags")?.jsonArray?.last()?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `runtime execution environment overrides are unavailable and disclose names only`() = runTest {
        repository()
        Files.writeString(directory.resolve("package.json"), "{\"dependencies\":{\"declared\":\"1.0\"}}")
        val names = listOf(
            "NODE_OPTIONS", "PYTHONPATH", "RUBYOPT", "JAVA_TOOL_OPTIONS", "CLASSPATH", "CUSTOM_WRAPPER",
            "CPATH", "RUST_LOG", "XPC_FLAGS",
        )
        for (name in names) {
            val environment = testEnvironment().toMutableMap().apply { put(name, "private-runtime-override-value") }
            val captured = captureProject(environment)
            val facts = captured["environmentFacts"]!!.jsonObject
            assertEquals("unavailable", facts.text("status"), name)
            assertTrue(facts["overrideNames"]!!.jsonArray.any { it.jsonPrimitive.content == name })
            assertFalse("private-runtime-override-value" in captured.toString())
            assertTrue(facts.text("coverage").orEmpty().contains("not proven absent"))
            assertEquals("unavailable", capture(environment = environment).text("status"), name)
        }
    }

    @Test
    fun `local Git captures hooks and index without requiring any remote or inspecting credentials`() = runTest {
        repository()
        git("remote", "remove", "origin")
        git("config", "credential.helper", "!echo private-credential-helper")
        val hook = directory.resolve("hooks/pre-commit")
        Files.writeString(hook, "#!/bin/sh\nprintf checked >> hook-events\n")
        assertTrue(hook.toFile().setExecutable(true) || Files.isExecutable(hook))
        val first = capture("git commit --no-edit")
        assertEquals("localGit", first.text("kind"))
        assertEquals("captured", first.text("status"))
        assertFalse("private-credential-helper" in first.toString())
        assertTrue("printf checked" in first.toString())
        assertFalse(Files.exists(directory.resolve("hook-events")))
        assertEquals(first, capture("git commit --no-edit"))
        Files.writeString(directory.resolve("change.txt"), "staged")
        git("add", "change.txt")
        assertNotEquals(first["metadata"], capture("git commit --no-edit")["metadata"])
        Files.writeString(hook, "#!/bin/sh\nprintf changed >> hook-events\n")
        assertNotEquals(first["hooks"], capture("git commit --no-edit")["hooks"])
    }

    @Test
    fun `compound local inspection does not inherit project network requirements`() = runTest {
        repository()
        git("remote", "remove", "origin")
        val command = "printf 'staged files\\n'; git diff --cached --name-only | sort; " +
            "git rev-parse --git-path MERGE_HEAD"
        val result = assertNotNull(
            evidence.capture(directory.toString(), command, testEnvironment(), listOf("package.json")),
        )
        assertEquals("localGit", result.text("kind"))
        assertEquals("captured", result.text("status"))
        assertEquals("incomplete", result["reviewFiles"]?.jsonObject?.text("status"))
    }

    @Test
    fun `local context supports an unborn branch and linked worktree Git directories`() = runTest {
        git("init", "--initial-branch=feature")
        assertEquals("captured", capture("git status").text("status"))
        assertEquals("", capture("git status").text("head"))
        git("config", "user.name", "Git evidence test")
        git("config", "user.email", "test@example.invalid")
        git("-c", "commit.gpgSign=false", "commit", "--allow-empty", "-m", "base")
        val linked = directory.resolve("linked")
        git("worktree", "add", "-b", "linked", linked.toString())
        val result = assertNotNull(evidence.capture(linked.toString(), "git status", testEnvironment(), emptyList()))
        assertEquals("captured", result.text("status"))
        assertEquals(linked.toString(), result.text("root"))
    }

    @Test
    fun `sensitive oversized or symlink hook contents are never disclosed`() = runTest {
        repository()
        val hook = directory.resolve("hooks/pre-commit")
        for (text in listOf(
            "#!/bin/sh\napi_key=never-disclose-this-value",
            "#!/bin/sh\nprintf 'sk-sp-H.artificial.segment-for-screening'",
            "#!/bin/sh\ncurl -H 'Authorization: Bearer artificial-review-secret' https://example.invalid",
            "#!/bin/sh\ncurl -H 'Authorization: Basic YXJ0aWZpY2lhbDpjcmVkZW50aWFs' https://example.invalid",
            "x".repeat(40_000),
        )) {
            Files.writeString(hook, text)
            assertTrue(hook.toFile().setExecutable(true) || Files.isExecutable(hook))
            val result = capture("git commit -m checked")
            assertEquals("unavailable", result.text("status"), result.toString())
            assertFalse("never-disclose-this-value" in result.toString())
            assertFalse("artificial" in result.toString())
            assertFalse("YXJ0aWZpY2lhbDpjcmVkZW50aWFs" in result.toString())
        }
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) {
            Files.delete(hook)
            val target = Files.writeString(helperDirectory.resolve("sensitive-hook"), "never-disclose-this-value")
            assertTrue(target.toFile().setExecutable(true))
            Files.createSymbolicLink(hook, target)
            val result = capture("git commit -m checked")
            assertEquals("unavailable", result.text("status"), result.toString())
            assertFalse("never-disclose-this-value" in result.toString())
            assertFalse("artificial" in result.toString())
            assertFalse("YXJ0aWZpY2lhbDpjcmVkZW50aWFs" in result.toString())
        }
    }

    @Test
    fun `local Git ignores transport proxies but refuses inherited execution overrides`() = runTest {
        repository()
        assertEquals(
            "captured",
            capture("git status", testEnvironment() + ("HTTPS_PROXY" to "http://proxy.invalid")).text("status"),
        )
        for (name in listOf(
            "GIT_CONFIG_COUNT",
            "GIT_DIR",
            "GIT_INDEX_FILE",
            "BASH_ENV",
            "LD_PRELOAD",
            "LD_LIBRARY_PATH",
        )) {
            val result = capture("git status", testEnvironment() + (name to "untrusted"))
            assertEquals("unavailable", result.text("status"), name)
        }
    }

    @Test
    fun `large staged merges have bounded evidence without listing every staged file`() = runTest {
        repository()
        val bulk = Files.createDirectory(directory.resolve("bulk"))
        repeat(6_367) { index -> Files.writeString(bulk.resolve("file-$index.txt"), "content") }
        git("-c", "core.autocrlf=false", "add", "bulk", timeoutSeconds = 30)
        val captured = capture("git commit --no-edit")
        assertEquals("captured", captured.text("status"))
        assertTrue(captured.toString().length < 16_384)
        assertFalse("file-6366.txt" in captured.toString())
    }

    @Test
    fun `global flags and absolute Git keep local context with explicit scope limits`() = runTest {
        repository()
        val original = capture("git status")
        val executable = original.text("gitExecutable")
        for (command in listOf(
            "git --no-pager commit -m checkpoint",
            "\"$executable\" --no-pager commit -m checkpoint",
            "git -C . commit -m checkpoint",
            "git --git-dir=other.git status",
        )) {
            val result = capture(command)
            assertEquals("localGit", result.text("kind"), command)
            assertEquals("captured", result.text("status"), command)
            assertEquals(original["metadata"], result["metadata"], command)
            assertEquals(original["hooks"], result["hooks"], command)
            assertTrue("separate evaluation" in result.text("scopeCoverage").orEmpty())
        }
    }

    @Test
    fun `local context adds network facts without hiding overrides for push or package managers`() = runTest {
        repository()
        Files.writeString(directory.resolve("package.json"), "{}")
        val projection = testEnvironment() + mapOf(
            "GIT_HEARTBEAT_UNSAFE_OVERRIDE_PRESENT" to "1",
            "HEARTBEAT_LOCAL_GIT_EXECUTION_OVERRIDE" to "0",
        )
        val local = capture("git status", projection)
        assertEquals("captured", local.text("status"))
        assertEquals("unavailable", local["environmentFacts"]?.jsonObject?.text("status"))
        assertEquals("unavailable", capture("git push", projection).text("status"))
        for (command in listOf("npm ci && printf git", "npm ci --prefix git-app")) {
            val bundle = evidence.capture(
                directory.toString(),
                command,
                projection,
                listOf("package.json"),
            )
            val result = assertNotNull(bundle)
            assertEquals("captured", result.text("status"))
            assertEquals("unavailable", result["environmentFacts"]?.jsonObject?.text("status"))
            val network = assertNotNull(result["projectNetwork"]).jsonObject
            assertEquals("unavailable", network["repository"]?.jsonObject?.text("status"))
            assertEquals("captured", network["reviewFiles"]?.jsonObject?.text("status"))
        }
        // A marker cannot hide an execution override still present in a full hosted environment.
        assertEquals("unavailable", capture("git status", projection + ("GIT_DIR" to "other")).text("status"))
        for (key in listOf("NODE_OPTIONS", "PYTHONPATH", "JAVA_TOOL_OPTIONS")) {
            assertEquals("unavailable", capture("git status", testEnvironment() + (key to "override")).text("status"))
        }
    }

    @Test
    fun `an unrelated git word outside a repository preserves selected package context`() = runTest {
        Files.writeString(directory.resolve("package.json"), "{}")
        val bundle = evidence.capture(
            directory.toString(),
            "npm ci && printf git",
            testEnvironment(),
            listOf("package.json"),
        )
        val result = assertNotNull(bundle)
        assertEquals("unavailable", result.text("status"))
        val network = assertNotNull(result["projectNetwork"]).jsonObject
        assertEquals("captured", network["reviewFiles"]?.jsonObject?.text("status"))
        assertEquals("captured", result["environmentFacts"]?.jsonObject?.text("status"))
    }

    private suspend fun capture(
        command: String = "git push",
        environment: Map<String, String> = testEnvironment(),
    ): JsonObject = assertNotNull(evidence.capture(directory.toString(), command, environment, emptyList()))

    private suspend fun captureProject(environment: Map<String, String>, command: String = "tool install"): JsonObject =
        assertNotNull(evidence.capture(directory.toString(), command, environment, listOf("package.json")))

    private fun testEnvironment(): Map<String, String> = System.getenv().filterKeys {
        it.uppercase() in setOf("PATH", "PATHEXT", "SYSTEMROOT", "WINDIR", "TMPDIR", "TMP", "TEMP")
    } + mapOf("HOME" to homeDirectory.toString(), "USERPROFILE" to homeDirectory.toString())

    private fun repository() {
        git("init", "--initial-branch=feature")
        git("config", "user.name", "Git evidence test")
        git("config", "user.email", "test@example.invalid")
        git("config", "commit.gpgSign", "false")
        Files.createDirectory(directory.resolve("hooks"))
        git("config", "core.hooksPath", directory.resolve("hooks").toString())
        git("commit", "--allow-empty", "-m", "initial")
        git("remote", "add", "origin", "https://github.com/example/project.git")
    }

    private fun git(vararg arguments: String, timeoutSeconds: Long = 5): String {
        val child = ProcessBuilder(listOf("git", "--no-pager") + arguments).directory(directory.toFile())
            .redirectErrorStream(true).apply {
                environment().clear()
                environment().putAll(testEnvironment())
            }.start()
        try {
            child.outputStream.close()
            assertTrue(child.waitFor(timeoutSeconds, TimeUnit.SECONDS), "Local git setup timed out")
            val output = child.inputStream.bufferedReader().readText().trim()
            assertEquals(0, child.exitValue(), output)
            return output
        } finally {
            if (child.isAlive) child.destroyForcibly()
            child.inputStream.close()
            child.errorStream.close()
            child.outputStream.close()
        }
    }
}

private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.content
