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
    validatePackagedJvmConfiguration(result)
    return result
}

private fun validatePackagedJvmConfiguration(configuration: PackagedJvmConfiguration) {
    require(
        configuration.mainClass == "io.aequicor.heartbeat.selector.SelectorMain",
    ) { "Unexpected packaged entry point" }
    require(configuration.classpath.isNotEmpty() && configuration.classpath.none { it.isEmpty() }) {
        "Missing packaged classpath"
    }
    val hasAgent = configuration.options.any {
        it.startsWith("-javaagent:") && it.removePrefix("-javaagent:").endsWith("launcher-selector.jar")
    }
    require(hasAgent) { "Missing selector agent" }
    val hasEmbedded = configuration.options.any {
        it.startsWith("-Dheartbeat.selector.embedded=") && it.substringAfter('=').isNotEmpty()
    }
    require(hasEmbedded) { "Missing embedded launcher" }
}
