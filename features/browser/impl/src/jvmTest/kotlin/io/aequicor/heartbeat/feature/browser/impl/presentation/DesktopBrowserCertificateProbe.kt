package io.aequicor.heartbeat.feature.browser.impl.presentation

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.awt.BorderLayout
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.LinkedBlockingQueue
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Local, self-signed TLS fixture; no external service, trust-store modification or certificate bypass flag. */
internal class DesktopBrowserCertificateProbe(
    private val createEngine: (
        LinkedBlockingQueue<BrowserViewState>,
        DesktopCertificateExceptions,
    ) -> DesktopBrowserEngine,
    private val createFrame: () -> JFrame,
    private val awaitState: (LinkedBlockingQueue<BrowserViewState>, (BrowserViewState) -> Boolean) -> BrowserViewState,
) {
    fun verify() {
        val directory = Files.createTempDirectory("heartbeat-test-tls")
        val server = tlsServer(directory)
        val states = LinkedBlockingQueue<BrowserViewState>()
        val exceptions = DesktopCertificateExceptions()
        val url = "https://localhost:${server.address.port}/"
        var engine: DesktopBrowserEngine? = null
        var frame: JFrame? = null
        try {
            SwingUtilities.invokeAndWait {
                engine = createEngine(states, exceptions)
                frame = createFrame().apply {
                    add(assertNotNull(engine).component, BorderLayout.CENTER)
                    validate()
                }
                assertNotNull(engine).start(url)
            }
            val failure = awaitState(states) { it.failure?.isCertificate == true && !it.isLoading }
            assertEquals("ERR_CERT_AUTHORITY_INVALID", failure.failure?.code)
            val runtime = assertNotNull(engine)
            // The terminal native load callback follows the error-document commit.
            awaitState(states) { !it.isLoading && it.failure != null }
            runBlocking {
                val diagnostic = runtime.devTools("Page.getFrameTree", JsonObject(emptyMap()))
                val interstitial = DesktopBrowserInterstitial(runtime::devTools)
                assertTrue(interstitial.isProceedAllowed(url), diagnostic)
                val isAccepted = interstitial.proceed(url) {
                    // Mirror the user action: label before Chromium can reuse the accepted certificate.
                    exceptions.remember(url)
                    true
                }
                assertTrue(isAccepted)
            }
            val loaded = awaitState(states) { it.title == "accepted-local-tls" && !it.isLoading }
            assertEquals(null, loaded.failure)
            assertEquals(
                io.aequicor.heartbeat.feature.browser.api.BrowserSecurity.CertificateException,
                loaded.security,
            )
        } finally {
            SwingUtilities.invokeAndWait {
                engine?.release()
                frame?.dispose()
            }
            runBlocking { engine?.awaitClosed() }
            server.stop(0)
            Files.deleteIfExists(directory.resolve("test.p12"))
            Files.deleteIfExists(directory)
        }
    }

    private fun tlsServer(directory: Path): HttpsServer {
        val store = directory.resolve("test.p12")
        val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
        val process = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "2",
            "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", PASSWORD,
            "-keypass", PASSWORD, "-noprompt",
        ).redirectErrorStream(true).start()
        val diagnostic = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), diagnostic)
        val keys = KeyStore.getInstance("PKCS12").apply {
            Files.newInputStream(store).use { load(it, PASSWORD.toCharArray()) }
        }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keys, PASSWORD.toCharArray())
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        return HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(ssl)
            createContext("/") { exchange ->
                val body = "<title>accepted-local-tls</title>Local TLS fixture".toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/html")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
    }

    private companion object {
        const val PASSWORD = "heartbeat-test-only"
    }
}
