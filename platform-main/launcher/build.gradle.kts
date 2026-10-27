plugins {
    `java-library`
}

// Лаунчер платформы desktop: чистая JVM, без Kotlin-зависимостей (запускается до загрузки stdlib из бандлов).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
}

// -javaagent:<launcher jar>: the installer starts the launcher with this agent (see LauncherAgent).
tasks.jar {
    manifest {
        attributes("Premain-Class" to "io.aequicor.heartbeat.launcher.LauncherAgent")
    }
}

tasks.test {
    useJUnitPlatform()
}
