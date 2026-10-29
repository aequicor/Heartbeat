package io.aequicor.heartbeat.platform.nativelauncher.provisioning

import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Раскладка image: атомарная активация, offline-чтение, previous/prune, provision lock. */
class ImageStoreTest {
    private val fs = FileSystem.SYSTEM
    private val temp = Files.createTempDirectory("image-test").toString().toPath()
    private val store = ImageStore(temp.resolve("bootstrap"), fs)

    private fun stage(id: String): ProvisionedImage {
        val dir = store.stagingDir(id)
        fs.createDirectories(dir)
        fs.write(dir.resolve("app.txt")) { writeUtf8("image-$id") }
        return ProvisionedImage(id, store.imageDir(id), "jbr21-windows-x64", "train-1")
    }

    @Test
    fun activatedImageIsReadableOffline() {
        assertNull(store.currentImage())
        val image = stage("image-1")
        val staging = store.stagingDir(image.id)
        assertNull(store.currentImage(), "staging is never current")
        store.activate(image, staging)
        val current = store.currentImage()
        assertNotNull(current)
        assertEquals("image-1", current.id)
        assertEquals("jbr21-windows-x64", current.runtimeId)
        assertEquals("train-1", current.trainId)
        assertTrue(fs.exists(current.directory.resolve("app.txt")))
        assertTrue(fs.exists(store.markerFile(current.directory)))
        assertFalse(fs.exists(staging), "staging is moved, not copied")
    }

    @Test
    fun previousWorkingImageSurvivesActivationOfTheNextOne() {
        val first = stage("image-1")
        store.activate(first, store.stagingDir(first.id))
        val second = stage("image-2")
        store.activate(second, store.stagingDir(second.id))
        assertEquals("image-2", store.currentImage()?.id)
        assertEquals("image-1", store.previousImageId())
        assertTrue(fs.exists(store.imageDir("image-1")), "previous image is not deleted on activation")
        store.prune()
        assertTrue(fs.exists(store.imageDir("image-1")), "previous is kept by prune")
        val third = stage("image-3")
        store.activate(third, store.stagingDir(third.id))
        store.prune()
        assertFalse(fs.exists(store.imageDir("image-1")), "image outside current/previous is pruned")
        assertTrue(fs.exists(store.imageDir("image-2")))
        assertTrue(fs.exists(store.imageDir("image-3")))
    }

    @Test
    fun mismatchedMarkerIsNotAnOfflineImage() {
        val image = stage("image-1")
        store.activate(image, store.stagingDir(image.id))
        fs.write(store.currentFile) { writeUtf8("other") }
        assertNull(store.currentImage())
    }

    @Test
    fun incompleteImageDirectoryBlocksActivationAndKeepsCurrent() {
        val first = stage("image-1")
        store.activate(first, store.stagingDir(first.id))
        val second = stage("image-2")
        fs.createDirectories(store.imageDir("image-2"))
        assertFailsWith<IllegalStateException> {
            store.activate(second, store.stagingDir(second.id))
        }
        assertEquals("image-1", store.currentImage()?.id)
    }

    @Test
    fun lockIsExclusiveAndReleases() {
        val lockFile = store.lockFile
        val first = ProvisioningPlatform.tryLock(lockFile)
        assertNotNull(first)
        assertNull(ProvisioningPlatform.tryLock(lockFile))
        first.release()
        val again = ProvisioningPlatform.tryLock(lockFile)
        assertNotNull(again)
        again.release()
    }
}
