package io.aequicor.heartbeat.platform.nativelauncher

import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ImageStore
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionedImage
import okio.FileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LauncherConfigTest {
    @Test
    fun offlineImageMustMatchInstalledPlatformVersionAndArchitecture() {
        val fs = FileSystem.SYSTEM
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.resolve("hb-config-${Random.nextLong()}")
        val store = ImageStore(root)
        val config = LauncherConfig(
            "",
            "",
            emptyList(),
            root.resolve("data"),
            "1.0.0",
            "windows-arm64",
            channel = "release",
        )
        try {
            val staged = store.stagingDir("test")
            fs.createDirectories(staged)
            store.activate(
                ProvisionedImage("test", store.imageDir("test"), "jbr", "train", "1.0.0", "windows-arm64", "release"),
                staged,
            )
            val image = assertNotNull(config.currentImage(store))
            assertNull(config.copy(version = "2.0.0").currentImage(store))
            assertNull(config.copy(target = "windows-x64").currentImage(store))
            assertNull(config.copy(channel = "beta").currentImage(store))
            store.writeMarker(image.directory, image.copy(minNativeLauncherVersion = 2))
            assertNull(config.currentImage(store))
        } finally {
            fs.deleteRecursively(root)
        }
    }
}
