package io.aequicor.heartbeat.feature.browser.impl.presentation

import androidx.room.RoomDatabase
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.browser.impl.data.DesktopBrowserProfiles
import io.aequicor.heartbeat.feature.browser.impl.data.DesktopPersistentBrowserProfile
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserStorage
import io.aequicor.heartbeat.feature.browser.impl.domain.DesktopBrowserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.cef.CefApp
import org.cef.browser.CefRequestContext
import org.cef.handler.CefRequestContextHandler
import java.awt.BorderLayout
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * Real CEF proof of the storage a tab is created with: the user's tabs share the profile's cookies and local storage,
 * an agent session's tabs share storage of their own, and nothing leaks between the user, two sessions, or a session
 * and its own successor after the last tab of the session was closed.
 */
internal class DesktopBrowserStorageNativeProbe(
    private val app: CefApp,
    private val profiles: DesktopBrowserProfiles,
    private val storageRoot: Path,
    private val createEngine: (
        LinkedBlockingQueue<BrowserViewState>,
        (CefRequestContextHandler) -> CefRequestContext,
    ) -> DesktopBrowserEngine,
    private val createFrame: () -> JFrame,
    private val awaitTitle: (LinkedBlockingQueue<BrowserViewState>, String) -> Unit,
    private val stage: (String) -> Unit,
) {
    private val tabs = mutableListOf<Tab>()
    private lateinit var profile: DesktopBrowserProfile

    /** The production profile hands out the user's persistent context and a separate in-memory one per session. */
    fun verify(base: String) {
        val handle = BrowserTestScope(CoroutineScope(SupervisorJob() + Dispatchers.Swing))
        profile = DesktopPersistentBrowserProfile(
            dispatchers(),
            handle,
            handle,
            NativeProfileStores(storageRoot, PROFILE),
            profiles,
        )
        try {
            verifyStorage(base)
        } finally {
            handle.close()
            runBlocking { profiles.wipeProfile(PROFILE) }
        }
    }

    private fun verifyStorage(base: String) {
        stage("agent session storage")
        try {
            val user = open(BrowserStorage.User)
            visit(user, "$base/storage-set?name=user", "set:user")
            visit(user, "$base/storage-read", "read:user")

            // An agent never meets the user's cookies or local storage.
            val first = open(BrowserStorage.Agent("a"))
            visit(first, "$base/storage-read", "read:none")
            visit(first, "$base/storage-set?name=a", "set:a")
            visit(first, "$base/storage-read", "read:a")

            // The tabs of one session share it; another session and the user's other tabs do not.
            val sibling = open(BrowserStorage.Agent("a"))
            visit(sibling, "$base/storage-read", "read:a")
            val other = open(BrowserStorage.Agent("b"))
            visit(other, "$base/storage-read", "read:none")
            visit(other, "$base/storage-set?name=b", "set:b")
            visit(open(BrowserStorage.User), "$base/storage-read", "read:user")

            // Storage still needed by an open tab is kept; the last tab of a session takes its storage with it.
            runBlocking { profile.releaseAgentContext("b") { true } }
            visit(open(BrowserStorage.Agent("b")), "$base/storage-read", "read:b")
            close(first)
            close(sibling)
            runBlocking { profile.releaseAgentContext("a") { false } }
            visit(open(BrowserStorage.Agent("a")), "$base/storage-read", "read:none")
        } finally {
            stage("agent session storage cleanup")
            tabs.toList().forEach(::close)
        }
    }

    private fun open(storage: BrowserStorage): Tab {
        val shared = runBlocking {
            when (storage) {
                BrowserStorage.User -> profile.useContext { it }
                is BrowserStorage.Agent -> profile.useAgentContext(storage.owner) { it }
            }
        }
        val states = LinkedBlockingQueue<BrowserViewState>()
        var tab: Tab? = null
        SwingUtilities.invokeAndWait {
            val engine = createEngine(states) { handler ->
                checkNotNull(CefRequestContext.createContext(shared, handler))
            }
            val frame = createFrame().apply {
                add(engine.component, BorderLayout.CENTER)
                validate()
            }
            tab = Tab(engine, frame, states)
        }
        return checkNotNull(tab).also { tabs += it }
    }

    private fun visit(tab: Tab, url: String, title: String) {
        SwingUtilities.invokeAndWait { tab.engine.start(url) }
        awaitTitle(tab.states, title)
    }

    private fun close(tab: Tab) {
        if (!tabs.remove(tab)) return
        SwingUtilities.invokeAndWait {
            tab.engine.release()
            tab.frame.dispose()
        }
        runBlocking { tab.engine.awaitClosed() }
    }

    private class Tab(
        val engine: DesktopBrowserEngine,
        val frame: JFrame,
        val states: LinkedBlockingQueue<BrowserViewState>,
    )

    private fun dispatchers() = object : DispatcherProvider {
        override val main = Dispatchers.Swing
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
    }

    private companion object {
        val PROFILE = ProfileId("storage-probe")
    }
}

/** `/storage-set` leaves a named cookie and local storage entry; `/storage-read` titles the page with the names. */
internal fun registerStorageEndpoints(server: HttpServer) {
    fun page(exchange: HttpExchange, html: String) {
        val bytes = html.toByteArray()
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    server.createContext("/storage-set") { exchange ->
        val name = exchange.requestURI.query.orEmpty().substringAfter("name=")
        exchange.responseHeaders.add("Set-Cookie", "sx_$name=1; Path=/; SameSite=Lax")
        page(exchange, "<title>set:$name</title><script>localStorage.setItem('sx_$name','1')</script>")
    }
    server.createContext("/storage-read") { exchange ->
        page(
            exchange,
            """<title>reading</title><script>
                const names = [...document.cookie.split('; ').map(c => c.split('=')[0]), ...Object.keys(localStorage)]
                    .filter(k => k.startsWith('sx_')).map(k => k.substring(3));
                document.title = 'read:' + ([...new Set(names)].sort().join('+') || 'none');
            </script>""",
        )
    }
}

/** The profile's files are the only storage the persistent browser profile asks of its owner. */
internal class NativeProfileStores(directory: Path, id: ProfileId) : DataStores {
    private val root = directory
    override val owner = StorageOwner.Profile(id)
    override fun filesDirectory(name: String): String = root.resolve(name).toString()
    override fun keyValue(spec: KeyValueSpec): KeyValueStore = error("Native fixture does not use key-value storage")
    override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = error("Native fixture does not use Room")
    override suspend fun fire(event: DataEvent): Unit = error("Native fixture does not use retention events")
}
