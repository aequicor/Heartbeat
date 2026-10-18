package io.aequicor.heartbeat.feature.harness.impl.domain.github

import kotlinx.serialization.json.JsonElement

/** Fixed github.com API transport. No caller-selected executable, URL, headers or authentication payload. */
internal interface GitHubTransport {
    val isAvailable: Boolean
    suspend fun call(request: GitHubRequest): GitHubResponse
}

/** Constructed only by the typed adapter; payload travels in a private input file, never in command-line arguments. */
internal data class GitHubRequest(val method: String, val path: String, val body: JsonElement? = null) {
    override fun toString(): String = "GitHubRequest(***)"
}

/** Transport failure has status zero. Bodies and raw errors must never be logged. */
internal data class GitHubResponse(
    val status: Int,
    val body: JsonElement? = null,
    val headers: Map<String, String> = emptyMap(),
) {
    override fun toString(): String = "GitHubResponse(status=$status)"
}

/** HTTP codes used by both adapters and evidence checks. */
internal object GitHubHttp {
    const val OK = 200
    const val UNAUTHORIZED = 401
    const val FORBIDDEN = 403
    const val NOT_FOUND = 404
    const val RATE_LIMITED = 429
    const val SERVER_ERROR = 500
    val SUCCESS = 200..299
    val VALID = 100..599
}
