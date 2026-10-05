plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.designSystem.tokens)
            implementation(libs.compose.material3)
        }
        jvmMain.dependencies {
            implementation(libs.fluent)
            implementation(libs.macos.get().toString()) {
                // This host uses AWT; Tao's dispatcher overrides Swing and breaks lifecycle thread checks.
                exclude(group = "dev.nucleusframework", module = "nucleus.decorated-window-tao")
            }
        }
    }
}

dependencies {
    constraints {
        // Fluent's haze 1.6.6 calls ShaderBrush.createShader with its Compose 1.8 signature, absent from Compose 1.12.
        add("jvmMainImplementation", libs.haze)
    }
}
