package io.aequicor.heartbeat.platform.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class AppChannelTest {
    @Test
    fun `a release build shows itself as beta only while it runs a beta train`() {
        assertEquals(AppChannel.Release, AppChannel.resolve("release", null))
        assertEquals(AppChannel.Release, AppChannel.resolve(null, null))
        assertEquals(AppChannel.Beta, AppChannel.resolve("release", "beta"))
        assertEquals(AppChannel.Release, AppChannel.resolve("release", "nightly"))
    }

    @Test
    fun `a dev or beta build keeps its channel whatever train it runs`() {
        assertEquals(AppChannel.Dev, AppChannel.resolve("dev", "beta"))
        assertEquals(AppChannel.Dev, AppChannel.resolve("dev", null))
        assertEquals(AppChannel.Beta, AppChannel.resolve("beta", null))
    }
}
