package io.aequicor.heartbeat.core.network.impl

import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.statement.HttpResponse
import io.ktor.http.BadContentTypeFormatException
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.ContentConvertException

/** Runs after retries but before Ktor's default validation tries to decode an HTTP error body. */
internal val ResponseHeaderValidation = createClientPlugin("ResponseHeaderValidation") {
    on(Send) { request ->
        val call = proceed(request)
        validateResponseContentType(call.response, request.expectSuccess)
        call
    }
}

/** Normalizes only response metadata; request header and serialization mistakes still propagate. */
private fun validateResponseContentType(response: HttpResponse, expectSuccess: Boolean) {
    try {
        response.contentType()
    } catch (e: BadContentTypeFormatException) {
        // Do not retain the cause: its message contains the untrusted header, which may include secrets.
        if (expectSuccess && response.status.value >= HttpStatusCode.MultipleChoices.value) {
            throw ResponseException(response, "Malformed response Content-Type (${e::class.simpleName ?: "unknown"})")
        }
        throw ContentConvertException("Malformed response Content-Type (${e::class.simpleName ?: "unknown"})")
    }
}
