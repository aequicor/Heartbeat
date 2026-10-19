package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CommandReviewFilesTest {
    private val directory = Files.createTempDirectory("command-review-files").toRealPath()
    private val outside = Files.createTempDirectory("command-review-outside").toRealPath()

    @AfterTest
    fun cleanup() {
        directory.toFile().deleteRecursively()
        outside.toFile().deleteRecursively()
    }

    @Test
    fun `ordinary selected text is complete ordered deduplicated and recaptured`() = runTest {
        Files.writeString(directory.resolve("manifest.json"), "{\"registry\":\"https://registry.example.com\"}")
        Files.writeString(directory.resolve("install.sh"), "echo local build\n")
        val first = captureCommandReviewFiles(directory, listOf("manifest.json", "install.sh", "./manifest.json"))
        assertEquals("captured", first["status"]?.jsonPrimitive?.content)
        val records = first["files"]!!.jsonArray
        assertEquals(2, records.size)
        assertEquals("install.sh", records[0].jsonObject["path"]?.jsonPrimitive?.content)
        assertEquals("echo local build\n", records[0].jsonObject["content"]?.jsonPrimitive?.content)
        assertEquals(first, captureCommandReviewFiles(directory, listOf("install.sh", "manifest.json")))
        Files.writeString(directory.resolve("manifest.json"), "{\"registry\":\"https://other.example.com\"}")
        assertNotEquals(first, captureCommandReviewFiles(directory, listOf("install.sh", "manifest.json")))
    }

    @Test
    fun `sensitive paths and credential shaped text never leave the collector`() = runTest {
        val secret = "secret-value-never-forward"
        for (name in listOf(".env", ".env.production", ".npmrc", ".pypirc", "auth.json", "private.pem")) {
            Files.writeString(directory.resolve(name), secret)
            val captured = captureCommandReviewFiles(directory, listOf(name))
            assertEquals("incomplete", captured["status"]?.jsonPrimitive?.content)
            assertFalse(secret in captured.toString())
            assertTrue(captured["files"]!!.jsonArray.isEmpty())
        }
        for (text in listOf(
            "https://user:$secret@registry.example.com",
            "https://registry.example.com?token=$secret",
            "ghp_" + "a".repeat(36),
            "-----BEGIN OPENSSH PRIVATE KEY-----\n$secret",
        )) {
            Files.writeString(directory.resolve("manifest.json"), text)
            val captured = captureCommandReviewFiles(directory, listOf("manifest.json"))
            assertEquals("incomplete", captured["status"]?.jsonPrimitive?.content)
            assertFalse(text in captured.toString())
        }
    }

    @Test
    fun `plain package configuration and source credential assignments are redacted before review`() = runTest {
        val secret = "plain-secret-that-must-stay-local"
        Files.createDirectory(directory.resolve(".cargo"))
        val files = mapOf(
            ".cargo/credentials.toml" to "[registry]\nuser = '$secret'",
            "NuGet.Config" to "<add key=\"ClearTextPassword\" value=\"$secret\" />",
            "gradle.properties" to "signing.password=$secret",
            "config.json" to "{\"apiKey\":\"$secret\"}",
            "settings.py" to "PASSWORD = '$secret'",
            "settings.toml" to "access_token = '$secret'",
        )
        for ((name, content) in files) {
            Files.writeString(directory.resolve(name), content)
            val captured = captureCommandReviewFiles(directory, listOf(name))
            assertEquals("incomplete", captured["status"]?.jsonPrimitive?.content, name)
            assertTrue(captured["files"]!!.jsonArray.isEmpty())
            assertFalse(secret in captured.toString())
        }
    }

    @Test
    fun `missing metadata traversal absolute and escaping links are incomplete`() = runTest {
        val external = Files.writeString(outside.resolve("secret.txt"), "not project evidence")
        Files.createDirectory(directory.resolve(".git"))
        Files.writeString(directory.resolve(".git/config"), "private config")
        for (path in listOf("missing", ".git/config", "../outside", external.toString())) {
            val captured = captureCommandReviewFiles(directory, listOf(path))
            assertEquals("incomplete", captured["status"]?.jsonPrimitive?.content)
            assertTrue(captured["files"]!!.jsonArray.isEmpty())
        }
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) {
            Files.createSymbolicLink(directory.resolve("linked.txt"), external)
            assertEquals(
                "incomplete",
                captureCommandReviewFiles(directory, listOf("linked.txt"))["status"]?.jsonPrimitive?.content,
            )
        }
    }

    @Test
    fun `maximum sized plain tokens remain complete and long credential URLs stay private`() = runTest {
        val path = directory.resolve("source.txt")
        for (text in listOf("x".repeat(65_536), "1+x-".repeat(16_384))) {
            Files.writeString(path, text)
            val captured = captureCommandReviewFiles(directory, listOf("source.txt"))
            assertEquals("captured", captured["status"]?.jsonPrimitive?.content)
            assertEquals(text, captured["files"]!!.jsonArray.single().jsonObject["content"]?.jsonPrimitive?.content)
        }
        for (scheme in listOf("x".repeat(65_000), "1+x-".repeat(16_250))) {
            val text = "$scheme://user:private-value@example.com"
            Files.writeString(path, text)
            val captured = captureCommandReviewFiles(directory, listOf("source.txt"))
            assertEquals("incomplete", captured["status"]?.jsonPrimitive?.content)
            assertTrue(captured["files"]!!.jsonArray.isEmpty())
            assertFalse("private-value" in captured.toString())
        }
    }

    @Test
    fun `binary oversized total budget and file count failures are never truncated evidence`() = runTest {
        Files.write(directory.resolve("binary"), byteArrayOf(1, 0, 2))
        Files.write(directory.resolve("invalid-utf8"), byteArrayOf(-128))
        Files.writeString(directory.resolve("oversized"), "x".repeat(65_537))
        for (path in listOf("binary", "invalid-utf8", "oversized")) {
            assertEquals(
                "incomplete",
                captureCommandReviewFiles(directory, listOf(path))["status"]?.jsonPrimitive?.content,
            )
        }
        val names = (1..3).map { "large-$it" }
        names.forEach { Files.writeString(directory.resolve(it), "x".repeat(65_536)) }
        val total = captureCommandReviewFiles(directory, names)
        assertEquals("incomplete", total["status"]?.jsonPrimitive?.content)
        assertTrue(total["files"]!!.jsonArray.isEmpty())
        val count = captureCommandReviewFiles(directory, (1..17).map { "file-$it" })
        assertEquals("incomplete", count["status"]?.jsonPrimitive?.content)
    }
}
