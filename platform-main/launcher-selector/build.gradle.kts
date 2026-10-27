plugins {
    `java-library`
}

// Селектор слотов лаунчера: единственный код, который неизменно живёт в установщике. Чистая JVM без зависимостей
// (запускается раньше любого лаунчера и stdlib из бандлов), поэтому его нужно держать минимальным.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(libs.junit.jupiter)
    // Паритет путей с лаунчером проверяет тест; в продовый код зависимость не входит.
    testImplementation(projects.platformMain.launcher)
    testRuntimeOnly(libs.junit.platformLauncher)
}

// -javaagent:<selector jar>: установщик запускает приложение с этим агентом (см. SelectorAgent).
tasks.jar {
    manifest {
        attributes("Premain-Class" to "io.aequicor.heartbeat.selector.SelectorAgent")
    }
}

tasks.test {
    useJUnitPlatform()
}
