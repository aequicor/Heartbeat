package io.aequicor

internal interface Platform {
    val name: String
}

internal expect fun getPlatform(): Platform
