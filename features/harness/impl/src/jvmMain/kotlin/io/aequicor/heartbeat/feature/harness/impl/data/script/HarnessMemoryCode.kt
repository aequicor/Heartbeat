package io.aequicor.heartbeat.feature.harness.impl.data.script

import io.aequicor.heartbeat.feature.harness.impl.domain.script.CompiledHarnessCode
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeKind
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.script.experimental.jvm.impl.KJvmCompiledScript
import kotlin.script.experimental.jvm.impl.createScriptFromClassLoader

/** Immutable class/resource bytes prevent a later cache replacement from changing an approved live artifact. */
internal class HarnessMemoryLoader(private val resources: Map<String, ByteArray>, parent: ClassLoader) :
    ClassLoader(parent) {
    override fun findClass(name: String): Class<*> {
        val bytes = resources[name.replace('.', '/') + ".class"] ?: throw ClassNotFoundException(name)
        return defineClass(name, bytes, 0, bytes.size)
    }

    override fun getResourceAsStream(name: String): InputStream? =
        resources[name]?.let(::ByteArrayInputStream) ?: super.getResourceAsStream(name)
}

/** One opaque lease. Evaluation borrows another lease; runtime owners release only after callback drain. */
internal class HarnessMemoryCode private constructor(private val shared: SharedArtifact) : CompiledHarnessCode {
    private val isClosed = AtomicBoolean(false)
    override val kind: HarnessCodeKind get() = shared.kind

    override fun retain(): HarnessMemoryCode = synchronized(shared) {
        check(!isClosed.get()) { "Compiled harness code is closed" }
        shared.references++
        HarnessMemoryCode(shared)
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            synchronized(shared) {
                shared.release()
            }
        }
    }

    fun content(): Pair<KJvmCompiledScript, ClassLoader> = synchronized(shared) {
        check(!isClosed.get()) { "Compiled harness code is closed" }
        checkNotNull(shared.script) to checkNotNull(shared.loader)
    }

    override fun toString(): String = "HarnessMemoryCode(kind=$kind, ***)"

    companion object {
        fun create(kind: HarnessCodeKind, className: String, resources: Map<String, ByteArray>): HarnessMemoryCode {
            val loader = HarnessMemoryLoader(resources, HarnessMemoryCode::class.java.classLoader)
            val script = createScriptFromClassLoader(className, loader)
            return HarnessMemoryCode(SharedArtifact(kind, script, loader))
        }
    }
}

private class SharedArtifact(
    val kind: HarnessCodeKind,
    var script: KJvmCompiledScript?,
    var loader: HarnessMemoryLoader?,
    var references: Int = 1,
) {
    fun release() {
        references--
        if (references == 0) {
            script = null
            loader = null
        }
    }

    override fun toString(): String = "SharedArtifact(kind=$kind, references=$references, ***)"
}
