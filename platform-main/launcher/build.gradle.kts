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

tasks.test {
    useJUnitPlatform()
}
