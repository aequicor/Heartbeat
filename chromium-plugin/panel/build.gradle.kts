import io.aequicor.heartbeat.buildlogic.PrepareVerifiedFiles

// Side panel of the Chromium extension (`chromium-plugin/`): the studio chat on Compose Multiplatform for Kotlin/Wasm.
// `jvm` only compiles and tests the shared code. The extension loads the webpack distribution that
// `syncChromiumPanel` copies into `chromium-plugin/app/` (ignored by git, rebuilt by Gradle).
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.heartbeat.detekt)
    alias(libs.plugins.heartbeat.kmp.wasm)
}

kotlin {
    // Same toolchain as heartbeat.kmp.library.
    jvmToolchain(17)
    jvm()
    wasmJs {
        outputModuleName.set("heartbeat-panel")
        browser {
            commonWebpackConfig { outputFileName = "panel.js" }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.chromiumBridge.protocol)
            implementation(projects.designSystem.components)
            implementation(projects.designSystem.theme)
            implementation(projects.designSystem.layouts)
            implementation(projects.core.logging)
            implementation(projects.core.mvi)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.collectionsImmutable)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.flowmvi.test)
        }
    }
}

// The canvas has no system fonts: Compose's default family covers Latin and Cyrillic only. Emoji, symbols, arrows
// and box drawing come from Noto fallback fonts, and code from Noto Sans Mono (SIL OFL 1.1, shipped as OFL.txt).
val notoEmoji = "https://raw.githubusercontent.com/googlefonts/noto-emoji/${libs.versions.noto.emoji.get()}/fonts"
val notoFonts = "https://raw.githubusercontent.com/notofonts/notofonts.github.io/${libs.versions.notofonts.get()}/fonts"
val panelFonts = mapOf(
    "Noto-COLRv1.ttf" to ("$notoEmoji/Noto-COLRv1.ttf" to
        "0ae57fe58645638523ba35f388d93739d292539a9acb84df5700c81b1e1a28d2"),
    "OFL.txt" to ("$notoEmoji/LICENSE" to "6a73f9541c2de74158c0e7cf6b0a58ef774f5a780bf191f2d7ec9cc53efe2bf2"),
    "NotoSansSymbols2-Regular.ttf" to ("$notoFonts/NotoSansSymbols2/unhinted/ttf/NotoSansSymbols2-Regular.ttf" to
        "c4a0a80f0041ce4be81e2478faad22776d23edb98ae3f0d19bd37044820ecf9d"),
    "NotoSansMath-Regular.ttf" to ("$notoFonts/NotoSansMath/unhinted/ttf/NotoSansMath-Regular.ttf" to
        "b127e84699212b6b2ef50aff58e0ebebeec04ffe6db1b9eb9e209c8c3d97b4aa"),
    "NotoSansMono-Regular.ttf" to ("$notoFonts/NotoSansMono/unhinted/ttf/NotoSansMono-Regular.ttf" to
        "87f8ce0522a6c99b743ee5fc75b4073cfdd575639119672828b7b9944b65b4f4"),
)

val preparePanelFonts = tasks.register<PrepareVerifiedFiles>("preparePanelFonts") {
    urls.set(panelFonts.mapValues { (_, source) -> source.first })
    sha256.set(panelFonts.mapValues { (_, source) -> source.second })
    subdirectory.set("files/fonts")
    offline.set(gradle.startParameter.isOffline)
    cacheDirectory.set(gradle.gradleUserHomeDir.resolve("caches/heartbeat/fonts"))
    outputDirectory.set(layout.buildDirectory.dir("generated/panelFonts"))
}

compose.resources {
    packageOfResClass = "io.aequicor.heartbeat.chromiumplugin.panel.resources"
    customDirectory(sourceSetName = "wasmJsMain", directoryProvider = preparePanelFonts.flatMap { it.outputDirectory })
}

// The unpacked extension loads the panel from `chromium-plugin/app/`; the desktop distribution copies it with the
// rest of the extension folder (`collectAppResources`).
tasks.register<Sync>("syncChromiumPanel") {
    group = "build"
    description = "Builds the side panel and copies it into the extension folder (chromium-plugin/app)."
    from(tasks.named("wasmJsBrowserDistribution"))
    into(layout.settingsDirectory.dir("chromium-plugin/app"))
}
