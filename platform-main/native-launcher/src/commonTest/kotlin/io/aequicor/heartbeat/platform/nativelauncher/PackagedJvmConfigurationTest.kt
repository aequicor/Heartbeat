package io.aequicor.heartbeat.platform.nativelauncher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PackagedJvmConfigurationTest {
    private val config = """
        [Application]
        app.classpath=${'$'}APPDIR/launcher-selector.jar
        app.mainclass=io.aequicor.heartbeat.selector.SelectorMain
        app.classpath=${'$'}APPDIR/example.jar
        [JavaOptions]
        java-options=-javaagent:${'$'}APPDIR/launcher-selector.jar
        java-options=-Dheartbeat.selector.embedded=${'$'}APPDIR/resources/launcher-embedded.seed
        java-options=-Dheartbeat.app.main=io.aequicor.heartbeat.platform.desktop.MainKt
        java-options=-Dexample=value with spaces
    """.trimIndent()

    @Test
    fun preservesOrderedOptionsAndExpandsUnicodePaths() {
        val result = parsePackagedJvmConfiguration(config, "C:/Пример приложения/app")
        assertEquals("io.aequicor.heartbeat.selector.SelectorMain", result.mainClass)
        assertEquals(
            listOf("C:/Пример приложения/app/launcher-selector.jar", "C:/Пример приложения/app/example.jar"),
            result.classpath,
        )
        assertEquals("-javaagent:C:/Пример приложения/app/launcher-selector.jar", result.options[0])
        assertEquals("-Dexample=value with spaces", result.options.last())
    }

    @Test
    fun refusesMissingSelectorOrAgent() {
        assertFailsWith<IllegalArgumentException> {
            parsePackagedJvmConfiguration(config.replace("selector.SelectorMain", "desktop.MainKt"), "C:/app")
        }
        assertFailsWith<IllegalArgumentException> {
            parsePackagedJvmConfiguration(config.replace("-javaagent:", "-Dagent="), "C:/app")
        }
        assertFailsWith<IllegalArgumentException> {
            parsePackagedJvmConfiguration(config.replace("-Dheartbeat.selector.embedded=", "-Dother="), "C:/app")
        }
    }

    @Test
    fun refusesAmbiguousMainClass() {
        assertFailsWith<IllegalArgumentException> {
            parsePackagedJvmConfiguration(
                config.replace("[JavaOptions]", "app.mainclass=other.Main\n[JavaOptions]"),
                "C:/app",
            )
        }
    }

    @Test
    fun rejectsSubstitutionAndConflictingOptions() {
        val selectorPath = "app.classpath=${'$'}APPDIR/launcher-selector.jar"
        assertFailsWith<IllegalArgumentException> {
            parsePackagedJvmConfiguration(config.replace(selectorPath, "app.classpath=other.jar"), "C:/app")
        }
        assertFailsWith<IllegalArgumentException> {
            parsePackagedJvmConfiguration(config.replace("-javaagent:${'$'}APPDIR", "-javaagent:C:/other"), "C:/app")
        }
        assertFailsWith<IllegalArgumentException> {
            parsePackagedJvmConfiguration(config + "\njava-options=-Dheartbeat.app.main=other.Main", "C:/app")
        }
        assertFailsWith<IllegalArgumentException> {
            parsePackagedJvmConfiguration(config + "\njava-options=-Dheartbeat.selector.embedded=other", "C:/app")
        }
    }

    @Test
    fun acceptsWindowsPathSeparators() {
        val windowsConfig = config.replace("${'$'}APPDIR/", "${'$'}APPDIR\\")
        parsePackagedJvmConfiguration(windowsConfig, "C:\\Пример приложения\\app")
    }
}
