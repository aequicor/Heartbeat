import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

plugins {
    `java-library`
}

// Селектор слотов лаунчера: единственный код, который неизменно живёт в установщике. Чистая JVM без зависимостей
// (запускается раньше любого лаунчера и stdlib из бандлов), поэтому его нужно держать минимальным.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// --release 17 checks the JDK API too: without it a call to an API of JDK 21 compiles on the build JDK and fails with
// NoSuchMethodError in the installed app, where the selector cannot be repaired without a reinstall.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
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

// The trusted public keys (trusted-keys.txt) are committed in the module: the primary key of the CI signing secret and the
// offline backup key. -Pheartbeat.trustedKeysFile=<file> replaces them at packaging; it is for the local end-to-end check
// with throwaway keys (scripts/e2e_launcher_slots.py), not for releases. A file that is missing, holds no key, more than
// four, or a key that is not an Ed25519 X.509 key fails the build: a selector without usable keys trusts no slot and
// cannot be repaired after the install.
val trustedKeysFile = providers.gradleProperty("heartbeat.trustedKeysFile")
tasks.processResources {
    val replacement = trustedKeysFile.map { layout.settingsDirectory.file(it).asFile.toPath().toAbsolutePath().normalize() }
    inputs.files(replacement.map { it.toFile() }).withPropertyName("trustedKeys").optional()
    val committed = layout.projectDirectory.file("src/main/resources/io/aequicor/heartbeat/selector/trusted-keys.txt")
        .asFile.toPath().toAbsolutePath().normalize()
    if (replacement.isPresent && replacement.get() != committed) {
        val keysFile = replacement.get().toFile()
        from(keysFile) {
            into("io/aequicor/heartbeat/selector")
            rename { "trusted-keys.txt" }
        }
        // Both files are named trusted-keys.txt, so the committed one is dropped by its source file, not by its name.
        eachFile {
            if (name == "trusted-keys.txt" && this.file.absoluteFile != keysFile.absoluteFile) exclude()
        }
        doFirst {
            check(keysFile.isFile) { "heartbeat.trustedKeysFile not found: $keysFile" }
            val keys = keysFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            check(keys.size in 1..4) { "heartbeat.trustedKeysFile must hold 1 to 4 keys, found ${keys.size}: $keysFile" }
            val factory = KeyFactory.getInstance("Ed25519")
            keys.forEach { key -> factory.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(key))) }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
