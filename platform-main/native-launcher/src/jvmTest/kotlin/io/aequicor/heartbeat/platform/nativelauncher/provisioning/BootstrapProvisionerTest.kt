package io.aequicor.heartbeat.platform.nativelauncher.provisioning

import io.aequicor.heartbeat.platform.nativelauncher.bootstrap.BootstrapArtifact
import io.aequicor.heartbeat.platform.nativelauncher.bootstrap.BootstrapManifest
import io.aequicor.heartbeat.platform.nativelauncher.bootstrap.BootstrapRuntime
import io.aequicor.heartbeat.platform.nativelauncher.bootstrap.BootstrapSeed
import io.aequicor.heartbeat.platform.nativelauncher.bootstrap.sha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Поток provisioning: первый запуск, resume, повреждения, отмена, offline, lock, свободное место. */
class BootstrapProvisionerTest {
    private val fs = FileSystem.SYSTEM
    private val temp = Files.createTempDirectory("provision-test").toString().toPath()
    private val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val json = Json { ignoreUnknownKeys = true }

    private inner class FakeTransport : HttpTransport {
        val responses = mutableMapOf<String, ByteArray>()
        var honorRange = true
        var downloadDelayMs = 0L
        val downloads = mutableListOf<String>()
        var getBytesCalls = 0

        override suspend fun getBytes(url: String, limit: Long): HttpBytesResponse {
            getBytesCalls++
            return HttpBytesResponse(200, responses[url] ?: error("no response for $url"))
        }

        override suspend fun download(
            url: String,
            offset: Long,
            onStart: (isResumed: Boolean) -> Unit,
            onChunk: (ByteArray) -> Unit,
        ): HttpDownloadResult {
            downloads += "$url@$offset"
            if (downloadDelayMs > 0) delay(downloadDelayMs)
            val data = responses[url] ?: error("no response for $url")
            val isResumed = honorRange && offset > 0
            onStart(isResumed)
            val from = if (isResumed) offset.toInt() else 0
            data.copyOfRange(from, data.size).asList().chunked(7) { onChunk(it.toByteArray()) }
            return HttpDownloadResult(200, isResumed)
        }
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private inner class Fixture(os: String = "windows", minLauncherVersion: Int = 1) {
        val store = ImageStore(temp.resolve("root-${System.nanoTime()}"), fs)
        val runtime = zip(mapOf("runtime/bin/java.dll" to byteArrayOf(1, 2, 3, 4)))
        val platform = zip(mapOf("app/Heartbeat.cfg" to "[Application]".encodeToByteArray()))
        val seed = zip(mapOf("manifest.json" to "{\"train\":\"train-1\"}".encodeToByteArray()))
        val manifest = BootstrapManifest(
            version = "1.0.0",
            channel = "release",
            os = os,
            arch = "x64",
            minNativeLauncherVersion = minLauncherVersion,
            runtime = BootstrapRuntime(
                "jbr21-windows-x64",
                "$BASE/runtime.zip",
                runtime.size.toLong(),
                sha256Hex(runtime),
            ),
            platform = BootstrapArtifact("$BASE/platform.zip", platform.size.toLong(), sha256Hex(platform)),
            seed = BootstrapSeed("train-1", "$BASE/seed.zip", seed.size.toLong(), sha256Hex(seed)),
        )
        val manifestBytes = json.encodeToString(BootstrapManifest.serializer(), manifest).encodeToByteArray()
        val signature = Signature.getInstance("Ed25519").apply {
            initSign(keyPair.private)
            update(manifestBytes)
        }.sign()

        fun transport() = FakeTransport().also { transport ->
            transport.responses["$BASE/manifest.json"] = manifestBytes
            transport.responses["$BASE/manifest.sig"] = signature
            transport.responses["$BASE/runtime.zip"] = runtime
            transport.responses["$BASE/platform.zip"] = platform
            transport.responses["$BASE/seed.zip"] = seed
        }

        fun provisioner(
            transport: HttpTransport,
            free: Long = Long.MAX_VALUE,
            expectedVersion: String? = null,
            expectedTarget: String? = null,
            expectedChannel: String? = null,
        ) = BootstrapProvisioner(
            store = store,
            transport = transport,
            manifestUrl = "$BASE/manifest.json",
            signatureUrl = "$BASE/manifest.sig",
            trustedKeys = listOf(keyPair.public.encoded),
            fs = fs,
            freeSpace = { free },
            expectedVersion = expectedVersion,
            expectedTarget = expectedTarget,
            expectedChannel = expectedChannel,
            lockPollMillis = 20,
            lockTimeoutMillis = 2000,
        )
    }

    @Test
    fun firstLaunchProvisionsAllArtifactsAndStartsOffline() {
        val fixture = Fixture()
        val transport = fixture.transport()
        val provisioner = fixture.provisioner(transport)
        val image = runBlocking { provisioner.provision() }
        assertTrue(fs.exists(image.directory.resolve("runtime/bin/java.dll")))
        assertTrue(fs.exists(image.directory.resolve("app/Heartbeat.cfg")))
        assertTrue(fs.exists(image.directory.resolve("app/resources/seed/manifest.json")))
        assertEquals(image.id, provisioner.currentOfflineImage()?.id)
        assertEquals("1.0.0", provisioner.currentOfflineImage()?.platformVersion)
        assertEquals(3, transport.downloads.size)
        assertTrue(transport.downloads.all { it.endsWith("@0") })
        val callsBefore = transport.getBytesCalls
        assertNotNull(fixture.provisioner(FakeTransport()).currentOfflineImage())
        assertEquals(callsBefore, transport.getBytesCalls, "offline restart does not touch the network")
    }

    @Test
    fun unixSeedUsesPlatformSpecificResourcesDirectory() {
        for (os in listOf("macos", "linux")) {
            val fixture = Fixture(os)
            val image = runBlocking { fixture.provisioner(fixture.transport()).provision() }
            val seed = BootstrapLayout.forPlatform(os, "release").seed
            assertTrue(fs.exists(image.directory.resolve(seed).resolve("manifest.json")))
            assertTrue(!fs.exists(image.directory.resolve("app/resources/seed/manifest.json")))
        }
    }

    @Test
    fun incompatibleSignedManifestIsRejectedBeforeArtifactDownloads() {
        val fixture = Fixture()
        for (provisioner in listOf<(FakeTransport) -> BootstrapProvisioner>(
            { fixture.provisioner(it, expectedVersion = "2.0.0") },
            { fixture.provisioner(it, expectedTarget = "windows-arm64") },
            { fixture.provisioner(it, expectedChannel = "beta") },
        )) {
            val transport = fixture.transport()
            assertFailsWith<IllegalArgumentException> { runBlocking { provisioner(transport).provision() } }
            assertTrue(transport.downloads.isEmpty())
            assertNull(fixture.store.currentImage())
        }
        val newer = Fixture(minLauncherVersion = 2)
        val transport = newer.transport()
        assertFailsWith<IllegalArgumentException> { runBlocking { newer.provisioner(transport).provision() } }
        assertTrue(transport.downloads.isEmpty())
    }

    @Test
    fun resumeAppendsToPartialDownload() {
        val fixture = Fixture()
        val transport = fixture.transport()
        val partial = fixture.runtime.copyOf(3)
        fs.createDirectories(fixture.store.downloadsDir)
        fs.write(fixture.store.downloadsDir.resolve("runtime.part")) { write(partial, 0, partial.size) }
        runBlocking { fixture.provisioner(transport).provision() }
        assertTrue(transport.downloads.any { it.endsWith("runtime.zip@3") })
        assertNotNull(fixture.provisioner(transport).currentOfflineImage())
    }

    @Test
    fun ignoredRangeRestartsFromZero() {
        val fixture = Fixture()
        val transport = fixture.transport().also { it.honorRange = false }
        fs.createDirectories(fixture.store.downloadsDir)
        fs.write(fixture.store.downloadsDir.resolve("runtime.part")) { write(fixture.runtime.copyOf(3), 0, 3) }
        runBlocking { fixture.provisioner(transport).provision() }
        assertNotNull(fixture.provisioner(transport).currentOfflineImage())
    }

    @Test
    fun corruptedDownloadIsRejectedAndRetryStartsFromZero() {
        val fixture = Fixture()
        val transport = fixture.transport()
        val tampered = fixture.runtime.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        transport.responses["$BASE/runtime.zip"] = tampered
        assertFailsWith<ProvisionException.CorruptedDownload> {
            runBlocking { fixture.provisioner(transport).provision() }
        }
        assertNull(fixture.provisioner(transport).currentOfflineImage())
        transport.responses["$BASE/runtime.zip"] = fixture.runtime
        runBlocking { fixture.provisioner(transport).provision() }
        assertTrue(transport.downloads.count { it.contains("runtime.zip") } >= 1)
        assertNotNull(fixture.provisioner(transport).currentOfflineImage())
    }

    @Test
    fun untrustedSignatureStopsBeforeAnyArtifactDownload() {
        val fixture = Fixture()
        val transport = fixture.transport()
        transport.responses["$BASE/manifest.sig"] = ByteArray(64)
        assertFailsWith<ProvisionException.BadSignature> {
            runBlocking { fixture.provisioner(transport).provision() }
        }
        assertTrue(transport.downloads.isEmpty())
    }

    @Test
    fun cancellationKeepsPartialFilesAndDoesNotActivate() {
        val fixture = Fixture()
        val transport = fixture.transport()
        var cancel = false
        val cancelling = object : HttpTransport by transport {
            override suspend fun download(
                url: String,
                offset: Long,
                onStart: (isResumed: Boolean) -> Unit,
                onChunk: (ByteArray) -> Unit,
            ): HttpDownloadResult = transport.download(url, offset, onStart) { chunk ->
                onChunk(chunk)
                cancel = true
            }
        }
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            runBlocking { fixture.provisioner(cancelling).provision(isCancelled = { cancel }) }
        }
        assertNull(fixture.provisioner(transport).currentOfflineImage())
        assertTrue((fs.metadata(fixture.store.downloadsDir.resolve("runtime.part")).size ?: 0) > 0)
    }

    @Test
    fun insufficientFreeSpaceStopsBeforeDownloads() {
        val fixture = Fixture()
        val transport = fixture.transport()
        assertFailsWith<ProvisionException.InsufficientSpace> {
            runBlocking { fixture.provisioner(transport, free = 0).provision() }
        }
        assertTrue(transport.downloads.isEmpty())
    }

    @Test
    fun concurrentInstancesProvisionOnlyOnce() {
        val fixture = Fixture()
        val transport = fixture.transport().also { it.downloadDelayMs = 50 }
        val first = fixture.provisioner(transport)
        val second = fixture.provisioner(transport)
        val (image1, image2) = runBlocking {
            val a = async(Dispatchers.Default) { first.provision() }
            val b = async(Dispatchers.Default) { second.provision() }
            a.await() to b.await()
        }
        assertEquals(image1.id, image2.id)
        assertEquals(1, transport.downloads.count { it.contains("runtime.zip") })
        assertEquals(1, transport.downloads.count { it.contains("platform.zip") })
        assertEquals(1, transport.downloads.count { it.contains("seed.zip") })
    }

    companion object {
        private const val BASE = "https://test"
    }
}
