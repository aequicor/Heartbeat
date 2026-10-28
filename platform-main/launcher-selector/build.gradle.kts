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

// The public keys the selector trusts (trusted-keys.txt) are filled in at packaging: the release workflow passes
// -Pheartbeat.trustedKeysFile=<file> with the primary and the backup key. Without it the selector trusts nothing and the
// installed app always runs its embedded launcher, which is also what the testing flavor does: it has no signing key.
val trustedKeysFile = providers.gradleProperty("heartbeat.trustedKeysFile")
tasks.processResources {
    val replacement = trustedKeysFile.map { rootProject.file(it) }
    inputs.files(replacement).withPropertyName("trustedKeys").optional()
    if (replacement.isPresent) {
        val file = replacement.get()
        from(file) {
            into("io/aequicor/heartbeat/selector")
            rename { "trusted-keys.txt" }
        }
        // The committed file is replaced, not the other way round: both are named trusted-keys.txt, so the exclusion
        // goes by the source file, not by the name.
        eachFile {
            if (name == "trusted-keys.txt" && this.file.absoluteFile != file.absoluteFile) exclude()
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
