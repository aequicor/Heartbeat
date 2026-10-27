package io.aequicor.heartbeat.buildlogic.sdkfixture

// Compiled with the tests: the SDK keep rules are generated from these real class files.

class Api(val visible: Int, internal val hidden: Int) {
    var mutable: String = ""
    private val secret = 1
    internal var internalMutable: String = ""

    fun shown(value: Int = 1): Int = value + secret

    internal fun hiddenFun(): Int = hidden

    protected fun inherited() = Unit

    @PublishedApi
    internal fun published(): Int = hidden

    @PublishedApi
    internal val publishedProperty: Int = 2

    inline fun inlined(): Int = published() + publishedProperty

    companion object {
        const val CONSTANT = 3

        @JvmStatic
        fun staticFun(): Int = CONSTANT
    }
}

internal class HiddenClass {
    fun method() = Unit
}

@PublishedApi
internal class PublishedClass {
    fun method() = Unit
}

object Singleton {
    fun call() = Unit
}

enum class Mode { On, Off }

interface Contract {
    fun act(): Int
}

fun topLevel(value: String): String = value

internal fun hiddenTopLevel() = Unit

val topLevelProperty: Int = 4
