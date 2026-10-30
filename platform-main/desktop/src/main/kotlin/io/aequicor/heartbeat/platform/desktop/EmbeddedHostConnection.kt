package io.aequicor.heartbeat.platform.desktop

import io.aequicor.heartbeat.core.logging.Log
import java.awt.Frame
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** JDK-only transport across the immutable selector and the selected train's classloader. */
internal class EmbeddedHostConnection private constructor(private val bridge: Class<*>) {
    private val methods = ConcurrentHashMap<String, Method>()
    val handle: Long get() = checkNotNull(invoke("handle")) as Long

    fun createFrame(): Frame? = try {
        checkNotNull(invoke("createFrame")) as Frame
    } catch (error: UnsupportedOperationException) {
        log.w(error) { "JAWT embedding is unavailable; keeping this JVM and using its ordinary desktop window" }
        invoke("abandon")
        null
    }

    fun synchronize(frame: Frame) {
        invoke("synchronize", frame, Frame::class.java)
    }

    fun isRevealed(): Boolean = checkNotNull(invoke("isRevealed")) as Boolean

    fun takeCloseRequest(): Boolean = checkNotNull(invoke("takeCloseRequest")) as Boolean

    fun attachChild(handle: Long) {
        invoke("attachChild", handle, java.lang.Long.TYPE)
    }

    fun captionClient(client: Boolean) {
        invoke("captionClient", client, java.lang.Boolean.TYPE)
    }

    fun present() {
        invoke("present")
    }

    private fun invoke(name: String, argument: Any? = null, type: Class<*>? = null): Any? = try {
        val method = methods.computeIfAbsent(name) {
            if (type == null) bridge.getMethod(name) else bridge.getMethod(name, type)
        }
        if (type == null) method.invoke(null) else method.invoke(null, argument)
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }

    companion object {
        private val log = Log.tag("EmbeddedHost")

        /** Old selectors and ordinary jpackage launches have no native host, so retain the existing desktop path. */
        fun current(loader: ClassLoader = ClassLoader.getSystemClassLoader()): EmbeddedHostConnection? {
            if (loader.getResource("io/aequicor/heartbeat/selector/EmbeddedHost.class") == null) return null
            val type = try {
                loader.loadClass("io.aequicor.heartbeat.selector.EmbeddedHost")
            } catch (error: ClassNotFoundException) {
                log.w(error) {
                    "Installer has no embedded window transport; using the desktop window"
                }
                return null
            }
            val connection = EmbeddedHostConnection(type)
            return connection.takeIf { it.invoke("version") == 1 }
        }
    }
}
