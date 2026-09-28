package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class AlibabaKoogNormalizationTest {
    @Test
    fun `empty text normalization preserves tool data and response metadata`() {
        val input = """{"id":"reply","created":42,"model":"qwen-test","object":"chat.completion.chunk",
            "usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7},"choices":[
            {"index":0,"finish_reason":"tool_calls","delta":{"content":"","reasoning_content":"reason",
            "tool_calls":[{"index":1,"id":"call","type":"function","function":{"name":"web_fetch",
            "arguments":""}}]}},{"index":1,"delta":{"content":" "}}]}"""
        val expected = input.replace("\"content\":\"\",", "")
        assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(normalizeQwenStreamChunk(input)))
    }

    @Test
    fun `nonempty text null content and usage-only chunks pass through unchanged`() {
        val frames = listOf(
            """{"choices":[{"index":0,"delta":{"content":" \ntext"}}]}""",
            """{"choices":[{"index":0,"delta":{"content":null}}]}""",
            """{"choices":[],"usage":{"total_tokens":7}}""",
        )
        frames.forEach { assertEquals(it, normalizeQwenStreamChunk(it)) }
    }
}
