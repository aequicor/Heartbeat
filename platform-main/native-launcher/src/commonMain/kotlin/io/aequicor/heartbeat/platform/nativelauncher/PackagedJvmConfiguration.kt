package io.aequicor.heartbeat.platform.nativelauncher

/** JVM launch parameters from the jpackage configuration in a verified application image. */
internal data class PackagedJvmConfiguration(
    val mainClass: String,
    val classpath: List<String>,
    val options: List<String>,
)

/**
 * Reads the repeated classpath and JavaOptions entries without splitting options on spaces. Paths are expanded against
 * the image's app directory, as in jpackage. Reject an incomplete or unexpected entry point instead of bypassing the
 * selector and its signature and rollback checks.
 */
internal fun parsePackagedJvmConfiguration(text: String, appDirectory: String): PackagedJvmConfiguration {
    require(appDirectory.isNotEmpty() && '\n' !in appDirectory && '\r' !in appDirectory)
    var section = ""
    var mainClass: String? = null
    val classpath = mutableListOf<String>()
    val options = mutableListOf<String>()
    text.lineSequence().forEach { raw ->
        val line = raw.trim()
        when {
            line.startsWith('[') && line.endsWith(']') -> section = line.substring(1, line.length - 1)

            section == "Application" && line.startsWith("app.mainclass=") -> {
                require(mainClass == null) { "Multiple packaged main classes" }
                mainClass = line.substringAfter('=')
            }

            section == "Application" && line.startsWith("app.classpath=") ->
                classpath += line.substringAfter('=').replace("\$APPDIR", appDirectory)

            section == "JavaOptions" && line.startsWith("java-options=") ->
                options += line.substringAfter('=').replace("\$APPDIR", appDirectory)
        }
    }
    val result = PackagedJvmConfiguration(mainClass.orEmpty(), classpath, options)
    validatePackagedJvmConfiguration(result, appDirectory)
    return result
}

private fun validatePackagedJvmConfiguration(configuration: PackagedJvmConfiguration, appDirectory: String) {
    require(configuration.mainClass == "io.aequicor.heartbeat.selector.SelectorMain") {
        "Unexpected packaged entry point"
    }
    val selector = "$appDirectory/launcher-selector.jar".replace('\\', '/')
    require(configuration.classpath.isNotEmpty() && configuration.classpath.none { it.isEmpty() }) {
        "Missing packaged classpath"
    }
    require(configuration.classpath.first().replace('\\', '/') == selector) {
        "Selector must be first on the packaged classpath"
    }
    val agents = configuration.options.filter { it.startsWith("-javaagent:") }
    require(agents.map { it.replace('\\', '/') } == listOf("-javaagent:$selector")) {
        "Unexpected selector agent"
    }
    requireUniqueOption(
        configuration.options,
        "-Dheartbeat.selector.embedded=",
        "$appDirectory/resources/launcher-embedded.seed",
        normalizePath = true,
    )
    requireUniqueOption(
        configuration.options,
        "-Dheartbeat.app.main=",
        "io.aequicor.heartbeat.platform.desktop.MainKt",
    )
}

private fun requireUniqueOption(
    options: List<String>,
    prefix: String,
    expected: String,
    normalizePath: Boolean = false,
) {
    val values = options.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
    val normalized = if (normalizePath) values.map { it.replace('\\', '/') } else values
    val expectedValue = if (normalizePath) expected.replace('\\', '/') else expected
    require(normalized == listOf(expectedValue)) { "Missing or ambiguous packaged option: $prefix" }
}

/**
 * Constructs JNI options from the packaged configuration. jpackage normally provides its executable path itself;
 * workers depend on that path, while the external launcher is supplied separately through the environment.
 */
internal fun PackagedJvmConfiguration.windowsJvmOptions(innerExecutable: String): List<String> {
    require(innerExecutable.isNotEmpty() && innerExecutable.none { it == '\u0000' || it == '\r' || it == '\n' }) {
        "Invalid packaged executable path"
    }
    require(classpath.none { path -> path.any { it == ';' || it == '\u0000' || it == '\r' || it == '\n' } }) {
        "Classpath entry cannot be represented in Windows JVM options"
    }
    val hasConflicts = options.any {
        it == "-Djava.class.path" || it.startsWith("-Djava.class.path=") ||
            it == "-Djpackage.app-path" || it.startsWith("-Djpackage.app-path=")
    }
    require(!hasConflicts) { "Packaged options override launcher-owned JVM properties" }
    return listOf(
        "-Djava.class.path=${classpath.joinToString(";")}",
        "-Djpackage.app-path=$innerExecutable",
    ) + options
}
