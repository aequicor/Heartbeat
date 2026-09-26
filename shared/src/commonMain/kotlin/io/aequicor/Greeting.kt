package io.aequicor

internal class Greeting {
    private val platform = getPlatform()

    fun greet(): String = sayHello(platform.name)
}
