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
        val environment = System.getenv().toMutableMap().apply {
            put("HOME", home.toString())
            put("PATH", helperDirectory.toString() + File.pathSeparator + get("PATH"))
        }
        git("config", "--add", "credential.helper", "")
        git("config", "--add", "credential.helper", "osxkeychain")
        val standard = assertNotNull(evidence.capture(directory.toString(), "git push", environment))
        assertEquals("false", standard.text("customTransportOrPushOptions"))
        assertEquals("osxkeychain", standard["standardCredentialHelpers"]?.jsonArray?.single()?.jsonPrimitive?.content)
        assertNotNull(standard["standardCredentialHelperExecutables"]?.jsonObject?.get("osxkeychain"))
        val secret = "credential-helper-secret"
        git("config", "--add", "credential.helper", "!touch $secret")
        val custom = assertNotNull(evidence.capture(directory.toString(), "git push", environment))
        assertEquals("true", custom.text("customTransportOrPushOptions"))
        assertFalse(secret in custom.toString())
        assertFalse(Files.exists(directory.resolve(secret)))
        git("config", "--add", "credential.helper", "")
        git("config", "--add", "credential.helper", "cache")
        val reset = assertNotNull(evidence.capture(directory.toString(), "git push", environment))
        assertEquals("false", reset.text("customTransportOrPushOptions"))
        git("config", "credential.https://github.com.helper", "!echo $secret")
        val scoped = assertNotNull(evidence.capture(directory.toString(), "git push", environment))
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
            val environment = System.getenv().toMutableMap().apply {
                put("PATH", directory.toString() + File.pathSeparator + get("PATH"))
            }
            val captured = assertNotNull(evidence.capture(directory.toString(), "git push", environment))
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
    fun `commands outside the simple forms receive no evidence`() = runTest {
        for (command in listOf(
            "git push --force origin feature", "git push --delete origin feature", "git push origin :feature",
            "git push --tags", "git push --mirror", "git push other feature", "git push https://example.com/a feature",
            "git push origin feature; true", "git push origin feature && true", "git -C elsewhere push",
        )) {
            assertNull(evidence.capture(directory.toString(), command, null), command)
        }
        assertEquals("unavailable", capture().text("status"))
    }

    @Test
    fun `supplied child environment controls config and has a stable private digest`() = runTest {
        repository()
        val home = Files.createDirectory(directory.resolve("child-home"))
        git("config", "--file", home.resolve(".gitconfig").toString(), "push.followTags", "true")
        val environment = System.getenv().toMutableMap().apply { put("HOME", home.toString()) }
        val child = assertNotNull(evidence.capture(directory.toString(), "git push", environment))
        assertEquals("captured", child.text("status"))
        assertEquals(
            "true",
            child["config"]?.jsonObject?.get("push.followtags")?.jsonArray?.last()?.jsonPrimitive?.content,
        )
        assertEquals(child, evidence.capture(directory.toString(), "git push", environment))
        environment["HEARTBEAT_PRIVATE_TEST"] = "child-value-never-disclose"
        val changed = assertNotNull(evidence.capture(directory.toString(), "git push", environment))
        assertNotEquals(child["executionEnvironmentDigest"], changed["executionEnvironmentDigest"])
        assertFalse("child-value-never-disclose" in changed.toString())
        environment["GIT_CONFIG_COUNT"] = "override-value-never-disclose"
        val refused = assertNotNull(evidence.capture(directory.toString(), "git push", environment))
        assertEquals("unavailable", refused.text("status"))
        assertEquals("true", refused.text("environmentOverrides"))
        assertFalse("override-value-never-disclose" in refused.toString())
    }

    private suspend fun capture(command: String = "git push"): JsonObject =
        assertNotNull(evidence.capture(directory.toString(), command, null))

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

    private fun git(vararg arguments: String): String {
        val child = ProcessBuilder(listOf("git", "--no-pager") + arguments).directory(directory.toFile())
            .redirectErrorStream(true).start()
        try {
            child.outputStream.close()
            assertTrue(child.waitFor(5, TimeUnit.SECONDS), "Local git setup timed out")
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
