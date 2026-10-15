package io.aequicor.heartbeat.platform.desktop

import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class PackagedPlantUmlWorkerTest {
    @Test
    fun `normal application startup does not resolve the optional engine`() {
        assertFalse(runPackagedPlantUmlWorker(emptyArray()))
        assertFalse(runPackagedPlantUmlWorker(arrayOf("--other-mode")))
    }

    @Test
    fun `the private mode accepts exactly one prepared jar path`() {
        assertFailsWith<IllegalArgumentException> {
            runPackagedPlantUmlWorker(arrayOf("--heartbeat-plantuml-worker"))
        }
        assertFailsWith<IllegalArgumentException> {
            runPackagedPlantUmlWorker(arrayOf("--heartbeat-plantuml-worker", "engine.jar", "diagram source"))
        }
    }

    @Test
    fun `the private mode refuses arbitrary code before creating a worker loader`() {
        val jar = Files.createTempFile("plantuml-worker-untrusted-", ".jar")
        try {
            Files.writeString(jar, "untrusted")
            val failure = assertFailsWith<InvocationTargetException> {
                runPackagedPlantUmlWorker(arrayOf("--heartbeat-plantuml-worker", jar.toString()))
            }
            val cause = assertIs<IllegalArgumentException>(failure.cause)
            assertEquals("Invalid PlantUML runtime file", cause.message)
        } finally {
            Files.delete(jar)
        }
    }
}
